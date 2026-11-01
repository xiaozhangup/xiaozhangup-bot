package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*
import java.util.UUID

internal data class ToolData(val data: JsonObject, val content: List<JsonObject> = emptyList())
internal data class QqTool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean,
    val destructive: Boolean, val idempotent: Boolean, val handler: suspend (JsonObject) -> ToolData) {
    fun definition() = obj("name" to name, "description" to description, "inputSchema" to schema,
        "outputSchema" to obj("type" to "object", "properties" to obj(
            "ok" to booleanSchema, "data" to obj("type" to "object"),
            "error" to obj("type" to "object", "properties" to obj("code" to textSchema("错误码"), "message" to textSchema("错误说明"), "backend_code" to obj("type" to listOf("integer", "null"))), "required" to listOf("code", "message"))
        ), "required" to listOf("ok")),
        "annotations" to obj("readOnlyHint" to readOnly, "destructiveHint" to destructive,
            "idempotentHint" to idempotent, "openWorldHint" to true))
}

internal class QqTools(val gateway: QqGateway, val files: QqFiles, val events: QqEventStore) {
    val tools = LinkedHashMap<String, QqTool>()
    private data class Attachment(val target: QqTarget, val url: String, val name: String)
    private val attachments = LinkedHashMap<String, Attachment>()

    init {
        registerContactTools()
        registerMessageTools()
        registerFileTools()
        registerGroupTools()
        registerEventTools()
    }

    fun tool(name: String, description: String, vararg fields: Field, readOnly: Boolean = true,
        destructive: Boolean = false, idempotent: Boolean = readOnly, handler: suspend (JsonObject) -> JsonObject) {
        contentTool(name, description, *fields, readOnly = readOnly, destructive = destructive, idempotent = idempotent) { ToolData(handler(it)) }
    }

    fun contentTool(name: String, description: String, vararg fields: Field, readOnly: Boolean = true,
        destructive: Boolean = false, idempotent: Boolean = readOnly, handler: suspend (JsonObject) -> ToolData) {
        check(name !in tools)
        tools[name] = QqTool(name, description, objectSchema(*fields), readOnly, destructive, idempotent, handler)
    }

    suspend fun call(name: String, args: JsonObject): JsonObject {
        val tool = tools[name] ?: throw QqException("UNKNOWN_TOOL", "工具不存在：$name")
        return try {
            validate(tool.schema, args)
            val output = tool.handler(args)
            val envelope = obj("ok" to true, "data" to output.data)
            obj("isError" to false, "structuredContent" to envelope,
                "content" to listOf(obj("type" to "text", "text" to envelope.toString())) + output.content)
        } catch (e: QqException) {
            failure(e)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            failure(QqException("FILE_ALREADY_EXISTS", "目标文件已存在，请使用其他文件名"))
        } catch (_: java.io.IOException) {
            failure(QqException("FILE_IO_ERROR", "本地文件读写或网络下载失败"))
        }
    }

    private fun failure(error: QqException): JsonObject {
        val envelope = obj("ok" to false, "error" to obj("code" to error.code, "message" to error.message, "backend_code" to error.backendCode))
        return obj("isError" to true, "structuredContent" to envelope, "content" to listOf(obj("type" to "text", "text" to envelope.toString())))
    }

    fun capabilities() = obj("tools" to tools.keys.toList(), "message_types" to listOf("text", "at", "at_all", "face", "image", "audio", "video"),
        "files" to files.capabilities(), "history_source" to "platform", "events_source" to "received_events",
        "platform_support" to "Backend API availability and account permissions are checked when called")

    suspend fun status(): JsonObject {
        val bot = gateway.currentBot()
        return obj("user_id" to bot?.id?.toString(), "nickname" to bot?.nick, "online" to (bot?.isOnline == true),
            "backend" to if (bot?.isOnline == true) gateway.action("get_version_info") else null,
            "capabilities" to capabilities())
    }

    suspend fun message(target: QqTarget, id: String): JsonObject {
        gateway.requireTarget(target)
        val message = gateway.action("get_msg", obj("message_id" to id)).jsonObject
        val recordedTarget = events.origin(id)
        val group = message.optionalString("group_id")
        val messageType = message.string("message_type")
        val sender = message.optionalString("user_id") ?: message["sender"]?.jsonObject?.optionalString("user_id")
        val matches = if (target.type == "group") messageType == "group" && group == target.id
            else messageType == "private" && (recordedTarget == target || sender == target.id)
        if (!matches) throw QqException("MESSAGE_TARGET_MISMATCH", "无法确认消息属于指定会话；机器人发出的私聊消息请先从该会话历史中读取")
        events.remember(id, target)
        rememberForwardReferences(target, message["message"])
        return JsonObject(message + ("target" to target.json()))
    }

    fun rememberMessages(target: QqTarget, messages: List<JsonObject>) {
        messages.forEach {
            it.optionalString("message_id")?.let { id -> events.remember(id, target) }
            rememberForwardReferences(target, it["message"])
        }
    }

    private fun rememberForwardReferences(target: QqTarget, content: JsonElement?) {
        (content as? JsonArray)?.forEach { part ->
            val segment = part.jsonObject
            if (segment.optionalString("type") == "forward") segment["data"]?.jsonObject?.optionalString("id")?.let { events.remember(it, target) }
        }
    }

