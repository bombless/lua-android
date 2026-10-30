# Lua Android IPC Manual

> Status: architecture and integration contract (initial repository bootstrap).
> This document specifies the public cross-application API and implementation requirements. The repository was empty when this document was created, so this is a design contract, not a claim that the service implementation already exists.

## 1. Purpose

Lua Android is a host application that exposes a local Lua execution service to other Android applications. A client app must be able to:

- submit a Lua script and optional input files;
- execute scripts asynchronously;
- query a job by its stable `jobId`;
- receive state-change callbacks;
- read stdout, stderr, return values and generated files;
- cancel a running job;
- use LuaRocks-managed Lua modules.

The client does not need to embed Lua or link against the host app's native libraries.

## 2. IPC and large-file transport

### 2.1 Recommended transport

Use **AIDL/Binder for control-plane calls** and **ParcelFileDescriptor (PFD) for data-plane file transfer**.

Do not put script payloads, binary files, or large results in Binder `byte[]`, `Bundle`, or Intent extras. Binder has a limited transaction buffer shared by in-flight transactions; large payloads can cause `TransactionTooLargeException`.

The AIDL control API should carry only bounded metadata (IDs, options, small source strings) and file descriptors. For large inputs, the client opens a file and passes a read-only PFD. The service copies/streams it into its private job directory before returning from submission, or explicitly documents an asynchronous FD-consumption handshake. The simplest safe contract is: **the service duplicates/owns the received descriptor and completes staging before submit returns**.

For results, expose a read-only PFD or a time-limited `content://` URI backed by a ContentProvider. Grant URI read permission to the calling package when returning a URI. Never expose raw private filesystem paths.

### 2.2 Alternatives

- **ContentProvider + openFile()**: excellent for large file transfer and interoperability. Use it for result/input file access and optionally for a file-oriented submission API.
- **Unix domain sockets**: technically possible, including streaming and descriptor passing via `SCM_RIGHTS` in native code. However, filesystem socket permissions, app sandbox UIDs, SELinux, lifecycle, discovery and authentication make it more work than Binder. Consider only for a future high-throughput streaming protocol.
- **mmap / SharedMemory**: useful for bounded shared-memory buffers, not a general file API. Android `SharedMemory` (API 27+) still requires explicit lifecycle and synchronization. Avoid mapping multi-GB files or treating shared memory as persistent storage.
- **Intent extras**: only for small launch/configuration metadata, not the service API or file payload.

### 2.3 Data flow

1. Client binds explicitly to the exported service using the documented component/action.
2. Service authenticates the Binder caller (UID/package/signature policy).
3. Client calls `submit()` with a bounded request object and either inline source (small scripts) or a PFD (large scripts).
4. Service stages inputs under `files/jobs/<jobId>/input/`, persists a QUEUED record, schedules execution, and returns `jobId`.
5. Worker runs the script in a fresh Lua state and captures stdout, stderr, result values and generated files.
6. Job state and metadata are persisted atomically.
7. Client polls `getJob()` or registers `IJobCallback`.
8. Client opens result files through a PFD or ContentProvider URI.

## 3. Public API contract

The following is the intended AIDL surface. Place AIDL files in a stable package such as `com.bombless.luaandroid.ipc`. Keep wire types versioned and backward-compatible.

### 3.1 AIDL sketch

```aidl
// ILuaRuntimeService.aidl
package com.bombless.luaandroid.ipc;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.bombless.luaandroid.ipc.JobInfo;
import com.bombless.luaandroid.ipc.JobRequest;
import com.bombless.luaandroid.ipc.IJobCallback;

interface ILuaRuntimeService {
    int getApiVersion();
    String getRuntimeInfo();

    // Returns a stable job ID. Must not block for script execution.
    String submit(in JobRequest request);

    JobInfo getJob(String jobId);
    List<JobInfo> listJobs(int limit, long beforeCreatedAtMillis);
    boolean cancel(String jobId);

    // Register callback for one job. Callback may be invoked on a Binder thread.
    void registerCallback(String jobId, IJobCallback callback);
    void unregisterCallback(String jobId, IJobCallback callback);

    // Open a generated artifact by its opaque artifact ID.
    ParcelFileDescriptor openResult(String jobId, String artifactId);
}
```

```aidl
// JobRequest.aidl
package com.bombless.luaandroid.ipc;
parcelable JobRequest;
```

```aidl
// JobInfo.aidl
package com.bombless.luaandroid.ipc;
parcelable JobInfo;
```

