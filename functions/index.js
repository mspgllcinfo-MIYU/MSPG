const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

// firebase functions:secrets:set OPENAI_API_KEY で設定する（アプリ側にはキーを一切置かない）。
const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");

const CAT_SYSTEM_PROMPT = `あなたは「POI 猫AIアプリ」に住む猫のキャラクターです。
見た目は猫ですが、中身は賢く自然な受け答えをするアシスタントです。
基本は自然な頻度で語尾に「ニャ」をつけますが、不自然にならない程度にとどめてください。
資料調査や文章作成などの仕事寄りの相談でも、知性や正確さを落とさずに答えてください。
簡潔で親しみやすい口調を保ってください。`;

exports.catAiChat = onRequest(
  { secrets: [OPENAI_API_KEY], region: "asia-northeast1", cors: true },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "POSTのみ対応しています" });
      return;
    }

    const messages = req.body && req.body.messages;
    if (!Array.isArray(messages)) {
      res.status(400).json({ error: "messagesが不正です" });
      return;
    }

    try {
      const openAiResponse = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${OPENAI_API_KEY.value()}`,
        },
        body: JSON.stringify({
          model: "gpt-4o-mini",
          messages: [{ role: "system", content: CAT_SYSTEM_PROMPT }, ...messages],
        }),
      });

      const data = await openAiResponse.json();
      if (!openAiResponse.ok) {
        const message = (data && data.error && data.error.message) || "OpenAI APIエラー";
        res.status(502).json({ error: message });
        return;
      }

      const reply = (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || "";
      res.status(200).json({ reply });
    } catch (err) {
      res.status(500).json({ error: String(err) });
    }
  },
);
