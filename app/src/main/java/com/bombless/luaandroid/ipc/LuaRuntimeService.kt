package com.bombless.luaandroid.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bombless.luaandroid.LuaAndroidApp

class LuaRuntimeService : Service() {

    private val binder = object : ILuaRuntimeService.Stub() {
        override fun getApiVersion(): Int = API_VERSION

        override fun getRuntimeInfo(): String {
            return """{"lua":"LuaJ 3.0.1 (Lua 5.2 compatible)","engine":"luaj-jse","apiVersion":$API_VERSION,"package":"${packageName}","abi":"pure-java"}"""
        }

        override fun submit(request: JobRequest): String {
            enforceCaller()
            requireNotNull(request) { "request is null" }
            return LuaAndroidApp.instance.jobManager.submit(request)
        }

        override fun getJob(jobId: String): JobInfo? {
            enforceCaller()
            return LuaAndroidApp.instance.jobManager.getJob(jobId)
        }

        override fun listJobs(limit: Int, beforeCreatedAtMillis: Long): List<JobInfo> {
            enforceCaller()
            return LuaAndroidApp.instance.jobManager.listJobs(limit, beforeCreatedAtMillis)
        }

        override fun cancel(jobId: String): Boolean {
            enforceCaller()
            return LuaAndroidApp.instance.jobManager.cancel(jobId)
        }

        override fun registerCallback(jobId: String, callback: IJobCallback?) {
            enforceCaller()
            if (callback != null) {
                LuaAndroidApp.instance.jobManager.registerCallback(jobId, callback)
            }
        }

        override fun unregisterCallback(jobId: String, callback: IJobCallback?) {
            enforceCaller()
            if (callback != null) {
                LuaAndroidApp.instance.jobManager.unregisterCallback(jobId, callback)
            }
        }

        override fun openResult(jobId: String, artifactId: String): ParcelFileDescriptor? {
            enforceCaller()
            return LuaAndroidApp.instance.jobManager.openResult(jobId, artifactId)
        }

        private fun enforceCaller() {
            // Permission is also enforced by the system via android:permission on the service.
            // Additional UID logging for diagnostics.
            val uid = getCallingUid()
            Log.d(TAG, "Binder call from uid=$uid")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind action=${intent?.action}")
        return binder
    }

    companion object {
        private const val TAG = "LuaRuntimeService"
        const val API_VERSION = 1
        const val ACTION_BIND = "com.bombless.luaandroid.action.BIND_RUNTIME"
    }
}
