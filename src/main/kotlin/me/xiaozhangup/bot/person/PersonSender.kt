package me.xiaozhangup.bot.person

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.obj.StringComponent

object PersonSender {
    suspend fun send(
        source: Source,
        parts: List<String>,
        profile: PersonProfile,
        interruptedDuring: suspend (Long) -> Boolean,
        onSent: (String) -> Unit
    ): Boolean {
        for (part in parts) {
            val characters = part.codePointCount(0, part.length)
            val delay = minOf(profile.maxDelayMs, profile.baseDelayMs + characters.toLong() * profile.charDelayMs)
            if (interruptedDuring(delay)) return false
            currentCoroutineContext().ensureActive()
            // 已提交平台的这一条等回执；停用或新消息只阻止后续尚未提交的消息。
            withContext(NonCancellable) {
                source.sendMessageConfirmed(StringComponent(part))
                onSent(part)
            }
        }
        return true
    }
}
