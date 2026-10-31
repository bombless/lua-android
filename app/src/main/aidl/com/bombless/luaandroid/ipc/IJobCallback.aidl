package com.bombless.luaandroid.ipc;

import com.bombless.luaandroid.ipc.JobInfo;

oneway interface IJobCallback {
    void onJobChanged(in JobInfo info);
}
