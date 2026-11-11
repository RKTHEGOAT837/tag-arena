package com.aidj.core.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The local DJ surface Claude (via the Messages API) and external MCP clients can drive.
 * Implemented by the Android app on top of the native engine. Every method returns a JSON result
 * object that is sent back to the model verbatim; throw [ToolFailure] for user-facing errors.
 * Methods may block (they are always invoked off the audio and UI threads).
 */
interface DjController {
    fun searchMusic(query: String, source: String, limit: Int): JsonElement
    fun getTrackMetadata(trackId: String): JsonElement
    fun analyseAudio(trackId: String): JsonElement
    fun detectBpm(trackId: String): JsonElement
    fun detectKey(trackId: String): JsonElement
    fun getSongStructure(trackId: String): JsonElement
    fun selectNextTrack(trackId: String?, genres: List<String>, energyDelta: Double?, reason: String): JsonElement
    fun setBpm(bpm: Double, overBeats: Double): JsonElement
    fun syncBeats(deck: String, enabled: Boolean): JsonElement
    fun applyEq(deck: String, low: Double, mid: Double, high: Double, overBeats: Double): JsonElement
    fun applyEffect(deck: String, effect: String, amount: Double, beats: Double, holdBars: Double): JsonElement
    fun createLoop(deck: String, lengthBeats: Double, slip: Boolean, release: Boolean): JsonElement
    fun startTransition(style: String?, bars: Int?, drama: Double?, asap: Boolean): JsonElement
    fun createRemix(element: String, pattern: String?, level: Double, bars: Int): JsonElement
    fun controlVirtualDj(move: String, intensity: Double): JsonElement
    fun getAudioAnalysis(): JsonElement
    fun getDjState(): JsonElement
    fun setPartyMode(mode: String, customGenres: List<String>): JsonElement
    fun announce(text: String): JsonElement
}

class ToolFailure(message: String) : Exception(message)

class ToolDef(val name: String, val description: String, val inputSchema: JsonObject, val run: (DjController, JsonObject) -> JsonElement)

/** Every tool here is backed by a real implementation in [DjController]. */
object DjTools {
    val DECKS = listOf("A", "B", "current", "next")
    val EFFECTS = listOf("echo", "reverb", "flanger", "filter_lowpass", "filter_highpass", "brake", "impact", "none")
    val STYLES = listOf("bass_swap", "filter_sweep", "echo_out", "loop_roll_drop", "cut", "brake", "drum_bridge", "mashup_layer", "crossfade")
    val REMIX = listOf("drum_layer", "riser", "impact", "beat_roll", "extend_section", "stop_remix")
    val MOVES = listOf("hype_crowd", "point_crowd", "hands_up", "headbang", "mix_focus", "celebrate", "chill_sway", "wave")
    val MODES = listOf("auto", "club", "bollywood", "punjabi", "retro", "chill", "festival", "custom")

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.strList(k: String) = (this[k]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }

