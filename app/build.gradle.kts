plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.6.6"

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
            isMinifyEnabled = true
            isShrinkResources = true
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
