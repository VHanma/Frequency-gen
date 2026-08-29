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
        versionCode = 2
        versionName = "1.1.0"
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
