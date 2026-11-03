# Lua Android

A cross-app **Lua 5.4 runtime for Android**. Any app can hand a script (and input files) to this app over Binder and
get back a job ID, live state, `stdout`/`stderr`, a JSON return value and generated files. The runtime ships
**LuaRocks** so pure-Lua packages can be installed once and `require()`d by every script.

> **中文简介**　这是一个跨应用的 Lua 5.4 运行时 App：其他 App 通过 Binder（AIDL）提交脚本和输入文件，拿到任务 ID，
> 随时查询状态、读取 stdout/stderr、JSON 返回值和脚本生成的文件。每个任务在**独立进程**里运行（硬超时、内存上限、
> 可随时取消、崩溃不影响宿主）；调用方需要经过所有者批准并固定签名证书；内置**真正的 LuaRocks 3.13**（仅纯 Lua 包，
> 需要编译 C 的包会被明确拒绝）。设计文档见 [`Manual.md`](Manual.md)，实现状态见其第 11 节。
> 下载 APK：到 [Releases](../../releases) 页面。

- **Android 8.0+ (API 26)**, ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64` (one universal APK)
- Lua **5.4.9**, LuaRocks **3.13.0**
- Design contract: [`Manual.md`](Manual.md) · implementation status and published identifiers: Manual §11
- This implementation lives on the `claude` branch; `main` carries an earlier LuaJ-based MVP.

## Contents

1. [Install and try it](#install-and-try-it)
2. [Using the runtime from another app](#using-the-runtime-from-another-app)
3. [The Lua environment of a script](#the-lua-environment-of-a-script)
4. [LuaRocks](#luarocks)
5. [Architecture](#architecture)
6. [Trust model and security](#trust-model-and-security)
7. [Building, testing, releasing](#building-testing-releasing)
8. [Limitations](#limitations)
9. [Third-party software](#third-party-software)

## Install and try it

Download `lua-android-<version>.apk` from the [Releases](../../releases) page and open it on the device. The app has five tabs:

| Tab | What it does |
|---|---|
| **Run** | Edit a Lua script, pick an example, run it with a 3/15/60 s deadline. stdout streams in live; stderr, the JSON return value and files in `output/` are shown when the job ends. |
| **Jobs** | Every job of every app, with details, cancel and delete. |
| **Rocks** | LuaRocks: search, install, remove; operation logs; servers and the host allow-list. |
| **Access** | Which apps may use the runtime. Requests from unknown apps wait here (and raise a notification) until you allow or deny them. |
| **About** | Runtime info, retention settings, and the trust model in plain words. |

The **Run** tab uses exactly the same code path as other apps (it just acts as the "owner"), so what you see is what clients get.

## Using the runtime from another app

### 1. Declare two things in your manifest

```xml
<uses-permission android:name="com.bombless.luaandroid.permission.USE_LUA_RUNTIME" />
<queries><package android:name="com.bombless.luaandroid" /></queries>   <!-- Android 11+ package visibility -->
```

The permission only makes the service discoverable; it is **not** the authorisation (see [Trust model](#trust-model-and-security)).
The first call from an app the owner has not approved fails with `UNAUTHORIZED_CALLER` and shows a request in the **Access** tab.
Apps signed with the same certificate as Lua Android are trusted automatically (switchable).

### 2. Add the client library

Each release contains `lua-android-ipc-api-<version>.aar` (AIDL interfaces, parcelables, a small helper) and
`lua-android-ipc-api-sources-<version>.zip`. Or copy the `ipc-api` Gradle module into your project. The AAR is written in Kotlin,
so non-Kotlin projects need `org.jetbrains.kotlin:kotlin-stdlib` at runtime. The AIDL package is `com.bombless.luaandroid.ipc`
and is stable for API v1; you can also bind with plain AIDL and no helper.

### 3. Bind, submit, wait

```kotlin
val client = LuaRuntimeClient(context)
client.bind(object : LuaRuntimeClient.Listener {
    override fun onConnected(runtime: ILuaRuntimeService) { /* ready */ }
    override fun onDisconnected() { /* the system rebinds automatically */ }
})

