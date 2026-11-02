// ILuaRuntimeService.aidl
package com.bombless.luaandroid.ipc;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.bombless.luaandroid.ipc.JobInfo;
import com.bombless.luaandroid.ipc.JobRequest;
import com.bombless.luaandroid.ipc.IJobCallback;

/**
 * Cross-app Lua runtime. API version 1 (see Manual.md and README.md).
 *
 * Errors that reject a call are reported as Binder exceptions whose message starts with a stable
 * machine-readable code followed by ": " (for example "INVALID_REQUEST: ..."):
 *   SecurityException          UNAUTHORIZED_CALLER
 *   IllegalArgumentException   INVALID_REQUEST, INPUT_TOO_LARGE, UNSUPPORTED_API_VERSION, JOB_NOT_FOUND
 *   IllegalStateException      QUEUE_FULL, INTERNAL_ERROR
 * Use ErrorCode.fromException() to extract the code. Script failures are NOT exceptions:
 * they are reported through JobInfo.state / errorCode.
 */
interface ILuaRuntimeService {
    int getApiVersion();

    /** JSON document: Lua version, ABI, LuaRocks version, trees, limits. */
    String getRuntimeInfo();

    /**
     * Returns a stable job ID. Does not block for script execution.
     * All file descriptors in the request (scriptFd, inputFiles) are fully copied into the job's
     * private directory before this call returns, so the caller may close them afterwards.
     */
    String submit(in JobRequest request);

    JobInfo getJob(String jobId);
    List<JobInfo> listJobs(int limit, long beforeCreatedAtMillis);

    /**
     * Idempotent. Returns true when cancellation was accepted (job queued/running, or already
     * cancelled); false when the job had already finished in another terminal state.
     */
    boolean cancel(String jobId);

    // Register callback for one job. Callback may be invoked on a Binder thread.
    void registerCallback(String jobId, IJobCallback callback);
    void unregisterCallback(String jobId, IJobCallback callback);

    // Open a generated artifact by its opaque artifact ID. Read-only descriptor.
    ParcelFileDescriptor openResult(String jobId, String artifactId);
}
