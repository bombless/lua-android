package com.bombless.luaandroid.security

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** An app the owner approved. [certs] are the pinned SHA-256 digests of its signing certificates. */
data class AllowedApp(
    val packageName: String,
    val label: String,
    val certs: Set<String>,
    val canRun: Boolean = true,
    val canManageRocks: Boolean = false,
    val since: Long = 0,
)

/** An app that tried to call the service and is waiting for the owner's decision. */
data class PendingRequest(
    val packageName: String,
    val label: String,
    val certs: Set<String>,
    val firstSeen: Long,
    val lastSeen: Long,
    val attempts: Int,
    val wantsRocks: Boolean,
    /** The package was approved before but is now signed with a different certificate. */
    val signatureChanged: Boolean = false,
)

data class BlockedApp(val packageName: String, val since: Long)

/**
 * Persistent allow-list / pending requests / blocked list (`files/lua/access.json`). Approval pins the
 * signing certificate digest (trust on first use): a different key for the same package name is treated
 * as an unknown app.
 */
class AccessStore(private val file: File) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var allowed = LinkedHashMap<String, AllowedApp>()
    private var pending = LinkedHashMap<String, PendingRequest>()
    private var blocked = LinkedHashMap<String, BlockedApp>()
    private var loaded = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText())
            root.optJSONArray("allowed")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val a = AllowedApp(o.getString("package"), o.optString("label"), strings(o.getJSONArray("certs")), o.optBoolean("run", true), o.optBoolean("rocks", false), o.optLong("since"))
                    allowed[a.packageName] = a
                }
            }
            root.optJSONArray("pending")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = PendingRequest(o.getString("package"), o.optString("label"), strings(o.getJSONArray("certs")), o.optLong("firstSeen"), o.optLong("lastSeen"), o.optInt("attempts", 1), o.optBoolean("rocks"), o.optBoolean("signatureChanged"))
                    pending[p.packageName] = p
                }
            }
            root.optJSONArray("blocked")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    blocked[o.getString("package")] = BlockedApp(o.getString("package"), o.optLong("since"))
                }
            }
        } catch (_: Exception) {
            // A corrupt file must not grant anything: start empty.
            allowed.clear(); pending.clear(); blocked.clear()
        }
    }

    private fun strings(a: JSONArray): Set<String> = (0 until a.length()).map { a.getString(it) }.toSet()

    @Synchronized private fun persist() {
        val root = JSONObject()
        root.put("version", 1)
        root.put("allowed", JSONArray(allowed.values.map {
            JSONObject().put("package", it.packageName).put("label", it.label).put("certs", JSONArray(it.certs.toList()))
                .put("run", it.canRun).put("rocks", it.canManageRocks).put("since", it.since)
        }))
        root.put("pending", JSONArray(pending.values.map {
            JSONObject().put("package", it.packageName).put("label", it.label).put("certs", JSONArray(it.certs.toList()))
                .put("firstSeen", it.firstSeen).put("lastSeen", it.lastSeen).put("attempts", it.attempts)
                .put("rocks", it.wantsRocks).put("signatureChanged", it.signatureChanged)
        }))
        root.put("blocked", JSONArray(blocked.values.map { JSONObject().put("package", it.packageName).put("since", it.since) }))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun changed() = listeners.forEach { it() }

    @Synchronized fun allowedApps(): List<AllowedApp> { ensureLoaded(); return allowed.values.toList() }
    @Synchronized fun pendingRequests(): List<PendingRequest> { ensureLoaded(); return pending.values.sortedByDescending { it.lastSeen } }
    @Synchronized fun blockedApps(): List<BlockedApp> { ensureLoaded(); return blocked.values.toList() }
    @Synchronized fun findAllowed(pkg: String): AllowedApp? { ensureLoaded(); return allowed[pkg] }
    @Synchronized fun isBlocked(pkg: String): Boolean { ensureLoaded(); return pkg in blocked }

    /** Records a rejected call. Returns true when this created a new pending entry (worth a notification). */
    fun recordAttempt(pkg: String, label: String, certs: Set<String>, wantsRocks: Boolean, now: Long): Boolean {
        val isNew: Boolean
        synchronized(this) {
            ensureLoaded()
            if (pkg in blocked) return false
            val prev = pending[pkg]
            val changed = allowed[pkg]?.let { a -> a.certs.none { it in certs } } ?: false
            isNew = prev == null
            pending[pkg] = PendingRequest(pkg, label, certs, prev?.firstSeen ?: now, now, (prev?.attempts ?: 0) + 1, wantsRocks || (prev?.wantsRocks ?: false), changed)
            persist()
        }
        changed()
        return isNew
    }

    fun allow(pkg: String, label: String, certs: Set<String>, canManageRocks: Boolean, now: Long) {
        synchronized(this) {
            ensureLoaded()
            allowed[pkg] = AllowedApp(pkg, label, certs, true, canManageRocks, now)
            pending.remove(pkg); blocked.remove(pkg)
            persist()
        }
        changed()
    }

    fun setRocksAccess(pkg: String, enabled: Boolean) {
        synchronized(this) {
            ensureLoaded()
            val a = allowed[pkg] ?: return
            allowed[pkg] = a.copy(canManageRocks = enabled)
            persist()
        }
        changed()
    }

    fun deny(pkg: String, now: Long) {
        synchronized(this) {
            ensureLoaded()
            pending.remove(pkg)
            blocked[pkg] = BlockedApp(pkg, now)
            persist()
        }
        changed()
    }

    fun revoke(pkg: String) {
        synchronized(this) {
            ensureLoaded()
            allowed.remove(pkg)
            persist()
        }
        changed()
    }

    fun unblock(pkg: String) {
        synchronized(this) {
            ensureLoaded()
            blocked.remove(pkg)
            persist()
        }
        changed()
    }

    fun dismissPending(pkg: String) {
        synchronized(this) {
            ensureLoaded()
            pending.remove(pkg)
            persist()
        }
        changed()
    }
}
