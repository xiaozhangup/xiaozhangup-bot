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

    open fun onMessageReaction(message: Message, reaction: Reaction) {}
}