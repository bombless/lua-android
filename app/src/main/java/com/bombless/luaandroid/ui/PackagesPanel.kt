package com.bombless.luaandroid.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.RockInfo
import com.bombless.luaandroid.ipc.RockOperation
import com.bombless.luaandroid.rocks.HttpBridge

/** LuaRocks, owner side: install / remove / search pure-Lua rocks into the private tree. */
class PackagesPanel(activity: MainActivity) : Panel(activity) {
    private val ctx = activity
    private val rocks get() = activity.graph.rocks
    private val settings get() = activity.graph.settings

    private val nameInput = Ui.input(ctx, "rock name, e.g. inspect")
    private val versionInput = Ui.input(ctx, "version (optional)")
    private val installedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val opsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val hostsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val hostInput = Ui.input(ctx, "allow another host, e.g. example.org")
    private val serversInput = Ui.input(ctx, "rocks servers, one per line", multiline = true, mono = true).apply { minLines = 2 }
    private val expanded = HashSet<String>()
    private var lastFinished = -1
    private val listener: () -> Unit = { refreshSoon { renderOps() } }

    override val view: View = ScrollView(ctx).apply {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        col.addView(Ui.text(ctx, "LuaRocks ${com.bombless.luaandroid.BuildConfig.LUAROCKS_VERSION} · pure-Lua rocks only. " +
            "Rocks that need a C compiler are refused with NATIVE_ROCK_UNSUPPORTED; downloads are made by this app, over HTTPS, from allowed hosts only.",
            12.5f, Ui.MUTED))
        col.addView(Ui.space(ctx, 10))
        col.addView(nameInput)
        col.addView(Ui.space(ctx, 6))
        col.addView(versionInput)
        col.addView(Ui.space(ctx, 8))
        col.addView(Ui.row(ctx).apply {
            addView(Ui.button(ctx, "Install", primary = true) { install() })
            addView(Ui.space(ctx, 0, 8))
            addView(Ui.button(ctx, "Search") { search() })
        })
        col.addView(Ui.heading(ctx, "Installed"))
        col.addView(installedBox)
        col.addView(Ui.heading(ctx, "Operations"))
        col.addView(opsBox)
        col.addView(Ui.heading(ctx, "Servers"))
        col.addView(serversInput)
        col.addView(Ui.space(ctx, 6))
        col.addView(Ui.button(ctx, "Save servers") {
            val urls = serversInput.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bad = urls.firstNotNullOfOrNull { u -> HttpBridge.checkUrl(u, settings.allowedHosts, settings.allowLoopbackHttp)?.let { "$u: $it" } }
            if (urls.isEmpty() || bad != null) Ui.toast(ctx, bad ?: "At least one server is required") else { settings.rocksServers = urls; Ui.toast(ctx, "Saved") }
        })
        col.addView(Ui.heading(ctx, "Allowed download hosts"))
        col.addView(hostsBox)
        col.addView(Ui.space(ctx, 6))
        col.addView(hostInput)
        col.addView(Ui.space(ctx, 6))
        col.addView(Ui.button(ctx, "Allow host") {
            val h = hostInput.text.toString().trim().lowercase()
            if (!Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?").matches(h)) Ui.toast(ctx, "Enter a host name, not a URL")
            else { settings.allowedHosts = settings.allowedHosts + h; hostInput.setText(""); renderHosts() }
        })
        addView(col)
    }

    override fun onShow() {
        rocks.addListener(listener)
        serversInput.setText(settings.rocksServers.joinToString("\n"))
        renderHosts()
        renderOps()
        refreshInstalled()
    }

    override fun onHide() = rocks.removeListener(listener)

    private fun install() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { Ui.toast(ctx, "Enter a rock name"); return }
        run { rocks.install(name, versionInput.text.toString().trim().ifEmpty { null }, null) }
    }

    private fun search() {
        val q = nameInput.text.toString().trim()
        if (q.isEmpty()) { Ui.toast(ctx, "Enter something to search for"); return }
        run { rocks.search(q) }
    }

