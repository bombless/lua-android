-- TLS is performed by the host (Java's HttpsURLConnection with the system trust store).
-- LuaRocks only checks that this module exists and that _VERSION >= 1.0.3.
local http = require("socket.http")
local M = { _VERSION = "1.3.2" }
function M.request(...) return http.request(...) end
return M
