package me.xiaozhangup.bot.port.event

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction

class EventTrigger {

    fun triggerGroupMessage(message: Message) {
        EventBus.getEvents().forEach { it.onGroupMessage(message) }
    }

    fun triggerFriendMessage(message: Message) {
        EventBus.getEvents().forEach { it.onFriendMessage(message) }
    }

    fun triggerMessageReaction(message: Message, reaction: Reaction) {
        EventBus.getEvents().forEach { it.onMessageReaction(message, reaction) }
    }

}