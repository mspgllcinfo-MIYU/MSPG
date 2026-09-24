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
        // logic). CAMERA-PROGRESSION-PHASE-2 generalizes
        // BoardRenderer.boardBounds()/drawTile() to the same 4-coefficient
        // (Xx,Xy,Zx,Zy) model Phase 1 gave IsoProjection -- boardBounds now
        // derives its margins by actually projecting the board's own
        // corners (no assumption that any coefficient's sign is
        // non-negative), and drawTile builds each tile's 4 corners as
        // center +/- Xbasis/2 +/- Zbasis/2. Both are verified algebraically
        // and numerically identical to the prior formulas for every Stage
        // (still FRONT_ALIGNED-equivalent everywhere), so on-screen board
        // position/size/tiling are byte-for-byte unchanged. No Stage-
        // dependent projection table exists yet (a later round); cat
        // punch, GAME OVER sequence, SE, LIFE, STAGE_WAVES, and board/
        // camera game logic are all unchanged.
        versionCode = 38
        versionName = "0.1-CAMERA_PROJECTION_PHASE2"
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
