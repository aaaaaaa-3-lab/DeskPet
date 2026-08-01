plugins {
    id("com.android.application")
}

android {
    namespace = "com.vaelky.deskpet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vaelky.deskpet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}