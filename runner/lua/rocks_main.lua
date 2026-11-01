-- rocks_main.lua - runs the real LuaRocks (third_party/luarocks, unmodified) inside luart.
--
-- What makes this work on Android, where there is no curl/unzip/tar/md5sum/compiler:
--   * lfs, zlib (lua-zlib), md5 and a small posix subset are compiled into the runner;
--   * socket.http / ssl.https / ltn12 shims route downloads through the host app
--     (see shims/luart_bridge.lua) which enforces HTTPS, a host allow-list and size limits;
--   * os.execute / io.popen are disabled, so build scripts can never run commands;
--   * native build back-ends are replaced by stubs that fail with NATIVE_ROCK_UNSUPPORTED.
--
-- Environment (set by the host):
--   LUART_TREE      rock tree root                (required)
--   LUART_CONF_DIR  writable dir for generated LuaRocks config
--   LUART_SERVERS   space separated server URLs   (default https://luarocks.org)
--   HOME, TMPDIR    writable directories inside the app sandbox
--
-- The last thing written to stdout is a frame  "\1{"op":"done","exitCode":N,...}"  (see luart_bridge).

local luart, opts = ...
local argv = { select(3, ...) }
local RUNTIME = opts.runtime

local function env(name, default)
  local v = os.getenv(name)
  if v == nil or v == "" then return default end
  return v
end

local TREE = env("LUART_TREE")
if not TREE then
  io.stderr:write("luart rocks: LUART_TREE is not set\n")
  return 64
end
local CONF_DIR = env("LUART_CONF_DIR", TREE .. "/etc")
local HOME = env("HOME", TREE .. "/home")
local TMPDIR = env("TMPDIR", HOME .. "/tmp")
local CACHE = HOME .. "/cache"
local SERVERS = env("LUART_SERVERS", "https://luarocks.org")

package.path = RUNTIME .. "/?.lua;" .. RUNTIME .. "/?/init.lua;" .. RUNTIME .. "/shims/?.lua"
package.cpath = ""

local lfs = require("lfs")

local function mkdirs(p)
  local cur = ""
  for seg in p:gmatch("[^/]+") do
    cur = cur .. "/" .. seg
    if not lfs.attributes(cur) then assert(lfs.mkdir(cur)) end
  end
end
for _, d in ipairs({ CONF_DIR, TREE, HOME, TMPDIR, CACHE }) do mkdirs(d) end

-- /tmp does not exist on Android: os.tmpname() must live under TMPDIR.
do
  local counter = 0
  function os.tmpname()
    counter = counter + 1
    local p = string.format("%s/lua_%d_%d_%d", TMPDIR, luart.getpid(), luart.now_ms() % 1000000000, counter)
    local f = io.open(p, "w")
    if f then f:close() end
    return p
  end
end

-- No child processes, ever.
function os.execute(cmd)
  if cmd == nil then return false end -- "is a shell available?"
  return nil, "exit", 127
end
function io.popen() return nil, "external commands are disabled in this runtime" end

-- ---------------------------------------------------------------------------
-- LuaRocks configuration
-- ---------------------------------------------------------------------------
local cpu = ({ ["arm64-v8a"] = "aarch64", ["armeabi-v7a"] = "armv7l", x86_64 = "x86_64", x86 = "i686" })[luart.abi] or "unknown"
local LUA_EXE = TREE .. "/bin/luart"

package.loaded["luarocks.core.hardcoded"] = {
  SYSTEM = "linux",
  PROCESSOR = cpu,
  PREFIX = TREE,
  SYSCONFDIR = CONF_DIR,
  FORCE_CONFIG = true, -- ignore ~/.luarocks, XDG and project config: only the file we generate below counts
  LUA_VERSION = "5.4",
  LUA = LUA_EXE,
  LUA_DIR = TREE,
  LUA_BINDIR = TREE .. "/bin",
}

local function q(s) return string.format("%q", s) end
local servers = {}
for s in SERVERS:gmatch("%S+") do servers[#servers + 1] = q(s) end
do
  local cf = assert(io.open(CONF_DIR .. "/config-5.4.lua", "w"))
  cf:write(string.format([[
rocks_trees = { { name = "app", root = %s } }
rocks_servers = { %s }
fs_use_modules = true
show_downloads = false
connection_timeout = 30
local_cache = %s
variables = { LUA = %s, LUA_DIR = %s, LUA_BINDIR = %s }
]], q(TREE), table.concat(servers, ", "), q(CACHE), q(LUA_EXE), q(TREE), q(TREE .. "/bin")))
  cf:close()
end

-- ---------------------------------------------------------------------------
-- Result frame + exit handling
-- ---------------------------------------------------------------------------
local bridge = require("luart_bridge")
local rejection

local function finish(code)
  if code == true or code == nil then code = 0 elseif code == false then code = 1 end
  local msg = { op = "done", exitCode = code }
  if rejection then
    msg.errorCode, msg.errorMessage = rejection.code, rejection.message
  end
  bridge.emit(msg)
  luart.exit(code)
end
os.exit = function(code) finish(code) end

local function reject(code, message)
  rejection = { code = code, message = message }
  return nil, code .. ": " .. message
end

-- ---------------------------------------------------------------------------
-- Make LuaRocks believe a Lua interpreter exists (it would otherwise exec it)
-- ---------------------------------------------------------------------------
local util = require("luarocks.util")
util.check_lua_version = function() return "5.4" end
util.find_lua = function()
  return { lua_version = "5.4", lua = LUA_EXE, lua_dir = TREE, lua_bindir = TREE .. "/bin" }
end

-- ---------------------------------------------------------------------------
-- Build back-ends: pure Lua only
-- ---------------------------------------------------------------------------
local function native_message(rockspec, why)
  local name = rockspec and rockspec.package and (rockspec.package .. " " .. tostring(rockspec.version)) or "this rock"
  return string.format("%s needs to compile native code (%s), which this app cannot do: it ships no C toolchain. " ..
    "Only pure-Lua rocks can be installed. Pick a pure-Lua alternative or ask the app owner to provide a prebuilt module.", name, why)
end

for _, backend in ipairs({ "make", "cmake", "command" }) do
  package.preload["luarocks.build." .. backend] = function()
    return {
      skip_lua_inc_lib_check = true,
      run = function(rockspec) return reject("NATIVE_ROCK_UNSUPPORTED", native_message(rockspec, "build type '" .. backend .. "' runs external tools")) end,
    }
  end
end

local function looks_native(entry)
  if type(entry) == "table" then return true end
  if type(entry) == "string" then
    local ext = entry:match("%.(%w+)$")
    ext = ext and ext:lower()
    return ext == "c" or ext == "cc" or ext == "cpp" or ext == "cxx" or ext == "m" or ext == "mm" or ext == "h"
  end
  return false
end

package.preload["luarocks.build.builtin"] = function(name)
  local path = assert(package.searchpath(name, package.path))
  local builtin = assert(loadfile(path))(name, path)
  builtin.skip_lua_inc_lib_check = true
  local real_run = builtin.run
  builtin.run = function(rockspec, no_install)
    local build = rockspec.build or {}
    for mod, entry in pairs(build.modules or {}) do
      if looks_native(entry) then
        return reject("NATIVE_ROCK_UNSUPPORTED", native_message(rockspec, "module '" .. tostring(mod) .. "' has C sources"))
      end
    end
    if build.install and type(build.install.lib) == "table" and next(build.install.lib) ~= nil then
      return reject("NATIVE_ROCK_UNSUPPORTED", native_message(rockspec, "it installs prebuilt libraries"))
    end
    if type(rockspec.external_dependencies) == "table" and next(rockspec.external_dependencies) ~= nil then
      return reject("NATIVE_ROCK_UNSUPPORTED", native_message(rockspec, "it needs external C libraries"))
    end
    return real_run(rockspec, no_install)
  end
  return builtin
end

-- ---------------------------------------------------------------------------
-- Run LuaRocks
-- ---------------------------------------------------------------------------
arg = { [0] = "luarocks" }
for i, v in ipairs(argv) do arg[i] = v end

local cmd = require("luarocks.cmd")
local commands = {
  install = "luarocks.cmd.install",
  remove = "luarocks.cmd.remove",
  list = "luarocks.cmd.list",
  search = "luarocks.cmd.search",
  show = "luarocks.cmd.show",
  path = "luarocks.cmd.path",
  config = "luarocks.cmd.config",
  which = "luarocks.cmd.which",
  purge = "luarocks.cmd.purge",
  doc = "luarocks.cmd.doc",
}

local ok, err = xpcall(function()
  cmd.run_command("LuaRocks for Lua Android (pure-Lua rocks only)", commands, "luarocks.cmd.external", table.unpack(argv))
end, debug.traceback)
if not ok then
  io.stderr:write("luarocks driver error: ", tostring(err), "\n")
  rejection = rejection or { code = "INTERNAL_ERROR", message = tostring(err):match("^[^\n]*") }
  finish(70)
end
finish(0)
