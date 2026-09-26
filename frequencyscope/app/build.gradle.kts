plugins { id("com.android.application") }
android {
    namespace = "com.vaan.frequencyscope"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vaan.frequencyscope"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
