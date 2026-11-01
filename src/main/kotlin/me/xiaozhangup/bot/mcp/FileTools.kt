package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*
import me.xiaozhangup.bot.client.QqFileTransfer
import me.xiaozhangup.bot.client.QqUploadUnconfirmedException

internal fun QqTools.registerFileTools() {
    val group = field("group_id", qqIdSchema)
    val fileId = field("file_id", textSchema("列表返回的群文件 ID"))
    val busid = field("busid", numberSchema(0, Int.MAX_VALUE), true)
    tool("qq_send_file", "发送服务端共享目录文件或 URL 文件；folder_id 只适用于群文件",
        field("target", targetSchema), field("source", sourceSchema), field("name", textSchema("发送时使用的文件名"), true),
        field("folder_id", textSchema("群文件目标目录 ID"), true), readOnly = false) {
        val target = QqTarget.parse(it.getValue("target"))
        gateway.requireTarget(target)
        if (target.type == "private" && it["folder_id"] != null) invalid("私聊文件不支持 folder_id")
        val (bytes, sourceName) = files.sourceBytes(it["source"]!!.jsonObject)
        val name = it.optionalString("name") ?: sourceName
        val result = try {
            QqFileTransfer.shared.upload(bytes.inputStream()) { path ->
                val params = JsonObject(target.params() + obj("file" to path, "name" to name) +
                    (it.optionalString("folder_id")?.let { folder -> obj("folder" to folder) } ?: obj()))
                gateway.action(if (target.type == "group") "upload_group_file" else "upload_private_file", params, true, timeoutMillis = 300_000)
            }
        } catch (e: QqUploadUnconfirmedException) {
            throw QqException("OUTCOME_UNKNOWN", e.message!!)
        }
        obj("status" to "uploaded", "target" to target.json(), "name" to name, "size" to bytes.size, "platform_result" to result)
    }
    tool("qq_list_group_files", "读取一个群文件目录，保留文件与子目录结构；最多查询 10000 个条目", group,
        field("folder_id", textSchema("目录 ID，省略表示根目录"), true), field("query", textSchema("文件或目录关键词"), true), *paginationFields) {
        val id = it.string("group_id")
        gateway.groupRole(id)
        val folder = it.optionalString("folder_id")
        val params = JsonObject(obj("group_id" to id, "file_count" to 10000) + (folder?.let { f -> obj("folder_id" to f) } ?: obj()))
        val result = gateway.action(if (folder == null) "get_group_root_files" else "get_group_files_by_folder", params).jsonObject
        val entries = result["folders"]!!.jsonArray.map { item -> JsonObject(item.jsonObject + obj("kind" to "folder", "parent_folder_id" to (folder ?: "/"))) } +
            result["files"]!!.jsonArray.map { item -> JsonObject(item.jsonObject + obj("kind" to "file", "parent_folder_id" to (folder ?: "/"))) }
        JsonObject(page(filtered(entries, it.optionalString("query")), it, "files:$id:$folder:${it.optionalString("query")}") +
            obj("coverage" to obj("maximum_entries" to 10000, "possibly_truncated" to (entries.size >= 10000))))
    }
    tool("qq_get_group_file_url", "获取群文件临时下载链接，链接有效期由平台决定", group, fileId, busid) {
        gateway.groupRole(it.string("group_id"))
        gateway.action("get_group_file_url", it).jsonObject
    }
    tool("qq_download_group_file", "下载群文件到服务端配置的下载目录；不会覆盖已有文件", group, fileId, busid,
        field("directory", textSchema("下载根目录内的服务端目录"), true), field("name", textSchema("保存文件名，省略时自动生成"), true), readOnly = false) {
        gateway.groupRole(it.string("group_id"))
        val params = JsonObject(it.filterKeys { key -> key in setOf("group_id", "file_id", "busid") })
        val url = gateway.action("get_group_file_url", params).jsonObject.optionalString("url")
            ?: throw QqException("ATTACHMENT_UNAVAILABLE", "平台未返回群文件下载链接")
        val (bytes, _) = files.fetch(url)
        files.save(bytes, it.optionalString("directory"), it.optionalString("name") ?: "qq-${java.util.UUID.randomUUID()}.bin")
    }
    fun modify(name: String, description: String, action: String, vararg fields: Field, destructive: Boolean = false,
        params: (JsonObject) -> JsonObject = { it }) {
        tool(name, description, group, *fields, readOnly = false, destructive = destructive) {
            gateway.groupRole(it.string("group_id"))
            obj("status" to "confirmed", "platform_result" to gateway.action(action, params(it), true))
        }
    }
    modify("qq_create_group_folder", "创建群文件目录", "create_group_file_folder", field("name", textSchema("目录名称")))
    modify("qq_move_group_file", "移动群文件，需要列表返回的来源目录 ID", "move_group_file", fileId,
        field("parent_folder_id", textSchema("当前父目录 ID，根目录为 /")), field("destination_folder_id", textSchema("目标父目录 ID，根目录为 /"))) {
        obj("group_id" to it.string("group_id"), "file_id" to it.string("file_id"), "current_parent_directory" to it.string("parent_folder_id"), "target_parent_directory" to it.string("destination_folder_id"))
    }
    modify("qq_rename_group_file", "重命名群文件", "rename_group_file", fileId,
        field("parent_folder_id", textSchema("父目录 ID，根目录为 /")), field("name", textSchema("新文件名"))) {
        obj("group_id" to it.string("group_id"), "file_id" to it.string("file_id"), "current_parent_directory" to it.string("parent_folder_id"), "new_name" to it.string("name"))
    }
    tool("qq_delete_group_file", "删除群文件；普通成员只能删除自己上传的文件，平台验证权限", group, fileId, busid, readOnly = false, destructive = true) {
        gateway.groupRole(it.string("group_id"))
        obj("status" to "confirmed", "platform_result" to gateway.action("delete_group_file", it, true))
    }
    modify("qq_delete_group_folder", "删除群文件目录，按平台规则处理目录内容", "delete_group_folder", field("folder_id", textSchema("目录 ID")), destructive = true)
}