// off the main thread:
val request = JobRequest().apply {
    source = "print('hi') return { answer = 42, args = {...} }"
    arguments = arrayOf("a", "b")
    environment = Bundle().apply { putString("GREETING", "hello") }
    timeoutMillis = 10_000
}
val jobId = runtime.submit(request)                       // returns quickly
val info  = LuaRuntimeClient.waitForTerminal(runtime, jobId)   // or runtime.registerCallback(jobId, cb)
when (info.state) {
    JobState.SUCCEEDED -> println(info.returnValueJson)   // {"answer":42,"args":["a","b"]}
    JobState.FAILED, JobState.TIMED_OUT, JobState.CANCELLED -> println("${info.errorCode}: ${info.errorMessage}")
}
val stdout = LuaRuntimeClient.readArtifactText(runtime, jobId, "stdout")
```

[`client-example/`](client-example/src/main/java/com/bombless/luaandroid/example/MainActivity.kt) is a complete example app (it is also
the second UID of the acceptance tests).

### The contract in short

**Calls** (`ILuaRuntimeService`, Manual §3.1): `getApiVersion`, `getRuntimeInfo` (JSON), `submit`, `getJob`, `listJobs`, `cancel`,
`registerCallback`/`unregisterCallback`, `openResult`.

**States**: `QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT`, and `QUEUED → CANCELLED`. Terminal states never change.
States are ints; treat unknown values `>= 2` as terminal. Callbacks are best-effort notifications; `getJob` is the source of truth
and keeps working after the service process restarts.

**Errors.** Rejected calls throw a Binder-safe exception whose message starts with a stable code and `": "`:
`SecurityException` for `UNAUTHORIZED_CALLER`; `IllegalArgumentException` for `INVALID_REQUEST`, `INPUT_TOO_LARGE`,
`UNSUPPORTED_API_VERSION`, `JOB_NOT_FOUND`; `IllegalStateException` for `QUEUE_FULL`, `INTERNAL_ERROR`.
`ErrorCode.fromException(e)` extracts the code. Script failures are *not* exceptions: the job ends `FAILED` with
`SCRIPT_COMPILE_ERROR`, `SCRIPT_RUNTIME_ERROR`, `MEMORY_LIMIT_EXCEEDED`, `RESULT_NOT_SERIALIZABLE`, `RESULT_TOO_LARGE`, or ends
`TIMED_OUT` (`EXECUTION_TIMEOUT`) / `CANCELLED` (`EXECUTION_CANCELLED`). Clients must tolerate codes they do not know.

**Large data.** Never put big data in the request. Pass `scriptFd` (script ≤ 8 MiB) and `inputFiles` (each ≤ 256 MiB, total ≤ 512 MiB,
at most 16) as `ParcelFileDescriptor`s of *regular files*. `submit()` copies them into the job directory **before it returns**, so
you may close them right after; a state of `QUEUED` therefore means "accepted and persisted". Pipes and sockets are rejected (they could block
a Binder thread forever). Scripts read inputs as `input/<name>`; they are streamed, not loaded into memory.

**Results.** `JobInfo.artifacts` lists `stdout` and `stderr` (the script's raw output, capped at 1 MiB each by default; `truncated`
tells you), `result` (the JSON return value, when there is one), and `f1…` for each file the script wrote to `output/`
(up to 64). Open an artifact with `openResult(jobId, artifactId)` (read-only descriptor) or through `ArtifactInfo.contentUri`
(`content://com.bombless.luaandroid.results/<job>/<artifact>`; read access is granted to the app that owns the job).
While a job runs, `stdout` and `stderr` can already be read.

**Return values.** What the script `return`s becomes `JobInfo.returnValueJson` (and the `result` artifact):

