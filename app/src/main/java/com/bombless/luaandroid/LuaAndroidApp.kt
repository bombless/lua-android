package com.bombless.luaandroid

import android.app.Application
import com.bombless.luaandroid.ipc.JobManager

class LuaAndroidApp : Application() {
    lateinit var jobManager: JobManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        jobManager = JobManager(this)
    }

    companion object {
        @Volatile
        lateinit var instance: LuaAndroidApp
            private set
    }
}
