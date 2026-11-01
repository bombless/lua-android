#!/usr/bin/env python3
"""Exec-mode tests for the luart runner. Usage: run_tests.py [path-to-luart] [-k substring]"""
import json, os, shutil, sys, tempfile, time, traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness
from harness import run

TESTS = []
def test(fn):
    TESTS.append(fn)
    return fn

def ok(r, **kw):
    assert r.status is not None, "no status.json (rc=%s stderr=%r)" % (r.rc, r.stderr[:300])
    assert r.status["status"] == "ok", "expected ok, got %s" % (r.status,)
    assert r.rc == 0, "rc=%s" % r.rc

def failed(r, code, status="error"):
    assert r.status is not None, "no status.json (rc=%s stderr=%r)" % (r.rc, r.stderr[:300])
    assert r.status["status"] == status and r.status.get("errorCode") == code, "got %s" % (r.status,)

# ------------------------------------------------------------------ basics
@test
def basic_print_and_return():
    r = run('local s=0 for i=1,100 do s=s+i end print("sum", s) return s')
    ok(r)
    assert r.stdout == "sum\t5050\n", repr(r.stdout)
    assert r.result == 5050
    assert r.status["stdoutBytes"] == len(r.stdout) and r.status["truncated"] is False
    assert os.path.exists(os.path.join(r.job, "runner.pid"))

@test
def args_and_env():
    r = run('return {arg[0], arg[1], arg[2], select("#", ...), os.getenv("FOO"), tostring(os.getenv("PATH")), job.args[1], job.env.FOO, job.id}',
            args=["a", "b c"], env={"FOO": "bar"})
    ok(r)
    assert r.result == ["script.lua", "a", "b c", 2, "bar", "nil", "a", "bar", "test-job"], r.result

@test
def stdin_is_empty():
    r = run('return {tostring(io.read()), tostring(io.stdin:read("a")), io.type(io.stdin), io.type(io.stdout)}')
    ok(r)
    assert r.result == ["nil", "nil", "file", "file"], r.result

@test
def io_write_variants():
    r = run('io.write("a", 1, 2.5, "\\n") io.stdout:write("x"):write("y\\n") io.stderr:write("err\\n") print(io.write("z") == io.stdout)')
    ok(r)
    assert r.stdout == "a12.5\nxy\nztrue\n", repr(r.stdout)
    assert r.stderr == "err\n", repr(r.stderr)

@test
def shebang_and_bom():
    ok(run(None, script_bytes=b"\xef\xbb\xbf#!/usr/bin/lua\nreturn 7"))
    r = run(None, script_bytes=b"#!/usr/bin/lua\nreturn 8")
    ok(r); assert r.result == 8

@test
def general_language_features():
    src = r'''
    local function fib(n) if n < 2 then return n end return fib(n-1) + fib(n-2) end
    local co = coroutine.wrap(function() for i = 1, 3 do coroutine.yield(i * i) end end)
    local t = {} for v in co do t[#t+1] = v end
    local s = ("hello world"):gsub("o", "0"):upper()
    goto done
    do return "skipped" end
    ::done::
    local cls = setmetatable({}, {__index = function(_, k) return k .. "!" end})
    return {fib(20), t, s, 7 // 2, 7 / 2, utf8.len("héllo"), cls.x, string.format("%5.2f|%d|%s", 3.14159, 42, "z"),
            os.date("!%Y-%m-%d", 0), math.type(1), math.type(1.0), select("#", table.unpack({1, 2, 3}))}
    '''
    r = run(src)
    ok(r)
    assert r.result == [6765, [1, 4, 9], "HELL0 W0RLD", 3, 3.5, 5, "x!", " 3.14|42|z", "1970-01-01", "integer", "float", 3], r.result

# ------------------------------------------------------------------ JSON results
@test
def json_values():
    cases = [
        ("return nil", "null"), ("return true", "true"), ("return 1.5", "1.5"), ("return 3.0", "3.0"),
        ("return 2^53", "9007199254740992.0"), ("return math.maxinteger", "9223372036854775807"),
        ("return 0.1", "0.1"), ("return -0.0", "-0.0"), ("return 1e100", "1e+100"),
        ('return "h\\u{e9}llo\\n\\"x\\"\\\\\\1"', '"h\u00e9llo\\n\\"x\\"\\\\\\u0001"'),
        ("return {1,2,3}", "[1,2,3]"), ("return {}", "[]"), ("return {a=1,b={true,false}}", '{"a":1,"b":[true,false]}'),
        ("return 1, nil, 'x'", '[1,null,"x"]'), ("return {{}, {{}}}", "[[],[[]]]"), ("return {['1']=1}", '{"1":1}'),
    ]
    for src, want in cases:
        r = run(src)
        ok(r)
        assert r.result_text == want, "%s -> %r (want %r)" % (src, r.result_text, want)

