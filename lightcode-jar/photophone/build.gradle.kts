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

// Keep only the canonical unlimited ULP3 implementation. Earlier experimental files may be
// restored by Gradle source caches, so remove those duplicates before every Kotlin compilation.
val normalizeUniversalPayloadSources by tasks.registering {
    doLast {
        listOf(
            "src/main/java/com/vhanma/lightcode/photophone/StreamingOpticalEngines.kt",
            "src/main/java/com/vhanma/lightcode/photophone/UniversalPayloadEncoderActivity.kt",
            "src/main/java/com/vhanma/lightcode/photophone/StreamingPayloadEngines.kt",
            "src/main/java/com/vhanma/lightcode/photophone/StreamingPayloadLightView.kt",
            "src/main/java/com/vhanma/lightcode/photophone/UniversalPayloadCarrier.kt",
            "src/main/java/com/vhanma/lightcode/photophone/UniversalPayloadStream.kt"
        ).forEach { stalePath ->
            val stale = file(stalePath)
            if (stale.exists()) stale.delete()
        }

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
