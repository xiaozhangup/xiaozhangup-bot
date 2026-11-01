package me.xiaozhangup.bot.mcp

import kotlinx.serialization.json.*

internal data class Field(val name: String, val schema: JsonObject, val required: Boolean = true)
internal fun field(name: String, schema: JsonObject, optional: Boolean = false) = Field(name, schema, !optional)
internal fun textSchema(description: String, empty: Boolean = false) = obj("type" to "string", "description" to description, "minLength" to if (empty) 0 else 1)
internal fun enumSchema(vararg values: String) = obj("type" to "string", "enum" to values.toList())
internal fun numberSchema(min: Int, max: Int, default: Int? = null) = obj("type" to "integer", "minimum" to min, "maximum" to max).let {
    if (default == null) it else JsonObject(it + ("default" to JsonPrimitive(default)))
}
internal val booleanSchema = obj("type" to "boolean")
internal fun arraySchema(items: JsonObject, min: Int = 0, max: Int = 100) = obj("type" to "array", "items" to items, "minItems" to min, "maxItems" to max)
internal fun objectSchema(vararg fields: Field) = obj(
    "type" to "object", "properties" to JsonObject(fields.associate { it.name to it.schema }),
    "required" to fields.filter { it.required }.map { it.name }, "additionalProperties" to false
)
internal fun unionSchema(vararg alternatives: JsonObject) = obj("oneOf" to alternatives.toList())
internal val qqIdSchema = obj("type" to "string", "pattern" to "^[1-9][0-9]*$", "description" to "QQ 号或群号，使用字符串")
internal val targetSchema = objectSchema(field("type", enumSchema("group", "private")), field("id", qqIdSchema))
internal val sourceSchema = unionSchema(
    objectSchema(field("type", enumSchema("path")), field("path", textSchema("服务端共享目录中的文件路径"))),
    objectSchema(field("type", enumSchema("url")), field("url", textSchema("HTTP 或 HTTPS 地址")))
)
internal val messagePartSchema = unionSchema(
    objectSchema(field("type", enumSchema("text")), field("text", textSchema("文本内容", true))),
    objectSchema(field("type", enumSchema("at")), field("user_id", qqIdSchema)),
    objectSchema(field("type", enumSchema("at_all"))),
    objectSchema(field("type", enumSchema("face")), field("face_id", textSchema("QQ 表情 ID"))),
    objectSchema(field("type", enumSchema("image", "audio", "video")), field("source", sourceSchema))
)
internal val contentSchema = unionSchema(textSchema("纯文本消息"), arraySchema(messagePartSchema, 1))
internal val paginationFields = arrayOf(
    field("limit", numberSchema(1, 100, 50), true), field("cursor", textSchema("上次返回的分页游标"), true)
)

/** Only the schema vocabulary generated above is accepted at the public input boundary. */
internal fun validate(schema: JsonObject, value: JsonElement, path: String = "arguments") {
    schema["oneOf"]?.jsonArray?.let { alternatives ->
        val matches = alternatives.count { candidate ->
            try { validate(candidate.jsonObject, value, path); true } catch (_: QqException) { false }
        }
        if (matches != 1) invalid("$path 不符合允许的参数结构")
        return
    }
    when (schema.optionalString("type")) {
        "object" -> {
            val objectValue = value as? JsonObject ?: invalid("$path 必须是对象")
            val properties = schema["properties"]!!.jsonObject
            schema["required"]!!.jsonArray.forEach { if (it.jsonPrimitive.content !in objectValue) invalid("$path 缺少 ${it.jsonPrimitive.content}") }
            objectValue.forEach { (name, item) -> validate(properties[name]?.jsonObject ?: invalid("$path 包含未知参数 $name"), item, "$path.$name") }
        }
        "array" -> {
            val array = value as? JsonArray ?: invalid("$path 必须是数组")
            if (array.size !in schema.int("minItems", 0)..schema.int("maxItems", 100)) invalid("$path 元素数量超出范围")
            array.forEachIndexed { i, item -> validate(schema["items"]!!.jsonObject, item, "$path[$i]") }
        }
        "string" -> {
            val primitive = value as? JsonPrimitive ?: invalid("$path 必须是字符串")
            if (!primitive.isString) invalid("$path 必须是字符串")
            val string = primitive.content
            if (string.length < schema.int("minLength", 0)) invalid("$path 不能为空")
            schema.optionalString("pattern")?.let { if (!Regex(it).matches(string)) invalid("$path 格式无效") }
            schema["enum"]?.jsonArray?.let { if (primitive !in it) invalid("$path 的值不在允许范围内") }
        }
        "integer" -> {
            val primitive = value as? JsonPrimitive ?: invalid("$path 必须是整数")
            if (primitive.isString) invalid("$path 必须是整数")
            val integer = primitive.intOrNull ?: invalid("$path 必须是整数")
            if (integer !in schema.int("minimum", Int.MIN_VALUE)..schema.int("maximum", Int.MAX_VALUE)) invalid("$path 超出允许范围")
        }
        "boolean" -> if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) invalid("$path 必须是布尔值")
    }
}