    val all: List<ToolDef> = listOf(
        ToolDef("search_music", "Search the local library or a licensed online catalogue (Jamendo / Internet Archive, Creative Commons) for tracks. Online results include license and what the app may do with them.",
            objectSchema {
                string("query", "Free text: artist, title, genre or mood")
                string("source", "Where to search", enum = listOf("library", "jamendo", "internet_archive"))
                integer("limit", "Max results", 1, 25)
            }) { c, a -> c.searchMusic(a.str("query")!!, a.str("source")!!, a.num("limit")!!.toInt()) },
        ToolDef("get_track_metadata", "Metadata, source license and capabilities (playback/processing/remix) for a library track.",
            objectSchema { string("track_id", "Library track id") }) { c, a -> c.getTrackMetadata(a.str("track_id")!!) },
        ToolDef("analyse_audio", "Run (or fetch cached) local audio analysis of a library track: BPM, beat grid confidence, key, energy, sections, drops.",
            objectSchema { string("track_id", "Library track id") }) { c, a -> c.analyseAudio(a.str("track_id")!!) },
        ToolDef("detect_bpm", "Tempo and beat-grid confidence of a library track (from local analysis).",
            objectSchema { string("track_id", "Library track id") }) { c, a -> c.detectBpm(a.str("track_id")!!) },
        ToolDef("detect_key", "Musical key (with Camelot code) of a library track (from local analysis).",
            objectSchema { string("track_id", "Library track id") }) { c, a -> c.detectKey(a.str("track_id")!!) },
        ToolDef("get_song_structure", "Section map (intro, groove, buildup, drop, breakdown, outro) in bars with energy and vocal flags.",
            objectSchema { string("track_id", "Library track id") }) { c, a -> c.getSongStructure(a.str("track_id")!!) },
        ToolDef("select_next_track", "Queue the next track. Give a track_id to choose it explicitly, or leave it empty to let the local selector pick the best match for the given genres/energy. Returns the planned transition.",
            objectSchema {
                string("track_id", "Library track id to play next; empty string = let the selector choose", required = false)
                stringArray("genres", "Genre keywords to steer towards (may be empty)", 6)
                number("energy_delta", "Energy change relative to the set's target, -0.5..0.5", -0.5, 0.5, required = false)
                string("reason", "Short reason shown to the user", maxLength = 160)
            }) { c, a -> c.selectNextTrack(a.str("track_id")?.ifBlank { null }, a.strList("genres"), a.num("energy_delta"), a.str("reason")!!) },
        ToolDef("set_bpm", "Glide the master tempo (all synced decks follow, key-locked) to a BPM over a number of beats.",
            objectSchema {
                number("bpm", "Target BPM", 60.0, 200.0)
                number("over_beats", "Glide length in beats (0 = immediate)", 0.0, 256.0)
            }) { c, a -> c.setBpm(a.num("bpm")!!, a.num("over_beats")!!) },
        ToolDef("sync_beats", "Enable/disable beat sync (tempo + phase lock to the master clock) for a deck.",
            objectSchema {
                string("deck", "Deck", enum = DECKS)
                boolean("enabled", "Sync on/off")
            }) { c, a -> c.syncBeats(a.str("deck")!!, a.bool("enabled")!!) },
        ToolDef("apply_eq", "Set a deck's 3-band isolator EQ (0 = kill, 1 = flat, 1.6 = +4 dB) with a beat-timed ramp.",
            objectSchema {
                string("deck", "Deck", enum = DECKS)
                number("low", "Low band gain", 0.0, 1.6)
                number("mid", "Mid band gain", 0.0, 1.6)
                number("high", "High band gain", 0.0, 1.6)
                number("over_beats", "Ramp length in beats", 0.0, 64.0)
            }) { c, a -> c.applyEq(a.str("deck")!!, a.num("low")!!, a.num("mid")!!, a.num("high")!!, a.num("over_beats")!!) },
        ToolDef("apply_effect", "Apply a beat-synced effect to a deck for a while, then release it. amount 0..1.",
            objectSchema {
                string("deck", "Deck", enum = DECKS)
                string("effect", "Effect", enum = EFFECTS)
                number("amount", "Wet amount / filter depth", 0.0, 1.0)
                number("beats", "Echo time or effect rate in beats", 0.125, 4.0)
                number("hold_bars", "How long to keep it before releasing", 0.25, 16.0)
            }) { c, a -> c.applyEffect(a.str("deck")!!, a.str("effect")!!, a.num("amount")!!, a.num("beats")!!, a.num("hold_bars")!!) },
        ToolDef("create_loop", "Beat-quantised loop on a deck (slip mode keeps the track's timeline running underneath). release=true exits the loop.",
            objectSchema {
                string("deck", "Deck", enum = DECKS)
                number("length_beats", "Loop length in beats (0.125..32)", 0.125, 32.0)
                boolean("slip", "Slip mode")
                boolean("release", "Exit the current loop instead of creating one")
            }) { c, a -> c.createLoop(a.str("deck")!!, a.num("length_beats")!!, a.bool("slip")!!, a.bool("release")!!) },
        ToolDef("start_transition", "Start mixing into the queued next track now (or at the next phrase if asap=false). style may be empty to let the planner choose.",
            objectSchema {
                string("style", "Transition technique", enum = STYLES + "", required = false)
                integer("bars", "Blend length in bars", 1, 64, required = false)
                number("drama", "0 = smooth, 1 = maximum drama (FX, risers, impacts)", 0.0, 1.0, required = false)
                boolean("asap", "true = start on the next phrase; false = wait for the planned mix-out point")
            }) { c, a -> c.startTransition(a.str("style")?.ifBlank { null }, a.num("bars")?.toInt(), a.num("drama"), a.bool("asap")!!) },
        ToolDef("create_remix", "Live remix elements generated by the local engine (only on tracks whose license allows remixing): drum layer with a pattern, riser build, impact hit, beat roll, extend the current section with a loop.",
            objectSchema {
                string("element", "Remix element", enum = REMIX)
                string("pattern", "Drum pattern name (drum_layer only): four on the floor, build-up roll, dhol bhangra groove, breakbeat, half-time, tribal percussion", required = false, maxLength = 40)
                number("level", "Level 0..1", 0.0, 1.0)
                integer("bars", "Duration in bars", 1, 32)
            }) { c, a -> c.createRemix(a.str("element")!!, a.str("pattern"), a.num("level")!!, a.num("bars")!!.toInt()) },
        ToolDef("control_virtual_dj", "Make the on-stage virtual DJ perform a gesture (it otherwise animates automatically from the music).",
            objectSchema {
                string("move", "Gesture", enum = MOVES)
                number("intensity", "0..1", 0.0, 1.0)
            }) { c, a -> c.controlVirtualDj(a.str("move")!!, a.num("intensity")!!) },
        ToolDef("get_audio_analysis", "Live analysis of what is playing right now: section, bar/beat, master BPM, real-time band levels, energy trend, upcoming drop/outro timing.",
            objectSchema { }) { c, _ -> c.getAudioAnalysis() },
        ToolDef("get_dj_state", "Full DJ state: now playing, queued next track and plan, recent history, mode, steering, top candidates.",
            objectSchema { }) { c, _ -> c.getDjState() },
        ToolDef("set_party_mode", "Switch the party mode (changes genre preference, energy curve and transition style).",
            objectSchema {
                string("mode", "Mode", enum = MODES)
                stringArray("custom_genres", "Genres for custom mode (may be empty)", 8)
            }) { c, a -> c.setPartyMode(a.str("mode")!!, a.strList("custom_genres")) },
        ToolDef("announce", "Speak a short DJ announcement over the music (text-to-speech, music is ducked). Use sparingly.",
            objectSchema { string("text", "What to say (max ~20 words)", maxLength = 160) }) { c, a -> c.announce(a.str("text")!!) },
    )

