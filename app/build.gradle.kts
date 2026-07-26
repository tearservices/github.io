plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cyrfix.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cyrfix.overlay"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build is a genuine upgrade rather
        // than a same-version reinstall.
        versionCode = (project.findProperty("cyrfixVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("cyrfixVersionName") as String?) ?: "1.0-local"
    }

    /**
     * A checked-in key, deliberately.
     *
     * AGP generates a debug keystore per machine, so every CI runner was signing
     * with a different key. Android refuses to install an update whose signature
     * does not match the installed app, which is why each build collided with
     * the last and had to be uninstalled first. Pinning one key makes updates
     * install straight over the top.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/cyrfix.jks")
            storePassword = "cyrfixkey"
            keyAlias = "cyrfix"
            keyPassword = "cyrfixkey"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The bundled Noto Sans faces must never be run through the resource
    // optimizer -- AAPT2 will happily "compress" a .ttf into something the
    // font loader cannot parse.
    androidResources {
        noCompress += listOf("ttf")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
