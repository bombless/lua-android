package com.bombless.luaandroid.example

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.IJobCallback
import com.bombless.luaandroid.ipc.ILuaRuntimeService
import com.bombless.luaandroid.ipc.JobInfo
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.LuaRuntimeClient
import com.bombless.luaandroid.ipc.LuaRuntimeContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Caller identification, consent and job isolation (Manual.md sections 4.1 and 10). */
@RunWith(AndroidJUnit4::class)
class AccessControlTest {
    private lateinit var client: LuaRuntimeClient
    private lateinit var rt: ILuaRuntimeService
    private val ctx get() = Host.context

    @Before
    fun setUp() {
        Host.control("reset")
        client = LuaRuntimeClient(ctx)
        rt = client.bindAndWait(30_000) ?: throw AssertionError("cannot bind")
    }

    @After
    fun tearDown() {
        Host.control("reset")
        client.unbind()
    }

    @Test
    fun unknownAppsAreRejectedUntilTheOwnerApprovesAndApprovalIsRevocable() {
        Host.control("set_trust", "false") // same-signer shortcut off: this app is now "a stranger"
        val e = expectThrows<SecurityException> { rt.submit(JobRequest().apply { source = "return 1" }) }
        assertEquals(ErrorCode.UNAUTHORIZED_CALLER, ErrorCode.fromException(e))
        assertTrue(e.message!!, e.message!!.contains(ctx.packageName)) // actionable: names the app and where to approve
        // every call is guarded, not only submit()
        expectThrows<SecurityException> { rt.getJob("0".repeat(32)) }
        expectThrows<SecurityException> { rt.listJobs(10, 0) }
        expectThrows<SecurityException> { rt.runtimeInfo }

        val pending = Host.control("pending")
        assertTrue(pending.getStringArrayList("packages")!!.contains(ctx.packageName))

        Host.control("allow", ctx.packageName) // the owner taps "Allow" (pins this app's certificate)
        assertEquals(JobState.SUCCEEDED, rt.runScript("return 1").state)

        Host.control("revoke", ctx.packageName)
        expectThrows<SecurityException> { rt.submit(JobRequest().apply { source = "return 1" }) }
    }

    @Test
    fun callersCannotSeeEachOthersJobsOrArtifacts() {
        val foreign = Host.control("submit_as_owner", "local f = io.open('secret.txt', 'w') f:write('top secret') f:close() return 'secret'").getString("jobId")!!
        val mine = rt.runScript("return 'mine'")

        val noop = object : IJobCallback.Stub() { override fun onJobChanged(info: JobInfo) {} }
        val attempts: List<() -> Unit> = listOf(
            { rt.getJob(foreign) }, { rt.cancel(foreign) }, { rt.openResult(foreign, "stdout") },
            { rt.registerCallback(foreign, noop) }, { rt.unregisterCallback(foreign, noop) },
            { rt.getJob("f".repeat(32)) }, { rt.getJob("../../../etc/passwd") }, { rt.getJob("") },
        )
        for (attempt in attempts) {
            val e = expectThrows<IllegalArgumentException> { attempt() }
            // "unknown", "malformed" and "someone else's" are indistinguishable: job IDs cannot be probed
            assertEquals(e.message, ErrorCode.JOB_NOT_FOUND, ErrorCode.fromException(e))
        }
        assertTrue(rt.listJobs(100, 0).map { it.jobId } == listOf(mine.jobId))

        // nor through the content provider: no URI grant exists for someone else's job
        val uri = Uri.Builder().scheme("content").authority(LuaRuntimeContract.RESULT_AUTHORITY).appendPath(foreign).appendPath("stdout").build()
        expectThrows<SecurityException> { ctx.contentResolver.openInputStream(uri)!!.close() }
        // artifact IDs are opaque: a path-like ID is not an ID
        expectThrows<IllegalArgumentException> { rt.openResult(mine.jobId, "../../job.json") }
        expectThrows<IllegalArgumentException> { rt.openResult(mine.jobId, "f999") }
    }

    @Test
    fun packageManagementNeedsItsOwnGrant() {
        // Same-signer trust covers running scripts only. Installing packages needs an explicit grant.
        val (rocks, connection) = Host.connectRocks()
        try {
            val e = expectThrows<SecurityException> { rocks.installRock("inspect", null, null) }
            assertEquals(ErrorCode.UNAUTHORIZED_CALLER, ErrorCode.fromException(e))
            expectThrows<SecurityException> { rocks.listInstalledRocks() }
            Host.control("allow", ctx.packageName, Bundle().apply { putBoolean("rocks", true) })
            rocks.listInstalledRocks() // now permitted (the tree may be empty)
            assertTrue(rocks.luaRocksVersion.isNotEmpty())
        } finally {
            ctx.unbindService(connection)
        }
    }
}
