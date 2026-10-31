# Lua Android (MVP)

Cross-application **Lua execution service** for Android, based on the design in [Manual.md](Manual.md).

This MVP uses **LuaJ** (pure Java, Lua 5.2 compatible) so it builds without the NDK.

## Features (v0.1.0-mvp)

- Exported AIDL service: `com.bombless.luaandroid.action.BIND_RUNTIME`
- Async job submit / query / cancel / callbacks
- State machine: `QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT`
- Job isolation under `files/lua/jobs/<jobId>/`
- Bounded stdout/stderr capture and simple JSON return values
- `write_artifact(name, content)` helper for generated files
- In-app console (MainActivity) for local testing
- Debug APK published via GitHub Actions Releases

## Package

| Build | applicationId |
|-------|----------------|
| debug | `com.bombless.luaandroid.debug` |
| release | `com.bombless.luaandroid` |

Permission: `com.bombless.luaandroid.permission.USE_LUA_RUNTIME`

## Client bind example

```kotlin
val intent = Intent("com.bombless.luaandroid.action.BIND_RUNTIME").apply {
    setPackage("com.bombless.luaandroid.debug") // or release package
}
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

Then call `ILuaRuntimeService.submit(JobRequest)` with inline `source` or a `ParcelFileDescriptor`.

## Build locally

Requires Android SDK 35, JDK 17+.

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Security notes (MVP)

- Service is exported and gated by a `normal` permission (discoverability, not strong auth).
- Each job runs in a fresh LuaJ `Globals`; `os` is removed.
- This is **not** a security boundary against malicious native code. Do not expose to untrusted third parties without an allowlist / consent model (see Manual.md §4).

## Roadmap

- [ ] Signature / allowlist authorization
- [ ] Full inputFiles[] staging via PFD
- [ ] Official Lua + NDK option
- [ ] LuaRocks pure-Lua tree support
- [ ] Separate isolated process for untrusted scripts
