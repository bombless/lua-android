package com.bombless.luaandroid.ipc;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of a job. Terminal states are immutable.
 */
public final class JobInfo implements Parcelable {

    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CANCELLED = "CANCELLED";
    public static final String STATE_TIMED_OUT = "TIMED_OUT";

    public String jobId = "";
    public String state = STATE_QUEUED;
    public long createdAtMillis;
    public long startedAtMillis;
    public long finishedAtMillis;
    @Nullable public Integer exitCode;
    @Nullable public String returnValueJson;
    public long stdoutBytes;
    public long stderrBytes;
    public boolean truncated;
    @Nullable public String errorCode;
    @Nullable public String errorMessage;
    public List<ArtifactInfo> artifacts = new ArrayList<>();

    public JobInfo() {}

    protected JobInfo(Parcel in) {
        jobId = in.readString();
        state = in.readString();
        createdAtMillis = in.readLong();
        startedAtMillis = in.readLong();
        finishedAtMillis = in.readLong();
        if (in.readByte() != 0) {
            exitCode = in.readInt();
        }
        returnValueJson = in.readString();
        stdoutBytes = in.readLong();
        stderrBytes = in.readLong();
        truncated = in.readByte() != 0;
        errorCode = in.readString();
        errorMessage = in.readString();
        artifacts = in.createTypedArrayList(ArtifactInfo.CREATOR);
        if (artifacts == null) artifacts = new ArrayList<>();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(jobId);
        dest.writeString(state);
        dest.writeLong(createdAtMillis);
        dest.writeLong(startedAtMillis);
        dest.writeLong(finishedAtMillis);
        if (exitCode != null) {
            dest.writeByte((byte) 1);
            dest.writeInt(exitCode);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeString(returnValueJson);
        dest.writeLong(stdoutBytes);
        dest.writeLong(stderrBytes);
        dest.writeByte((byte) (truncated ? 1 : 0));
        dest.writeString(errorCode);
        dest.writeString(errorMessage);
        dest.writeTypedList(artifacts);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<JobInfo> CREATOR = new Creator<JobInfo>() {
        @Override
        public JobInfo createFromParcel(Parcel in) {
            return new JobInfo(in);
        }

        @Override
        public JobInfo[] newArray(int size) {
            return new JobInfo[size];
        }
    };

    public boolean isTerminal() {
        return STATE_SUCCEEDED.equals(state)
                || STATE_FAILED.equals(state)
                || STATE_CANCELLED.equals(state)
                || STATE_TIMED_OUT.equals(state);
    }

    public static final class ArtifactInfo implements Parcelable {
        public String artifactId = "";
        public String displayName = "";
        public String mimeType = "application/octet-stream";
        public long sizeBytes;
        @Nullable public String contentUri;

        public ArtifactInfo() {}

        protected ArtifactInfo(Parcel in) {
            artifactId = in.readString();
            displayName = in.readString();
            mimeType = in.readString();
            sizeBytes = in.readLong();
            contentUri = in.readString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(artifactId);
            dest.writeString(displayName);
            dest.writeString(mimeType);
            dest.writeLong(sizeBytes);
            dest.writeString(contentUri);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<ArtifactInfo> CREATOR = new Creator<ArtifactInfo>() {
            @Override
            public ArtifactInfo createFromParcel(Parcel in) {
                return new ArtifactInfo(in);
            }

            @Override
            public ArtifactInfo[] newArray(int size) {
                return new ArtifactInfo[size];
            }
        };
    }
}
