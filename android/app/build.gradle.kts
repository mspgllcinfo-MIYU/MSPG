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
        // logic). GAMEOVER-GLASS-02: the GAME OVER finishing sequence's
        // glass now cracks progressively from each cat's own impact point
        // (mari -> anko -> azusan -> BB's 4 stomps), accumulating toward
        // the existing 6300ms GLASS_SHATTER -- purely additive to
        // GlassCrackEffect (a parallel GoStage pattern system alongside
        // the untouched HIT1-3 one) plus one new guarded draw call in
        // GameView.onDraw. GameOverCatEffect's own cat-animation body and
        // fixed timing constants, PawActionButtonView's "ムニョ" press
        // animation, RotationalStickView, ActionInputSource, CAT PUNCH,
        // MARK/ACTIVATE, QUBE movement, and the title/START flow are all
        // byte-for-byte untouched from GOLDEN.
        versionCode = 46
        versionName = "0.1-GAMEOVER_GLASS_02"
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
