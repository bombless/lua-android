"""Shared helpers for driving the luart runner from tests (mirrors what the Kotlin host does)."""
import json, os, shutil, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
RUNTIME = os.path.join(HERE, "..", "lua")

def lua_quote(s):
    """Quote a str/bytes as a Lua string literal using only safe ASCII + \\ddd escapes (same as the Kotlin host)."""
    b = s.encode() if isinstance(s, str) else s
    out = ['"']
    for c in b:
        if (48 <= c <= 57) or (65 <= c <= 90) or (97 <= c <= 122) or chr(c) in " _./:-":
            out.append(chr(c))
        else:
            out.append("\\%03d" % c)
    out.append('"')
    return "".join(out)

def write_spec(job, job_id="test-job", script_name="script.lua", args=(), env=None, workdir=""):
    lines = ["return {", "  apiVersion = 1,", "  jobId = %s," % lua_quote(job_id), "  scriptName = %s," % lua_quote(script_name)]
    lines.append("  workdir = %s," % lua_quote(workdir))
    lines.append("  args = {%s}," % ", ".join(lua_quote(a) for a in args))
    lines.append("  env = {%s}," % ", ".join("[%s] = %s" % (lua_quote(k), lua_quote(v)) for k, v in (env or {}).items()))
    lines.append("}")
    with open(os.path.join(job, "spec.lua"), "w") as f:
        f.write("\n".join(lines) + "\n")

class Result:
    def __init__(self, job, rc, wall, killed):
        self.job, self.rc, self.wall, self.killed = job, rc, wall, killed
        def rd(name):
            p = os.path.join(job, name)
            return open(p, "rb").read() if os.path.exists(p) else None
        self.stdout = (rd("stdout.txt") or b"").decode("utf-8", "replace")
        self.stderr = (rd("stderr.txt") or b"").decode("utf-8", "replace")
        st = rd("status.json")
        self.status = json.loads(st) if st else None
        rj = rd("result.json")
        self.result_text = rj.decode() if rj is not None else None
        self.result = json.loads(rj) if rj else None
    def out_file(self, name):
        p = os.path.join(self.job, "output", name)
        return open(p, "rb").read() if os.path.exists(p) else None

def run(script, *, args=(), env=None, inputs=None, timeout_ms=5000, mem=128 << 20, out=1 << 20, result=1 << 20,
        fsize=64 << 20, workdir="", rocks=None, runtime=RUNTIME, binary=None, wall=20, term_after=None,
        script_bytes=None, keep=False, setup=None):
    binary = binary or os.environ.get("LUART", os.path.join(HERE, "..", "build", "host", "luart"))
    job = tempfile.mkdtemp(prefix="luart-job-")
    for d in ("input", "output", "tmp"):
        os.makedirs(os.path.join(job, d))
    with open(os.path.join(job, "script.lua"), "wb") as f:
        f.write(script_bytes if script_bytes is not None else script.encode())
    for name, data in (inputs or {}).items():
        with open(os.path.join(job, "input", name), "wb") as f:
            f.write(data if isinstance(data, bytes) else data.encode())
    if workdir:
        os.makedirs(os.path.join(job, workdir), exist_ok=True)
    write_spec(job, args=args, env=env, workdir=workdir)
    if setup:
        setup(job)
    cmd = [binary, "exec", "--runtime", runtime, "--job", job, "--timeout-ms", str(timeout_ms), "--mem-bytes", str(mem),
           "--out-bytes", str(out), "--result-bytes", str(result), "--fsize-bytes", str(fsize)]
    if rocks:
        cmd += ["--rocks", rocks]
    t0 = time.time()
    killed = False
    with open(os.path.join(job, "stdout.txt"), "wb") as so, open(os.path.join(job, "stderr.txt"), "wb") as se:
        p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=so, stderr=se, cwd=job)
        try:
            if term_after is not None:
                time.sleep(term_after)
                p.terminate()
            rc = p.wait(timeout=wall)
        except subprocess.TimeoutExpired:
            p.kill(); rc = p.wait(); killed = True
    r = Result(job, rc, time.time() - t0, killed)
    if not keep:
        r.cleanup = lambda: shutil.rmtree(job, ignore_errors=True)
    return r
