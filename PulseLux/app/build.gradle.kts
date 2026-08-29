plugins {
    id("com.android.application")
}

android {
    namespace = "com.vaan.pulseluxsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vaan.pulseluxsync"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
