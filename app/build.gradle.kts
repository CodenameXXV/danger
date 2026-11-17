import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Sherpa-ONNX Android AAR ──────────────────────────────────────────────────
// AAR (~48 МБ) не зберігається в репозиторії: при першій збірці Gradle сам
// завантажує його з офіційного релізу k2-fsa/sherpa-onnx у папку app/libs/.
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
val sherpaAarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"

tasks.register("downloadSherpaAar") {
    group = "setup"
    description = "Завантажує sherpa-onnx-$sherpaVersion.aar в app/libs/, якщо файла там немає"
    outputs.file(sherpaAar)
    onlyIf { !sherpaAar.exists() }
    doLast {
        sherpaAar.parentFile.mkdirs()
        val tmp = File(sherpaAar.parentFile, sherpaAar.name + ".part")
        println("Завантаження $sherpaAarUrl …")
        val conn = URL(sherpaAarUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        try {
            if (conn.responseCode != 200) {
                throw GradleException("Не вдалося завантажити $sherpaAarUrl (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() < 1_000_000L) {
            tmp.delete()
            throw GradleException("Завантажений файл підозріло малий — перевірте інтернет")
        }
        if (!tmp.renameTo(sherpaAar)) {
            sherpaAar.delete()
            tmp.renameTo(sherpaAar)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadSherpaAar")
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
    // Офлайн-синтез мовлення (Sherpa-ONNX framework + ONNX Runtime).
    // AAR докачується завданням downloadSherpaAar при першій збірці.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Розпакування моделі TTS (.tar.bz2), яку докачуємо при першому запуску
    implementation("org.apache.commons:commons-compress:1.21")
}
