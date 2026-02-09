package me.xiaozhangup.bot.util

import me.xiaozhangup.bot.PluginMain

fun info(message: String) {
    PluginMain.logger.info(message)
}

fun warning(message: String) {
    PluginMain.logger.warning(message)
}

fun error(message: String) {
    PluginMain.logger.error(message)
}