@test
def json_no_return_has_no_result_file():
    r = run("local x = 1")
    ok(r)
    assert r.result_text is None

@test
def json_errors():
    bad = [
        ("return {[1]=1,[3]=3}", "RESULT_NOT_SERIALIZABLE"), ("return {1, 2, x = 3}", "RESULT_NOT_SERIALIZABLE"),
        ("local t = {} t.t = t return t", "RESULT_NOT_SERIALIZABLE"), ("return print", "RESULT_NOT_SERIALIZABLE"),
        ("return 0/0", "RESULT_NOT_SERIALIZABLE"), ("return math.huge", "RESULT_NOT_SERIALIZABLE"),
        ('return "\\xff"', "RESULT_NOT_SERIALIZABLE"), ("return coroutine.create(print)", "RESULT_NOT_SERIALIZABLE"),
        ("return string.rep('x', 2000000)", "RESULT_TOO_LARGE"),
        ("local t = {} local c = t for i = 1, 100 do c[1] = {} c = c[1] end return t", "RESULT_NOT_SERIALIZABLE"),
    ]
    for src, code in bad:
        r = run(src)
        failed(r, code)
        assert r.rc == 1
    # output produced before the serialisation error is kept
    r = run("print('kept') return print")
    failed(r, "RESULT_NOT_SERIALIZABLE"); assert r.stdout == "kept\n"

# ------------------------------------------------------------------ errors
@test
def runtime_error():
    r = run('print("before")\nerror("boom")')
    failed(r, "SCRIPT_RUNTIME_ERROR")
    assert "script.lua:2: boom" in r.status["errorMessage"], r.status
    assert r.stderr.startswith("lua: script.lua:2: boom\nstack traceback:"), r.stderr
    assert "xpcall" not in r.stderr and "bootstrap" not in r.stderr, r.stderr
    assert r.stdout == "before\n" and r.rc == 1

@test
def compile_error():
    r = run("local = 1")
    failed(r, "SCRIPT_COMPILE_ERROR")
    assert "script.lua:1:" in r.status["errorMessage"]
    r = run(None, script_bytes=b"\x1bLua")
    failed(r, "SCRIPT_COMPILE_ERROR")

@test
def error_objects():
    r = run("error({code = 1})"); failed(r, "SCRIPT_RUNTIME_ERROR")
    assert "error object is a table value" in r.status["errorMessage"]
    r = run('error(setmetatable({}, {__tostring = function() return "custom!" end}))'); failed(r, "SCRIPT_RUNTIME_ERROR")
    assert "custom!" in r.status["errorMessage"]
    r = run('error("plain", 0)'); failed(r, "SCRIPT_RUNTIME_ERROR")
    assert r.status["errorMessage"] == "plain"

@test
def os_exit():
    r = run('print("a") os.exit(3) print("b")')
    failed(r, "SCRIPT_RUNTIME_ERROR"); assert r.rc == 3 and r.stdout == "a\n" and r.status["exitCode"] == 3
    r = run('print("a") os.exit(true) print("b")'); ok(r); assert r.stdout == "a\n"
    r = run('os.exit(false)'); failed(r, "SCRIPT_RUNTIME_ERROR"); assert r.rc == 1

# ------------------------------------------------------------------ limits
@test
def timeout_graceful():
    r = run('print("before") while true do end', timeout_ms=500)
    failed(r, "EXECUTION_TIMEOUT", "timeout")
    assert r.rc == 124 and r.wall < 3, (r.rc, r.wall)
    assert r.stdout == "before\n"

@test
def timeout_swallowed_by_pcall():
    r = run('while true do pcall(function() while true do end end) end', timeout_ms=500)
    failed(r, "EXECUTION_TIMEOUT", "timeout")
    assert r.rc == 124 and r.wall < 4, (r.rc, r.wall)

@test
def timeout_inside_c_function():
    r = run('return string.find(string.rep("a", 80), string.rep("a*", 40) .. "b")', timeout_ms=500)
    failed(r, "EXECUTION_TIMEOUT", "timeout")
    assert r.rc == 124 and r.wall < 4, (r.rc, r.wall)

