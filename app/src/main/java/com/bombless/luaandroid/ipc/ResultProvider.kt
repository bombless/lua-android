package com.bombless.luaandroid.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import com.bombless.luaandroid.LuaApp
import com.bombless.luaandroid.core.Ids
import com.bombless.luaandroid.core.JobRecord
import java.io.FileNotFoundException

/**
 * Read-only artifacts: content://com.bombless.luaandroid.results/<jobId>/<artifactId>.
 * Not exported; the owning app receives a URI grant when its job finishes (JobManager), and every access
 * re-checks that the calling UID owns the job.
 */
class ResultProvider : ContentProvider() {
    private val graph get() = (context!!.applicationContext as LuaApp).graph

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): Pair<JobRecord, com.bombless.luaandroid.core.ArtifactRecord>? {
        val seg = uri.pathSegments
        if (seg.size != 2 || !Ids.isJobId(seg[0]) || !Ids.isArtifactId(seg[1])) return null
        graph.ensureReady()
        val rec = graph.jobs.recordOrNull(seg[0]) ?: return null
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && uid != rec.ownerUid) return null
        val art = rec.artifacts.firstOrNull { it.id == seg[1] } ?: return null
        return rec to art
    }

    override fun getType(uri: Uri): String? = resolve(uri)?.second?.mimeType

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val (_, art) = resolve(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) art.displayName else art.sizeBytes }.toTypedArray())
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("artifacts are read-only")
        val (rec, art) = resolve(uri) ?: throw FileNotFoundException("no such artifact")
        val file = graph.jobs.resolveArtifactFile(rec, art.id) ?: throw FileNotFoundException("the artifact is no longer available")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only provider")
}