    private fun run(block: () -> String) {
        try { block() } catch (e: ServiceError) { Ui.toast(ctx, e.detail) }
        renderOps()
    }

    private fun refreshInstalled() {
        activity.background.execute {
            val list: List<RockInfo>? = try { rocks.listInstalled() } catch (_: Exception) { null }
            ctx.runOnUiThread {
                installedBox.removeAllViews()
                when {
                    list == null -> installedBox.addView(Ui.text(ctx, "Could not read the rock tree.", 13f, Ui.ERR))
                    list.isEmpty() -> installedBox.addView(Ui.text(ctx, "Nothing installed yet.", 13f, Ui.MUTED))
                    else -> list.forEach { r ->
                        installedBox.addView(Ui.row(ctx).apply {
                            addView(Ui.text(ctx, "${r.name}  ${r.version}", 14f, Ui.TEXT, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(Ui.button(ctx, "Remove") { run { rocks.remove(r.name, r.version) } })
                        })
                    }
                }
            }
        }
    }

    private fun renderHosts() {
        hostsBox.removeAllViews()
        settings.allowedHosts.sorted().forEach { h ->
            hostsBox.addView(Ui.row(ctx).apply {
                addView(Ui.text(ctx, h, 13f, Ui.TEXT, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.button(ctx, "Remove") { settings.allowedHosts = settings.allowedHosts - h; renderHosts() })
            })
        }
    }

    private fun renderOps() {
        opsBox.removeAllViews()
        val ops = rocks.operations()
        val finished = ops.count { it.isTerminal }
        if (lastFinished >= 0 && finished != lastFinished) refreshInstalled()
        lastFinished = finished
        if (ops.isEmpty()) { opsBox.addView(Ui.text(ctx, "No operations yet.", 13f, Ui.MUTED)); return }
        ops.take(15).forEach { opsBox.addView(opCard(it)) }
    }

    private fun opCard(op: RockOperation): View {
        val c = Ui.card(ctx)
        c.addView(Ui.row(ctx).apply {
            addView(Ui.text(ctx, "${op.kind} ${op.target ?: ""}", 14f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.chip(ctx, JobState.name(op.state), Ui.stateColor(op.state)))
        })
        if (op.errorCode != null) c.addView(Ui.text(ctx, "${op.errorCode}: ${op.errorMessage ?: ""}".take(300), 12f, Ui.ERR))
        if (op.kind == "search" && op.state == JobState.SUCCEEDED) {
            val rows = op.log.lines().mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 } }
            val byName = rows.groupBy({ it[0] }, { it[1] }).entries.take(20)
            if (byName.isEmpty()) c.addView(Ui.text(ctx, "No results.", 12.5f, Ui.MUTED))
            byName.forEach { (name, versions) ->
                c.addView(Ui.row(ctx).apply {
                    addView(Ui.text(ctx, "$name  ${versions.distinct().take(3).joinToString(", ")}", 12.5f, Ui.TEXT, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.button(ctx, "Install") { run { rocks.install(name, null, null) } })
                })
            }
        } else if (op.id in expanded) {
            c.addView(Ui.codeBox(ctx).apply { text = op.log.takeLast(4000).ifEmpty { "(no output)" } })
        }
        if (op.kind != "search" || op.state != JobState.SUCCEEDED) {
            c.addView(Ui.space(ctx, 6))
            c.addView(Ui.row(ctx).apply {
                addView(Ui.button(ctx, if (op.id in expanded) "Hide log" else "Log") { if (op.id in expanded) expanded.remove(op.id) else expanded.add(op.id); renderOps() })
                if (!op.isTerminal) {
                    addView(Ui.space(ctx, 0, 8))
                    addView(Ui.button(ctx, "Cancel") { try { rocks.cancel(op.operationId) } catch (_: ServiceError) {} })
                }
            })
        }
        return c
    }

    private val RockOperation.id: String get() = operationId
}
