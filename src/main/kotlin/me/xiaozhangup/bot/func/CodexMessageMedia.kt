package me.xiaozhangup.bot.func

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import me.xiaozhangup.bot.client.GroupContainer
import java.util.Base64
import java.util.Locale

/** Keep attachments in the Codex workspace so later turns can still read the original files. */
internal class CodexMessageMedia(private val container: GroupContainer, groupId: String, messageId: Int) {
    private val directory = "/workspace/inputs/qq/$groupId/$messageId-${java.util.UUID.randomUUID()}"
    private var totalBytes = 0L

    fun save(source: JsonObject, name: String, limit: Int): String {
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(100).ifBlank { "attachment" }
        val target = File.createTempFile("qbot-media-", ".part")
        var fileName = "${java.util.UUID.randomUUID()}-$safeName"
        var connection: HttpURLConnection? = null
        try {
            val encoded = source.value("base64")?.takeIf(String::isNotBlank)
            val stream = if (encoded != null) {
                require(encoded.length.toLong() <= (limit.toLong() + 2) / 3 * 4) { "附件超过 ${limit / MIB} MiB" }
                Base64.getDecoder().decode(encoded).inputStream()
            } else {
                val uri = URI(source.value("url") ?: error("平台没有返回下载地址或 base64"))
                require(uri.scheme in setOf("http", "https")) { "平台只返回了本地路径，机器人无法读取容器内文件" }
                connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                require(connection.responseCode in 200..299) { "附件下载 HTTP ${connection.responseCode}" }
                connection.inputStream
            }
            val mime: String?
            stream.buffered().use { input ->
                mime = URLConnection.guessContentTypeFromStream(input)
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        totalBytes += count
                        require(size <= limit) { "附件超过 ${limit / MIB} MiB" }
                        require(totalBytes <= 400L * MIB) { "本条消息的附件总量超过 400 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (name == "image" || name == "card-image") {
                // Java's sniffer does not recognize WebP.
                val webp = target.inputStream().use { it.readNBytes(12) }.let {
                    it.size == 12 && String(it, 0, 4, Charsets.US_ASCII) == "RIFF" && String(it, 8, 4, Charsets.US_ASCII) == "WEBP"
                }
                val extension = when {
                    webp -> "webp"
                    mime == "image/jpeg" -> "jpg"
                    mime == "image/png" -> "png"
                    mime == "image/gif" -> "gif"
                    else -> error("图片格式不受支持：$mime")
                }
                fileName += ".$extension"
            }
            return container.importFile(target, "$directory/$fileName")
        } finally { connection?.disconnect(); target.delete() }
    }

    fun frames(file: String): List<Pair<Double, String>> {
        val probe = run(listOf("/usr/bin/ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", file))
        val duration = Json.parseToJsonElement(probe).jsonObject["format"]?.jsonObject?.value("duration")?.toDoubleOrNull()
        require(duration != null && duration.isFinite() && duration > 0) { "无法读取视频或动画时长，原文件已保留" }
        val count = minOf(12, maxOf(1, kotlin.math.ceil(duration * 2).toInt()))
        return (0 until count).map { index ->
            val seconds = duration * index / count
            val frame = "$file-frame-$index.png"
            run(listOf("/usr/bin/ffmpeg", "-nostdin", "-v", "error", "-ss", String.format(Locale.ROOT, "%.3f", seconds),
                "-i", file, "-frames:v", "1", "-vf", "scale=1280:1280:force_original_aspect_ratio=decrease",
                "-y", frame))
            require(container.run(listOf("/usr/bin/test", "-s", frame)).code == 0) { "抽帧失败，原文件已保留" }
            seconds to frame
        }
    }

    private fun run(command: List<String>): String {
        val result = container.run(command)
        check(!result.timedOut) { "${command.first()} 处理超时，原文件已保留" }
        check(result.code == 0) { "${command.first()} 失败：${result.output.take(300)}" }
        return result.output
    }

    companion object { private const val MIB = 1024 * 1024 }
}
