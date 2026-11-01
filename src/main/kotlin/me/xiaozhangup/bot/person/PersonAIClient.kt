package me.xiaozhangup.bot.person

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.xiaozhangup.bot.client.ai.ChatResponse
import me.xiaozhangup.bot.client.ai.Usage
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PersonAIClient(
    private val systemPrompt: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-flash",
    private val thinking: Boolean? = null
) {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun ask(messages: List<PersonAIMessage>): AskResult {
        val finalMessages = mutableListOf(PersonAIMessage("system", JsonPrimitive(systemPrompt)))
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
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // 服务商错误正文可能回显请求或鉴权信息，不写入日志。
                throw RuntimeException("AI 接口返回 HTTP $code")
            }
            val responseText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val response = json.decodeFromString(ChatResponse.serializer(), responseText)
            return AskResult(response.choices.first().message.content, response.usage)
        } finally {
            conn.disconnect()
        }
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
    val messages: List<PersonAIMessage>,
    val thinking: ThinkingConfig? = null
)

@Serializable
internal data class ThinkingConfig(
    val type: String
)

@Serializable
data class PersonAIMessage(val role: String, val content: JsonElement)
