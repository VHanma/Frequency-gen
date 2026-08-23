plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vaan.frequencyremapper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vaan.frequencyremapper"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Keep the rendered file available even when Android's automatic MediaStore
// write fails, and route verified exports through the public Downloads folder.
// This source patch is idempotent and is applied before every Kotlin compile.
val applyReliableSaveFix by tasks.registering {
    doLast {
        val activity = file("src/main/java/com/vaan/frequencyremapper/MainActivity.kt")
        var text = activity.readText()

        val automaticSaveBlock = Regex(
            """val uri = AudioSaver\.saveToMusic\(\s*context,\s*file,\s*AudioSaver\.defaultOutputName\(source\.sourceName\)\s*\)\s*file to uri"""
        )
        text = automaticSaveBlock.replace(text) {
            """val uri = runCatching {
                        ReliableAudioSaver.saveToDownloads(
                            context,
                            file,
                            AudioSaver.defaultOutputName(source.sourceName)
                        )
                    }.getOrNull()
                    file to uri"""
        }

        text = text.replace(
            "AudioSaver.copyToUri(context, file, destination)",
            "ReliableAudioSaver.copyToUri(context, file, destination)"
        )

        text = text.replace(
            "status = \"Rendered and saved in Music/FrequencyRemapper.\"",
            """status = if (result.second != null) {
                    "Rendered and saved in Downloads/FrequencyRemapper."
                } else {
                    "Rendered successfully. Automatic save failed, so tap SAVE AS… and choose the exact location."
                }"""
        )

        text = text.replace(
            "This creates a new WAV in Music/FrequencyRemapper. Your original file stays untouched.",
            "This creates a new WAV in Downloads/FrequencyRemapper. Your original file stays untouched."
        )

        check("ReliableAudioSaver.saveToDownloads" in text) {
            "Reliable save patch did not apply to MainActivity.kt"
        }
        check("ReliableAudioSaver.copyToUri" in text) {
            "Reliable Save As patch did not apply to MainActivity.kt"
        }
        activity.writeText(text)
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.endsWith("Kotlin")) {
        dependsOn(applyReliableSaveFix)
    }
}
