package me.xiaozhangup.bot.util

import me.xiaozhangup.bot.ove.OverHeadlessMessage
import me.xiaozhangup.bot.ove.OverHeadlessSource
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import top.mrxiaom.overflow.OverflowAPI

suspend fun asMessage(chain: MessageChain): List<MessageComponent> {
    return chain.mapNotNull { msg ->
        when (msg) {
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

            is LightApp -> {
                RichComponent("小程序", "")
            }

            is ServiceMessage -> {
                RichComponent("服务", msg.serviceId.toString())
            }

            is RichMessage -> {
                RichComponent("富文本", "")
            }

            is FileMessage -> {
                RichComponent("文件", msg.name)
            }

            is ForwardMessage -> {
                ContainerComponent(
                    msg.title,
                    msg.nodeList.map {
                        OverHeadlessMessage(
                            OverHeadlessSource(it.senderName, it.senderId.toString()),
                            -1,
                            asMessage(it.messageChain)
                        )
                    }
                )
            }

            else -> {
                val content = msg.content
                if (content.isEmpty()) null
                else StringComponent(msg.content)
            }
        }
    }
}

fun asMessageChain(vararg messages: MessageComponent): MessageChain {
    return buildMessageChain {
        messages.forEach { comp ->
            when (comp) {
                is StringComponent -> +PlainText(comp.asString())
                is AtComponent -> comp.context.toLongOrNull()
                    ?.let { +At(it) }
                    ?: +AtAll
                is ImageComponent -> +OverflowAPI.get().imageFromFile(comp.context)
                is RichComponent -> +PlainText(comp.asString())
                is ContainerComponent -> +PlainText(comp.asString())
                else -> +PlainText(comp.asString())
            }
        }
    }
}