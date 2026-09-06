plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vaan.voiceforgex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vaan.voiceforgex"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-omega"
        ndk { abiFilters += setOf("arm64-v8a") }
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
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "sherpa-onnx/native/osx-aarch64/**",
            "sherpa-onnx/native/osx-x64/**",
            "sherpa-onnx/native/win-arm64/**",
            "sherpa-onnx/native/win-x64/**",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.7") {
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
}
