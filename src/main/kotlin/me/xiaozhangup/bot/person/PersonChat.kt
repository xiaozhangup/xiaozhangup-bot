package me.xiaozhangup.bot.person

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.warning

class PersonChat : EventUnit("person_chat", "模仿主人聊天风格", 2) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessions: Map<PersonTarget, PersonSession> = emptyMap()
    private var botId = ""

    fun start() {
        try {
            val config = properties("person")
            val profiles = PersonProfileLoader.load(config)
            if (profiles.isEmpty()) {
                info("[PersonChat] 未启用会话，请配置 person/profiles/*.properties")
                return
            }
            botId = config.getProperty("bot.id", "").trim()
            require(botId.toLongOrNull()?.let { it > 0 } == true) { "person.properties 的 bot.id 必须为当前登录的 QQ 号" }
            val apiKey = config.getProperty("ai.key", "").trim()
            require(apiKey.isNotEmpty()) { "person.properties 未配置 ai.key" }
            val botName = config.getProperty("bot.name", "小张张张张")
            val thinking = config.getProperty("ai.thinking.enabled")?.trim()?.takeIf { it.isNotEmpty() }
                ?.toBooleanStrict()
            sessions = profiles.associate { profile ->
                val client = PersonAIClient(
                    PersonPromptLoader.prompt(profile.style), apiKey,
                    config.getProperty("ai.url", "https://api.deepseek.com"),
                    config.getProperty("ai.model", "deepseek-flash"), thinking
                )
                profile.target to PersonSession(profile, botId, botName, client, scope)
            }
            info("[PersonChat] 已启动 ${sessions.size} 组会话")
        } catch (e: Exception) {
            warning("[PersonChat] 启动失败：${e.message}")
        }
    }

    override fun onGroupMessage(message: Message) = handle(message)
    override fun onFriendMessage(message: Message) = handle(message)

    private fun handle(message: Message) {
        val session = sessions[PersonTarget(message.type, message.source.id)] ?: return
        val sender = message.getSender() ?: return
        if (sender.id == botId || message.getMessage().trimStart().startsWith("/")) return
        session.accept(message, sender)
    }

    fun close() {
        sessions.values.forEach { it.close() }
        scope.cancel()
    }
}
