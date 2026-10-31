package com.bombless.luaandroid.ipc

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central job store, persistence and worker dispatch.
 * Matches Manual.md state machine and directory layout.
 */
class JobManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().serializeNulls().create()
    private val jobs = ConcurrentHashMap<String, JobRecord>()
    private val callbacks = ConcurrentHashMap<String, RemoteCallbackList<IJobCallback>>()
    private val mutex = Mutex()

    private val jobsRoot: File
        get() = File(context.filesDir, "lua/jobs").also { it.mkdirs() }

    init {
        // Restore retained jobs from disk (best-effort)
        scope.launch {
            restoreFromDisk()
        }
    }

    fun submit(request: JobRequest): String {
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val jobDir = File(jobsRoot, jobId).also { it.mkdirs() }
        File(jobDir, "input").mkdirs()
        File(jobDir, "output").mkdirs()

        // Stage script
        val scriptFile = File(jobDir, "script.lua")
        when {
            !request.source.isNullOrEmpty() -> {
                if (request.source!!.length > MAX_INLINE_SOURCE) {
                    throw IllegalArgumentException("Inline source exceeds limit ($MAX_INLINE_SOURCE bytes)")
                }
                scriptFile.writeText(request.source!!)
            }
            request.scriptFd != null -> {
                request.scriptFd!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        FileOutputStream(scriptFile).use { output ->
                            val buf = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > MAX_SCRIPT_BYTES) {
                                    throw IllegalArgumentException("Script exceeds size limit")
                                }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("Either source or scriptFd is required")
        }

        val timeout = request.timeoutMillis.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val record = JobRecord(
            info = JobInfo().apply {
                this.jobId = jobId
                state = JobInfo.STATE_QUEUED
                createdAtMillis = now
            },
            jobDir = jobDir,
            timeoutMillis = timeout,
            arguments = request.arguments ?: emptyArray(),
            cancelled = AtomicBoolean(false)
        )
        jobs[jobId] = record
        persist(record)
        notifyCallbacks(record)

        scope.launch {
            runJob(record)
        }
        return jobId
    }

    fun getJob(jobId: String): JobInfo? = jobs[jobId]?.info?.copySnapshot()

    fun listJobs(limit: Int, beforeCreatedAtMillis: Long): List<JobInfo> {
        val cap = limit.coerceIn(1, 100)
        return jobs.values
            .asSequence()
            .map { it.info }
            .filter { beforeCreatedAtMillis <= 0L || it.createdAtMillis < beforeCreatedAtMillis }
            .sortedByDescending { it.createdAtMillis }
            .take(cap)
            .map { it.copySnapshot() }
            .toList()
    }

    fun cancel(jobId: String): Boolean {
        val record = jobs[jobId] ?: return false
        if (record.info.isTerminal()) return true
        record.cancelled.set(true)
        if (record.info.state == JobInfo.STATE_QUEUED) {
            finish(record, JobInfo.STATE_CANCELLED, exitCode = null, errorCode = "CANCELLED", errorMessage = "Cancelled before start")
        }
        return true
    }

    fun registerCallback(jobId: String, callback: IJobCallback) {
        val list = callbacks.getOrPut(jobId) { RemoteCallbackList() }
        list.register(callback)
        // Push current state immediately
        jobs[jobId]?.let { cb ->
            try {
                callback.onJobChanged(cb.info.copySnapshot())
            } catch (_: Exception) {
            }
        }
    }

    fun unregisterCallback(jobId: String, callback: IJobCallback) {
        callbacks[jobId]?.unregister(callback)
    }

    fun openResult(jobId: String, artifactId: String): ParcelFileDescriptor? {
        val record = jobs[jobId] ?: return null
        val safeId = sanitizeName(artifactId) ?: return null
        val file = when (safeId) {
            "stdout" -> File(record.jobDir, "stdout.txt")
            "stderr" -> File(record.jobDir, "stderr.txt")
            else -> File(File(record.jobDir, "output"), safeId)
        }
        if (!file.exists() || !file.isFile) return null
        // Ensure path stays under jobDir
        if (!file.canonicalPath.startsWith(record.jobDir.canonicalPath)) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun getArtifactFile(jobId: String, artifactId: String): File? {
        val record = jobs[jobId] ?: return null
        val safeId = sanitizeName(artifactId) ?: return null
        val file = when (safeId) {
            "stdout" -> File(record.jobDir, "stdout.txt")
            "stderr" -> File(record.jobDir, "stderr.txt")
            else -> File(File(record.jobDir, "output"), safeId)
        }
        if (!file.exists() || !file.canonicalPath.startsWith(record.jobDir.canonicalPath)) return null
        return file
    }

    private suspend fun runJob(record: JobRecord) {
        if (record.cancelled.get()) {
            finish(record, JobInfo.STATE_CANCELLED, null, "CANCELLED", "Cancelled")
            return
        }
        record.info.state = JobInfo.STATE_RUNNING
        record.info.startedAtMillis = System.currentTimeMillis()
        persist(record)
        notifyCallbacks(record)

        val runner = LuaJobRunner(record)
        try {
            val result = runner.execute()
            if (record.cancelled.get()) {
                finish(record, JobInfo.STATE_CANCELLED, null, "CANCELLED", "Cancelled during execution")
            } else if (result.timedOut) {
                finish(record, JobInfo.STATE_TIMED_OUT, -1, "TIMEOUT", "Execution exceeded timeout")
            } else if (result.error != null) {
                finish(record, JobInfo.STATE_FAILED, 1, "RUNTIME", result.error)
            } else {
                record.info.returnValueJson = result.returnValueJson
                record.info.stdoutBytes = result.stdoutBytes
                record.info.stderrBytes = result.stderrBytes
                record.info.truncated = result.truncated
                record.info.artifacts = buildArtifacts(record)
                finish(record, JobInfo.STATE_SUCCEEDED, 0, null, null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Job ${record.info.jobId} crashed", t)
            finish(record, JobInfo.STATE_FAILED, 1, "HOST", t.message ?: "Unexpected error")
        }
    }

    private fun finish(
        record: JobRecord,
        state: String,
        exitCode: Int?,
        errorCode: String?,
        errorMessage: String?
    ) {
        if (record.info.isTerminal()) return
        record.info.state = state
        record.info.exitCode = exitCode
        record.info.errorCode = errorCode
        record.info.errorMessage = errorMessage
        record.info.finishedAtMillis = System.currentTimeMillis()
        // Refresh artifact list for terminal states
        if (record.info.artifacts.isEmpty()) {
            record.info.artifacts = buildArtifacts(record)
        }
        persist(record)
        notifyCallbacks(record)
    }

    private fun buildArtifacts(record: JobRecord): MutableList<JobInfo.ArtifactInfo> {
        val list = mutableListOf<JobInfo.ArtifactInfo>()
        val stdout = File(record.jobDir, "stdout.txt")
        if (stdout.exists()) {
            list.add(JobInfo.ArtifactInfo().apply {
                artifactId = "stdout"
                displayName = "stdout.txt"
                mimeType = "text/plain"
                sizeBytes = stdout.length()
            })
        }
        val stderr = File(record.jobDir, "stderr.txt")
        if (stderr.exists()) {
            list.add(JobInfo.ArtifactInfo().apply {
                artifactId = "stderr"
                displayName = "stderr.txt"
                mimeType = "text/plain"
                sizeBytes = stderr.length()
            })
        }
        val outDir = File(record.jobDir, "output")
        outDir.listFiles()?.forEach { f ->
            if (f.isFile) {
                list.add(JobInfo.ArtifactInfo().apply {
                    artifactId = f.name
                    displayName = f.name
                    mimeType = guessMime(f.name)
                    sizeBytes = f.length()
                })
            }
        }
        return list
    }

    private fun persist(record: JobRecord) {
        try {
            val json = JSONObject().apply {
                put("jobId", record.info.jobId)
                put("state", record.info.state)
                put("createdAtMillis", record.info.createdAtMillis)
                put("startedAtMillis", record.info.startedAtMillis)
                put("finishedAtMillis", record.info.finishedAtMillis)
                put("exitCode", record.info.exitCode)
                put("returnValueJson", record.info.returnValueJson)
                put("stdoutBytes", record.info.stdoutBytes)
                put("stderrBytes", record.info.stderrBytes)
                put("truncated", record.info.truncated)
                put("errorCode", record.info.errorCode)
                put("errorMessage", record.info.errorMessage)
            }
            File(record.jobDir, "job.json").writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist job ${record.info.jobId}", e)
        }
    }

    private fun restoreFromDisk() {
        val root = jobsRoot
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val meta = File(dir, "job.json")
            if (!meta.exists()) return@forEach
            try {
                val obj = JSONObject(meta.readText())
                val info = JobInfo().apply {
                    jobId = obj.optString("jobId", dir.name)
                    state = obj.optString("state", JobInfo.STATE_FAILED)
                    createdAtMillis = obj.optLong("createdAtMillis")
                    startedAtMillis = obj.optLong("startedAtMillis")
                    finishedAtMillis = obj.optLong("finishedAtMillis")
                    if (obj.has("exitCode") && !obj.isNull("exitCode")) {
                        exitCode = obj.getInt("exitCode")
                    }
                    returnValueJson = if (obj.has("returnValueJson") && !obj.isNull("returnValueJson")) obj.getString("returnValueJson") else null
                    stdoutBytes = obj.optLong("stdoutBytes")
                    stderrBytes = obj.optLong("stderrBytes")
                    truncated = obj.optBoolean("truncated")
                    errorCode = if (obj.has("errorCode") && !obj.isNull("errorCode")) obj.getString("errorCode") else null
                    errorMessage = if (obj.has("errorMessage") && !obj.isNull("errorMessage")) obj.getString("errorMessage") else null
                }
                // Non-terminal jobs left from a crash become FAILED
                if (!info.isTerminal()) {
                    info.state = JobInfo.STATE_FAILED
                    info.errorCode = "RESTART"
                    info.errorMessage = "Service restarted while job was active"
                    info.finishedAtMillis = System.currentTimeMillis()
                }
                val record = JobRecord(
                    info = info,
                    jobDir = dir,
                    timeoutMillis = MAX_TIMEOUT_MS,
                    arguments = emptyArray(),
                    cancelled = AtomicBoolean(false)
                )
                record.info.artifacts = buildArtifacts(record)
                jobs[info.jobId] = record
            } catch (e: Exception) {
                Log.w(TAG, "Skip corrupt job dir ${dir.name}", e)
            }
        }
    }

    private fun notifyCallbacks(record: JobRecord) {
        val list = callbacks[record.info.jobId] ?: return
        val snapshot = record.info.copySnapshot()
        val n = list.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    list.getBroadcastItem(i).onJobChanged(snapshot)
                } catch (_: Exception) {
                }
            }
        } finally {
            list.finishBroadcast()
        }
    }

    private fun JobInfo.copySnapshot(): JobInfo {
        val c = JobInfo()
        c.jobId = jobId
        c.state = state
        c.createdAtMillis = createdAtMillis
        c.startedAtMillis = startedAtMillis
        c.finishedAtMillis = finishedAtMillis
        c.exitCode = exitCode
        c.returnValueJson = returnValueJson
        c.stdoutBytes = stdoutBytes
        c.stderrBytes = stderrBytes
        c.truncated = truncated
        c.errorCode = errorCode
        c.errorMessage = errorMessage
        c.artifacts = artifacts.map { a ->
            JobInfo.ArtifactInfo().apply {
                artifactId = a.artifactId
                displayName = a.displayName
                mimeType = a.mimeType
                sizeBytes = a.sizeBytes
                contentUri = a.contentUri
            }
        }.toMutableList()
        return c
    }

    companion object {
        private const val TAG = "JobManager"
        const val MAX_INLINE_SOURCE = 256 * 1024
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        const val MAX_TIMEOUT_MS = 5 * 60 * 1000L
        const val MAX_OUTPUT_BYTES = 512 * 1024L

        fun sanitizeName(name: String?): String? {
            if (name.isNullOrBlank()) return null
            if (name.contains("..") || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
                return null
            }
            return name
        }

        fun guessMime(name: String): String = when {
            name.endsWith(".txt") || name.endsWith(".log") -> "text/plain"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".lua") -> "text/x-lua"
            else -> "application/octet-stream"
        }
    }
}

data class JobRecord(
    val info: JobInfo,
    val jobDir: File,
    val timeoutMillis: Long,
    val arguments: Array<String>,
    val cancelled: AtomicBoolean
)
