package com.bombless.luaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LuaLiteralTest {
    /** Decodes what Lua would read: plain characters and \ddd decimal escapes. */
    private fun decode(literal: String): List<Int> {
        assertTrue(literal.startsWith("\"") && literal.endsWith("\""))
        val body = literal.substring(1, literal.length - 1)
        val out = ArrayList<Int>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') {
                out += body.substring(i + 1, i + 4).toInt()
                i += 4
            } else {
                out += c.code
                i++
            }
        }
        return out
    }

    @Test
    fun quotingIsPlainAsciiAndExactlyReversible() {
        val samples = listOf("", "plain text-1.0/x:y", "\"quote\"", "back\\slash", "new\nline\r\n", "tab\t", "\u0000nul", "\u00e9\u4e2d\u6587\ud83d\ude00",
            "]]--[[", "'single'", "%s%d", "\\\"", "a".repeat(5000))
        for (s in samples) {
            val q = LuaLiteral.quote(s)
            assertTrue("only printable ASCII: $q", q.all { it.code in 32..126 })
            assertTrue("no raw quote/backslash inside the body", q.substring(1, q.length - 1).none { it == '"' } )
            assertEquals(s.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }, decode(q))
        }
    }

    @Test
    fun specMatchesTheGoldenFileUsedByTheRunnerTests() {
        // runner/tests/golden/spec.lua is produced by the Python harness (runner/tests/harness.py) and is
        // accepted by the real runner there; byte equality ties the Kotlin generator to that format.
        val golden = File("../runner/tests/golden/spec.lua").readText()
        val spec = LuaLiteral.spec(
            jobId = "0123456789abcdef0123456789abcdef",
            scriptName = "my script \u00e9.lua",
            args = listOf("a b", "\"q\"\\", "\u00e9\n\u0000", ""),
            env = linkedMapOf("K" to "v\\", "EMPTY" to "", "Z_9" to "line1\nline2"),
            workingDirectory = "output/sub",
        )
        assertEquals(golden, spec)
    }
}
