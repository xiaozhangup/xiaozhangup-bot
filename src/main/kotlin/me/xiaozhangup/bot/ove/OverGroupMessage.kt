package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.AtAll
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.buildMessageChain
import top.mrxiaom.overflow.contact.RemoteGroup.Companion.asRemoteGroup

class OverGroupMessage(
    val group: Group,
    val msgId: Int,
    val msgSource: MessageSource?,
    val components: List<MessageComponent>
) : Message(
    OverGroup(group),
    Type.GROUP,
    msgId,
    components
) {
    override fun addReaction(reaction: Reaction, boolean: Boolean) {
        group.launch {
            val icon = when (reaction) {
                Reaction.CAKE -> "127874"
                Reaction.HEART -> "66"
                Reaction.LIKE -> "76"
                Reaction.BUTTON -> "424"
                Reaction.QUESTION -> "10068"
                Reaction.OK -> "124"
                Reaction.SPARK -> "10024"
            }
            group.asRemoteGroup.setMsgReaction(msgId, icon, boolean)
        }
    }

    override fun addReply(message: String) {
        group.launch {
            if (msgSource == null) {
                group.sendMessage(message)
                return@launch
            }
            group.sendMessage(
                QuoteReply(msgSource) + PlainText(message)
            )
        }
    }

    override fun addReply(vararg messages: MessageComponent) {
        group.launch {
            val message = buildMessageChain {
                messages.forEach { comp ->
                    when (comp) {
                        is StringComponent -> +PlainText(comp.context)
                        is AtComponent -> comp.context.toLongOrNull()
                            ?.let { +At(it) }
                            ?: +AtAll
                        is ImageComponent -> TODO()
                        else -> +PlainText(comp.context)
                    }
                }
            }
            if (msgSource == null) {
                group.sendMessage(message)
                return@launch
            }
            group.sendMessage(
                QuoteReply(msgSource) + message
            )
        }
    }
}