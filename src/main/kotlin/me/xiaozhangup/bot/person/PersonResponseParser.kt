package me.xiaozhangup.bot.person

object PersonResponseParser {

    private const val EMPTY_TOKEN = "<empty>"
    private const val SPILT_TOKEN = "<spilt>"

    fun parse(raw: String): List<String> {
        val normalized = raw.replace("\r", "").trim()
        if (normalized.isEmpty()) return emptyList()

        val withoutEmpty = normalized.replace(EMPTY_TOKEN, "").trim()
        if (withoutEmpty.isEmpty()) return emptyList()

        return withoutEmpty.split(SPILT_TOKEN)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
