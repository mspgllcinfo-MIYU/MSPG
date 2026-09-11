# poicat-openai-proxy

`com.mspg.poicat` Android アプリから OpenAI API / Gemini API を安全に呼び出す
ための Cloudflare Workers 製サーバーサイドプロキシです。

このWorkerは元々OpenAI用でしたが、マリたん（音声AI）がGemini APIキーを
APKへ一切埋め込まない構成にするため、`/v1/gemini/generate` エンドポイントを
追加しました。新しい別のインフラは作らず、既存のFirebase IDトークン検証・
KVレート制限の仕組みをそのまま両エンドポイントで共有しています。

## 仕組み

1. Android アプリは Firebase Authentication でサインインし、ID トークンを取得する。
2. アプリは Worker の `POST /v1/chat/completions` に
   `Authorization: Bearer <Firebase ID トークン>` を付けてリクエストする。
3. Worker は ID トークンを Google の公開鍵 (JWKS) で検証し、
   `iss` / `aud` が Firebase プロジェクト `miyuki-neko-ai` のものであることを確認する。
4. 検証に成功したユーザーの uid ごとに、KV を使って簡易レート制限（既定: 20 req/分）をかける。
5. リクエストのモデル名を許可リスト (`ALLOWED_MODELS`) で検証し、
   `max_tokens` を上限 (`MAX_TOKENS_LIMIT`) にクランプしてから OpenAI の
   Chat Completions API を呼び出し、レスポンスをそのまま返す。

OpenAI の API キーはコードにも Git にも含まれず、Worker のシークレット
(`wrangler secret put OPENAI_API_KEY`) としてのみ保存されます。

## ファイル構成

- `src/index.ts` — リクエスト処理本体（認証・レート制限・OpenAI 呼び出し）
- `src/firebaseAuth.ts` — Firebase ID トークンの検証ロジック
- `wrangler.toml` — Worker 設定（非機密の環境変数、KV バインディング）
- `.dev.vars.example` — ローカル開発用シークレットのテンプレート（`.dev.vars` はコミットしない）

## セットアップ（ローカルで実行する手動手順）

```bash
cd cloudflare/openai-proxy
npm install
wrangler login                      # 初回のみ、ブラウザでCloudflareにログイン
wrangler kv namespace create RATE_LIMIT_KV
# 出力された id を wrangler.toml の [[kv_namespaces]] の id に貼り付ける

cp .dev.vars.example .dev.vars
# .dev.vars 内の OPENAI_API_KEY をローカルテスト用のキーに書き換える（コミットしない）

wrangler secret put OPENAI_API_KEY  # 本番用シークレットを対話入力で登録
wrangler secret put GEMINI_API_KEY  # マリたん用。MIYUxAIプロジェクトで発行したキーを入力
npm run deploy
```

**重要:** `GEMINI_API_KEY` を設定しない限り、`/v1/gemini/generate` は
Gemini側で認証エラーになります（マリたんの音声会話には必須）。

デプロイ方式が「Cloudflare Workers Builds（Git連携）」の場合、Cloudflare
ダッシュボード側の「プロダクションブランチ」設定がこのコードの存在する
ブランチ（例: `claude/step-3-uouuf6`）を向いているか確認してください。
向いていない場合は自動デプロイされないため、上記の `npm run deploy` を
手動で実行してください。

デプロイ後、以下で疎通確認できます。

```bash
curl https://poicat-openai-proxy.<あなたのサブドメイン>.workers.dev/health
# => {"status":"ok"}
```

## Android 側の呼び出し例（Kotlin）

```kotlin
val idToken = Firebase.auth.currentUser
    ?.getIdToken(false)
    ?.await()
    ?.token
    ?: error("not signed in")

val response = httpClient.post("https://poicat-openai-proxy.<あなたのサブドメイン>.workers.dev/v1/chat/completions") {
    header("Authorization", "Bearer $idToken")
    contentType(ContentType.Application.Json)
    setBody(
        mapOf(
            "messages" to listOf(mapOf("role" to "user", "content" to userInput)),
        )
    )
}
```

`model` は省略可能（省略時は `ALLOWED_MODELS` の先頭が使われる）。
OpenAI の API キーはアプリ側に一切含める必要はありません。

## マリたん用: Gemini中継エンドポイント (`POST /v1/gemini/generate`)

Android側（マリたん）はGeminiの`generateContent`とほぼ同じ形のリクエスト
ボディ（`system_instruction` / `contents`）を、Firebase IDトークン付きで
このエンドポイントへ送るだけです。モデル名はクライアントから指定できず、
`wrangler.toml`の`GEMINI_MODEL`（現在: `gemini-3.5-flash-lite`）で固定されて
います。レスポンスはGemini APIの応答をそのまま中継します（成功時のJSON形状も
429等のエラーもGemini側のものがそのまま返る）。

```kotlin
val idToken = FirebaseAuth.getInstance().currentUser
    ?.getIdToken(false)?.await()?.token
    ?: error("not signed in")

val response = httpClient.post("https://poicat-openai-proxy.<あなたのサブドメイン>.workers.dev/v1/gemini/generate") {
    header("Authorization", "Bearer $idToken")
    contentType(ContentType.Application.Json)
    setBody(mapOf("system_instruction" to ..., "contents" to ...))
}
```

Gemini APIキーは`wrangler secret put GEMINI_API_KEY`としてのみ保存され、
Androidアプリ側のコード・APK・GitHubリポジトリのいずれにも一切含まれません。

## 設定値（`wrangler.toml` の `[vars]`）

| 変数 | 用途 |
|---|---|
| `FIREBASE_PROJECT_ID` | ID トークン検証時の issuer/audience チェックに使用 |
| `ALLOWED_MODELS` | カンマ区切りの許可モデル一覧。コスト管理のため必ず絞る |
| `MAX_TOKENS_LIMIT` | 1リクエストあたりの `max_tokens` 上限 |
| `RATE_LIMIT_PER_MINUTE` | uid ごとの1分あたり許可リクエスト数 |

いずれも機密情報ではないため `wrangler.toml` に平文で記載しています。
`OPENAI_API_KEY` のみ Secret として別管理します。

## 既知の制約

- レート制限は KV ベースの簡易カウンタです。書き込みは結果整合性のため、
  高頻度リクエストでは多少の誤差があります。厳密なレート制限が必要な場合は
  Durable Objects（Workers Paid プラン）への移行を検討してください。
- `stream: true` を渡した場合はレスポンスをそのまま Server-Sent Events として
  クライアントに中継します。

## デプロイ方式

このディレクトリは Cloudflare Workers Builds（Git 連携）でデプロイされます。
Cloudflare ダッシュボード側の「プロダクション ブランチ」は、この Worker コードが
存在するブランチ（`claude/cloudflare-api-token-fp36lb`、将来的に `main` にマージ後は
`main`）に合わせて設定してください。
