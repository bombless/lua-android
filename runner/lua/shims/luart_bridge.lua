-- Host bridge used by the LuaRocks mode.
--
-- The runner has no network stack of its own. Whenever LuaRocks needs a file it
-- sends one frame on stdout and waits for the reply on stdin:
--
--   runner -> host   "\1" .. json .. "\n"      (anything before "\1" on the line is ordinary log text)
--   host   -> runner json .. "\n"
--
-- The host (Kotlin) validates every request (scheme, host allow-list, size,
-- destination path) before doing anything.
local json = require("luarocks.vendor.dkjson")

local M = {}
local next_id = 0

function M.call(msg)
  next_id = next_id + 1
  msg.id = next_id
  io.stdout:write("\1", json.encode(msg), "\n")
  io.stdout:flush()
  local line = io.stdin:read("l")
  if not line then return nil, "host closed the bridge" end
  local reply, _, err = json.decode(line)
  if type(reply) ~= "table" then return nil, "bad reply from host: " .. tostring(err) end
  return reply
end

function M.emit(msg)
  io.stdout:write("\1", json.encode(msg), "\n")
  io.stdout:flush()
end

return M
