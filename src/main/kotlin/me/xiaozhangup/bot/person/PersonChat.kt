package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.TaskUtils
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import me.xiaozhangup.bot.util.submitDelay
import me.xiaozhangup.bot.util.warning
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PersonChat : EventUnit(
    "person_chat",
    "模仿主人聊天风格",
    1
) {

    private val config by lazy { properties("person") }

    private val enabledGroups by lazy {
        config.getProperty("enabled.groups")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    private val enabledUsers by lazy {
        config.getProperty("enabled.users")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    private val botId by lazy {
        config.getProperty("bot.id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("bot.id is not set in person.properties")
    }

    private val botName by lazy {
        config.getProperty("bot.name")?.trim()?.takeIf { it.isNotEmpty() } ?: "小张张张张"
    }

    private val historySize by lazy {
        config.getProperty("history.size")?.toIntOrNull()?.takeIf { it > 0 } ?: 20
    }

    private val charDelayMs by lazy {
        config.getProperty("char.delay.ms")?.toLongOrNull()?.takeIf { it >= 0 } ?: 200L
    }

    private val debounceMs by lazy {
        (config.getProperty("debounce.seconds")?.toLongOrNull()?.takeIf { it >= 0 } ?: 10L) * 1000L
    }

    private val groupMaxBatch by lazy {
        config.getProperty("group.max.batch")?.toIntOrNull()?.takeIf { it > 0 } ?: 6
    }

    private val thinkingEnabled: Boolean? by lazy {
        config.getProperty("ai.thinking.enabled")?.trim()?.lowercase()?.let {
            when (it) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
    }

    private val context by lazy { PersonContextStore(historySize) }

    private val inflight = ConcurrentHashMap<String, Unit>()

    private val buffers = ConcurrentHashMap<String, Buffer>()

    private val aiClient by lazy {
        val apiKey = config.getProperty("ai.key")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ai.key is not set in person.properties")
        val apiUrl = config.getProperty("ai.url")?.takeIf { it.isNotBlank() }
            ?: "https://open.bigmodel.cn/api/paas/v4/"
        val apiModel = config.getProperty("ai.model")?.takeIf { it.isNotBlank() }
            ?: "glm-4.1v-thinking-flash"
        PersonAIClient(PersonPromptLoader.prompt(), apiKey, apiUrl, apiModel, thinkingEnabled)
    }

    override fun onGroupMessage(message: Message) {
        handle(message, enabledGroups, groupMaxBatch)
    }

    override fun onFriendMessage(message: Message) {
        handle(message, enabledUsers, 0)
    }

    private fun handle(message: Message, whitelist: Set<String>, maxBatch: Int) {
        val sourceId = message.source.id
        if (sourceId !in whitelist) return

        val sender = message.getSender() ?: return
        val senderId = sender.id
        if (senderId == botId) return

        val raw = PersonMessageExpander.expand(message)
        if (raw.isEmpty()) return
        if (message.getMessage().trimStart().startsWith("/")) return

        val line = ChatLine(
            senderId = senderId,
            senderName = sender.name,
            text = raw,
            ts = System.currentTimeMillis()
        )
        context.append(sourceId, line)

        val buffer = buffers.computeIfAbsent(sourceId) { Buffer() }
        var triggerFlush = false
        synchronized(buffer) {
            buffer.size++
            buffer.lastMessage = message
            buffer.timer?.cancel()
            buffer.timer = null

            if (maxBatch > 0 && buffer.size >= maxBatch) {
                triggerFlush = true
            } else {
                buffer.timer = submitDelay(debounceMs, TimeUnit.MILLISECONDS) {
                    flush(sourceId)
                }
            }
        }
        if (triggerFlush) flush(sourceId)
    }

    private fun flush(sourceId: String) {
        val buffer = buffers[sourceId] ?: return

        var replyTarget: Message? = null
        var rescheduled = false
        synchronized(buffer) {
            if (buffer.size == 0) return
            if (inflight.putIfAbsent(sourceId, Unit) != null) {
                buffer.timer?.cancel()
                buffer.timer = submitDelay(debounceMs, TimeUnit.MILLISECONDS) {
                    flush(sourceId)
                }
                rescheduled = true
            } else {
                replyTarget = buffer.lastMessage
                buffer.size = 0
                buffer.lastMessage = null
                buffer.timer?.cancel()
                buffer.timer = null
            }
        }
        if (rescheduled) return
        val target = replyTarget ?: run {
            inflight.remove(sourceId)
            return
        }

        submit {
            try {
                val all = context.snapshot(sourceId)
                val isGroup = target.type == Message.Type.GROUP
                val messages = PersonInputBuilder.build(all, botId, isGroup)
                val logRequest = messages.joinToString("\n") { "[${it.role}] ${it.content}" }
                info("[PersonChat][$sourceId] AI request:\n$logRequest")
                val resp = aiClient.ask(messages)
                val content = resp.content.trim()
                resp.usage?.let { u ->
                    val cached = u.promptTokensDetails?.cachedTokens
                    info(
                        "[PersonChat][$sourceId] tokens prompt=${u.promptTokens ?: "-"}" +
                                " completion=${u.completionTokens ?: "-"}" +
                                " total=${u.totalTokens ?: "-"}" +
                                " cached=${cached ?: "-"}"
                    )
                }
                info("[PersonChat][$sourceId] AI response:\n$content")
                val parts = PersonResponseParser.parse(content)
                if (parts.isNotEmpty()) {
                    PersonSender.send(target, parts, charDelayMs) { sentPart ->
                        context.append(
                            sourceId,
                            ChatLine(
                                senderId = botId,
                                senderName = botName,
                                text = sentPart,
                                ts = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                warning("[PersonChat] AI 调用失败: ${e.message}")
            } finally {
                inflight.remove(sourceId)
            }
        }
    }

    private class Buffer(
        var size: Int = 0,
        var lastMessage: Message? = null,
        var timer: TaskUtils.Task? = null
    )
}
