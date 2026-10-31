package com.bombless.luaandroid.ipc;

import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * Wire type for submit(). Keep fields bounded and versioned.
 */
public final class JobRequest implements Parcelable {

    public static final int CURRENT_API_VERSION = 1;

    public int apiVersion = CURRENT_API_VERSION;
    @Nullable public String source;
    @Nullable public ParcelFileDescriptor scriptFd;
    @Nullable public String scriptName;
    @Nullable public String[] arguments;
    @Nullable public Bundle environment;
    @Nullable public String workingDirectory;
    public long timeoutMillis = 30_000L;
    public long memoryLimitBytes = 0L;
    public int flags = 0;

    public JobRequest() {}

    protected JobRequest(Parcel in) {
        apiVersion = in.readInt();
        source = in.readString();
        scriptFd = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        scriptName = in.readString();
        arguments = in.createStringArray();
        environment = in.readBundle(getClass().getClassLoader());
        workingDirectory = in.readString();
        timeoutMillis = in.readLong();
        memoryLimitBytes = in.readLong();
        flags = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(apiVersion);
        dest.writeString(source);
        dest.writeParcelable(scriptFd, flags);
        dest.writeString(scriptName);
        dest.writeStringArray(arguments);
        dest.writeBundle(environment);
        dest.writeString(workingDirectory);
        dest.writeLong(timeoutMillis);
        dest.writeLong(memoryLimitBytes);
        dest.writeInt(this.flags);
    }

    @Override
    public int describeContents() {
        return scriptFd != null ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public static final Creator<JobRequest> CREATOR = new Creator<JobRequest>() {
        @Override
        public JobRequest createFromParcel(Parcel in) {
            return new JobRequest(in);
        }

        @Override
        public JobRequest[] newArray(int size) {
            return new JobRequest[size];
        }
    };
}
