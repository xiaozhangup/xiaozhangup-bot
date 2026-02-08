package me.xiaozhangup.bot

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

    private val overflowBot = OverflowBot()

    override fun onEnable() {
        overflowBot.onEnable()
    }

    override fun onDisable() {
        overflowBot.onDisable()
    }
}