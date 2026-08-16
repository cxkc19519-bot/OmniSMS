plugins {
    id("com.android.application")
}

android {
    namespace = "com.omnisms.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omnisms.probe"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
