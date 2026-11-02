package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResultCollectorTest {
    private val base = JobRecord("0123456789abcdef0123456789abcdef", JobState.RUNNING, 1, listOf("p"), 100, timeoutMillis = 2_000, startedAt = 110)

    private fun job(configure: File.() -> Unit): File {
        val dir = Files.createTempDirectory("job").toFile()
        File(dir, "output").mkdirs()
        dir.configure()
        return dir
    }

    private fun File.write(name: String, text: String) { File(this, name).also { it.parentFile?.mkdirs() }.writeText(text) }

    @Test
    fun successWithInlineResultAndGeneratedFiles() {
        val dir = job {
            write("stdout.txt", "hi\n"); write("stderr.txt", "")
            write("result.json", "{\"a\":1}")
            write("status.json", """{"status":"ok","exitCode":0,"truncated":false}""")
            write("output/b.txt", "bb"); write("output/a.txt", "a"); write("output/sub/c.csv", "1,2")
        }
        val r = ResultCollector.collect(base, dir, RunOutcome(0), 500)
        assertEquals(JobState.SUCCEEDED, r.state)
        assertEquals(0, r.exitCode)
        assertEquals("{\"a\":1}", r.returnValueJson)
        assertNull(r.errorCode)
        assertEquals(500L, r.finishedAt)
        assertEquals(3L, r.stdoutBytes)
        val ids = r.artifacts.map { it.id }
        assertEquals(listOf("stdout", "stderr", "result", "f1", "f2", "f3"), ids)
        assertEquals(listOf("a.txt", "b.txt", "sub/c.csv"), r.artifacts.drop(3).map { it.displayName })
        assertEquals(listOf("output/a.txt", "output/b.txt", "output/sub/c.csv"), r.artifacts.drop(3).map { it.path })
        assertEquals("text/csv", r.artifacts.last().mimeType)
        assertEquals("application/json", r.artifacts.first { it.id == ApiLimits.ARTIFACT_RESULT }.mimeType)
    }

    @Test
    fun largeResultIsOnlyAnArtifact() {
        val big = "[" + "1,".repeat(ApiLimits.MAX_INLINE_RESULT_BYTES) + "1]"
        val dir = job { write("result.json", big); write("status.json", """{"status":"ok","exitCode":0}""") }
        val r = ResultCollector.collect(base, dir, RunOutcome(0), 1)
        assertNull(r.returnValueJson)
        assertEquals(big.length.toLong(), r.artifacts.first { it.id == ApiLimits.ARTIFACT_RESULT }.sizeBytes)
    }

    @Test
    fun scriptErrorsKeepTheRunnersCodeAndMessage() {
        val dir = job {
            write("status.json", """{"status":"error","exitCode":1,"errorCode":"SCRIPT_COMPILE_ERROR","errorMessage":"script.lua:1: unexpected symbol"}""")
            write("result.json", "1") // a result of a failed job is not exposed
        }
        val r = ResultCollector.collect(base, dir, RunOutcome(1), 1)
        assertEquals(JobState.FAILED, r.state)
        assertEquals(ErrorCode.SCRIPT_COMPILE_ERROR, r.errorCode)
        assertEquals("script.lua:1: unexpected symbol", r.errorMessage)
        assertNull(r.returnValueJson)
        assertFalse(r.artifacts.any { it.id == ApiLimits.ARTIFACT_RESULT })
    }

    @Test
    fun runnerReportedTimeoutMemoryAndCancel() {
        fun collect(status: String, outcome: RunOutcome = RunOutcome(0)) = ResultCollector.collect(base, job { write("status.json", status) }, outcome, 1)
        collect("""{"status":"timeout","exitCode":124,"errorCode":"EXECUTION_TIMEOUT","errorMessage":"Execution deadline exceeded"}""").let {
            assertEquals(JobState.TIMED_OUT, it.state); assertEquals(ErrorCode.EXECUTION_TIMEOUT, it.errorCode); assertEquals(124, it.exitCode)
        }
        collect("""{"status":"memory","exitCode":137,"errorCode":"MEMORY_LIMIT_EXCEEDED","errorMessage":"limit"}""").let {
            assertEquals(JobState.FAILED, it.state); assertEquals(ErrorCode.MEMORY_LIMIT_EXCEEDED, it.errorCode)
        }
        collect("""{"status":"cancelled","exitCode":143}""").let { assertEquals(JobState.CANCELLED, it.state); assertEquals(ErrorCode.EXECUTION_CANCELLED, it.errorCode) }
        collect("""{"status":"error","exitCode":3,"errorMessage":"script exited with status 3"}""").let {
            assertEquals(JobState.FAILED, it.state); assertEquals(ErrorCode.SCRIPT_RUNTIME_ERROR, it.errorCode); assertEquals(3, it.exitCode)
        }
    }

    @Test
    fun hostSideKillsWinOverWhateverTheRunnerWrote() {
        val ok = """{"status":"ok","exitCode":0}"""
        ResultCollector.collect(base, job { write("status.json", ok) }, RunOutcome(143, KillReason.CANCEL), 1).let {
            assertEquals(JobState.CANCELLED, it.state); assertEquals(ErrorCode.EXECUTION_CANCELLED, it.errorCode)
        }
        ResultCollector.collect(base, job { }, RunOutcome(137, KillReason.DEADLINE), 1).let {
            assertEquals(JobState.TIMED_OUT, it.state); assertTrue(it.errorMessage!!.contains("2000"))
        }
        ResultCollector.collect(base, job { }, RunOutcome(137, KillReason.QUOTA), 1).let {
            assertEquals(JobState.FAILED, it.state); assertEquals(ErrorCode.RESULT_TOO_LARGE, it.errorCode)
        }
    }

    @Test
    fun crashesAndStartFailures() {
        ResultCollector.collect(base, job { }, RunOutcome(139), 1).let {
            assertEquals(JobState.FAILED, it.state); assertEquals(ErrorCode.INTERNAL_ERROR, it.errorCode); assertTrue(it.errorMessage!!.contains("139"))
        }
        ResultCollector.collect(base, job { write("status.json", "not json") }, RunOutcome(0), 1).let { assertEquals(ErrorCode.INTERNAL_ERROR, it.errorCode) }
        ResultCollector.collect(base, job { }, RunOutcome(-1, startFailure = "no runner"), 1).let { assertEquals("no runner", it.errorMessage) }
        ResultCollector.collect(base, job { }, RunOutcome(-1), 1, interruptedMessage = "restarted").let { assertEquals("restarted", it.errorMessage) }
    }

    @Test
    fun outputFileCountIsCappedAndSymlinksAreIgnored() {
        val dir = job {
            for (i in 1..(Limits.MAX_OUTPUT_FILES + 10)) write("output/f%03d.txt".format(i), "x")
            write("status.json", """{"status":"ok","exitCode":0}""")
            try { Files.createSymbolicLink(File(this, "output/zzz-link").toPath(), File("/etc/passwd").toPath()) } catch (_: Exception) {}
        }
        val files = ResultCollector.collect(base, dir, RunOutcome(0), 1).artifacts.filter { it.id.startsWith("f") }
        assertEquals(Limits.MAX_OUTPUT_FILES, files.size)
        assertFalse(files.any { it.displayName.contains("link") })
    }
}
