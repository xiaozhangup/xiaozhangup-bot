package me.xiaozhangup.bot.func

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.client.ai.AIClient
import me.xiaozhangup.bot.client.mail.FetchedMail
import me.xiaozhangup.bot.client.mail.ImapClient
import me.xiaozhangup.bot.client.mail.MailStateStore
import me.xiaozhangup.bot.client.mail.MailSummaryItem
import me.xiaozhangup.bot.client.mail.MailboxConfig
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.dataFolder
import me.xiaozhangup.bot.util.getGroup
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.registerScheduled
import me.xiaozhangup.bot.util.submit
import me.xiaozhangup.bot.util.warning
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Properties

class MailSummary : EventUnit(
    "mail_summary",
    "邮件摘要工具",
    1
) {

    enum class Slot(val hour: Int, val windowHours: Int, val label: String) {
        MORNING(8, 8, "00:00-08:00"),
        NOON(14, 6, "08:00-14:00"),
        NIGHT(22, 8, "14:00-22:00")
    }

    data class MailboxSummary(
        val label: String,
        val summaries: List<String>,
        val filteredSpamCount: Int,
        val isFailed: Boolean = false,
        val failureReason: String? = null
    )

    private val config by lazy { properties("mail_summary") }
    private val stateStore by lazy { MailStateStore(dataFolder("mail_summary")) }

    private val bodyMaxChars by lazy {
        config.getProperty("ai.body.maxChars")?.toIntOrNull() ?: 2000
    }
    private val pushEmpty by lazy {
        config.getProperty("push.empty")?.toBoolean() ?: true
    }
    private val prefilterKeywords by lazy {
        config.getProperty("prefilter.keywords")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }
    private val mailboxes: List<MailboxConfig> by lazy { parseMailboxes(config) }

    private val aiClient by lazy {
        val apiKey = config.getProperty("ai.key")
            ?: throw IllegalStateException("AI key not set in mail_summary.properties")
        require(apiKey.isNotBlank()) { "AI key not set in mail_summary.properties" }
        val apiModel = config.getProperty("ai.model") ?: "glm-4.1v-thinking-flash"
        val apiUrl = config.getProperty("ai.url") ?: "https://open.bigmodel.cn/api/paas/v4/"
        AIClient(SYSTEM_PROMPT, apiKey, apiUrl, apiModel)
    }

    private val overallSummaryClient by lazy {
        val apiKey = config.getProperty("ai.key")
            ?: throw IllegalStateException("AI key not set in mail_summary.properties")
        require(apiKey.isNotBlank()) { "AI key not set in mail_summary.properties" }
        val apiModel = config.getProperty("ai.model") ?: "glm-4.1v-thinking-flash"
        val apiUrl = config.getProperty("ai.url") ?: "https://open.bigmodel.cn/api/paas/v4/"
        AIClient(OVERALL_SUMMARY_PROMPT, apiKey, apiUrl, apiModel)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        registerScheduled("mail_summary_morning", Slot.MORNING.hour, 0) { runSlot(Slot.MORNING) }
        registerScheduled("mail_summary_noon", Slot.NOON.hour, 0) { runSlot(Slot.NOON) }
        registerScheduled("mail_summary_night", Slot.NIGHT.hour, 0) { runSlot(Slot.NIGHT) }
    }

    private suspend fun runSlot(slot: Slot) {
        if (mailboxes.isEmpty()) {
            info("[MailSummary] No mailbox configured, skip slot=$slot.")
            return
        }
        val targetId = config.getProperty("notification.target")
        if (targetId.isNullOrBlank()) {
            warning("[MailSummary] notification.target is empty, skip slot=$slot.")
            return
        }
        val target = getGroup(targetId)
        if (target == null) {
            warning("[MailSummary] Notification target group $targetId not found, skip slot=$slot.")
            return
        }

        val slotEnd = computeSlotEnd(slot)
        info("[MailSummary] Running slot=$slot for ${mailboxes.size} mailbox(es), until=$slotEnd.")
        val results = coroutineScope {
            mailboxes.map { mb ->
                async(Dispatchers.IO) {
                    try {
                        processMailbox(mb, computeSince(mb.id, slot), slotEnd, recordState = slot.name)
                    } catch (e: Exception) {
                        warning("[MailSummary] mailbox=${mb.id}(${mb.label}) failed: ${e.message}")
                        e.printStackTrace()
                        MailboxSummary(mb.label, emptyList(), 0, isFailed = true, failureReason = e.message ?: e.toString())
                    }
                }
            }.awaitAll()
        }

        val allEmpty = results.all { it.summaries.isEmpty() }
        if (allEmpty && !pushEmpty) {
            info("[MailSummary] All mailboxes empty for slot=$slot, push.empty=false, skip push.")
            return
        }

        val overallSummary = summarizeOverall(results)
        val msg = formatSlotMessage(slot, results, overallSummary)
        target.sendMessage(msg)
    }

    override fun onGroupMessage(message: Message) {
        val text = message.getMessage().trim()
        if (text != "/mailbox") return

        val targetId = config.getProperty("notification.target")
        if (targetId.isNullOrBlank() || message.source.id != targetId) return

        if (mailboxes.isEmpty()) {
            message.addReply("未配置任何邮箱")
            return
        }

        message.addReaction(Reaction.SPARK)
        submit {
            try {
                val now = System.currentTimeMillis()
                val since = now - 24 * 60 * 60 * 1000L
                info("[MailSummary] Manual /mailbox triggered, since=$since (24h).")
                val results = coroutineScope {
                    mailboxes.map { mb ->
                        async(Dispatchers.IO) {
                            try {
                                processMailbox(mb, since, now, recordState = null)
                            } catch (e: Exception) {
                                warning("[MailSummary] mailbox=${mb.id}(${mb.label}) failed: ${e.message}")
                                e.printStackTrace()
                                MailboxSummary(mb.label, emptyList(), 0, isFailed = true, failureReason = e.message ?: e.toString())
                            }
                        }
                    }.awaitAll()
                }
                val overallSummary = summarizeOverall(results)
                message.addReply(formatManualMessage(results, overallSummary))
            } catch (e: Exception) {
                warning("[MailSummary] Manual run failed: ${e.message}")
                e.printStackTrace()
                message.addReply("邮件摘要执行失败: ${e.message}")
            }
        }
    }

    private fun processMailbox(
        mb: MailboxConfig,
        since: Long,
        until: Long,
        recordState: String?
    ): MailboxSummary {
        val raw = ImapClient(mb).fetchSince(since, until, bodyMaxChars)
        val filtered = raw
            .filterNot { hitsPrefilterKeywords(it) }
            .sortedByDescending { it.receivedAt }
            .mapIndexed { i, m -> m.copy(index = i) }
        val prefilterSpamCount = raw.size - filtered.size

        info("[MailSummary] mailbox=${mb.id}(${mb.label}) fetched=${raw.size} afterPrefilter=${filtered.size} since=$since until=$until")

        if (filtered.isEmpty()) {
            if (recordState != null) stateStore.setLastRun(mb.id, recordState, until)
            return MailboxSummary(mb.label, emptyList(), prefilterSpamCount)
        }

        val items = summarizeWithAI(filtered)
        if (items == null) {
            warning("[MailSummary] mailbox=${mb.id} AI summarize failed after retries.")
            return MailboxSummary(mb.label, emptyList(), prefilterSpamCount)
        }

        val useful = items
            .filter { it.isUseful && it.summary.isNotBlank() }
            .map { it.summary.trim() }
        info("[MailSummary] mailbox=${mb.id} AI summarize finished, total ${useful.size}.")
        if (recordState != null) stateStore.setLastRun(mb.id, recordState, until)
        val spamCount = raw.size - useful.size
        return MailboxSummary(mb.label, useful, spamCount)
    }

    private fun computeSlotEnd(slot: Slot): Long {
        val zone = ZoneId.systemDefault()
        return LocalDateTime.of(LocalDate.now(), LocalTime.of(slot.hour, 0))
            .atZone(zone).toInstant().toEpochMilli()
    }

    private fun computeSince(mailboxId: String, slot: Slot): Long {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val slotStartHour = slot.hour - slot.windowHours
        val slotStartMillis = LocalDateTime.of(today, LocalTime.of(slotStartHour.coerceAtLeast(0), 0))
            .atZone(zone).toInstant().toEpochMilli()
        val slotEndMillis = computeSlotEnd(slot)
        val last = stateStore.getLastRun(mailboxId, slot.name) ?: return slotStartMillis
        // last 必须落在本时段窗口内才有意义；跨天的旧值或异常值都退回到 slotStart。
        return last.coerceIn(slotStartMillis, slotEndMillis)
    }

    private fun hitsPrefilterKeywords(m: FetchedMail): Boolean {
        if (prefilterKeywords.isEmpty()) return false
        val hay = (m.subject + "\n" + m.from + "\n" + m.body.take(200)).lowercase()
        return prefilterKeywords.any { hay.contains(it.lowercase()) }
    }

    private fun summarizeWithAI(mails: List<FetchedMail>): List<MailSummaryItem>? {
        val input = buildAIInput(mails)
        repeat(3) {
            try {
                val result = aiClient.ask(input)
                val start = result.indexOfFirst { it == '[' }
                val end = result.indexOfLast { it == ']' }
                if (start == -1 || end == -1 || end <= start) {
                    warning("[MailSummary] AI response missing JSON array brackets, retrying...")
                    return@repeat
                }
                val payload = result.substring(start, end + 1)
                return json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(MailSummaryItem.serializer()),
                    payload
                )
            } catch (e: Exception) {
                warning("[MailSummary] AI parse failed: ${e.message}")
                e.printStackTrace()
            }
        }
        return null
    }

    private fun buildAIInput(mails: List<FetchedMail>): String {
        val arr = kotlinx.serialization.json.buildJsonArray {
            mails.forEach { m ->
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("index", kotlinx.serialization.json.JsonPrimitive(m.index))
                        put("from", kotlinx.serialization.json.JsonPrimitive(m.from))
                        put("subject", kotlinx.serialization.json.JsonPrimitive(m.subject))
                        put("body", kotlinx.serialization.json.JsonPrimitive(m.body))
                    }
                )
            }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr)
    }

    private fun summarizeOverall(results: List<MailboxSummary>): String? {
        val summaries = results.flatMap { it.summaries }
        if (summaries.isEmpty()) return null
        val input = buildOverallSummaryInput(results)
        repeat(3) {
            try {
                val text = overallSummaryClient.ask(input)
                    .replace("\n", " ")
                    .trim()
                if (text.isNotBlank()) return text
            } catch (e: Exception) {
                warning("[MailSummary] Overall summary failed: ${e.message}")
                e.printStackTrace()
            }
        }
        return null
    }

    private fun buildOverallSummaryInput(results: List<MailboxSummary>): String {
        return buildString {
            append("请对以下邮件摘要进行客观、直白、高效的总体总结，不超过 80 字，无需任何情绪色彩，直接提炼核心事项，避免逐条复述。\n")
            results.forEach { result ->
                if (result.summaries.isNotEmpty()) {
                    append(result.label).append("\n")
                    result.summaries.forEachIndexed { i, s ->
                        append(i + 1).append(". ").append(s).append("\n")
                    }
                }
            }
        }
    }

    private fun formatSlotMessage(
        slot: Slot,
        results: List<MailboxSummary>,
        overallSummary: String?
    ): String {
        val greeting = when (slot) {
            Slot.MORNING -> "早上好"
            Slot.NOON -> "下午好"
            Slot.NIGHT -> "晚上好"
        }
        val header = "$greeting！已整理 ${slot.label} 时段（%02d:00 推送）的邮箱摘要:"
            .format(slot.hour)
        return formatBody(header, results, allEmptyHint = "本时段无重要邮件", overallSummary = overallSummary)
    }

    private fun formatManualMessage(
        results: List<MailboxSummary>,
        overallSummary: String?
    ): String {
        val header = "邮件摘要 (近 24 小时):"
        return formatBody(header, results, allEmptyHint = "近 24 小时无重要邮件", overallSummary = overallSummary)
    }

    private fun formatBody(
        header: String,
        results: List<MailboxSummary>,
        allEmptyHint: String,
        overallSummary: String?
    ): String {
        val allSuccess = results.none { it.isFailed }
        val anySummaries = results.any { it.summaries.isNotEmpty() }
        val summaryHeader = overallSummary.orEmpty()

        if (!anySummaries && allSuccess) {
            val spamDetails = results.filter { it.filteredSpamCount > 0 }
                .joinToString(", ") { "${it.label}过滤 ${it.filteredSpamCount} 封" }
            val spamHint = if (spamDetails.isNotEmpty()) " ($spamDetails)" else ""
            return "$header\n$summaryHeader\n\n$allEmptyHint$spamHint"
        }

        val body = buildString {
            results.forEach { result ->
                append("\n\n").append(result.label)
                when {
                    result.isFailed -> {
                        append(" (获取失败):")
                        append("\n").append(result.failureReason?.take(120) ?: "未知错误")
                    }
                    result.summaries.isEmpty() -> {
                        val filterText = if (result.filteredSpamCount > 0) " (过滤 ${result.filteredSpamCount} 封):" else " (无新邮件):"
                        append(filterText).append("\n暂无重要邮件")
                    }
                    else -> {
                        val filterText = if (result.filteredSpamCount > 0) " (过滤 ${result.filteredSpamCount} 封):" else ":"
                        append(filterText)
                        result.summaries.forEachIndexed { i, s ->
                            append("\n").append(i + 1).append(". ").append(s)
                        }
                    }
                }
            }
        }
        return "$header\n$summaryHeader$body"
    }

    private fun parseMailboxes(cfg: Properties): List<MailboxConfig> {
        val ids = cfg.stringPropertyNames()
            .mapNotNull { key ->
                Regex("^mailbox\\.(\\d+)\\.host$").matchEntire(key)?.groupValues?.get(1)
            }
            .toSortedSet()

        return ids.mapNotNull { id ->
            val host = cfg.getProperty("mailbox.$id.host")?.trim().orEmpty()
            val user = cfg.getProperty("mailbox.$id.user")?.trim().orEmpty()
            val password = cfg.getProperty("mailbox.$id.password").orEmpty()
            if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
                warning("[MailSummary] mailbox.$id incomplete (host/user/password), skipped.")
                return@mapNotNull null
            }
            val port = cfg.getProperty("mailbox.$id.port")?.toIntOrNull() ?: 993
            val label = cfg.getProperty("mailbox.$id.label")?.trim().takeUnless { it.isNullOrEmpty() } ?: user
            val ssl = cfg.getProperty("mailbox.$id.ssl")?.toBoolean() ?: true
            MailboxConfig(id, host, port, user, password, label, ssl)
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            你是邮件筛选与摘要助手。用户提交一个 JSON 数组，每个元素包含 index/from/subject/body。
            请按以下规则处理：
            1) 判断邮件是否对用户有阅读价值；
            2) 验证码、广告、营销、订阅推广、自动回复、系统通知（无操作要求）等均判为无价值 (is_useful=false)；
            3) 对有价值邮件用中文写一句不超过 60 字的摘要，包含发件方与核心事项；
            4) 严格只输出 JSON 数组，不要包含任何额外文本、解释或 Markdown 代码块标记。

            输出格式（数组中元素数量与输入相同）：
            [
              {"index": 0, "is_useful": true, "summary": "..."},
              {"index": 1, "is_useful": false, "summary": ""}
            ]
        """.trimIndent()

        private val OVERALL_SUMMARY_PROMPT = """
            你是邮件总体总结助手。用户提供各邮箱的摘要条目。
            请用客观、直白、高效且不带任何情绪色彩的中文写一段总体总结，不超过 80 字。
            无需友好寒暄、客套或口语化修饰，直接提炼关键事项，不要逐条复述，有多个要点时，不同要点之间使用逗号进行分隔，不要输出列表或编号。
        """.trimIndent()
    }
}
