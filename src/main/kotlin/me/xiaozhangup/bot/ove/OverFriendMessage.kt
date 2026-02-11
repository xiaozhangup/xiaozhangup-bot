package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.message.data.*

class OverFriendMessage(
    val user: User,
    val msgId: Int,
    val msgSource: MessageSource,
    val components: List<MessageComponent>
) : Message(
    OverUser(user),
    Type.USER,
    msgId,
    components
) {
    override fun addReaction(reaction: Reaction, boolean: Boolean) {
        throw NotImplementedError("Friend message does not support reactions")
    }

    override fun addReply(message: String) {
        user.launch {
            user.sendMessage(
                QuoteReply(msgSource) + PlainText(message)
            )
        }
    }

    override fun addReply(vararg messages: MessageComponent) {
        user.launch {
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
            user.sendMessage(
                QuoteReply(msgSource) + message
            )
        }
    }

    override fun getSender(): Source {
        return source
    }
}