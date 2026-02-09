package me.xiaozhangup.bot

import me.xiaozhangup.bot.ove.OverGroup
import me.xiaozhangup.bot.ove.OverUser
import me.xiaozhangup.bot.port.Contact
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.User
import net.mamoe.mirai.Bot

class OverflowContact(
    val bot: Bot
) : Contact {

    override fun getGroup(id: String): Group? {
        return bot.getGroup(id.toLong())?.let {
            OverGroup(it)
        }
    }

    override fun getUser(id: String): User? {
        return bot.getFriend(id.toLong())?.let {
            OverUser(it)
        }
    }

}