package me.xiaozhangup.bot.ove

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.msg.MessageComponent
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.message.data.MessageSource

class OveFriendMessage(
    val user: User,
    val msgId: Int,
    val components: List<MessageComponent>
) : Message(
    OverUser(user),
    Message.Type.USER,
    msgId,
    components
) {
    override fun addReaction(reaction: Reaction) {
        throw NotImplementedError("Friend message does not support reactions")
    }
}