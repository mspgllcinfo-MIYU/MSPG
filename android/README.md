# poicat (Android)

`com.mspg.poicat` の新規Androidプロジェクトです。既存の `app-debug.apk`
を解析し、判読可能だった仕様（画面構成・パッケージ名・チャットのやり取り・
音声入力など）を元に、クリーンなKotlin/Jetpack Composeで再構築しています。

## 現在の状態

| 画面 | 状態 |
|---|---|
| 猫AI（AIチャット） | ✅ 完全動作（Firebase認証 + Cloudflare Worker連携） |
| ホーム / ポイ / カレンダー / メモ | 🚧 ナビゲーションのみ（準備中プレースホルダー） |

まず「猫AI」チャットを最優先で完全動作させました。他の4画面は元のAPKに
機能が存在すること自体は解析済みですが、データモデルやUIの再構築は
今後段階的に行います。

## 認証方式

**Firebase Anonymous Authentication**（匿名認証）を採用しています。
ログイン画面はなく、アプリ起動時に自動的にサインインします。
取得したFirebase IDトークンを使って、Cloudflare Worker
（`poicat-openai-proxy`）にリクエストします。

## AIチャットの仕組み

1. アプリ起動時に匿名サインイン（`AuthRepository`）
2. メッセージ送信時、Firebase IDトークンを取得
3. `CatAiClient` が以下へPOST:
   `https://poicat-openai-proxy.mspgllc-info.workers.dev/v1/chat/completions`
   ヘッダー: `Authorization: Bearer <Firebase ID token>`
4. レスポンス（OpenAI Chat Completions形式）から返信を取り出し表示
5. 会話履歴はアプリ内ローカル（`filesDir`）にJSONで保存（元のAPKと同じ方式）

3つの部屋（仕事 / プライベート / 雑談）を切り替えて会話できます
（元のAPKの `ChatRoom` をそのまま踏襲）。

## ビルドに必要な設定（GitHub Actions）

このプロジェクトは **GitHub Actions でクラウドビルド** します。
PC不要で、リポジトリへのpushをきっかけに自動的にAPKがビルドされ、
Actionsの実行結果ページからダウンロードできます。

ビルドには `google-services.json`（Firebaseの設定ファイル）が必要ですが、
秘密情報を含むためリポジトリにはコミットしません。代わりに、GitHubの
リポジトリシークレット `GOOGLE_SERVICES_JSON` にファイルの中身をそのまま
貼り付けて設定してください（`.github/workflows/android-build.yml` が
ビルド時にこの中身を `android/app/google-services.json` として書き出します）。

## ローカルでの開発（Android Studioがある場合）

```bash
cd android
# Firebaseコンソールからダウンロードした google-services.json を
# android/app/google-services.json に配置してから:
./gradlew assembleDebug
```