```aidl
// IJobCallback.aidl
package com.bombless.luaandroid.ipc;
import com.bombless.luaandroid.ipc.JobInfo;
oneway interface IJobCallback {
    void onJobChanged(in JobInfo info);
}
```

Use `Parcelable` implementations (or stable AIDL where appropriate) for `JobRequest` and `JobInfo`. A parcelable request should include:

| Field | Type | Meaning |
|---|---|---|
| `apiVersion` | int | Request schema version |
| `source` | String? | Inline Lua source for small scripts |
| `scriptFd` | ParcelFileDescriptor? | Script file when source is omitted |
| `scriptName` | String? | Display/debug name |
| `arguments` | String[] | Lua `arg` values |
| `environment` | Bundle | Bounded string-only environment map |
| `workingDirectory` | String? | Relative path inside this job's sandbox only |
| `timeoutMillis` | long | Execution deadline, subject to service limits |
| `memoryLimitBytes` | long | Requested limit, subject to runtime/platform support |
| `inputFiles` | InputFile[] | Named PFD inputs staged into the job |
| `flags` | int | Versioned execution flags |

Never accept arbitrary absolute filesystem paths from clients. Validate names against traversal (reject `..`, separators where not allowed, NUL and empty names). Cap inline source, argument count/length, environment size, input count, file size, execution time and output size.

`JobInfo` should include at least:

- `jobId`, `state`, `createdAtMillis`, `startedAtMillis`, `finishedAtMillis`;
- `exitCode` (nullable until terminal);
- `returnValueJson` (bounded JSON representation, nullable);
- `stdoutBytes`, `stderrBytes`, `truncated`;
- `errorCode`, `errorMessage`;
- `artifacts[]` with opaque artifact IDs, display names, MIME types, sizes and optional content URIs.

### 3.2 State machine

Use these states:

- `QUEUED`: accepted and persisted, waiting for a worker.
- `RUNNING`: Lua VM is executing.
- `SUCCEEDED`: script completed successfully.
- `FAILED`: compile/runtime/host error.
- `CANCELLED`: cancellation completed.
- `TIMED_OUT`: execution deadline exceeded.

Allowed transitions:

```text
QUEUED -> RUNNING -> SUCCEEDED
                   -> FAILED
                   -> CANCELLED
                   -> TIMED_OUT
QUEUED -> CANCELLED
```

Terminal states are immutable. A cancel request is idempotent. `getJob()` must work after service process restart for retained jobs. Define and document retention/cleanup policy (for example, keep recent jobs and remove expired job directories only after a configurable retention period).

Callbacks are best-effort notifications, not the source of truth. Clients must call `getJob()` after reconnecting or when a callback is missed. Handle `DeadObjectException` by reconnecting and querying the job.

### 3.3 Return values and artifacts

Provide:
- stdout and stderr as UTF-8 text artifacts (with configurable bounded capture);
- a structured JSON result for nil/boolean/number/string/table values where serialization is safe;
- an explicit serialization error for cyclic tables, unsupported userdata/functions, or values exceeding limits;
- generated files as separate artifacts accessed by `openResult(jobId, artifactId)`.

Do not silently stringify arbitrary Lua values and claim round-trip fidelity. Define a stable JSON conversion policy and document it.

## 4. Service discovery and security

### 4.1 Explicit binding

Publish a stable service action and package name in this manual only after the Android application ID is finalized. Clients should use an explicit component or an explicit package-scoped intent; do not rely on implicit service resolution.

The service must be exported for cross-app use. Protect it with a custom signature permission if clients can share the signing certificate. If arbitrary third-party apps must call it, a signature permission is not viable: use an explicit allowlist/consent model and authenticate callers using `Binder.getCallingUid()`, `PackageManager.getPackagesForUid()`, and signing-certificate checks where configured. Do not trust a package name supplied in the request.

### 4.2 Required safeguards

Lua scripts are code execution. The service must:
- enforce caller authorization before accepting work or opening artifacts;
- run each job in a fresh Lua VM and isolated working directory;
- avoid exposing host app private files, credentials, or other jobs;
- deny or tightly control Java/JNI bridging and shell execution;
- apply execution timeout, output cap, input-size cap and queue limits;
- validate all paths and artifact IDs;
- make cancellation interrupt the VM and terminate associated native work safely;
- avoid logging script contents or sensitive file contents by default.

