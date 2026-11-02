package com.bombless.luaandroid.example

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.IJobCallback
import com.bombless.luaandroid.ipc.ILuaRuntimeService
import com.bombless.luaandroid.ipc.InputFile
import com.bombless.luaandroid.ipc.JobInfo
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.LuaRuntimeClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Acceptance tests of Manual.md section 10, run from a SECOND app (this example client, its own UID)
 * against the installed host app.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeAcceptanceTest {
    private lateinit var client: LuaRuntimeClient
    private lateinit var rt: ILuaRuntimeService
    private val ctx get() = Host.context

    @Before
    fun setUp() {
        Host.control("reset")
        client = LuaRuntimeClient(ctx)
        rt = client.bindAndWait(30_000) ?: throw AssertionError("cannot bind to the Lua Android host app")
    }

    @After
    fun tearDown() {
        client.unbind()
    }

    @Test
    fun anotherAppCanBindWithoutEmbeddingLua() {
        assertEquals(1, rt.apiVersion)
        val info = JSONObject(rt.runtimeInfo)
        assertTrue(info.getJSONObject("lua").getString("release").startsWith("Lua 5.4"))
        assertTrue(info.getJSONObject("luarocks").getString("version").isNotEmpty())
        assertTrue(info.getJSONObject("limits").getLong("maxInputFileBytes") >= 64L * 1024 * 1024)
    }

    @Test
    fun submitReturnsQuicklyAndTheJobProducesStdoutAndAJsonResult() {
        val t0 = SystemClock.elapsedRealtime()
        val id = rt.submit(JobRequest().apply { source = "print('hi') return { answer = 42, list = {1, 2, 3} }" })
        val submitMillis = SystemClock.elapsedRealtime() - t0
        assertTrue("submit() took $submitMillis ms", submitMillis < 3_000)
        val info = LuaRuntimeClient.waitForTerminal(rt, id)
        assertEquals(JobState.SUCCEEDED, info.state)
        assertEquals(0, info.exitCode)
        assertEquals("""{"answer":42,"list":[1,2,3]}""", info.returnValueJson)
        assertEquals("hi\n", LuaRuntimeClient.readArtifactText(rt, id, ApiLimits.ARTIFACT_STDOUT))
        assertEquals("", LuaRuntimeClient.readArtifactText(rt, id, ApiLimits.ARTIFACT_STDERR))
        assertNull(info.errorCode)
        assertTrue(info.createdAtMillis in 1..info.startedAtMillis && info.finishedAtMillis >= info.startedAtMillis)
        assertTrue(info.artifacts.any { it.artifactId == ApiLimits.ARTIFACT_RESULT })
    }

    @Test
    fun largeInputAndScriptTravelByDescriptorNotByBinder() {
        val size = 64L * 1024 * 1024
        val big = File(ctx.cacheDir, "big-input.bin")
        RandomAccessFile(big, "rw").use { it.setLength(size); it.seek(size - 8); it.write("TAILMARK".toByteArray()) }
        // A script larger than the inline limit, also by descriptor.
        val script = File(ctx.cacheDir, "big-script.lua")
        script.writeText("-- " + "x".repeat(ApiLimits.MAX_INLINE_SOURCE_BYTES + 4096) + "\n" +
            "local f = assert(io.open('input/big.bin', 'rb')) local n = f:seek('end') f:seek('set', n - 8) local tail = f:read(8) f:close() return { n, tail }\n")
        val id = ParcelFileDescriptor.open(big, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(script, ParcelFileDescriptor.MODE_READ_ONLY).use { scriptDescriptor ->
                rt.submit(JobRequest().apply {
                    scriptFd = scriptDescriptor
                    inputFiles = arrayOf(InputFile("big.bin", input))
                    timeoutMillis = 60_000
                })
            }
        } // descriptors are closed here: the contract says submit() has copied everything
        val info = LuaRuntimeClient.waitForTerminal(rt, id, 120_000)
        assertEquals(info.errorMessage, JobState.SUCCEEDED, info.state)
        assertEquals("[$size,\"TAILMARK\"]", info.returnValueJson)
    }

    @Test
    fun stateMachineQueuedRunningTerminalAndCancellation() {
        val workers = Host.control("max_concurrency").getInt("value")
        val long = (1..workers).map { rt.submit(JobRequest().apply { source = "while true do end"; timeoutMillis = 120_000 }) }
        waitFor(description = "workers to be busy") { long.all { rt.getJob(it).state == JobState.RUNNING } }

        val queued = rt.submit(JobRequest().apply { source = "return 1" })
        assertEquals(JobState.QUEUED, rt.getJob(queued).state)
        assertTrue(rt.cancel(queued))
        val cancelledQueued = rt.getJob(queued)
        assertEquals(JobState.CANCELLED, cancelledQueued.state)
        assertEquals(0L, cancelledQueued.startedAtMillis) // QUEUED -> CANCELLED never ran
        assertEquals(ErrorCode.EXECUTION_CANCELLED, cancelledQueued.errorCode)

        long.forEach { assertTrue(rt.cancel(it)) }
        long.forEach {
            val info = LuaRuntimeClient.waitForTerminal(rt, it, 20_000)
            assertEquals(JobState.CANCELLED, info.state)
            assertEquals(ErrorCode.EXECUTION_CANCELLED, info.errorCode)
        }
        assertTrue("cancel is idempotent", rt.cancel(queued))
        val finished = rt.runScript("return 1")
        assertFalse("cancelling a finished job reports false", rt.cancel(finished.jobId))
        assertEquals(JobState.SUCCEEDED, rt.getJob(finished.jobId).state) // terminal states are immutable
    }

    @Test
    fun timeoutKillsTheJob() {
        val started = SystemClock.elapsedRealtime()
        val info = rt.runScript("print('spin') while true do end") { timeoutMillis = 1_000 }
        assertEquals(JobState.TIMED_OUT, info.state)
        assertEquals(ErrorCode.EXECUTION_TIMEOUT, info.errorCode)
        assertTrue("took ${SystemClock.elapsedRealtime() - started} ms", SystemClock.elapsedRealtime() - started < 10_000)
        assertEquals("spin\n", LuaRuntimeClient.readArtifactText(rt, info.jobId, ApiLimits.ARTIFACT_STDOUT)) // output so far is kept
    }

    @Test
    fun scriptFailuresAreReportedNotThrown() {
        val runtime = rt.runScript("error('boom')")
        assertEquals(JobState.FAILED, runtime.state)
        assertEquals(ErrorCode.SCRIPT_RUNTIME_ERROR, runtime.errorCode)
        assertTrue(runtime.errorMessage!!.contains("boom"))
        assertTrue(LuaRuntimeClient.readArtifactText(rt, runtime.jobId, ApiLimits.ARTIFACT_STDERR).contains("stack traceback"))

        assertEquals(ErrorCode.SCRIPT_COMPILE_ERROR, rt.runScript("local = 1").errorCode)
        assertEquals(ErrorCode.RESULT_NOT_SERIALIZABLE, rt.runScript("return print").errorCode)
        val oom = rt.runScript("local t = {} for i = 1, 1e9 do t[i] = tostring(i) end") { memoryLimitBytes = 16L * 1024 * 1024 }
        assertEquals(JobState.FAILED, oom.state)
        assertEquals(ErrorCode.MEMORY_LIMIT_EXCEEDED, oom.errorCode)
    }

    @Test
    fun argumentsEnvironmentAndTheSandbox() {
        val info = rt.runScript(
            """
            return { arg[1], arg[2], os.getenv('GREETING'), tostring(os.getenv('PATH')), tostring(os.execute), tostring(io.popen),
                     select(2, io.open('/etc/passwd')), select(2, io.open('../x')), tostring(debug) }
            """.trimIndent(),
        ) {
            arguments = arrayOf("a", "b c")
            environment = android.os.Bundle().apply { putString("GREETING", "héllo \"quoted\"\n") }
        }
        assertEquals(info.errorMessage, JobState.SUCCEEDED, info.state)
        val r = org.json.JSONArray(info.returnValueJson)
        assertEquals("a", r.getString(0)); assertEquals("b c", r.getString(1))
        assertEquals("héllo \"quoted\"\n", r.getString(2))
        assertEquals("nil", r.getString(3)); assertEquals("nil", r.getString(4)); assertEquals("nil", r.getString(5))
        assertTrue(r.getString(6).contains("absolute paths")); assertTrue(r.getString(7).contains("'..'")); assertEquals("nil", r.getString(8))
    }

    @Test
    fun generatedFilesAreArtifactsReadableThroughDescriptorAndContentUri() {
        val info = rt.runScript("local f = assert(io.open('hello.txt', 'w')) f:write('hello artifact') f:close() return 1")
        assertEquals(JobState.SUCCEEDED, info.state)
        val file = info.artifacts.first { it.displayName == "hello.txt" }
        assertEquals(14L, file.sizeBytes)
        assertEquals("hello artifact", LuaRuntimeClient.readArtifactText(rt, info.jobId, file.artifactId))
        val uri: Uri = file.contentUri ?: throw AssertionError("no content URI")
        assertEquals("hello artifact", ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        ctx.contentResolver.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hello.txt", c.getString(c.getColumnIndexOrThrow("_display_name")))
            assertEquals(14L, c.getLong(c.getColumnIndexOrThrow("_size")))
        }
        expectThrows<Exception> { ctx.contentResolver.openOutputStream(uri)!!.close() } // read-only
    }

    @Test
    fun callbacksReportMonotonicStateChanges() {
        val seen = CopyOnWriteArrayList<Int>()
        val done = CountDownLatch(1)
        val callback = object : IJobCallback.Stub() {
            override fun onJobChanged(info: JobInfo) {
                seen += info.state
                if (info.isTerminal) done.countDown()
            }
        }
        val id = rt.submit(JobRequest().apply { source = "local t = os.clock() while os.clock() - t < 0.3 do end return 'done'" })
        rt.registerCallback(id, callback)
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals(JobState.SUCCEEDED, seen.last())
        assertEquals(seen.sorted(), seen.toList())
        rt.unregisterCallback(id, callback)
    }

    @Test
    fun invalidRequestsAreRejectedBeforeAnythingIsStaged() {
        fun rejected(code: String, configure: JobRequest.() -> Unit) {
            val e = expectThrows<IllegalArgumentException> { rt.submit(JobRequest().apply { source = "return 1"; configure() }) }
            assertEquals(e.message, code, ErrorCode.fromException(e))
        }
        val tmp = File(ctx.cacheDir, "tiny.txt").also { it.writeText("x") }
        fun input(name: String) = arrayOf(InputFile(name, ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)))
        rejected(ErrorCode.UNSUPPORTED_API_VERSION) { apiVersion = 99 }
        rejected(ErrorCode.INVALID_REQUEST) { source = null }
        rejected(ErrorCode.INVALID_REQUEST) { scriptName = "a/b.lua" }
        rejected(ErrorCode.INPUT_TOO_LARGE) { arguments = Array(ApiLimits.MAX_ARGUMENTS + 1) { "x" } }
        rejected(ErrorCode.INPUT_TOO_LARGE) { source = "-- " + "x".repeat(ApiLimits.MAX_INLINE_SOURCE_BYTES + 1) }
        rejected(ErrorCode.INVALID_REQUEST) { environment = android.os.Bundle().apply { putString("bad key", "v") } }
        rejected(ErrorCode.INVALID_REQUEST) { environment = android.os.Bundle().apply { putString("A=B", "v") } }
        for (wd in listOf("../..", "/etc", "output/../input", "elsewhere", "output//x", "output/./x")) rejected(ErrorCode.INVALID_REQUEST) { workingDirectory = wd }
        for (name in listOf("../x", "a/b", ".hidden", "", "x\u0000y", "a b")) rejected(ErrorCode.INVALID_REQUEST) { inputFiles = input(name) }
        rejected(ErrorCode.INVALID_REQUEST) { inputFiles = input("dup") + input("dup") }
        // A pipe is not a regular file: it could block a Binder thread forever.
        val pipe = ParcelFileDescriptor.createPipe()
        rejected(ErrorCode.INVALID_REQUEST) { inputFiles = arrayOf(InputFile("p", pipe[0])) }
        pipe[0].close()
        pipe[1].close()
    }

    @Test
    fun perCallerQueueLimit() {
        val ids = ArrayList<String>()
        try {
            repeat(16) { ids += rt.submit(JobRequest().apply { source = "while true do end"; timeoutMillis = 120_000 }) }
            val e = expectThrows<IllegalStateException> { rt.submit(JobRequest().apply { source = "return 1" }) }
            assertEquals(ErrorCode.QUEUE_FULL, ErrorCode.fromException(e))
        } finally {
            ids.forEach { rt.cancel(it) }
        }
        ids.forEach { assertTrue(LuaRuntimeClient.waitForTerminal(rt, it, 30_000).isTerminal) }
    }

    @Test
    fun jobsSurviveTheDeathOfTheHostProcess() {
        val running = rt.submit(JobRequest().apply { source = "while true do end"; timeoutMillis = 120_000 })
        waitFor(description = "job to run") { rt.getJob(running).state == JobState.RUNNING }
        val finished = rt.runScript("return 'before'")
        assertEquals(JobState.SUCCEEDED, finished.state)

        client.unbind()
        Host.shell("am force-stop com.bombless.luaandroid") // kills the host and the runner processes
        client = LuaRuntimeClient(ctx)
        rt = client.bindAndWait(60_000) ?: throw AssertionError("host did not come back")

        val after = rt.getJob(running)
        assertEquals(JobState.FAILED, after.state)
        assertEquals(ErrorCode.INTERNAL_ERROR, after.errorCode)
        assertTrue(after.errorMessage!!, after.errorMessage!!.contains("restarted"))
        assertEquals("\"before\"", rt.getJob(finished.jobId).returnValueJson) // finished jobs are durable
        assertEquals(JobState.SUCCEEDED, rt.runScript("return 1").state) // and the service accepts new work
    }

    @Test
    fun finishedJobsListIsOrderedAndLimited() {
        val ids = (1..3).map { rt.runScript("return $it").jobId }
        val listed = rt.listJobs(2, 0)
        assertEquals(2, listed.size)
        assertEquals(ids[2], listed[0].jobId)
        val older = rt.listJobs(10, listed.last().createdAtMillis)
        assertTrue(older.all { it.createdAtMillis < listed.last().createdAtMillis })
        assertNotNull(older.firstOrNull { it.jobId == ids[0] })
    }
}