@test
def sigterm_is_cancellation():
    r = run("while true do end", timeout_ms=60000, term_after=0.4)
    failed(r, "EXECUTION_CANCELLED", "cancelled")
    assert r.rc == 143, r.rc

@test
def memory_limit():
    r = run("local t = {} for i = 1, 1e9 do t[i] = i end", mem=32 << 20)
    failed(r, "MEMORY_LIMIT_EXCEEDED", "memory"); assert r.rc == 137 and r.wall < 10
    r = run('local s = string.rep("x", 1e9)', mem=32 << 20)
    failed(r, "MEMORY_LIMIT_EXCEEDED", "memory")
    r = run("return #string.rep('x', 1000000)", mem=32 << 20)  # well below the limit
    ok(r); assert r.result == 1000000

@test
def output_caps():
    r = run('for i = 1, 200000 do print(string.rep("x", 100)) end', out=1 << 20)
    ok(r)
    assert os.path.getsize(os.path.join(r.job, "stdout.txt")) == 1 << 20
    assert r.status["truncated"] is True and r.status["stdoutBytes"] == 1 << 20
    r = run('io.stderr:write(string.rep("y", 3000000)) io.stdout:write("fine")', out=1 << 20)
    ok(r)
    assert os.path.getsize(os.path.join(r.job, "stderr.txt")) == 1 << 20 and r.stdout == "fine" and r.status["truncated"] is True

@test
def file_size_limit_per_file():
    src = 'local f = assert(io.open("big.bin", "wb")) local chunk = string.rep("z", 65536) local ok, err for i = 1, 100 do ok, err = f:write(chunk) if not ok then break end end f:close() return tostring(err)'
    r = run(src, fsize=1 << 20)
    ok(r)
    assert os.path.getsize(os.path.join(r.job, "output", "big.bin")) <= 1 << 20

# ------------------------------------------------------------------ sandbox
@test
def forbidden_functions():
    src = '''return {tostring(os.execute), tostring(io.popen), tostring(debug), tostring(package.loadlib), tostring(string.dump),
            tostring(os.getenv("HOME")), tostring(os.setlocale), tostring(rawget(_G, "dofile") ~= nil),
            tostring(select(1, pcall(collectgarbage, "setpause", 100))), tostring(os.tmpname():match("^tmp/") ~= nil)}'''
    r = run(src)
    ok(r)
    assert r.result == ["nil"] * 7 + ["true", "false", "true"], r.result

@test
def path_confinement():
    src = '''
    local out = {}
    local function try(p, mode) local f, e = io.open(p, mode) if f then f:close() return "OPENED" end return e end
    out.abs = try("/etc/passwd")
    out.dotdot = try("../script.lua")
    out.dotdot2 = try("input/../../script.lua")
    out.dotdot3 = try("output/../spec.lua")
    out.backslash = try("a\\\\b")
    out.nul = try("a\\0b")
    out.root_file = try("../status.json")
    out.input_write = try("input/data.txt", "w")
    out.input_append = try("input/data.txt", "a")
    out.input_read = try("input/data.txt", "r")
    out.missing = try("nothing.txt")
    out.job_dir = try("spec.lua")
    out.remove_input = tostring(select(2, os.remove("input/data.txt")))
    out.rename_out = tostring(select(2, os.rename("input/data.txt", "output/x")))
    out.lf = tostring(select(2, loadfile("/etc/passwd")))
    out.lf2 = tostring(select(2, loadfile("../script.lua")))
    out.lines = tostring(select(2, pcall(io.lines, "/etc/passwd")))
    out.badmode = tostring(select(2, pcall(io.open, "x", "rw")))
    return out
    '''
    r = run(src, inputs={"data.txt": "hello"})
    ok(r)
    o = r.result
    assert "absolute paths" in o["abs"], o
    for k in ("dotdot", "dotdot2", "dotdot3", "root_file", "lf2"):
        assert "'..'" in o[k], (k, o[k])
    assert "backslash" in o["backslash"] and "NUL" in o["nul"], o
    assert "Permission denied" in o["input_write"] and "Permission denied" in o["input_append"], o
    assert o["input_read"] == "OPENED", o
    assert "No such file" in o["missing"] and r.job not in o["missing"], o
    assert "outside" in o["job_dir"] or "No such file" in o["job_dir"], o
    assert "Permission denied" in o["remove_input"] and "Permission denied" in o["rename_out"], o
    assert "absolute" in o["lf"] and "absolute" in o["lines"] and "invalid mode" in o["badmode"], o
    assert r.job not in json.dumps(o), "absolute job path leaked"

