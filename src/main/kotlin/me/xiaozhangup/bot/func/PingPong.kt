package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.properties

class PingPong : EventUnit(
    "ping_pong",
    "Ping-Pong 响应工具",
    1
) {
    private val config by lazy { properties("ping_pong") }

    private val enabledGroups by lazy {
        config.getProperty("enabled.groups")?.split(',')?.map { it.trim() } ?: listOf()
    }

    init {
        info("[PingPong] PingPong initialized. Enabled groups: $enabledGroups")
    }

    override fun onGroupMessage(message: Message) {
        if (!enabledGroups.contains(message.source.id)) return

        val content = message.getMessage().trim()
        if (content.equals("ping", ignoreCase = true)) {
            message.addReply("pong")
        }
    }

    override fun onFriendMessage(message: Message) {
        val content = message.getMessage().trim()
        if (content.equals("ping", ignoreCase = true)) {
            message.addReply("pong")
        }
    }
}

