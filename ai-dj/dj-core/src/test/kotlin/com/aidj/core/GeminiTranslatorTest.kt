package com.aidj.core

import com.aidj.core.ai.ClaudeAgent
import com.aidj.core.ai.DjController
import com.aidj.core.ai.DjTools
import com.aidj.core.ai.GeminiTranslator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class GeminiTranslatorTest {
    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun allDjToolSchemasTranslate() {
        val req = GeminiTranslator.request("sys", JsonArray(listOf(j("""{"role":"user","content":"hi"}"""))), JsonArray(DjTools.anthropicTools()), 1000)
        val decls = req["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(DjTools.all.size, decls.size)
        val setBpm = decls.first { it.jsonObject["name"]!!.jsonPrimitive.content == "set_bpm" }.jsonObject["parameters"]!!.jsonObject
        assertEquals("OBJECT", setBpm["type"]!!.jsonPrimitive.content)
        assertEquals("NUMBER", setBpm["properties"]!!.jsonObject["bpm"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(setBpm["additionalProperties"] == null)
    }

    @Test
    fun agentLoopRunsOverGeminiShapes() {
        val calls = ArrayList<String>()
        val controller = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DjController::class.java)) { _, m, _ ->
            calls += m.name; DjTools.ok("done" to m.name)
        } as DjController
        val geminiReplies = ArrayDeque(listOf(
            j("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"set_party_mode","args":{"mode":"punjabi","custom_genres":[]}}}]},"finishReason":"STOP"}]}"""),
            j("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Punjabi time!"}]},"finishReason":"STOP"}]}"""),
        ))
        val sent = ArrayList<JsonObject>()
        val agent = ClaudeAgent({ r ->
            val gReq = GeminiTranslator.request(r["system"]!!.jsonPrimitive.content, r["messages"]!!.jsonArray, r["tools"]!!.jsonArray, 2000)
            sent += gReq
            GeminiTranslator.response(geminiReplies.removeFirst(), "gemini-2.5-flash")
        }, controller)
        val res = agent.run("command", "sys", "switch to punjabi")
        assertEquals("Punjabi time!", res.text)
        assertEquals(listOf("setPartyMode"), calls)
        // Second request carries the function call and its response.
        val contents = sent[1]["contents"]!!.jsonArray
        assertEquals("set_party_mode", contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val fr = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("set_party_mode", fr["name"]!!.jsonPrimitive.content)
        assertEquals("true", fr["response"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsAndBlocksMap() {
        assertEquals("error", GeminiTranslator.response(j("""{"error":{"code":429,"message":"quota"}}"""), "m")["type"]!!.jsonPrimitive.content)
        assertEquals("refusal", GeminiTranslator.response(j("""{"promptFeedback":{"blockReason":"SAFETY"}}"""), "m")["stop_reason"]!!.jsonPrimitive.content)
    }
}
