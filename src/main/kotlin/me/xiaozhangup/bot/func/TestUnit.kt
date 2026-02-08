package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit

class TestUnit : EventUnit(
    "test",
    "test unit",
    1
) {
    override fun onGroupMessage(message: Message) {
        message.source.sendMessage("Hello World!")
        message.addReaction(Reaction.LIKE)
    }
}