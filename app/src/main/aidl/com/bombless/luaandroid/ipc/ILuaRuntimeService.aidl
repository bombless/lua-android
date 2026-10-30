package com.bombless.luaandroid.ipc;
import com.bombless.luaandroid.ipc.JobInfo;
import com.bombless.luaandroid.ipc.JobRequest;
import com.bombless.luaandroid.ipc.IJobCallback;
import android.os.ParcelFileDescriptor;
interface ILuaRuntimeService {
    int getApiVersion();
    String getRuntimeInfo();
    String submit(in JobRequest request);
    JobInfo getJob(String jobId);
    List<JobInfo> listJobs(int limit, long beforeCreatedAtMillis);
    boolean cancel(String jobId);
    void registerCallback(String jobId, IJobCallback callback);
    void unregisterCallback(String jobId, IJobCallback callback);
    ParcelFileDescriptor openResult(String jobId, String artifactId);
}