    private val byName = all.associateBy { it.name }

    fun find(name: String) = byName[name]

    /** Validate and execute a tool call. Returns (resultJson, isError). Never throws. */
    fun execute(controller: DjController, name: String, input: JsonElement): Pair<JsonElement, Boolean> {
        val def = byName[name] ?: return errorJson("Unknown tool '$name'") to true
        return try {
            val obj = input as? JsonObject ?: throw SchemaViolation("$: expected object")
            SchemaValidator.validate(def.inputSchema, obj)
            def.run(controller, obj) to false
        } catch (e: SchemaViolation) {
            errorJson("Invalid arguments: ${e.message}") to true
        } catch (e: ToolFailure) {
            errorJson(e.message ?: "failed") to true
        } catch (e: Exception) {
            errorJson("Internal error: ${e.javaClass.simpleName}: ${e.message}") to true
        }
    }

    fun errorJson(msg: String) = buildJsonObject { put("error", msg) }

    /** Tool definitions in Claude Messages API format (strict schemas). */
    fun anthropicTools(names: Collection<String>? = null): List<JsonObject> = all.filter { names == null || it.name in names }.map {
        buildJsonObject {
            put("name", it.name); put("description", it.description); put("input_schema", it.inputSchema)
        }
    }

    /** Tool definitions in MCP `tools/list` format. */
    fun mcpTools(): List<JsonObject> = all.map {
        buildJsonObject { put("name", it.name); put("description", it.description); put("inputSchema", it.inputSchema) }
    }

    fun ok(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("ok", true)
        for ((k, v) in pairs) when (v) {
            null -> {}
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}
