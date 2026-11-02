package com.bombless.luaandroid.core

import android.content.Context
import com.bombless.luaandroid.BuildConfig
import com.bombless.luaandroid.ipc.ApiLimits

/** Owner-editable settings (SharedPreferences). All values are bounded on read. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var retentionDays: Int
        get() = prefs.getInt("retentionDays", 7).coerceIn(1, 365)
        set(v) = prefs.edit().putInt("retentionDays", v.coerceIn(1, 365)).apply()

    var maxRetainedJobs: Int
        get() = prefs.getInt("maxRetainedJobs", 200).coerceIn(10, 2000)
        set(v) = prefs.edit().putInt("maxRetainedJobs", v.coerceIn(10, 2000)).apply()

    /** Takes effect on the next process start. */
    var maxConcurrentJobs: Int
        get() = prefs.getInt("maxConcurrentJobs", 2).coerceIn(1, 4)
        set(v) = prefs.edit().putInt("maxConcurrentJobs", v.coerceIn(1, 4)).apply()

    var maxTimeoutMillis: Long
        get() = prefs.getLong("maxTimeoutMillis", ApiLimits.MAX_TIMEOUT_MILLIS).coerceIn(ApiLimits.MIN_TIMEOUT_MILLIS, ApiLimits.MAX_TIMEOUT_MILLIS)
        set(v) = prefs.edit().putLong("maxTimeoutMillis", v).apply()

    var streamCapBytes: Long
        get() = prefs.getLong("streamCapBytes", Limits.DEFAULT_STREAM_CAP_BYTES).coerceIn(16L * 1024, 8L * 1024 * 1024)
        set(v) = prefs.edit().putLong("streamCapBytes", v).apply()

    /** Apps signed with this app's certificate are trusted without a prompt. */
    var trustSameSigner: Boolean
        get() = prefs.getBoolean("trustSameSigner", true)
        set(v) = prefs.edit().putBoolean("trustSameSigner", v).apply()

    /** Hosts LuaRocks may download from (exact match or subdomain). */
    var allowedHosts: Set<String>
        get() = prefs.getStringSet("allowedHosts", DEFAULT_HOSTS)?.toSet() ?: DEFAULT_HOSTS
        set(v) = prefs.edit().putStringSet("allowedHosts", v).apply()

    /** Rock servers searched by `luarocks install/search`, in order. */
    var rocksServers: List<String>
        get() = (prefs.getString("rocksServers", null) ?: DEFAULT_SERVERS.joinToString("\n")).lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("rocksServers", v.joinToString("\n")).apply()

    /** http:// (cleartext) is refused except for loopback hosts, and only when this is on. Debug builds always allow loopback. */
    var allowLoopbackHttp: Boolean
        get() = BuildConfig.DEBUG || prefs.getBoolean("allowLoopbackHttp", false)
        set(v) = prefs.edit().putBoolean("allowLoopbackHttp", v).apply()

    companion object {
        val DEFAULT_SERVERS = listOf("https://luarocks.org")
        val DEFAULT_HOSTS = setOf("luarocks.org", "github.com", "codeload.github.com", "raw.githubusercontent.com", "objects.githubusercontent.com", "gitlab.com")
    }
}
