plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// D1: release 签名从环境变量读取（CI 用 GitHub Secrets 注入）；
// 未配置时回退到 debug 签名，保证本地/CI 仍可构建。
val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.xf3.tuner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xf3.tuner"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
