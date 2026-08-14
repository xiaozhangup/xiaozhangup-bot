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

    /**
     * 发送消息并返回发送成功后分配的消息 ID（用于后续识别引用回复）。
     * 默认实现走 [sendMessage] 且无法获得 ID，返回空列表；
     * 支持获取 ID 的实现（如 [me.xiaozhangup.bot.ove.OverGroup]）应覆写。
     */
    open fun sendMessageWithIds(message: String): List<Int> {
        sendMessage(message)
        return emptyList()
    }
}