Important: a Lua VM in the host app process is **not** a security boundary against malicious native modules. If untrusted callers/scripts are in scope, evaluate a separate isolated process (and, where feasible, a separate UID) plus a restricted native-module policy. Android app components in one package normally share the app UID; a secondary process alone does not create a distinct UID sandbox.

### 4.3 Manifest sketch

```xml
<permission
    android:name="com.bombless.luaandroid.permission.USE_LUA_RUNTIME"
    android:protectionLevel="normal" />

<service
    android:name=".ipc.LuaRuntimeService"
    android:exported="true"
    android:permission="com.bombless.luaandroid.permission.USE_LUA_RUNTIME">
    <intent-filter>
        <action android:name="com.bombless.luaandroid.action.BIND_RUNTIME" />
    </intent-filter>
</service>
```

The `normal` permission above is only a discoverability/access gate, not strong authentication. For a controlled ecosystem, prefer `signature`. For public third-party clients, implement runtime authorization/consent and document the trust model. Do not ship an exported unrestricted arbitrary-code-execution service.

## 5. Lua runtime and LuaRocks

### 5.1 Runtime layout

Keep runtime data in app-private storage, for example:

```text
files/
  lua/
    runtime/              # Lua interpreter and standard libraries
    luarocks/              # LuaRocks scripts/configuration
    rocks/                 # default LuaRocks tree
    jobs/<jobId>/
      script.lua
      input/
      output/
      stdout.txt
      stderr.txt
      job.json
```

Use a single documented Lua version/ABI and keep LuaRocks configuration aligned with it. Expose runtime metadata via `getRuntimeInfo()`: Lua version, architecture/ABI, LuaRocks version, configured rock trees and supported native module ABI.

### 5.2 LuaRocks requirements

The service should bundle or provision a compatible LuaRocks distribution and expose package operations through a separate API/CLI layer. Minimum operations:

- `luarocks --version`
- `luarocks list`
- `luarocks search <name>`
- `luarocks install <name> [version]`
- `luarocks remove <name>`
- `luarocks show <name>`
- `luarocks path`

Configure LuaRocks to install into the app-private tree, never a global system path. Set `LUA_PATH`, `LUA_CPATH`, `LUAROCKS_CONFIG` and tree paths consistently for each job. Do not allow package installation to write outside the app sandbox.

**Native rock compatibility is a separate problem.** Pure-Lua rocks are the easiest to support. Rocks containing C modules must be built for the exact Android ABI (arm64-v8a, armeabi-v7a, x86_64 as shipped), Android NDK/API level, Lua version and linker constraints. Desktop Linux/macOS binaries are not Android-compatible. Initially document pure-Lua rocks as supported and native rocks as supported only when a matching Android build recipe/prebuilt artifact exists. Do not imply that every LuaRocks package will work on Android.

Package installation can execute build scripts. Restrict repositories, build tools, install paths, resource usage and caller permissions. Consider making package management an owner-only operation rather than allowing every IPC client to install arbitrary rocks.

### 5.3 Package manager API

A future versioned package API can expose asynchronous operations:

```text
installRock(name, version, options) -> operationId
getRockOperation(operationId) -> state/log/result
listInstalledRocks() -> RockInfo[]
removeRock(name, version) -> operationId
```

Do not overload script job IDs with package-operation IDs. Package installation should be serialized or protected by a package-tree lock so two clients cannot corrupt the same LuaRocks tree.

## 6. Client usage examples

These examples describe the intended API; names must be adjusted only if the published AIDL package/component changes.

### 6.1 Kotlin binding

```kotlin
val intent = Intent("com.bombless.luaandroid.action.BIND_RUNTIME")
    .setPackage("com.bombless.luaandroid")

val connected = bindService(intent, connection, Context.BIND_AUTO_CREATE)

private var runtime: ILuaRuntimeService? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        runtime = ILuaRuntimeService.Stub.asInterface(binder)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        runtime = null
    }
}
```

### 6.2 Submit a small inline script

```kotlin
val request = JobRequest().apply {
    apiVersion = 1
    source = """
        local sum = 0
        for i = 1, 100 do sum = sum + i end
        print("sum", sum)
        return sum
    """.trimIndent()
    arguments = emptyArray()
    timeoutMillis = 15_000
}
val jobId = runtime!!.submit(request)
```

### 6.3 Submit a large script or input file

```kotlin
val pfd = contentResolver.openFileDescriptor(scriptUri, "r")
    ?: error("Cannot open script URI")

val request = JobRequest().apply {
    apiVersion = 1
    source = null
    scriptFd = pfd
    scriptName = "main.lua"
    timeoutMillis = 60_000
}
val jobId = runtime!!.submit(request)
// The service contract must state whether submit has finished staging the FD.
// Close the client's descriptor after the documented ownership handoff.
pfd.close()
```

