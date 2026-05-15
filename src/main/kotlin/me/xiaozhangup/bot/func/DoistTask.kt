package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.doist.TodoistClient
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.properties
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class DoistTask : EventUnit(
    "todoist_task",
    "Todoist 快速添加工具",
    1
) {
    private val config by lazy { properties("doist_task") }
    private val targetGroups by lazy {
        config.getProperty("target.groups")?.split(',')?.map { it.trim() } ?: listOf()
    }
    private val targetUsers by lazy {
        config.getProperty("target.users")?.split(',')?.map { it.trim() } ?: listOf()
    }
    private val doistClient by lazy {
        val token = config.getProperty("doist.token")
        if (token.isNullOrBlank()) {
            throw IllegalStateException("Todoist API token is not set in task_abstract.properties")
        }
        TodoistClient(token)
    }

    override fun onGroupMessage(message: Message) {
        if (message.source.id !in targetGroups) return
        val text = message.getMessage().trim().split(' ', limit = 2)
        if (text.getOrNull(0) != "/task") return
        if (text.size != 2) {
            message.addReaction(Reaction.QUESTION)
            return
        }

        try {
            addTask(text[1], message.getSender())
            message.addReaction(Reaction.SPARK)
        } catch (e: Exception) {
            message.addReply("添加任务失败: ${e.message ?: "未知错误"}")
            message.addReaction(Reaction.QUESTION)
        }
    }

    override fun onFriendMessage(message: Message) {
        if (message.source.id !in targetUsers) return
        val text = message.getMessage().trim().split(' ', limit = 2)
        if (text.getOrNull(0) != "/task") return
        if (text.size != 2) {
            message.addReply("请提供任务内容")
            return
        }

        try {
            addTask(text[1], message.getSender())
            message.addReply("任务已添加!")
        } catch (e: Exception) {
            message.addReply("添加任务失败: ${e.message ?: "未知错误"}")
        }
    }

    private fun addTask(string: String, source: Source?) {
        val chunk = string.split("\n", limit = 2)
        val time = "${LocalDate.now()} ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}"
        doistClient.createTask(
            content = chunk[0],
            description = "${
                if (chunk.size > 1) {
                    chunk[1] + "\n\n"
                } else ""
            }来自: ${source?.name ?: "未知"}\n时间: $time",
            dueString = "today",
            dueLang = "en"
        )
    }
}