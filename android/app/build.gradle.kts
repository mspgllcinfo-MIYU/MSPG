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
        // logic). CAMERA-PROGRESSION-PHASE-1 generalizes IsoProjection from
        // (axisMajorPx, axisMinorPx) to independent per-axis (Xx,Xy,Zx,Zy)
        // coefficients (design confirmed in the CAMERA-PROGRESSION-AUDIT-01/
        // DESIGN-02/DESIGN-02-FIX/DESIGN-03 design-only rounds), but still
        // constructs it with exactly the old FRONT_ALIGNED-equivalent
        // values for every Stage -- verified algebraically identical to the
        // prior 2-coefficient formula for arbitrary axisMajor/axisMinor, so
        // on-screen board position/size/lean are byte-for-byte unchanged.
        // No Stage-dependent projection table exists yet (that is a later
        // round); cat punch, GAME OVER sequence, SE, LIFE, STAGE_WAVES, and
        // board/camera logic are all unchanged.
        versionCode = 37
        versionName = "0.1-CAMERA_PROJECTION_PHASE1"
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
