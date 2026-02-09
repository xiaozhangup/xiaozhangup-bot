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
        message.source.sendMessage(
            message.component.joinToString("\n") {
                "${it.type}: ${it.context}"
            }
        )
        message.addReaction(Reaction.LIKE)
        message.addReply("我真的服气了")
    }
}