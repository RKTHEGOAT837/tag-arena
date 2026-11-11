package com.aidj.app.brain

import android.util.Log
import com.aidj.app.data.AnalysisQueue
import com.aidj.app.data.LibraryRepository
import com.aidj.app.data.SettingsStore
import com.aidj.app.engine.DjEvent
import com.aidj.app.engine.Performer
import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.PartyMode
import com.aidj.core.model.TransitionStyle
import com.aidj.core.planning.TransitionPlanner
import com.aidj.core.planning.TransitionRequest
import com.aidj.core.selection.ScoredCandidate
import com.aidj.core.selection.SelectionSteering
import com.aidj.core.selection.SetContext
import com.aidj.core.selection.TrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class BrainStatus { IDLE, STARTING, PLAYING, PAUSED }
enum class AiState(val label: String) { OFF("AI off"), LOCAL("Local AI"), THINKING("Cloud AI thinking"), CLAUDE("Cloud AI ready"), OFFLINE("Offline – local AI"), ERROR("Cloud AI unavailable – local AI") }

/** User/AI overrides for the next transition. */
data class TransitionOverrides(val style: TransitionStyle? = null, val bars: Int? = null, val drama: Float? = null, val asap: Boolean = false)

/**
 * The DJ's decision loop. Local algorithms handle everything time-critical (selection fallback,
 * planning, scheduling); Claude is consulted asynchronously once per track for high-level choices
 * and on user requests. If Claude is slow or offline, the local choice is used and the music never waits.
 */
