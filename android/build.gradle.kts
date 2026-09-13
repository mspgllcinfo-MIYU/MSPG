plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // Firebase接続準備: google-services.json を読み込み、FirebaseのAPIキー/プロジェクト
    // 設定をビルドへ反映するプラグイン。Firestore/匿名認証のみを使用し、Firebase
    // Storageは不使用（写真/ファイル本体はGoogle Driveへ保存する方針のため）。
    id("com.google.gms.google-services") version "4.4.4" apply false
}
