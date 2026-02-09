package me.xiaozhangup.bot.util

import me.xiaozhangup.bot.PluginMain
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.User
import java.io.File

fun getGroup(id: String): Group? {
    return PluginMain.overflowBot.getContact().getGroup(id)
}

fun getUser(id: String): User? {
    return PluginMain.overflowBot.getContact().getUser(id)
}

fun getDataFolder(): File {
    return PluginMain.overflowBot.getDataFolder()
}