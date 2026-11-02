package com.bombless.luaandroid.ui

import android.os.Process
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.core.JobRecord
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.ipc.JobState
import java.io.File

/** Every job of every caller (the owner sees all), with details, cancel and delete. */
class JobsPanel(activity: MainActivity) : Panel(activity) {
    private val ctx = activity
    private val graph get() = activity.graph
    private val owner get() = Caller(Process.myUid(), listOf(ctx.packageName), true)
    private val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
    }
    private val expanded = HashSet<String>()
    private val listener: () -> Unit = { refreshSoon { render() } }

    override val view: View = ScrollView(ctx).apply { addView(list) }

    override fun onShow() {
        graph.jobs.addListener(listener)
        render()
    }

    override fun onHide() = graph.jobs.removeListener(listener)

    private fun render() {
        list.removeAllViews()
        val jobs = graph.jobs.snapshot()
        if (jobs.isEmpty()) {
            list.addView(Ui.text(ctx, "No jobs yet. Scripts submitted by this app or by other apps appear here.", 13f, Ui.MUTED))
            return
        }
        jobs.take(100).forEach { list.addView(card(it)) }
    }

    private fun card(rec: JobRecord): View {
        val c = Ui.card(ctx)
        val color = Ui.stateColor(rec.state)
        c.addView(Ui.row(ctx).apply {
            addView(Ui.text(ctx, Ui.shortId(rec.id), 13f, Ui.TEXT, bold = true, mono = true))
            addView(Ui.space(ctx, 0, 10))
            addView(Ui.chip(ctx, JobState.name(rec.state), color))
            addView(Ui.space(ctx, 0, 10))
            addView(Ui.text(ctx, Ui.duration(rec.startedAt.takeIf { it > 0 } ?: rec.createdAt, rec.finishedAt), 12f, Ui.MUTED, mono = true))
        })
        val who = rec.ownerPackages.firstOrNull()?.let { if (it == ctx.packageName) "this app" else it } ?: "uid ${rec.ownerUid}"
        c.addView(Ui.text(ctx, "${rec.scriptName} · $who", 12f, Ui.MUTED))
        if (rec.errorCode != null) {
            c.addView(Ui.text(ctx, "${rec.errorCode}: ${rec.errorMessage ?: ""}".take(240), 12f, if (rec.state == JobState.FAILED) Ui.ERR else Ui.WARN))
        }
        val open = rec.id in expanded
        if (open) {
            val dir = graph.store.jobDir(rec.id)
            c.addView(Ui.heading(ctx, "stdout"))
            c.addView(Ui.codeBox(ctx).apply { text = Ui.readTextTail(File(dir, "stdout.txt"), 4096).ifEmpty { "(empty)" } })
            c.addView(Ui.heading(ctx, "stderr"))
            c.addView(Ui.codeBox(ctx).apply { setTextColor(Ui.ERR); text = Ui.readTextTail(File(dir, "stderr.txt"), 4096).ifEmpty { "(empty)" } })
            if (rec.returnValueJson != null) {
                c.addView(Ui.heading(ctx, "return value"))
                c.addView(Ui.codeBox(ctx).apply { setTextColor(Ui.OK); text = rec.returnValueJson })
            }
            val files = rec.artifacts.filter { it.id.startsWith("f") }
            if (files.isNotEmpty()) {
                c.addView(Ui.heading(ctx, "files"))
                c.addView(Ui.text(ctx, files.joinToString("\n") { "${it.displayName}  (${it.sizeBytes} B)" }, 12.5f, Ui.TEXT, mono = true))
            }
        }
        c.addView(Ui.space(ctx, 8))
        c.addView(Ui.row(ctx).apply {
            addView(Ui.button(ctx, if (open) "Hide" else "Details") { if (open) expanded.remove(rec.id) else expanded.add(rec.id); render() })
            if (!rec.isTerminal) {
                addView(Ui.space(ctx, 0, 8))
                addView(Ui.button(ctx, "Cancel") { activity.background.execute { try { graph.jobs.cancel(owner, rec.id) } catch (_: ServiceError) {} } })
            } else {
                addView(Ui.space(ctx, 0, 8))
                addView(Ui.button(ctx, "Delete") { activity.background.execute { graph.jobs.delete(rec.id) } })
            }
        })
        return c
    }
}
