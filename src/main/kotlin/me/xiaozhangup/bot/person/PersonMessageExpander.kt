package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.ContainerComponent
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.QuoteComponent

data class ExpandedPersonMessage(val text: String, val images: List<String>)

object PersonMessageExpander {

    private const val INDENT = "  "
    private const val MAX_DEPTH = 5

    fun expand(message: Message): ExpandedPersonMessage {
        val out = StringBuilder()
        val images = mutableListOf<String>()
        renderComponents(out, message.component, 0, message.source, images)
        return ExpandedPersonMessage(out.toString().trim(), images)
    }

    private fun renderComponents(
        out: StringBuilder,
        components: List<MessageComponent>,
        depth: Int,
        topSource: Source,
        images: MutableList<String>
    ) {
        components.forEach { comp ->
            when (comp) {
                is ContainerComponent -> renderForward(out, comp, depth, topSource, images)
                is ImageComponent -> {
                    images.add(comp.context)
                    out.append("[图片${images.size}]")
                }
                is QuoteComponent -> renderQuote(out, comp, depth, topSource, images)
                is AtComponent -> renderAt(out, comp, topSource)
                else -> out.append(comp.asString())
            }
        }
    }

    private fun renderAt(out: StringBuilder, comp: AtComponent, topSource: Source) {
        val target = comp.context
        if (target == "all") {
            out.append("@全体成员")
            return
        }
        val name = (topSource as? Group)?.getMemberName(target)
        if (!name.isNullOrBlank()) {
            out.append("@").append(name)
        } else {
            out.append("@").append(target)
        }
    }

    private fun renderForward(
        out: StringBuilder,
        comp: ContainerComponent,
        depth: Int,
        topSource: Source,
        images: MutableList<String>
    ) {
        if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')

        val title = comp.context.ifBlank { "合并转发" }
        if (depth >= MAX_DEPTH) {
            out.append(INDENT.repeat(depth)).append("└─ ").append(title).append(" [嵌套过深,已省略]\n")
            return
        }

        out.append(INDENT.repeat(depth)).append("└─ ").append(title).append('\n')
        comp.elements.forEach { node ->
            val sender = node.source
            out.append(INDENT.repeat(depth + 1))
                .append("[")
                .append(sender.name)
                .append("]: ")
            renderComponents(out, node.component, depth + 1, topSource, images)
            if (!out.endsWith('\n')) out.append('\n')
        }
    }

    private fun renderQuote(
        out: StringBuilder,
        comp: QuoteComponent,
        depth: Int,
        topSource: Source,
        images: MutableList<String>
    ) {
        out.append("[引用消息] ")
        if (depth >= MAX_DEPTH || comp.components.isEmpty()) {
            out.append(comp.context)
        } else {
            renderComponents(out, comp.components, depth + 1, topSource, images)
        }
        out.append(" [/引用消息]\n")
    }
}
