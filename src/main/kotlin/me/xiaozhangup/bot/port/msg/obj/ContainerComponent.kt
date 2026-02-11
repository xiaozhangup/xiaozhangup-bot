package me.xiaozhangup.bot.port.msg.obj

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.msg.MessageComponent

class ContainerComponent(
    title: String,
    val elements: List<Message>
) : MessageComponent(Type.CONTAINER, title) {
    override fun asString(): String {
        return elements.joinToString("\n") { it.getMessage() }
    }
}