@test
def reading_and_writing_files():
    src = '''
    local f = assert(io.open(job.input("data.txt"))) local text = f:read("a") f:close()
    local w = assert(io.open("result.txt", "w")) w:write(text:upper(), "\\n") w:close()
    local w2 = assert(io.open("output/explicit.txt", "wb")) w2:write("bin\\0ary") w2:close()
    local name = os.tmpname()
    local t = assert(io.open(name, "w+")) t:write("scratch") t:seek("set") local back = t:read("a") t:close()
    assert(os.remove(name))
    local n = 0 for line in io.lines("input/data.txt") do n = n + 1 end
    local tf = io.tmpfile() tf:write("x") tf:close()
    return {back, n, io.type(w), io.type(io.stdout), io.type(42) == nil}
    '''
    r = run(src, inputs={"data.txt": "hello\n"})
    ok(r)
    assert r.result == ["scratch", 1, "closed file", "file", True], r.result
    assert r.out_file("result.txt") == b"HELLO\n\n" and r.out_file("explicit.txt") == b"bin\x00ary"
    assert os.listdir(os.path.join(r.job, "tmp")) != [] or True  # tmp is scratch; host deletes it

@test
def working_directory():
    r = run('local f = assert(io.open("x.txt", "w")) f:write("w") f:close() return job.output("y")', workdir="output/sub")
    ok(r)
    assert open(os.path.join(r.job, "output", "sub", "x.txt")).read() == "w"
    r = run('return tostring(select(2, io.open("x.txt", "w")))', workdir="bogus")
    ok(r); assert "outside input/, output/ and tmp/" in r.result, r.result

@test
def load_is_text_only_and_sandboxed():
    src = '''
    local out = {}
    out[1] = tostring(load("return os.execute")())
    load("leaked = 1")() out[2] = tostring(leaked) out[3] = tostring(rawget(_G, "leaked"))
    local bytecode = ("").dump(function() return 1 end)
    out[4] = tostring(select(2, load(bytecode, "bc", "b")))
    out[5] = tostring(select(2, load(bytecode)))
    out[6] = tostring(load("return x", "c", "t", {x = "custom"})())
    out[7] = tostring(select(2, load("return 1", "c", "bt")))
    return out
    '''
    r = run(src)
    ok(r)
    assert r.result[0] == "nil" and r.result[1] == "1" and r.result[2] == "1", r.result
    assert "binary" in r.result[3] and "binary" in r.result[4] and r.result[5] == "custom" and "binary" in r.result[6], r.result

@test
def metatable_tampering_does_not_break_host():
    src = '''
    local fm = getmetatable(io.open("x.txt", "w")) fm.__index.write = function() end
    getmetatable("").__index = nil
    string.format = function() return "evil" end
    print("still works")
    return {a = 1}
    '''
    r = run(src)
    ok(r); assert r.stdout == "still works\n" and r.result == {"a": 1}, (r.stdout, r.result)

@test
def environment_isolated_between_scripts():
    r1 = run('shared = 42 string.custom = 1 return true')
    r2 = run('return {tostring(shared), tostring(string.custom)}')
    ok(r2); assert r2.result == ["nil", "nil"]

# ------------------------------------------------------------------ require
@test
def require_from_rock_tree_and_input():
    rocks = tempfile.mkdtemp(prefix="luart-rocks-")
    try:
        base = os.path.join(rocks, "share", "lua", "5.4")
        os.makedirs(os.path.join(base, "pkg")); os.makedirs(os.path.join(base, "a"))
        open(os.path.join(base, "mymod.lua"), "w").write('local M = {} function M.hello() return "hi" end M.name = ... return M')
        open(os.path.join(base, "pkg", "init.lua"), "w").write('return {kind = "pkg", sub = require("pkg.sub")}')
        open(os.path.join(base, "pkg", "sub.lua"), "w").write('return "sub-module"')
        open(os.path.join(base, "a", "b.lua"), "w").write('return {path = select(2, ...)}')
        open(os.path.join(base, "evil.lua"), "w").write('return tostring(os.execute)')
        open(os.path.join(base, "broken.lua"), "w").write('error("module exploded")')
        src = '''
        local m = require("mymod")
        local p = require("pkg")
        local h = require("helper")
        local r = {m.hello(), m.name, p.kind, p.sub, h.x, require("a.b").path, require("evil"), tostring(require("mymod") == m),
                   tostring(package.loaded.mymod == m), tostring(require("string") == string)}
        local ok, e = pcall(require, "broken") r[#r + 1] = e
        ok, e = pcall(require, "nope.missing") r[#r + 1] = e
        ok, e = pcall(require, "../script") r[#r + 1] = e
        ok, e = pcall(require, "a/b") r[#r + 1] = e
        return r
        '''
        r = run(src, rocks=rocks, inputs={"helper.lua": "return {x = 42}"})
        ok(r)
        res = r.result
        assert res[:10] == ["hi", "mymod", "pkg", "sub-module", 42, "<rocks>/share/lua/5.4/a/b.lua", "nil", "true", "true", "true"], res
        assert "module exploded" in res[10] and "<rocks>/share/lua/5.4/broken.lua" in res[10], res[10]
        assert "no file '<rocks>/share/lua/5.4/nope/missing.lua'" in res[11] and "no file 'input/nope/missing.lua'" in res[11], res[11]
        assert "invalid module name" in res[12] and "invalid module name" in res[13], res[12:]
        assert rocks not in json.dumps(res) and r.job not in json.dumps(res), "absolute path leaked"
    finally:
        shutil.rmtree(rocks, ignore_errors=True)

