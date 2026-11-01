package me.xiaozhangup.bot.func

import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.xiaozhangup.bot.client.CodexUsage
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.ScheduledUtils
import me.xiaozhangup.bot.util.getGroup
import me.xiaozhangup.bot.util.getDataFolder
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import net.mamoe.mirai.utils.currentTimeSeconds
import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.io.File
import kotlin.math.round
import kotlin.time.Duration.Companion.minutes

class CodexUsage: EventUnit(
    "codex_usage",
    "Codex 额度查询工具",
    1
) {

    private var displayedPercent: Int? = null
    private var notifiedResetAt: Long? = null
    private val renameMutex = Mutex()
    private val renameStateFile = File(getDataFolder(), "codex_usage_last_rename.txt")
    private var lastRenameAt = if (renameStateFile.exists()) renameStateFile.readText().trim().toLongOrNull() ?: 0L else 0L
    private var pendingRename: Job? = null

    init {
        ScheduledUtils.getScheduler().launch {
            while (true) {
                try {
                    val group = getGroup("1033959018")!!
                    val usage = CodexUsage.fetchCodexUsage()
                    val window = usage.rateLimit?.primaryWindow!!
                    val resetAt = window.resetAt!!
                    val reset = resetAt - window.limitWindowSeconds!!
                    val curr = currentTimeSeconds()
                    if (curr - reset in 0 until 60 * 6 && notifiedResetAt != resetAt) {
                        group.sendMessageConfirmed(StringComponent("Codex 额度已刷新"))
                        notifiedResetAt = resetAt
                    }

                    val percent = round(window.remainingPercent!!).toInt()
                    updateGroupName(percent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(5.minutes)
            }
        }
    }

    private suspend fun updateGroupName(percent: Int): Unit = renameMutex.withLock {
        val group = getGroup("1033959018")!!
        val suffix = Regex("（(\\d+)%）$")
        val previous = displayedPercent ?: suffix.find(group.name)?.groupValues?.get(1)?.toIntOrNull()
        if (previous != null && kotlin.math.abs(percent - previous) < 3) return@withLock

        val remainingMillis = lastRenameAt + 30.minutes.inWholeMilliseconds - System.currentTimeMillis()
        if (remainingMillis > 0) {
            if (pendingRename?.isActive != true) {
                pendingRename = ScheduledUtils.getScheduler().launch {
                    delay(remainingMillis)
                    renameMutex.withLock { pendingRename = null }
                    try {
                        val window = CodexUsage.fetchCodexUsage().rateLimit?.primaryWindow!!
                        updateGroupName(round(window.remainingPercent!!).toInt())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return@withLock
        }

        group.setGroupName("${group.name.replace(suffix, "")}（${percent}%）")
        lastRenameAt = System.currentTimeMillis()
        renameStateFile.writeText(lastRenameAt.toString())
        displayedPercent = percent
    }

    override fun onFriendMessage(message: Message) {
        val sender = message.getSender() ?: return
        val context = message.getMessage()
        if (context != "/usage" && context != "/usage rpm" && context != "/usage tpm") return

        getGroup("1033959018")?.let { group ->
            if (group.isMember(sender.id)) {
                submit {
                    try {
                        val usage = CodexUsage.fetchCodexUsage()
                        val window = usage.rateLimit?.primaryWindow!!
                        val type = if (window.limitWindowSeconds!! < 60 * 60 * 6) "5小时" else "周"
                        message.addReply("Codex ${type}额度剩余 ${round(window.remainingPercent!!).toInt()}%")
                    } catch (e: Exception) {
                        message.addReply("查询剩余额度状况失败: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onGroupMessage(message: Message) {
        val group = message.source.id
        if (group != "1033959018") return

        val context = message.getMessage()
        if (context != "/usage" && context != "/usage rpm" && context != "/usage tpm") return

        message.addReaction(Reaction.SPARK)
        submit {
            try {
                if (context == "/usage rpm" || context == "/usage tpm") {
                    val stat = CodexUsage.fetchRelayStat(properties("usage_stat"))
                    val value = if (context == "/usage rpm") "RPM: ${stat.rpm}" else "TPM: ${formatTpm(stat.tpm)}"
                    message.addReply("中转站当前 $value")
                    return@submit
                }
                val usage = CodexUsage.fetchCodexUsage()
                val window = usage.rateLimit?.primaryWindow
                if (window == null) {
                    message.addReply("查询剩余额度状况失败")
                    return@submit
                }

                val type = if (window.limitWindowSeconds!! < 60 * 60 * 6) "5小时" else "周"
                val details = CodexUsage.fetchResetCredits()
                val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Shanghai"))
                val expirations = details.credits.filter { it.status == "available" }
                    .sortedBy { it.expiresAt?.let(Instant::parse) ?: Instant.MAX }
                    .map { credit ->
                        val expiresAt = credit.expiresAt?.let { dateFormat.format(Instant.parse(it)) }
                        "- ${expiresAt ?: "未提供到期时间"}"
                    }
                message.addReply(
                    """
                    Codex ${type}额度剩余 ${round(window.remainingPercent!!).toInt()}%
                    下次刷新于 ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(window.resetAt!! * 1000))}
                    """.trimIndent() + "\n\n重置卡:\n" + expirations.joinToString("\n").ifEmpty { "- 无剩余重置卡" }
                )
            } catch (e: Exception) {
                val subject = if (context == "/usage") "剩余额度状况" else "中转站用量"
                message.addReply("查询${subject}失败: ${e.message}")
            }
        }
    }

    private fun formatTpm(tpm: Long): String {
        val (divisor, suffix) = when {
            tpm >= 1_000_000_000 -> 1_000_000_000L to "b"
            tpm >= 1_000_000 -> 1_000_000L to "m"
            tpm >= 1_000 -> 1_000L to "k"
            else -> return tpm.toString()
        }
        return BigDecimal.valueOf(tpm).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString() + suffix
    }
}
