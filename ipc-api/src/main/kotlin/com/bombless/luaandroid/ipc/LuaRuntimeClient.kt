package com.bombless.luaandroid.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Convenience wrapper around binding to the Lua Android host app. It adds nothing to the wire
 * contract: everything it does can be done with the AIDL interfaces directly (Manual.md section 6).
 *
 * The client app must declare, in its own manifest:
 *
 *     <uses-permission android:name="com.bombless.luaandroid.permission.USE_LUA_RUNTIME" />
 *     <queries><package android:name="com.bombless.luaandroid" /></queries>
 *
 * and the user must have approved it in Lua Android -> Access (apps signed with the same
 * certificate as the host are trusted automatically unless the owner turned that off).
 */
class LuaRuntimeClient(context: Context) {
    interface Listener {
        fun onConnected(runtime: ILuaRuntimeService)
        fun onDisconnected()
    }

    private val appContext: Context = context.applicationContext
    private var listener: Listener? = null
    private var latch: CountDownLatch? = null
    private var bound = false

    @Volatile
    private var service: ILuaRuntimeService? = null

    /** The connected service, or null while (re)connecting. Always handle `DeadObjectException`. */
    val runtime: ILuaRuntimeService? get() = service

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = ILuaRuntimeService.Stub.asInterface(binder)
            service = s
            latch?.countDown()
            listener?.onConnected(s)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            listener?.onDisconnected()
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            listener?.onDisconnected()
        }
    }

    /** Starts binding. Returns false when the host app is not installed or not visible to this app. */
    fun bind(listener: Listener? = null): Boolean {
        this.listener = listener
        if (bound) return true
        bound = appContext.bindService(LuaRuntimeContract.runtimeIntent(), connection, Context.BIND_AUTO_CREATE)
        return bound
    }

    /** Binds and blocks until connected (never call from the main thread). */
    fun bindAndWait(timeoutMillis: Long = 10_000): ILuaRuntimeService? {
        val l = CountDownLatch(1)
        latch = l
        if (!bind(listener)) return null
        service?.let { return it }
        l.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return service
    }

    fun unbind() {
        if (bound) {
            bound = false
            try {
                appContext.unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
        }
        service = null
    }

    companion object {
        /**
         * Polls getJob() with exponential backoff (50 ms .. 1 s) until the job is terminal. Callbacks
         * (registerCallback) are preferable; this is the documented fallback. Blocks; not for the main thread.
         * Returns the last seen info, which is not terminal if the timeout elapsed.
         */
        @JvmStatic
        @JvmOverloads
        fun waitForTerminal(runtime: ILuaRuntimeService, jobId: String, timeoutMillis: Long = 60_000): JobInfo {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            var delay = 50L
            var info = runtime.getJob(jobId)
            while (!info.isTerminal && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(delay)
                delay = minOf(delay * 2, 1000L)
                info = runtime.getJob(jobId)
            }
            return info
        }

        /** Reads an artifact fully into memory (at most [maxBytes]; larger artifacts throw). */
        @JvmStatic
        @JvmOverloads
        fun readArtifact(runtime: ILuaRuntimeService, jobId: String, artifactId: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
            val pfd: ParcelFileDescriptor = runtime.openResult(jobId, artifactId)
                ?: throw IllegalStateException("${ErrorCode.INTERNAL_ERROR}: artifact $artifactId could not be opened")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IllegalStateException("${ErrorCode.INPUT_TOO_LARGE}: artifact larger than $maxBytes bytes")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        }

        /** Convenience: reads an artifact as UTF-8 text. */
        @JvmStatic
        @JvmOverloads
        fun readArtifactText(runtime: ILuaRuntimeService, jobId: String, artifactId: String, maxBytes: Int = 8 * 1024 * 1024): String =
            String(readArtifact(runtime, jobId, artifactId, maxBytes), Charsets.UTF_8)
    }
}
