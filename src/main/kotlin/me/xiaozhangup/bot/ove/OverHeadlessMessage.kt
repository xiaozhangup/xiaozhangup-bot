package me.xiaozhangup.bot.ove

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.MessageComponent

class OverHeadlessMessage(
    source: Source,
    id: Int,
    components: List<MessageComponent>
) : Message(
    source,
    Type.GROUP,
    id,
    components
)