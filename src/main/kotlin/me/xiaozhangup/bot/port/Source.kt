package me.xiaozhangup.bot.port

import me.xiaozhangup.bot.port.msg.MessageComponent

abstract class Source(
    val name: String,
    val id: String
) {
    open fun sendMessage(message: String) {
        throw NotImplementedError()
    }

    open fun sendMessage(vararg messages: MessageComponent) {
        throw NotImplementedError()
    }
}