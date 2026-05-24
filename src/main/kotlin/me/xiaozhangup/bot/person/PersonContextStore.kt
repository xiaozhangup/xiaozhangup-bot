package me.xiaozhangup.bot.person

data class ChatLine(
    val senderId: String,
    val senderName: String,
    val text: String,
    val ts: Long
)

class PersonContextStore(private val capacity: Int) {

    private val map = mutableMapOf<String, MutableList<ChatLine>>()

    @Synchronized
    fun append(sourceId: String, line: ChatLine) {
        val list = map.getOrPut(sourceId) { mutableListOf() }
        list.add(line)
        while (list.size > capacity) {
            list.removeAt(0)
        }
    }

    @Synchronized
    fun snapshot(sourceId: String): List<ChatLine> {
        return map[sourceId]?.toList() ?: emptyList()
    }
}
