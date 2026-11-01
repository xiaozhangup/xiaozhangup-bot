package me.xiaozhangup.bot.person

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.QuoteComponent
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.warning

internal class PersonSession(
    private val profile: PersonProfile,
    private val botId: String,
    private val botName: String,
    private val client: PersonAIClient,
    scope: CoroutineScope
) {
    private data class Incoming(val message: Message, val sender: Source, val timestamp: Long, val sequence: Long)

    private val inbox = Channel<Incoming>(Channel.UNLIMITED)
    private val context = PersonContextStore(profile.historySize)
    private var sequence = 0L

    @Synchronized
    fun accept(message: Message, sender: Source) {
        inbox.trySend(Incoming(message, sender, System.currentTimeMillis(), ++sequence))
    }

    private val worker = scope.launch {
        var next: Incoming? = null
        var evaluatedSequence = 0L
        while (isActive) {
            val batch = collect(next ?: inbox.receive())
            next = null
            var decision: ChatDecision? = null
            try {
                for (incoming in batch) {
                    val message = incoming.message
                    val expanded = PersonMessageExpander.expand(message)
                    context.append(ChatLine(
                        incoming.sender.id, incoming.sender.name, expanded.text, incoming.timestamp, incoming.sequence,
                        message.id, message.component.filterIsInstance<AtComponent>().map { it.context },
                        message.component.filterIsInstance<QuoteComponent>(), expanded.images
                    ))
                }
                // 上一轮请求期间的新消息先合并，不能对过期快照发言。
                next = inbox.tryReceive().getOrNull()
                if (next != null) continue
                val messages = PersonInputBuilder.build(
                    context.snapshot(), botId, botName, profile.target, evaluatedSequence, context.decisionSnapshot()
                )
                val response = withContext(Dispatchers.IO) { client.ask(messages) }
                val currentDecision = ChatDecision(batch.last().sequence, response.content)
                context.recordDecision(currentDecision)
                decision = currentDecision
                next = inbox.tryReceive().getOrNull()
                if (next != null) {
                    currentDecision.status = "superseded"
                    continue
                }

                val parts = PersonResponseParser.parse(response.content)
                info("[PersonChat][${profile.name}] ${if (parts.isEmpty()) "保持静默" else "准备发送 ${parts.size} 条"}" +
                    "，tokens=${response.usage?.totalTokens ?: "-"}")
                val completed = PersonSender.send(batch.last().message.source, parts, profile, { delayMs ->
                    next = receiveWithin(delayMs)
                    // 延迟到期后再检查一次，处理恰好在到期时到达的消息。
                    if (next == null) next = inbox.tryReceive().getOrNull()
                    next != null
                }) { part ->
                    context.append(ChatLine(botId, botName, part, System.currentTimeMillis()))
                    currentDecision.sentParts++
                }
                currentDecision.status = when {
                    !completed -> "interrupted"
                    parts.isEmpty() -> "silent"
                    else -> "sent"
                }
                if (completed) evaluatedSequence = batch.last().sequence
            } catch (e: CancellationException) {
                decision?.status = "interrupted"
                throw e
            } catch (e: Exception) {
                decision?.status = "failed"
                // 不将内部错误发送给聊天对象，也不重试结果不确定的发送。
                warning("[PersonChat][${profile.name}] 本轮处理失败：${e.message}")
            }
        }
    }

    private suspend fun collect(first: Incoming): List<Incoming> {
        val batch = mutableListOf(first)
        val maxDeadline = if (profile.target.type == Message.Type.GROUP)
            first.timestamp + profile.groupMaxWaitMs else Long.MAX_VALUE
        var last = first
        while (true) {
            val remaining = minOf(last.timestamp + profile.debounceMs, maxDeadline) - System.currentTimeMillis()
            if (remaining <= 0) break
            val incoming = receiveWithin(remaining) ?: break
            batch.add(incoming)
            last = incoming
        }
        return batch
    }

    // select 原子选择消息或到期，避免超时取消 receive 时丢失恰好到达的消息。
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun receiveWithin(waitMs: Long): Incoming? = select {
        inbox.onReceive { it }
        onTimeout(waitMs) { null }
    }

    fun close() {
        worker.cancel()
        inbox.cancel()
    }
}
