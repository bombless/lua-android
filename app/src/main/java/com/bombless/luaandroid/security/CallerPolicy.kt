package com.bombless.luaandroid.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import com.bombless.luaandroid.core.AppSettings
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.ipc.ErrorCode
import java.security.MessageDigest

enum class Capability { RUN, MANAGE_ROCKS }

/**
 * Per-call authorisation (Manual.md section 4.1). The caller is identified ONLY from
 * Binder.getCallingUid(): package names come from the package manager and the signing certificate is
 * compared with the digest pinned when the owner approved the app. Nothing in a request is trusted.
 *
 *   * this app's own UID                         -> owner (full access)
 *   * approved app, certificate matches the pin  -> allowed (RUN, plus MANAGE_ROCKS if granted)
 *   * signed with this app's certificate         -> allowed for RUN unless the owner turned that off
 *   * anything else                              -> rejected, recorded as a pending request
 */
class CallerPolicy(
    private val context: Context,
    private val store: AccessStore,
    private val settings: AppSettings,
    private val onNewRequest: (PendingRequest) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pm: PackageManager = context.packageManager
    private val ownDigests: Set<String> by lazy { signingDigests(context.packageName) }

    fun authorize(uid: Int, capability: Capability): Caller {
        if (uid == Process.myUid()) return Caller(uid, listOf(context.packageName), true)

        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (packages.isEmpty()) throw ServiceError(ErrorCode.UNAUTHORIZED_CALLER, "the calling app could not be identified")

        var firstDigests: Set<String> = emptySet()
        for ((index, pkg) in packages.withIndex()) {
            val certs = signingDigests(pkg)
            if (index == 0) firstDigests = certs
            if (certs.isEmpty()) continue
            val entry = store.findAllowed(pkg)
            if (entry != null && entry.certs.any { it in certs }) {
                val ok = when (capability) {
                    Capability.RUN -> entry.canRun
                    Capability.MANAGE_ROCKS -> entry.canManageRocks
                }
                if (ok) return Caller(uid, packages, false)
            }
            if (capability == Capability.RUN && settings.trustSameSigner && certs.any { it in ownDigests }) {
                return Caller(uid, packages, false)
            }
        }

        val pkg = packages.first()
        val isNew = store.recordAttempt(pkg, labelOf(pkg), firstDigests, capability == Capability.MANAGE_ROCKS, clock())
        if (isNew) store.pendingRequests().firstOrNull { it.packageName == pkg }?.let(onNewRequest)
        val what = if (capability == Capability.MANAGE_ROCKS) "manage Lua packages" else "run Lua scripts"
        throw ServiceError(
            ErrorCode.UNAUTHORIZED_CALLER,
            "$pkg is not allowed to $what. The owner has been asked: open Lua Android > Access and approve it.",
        )
    }

    private fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** SHA-256 digests (hex) of the package's current signing certificates, including its rotation history. */
    @Suppress("DEPRECATION")
    fun signingDigests(pkg: String): Set<String> = try {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            when {
                si == null -> emptyArray()
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            } ?: emptyArray()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        signatures.map { sha256Hex(it.toByteArray()) }.toSet()
    } catch (_: PackageManager.NameNotFoundException) {
        emptySet()
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
