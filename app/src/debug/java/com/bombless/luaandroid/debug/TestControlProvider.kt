package com.bombless.luaandroid.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.bombless.luaandroid.LuaApp
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.ipc.JobRequest

/** Debug-only remote control for the acceptance tests (see the debug AndroidManifest.xml). */
class TestControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val graph = (context!!.applicationContext as LuaApp).graph
        graph.ensureReady()
        val owner = Caller(Process.myUid(), listOf(context!!.packageName), true)
        val out = Bundle()
        when (method) {
            // Cancel everything, delete all jobs, forget every approval, restore defaults.
            "reset" -> {
                graph.jobs.snapshot().filter { !it.isTerminal }.forEach { graph.jobs.cancel(owner, it.id) }
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (graph.jobs.hasActiveJobs() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                graph.jobs.snapshot().forEach { graph.jobs.delete(it.id) }
                graph.accessStore.allowedApps().forEach { graph.accessStore.revoke(it.packageName) }
                graph.accessStore.pendingRequests().forEach { graph.accessStore.dismissPending(it.packageName) }
                graph.accessStore.blockedApps().forEach { graph.accessStore.unblock(it.packageName) }
                graph.settings.trustSameSigner = true
                graph.settings.rocksServers = com.bombless.luaandroid.core.AppSettings.DEFAULT_SERVERS
                graph.settings.allowedHosts = com.bombless.luaandroid.core.AppSettings.DEFAULT_HOSTS
            }
            "set_trust" -> graph.settings.trustSameSigner = arg == "true"
            "set_servers" -> graph.settings.rocksServers = requireNotNull(arg).split("\n")
            "allow" -> {
                val pkg = requireNotNull(arg)
                graph.accessStore.allow(pkg, pkg, graph.policy.signingDigests(pkg), extras?.getBoolean("rocks") ?: false, System.currentTimeMillis())
            }
            "revoke" -> graph.accessStore.revoke(requireNotNull(arg))
            "pending" -> {
                val pending = graph.accessStore.pendingRequests()
                out.putInt("count", pending.size)
                out.putStringArrayList("packages", ArrayList(pending.map { it.packageName }))
            }
            "submit_as_owner" -> out.putString("jobId", graph.jobs.submit(owner, JobRequest().apply { source = arg }))
            "wipe_rocks" -> {
                graph.paths.rocks.deleteRecursively()
                graph.paths.rocks.mkdirs()
            }
            "max_concurrency" -> out.putInt("value", graph.settings.maxConcurrentJobs)
            else -> throw IllegalArgumentException("unknown method $method")
        }
        return out
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
