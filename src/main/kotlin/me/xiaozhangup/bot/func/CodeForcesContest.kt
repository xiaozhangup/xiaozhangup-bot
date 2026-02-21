package me.xiaozhangup.bot.func

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.client.WebClient
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CodeForcesContest : EventUnit(
    "codeforces_contest",
    "CodeForces 竞赛信息工具",
    1
) {

    private val config by lazy { properties("codeforces_contest") }
    private val enabledGroups by lazy {
        config.getProperty("enabled.groups")?.split(',')?.map { it.trim() } ?: listOf()
    }
    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun onGroupMessage(message: Message) {
        if (!enabledGroups.contains(message.source.id)) return
        val text = message.getMessage().trim().split(' ', limit = 2)
        if (text.getOrNull(0) != "/cf") return
        message.addReaction(Reaction.SPARK)
        submit {
            val result = WebClient.fetchUrl("https://codeforces.com/api/contest.list")
            val contestList = json.decodeFromString(CodeForcesContestList.serializer(), result)
            if (contestList.status != "OK") {
                message.addReply("无法获取 CodeForces 竞赛信息")
            } else {
                val res = contestList.result.filter {
                    it.phase == "BEFORE" &&
                        (text.getOrNull(1) == null ||
                            it.name.replace(" ", "").contains(text[1].replace(" ", ""), ignoreCase = true)
                        )
                }.sortedBy { it.startTimeSeconds }
                if (res.isEmpty()) {
                    message.addReply("没有找到符合条件的即将开始的竞赛")
                } else {
                    val ids = res.map { it.id }
                    val url = buildUrl(*ids.toIntArray())
                    message.addReply(buildString {
                        append("即将开始的竞赛:\n")
                        res.forEach {
                            appendLine("${it.name} (${formatTime(it.startTimeSeconds)})\n${buildUrl(it.id)}\n")
                        }
                        append("查看详情: $url")
                    })
                }
            }
        }
    }

    private fun formatTime(seconds: Long): String {
        val instant = java.time.Instant.ofEpochSecond(seconds)
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.systemDefault())
        return formatter.format(instant)
    }

    private fun buildUrl(vararg id: Int): String {
        return "https://codeforces.com/contests/${id.joinToString(",")}"
    }

    @Serializable
    data class CodeForcesContestList(
        val status: String,
        val result: List<Contest>
    )

    @Serializable
    data class Contest(
        val id: Int,
        val name: String,
        val type: String,
        val phase: String,
        val frozen: Boolean,
        val durationSeconds: Int,
        val startTimeSeconds: Long,
        val relativeTimeSeconds: Long
    )
}