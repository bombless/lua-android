-- bootstrap.lua - sandboxed job executor (luart "exec" mode).
--
-- Runs as a trusted chunk inside the runner. It builds a restricted
-- environment, runs <job>/script.lua in it, then writes:
--   <job>/result.json   JSON of the script's return value (only when it returned something)
--   <job>/status.json   machine readable outcome (read by the Kotlin host)
-- stdout/stderr of the process are redirected to <job>/stdout.txt|stderr.txt by the host.
--
-- Security model (see Manual.md section 4.2 and README "Trust model"):
--  * a fresh Lua state per job, in its own OS process;
--  * scripts get copies of the standard libraries and a whitelisted globals table;
--  * file access is confined to input/ (read only), output/ and tmp/ of the job directory,
--    absolute paths and ".." are rejected; no symlink can be created from Lua;
--  * no os.execute / io.popen / debug / package.loadlib / binary chunks;
--  * stdout/stderr and the serialised result are size capped.
-- This is NOT a boundary against hostile native modules: the process shares the app UID.

local luart, opts = ...

local JOB_DIR, RUNTIME_DIR, ROCKS_DIR = opts.job, opts.runtime, opts.rocks
local OUT_LIMIT, RESULT_LIMIT = opts.out_bytes, opts.result_bytes

-- ---------------------------------------------------------------------------
-- Capture every primitive we use after the script ran, so a script that
-- tampers with shared tables (e.g. through the string metatable) cannot
-- influence how results are produced.
-- ---------------------------------------------------------------------------
local type, tostring, tonumber, pairs, ipairs, next, select = type, tostring, tonumber, pairs, ipairs, next, select
local pcall, xpcall, error, assert, setmetatable, getmetatable = pcall, xpcall, error, assert, setmetatable, getmetatable
local rawget, rawset, rawlen, rawequal, load = rawget, rawset, rawlen, rawequal, load
local sformat, sgsub, sfind, ssub, sbyte, srep, slower = string.format, string.gsub, string.find, string.sub, string.byte, string.rep, string.lower
local smatch, sgmatch = string.match, string.gmatch
local tconcat, tsort, tunpack, tpack, tinsert = table.concat, table.sort, table.unpack, table.pack, table.insert
local mtype, mhuge = math.type, math.huge
local utf8len = utf8.len
local io_open, io_lines, io_type, io_stdout, io_stderr = io.open, io.lines, io.type, io.stdout, io.stderr
local os_time, os_clock, os_date, os_difftime = os.time, os.clock, os.date, os.difftime
local os_remove, os_rename = os.remove, os.rename
local loadlib, traceback = package.loadlib, debug.traceback
local gcollect = collectgarbage
local now_ms = luart.now_ms
local FILE_MT = getmetatable(io_stdout)
local FILE_METHODS = FILE_MT.__index
local f_write, f_read, f_close, f_flush, f_setvbuf = FILE_METHODS.write, FILE_METHODS.read, FILE_METHODS.close, FILE_METHODS.flush, FILE_METHODS.setvbuf
local STRING_MT = getmetatable("")

local G_string, G_table, G_math, G_utf8, G_coroutine = string, table, math, utf8, coroutine
local real_string_dump = string.dump

local function read_all(path)
  local f = io_open(path, "rb")
  if not f then return nil end
  local s = f_read(f, "a")
  f_close(f)
  return s
end

local function write_all(path, data)
  local f = io_open(path, "wb")
  if f then
    f_write(f, data)
    f_close(f)
  end
end

