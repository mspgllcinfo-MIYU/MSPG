import { verifyFirebaseIdToken } from "./firebaseAuth";

export interface Env {
  OPENAI_API_KEY: string;
  FIREBASE_PROJECT_ID: string;
  ALLOWED_MODELS: string;
  MAX_TOKENS_LIMIT: string;
  RATE_LIMIT_PER_MINUTE: string;
  RATE_LIMIT_KV: KVNamespace;
  // マリたん(/v1/gemini/generate)用。GEMINI_API_KEYはコードにもGitにも含まれず、
  // Workerのシークレット(wrangler secret put GEMINI_API_KEY)としてのみ保存される
  // — Android側はこのWorkerを経由するだけで、Gemini APIキー自体を一切持たない。
  GEMINI_API_KEY: string;
  GEMINI_MODEL: string;
  GEMINI_RATE_LIMIT_PER_MINUTE: string;
  // 家族の家庭内Wi-Fi等、同一IPから複数端末で使うケースを想定してuid制限より
  // 緩めの値にしてある(uid制限が主、IP制限は乱用の量産的パターンを防ぐ補助)。
  GEMINI_IP_RATE_LIMIT_PER_MINUTE: string;
}

const OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
const GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

interface ChatRequestBody {
  model?: string;
  messages?: unknown;
  temperature?: number;
  top_p?: number;
  max_tokens?: number;
  presence_penalty?: number;
  frequency_penalty?: number;
  stop?: unknown;
  stream?: boolean;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

// keyPrefixでOpenAI用("rl")とGemini用("gl")のカウンタを分離する — マリたんの
// 利用量が黒猫AI側(将来OpenAI proxyを使う場合)の枠を食い潰さないようにするため。
async function checkRateLimit(
  env: Env,
  uid: string,
  keyPrefix: string,
  limit: number,
): Promise<boolean> {
  const windowStart = Math.floor(Date.now() / 60_000);
  const key = `${keyPrefix}:${uid}:${windowStart}`;

  const current = Number.parseInt((await env.RATE_LIMIT_KV.get(key)) ?? "0", 10);
  if (current >= limit) {
    return false;
  }

  await env.RATE_LIMIT_KV.put(key, String(current + 1), { expirationTtl: 90 });
  return true;
}

interface GeminiRequestBody {
  system_instruction?: unknown;
  contents?: unknown;
}

/**
 * マリたん専用のGemini中継。クライアント(Android)が組み立てたsystem_instruction/
 * contentsをほぼそのまま検証だけしてGeminiへ転送し、レスポンスをそのまま返す
 * だけの薄いプロキシ。キャラクター設定・Memory注入等のプロンプト構築ロジックは
 * 引き続きAndroidアプリ側(GeminiSearchService.kt)に置いたまま — Cloudflare側の
 * 再デプロイ無しでマリたんの応答調整ができるようにするため。
 * toolsフィールドは受け付けない(Google Search Grounding封じ — 過去のA/Bテストで
 * 429の直接原因と確定済み。クライアント側も送らない設計だが、念のためサーバー側
 * でも許可しない)。
 */
async function handleGeminiGenerate(request: Request, env: Env): Promise<Response> {
  // IPベースのレート制限を認証より前にかける — Firebase匿名認証は誰でも新しい
  // uidを取得できてしまう(公開のFirebase Web APIキーでsignInAnonymouslyを直接
  // 叩けば、POIアプリを一切経由せずIDトークンを取得できる)ため、uidだけの制限
  // では「新しいuidを量産する」形の乱用を防げない。同一IPからの量産的な乱用に
  // 対する多層防御の1つとして、IP単位の上限も別枠で設ける(uid制限を回避しても
  // ここで止まる)。これも万能ではない(IP自体を変える相手には効かない)ため、
  // Firebase App Check/Play Integrity等のより強い対策は別途検証中。
  const clientIp = request.headers.get("cf-connecting-ip") ?? "unknown";
  const ipLimit = Number.parseInt(env.GEMINI_IP_RATE_LIMIT_PER_MINUTE, 10) || 30;
  if (!(await checkRateLimit(env, clientIp, "ip-gl", ipLimit))) {
    return jsonResponse({ error: "rate limit exceeded, try again later" }, 429);
  }

  const authHeader = request.headers.get("authorization") ?? "";
  const match = authHeader.match(/^Bearer (.+)$/);
  if (!match) {
    return jsonResponse({ error: "missing Authorization: Bearer <Firebase ID token>" }, 401);
  }

  let uid: string;
  try {
    uid = await verifyFirebaseIdToken(match[1], env.FIREBASE_PROJECT_ID);
  } catch {
    return jsonResponse({ error: "invalid or expired ID token" }, 401);
  }

  const limit = Number.parseInt(env.GEMINI_RATE_LIMIT_PER_MINUTE, 10) || 15;
  if (!(await checkRateLimit(env, uid, "gl", limit))) {
    return jsonResponse({ error: "rate limit exceeded, try again later" }, 429);
  }

  let input: GeminiRequestBody;
  try {
    input = await request.json();
  } catch {
    return jsonResponse({ error: "request body must be valid JSON" }, 400);
  }

  if (!Array.isArray(input.contents) || input.contents.length === 0) {
    return jsonResponse({ error: "contents is required and must be a non-empty array" }, 400);
  }

  const upstreamBody: Record<string, unknown> = { contents: input.contents };
  if (input.system_instruction !== undefined) {
    upstreamBody.system_instruction = input.system_instruction;
  }

  const url = `${GEMINI_API_BASE}/${env.GEMINI_MODEL}:generateContent?key=${encodeURIComponent(env.GEMINI_API_KEY)}`;
  const upstreamResponse = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(upstreamBody),
  });

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    headers: {
      "content-type": upstreamResponse.headers.get("content-type") ?? "application/json",
    },
  });
}

