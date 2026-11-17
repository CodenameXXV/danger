plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.nebosumy"
    compileSdk = 34

    defaultConfig {
        applicationId = "ua.nebosumy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // sherpa-onnx AAR містить .so для 4 ABI; лишаємо тільки потрібні,
        // щоб APK не роздувся (x86_64 — для емулятора).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("monitor.jks")
            storePassword = "monitor123"
            keyAlias = "monitor"
            keyPassword = "monitor123"
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
    // Офлайн-синтез мовлення (Sherpa-ONNX framework + ONNX Runtime, Android AAR з
    // https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Розпакування моделі TTS (.tar.bz2), яку докачуємо при першому запуску
    implementation("org.apache.commons:commons-compress:1.21")
}
