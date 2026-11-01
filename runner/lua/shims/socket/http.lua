-- Drop-in subset of LuaSocket's socket.http, backed by the host bridge.
-- LuaRocks only uses the table form: http.request{ url=, method=, sink=, headers=, redirect=false }
-- and expects:  1, status_code, headers(lower-case keys), status_line   (or nil, message)
local ltn12 = require("ltn12")
local bridge = require("luart_bridge")

local M = { TIMEOUT = 60, USERAGENT = "luart-http/1.0", PORT = 80, _VERSION = "luart-shim 1.0" }

local function tmp_path()
  local dir = os.getenv("TMPDIR") or "/tmp"
  return string.format("%s/luart-dl-%d-%d.tmp", dir, math.random(1, 1 << 30), os.time())
end

local function request_table(req)
  if type(req.url) ~= "string" then return nil, "invalid url" end
  if req.source or req.body then return nil, "uploads are not supported" end
  local method = (req.method or "GET"):upper()
  if method ~= "GET" and method ~= "HEAD" then return nil, "unsupported method " .. method end
  local dest = method == "GET" and tmp_path() or nil
  local reply, err = bridge.call({
    op = "http", method = method, url = req.url, dest = dest,
    headers = req.headers, timeout = req.timeout or M.TIMEOUT,
  })
  if not reply then return nil, err end
  if not reply.ok then
    if dest then os.remove(dest) end
    return nil, reply.error or "request failed"
  end
  if dest and req.sink then
    local f = io.open(dest, "rb")
    if f then
      local ok, perr = ltn12.pump.all(ltn12.source.file(f), req.sink)
      if not ok then os.remove(dest) return nil, perr or "sink failed" end
    end
  end
  if dest then os.remove(dest) end
  return 1, reply.status, reply.headers or {}, reply.statusLine or ("HTTP/1.1 " .. tostring(reply.status))
end

function M.request(req, body)
  if type(req) == "string" then
    local chunks = {}
    local ok, code, headers, status = request_table({ url = req, sink = ltn12.sink.table(chunks), body = body })
    if not ok then return nil, code end
    return table.concat(chunks), code, headers, status
  end
  return request_table(req)
end

return M
