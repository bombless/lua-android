package com.bombless.luaandroid.ipc

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class InputFile(val name: String, val fd: ParcelFileDescriptor) : Parcelable

@Parcelize
data class JobRequest(
    val apiVersion: Int = 1,
    val source: String? = null,
    val scriptFd: ParcelFileDescriptor? = null,
    val scriptName: String? = null,
    val arguments: Array<String> = emptyArray(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val timeoutMillis: Long = 15_000,
    val memoryLimitBytes: Long = 0,
    val inputFiles: Array<InputFile> = emptyArray(),
    val flags: Int = 0
) : Parcelable

@Parcelize
data class ArtifactInfo(
    val artifactId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: Uri? = null
) : Parcelable

@Parcelize
data class JobInfo(
    val jobId: String,
    val state: String,
    val createdAtMillis: Long,
    val startedAtMillis: Long = 0,
    val finishedAtMillis: Long = 0,
    val exitCode: Int? = null,
    val returnValueJson: String? = null,
    val stdoutBytes: Long = 0,
    val stderrBytes: Long = 0,
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val artifacts: Array<ArtifactInfo> = emptyArray()
) : Parcelable
