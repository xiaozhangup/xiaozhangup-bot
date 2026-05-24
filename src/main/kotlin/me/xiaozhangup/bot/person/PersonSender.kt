package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.util.submitDelay
import java.util.concurrent.TimeUnit

object PersonSender {

    fun send(
        message: Message,
        parts: List<String>,
        charDelayMs: Long,
        onSent: (String) -> Unit = {}
    ) {
        if (parts.isEmpty()) return
        val source = message.source
        source.sendMessage(parts[0])
        onSent(parts[0])
        var accumulatedDelay = parts[0].length * charDelayMs
        for (idx in 1 until parts.size) {
            val part = parts[idx]
            submitDelay(accumulatedDelay, TimeUnit.MILLISECONDS) {
                source.sendMessage(part)
                onSent(part)
            }
            accumulatedDelay += part.length * charDelayMs
        }
    }
}
