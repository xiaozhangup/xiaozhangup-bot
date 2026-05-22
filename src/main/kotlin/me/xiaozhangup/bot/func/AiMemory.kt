package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.ai.AiMemoryStore
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import java.util.concurrent.ConcurrentHashMap

class AiMemory : EventUnit(
    "ai_memory",
    "AI 自定义要求管理",
    1
) {
    private val memoryAliasStore = ConcurrentHashMap<String, Map<Int, Int>>()
    private val config by lazy { properties("ai_memory") }
    private val targetGroups by lazy {
        config.getProperty("target.groups")?.split(',')?.map { it.trim() } ?: listOf()
    }
    private val targetUsers by lazy {
        config.getProperty("target.users")?.split(',')?.map { it.trim() } ?: listOf()
    }

    override fun onGroupMessage(message: Message) {
        if (message.source.id !in targetGroups) return
        handleMemoryCommand(message, isGroup = true)
    }

    override fun onFriendMessage(message: Message) {
        if (message.source.id !in targetUsers) return
        handleMemoryCommand(message, isGroup = false)
    }

    private fun handleMemoryCommand(message: Message, isGroup: Boolean) {
        val raw = message.getMessage().trim()
        val sourceId = message.source.id
        if (!raw.startsWith("/memory")) return

        val content = raw.removePrefix("/memory").trim()
        val command = content.substringBefore(' ').trim().lowercase()
        val arg = if (content.contains(' ')) content.substringAfter(' ').trim() else ""
        submit {
            try {
                when {
                    content.isBlank() || command in setOf("help", "h", "?") -> {
                        message.addReply(helpMessage())
                    }

                    command in setOf("list", "l", "ls") -> {
                        message.addReply(listMemories(sourceId))
                    }

                    command in setOf("add", "a") -> {
                        if (arg.isBlank()) {
                            message.addReply("参数错误：/memory add <内容>")
                        } else {
                            AiMemoryStore.add(arg)
                            if (isGroup) {
                                message.addReaction(Reaction.SPARK)
                            } else {
                                message.addReply("要求已添加!")
                            }
                        }
                    }

                    command in setOf("del", "d", "rm", "delete", "remove") -> {
                        val alias = arg.trimStart('#').toIntOrNull()
                        val memoryId = alias?.let { memoryAliasStore[sourceId]?.get(it) }
                        if (alias == null || memoryId == null) {
                            message.addReply("参数错误：/memory del <编号>")
                        } else {
                            val removed = AiMemoryStore.remove(memoryId)
                            if (removed != null) {
                                message.addReply("要求已删除")
                            } else {
                                message.addReply("删除失败，要求可能已不存在")
                            }
                        }
                    }

                    else -> {
                        AiMemoryStore.add(content)
                        if (isGroup) {
                            message.addReaction(Reaction.SPARK)
                        } else {
                            message.addReply("要求已添加!")
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = when {
                    command in setOf("list", "l", "ls") -> "列出要求失败"
                    command in setOf("del", "d", "rm", "delete", "remove") -> "删除要求失败"
                    else -> "添加要求失败"
                }
                message.addReply("$msg: ${e.message ?: "未知错误"}")
                if (isGroup) {
                    message.addReaction(Reaction.QUESTION)
                }
            }
        }
    }

    private fun listMemories(sourceId: String): String {
        val items = AiMemoryStore.list()
        if (items.isEmpty()) {
            memoryAliasStore[sourceId] = emptyMap()
            return "当前没有自定义要求"
        }
        memoryAliasStore[sourceId] = items.mapIndexed { index, item ->
            index + 1 to item.id
        }.toMap()
        return buildString {
            append("所有自定义要求:\n\n")
            items.forEachIndexed { index, item ->
                append("${index + 1}. ${item.content}\n")
            }
        }.trim()
    }

    private fun helpMessage(): String {
        return """
            1) /memory <内容>
               功能: 快速添加自定义要求

            2) /memory list
               功能: 列出所有自定义要求

            3) /memory del <编号>
               功能: 删除指定要求
        """.trimIndent()
    }
}
