package com.bombless.luaandroid.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ArtifactInfo
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.IJobCallback
import com.bombless.luaandroid.ipc.JobInfo
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ipc.JobState
import com.bombless.luaandroid.ipc.LuaRuntimeContract
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the job life cycle (Manual.md section 3.2):
 *
 *     QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED | TIMED_OUT        QUEUED -> CANCELLED
 *
 *  - submit() validates and stages everything (script, input descriptors) into the job directory and
 *    persists the QUEUED record before returning, so a queued job survives a process death;
 *  - a small worker pool runs one runner process per job (see [RunnerLauncher]);
 *  - every transition is persisted first, then broadcast to callbacks and UI listeners;
 *  - job IDs are unguessable and every lookup checks ownership: a caller can only see its own jobs
 *    (the host app's own UI is the "owner" and sees all).
 */
class JobManager(
    private val context: Context,
    private val paths: Paths,
    private val store: JobStore,
    private val launcher: RunnerLauncher,
    private val settings: AppSettings,
) {
    private val lock = Any()
    private val records = HashMap<String, JobRecord>()
    private val controls = ConcurrentHashMap<String, RunControl>()
    private val callbacks = ConcurrentHashMap<String, RemoteCallbackList<IJobCallback>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var reserved = 0
    private val threadCounter = AtomicInteger()

    /** Invoked (on a worker thread) when no job is queued or running any more. */
    @Volatile var idleListener: (() -> Unit)? = null

    private val workers = ThreadPoolExecutor(
        settings.maxConcurrentJobs, settings.maxConcurrentJobs, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, "lua-job-${threadCounter.incrementAndGet()}").apply { isDaemon = true } },
    )

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // ------------------------------------------------------------------------------------------
    // Submit
    // ------------------------------------------------------------------------------------------

    fun submit(caller: Caller, req: JobRequest): String {
        val env = LinkedHashMap<String, String>()
        for (k in req.environment.keySet()) {
            env[k] = req.environment.getString(k) ?: throw ServiceError(ErrorCode.INVALID_REQUEST, "environment values must be strings")
        }
        try {
            val v = RequestValidator.validate(
                apiVersion = req.apiVersion,
                source = req.source,
                hasScriptFd = req.scriptFd != null,
                scriptName = req.scriptName,
                arguments = req.arguments.toList(),
                environment = env,
                workingDirectory = req.workingDirectory,
                timeoutMillis = req.timeoutMillis,
                memoryLimitBytes = req.memoryLimitBytes,
                inputNames = req.inputFiles.map { it.name },
                maxTimeoutMillis = settings.maxTimeoutMillis,
            )
            reserveSlot(caller)
            var slotReleased = false
            val id = Ids.newJobId()
            val dir = store.jobDir(id)
            val rec: JobRecord
            try {
                stage(dir, id, v, req)
                rec = JobRecord(
                    id = id, state = JobState.QUEUED, ownerUid = caller.uid, ownerPackages = caller.packages,
                    createdAt = System.currentTimeMillis(), scriptName = v.scriptName,
                    timeoutMillis = v.timeoutMillis, memoryLimitBytes = v.memoryLimitBytes,
                )
                synchronized(lock) {
                    records[id] = rec
                    store.save(rec)
                    reserved--
                    slotReleased = true
                }
            } catch (t: Throwable) {
                if (!slotReleased) synchronized(lock) { reserved-- }
                store.delete(id)
                throw if (t is ServiceError) t else ServiceError(ErrorCode.INTERNAL_ERROR, "could not stage the job: ${t.message}")
            }
            workers.execute { runJob(id) }
            notifyChanged(rec)
            return id
        } finally {
            closeDescriptors(req)
        }
    }

    private fun reserveSlot(caller: Caller) {
        synchronized(lock) {
            val active = records.values.count { !it.isTerminal }
            if (active + reserved >= Limits.MAX_QUEUED_TOTAL) {
                throw ServiceError(ErrorCode.QUEUE_FULL, "the service queue is full ($active active jobs); try again later")
            }
            val mine = records.values.count { !it.isTerminal && it.ownerUid == caller.uid }
            if (mine >= Limits.MAX_ACTIVE_PER_CALLER) {
                throw ServiceError(ErrorCode.QUEUE_FULL, "too many active jobs for this caller (limit ${Limits.MAX_ACTIVE_PER_CALLER})")
            }
            reserved++
        }
    }

    private fun closeDescriptors(req: JobRequest) {
        try { req.scriptFd?.close() } catch (_: IOException) {}
        for (f in req.inputFiles) try { f.fd?.close() } catch (_: IOException) {}
    }

    private fun stage(dir: File, id: String, v: ValidatedRequest, req: JobRequest) {
        if (!dir.mkdirs()) throw IOException("cannot create the job directory")
        val inputDir = File(dir, "input").also { it.mkdir() }
        File(dir, "output").mkdir()
        File(dir, v.workingDirectory).mkdirs()
        val script = File(dir, "script.lua")
        if (v.source != null) {
            script.writeBytes(v.source.toByteArray(Charsets.UTF_8))
        } else {
            copyDescriptor(req.scriptFd!!, script, ApiLimits.MAX_SCRIPT_FILE_BYTES, "scriptFd")
        }
        var total = 0L
        for (f in req.inputFiles) {
            val pfd = f.fd ?: throw ServiceError(ErrorCode.INVALID_REQUEST, "input file '${f.name}' has no descriptor")
            val budget = minOf(ApiLimits.MAX_INPUT_FILE_BYTES, ApiLimits.MAX_TOTAL_INPUT_BYTES - total)
            total += copyDescriptor(pfd, File(inputDir, f.name), budget, "input file '${f.name}'")
        }
        File(dir, "spec.lua").writeText(LuaLiteral.spec(id, v.scriptName, v.arguments, v.environment, v.workingDirectory))
    }

    /**
     * Copies a client-supplied descriptor. Only regular files are accepted (a pipe could block a Binder
     * thread forever) and positional reads are used, so the client's file offset is neither relied upon
     * nor changed.
     */
    private fun copyDescriptor(pfd: ParcelFileDescriptor, dest: File, maxBytes: Long, what: String): Long {
        val fd = pfd.fileDescriptor
        val st = try {
            Os.fstat(fd)
        } catch (e: ErrnoException) {
            throw ServiceError(ErrorCode.INVALID_REQUEST, "$what: the descriptor cannot be read")
        }
        if (!OsConstants.S_ISREG(st.st_mode)) {
            throw ServiceError(ErrorCode.INVALID_REQUEST, "$what must be a regular file (copy streams to a file first)")
        }
        if (st.st_size > maxBytes) {
            throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "$what is larger than the allowed ${maxBytes / (1024 * 1024)} MiB")
        }
        val buf = ByteArray(64 * 1024)
        var offset = 0L
        dest.outputStream().use { out ->
            while (true) {
                val n = try {
                    Os.pread(fd, buf, 0, buf.size, offset)
                } catch (e: ErrnoException) {
                    throw ServiceError(ErrorCode.INVALID_REQUEST, "$what: read failed (${e.message})")
                }
                if (n <= 0) break
                offset += n
                if (offset > maxBytes) throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "$what grew beyond the allowed size while being copied")
                out.write(buf, 0, n)
            }
        }
        return offset
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    private fun runJob(id: String) {
        val control = controls.getOrPut(id) { RunControl() }
        val running: JobRecord
        synchronized(lock) {
            val cur = records[id] ?: return
            if (cur.state != JobState.QUEUED) return // cancelled while it was waiting
            running = cur.copy(state = JobState.RUNNING, startedAt = System.currentTimeMillis())
            records[id] = running
            store.save(running)
        }
        notifyChanged(running)

        val dir = store.jobDir(id)
        val outcome = try {
            launcher.run(dir, running.timeoutMillis, running.memoryLimitBytes, settings.streamCapBytes, control)
        } catch (t: Throwable) {
            Log.e(TAG, "runner supervision failed for $id", t)
            RunOutcome(-1, startFailure = "Internal error while running the job: ${t.javaClass.simpleName}")
        }
        val finished = try {
            ResultCollector.collect(running, dir, outcome, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.e(TAG, "collecting results failed for $id", t)
            running.copy(state = JobState.FAILED, finishedAt = System.currentTimeMillis(), errorCode = ErrorCode.INTERNAL_ERROR, errorMessage = "Could not collect the job results.")
        }
        synchronized(lock) {
            records[id] = finished
            store.save(finished)
        }
        afterTerminal(finished)
    }

    private fun afterTerminal(rec: JobRecord) {
        val dir = store.jobDir(rec.id)
        // Inputs can be huge and the spec holds arguments/environment: neither is needed after the run.
        File(dir, "input").deleteRecursively()
        File(dir, "tmp").deleteRecursively()
        File(dir, "spec.lua").delete()
        File(dir, "runner.pid").delete()
        grantReadAccess(rec)
        notifyChanged(rec)
        callbacks.remove(rec.id)?.kill()
        controls.remove(rec.id)
        pruneExpired()
        if (!hasActiveJobs()) idleListener?.invoke()
    }

    // ------------------------------------------------------------------------------------------
    // Queries and commands (all ownership checked)
    // ------------------------------------------------------------------------------------------

    private fun recordFor(caller: Caller, id: String?): JobRecord {
        if (!Ids.isJobId(id)) throw ServiceError(ErrorCode.JOB_NOT_FOUND, "no such job")
        val rec = synchronized(lock) { records[id!!] }
        // Same answer for "missing" and "belongs to someone else": do not reveal which job IDs exist.
        if (rec == null || !(caller.isOwner || rec.ownerUid == caller.uid)) throw ServiceError(ErrorCode.JOB_NOT_FOUND, "no such job")
        return rec
    }

    fun get(caller: Caller, id: String?): JobInfo {
        val rec = recordFor(caller, id)
        if (rec.isTerminal && !caller.isOwner) grantReadAccess(rec) // grants do not survive a reboot
        return buildInfo(rec)
    }

    fun list(caller: Caller, limit: Int, beforeCreatedAtMillis: Long): List<JobInfo> {
        val max = limit.coerceIn(1, 100)
        val visible = synchronized(lock) {
            records.values.filter { (caller.isOwner || it.ownerUid == caller.uid) && (beforeCreatedAtMillis <= 0 || it.createdAt < beforeCreatedAtMillis) }
        }
        return visible.sortedByDescending { it.createdAt }.take(max).map { buildInfo(it) }
    }

    fun cancel(caller: Caller, id: String?): Boolean {
        val rec = recordFor(caller, id)
        var cancelledWhileQueued: JobRecord? = null
        val accepted = synchronized(lock) {
            val cur = records[rec.id] ?: return false
            when (cur.state) {
                JobState.QUEUED -> {
                    val updated = cur.copy(
                        state = JobState.CANCELLED, finishedAt = System.currentTimeMillis(),
                        errorCode = ErrorCode.EXECUTION_CANCELLED, errorMessage = "The job was cancelled before it started.",
                    )
                    records[rec.id] = updated
                    store.save(updated)
                    cancelledWhileQueued = updated
                    true
                }
                JobState.RUNNING -> {
                    controls.getOrPut(rec.id) { RunControl() }.cancelRequested = true
                    true
                }
                JobState.CANCELLED -> true
                else -> false
            }
        }
        cancelledWhileQueued?.let { afterTerminal(it) }
        return accepted
    }

    fun registerCallback(caller: Caller, id: String?, cb: IJobCallback) {
        val rec = recordFor(caller, id)
        val list = callbacks.getOrPut(rec.id) { RemoteCallbackList() }
        list.register(cb)
        val cur = synchronized(lock) { records[rec.id] } ?: return
        if (cur.isTerminal) {
            // Late registration: deliver the final state once (callbacks must be idempotent anyway).
            try { cb.onJobChanged(buildInfo(cur)) } catch (_: RemoteException) {}
            list.unregister(cb)
        }
    }

    fun unregisterCallback(caller: Caller, id: String?, cb: IJobCallback) {
        val rec = recordFor(caller, id)
        callbacks[rec.id]?.unregister(cb)
    }

    /** Read-only descriptor for an artifact. While a job runs, "stdout" and "stderr" can already be read. */
    fun openArtifact(caller: Caller, id: String?, artifactId: String?): ParcelFileDescriptor {
        val rec = recordFor(caller, id)
        val file = resolveArtifactFile(rec, artifactId) ?: throw ServiceError(ErrorCode.JOB_NOT_FOUND, "no such artifact")
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw ServiceError(ErrorCode.JOB_NOT_FOUND, "the artifact is no longer available")
        }
    }

    /** Looks an opaque artifact ID up in the record (never builds a path from client input). */
    fun resolveArtifactFile(rec: JobRecord, artifactId: String?): File? {
        if (!Ids.isArtifactId(artifactId)) return null
        val dir = store.jobDir(rec.id)
        val relative = rec.artifacts.firstOrNull { it.id == artifactId }?.path
            ?: if (!rec.isTerminal && artifactId == ApiLimits.ARTIFACT_STDOUT) "stdout.txt"
            else if (!rec.isTerminal && artifactId == ApiLimits.ARTIFACT_STDERR) "stderr.txt"
            else return null
        val file = File(dir, relative)
        // Defence in depth: the file must really be inside the job directory.
        val base = dir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(base) || !file.isFile) return null
        return file
    }

    fun recordOrNull(id: String): JobRecord? = if (Ids.isJobId(id)) synchronized(lock) { records[id] } else null

    /** Snapshot for the owner UI, newest first. */
    fun snapshot(): List<JobRecord> = synchronized(lock) { records.values.sortedByDescending { it.createdAt } }

    fun hasActiveJobs(): Boolean = synchronized(lock) { reserved > 0 || records.values.any { !it.isTerminal } }

    /** Owner UI: deletes a finished job and all its files. */
    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            val rec = records[id] ?: return false
            if (!rec.isTerminal) return false
            records.remove(id)
            true
        }
        if (removed) deleteFiles(id)
        notifyListeners()
        return removed
    }

    // ------------------------------------------------------------------------------------------
    // Info objects, URI grants, notifications
    // ------------------------------------------------------------------------------------------

    fun buildInfo(rec: JobRecord): JobInfo {
        val info = JobInfo()
        info.jobId = rec.id
        info.state = rec.state
        info.createdAtMillis = rec.createdAt
        info.startedAtMillis = rec.startedAt
        info.finishedAtMillis = rec.finishedAt
        info.exitCode = rec.exitCode
        info.returnValueJson = rec.returnValueJson
        info.stdoutBytes = rec.stdoutBytes
        info.stderrBytes = rec.stderrBytes
        info.truncated = rec.truncated
        info.errorCode = rec.errorCode
        info.errorMessage = rec.errorMessage
        info.artifacts = rec.artifacts.map { a ->
            val ai = ArtifactInfo()
            ai.artifactId = a.id
            ai.displayName = a.displayName
            ai.mimeType = a.mimeType
            ai.sizeBytes = a.sizeBytes
            ai.contentUri = artifactUri(rec.id, a.id)
            ai
        }.toTypedArray()
        return info
    }

    private fun artifactUri(jobId: String, artifactId: String): Uri =
        Uri.Builder().scheme("content").authority(LuaRuntimeContract.RESULT_AUTHORITY).appendPath(jobId).appendPath(artifactId).build()

    private fun jobBaseUri(jobId: String): Uri =
        Uri.Builder().scheme("content").authority(LuaRuntimeContract.RESULT_AUTHORITY).appendPath(jobId).build()

    /** One prefix grant per job and owner package: covers every artifact URI of that job and nothing else. */
    private fun grantReadAccess(rec: JobRecord) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        for (pkg in rec.ownerPackages) {
            if (pkg == context.packageName) continue
            try {
                context.grantUriPermission(pkg, jobBaseUri(rec.id), flags)
            } catch (e: Exception) {
                Log.w(TAG, "cannot grant $pkg access to job ${rec.id}: ${e.message}")
            }
        }
    }

    private fun notifyChanged(rec: JobRecord) {
        callbacks[rec.id]?.let { list ->
            val info = buildInfo(rec)
            val n = list.beginBroadcast()
            try {
                for (i in 0 until n) {
                    try {
                        list.getBroadcastItem(i).onJobChanged(info)
                    } catch (_: RemoteException) {
                        // the callback's process died: RemoteCallbackList drops it
                    }
                }
            } finally {
                list.finishBroadcast()
            }
        }
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { l ->
        try { l() } catch (t: Throwable) { Log.w(TAG, "listener failed", t) }
    }

    // ------------------------------------------------------------------------------------------
    // Recovery and retention
    // ------------------------------------------------------------------------------------------

    /**
     * Rebuilds state after a process start: QUEUED jobs are queued again, jobs that were RUNNING are
     * finished from whatever the runner left behind (or FAILED when it left nothing), orphaned runner
     * processes are killed, half-staged directories are removed, expired jobs are pruned.
     */
    fun recover() {
        val now = System.currentTimeMillis()
        val rerun = ArrayList<JobRecord>()
        for (id in store.listDirectoryIds()) {
            val dir = store.jobDir(id)
            val rec = store.load(id)
            if (rec == null) {
                store.delete(id) // submit() died while staging
                continue
            }
            var current = rec
            when (rec.state) {
                JobState.QUEUED -> {
                    if (File(dir, "script.lua").isFile && File(dir, "spec.lua").isFile) {
                        rerun += rec
                    } else {
                        current = rec.copy(state = JobState.FAILED, finishedAt = now, errorCode = ErrorCode.INTERNAL_ERROR, errorMessage = "The staged files of this job are missing.")
                        store.save(current)
                    }
                }
                JobState.RUNNING -> {
                    RunnerLauncher.killOrphan(dir)
                    current = ResultCollector.collect(
                        rec, dir, RunOutcome(-1), now,
                        interruptedMessage = "The Lua runtime service restarted while this job was running.",
                    )
                    store.save(current)
                    File(dir, "input").deleteRecursively()
                    File(dir, "tmp").deleteRecursively()
                    File(dir, "spec.lua").delete()
                    File(dir, "runner.pid").delete()
                }
            }
            synchronized(lock) { records[id] = current }
        }
        pruneExpired()
        rerun.sortedBy { it.createdAt }.forEach { r -> workers.execute { runJob(r.id) } }
        notifyListeners()
    }

    fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.retentionDays.toLong())
        val victims = ArrayList<String>()
        synchronized(lock) {
            val terminal = records.values.filter { it.isTerminal }.sortedBy { it.finishedAt }
            terminal.filter { it.finishedAt in 1 until cutoff }.forEach { victims += it.id }
            val overflow = terminal.size - victims.size - settings.maxRetainedJobs
            if (overflow > 0) terminal.filter { it.id !in victims }.take(overflow).forEach { victims += it.id }
            victims.forEach { records.remove(it) }
        }
        victims.forEach { deleteFiles(it) }
    }

    private fun deleteFiles(id: String) {
        try {
            context.revokeUriPermission(jobBaseUri(id), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        } catch (_: Exception) {
        }
        store.delete(id)
    }

    private companion object {
        const val TAG = "LuaJobManager"
    }
}
