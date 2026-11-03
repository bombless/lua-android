"""Python stand-in for the Kotlin host side of `luart rocks` (bridge protocol of shims/luart_bridge.lua)."""
import http.client, json, os, subprocess, tempfile, threading, urllib.parse
import http.server, socketserver, functools

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass

def serve(directory):
    handler = functools.partial(QuietHandler, directory=directory)
    srv = socketserver.ThreadingTCPServer(("127.0.0.1", 0), handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]

def stage_runtime(dest):
    subprocess.check_call([os.path.join(REPO_ROOT, "scripts", "stage-runtime.sh"), dest])

class RocksResult:
    def __init__(self): self.log, self.stderr, self.done, self.requests, self.rc = "", "", None, [], None

def run_rocks(args, *, root, tree, work, servers, allowed_hosts=("127.0.0.1",), binary=None, timeout=120, max_bytes=64 << 20, extra_env=None, timeout_ms=100000):
    binary = binary or os.environ.get("LUART", os.path.join(HERE, "..", "build", "host", "luart"))
    home, tmp, conf = (os.path.join(work, d) for d in ("home", "tmp", "etc"))
    for d in (home, tmp, conf): os.makedirs(d, exist_ok=True)
    env = {"PATH": "/nonexistent", "HOME": home, "TMPDIR": tmp, "LUART_TREE": tree, "LUART_CONF_DIR": conf, "LUART_SERVERS": " ".join(servers)}
    env.update(extra_env or {})
    p = subprocess.Popen([binary, "rocks", "--runtime", os.path.join(root, "runtime"), "--luarocks", os.path.join(root, "luarocks"),
                          "--timeout-ms", str(timeout_ms), "--"] + list(args), stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, cwd=work)
    res = RocksResult()
    err_chunks = []
    t = threading.Thread(target=lambda: err_chunks.append(p.stderr.read()), daemon=True); t.start()
    log = []
    for raw in p.stdout:
        line = raw.decode("utf-8", "replace")
        if "\x01" in line:
            before, frame = line.split("\x01", 1)
            log.append(before)
            msg = json.loads(frame)
            if msg["op"] == "done":
                res.done = msg
            elif msg["op"] == "http":
                res.requests.append((msg["method"], msg["url"]))
                reply = handle_http(msg, allowed_hosts, tmp, max_bytes)
                reply["id"] = msg["id"]
                p.stdin.write((json.dumps(reply) + "\n").encode()); p.stdin.flush()
        else:
            log.append(line)
    res.rc = p.wait(timeout=timeout); t.join(5)
    res.log = "".join(log); res.stderr = (err_chunks[0] if err_chunks else b"").decode("utf-8", "replace")
    return res

def handle_http(msg, allowed_hosts, tmp, max_bytes):
    """Mirrors the validation the Kotlin host performs."""
    url = urllib.parse.urlsplit(msg["url"])
    if url.scheme not in ("http", "https"):
        return {"ok": False, "error": "unsupported scheme"}
    if url.hostname not in allowed_hosts:
        return {"ok": False, "error": "host not allowed: %s" % url.hostname}
    dest = msg.get("dest")
    if dest and os.path.dirname(os.path.abspath(dest)) != os.path.abspath(tmp):
        return {"ok": False, "error": "destination outside the temp directory"}
    conn = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=20)
    try:
        conn.request(msg["method"], (url.path or "/") + ("?" + url.query if url.query else ""), headers=msg.get("headers") or {})
        r = conn.getresponse()
        headers = {k.lower(): v for k, v in r.getheaders()}
        status_line = "HTTP/1.1 %d %s" % (r.status, r.reason)
        if dest:
            body = r.read(max_bytes + 1) if r.status == 200 else b""
            if len(body) > max_bytes:
                return {"ok": False, "error": "download too large"}
            with open(dest, "wb") as f: f.write(body)
        return {"ok": True, "status": r.status, "statusLine": status_line, "headers": headers}
    except Exception as e:
        return {"ok": False, "error": str(e)}
    finally:
        conn.close()
