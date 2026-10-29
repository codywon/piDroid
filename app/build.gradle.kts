plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * R8 逆送开关。默认开启压缩与混淆。
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
val minifyRelease = (project.findProperty("pidroid.minify") as String?)?.toBoolean() ?: true

android {
    namespace = "com.pi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 68
        versionName = "1.7.42"

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
        // 发布签名完全由环境变量提供，不再内置任何默认密码。
        // 缺少 keystore/密码时 AGP 的 validateSigning 会直接失败，
        // 而不是静默产出一个未签名的 APK。配置方法见 docs/SIGNING.md。
        create("release") {
            val keystoreFile = file("${rootDir}/keystore/release.jks")
            storeFile = keystoreFile.takeIf { it.exists() }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "pidroid"
            keyPassword = System.getenv("KEY_PASSWORD")
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
