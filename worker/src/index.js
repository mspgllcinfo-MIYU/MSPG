const CAT_SYSTEM_PROMPT = `あなたは「POI 猫AIアプリ」に住む猫のキャラクターです。
見た目は猫ですが、中身は賢く自然な受け答えをするアシスタントです。
基本は自然な頻度で語尾に「ニャ」をつけますが、不自然にならない程度にとどめてください。
資料調査や文章作成などの仕事寄りの相談でも、知性や正確さを落とさずに答えてください。
簡潔で親しみやすい口調を保ってください。`;

function jsonResponse(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return jsonResponse({ error: "POSTのみ対応しています" }, 405);
    }

    let body;
    try {
      body = await request.json();
    } catch (err) {
      return jsonResponse({ error: "リクエストの形式が不正です" }, 400);
    }

    const messages = body && body.messages;
    if (!Array.isArray(messages)) {
      return jsonResponse({ error: "messagesが不正です" }, 400);
    }

    if (!env.OPENAI_API_KEY) {
      return jsonResponse({ error: "OPENAI_API_KEYが未設定です" }, 500);
    }

    try {
      const openAiResponse = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${env.OPENAI_API_KEY}`,
        },
        body: JSON.stringify({
          model: "gpt-4o-mini",
          messages: [{ role: "system", content: CAT_SYSTEM_PROMPT }, ...messages],
        }),
      });

      const data = await openAiResponse.json();
      if (!openAiResponse.ok) {
        const message = (data && data.error && data.error.message) || "OpenAI APIエラー";
        return jsonResponse({ error: message }, 502);
      }

      const reply =
        (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || "";
      return jsonResponse({ reply }, 200);
    } catch (err) {
      return jsonResponse({ error: String(err) }, 500);
    }
  },
};