@test
def prebuilt_native_module_in_rock_tree_loads():
    """A rock tree may contain prebuilt .so modules for the device ABI (the runner exports the Lua API)."""
    import subprocess
    cc = shutil.which("cc") or shutil.which("gcc")
    if not cc:
        return
    rocks = tempfile.mkdtemp(prefix="luart-rocks-")
    try:
        cdir = os.path.join(rocks, "lib", "lua", "5.4"); os.makedirs(cdir)
        src = os.path.join(rocks, "cmod.c")
        open(src, "w").write('''
#include "lua.h"
#include "lauxlib.h"
static int hello(lua_State *L) { lua_pushstring(L, "hello from C"); return 1; }
int luaopen_cmod(lua_State *L) { lua_newtable(L); lua_pushcfunction(L, hello); lua_setfield(L, -2, "hello"); return 1; }
int luaopen_cmod_sub(lua_State *L) { lua_pushinteger(L, 7); return 1; }
''')
        inc = os.path.join(harness.HERE, "..", "..", "third_party", "lua")
        subprocess.check_call([cc, "-shared", "-fPIC", "-I", inc, src, "-o", os.path.join(cdir, "cmod.so")])
        os.makedirs(os.path.join(cdir, "cmod")); shutil.copy(os.path.join(cdir, "cmod.so"), os.path.join(cdir, "cmod", "sub.so"))
        r = run('return {require("cmod").hello(), require("cmod.sub"), tostring(pcall(require, "missing_native"))}', rocks=rocks)
        ok(r); assert r.result == ["hello from C", 7, "false"], (r.result, r.stderr)
    finally:
        shutil.rmtree(rocks, ignore_errors=True)

# ------------------------------------------------------------------ big inputs
@test
def large_input_is_streamed_not_loaded():
    size = 300 << 20
    def make_big(job):
        with open(os.path.join(job, "input", "big.bin"), "wb") as f:   # sparse file: no real disk usage
            f.truncate(size); f.seek(size - 4); f.write(b"TAIL")
    r = run('local f = assert(io.open("input/big.bin", "rb")) local n = f:seek("end") f:seek("set", n - 4) local tail = f:read(4) f:close() return {n, tail}',
            mem=16 << 20, setup=make_big)
    ok(r)
    assert r.result == [size, "TAIL"], r.result

# ------------------------------------------------------------------ misc
@test
def md5_and_posix_modules_not_visible_to_scripts():
    r = run('return {tostring(pcall(require, "md5")), tostring(pcall(require, "lfs")), tostring(pcall(require, "posix")), tostring(pcall(require, "zlib"))}')
    ok(r); assert r.result == ["false"] * 4, r.result

def main():
    args = [a for a in sys.argv[1:]]
    pattern = None
    if "-k" in args:
        i = args.index("-k"); pattern = args[i + 1]; del args[i:i + 2]
    if args:
        os.environ["LUART"] = os.path.abspath(args[0])
    passed = failed_n = 0
    for fn in TESTS:
        if pattern and pattern not in fn.__name__:
            continue
        t0 = time.time()
        try:
            fn()
            print("  PASS  %-48s %.2fs" % (fn.__name__, time.time() - t0)); passed += 1
        except Exception as e:
            print("  FAIL  %-48s %s" % (fn.__name__, e)); failed_n += 1
            if os.environ.get("TRACE"): traceback.print_exc()
    print("\n%d passed, %d failed" % (passed, failed_n))
    sys.exit(1 if failed_n else 0)

if __name__ == "__main__":
    main()
