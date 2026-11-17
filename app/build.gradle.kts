import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fixedKeystoreFile = file("signing/photosyncguard.p12")
val fixedKeystorePassword = "PhotoSyncGuard2026!"
val fixedKeyAlias: String = KeyStore.getInstance("PKCS12").apply {
    fixedKeystoreFile.inputStream().use { load(it, fixedKeystorePassword.toCharArray()) }
}.aliases().nextElement()

android {
    namespace = "com.guard.photosync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guard.photosync"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "1.2"
    }

    signingConfigs {
        create("fixed") {
            storeFile = fixedKeystoreFile
            storePassword = fixedKeystorePassword
            keyAlias = fixedKeyAlias
            keyPassword = fixedKeystorePassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Shizuku API
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
