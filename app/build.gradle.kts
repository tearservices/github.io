plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lingo.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lingo.overlay"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build is a genuine upgrade rather
        // than a same-version reinstall.
        versionCode = (project.findProperty("lingoVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("lingoVersionName") as String?) ?: "1.0-local"

        // ML Kit ships the translation and language-id engines as native
        // libraries for four ABIs. x86 and x86_64 are emulator-only and were
        // over half the APK; this is an app you download onto a phone over
        // mobile data, so they are not worth 36 MB.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * A checked-in key, deliberately.
     *
     * AGP generates a debug keystore per machine, so every CI runner was signing
     * with a different key. Android refuses to install an update whose signature
     * does not match the installed app, which is why each build collided with
     * the last and had to be uninstalled first. Pinning one key makes updates
     * install straight over the top.
     *
     * The trade-off is that the key is public, so anyone could build an APK
     * Android would accept as an update to this one. For a personal sideloaded
     * app that is a fair price for being able to update from a phone; to close
     * it, move the keystore into a GitHub Actions secret.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/lingo.jks")
            storePassword = "lingokey"
            keyAlias = "lingo"
            keyPassword = "lingokey"
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

    // On-device translation. Deliberately not the Cloud Translation API: this
    // overlay has to keep up with a finger flinging a chat list, and a network
    // round trip per message would visibly trail the text it is covering. These
    // are the same models Google Translate uses offline, running in-process.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
