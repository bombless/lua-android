package com.bombless.luaandroid.ui

/** Example scripts for the Run tab. They double as documentation of the script environment. */
object Samples {
    class Sample(val title: String, val source: String)

    val all = listOf(
        Sample(
            "Hello",
            """
            -- print() goes to stdout; the returned value becomes the JSON result
            print("Hello from " .. _VERSION)
            local sum = 0
            for i = 1, 100 do sum = sum + i end
            return { sum = sum, ok = true }
            """.trimIndent(),
        ),
        Sample(
            "Files",
            """
            -- Relative paths resolve inside output/, which is returned as artifacts.
            -- input/ is read-only; tmp/ is scratch space. Nothing else is reachable.
            local f = assert(io.open("report.txt", "w"))
            for i = 1, 5 do f:write(string.format("line %d: %s\n", i, string.rep("*", i))) end
            f:close()
            print(select(2, io.open("/etc/passwd")))
            return job.output("report.txt")
            """.trimIndent(),
        ),
        Sample(
            "Environment",
            """
            -- Arguments, environment and job info supplied by the caller
            print("args:", #arg, ...)
            print("HOME visible?", os.getenv("HOME"))
            print("job id:", job.id, "lua:", job.runtime.lua, job.runtime.abi)
            return os.date("!%Y-%m-%dT%H:%M:%SZ")
            """.trimIndent(),
        ),
        Sample(
            "Coroutines",
            """
            local function primes(n)
              return coroutine.wrap(function()
                local found = 0
                local candidate = 2
                while found < n do
                  local isPrime = true
                  for d = 2, math.floor(math.sqrt(candidate)) do
                    if candidate % d == 0 then isPrime = false break end
                  end
                  if isPrime then found = found + 1 coroutine.yield(candidate) end
                  candidate = candidate + 1
                end
              end)
            end
            local list = {}
            for p in primes(15) do list[#list + 1] = p end
            return list
            """.trimIndent(),
        ),
        Sample(
            "Rocks",
            """
            -- Anything installed on the Rocks tab can be require()d.
            -- Try: install "inspect" or "lua-cjson"(native: rejected) from the Rocks tab.
            local ok, inspect = pcall(require, "inspect")
            if not ok then
              print("inspect is not installed yet:\n" .. tostring(inspect))
              return nil
            end
            print(inspect({ 1, 2, { three = 3 } }))
            """.trimIndent(),
        ),
        Sample(
            "Error",
            """
            local function fail(depth)
              if depth == 0 then error("something went wrong") end
              return fail(depth - 1)
            end
            print("about to fail")
            fail(3)
            """.trimIndent(),
        ),
        Sample("Infinite loop", "-- Killed by the deadline (try the 3 s timeout)\nprint(\"spinning...\")\nwhile true do end"),
        Sample("Memory", "-- Hits the memory limit and fails with MEMORY_LIMIT_EXCEEDED\nlocal t = {}\nfor i = 1, 1e9 do t[i] = tostring(i) end"),
    )
}
