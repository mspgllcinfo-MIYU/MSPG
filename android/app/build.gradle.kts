plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mspgllc.iqpuchin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mspgllc.iqpuchin"
        minSdk = 24
        targetSdk = 34
        // AZUSAN-SIZE-TEST-01 established bumping these every round purely
        // for on-device identification (they're read by nothing in game
        // logic). This STAGE-DESIGN-01C revision (0-row wave gap,
        // superseding the prior 01C's 2-row-gap build at versionCode 14)
        // continues that -- versionCode still bumped even though
        // versionName is reused verbatim, since Android requires a
        // strictly increasing versionCode to install as an upgrade.
        versionCode = 15
        versionName = "0.1-STAGE_DESIGN_01C"
    }

    // Pinned debug signing key (app/debug.keystore), checked into the repo
    // on purpose: standard, publicly-documented Android debug credentials
    // (password "android", alias "androiddebugkey" -- not a secret, the
    // same values `keytool`/AGP use to auto-generate a debug keystore
    // anyway). Without this, every CI runner starts clean and AGP
    // generates a *different random* debug key per run, so re-installing
    // a newer debug APK over an older one fails (Android refuses installs
    // with a mismatched signature). Pinning it means every build -- CI or
    // local -- shares one signature and installs as a normal upgrade.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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
