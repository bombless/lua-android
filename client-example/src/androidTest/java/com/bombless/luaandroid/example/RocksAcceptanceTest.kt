package com.bombless.luaandroid.example

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.ILuaRocksService
import com.bombless.luaandroid.ipc.ILuaRuntimeService
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.LuaRuntimeClient
import com.bombless.luaandroid.ipc.RockOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LuaRocks acceptance (Manual.md section 10, tests 9 and 10): a pure-Lua rock installs into the private tree
 * and can be require()d by a script of another app; native rocks fail with an actionable error.
 */
@RunWith(AndroidJUnit4::class)
class RocksAcceptanceTest {
    private lateinit var client: LuaRuntimeClient
    private lateinit var rt: ILuaRuntimeService
    private lateinit var rocks: ILuaRocksService
    private lateinit var connection: android.content.ServiceConnection
    private lateinit var repo: MockRocksServer
    private val ctx get() = Host.context

    @Before
    fun setUp() {
        Host.control("reset")
        Host.control("wipe_rocks")
        Host.control("allow", ctx.packageName, Bundle().apply { putBoolean("rocks", true) })
        client = LuaRuntimeClient(ctx)
        rt = client.bindAndWait(30_000) ?: throw AssertionError("cannot bind")
        val (service, conn) = Host.connectRocks()
        rocks = service
        connection = conn
        repo = MockRocksServer()
        Host.control("set_servers", repo.url) // searches and installs without an explicit server use the mock, not luarocks.org
    }

    @After
    fun tearDown() {
        if (::repo.isInitialized) repo.close()
        if (::connection.isInitialized) ctx.unbindService(connection)
        if (::client.isInitialized) client.unbind()
        Host.control("wipe_rocks")
        Host.control("reset")
    }

    private fun install(name: String, version: String?) =
        rocks.await(rocks.installRock(name, version, RockOptions().apply { server = repo.url }))

    @Test
    fun pureLuaRockInstallsWithItsDependenciesAndScriptsCanRequireIt() {
        val op = install("purelua", "1.0")
        assertEquals("log:\n${op.log}", JobState.SUCCEEDED, op.state)
        assertEquals(0, op.exitCode)
        val installed = rocks.listInstalledRocks().map { it.name to it.version }
        assertTrue(installed.toString(), "purelua" to "1.0-1" in installed && "helperlib" to "1.0-1" in installed) // dependency resolved
        assertTrue(rocks.rocksPath.contains("share/lua/5.4"))
        // any app's script sees the module through require()
        val job = rt.runScript("return require('purelua').answer()")
        assertEquals(job.errorMessage, JobState.SUCCEEDED, job.state)
        assertEquals("42", job.returnValueJson)
        // listing and removal
        val removed = rocks.await(rocks.removeRock("purelua", null))
        assertEquals(removed.log, JobState.SUCCEEDED, removed.state)
        assertFalse(rocks.listInstalledRocks().any { it.name == "purelua" })
        assertEquals(JobState.FAILED, rt.runScript("return require('purelua')").state)
    }

    @Test
    fun searchAndShowOperations() {
        val search = rocks.await(rocks.searchRocks("purelua"))
        assertEquals(search.log, JobState.SUCCEEDED, search.state)
        assertTrue(search.log, search.log.lines().any { it.startsWith("purelua\t1.0-1") })
        assertTrue(repo.requests.any { it.endsWith("manifest-5.4") })

        // no explicit server: the configured one is used
        assertEquals(JobState.SUCCEEDED, rocks.await(rocks.installRock("purelua", null, null)).state)
        val show = rocks.await(rocks.showRock("purelua", null))
        assertEquals(show.log, JobState.SUCCEEDED, show.state)
        assertTrue(show.log, show.log.contains("purelua 1.0-1") && show.log.contains("helperlib"))
    }

    @Test
    fun nativeRocksAreRefusedWithAnActionableError() {
        val op = install("nativemod", null)
        assertEquals(JobState.FAILED, op.state)
        assertEquals(ErrorCode.NATIVE_ROCK_UNSUPPORTED, op.errorCode)
        assertTrue(op.errorMessage!!, op.errorMessage!!.contains("pure-Lua"))
        assertFalse(rocks.listInstalledRocks().any { it.name == "nativemod" })
    }

    @Test
    fun unknownRocksAndBadInputs() {
        val op = install("doesnotexist", null)
        assertEquals(JobState.FAILED, op.state)
        assertEquals(ErrorCode.ROCK_NOT_FOUND, op.errorCode)
        for (bad in listOf("", "../x", "a b", "-rf", "x;rm", "a".repeat(200))) {
            val e = expectThrows<IllegalArgumentException> { rocks.installRock(bad, null, null) }
            assertEquals(e.message, ErrorCode.INVALID_REQUEST, ErrorCode.fromException(e))
        }
        val e = expectThrows<IllegalArgumentException> { rocks.installRock("purelua", "1.0; rm", null) }
        assertEquals(ErrorCode.INVALID_REQUEST, ErrorCode.fromException(e))
    }

    @Test
    fun serversMustBeAllowedHostsOverHttps() {
        for (server in listOf("https://evil.example.org", "http://luarocks.org", "ftp://luarocks.org", "https://user:pw@luarocks.org", "file:///data/data")) {
            val e = expectThrows<IllegalArgumentException> { rocks.installRock("purelua", null, RockOptions().apply { this.server = server }) }
            assertEquals("$server: ${e.message}", ErrorCode.INVALID_REQUEST, ErrorCode.fromException(e))
        }
    }

    @Test
    fun operationsAreIsolatedFromScriptJobIds() {
        val id = rocks.installRock("helperlib", null, RockOptions().apply { server = repo.url })
        val e = expectThrows<IllegalArgumentException> { rt.getJob(id) }
        assertEquals(ErrorCode.JOB_NOT_FOUND, ErrorCode.fromException(e))
        rocks.await(id)
        val e2 = expectThrows<IllegalArgumentException> { rocks.getRockOperation("0".repeat(32)) }
        assertEquals(ErrorCode.JOB_NOT_FOUND, ErrorCode.fromException(e2))
    }
}
