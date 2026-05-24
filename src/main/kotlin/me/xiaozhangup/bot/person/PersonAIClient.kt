package me.xiaozhangup.bot.person

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.client.ai.ChatResponse
import me.xiaozhangup.bot.client.ai.Message
import me.xiaozhangup.bot.client.ai.Usage
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PersonAIClient(
    private val systemPrompt: String,
    private val apiKey: String,
    private val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/",
    private val model: String = "glm-4.1v-thinking-flash",
    private val thinking: Boolean? = null
) {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun ask(messages: List<Message>): AskResult {
        val finalMessages = mutableListOf(Message("system", systemPrompt))
        finalMessages.addAll(messages)

        val request = PersonChatRequest(
            model = model,
            messages = finalMessages,
            thinking = thinking?.let { ThinkingConfig(if (it) "enabled" else "disabled") }
        )

        val body = json.encodeToString(PersonChatRequest.serializer(), request)

        val conn = URL("${baseUrl.trimEnd('/')}/chat/completions")
            .openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        conn.outputStream.use {
            it.write(body.toByteArray(StandardCharsets.UTF_8))
        }

        val code = conn.responseCode
        val responseText = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "(no body)"
            throw RuntimeException("HTTP $code from ${conn.url}: $err")
        }

        val response = json.decodeFromString(
            ChatResponse.serializer(),
            responseText
        )

        return AskResult(response.choices.first().message.content, response.usage)
    }
}

data class AskResult(
    val content: String,
    val usage: Usage?
)

@Serializable
internal data class PersonChatRequest(
    val model: String,
    val temperature: Double = 0.3,
    val messages: List<Message>,
    val thinking: ThinkingConfig? = null
)

@Serializable
internal data class ThinkingConfig(
    val type: String
)
