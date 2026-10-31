package com.bombless.luaandroid.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bombless.luaandroid.LuaAndroidApp
import java.io.FileNotFoundException

/**
 * content://com.bombless.luaandroid.artifacts/{jobId}/{artifactId}
 */
class JobArtifactProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" && mode != "read") {
            throw SecurityException("Only read mode is allowed")
        }
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("Invalid URI")
        val jobId = segments[0]
        val artifactId = segments[1]
        val file = LuaAndroidApp.instance.jobManager.getArtifactFile(jobId, artifactId)
            ?: throw FileNotFoundException("Artifact not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
