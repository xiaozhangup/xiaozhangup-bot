package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.util.asMessageChain

class OverGroup(
    val oveGroup: net.mamoe.mirai.contact.Group
) : Group(
    oveGroup.name,
    oveGroup.id.toString()
) {
    override fun sendMessage(message: String) {
        oveGroup.launch {
            oveGroup.sendMessage(message)
        }
    }

    override fun sendMessage(vararg messages: MessageComponent) {
        oveGroup.launch {
            oveGroup.sendMessage(asMessageChain(*messages))
        }
    }
}