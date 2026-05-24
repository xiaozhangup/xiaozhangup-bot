package me.xiaozhangup.bot.port

abstract class Group(
    name: String,
    id: String
) : Source(name, id) {

    open fun getMemberName(memberId: String): String? = null
}
