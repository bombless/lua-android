# Lua Android
Initial API v1 Android host implementation scaffold based on Manual.md.

- Application ID: com.bombless.luaandroid
- Service action: com.bombless.luaandroid.action.BIND_RUNTIME
- AIDL package: com.bombless.luaandroid.ipc
- Permission: com.bombless.luaandroid.permission.USE_LUA_RUNTIME (signature)
- Runtime: LuaJ 3.0.1, Lua 5.2-compatible API; pure Java, no native ABI.

## Status and known gaps
Implemented: Android/Gradle project, exported signature-protected Binder service, API version/runtime info, asynchronous jobs, persisted job metadata, polling, callbacks, cancellation request, and stdout/scalar result artifacts.

Not yet production complete: timeout is best-effort because cancelling a Java Future does not reliably preempt an infinite Lua loop; hard isolation requires a killable worker process. Large-file staging currently buffers bounded input in memory. Input-file staging, stderr, arbitrary Lua table JSON conversion, artifact creation, LuaRocks provisioning/operations, crash recovery, retention cleanup, example client, and instrumentation tests remain TODOs. Native LuaRocks modules are not supported.

The runtime uses JME globals to avoid exposing Java interop and host filesystem APIs. Do not replace the signature permission with an unrestricted public permission: this is an arbitrary-code execution service.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. Generate a Gradle 8.9 wrapper or use Android Studio Gradle integration, then run :app:assembleDebug.
