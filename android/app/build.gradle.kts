plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mspg.poicat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mspg.poicat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
    }

    signingConfigs {
        // Checked-in debug keystore, shared by every CI build (and any local
        // build) instead of each machine/runner generating its own. Without
        // this, GitHub Actions creates a fresh ~/.android/debug.keystore on
        // every run, so each new APK is signed differently and Android treats
        // installing it as a different app — wiping all on-device data instead
        // of updating in place.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

ksp {
    // Room writes each @Database's schema history here as JSON (one file per
    // version) whenever exportSchema = true — a compile-time-only record for
    // tracking/testing migrations. Never read by the app at runtime, so this
    // has no effect on on-device data.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase接続準備: 匿名認証＋Firestoreのみ（Sparkプラン無料枠、カード登録不要）。
    // Firebase Storageは使用しない — 写真/ファイル本体はGoogle Driveへ保存する方針のため。
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
