package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*

internal suspend fun QqTools.send(target: QqTarget, content: JsonElement, replyTo: String? = null): JsonObject {
    gateway.requireTarget(target)
    val parts = mutableListOf<JsonElement>()
    if (replyTo != null) {
        message(target, replyTo)
        parts += obj("type" to "reply", "data" to obj("id" to replyTo))
    }
    if (content is JsonPrimitive) {
        if (content.content.isBlank()) invalid("消息文本不能为空白")
        parts += obj("type" to "text", "data" to obj("text" to content.content))
    } else {
        val segments = content.jsonArray
        if (segments.all { it.jsonObject.string("type") == "text" && it.jsonObject.string("text").isBlank() }) invalid("消息内容不能为空白")
        segments.forEach { element ->
            val part = element.jsonObject
            val type = part.string("type")
            val data = when (type) {
                "text" -> obj("text" to part.string("text"))
                "at" -> {
                    if (target.type != "group") invalid("@ 只适用于群聊")
                    obj("qq" to part.string("user_id"))
                }
                "at_all" -> {
                    if (target.type != "group") invalid("@全体成员只适用于群聊")
                    obj("qq" to "all")
                }
                "face" -> obj("id" to part.string("face_id"))
                else -> obj("file" to files.platformSource(part["source"]!!.jsonObject))
            }
            parts += obj("type" to when (type) { "at_all" -> "at"; "audio" -> "record"; else -> type }, "data" to data)
        }
    }
    val result = gateway.action(if (target.type == "group") "send_group_msg" else "send_private_msg",
        JsonObject(target.params() + obj("message" to parts)), true).jsonObject
    val id = result.optionalString("message_id")
    if (id != null) events.remember(id, target)
    events.append("message_sent", target, obj("message_id" to id, "content" to content, "reply_to" to replyTo))
    return obj("target" to target.json(), "message_id" to id, "status" to "sent", "platform_result" to result)
}

internal fun QqTools.registerMessageTools() {
    val target = field("target", targetSchema)
    val messageId = field("message_id", textSchema("平台消息 ID，可为负数，按原值传入"))
    tool("qq_send_message", "发送文本或消息段，等待平台回执；sent 不代表对方已读",
        target, field("content", contentSchema), field("reply_to", textSchema("引用的消息 ID"), true), readOnly = false) {
        send(QqTarget.parse(it.getValue("target")), it.getValue("content"), it.optionalString("reply_to"))
    }
    tool("qq_get_message", "读取指定会话中的单条消息", target, messageId) {
        message(QqTarget.parse(it.getValue("target")), it.string("message_id"))
    }
    tool("qq_list_messages", "分页读取平台可访问的历史消息，按时间从早到晚返回", target,
        field("limit", numberSchema(1, 100, 20), true), field("cursor", textSchema("历史游标"), true)) {
        history(QqTarget.parse(it.getValue("target")), it.int("limit", 20), it.optionalString("cursor"))
    }
    tool("qq_search_messages", "在有限历史范围内搜索关键词，默认最多扫描 200 条；无匹配不代表全部历史没有匹配", target,
        field("query", textSchema("搜索词")), field("scan_limit", numberSchema(1, 1000, 200), true),
        field("limit", numberSchema(1, 100, 20), true), field("cursor", textSchema("搜索游标"), true)) { search(it) }
    tool("qq_get_forward_message", "读取合并转发；forward_id 来自该会话的消息详情", target, field("forward_id", textSchema("合并转发 ID"))) {
        val destination = QqTarget.parse(it.getValue("target"))
        gateway.requireTarget(destination)
        val id = it.string("forward_id")
        if (id.toLongOrNull() != null) message(destination, id)
        else if (events.origin(id) != destination) throw QqException("MESSAGE_TARGET_MISMATCH", "请先读取原消息，确认合并转发引用属于这个会话")
        obj("items" to gateway.action("get_forward_msg", obj("message_id" to id)).jsonObject.getValue("messages"))
    }
    tool("qq_forward_messages", "转发已存在的消息；多条消息合并转发，保留原始发送者",
        field("source_target", targetSchema), field("message_ids", arraySchema(textSchema("消息 ID"), 1, 50)), target, readOnly = false) {
        val source = QqTarget.parse(it.getValue("source_target"))
        val destination = QqTarget.parse(it.getValue("target"))
        gateway.requireTarget(destination)
        val nodes = it["message_ids"]!!.jsonArray.map { element ->
            val id = element.jsonPrimitive.content
            val original = message(source, id)
            val sender = original["sender"]!!.jsonObject
            val content = original["message"] as? JsonArray ?: throw QqException("UNSUPPORTED_CAPABILITY", "后端需要配置数组消息格式才能转发")
            obj("type" to "node", "data" to obj("user_id" to sender.string("user_id"), "nickname" to sender.string("nickname"), "content" to content))
        }
        val result = gateway.action(if (destination.type == "group") "send_group_forward_msg" else "send_private_forward_msg",
            JsonObject(destination.params() + obj("messages" to nodes)), true).jsonObject
        result.optionalString("message_id")?.let { id -> events.remember(id, destination) }
        result.optionalString("res_id")?.let { id -> events.remember(id, destination) }
        events.append("message_sent", destination, obj("message_id" to result.optionalString("message_id"), "forwarded_count" to nodes.size))
        JsonObject(result + obj("target" to destination.json(), "status" to "sent"))
    }
    tool("qq_recall_message", "撤回消息，受平台撤回时限及机器人权限约束", target, messageId, readOnly = false, destructive = true) {
        message(QqTarget.parse(it.getValue("target")), it.string("message_id"))
        obj("status" to "confirmed", "platform_result" to gateway.action("delete_msg", obj("message_id" to it.string("message_id")), true))
    }
    tool("qq_set_message_reaction", "添加或移除群消息的表情回应", field("group_id", qqIdSchema), messageId,
        field("emoji_id", textSchema("QQ 表情 ID")), field("enabled", booleanSchema, true), readOnly = false, idempotent = true) {
        message(QqTarget("group", it.string("group_id")), it.string("message_id"))
        obj("status" to "confirmed", "platform_result" to gateway.action("set_msg_emoji_like",
            obj("message_id" to it.string("message_id"), "emoji_id" to it.string("emoji_id"), "set" to it.bool("enabled", true)), true))
    }
    tool("qq_mark_read", "将指定会话标记为已读", target, readOnly = false, idempotent = true) {
        val conversation = QqTarget.parse(it.getValue("target"))
        gateway.requireTarget(conversation)
        obj("status" to "confirmed", "platform_result" to gateway.action(if (conversation.type == "group") "mark_group_msg_as_read" else "mark_private_msg_as_read", conversation.params(), true))
    }
    contentTool("qq_read_attachment", "读取附件；小图片或音频直接返回 MCP 媒体内容，大文件返回资源链接", target, messageId,
        field("segment_index", numberSchema(0, 999))) { readAttachment(it) }
}
