package me.xiaozhangup.bot.port.msg

abstract class MessageComponent(
    val type: Type,
    val context: String
) {
    enum class Type {
        STRING,
        IMAGE,
        AT,
        RICH,
        CONTAINER,
        QUOTE
    }

    open fun asString(): String {
        return context
    }
}