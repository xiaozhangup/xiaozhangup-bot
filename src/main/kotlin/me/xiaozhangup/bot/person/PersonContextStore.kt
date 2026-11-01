package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.port.msg.obj.QuoteComponent

data class ChatLine(
    val senderId: String,
    val senderName: String,
    val text: String,
    val ts: Long,
    val sequence: Long = 0,
    val messageId: Int? = null,
    val mentions: List<String> = emptyList(),
    val quotes: List<QuoteComponent> = emptyList(),
    val images: List<String> = emptyList()
)

data class ChatDecision(
    val throughSequence: Long,
    val output: String,
    val ts: Long = System.currentTimeMillis(),
    var status: String = "generated",
    var sentParts: Int = 0
)

/** 每个会话独立持有，仅由该会话的顺序协程读写。 */
class PersonContextStore(private val capacity: Int) {
    private val lines = ArrayDeque<ChatLine>()
    private val decisions = ArrayDeque<ChatDecision>()

    fun append(line: ChatLine) {
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
    }

    fun snapshot(): List<ChatLine> = lines.toList()

    fun recordDecision(decision: ChatDecision) {
        decisions.addLast(decision)
        while (decisions.size > capacity) decisions.removeFirst()
    }

    fun decisionSnapshot(): List<ChatDecision> = decisions.toList()
}
