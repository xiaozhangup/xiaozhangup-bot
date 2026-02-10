package me.xiaozhangup.bot.func

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.PluginMain
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.ai.AIClient
import me.xiaozhangup.bot.util.doist.TodoistClient
import me.xiaozhangup.bot.util.getDataFolder
import me.xiaozhangup.bot.util.obj.FixedSizeMap
import me.xiaozhangup.bot.util.submit
import me.xiaozhangup.bot.util.warning
import java.io.File
import java.nio.file.Files
import java.util.*


class TaskAbstract : EventUnit(
    "task_abstract",
    "任务摘要工具",
    1
) {
    private val history = FixedSizeMap<Int, Message>(30)
    private val configFile by lazy { File(getDataFolder(), "task_abstract.properties") }
    private val config by lazy {
        val props = Properties()
        if (!configFile.exists()) {
            configFile.createNewFile()
        }
        Files.newInputStream(configFile.toPath()).use {
            props.load(it)
        }
        props
    }
    private val doistClient by lazy {
        val token = config.getProperty("doist.token")
        if (token.isNullOrBlank()) {
            throw IllegalStateException("Todoist API token is not set in task_abstract.properties")
        }
        TodoistClient(token)
    }
    private val aiClient by lazy {
        val apiKey = config.getProperty("api.key")
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("AI API key is not set in ai.properties")
        }
        AIClient("""
            我将向你发送一条QQ消息，该消息通常为学校发布的通知类内容。请你严格按照以下要求执行任务：
            
            ### 核心目标
            **100%精准匹配原文信息**，提取通知中的**任务主体**、**附件信息**、**相关时间**三类关键内容，并以指定JSON格式输出，确保无遗漏、无冗余、格式完全合规。
            
            ### 任务拆解与执行规则
            1. **信息提取范围**：**仅提取我发送的QQ通知消息原文中的显性信息**，禁止添加任何原文未提及的内容（包括推测、补充说明等）。
            2. **分类提取规则（精准约束）**：
               - **任务标题**：简短概括通知的核心任务内容，48字以内，禁止使用泛泛描述（如“通知”、“安排”等），必须具体指向任务主体；若消息本身不是通知类型，该项为""。
               - **任务主体**：仅提取通知中**明确要求执行的核心动作事项**，为纯文本（可保留原文换行逻辑，但需剔除无关铺垫/背景）；若通知无明确任务要求，该项为""。
               - **附件信息**：仅提取通知中**直接呈现的附加内容**（链接、号码、网站、工具名称等），以文本列表形式呈现，每条格式固定为「类型: 内容」（类型需严格匹配内容属性，如链接、号码、工具、网站等，禁止自定义模糊类型）；若无附件，该项为[]。
               - **相关时间**：仅提取通知中**明确标注的日期/时间段/相对时间词信息**，严格遵循以下格式规则；若无时间信息，该项为""。
            
            ### 时间格式化规则（强制统一）
            - 现在的时间是: ${java.time.LocalDate.now()}
            - 单日时间：YYYY.MM.DD（如2026.02.03）
            - 时间段：YYYY.MM.DD-YYYY.MM.DD（如2026.02.03-2026.03.01）
            - 仅指定开始日（无结束日）：YYYY.MM.DD+
            - 仅指定结束日（无开始日）：YYYY.MM.DD-
            - 若时间无年份，需根据通知上下文（如当前学年/学期）默认填充对应年份（若无法判断则保留原文时间格式，但需转换为半角句号分割）
            - 若有多个时间信息，均需提取并按上述格式规范化后，以逗号分隔呈现（如有时间段与单日时间混合出现，取时间段为准）
            - 若为相对时间词，必须根据“现在的时间”进行解析，并将其转化为明确的日期或日期范围
            
            ### 输出格式要求（严格合规）
            输出必须为**标准JSON格式**（无语法错误，键名/引号/逗号完全符合JSON规范），键名固定为英文：
            {
              "task_title": "提取的任务标题（简短概括任务主体，48字以内）",
              "task_subject": "提取的任务主体纯文本（仅核心动作，无冗余）",
              "attachments": ["类型1: 内容1", "类型2: 内容2"],
              "related_time": "按规则格式化后的时间字符串"
            }
            若某类别无对应信息，对应值严格填充：任务主体为""、附件为[]、相关时间为""。
            若消息本身不是通知类型，则所有字段均填充为空值。
        """.trimIndent(), apiKey)
    }

    override fun onGroupMessage(message: Message) {
        history[message.id] = message
        if (message.getMessage().contains("@全体成员") || message.component.any {
                it is AtComponent && it.context == "all"
            }
        ) {
            abstractTask(message)
        }

        doistClient.getSections().forEach {
            warning("Section: ${it.name} (ID: ${it.id})")
        }
    }

    override fun onMessageReaction(message: Message, reaction: Reaction, operation: Boolean) {
        val msg = history[message.id] ?: return
        if (reaction == Reaction.BUTTON) {
            abstractTask(msg)
        }
    }

    private fun abstractTask(message: Message) {
        message.addReaction(Reaction.SPARK)
        submit {
            val result = aiClient.ask(message.getMessage())
            val start = result.indexOfFirst { c -> c == '{' }
            val end = result.indexOfLast { c -> c == '}' }
            if (start != -1 && end != -1 && end > start) {
                val json = result.substring(start, end + 1)
                try {
                    val task = Json.decodeFromString<TaskResult>(json)
                    message.addReply(
                        "任务标题: ${
                            if (task.taskTitle.isBlank()) "无" else "\n" + task.taskTitle
                        }\n\n" +
                        "相关时间: ${
                            if (task.relatedTime.isBlank()) "无" else "\n" + task.relatedTime
                        }\n\n" +
                        "附件信息: ${
                            if (task.attachments.isEmpty()) "无" else "\n - " + task.attachments.joinToString(
                                "\n - "
                            )
                        }\n\n" +
                        "任务内容: ${
                            if (task.taskSubject.isBlank()) "无" else "\n" + task.taskSubject
                        }\n\n" +
                        "#${message.id} #任务"
                    )
                } catch (e: Exception) {
                    message.addReply("解析AI返回的JSON时出错: ${e.message}")
                    warning("Failed to parse task abstract JSON: $json")
                    return@submit
                }
            } else {
                message.addReply("无法从返回的数据中找到有效的JSON内容")
                warning("No valid JSON found in AI response: $result")
            }
        }
    }

    @Serializable
    data class TaskResult(
        @SerialName("task_title")
        val taskTitle: String,
        @SerialName("task_subject")
        val taskSubject: String,
        val attachments: List<String>,
        @SerialName("related_time")
        val relatedTime: String
    )
}