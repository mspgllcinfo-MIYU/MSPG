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
        // logic). CAT-PAW-FEEL-03: PawActionButtonView's press feedback
        // is now a multi-stage "ムニョッ -> ミューン -> プルンッ" elastic
        // squash-and-recover (ValueAnimator-driven scaleX/scaleY/sink,
        // applied only inside onDraw's own canvas transform -- never a
        // real View.scaleX/scaleY/translationY, so Android's touch-event
        // coordinate remapping is never involved) instead of the old flat
        // 0.88x step. onTouchEvent's own ACTION_DOWN/MOVE/UP/CANCEL
        // coordinate handling, dx/dy computation, classifyGesture, and
        // GESTURE_THRESHOLD_DP (still 20dp) are all byte-for-byte
        // unchanged from GOLDEN -- gesture classification and
        // onPunchGesture/onTapClick still fire synchronously in
        // onTouchEvent, never gated by or waiting on the animation.
        // RotationalStickView, GameView (CAT PUNCH/MARK/ACTIVATE/QUBE
        // logic), MainActivity, and GAME OVER are all untouched.
        versionCode = 45
        versionName = "0.1-CAT_PAW_FEEL_03"
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
