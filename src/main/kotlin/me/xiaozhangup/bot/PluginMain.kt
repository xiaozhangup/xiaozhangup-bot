package me.xiaozhangup.bot

import me.xiaozhangup.bot.port.LifeCycle
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import top.mrxiaom.overflow.BuildConstants

object PluginMain : KotlinPlugin(
    JvmPluginDescription(
        id = "me.xiaozhangup.bot",
        name = "xiaozhangup-bot",
        version = BuildConstants.VERSION
    ) {
        author("xiaozhangup")
    }
) {

    val overflowBot: LifeCycle = OverflowBot()

    override fun onEnable() {
        overflowBot.onEnable()
    }

    override fun onDisable() {
        overflowBot.onDisable()
    }
}