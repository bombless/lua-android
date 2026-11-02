package com.bombless.luaandroid

import android.content.Context
import com.bombless.luaandroid.core.AppSettings
import com.bombless.luaandroid.core.JobManager
import com.bombless.luaandroid.core.JobStore
import com.bombless.luaandroid.core.Paths
import com.bombless.luaandroid.core.RunnerLauncher
import com.bombless.luaandroid.core.RuntimeInfo
import com.bombless.luaandroid.core.RuntimeInstaller
import com.bombless.luaandroid.rocks.RockManager
import com.bombless.luaandroid.security.AccessNotifier
import com.bombless.luaandroid.security.AccessStore
import com.bombless.luaandroid.security.CallerPolicy

/** Wires the components together. Services, the provider and the UI all share one instance. */
class AppGraph(val context: Context) {
    val paths = Paths(context)
    val settings = AppSettings(context)
    val accessStore = AccessStore(paths.access)
    private val notifier = AccessNotifier(context)
    val policy = CallerPolicy(context, accessStore, settings, onNewRequest = { notifier.notifyRequest(it) })
    private val installer = RuntimeInstaller(context, paths)
    val store = JobStore(paths.jobs)
    val jobs = JobManager(context, paths, store, RunnerLauncher(paths), settings)
    val rocks = RockManager(context, paths, settings)
    val runtimeInfo = RuntimeInfo(context, paths, settings)

    @Volatile private var ready = false
    private val readyLock = Any()

    /** Installs the Lua runtime on first use / after an update and recovers persisted jobs. Idempotent. */
    fun ensureReady() {
        if (ready) return
        synchronized(readyLock) {
            if (ready) return
            paths.ensureDirs()
            installer.ensureInstalled()
            jobs.recover()
            ready = true
        }
    }
}
