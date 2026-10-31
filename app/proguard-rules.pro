-keep class org.luaj.** { *; }
-keep class com.bombless.luaandroid.ipc.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
