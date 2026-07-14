plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vhanma.lightcode.photophone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vhanma.lightcode.photophone.v2"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0-forge"
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
