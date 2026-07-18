plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vhanma.lightcode.photophone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vhanma.lightcode.investigation"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-investigation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// The universal payload implementation is intentionally kept as one readable laboratory source
// file. This pre-compile normalization fixes Kotlin visibility inference and locks the first
// protocol version to a stable FEC format across every loop pass.
val normalizeUniversalPayloadSources by tasks.registering {
    doLast {
        val streamFile = file(
            "src/main/java/com/vhanma/lightcode/photophone/UniversalPayloadStreaming.kt"
        )
        if (streamFile.exists()) {
            val original = streamFile.readText()
            val patched = original
                .replace(
                    "private class UniversalPacketByteStream(",
                    "internal class UniversalPacketByteStream("
                )
                .replace(
                    "return PacketFactoryHolder.currentFec.get() ?: false",
                    "return true"
                )
            streamFile.writeText(patched)
        }

        val activityFile = file(
            "src/main/java/com/vhanma/lightcode/photophone/UniversalPayloadActivity.kt"
        )
        if (activityFile.exists()) {
            val original = activityFile.readText()
            val patched = original.replace(
                """fecCheck = CheckBox(this).apply {
            text = "Extended Hamming error correction"
            setTextColor(Color.WHITE)
            isChecked = true
        }""",
                """fecCheck = CheckBox(this).apply {
            text = "Extended Hamming error correction · required in ULP3"
            setTextColor(Color.WHITE)
            isChecked = true
            isEnabled = false
        }"""
            )
            activityFile.writeText(patched)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(normalizeUniversalPayloadSources)
}
