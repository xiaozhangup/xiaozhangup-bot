package me.xiaozhangup.bot.client.mail

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import java.util.Date
import java.util.Properties

class ImapClient(private val cfg: MailboxConfig) {

    fun fetchSince(since: Long, bodyMaxChars: Int = 2000): List<FetchedMail> {
        val props = Properties().apply {
            val protocol = if (cfg.ssl) "imaps" else "imap"
            setProperty("mail.store.protocol", protocol)
            setProperty("mail.$protocol.host", cfg.host)
            setProperty("mail.$protocol.port", cfg.port.toString())
            setProperty("mail.$protocol.connectiontimeout", "20000")
            setProperty("mail.$protocol.timeout", "30000")
            if (cfg.ssl) {
                setProperty("mail.imaps.ssl.enable", "true")
            }
        }

        val session = Session.getInstance(props)
        val store = session.getStore(if (cfg.ssl) "imaps" else "imap")
        store.connect(cfg.host, cfg.port, cfg.user, cfg.password)
        sendImapIdIfSupported(store)

        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val term = ReceivedDateTerm(ComparisonTerm.GE, Date(since))
                val messages = folder.search(term)
                return messages.mapIndexedNotNull { i, msg ->
                    runCatching { toFetchedMail(i, msg, bodyMaxChars) }.getOrNull()
                }
            } finally {
                runCatching { folder.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun sendImapIdIfSupported(store: jakarta.mail.Store) {
        val method = runCatching { store.javaClass.getMethod("id", Map::class.java) }.getOrNull() ?: return
        val idMap = linkedMapOf(
            "name" to "xiaozhangup-bot",
            "version" to (System.getProperty("java.version") ?: "unknown"),
            "vendor" to (System.getProperty("java.vendor") ?: "unknown"),
            "os" to (System.getProperty("os.name") ?: "unknown"),
            "os-version" to (System.getProperty("os.version") ?: "unknown")
        )
        try {
            method.invoke(store, idMap)
        } catch (_: Exception) {
            // Ignore ID failures to avoid blocking mailbox fetch.
        }
    }

    private fun toFetchedMail(index: Int, msg: Message, bodyMaxChars: Int): FetchedMail {
        val from = msg.from?.joinToString(", ") { it.toString() } ?: ""
        val subject = msg.subject ?: ""
        val body = extractText(msg).let { trim(it, bodyMaxChars) }
        val receivedAt = (msg.receivedDate ?: msg.sentDate ?: Date()).time
        return FetchedMail(
            index = index,
            from = decode(from),
            subject = decode(subject),
            body = body,
            receivedAt = receivedAt
        )
    }

    private fun decode(s: String): String = try {
        MimeUtility.decodeText(s)
    } catch (_: Exception) {
        s
    }

    private fun extractText(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> (part.content as? String).orEmpty()
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as jakarta.mail.Multipart
                    val plainParts = StringBuilder()
                    val htmlParts = StringBuilder()
                    for (i in 0 until mp.count) {
                        val bp = mp.getBodyPart(i)
                        when {
                            bp.isMimeType("text/plain") -> plainParts.append(bp.content as? String ?: "").append('\n')
                            bp.isMimeType("text/html") -> htmlParts.append(bp.content as? String ?: "").append('\n')
                            bp.isMimeType("multipart/*") -> plainParts.append(extractText(bp)).append('\n')
                        }
                    }
                    if (plainParts.isNotBlank()) plainParts.toString() else stripHtml(htmlParts.toString())
                }
                part.isMimeType("text/html") -> stripHtml(part.content as? String ?: "")
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun trim(s: String, max: Int): String {
        val normalized = s.replace("\r\n", "\n").trim()
        return if (normalized.length <= max) normalized else normalized.substring(0, max) + "…"
    }
}
