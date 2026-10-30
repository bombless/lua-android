package com.bombless.luaandroid.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaExecutorTest {
    private fun execute(source: String): LuaExecutor.Result {
        val dir = Files.createTempDirectory("lua-android-test").toFile()
        FileCompat.ensureDirectory(dir, "input")
        FileCompat.ensureDirectory(dir, "output")
        val script = java.io.File(dir, "script.lua").apply { writeText(source) }
        return try {
            LuaExecutor().execute(script, dir, emptyArray(), emptyMap(), 5_000) { _, _ -> }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun preservesAllTopLevelReturnValues() {
        val result = execute("return 7, 'lua', true")
        assertEquals(null, result.errorCode)
        assertEquals("[7,\"lua\",true]", result.json)
    }

    @Test fun reportsCyclicTableSerializationAsAnError() {
        val result = execute("local value = {}; value.self = value; return value")
        assertEquals("RESULT_SERIALIZATION_ERROR", result.errorCode)
    }

    @Test fun reportsOutputTruncation() {
        val result = execute("print(string.rep('x', 1024 * 1024 + 1))")
        assertEquals(null, result.errorCode)
        assertTrue(result.truncated)
    }
}

private object FileCompat {
    fun ensureDirectory(parent: java.io.File, name: String) {
        check(java.io.File(parent, name).mkdirs())
    }
}
