package com.bombless.luaandroid.ipc

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

/**
 * One retrievable output of a job. [artifactId] is opaque: "stdout", "stderr", "result" have fixed
 * meaning (see [ApiLimits]); generated files get IDs of the form "f<number>". Open with
 * [ILuaRuntimeService.openResult] or, when [contentUri] is present, with ContentResolver (read access
 * to the URI is granted to the app that owns the job).
 */
class ArtifactInfo() : Parcelable {
    var artifactId: String = ""
    var displayName: String = ""
    var mimeType: String = "application/octet-stream"
    var sizeBytes: Long = 0
    var contentUri: Uri? = null

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        Wire.write(dest, 1) {
            dest.writeString(artifactId)
            dest.writeString(displayName)
            dest.writeString(mimeType)
            dest.writeLong(sizeBytes)
            Uri.writeToParcel(dest, contentUri)
        }
    }

    override fun toString(): String = "ArtifactInfo(id=$artifactId, name=$displayName, size=$sizeBytes)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ArtifactInfo> = object : Parcelable.Creator<ArtifactInfo> {
            override fun createFromParcel(source: Parcel): ArtifactInfo = Wire.read(source) { _ ->
                val a = ArtifactInfo()
                a.artifactId = source.readString() ?: ""
                a.displayName = source.readString() ?: ""
                a.mimeType = source.readString() ?: "application/octet-stream"
                a.sizeBytes = source.readLong()
                a.contentUri = Uri.CREATOR.createFromParcel(source)
                a
            }

            override fun newArray(size: Int): Array<ArtifactInfo?> = arrayOfNulls(size)
        }
    }
}
