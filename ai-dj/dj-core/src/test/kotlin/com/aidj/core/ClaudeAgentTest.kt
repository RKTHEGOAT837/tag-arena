package com.aidj.core

import com.aidj.core.ai.ClaudeAgent
import com.aidj.core.ai.ClaudeTransport
import com.aidj.core.ai.ClaudeUnavailable
import com.aidj.core.ai.DjController
import com.aidj.core.ai.DjTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

class ClaudeAgentTest {
    private val calls = ArrayList<String>()

    /** A DjController where every method records its name and returns ok. */
    private val controller: DjController = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DjController::class.java)) { _, m, args ->
        calls += m.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
        DjTools.ok("done" to m.name)
    } as DjController

    private fun msg(stop: String, content: String) = Json.parseToJsonElement(
        """{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"$stop","content":$content,"usage":{"input_tokens":1,"output_tokens":1}}""",
    ).jsonObject

    @Test
    fun runsToolLoopAndReturnsResultsInOneUserTurn() {
        val requests = ArrayList<JsonObject>()
        val responses = ArrayDeque(listOf(
            msg("tool_use", """[{"type":"thinking","thinking":"","signature":"sig"},
                {"type":"tool_use","id":"t1","name":"set_bpm","input":{"bpm":126,"over_beats":16}},
                {"type":"tool_use","id":"t2","name":"apply_effect","input":{"deck":"A","effect":"laser","amount":1,"beats":1,"hold_bars":1}}]"""),
            msg("end_turn", """[{"type":"text","text":"Tempo raised."}]"""),
        ))
        val transport = ClaudeTransport { r -> requests += r; responses.removeFirst() }
        val res = ClaudeAgent(transport, controller).run("command", "sys", "faster please")
        assertEquals("Tempo raised.", res.text)
        assertEquals(listOf("setBpm(126.0, 16.0)"), calls) // the invalid effect never reached the controller
        assertEquals(2, requests.size)
        val second = requests[1]["messages"]!!.jsonArray
        // [user, assistant(with thinking block preserved), user(tool_results)]
        assertEquals(3, second.size)
        val assistant = second[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", assistant[0].jsonObject["type"]!!.jsonPrimitive.content)
        val results = second[2].jsonObject["content"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals("t1", results[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("true", results[1].jsonObject["is_error"]!!.jsonPrimitive.content)
        assertTrue(res.trace.size == 2 && res.trace[1].isError)
    }

    @Test
    fun refusalAndApiErrorsAreHandled() {
        val r = ClaudeAgent({ msg("refusal", "[]") }, controller).run("command", "s", "x")
        assertEquals("refusal", r.stopReason)
        try {
            ClaudeAgent({ Json.parseToJsonElement("""{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""").jsonObject }, controller).run("d", "s", "x")
            fail("expected ClaudeUnavailable")
        } catch (e: ClaudeUnavailable) { assertTrue(e.message!!.contains("busy")) }
        try {
            ClaudeAgent({ throw java.io.IOException("offline") }, controller).run("d", "s", "x")
            fail("expected ClaudeUnavailable")
        } catch (e: ClaudeUnavailable) { assertTrue(e.message!!.contains("offline")) }
    }

    @Suppress("unused") private fun unused(e: JsonElement) = e
}
