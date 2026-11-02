package com.bombless.luaandroid.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-backed persistence: one directory per job, `job.json` is rewritten atomically on every transition. */
class JobStore(private val jobsDir: File) {
    fun jobDir(id: String): File {
        require(Ids.isJobId(id)) { "invalid job id" }
        return File(jobsDir, id)
    }

    @Synchronized
    fun save(rec: JobRecord) {
        val dir = jobDir(rec.id)
        dir.mkdirs()
        val tmp = File(dir, "job.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(rec.toJson().toString(2).toByteArray(Charsets.UTF_8))
            out.fd.sync() // QUEUED must survive a power loss, not just a process kill
        }
        try {
            Files.move(tmp.toPath(), File(dir, "job.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (!tmp.renameTo(File(dir, "job.json"))) throw e
        }
    }

    fun load(id: String): JobRecord? {
        if (!Ids.isJobId(id)) return null
        val f = File(jobDir(id), "job.json")
        if (!f.isFile || f.length() > 4L * 1024 * 1024) return null
        return try {
            JobRecord.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    /** Ids of all directories that look like jobs (with or without a readable job.json). */
    fun listDirectoryIds(): List<String> =
        jobsDir.listFiles()?.filter { it.isDirectory && Ids.isJobId(it.name) }?.map { it.name } ?: emptyList()

    fun delete(id: String) {
        if (Ids.isJobId(id)) File(jobsDir, id).deleteRecursively()
    }
}
