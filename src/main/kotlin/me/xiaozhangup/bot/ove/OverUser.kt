package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.User
import net.mamoe.mirai.contact.nameCardOrNick

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
}