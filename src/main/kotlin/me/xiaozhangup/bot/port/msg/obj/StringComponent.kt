package me.xiaozhangup.bot.port.msg.obj

import me.xiaozhangup.bot.port.msg.MessageComponent

class StringComponent(
    content: String
) : MessageComponent(Type.STRING, content)