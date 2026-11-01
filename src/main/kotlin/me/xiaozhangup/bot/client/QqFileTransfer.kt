package me.xiaozhangup.bot.client

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import top.mrxiaom.overflow.action.ActionContext
import top.mrxiaom.overflow.contact.RemoteBot
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.warning
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Only the host bridge and NapCat can access this spool; group containers cannot. */
internal class QqFileTransfer(private val hostDirectory: File, private val napcatDirectory: String) {
    suspend fun <T> upload(input: InputStream, send: suspend (String) -> T): T {
        val directory = input.use { source ->
            require(hostDirectory.isDirectory) { "QQ 文件中转目录不存在：$hostDirectory" }
            val dir = Files.createTempDirectory(hostDirectory.toPath(), "upload-").toFile()
            try {
                // NapCat may use a different UID. Parent is a dedicated private mount root.
                Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
                val payload = File(dir, "payload")
                payload.outputStream().use { source.copyTo(it) }
                Files.setPosixFilePermissions(payload.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
                dir
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
        val result = try {
            send("${napcatDirectory.trimEnd('/')}/${directory.name}/payload")
        } catch (e: Exception) {
            // A lost response does not prove the upload failed; retain the source for inspection.
            throw QqUploadUnconfirmedException(directory.name, e)
        }
        if (!directory.deleteRecursively()) warning("[QQ File] 上传已确认，但中转目录清理失败：${directory.name}")
        return result
    }

    suspend fun uploadFile(bot: RemoteBot, type: String, id: String, file: File): JsonObject =
        upload(file.inputStream()) { path ->
            val response = withTimeout(300_000) {
                bot.executeAction(ActionContext.build(if (type == "group") "upload_group_file" else "upload_private_file") {
                    throwExceptions(null)
                }, buildJsonObject {
                    put(if (type == "group") "group_id" else "user_id", id)
                    put("file", path)
                    put("name", file.name)
                }.toString())
            }
            val envelope = Json.parseToJsonElement(response).jsonObject
            check(envelope["status"]?.jsonPrimitive?.content == "ok" &&
                envelope["retcode"]?.jsonPrimitive?.intOrNull == 0) {
                "QQ 未确认文件上传成功：${envelope["message"]?.jsonPrimitive?.contentOrNull ?: envelope["wording"]?.jsonPrimitive?.contentOrNull ?: "平台返回失败"}"
            }
            envelope
        }

    companion object {
        val shared: QqFileTransfer by lazy {
            val config = properties("qq_transfer")
            val host = config.getProperty("host.directory", "").trim()
            val napcat = config.getProperty("napcat.directory", "").trim()
            require(host.startsWith('/') && napcat.startsWith('/')) { "请配置 qq_transfer.properties 的两个绝对目录" }
            QqFileTransfer(File(host), napcat)
        }
    }
}

internal class QqUploadUnconfirmedException(id: String, cause: Exception) : RuntimeException(
    "上传未确认，中转文件已保留（$id）；请先核查群文件，不要直接重试。原因：${cause.message ?: cause.javaClass.simpleName}", cause
)
