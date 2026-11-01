package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*

internal fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) })
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    else -> error("Unsupported JSON value: ${value.javaClass.name}")
}

internal fun obj(vararg fields: Pair<String, Any?>): JsonObject =
    JsonObject(fields.associate { it.first to jsonValue(it.second) })

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(name: String, default: Int): Int = get(name)?.jsonPrimitive?.int ?: default
internal fun JsonObject.bool(name: String, default: Boolean = false): Boolean = get(name)?.jsonPrimitive?.boolean ?: default

internal class QqException(val code: String, message: String, val backendCode: Int? = null) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw QqException("INVALID_ARGUMENT", message)

private val idFields = setOf("user_id", "group_id", "message_id", "self_id", "operator_id", "sender_id", "file_id", "folder_id", "message_seq")

internal fun normalizeIds(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> JsonArray(element.map(::normalizeIds))
    is JsonObject -> JsonObject(element.mapValues { (name, value) ->
        if (name in idFields && value is JsonPrimitive && value != JsonNull)
            JsonPrimitive(value.content)
        else normalizeIds(value)
    })
    else -> element
}
