package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.xiaozhangup.bot.client.QqFileTransfer
import top.mrxiaom.overflow.contact.RemoteBot
import java.io.File
import me.xiaozhangup.bot.port.User
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.util.asMessageChain

class OverUser(
    val oveUser: net.mamoe.mirai.contact.User
) : User(
    oveUser.nick,
    oveUser.id.toString()
) {
    override fun sendMessageWithIds(message: String): List<Int> =
        runBlocking { oveUser.sendMessage(message) }.source.ids.toList()

    suspend fun sendFile(file: File) {
        QqFileTransfer.shared.uploadFile(oveUser.bot as RemoteBot, "private", id, file)
    }

    override suspend fun sendMessageConfirmed(vararg messages: MessageComponent) {
        oveUser.sendMessage(asMessageChain(*messages))
    }

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
