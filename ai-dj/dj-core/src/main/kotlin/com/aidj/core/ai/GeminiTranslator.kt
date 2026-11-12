package com.aidj.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Translates the Messages-API-shaped turns the [ClaudeAgent] tool loop speaks into Google Gemini
 * `generateContent` requests and back, so the same loop and tools run on Gemini (free tier) directly
 * from the phone. Mirrors backend/server.mjs (toGeminiRequest / fromGeminiResponse).
 */
object GeminiTranslator {

    /** JSON Schema (as used in our tool definitions) -> Gemini Schema subset. */
    fun schema(s: JsonObject): JsonObject = buildJsonObject {
        (s["type"] as? JsonPrimitive)?.contentOrNull?.let { put("type", it.uppercase()) }
        (s["description"] as? JsonPrimitive)?.contentOrNull?.let { put("description", it) }
        // Gemini rejects empty enum values (we use "" to mean "planner's choice"); the field is optional anyway.
        (s["enum"] as? JsonArray)?.let { e ->
            val vals = e.filter { (it as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }
            if (vals.isNotEmpty()) put("enum", JsonArray(vals))
        }
        (s["minimum"] as? JsonPrimitive)?.let { put("minimum", it) }
        (s["maximum"] as? JsonPrimitive)?.let { put("maximum", it) }
        (s["items"] as? JsonObject)?.let { put("items", schema(it)) }
        (s["properties"] as? JsonObject)?.let { props ->
            putJsonObject("properties") { for ((k, v) in props) if (v is JsonObject) put(k, schema(v)) }
        }
        (s["required"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("required", it) }
    }

    /** {system, messages, tools} (Messages API shapes) -> Gemini request body. */
    fun request(system: String, messages: JsonArray, tools: JsonArray, maxOutputTokens: Int): JsonObject {
        val idToName = HashMap<String, String>()
        for (m in messages) {
            val c = m.jsonObject["content"] as? JsonArray ?: continue
            for (b in c) {
                val o = b.jsonObject
                if (o.str("type") == "tool_use") idToName[o.str("id") ?: ""] = o.str("name") ?: ""
            }
        }
        return buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                for (m in messages) {
                    val mo = m.jsonObject
                    val role = if (mo.str("role") == "assistant") "model" else "user"
                    val parts = buildJsonArray {
                        when (val c = mo["content"]) {
                            is JsonPrimitive -> c.contentOrNull?.takeIf { it.isNotEmpty() }?.let { t -> addJsonObject { put("text", t) } }
                            is JsonArray -> for (b in c) {
                                val o = b.jsonObject
                                when (o.str("type")) {
                                    "text" -> o.str("text")?.takeIf { it.isNotEmpty() }?.let { t -> addJsonObject { put("text", t) } }
                                    "tool_use" -> addJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", o.str("name") ?: "")
                                            put("args", o["input"] as? JsonObject ?: JsonObject(emptyMap()))
                                        }
                                    }
                                    "tool_result" -> addJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", idToName[o.str("tool_use_id")] ?: "unknown_tool")
                                            put("response", resultObject(o["content"]))
                                        }
                                    }
                                    else -> {} // thinking etc.: nothing Gemini can use
                                }
                            }
                            else -> {}
                        }
                    }
                    if (parts.isNotEmpty()) addJsonObject { put("role", role); put("parts", parts) }
                }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (t in tools) {
                            val o = t.jsonObject
                            addJsonObject {
                                put("name", o.str("name") ?: "")
                                put("description", o.str("description") ?: "")
                                (o["input_schema"] as? JsonObject)?.let { put("parameters", schema(it)) }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", maxOutputTokens); put("temperature", 0.7) }
        }
    }

    private fun resultObject(content: JsonElement?): JsonObject {
        val raw = when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            null -> ""
            else -> content.toString()
        }
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
        return when (parsed) {
            is JsonObject -> parsed
            null -> buildJsonObject { put("text", raw) }
            else -> buildJsonObject { put("result", parsed) }
        }
    }

    /** Gemini response -> Messages-API response object consumed by [ClaudeAgent]. */
    fun response(json: JsonObject, model: String): JsonObject {
        val cand = (json["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        if (cand == null) {
            val error = json["error"] as? JsonObject
            if (error != null) return buildJsonObject {
                put("type", "error")
                putJsonObject("error") { put("type", "gemini_error"); put("message", error.str("message") ?: "Gemini error") }
            }
            val blocked = (json["promptFeedback"] as? JsonObject)?.str("blockReason") != null
            return message(model, JsonArray(emptyList()), if (blocked) "refusal" else "end_turn")
        }
        var calls = 0
        val stamp = System.nanoTime().toString(36)
        val content = buildJsonArray {
            val parts = (cand["content"] as? JsonObject)?.get("parts") as? JsonArray ?: JsonArray(emptyList())
            for (p in parts) {
                val o = p.jsonObject
                val text = o.str("text")
                val fc = o["functionCall"] as? JsonObject
                if (!text.isNullOrEmpty() && o["thought"]?.jsonPrimitive?.contentOrNull != "true") addJsonObject { put("type", "text"); put("text", text) }
                else if (fc != null) addJsonObject {
                    put("type", "tool_use"); put("id", "toolu_g${stamp}_${calls++}")
                    put("name", fc.str("name") ?: ""); put("input", fc["args"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            }
        }
        val stop = when {
            calls > 0 -> "tool_use"
            cand.str("finishReason") == "MAX_TOKENS" -> "max_tokens"
            cand.str("finishReason") == "SAFETY" -> "refusal"
            else -> "end_turn"
        }
        return message(model, content, stop)
    }

    private fun message(model: String, content: JsonArray, stop: String) = buildJsonObject {
        put("id", "msg_gemini"); put("type", "message"); put("role", "assistant"); put("model", model)
        put("content", content); put("stop_reason", stop)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