    suspend fun history(target: QqTarget, limit: Int, cursor: String? = null, scope: String = "history:${target.key()}"): JsonObject {
        gateway.requireTarget(target)
        val anchor = decodeCursor(cursor, scope)
        val params = JsonObject(target.params() + obj("count" to limit + if (anchor == null) 0 else 1, "reverse_order" to true,
            "reverseOrder" to true, "parse_mult_msg" to true, "disable_get_url" to false) +
            if (anchor == null) emptyMap() else mapOf("message_seq" to JsonPrimitive(anchor)))
        val raw = try {
            gateway.action(if (target.type == "group") "get_group_msg_history" else "get_friend_msg_history", params).jsonObject["messages"]!!.jsonArray
        } catch (e: QqException) {
            if (e.code != "MESSAGE_NOT_FOUND") throw e
            if (anchor != null) throw QqException("HISTORY_CURSOR_EXPIRED", "历史消息锚点已不可访问，请重新读取最近消息")
            JsonArray(emptyList())
        }
        val selected = raw.map { it.jsonObject }.filter { it.optionalString("message_id") != anchor }
            .sortedBy { it["time"]?.jsonPrimitive?.longOrNull ?: 0L }.takeLast(limit)
        rememberMessages(target, selected)
        val oldest = selected.firstOrNull()?.optionalString("message_id")
        return obj("items" to selected.map { JsonObject(it + ("target" to target.json())) },
            "next_cursor" to if (selected.size == limit && oldest != null && oldest != anchor) encodeCursor(scope, oldest) else null,
            "coverage" to obj("source" to "platform", "complete" to false, "returned" to selected.size))
    }

    suspend fun search(args: JsonObject): JsonObject {
        val target = QqTarget.parse(args.getValue("target"))
        val query = args.string("query")
        val scope = "search:${target.key()}:$query"
        val scanLimit = args.int("scan_limit", 200)
        val limit = args.int("limit", 20)
        var cursor = args.optionalString("cursor")
        var scanned = 0
        val matches = mutableListOf<JsonElement>()
        while (scanned < scanLimit && matches.size < limit) {
            val batch = history(target, minOf(100, scanLimit - scanned), cursor, scope)
            val items = batch["items"]!!.jsonArray
            for (item in items.reversed()) {
                scanned++
                val message = item.jsonObject
                if ((message.optionalString("raw_message") ?: message["message"].toString()).contains(query, true)) matches += item
                cursor = encodeCursor(scope, message.string("message_id"))
                if (matches.size == limit) break
            }
            if (matches.size == limit) break
            cursor = batch.optionalString("next_cursor")
            if (cursor == null || items.isEmpty()) break
        }
        return obj("items" to matches.reversed(), "next_cursor" to cursor,
            "coverage" to obj("source" to "platform", "complete" to false, "scanned" to scanned, "scan_limit" to scanLimit))
    }

    private fun storeAttachment(target: QqTarget, url: String, name: String): String = synchronized(attachments) {
        val id = UUID.randomUUID().toString()
        attachments[id] = Attachment(target, url, name)
        while (attachments.size > 128) attachments.remove(attachments.keys.first())
        "qq://attachments/$id"
    }

    suspend fun readAttachment(args: JsonObject): ToolData {
        val target = QqTarget.parse(args.getValue("target"))
        val message = message(target, args.string("message_id"))
        val segments = message["message"] as? JsonArray ?: throw QqException("UNSUPPORTED_CAPABILITY", "后端需要配置为数组消息格式才能读取附件")
        val segment = segments.getOrNull(args.int("segment_index", 0))?.jsonObject ?: invalid("附件消息段索引超出范围")
        val type = segment.string("type")
        val data = segment["data"]!!.jsonObject
        val url = data.optionalString("url") ?: when (type) {
            "image", "record" -> gateway.action(if (type == "image") "get_image" else "get_record",
                obj("file" to data.string("file"), *if (type == "record") arrayOf("out_format" to "mp3") else emptyArray())).jsonObject.optionalString("url")
            "file" -> gateway.action(if (target.type == "group") "get_group_file_url" else "get_private_file_url",
                JsonObject(obj("file_id" to (data.optionalString("file_id") ?: data.string("file"))) + if (target.type == "group") target.params() else obj())).jsonObject.optionalString("url")
            else -> null
        } ?: throw QqException("ATTACHMENT_UNAVAILABLE", "平台未提供可访问的附件 URL")
        val name = data.optionalString("name") ?: "attachment"
        val uri = storeAttachment(target, url, name)
        val (bytes, mime) = files.fetch(url)
        val media = files.inlineContent(bytes, mime)
        return ToolData(obj("uri" to uri, "url" to url, "name" to name, "mime_type" to mime, "size" to bytes.size),
            listOf(media ?: obj("type" to "resource_link", "uri" to uri, "name" to name, "mimeType" to mime)))
    }

    suspend fun attachmentResource(id: String): JsonObject {
        val attachment = synchronized(attachments) { attachments[id] } ?: throw QqException("ATTACHMENT_UNAVAILABLE", "附件引用已失效，请重新读取原消息附件")
        gateway.requireTarget(attachment.target)
        val (bytes, mime) = files.fetch(attachment.url)
        val uri = "qq://attachments/$id"
        return if (bytes.size <= files.capabilities().int("inline_bytes", 1048576))
            obj("uri" to uri, "mimeType" to mime, "blob" to java.util.Base64.getEncoder().encodeToString(bytes))
        else obj("uri" to uri, "mimeType" to "application/json", "text" to obj("name" to attachment.name, "url" to attachment.url, "mime_type" to mime, "size" to bytes.size).toString())
    }
}
