# ---------------------------------------------------------------------------
# BiliMonitor release 混淆规则
#
# 本工程使用 Retrofit + kotlinx.serialization + Room + Hilt + WorkManager。
# 这些库都有"靠反射或按类名实例化"的部分，缺规则会在 release 构建上运行期崩溃，
# 而 debug（不混淆）完全正常 —— 因此规则必须随 release 配置一并交付。
# ---------------------------------------------------------------------------

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembernames class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.bilimonitor.**$$serializer { *; }

# ---- Retrofit ----
# 接口方法的泛型返回类型与注解在运行期被解析，不能删除/混淆。
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.example.bilimonitor.data.remote.bilibili.BiliLiveApi { *; }
-keep interface com.example.bilimonitor.data.remote.bilibili.BiliAccountApi { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ---- androidx.security:security-crypto（内部使用 Google Tink）----
# Tink 的类上带有 errorprone / javax.annotation 的编译期注解，
# 这些注解库是 compileOnly，不随 APK 分发。R8 会因此报 "Missing class"。
# 这是官方推荐的标准处理方式（注解仅用于静态检查，运行期不需要）。
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# ---- WorkManager ----
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# ---- 系统按类名实例化的组件 ----
-keep class com.example.bilimonitor.background.** { *; }
-keep class com.example.bilimonitor.MainActivity { *; }
-keep class com.example.bilimonitor.BiliMonitorApp { *; }

# ---- 枚举 ----
# 本项目用 Enum.name 持久化，且与 DDL CHECK 字面量逐字一致；
# 混淆枚举常量名会直接让已有数据库中的历史数据无法解析。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# ---- 保留行号，便于线上崩溃定位 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
