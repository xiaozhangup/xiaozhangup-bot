package me.xiaozhangup.bot.port

interface LifeCycle {

    fun onEnable() { }

    fun onDisable() { }

    fun getContact(): Contact
}