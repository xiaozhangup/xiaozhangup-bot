package me.xiaozhangup.bot.port.msg.obj

import me.xiaozhangup.bot.port.msg.MessageComponent

class AtComponent(
    target: String
) : MessageComponent(Type.AT, target)