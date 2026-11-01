plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshpet.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dshpet.android.screen"
        minSdk = 28
        targetSdk = 34
        versionCode = 33
        versionName = "1.8.23-screenbtn"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/dshpet.jks")
            storePassword = "dshpet123"
            keyAlias = "dshpet"
            keyPassword = "dshpet123"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            // 固定 keystore 签名，保证后续版本能覆盖安装
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // 动画 WebP 已经压过，构建时不要再二次处理
        noCompress += listOf("webp")
    }
}

dependencies {
    // 刻意零第三方依赖：只用 Android 框架 API（minSdk 28 足够）
}
