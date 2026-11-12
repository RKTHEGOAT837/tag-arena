package com.aidj.core

import com.aidj.core.ai.ClaudeAgent
import com.aidj.core.ai.DjController
import com.aidj.core.ai.DjTools
import com.aidj.core.ai.GeminiTranslator
import com.aidj.core.ai.Prompts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Live check against the real Gemini API. Runs only when GEMINI_API_KEY is set in the environment. */
class GeminiLiveTest {
    @Test
    fun realGeminiDrivesTheDjTools() {
        val key = System.getenv("GEMINI_API_KEY") ?: return
        val calls = ArrayList<String>()
        val controller = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DjController::class.java)) { _, m, args ->
            calls += m.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
            if (m.name == "getDjState") Json.parseToJsonElement("""{"party_mode":"auto","current_track":{"title":"Brown Munde","artist":"AP Dhillon","bpm":95,"key":"8A","genres":["punjabi"]},
                "next_track_candidates":[{"id":"t1","title":"Excuses","artist":"AP Dhillon","bpm":96,"key":"9A","genres":["punjabi"]},{"id":"t2","title":"Levitating","artist":"Dua Lipa","bpm":103,"key":"6A","genres":["pop"]}]}""")
            else DjTools.ok("done" to m.name)
        } as DjController
        val http = HttpClient.newHttpClient()
        val agent = ClaudeAgent({ r ->
            val body = GeminiTranslator.request(r["system"]!!.jsonPrimitive.content, r["messages"]!!.jsonArray, r["tools"]!!.jsonArray, 2000)
            val req = HttpRequest.newBuilder(URI("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .header("content-type", "application/json").header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            GeminiTranslator.response(Json.parseToJsonElement(res.body()).jsonObject, "gemini-2.5-flash")
        }, controller)
        val result = agent.run("command", Prompts.SYSTEM, "Switch to Punjabi mode and queue the best Punjabi candidate next. Check the DJ state first.")
        println("tools called: $calls")
        println("trace: ${result.trace.map { "${it.tool}${if (it.isError) "(ERR: ${it.result})" else ""}" }}")
        println("reply: ${result.text}")
        assertTrue("Gemini should have used tools", calls.isNotEmpty())
        assertTrue("no invalid tool calls", result.trace.none { it.isError })
    }
}
