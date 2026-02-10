package me.xiaozhangup.bot.util

fun extractTags(text: String): List<String> {
    val regex = Regex("#([^\\s#]+)")
    return regex.findAll(text)
        .map { it.groupValues[1] }
        .toList()
}
