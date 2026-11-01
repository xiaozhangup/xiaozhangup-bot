package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

internal class QqFiles(private val config: McpConfig) {
    fun sourceBytes(source: JsonObject): Pair<ByteArray, String> = when (source.string("type")) {
        "path" -> {
            val file = File(source.string("path")).canonicalFile
            if (config.shareRoots.none { file.toPath().startsWith(it.toPath()) }) throw QqException("PERMISSION_DENIED", "文件不在配置的共享目录中")
            if (!file.isFile) throw QqException("FILE_NOT_FOUND", "共享文件不存在")
            file.inputStream().use { readLimited(it) } to file.name
        }
        "url" -> {
            val url = source.string("url")
            fetch(url).first to (URI(url).path.substringAfterLast('/').ifBlank { "attachment" })
        }
        else -> invalid("不支持的文件来源")
    }

    fun platformSource(source: JsonObject): String = "base64://" + Base64.getEncoder().encodeToString(sourceBytes(source).first)

    fun fetch(url: String): Pair<ByteArray, String> {
        val uri = try { URI(url) } catch (_: Exception) { invalid("文件 URL 无效") }
        if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.userInfo != null) invalid("文件地址必须是无内嵌凭据的 HTTP 或 HTTPS URL")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = config.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        connection.readTimeout = connection.connectTimeout
        try {
            if (connection.responseCode !in 200..299) throw QqException("FILE_DOWNLOAD_FAILED", "下载地址返回 HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { readLimited(it) }
            val mime = connection.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
            return bytes to mime
        } finally { connection.disconnect() }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val bytes = input.readNBytes(config.maxFileBytes + 1)
        if (bytes.size > config.maxFileBytes) throw QqException("FILE_TOO_LARGE", "文件超过 ${config.maxFileBytes} 字节限制")
        return bytes
    }

    fun save(bytes: ByteArray, directory: String?, name: String): JsonObject {
        val dir = if (directory == null) config.downloadRoot else File(directory).canonicalFile
        if (!dir.toPath().startsWith(config.downloadRoot.toPath())) throw QqException("PERMISSION_DENIED", "下载目录不在配置的下载根目录中")
        if (name.isBlank() || File(name).name != name || name in setOf(".", "..")) invalid("文件名必须是单个文件名")
        Files.createDirectories(dir.toPath())
        val file = File(dir, name).canonicalFile
        if (file.parentFile != dir) invalid("下载路径无效")
        // CREATE_NEW prevents both overwriting an existing file and following an existing symlink.
        val output = Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW)
        try { output.use { it.write(bytes) } }
        catch (e: java.io.IOException) { Files.deleteIfExists(file.toPath()); throw e }
        return obj("path" to file.absolutePath, "size" to bytes.size,
            "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    fun inlineContent(bytes: ByteArray, mime: String): JsonObject? {
        if (bytes.size > config.inlineBytes) return null
        val type = when {
            mime in setOf("image/png", "image/jpeg", "image/gif", "image/webp") -> "image"
            mime.startsWith("audio/") -> "audio"
            else -> return null
        }
        return obj("type" to type, "mimeType" to mime, "data" to Base64.getEncoder().encodeToString(bytes))
    }

    fun capabilities() = obj("max_file_bytes" to config.maxFileBytes, "inline_bytes" to config.inlineBytes,
        "share_roots" to config.shareRoots.map { it.absolutePath }, "download_root" to config.downloadRoot.absolutePath)
}
