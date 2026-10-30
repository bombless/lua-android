package com.bombless.luaandroid.ipc

import android.content.Context
import org.json.JSONObject
import java.io.File

internal class JobStore(context: Context) {
    val root = File(context.filesDir, "lua/jobs").apply { mkdirs() }

    fun dir(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, id)
    }

    fun save(info: JobInfo, uid: Int) {
        val artifacts = org.json.JSONArray().apply {
            info.artifacts.forEach {
                put(JSONObject()
                    .put("id", it.artifactId)
                    .put("name", it.displayName)
                    .put("mime", it.mimeType)
                    .put("size", it.sizeBytes))
            }
        }
        val json = JSONObject()
            .put("jobId", info.jobId)
            .put("state", info.state)
            .put("created", info.createdAtMillis)
            .put("started", info.startedAtMillis)
            .put("finished", info.finishedAtMillis)
            .put("exit", info.exitCode)
            .put("result", info.returnValueJson)
            .put("stdout", info.stdoutBytes)
            .put("stderr", info.stderrBytes)
            .put("truncated", info.truncated)
            .put("errorCode", info.errorCode)
            .put("errorMessage", info.errorMessage)
            .put("uid", uid)
            .put("artifacts", artifacts)

        val directory = dir(info.jobId).apply { mkdirs() }
        val temporary = File(directory, "job.tmp")
        val target = File(directory, "job.json")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(target)) {
            target.writeText(json.toString())
            temporary.delete()
        }
    }

    fun read(id: String): Pair<JobInfo, Int>? = runCatching {
        val json = JSONObject(File(dir(id), "job.json").readText())
        val artifactsJson = json.optJSONArray("artifacts") ?: org.json.JSONArray()
        val artifacts = Array(artifactsJson.length()) { index ->
            val item = artifactsJson.getJSONObject(index)
            ArtifactInfo(
                item.getString("id"),
                item.getString("name"),
                item.getString("mime"),
                item.getLong("size")
            )
        }
        JobInfo(
            id,
            json.getString("state"),
            json.getLong("created"),
            json.optLong("started"),
            json.optLong("finished"),
            if (json.isNull("exit")) null else json.getInt("exit"),
            if (json.isNull("result")) null else json.getString("result"),
            json.optLong("stdout"),
            json.optLong("stderr"),
            json.optBoolean("truncated"),
            json.optString("errorCode").takeIf { it != "null" && it.isNotEmpty() },
            json.optString("errorMessage").takeIf { it != "null" && it.isNotEmpty() },
            artifacts
        ) to json.getInt("uid")
    }.getOrNull()

    fun list(limit: Int, before: Long): List<JobInfo> =
        root.listFiles().orEmpty()
            .mapNotNull { read(it.name)?.first }
            .filter { before <= 0 || it.createdAtMillis < before }
            .sortedByDescending { it.createdAtMillis }
            .take(limit)

    /**
     * Jobs cannot survive process death while executing in the current in-process
     * worker implementation. Mark them as interrupted instead of leaving them
     * permanently QUEUED/RUNNING, and prune expired job directories.
     */
    fun recoverAndPrune(nowMillis: Long = System.currentTimeMillis(), retentionMillis: Long = DEFAULT_RETENTION_MILLIS) {
        root.listFiles().orEmpty().forEach { directory ->
            val pair = read(directory.name)
            if (pair == null) {
                // Ignore malformed entries; do not recursively delete unknown data.
                return@forEach
            }
            val (info, uid) = pair
            if (nowMillis - info.createdAtMillis > retentionMillis) {
                directory.deleteRecursively()
            } else if (info.state == "QUEUED" || info.state == "RUNNING") {
                save(
                    info.copy(
                        state = "FAILED",
                        finishedAtMillis = nowMillis,
                        errorCode = "SERVICE_RESTARTED",
                        errorMessage = "The runtime process stopped before this job completed."
                    ),
                    uid
                )
            }
        }
    }

    companion object {
        const val DEFAULT_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
