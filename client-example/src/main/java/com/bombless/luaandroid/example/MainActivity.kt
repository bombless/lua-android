package com.bombless.luaandroid.example

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.IJobCallback
import com.bombless.luaandroid.ipc.ILuaRuntimeService
import com.bombless.luaandroid.ipc.JobInfo
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.LuaRuntimeClient
import java.util.concurrent.Executors

/**
 * Minimal client of the Lua Android service (Manual.md section 6). Everything here uses only the
 * published API: bind -> submit -> callback (with polling as the fallback) -> read artifacts.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: LuaRuntimeClient
    private lateinit var source: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var run: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = LuaRuntimeClient(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(0xFF1A1B26.toInt())
        }
        column.addView(TextView(this).apply { text = "Lua client example"; textSize = 20f; setTextColor(0xFFC0CAF5.toInt()); setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { setTextColor(0xFF8089B3.toInt()); text = "Connecting to Lua Android…" }
        column.addView(status)
        source = EditText(this).apply {
            setText("print('hello from another app')\nreturn { answer = 42, args = {...} }")
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFC0CAF5.toInt())
            setBackgroundColor(0xFF16161E.toInt())
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        column.addView(source, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        run = Button(this).apply { text = "Run in Lua Android"; setOnClickListener { submit() } }
        column.addView(run)
        output = TextView(this).apply { typeface = Typeface.MONOSPACE; setTextColor(0xFF9ECE6A.toInt()); setTextIsSelectable(true) }
        column.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
    }

    override fun onStart() {
        super.onStart()
        val ok = client.bind(object : LuaRuntimeClient.Listener {
            override fun onConnected(runtime: ILuaRuntimeService) = ui.post { status.text = "Connected (API ${runtime.apiVersion})" }.let {}
            override fun onDisconnected() { ui.post { status.text = "Disconnected - will reconnect" } }
        })
        if (!ok) status.text = "Lua Android is not installed (or not visible: see <queries> in the manifest)."
    }

    override fun onStop() {
        client.unbind()
        super.onStop()
    }

    private fun submit() {
        val runtime = client.runtime ?: run { output.text = "Not connected yet."; return }
        run.isEnabled = false
        output.text = ""
        worker.execute {
            try {
                val request = JobRequest().apply {
                    apiVersion = 1
                    source = this@MainActivity.source.text.toString()
                    arguments = arrayOf("one", "two")
                    timeoutMillis = 10_000
                }
                val jobId = runtime.submit(request)
                show("submitted ${jobId.take(8)} …")
                val info = LuaRuntimeClient.waitForTerminal(runtime, jobId, 30_000) // callbacks are better; polling is the fallback
                val stdout = LuaRuntimeClient.readArtifactText(runtime, jobId, ApiLimits.ARTIFACT_STDOUT)
                show(buildString {
                    append(JobState.name(info.state))
                    info.errorCode?.let { append("  ").append(it).append(": ").append(info.errorMessage) }
                    append("\n\nstdout:\n").append(stdout)
                    append("\nreturn value: ").append(info.returnValueJson ?: "(none)")
                })
            } catch (e: SecurityException) {
                show("Not allowed yet.\nOpen Lua Android -> Access and approve this app.\n\n${ErrorCode.detail(e)}")
            } catch (e: Exception) {
                show("${ErrorCode.fromException(e) ?: e.javaClass.simpleName}: ${ErrorCode.detail(e)}")
            } finally {
                ui.post { run.isEnabled = true }
            }
        }
    }

    private fun show(text: String) = ui.post { output.text = text }.let {}

    /** Example of the callback API: registers for one job and updates the status line. */
    @Suppress("unused")
    private fun watch(runtime: ILuaRuntimeService, jobId: String) {
        runtime.registerCallback(jobId, object : IJobCallback.Stub() {
            override fun onJobChanged(info: JobInfo) {
                ui.post { status.text = "job ${info.jobId.take(8)}: ${JobState.name(info.state)}" }
            }
        })
    }
}
