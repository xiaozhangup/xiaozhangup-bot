package me.xiaozhangup.bot.client.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val temperature: Double = 0.3,
    val messages: List<Message>
)

@Serializable
data class Message(
    val role: String,
    val content: String = ""
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val message: Message
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null
)

@Serializable
data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null
)
