package me.xiaozhangup.bot.mcp

import me.xiaozhangup.bot.util.properties
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

internal data class McpConfig(
    val host: String, val port: Int, val token: String, val origins: Set<String>,
    val shareRoots: List<File>, val downloadRoot: File, val maxFileBytes: Int,
    val inlineBytes: Int, val eventCapacity: Int, val timeoutMillis: Long, val maxSessions: Int
) {
    companion object {
        fun load(dataFolder: File): McpConfig {
            val values = properties("mcp")
            var token = values.getProperty("token", "").trim()
            if (token.isEmpty()) {
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
                values.setProperty("token", token)
                val path = File(dataFolder, "mcp.properties").toPath()
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                Files.newBufferedWriter(path).use { values.store(it, "QQ MCP configuration") }
            }
            fun positive(name: String, default: Int): Int = values.getProperty(name, default.toString()).toInt().also { require(it > 0) { "$name 必须大于 0" } }
            val roots = values.getProperty("share.roots", "").split(';').map(String::trim).filter(String::isNotEmpty)
                .map { File(it).canonicalFile }.ifEmpty { listOf(File(dataFolder, "mcp/share").canonicalFile) }
            val download = values.getProperty("download.root", "").trim().let {
                if (it.isEmpty()) File(dataFolder, "mcp/downloads") else File(it)
            }.canonicalFile
            roots.forEach { Files.createDirectories(it.toPath()) }
            Files.createDirectories(download.toPath())
            val port = positive("port", 48247).also { require(it <= 65535) }
            val maxFileBytes = positive("max.file.bytes", 20971520).also { require(it <= 104857600) { "单文件限制不能超过 100 MiB" } }
            val maxSessions = positive("max.sessions", 32).also { require(it <= 128) { "会话上限不能超过 128" } }
            return McpConfig(values.getProperty("host", "127.0.0.1").trim(), port, token,
                values.getProperty("allowed.origins", "").split(';').map(String::trim).filter(String::isNotEmpty).toSet(),
                roots, download, maxFileBytes, positive("inline.bytes", 1048576).coerceAtMost(maxFileBytes),
                positive("event.capacity", 2000), positive("action.timeout.seconds", 30) * 1000L, maxSessions)
        }
    }
}
