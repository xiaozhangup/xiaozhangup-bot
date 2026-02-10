package me.xiaozhangup.bot.util

import me.xiaozhangup.bot.PluginMain
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties

fun properties(name: String): Properties {
    val file = File(getDataFolder(), "$name.properties")
    if (!file.exists()) { file.createNewFile() }
    return Properties().apply {
        Files.newBufferedReader(
            file.toPath(),
            StandardCharsets.UTF_8
        ).use { reader -> load(reader) }
    }
}

fun dataFolder(name: String): File {
    val dir = File(getDataFolder(), name)
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

fun getDataFolder(): File {
    return PluginMain.overflowBot.getDataFolder()
}