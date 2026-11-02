package com.bombless.luaandroid.core

import java.security.SecureRandom

object Ids {
    private val random = SecureRandom()
    private val JOB_ID = Regex("[0-9a-f]{32}")
    private val OP_ID = Regex("op-[0-9a-f]{16}")
    private val ARTIFACT_ID = Regex("stdout|stderr|result|f[0-9]{1,4}")

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 128 random bits: unguessable, and safe to use as a directory name. */
    fun newJobId(): String = hex(16)
    fun newOperationId(): String = "op-" + hex(8)

    /** IDs coming from clients are validated before they ever touch the file system. */
    fun isJobId(s: String?): Boolean = s != null && JOB_ID.matches(s)
    fun isOperationId(s: String?): Boolean = s != null && OP_ID.matches(s)
    fun isArtifactId(s: String?): Boolean = s != null && ARTIFACT_ID.matches(s)
}
