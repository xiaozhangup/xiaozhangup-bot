package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.msg.MessageComponent
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.message.data.MessageSource
import top.mrxiaom.overflow.contact.RemoteGroup.Companion.asRemoteGroup

class OveGroupMessage(
    val group: Group,
    val msgId: Int,
    val components: List<MessageComponent>
) : Message(
    OverGroup(group),
    Type.GROUP,
    msgId,
    components
) {
    override fun addReaction(reaction: Reaction) {
        group.launch {
            val icon = when (reaction) {
                Reaction.CAKE -> "127874"
                Reaction.HEART -> "66"
                Reaction.LIKE -> "76"
                Reaction.BUTTON -> "424"
                Reaction.QUESTION -> "10068"
                Reaction.OK -> "124"
            }
            group.asRemoteGroup.setMsgReaction(msgId, icon, true)
        }
    }
}