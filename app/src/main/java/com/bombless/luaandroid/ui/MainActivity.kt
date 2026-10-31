package com.bombless.luaandroid.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bombless.luaandroid.R
import com.bombless.luaandroid.ipc.IJobCallback
import com.bombless.luaandroid.ipc.JobInfo
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.LuaRuntimeService
import com.bombless.luaandroid.LuaAndroidApp

/**
 * Simple in-process console to exercise the runtime without a second app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())

    private val localCallback = object : IJobCallback.Stub() {
        override fun onJobChanged(info: JobInfo) {
            handler.post {
                append("[${info.jobId}] state=${info.state} exit=${info.exitCode}\n")
                if (info.isTerminal()) {
                    info.returnValueJson?.let { append("return: $it\n") }
                    info.errorMessage?.let { append("error: $it\n") }
                    if (info.stdoutBytes > 0 || info.stderrBytes > 0) {
                        try {
                            val jm = LuaAndroidApp.instance.jobManager
                            jm.getArtifactFile(info.jobId, "stdout")?.readText()?.let {
                                if (it.isNotEmpty()) append("--- stdout ---\n$it\n")
                            }
                            jm.getArtifactFile(info.jobId, "stderr")?.readText()?.let {
                                if (it.isNotEmpty()) append("--- stderr ---\n$it\n")
                            }
                        } catch (e: Exception) {
                            append("read artifacts failed: ${e.message}\n")
                        }
                    }
                    append("----\n")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.scriptInput)
        output = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)

        input.setText(
            """
            print("Hello from LuaJ on Android!")
            local sum = 0
            for i = 1, 10 do sum = sum + i end
            print("sum=", sum)
            write_artifact("result.txt", "sum=" .. sum)
            return { ok = true, sum = sum }
            """.trimIndent()
        )

        findViewById<Button>(R.id.btnRun).setOnClickListener { runScript() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            output.text = ""
        }

        append("Lua Android MVP ${packageName}\n")
        append("API version ${LuaRuntimeService.API_VERSION}\n")
        append("Submit a script below. Jobs run via JobManager (same process for this demo).\n\n")
    }

    private fun runScript() {
        val source = input.text?.toString().orEmpty()
        if (source.isBlank()) {
            append("Empty script\n")
            return
        }
        try {
            val req = JobRequest().apply {
                this.source = source
                scriptName = "console.lua"
                timeoutMillis = 15_000L
            }
            val jobId = LuaAndroidApp.instance.jobManager.submit(req)
            LuaAndroidApp.instance.jobManager.registerCallback(jobId, localCallback)
            append("submitted jobId=$jobId\n")
        } catch (e: Exception) {
            append("submit failed: ${e.message}\n")
        }
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
