package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*

internal fun QqTools.registerContactTools() {
    tool("qq_get_status", "查看 QQ 机器人在线状态、后端版本和 MCP 能力") { status() }
    tool("qq_list_friends", "查询好友列表，query 匹配 QQ 号、昵称或备注",
        field("query", textSchema("查询关键词"), true), *paginationFields) {
        page(filtered(gateway.action("get_friend_list").jsonArray, it.optionalString("query")), it, "friends:${it.optionalString("query")}")
    }
    tool("qq_list_groups", "查询机器人已加入的群",
        field("query", textSchema("群号或群名关键词"), true), *paginationFields) {
        page(filtered(gateway.action("get_group_list").jsonArray, it.optionalString("query")), it, "groups:${it.optionalString("query")}")
    }
    tool("qq_list_recent_conversations", "读取平台最近会话，最多查询最近 1000 个会话", *paginationFields) {
        page(gateway.action("get_recent_contact", obj("count" to 1000)).jsonArray, it, "recent")
    }
    tool("qq_get_user", "获取用户资料；查询成功不代表可以发送私聊", field("user_id", qqIdSchema)) {
        val id = it.string("user_id")
        val userNumber = id.toLongOrNull() ?: invalid("QQ 号超出平台支持范围")
        val info = gateway.action("get_stranger_info", obj("user_id" to id)).jsonObject
        val bot = gateway.bot()
        val relationship = when {
            bot.getFriend(userNumber) != null -> "friend"
            bot.getStranger(userNumber) != null -> "stranger"
            else -> "unknown"
        }
        JsonObject(info + obj("relationship" to relationship))
    }
    tool("qq_get_group", "获取群资料和机器人群身份", field("group_id", qqIdSchema)) {
        val id = it.string("group_id")
        val role = gateway.groupRole(id)
        JsonObject(gateway.action("get_group_info", obj("group_id" to id)).jsonObject + obj("self_role" to role))
    }
    tool("qq_list_group_members", "查询群成员，可按身份和关键词筛选",
        field("group_id", qqIdSchema), field("query", textSchema("查询关键词"), true), field("role", enumSchema("member", "admin", "owner"), true), *paginationFields) {
        val groupId = it.string("group_id")
        gateway.groupRole(groupId)
        val members = gateway.action("get_group_member_list", obj("group_id" to groupId)).jsonArray
            .filter { member -> it.optionalString("role") == null || member.jsonObject.optionalString("role") == it.optionalString("role") }
        page(filtered(members, it.optionalString("query")), it, "members:$groupId:${it.optionalString("role")}:${it.optionalString("query")}")
    }
    tool("qq_get_group_member", "获取单个群成员的资料、身份和禁言状态", field("group_id", qqIdSchema), field("user_id", qqIdSchema)) {
        gateway.groupRole(it.string("group_id"))
        gateway.action("get_group_member_info", it).jsonObject
    }
}
