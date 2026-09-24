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
        // logic). CAT-PAW-UI-FIX-02: real-device feedback found the paw
        // photos (CAT-PAW-IMAGE-TITLE-01) far too small -- the left
        // knob's own image now fills ~87.5% of its (unchanged) ring's
        // diameter instead of ~43.5%, and the right button's image scales
        // with it (still ~7.5% larger than the left, was ~12.5%); no
        // touch/input geometry changed. Also fixes a real bug: the 3-
        // stage glass-crack overlay (a GAME-OVER-context cue that
        // intentionally survives a stage transition) was bleeding through
        // the STAGE CLEAR screen because its draw call never checked
        // stageClear -- now gated on !stageClear, with zero effect on
        // GAME OVER itself (the two states are mutually exclusive by
        // construction). Title screen/START button, 360-degree input/
        // movement/dead-zone/haptics, cat punch, MARK/ACTIVATE, QUBE,
        // GAME OVER's own timing, camera, and SE are all untouched.
        versionCode = 43
        versionName = "0.1-CAT_PAW_UI_FIX_02"
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
