plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * R8 压缩与混淆开关。默认开启。
 *
 * 审计结论（代码层面的反射面）：
 *  - 唯一的 Gson 反射反序列化目标是 TypeToken<List<Session>>，Session 位于
 *    com.pi.app.data.model，已被 proguard-rules.pro 整体保留；
 *  - 全工程无 Class.forName / getDeclaredField / getDeclaredMethod；
 *  - Manifest 声明的三个组件（MainActivity / PiForegroundService /
 *    FileProvider）均已显式 keep。
 * 因此 R8 风险可控。但若某次依赖升级引入了默认规则覆盖不到的反射，
 * 可先用 ./gradlew assembleRelease -Ppidroid.minify=false 出货，再排查。
 */
val minifyRelease = when (val raw = (project.findProperty("pidroid.minify") as String?)?.trim()?.lowercase()) {
    null -> true
    "true" -> true
    "false" -> false
    // 以前用 `toBoolean()`：任何拼错的值（flase / no / 0 / …）都会静默变成 false，
    // 于是一次手滑就发出去一个没压缩、没混淆的 release 包。宁可在配置阶段就红。
    else -> error("pidroid.minify 只能是 'true' 或 'false'（不区分大小写），实际收到：'$raw'")
}

// 发布签名材料：全部来自环境变量（CI 用 secrets 注入，说明见 docs/SIGNING.md）。
// 缺哪项由下面的 taskGraph 校验在「真的要出 release 包」时报错，不在此处卡住配置阶段。
val releaseKeystoreFile = file("${rootDir}/keystore/release.jks")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")

android {
    namespace = "com.pi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 211
        versionName = "2.0.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            val debugKeystore = file("${rootDir}/debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // 发布签名完全由环境变量提供，不再内置任何默认密码/别名。
        // 以前 KEY_ALIAS 有个硬编码兜底 "pidroid"：keystore 换代之后照样用它签，
        // 报错是「别名不存在」，把真正的病因（少配了一个 secret）藏了起来。
        create("release") {
            storeFile = releaseKeystoreFile.takeIf { it.exists() }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            val debugKeystore = file("${rootDir}/debug.keystore")
            if (debugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        release {
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

/**
 * 发布签名材料的硬校验，只在「本次构建真的要出 release 包」时生效。
 *
 * 不在配置阶段无条件 error：`testDebugUnitTest` 一样会配置整个工程，
 * 那样会让 CI 的单测步骤在缺 secret 时直接崩掉，而它跟签名根本无关。
 * 这里缺项直接失败，不让流水线带一个没签名或签错名的包往下走。
 */
val releasePackagingTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "validateSigningRelease"
)
tasks.matching { task -> task.name in releasePackagingTaskNames }.configureEach {
    // doFirst 只在这些任务**真的被执行**时跑，因此单测/日常配置仍然不受影响。
    doFirst {
        val missing = buildList {
            if (!releaseKeystoreFile.exists()) add("keystore/release.jks")
            if (releaseStorePassword.isNullOrBlank()) add("KEYSTORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured: missing ${missing.joinToString(", ")}. See docs/SIGNING.md."
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // 网络与 SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    // 相机扫码
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Markdown Standard Renderer (CommonMark + GFM Tables + Strikethrough + Autolink)
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.21.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.21.0")
}