For large binary inputs, use an `InputFile` entry containing a display name and PFD. The service copies each stream into the job's private input directory and scripts access it through a documented relative path.

### 6.4 Poll job state

```kotlin
val info = runtime!!.getJob(jobId)
when (info.state) {
    JobState.QUEUED, JobState.RUNNING -> { /* query again later */ }
    JobState.SUCCEEDED -> {
        val resultJson = info.returnValueJson
        // Open artifacts using runtime.openResult(jobId, artifactId)
    }
    JobState.FAILED -> {
        Log.e("Lua", "${info.errorCode}: ${info.errorMessage}")
    }
    JobState.CANCELLED, JobState.TIMED_OUT -> { /* terminal */ }
}
```

Do not busy-loop. Use callbacks where possible and bounded polling with backoff as a fallback. Binder calls should not be made on the Android main thread if they may perform I/O.

### 6.5 Receive state callbacks

```kotlin
val callback = object : IJobCallback.Stub() {
    override fun onJobChanged(info: JobInfo) {
        // This runs on a Binder thread. Dispatch UI work to the main executor.
    }
}
runtime!!.registerCallback(jobId, callback)
```

Unregister callbacks when no longer needed. Re-query after process death/reconnection.

### 6.6 Cancel

```kotlin
val accepted = runtime!!.cancel(jobId)
```

A true return means cancellation was accepted, not necessarily that cleanup has finished. Query the job until it reaches `CANCELLED` or another terminal state.

## 7. Error model

Use stable machine-readable error codes, for example:

- `UNAUTHORIZED_CALLER`
- `INVALID_REQUEST`
- `INPUT_TOO_LARGE`
- `QUEUE_FULL`
- `JOB_NOT_FOUND`
- `JOB_ALREADY_FINISHED`
- `SCRIPT_COMPILE_ERROR`
- `SCRIPT_RUNTIME_ERROR`
- `EXECUTION_TIMEOUT`
- `EXECUTION_CANCELLED`
- `RESULT_TOO_LARGE`
- `ROCK_NOT_FOUND`
- `ROCK_INSTALL_FAILED`
- `NATIVE_ROCK_UNSUPPORTED`
- `INTERNAL_ERROR`

Binder transport failures are distinct from job failures. Clients should handle service unavailable, binder death, permission denial and API-version mismatch.

## 8. Versioning and compatibility

- Start with API version 1 and expose `getApiVersion()`.
- Never change the meaning/order of published Parcelable fields incompatibly.
- Add optional fields with safe defaults.
- Keep action, permission, service component and AIDL package stable.
- Clients must check API version and tolerate unknown job states/error codes.
- Document minimum Android API level and supported ABIs in the project README once decided.

## 9. Implementation checklist

1. Establish Android application ID, Kotlin/Gradle structure, minSdk/targetSdk and supported ABIs.
2. Integrate Lua runtime and verify JNI loading on each ABI.
3. Implement AIDL parcelables, service binding and caller authorization.
4. Implement job persistence, queue, worker pool, state machine, cancellation and cleanup.
5. Implement PFD staging and result ContentProvider with URI grants.
6. Implement output capture, JSON result conversion and artifact listing.
7. Integrate LuaRocks with private tree/config and test pure-Lua packages.
8. Define native rock build support per ABI; reject unsupported native rocks clearly.
9. Add an example client app and instrumentation tests using a separate package/UID.
10. Test process death, device restart, binder death, large files, malicious paths, permission denial, cancellation and timeout.
11. Publish the actual package name, service component, permission, AIDL source/artifact and tested sample code here.

## 10. Acceptance tests

- A second app can bind without sharing the Lua implementation.
- A script can be submitted and a job ID returned quickly.
- A large input file is transferred without Binder transaction-size failure and without loading the whole file into memory.
- The caller can query QUEUED/RUNNING/terminal state and retrieve stdout/stderr/result artifacts.
- A callback loss or service process restart does not make persisted job state inaccessible.
- A caller cannot read another caller's jobs/artifacts unless explicitly authorized.
- Path traversal and unauthorized file access are rejected.
- Timeout and cancellation reach terminal states and clean up resources.
- Pure-Lua LuaRocks package installation and `require()` work using the private tree.
- Unsupported native rocks fail with an actionable error rather than crashing the host app.
