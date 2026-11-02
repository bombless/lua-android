package com.bombless.luaandroid.ui

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.core.JobRecord
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.JobState

/** Write a script, run it through the same path external apps use, watch stdout live. */
class RunPanel(activity: MainActivity) : Panel(activity) {
    private val ctx = activity
    private val graph get() = activity.graph
    private val owner get() = Caller(Process.myUid(), listOf(ctx.packageName), true)
    private val ui = Handler(Looper.getMainLooper())

    private val editor = Ui.input(ctx, "-- Lua 5.4", multiline = true, mono = true).apply {
        minLines = 9
        setText(Samples.all.first().source)
    }
    private val statusChip = Ui.chip(ctx, "idle", Ui.MUTED)
    private val statusText = Ui.text(ctx, "", 12f, Ui.MUTED, mono = true)
    private val runButton = Ui.button(ctx, "Run", primary = true) { run() }
    private val cancelButton = Ui.button(ctx, "Cancel") { cancel() }.apply { isEnabled = false }
    private val stdoutBox = Ui.codeBox(ctx)
    private val stderrBox = Ui.codeBox(ctx).apply { setTextColor(Ui.ERR) }
    private val resultBox = Ui.codeBox(ctx).apply { setTextColor(Ui.OK) }
    private val filesBox = Ui.text(ctx, "", 12.5f, Ui.TEXT, mono = true)
    private var timeoutMillis = ApiLimits.DEFAULT_TIMEOUT_MILLIS
    private val timeoutButtons = mutableListOf<Pair<Long, TextView>>()
    private var currentJobId: String? = null

    private val listener: () -> Unit = { refreshSoon(100) { refreshJob() } }
    private val ticker = object : Runnable {
        override fun run() {
            refreshJob()
            val rec = currentJobId?.let { graph.jobs.recordOrNull(it) }
            if (rec != null && !rec.isTerminal) ui.postDelayed(this, 300)
        }
    }

    override val view: View = ScrollView(ctx).apply {
        isFillViewport = true
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        col.addView(Ui.heading(ctx, "Examples"))
        col.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                Samples.all.forEach { s ->
                    addView(Ui.button(ctx, s.title) { editor.setText(s.source) }.apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = Ui.dp(ctx, 8)
                    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Ui.dp(ctx, 8) })
                }
            })
        })
        col.addView(Ui.space(ctx, 10))
        col.addView(editor)
        col.addView(Ui.space(ctx, 10))
        col.addView(Ui.row(ctx).apply {
            addView(Ui.text(ctx, "Timeout", 12f, Ui.MUTED))
            addView(Ui.space(ctx, 0, 8))
            for ((label, ms) in listOf("3 s" to 3_000L, "15 s" to 15_000L, "60 s" to 60_000L)) {
                val tv = Ui.chip(ctx, label, Ui.MUTED).apply { setOnClickListener { setTimeout(ms) } }
                timeoutButtons += ms to tv
                addView(tv)
                addView(Ui.space(ctx, 0, 6))
            }
        })
        setTimeout(timeoutMillis)
        col.addView(Ui.space(ctx, 10))
        col.addView(Ui.row(ctx).apply {
            addView(runButton)
            addView(Ui.space(ctx, 0, 8))
            addView(cancelButton)
            addView(Ui.space(ctx, 0, 12))
            addView(statusChip)
        })
        col.addView(Ui.space(ctx, 4))
        col.addView(statusText)
        col.addView(Ui.heading(ctx, "stdout"))
        col.addView(stdoutBox)
        col.addView(Ui.heading(ctx, "stderr"))
        col.addView(stderrBox)
        col.addView(Ui.heading(ctx, "return value (JSON)"))
        col.addView(resultBox)
        col.addView(Ui.heading(ctx, "files in output/"))
        col.addView(filesBox)
        addView(col)
    }

    private fun setTimeout(ms: Long) {
        timeoutMillis = ms
        timeoutButtons.forEach { (value, tv) ->
            tv.setTextColor(if (value == ms) Ui.ACCENT else Ui.MUTED)
            tv.background = Ui.rounded(ctx, if (value == ms) 0x337AA2F7 else 0x33000000, 8)
        }
    }

    override fun onShow() {
        graph.jobs.addListener(listener)
        ui.post(ticker)
    }

    override fun onHide() {
        graph.jobs.removeListener(listener)
        ui.removeCallbacks(ticker)
    }

    private fun run() {
        val code = editor.text.toString()
        runButton.isEnabled = false
        statusChip.text = "submitting"
        activity.background.execute {
            try {
                graph.ensureReady()
                val req = JobRequest().apply {
                    source = code
                    scriptName = "console.lua"
                    timeoutMillis = this@RunPanel.timeoutMillis
                    arguments = arrayOf("alpha", "beta")
                }
                val id = graph.jobs.submit(owner, req)
                currentJobId = id
                ctx.runOnUiThread { ui.removeCallbacks(ticker); ui.post(ticker) }
            } catch (e: ServiceError) {
                ctx.runOnUiThread { Ui.toast(ctx, e.message ?: "rejected"); runButton.isEnabled = true; statusChip.text = "rejected" }
            } catch (t: Throwable) {
                ctx.runOnUiThread { Ui.toast(ctx, "Failed: ${t.message}"); runButton.isEnabled = true }
            }
        }
    }

    private fun cancel() {
        val id = currentJobId ?: return
        activity.background.execute { try { graph.jobs.cancel(owner, id) } catch (_: ServiceError) {} }
    }

    private fun refreshJob() {
        val id = currentJobId ?: return
        val rec: JobRecord = graph.jobs.recordOrNull(id) ?: return
        val color = Ui.stateColor(rec.state)
        statusChip.text = JobState.name(rec.state)
        statusChip.setTextColor(color)
        statusChip.background = Ui.rounded(ctx, (color and 0x00FFFFFF) or 0x33000000, 8)
        runButton.isEnabled = rec.isTerminal
        cancelButton.isEnabled = !rec.isTerminal
        val dir = graph.store.jobDir(id)
        stdoutBox.text = Ui.readTextTail(java.io.File(dir, "stdout.txt"))
        stderrBox.text = Ui.readTextTail(java.io.File(dir, "stderr.txt"))
        statusText.text = buildString {
            append(Ui.shortId(id)).append("  ").append(Ui.duration(rec.startedAt.takeIf { it > 0 } ?: rec.createdAt, rec.finishedAt))
            if (rec.exitCode != null) append("  exit ").append(rec.exitCode)
            if (rec.errorCode != null) append("\n").append(rec.errorCode).append(": ").append(rec.errorMessage ?: "")
            if (rec.truncated) append("\n(output was truncated at the cap)")
        }
        resultBox.text = rec.returnValueJson
            ?: rec.artifacts.firstOrNull { it.id == ApiLimits.ARTIFACT_RESULT }?.let { "(${it.sizeBytes} bytes - see the result artifact)" }
            ?: if (rec.isTerminal) "(none)" else ""
        val files = rec.artifacts.filter { it.id.startsWith("f") }
        filesBox.text = if (files.isEmpty()) (if (rec.isTerminal) "(none)" else "") else files.joinToString("\n") { "${it.displayName}  (${it.sizeBytes} B)" }
    }
}
