package me.xiaozhangup.bot.port.msg.obj

import me.xiaozhangup.bot.port.msg.MessageComponent

class AtComponent(
    context: String
) : MessageComponent(Type.AT, context)