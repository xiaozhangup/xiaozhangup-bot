package me.xiaozhangup.bot.port

abstract class Source {
    open fun sendMessage(message: String) {
        throw NotImplementedError()
    }
}