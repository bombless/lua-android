package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.JobState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobRecordTest {
    @Test
    fun roundTripsThroughJson() {
        val rec = JobRecord(
            id = "0123456789abcdef0123456789abcdef", state = JobState.SUCCEEDED, ownerUid = 10234,
            ownerPackages = listOf("com.example.a", "com.example.b"), createdAt = 1_700_000_000_000, scriptName = "my script.lua",
            timeoutMillis = 15_000, memoryLimitBytes = 134217728, startedAt = 1_700_000_000_100, finishedAt = 1_700_000_000_900,
            exitCode = 0, returnValueJson = "{\"a\":[1,2,\"\u00e9\"]}", stdoutBytes = 12, stderrBytes = 0, truncated = true,
            errorCode = null, errorMessage = null,
            artifacts = listOf(ArtifactRecord("stdout", "stdout.txt", "text/plain", 12, "stdout.txt"), ArtifactRecord("f1", "a/b.txt", "text/plain", 3, "output/a/b.txt")),
        )
        val back = JobRecord.fromJson(JSONObject(rec.toJson().toString()))
        assertEquals(rec, back)
        assertTrue(back.isTerminal)
    }

    @Test
    fun nullsAndNonTerminalStates() {
        val rec = JobRecord("0123456789abcdef0123456789abcdef", JobState.QUEUED, 1, listOf("p"), 5)
        val back = JobRecord.fromJson(JSONObject(rec.toJson().toString()))
        assertEquals(rec, back)
        assertNull(back.exitCode)
        assertNull(back.returnValueJson)
        assertFalse(back.isTerminal)
        assertTrue(JobRecord("x", JobState.TIMED_OUT, 1, emptyList(), 1).isTerminal)
        assertFalse(JobRecord("x", JobState.RUNNING, 1, emptyList(), 1).isTerminal)
    }
}
