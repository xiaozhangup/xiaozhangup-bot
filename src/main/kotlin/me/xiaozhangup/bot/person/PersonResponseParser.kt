package me.xiaozhangup.bot.person

object PersonResponseParser {

    private const val EMPTY_TOKEN = "<empty>"
    private const val SPILT_TOKEN = "<spilt>"

    fun parse(raw: String): List<String> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()

        if (normalized == EMPTY_TOKEN) return emptyList()
        require(EMPTY_TOKEN !in normalized) { "回复混用了 <empty> 与正文，已取消发送" }
        require("\\<spilt>" !in normalized) { "分隔符不能带反斜杠，已取消发送" }

        return normalized.split(SPILT_TOKEN, "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
