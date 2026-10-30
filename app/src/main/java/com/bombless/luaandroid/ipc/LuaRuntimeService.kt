package com.bombless.luaandroid.ipc

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.net.Uri
import com.bombless.luaandroid.core.JobStore
import com.bombless.luaandroid.core.LuaExecutor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class LuaRuntimeService : Service() {
    private lateinit var store: JobStore
    private val executor = Executors.newFixedThreadPool(2)
    private val slots = Semaphore(8)
    private val callbacks = ConcurrentHashMap<String, MutableSet<IJobCallback>>()
    private val running = ConcurrentHashMap<String, Thread>()
    private val lua = LuaExecutor()

    override fun onCreate() {
        super.onCreate()
        store = JobStore(this)
        store.cleanup()
        // A process restart cannot resume a VM; queued/running jobs are made explicitly failed.
        store.list(100, 0).filter { it.state == "QUEUED" || it.state == "RUNNING" }.forEach {
            publish(it.copy(state = "FAILED", finishedAtMillis = System.currentTimeMillis(),
                errorCode = "SERVICE_RESTARTED", errorMessage = "Execution interrupted by service process restart"))
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ILuaRuntimeService.Stub() {
        override fun getApiVersion() = 1
        override fun getRuntimeInfo() = """{"luaEngine":"LuaJ 3.0.1","languageCompatibility":"Lua 5.2 subset","abi":"JVM","nativeModules":false,"luarocks":"not bundled","apiVersion":1}"""
        override fun submit(request: JobRequest): String {
            enforceCaller()
            validate(request)
            if (!slots.tryAcquire()) throw IllegalStateException("QUEUE_FULL")
            val (id, dir) = store.create()
            try {
                stageRequest(request, dir)
                val queued = store.get(id) ?: error("INTERNAL_ERROR")
                publish(queued)
                executor.execute { runJob(id, dir, request.arguments, request.environment, request.timeoutMillis) }
                return id
            } catch (t: Throwable) {
                slots.release()
                dir.deleteRecursively()
                throw t
            }
        }
        override fun getJob(jobId: String): JobInfo? { enforceCaller(); return store.get(jobId) }
        override fun listJobs(limit: Int, beforeCreatedAtMillis: Long): MutableList<JobInfo> {
            enforceCaller(); return store.list(limit, beforeCreatedAtMillis).toMutableList()
        }
        override fun cancel(jobId: String): Boolean {
            enforceCaller()
            val info = store.get(jobId) ?: return false
            if (info.state !in setOf("QUEUED", "RUNNING")) return false
            running[jobId]?.interrupt()
            publish(info.copy(state = "CANCELLED", finishedAtMillis = System.currentTimeMillis(),
                errorCode = "EXECUTION_CANCELLED", errorMessage = "Cancellation requested"))
            return true
        }
        override fun registerCallback(jobId: String, callback: IJobCallback) {
            enforceCaller()
            if (store.get(jobId) == null) throw IllegalArgumentException("JOB_NOT_FOUND")
            callbacks.computeIfAbsent(jobId) { ConcurrentHashMap.newKeySet() }.add(callback)
            callback.asBinder().linkToDeath({ callbacks[jobId]?.remove(callback) }, 0)
        }
        override fun unregisterCallback(jobId: String, callback: IJobCallback) {
            enforceCaller(); callbacks[jobId]?.remove(callback)
        }
        override fun openResult(jobId: String, artifactId: String): ParcelFileDescriptor {
            enforceCaller()
            val file = store.artifact(jobId, artifactId) ?: throw IllegalArgumentException("JOB_NOT_FOUND")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        // Default-deny external UIDs. An owner-controlled consent/allowlist UI is required before
        // enabling third-party clients; the manifest permission is only a coarse gate.
        val allowed = getSharedPreferences("caller_policy", MODE_PRIVATE).getStringSet("allowed_packages", emptySet()).orEmpty()
        if (packages.none { it in allowed }) throw SecurityException("UNAUTHORIZED_CALLER")
    }

    private fun validate(r: JobRequest) {
        require(r.apiVersion == 1) { "API_VERSION_MISMATCH" }
        require((r.source != null) xor (r.scriptFd != null)) { "INVALID_REQUEST: provide exactly one script source" }
        require((r.source?.toByteArray()?.size ?: 0) <= 256 * 1024) { "INPUT_TOO_LARGE" }
        require(r.arguments.size <= 128 && r.arguments.all { it.length <= 4096 }) { "INVALID_REQUEST: arguments" }
        require(r.environment.size <= 64 && r.environment.all { it.key.length <= 128 && it.value.length <= 4096 }) { "INVALID_REQUEST: environment" }
        require(r.inputFiles.size <= 64) { "INVALID_REQUEST: input count" }
        require(r.timeoutMillis in 1_000..300_000) { "INVALID_REQUEST: timeout" }
        require(r.workingDirectory == null || safeRelative(r.workingDirectory)) { "INVALID_REQUEST: working directory" }
        r.inputFiles.forEach { require(safeName(it.name)) { "INVALID_REQUEST: input name" } }
        require(r.scriptName == null || safeName(r.scriptName!!)) { "INVALID_REQUEST: script name" }
    }
    private fun safeName(s: String) = s.isNotBlank() && s.length <= 128 && !s.contains('/') && !s.contains('\\') && !s.contains("..") && !s.contains('\u0000')
    private fun safeRelative(s: String) = s.isNotBlank() && !s.startsWith('/') && s.split('/').all { it.isNotBlank() && it != "." && it != ".." && !it.contains('\\') }

    private fun stageRequest(r: JobRequest, dir: File) {
        val script = File(dir, "script.lua")
        if (r.source != null) script.writeText(r.source)
        else FileInputStream(r.scriptFd!!.fileDescriptor).use { input -> FileOutputStream(script).use { input.copyToBounded(it, 8L * 1024 * 1024) } }
        r.inputFiles.forEach { input ->
            FileInputStream(input.fd.fileDescriptor).use { src ->
                FileOutputStream(File(dir, "input/${input.name}")).use { src.copyToBounded(it, 64L * 1024 * 1024) }
            }
        }
        // PFDs are consumed and staged synchronously before submit returns.
    }
    private fun java.io.InputStream.copyToBounded(out: FileOutputStream, max: Long) {
        val buf = ByteArray(32 * 1024); var total = 0L
        while (true) { val n = read(buf); if (n < 0) break; total += n; require(total <= max) { "INPUT_TOO_LARGE" }; out.write(buf, 0, n) }
    }

    private fun runJob(id: String, dir: File, args: Array<String>, env: Map<String, String>, timeout: Long) {
        try {
            var info = store.get(id) ?: return
            if (info.state == "CANCELLED") return
            info = info.copy(state = "RUNNING", startedAtMillis = System.currentTimeMillis())
            publish(info)
            running[id] = Thread.currentThread()
            val result = lua.execute(File(dir, "script.lua"), dir, args, env, timeout) { _, _ -> }
            val current = store.get(id) ?: return
            if (current.state == "CANCELLED") return
            val state = when (result.errorCode) {
                "EXECUTION_TIMEOUT" -> "TIMED_OUT"
                null -> "SUCCEEDED"
                else -> "FAILED"
            }
            publish(current.copy(state = state, finishedAtMillis = System.currentTimeMillis(),
                exitCode = result.exitCode, returnValueJson = result.json,
                stdoutBytes = File(dir, "output/stdout.txt").length(), stderrBytes = File(dir, "output/stderr.txt").length(),
                errorCode = result.errorCode, errorMessage = result.error,
                artifacts = store.artifacts(id).toTypedArray()))
        } finally { running.remove(id); slots.release() }
    }

    private fun publish(info: JobInfo) {
        store.save(info)
        callbacks[info.jobId]?.toList()?.forEach { cb -> runCatching { cb.onJobChanged(info) }.onFailure { callbacks[info.jobId]?.remove(cb) } }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
