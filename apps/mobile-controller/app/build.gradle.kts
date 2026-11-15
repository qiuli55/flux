import java.util.Properties

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
        // 手机端已装过实验版 vc9（0.3.0 轻量重写），versionCode 必须跨过 9 才能覆盖安装。
        // 已发布过 0.9.12（vc69），本次提升到 vc70，保证同签名直接覆盖升级。
        versionCode = 70
        versionName = "0.9.13"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 固定的分发签名：历史 APK（含 vc57 / 0.9.0）全部用 A 机的 debug keystore
    // （证书 SHA-256 ec560d8d…）签名，若不固定，各机器会用各自的 ~/.android/debug.keystore，
    // 导致 OTA 升级时报签名冲突。
    //
    // 解析顺序（前者优先）：
    //   1. 环境变量 FLUX_KEYSTORE_PATH / FLUX_KEYSTORE_PASSWORD / FLUX_KEY_ALIAS / FLUX_KEY_PASSWORD
    //      （CI 由 secrets 注入）
    //   2. 本地 gitignored 的 signing.properties（storeFile / storePassword / keyAlias / keyPassword），
    //      路径可用 FLUX_SIGNING_PROPERTIES 覆盖；该文件永不入库
    //   3. keystore/flux-release.jks + 历史默认口令（仅当文件存在时生效）
    // keystore 本身不进仓库（*.jks 已忽略）；CI 通过 FLUX_KEYSTORE_BASE64 还原到 keystore/flux-release.jks。
    // 找不到分发 keystore 时明确告警并回落 AGP 默认 debug 签名（仅编译验证，不可用于分发/OTA），
    // 绝不静默换签名。
    val signingPropertiesFile = rootProject.file(
        System.getenv("FLUX_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() } ?: "signing.properties"
    )
    val signingProperties = Properties().apply {
        if (signingPropertiesFile.isFile) {
            signingPropertiesFile.inputStream().use { load(it) }
            logger.lifecycle("[FluxUx] loaded signing config from ${signingPropertiesFile.absolutePath}")
        }
    }

    fun signingValue(envName: String, propertyName: String, fallback: String): String =
        System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
            ?: fallback

    val defaultKeystore = rootProject.file("keystore/flux-release.jks")
    val releaseKeystore = rootProject.file(
        signingValue("FLUX_KEYSTORE_PATH", "storeFile", defaultKeystore.path)
    )
    val releaseKeyAlias = signingValue("FLUX_KEY_ALIAS", "keyAlias", "androiddebugkey")
    val releaseStorePassword = signingValue("FLUX_KEYSTORE_PASSWORD", "storePassword", "android")
    val releaseKeyPassword = signingValue("FLUX_KEY_PASSWORD", "keyPassword", "android")

    signingConfigs {
        create("flux") {
            if (releaseKeystore.isFile) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    val distributionSigning =
        if (releaseKeystore.isFile) signingConfigs.getByName("flux") else null
    if (distributionSigning != null) {
        logger.lifecycle(
            "[FluxUx] distribution signing enabled: keystore=${releaseKeystore.absolutePath} " +
                "alias=${releaseKeyAlias}"
        )
    } else {
        logger.warn(
            "[FluxUx] distribution signing DISABLED: keystore not found at " +
                "${releaseKeystore.absolutePath}. Falling back to the AGP default debug keystore " +
                "(usually ~/.android/debug.keystore, generated on demand); such an APK can NOT " +
                "install as an in-place OTA update. Provide signing.properties or FLUX_KEYSTORE_* " +
                "env vars to sign with the historically distributed certificate."
        )
    }

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

    // Compose UI 测试跑在 JVM（Robolectric）上，需要真实的 Android 资源与 manifest。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

    // Compose UI 测试（JVM / Robolectric）：本机没有可用模拟器，且基线工程没有任何
    // androidTest 基础设施，因此把布局断言放在 :app:testDebugUnitTest 里跑。
    // 均为测试期依赖，不进 APK；compose 版本仍由上面的 BOM 统一约束。
    testImplementation(platform("androidx.compose:compose-bom:2024.02.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.11.1")
    // createComposeRule 需要一个可装载的 ComponentActivity（仅 debug 变体测试用）。
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}