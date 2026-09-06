package com.mspg.poicat

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 猫AIサーバー（Firebase Cloud Functions想定）への問い合わせ。呼び出し側でIOスレッドから呼ぶこと。 */
object CatAiClient {
    class NotConfiguredException : Exception("猫AIのサーバーURLが未設定です")

    fun sendMessage(history: List<ChatMessage>): String {
        val endpointUrl = BuildConfig.CAT_AI_ENDPOINT
        if (endpointUrl.isBlank()) throw NotConfiguredException()

        val messagesJson = JSONArray()
        history.forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.text)
            messagesJson.put(obj)
        }
        val body = JSONObject().put("messages", messagesJson).toString()

        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw Exception("サーバーエラー（$responseCode）：$responseText")
            }

            return JSONObject(responseText).getString("reply")
        } finally {
            connection.disconnect()
        }
    }
}
