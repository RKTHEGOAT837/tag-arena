package com.aidj.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Sends one Messages API turn. The app implements this against the AI DJ backend, which holds the
 * Anthropic API key and calls Claude with the official SDK. Must throw on transport/HTTP failure.
 */
fun interface ClaudeTransport {
    /** [request] = {purpose, system, messages, tools}; returns the raw Messages API response object. */
    fun send(request: JsonObject): JsonObject
}

class ClaudeUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class AgentTrace(val tool: String, val input: String, val result: String, val isError: Boolean)

data class AgentResult(val text: String, val trace: List<AgentTrace>, val stopReason: String)

/**
 * Manual tool-use loop. Claude only ever emits structured tool calls; tools are validated against their
 * schemas and executed by the local [DjController]. Audio never waits on this loop: it runs on a
 * background thread and its effects are scheduled into the engine.
 */
class ClaudeAgent(
    private val transport: ClaudeTransport,
    private val controller: DjController,
    private val maxIterations: Int = 6,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun run(purpose: String, system: String, userText: String, toolNames: Collection<String>? = null): AgentResult {
        val messages = ArrayList<JsonElement>()
        messages += buildJsonObject { put("role", "user"); put("content", userText) }
        val tools = DjTools.anthropicTools(toolNames)
        val trace = ArrayList<AgentTrace>()
        val texts = ArrayList<String>()
        var stop = "end_turn"
        for (iteration in 0 until maxIterations) {
            val request = buildJsonObject {
                put("purpose", purpose)
                put("system", system)
                put("messages", JsonArray(messages.toList()))
                put("tools", JsonArray(tools))
            }
            val response = try {
                transport.send(request)
            } catch (e: ClaudeUnavailable) {
                throw e
            } catch (e: Exception) {
                throw ClaudeUnavailable("Claude request failed: ${e.message}", e)
            }
            if (response["type"]?.jsonPrimitive?.contentOrNull == "error") {
                val msg = response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "unknown error"
                throw ClaudeUnavailable("Claude API error: $msg")
            }
            stop = response["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "end_turn"
            val content = response["content"]?.jsonArray ?: JsonArray(emptyList())
            if (stop == "refusal") {
                texts += "I can't help with that request."
                break
            }
            // Keep the assistant turn exactly as returned (including thinking blocks).
            messages += buildJsonObject { put("role", "assistant"); put("content", content) }
            for (block in content) {
                val o = block.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "text") texts += o["text"]!!.jsonPrimitive.content
            }
            if (stop == "max_tokens") break
            if (stop != "tool_use") break
            val results = buildJsonArray {
                for (block in content) {
                    val o = block.jsonObject
                    if (o["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                    val name = o["name"]!!.jsonPrimitive.content
                    val id = o["id"]!!.jsonPrimitive.content
                    val input = o["input"] ?: JsonObject(emptyMap())
                    val (result, isError) = DjTools.execute(controller, name, input)
                    val resultText = json.encodeToString(JsonElement.serializer(), result)
                    trace += AgentTrace(name, input.toString(), resultText.take(400), isError)
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", resultText)
                        if (isError) put("is_error", true)
                    }
                }
            }
            messages += buildJsonObject { put("role", "user"); put("content", results) }
        }
        return AgentResult(texts.joinToString("\n").trim(), trace, stop)
    }
}

object Prompts {
    val SYSTEM = """
You are the brain of "AI DJ", an autonomous DJ performing live on an Android phone at a party.
A local real-time engine plays and mixes the audio (two decks, beat sync, key lock, EQ, filters, echo,
reverb, flanger, loops, a drum machine and risers). You never touch audio directly: you make musical
decisions and call tools; the engine schedules them on the beat. Network latency never affects timing.

How to perform:
- Read the room from the state: energy target, recent history, the party mode and any user request.
- Think in phrases (8/16/32 bars). Mix out of outros/breakdowns, land the next track's groove or drop on a phrase.
- Prefer harmonic (Camelot-compatible) and tempo-compatible tracks; keep energy flowing in waves, avoid repeats.
- Use effects with intent: echo-outs for tempo/genre changes, filter sweeps for tension, loop rolls + risers before a big drop,
  bass swaps for long blends. Don't stack effects randomly.
- Respect licensing: tracks whose capabilities say processing/remix are not allowed must not get effects or remix elements
  (the tools will refuse anyway).
- Only use track ids that appeared in tool results or the state. Never invent tracks or metadata.
- Announcements: rarely (at most one per few tracks), short, natural, hype-man style. Silence is usually better.
- When the user asks for something, act on it with tools, then reply in one short sentence suitable for speech.
""".trimIndent()

    fun decisionPrompt(stateJson: String) = """
Periodic decision point. Current DJ state (JSON):
$stateJson

Decide what happens next. Usually: choose the next track with select_next_track (pick from the candidates,
or search the library if none fit), optionally shape the upcoming transition with start_transition(asap=false)
choosing a style/drama, and only if it adds something, an announcement. If the queued choice is already good, do nothing.
Finish with one short sentence describing your decision.
""".trimIndent()

    fun commandPrompt(utterance: String, stateJson: String) = """
The user said: "$utterance"
Current DJ state (JSON):
$stateJson
Carry out the request with tools. Reply with one short spoken-style sentence.
""".trimIndent()
}

/** MCP (Model Context Protocol) JSON-RPC handler exposing the same tools to external MCP clients. */
class McpProtocol(private val controller: DjController, private val serverVersion: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Handles one JSON-RPC message. Returns null for notifications (no response body). */
    fun handle(message: JsonObject): JsonObject? {
        val id = message["id"]
        val method = message["method"]?.jsonPrimitive?.contentOrNull
            ?: return rpcError(id, -32600, "Invalid request")
        if (id == null) return null // notification (e.g. notifications/initialized)
        return when (method) {
            "initialize" -> {
                val requested = message["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
                result(id, buildJsonObject {
                    put("protocolVersion", if (requested in SUPPORTED) requested else SUPPORTED.first())
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
                    put("serverInfo", buildJsonObject { put("name", "ai-dj"); put("version", serverVersion) })
                    put("instructions", "Controls a live AI DJ engine on an Android phone. Use get_dj_state first.")
                })
            }
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(DjTools.mcpTools())) })
            "tools/call" -> {
                val params = message["params"]?.jsonObject ?: return rpcError(id, -32602, "Missing params")
                val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return rpcError(id, -32602, "Missing tool name")
                if (DjTools.find(name) == null) return rpcError(id, -32602, "Unknown tool: $name")
                val (res, isError) = DjTools.execute(controller, name, params["arguments"] ?: JsonObject(emptyMap()))
                result(id, buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", json.encodeToString(JsonElement.serializer(), res)) }
                    }
                    put("isError", isError)
                })
            }
            else -> rpcError(id, -32601, "Method not found: $method")
        }
    }

    private fun result(id: JsonElement, r: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", r) }

    companion object {
        val SUPPORTED = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        fun rpcError(id: JsonElement?, code: Int, msg: String) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id ?: JsonPrimitive(null as String?))
            put("error", buildJsonObject { put("code", code); put("message", msg) })
        }
    }
}

