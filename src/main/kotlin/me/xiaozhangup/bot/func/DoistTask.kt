package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.doist.TodoistClient
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.unit.EventUnit
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
        if (!raw.startsWith("/task")) return

        val content = raw.removePrefix("/task").trim()
        val command = content.substringBefore(' ').trim().lowercase()
        val arg = if (content.contains(' ')) content.substringAfter(' ').trim() else ""
        val sourceId = message.source.id
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
                        if (sectionAlias == null || sectionId == null) {
                            message.addReply("用法错误：/task tasks <板块编号>（先用 /task sections 获取编号）")
                        } else {
                            message.addReply(listSectionTasks(sourceId, sectionAlias, sectionId))
                        }
                    }

                    command in setOf("delete", "d") -> {
                        val taskAlias = arg.toIntOrNull()
                        val taskId = taskAlias?.let { taskAliasStore[sourceId]?.get(it) }
                        if (taskAlias == null || taskId == null) {
                            message.addReply("用法错误：/task delete <任务编号>（先用 /task tasks <板块编号> 获取编号）")
                        } else {
                            val deleted = doistClient.deleteTask(taskId)
                            if (deleted) {
                                message.addReply("任务已删除（编号: $taskAlias）")
                            } else {
                                message.addReply("删除失败，任务可能不存在或无权限（编号: $taskAlias）")
                            }
                        }
                    }

                    command in setOf("add", "a") -> {
                        addTask(arg, message.getSender())
                        if (isGroup) {
                            message.addReaction(Reaction.SPARK)
                        } else {
                            message.addReply("任务已添加!")
                        }
                    }

                    else -> {
                        addTask(content, message.getSender())
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
                    else -> "添加任务失败"
                }
                message.addReply("$msg: ${e.message ?: "未知错误"}")
                if (isGroup) {
                    message.addReaction(Reaction.QUESTION)
                }
            }
        }
    }

    private fun addTask(string: String, source: Source?) {
        if (string.isBlank()) {
            throw IllegalArgumentException("请提供任务内容")
        }
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

    private fun listSections(sourceId: String): String {
        val projects = doistClient.getProjects().associateBy { it.id }
        val sections = doistClient.getSections()
        if (sections.isEmpty()) return "当前没有板块"
        sectionAliasStore[sourceId] = sections.mapIndexed { index, section ->
            index + 1 to section.id
        }.toMap()
        taskAliasStore.remove(sourceId)

        return buildString {
            append("所有板块（共 ${sections.size} 个）:\n")
            sections.forEachIndexed { index, section ->
                val projectName = projects[section.projectId]?.name ?: "未知项目"
                append("${index + 1}. $projectName.${section.name}\n")
                append("   板块编号: ${index + 1}\n")
            }
            append("\n使用 /task tasks <板块编号>（或 /task t <板块编号>）查看该板块任务")
        }.trim()
    }

    private fun listSectionTasks(sourceId: String, sectionAlias: Int, sectionId: String): String {
        val section = doistClient.getSection(sectionId)
        val tasks = doistClient.getTasks(sectionId = sectionId)
        if (tasks.isEmpty()) {
            taskAliasStore[sourceId] = emptyMap()
            return "板块「${section.name}」（编号: $sectionAlias）当前没有任务"
        }

        val maxShow = 30
        val shown = tasks.take(maxShow)
        taskAliasStore[sourceId] = shown.mapIndexed { index, task ->
            index + 1 to task.id
        }.toMap()
        return buildString {
            append("板块「${section.name}」（编号: $sectionAlias）任务清单（共 ${tasks.size} 个）:\n")
            shown.forEachIndexed { index, task ->
                val due = task.due?.string ?: task.due?.date
                append("${index + 1}. ${task.content}\n")
                append("   任务编号: ${index + 1}")
                if (!due.isNullOrBlank()) {
                    append(" | 截止: $due")
                }
                append('\n')
            }
            if (tasks.size > maxShow) {
                append("\n仅展示前 $maxShow 个任务")
            }
            append("\n使用 /task delete <任务编号>（或 /task d <任务编号>）删除任务")
        }.trim()
    }

    private fun helpMessage(): String {
        return """
            Doist 命令帮助
            
            1) /task <任务内容>
               功能: 快速添加任务（兼容旧用法）
            
            2) /task add <任务内容>
               功能: 添加任务
               缩写: /task a <任务内容>
             
            3) /task sections
               功能: 列出所有板块（自动分配板块编号）
               缩写: /task s
             
            4) /task tasks <板块编号>
               功能: 列出某个板块的任务清单（自动分配任务编号）
               缩写: /task t <板块编号>
             
            5) /task delete <任务编号>
               功能: 删除指定任务
               缩写: /task d <任务编号>
             
            6) /task help
               功能: 显示本帮助
               缩写: /task h
        """.trimIndent()
    }
}
