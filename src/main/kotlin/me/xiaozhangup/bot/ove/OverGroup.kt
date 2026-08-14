package me.xiaozhangup.bot.ove

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.GroupFile
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.util.asMessageChain
import top.mrxiaom.overflow.action.ActionContext
import top.mrxiaom.overflow.contact.RemoteBot
import java.io.File
import java.util.Base64

class OverGroup(
    val oveGroup: net.mamoe.mirai.contact.Group
) : Group(
    oveGroup.name,
    oveGroup.id.toString()
) {
    override fun sendMessage(message: String) {
        oveGroup.launch {
            oveGroup.sendMessage(message)
        }
    }

    override fun sendMessageWithIds(message: String): List<Int> {
        val receipt = runBlocking { oveGroup.sendMessage(message) }
        return receipt.source.ids.toList()
    }

    override fun sendMessage(vararg messages: MessageComponent) {
        oveGroup.launch {
            oveGroup.sendMessage(asMessageChain(*messages))
        }
    }

    override fun getMemberName(memberId: String): String? {
        val id = memberId.toLongOrNull() ?: return null
        val member = oveGroup[id] ?: return null
        return member.nameCard.ifBlank { member.nick }
    }

    override fun setBotNickname(nickname: String) {
        oveGroup.botAsMember.nameCard = nickname
    }

    override suspend fun sendFile(file: File) {
        executeAction("upload_group_file", buildJsonObject {
            put("group_id", oveGroup.id.toString())
            put("file", "base64://${Base64.getEncoder().encodeToString(file.readBytes())}")
            put("name", file.name)
        })
    }

    override suspend fun listFiles(): List<GroupFile> {
        val root = executeAction("get_group_root_files", buildJsonObject {
            put("group_id", oveGroup.id.toString())
            put("file_count", 10_000)
        }).jsonObject
        val files = parseFiles(root)
        root["folders"]?.jsonArray.orEmpty().forEach { folderElement ->
            val folder = folderElement.jsonObject
            val folderId = folder["folder_id"]?.jsonPrimitive?.content ?: return@forEach
            val folderName = folder["folder_name"]?.jsonPrimitive?.content ?: return@forEach
            val contents = executeAction("get_group_files_by_folder", buildJsonObject {
                put("group_id", oveGroup.id.toString())
                put("folder_id", folderId)
                put("file_count", 10_000)
            }).jsonObject
            files += parseFiles(contents, "$folderName/")
        }
        return files
    }

    override suspend fun getFileUrl(fileId: String): String {
        val data = executeAction("get_group_file_url", buildJsonObject {
            put("group_id", oveGroup.id.toString())
            put("file_id", fileId)
        }).jsonObject
        return data["url"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("QQ 未返回群文件下载地址")
    }

    private suspend fun executeAction(action: String, params: JsonObject): JsonElement {
        val response = (oveGroup.bot as RemoteBot).executeAction(
            ActionContext.build(action) { throwExceptions(true) },
            params.toString()
        )
        return Json.parseToJsonElement(response).jsonObject["data"]
            ?: throw IllegalStateException("QQ 接口未返回 data：$action")
    }

    private fun parseFiles(data: JsonObject, prefix: String = ""): MutableList<GroupFile> {
        return data["files"]?.jsonArray.orEmpty().mapNotNullTo(mutableListOf()) { element ->
            val file = element.jsonObject
            val id = file["file_id"]?.jsonPrimitive?.content ?: return@mapNotNullTo null
            val name = file["file_name"]?.jsonPrimitive?.content ?: return@mapNotNullTo null
            GroupFile(id, prefix + name, groupFileTimestamp(file))
        }
    }
}

internal fun groupFileTimestamp(file: JsonObject): Long {
    return file["upload_time"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0 }
        ?: file["modify_time"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: 0
}
