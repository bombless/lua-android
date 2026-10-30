# Lua Android

Android host app exposing a local Lua execution service to other applications. The public IPC contract follows [Manual.md](Manual.md).

## Build

- Android Studio / Gradle 8.9+
- JDK 17
- Android SDK 35
- Android Gradle Plugin 8.7.3

Open this repository in Android Studio and run the `:app` configuration.

## IPC

- Explicit action: `com.bombless.luaandroid.action.BIND_RUNTIME`
- Package: `com.bombless.luaandroid`
- AIDL package: `com.bombless.luaandroid.ipc`
- API version: 1
- Service: `com.bombless.luaandroid.ipc.LuaRuntimeService`

External UIDs are denied by default. The normal-level manifest permission is not authentication. A trusted owner must explicitly populate the private `caller_policy/allowed_packages` preference set before allowing third-party clients. Do not expose this service to untrusted callers.

## Current implementation boundary

This bootstrap uses LuaJ 3.0.1 (a JVM Lua implementation with Lua 5.2-era compatibility), not the native Lua version/ABI assumed by LuaRocks. Native Lua modules and LuaRocks installation are **not implemented** and are not advertised as supported. Runtime metadata reports this explicitly. This is a known gap against Manual sections 5 and acceptance test 9; do not use this build where exact Lua/LuaRocks compatibility is required.

The execution timeout is checked after the current LuaJ call returns; it is not a hard preemption guarantee. A production deployment handling untrusted scripts must move execution into a separately constrained process/UID or isolated service and add native VM interruption/resource enforcement. A secondary process sharing the app UID is not a security boundary.

## Implemented

- AIDL API surface and Parcelable v1 request/result types.
- Explicit Binder service, bounded queue, caller allowlist default-deny.
- Synchronous bounded PFD staging into per-job private directories.
- Durable JSON job records, state notifications, polling, cancellation request and retention cleanup.
- stdout/stderr artifacts and bounded JSON conversion of common Lua values.
- Opaque artifact IDs and read-only PFD result access.

See Manual.md for the full target contract and remaining acceptance tests.
