package me.xiaozhangup.bot

import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.utils.info
import top.mrxiaom.overflow.BuildConstants
import top.mrxiaom.overflow.contact.RemoteGroup.Companion.asRemoteGroup

object PluginMain : KotlinPlugin(
    JvmPluginDescription(
        id = "me.xiaozhangup.bot",
        name = "xiaozhangup-bot",
        version = BuildConstants.VERSION
    ) {
        author("xiaozhangup")
    }
) {
    override fun onEnable() {
        logger.info { "Plugin loaded" }
        //配置文件目录 "${dataFolder.absolutePath}/"
        val eventChannel = GlobalEventChannel.parentScope(this)
        eventChannel.subscribeAlways<GroupMessageEvent> {
            val icon = "127874" // 表情ID
            val msgId = source.ids[0]
            group.asRemoteGroup.setMsgReaction(msgId, icon, true)
        }
    }
}