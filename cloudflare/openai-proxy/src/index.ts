import { verifyFirebaseIdToken } from "./firebaseAuth";

export interface Env {
  OPENAI_API_KEY: string;
  FIREBASE_PROJECT_ID: string;
  ALLOWED_MODELS: string;
  MAX_TOKENS_LIMIT: string;
  RATE_LIMIT_PER_MINUTE: string;
  RATE_LIMIT_KV: KVNamespace;
}

const OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

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

async function checkRateLimit(env: Env, uid: string): Promise<boolean> {
  const limit = Number.parseInt(env.RATE_LIMIT_PER_MINUTE, 10) || 20;
  const windowStart = Math.floor(Date.now() / 60_000);
  const key = `rl:${uid}:${windowStart}`;

  const current = Number.parseInt((await env.RATE_LIMIT_KV.get(key)) ?? "0", 10);
  if (current >= limit) {
    return false;
  }

  await env.RATE_LIMIT_KV.put(key, String(current + 1), { expirationTtl: 90 });
  return true;
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

    if (!(await checkRateLimit(env, uid))) {
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
