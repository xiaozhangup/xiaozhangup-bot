package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.CodexUsage
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.ScheduledUtils
import me.xiaozhangup.bot.util.getGroup
import me.xiaozhangup.bot.util.submit
import kotlin.math.round

class CodexUsage: EventUnit(
    "codex_usage",
    "Codex 额度查询工具",
    1
) {

    override fun onFriendMessage(message: Message) {
        val sender = message.getSender() ?: return
        val context = message.getMessage()
        if (context != "/usage") return

        getGroup("1033959018")?.let { group ->
            if (group.isMember(sender.id)) {
                submit {
                    try {
                        val usage = CodexUsage.fetchCodexUsage()
                        val window = usage.rateLimit?.primaryWindow!!
                        val type = if (window.limitWindowSeconds!! < 60 * 60 * 6) "5小时" else "周"
                        message.addReply("Codex ${type}额度剩余 ${round(window.remainingPercent!! * 100).toInt()}%")
                    } catch (e: Exception) {
                        message.addReply("查询剩余额度状况失败: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onGroupMessage(message: Message) {
        val group = message.source.id
        if (group != "1033959018") return

        val context = message.getMessage()
        if (context != "/usage") return

        message.addReaction(Reaction.SPARK)
        submit {
            val usage = CodexUsage.fetchCodexUsage()
            val window = usage.rateLimit?.primaryWindow
            if (window == null) {
                message.addReply("查询剩余额度状况失败")
                return@submit
            }

            try {
                val type = if (window.limitWindowSeconds!! < 60 * 60 * 6) "5小时" else "周"
                message.addReply("Codex ${type}额度剩余 ${round(window.remainingPercent!! * 100).toInt()}%")
            } catch (e: Exception) {
                message.addReply("查询剩余额度状况失败: ${e.message}")
            }
        }
    }
}