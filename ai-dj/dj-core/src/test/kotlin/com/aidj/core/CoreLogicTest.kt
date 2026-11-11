package com.aidj.core

import com.aidj.core.ai.DjController
import com.aidj.core.ai.DjTools
import com.aidj.core.ai.McpProtocol
import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.MusicalKey
import com.aidj.core.model.PartyMode
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import com.aidj.core.model.TrackAnalysis
import com.aidj.core.model.TransitionStyle
import com.aidj.core.planning.ActionType
import com.aidj.core.planning.Param
import com.aidj.core.planning.TransitionPlanner
import com.aidj.core.planning.TransitionRequest
import com.aidj.core.selection.Harmonic
import com.aidj.core.selection.SetContext
import com.aidj.core.selection.TrackSelector
import com.aidj.core.voice.CommandParser
import com.aidj.core.voice.VoiceCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoreLogicTest {
    companion object {
        private val analyzer = AudioAnalyzer()
        // Real analyses of synthetic tracks (cached across tests).
        val am124: TrackAnalysis by lazy { analyzer.analyze(SynthTrack.render(124.0, rootPc = 9, minor = true), 22050) }
        val em126: TrackAnalysis by lazy { analyzer.analyze(SynthTrack.render(126.0, rootPc = 4, minor = true, seed = 2), 22050) }
        val fs100: TrackAnalysis by lazy { analyzer.analyze(SynthTrack.render(100.0, rootPc = 6, minor = false, seed = 3), 22050) }
    }

    private fun lt(id: String, a: TrackAnalysis, genres: List<String> = listOf("house"), caps: SourceCapabilities = SourceCapabilities.LOCAL) =
        LibraryTrack(Track(id, "Title $id", "Artist $id", durationMs = (a.durationSec * 1000).toLong(), uri = "file://$id",
            source = SourceType.LOCAL_FILE, capabilities = caps, genres = genres), a)

    @Test
    fun camelotRules() {
        val am = MusicalKey(9, true)
        assertEquals("8A", am.camelot)
        assertEquals(1f, Harmonic.camelotScore(am, am))
        assertEquals(0.9f, Harmonic.camelotScore(am, MusicalKey(4, true))) // Em = 9A
        assertEquals(0.85f, Harmonic.camelotScore(am, MusicalKey(0, false))) // C = 8B
        assertTrue(Harmonic.camelotScore(am, MusicalKey(6, false)) < 0.3f) // F# major far away
        assertEquals(MusicalKey(9, true), MusicalKey.parse("8A"))
        assertEquals(MusicalKey(1, true), MusicalKey.parse("C#m"))
        // Shifting F#m (11A) by -2 semitones gives Em (9A), adjacent to Am (8A): should be found.
        val (shift, score) = Harmonic.bestKeyShift(am, MusicalKey(6, true), 2)
        assertTrue("shift=$shift score=$score", score >= 0.85f && shift != 0)
        val (ratio, eff) = Harmonic.tempoMatch(128.0, 65.0)
        assertEquals(130.0, eff, 1e-9)
        assertEquals(128.0 / 130.0, ratio, 1e-9)
    }

    @Test
    fun ccLicensesMapToCapabilities() {
        val nd = SourceCapabilities.fromCreativeCommons("https://creativecommons.org/licenses/by-nc-nd/3.0/")
        assertTrue(nd.playback); assertFalse(nd.processing); assertFalse(nd.remix)
        val by = SourceCapabilities.fromCreativeCommons("http://creativecommons.org/licenses/by-sa/4.0/")
        assertTrue(by.processing); assertTrue(by.remix)
        val unknown = SourceCapabilities.fromCreativeCommons(null)
        assertFalse(unknown.processing)
        assertEquals("CC BY-NC-ND 3.0", SourceCapabilities.licenseShortName("https://creativecommons.org/licenses/by-nc-nd/3.0/"))
    }

    @Test
    fun selectorPrefersCompatibleTracksAndAvoidsRepeats() {
        val cur = lt("cur", am124)
        val good = lt("good", em126)
        val far = lt("far", fs100, genres = listOf("rock"))
        val played = lt("played", em126)
        val ctx = SetContext(cur, history = listOf("played"), historyArtists = emptyList(), minutesPlayed = 10f, allowKeyShift = false)
        val ranked = TrackSelector(Random(1)).rank(listOf(cur, good, far, played), ctx, PartyMode.CLUB.profile())
        assertEquals("good", ranked.first().trackId)
        assertTrue(ranked.none { it.trackId == "played" || it.trackId == "cur" })
        assertTrue(ranked.first().reasons.isNotEmpty())
    }

    @Test
    fun plannerBuildsPhraseAlignedBeatmatchedPlans() {
        val planner = TransitionPlanner(Random(3))
        for (style in TransitionStyle.entries) {
            val plan = planner.plan(am124, SourceCapabilities.LOCAL, 30.0, em126, SourceCapabilities.LOCAL, 124.0,
                PartyMode.AUTO.profile(), TransitionRequest(style = style, drama = 0.8f))
            // Starts on a bar line of A after the current position.
            assertEquals(0, (plan.startBeatOut - am124.grid.downbeatPhase) % 4)
            assertTrue(plan.startTimeOutSec > 30.0)
            assertTrue("B must start: $style", plan.actions.any { it.type == ActionType.START_DECK && it.deck == 1 })
            assertTrue(plan.actions.any { it.type == ActionType.STOP_DECK && it.deck == 0 })
            assertTrue(plan.ramps.all { it.to in it.param.min..it.param.max })
            assertTrue(plan.ramps.all { it.endBeat >= it.startBeat })
            val expectedStyle = if (style == TransitionStyle.MASHUP_LAYER && Harmonic.camelotScore(am124.key, em126.key) < 0.85f) null else style
            if (expectedStyle != null) assertEquals(style, plan.style)
            println("$style -> ${plan.description}; start=${"%.2f".format(plan.startTimeOutSec)}s cue=${"%.2f".format(plan.inCueSec)}s len=${plan.lengthBeats}")
        }
        // Default plan waits for A's outro.
        val auto = planner.plan(am124, SourceCapabilities.LOCAL, 30.0, em126, SourceCapabilities.LOCAL, 124.0, PartyMode.CLUB.profile())
        assertTrue("starts near outro (bar ${am124.outroStartBar})", am124.grid.barIndexAt(auto.startTimeOutSec + 0.01) >= am124.outroStartBar - 16)
    }

    @Test
    fun plannerRespectsLicenses() {
        val nd = SourceCapabilities.fromCreativeCommons("https://creativecommons.org/licenses/by-nd/4.0/")
        val plan = TransitionPlanner(Random(1)).plan(am124, SourceCapabilities.LOCAL, 30.0, em126, nd, 124.0,
            PartyMode.FESTIVAL.profile(), TransitionRequest(style = TransitionStyle.BASS_SWAP))
        assertFalse("ND track must not be synced/processed", plan.syncIn)
        assertEquals(0, plan.keyShift)
        assertFalse(plan.usesRemix)
        assertTrue(plan.ramps.none { it.deck == 1 && it.param in setOf(Param.EQ_LOW, Param.FILTER, Param.ECHO_MIX) && it.to != it.param.default })
        // ND outgoing track: no effects on A.
        val plan2 = TransitionPlanner(Random(1)).plan(am124, nd, 30.0, em126, SourceCapabilities.LOCAL, 124.0,
            PartyMode.FESTIVAL.profile(), TransitionRequest(style = TransitionStyle.ECHO_OUT))
        assertTrue(plan2.ramps.none { it.deck == 0 && it.param in setOf(Param.ECHO_MIX, Param.FILTER, Param.EQ_LOW) })
    }

    @Test
    fun bigTempoGapUsesBridgeOrCut() {
        val plan = TransitionPlanner(Random(5)).plan(am124, SourceCapabilities.LOCAL, 30.0, fs100, SourceCapabilities.LOCAL, 124.0,
            PartyMode.AUTO.profile())
        assertTrue(plan.style in setOf(TransitionStyle.DRUM_BRIDGE, TransitionStyle.ECHO_OUT, TransitionStyle.CUT, TransitionStyle.BRAKE,
            TransitionStyle.CROSSFADE, TransitionStyle.LOOP_ROLL_DROP))
        if (plan.style == TransitionStyle.DRUM_BRIDGE) assertTrue(plan.ramps.any { it.param == Param.MASTER_BPM && it.to == 100f })
    }

    private class FakeController : DjController {
        val calls = ArrayList<String>()
        private fun r(name: String): JsonElement { calls += name; return DjTools.ok("tool" to name) }
        override fun searchMusic(query: String, source: String, limit: Int) = r("search:$query:$source:$limit")
        override fun getTrackMetadata(trackId: String) = r("meta")
        override fun analyseAudio(trackId: String) = r("analyse")
        override fun detectBpm(trackId: String) = r("bpm")
        override fun detectKey(trackId: String) = r("key")
        override fun getSongStructure(trackId: String) = r("structure")
        override fun selectNextTrack(trackId: String?, genres: List<String>, energyDelta: Double?, reason: String) = r("select:$trackId:$genres")
        override fun setBpm(bpm: Double, overBeats: Double) = r("bpm:$bpm")
        override fun syncBeats(deck: String, enabled: Boolean) = r("sync")
        override fun applyEq(deck: String, low: Double, mid: Double, high: Double, overBeats: Double) = r("eq")
        override fun applyEffect(deck: String, effect: String, amount: Double, beats: Double, holdBars: Double) = r("fx:$effect")
        override fun createLoop(deck: String, lengthBeats: Double, slip: Boolean, release: Boolean) = r("loop")
        override fun startTransition(style: String?, bars: Int?, drama: Double?, asap: Boolean) = r("transition:$style:$bars")
        override fun createRemix(element: String, pattern: String?, level: Double, bars: Int) = r("remix")
        override fun controlVirtualDj(move: String, intensity: Double) = r("vdj")
        override fun getAudioAnalysis() = r("live")
        override fun getDjState() = r("state")
        override fun setPartyMode(mode: String, customGenres: List<String>) = r("mode")
        override fun announce(text: String) = r("announce")
    }

    @Test
    fun toolValidationRejectsOutOfBoundsAndUnknownArgs() {
        val c = FakeController()
        val (_, err1) = DjTools.execute(c, "set_bpm", buildJsonObject { put("bpm", 400); put("over_beats", 8) })
        assertTrue(err1)
        val (_, err2) = DjTools.execute(c, "set_bpm", buildJsonObject { put("bpm", 126); put("over_beats", 8); put("evil", 1) })
        assertTrue(err2)
        val (_, err3) = DjTools.execute(c, "apply_effect", buildJsonObject {
            put("deck", "A"); put("effect", "laser"); put("amount", 0.5); put("beats", 1); put("hold_bars", 2)
        })
        assertTrue(err3)
        val (_, ok) = DjTools.execute(c, "set_bpm", buildJsonObject { put("bpm", 126.5); put("over_beats", 16) })
        assertFalse(ok)
        val (_, ok2) = DjTools.execute(c, "start_transition", buildJsonObject { put("asap", true) })
        assertFalse(ok2)
        assertEquals(listOf("bpm:126.5", "transition:null:null"), c.calls)
        val (_, unknown) = DjTools.execute(c, "rm_rf", buildJsonObject { })
        assertTrue(unknown)
        // Every tool definition is a closed object schema.
        DjTools.all.forEach { assertEquals(false, it.inputSchema["additionalProperties"]!!.jsonPrimitive.content.toBoolean()) }
    }

    @Test
    fun mcpProtocolHandshakeListAndCall() {
        val mcp = McpProtocol(FakeController(), "1.0")
        val json = Json
        val init = mcp.handle(json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""").jsonObject)!!
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(mcp.handle(json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").jsonObject))
        val list = mcp.handle(json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").jsonObject)!!
        val names = list["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        for (required in listOf("search_music", "get_track_metadata", "analyse_audio", "detect_bpm", "detect_key", "get_song_structure",
            "select_next_track", "set_bpm", "sync_beats", "apply_eq", "apply_effect", "create_loop", "start_transition",
            "create_remix", "control_virtual_dj", "get_audio_analysis")) assertTrue(required, required in names)
        val call = mcp.handle(json.parseToJsonElement("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"detect_bpm","arguments":{"track_id":"x"}}}""").jsonObject)!!
        assertEquals(false, call["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        val bad = mcp.handle(json.parseToJsonElement("""{"jsonrpc":"2.0","id":4,"method":"nope"}""").jsonObject)!!
        assertEquals(-32601, bad["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun voiceCommands() {
        assertEquals(VoiceCommand.Energy(0.2f), CommandParser.parse("Play something more energetic."))
        assertEquals(VoiceCommand.SetMode(PartyMode.BOLLYWOOD), CommandParser.parse("Switch to Bollywood."))
        assertEquals(VoiceCommand.Genres(listOf("punjabi", "edm")), CommandParser.parse("Mix some Punjabi with EDM."))
        assertEquals(VoiceCommand.Era(2000, 2009), CommandParser.parse("Play songs from the 2000s."))
        assertEquals(VoiceCommand.Era(1990, 1999), CommandParser.parse("give me some 90s hits"))
        assertEquals(VoiceCommand.Drama(1f), CommandParser.parse("Make the next transition more dramatic."))
        assertEquals(VoiceCommand.Energy(-0.25f), CommandParser.parse("Slow things down."))
        assertEquals(VoiceCommand.PowerDrop, CommandParser.parse("Give me a powerful drop."))
        assertEquals(VoiceCommand.CrowdPleaser, CommandParser.parse("Play something everyone can dance to."))
        assertEquals(VoiceCommand.Skip, CommandParser.parse("skip this"))
        assertTrue(CommandParser.parse("what's the weather like") is VoiceCommand.Unknown)
    }

    @Suppress("unused") private fun unused(o: JsonObject) = o
}
