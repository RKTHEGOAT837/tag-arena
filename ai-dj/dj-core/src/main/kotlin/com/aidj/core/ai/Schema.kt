package com.aidj.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Tiny DSL for JSON Schemas (the subset used by tool definitions) plus a strict validator. */
class ObjectSchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = ArrayList<String>()

    fun string(name: String, desc: String, required: Boolean = true, enum: List<String>? = null, maxLength: Int = 500) {
        props[name] = buildJsonObject {
            put("type", "string"); put("description", desc); put("maxLength", maxLength)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
        }
        if (required) this.required += name
    }

    fun number(name: String, desc: String, min: Double, max: Double, required: Boolean = true) {
        props[name] = buildJsonObject { put("type", "number"); put("description", desc); put("minimum", min); put("maximum", max) }
        if (required) this.required += name
    }

    fun integer(name: String, desc: String, min: Long, max: Long, required: Boolean = true) {
        props[name] = buildJsonObject { put("type", "integer"); put("description", desc); put("minimum", min); put("maximum", max) }
        if (required) this.required += name
    }

    fun boolean(name: String, desc: String, required: Boolean = true) {
        props[name] = buildJsonObject { put("type", "boolean"); put("description", desc) }
        if (required) this.required += name
    }

    fun stringArray(name: String, desc: String, maxItems: Int, enum: List<String>? = null, required: Boolean = true) {
        props[name] = buildJsonObject {
            put("type", "array"); put("description", desc); put("maxItems", maxItems)
            put("items", buildJsonObject {
                put("type", "string"); put("maxLength", 80)
                if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
            })
        }
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}

fun objectSchema(block: ObjectSchemaBuilder.() -> Unit): JsonObject = ObjectSchemaBuilder().apply(block).build()

class SchemaViolation(message: String) : IllegalArgumentException(message)

object SchemaValidator {
    /** Validates [value] against [schema]; throws [SchemaViolation] with a precise path on failure. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$") {
        val type = (schema["type"] as? JsonPrimitive)?.content
        when (type) {
            "object" -> {
                val obj = value as? JsonObject ?: throw SchemaViolation("$path: expected object")
                val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val req = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                for (r in req) if (r !in obj || obj[r] is JsonNull) throw SchemaViolation("$path.$r: required")
                val additional = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true
                for ((k, v) in obj) {
                    val ps = props[k] as? JsonObject
                    if (ps == null) {
                        if (!additional) throw SchemaViolation("$path.$k: unknown property")
                    } else if (v !is JsonNull || k in req) validate(ps, v, "$path.$k")
                }
            }
            "array" -> {
                val arr = value as? JsonArray ?: throw SchemaViolation("$path: expected array")
                val max = (schema["maxItems"] as? JsonPrimitive)?.longOrNull
                if (max != null && arr.size > max) throw SchemaViolation("$path: more than $max items")
                val items = schema["items"] as? JsonObject
                if (items != null) arr.forEachIndexed { i, e -> validate(items, e, "$path[$i]") }
            }
            "string" -> {
                val p = value as? JsonPrimitive ?: throw SchemaViolation("$path: expected string")
                if (!p.isString) throw SchemaViolation("$path: expected string")
                val max = (schema["maxLength"] as? JsonPrimitive)?.longOrNull
                if (max != null && p.content.length > max) throw SchemaViolation("$path: longer than $max chars")
                checkEnum(schema, p, path)
            }
            "number", "integer" -> {
                val p = value as? JsonPrimitive ?: throw SchemaViolation("$path: expected $type")
                if (p.isString) throw SchemaViolation("$path: expected $type, got string")
                val d = p.doubleOrNull ?: throw SchemaViolation("$path: expected $type")
                if (d.isNaN() || d.isInfinite()) throw SchemaViolation("$path: not finite")
                if (type == "integer" && d != Math.floor(d)) throw SchemaViolation("$path: expected integer")
                (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (d < it) throw SchemaViolation("$path: below minimum $it") }
                (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (d > it) throw SchemaViolation("$path: above maximum $it") }
            }
            "boolean" -> {
                val p = value as? JsonPrimitive ?: throw SchemaViolation("$path: expected boolean")
                if (p.isString || p.booleanOrNull == null) throw SchemaViolation("$path: expected boolean")
            }
            null -> {}
            else -> throw SchemaViolation("$path: unsupported schema type $type")
        }
    }

    private fun checkEnum(schema: JsonObject, p: JsonPrimitive, path: String) {
        val e = schema["enum"] as? JsonArray ?: return
        if (e.none { (it as? JsonPrimitive)?.content == p.content }) {
            throw SchemaViolation("$path: '${p.content}' not one of ${e.joinToString { it.jsonPrimitive.content }}")
        }
    }
}
