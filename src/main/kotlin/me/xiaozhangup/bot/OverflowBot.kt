package me.xiaozhangup.bot

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.func.AiMemory
import me.xiaozhangup.bot.func.CodeForcesContest
import me.xiaozhangup.bot.func.DoistTask
import me.xiaozhangup.bot.func.MailSummary
import me.xiaozhangup.bot.func.PingPong
import me.xiaozhangup.bot.func.TaskAbstract
import me.xiaozhangup.bot.func.WeatherReminder
import me.xiaozhangup.bot.person.PersonChat
import me.xiaozhangup.bot.ove.OverFriendMessage
import me.xiaozhangup.bot.ove.OverGroupMessage
import me.xiaozhangup.bot.port.Contact
import me.xiaozhangup.bot.port.LifeCycle
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.event.EventBus
import me.xiaozhangup.bot.util.ScheduledUtils
import me.xiaozhangup.bot.util.asMessage
import net.mamoe.mirai.Bot
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import top.mrxiaom.overflow.event.MessageReactionEvent
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8

class OverflowBot : LifeCycle {

    private val plugin = PluginMain
    private val logger = plugin.logger
    private val dataFolder = plugin.dataFolder
    private val contact by lazy { OverflowContact(Bot.instances[0]) }
    private val json = Json
    private var httpServer: HttpServer? = null

    override fun onEnable() {
        logger.info("[EventBus] Registering event listeners...")
        val eventChannel = GlobalEventChannel.parentScope(plugin)
        eventChannel.subscribeAlways<GroupMessageEvent> {
            EventBus.getTrigger().triggerGroupMessage(
                OverGroupMessage(
                    this.group,
                    this.sender,
                    this.source.ids.getOrNull(0) ?: -1,
                    this.source,
                    asMessage(this.message)
                )
            )
        }
        eventChannel.subscribeAlways<FriendMessageEvent> {
            EventBus.getTrigger().triggerFriendMessage(
                OverFriendMessage(
                    this.user,
                    this.source.ids.getOrNull(0) ?: -1,
                    this.source,
                    asMessage(this.message)
                )
            )
        }
        eventChannel.subscribeAlways<MessageReactionEvent> {
            EventBus.getTrigger().triggerMessageReaction(
                OverGroupMessage(
                    this.group,
                    this.operator,
                    this.messageId,
                    null,
//                    this.group.roamingMessages.getAllMessages {
//                        it.ids[0] == this.messageId
//                    }.firstOrNull()?.let {
//                        asMessage(it)
//                    } ?: emptyList() 不支持漫游
                    emptyList()
                ),
                asReaction(this.reaction),
                this.operation
            )
        }
        logger.info("[EventBus] Event listeners registered.")

        ScheduledUtils.start(40)
        logger.info("[Scheduler] Scheduler started.")

        EventBus.register(TaskAbstract())
        EventBus.register(WeatherReminder())
        EventBus.register(PingPong())
        EventBus.register(CodeForcesContest())
        EventBus.register(DoistTask())
        EventBus.register(MailSummary())
        EventBus.register(AiMemory())
        EventBus.register(PersonChat())

        httpServer = HttpServer.create(InetSocketAddress(48247), 0).apply {
            createContext("/send") { handleSend(it) }
            start()
        }
        logger.info("[HTTP] Listening on port 48247.")
    }

    override fun onDisable() {
        httpServer?.stop(0)
        ScheduledUtils.stop()
        logger.info("[EventBus] Goodbye!")
    }

    override fun getContact(): Contact {
        return contact
    }

    override fun getDataFolder(): File {
        return dataFolder
    }

    private fun asReaction(action: String): Reaction {
        return when (action) {
            "127874" -> Reaction.CAKE
            "66" -> Reaction.HEART
            "76" -> Reaction.LIKE
            "424" -> Reaction.BUTTON
            "10068" -> Reaction.QUESTION
            "124" -> Reaction.OK
            "10024" -> Reaction.SPARK
            else -> Reaction.QUESTION
        }
    }

    private fun handleSend(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            exchange.respond(405, "method must be POST")
            return
        }

        try {
            val body = exchange.requestBody.use { it.readNBytes(MAX_BODY_SIZE + 1) }
            if (body.size > MAX_BODY_SIZE) {
                exchange.respond(413, "request body is too large")
                return
            }

            val request = runCatching {
                json.decodeFromString(SendRequest.serializer(), String(body, UTF_8))
            }.getOrElse {
                exchange.respond(400, "invalid JSON")
                return
            }
            val group = request.group?.trim()?.takeIf(String::isNotEmpty)
            val user = request.user?.trim()?.takeIf(String::isNotEmpty)
            if ((group == null) == (user == null)) {
                exchange.respond(400, "provide exactly one of group or user")
                return
            }
            if (request.message.isBlank()) {
                exchange.respond(400, "message must not be blank")
                return
            }

            val id = group ?: user!!
            if (id.toLongOrNull()?.let { it > 0 } != true) {
                exchange.respond(400, "group or user must be a valid QQ number")
                return
            }

            val target = group?.let(contact::getGroup) ?: contact.getUser(user!!)
            if (target == null) {
                exchange.respond(404, "target not found")
                return
            }

            target.sendMessage(request.message)
            exchange.respond(202, "accepted")
        } catch (e: Exception) {
            logger.warning("[HTTP] Failed to send message: ${e.message}")
            exchange.respond(500, "failed to send message")
        }
    }

    private fun HttpExchange.respond(status: Int, text: String) {
        val body = text.toByteArray(UTF_8)
        responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    @Serializable
    private data class SendRequest(
        val group: String? = null,
        val user: String? = null,
        val message: String
    )

    private companion object {
        const val MAX_BODY_SIZE = 64 * 1024
    }
}
