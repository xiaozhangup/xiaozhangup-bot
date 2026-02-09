package me.xiaozhangup.bot.port

interface Contact {

    fun getUser(id: String): User?

    fun getGroup(id: String): Group?

}