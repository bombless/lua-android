package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.JobState
import org.json.JSONArray
import org.json.JSONObject

/** [path] is relative to the job directory; opaque [id]s are what clients see. */
data class ArtifactRecord(val id: String, val displayName: String, val mimeType: String, val sizeBytes: Long, val path: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", displayName).put("mime", mimeType).put("size", sizeBytes).put("path", path)

    companion object {
        fun fromJson(o: JSONObject) = ArtifactRecord(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getString("path"))
    }
}

/** Persisted as `job.json` in the job directory (the source of truth after a process restart). */
data class JobRecord(
    val id: String,
    val state: Int,
    val ownerUid: Int,
    val ownerPackages: List<String>,
    val createdAt: Long,
    val scriptName: String = "script.lua",
    val timeoutMillis: Long = 0,
    val memoryLimitBytes: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val exitCode: Int? = null,
    val returnValueJson: String? = null,
    val stdoutBytes: Long = 0,
    val stderrBytes: Long = 0,
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val artifacts: List<ArtifactRecord> = emptyList(),
) {
    val isTerminal: Boolean get() = JobState.isTerminal(state)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1).put("id", id).put("state", state).put("ownerUid", ownerUid)
        o.put("ownerPackages", JSONArray(ownerPackages))
        o.put("createdAt", createdAt).put("scriptName", scriptName)
        o.put("timeoutMillis", timeoutMillis).put("memoryLimitBytes", memoryLimitBytes)
        o.put("startedAt", startedAt).put("finishedAt", finishedAt)
        o.put("exitCode", exitCode ?: JSONObject.NULL)
        o.put("returnValueJson", returnValueJson ?: JSONObject.NULL)
        o.put("stdoutBytes", stdoutBytes).put("stderrBytes", stderrBytes).put("truncated", truncated)
        o.put("errorCode", errorCode ?: JSONObject.NULL).put("errorMessage", errorMessage ?: JSONObject.NULL)
        o.put("artifacts", JSONArray(artifacts.map { it.toJson() }))
        return o
    }

    companion object {
        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

        fun fromJson(o: JSONObject): JobRecord {
            val pk = o.getJSONArray("ownerPackages")
            val arts = o.getJSONArray("artifacts")
            return JobRecord(
                id = o.getString("id"),
                state = o.getInt("state"),
                ownerUid = o.getInt("ownerUid"),
                ownerPackages = List(pk.length()) { pk.getString(it) },
                createdAt = o.getLong("createdAt"),
                scriptName = o.optString("scriptName", "script.lua"),
                timeoutMillis = o.optLong("timeoutMillis"),
                memoryLimitBytes = o.optLong("memoryLimitBytes"),
                startedAt = o.optLong("startedAt"),
                finishedAt = o.optLong("finishedAt"),
                exitCode = if (o.isNull("exitCode")) null else o.getInt("exitCode"),
                returnValueJson = o.str("returnValueJson"),
                stdoutBytes = o.optLong("stdoutBytes"),
                stderrBytes = o.optLong("stderrBytes"),
                truncated = o.optBoolean("truncated"),
                errorCode = o.str("errorCode"),
                errorMessage = o.str("errorMessage"),
                artifacts = List(arts.length()) { ArtifactRecord.fromJson(arts.getJSONObject(it)) },
            )
        }
    }
}
