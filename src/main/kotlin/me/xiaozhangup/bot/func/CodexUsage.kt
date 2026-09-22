package me.xiaozhangup.bot.func

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.xiaozhangup.bot.client.CodexUsage
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.ScheduledUtils
import me.xiaozhangup.bot.util.getGroup
import me.xiaozhangup.bot.util.submit
import net.mamoe.mirai.utils.currentTimeSeconds
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.round
import kotlin.time.Duration.Companion.minutes

class CodexUsage: EventUnit(
    "codex_usage",
    "Codex 额度查询工具",
    1
) {

    init {
        ScheduledUtils.getScheduler().launch {
            while (true) {
                try {
                    val usage = CodexUsage.fetchCodexUsage()
                    val window = usage.rateLimit?.primaryWindow!!
                    val reset = window.resetAt!! - window.limitWindowSeconds!!
                    val curr = currentTimeSeconds()

                    if ((curr - reset) < 60 * 6) {
                        getGroup("1033959018")?.sendMessage("Codex 额度已刷新")
                    }
                } finally { }
                delay(5.minutes)
            }
        }
    }

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
                message.addReply(
                    """
                    Codex ${type}额度剩余 ${round(window.remainingPercent!! * 100).toInt()}%
                    下次刷新于 ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(window.resetAt!! * 1000))}
                    """.trimIndent()
                )
            } catch (e: Exception) {
                message.addReply("查询剩余额度状况失败: ${e.message}")
            }
        }
    }
}