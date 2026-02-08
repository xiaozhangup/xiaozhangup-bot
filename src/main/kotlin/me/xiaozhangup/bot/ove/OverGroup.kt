package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.Group

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
}