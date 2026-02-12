package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.User
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.util.asMessageChain

class OverUser(
    val oveUser: net.mamoe.mirai.contact.User
) : User(
    oveUser.nick,
    oveUser.id.toString()
) {
    override fun sendMessage(message: String) {
        oveUser.launch {
            oveUser.sendMessage(message)
        }
    }

    override fun sendMessage(vararg messages: MessageComponent) {
        oveUser.launch {
            oveUser.sendMessage(asMessageChain(*messages))
        }
    }
}