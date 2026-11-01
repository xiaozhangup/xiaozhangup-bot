package me.xiaozhangup.bot.person

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object PersonInputBuilder {
    fun build(
        lines: List<ChatLine>, botId: String, botName: String,
        target: PersonTarget, evaluatedSequence: Long, decisions: List<ChatDecision>
    ): List<PersonAIMessage> {
        val input = buildJsonObject {
            put("conversationType", if (target.type == me.xiaozhangup.bot.port.Message.Type.GROUP) "group" else "private")
            put("conversationId", target.id)
            put("selfId", botId)
            put("selfName", botName)
            put("currentTime", ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString())
            put("messages", buildJsonArray {
                lines.forEachIndexed { index, line -> add(buildJsonObject {
                    put("contextIndex", index + 1)
                    put("sequence", line.sequence)
                    put("senderId", line.senderId)
                    put("senderName", line.senderName)
                    put("isSelf", line.senderId == botId)
                    put("isNew", line.sequence > evaluatedSequence)
                    put("time", Instant.ofEpochMilli(line.ts).toString())
                    line.messageId?.let { put("messageId", it) }
                    put("text", line.text)
                    put("imageCount", line.images.size)
                    put("mentions", buildJsonArray { line.mentions.forEach { add(it) } })
                    put("quotes", buildJsonArray {
                        line.quotes.forEach { quote -> add(buildJsonObject {
                            quote.senderId?.let { put("senderId", it) }
                            put("messageIds", buildJsonArray { quote.sourceIds.forEach { add(it) } })
                            put("text", quote.context)
                        }) }
                    })
                }) }
            })
            put("decisions", buildJsonArray {
                decisions.forEach { decision -> add(buildJsonObject {
                    put("throughSequence", decision.throughSequence)
                    put("time", Instant.ofEpochMilli(decision.ts).toString())
                    put("output", decision.output)
                    put("status", decision.status)
                    put("sentParts", decision.sentParts)
                }) }
            })
        }
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", input.toString())
            })
            lines.forEachIndexed { lineIndex, line ->
                line.images.forEachIndexed { imageIndex, url ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "contextIndex=${lineIndex + 1} 消息中的[图片${imageIndex + 1}]")
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", url) })
                    })
                }
            }
        }
        return listOf(PersonAIMessage("user", content))
    }
}
