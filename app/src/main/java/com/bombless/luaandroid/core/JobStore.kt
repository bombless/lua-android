package com.bombless.luaandroid.core

import android.content.Context
import com.bombless.luaandroid.ipc.ArtifactInfo
import com.bombless.luaandroid.ipc.JobInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JobStore(private val context: Context) {
    private val root = File(context.filesDir, "lua/jobs").apply { mkdirs() }
    private val cache = ConcurrentHashMap<String, JobInfo>()

    fun create(): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs(); File(this, "input").mkdirs(); File(this, "output").mkdirs() }
        val info = JobInfo(id, "QUEUED", System.currentTimeMillis())
        save(info)
        return id to dir
    }

    fun directory(id: String): File? {
        if (!validId(id)) return null
        return File(root, id).takeIf { it.isDirectory && it.canonicalFile.parentFile == root.canonicalFile }
    }

    fun get(id: String): JobInfo? {
        if (!validId(id)) return null
        cache[id]?.let { return it }
        val file = File(root, "$id/job.json")
        if (!file.isFile) return null
        return runCatching { decode(file.readText()).also { cache[id] = it } }.getOrNull()
    }

    fun list(limit: Int, before: Long): List<JobInfo> = root.listFiles().orEmpty()
        .asSequence().filter { it.isDirectory }.mapNotNull { get(it.name) }
        .filter { before <= 0 || it.createdAtMillis < before }
        .sortedByDescending { it.createdAtMillis }.take(limit.coerceIn(1, 100)).toList()

    @Synchronized fun save(info: JobInfo) {
        val dir = directory(info.jobId) ?: return
        val file = File(dir, "job.json")
        val tmp = File(dir, "job.json.tmp")
        tmp.writeText(encode(info).toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        cache[info.jobId] = info
    }

    /**
     * Atomically applies a state transition only while the persisted job is in
     * one of [expectedStates]. This prevents cancel/start/finish races from
     * resurrecting a terminal job or overwriting a completed result.
     */
    @Synchronized fun transition(
        id: String,
        expectedStates: Set<String>,
        transform: (JobInfo) -> JobInfo
    ): JobInfo? {
        val current = get(id) ?: return null
        if (current.state !in expectedStates) return null
        val updated = transform(current)
        require(updated.jobId == current.jobId) { "Job ID cannot change" }
        save(updated)
        return updated
    }

    fun artifacts(id: String): List<ArtifactInfo> {
        val out = File(directory(id) ?: return emptyList(), "output")
        return out.listFiles().orEmpty().filter { it.isFile }.map {
            ArtifactInfo(it.name, it.name, mime(it.name), it.length())
        }
    }

    fun artifact(id: String, artifactId: String): File? {
        if (!artifactId.matches(Regex("[A-Za-z0-9._-]{1,128}")) || artifactId == "." || artifactId == "..") return null
        val dir = File(directory(id) ?: return null, "output").canonicalFile
        return File(dir, artifactId).canonicalFile.takeIf { it.parentFile == dir && it.isFile }
    }

    fun cleanup(retentionMillis: Long = 7L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - retentionMillis
        root.listFiles().orEmpty().filter { it.isDirectory && it.lastModified() < cutoff }.forEach { it.deleteRecursively(); cache.remove(it.name) }
    }

    private fun validId(id: String) = id.matches(Regex("[0-9a-fA-F-]{36}"))
    private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "lua", "json" -> "text/plain"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "pdf" -> "application/pdf"; else -> "application/octet-stream"
    }
    private fun encode(i: JobInfo) = JSONObject().apply {
        put("jobId", i.jobId); put("state", i.state); put("created", i.createdAtMillis)
        put("started", i.startedAtMillis); put("finished", i.finishedAtMillis)
        put("exitCode", i.exitCode ?: JSONObject.NULL); put("returnValueJson", i.returnValueJson ?: JSONObject.NULL)
        put("stdoutBytes", i.stdoutBytes); put("stderrBytes", i.stderrBytes); put("truncated", i.truncated)
        put("errorCode", i.errorCode ?: JSONObject.NULL); put("errorMessage", i.errorMessage ?: JSONObject.NULL)
    }
    private fun decode(s: String): JobInfo {
        val j = JSONObject(s)
        fun nullable(k: String) = j.optString(k).takeUnless { it == "null" || it.isEmpty() }
        return JobInfo(j.getString("jobId"), j.getString("state"), j.getLong("created"),
            j.optLong("started"), j.optLong("finished"),
            if (j.isNull("exitCode")) null else j.optInt("exitCode"),
            nullable("returnValueJson"), j.optLong("stdoutBytes"), j.optLong("stderrBytes"),
            j.optBoolean("truncated"), nullable("errorCode"), nullable("errorMessage"), artifacts(j.getString("jobId")).toTypedArray())
    }
}
