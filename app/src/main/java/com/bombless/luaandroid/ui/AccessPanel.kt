package com.bombless.luaandroid.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.bombless.luaandroid.security.AllowedApp
import com.bombless.luaandroid.security.PendingRequest
import java.text.DateFormat
import java.util.Date

/**
 * The consent screen (Manual.md section 4.1). Approving an app pins the SHA-256 of its signing
 * certificate: the same package name signed with another key is treated as a new, unknown app.
 */
class AccessPanel(activity: MainActivity) : Panel(activity) {
    private val ctx = activity
    private val store get() = activity.graph.accessStore
    private val settings get() = activity.graph.settings
    private val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
    }
    private val listener: () -> Unit = { ctx.runOnUiThread { render() } }

    override val view: View = ScrollView(ctx).apply { addView(col) }

    override fun onShow() {
        store.addListener(listener)
        render()
    }

    override fun onHide() = store.removeListener(listener)

    private fun fingerprint(certs: Set<String>): String = certs.firstOrNull()?.let { it.take(16).chunked(4).joinToString(" ") } ?: "unknown"

    private fun render() {
        col.removeAllViews()
        col.addView(Ui.text(ctx, "Other apps can run Lua scripts here only if you allow them. Scripts cannot touch your files, the network or other apps; they run in a separate process with limits.", 12.5f, Ui.MUTED))
        col.addView(Ui.space(ctx, 10))

        val trust = Ui.card(ctx)
        trust.addView(Switch(ctx).apply {
            text = "Trust apps signed with the same key as Lua Android"
            setTextColor(Ui.TEXT)
            isChecked = settings.trustSameSigner
            setOnCheckedChangeListener { _, on -> settings.trustSameSigner = on }
        })
        trust.addView(Ui.text(ctx, "Convenient for your own apps. Turn it off to approve every app by hand.", 12f, Ui.MUTED))
        col.addView(trust)

        val pending = store.pendingRequests()
        col.addView(Ui.heading(ctx, "Waiting for your decision (${pending.size})"))
        if (pending.isEmpty()) col.addView(Ui.text(ctx, "No requests. An app that tries to use the runtime will show up here.", 13f, Ui.MUTED))
        pending.forEach { col.addView(pendingCard(it)) }

        val allowed = store.allowedApps()
        col.addView(Ui.heading(ctx, "Allowed apps (${allowed.size})"))
        if (allowed.isEmpty()) col.addView(Ui.text(ctx, "None.", 13f, Ui.MUTED))
        allowed.forEach { col.addView(allowedCard(it)) }

        val blocked = store.blockedApps()
        if (blocked.isNotEmpty()) {
            col.addView(Ui.heading(ctx, "Blocked"))
            blocked.forEach { b ->
                col.addView(Ui.row(ctx).apply {
                    addView(Ui.text(ctx, b.packageName, 13f, Ui.TEXT, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.button(ctx, "Unblock") { store.unblock(b.packageName) })
                })
            }
        }
    }

    private fun pendingCard(p: PendingRequest): View {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, p.label, 16f, Ui.TEXT, bold = true))
        c.addView(Ui.text(ctx, p.packageName, 12f, Ui.MUTED, mono = true))
        c.addView(Ui.text(ctx, "Key ${fingerprint(p.certs)} · ${p.attempts} attempt(s) · last ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.lastSeen))}", 12f, Ui.MUTED, mono = true))
        if (p.signatureChanged) c.addView(Ui.text(ctx, "Warning: this package was approved before but is now signed with a DIFFERENT key. Only allow it if you re-installed it deliberately.", 12.5f, Ui.ERR))
        if (p.wantsRocks) c.addView(Ui.text(ctx, "It also asked to manage Lua packages (LuaRocks).", 12.5f, Ui.WARN))
        c.addView(Ui.space(ctx, 8))
        c.addView(Ui.row(ctx).apply {
            addView(Ui.button(ctx, "Allow", primary = true) { store.allow(p.packageName, p.label, p.certs, false, System.currentTimeMillis()) })
            addView(Ui.space(ctx, 0, 8))
            addView(Ui.button(ctx, "Allow + packages") {
                Ui.confirm(ctx, "Allow package management?", "${p.label} will be able to install and remove Lua packages, which then run in scripts of every allowed app.", "Allow") {
                    store.allow(p.packageName, p.label, p.certs, true, System.currentTimeMillis())
                }
            })
            addView(Ui.space(ctx, 0, 8))
            addView(Ui.button(ctx, "Deny") { store.deny(p.packageName, System.currentTimeMillis()) })
        })
        return c
    }

    private fun allowedCard(a: AllowedApp): View {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, a.label.ifEmpty { a.packageName }, 15f, Ui.TEXT, bold = true))
        c.addView(Ui.text(ctx, a.packageName, 12f, Ui.MUTED, mono = true))
        c.addView(Ui.text(ctx, "Pinned key ${fingerprint(a.certs)}", 12f, Ui.MUTED, mono = true))
        c.addView(Switch(ctx).apply {
            text = "Can manage Lua packages"
            setTextColor(Ui.TEXT)
            isChecked = a.canManageRocks
            setOnCheckedChangeListener { _, on -> store.setRocksAccess(a.packageName, on) }
        })
        c.addView(Ui.space(ctx, 6))
        c.addView(Ui.button(ctx, "Revoke") { store.revoke(a.packageName) })
        return c
    }
}
