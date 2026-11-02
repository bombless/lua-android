package com.bombless.luaandroid.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bombless.luaandroid.AppGraph
import com.bombless.luaandroid.LuaApp
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.security.Capability

/**
 * The cross-app script runtime (Manual.md section 6). Every call is authorised from the caller's UID
 * (see CallerPolicy) before it does anything; rejected calls throw a Binder-safe exception whose message
 * starts with the stable error code.
 */
class LuaRuntimeService : Service() {
    private val graph: AppGraph get() = (application as LuaApp).graph

    override fun onCreate() {
        super.onCreate()
        graph.jobs.idleListener = { stopSelf() }
    }

    override fun onDestroy() {
        graph.jobs.idleListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        // Keep the process (and its queue) alive after the last client unbinds, until the queue drains.
        try {
            startService(Intent(this, LuaRuntimeService::class.java))
        } catch (e: Exception) {
            Log.i(TAG, "cannot start as a started service from here: ${e.javaClass.simpleName}")
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!graph.jobs.hasActiveJobs()) stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun <T> guard(capability: Capability = Capability.RUN, block: (Caller) -> T): T {
        try {
            val caller = graph.policy.authorize(Binder.getCallingUid(), capability)
            graph.ensureReady()
            return block(caller)
        } catch (e: ServiceError) {
            throw e.toBinderException()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "unexpected failure", e)
            throw IllegalStateException("${ErrorCode.INTERNAL_ERROR}: unexpected failure (${e.javaClass.simpleName})")
        }
    }

    private val binder = object : ILuaRuntimeService.Stub() {
        override fun getApiVersion(): Int = LuaRuntimeContract.API_VERSION

        override fun getRuntimeInfo(): String = guard { graph.runtimeInfo.json() }

        override fun submit(request: JobRequest?): String = guard { caller ->
            if (request == null) throw ServiceError(ErrorCode.INVALID_REQUEST, "request is null")
            graph.jobs.submit(caller, request)
        }

        override fun getJob(jobId: String?): JobInfo = guard { caller -> graph.jobs.get(caller, jobId) }

        override fun listJobs(limit: Int, beforeCreatedAtMillis: Long): MutableList<JobInfo> =
            guard { caller -> graph.jobs.list(caller, limit, beforeCreatedAtMillis).toMutableList() }

        override fun cancel(jobId: String?): Boolean = guard { caller -> graph.jobs.cancel(caller, jobId) }

        override fun registerCallback(jobId: String?, callback: IJobCallback?) = guard { caller ->
            if (callback == null) throw ServiceError(ErrorCode.INVALID_REQUEST, "callback is null")
            graph.jobs.registerCallback(caller, jobId, callback)
        }

        override fun unregisterCallback(jobId: String?, callback: IJobCallback?) = guard { caller ->
            if (callback == null) throw ServiceError(ErrorCode.INVALID_REQUEST, "callback is null")
            graph.jobs.unregisterCallback(caller, jobId, callback)
        }

        override fun openResult(jobId: String?, artifactId: String?): ParcelFileDescriptor =
            guard { caller -> graph.jobs.openArtifact(caller, jobId, artifactId) }
    }

    private companion object {
        const val TAG = "LuaRuntimeService"
    }
}
