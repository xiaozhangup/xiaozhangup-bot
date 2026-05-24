package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.doist.SectionBindingStore
import me.xiaozhangup.bot.client.doist.TodoistClient
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.dataFolder
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class DoistTask : EventUnit(
    "todoist_task",
    "Todoist 快速添加工具",
    1
) {
    private val sectionAliasStore = ConcurrentHashMap<String, Map<Int, String>>()
    private val taskAliasStore = ConcurrentHashMap<String, Map<Int, String>>()
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
    private val bindingStore by lazy { SectionBindingStore(dataFolder("doist_task")) }

    override fun onGroupMessage(message: Message) {
        if (message.source.id !in targetGroups) return
        handleTaskCommand(message, isGroup = true)
    }

    override fun onFriendMessage(message: Message) {
        if (message.source.id !in targetUsers) return
        handleTaskCommand(message, isGroup = false)
    }

    private fun handleTaskCommand(message: Message, isGroup: Boolean) {
        val raw = message.getMessage().trim()
        val sourceId = message.source.id
        if (!raw.startsWith("/task")) {
            if (raw == "/inbox") {
                message.addReply(listSectionTasks(sourceId, bindingStore.get(sourceId)))
            }
            return
        }

        val content = raw.removePrefix("/task").trim()
        val command = content.substringBefore(' ').trim().lowercase()
        val arg = if (content.contains(' ')) content.substringAfter(' ').trim() else ""
        submit {
            try {
                when {
                    content.isBlank() || command in setOf("help", "h", "?") -> {
                        message.addReply(helpMessage())
                    }

                    command in setOf("sections", "s") -> {
                        message.addReply(listSections(sourceId))
                    }

                    command in setOf("tasks", "t") -> {
                        val sectionAlias = arg.toIntOrNull()
                        val sectionId = sectionAlias?.let { sectionAliasStore[sourceId]?.get(it) }
                        message.addReply(listSectionTasks(sourceId, sectionId))
                    }

                    command in setOf("close", "c") -> {
                        val taskAlias = arg.toIntOrNull()
                        val taskId = taskAlias?.let { taskAliasStore[sourceId]?.get(it) }
                        if (taskAlias == null || taskId == null) {
                            message.addReply("参数错误：/task close <任务编号>")
                        } else {
                            val closed = doistClient.closeTask(taskId)
                            if (closed) {
                                message.addReply("任务已关闭")
                            } else {
                                message.addReply("关闭失败，任务可能不存在或无权限")
                            }
                        }
                    }

                    command in setOf("bind", "b") -> {
                        val sectionAlias = arg.toIntOrNull()
                        val sectionId = sectionAlias?.let { sectionAliasStore[sourceId]?.get(it) }
                        if (sectionAlias == null || sectionId == null) {
                            message.addReply("参数错误：/task bind <板块编号> (先用 /task sections 查看板块)")
                        } else {
                            bindingStore.set(sourceId, sectionId)
                            val sectionName = runCatching { doistClient.getSection(sectionId).name }.getOrNull()
                            message.addReply("已绑定到板块: ${sectionName ?: sectionId}")
                        }
                    }

                    command in setOf("unbind", "u") -> {
                        if (bindingStore.remove(sourceId)) {
                            message.addReply("已解除当前绑定")
                        } else {
                            message.addReply("当前没有绑定的板块")
                        }
                    }

                    command in setOf("add", "a") -> {
                        addTask(arg, message.getSender(), bindingStore.get(sourceId))
                        if (isGroup) {
                            message.addReaction(Reaction.SPARK)
                        } else {
                            message.addReply("任务已添加!")
                        }
                    }

                    else -> {
                        addTask(content, message.getSender(), bindingStore.get(sourceId))
                        if (isGroup) {
                            message.addReaction(Reaction.SPARK)
                        } else {
                            message.addReply("任务已添加!")
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = when {
                    command in setOf("sections", "s") -> "列出板块失败"
                    command in setOf("tasks", "t") -> "获取板块任务失败"
                    command in setOf("delete", "d") -> "删除任务失败"
                    command in setOf("bind", "b") -> "绑定板块失败"
                    else -> "添加任务失败"
                }
                message.addReply("$msg: ${e.message ?: "未知错误"}")
                if (isGroup) {
                    message.addReaction(Reaction.QUESTION)
                }
            }
        }
    }

    private fun addTask(string: String, source: Source?, sectionId: String?) {
        if (string.isBlank()) {
            throw IllegalArgumentException("请提供任务内容")
        }
        val chunk = string.split("\n", limit = 2)
        val time = "${LocalDate.now()} ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}"
        doistClient.createTask(
            content = chunk[0],
            sectionId = sectionId,
            description = "${
                if (chunk.size > 1) {
                    chunk[1] + "\n\n"
                } else ""
            }来自: ${source?.name ?: "未知"}\n时间: $time",
            dueString = "today",
            dueLang = "en"
        )
    }

    private fun listSections(sourceId: String): String {
        val projects = doistClient.getProjects().associateBy { it.id }
        val sections = doistClient.getSections()
        if (sections.isEmpty()) return "当前没有板块"
        sectionAliasStore[sourceId] = sections.mapIndexed { index, section ->
            index + 1 to section.id
        }.toMap()
        taskAliasStore.remove(sourceId)

        return buildString {
            append("所有板块:\n\n")
            sections.forEachIndexed { index, section ->
                val projectName = projects[section.projectId]?.name ?: "未知项目"
                append("${index + 1}. $projectName.${section.name}\n")
            }
        }.trim()
    }

    private fun listSectionTasks(sourceId: String, sectionId: String?): String {
        val section = sectionId?.let { doistClient.getSection(it) }
        val tasks = doistClient.getTasks(sectionId = sectionId).filter {
            it.sectionId == sectionId
        }
        val name = section?.name ?: "收件箱"
        if (tasks.isEmpty()) {
            taskAliasStore[sourceId] = emptyMap()
            return "\"$name\" 中的内容:\n\n空板块"
        }

        val maxShow = 16
        val shown = tasks.take(maxShow)
        taskAliasStore[sourceId] = shown.mapIndexed { index, task ->
            index + 1 to task.id
        }.toMap()
        return buildString {
            append("\"$name\" 中的内容:\n\n")
            shown.forEachIndexed { index, task ->
                val due = task.due?.string ?: task.due?.date
                append("${index + 1}. ${task.content}")
                if (!due.isNullOrBlank()) {
                    append(" (截止: $due)")
                }
                append('\n')
            }
            if (tasks.size > maxShow) {
                append("\n剩余 ${tasks.size - maxShow} 个任务")
            }
        }.trim()
    }

    private fun helpMessage(): String {
        return """
            1) /task <任务内容>
               功能: 快速添加任务（按当前群绑定的板块）

            2) /task sections
               功能: 列出所有板块

            3) /task tasks <板块编号>
               功能: 列出某板块的任务清单

            4) /task close <任务编号>
               功能: 关闭指定任务

            5) /task bind <板块编号>
               功能: 将当前会话绑定到指定板块

            6) /task unbind
               功能: 解除当前会话的板块绑定
        """.trimIndent()
    }
}