-- ---------------------------------------------------------------------------
-- JSON (results and status)
-- ---------------------------------------------------------------------------
local ESC = { ['"'] = '\\"', ["\\"] = "\\\\", ["\b"] = "\\b", ["\f"] = "\\f", ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t" }
local function esc_char(c) return ESC[c] or sformat("\\u%04x", sbyte(c)) end

local function json_fail(code, msg) error({ code = code, msg = msg }, 0) end

local function enc_string(s)
  if not utf8len(s) then json_fail("RESULT_NOT_SERIALIZABLE", "string value is not valid UTF-8 (write binary data to an output file instead)") end
  return '"' .. sgsub(s, '[%c"\\]', esc_char) .. '"'
end

local function enc_number(n)
  if mtype(n) == "integer" then return sformat("%d", n) end
  if n ~= n or n == mhuge or n == -mhuge then json_fail("RESULT_NOT_SERIALIZABLE", "NaN and infinity cannot be represented in JSON") end
  local s
  for p = 15, 17 do
    s = sformat("%." .. p .. "g", n)
    if tonumber(s) == n then break end
  end
  if not sfind(s, "[%.eE]") then s = s .. ".0" end
  return s
end

local MAX_DEPTH, MAX_NODES = 64, 200000

local function json_encode(root, limit, list_n)
  local active, nodes, size = {}, 0, 0
  local buf, nbuf = {}, 0
  local function put(s)
    size = size + #s
    if size > limit then json_fail("RESULT_TOO_LARGE", "serialised result exceeds " .. limit .. " bytes (write large data to an output file)") end
    nbuf = nbuf + 1
    buf[nbuf] = s
  end
  local function enc(v, depth)
    local t = type(v)
    nodes = nodes + 1
    if nodes > MAX_NODES then json_fail("RESULT_TOO_LARGE", "result has too many values") end
    if t == "nil" then put("null")
    elseif t == "boolean" then put(v and "true" or "false")
    elseif t == "number" then put(enc_number(v))
    elseif t == "string" then put(enc_string(v))
    elseif t == "table" then
      if depth > MAX_DEPTH then json_fail("RESULT_NOT_SERIALIZABLE", "table nesting deeper than " .. MAX_DEPTH) end
      if active[v] then json_fail("RESULT_NOT_SERIALIZABLE", "cyclic table cannot be serialised") end
      active[v] = true
      local count = 0
      for _ in next, v do count = count + 1 end
      local n = rawlen(v)
      local is_array = count == 0
      if v == root and list_n then
        is_array, n = true, list_n -- multiple return values: explicit count, holes become null
      elseif not is_array and n == count then
        is_array = true
        for i = 1, n do
          if rawget(v, i) == nil then is_array = false break end
        end
      end
      if is_array then
        put("[")
        for i = 1, n do
          if i > 1 then put(",") end
          enc(rawget(v, i), depth + 1)
        end
        put("]")
      else
        local keys = {}
        for k in next, v do
          if type(k) ~= "string" then
            json_fail("RESULT_NOT_SERIALIZABLE", "table has a non-string key (" .. type(k) .. "); only pure arrays and string-keyed tables are supported")
          end
          keys[#keys + 1] = k
        end
        tsort(keys)
        put("{")
        for i = 1, #keys do
          if i > 1 then put(",") end
          put(enc_string(keys[i]))
          put(":")
          enc(rawget(v, keys[i]), depth + 1)
        end
        put("}")
      end
      active[v] = nil
    else
      json_fail("RESULT_NOT_SERIALIZABLE", t .. " values cannot be serialised")
    end
  end
  enc(root, 1)
  return tconcat(buf, "", 1, nbuf)
end

-- ---------------------------------------------------------------------------
-- Spec written by the host
-- ---------------------------------------------------------------------------
local spec_src = read_all(JOB_DIR .. "/spec.lua")
assert(spec_src, "job spec is missing")
local spec = assert(load(spec_src, "=spec", "t", {}))()
local SCRIPT_NAME = spec.scriptName or "script.lua"
local ARGS = spec.args or {}
local ENV = spec.env or {}
local WORKDIR = spec.workdir
if WORKDIR == nil or WORKDIR == "" then WORKDIR = "output" end

-- ---------------------------------------------------------------------------
-- Output streams (capped) and status bookkeeping
-- ---------------------------------------------------------------------------
local truncated = false
local counters = { stdout = 0, stderr = 0 }

f_setvbuf(io_stdout, "line")
f_setvbuf(io_stderr, "no")

local function make_stream(raw, name)
  local S = {}
  local mt = { __index = S, __name = "FILE*", __metatable = "FILE*" }
  mt.__tostring = function() return "file (" .. name .. ")" end
  local self = setmetatable({}, mt)
  local function emit(s)
    local used = counters[name]
    if used >= OUT_LIMIT then
      truncated = true
      return
    end
    if used + #s > OUT_LIMIT then
      s = ssub(s, 1, OUT_LIMIT - used)
      truncated = true
    end
    counters[name] = used + #s
    f_write(raw, s)
  end
  function S.write(f, ...)
    for i = 1, select("#", ...) do
      local v = select(i, ...)
      local t = type(v)
      if t == "number" then v = tostring(v)
      elseif t ~= "string" then error("bad argument #" .. i .. " to 'write' (string expected, got " .. t .. ")", 2) end
      emit(v)
    end
    return f
  end
  function S.flush(f) f_flush(raw) return f end
  function S.setvbuf() return true end
  function S.close() return nil, "cannot close standard file" end
  function S.seek() return nil, "Illegal seek", 29 end
  function S.read() return nil, "Bad file descriptor", 9 end
  function S.lines() error("file is not readable", 2) end
  return self, emit
end

local stdout_proxy, emit_out = make_stream(io_stdout, "stdout")
local stderr_proxy, emit_err = make_stream(io_stderr, "stderr")

local stdin_proxy = setmetatable({}, { __name = "FILE*", __metatable = "FILE*", __tostring = function() return "file (stdin)" end, __index = {
  read = function() return nil end,
  lines = function() return function() return nil end end,
  close = function() return nil, "cannot close standard file" end,
  seek = function() return nil, "Illegal seek", 29 end,
  setvbuf = function() return true end,
  write = function() return nil, "Bad file descriptor", 9 end,
  flush = function() return true end,
} })

local function is_proxy(x) return x == stdout_proxy or x == stderr_proxy or x == stdin_proxy end

-- ---------------------------------------------------------------------------
-- Confined file system view
-- ---------------------------------------------------------------------------
local ROOTS = { input = false, output = true, tmp = true } -- value: writable

local function split_path(rel)
  if type(rel) ~= "string" then return nil, "path must be a string" end
  if rel == "" then return nil, "empty path" end
  if sfind(rel, "\0", 1, true) then return nil, "path contains NUL" end
  if sfind(rel, "\\", 1, true) then return nil, "backslash is not allowed in paths" end
  if ssub(rel, 1, 1) == "/" then return nil, "absolute paths are not allowed" end
  local segs = {}
  for seg in sgmatch(rel, "[^/]+") do
    if seg == ".." then return nil, "'..' is not allowed in paths" end
    if seg ~= "." then segs[#segs + 1] = seg end
  end
  if #segs == 0 then return nil, "empty path" end
  return segs
end

-- Returns real_path, writable  |  nil, message
local function resolve(path)
  local segs, err = split_path(path)
  if not segs then return nil, err end
  if ROOTS[segs[1]] == nil then
    -- relative to the working directory
    local base = split_path(WORKDIR)
    local merged = {}
    for _, s in ipairs(base) do merged[#merged + 1] = s end
    for _, s in ipairs(segs) do merged[#merged + 1] = s end
    segs = merged
  end
  local writable = ROOTS[segs[1]]
  if writable == nil then return nil, "access outside input/, output/ and tmp/ is not permitted" end
  return JOB_DIR .. "/" .. tconcat(segs, "/"), writable
end

local function plain_replace(s, from, to)
  local out, pos = {}, 1
  while true do
    local a, b = sfind(s, from, pos, true)
    if not a then break end
    out[#out + 1] = ssub(s, pos, a - 1)
    out[#out + 1] = to
    pos = b + 1
  end
  out[#out + 1] = ssub(s, pos)
  return tconcat(out)
end

local function hide(msg, real, shown)
  if type(msg) ~= "string" then return msg end
  return plain_replace(plain_replace(msg, real, shown), JOB_DIR .. "/", "")
end

local function check_mode(mode)
  if mode == nil then return "r" end
  if type(mode) ~= "string" or not smatch(mode, "^[rwa]%+?b?$") then return nil end
  return mode
end

local sbox_io = {}

function sbox_io.open(path, mode)
  mode = check_mode(mode)
  if not mode then error("bad argument #2 to 'open' (invalid mode)", 2) end
  local real, writable = resolve(path)
  if not real then return nil, tostring(path) .. ": " .. writable, 13 end
  if not writable and (mode ~= "r" and mode ~= "rb") then return nil, path .. ": Permission denied (input/ is read-only)", 13 end
  local f, msg, code = io_open(real, mode)
  if not f then return nil, hide(msg, real, path), code end
  return f
end

function sbox_io.lines(path, ...)
  if path == nil then return stdin_proxy:lines() end
  local real, why = resolve(path)
  if not real then error(tostring(path) .. ": " .. why, 2) end
  local ok, it, a, b, c = pcall(io_lines, real, ...)
  if not ok then error(hide(it, real, path), 2) end
  return it, a, b, c
end

local default_in, default_out = stdin_proxy, stdout_proxy

local function set_default(current, file, mode)
  if file == nil then return current end
  if type(file) == "string" then
    local f, msg = sbox_io.open(file, mode)
    if not f then error(msg, 3) end
    return f
  end
  if is_proxy(file) or io_type(file) then return file end
  error("bad argument #1 (FILE* expected, got " .. type(file) .. ")", 3)
end

function sbox_io.input(file) default_in = set_default(default_in, file, "r") return default_in end
function sbox_io.output(file) default_out = set_default(default_out, file, "w") return default_out end
function sbox_io.read(...) return default_in:read(...) end
function sbox_io.write(...) return default_out:write(...) end
function sbox_io.flush() return default_out:flush() end
function sbox_io.close(f)
  if f == nil then f = default_out end
  return f:close()
end
function sbox_io.type(x)
  if is_proxy(x) then return "file" end
  return io_type(x)
end

local tmp_counter = 0
local function tmpname()
  tmp_counter = tmp_counter + 1
  return sformat("tmp/lua_%d_%d_%d", luart.getpid(), now_ms() % 100000000, tmp_counter)
end
function sbox_io.tmpfile() return sbox_io.open(tmpname(), "w+b") end

sbox_io.stdout, sbox_io.stderr, sbox_io.stdin = stdout_proxy, stderr_proxy, stdin_proxy

-- ---------------------------------------------------------------------------
-- Lifecycle: finish() writes the status file and ends the process
-- ---------------------------------------------------------------------------
local finished = false

local function jstr(s) return enc_string(s) end

local function finish(status, exit_code, code, message)
  if finished then return end
  finished = true
  local _, peak = luart.mem()
  local parts = {
    '"status":' .. jstr(status),
    '"exitCode":' .. sformat("%d", exit_code),
    '"truncated":' .. (truncated and "true" or "false"),
    '"stdoutBytes":' .. sformat("%d", counters.stdout),
    '"stderrBytes":' .. sformat("%d", counters.stderr),
    '"memPeak":' .. sformat("%d", peak),
  }
  if code then parts[#parts + 1] = '"errorCode":' .. jstr(code) end
  if message then
    if #message > 4096 then message = ssub(message, 1, 4096) end
    if not utf8len(message) then message = sgsub(message, "[\128-\255]", "?") end
    parts[#parts + 1] = '"errorMessage":' .. jstr(message)
  end
  write_all(JOB_DIR .. "/status.json", "{" .. tconcat(parts, ",") .. "}\n")
  pcall(f_flush, io_stdout)
  pcall(f_flush, io_stderr)
  luart.exit(exit_code)
end

local function classify_failure(err_text)
  -- Called after a script error with the original message text.
  if luart.timed_out() then
    return "timeout", 124, "EXECUTION_TIMEOUT", "Execution deadline exceeded"
  end
  local _, _, oom = luart.mem()
  if oom then
    luart.relax_mem()
    return "memory", 137, "MEMORY_LIMIT_EXCEEDED", "Script exceeded its memory limit"
  end
  return "error", 1, "SCRIPT_RUNTIME_ERROR", err_text
end

-- ---------------------------------------------------------------------------
-- Sandbox environment
-- ---------------------------------------------------------------------------
local function copy(t)
  local c = {}
  for k, v in next, t do c[k] = v end
  return c
end

local env = {}
for _, name in ipairs({ "assert", "error", "ipairs", "next", "pairs", "pcall", "rawequal", "rawget", "rawlen", "rawset", "select", "setmetatable", "tonumber", "tostring", "type", "xpcall" }) do
  env[name] = _G[name]
end
env._VERSION = _VERSION
env._G = env
local FAKE_FILE_MT = { __index = {}, __name = "FILE*" }
local FAKE_STRING_MT
function env.getmetatable(x)
  local mt = getmetatable(x)
  if mt == nil then return nil end
  if mt == STRING_MT then return FAKE_STRING_MT end
  if mt == FILE_MT then return FAKE_FILE_MT end
  return mt
end
env.string, env.table, env.math, env.utf8, env.coroutine = copy(G_string), copy(G_table), copy(G_math), copy(G_utf8), copy(G_coroutine)
env.string.dump = nil -- bytecode could be fed back through load; also pointless in a sandbox
FAKE_STRING_MT = { __index = env.string }

function env.print(...)
  local n = select("#", ...)
  local t = {}
  for i = 1, n do t[i] = tostring((select(i, ...))) end
  emit_out(tconcat(t, "\t", 1, n) .. "\n")
end

function env.collectgarbage(opt, arg)
  if opt == nil or opt == "collect" or opt == "count" or opt == "step" or opt == "isrunning" then
    return gcollect(opt, arg)
  end
  error("bad argument #1 to 'collectgarbage' (option '" .. tostring(opt) .. "' is not permitted)", 2)
end

-- load: text chunks only, default environment is the sandbox
function env.load(chunk, chunkname, mode, ...)
  if mode ~= nil and mode ~= "t" then return nil, "binary chunks are not permitted" end
  local e = env
  if select("#", ...) > 0 then e = (...) end
  if type(chunk) == "string" and ssub(chunk, 1, 1) == "\27" then return nil, "binary chunks are not permitted" end
  return load(chunk, chunkname or "=(load)", "t", e)
end

local function loadfile_confined(path, mode, ...)
  if mode ~= nil and mode ~= "t" then return nil, "binary chunks are not permitted" end
  local real, why = resolve(path)
  if not real then return nil, tostring(path) .. ": " .. why end
  local src = read_all(real)
  if not src then return nil, tostring(path) .. ": cannot open file" end
  if ssub(src, 1, 1) == "#" then src = "--" .. src end
  local e = env
  if select("#", ...) > 0 then e = (...) end
  return load(src, "@" .. path, "t", e)
end
env.loadfile = loadfile_confined
function env.dofile(path)
  local fn, err = loadfile_confined(path)
  if not fn then error(err, 2) end
  return fn()
end

-- os: only what is safe and useful
local sbox_os = { time = os_time, clock = os_clock, date = os_date, difftime = os_difftime }
function sbox_os.getenv(name)
  if type(name) ~= "string" then error("bad argument #1 to 'getenv' (string expected, got " .. type(name) .. ")", 2) end
  return ENV[name]
end
function sbox_os.tmpname() return tmpname() end
function sbox_os.remove(path)
  local real, writable = resolve(path)
  if not real or not writable then return nil, tostring(path) .. ": Permission denied", 13 end
  local ok, msg, code = os_remove(real)
  if not ok then return nil, hide(msg, real, path), code end
  return true
end
function sbox_os.rename(from, to)
  local a, wa = resolve(from)
  local b, wb = resolve(to)
  if not a or not b or not wa or not wb then return nil, "Permission denied", 13 end
  local ok, msg, code = os_rename(a, b)
  if not ok then return nil, hide(msg, a, from), code end
  return true
end
function sbox_os.exit(code, _)
  pcall(f_flush, io_stdout)
  if code == nil or code == true or code == 0 then return finish("ok", 0) end
  if code == false then code = 1 end
  if type(code) ~= "number" then code = 1 end
  return finish("error", math.tointeger(code) or 1, "SCRIPT_RUNTIME_ERROR", "script exited with status " .. tostring(code))
end
env.os, env.io = sbox_os, sbox_io

-- require: private search list, scripts cannot influence it
local loaded, preload = {}, {}
local LUA_VER = "5.4"
local lua_dirs, c_dirs = {}, {}
if ROCKS_DIR then
  lua_dirs[#lua_dirs + 1] = { ROCKS_DIR .. "/share/lua/" .. LUA_VER, "<rocks>/share/lua/" .. LUA_VER }
  c_dirs[#c_dirs + 1] = { ROCKS_DIR .. "/lib/lua/" .. LUA_VER, "<rocks>/lib/lua/" .. LUA_VER }
end
lua_dirs[#lua_dirs + 1] = { JOB_DIR .. "/input", "input" }

-- Lua's rule for names with a hyphen: try the part before it, then the part after it.
local function open_names(name)
  local a, b = smatch(name, "^([^%-]*)%-(.*)$")
  if a then return { a, b } end
  return { name }
end

local function module_files(dir, name, suffixes)
  local rel = sgsub(name, "%.", "/")
  local out = {}
  for _, suf in ipairs(suffixes) do out[#out + 1] = dir .. "/" .. rel .. suf end
  return out
end

local function sbox_require(name)
  if type(name) ~= "string" then error("bad argument #1 to 'require' (string expected, got " .. type(name) .. ")", 2) end
  local cached = loaded[name]
  if cached ~= nil then return cached end
  if not smatch(name, "^[%w_%-]+[%w_%-%.]*$") or sfind(name, "..", 1, true) then
    error("module '" .. name .. "' not found: invalid module name", 2)
  end
  local pre = preload[name]
  if pre ~= nil then
    local r = pre(name, ":preload:")
    if r ~= nil then loaded[name] = r elseif loaded[name] == nil then loaded[name] = true end
    return loaded[name], ":preload:"
  end
  local tried = {}
  for _, d in ipairs(lua_dirs) do
    for _, path in ipairs(module_files(d[1], name, { ".lua", "/init.lua" })) do
      local src = read_all(path)
      if src then
        local shown = d[2] .. ssub(path, #d[1] + 1)
        if ssub(src, 1, 1) == "#" then src = "--" .. src end
        local fn, err = load(src, "@" .. shown, "t", env)
        if not fn then error("error loading module '" .. name .. "' from file '" .. shown .. "':\n\t" .. err, 2) end
        local r = fn(name, shown)
        if r ~= nil then loaded[name] = r elseif loaded[name] == nil then loaded[name] = true end
        return loaded[name], shown
      end
      tried[#tried + 1] = "no file '" .. d[2] .. ssub(path, #d[1] + 1) .. "'"
    end
  end
  for _, d in ipairs(c_dirs) do
    for _, path in ipairs(module_files(d[1], name, { ".so" })) do
      local fh = io_open(path, "rb")
      if fh then
        f_close(fh)
        local fn, err
        for _, base in ipairs(open_names(name)) do
          fn, err = loadlib(path, "luaopen_" .. sgsub(base, "%.", "_"))
          if fn then break end
        end
        if not fn then error("error loading native module '" .. name .. "': " .. tostring(err), 2) end
        local r = fn(name, path)
        if r ~= nil then loaded[name] = r elseif loaded[name] == nil then loaded[name] = true end
        return loaded[name], path
      end
      tried[#tried + 1] = "no file '" .. d[2] .. ssub(path, #d[1] + 1) .. "'"
    end
  end
  error("module '" .. name .. "' not found:\n\tno field package.preload['" .. name .. "']\n\t" .. tconcat(tried, "\n\t"), 2)
end

env.require = sbox_require
env.package = { loaded = loaded, preload = preload, path = "", cpath = "", config = "/\n;\n?\n!\n-\n" }
loaded._G, loaded.string, loaded.table, loaded.math, loaded.utf8 = env, env.string, env.table, env.math, env.utf8
loaded.coroutine, loaded.os, loaded.io, loaded.package = env.coroutine, env.os, env.io, env.package

-- job: small host API
local job = {
  id = spec.jobId,
  args = ARGS,
  env = ENV,
  api_version = spec.apiVersion or 1,
  runtime = { lua = _VERSION, luart = luart.version, abi = luart.abi },
}
function job.input(name)
  local segs = split_path(name)
  if not segs then error("invalid file name", 2) end
  return "input/" .. tconcat(segs, "/")
end
function job.output(name)
  local segs = split_path(name)
  if not segs then error("invalid file name", 2) end
  return "output/" .. tconcat(segs, "/")
end
env.job = job

local arg_table = { [0] = SCRIPT_NAME }
for i, v in ipairs(ARGS) do arg_table[i] = v end
env.arg = arg_table

-- ---------------------------------------------------------------------------
-- Run the script
-- ---------------------------------------------------------------------------
local src = read_all(JOB_DIR .. "/script.lua")
if not src then finish("error", 70, "INTERNAL_ERROR", "script file is missing") end
if ssub(src, 1, 3) == "\239\187\191" then src = ssub(src, 4) end
if ssub(src, 1, 1) == "#" then src = "--" .. src end
if ssub(src, 1, 1) == "\27" then
  f_write(io_stderr, "lua: binary chunks are not permitted\n")
  finish("error", 1, "SCRIPT_COMPILE_ERROR", "binary chunks are not permitted")
end

local fn, cerr = load(src, "@" .. SCRIPT_NAME, "t", env)
if not fn then
  f_write(io_stderr, "lua: ", cerr, "\n")
  finish("error", 1, "SCRIPT_COMPILE_ERROR", cerr)
end

local function handler(e)
  if type(e) ~= "string" then
    local mt = getmetatable(e)
    if type(mt) == "table" and mt.__tostring then
      e = tostring(e)
    else
      e = "(error object is a " .. type(e) .. " value)"
    end
  end
  return traceback(e, 2)
end

local function short_message(tb)
  local first = smatch(tb, "^(.-)\nstack traceback:") or tb
  return first
end

local function clean_traceback(tb)
  local lines = {}
  for line in sgmatch(tb .. "\n", "(.-)\n") do
    if sfind(line, "[C]: in function 'xpcall'", 1, true) then break end
    lines[#lines + 1] = line
  end
  return tconcat(lines, "\n")
end

local packed = tpack(xpcall(fn, handler, tunpack(ARGS)))
if not packed[1] then
  local tb = packed[2]
  local status, exit_code, code, msg = classify_failure(short_message(tb))
  if status == "error" then
    f_write(io_stderr, "lua: ", clean_traceback(tb), "\n")
  end
  finish(status, exit_code, code, msg)
end

-- Success: serialise the return value(s)
local nret = packed.n - 1
if nret > 0 then
  local ok, res
  if nret == 1 then
    ok, res = pcall(json_encode, packed[2], RESULT_LIMIT)
  else
    local arr = {}
    for i = 1, nret do arr[i] = packed[i + 1] end
    ok, res = pcall(json_encode, arr, RESULT_LIMIT, nret)
  end
  if not ok then
    local code, msg = "RESULT_NOT_SERIALIZABLE", tostring(res)
    if type(res) == "table" and res.code then code, msg = res.code, res.msg end
    f_write(io_stderr, "lua: result error: ", msg, "\n")
    finish("error", 1, code, msg)
  end
  write_all(JOB_DIR .. "/result.json", res)
end
finish("ok", 0)
