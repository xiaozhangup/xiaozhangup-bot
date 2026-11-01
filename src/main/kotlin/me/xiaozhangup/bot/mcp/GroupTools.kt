package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*

internal fun QqTools.registerGroupTools() {
    val group = field("group_id", qqIdSchema)
    val user = field("user_id", qqIdSchema)
    fun modify(name: String, description: String, action: String, vararg fields: Field,
        owner: Boolean = false, destructive: Boolean = false, idempotent: Boolean = !destructive, params: (JsonObject) -> JsonObject) {
        tool(name, description, group, *fields, readOnly = false, destructive = destructive, idempotent = idempotent) {
            gateway.requireGroupRole(it.string("group_id"), owner)
            val result = gateway.action(action, params(it), write = true)
            obj("status" to "confirmed", "platform_result" to result)
        }
    }
    modify("qq_set_group_name", "修改群名", "set_group_name", field("name", textSchema("新的群名称"))) {
        obj("group_id" to it.string("group_id"), "group_name" to it.string("name"))
    }
    tool("qq_set_group_card", "修改群名片；普通成员只能修改机器人自己的名片", group, user, field("card", textSchema("群名片，空字符串表示清空", true)), readOnly = false, idempotent = true) {
        val role = gateway.groupRole(it.string("group_id"))
        if (role == "member" && it.string("user_id") != gateway.bot().id.toString()) throw QqException("PERMISSION_DENIED", "机器人只能修改自己的群名片")
        obj("status" to "confirmed", "platform_result" to gateway.action("set_group_card", it, true))
    }
    modify("qq_set_group_special_title", "设置成员专属头衔，需要群主权限", "set_group_special_title", user, field("title", textSchema("头衔，空字符串表示清空", true)), owner = true) {
        obj("group_id" to it.string("group_id"), "user_id" to it.string("user_id"), "special_title" to it.string("title"))
    }
    modify("qq_mute_member", "禁言群成员，duration_seconds=0 表示解除禁言", "set_group_ban", user, field("duration_seconds", numberSchema(0, 2592000))) {
        obj("group_id" to it.string("group_id"), "user_id" to it.string("user_id"), "duration" to it.int("duration_seconds", 0))
    }
    modify("qq_set_group_mute", "开启或关闭全员禁言", "set_group_whole_ban", field("enabled", booleanSchema)) {
        obj("group_id" to it.string("group_id"), "enable" to it.bool("enabled"))
    }
    modify("qq_kick_member", "移除群成员", "set_group_kick", user, field("reject_future_requests", booleanSchema, true), destructive = true) {
        obj("group_id" to it.string("group_id"), "user_id" to it.string("user_id"), "reject_add_request" to it.bool("reject_future_requests"))
    }
    modify("qq_set_group_admin", "设置或取消管理员，需要群主权限", "set_group_admin", user, field("enabled", booleanSchema), owner = true) {
        obj("group_id" to it.string("group_id"), "user_id" to it.string("user_id"), "enable" to it.bool("enabled"))
    }
    tool("qq_list_group_notices", "读取群公告", group) {
        gateway.groupRole(it.string("group_id"))
        val result = gateway.action("_get_group_notice", it)
        obj("items" to result)
    }
    modify("qq_publish_group_notice", "发布群公告，成功回执可能没有公告 ID", "_send_group_notice", field("content", textSchema("公告内容")), field("image", sourceSchema, true), idempotent = false) {
        JsonObject(obj("group_id" to it.string("group_id"), "content" to it.string("content")) +
            (it["image"]?.let { image -> obj("image" to files.platformSource(image.jsonObject)) } ?: obj()))
    }
    modify("qq_delete_group_notice", "删除群公告", "_del_group_notice", field("notice_id", textSchema("公告 ID")), destructive = true) {
        obj("group_id" to it.string("group_id"), "notice_id" to it.string("notice_id"))
    }
    tool("qq_leave_group", "退出群聊；dismiss=true 表示解散群，需要群主权限", group, field("dismiss", booleanSchema, true), readOnly = false, destructive = true) {
        if (it.bool("dismiss")) gateway.requireGroupRole(it.string("group_id"), true) else gateway.groupRole(it.string("group_id"))
        obj("status" to "confirmed", "platform_result" to gateway.action("set_group_leave", obj("group_id" to it.string("group_id"), "is_dismiss" to it.bool("dismiss")), true))
    }
}
