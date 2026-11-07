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
        versionCode = 5
        versionName = "2.1"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
