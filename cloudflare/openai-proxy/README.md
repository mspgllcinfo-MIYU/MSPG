# poicat-openai-proxy

`com.mspg.poicat` Android アプリから OpenAI API を安全に呼び出すための
Cloudflare Workers 製サーバーサイドプロキシです。

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
npm run deploy
```

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
