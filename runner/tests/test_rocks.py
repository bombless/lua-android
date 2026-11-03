#!/usr/bin/env python3
"""LuaRocks-mode tests: real LuaRocks inside luart, talking to a mock server through the host bridge.
Usage: test_rocks.py [path-to-luart] [-k substring]"""
import os, shutil, subprocess, sys, tempfile, time, traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harness, mockrepo, rocks_host as H

TESTS = []
def test(fn):
    TESTS.append(fn)
    return fn

class Env:
    """A fresh server + empty tree per test."""
    def __init__(self, rocks=None, allowed=("127.0.0.1",)):
        self.work = tempfile.mkdtemp(prefix="rocks-test-")
        self.repo = os.path.join(self.work, "repo")
        mockrepo.build_repo(self.repo, rocks)
        self.srv, self.url = H.serve(self.repo)
        self.root = os.path.join(self.work, "lua")   # <root>/runtime + <root>/luarocks, like files/lua on device
        H.stage_runtime(self.root)
        self.tree = os.path.join(self.work, "tree")
        self.allowed = allowed
    def lr(self, *args, **kw):
        return H.run_rocks(args, root=self.root, tree=self.tree, work=self.work, servers=[self.url], allowed_hosts=self.allowed, **kw)
    def tree_file(self, rel): return os.path.join(self.tree, rel)
    def script(self, src, **kw):
        return harness.run(src, rocks=self.tree, **kw)
    def close(self):
        self.srv.shutdown(); shutil.rmtree(self.work, ignore_errors=True)

def with_env(**kw):
    def deco(fn):
        def wrapper():
            e = Env(**kw)
            try: fn(e)
            finally: e.close()
        wrapper.__name__ = fn.__name__
        return test(wrapper)
    return deco

def assert_ok(r):
    assert r.rc == 0 and r.done and r.done["exitCode"] == 0, "rc=%s done=%s\nlog:%s\nstderr:%s" % (r.rc, r.done, r.log[-600:], r.stderr[-600:])

@with_env()
def version_and_empty_list(e):
    r = e.lr("--version"); assert_ok(r); assert "3.13.0" in r.log, r.log
    r = e.lr("list"); assert_ok(r); assert "Rocks installed for Lua 5.4" in r.log and "purelua" not in r.log

@with_env()
def search_uses_manifest(e):
    r = e.lr("search", "purelua"); assert_ok(r)
    assert "1.0-1 (src)" in r.log and "1.1-1 (src)" in r.log, r.log
    # LuaRocks probes the zipped manifest first, then falls back to the plain one
    assert [u.rsplit("/", 1)[1] for _, u in r.requests] == ["manifest-5.4.zip", "manifest-5.4"], r.requests

@with_env()
def install_resolves_dependencies_and_job_can_require(e):
    r = e.lr("install", "purelua", "1.0"); assert_ok(r)
    for rel in ("share/lua/5.4/purelua.lua", "share/lua/5.4/purelua/util.lua", "share/lua/5.4/helperlib.lua",
                "lib/luarocks/rocks-5.4/purelua/1.0-1/purelua-1.0-1.rockspec", "lib/luarocks/rocks-5.4/manifest"):
        assert os.path.exists(e.tree_file(rel)), "missing " + rel
    j = e.script('local p = require("purelua") return {p.answer(), p.version, require("helperlib").version}')
    assert j.status["status"] == "ok" and j.result == [42, "1.0", "1.0"], (j.status, j.result, j.stderr)

@with_env()
def install_latest_by_default(e):
    r = e.lr("install", "purelua"); assert_ok(r)
    j = e.script('return require("purelua").answer()')
    assert j.result == 43, j.result

@with_env()
def list_show_remove(e):
    assert_ok(e.lr("install", "purelua", "1.0"))
    r = e.lr("list", "--porcelain"); assert_ok(r)
    rows = [l.split("\t") for l in r.log.strip().splitlines()]
    assert ["helperlib", "1.0-1"] == rows[0][:2] and ["purelua", "1.0-1"] == rows[1][:2], rows
    r = e.lr("show", "purelua"); assert_ok(r)
    assert "purelua 1.0-1" in r.log and "purelua.util" in r.log and "helperlib" in r.log, r.log
    r = e.lr("remove", "purelua"); assert_ok(r)
    assert not os.path.exists(e.tree_file("share/lua/5.4/purelua.lua")) and os.path.exists(e.tree_file("share/lua/5.4/helperlib.lua"))
    assert_ok(e.lr("remove", "helperlib"))
    assert "purelua" not in e.lr("list", "--porcelain").log
    j = e.script('return tostring(pcall(require, "purelua"))')
    assert j.result == "false", j.result

@with_env()
def reinstall_is_idempotent(e):
    assert_ok(e.lr("install", "helperlib"))
    r = e.lr("install", "helperlib"); assert_ok(r)
    assert "already installed" in r.log.lower(), r.log

@with_env()
def path_command_points_into_tree(e):
    r = e.lr("path"); assert_ok(r)
    assert e.tree + "/share/lua/5.4/?.lua" in r.log and e.tree + "/lib/lua/5.4/?.so" in r.log, r.log

