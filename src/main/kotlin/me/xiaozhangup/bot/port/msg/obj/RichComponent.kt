package me.xiaozhangup.bot.port.msg.obj

import me.xiaozhangup.bot.port.msg.MessageComponent

class RichComponent(
    content: String
) : MessageComponent(Type.RICH, content) {
    override fun asString(): String {
        return "<$context>"
    }
}