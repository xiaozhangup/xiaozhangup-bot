package me.xiaozhangup.bot

import me.xiaozhangup.bot.func.PingPong
import me.xiaozhangup.bot.func.TaskAbstract
import me.xiaozhangup.bot.func.WeatherReminder
import me.xiaozhangup.bot.ove.OverFriendMessage
import me.xiaozhangup.bot.ove.OverGroupMessage
import me.xiaozhangup.bot.port.Contact
import me.xiaozhangup.bot.port.LifeCycle
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.event.EventBus
import me.xiaozhangup.bot.util.ScheduledUtils
import me.xiaozhangup.bot.util.asMessage
import net.mamoe.mirai.Bot
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import top.mrxiaom.overflow.event.MessageReactionEvent
import java.io.File

class OverflowBot : LifeCycle {

    private val plugin = PluginMain
    private val logger = plugin.logger
    private val dataFolder = plugin.dataFolder
    private val contact by lazy { OverflowContact(Bot.instances[0]) }

    override fun onEnable() {
        logger.info("[EventBus] Registering event listeners...")
        val eventChannel = GlobalEventChannel.parentScope(plugin)
        eventChannel.subscribeAlways<GroupMessageEvent> {
            EventBus.getTrigger().triggerGroupMessage(
                OverGroupMessage(
                    this.group,
                    this.sender,
                    this.source.ids.getOrNull(0) ?: -1,
                    this.source,
                    asMessage(this.message)
                )
            )
        }
        eventChannel.subscribeAlways<FriendMessageEvent> {
            EventBus.getTrigger().triggerFriendMessage(
                OverFriendMessage(
                    this.user,
                    this.source.ids.getOrNull(0) ?: -1,
                    this.source,
                    asMessage(this.message)
                )
            )
        }
        eventChannel.subscribeAlways<MessageReactionEvent> {
            EventBus.getTrigger().triggerMessageReaction(
                OverGroupMessage(
                    this.group,
                    this.operator,
                    this.messageId,
                    null,
//                    this.group.roamingMessages.getAllMessages {
//                        it.ids[0] == this.messageId
//                    }.firstOrNull()?.let {
//                        asMessage(it)
//                    } ?: emptyList() 不支持漫游
                    emptyList()
                ),
                asReaction(this.reaction),
                this.operation
            )
        }
        logger.info("[EventBus] Event listeners registered.")

        ScheduledUtils.start(40)
        logger.info("[Scheduler] Scheduler started.")

        EventBus.register(TaskAbstract())
        EventBus.register(WeatherReminder())
        EventBus.register(PingPong())
    }

    override fun onDisable() {
        ScheduledUtils.stop()
        logger.info("[EventBus] Goodbye!")
    }

    override fun getContact(): Contact {
        return contact
    }

    override fun getDataFolder(): File {
        return dataFolder
    }

    private fun asReaction(action: String): Reaction {
        return when (action) {
            "127874" -> Reaction.CAKE
            "66" -> Reaction.HEART
            "76" -> Reaction.LIKE
            "424" -> Reaction.BUTTON
            "10068" -> Reaction.QUESTION
            "124" -> Reaction.OK
            "10024" -> Reaction.SPARK
            else -> Reaction.QUESTION
        }
    }
}