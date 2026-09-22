package me.xiaozhangup.bot.port.unit

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction

abstract class EventUnit(
    val id: String,
    val description: String,
    val version: Int
) {

    open fun onGroupMessage(message: Message) {}

    open fun onFriendMessage(message: Message) {}

    /** 设置向导等会话可消费消息，避免同时触发聊天或其他功能。 */
    open fun interceptFriendMessage(message: Message): Boolean = false

    open fun onMessageReaction(message: Message, reaction: Reaction, operation: Boolean) {}
}
