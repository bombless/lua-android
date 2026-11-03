package com.bombless.luaandroid.rocks

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bombless.luaandroid.BuildConfig
import com.bombless.luaandroid.core.AppSettings
import com.bombless.luaandroid.core.Ids
import com.bombless.luaandroid.core.Limits
import com.bombless.luaandroid.core.Paths
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.RockInfo
import com.bombless.luaandroid.ipc.RockOperation
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the real LuaRocks (bundled, unmodified) inside the native runner's "rocks" mode and exposes it as
 * asynchronous operations. All mutating operations run on ONE thread, which is the package-tree lock
 * (Manual.md section 5.2). Scripts never see any of this: they only `require()` what is installed.
 */
class RockManager(
    private val context: Context,
    private val paths: Paths,
    private val settings: AppSettings,
) {
    val luarocksVersion: String get() = BuildConfig.LUAROCKS_VERSION

    private class Op(val id: String, val kind: String, val target: String?, val createdAt: Long) {
        @Volatile var state = JobState.QUEUED
        @Volatile var startedAt = 0L
        @Volatile var finishedAt = 0L
        @Volatile var exitCode: Int? = null
        @Volatile var errorCode: String? = null
        @Volatile var errorMessage: String? = null
        @Volatile var cancelRequested = false
        private val log = StringBuilder()

        @Synchronized fun append(text: String) {
            log.append(text)
            if (log.length > LOG_CAP) log.delete(0, log.length - LOG_CAP) // keep the tail
        }

        @Synchronized fun logText(): String = log.toString()
    }

    private val bridge = HttpBridge(settings, paths.tmp)
    private val ops = object : LinkedHashMap<String, Op>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Op>?): Boolean = size > 50
    }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rocks-ops").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // --------------------------------------------------------------------------------------------
    // Operations (asynchronous)
    // --------------------------------------------------------------------------------------------

    fun install(name: String?, version: String?, server: String?): String {
        val n = checkName(name)
        val v = checkVersion(version)
        val args = mutableListOf("install")
        if (!server.isNullOrBlank()) {
            HttpBridge.checkUrl(server, settings.allowedHosts, settings.allowLoopbackHttp)?.let { throw ServiceError(ErrorCode.INVALID_REQUEST, "server: $it") }
            args += listOf("--server", server)
        }
        args += n
        if (v != null) args += v
        return enqueue("install", n + (v?.let { " $it" } ?: ""), args)
    }

    fun remove(name: String?, version: String?): String {
        val n = checkName(name)
        val v = checkVersion(version)
        return enqueue("remove", n + (v?.let { " $it" } ?: ""), listOf("remove", n) + listOfNotNull(v))
    }

    /** The operation log holds `luarocks search --porcelain` rows: name, version, arch, server (tab separated). */
    fun search(query: String?): String {
        val q = query?.trim().orEmpty()
        if (!QUERY.matches(q)) throw ServiceError(ErrorCode.INVALID_REQUEST, "invalid search query")
        return enqueue("search", q, listOf("search", "--porcelain", q))
    }

    fun show(name: String?, version: String?): String {
        val n = checkName(name)
        val v = checkVersion(version)
        return enqueue("show", n + (v?.let { " $it" } ?: ""), listOf("show", n) + listOfNotNull(v))
    }

    fun operation(id: String?): RockOperation {
        val op = synchronized(ops) { if (Ids.isOperationId(id)) ops[id!!] else null }
            ?: throw ServiceError(ErrorCode.JOB_NOT_FOUND, "no such operation")
        return snapshot(op)
    }

    fun operations(): List<RockOperation> = synchronized(ops) { ops.values.toList() }.map { snapshot(it) }.sortedByDescending { it.createdAtMillis }

    fun cancel(id: String?): Boolean {
        val op = synchronized(ops) { if (Ids.isOperationId(id)) ops[id!!] else null }
            ?: throw ServiceError(ErrorCode.JOB_NOT_FOUND, "no such operation")
        if (JobState.isTerminal(op.state)) return op.state == JobState.CANCELLED
        op.cancelRequested = true
        if (op.state == JobState.QUEUED) {
            op.state = JobState.CANCELLED
            op.finishedAt = System.currentTimeMillis()
            op.errorCode = ErrorCode.EXECUTION_CANCELLED
            op.errorMessage = "Cancelled before it started."
            notifyListeners()
        }
        return true
    }

    // --------------------------------------------------------------------------------------------
    // Synchronous read-only queries (no network)
    // --------------------------------------------------------------------------------------------

    fun listInstalled(): List<RockInfo> {
        val out = StringBuilder()
        val run = runLuaRocks(listOf("list", "--porcelain"), 30_000, { false }) { out.append(it) }
        if (run.exitCode != 0) throw failure("could not list installed rocks", run.exitCode, out)
        return out.lines().map { it.trim('\r') }.filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 2) null else RockInfo().also { it.name = p[0]; it.version = p[1]; it.status = p.getOrNull(2) ?: "installed" }
        }
    }

    /** What `luarocks path` prints for the private tree. */
    fun rocksPath(): String {
        val out = StringBuilder()
        val run = runLuaRocks(listOf("path"), 30_000, { false }) { out.append(it) }
        if (run.exitCode != 0) throw failure("could not compute the rocks path", run.exitCode, out)
        return out.toString().trim()
    }

    // --------------------------------------------------------------------------------------------
    // Execution
    // --------------------------------------------------------------------------------------------

    private fun enqueue(kind: String, target: String, args: List<String>): String {
        val op = Op(Ids.newOperationId(), kind, target, System.currentTimeMillis())
        synchronized(ops) { ops[op.id] = op }
        executor.execute { execute(op, args) }
        notifyListeners()
        return op.id
    }

    private fun execute(op: Op, args: List<String>) {
        if (op.cancelRequested || op.state != JobState.QUEUED) return
        op.state = JobState.RUNNING
        op.startedAt = System.currentTimeMillis()
        notifyListeners()
        val run = try {
            runLuaRocks(args, OPERATION_TIMEOUT_MS, { op.cancelRequested }) { op.append(it) }
        } catch (t: Throwable) {
            op.append("\n${t.javaClass.simpleName}: ${t.message}\n")
            RockRun(-1, null, timedOut = false, cancelled = false)
        }
        val log = op.logText()
        op.exitCode = run.exitCode
        op.finishedAt = System.currentTimeMillis()
        when {
            run.cancelled -> { op.state = JobState.CANCELLED; op.errorCode = ErrorCode.EXECUTION_CANCELLED; op.errorMessage = "The operation was cancelled." }
            run.timedOut || run.exitCode == 124 -> { op.state = JobState.TIMED_OUT; op.errorCode = ErrorCode.EXECUTION_TIMEOUT; op.errorMessage = "The operation timed out." }
            run.exitCode == 0 -> op.state = JobState.SUCCEEDED
            else -> {
                op.state = JobState.FAILED
                op.errorCode = run.done?.optString("errorCode")?.takeIf { it.isNotEmpty() } ?: classifyFailure(op.kind, log)
                op.errorMessage = run.done?.optString("errorMessage")?.takeIf { it.isNotEmpty() } ?: lastErrorLine(log)
            }
        }
        notifyListeners()
    }

    private fun failure(what: String, exitCode: Int, output: CharSequence): ServiceError {
        Log.w(TAG, "$what (exit $exitCode): ${output.toString().takeLast(1500)}")
        return ServiceError(ErrorCode.INTERNAL_ERROR, "$what (exit $exitCode)${lastErrorLine(output.toString())?.let { ": $it" } ?: ""}")
    }

    private class RockRun(val exitCode: Int, val done: JSONObject?, val timedOut: Boolean, val cancelled: Boolean)

    private fun runLuaRocks(args: List<String>, timeoutMs: Long, isCancelled: () -> Boolean, onLog: (String) -> Unit): RockRun {
        paths.ensureDirs()
        val command = listOf(
            paths.runner.path, "rocks",
            "--runtime", paths.runtime.path,
            "--luarocks", paths.luarocks.path,
            "--timeout-ms", timeoutMs.toString(),
            "--mem-bytes", (256L * 1024 * 1024).toString(),
            "--fsize-bytes", (256L * 1024 * 1024).toString(),
            "--",
        ) + args
        val builder = ProcessBuilder(command).directory(paths.home)
        builder.environment().apply {
            clear()
            put("PATH", "") // there is nothing to execute: process spawning is disabled in the runner
            put("HOME", paths.home.path)
            put("TMPDIR", paths.tmp.path)
            put("LUART_TREE", paths.rocks.path)
            put("LUART_CONF_DIR", paths.etc.path)
            put("LUART_SERVERS", settings.rocksServers.joinToString(" "))
        }
        val process = builder.start()
        val stdin = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        val done = AtomicReference<JSONObject?>(null)

        val stdoutThread = Thread({
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            while (true) {
                val line = readBoundedLine(reader) ?: break
                val marker = line.indexOf('\u0001')
                if (marker < 0) {
                    onLog(line + "\n")
                    continue
                }
                if (marker > 0) onLog(line.substring(0, marker))
                val frame = try { JSONObject(line.substring(marker + 1)) } catch (_: Exception) { continue }
                when (frame.optString("op")) {
                    "http" -> {
                        val reply = bridge.handle(frame).put("id", frame.optInt("id"))
                        try {
                            stdin.write(reply.toString())
                            stdin.write("\n")
                            stdin.flush()
                        } catch (_: java.io.IOException) {
                            // the runner is gone
                        }
                    }
                    "done" -> done.set(frame)
                }
            }
        }, "rocks-stdout").apply { isDaemon = true; start() }
        val stderrThread = Thread({
            val reader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
            while (true) {
                val line = readBoundedLine(reader) ?: break
                onLog(line + "\n")
            }
        }, "rocks-stderr").apply { isDaemon = true; start() }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs + Limits.KILL_GRACE_MILLIS
        var cancelled = false
        var timedOut = false
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            if (!cancelled && !timedOut) {
                if (isCancelled()) { cancelled = true; process.destroyForcibly() }
                else if (SystemClock.elapsedRealtime() > deadline) { timedOut = true; process.destroyForcibly() }
            }
        }
        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        try { stdin.close() } catch (_: java.io.IOException) {}
        return RockRun(process.exitValue(), done.get(), timedOut, cancelled)
    }

    /** readLine() without unbounded memory use: lines longer than the cap are truncated. */
    private fun readBoundedLine(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length < MAX_LINE) sb.append(c.toChar())
        }
    }

    private fun snapshot(op: Op): RockOperation {
        val r = RockOperation()
        r.operationId = op.id
        r.kind = op.kind
        r.target = op.target
        r.state = op.state
        r.createdAtMillis = op.createdAt
        r.startedAtMillis = op.startedAt
        r.finishedAtMillis = op.finishedAt
        r.exitCode = op.exitCode
        r.errorCode = op.errorCode
        r.errorMessage = op.errorMessage
        r.log = op.logText()
        return r
    }

    private fun notifyListeners() = listeners.forEach { try { it() } catch (_: Throwable) {} }

    private fun checkName(name: String?): String {
        if (name == null || !NAME.matches(name)) throw ServiceError(ErrorCode.INVALID_REQUEST, "invalid rock name")
        return name
    }

    private fun checkVersion(version: String?): String? {
        if (version.isNullOrEmpty()) return null
        if (!VERSION.matches(version)) throw ServiceError(ErrorCode.INVALID_REQUEST, "invalid rock version")
        return version
    }

    companion object {
        private const val TAG = "LuaRocks"
        private const val LOG_CAP = 64 * 1024
        private const val MAX_LINE = 64 * 1024
        private const val OPERATION_TIMEOUT_MS = 10L * 60 * 1000
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}")
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
        private val QUERY = Regex("[A-Za-z0-9][A-Za-z0-9 ._+-]{0,99}")

        fun classifyFailure(kind: String, log: String): String = when {
            log.contains("No results matching query") || log.contains("Could not find a rock") || log.contains("Could not find rock") -> ErrorCode.ROCK_NOT_FOUND
            kind == "install" -> ErrorCode.ROCK_INSTALL_FAILED
            else -> ErrorCode.INTERNAL_ERROR
        }

        fun lastErrorLine(log: String): String? {
            val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val line = lines.lastOrNull { it.startsWith("Error:") } ?: lines.lastOrNull()
            return line?.take(500)
        }
    }
}
