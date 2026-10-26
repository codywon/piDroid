# ---------------------------------------------------------------------------
# piDroid R8 / ProGuard 规则
#
# 原则：保留「靠反射按名字访问」的东西，其余全部交给 R8 压缩与混淆。
# 任何新增的、由 Gson 反射反序列化的类都必须登记到下面的 model 保留区。
# ---------------------------------------------------------------------------

# 保留注解与泛型签名（Gson 依赖 Signature 属性还原泛型）
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 崩溃栈保留行号（混淆后仍可定位）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Gson ------------------------------------------------------------------
# 字段名即协议字段名，@SerializedName 覆盖的字段不能被改名
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 所有网络数据模型整体保留：Models.kt 中并非每个字段都带 @SerializedName，
# 一旦字段被改名，服务端下发的 JSON 就会静默解析成 null。
-keep class com.pi.app.data.model.** { *; }
-keepclassmembers class com.pi.app.data.model.** {
    <fields>;
    <init>(...);
}

# --- OkHttp / Okio / SSE ---------------------------------------------------
-keep class okhttp3.internal.platform.** { *; }
-keep class com.squareup.okhttp3.sse.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- 枚举 ----------------------------------------------------------------
# values() / valueOf() 被 R8 改写后，枚举反序列化会拿到 null
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Android 组件 --------------------------------------------------------
-keep class com.pi.app.MainActivity { *; }
-keep class com.pi.app.keepalive.PiForegroundService { *; }

# FileProvider 在 AndroidManifest 中按类名实例化
-keep class androidx.core.content.FileProvider { *; }

# --- commonmark ----------------------------------------------------------
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**

# --- Kotlin metadata / when 映射表 ---------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