| Lua | JSON |
|---|---|
| no value | none (`returnValueJson == null`) |
| one value | that value; several values → an array of them (`nil` becomes `null`) |
| `nil`, boolean, string | `null`, `true`/`false`, a string (must be valid UTF-8; otherwise `RESULT_NOT_SERIALIZABLE`) |
| integer / float | a number; floats always keep a `.` or exponent (`3.0`); `NaN`/`inf` → error |
| table with keys exactly `1..n` | array (`{}` is `[]`) |
| table with only string keys | object, keys sorted |
| other tables, cycles, depth > 64, functions, userdata, threads | error `RESULT_NOT_SERIALIZABLE` |
| more than 1 MiB | error `RESULT_TOO_LARGE` |

The inline `returnValueJson` is only set when it fits in 32 KiB (it travels over Binder); the `result` artifact always has the full text.

**Limits** (also in `getRuntimeInfo()` and `ApiLimits`): inline source 256 KiB, 64 arguments of ≤ 4 KiB, 64 environment entries,
timeout 0.1 s – 10 min (default 15 s), memory 8 – 512 MiB (default 128 MiB), 16 active jobs per caller, 64 in total.
Timeout and memory are clamped to the allowed range, everything else is rejected.

**Names are validated, never sanitised**: input file names are `[A-Za-z0-9_][A-Za-z0-9._-]*` (≤ 128 bytes, unique);
`workingDirectory` is `input`, `output` or `tmp` plus up to five plain segments (no `..`, no absolute paths);
environment keys are `[A-Za-z_][A-Za-z0-9_]*`. Violations → `INVALID_REQUEST`.

**Cancellation and timeouts** are enforced by killing the job's process: the script gets `SIGTERM` (then `SIGKILL` after 500 ms) on
cancel; the runner's own deadline fires first on timeout, and the host kills the whole process group 2 s after the deadline in any case.
Partial output stays available.

## The Lua environment of a script

Each job gets a fresh Lua 5.4 state in its own process. What a script can use:

- the standard `string` (without `dump`), `table`, `math`, `utf8`, `coroutine`; `print`, `pcall`, `load` (text chunks only), `require`, …
- `arg` and `...` = the request's `arguments`; `os.getenv(name)` returns the request's `environment` (nothing else is visible);
  `os.time/clock/date/difftime/tmpname/remove/rename/exit`.
- `io` is confined: relative paths resolve inside **`output/`** (override with `workingDirectory`); `input/` is read-only; `tmp/` is scratch
  space (deleted after the job); anything else (absolute paths, `..`, other directories) fails with a clear message. `io.stdout/stderr`
  are capped; `io.popen`, `os.execute`, `debug`, `package.loadlib` and binary chunks do not exist.
- `job`: `job.id`, `job.args`, `job.env`, `job.runtime = { lua, luart, abi }`, `job.input(name)` / `job.output(name)` (relative paths).
- `require`: modules from the LuaRocks tree and from `input/` (so a caller can ship helper modules as input files). Names are validated and the
  search list cannot be changed by scripts. A prebuilt native `.so` for the device ABI in the rock tree loads too.
- `os.exit(n)` ends the job: `0`/`true` is success, anything else `FAILED` (`SCRIPT_RUNTIME_ERROR`).

```lua
-- example: summarise an uploaded CSV, write a report file, return the numbers
local total, rows = 0, 0
for line in io.lines("input/data.csv") do
  local n = tonumber(line:match(",(%-?%d+%.?%d*)$"))
  if n then total, rows = total + n, rows + 1 end
end
local f = assert(io.open("report.txt", "w"))        -- lands in output/ -> artifact "f1"
f:write(("rows=%d total=%.2f\n"):format(rows, total))
f:close()
return { rows = rows, total = total }
```

## LuaRocks

Real **LuaRocks 3.13.0** (unmodified Lua sources) runs inside the native runner. Nothing external is needed on the device:
the runner contains `lfs`, `zlib` (lua-zlib), `md5` and a small `posix` subset, and `socket.http`/`ssl.https`/`ltn12` shims send downloads
through the host app, which performs them over HTTPS with the system trust store.

- **Pure-Lua rocks only.** Rocks with C sources, `make`/`cmake`/`command` build types, prebuilt libraries or external C dependencies are refused with
  `NATIVE_ROCK_UNSUPPORTED` and an explanation; nothing is half-installed. `os.execute`/`io.popen` are disabled, so a rockspec can never run commands.
  The phone has no compiler; a prebuilt `.so` for the device ABI that the owner places in the tree is loaded by `require`.
