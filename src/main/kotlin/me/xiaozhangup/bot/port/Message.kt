package me.xiaozhangup.bot.port

import me.xiaozhangup.bot.port.msg.MessageComponent

abstract class Message(
    val source: Source,
    val type: Type,
    val id: Int,
    val component: List<MessageComponent>
) {

    open fun addReaction(reaction: Reaction, boolean: Boolean = true) {
        throw NotImplementedError()
    }

    open fun addReply(message: String) {
        throw NotImplementedError()
    }

    open fun addReplay(vararg messages: MessageComponent) {
        throw NotImplementedError()
    }

    open fun getMessage(): String {
        return component.joinToString("") { it.context }
    }

    enum class Type {
        GROUP,
        USER
    }
}