class DjBrain(
    private val scope: CoroutineScope,
    val performer: Performer,
    private val library: LibraryRepository,
    private val analysis: AnalysisQueue,
    private val settings: SettingsStore,
) {
    private val selector = TrackSelector()
    private val planner = TransitionPlanner()
    private val mutex = Mutex()

    var claude: ClaudeOrchestrator? = null
    var announcer: Announcer? = null
    var taste: com.aidj.app.data.TasteStore? = null

    val sessionId = UUID.randomUUID().toString()
    private var sessionStart = 0L
    val history = ArrayList<String>()
    private val historyArtists = ArrayList<String>()

    private val _status = MutableStateFlow(BrainStatus.IDLE)
    val status: StateFlow<BrainStatus> = _status
    private val _ai = MutableStateFlow(AiState.LOCAL)
    val aiState: StateFlow<AiState> = _ai
    val steering = MutableStateFlow(SelectionSteering())
    val overrides = MutableStateFlow(TransitionOverrides())
    val manualQueue = MutableStateFlow<List<String>>(emptyList())
    /** AI DJ ON: automatic selection + creative transitions. OFF: plays the manual queue with plain crossfades. */
    val aiEnabled = MutableStateFlow(true)
    /** Pause AI decisions: no automatic selection/mixing until resumed. */
    val decisionsPaused = MutableStateFlow(false)
    /** Lock the current song: never mix out; extend with loops (where licensed) instead. */
    val locked = MutableStateFlow(false)
    val lastDecision = MutableStateFlow("")
    val candidates = MutableStateFlow<List<ScoredCandidate>>(emptyList())

    private var choosing: Job? = null
    @Volatile private var claudePick: String? = null
    @Volatile private var decisionOpen = false
    @Volatile private var claudeReason: String? = null
    private var lockedExtended = -1

    fun minutesPlayed(): Float = if (sessionStart == 0L) 0f else (System.currentTimeMillis() - sessionStart) / 60000f

    fun start() {
        scope.launch(Dispatchers.Default) {
            performer.events.collect { onEvent(it) }
        }
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                try { tick() } catch (e: Exception) { Log.e("DjBrain", "tick", e) }
                delay(300)
            }
        }
    }

    fun setAiState(s: AiState) { _ai.value = s }

    // ------------------------------------------------------------------ transport

    fun startParty() = scope.launch(Dispatchers.Default) {
        mutex.withLock {
            if (_status.value == BrainStatus.PLAYING) return@withLock
            if (_status.value == BrainStatus.PAUSED) { performer.resume(); _status.value = BrainStatus.PLAYING; return@withLock }
            _status.value = BrainStatus.STARTING
            sessionStart = System.currentTimeMillis()
            ensurePlayableMusic()
            val first = pickFirst()
            if (first == null) {
                performer.setActivity("No analysed tracks yet – import music or wait for analysis")
                _status.value = BrainStatus.IDLE
                return@withLock
            }
            try {
                val l = performer.load(0, first)
                performer.playNow(l)
                _status.value = BrainStatus.PLAYING
                announcer?.onPartyStart(first)
            } catch (e: Exception) {
                performer.setActivity("Could not start: ${e.message}")
                _status.value = BrainStatus.IDLE
            }
        }
    }

    fun resumeSession(trackId: String, positionSec: Double) = scope.launch(Dispatchers.Default) {
        mutex.withLock {
            val lt = library.get(trackId) ?: return@withLock
            sessionStart = System.currentTimeMillis()
            runCatching {
                val l = performer.load(0, lt)
                performer.playNow(l, positionSec)
                _status.value = BrainStatus.PLAYING
            }
        }
    }

    fun pause() { if (_status.value == BrainStatus.PLAYING) { choosing?.cancel(); performer.pauseAll(); _status.value = BrainStatus.PAUSED } }

    fun resume() {
        if (_status.value == BrainStatus.PAUSED) { performer.resume(); _status.value = BrainStatus.PLAYING }
        else if (_status.value == BrainStatus.IDLE) startParty()
    }

    fun togglePlay() = if (_status.value == BrainStatus.PLAYING) pause() else resume()

    fun stopParty() { choosing?.cancel(); performer.stopAll(); _status.value = BrainStatus.IDLE; settings.clearSession() }

    /** Skip: mix into the next track as soon as musically sensible (next phrase/bar). */
    fun skip() = scope.launch(Dispatchers.Default) {
        mutex.withLock {
            performer.current?.let { library.recordSkip(it.track.track.id) }
            if (performer.planStarted) { performer.completeNow(); return@withLock }
            performer.cancelPlan()
            choosing?.cancel()
            overrides.value = overrides.value.copy(asap = true, style = overrides.value.style ?: TransitionStyle.ECHO_OUT, bars = overrides.value.bars ?: 2)
        }
        prepareNext(immediate = true)
    }

    fun previous() {
        val prev = history.dropLast(1).lastOrNull() ?: return
        playNext(prev)
    }

    /** Play a specific track next, right away. */
    fun playNext(trackId: String) {
        manualQueue.value = listOf(trackId) + manualQueue.value.filter { it != trackId }
        skip()
    }

    fun enqueue(trackId: String) { manualQueue.value = manualQueue.value.filter { it != trackId } + trackId; replanIfNotStarted() }
    fun dequeue(trackId: String) { manualQueue.value = manualQueue.value.filter { it != trackId } }

    fun setMode(mode: PartyMode, custom: List<String> = settings.current.customGenres) {
        settings.update { it.copy(mode = mode, customGenres = if (mode == PartyMode.CUSTOM) custom else it.customGenres) }
        steering.value = steering.value.copy(genres = emptyList())
        performer.setActivity("${mode.label} mode")
        replanIfNotStarted()
    }

    fun steer(s: SelectionSteering) { steering.value = s; replanIfNotStarted() }
    fun setOverrides(o: TransitionOverrides) { overrides.value = o; replanIfNotStarted(keepTrack = true) }

    /** Re-choose / re-plan the upcoming transition if it has not started (so new instructions apply immediately). */
    fun replanIfNotStarted(keepTrack: Boolean = false) {
        if (_status.value != BrainStatus.PLAYING || performer.planStarted) return
        val keep = if (keepTrack) performer.incoming?.track?.track?.id else null
        choosing?.cancel()
        performer.cancelPlan()
        if (keep != null) manualQueue.value = listOf(keep) + manualQueue.value.filter { it != keep }
        prepareNext(immediate = overrides.value.asap)
    }

    // ------------------------------------------------------------------ the loop

    private suspend fun tick() {
        if (_status.value != BrainStatus.PLAYING) return
        val cur = performer.current ?: return
        val live = performer.live.value
        settings.saveSession(cur.track.track.id, live.positionSec, true)
        if (decisionsPaused.value) return
        if (locked.value) { maintainLock(cur, live.positionSec); return }
        if (performer.hasActivePlan || choosing?.isActive == true) return
        val a = cur.analysis
        val outroAt = a.grid.barTime(a.outroStartBar)
        val untilOutro = outroAt - live.positionSec
        // Prepare well ahead: Claude gets time to think, decoding/analysis gets time to finish.
        if (untilOutro < 100 || live.remainingSec < 110 || live.positionSec > 25) prepareNext(immediate = false)
    }

    private fun maintainLock(cur: Performer.Loaded, pos: Double) {
        val a = cur.analysis
        val outro = a.outroStartBar
        if (performer.hasActivePlan) { performer.cancelPlan() }
        if (cur.track.track.capabilities.remix && a.grid.barIndexAt(pos) >= outro - 1 && lockedExtended != outro) {
            // Loop an 8-bar phrase before the outro so the locked song keeps going.
            lockedExtended = outro
            performer.loop(cur.deck, 32.0, slip = false, release = false)
            performer.setActivity("Song locked: looping the groove")
        }
    }

    fun unlock() {
        locked.value = false
        lockedExtended = -1
        performer.current?.let { performer.loop(it.deck, 0.0, slip = false, release = true) }
    }

    private fun prepareNext(immediate: Boolean) {
        if (choosing?.isActive == true) return
        choosing = scope.launch(Dispatchers.Default) {
            try {
                val cur = performer.current ?: return@launch
                val profile = settings.current.mode.profile(settings.current.customGenres)
                val ctx = setContext()
                val ranked = selector.rank(library.tracks.value, ctx, profile, steering.value.copy(allowExplicit = settings.current.allowExplicit))
                candidates.value = ranked

                // 1) explicit user queue, 2) Claude's choice, 3) best local candidate.
                var nextId: String? = manualQueue.value.firstOrNull()?.also { manualQueue.value = manualQueue.value.drop(1) }
                var reason = if (nextId != null) "Requested" else ""
                if (nextId == null && aiEnabled.value && !immediate) {
                    val c = claude
                    if (c != null && c.isUsable()) {
                        claudePick = null; claudeReason = null
                        _ai.value = AiState.THINKING
                        // The Claude call blocks on network I/O, so run it separately and stop listening at the
                        // deadline: the music never waits for it, and a late answer is ignored.
                        decisionOpen = true
                        val call = scope.async(Dispatchers.IO) { c.decide() }
                        val ok = try { withTimeoutOrNull(30_000) { call.await() } } finally { decisionOpen = false }
                        _ai.value = c.statusAfter(ok)
                        claudePick?.let { nextId = it; reason = claudeReason ?: "Claude's pick" }
                    }
                }
                if (nextId == null) {
                    val best = ranked.firstOrNull() ?: run { performer.setActivity("No compatible analysed track to play next"); return@launch }
                    nextId = best.trackId
                    reason = best.reasons.joinToString(", ").ifBlank { "best overall match" }
                }
                val lt = library.get(nextId!!) ?: analysis.analyseNow(nextId!!)?.let { library.get(nextId!!) } ?: return@launch
                val ready = if (lt.analysis == null) library.get(lt.track.id)?.takeIf { it.analysis != null } ?: lt else lt
                val inDeck = performer.idleDeck
                val loaded = performer.load(inDeck, ready)
                val posNow = performer.live.value.positionSec
                val o = overrides.value
                val s = settings.current
                val req = TransitionRequest(
                    style = if (!aiEnabled.value) TransitionStyle.CROSSFADE else o.style,
                    bars = o.bars ?: s.transitionBars.takeIf { it > 0 },
                    drama = o.drama ?: s.remixIntensity,
                    asap = immediate || o.asap,
                    allowRemix = s.autoRemix && aiEnabled.value,
                    allowKeyShift = s.keyMatching,
                )
                var plan = planner.plan(cur.analysis, cur.track.track.capabilities, posNow, loaded.analysis, loaded.track.track.capabilities,
                    performer.live.value.masterBpm.takeIf { it > 0 } ?: cur.analysis.bpm, profile, req, cur.deck, inDeck)
                if (!performer.schedule(plan, loaded)) {
                    plan = planner.plan(cur.analysis, cur.track.track.capabilities, performer.live.value.positionSec, loaded.analysis,
                        loaded.track.track.capabilities, performer.live.value.masterBpm, profile, req.copy(asap = true), cur.deck, inDeck)
                    if (!performer.schedule(plan, loaded)) { performer.setActivity("Mix point passed – will cut on the next bar"); return@launch }
                }
                lastDecision.value = "${loaded.track.track.displayName}: $reason. ${plan.description}"
                Log.i("DjBrain", lastDecision.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DjBrain", "prepareNext failed", e)
                performer.setActivity("Couldn't prepare next track: ${e.message}")
                delay(3000)
            }
        }
    }

    /** Make sure at least two analysed tracks exist: analyse pending imports first, else create demo tracks. */
    private suspend fun ensurePlayableMusic() {
        fun ready() = library.tracks.value.count { it.analysis != null }
        if (ready() >= 2) return
        if (library.tracks.value.none { it.analysisError == null && it.track.capabilities.analysis && it.analysis == null } ) {
            performer.setActivity("No music yet – creating demo tracks…")
            library.addDemoTracks()
        }
        // Analyse up to four pending tracks right now (the background queue continues with the rest).
        val pending = library.tracks.value.filter { it.analysis == null && it.analysisError == null && it.track.capabilities.analysis }.take(4)
        for ((i, lt) in pending.withIndex()) {
            if (ready() >= 4) break
            performer.setActivity("Analysing music ${i + 1}/${pending.size}: ${lt.track.title}")
            analysis.analyseNow(lt.track.id)
        }
        if (ready() < 2) { performer.setActivity("Couldn't analyse enough tracks – creating demo tracks…"); library.addDemoTracks()
            library.tracks.value.filter { it.analysis == null && it.track.artist == "AI DJ Demo" }.forEach { analysis.analyseNow(it.track.id) } }
    }

    private fun setContext() = SetContext(
        current = performer.current?.track, history = history.toList(), historyArtists = historyArtists.toList(),
        minutesPlayed = minutesPlayed(), allowKeyShift = settings.current.keyMatching,
        tasteScores = taste?.scoresFor(library.tracks.value) ?: emptyMap(),
    )

    private fun pickFirst(): LibraryTrack? {
        manualQueue.value.firstOrNull()?.let { id -> manualQueue.value = manualQueue.value.drop(1); library.get(id)?.takeIf { it.analysis != null }?.let { return it } }
        val profile = settings.current.mode.profile(settings.current.customGenres)
        val ranked = selector.rank(library.tracks.value, setContext(), profile,
            steering.value.copy(energyTarget = steering.value.energyTarget ?: (profile.baseEnergy - 0.1f), allowExplicit = settings.current.allowExplicit))
        return ranked.firstOrNull()?.let { library.get(it.trackId) }
    }

    // Called by the Claude tool controller.
    fun claudeSelected(trackId: String, reason: String, fromDecision: Boolean) {
        if (fromDecision) {
            // Periodic decision: only accepted while the brain is still waiting for it.
            if (decisionOpen) { claudePick = trackId; claudeReason = reason }
            return
        }
        // A user request handled by Claude (voice/text): queue it first and re-plan immediately.
        manualQueue.value = listOf(trackId) + manualQueue.value.filter { it != trackId }
        replanIfNotStarted()
    }

    private fun onEvent(e: DjEvent) {
        when (e) {
            is DjEvent.TrackChanged -> {
                val id = e.track.track.id
                if (history.lastOrNull() != id) {
                    history += id; historyArtists += e.track.track.artist
                    library.recordPlay(id, sessionId)
                }
                overrides.value = TransitionOverrides()
                lockedExtended = -1
                announcer?.onTrackChanged(e.track, history.size)
            }
            is DjEvent.TrackEnded -> if (_status.value == BrainStatus.PLAYING) {
                // Nothing was scheduled (AI decisions paused or a failure): keep the party going.
                scope.launch(Dispatchers.Default) {
                    val next = manualQueue.value.firstOrNull() ?: if (!decisionsPaused.value) candidates.value.firstOrNull()?.trackId
                        ?: selector.rank(library.tracks.value, setContext(), settings.current.mode.profile(settings.current.customGenres)).firstOrNull()?.trackId else null
                    if (next == null) { performer.setActivity("Queue empty – pick a track"); return@launch }
                    manualQueue.value = manualQueue.value.filter { it != next }
                    val lt = library.get(next) ?: return@launch
                    runCatching { performer.playNow(performer.load(performer.idleDeck, lt)) }
                }
            }
            is DjEvent.DropStarted -> announcer?.onDrop()
            is DjEvent.TransitionStarted -> announcer?.onTransition(e.style)
            else -> {}
        }
    }
}
