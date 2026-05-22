package me.xiaozhangup.bot.client.mail

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class MailboxConfig(
    val id: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val label: String,
    val ssl: Boolean
)

data class FetchedMail(
    val index: Int,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Long
)

@Serializable
data class MailSummaryItem(
    val index: Int,
    @SerialName("is_useful") val isUseful: Boolean,
    val summary: String = ""
)
