package me.xiaozhangup.bot.mcp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import top.mrxiaom.overflow.action.ActionContext
import top.mrxiaom.overflow.contact.RemoteBot
import java.util.Base64

internal data class QqTarget(val type: String, val id: String) {
    fun json() = obj("type" to type, "id" to id)
    fun params() = obj((if (type == "group") "group_id" else "user_id") to id)
    fun key() = "$type:$id"
    companion object {
        fun parse(value: JsonElement): QqTarget {
            validate(targetSchema, value, "target")
            val target = value.jsonObject
            return QqTarget(target.string("type"), target.string("id"))
        }
    }
}

internal class QqGateway(private val botProvider: () -> Bot?, private val config: McpConfig) {
    fun currentBot(): Bot? = botProvider()
    fun bot(): Bot = botProvider()?.takeIf { it.isOnline }
        ?: throw QqException("BOT_OFFLINE", "QQ 机器人尚未上线")

    suspend fun action(name: String, params: JsonObject = obj(), write: Boolean = false, timeoutMillis: Long = config.timeoutMillis): JsonElement {
        val remote = bot() as RemoteBot
        if (remote.noPlatform) throw QqException("UNSUPPORTED_CAPABILITY", "Overflow 已关闭平台接口调用")
        val response = try {
            withTimeout(timeoutMillis) {
                remote.executeAction(ActionContext.build(name) { throwExceptions(null) }, params.toString())
            }
        } catch (_: TimeoutCancellationException) {
            throw QqException(if (write) "OUTCOME_UNKNOWN" else "PLATFORM_TIMEOUT",
                if (write) "平台回执超时，操作可能已执行，请先核实结果，不要直接重复调用" else "平台查询超时")
        } catch (_: Exception) {
            throw QqException(if (write) "OUTCOME_UNKNOWN" else "PLATFORM_ERROR",
                if (write) "未取得有效平台回执，操作结果未知" else "平台连接或查询失败")
        }
        val envelope = try { Json.parseToJsonElement(response).jsonObject } catch (_: Exception) {
            throw QqException(if (write) "OUTCOME_UNKNOWN" else "PLATFORM_ERROR", "平台没有返回有效 JSON 回执")
        }
        val code = envelope["retcode"]?.jsonPrimitive?.intOrNull
        if (envelope.optionalString("status") != "ok" || code != 0) {
            val message = envelope.optionalString("message")?.takeIf { it.isNotBlank() }
                ?: envelope.optionalString("wording")?.takeIf { it.isNotBlank() } ?: "平台未确认操作成功"
            val error = when {
                code == 1404 -> "UNSUPPORTED_CAPABILITY"
                message.contains("权限") || message.contains("permission", true) -> "PERMISSION_DENIED"
                name in setOf("get_msg", "delete_msg", "get_group_msg_history", "get_friend_msg_history") && (message.contains("不存在") || message.contains("撤回") || message.contains("not found", true)) -> "MESSAGE_NOT_FOUND"
                else -> "PLATFORM_ERROR"
            }
            throw QqException(error, message.take(500), code)
        }
        return normalizeIds(envelope["data"] ?: JsonNull)
    }

    fun requireTarget(target: QqTarget) {
        val bot = bot()
        val id = target.id.toLongOrNull() ?: invalid("QQ 号或群号超出平台支持范围")
        val found = if (target.type == "group") bot.getGroup(id) != null else bot.getFriend(id) != null || bot.getStranger(id) != null
        if (!found) throw QqException("TARGET_NOT_FOUND", "机器人联系人中没有 ${target.key()}")
    }

    fun groupRole(groupId: String): String {
        val group = bot().getGroup(groupId.toLongOrNull() ?: invalid("群号超出平台支持范围"))
            ?: throw QqException("TARGET_NOT_FOUND", "机器人不在这个群中")
        return when (group.botPermission) {
            net.mamoe.mirai.contact.MemberPermission.OWNER -> "owner"
            net.mamoe.mirai.contact.MemberPermission.ADMINISTRATOR -> "admin"
            net.mamoe.mirai.contact.MemberPermission.MEMBER -> "member"
        }
    }

    fun requireGroupRole(groupId: String, owner: Boolean = false) {
        val role = groupRole(groupId)
        if (role == "member" || (owner && role != "owner")) throw QqException("PERMISSION_DENIED", "机器人缺少所需群管理权限")
    }
}

/** Cursors are scoped to the original query; collection pagination reads a live platform list. */
internal fun encodeCursor(scope: String, value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(obj("scope" to scope, "value" to value).toString().toByteArray(Charsets.UTF_8))

internal fun decodeCursor(cursor: String?, scope: String): String? {
    if (cursor == null) return null
    val decoded = try { Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)).jsonObject }
        catch (_: Exception) { invalid("分页游标无效") }
    if (decoded.optionalString("scope") != scope) invalid("分页游标不属于当前查询")
    return decoded.optionalString("value") ?: invalid("分页游标缺少位置")
}

internal fun page(items: List<JsonElement>, args: JsonObject, scope: String, defaultLimit: Int = 50): JsonObject {
    val offset = decodeCursor(args.optionalString("cursor"), scope)?.toIntOrNull() ?: if (args["cursor"] == null) 0 else invalid("分页位置无效")
    if (offset !in 0..items.size) invalid("分页位置超出当前列表范围，请重新查询")
    val limit = args.int("limit", defaultLimit)
    val selected = items.drop(offset).take(limit)
    val next = offset + selected.size
    return obj("items" to selected, "next_cursor" to if (next < items.size) encodeCursor(scope, next.toString()) else null)
}

internal fun filtered(items: List<JsonElement>, query: String?): List<JsonElement> =
    if (query.isNullOrBlank()) items else items.filter { it.toString().contains(query, true) }
