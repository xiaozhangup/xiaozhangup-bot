package me.xiaozhangup.bot.client.ai

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
    val content: String
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: Message
)
