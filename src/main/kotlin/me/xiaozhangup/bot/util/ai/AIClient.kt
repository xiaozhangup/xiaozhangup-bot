package me.xiaozhangup.bot.util.ai

import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AIClient(
    private val systemPrompt: String,
    private val apiKey: String,
    private val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/",
    private val model: String = "glm-4.1v-thinking-flash"
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun ask(input: String): String {
        val request = ChatRequest(
            model = model,
            messages = listOf(
                Message("system", systemPrompt),
                Message("user", input)
            )
        )

        val body = json.encodeToString(ChatRequest.serializer(), request)

        val conn = URL("${baseUrl.trimEnd('/')}/chat/completions")
            .openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        conn.outputStream.use {
            it.write(body.toByteArray(StandardCharsets.UTF_8))
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }

        val response = json.decodeFromString(
            ChatResponse.serializer(),
            responseText
        )

        return response.choices.first().message.content
    }
}
