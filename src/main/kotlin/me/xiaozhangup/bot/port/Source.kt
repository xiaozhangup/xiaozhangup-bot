package me.xiaozhangup.bot.port

abstract class Source(
    val name: String,
    val id: String
) {
    open fun sendMessage(message: String) {
        throw NotImplementedError()
    }
}