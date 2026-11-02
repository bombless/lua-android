// IJobCallback.aidl
package com.bombless.luaandroid.ipc;

import com.bombless.luaandroid.ipc.JobInfo;

/**
 * Best-effort notification that a job changed state. Invoked on a Binder thread.
 * Callbacks are NOT the source of truth: call ILuaRuntimeService.getJob() after reconnecting
 * or whenever a notification may have been missed.
 */
oneway interface IJobCallback {
    void onJobChanged(in JobInfo info);
}
