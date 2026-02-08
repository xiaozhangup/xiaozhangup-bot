package me.xiaozhangup.bot.port.msg

import me.xiaozhangup.bot.port.Message

abstract class MessageComponent(
    val type: Type,
    val context: String
) {
    enum class Type {
        STRING,
        IMAGE,
        AT
    }
}