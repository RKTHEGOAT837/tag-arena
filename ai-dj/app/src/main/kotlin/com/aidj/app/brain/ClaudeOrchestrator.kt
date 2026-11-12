package com.aidj.app.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.aidj.app.data.InternetUsage
import com.aidj.app.data.LibraryRepository
import com.aidj.app.data.AnalysisQueue
import com.aidj.app.data.SettingsStore
import com.aidj.app.discovery.Discovery
import com.aidj.app.discovery.OnlineTrack
import com.aidj.app.engine.CapabilityException
import com.aidj.core.ai.ClaudeAgent
import com.aidj.core.ai.ClaudeTransport
import com.aidj.core.ai.ClaudeUnavailable
import com.aidj.core.ai.DjController
import com.aidj.core.ai.DjTools
import com.aidj.core.ai.Prompts
import com.aidj.core.ai.ToolFailure
import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.PartyMode
import com.aidj.core.model.SourceType
import com.aidj.core.model.TrackAnalysis
import com.aidj.core.model.TransitionStyle
import com.aidj.core.selection.TrackSelector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Claude as the decision layer. Talks to the AI DJ backend (which owns the Anthropic API key), runs the
 * tool loop and implements every tool against the real engine, library and discovery services.
 */
class ClaudeOrchestrator(
    private val context: Context,
    private val brain: DjBrain,
    private val library: LibraryRepository,
    private val analysis: AnalysisQueue,
    private val discovery: Discovery,
    private val settings: SettingsStore,
) : DjController {
    companion object { const val GEMINI_MODEL = "gemini-2.5-flash" }
    var taste: com.aidj.app.data.TasteStore? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    private val performer get() = brain.performer
    private val searchCache = ConcurrentHashMap<String, OnlineTrack>()
    @Volatile private var failures = 0
    @Volatile private var backoffUntil = 0L
    @Volatile var lastError: String? = null
        private set

    private val transport = ClaudeTransport { request ->
        if (settings.geminiApiKey.isNotBlank()) return@ClaudeTransport gemini(request)
        val s = settings.current
        val url = s.backendUrl.trimEnd('/') + "/v1/claude/messages"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${settings.backendToken}")
            .post(json.encodeToString(JsonObject.serializer(), request).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ClaudeUnavailable("Backend HTTP ${r.code}: ${body.take(200)}")
            json.parseToJsonElement(body).jsonObject
        }
    }

    /** Direct mode: the phone calls Google Gemini itself with the user's own key (no backend needed). */
    private fun gemini(request: JsonObject): JsonObject {
        val decision = request["purpose"]?.jsonPrimitive?.content == "decision"
        val body = com.aidj.core.ai.GeminiTranslator.request(
            request["system"]!!.jsonPrimitive.content, request["messages"]!!.jsonArray, request["tools"]!!.jsonArray,
            if (decision) 2000 else 4000,
        )
        val model = GEMINI_MODEL
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", settings.geminiApiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val msg = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content ?: text.take(200)
                throw ClaudeUnavailable(when (r.code) {
                    400 -> if ("API key" in msg) "Gemini rejected the API key" else "Gemini error: $msg"
                    401, 403 -> "Gemini rejected the API key"
                    429 -> "Gemini free-tier quota reached – using the local AI for now"
                    else -> "Gemini HTTP ${r.code}: $msg"
                })
            }
            return com.aidj.core.ai.GeminiTranslator.response(parsed ?: throw ClaudeUnavailable("Bad Gemini response"), model)
        }
    }

    // ------------------------------------------------------------------ availability

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return when (settings.current.internet) {
            InternetUsage.OFFLINE -> false
            InternetUsage.WIFI_ONLY -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            InternetUsage.ALWAYS -> true
        }
    }

    fun isConfigured(): Boolean = settings.geminiApiKey.isNotBlank() ||
        (settings.current.backendUrl.isNotBlank() && settings.backendToken.isNotBlank())

    fun isUsable(): Boolean = settings.current.claudeEnabled && isConfigured() && isOnline() && System.currentTimeMillis() >= backoffUntil

    fun statusAfter(ok: Boolean?): AiState = when {
        !settings.current.claudeEnabled || !isConfigured() -> AiState.LOCAL
        !isOnline() -> AiState.OFFLINE
        ok == true -> AiState.CLAUDE
        else -> AiState.ERROR
    }

    private fun <T> guarded(block: () -> T): T? = try {
        block().also { failures = 0; lastError = null }
    } catch (e: ClaudeUnavailable) {
        failures++
        lastError = e.message
        backoffUntil = System.currentTimeMillis() + (15_000L shl minOf(failures, 5)).coerceAtMost(10 * 60_000L)
        Log.w("Claude", "unavailable: ${e.message}")
        null
    }

    private val purpose = ThreadLocal<String>()

    /** Periodic decision for the next track. Returns true when Claude answered. */
    fun decide(): Boolean = guarded {
        purpose.set("decision")
        val agent = ClaudeAgent(transport, this, maxIterations = 5)
        val r = agent.run("decision", Prompts.SYSTEM, Prompts.decisionPrompt(stateJson()),
            listOf("get_dj_state", "get_audio_analysis", "search_music", "get_song_structure", "select_next_track", "start_transition", "announce", "create_remix"))
        if (r.text.isNotBlank()) brain.lastDecision.value = "Claude: ${r.text.take(240)}"
        true
    } ?: false

    /** Free-form user request (voice or text). Returns a short reply, or null if Claude is unavailable. */
    fun command(utterance: String): String? {
        if (!isUsable()) return null
        brain.setAiState(AiState.THINKING)
        val res = guarded {
            purpose.set("command")
            ClaudeAgent(transport, this, maxIterations = 6).run("command", Prompts.SYSTEM, Prompts.commandPrompt(utterance, stateJson()))
        }
        brain.setAiState(statusAfter(res != null))
        return res?.text?.ifBlank { "Done." }
    }

    // ------------------------------------------------------------------ state snapshot (no audio, only metadata)

    fun stateJson(): String {
        val live = performer.live.value
        val s = settings.current
        val cur = live.current
        val an = cur?.analysis
        val o = buildJsonObject {
            put("party_mode", s.mode.name.lowercase())
            put("minutes_played", "%.1f".format(brain.minutesPlayed()).toDouble())
            put("energy_target", "%.2f".format(s.mode.profile(s.customGenres).energyTarget(brain.minutesPlayed()) + brain.steering.value.energyDelta).toDouble())
            put("steering", json.encodeToJsonElement(com.aidj.core.selection.SelectionSteering.serializer(), brain.steering.value))
            if (cur != null && an != null) put("current_track", buildJsonObject {
                put("id", cur.track.id); put("title", cur.track.title); put("artist", cur.track.artist)
                put("genres", buildJsonArray { cur.track.genres.forEach { add(it) } })
                put("bpm", "%.1f".format(an.bpm).toDouble()); put("key", an.key.camelot); put("energy", "%.2f".format(an.energy).toDouble())
                put("position_s", live.positionSec.toInt()); put("remaining_s", live.remainingSec.toInt())
                put("section", live.section?.name?.lowercase() ?: "unknown")
                put("outro_starts_in_s", (an.grid.barTime(an.outroStartBar) - live.positionSec).toInt())
                put("license", cur.track.capabilities.note)
            })
            put("master_bpm", "%.1f".format(live.masterBpm).toDouble())
            live.next?.let { n -> put("queued_next", buildJsonObject { put("id", n.track.id); put("title", n.track.title); put("artist", n.track.artist) }) }
            put("recent_history", buildJsonArray { brain.history.takeLast(8).forEach { id -> library.get(id)?.let { add(it.track.displayName) } } })
            put("user_queue", buildJsonArray { brain.manualQueue.value.forEach { id -> library.get(id)?.let { add(it.track.displayName) } } })
            putJsonArray("next_track_candidates") {
                brain.candidates.value.take(10).forEach { c ->
                    addJsonObject {
                        put("id", c.trackId); put("title", c.title); put("artist", c.artist); put("bpm", "%.1f".format(c.bpm).toDouble())
                        put("key", c.key); put("energy", "%.2f".format(c.energy).toDouble()); put("genres", buildJsonArray { c.genres.take(3).forEach { add(it) } })
                        put("score", "%.2f".format(c.score).toDouble()); if (c.keyShift != 0) put("key_shift", c.keyShift)
                    }
                }
            }
            put("library_size", library.tracks.value.count { it.analysis != null })
            taste?.topArtists(8)?.takeIf { it.isNotEmpty() }?.let { top ->
                put("user_taste_top_artists", buildJsonArray { top.forEach { add(it.first) } })
            }
        }
        return json.encodeToString(JsonObject.serializer(), o)
    }

    // ------------------------------------------------------------------ DjController (tools)

    private fun need(id: String): LibraryTrack = library.get(id) ?: throw ToolFailure("Unknown track id '$id'")
    private fun analysed(id: String): TrackAnalysis = need(id).analysis ?: runBlocking { analysis.analyseNow(id) } ?: throw ToolFailure("Track could not be analysed")
    private fun wrap(block: () -> JsonElement): JsonElement = try { block() } catch (e: CapabilityException) { throw ToolFailure(e.message ?: "not permitted") }

    override fun searchMusic(query: String, source: String, limit: Int): JsonElement = when (source) {
        "library" -> {
            val q = query.lowercase().split(' ').filter { it.isNotBlank() }
            val hits = library.tracks.value.filter { lt ->
                val hay = (lt.track.title + " " + lt.track.artist + " " + lt.track.album + " " + lt.track.genres.joinToString(" ") + " " + (lt.track.year ?: "")).lowercase()
                q.all { it in hay }
            }.take(limit)
            buildJsonObject { putJsonArray("results") { hits.forEach { add(trackSummary(it)) } } }
        }
        else -> {
            if (!isOnline()) throw ToolFailure("Offline")
            val type = if (source == "jamendo") SourceType.JAMENDO else SourceType.INTERNET_ARCHIVE
            val res = try { runBlocking { discovery.search(query, type, limit) } } catch (e: IOException) { throw ToolFailure(e.message ?: "search failed") }
            buildJsonObject {
                put("note", "Online results must be downloaded before they can play. Pass id to select_next_track to download+queue it (only if download_allowed).")
                putJsonArray("results") {
                    res.forEach { t ->
                        val key = "${t.source.name.lowercase()}:${t.sourceId}"
                        searchCache[key] = t
                        addJsonObject {
                            put("id", key); put("title", t.title); put("artist", t.artist); put("genres", buildJsonArray { t.genres.take(4).forEach { add(it) } })
                            put("license", t.licenseName); put("download_allowed", t.downloadAllowed)
                            put("remix_allowed", t.capabilities.remix); put("in_library", library.get(t.libraryId) != null)
                        }
                    }
                }
            }
        }
    }

    private fun trackSummary(lt: LibraryTrack) = buildJsonObject {
        put("id", lt.track.id); put("title", lt.track.title); put("artist", lt.track.artist)
        put("genres", buildJsonArray { lt.track.genres.take(4).forEach { add(it) } })
        lt.track.year?.let { put("year", it) }
        lt.analysis?.let { a -> put("bpm", "%.1f".format(a.bpm).toDouble()); put("key", a.key.camelot); put("energy", "%.2f".format(a.energy).toDouble()) }
        put("analysed", lt.analysis != null)
    }

    override fun getTrackMetadata(trackId: String): JsonElement {
        val lt = need(trackId)
        return buildJsonObject {
            put("id", lt.track.id); put("title", lt.track.title); put("artist", lt.track.artist); put("album", lt.track.album)
            put("duration_s", lt.track.durationMs / 1000); put("source", lt.track.source.name.lowercase())
            put("genres", buildJsonArray { lt.track.genres.forEach { add(it) } }); lt.track.year?.let { put("year", it) }
            lt.track.licenseUrl?.let { put("license_url", it) }
            put("capabilities", json.encodeToJsonElement(com.aidj.core.model.SourceCapabilities.serializer(), lt.track.capabilities))
            put("play_count", lt.playCount); put("skip_count", lt.skipCount); put("liked", lt.liked)
        }
    }

    override fun analyseAudio(trackId: String): JsonElement {
        val a = analysed(trackId)
        return buildJsonObject {
            put("bpm", "%.2f".format(a.bpm).toDouble()); put("grid_confidence", "%.2f".format(a.grid.confidence).toDouble())
            put("key", a.key.name); put("camelot", a.key.camelot); put("key_confidence", "%.2f".format(a.keyConfidence).toDouble())
            put("energy", "%.2f".format(a.energy).toDouble()); put("mood", a.mood); put("loudness_db", "%.1f".format(a.loudnessDb).toDouble())
            put("bars", a.bars); put("intro_end_bar", a.introEndBar); put("outro_start_bar", a.outroStartBar)
            put("drop_bars", buildJsonArray { a.dropBars.forEach { add(it) } })
            put("vocal_bars_ratio", "%.2f".format(a.barVocal.count { it > 0.6f }.toFloat() / maxOf(1, a.bars)).toDouble())
        }
    }

    override fun detectBpm(trackId: String): JsonElement = analysed(trackId).let { a ->
        buildJsonObject { put("bpm", "%.2f".format(a.bpm).toDouble()); put("confidence", "%.2f".format(a.grid.confidence).toDouble()); put("first_beat_s", "%.3f".format(a.grid.firstBeatSec).toDouble()) }
    }

    override fun detectKey(trackId: String): JsonElement = analysed(trackId).let { a ->
        buildJsonObject { put("key", a.key.name); put("camelot", a.key.camelot); put("confidence", "%.2f".format(a.keyConfidence).toDouble()) }
    }

    override fun getSongStructure(trackId: String): JsonElement = analysed(trackId).let { a ->
        buildJsonObject {
            put("bar_seconds", "%.3f".format(a.grid.beatPeriodSec * 4).toDouble())
            putJsonArray("sections") {
                a.sections.forEach { s -> addJsonObject {
                    put("type", s.type.name.lowercase()); put("start_bar", s.startBar); put("end_bar", s.endBar)
                    put("start_s", "%.1f".format(a.grid.barTime(s.startBar)).toDouble()); put("energy", "%.2f".format(s.energy).toDouble()); put("vocal", s.vocal)
                } }
            }
        }
    }

    override fun selectNextTrack(trackId: String?, genres: List<String>, energyDelta: Double?, reason: String): JsonElement {
        if (genres.isNotEmpty() || energyDelta != null) {
            brain.steering.value = brain.steering.value.copy(genres = genres.ifEmpty { brain.steering.value.genres },
                energyDelta = energyDelta?.toFloat() ?: brain.steering.value.energyDelta, note = reason)
        }
        var id = trackId
        if (id != null && (id.startsWith("jamendo:") || id.startsWith("internet_archive:"))) {
            val t = searchCache[id] ?: throw ToolFailure("Search for the track first")
            if (!t.downloadAllowed) throw ToolFailure("That track may not be downloaded")
            id = library.get(t.libraryId)?.track?.id ?: runBlocking { discovery.download(t) {} }.id
            runBlocking { analysis.analyseNow(id) } ?: throw ToolFailure("Downloaded but analysis failed")
        }
        if (id != null) {
            val lt = need(id)
            if (lt.analysis == null) runBlocking { analysis.analyseNow(id) } ?: throw ToolFailure("Track could not be analysed")
            if (!settings.current.allowExplicit && lt.track.explicit) throw ToolFailure("Explicit tracks are disabled")
            brain.claudeSelected(id, reason, fromDecision = purpose.get() == "decision")
            return DjTools.ok("queued" to lt.track.displayName, "reason" to reason)
        }
        brain.replanIfNotStarted()
        return DjTools.ok("steering" to "updated; local selector will pick the best match", "reason" to reason)
    }

    override fun setBpm(bpm: Double, overBeats: Double) = wrap { performer.setBpm(bpm.toFloat(), overBeats.toFloat()); DjTools.ok("bpm" to bpm) }
    override fun syncBeats(deck: String, enabled: Boolean) = wrap { performer.setSync(performer.deckIndex(deck), enabled); DjTools.ok("sync" to enabled) }
    override fun applyEq(deck: String, low: Double, mid: Double, high: Double, overBeats: Double) =
        wrap { performer.applyEq(performer.deckIndex(deck), low.toFloat(), mid.toFloat(), high.toFloat(), overBeats.toFloat()); DjTools.ok() }
    override fun applyEffect(deck: String, effect: String, amount: Double, beats: Double, holdBars: Double) =
        wrap { performer.applyEffect(performer.deckIndex(deck), effect, amount.toFloat(), beats.toFloat(), holdBars.toFloat()); DjTools.ok("effect" to effect) }
    override fun createLoop(deck: String, lengthBeats: Double, slip: Boolean, release: Boolean) =
        wrap { performer.loop(performer.deckIndex(deck), lengthBeats, slip, release); DjTools.ok() }

    override fun startTransition(style: String?, bars: Int?, drama: Double?, asap: Boolean): JsonElement {
        val st = style?.let { s -> TransitionStyle.entries.firstOrNull { it.name.equals(s, true) } }
        brain.setOverrides(TransitionOverrides(st, bars, drama?.toFloat(), asap))
        if (asap && !brain.performer.hasActivePlan) brain.skip()
        return DjTools.ok("style" to (st?.label ?: "planner's choice"), "asap" to asap)
    }

    override fun createRemix(element: String, pattern: String?, level: Double, bars: Int) =
        wrap { performer.remix(element, pattern, level.toFloat(), bars); DjTools.ok("element" to element) }

    override fun controlVirtualDj(move: String, intensity: Double): JsonElement { performer.gesture(move, intensity.toFloat()); return DjTools.ok("move" to move) }

    override fun getAudioAnalysis(): JsonElement {
        val l = performer.live.value
        val an = l.current?.analysis
        return buildJsonObject {
            put("section", l.section?.name?.lowercase() ?: "none"); put("bar_in_section", l.barInSection + 1); put("section_bars", l.sectionBars)
            put("beat_in_bar", l.beatInBar + 1); put("master_bpm", "%.2f".format(l.masterBpm).toDouble())
            put("live_energy", "%.2f".format(l.energy).toDouble()); put("vocal", l.vocal)
            put("bands", buildJsonObject { put("low", "%.3f".format(l.engine.low).toDouble()); put("mid", "%.3f".format(l.engine.mid).toDouble()); put("high", "%.3f".format(l.engine.high).toDouble()) })
            if (an != null) {
                val bar = an.grid.barIndexAt(l.positionSec)
                an.dropBars.firstOrNull { it > bar }?.let { put("next_drop_in_s", (an.grid.barTime(it) - l.positionSec).toInt()) }
                put("outro_in_s", (an.grid.barTime(an.outroStartBar) - l.positionSec).toInt())
                put("upcoming_sections", buildJsonArray { an.sections.filter { it.startBar > bar }.take(3).forEach { add("${it.type.name.lowercase()}@${(an.grid.barTime(it.startBar) - l.positionSec).toInt()}s") } })
                put("where", TrackSelector.describePosition(an, l.positionSec))
            }
            put("transition", if (l.transitionProgress >= 0) "${l.transitionLabel} (${(l.transitionProgress * 100).toInt()}%)" else "none")
            put("engine_cpu", "%.2f".format(l.engine.cpuLoad).toDouble())
        }
    }

    override fun getDjState(): JsonElement = json.parseToJsonElement(stateJson())

    override fun setPartyMode(mode: String, customGenres: List<String>): JsonElement {
        val m = PartyMode.entries.firstOrNull { it.name.equals(mode, true) } ?: throw ToolFailure("Unknown mode")
        brain.setMode(m, customGenres)
        return DjTools.ok("mode" to m.label)
    }

    override fun announce(text: String): JsonElement {
        val ok = brain.announcer?.say(text, fromAi = true) ?: false
        return if (ok) DjTools.ok("spoken" to text) else DjTools.ok("spoken" to false, "why" to "announcements disabled or too frequent")
    }
}