function buildUpstreamBody(
  input: ChatRequestBody,
  env: Env,
): { body: Record<string, unknown> } | { error: string } {
  if (!Array.isArray(input.messages) || input.messages.length === 0) {
    return { error: "messages is required and must be a non-empty array" };
  }

  const allowedModels = env.ALLOWED_MODELS.split(",")
    .map((m) => m.trim())
    .filter(Boolean);
  const model =
    input.model && allowedModels.includes(input.model) ? input.model : allowedModels[0];
  if (!model) {
    return { error: "no allowed model is configured on the server" };
  }

  const maxTokensLimit = Number.parseInt(env.MAX_TOKENS_LIMIT, 10) || 1024;
  const maxTokens =
    typeof input.max_tokens === "number"
      ? Math.min(input.max_tokens, maxTokensLimit)
      : maxTokensLimit;

  const body: Record<string, unknown> = {
    model,
    messages: input.messages,
    max_tokens: maxTokens,
    n: 1,
  };

  if (typeof input.temperature === "number") body.temperature = input.temperature;
  if (typeof input.top_p === "number") body.top_p = input.top_p;
  if (typeof input.presence_penalty === "number") body.presence_penalty = input.presence_penalty;
  if (typeof input.frequency_penalty === "number") body.frequency_penalty = input.frequency_penalty;
  if (input.stop !== undefined) body.stop = input.stop;
  if (input.stream === true) body.stream = true;

  return { body };
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return jsonResponse({ status: "ok" });
    }

    if (request.method === "POST" && url.pathname === "/v1/gemini/generate") {
      return handleGeminiGenerate(request, env);
    }

    if (request.method !== "POST" || url.pathname !== "/v1/chat/completions") {
      return jsonResponse({ error: "not found" }, 404);
    }

    const authHeader = request.headers.get("authorization") ?? "";
    const match = authHeader.match(/^Bearer (.+)$/);
    if (!match) {
      return jsonResponse({ error: "missing Authorization: Bearer <Firebase ID token>" }, 401);
    }

    let uid: string;
    try {
      uid = await verifyFirebaseIdToken(match[1], env.FIREBASE_PROJECT_ID);
    } catch {
      return jsonResponse({ error: "invalid or expired ID token" }, 401);
    }

    const openAiLimit = Number.parseInt(env.RATE_LIMIT_PER_MINUTE, 10) || 20;
    if (!(await checkRateLimit(env, uid, "rl", openAiLimit))) {
      return jsonResponse({ error: "rate limit exceeded, try again later" }, 429);
    }

    let input: ChatRequestBody;
    try {
      input = await request.json();
    } catch {
      return jsonResponse({ error: "request body must be valid JSON" }, 400);
    }

    const result = buildUpstreamBody(input, env);
    if ("error" in result) {
      return jsonResponse({ error: result.error }, 400);
    }

    const upstreamResponse = await fetch(OPENAI_CHAT_URL, {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.OPENAI_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify(result.body),
    });

    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      headers: {
        "content-type": upstreamResponse.headers.get("content-type") ?? "application/json",
      },
    });
  },
} satisfies ExportedHandler<Env>;
