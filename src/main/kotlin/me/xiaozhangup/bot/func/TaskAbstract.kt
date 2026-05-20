package me.xiaozhangup.bot.func

import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.client.ai.AIClient
import me.xiaozhangup.bot.client.doist.TodoistClient
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.*
import me.xiaozhangup.bot.util.obj.FixedSizeMap
import me.xiaozhangup.bot.util.obj.TagMessageStore
import me.xiaozhangup.bot.util.obj.TextSimilarityStore
import java.io.File
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class TaskAbstract : EventUnit(
    "task_abstract",
    "任务摘要工具",
    1
) {
    private val history = mutableMapOf<String, FixedSizeMap<Int, Message>>()
    private val config by lazy { properties("task_abstract") }
    private val tagStore by lazy { TagMessageStore(dataFolder("task_abstract")) }
    private val similarityStore by lazy { TextSimilarityStore(12, File(dataFolder("task_abstract"), "task_abstract.store")) }
    private val doistClient by lazy {
        val token = config.getProperty("doist.token")
        if (token.isNullOrBlank()) {
            throw IllegalStateException("Todoist API token is not set in task_abstract.properties")
        }
        TodoistClient(token)
    }
    private val notificationGroup by lazy {
        getGroup(config.getProperty("notification.group"))
            ?: throw IllegalStateException("Notification group not found: ${config.getProperty("notification.group")}")
    }
    private val snifferGroups by lazy {
        config.getProperty("sniffer.groups")?.split(',') ?: listOf()
    }
    private val snifferWords by lazy {
        config.getProperty("sniffer.words")?.split(',') ?: listOf()
    }
    private val doistSection by lazy {
        config.getProperty("doist.section")
    }
    private val sectionId by lazy {
        val pojs = doistClient.getProjects()
        doistClient.getSections().first { section ->
            val res = listOf(
                pojs.first { it.id == section.projectId }.name,
                section.name
            ).joinToString(".")
            res == doistSection
        }.id
    }

    // 上下文缓存与防抖任务管理
    private val contextBuffer = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableList<BufferedMessage>>>()
    private val debounceJobs = ConcurrentHashMap<String, ConcurrentHashMap<String, Job>>()
    private val maxContextAgeMs = 5 * 60 * 1000L // 上下文消息最长保留5分钟
    private val debounceDelayMs = 30000L // 防抖等待时间：30秒

    private val aiClient by lazy {
        val apiKey = config.getProperty("api.key")
        val apiModel = config.getProperty("api.model")
        val apiBaseUrl = config.getProperty("api.url")
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("AI API key is not set in ai.properties")
        }
        AIClient(
            """
            我将向你发送一条或多条合并后的QQ消息，该消息通常为学校发布的通知类内容。请你严格按照以下要求执行任务：
            
            ### 核心目标
            **100%精准匹配原文信息**，提取通知中的**任务主体**、**附件信息**、**相关时间**三类关键内容，并以指定JSON数组格式输出，确保无遗漏、无冗余、格式完全合规。
            任何时间字段的输出，必须经过明确的语义推理，禁止机械按消息内年份填充。
            
            ### 任务拆解与执行规则
            1. **信息提取范围**：**仅提取我发送的QQ通知消息原文中的显性信息**，禁止添加任何原文未提及的内容（包括推测、补充说明等）。
            2. **分类提取规则（精准约束）**：
               - **任务标题**：简短概括通知的核心任务内容，48字以内，禁止使用泛泛描述（如“通知”、“安排”等），必须具体指向任务主体；若消息本身不是通知类型，该项为""。
               - **任务主体**：仅提取通知中**明确要求执行的核心动作事项**，为纯文本（可保留原文换行逻辑，但需剔除无关铺垫/背景）；若通知无明确任务要求，该项为""。
               - **附件信息**：仅提取通知中**直接呈现的附加内容**（链接、号码、网站、工具名称等），以文本列表形式呈现，每条格式固定为「类型: 内容」（类型需严格匹配内容属性，如链接、号码、工具、网站等，禁止自定义模糊类型）；若无附件，该项为[]。
               - **相关时间**：仅提取通知中**明确标注的日期/时间段/相对时间词信息**，严格遵循以下格式规则；若无时间信息，该项为""。
               - **最早时间**：从“相关时间”中筛选出的最早日期，仅包含单一日期（YYYY.MM.DD格式）；若无时间信息，该项为""。
            
            ### 时间格式化规则（强制统一）
            - 现在的时间是: ${LocalDate.now()}
            - 单日时间：YYYY.MM.DD（如2026.02.03）
            - 时间段：YYYY.MM.DD-YYYY.MM.DD（如2026.02.03-2026.03.01）
            - 仅通知仅指定开始日（无结束日）则在末尾添加加号：YYYY.MM.DD+
            - 仅通知仅指定结束日（无开始日）则在末尾添加减号：YYYY.MM.DD-
            - 若时间无年份，需根据通知上下文（如当前学年/学期）默认填充对应年份（若无法判断则保留原文时间格式，但需转换为半角句号分割）
            - 若有多个时间信息，均需提取并按上述格式规范化后，以逗号分隔呈现（如有时间段与单日时间混合出现，取时间段为准）
            - 若为相对时间词或者没有提供例如年份/月份等足够的时间信息，必须根据“现在的时间”进行解析，并将其转化为明确的日期或日期范围
            
            ### 年级 / 届别 / 学年时间推理规则（强制执行）
            - 当通知中出现“XX级学生 / XX届学生 / 入学年份”等表述时：
              - “XX级”默认指 **XX年入学**，而非事件发生年份。
              - 若任务发生在入学后的某一阶段（如“第二年”“下一学期”“毕业前”“实习期间”等），
                必须以入学年份为基准进行年份推算。
            - 禁止将“XX级”中的年份数字直接当作任务发生年份，除非通知中明确说明。
            - 若同时出现“当前时间”与“级 / 届”信息，**以级 / 届语义优先于当前年份**。
            - 示例：
              - 当前时间：2025.08.15  
                文本：2025级学生需于次年5月完成体测  
                → 相关时间：2026.05.01
                
            ### 最早时间（earliest_time）精度规则（强制执行）
            - earliest_time 用于表示通知中**最早发生的具体时间点**，应在不违反原文信息的前提下尽量提高精度。
            - 若通知中明确给出了“具体时刻”（如“13:30”“下午2点”“18:00前”等）：
              - earliest_time 必须包含日期与时间，格式为：YYYY.MM.DD HH:mm
              - 示例：2026.12.03 13:30
            - 若通知中仅给出日期、时间段或相对日期，**未出现具体点钟信息**：
              - earliest_time 仅输出日期，格式为：YYYY.MM.DD
            - 禁止在原文未给出具体时刻的情况下，擅自补充或推测时间点（如默认08:00、00:00等）。
            - 若多个时间信息中，只有部分包含具体时刻：
              - earliest_time 应选择**时间上最早且精度最高的那一项**。
            
            ### 输出格式要求（严格合规）
            输出必须为**标准JSON数组格式**（无语法错误，键名/引号/逗号完全符合JSON规范）。
            即使合并的消息上下文中只有一个任务，也必须使用数组包裹。
            若合并的消息中包含**多个不同且独立的任务/通知**，请将其拆分为数组中的多个JSON对象。
            若消息本身不是通知类型，则返回空数组 `[]`。
            
            每个任务对象的键名固定为英文：
            {
              "task_title": "提取的任务标题（简短概括任务主体，48字以内）",
              "task_subject": "提取的任务主体纯文本（仅核心动作，无冗余）",
              "attachments": ["类型1: 内容1", "类型2: 内容2"],
              "related_time": "按规则格式化后的时间字符串；此处不包含时刻信息，仅能填写严格按规则格式化后的日期或日期范围",
              "earliest_time": "最早的一个时间；若原文包含具体时刻则为 YYYY.MM.DD HH:mm，否则为 YYYY.MM.DD"
            }
        """.trimIndent(), apiKey, apiBaseUrl, apiModel
        )
    }

    override fun onGroupMessage(message: Message) {
        val groupId = message.source.id
        if (!snifferGroups.contains(groupId)) return

        // 将消息写入原始历史记录
        history.getOrPut(groupId) { FixedSizeMap(32) }[message.id] = message

        val senderId = message.getSender()?.id ?: throw Exception(
            "Unable to fetch sender id from ${message.getMessage().take(32)} (${groupId})"
        )
        val raw = message.getMessage()

        // 1. 将当前消息存入该用户的上下文缓存
        val groupMap = contextBuffer.getOrPut(groupId) { ConcurrentHashMap() }
        val userMessages = groupMap.getOrPut(senderId) { mutableListOf() }
        synchronized(userMessages) {
            userMessages.add(BufferedMessage(message))
            // 剔除5分钟前的老消息
            val now = System.currentTimeMillis()
            userMessages.removeAll { now - it.timestamp > maxContextAgeMs }
        }

        // 2. 检测触发词
        val hasTrigger = raw.contains("@全体成员") || message.component.any {
            it is AtComponent && it.context == "all"
        } || snifferWords.any { raw.contains(it) }

        if (hasTrigger) {
            // 3. 触发/重置防抖定时器
            startDebounceTimer(groupId, senderId, message)
        }
    }

    override fun onMessageReaction(message: Message, reaction: Reaction, operation: Boolean) {
        if (!operation) return
        if (message.source.id != notificationGroup.id) return
        val msg = history[message.source.id]?.get(message.id) ?: return
        if (reaction == Reaction.BUTTON) {
            abstractTask(msg)
        }
    }

    private fun abstractTask(message: Message) {
        val source = message.source
        val raw = message.getMessage()
        if (source is Group && source.id == notificationGroup.id) {
            message.addReaction(Reaction.SPARK)
        }
        if (similarityStore.insert(raw) > 0.8) {
            message.replyOrSend("与历史消息高度相似，已忽略")
            return
        }
        submit {
            val tasks = fetchResults(raw)
            if (tasks.isNullOrEmpty()) {
                message.replyOrSend("该消息未包含任务信息，未创建任务")
                return@submit
            }

            tasks.forEach { task ->
                if (task.taskTitle.isNotBlank()) {
                    val success = createTodoistTaskWithRetry(task, message, raw)
                    sendSummaryMessage(task, message, success)
                }
            }
        }
    }

    private fun startDebounceTimer(groupId: String, senderId: String, triggerMessage: Message) {
        val userJobs = debounceJobs.getOrPut(groupId) { ConcurrentHashMap() }

        // 取消上一次的计时，重置防抖
        userJobs[senderId]?.cancel()

        userJobs[senderId] = CoroutineScope(Dispatchers.Default).launch {
            delay(debounceDelayMs.milliseconds)
            processMergedContext(groupId, senderId, triggerMessage)
            userJobs.remove(senderId)
        }
    }

    private fun processMergedContext(groupId: String, senderId: String, triggerMessage: Message) {
        val userMessages = contextBuffer[groupId]?.get(senderId) ?: return
        if (userMessages.isEmpty()) return

        val mergedRawText = synchronized(userMessages) {
            val sorted = userMessages.sortedBy { it.timestamp }
            val triggerIndex = sorted.indexOfFirst { it.message.id == triggerMessage.id }
            if (triggerIndex == -1) return

            val relevantMessages = mutableListOf<BufferedMessage>()
            relevantMessages.add(sorted[triggerIndex])

            var lastTimestamp = sorted[triggerIndex].timestamp
            for (i in (triggerIndex - 1) downTo 0) {
                val prevMsg = sorted[i]
                if (lastTimestamp - prevMsg.timestamp <= 60000L) {
                    relevantMessages.add(0, prevMsg)
                    lastTimestamp = prevMsg.timestamp
                } else {
                    break
                }
            }

            lastTimestamp = sorted[triggerIndex].timestamp
            for (i in (triggerIndex + 1) until sorted.size) {
                val nextMsg = sorted[i]
                if (nextMsg.timestamp - lastTimestamp <= 60000L) {
                    relevantMessages.add(nextMsg)
                    lastTimestamp = nextMsg.timestamp
                } else {
                    break
                }
            }

            val text = relevantMessages.joinToString("\n") { it.message.getMessage() }
            userMessages.clear()
            text
        }

        if (mergedRawText.isBlank()) return

        if (triggerMessage.source is Group && triggerMessage.source.id == notificationGroup.id) {
            triggerMessage.addReaction(Reaction.SPARK)
        }

        if (similarityStore.insert(mergedRawText) > 0.8) {
            triggerMessage.replyOrSend("与历史消息高度相似，已忽略")
            return
        }

        submit {
            val tasks = fetchResults(mergedRawText)
            if (tasks.isNullOrEmpty()) {
                triggerMessage.replyOrSend("无法解析消息上下文，未能提取到有效任务信息")
                return@submit
            }

            tasks.forEach { task ->
                if (task.taskTitle.isNotBlank()) {
                    val success = createTodoistTaskWithRetry(task, triggerMessage, mergedRawText)
                    sendSummaryMessage(task, triggerMessage, success)
                }
            }
        }
    }

    private fun createTodoistTaskWithRetry(task: TaskResult, triggerMessage: Message, rawContextText: String): Boolean {
        val source = triggerMessage.source
        var failed = 0
        var success = false
        while (failed < 5) {
            try {
                doistClient.createTask(
                    content = task.taskTitle,
                    description = buildString {
                        if (task.taskSubject.isNotBlank()) {
                            append(task.taskSubject)
                        }
                        if (task.attachments.isNotEmpty()) {
                            append("\n\n附件: ")
                            var index = 1
                            task.attachments.forEach { att ->
                                append("\n${index++}. $att")
                            }
                        }
                        if (task.relatedTime.isNotBlank()) {
                            append("\n\n时间: ${task.relatedTime}")
                        }
                        append("\n\n来自: ${source.name} (${source.id})\n原始消息上下文:\n$rawContextText")
                    },
                    sectionId = sectionId,
                    dueString = task.earliestTime.ifBlank { null }
                )

                success = true
                info("[TaskAbstract] Created task '${task.taskTitle}' in Todoist.")
                break
            } catch (_: SocketTimeoutException) {
                failed++
                warning("[TaskAbstract] Socket timeout when creating task '${task.taskTitle}', retrying... ($failed/5)")
            } catch (e: Exception) {
                triggerMessage.replyOrSend("添加 Todoist 任务时出错: ${e.message}")
                warning("[TaskAbstract] Failed to create task '${task.taskTitle}': ${e.message}")
                e.printStackTrace()
                break
            }
        }
        return success
    }

    private fun sendSummaryMessage(task: TaskResult, triggerMessage: Message, success: Boolean) {
        var index = 1
        val msg = buildString {
            append(task.taskTitle)
            if (task.relatedTime.isNotBlank()) {
                append("\n\n时间: \n${task.relatedTime}")
            }
            if (task.attachments.isNotEmpty()) {
                append("\n\n附件: ")
                task.attachments.forEach { att ->
                    append("\n${index++}. $att")
                }
            }
            if (task.taskSubject.isNotBlank()) {
                append("\n\n${task.taskSubject}")
            }
            append("\n")
            if (!success) {
                append("\n#无法插入Todoist")
            }
            append("\n#任务 #${triggerMessage.id}")
        }
        tagStore.insert(msg)
        triggerMessage.replyOrSend(msg)
    }

    private fun Message.replyOrSend(msg: String) {
        if (source is Group && source.id == notificationGroup.id) {
            addReply(msg)
        } else {
            notificationGroup.sendMessage(
                "$msg\n\n来自: ${source.name} (${source.id})\n原文:\n${getMessage().take(69)}"
            )
        }
    }

    private fun fetchResults(msg: String): List<TaskResult>? {
        repeat(3) {
            try {
                val result = aiClient.ask(msg)

                val start = result.indexOfFirst { it == '[' }
                val end = result.indexOfLast { it == ']' }
                if (start == -1 || end == -1 || end <= start) {
                    return@repeat
                }

                val json = result.substring(start, end + 1)
                return Json.decodeFromString<List<TaskResult>>(json)
            } catch (e: Exception) {
                e.printStackTrace()
                warning("[TaskAbstract] AI response parse failed. Msg: \n$msg")
            }
        }
        return null
    }

    @Serializable
    data class TaskResult(
        @SerialName("task_title")
        val taskTitle: String,
        @SerialName("task_subject")
        val taskSubject: String,
        val attachments: List<String>,
        @SerialName("related_time")
        val relatedTime: String,
        @SerialName("earliest_time")
        val earliestTime: String
    )

    private data class BufferedMessage(
        val message: Message,
        val timestamp: Long = System.currentTimeMillis()
    )
}