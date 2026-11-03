import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------------------------
// Native runner: libluart.so is an *executable* (one OS process per job) packaged as a "native
// library" so that Android extracts it into nativeLibraryDir, the only place apps may exec from.
// AGP's CMake integration does not package executables, so the NDK clang is driven directly with
// exactly the sources the host Makefile (runner/Makefile) builds for the Linux tests.
// ---------------------------------------------------------------------------------------------
abstract class BuildLuaRunner : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Input abstract val includeDirs: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val prebuilt = File(ndkDir.get(), "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: throw GradleException("NDK toolchain not found under ${ndkDir.get()}")
        val triples = mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "armv7a-linux-androideabi",
            "x86_64" to "x86_64-linux-android",
            "x86" to "i686-linux-android",
        )
        val cFiles = sources.files.filter { it.extension == "c" }.map { it.path }.sorted()
        for (abi in abis.get()) {
            val out = File(outputDir.get().asFile, "$abi/libluart.so")
            out.parentFile.mkdirs()
            val clang = File(prebuilt, "bin/${triples.getValue(abi)}${apiLevel.get()}-clang").path
            val cmd = mutableListOf(
                clang, "-O2", "-fPIE", "-pie", "-Wall", "-DLUA_USE_LINUX", "-DANDROID", "-D_FILE_OFFSET_BITS=64",
            )
            includeDirs.get().forEach { cmd += listOf("-I", it) }
            cmd += cFiles
            cmd += listOf(
                "-o", out.path,
                "-Wl,--export-dynamic",      // prebuilt native rocks resolve lua_* symbols from the executable
                "-Wl,-z,max-page-size=16384", // 16 KB page size devices (Android 15+)
                "-Wl,-z,noexecstack", "-Wl,--build-id=none", "-Wl,-s",
                "-lz", "-lm", "-ldl",
            )
            logger.lifecycle("Building luart for $abi")
            exec.exec { commandLine(cmd) }
            check(out.isFile && out.length() > 0) { "luart for $abi was not produced" }
        }
    }
}

fun locateNdk(): String {
    (findProperty("ndkDir") as String?)?.let { return it }
    for (name in listOf("ANDROID_NDK_ROOT", "ANDROID_NDK_HOME", "ANDROID_NDK_LATEST_HOME")) {
        System.getenv(name)?.takeIf { File(it, "toolchains").isDirectory }?.let { return it }
    }
    val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
    val newest = sdkDir?.let { File(it, "ndk").listFiles()?.filter { d -> d.isDirectory }?.maxByOrNull { d -> d.name } }
    return newest?.path ?: throw GradleException("Android NDK not found. Install one (sdkmanager \"ndk;27.2.12479018\") or pass -PndkDir=<path>.")
}

val runnerAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val buildLuaRunner = tasks.register<BuildLuaRunner>("buildLuaRunner") {
    val third = rootProject.layout.projectDirectory.dir("third_party")
    sources.from(
        fileTree(third.dir("lua")) { include("*.c", "*.h"); exclude("lua.c", "luac.c") },
        third.file("lfs/lfs.c"), third.file("lfs/lfs.h"), third.file("lua-zlib/lua_zlib.c"),
        fileTree(rootProject.layout.projectDirectory.dir("runner/src")) { include("*.c", "*.h") },
    )
    includeDirs.set(listOf(third.dir("lua").asFile.path, third.dir("lfs").asFile.path))
    ndkDir.set(providers.provider { locateNdk() })
    abis.set(runnerAbis)
    apiLevel.set(26)
    outputDir.set(layout.buildDirectory.dir("generated/runner/jniLibs"))
}

// Lua-side runtime + LuaRocks sources shipped as assets (installed to files/lua/{runtime,luarocks} on first start).
val stageRuntimeAssets = tasks.register<Sync>("stageRuntimeAssets") {
    val root = rootProject.layout.projectDirectory
    into(layout.buildDirectory.dir("generated/runtime-assets"))
    into("lua/runtime") {
        from(root.dir("runner/lua"))
        from(root.file("third_party/luasocket/ltn12.lua")) { into("shims") }
    }
    into("lua/luarocks") { from(root.dir("third_party/luarocks/luarocks")) }
    doLast {
        val base = File(destinationDir, "lua")
        val files = base.walkTopDown().filter { it.isFile && it.name != "MANIFEST" }
            .map { it.relativeTo(base).invariantSeparatorsPath }.sorted().toList()
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { rel -> digest.update(rel.toByteArray()); digest.update(0); digest.update(File(base, rel).readBytes()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        File(base, "MANIFEST").writeText("sha256 $hash\n" + files.joinToString("\n") + "\n")
    }
}

val vName = (findProperty("versionName") as String?) ?: "1.0.0-dev"
val vCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.bombless.luaandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bombless.luaandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += runnerAbis }
        buildConfigField("String", "LUAROCKS_VERSION", "\"3.13.0\"")
    }

    // Release signing comes from the environment (CI) so that no key material lives in the repository.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    // Lint is not a release gate here; the vital-lint models would otherwise run on every assembleRelease.
    lint { checkReleaseBuilds = false }

    // Executables must be extracted to disk (nativeLibraryDir) to be runnable.
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets.getByName("main") {
        jniLibs.srcDir(buildLuaRunner.flatMap { it.outputDir })
        assets.srcDir(stageRuntimeAssets)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// Make the dependencies explicit: everything that reads the source sets (merge tasks, lint models) must see the
// runner and the staged assets, otherwise Gradle rejects the build ("uses this output ... without declaring a dependency").
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(buildLuaRunner) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(stageRuntimeAssets) }
tasks.configureEach {
    if (name.contains("lint", ignoreCase = true)) dependsOn(buildLuaRunner, stageRuntimeAssets)
}

dependencies {
    implementation(project(":ipc-api"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
