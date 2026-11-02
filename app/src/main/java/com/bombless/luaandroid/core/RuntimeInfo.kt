package com.bombless.luaandroid.core

import android.content.Context
import android.os.Build
import com.bombless.luaandroid.BuildConfig
import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.LuaRuntimeContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The JSON returned by ILuaRuntimeService.getRuntimeInfo(). No absolute paths are exposed. */
class RuntimeInfo(private val context: Context, private val paths: Paths, private val settings: AppSettings) {
    /** `libluart.so version`, e.g. {"luart":"1.0","lua":"Lua 5.4.9","abi":"arm64-v8a","pointerBits":64}. */
    val runner: JSONObject by lazy { queryRunner() }

    private fun queryRunner(): JSONObject = try {
        val p = ProcessBuilder(paths.runner.path, "version").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(3, TimeUnit.SECONDS)
        JSONObject(text.trim())
    } catch (_: Exception) {
        JSONObject().put("luart", "unavailable").put("lua", "Lua 5.4").put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
    }

    fun json(): String {
        val r = runner
        val o = JSONObject()
        o.put("apiVersion", LuaRuntimeContract.API_VERSION)
        o.put("app", JSONObject().put("package", context.packageName).put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
        o.put("lua", JSONObject().put("release", r.optString("lua")).put("abi", r.optString("abi")).put("runner", r.optString("luart")).put("pointerBits", r.optInt("pointerBits")))
        o.put("device", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))
        o.put(
            "luarocks",
            JSONObject().put("version", BuildConfig.LUAROCKS_VERSION).put("tree", "app-private").put("pureLuaOnly", true)
                .put("nativeModules", "only prebuilt .so files for this ABI; nothing is compiled on the device"),
        )
        o.put(
            "limits",
            JSONObject()
                .put("maxInlineSourceBytes", ApiLimits.MAX_INLINE_SOURCE_BYTES)
                .put("maxScriptFileBytes", ApiLimits.MAX_SCRIPT_FILE_BYTES)
                .put("maxInputFileBytes", ApiLimits.MAX_INPUT_FILE_BYTES)
                .put("maxTotalInputBytes", ApiLimits.MAX_TOTAL_INPUT_BYTES)
                .put("maxInputFiles", ApiLimits.MAX_INPUT_FILES)
                .put("defaultTimeoutMillis", ApiLimits.DEFAULT_TIMEOUT_MILLIS)
                .put("maxTimeoutMillis", settings.maxTimeoutMillis)
                .put("defaultMemoryLimitBytes", ApiLimits.DEFAULT_MEMORY_LIMIT_BYTES)
                .put("maxMemoryLimitBytes", ApiLimits.MAX_MEMORY_LIMIT_BYTES)
                .put("streamCapBytes", settings.streamCapBytes)
                .put("maxInlineResultBytes", ApiLimits.MAX_INLINE_RESULT_BYTES)
                .put("maxActiveJobsPerCaller", Limits.MAX_ACTIVE_PER_CALLER)
                .put("retentionDays", settings.retentionDays),
        )
        return o.toString()
    }
}
