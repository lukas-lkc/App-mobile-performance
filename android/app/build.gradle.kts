plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.calorscan"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.calorscan"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        // Chave fixa no repositorio para que novas versoes instalem por cima da anterior.
        create("app") {
            storeFile = file("calorscan.keystore")
            storePassword = "calorscan"
            keyAlias = "calorscan"
            keyPassword = "calorscan"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
