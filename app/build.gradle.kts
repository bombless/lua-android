plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.parcelize")
}
android {
 namespace = "com.bombless.luaandroid"
 compileSdk = 35
 defaultConfig { applicationId = "com.bombless.luaandroid"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.luaj:luaj-jse:3.0.1") }
