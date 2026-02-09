package me.xiaozhangup.bot.port

import java.io.File

interface LifeCycle {

    fun onEnable() { }

    fun onDisable() { }

    fun getContact(): Contact

    fun getDataFolder(): File
}