- **Owner-only by default.** The Rocks tab always works. Other apps need the extra "manage Lua packages" grant (`ILuaRocksService`:
  `installRock`, `removeRock`, `searchRocks`, `showRock`, `listInstalledRocks`, `getRocksPath`, `getRockOperation`, `cancelRockOperation`).
  Operations are asynchronous, have their own IDs, and run one at a time (the package tree lock).
- **Servers and hosts.** Default server `https://luarocks.org`. The host only downloads from allowed hosts (default: `luarocks.org`,
  GitHub's hosts, `gitlab.com`), only over HTTPS, never follows redirects blindly (each hop is validated again), and caps downloads at 64 MiB.
- One tree, shared by all apps' scripts, under the app's private storage: `files/lua/rocks`.

## Architecture

```
 client app (own UID)                          Lua Android (host app)
┌──────────────────────┐   Binder / AIDL   ┌──────────────────────────────────────────────────────────┐
│ ILuaRuntimeService   │ ───────────────►  │ LuaRuntimeService → CallerPolicy (uid → package → cert)   │
│ submit(JobRequest +  │   PFDs, not bytes │   JobManager: validate, stage, persist, queue, workers    │
│   file descriptors)  │                   │      │                                                    │
│ getJob / callbacks   │ ◄───────────────  │      ▼  one process per job                               │
│ openResult / URIs    │                   │   libluart.so exec ── Lua 5.4 + sandbox (bootstrap.lua)   │
└──────────────────────┘                   │   ResultProvider (content://…/results, URI grants)        │
                                           │   LuaRocksService → RockManager → libluart.so rocks       │
                                           └──────────────────────────────────────────────────────────┘
```

- **Why a process per job.** The runner is a native executable (`libluart.so`, shipped in `nativeLibraryDir`, the only place Android lets apps
  exec from). The host can `SIGKILL` it on timeout/cancel whatever the script does (even inside a C function), a native crash or OOM cannot take
  the host down, and the Lua memory ceiling is enforced by a counting allocator. There is no JNI.
- **Modules.** `ipc-api` (AIDL + parcelables + client helper, an Android library), `app` (the host), `client-example` (second app + the
  acceptance tests), `runner/` (C + Lua, built per ABI with the NDK; also built and tested on Linux), `third_party/` (vendored sources).
- **Storage** (`files/lua/`): `runtime/` (bootstrap, shims) and `luarocks/` (installed from the APK assets whenever their content hash changes),
  `rocks/` (the tree), `jobs/<id>/` (`script.lua`, `job.json` = the persisted state machine record, `input/`, `output/`, `stdout.txt`, `stderr.txt`,
  `result.json`), `access.json` (approvals). Inputs, `tmp/` and the spec are deleted when a job ends; finished jobs are kept for 7 days
  (at most 200, configurable).
- **Persistence and recovery.** `job.json` is rewritten atomically (and fsynced) at every transition. After the process dies, queued jobs are queued
  again, jobs that were running are finished from what the runner left (or `FAILED` with `INTERNAL_ERROR` "restarted"), orphaned runners are killed,
  half-staged directories are removed, and URI grants are re-issued on `getJob`.
- **Process model of the service.** It is a bound service that also starts itself so queued work outlives the last client, and stops when idle.

## Trust model and security

What is enforced:

- **Who may call.** Every call is authorised from `Binder.getCallingUid()`, never from the request: the package name comes from the package manager and its
  signing certificate must match the SHA-256 digest **pinned when the owner approved the app** (trust on first use; a different key for the same
  package name is a new, unknown app). Unknown callers are rejected and recorded as pending requests. Apps signed with this app's own key are trusted for
  running scripts (switch in *Access*); package management always needs its own grant. The declared permission is `normal` level and is only a discovery gate.
- **Job isolation between callers.** Job IDs are 128 random bits, every lookup checks the owner, and *unknown*, *malformed* and *someone else's* IDs get the same
  answer (`JOB_NOT_FOUND`). Artifact IDs are opaque and looked up in the job record; URIs are readable only with a grant for that job.
- **What a script can reach.** Only the job's `input/`, `output/`, `tmp/`; no processes, no network, no `debug`, no native loading, no bytecode.
  Limits on time, memory, output, file size and total files are enforced inside the runner and again by the host.
- **Input hardening.** Parcelables carry a version and length and bound every count; the environment travels as plain strings (no `Bundle` from the wire);
  descriptors must be regular files and are read with `pread`.

What is **not** a boundary — please read:

- The runner runs under the **same Android UID** as the host app. A memory-corruption bug in the Lua VM, or a malicious *prebuilt native rock*, could read the
  host's private data. Process isolation is not UID isolation. Mitigations: no native code is ever built or loaded unless the owner places it there; install only
  rocks you trust; approve only apps you trust. Making the runner an `isolatedProcess` service would change this and is listed as future work.
- A pure-Lua rock runs with the same powers as any script (sandboxed), but it is *shared code*: a malicious rock can read the input of every later job that requires it.
- `QUERY_ALL_PACKAGES` is declared because Android 11+ hides calling apps otherwise and caller identification depends on it. This is fine for sideloading from
  GitHub; Google Play would need a policy declaration.
- `INTERNET` is used only by the package manager's download bridge. Scripts have no network access.

## Building, testing, releasing

Requirements: JDK 17, Android SDK (platform 35, build-tools 35), Android NDK (r27), a C compiler and Python 3 for the host tests.

```sh
make -C runner test                      # native runner on Linux: 32 sandbox tests + 13 LuaRocks tests (mock server, no network)
./gradlew :app:testDebugUnitTest         # 38 JVM unit tests (validation, state mapping, access store, URL policy, ...)
./gradlew :app:assembleRelease           # APK (builds libluart.so for 3 ABIs with the NDK; pass -PndkDir=... if needed)
# on an emulator/device:
./gradlew :app:installDebug :client-example:connectedDebugAndroidTest   # cross-app acceptance tests (second UID)
./gradlew :app:connectedDebugAndroidTest                                  # owner UI smoke test
```

The acceptance tests mirror Manual §10 (see the mapping in Manual §11). Debug builds contain a `TestControlProvider` used by them
(reset state, approve apps, submit as the owner); it is not in release builds (checked by `scripts/verify-apk.sh`).

**CI** (`.github/workflows/ci.yml`): runner tests on Linux; Android build + unit tests + APK structure checks (ABIs, alignment, manifest, signature);
the acceptance tests on an Android 14 x86_64 emulator.

**Releases** (`.github/workflows/release.yml`): push a tag `vMAJOR.MINOR.PATCH[-suffix.N]`; the workflow builds, verifies and publishes the APKs, the example client,
the client library and checksums.

```sh
git tag v1.0.0 && git push origin v1.0.0
```

Signing: run `scripts/setup-signing.sh` once (creates a key and stores it as the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). **Back up the key**: without it users must uninstall to update. Until the secrets exist, releases are signed
with a throw-away key (clearly stated in the release notes) and consecutive releases cannot update each other.

## Limitations

- Isolation is process-level, not UID-level (see above).
- Pure-Lua packages only; no on-device compilation.
- The arm64-v8a and armeabi-v7a runners are built and structurally verified in CI (ELF type, architecture, 16 KB alignment) but only the x86_64 build is *executed* there (no ARM emulator on the runners).
- Device-restart recovery shares the code path of process death (which is tested) but is not separately automated.
- One rock tree for all apps; no per-app package sets.
- Results over 1 MiB must be written to files.

## Third-party software

Lua 5.4.9, LuaRocks 3.13.0 (with its bundled dkjson 2.7 and argparse), LuaFileSystem 1.9.0, lua-zlib 1.4, LuaSocket's `ltn12` 3.1.0 — all MIT-licensed, vendored
unmodified under [`third_party/`](third_party/); see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). zlib comes from the Android system. No license has been chosen for this
repository's own code yet.
