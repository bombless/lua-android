package com.bombless.luaandroid.ipc

import com.bombless.luaandroid.core.Ids
import com.bombless.luaandroid.core.ServiceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The published constants and helpers are part of the contract: changing them is a breaking change. */
class ApiContractTest {
    @Test
    fun stateNumbersAreStable() {
        assertEquals(0, JobState.QUEUED); assertEquals(1, JobState.RUNNING); assertEquals(2, JobState.SUCCEEDED)
        assertEquals(3, JobState.FAILED); assertEquals(4, JobState.CANCELLED); assertEquals(5, JobState.TIMED_OUT)
        assertFalse(JobState.isTerminal(JobState.QUEUED)); assertFalse(JobState.isTerminal(JobState.RUNNING))
        for (s in listOf(2, 3, 4, 5, 6, 100)) assertTrue("unknown future states count as terminal", JobState.isTerminal(s))
        assertEquals("TIMED_OUT", JobState.name(5)); assertEquals("UNKNOWN(42)", JobState.name(42))
    }

    @Test
    fun publishedIdentifiers() {
        assertEquals("com.bombless.luaandroid", LuaRuntimeContract.HOST_PACKAGE)
        assertEquals("com.bombless.luaandroid.action.BIND_RUNTIME", LuaRuntimeContract.ACTION_BIND_RUNTIME)
        assertEquals("com.bombless.luaandroid.action.BIND_ROCKS", LuaRuntimeContract.ACTION_BIND_ROCKS)
        assertEquals("com.bombless.luaandroid.permission.USE_LUA_RUNTIME", LuaRuntimeContract.PERMISSION_USE)
        assertEquals("com.bombless.luaandroid.results", LuaRuntimeContract.RESULT_AUTHORITY)
        assertEquals(1, LuaRuntimeContract.API_VERSION)
    }

    @Test
    fun errorCodesRoundTripThroughExceptionMessages() {
        assertEquals("INVALID_REQUEST", ErrorCode.fromMessage("INVALID_REQUEST: bad thing"))
        assertEquals("QUEUE_FULL", ErrorCode.fromException(IllegalStateException("QUEUE_FULL: busy")))
        assertNull(ErrorCode.fromMessage("no code here"))
        assertNull(ErrorCode.fromMessage("lowercase: x"))
        assertNull(ErrorCode.fromMessage(": x"))
        assertNull(ErrorCode.fromMessage(null))
        assertEquals("bad thing", ErrorCode.detail(IllegalArgumentException("INVALID_REQUEST: bad thing")))
        assertEquals("plain", ErrorCode.detail(RuntimeException("plain")))
    }

    @Test
    fun serviceErrorsMapToBinderSafeExceptionsKeepingTheCode() {
        val cases = mapOf(
            ErrorCode.UNAUTHORIZED_CALLER to SecurityException::class.java,
            ErrorCode.QUEUE_FULL to IllegalStateException::class.java,
            ErrorCode.INTERNAL_ERROR to IllegalStateException::class.java,
            ErrorCode.INVALID_REQUEST to IllegalArgumentException::class.java,
            ErrorCode.INPUT_TOO_LARGE to IllegalArgumentException::class.java,
            ErrorCode.JOB_NOT_FOUND to IllegalArgumentException::class.java,
            ErrorCode.UNSUPPORTED_API_VERSION to IllegalArgumentException::class.java,
        )
        for ((code, type) in cases) {
            val e = ServiceError(code, "detail").toBinderException()
            assertEquals(code, type, e.javaClass)
            assertEquals(code, ErrorCode.fromException(e))
            assertEquals("detail", ErrorCode.detail(e))
        }
    }

    @Test
    fun identifierValidation() {
        assertTrue(Ids.isJobId(Ids.newJobId()))
        assertTrue(Ids.isOperationId(Ids.newOperationId()))
        assertEquals(32, Ids.newJobId().length)
        assertFalse(Ids.newJobId() == Ids.newJobId())
        for (bad in listOf(null, "", "../..", "0".repeat(31), "0".repeat(33), "G".repeat(32), "a/".repeat(16), "A".repeat(32))) assertFalse("$bad", Ids.isJobId(bad))
        for (ok in listOf("stdout", "stderr", "result", "f1", "f64", "f0001")) assertTrue(ok, Ids.isArtifactId(ok))
        for (bad in listOf(null, "", "stdout.txt", "../job.json", "f", "fx", "f12345", "F1", "result/..")) assertFalse("$bad", Ids.isArtifactId(bad))
    }

    @Test
    fun limitsAreSane() {
        assertTrue(ApiLimits.MIN_TIMEOUT_MILLIS < ApiLimits.DEFAULT_TIMEOUT_MILLIS && ApiLimits.DEFAULT_TIMEOUT_MILLIS < ApiLimits.MAX_TIMEOUT_MILLIS)
        assertTrue(ApiLimits.MIN_MEMORY_LIMIT_BYTES < ApiLimits.DEFAULT_MEMORY_LIMIT_BYTES && ApiLimits.DEFAULT_MEMORY_LIMIT_BYTES < ApiLimits.MAX_MEMORY_LIMIT_BYTES)
        assertTrue("inline results must fit comfortably in one Binder transaction", ApiLimits.MAX_INLINE_RESULT_BYTES * 2 < 512 * 1024)
    }
}
