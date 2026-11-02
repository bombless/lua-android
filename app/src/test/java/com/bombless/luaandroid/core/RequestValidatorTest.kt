package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestValidatorTest {
    private fun validate(
        apiVersion: Int = 1,
        source: String? = "return 1",
        hasScriptFd: Boolean = false,
        scriptName: String? = null,
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        workingDirectory: String? = null,
        timeoutMillis: Long = 5_000,
        memoryLimitBytes: Long = 0,
        inputNames: List<String> = emptyList(),
        maxTimeoutMillis: Long = ApiLimits.MAX_TIMEOUT_MILLIS,
    ) = RequestValidator.validate(apiVersion, source, hasScriptFd, scriptName, arguments, environment, workingDirectory, timeoutMillis, memoryLimitBytes, inputNames, maxTimeoutMillis)

    private fun rejected(code: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ServiceError) {
            assertEquals(e.message, code, e.code)
            assertTrue("message starts with the code: ${e.message}", e.message!!.startsWith("$code: "))
            return
        }
        throw AssertionError("expected $code")
    }

    @Test
    fun minimalRequestGetsDefaults() {
        val v = validate()
        assertEquals("script.lua", v.scriptName)
        assertEquals("output", v.workingDirectory)
        assertEquals(5_000L, v.timeoutMillis)
        assertEquals(ApiLimits.DEFAULT_MEMORY_LIMIT_BYTES, v.memoryLimitBytes)
    }

    @Test
    fun timeoutAndMemoryAreClampedNotRejected() {
        assertEquals(ApiLimits.DEFAULT_TIMEOUT_MILLIS, validate(timeoutMillis = 0).timeoutMillis)
        assertEquals(ApiLimits.DEFAULT_TIMEOUT_MILLIS, validate(timeoutMillis = -5).timeoutMillis)
        assertEquals(ApiLimits.MIN_TIMEOUT_MILLIS, validate(timeoutMillis = 1).timeoutMillis)
        assertEquals(ApiLimits.MAX_TIMEOUT_MILLIS, validate(timeoutMillis = Long.MAX_VALUE).timeoutMillis)
        assertEquals(60_000L, validate(timeoutMillis = Long.MAX_VALUE, maxTimeoutMillis = 60_000).timeoutMillis) // owner-configured ceiling
        assertEquals(ApiLimits.MIN_MEMORY_LIMIT_BYTES, validate(memoryLimitBytes = 1).memoryLimitBytes)
        assertEquals(ApiLimits.MAX_MEMORY_LIMIT_BYTES, validate(memoryLimitBytes = Long.MAX_VALUE).memoryLimitBytes)
        assertEquals(64L * 1024 * 1024, validate(memoryLimitBytes = 64L * 1024 * 1024).memoryLimitBytes)
    }

    @Test
    fun apiVersion() {
        rejected(ErrorCode.UNSUPPORTED_API_VERSION) { validate(apiVersion = 0) }
        rejected(ErrorCode.UNSUPPORTED_API_VERSION) { validate(apiVersion = 2) }
        rejected(ErrorCode.UNSUPPORTED_API_VERSION) { validate(apiVersion = -1) }
    }

    @Test
    fun exactlyOneScriptSource() {
        rejected(ErrorCode.INVALID_REQUEST) { validate(source = null, hasScriptFd = false) }
        rejected(ErrorCode.INVALID_REQUEST) { validate(source = "x", hasScriptFd = true) }
        validate(source = null, hasScriptFd = true)
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(source = "x".repeat(ApiLimits.MAX_INLINE_SOURCE_BYTES + 1)) }
        validate(source = "x".repeat(ApiLimits.MAX_INLINE_SOURCE_BYTES))
        // the limit is in UTF-8 bytes, not characters
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(source = "\u00e9".repeat(ApiLimits.MAX_INLINE_SOURCE_BYTES / 2 + 1)) }
    }

    @Test
    fun argumentsAndEnvironmentAreBounded() {
        validate(arguments = List(ApiLimits.MAX_ARGUMENTS) { "a" })
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(arguments = List(ApiLimits.MAX_ARGUMENTS + 1) { "a" }) }
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(arguments = listOf("x".repeat(ApiLimits.MAX_ARGUMENT_BYTES + 1))) }
        validate(environment = (1..ApiLimits.MAX_ENVIRONMENT_ENTRIES).associate { "K$it" to "v" })
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(environment = (1..ApiLimits.MAX_ENVIRONMENT_ENTRIES + 1).associate { "K$it" to "v" }) }
        rejected(ErrorCode.INVALID_REQUEST) { validate(environment = mapOf("bad key" to "v")) }
        rejected(ErrorCode.INVALID_REQUEST) { validate(environment = mapOf("A=B" to "v")) }
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(environment = mapOf("K" to "x".repeat(ApiLimits.MAX_ENVIRONMENT_VALUE_BYTES + 1))) }
    }

    @Test
    fun namesAndPaths() {
        rejected(ErrorCode.INVALID_REQUEST) { validate(scriptName = "a/b.lua") }
        rejected(ErrorCode.INVALID_REQUEST) { validate(workingDirectory = "../..") }
        rejected(ErrorCode.INVALID_REQUEST) { validate(inputNames = listOf("../x")) }
        rejected(ErrorCode.INVALID_REQUEST) { validate(inputNames = listOf("a", "a")) }
        rejected(ErrorCode.INPUT_TOO_LARGE) { validate(inputNames = List(ApiLimits.MAX_INPUT_FILES + 1) { "f$it" }) }
        assertEquals(listOf("a", "b.txt"), validate(inputNames = listOf("a", "b.txt")).inputNames)
        assertEquals("tmp/x", validate(workingDirectory = "tmp/x").workingDirectory)
    }
}
