package com.bombless.luaandroid.example

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.bombless.luaandroid.ipc.ILuaRocksService
import com.bombless.luaandroid.ipc.ILuaRuntimeService
import com.bombless.luaandroid.ipc.JobInfo
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.LuaRuntimeClient
import com.bombless.luaandroid.ipc.LuaRuntimeContract
import com.bombless.luaandroid.ipc.RockOperation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Helpers shared by the acceptance tests. The tests run in this example app's process/UID. */
object Host {
    const val CONTROL = "content://com.bombless.luaandroid.testcontrol"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Debug-only remote control of the host app (see TestControlProvider). */
    fun control(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        context.contentResolver.call(Uri.parse(CONTROL), method, arg, extras) ?: Bundle()

    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun connectRocks(): Pair<ILuaRocksService, ServiceConnection> {
        val latch = CountDownLatch(1)
        var service: ILuaRocksService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = ILuaRocksService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        check(context.bindService(LuaRuntimeContract.rocksIntent(), connection, Context.BIND_AUTO_CREATE)) { "cannot bind the rocks service" }
        check(latch.await(20, TimeUnit.SECONDS)) { "rocks service did not connect" }
        return service!! to connection
    }
}

fun waitFor(timeoutMillis: Long = 30_000, description: String = "condition", condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (!condition()) {
        if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $description")
        Thread.sleep(50)
    }
}

/** Submits a script and waits for the terminal state. */
fun ILuaRuntimeService.runScript(source: String, timeoutMillis: Long = 60_000, configure: JobRequest.() -> Unit = {}): JobInfo {
    val request = JobRequest().apply { this.source = source; configure() }
    return LuaRuntimeClient.waitForTerminal(this, submit(request), timeoutMillis)
}

fun ILuaRocksService.await(operationId: String, timeoutMillis: Long = 180_000): RockOperation {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    var op = getRockOperation(operationId)
    while (!JobState.isTerminal(op.state) && SystemClock.elapsedRealtime() < deadline) {
        Thread.sleep(100)
        op = getRockOperation(operationId)
    }
    return op
}

inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("expected ${T::class.java.simpleName} but got $t", t)
    }
    throw AssertionError("expected ${T::class.java.simpleName} but nothing was thrown")
}
