import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.salarycat.rphub"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.salarycat.rphub"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "2.2.7"

        // 内置内容的版本号，需与 assets/web/version.json 中的 versionCode 一致
        buildConfigField("int", "BUNDLED_CONTENT_VERSION", "${project.findProperty("bundledContentVersion") ?: 0}")
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://rp-hub-mod.pages.dev/\"")
        // 壳能理解的内容格式版本，用于 version.json 的 minShellVersion 校验
        buildConfigField("int", "SHELL_VERSION", "2")
    }

    signingConfigs {
        create("release") {
            // 签名信息只从环境变量或本地 keystore.properties 读取，
            // 绝不写入版本库。CI 上由 GitHub Actions Secrets 注入。
            val props = Properties().apply {
                val f = rootProject.file("keystore.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun cfg(env: String, key: String): String? =
                System.getenv(env) ?: props.getProperty(key) as String?

            val store = cfg("RPHUB_STORE_FILE", "storeFile") ?: "../rphub.keystore"
            storeFile = rootProject.file(store)
            storePassword = cfg("RPHUB_STORE_PASSWORD", "storePassword")
            keyAlias = cfg("RPHUB_KEY_ALIAS", "keyAlias") ?: "rphub"
            keyPassword = cfg("RPHUB_KEY_PASSWORD", "keyPassword")

            // v1 兼容 Android 6 及以下；v3 支持日后密钥轮换，
            // 万一需要换证书也能保持覆盖安装能力。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    androidResources {
        // web 内容需保持原样，不能被压缩破坏
        noCompress += listOf("zip")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
