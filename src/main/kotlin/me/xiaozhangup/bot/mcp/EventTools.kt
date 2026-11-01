package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*

internal fun QqTools.registerEventTools() {
    tool("qq_poll_events", "独立游标读取最近事件，可等待最多 30 秒；过期游标不会静默跳过事件",
        field("cursor", textSchema("上次返回的事件游标"), true),
        field("types", arraySchema(enumSchema("message", "message_sent", "message_recall", "message_reaction", "member_join", "member_leave", "bot_status", "request", "request_updated"), 1, 9), true),
        field("target", targetSchema, true), field("limit", numberSchema(1, 100, 50), true), field("wait_seconds", numberSchema(0, 30, 0), true)) {
        events.poll(it)
    }
    tool("qq_list_requests", "读取服务启动后实际收到的好友申请、入群申请和群邀请；仅保留有限事件窗口",
        field("type", enumSchema("friend", "group"), true), field("group_id", qqIdSchema, true), *paginationFields) { events.listRequests(it) }
    tool("qq_handle_friend_request", "同意或拒绝已记录的好友申请", field("request_id", textSchema("申请列表中的 request_id")),
        field("approve", booleanSchema), field("remark", textSchema("同意后设置好友备注", true), true), readOnly = false) {
        val result = events.handleRequest(it, "friend")
        if (it.bool("approve") && it["remark"] != null) {
            try { gateway.action("set_friend_remark", obj("user_id" to result.string("user_id"), "remark" to it.string("remark")), true) }
            catch (e: QqException) { throw QqException("PARTIAL_SUCCESS", "好友申请已同意，但备注未确认设置成功：${e.message}") }
        }
        result
    }
    tool("qq_handle_group_request", "同意或拒绝已记录的入群申请或群邀请", field("request_id", textSchema("申请列表中的 request_id")),
        field("approve", booleanSchema), field("reason", textSchema("拒绝理由", true), true), readOnly = false) { events.handleRequest(it, "group") }
}
