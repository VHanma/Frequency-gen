plugins {
    id("com.android.application")
}

android {
    namespace = "com.vaan.infobeam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vaan.infobeam"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "4.0.0-parametric-lab"
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
}
