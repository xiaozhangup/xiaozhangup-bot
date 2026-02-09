package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.obj.FixedSizeMap

class TestUnit : EventUnit(
    "test",
    "test unit",
    1
) {
    private val history = FixedSizeMap<Int, Message>(30)

    override fun onGroupMessage(message: Message) {
        history[message.id] = message
    }

    override fun onMessageReaction(message: Message, reaction: Reaction, operation: Boolean) {

    }
}