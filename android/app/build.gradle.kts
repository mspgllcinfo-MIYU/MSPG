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
        // logic). ROTATIONAL-STICK-STOP-FIX-01 fixes the rotational
        // stick's release behavior: releasing the stick (or letting it
        // return to its dead zone) used to force-resync the continuous
        // playerVisualX/Z back onto boardLogic.playerPosition every idle
        // frame, which visibly snapped Azusan up to ~half a cell
        // backward at the moment of release (the actual "doesn't stop
        // cleanly" symptom) and left the WALK sprite animation running
        // for up to WALK_VISUAL_DURATION_MS afterward. Now the visual
        // position simply freezes wherever the glide left off and the
        // WALK window is force-cancelled immediately. The 360-degree
        // input/movement math itself, its speed, and the stick's own
        // dead zone/clamp/haptic tuning are all untouched.
        versionCode = 40
        versionName = "0.1-ROTATIONAL_STICK_STOP_FIX_01"
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
