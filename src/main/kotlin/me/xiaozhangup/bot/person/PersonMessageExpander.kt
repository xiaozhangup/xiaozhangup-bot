package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.ContainerComponent
import me.xiaozhangup.bot.port.msg.obj.ImageComponent

object PersonMessageExpander {

    private const val INDENT = "  "
    private const val MAX_DEPTH = 5

    fun expand(message: Message): String {
        val out = StringBuilder()
        renderComponents(out, message.component, 0, message.source)
        return out.toString().trim()
    }

    private fun renderComponents(
        out: StringBuilder,
        components: List<MessageComponent>,
        depth: Int,
        topSource: Source
    ) {
        components.forEach { comp ->
            when (comp) {
                is ContainerComponent -> renderForward(out, comp, depth, topSource)
                is ImageComponent -> renderImage(out, comp, depth)
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
        topSource: Source
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
            renderComponents(out, node.component, depth + 1, topSource)
            if (!out.endsWith('\n')) out.append('\n')
        }
    }

    private fun renderImage(out: StringBuilder, comp: ImageComponent, depth: Int) {
        val ocrText = PersonImageOCR.recognize(comp.context).trim()
        if (ocrText.isEmpty()) {
            out.append("[图片]")
            return
        }
        if ('\n' !in ocrText) {
            out.append("[图片: ").append(ocrText).append("]")
            return
        }
        if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
        out.append(INDENT.repeat(depth)).append("[图片内容]\n")
        ocrText.lines().filter { it.isNotBlank() }.forEach { line ->
            out.append(INDENT.repeat(depth + 1)).append(line).append('\n')
        }
        out.append(INDENT.repeat(depth)).append("[/图片]")
    }
}
