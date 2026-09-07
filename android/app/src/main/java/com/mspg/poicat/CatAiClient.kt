package com.mspg.poicat

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the poicat-openai-proxy Cloudflare Worker, which forwards
 * requests to OpenAI after verifying a Firebase ID token. See
 * cloudflare/openai-proxy/ in this repository for the server side.
 */
object CatAiClient {
    private const val WORKER_URL = "https://poicat-openai-proxy.mspgllc-info.workers.dev/v1/chat/completions"

    suspend fun sendMessage(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val idToken = AuthRepository.idToken()

        val messagesJson = JSONArray()
        history.forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.text)
            messagesJson.put(obj)
        }
        val body = JSONObject().put("messages", messagesJson).toString()

        val connection = URL(WORKER_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
            } ?: ""

            if (responseCode !in 200..299) {
                throw Exception("サーバーエラー（$responseCode）：$responseText")
            }

            val responseJson = JSONObject(responseText)
            responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            connection.disconnect()
        }
    }
}
