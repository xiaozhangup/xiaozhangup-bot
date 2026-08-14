package me.xiaozhangup.bot.port.msg.obj

import me.xiaozhangup.bot.port.msg.MessageComponent

/**
 * 引用回复（QQ 的"回复某条消息"）元数据组件，[context] 为被引用的原消息文本，
 * [sourceIds] 为被引用消息的消息 ID（[net.mamoe.mirai.message.data.MessageSource.ids]），
 * 用于精确识别引用的究竟是哪一条消息。
 *
 * [asString] 返回空字符串：引用内容只作为元数据附加在消息上，不参与基于
 * [me.xiaozhangup.bot.port.Message.getMessage] 的命令解析（避免污染其它模块的
 * 前缀匹配）；需要引用内容的模块应显式读取 [context] / [sourceIds]。
 */
class QuoteComponent(
    content: String,
    val sourceIds: List<Int> = emptyList()
) : MessageComponent(Type.QUOTE, content) {
    override fun asString(): String = ""
}
