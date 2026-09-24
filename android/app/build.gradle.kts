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
        // logic). CAT-PAW-IMAGE-TITLE-01: the left stick's knob and the
        // right ACTION button now show the two real Azusan-paw photos
        // provided this round (fur-from-above / pad-forward) instead of
        // the prior round's Canvas-drawn CatPawShape (deleted, now fully
        // unused); PLAYER_GLIDE_CELLS_PER_SEC retuned 5.0f->2.5f; and the
        // app now opens on a real title/START-wait screen (a new
        // GameView.titleActive freeze, gated first in the frame loop's
        // own branch chain, ahead of stageStartActive) instead of
        // launching straight into STAGE 1 -- QUBE/player/LIFE/SCORE are
        // all frozen until START is tapped, at which point the existing,
        // unmodified "STAGE 1 / READY" sequence begins exactly as
        // before. 360-degree input/movement/dead-zone/haptics, cat
        // punch, MARK/ACTIVATE, QUBE, GAME OVER, camera, and SE are all
        // untouched.
        versionCode = 42
        versionName = "0.1-CAT_PAW_IMAGE_TITLE_01"
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
