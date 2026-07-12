plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vhanma.lightcode.codex"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vhanma.lightcode.codex"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-codex"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../app/src/main/java")
            java.exclude(
                "com/vhanma/lightcode/MainActivity.kt",
                "com/vhanma/lightcode/SignalFactory.kt",
                "com/vhanma/lightcode/LightSurfaceView.kt"
            )
        }
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
