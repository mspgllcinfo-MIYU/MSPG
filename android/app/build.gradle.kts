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
        // logic). TITLE-SCREEN-FINAL-01: the title screen now shows the
        // final title art (title_qube_zero.png replaced in place -- QUBE:
        // ZERO logo, PUSH THINK ESCAPE, the ぷちんっ gag, Azusan, MIYU x AI,
        // and a fully-designed START button, all baked into the image).
        // The prior round's separate android.widget.Button is gone --
        // tapping is now hit-tested (MainActivity.isTouchOnTitleStart)
        // against that drawn button's own measured position, accounting
        // for the ImageView's FIT_CENTER letterboxing, so the tap target
        // stays aligned with the art on any screen ratio; the transparent
        // region draws nothing of its own. Tapping it still calls the
        // same, unmodified GameView.beginPlay(). 360-degree input/
        // movement/dead-zone/haptics, both paw images' sizing, cat punch,
        // MARK/ACTIVATE, QUBE, STAGE CLEAR, GAME OVER, camera, and SE are
        // all untouched.
        versionCode = 44
        versionName = "0.1-TITLE_SCREEN_FINAL_01"
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