@with_env()
def native_rocks_are_rejected_with_actionable_error(e):
    for name in ("nativemod", "makerock", "needsnative"):
        r = e.lr("install", name)
        assert r.rc != 0 and r.done["errorCode"] == "NATIVE_ROCK_UNSUPPORTED", (name, r.rc, r.done, r.stderr[-400:])
        msg = r.done["errorMessage"]
        assert "pure-Lua" in msg and "native code" in msg, msg
        assert "NATIVE_ROCK_UNSUPPORTED" in r.stderr
    assert "nativemod" not in e.lr("list", "--porcelain").log and "needsnative" not in e.lr("list", "--porcelain").log
    assert not os.path.exists(e.tree_file("share/lua/5.4/needsnative.lua"))

@with_env()
def unknown_rock(e):
    r = e.lr("install", "doesnotexist")
    assert r.rc != 0 and (r.done or {}).get("errorCode") is None, (r.rc, r.done)
    assert "No results matching query" in (r.stderr + r.log), r.stderr

@with_env(allowed=())
def host_allow_list_is_enforced_by_the_host(e):
    r = e.lr("install", "helperlib")
    assert r.rc != 0, r.rc
    assert any("host not allowed" in (r.stderr + r.log) for _ in [0]), (r.log, r.stderr)
    assert not os.path.exists(e.tree_file("share/lua/5.4/helperlib.lua"))

@with_env()
def no_process_spawning_is_possible_from_a_rockspec(e):
    # A rockspec cannot run commands: build.type = "command" is replaced by a stub, and os.execute is disabled.
    marker = os.path.join(e.work, "pwned")
    evil = mockrepo.rockspec_text("evilrock", "1.0-1", build_type="command", modules={},
                                  extra_build='   build_command = "touch %s",\n   install_command = "touch %s",' % (marker, marker))
    rocks = {"evilrock": {"1.0-1": (evil, {"x.txt": "x"})}}
    e2 = Env(rocks=rocks)
    try:
        r = e2.lr("install", "evilrock")
        assert r.rc != 0 and r.done["errorCode"] == "NATIVE_ROCK_UNSUPPORTED", (r.rc, r.done, r.stderr[-300:])
        assert not os.path.exists(marker)
    finally:
        e2.close()

@with_env()
def download_size_limit(e):
    r = e.lr("install", "helperlib", max_bytes=10)
    assert r.rc != 0 and "too large" in (r.stderr + r.log), (r.log, r.stderr)

@with_env()
def md5_and_posix_modules(e):
    """The C helper modules LuaRocks relies on, checked against Python's hashlib / os."""
    import hashlib
    rt2 = os.path.join(e.work, "rt2"); os.makedirs(rt2)
    open(os.path.join(rt2, "bootstrap.lua"), "w").write('''
local md5, posix = require("md5"), require("posix")
local parts = { md5.sumhexa(""), md5.sumhexa("abc"), md5.sumhexa(string.rep("x", 1000)), md5.sumhexa(string.rep("y", 55)),
                md5.sumhexa(string.rep("y", 56)), md5.sumhexa(string.rep("y", 64)), tostring(#md5.sum("a")) }
local dir = assert(posix.mkdtemp(os.getenv("TMPDIR") .. "/pxXXXXXX"))
local f = assert(io.open(dir .. "/f", "w")) f:write("x") f:close()
assert(posix.chmod(dir .. "/f", "rwxr-x---") == 0)
parts[#parts + 1] = tostring(posix.geteuid() == tonumber(os.getenv("EXPECT_UID")))
parts[#parts + 1] = #posix.umask() == 9 and "umask-ok" or "umask-bad"
parts[#parts + 1] = dir
io.write(table.concat(parts, " "))
return 0
''')
    job = os.path.join(e.work, "job"); os.makedirs(job)
    tmp = os.path.join(e.work, "tmp"); os.makedirs(tmp)
    res = subprocess.run([os.environ.get("LUART", "build/host/luart"), "exec", "--runtime", rt2, "--job", job],
                         capture_output=True, text=True, env={"TMPDIR": tmp, "EXPECT_UID": str(os.geteuid())})
    out = res.stdout.split(" ")
    want = [hashlib.md5(x).hexdigest() for x in (b"", b"abc", b"x" * 1000, b"y" * 55, b"y" * 56, b"y" * 64)] + ["16", "true", "umask-ok"]
    assert out[:9] == want, (out, res.stderr)
    assert oct(os.stat(os.path.join(out[9], "f")).st_mode & 0o777) == "0o750", oct(os.stat(os.path.join(out[9], "f")).st_mode)

def main():
    args = sys.argv[1:]
    pattern = None
    if "-k" in args:
        i = args.index("-k"); pattern = args[i + 1]; del args[i:i + 2]
    if args:
        os.environ["LUART"] = os.path.abspath(args[0])
    passed = failed = 0
    for fn in TESTS:
        if pattern and pattern not in fn.__name__:
            continue
        t0 = time.time()
        try:
            fn(); print("  PASS  %-58s %.2fs" % (fn.__name__, time.time() - t0)); passed += 1
        except Exception as ex:
            print("  FAIL  %-58s %s" % (fn.__name__, str(ex)[:900])); failed += 1
            if os.environ.get("TRACE"): traceback.print_exc()
    print("\n%d passed, %d failed" % (passed, failed))
    sys.exit(1 if failed else 0)

if __name__ == "__main__":
    main()
