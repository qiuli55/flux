// app 模块：Flux 手机端（原生 Android，Kotlin + Jetpack Compose）。
//
// 版本说明见根 build.gradle.kts 注释；所有版本号都是固定值，不用动态版本（+）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "top.qiuli55.flux.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.qiuli55.flux.mobile"
        minSdk = 26
        targetSdk = 34
        // 手机端已装过实验版 vc9（0.3.0 轻量重写），versionCode 必须跨过 9 才能覆盖安装
        versionCode = 61
        versionName = "0.9.4"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 固定的分发签名：历史 APK 全部用 A 机的 debug keystore（证书 SHA-256 ec560d8d…）签名，
    // 若不固定，各机器会用各自的 ~/.android/debug.keystore，导致升级时报签名冲突。
    // 口令优先读环境变量（CI 由 secrets 注入），本地未设置时回落到历史默认值，保证 OTA 签名一致。
    // keystore 不进仓库（*.jks 已忽略）：CI 通过 FLUX_KEYSTORE_BASE64 注入；未注入时回落到
    // AGP 默认 debug 签名，仅用于编译验证，该产物不可用于分发。
    fun envOr(name: String, fallback: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

    val releaseKeystore = rootProject.file("keystore/flux-release.jks")
    signingConfigs {
        create("flux") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = envOr("FLUX_KEYSTORE_PASSWORD", "android")
                keyAlias = envOr("FLUX_KEY_ALIAS", "androiddebugkey")
                keyPassword = envOr("FLUX_KEY_PASSWORD", "android")
            }
        }
    }
    val distributionSigning =
        if (releaseKeystore.exists()) signingConfigs.getByName("flux") else null

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = distributionSigning ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = distributionSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}