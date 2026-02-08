package me.xiaozhangup.bot

import kotlinx.coroutines.flow.firstOrNull
import me.xiaozhangup.bot.func.TestUnit
import me.xiaozhangup.bot.ove.OveFriendMessage
import me.xiaozhangup.bot.ove.OveGroupMessage
import me.xiaozhangup.bot.port.LifeCycle
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.event.EventBus
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import top.mrxiaom.overflow.event.MessageReactionEvent

class OverflowBot : LifeCycle {

    private val plugin = PluginMain
    private val logger = plugin.logger

    override fun onEnable() {
        logger.info("[EventBus] Registering event listeners...")
        val eventChannel = GlobalEventChannel.parentScope(plugin)
        eventChannel.subscribeAlways<GroupMessageEvent> {
            EventBus.getTrigger().triggerGroupMessage(
                OveGroupMessage(
                    this.group,
                    this.source.ids.getOrNull(0) ?: -1,
                    asMessage(this.message)
                )
            )
        }
        eventChannel.subscribeAlways<FriendMessageEvent> {
            EventBus.getTrigger().triggerFriendMessage(
                OveFriendMessage(
                    this.user,
                    this.source.ids.getOrNull(0) ?: -1,
                    asMessage(this.message)
                )
            )
        }
        eventChannel.subscribeAlways<MessageReactionEvent> {
            EventBus.getTrigger().triggerMessageReaction(
                OveGroupMessage(
                    this.group,
                    this.messageId,
//                    this.group.roamingMessages.getAllMessages {
//                        it.ids[0] == this.messageId
//                    }.firstOrNull()?.let {
//                        asMessage(it)
//                    } ?: emptyList() 不支持漫游
                    emptyList()
                ),
                asReaction(this.reaction)
            )
        }
        logger.info("[EventBus] Event listeners registered.")

        EventBus.register(TestUnit())
    }

    override fun onDisable() {
        logger.info("[EventBus] Goodbye!")
    }

    private suspend fun asMessage(chain: MessageChain): List<MessageComponent> {
        return chain.mapNotNull { msg ->
            when(msg) {
                is Image -> {
                    ImageComponent(msg.queryUrl())
                }

                is PlainText -> {
                    StringComponent(msg.content)
                }

                is At -> {
                    AtComponent(msg.target.toString())
                }

                is AtAll -> {
                    AtComponent("all")
                }

                else -> {
                    val content = msg.content
                    if (content.isEmpty()) null
                    else StringComponent(msg.content)
                }
            }
        }
    }

    private fun asReaction(action: String): Reaction {
        return when(action) {
            "127874" -> Reaction.CAKE
            "66" -> Reaction.HEART
            "76" -> Reaction.LIKE
            "424" -> Reaction.BUTTON
            "10068" -> Reaction.QUESTION
            "124" -> Reaction.OK
            else -> Reaction.QUESTION
        }
    }
}