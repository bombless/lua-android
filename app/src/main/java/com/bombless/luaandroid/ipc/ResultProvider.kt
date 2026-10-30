package com.bombless.luaandroid.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bombless.luaandroid.core.JobStore
import java.io.FileNotFoundException

class ResultProvider : ContentProvider() {
    private lateinit var store: JobStore
    override fun onCreate(): Boolean { store = JobStore(requireNotNull(context)); return true }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only")
        val parts = uri.pathSegments
        if (parts.size != 2) throw FileNotFoundException("Invalid artifact URI")
        val file = store.artifact(parts[0], parts[1]) ?: throw FileNotFoundException("Artifact not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
