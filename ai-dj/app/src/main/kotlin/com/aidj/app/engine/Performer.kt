package com.aidj.app.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aidj.app.data.AnalysisQueue
import com.aidj.app.data.AudioQuality
import com.aidj.app.data.SettingsStore
import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.SectionType
import com.aidj.core.model.TrackAnalysis
import com.aidj.core.model.TransitionStyle
import com.aidj.core.planning.ActionType
import com.aidj.core.planning.Curve
import com.aidj.core.planning.DrumPatterns
import com.aidj.core.planning.Param
import com.aidj.core.planning.Ramp
import com.aidj.core.planning.TransitionPlan
import com.aidj.engine.AudioDecoder
import com.aidj.engine.EngineState
import com.aidj.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

class CapabilityException(message: String) : Exception(message)

/**
 * Owns the two decks of the native engine. Loads tracks, schedules transition plans on the master
 * clock, exposes live state at ~60 Hz and derives musical events from engine state + analysis.
 * Nothing here is on the audio path: if this layer stalls, already-scheduled audio continues.
 */
class Performer(
    private val context: Context,
    val native: NativeEngine,
    private val analysisQueue: AnalysisQueue,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    data class Loaded(val track: LibraryTrack, val analysis: TrackAnalysis, val handleId: Int, val deck: Int)

    private class ActivePlan(val plan: TransitionPlan, val masterStart: Double, val outgoing: Loaded, val incoming: Loaded) {
        var started = false
        var handedOver = false
    }

    private val decoder = AudioDecoder(context)
    private val analyzer = AudioAnalyzer()
    private val loadMutex = Mutex()
    private val loaded = arrayOfNulls<Loaded>(2)
    @Volatile private var active: ActivePlan? = null
    @Volatile var currentDeck = 0
        private set
    @Volatile private var activity = "Waiting to start the party"
    @Volatile private var handTask = HandTask.NONE
    @Volatile private var handTaskUntilBeat = 0.0
    @Volatile private var paused = false

    private val _events = MutableSharedFlow<DjEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<DjEvent> = _events
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live

    val current: Loaded? get() = loaded[currentDeck]
    val incoming: Loaded? get() = active?.incoming ?: loaded[1 - currentDeck]?.takeIf { it.track.track.id != current?.track?.track?.id }
    val idleDeck: Int get() = 1 - currentDeck
    val hasActivePlan: Boolean get() = active != null
    val planStarted: Boolean get() = active?.started == true
    val activePlan: TransitionPlan? get() = active?.plan

    fun loadedOn(deck: Int): Loaded? = loaded[deck]

    fun start() {
        native.start()
        scope.launch(Dispatchers.Default) { pollLoop() }
    }

    // ------------------------------------------------------------------ loading

    /** Decode a track straight into native memory on [deck] (which must be idle). */
    suspend fun load(deck: Int, lt: LibraryTrack): Loaded = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val caps = lt.track.capabilities
            if (!caps.playback) throw CapabilityException("This source does not permit playback")
            setActivity("Loading ${lt.track.title}")
            val dec = decoder.decode(Uri.parse(lt.track.uri), native, wantAnalysis = lt.analysis == null && caps.analysis)
            val handle = dec.handle ?: throw IllegalStateException("No audio decoded")
            val analysis = lt.analysis ?: dec.analysisMono?.let { mono ->
                setActivity("Analysing ${lt.track.title}")
                analyzer.analyze(mono, dec.analysisRate).also { analysisQueue.store(lt.track.id, it) }
            } ?: throw CapabilityException("Track has no analysis")
            val old = loaded[deck]
            native.unload(deck)
            native.load(deck, handle, analysis.bpm, analysis.grid.firstBeatSec)
            native.resetDeck(deck)
            native.setKeylock(deck, settings.current.audioQuality != AudioQuality.BATTERY)
            native.setPitch(deck, 0.0)
            native.setSync(deck, false)
            val l = Loaded(lt.copy(analysis = analysis), analysis, handle.id, deck)
            loaded[deck] = l
            old?.let { native.releaseTrack(it.handleId) }
            l
        }
    }

    /** Start [l] immediately as the only/leading track (first track, emergency or manual start). */
    fun playNow(l: Loaded, atSec: Double = 0.0) {
        native.clearAutomation()
        active = null
        val other = 1 - l.deck
        native.pause(other)
        native.resetDeck(l.deck)
        native.setSync(l.deck, false)
        native.play(l.deck, atSec, anchorIfAlone = true)
        native.anchor(l.deck)
        native.setParam(Param.MASTER_BPM, -1, l.analysis.bpm.toFloat())
        if (l.track.track.capabilities.processing && l.analysis.grid.confidence >= 0.25f) native.setSync(l.deck, true)
        currentDeck = l.deck
        paused = false
        setActivity("Now playing ${l.track.track.title}")
        _events.tryEmit(DjEvent.TrackChanged(l.track))
    }

    // ------------------------------------------------------------------ transitions

    /** Schedule [plan] (built with outDeck = current deck) on the master clock. Returns false if it is already too late. */
    fun schedule(plan: TransitionPlan, incomingLoaded: Loaded): Boolean {
        val out = current ?: return false
        val st = native.readState()
        val pos = st.decks[out.deck].positionSec
        val deckBeat = out.analysis.grid.beatAt(pos)
        val offset = Math.round(st.masterBeat - deckBeat).toDouble()
        val masterStart = plan.startBeatOut + offset
        if (masterStart < st.masterBeat + 0.25) return false
        native.clearAutomation()
        // clearAutomation may have removed a live-remix layer's scheduled fade-out: silence the generators
        // (smoothed) so nothing is left stuck; a transition that wants drums ramps them up itself from 0.
        native.setParam(Param.DRUM_LEVEL, -1, 0f); native.setParam(Param.DRUM_BASS_LEVEL, -1, 0f); native.setParam(Param.RISER_LEVEL, -1, 0f)
        native.setGrid(plan.inDeck, plan.inSyncBpm, incomingLoaded.analysis.grid.firstBeatSec)
        native.setPitch(plan.inDeck, plan.keyShift.toDouble())
        for (r in plan.ramps) native.addRamp(r, masterStart)
        for (a in plan.actions) native.addAction(a, masterStart)
        active = ActivePlan(plan, masterStart, out, incomingLoaded)
        val secs = (masterStart - st.masterBeat) * 60.0 / st.masterBpm
        setActivity("Next: ${incomingLoaded.track.track.title} – ${plan.style.label} in ${secs.roundToLong()} s")
        return true
    }

    /** Cancel a scheduled transition that has not started yet. */
    fun cancelPlan(): Boolean {
        val a = active ?: return true
        if (a.started) return false
        native.clearAutomation()
        native.pause(a.incoming.deck)
        native.resetDeck(a.incoming.deck)
        native.setPitch(a.incoming.deck, 0.0)
        active = null
        return true
    }

    /** Abort a running transition and land fully on the incoming track (used by skip mid-mix). */
    fun completeNow() {
        val a = active ?: return
        native.clearAutomation()
        native.pause(a.outgoing.deck)
        for (p in listOf(Param.DECK_VOLUME, Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH)) native.setParam(p, a.incoming.deck, 1f)
        native.setParam(Param.FILTER, a.incoming.deck, 0f)
        native.setParam(Param.DRUM_LEVEL, -1, 0f); native.setParam(Param.DRUM_BASS_LEVEL, -1, 0f); native.setParam(Param.RISER_LEVEL, -1, 0f)
        native.exitLoop(a.incoming.deck)
        if (!a.handedOver) handOver(a)
        finish(a)
    }

    private fun handOver(a: ActivePlan) {
        a.handedOver = true
        currentDeck = a.incoming.deck
        _events.tryEmit(DjEvent.TrackChanged(a.incoming.track))
    }

    private fun finish(a: ActivePlan) {
        active = null
        native.resetDeck(a.outgoing.deck)
        _events.tryEmit(DjEvent.TransitionCompleted)
        setActivity("Riding ${a.incoming.track.track.title}")
    }

    // ------------------------------------------------------------------ transport

    fun pauseAll() {
        cancelPlan().let { if (!it) completeNow() }
        native.clearAutomation()
        native.pause(0); native.pause(1)
        native.setParam(Param.DRUM_LEVEL, -1, 0f); native.setParam(Param.DRUM_BASS_LEVEL, -1, 0f); native.setParam(Param.RISER_LEVEL, -1, 0f)
        paused = true
        setActivity("Paused")
    }

    fun resume() {
        val c = current ?: return
        val pos = native.readState().decks[c.deck].positionSec
        playNow(c, pos)
    }

    fun stopAll() {
        native.clearAutomation(); native.pause(0); native.pause(1)
        active = null; paused = false
        setActivity("Party stopped")
    }

    fun setMasterVolume(v: Float) = native.setParam(Param.MASTER_VOLUME, -1, v)

    // ------------------------------------------------------------------ manual / AI performance controls

    fun deckIndex(name: String): Int = when (name.lowercase()) {
        "a" -> 0; "b" -> 1; "next" -> incoming?.deck ?: idleDeck; else -> currentDeck
    }

    private fun requireProcessing(deck: Int) {
        val l = loaded[deck] ?: throw CapabilityException("Nothing loaded on that deck")
        if (!l.track.track.capabilities.processing) throw CapabilityException("License of '${l.track.track.title}' does not allow effects or tempo changes")
    }

    private fun requireRemix(deck: Int) {
        val l = loaded[deck] ?: throw CapabilityException("Nothing loaded on that deck")
        if (!l.track.track.capabilities.remix) throw CapabilityException("License of '${l.track.track.title}' does not allow remixing")
    }

    private fun nowBeat() = native.readState().masterBeat
    private fun nextBarBeat(): Double {
        val c = current ?: return Math.ceil(nowBeat())
        val st = native.readState()
        val pos = st.decks[c.deck].positionSec
        val deckBeat = c.analysis.grid.beatAt(pos)
        val offset = st.masterBeat - deckBeat
        val nextDown = Math.ceil((deckBeat - c.analysis.grid.downbeatPhase) / 4.0) * 4 + c.analysis.grid.downbeatPhase
        return nextDown + offset
    }

    fun applyEffect(deck: Int, effect: String, amount: Float, beats: Float, holdBars: Float) {
        requireProcessing(deck)
        val now = nowBeat()
        val end = now + holdBars * 4.0
        fun pulse(p: Param, v: Float, release: Float) {
            native.addRamp(Ramp(p, deck, 0.0, 0.5, null, v, Curve.EASE_OUT), now)
            native.addRamp(Ramp(p, deck, 0.0, 1.0, null, release, Curve.EASE_IN), end)
        }
        when (effect) {
            "echo" -> { native.setParam(Param.ECHO_BEATS, deck, beats); native.setParam(Param.ECHO_FEEDBACK, deck, 0.45f + 0.35f * amount); pulse(Param.ECHO_MIX, amount, 0f) }
            "reverb" -> pulse(Param.REVERB_MIX, amount, 0f)
            "flanger" -> pulse(Param.FLANGER_MIX, amount, 0f)
            "filter_lowpass" -> pulse(Param.FILTER, -amount, 0f)
            "filter_highpass" -> pulse(Param.FILTER, amount, 0f)
            "brake" -> native.brake(deck, beats.toDouble().coerceAtLeast(1.0))
            "impact" -> native.impact()
            "none" -> for (p in listOf(Param.ECHO_MIX, Param.REVERB_MIX, Param.FLANGER_MIX, Param.FILTER)) native.setParam(p, deck, 0f)
        }
        setHands(when (effect) { "filter_lowpass", "filter_highpass" -> HandTask.FILTER_KNOB; "brake" -> HandTask.JOG_NUDGE; else -> HandTask.FX_BUTTONS }, holdBars * 4.0)
        setActivity("FX: $effect")
    }

    fun applyEq(deck: Int, low: Float, mid: Float, high: Float, overBeats: Float) {
        requireProcessing(deck)
        val now = nowBeat()
        native.addRamp(Ramp(Param.EQ_LOW, deck, 0.0, overBeats.toDouble(), null, low, Curve.S_CURVE), now)
        native.addRamp(Ramp(Param.EQ_MID, deck, 0.0, overBeats.toDouble(), null, mid, Curve.S_CURVE), now)
        native.addRamp(Ramp(Param.EQ_HIGH, deck, 0.0, overBeats.toDouble(), null, high, Curve.S_CURVE), now)
        setHands(HandTask.EQ_KNOBS, max(4.0, overBeats.toDouble()))
    }

    fun setBpm(bpm: Float, overBeats: Float) {
        current?.let { requireProcessing(it.deck) }
        native.addRamp(Ramp(Param.MASTER_BPM, -1, 0.0, overBeats.toDouble(), null, bpm, Curve.S_CURVE), nowBeat())
        setHands(HandTask.JOG_NUDGE, max(4.0, overBeats.toDouble()))
        setActivity("Tempo → ${"%.1f".format(bpm)} BPM")
    }

    fun setSync(deck: Int, on: Boolean) { if (on) requireProcessing(deck); native.setSync(deck, on) }

    fun loop(deck: Int, beats: Double, slip: Boolean, release: Boolean) {
        if (release) { native.exitLoop(deck); return }
        requireRemix(deck)
        native.setLoop(deck, beats, slip)
        setHands(HandTask.LOOP_BUTTONS, 4.0)
        setActivity("Loop ${if (beats < 1) "1/${(1 / beats).toInt()}" else beats.toInt().toString()} beat${if (beats > 1) "s" else ""}")
    }

    /** Live remix elements generated by the engine. */
    fun remix(element: String, pattern: String?, level: Float, bars: Int) {
        val c = current ?: throw CapabilityException("Nothing playing")
        if (element != "stop_remix") requireRemix(c.deck)
        val start = nextBarBeat()
        val len = bars * 4.0
        when (element) {
            "drum_layer" -> {
                val p = pattern?.let { DrumPatterns.byName(it) } ?: DrumPatterns.suggest(c.analysis.bpm, c.track.track.genres)
                native.setDrumRoot(36 + c.analysis.key.pitchClass)
                native.addAction(com.aidj.core.planning.Action(ActionType.DRUM_PATTERN, 0, 0.0, p.id.toDouble()), start)
                native.addRamp(Ramp(Param.DRUM_LEVEL, -1, 0.0, 1.0, 0f, level, Curve.EASE_OUT), start)
                native.addRamp(Ramp(Param.DRUM_LEVEL, -1, len - 2, len, null, 0f, Curve.EASE_IN), start)
                // Patterns with a BASS lane (phonk 808s, four-on-the-floor sub) get the synth bass under the kit.
                native.addRamp(Ramp(Param.DRUM_BASS_LEVEL, -1, 0.0, 1.0, 0f, level * 0.8f, Curve.EASE_OUT), start)
                native.addRamp(Ramp(Param.DRUM_BASS_LEVEL, -1, len - 2, len, null, 0f, Curve.EASE_IN), start)
                setActivity("Live drums: ${p.name}")
                setHands(HandTask.FX_BUTTONS, 4.0)
            }
            "riser" -> {
                native.addRamp(Ramp(Param.RISER_PITCH, -1, 0.0, 0.0, 0f, 0f, Curve.STEP), start)
                native.addRamp(Ramp(Param.RISER_LEVEL, -1, 0.0, len, 0f, level, Curve.EASE_IN), start)
                native.addRamp(Ramp(Param.RISER_PITCH, -1, 0.0, len, 0f, 1f, Curve.EASE_IN), start)
                native.addRamp(Ramp(Param.RISER_LEVEL, -1, len, len, null, 0f, Curve.STEP), start)
                native.addAction(com.aidj.core.planning.Action(ActionType.IMPACT, 0, len), start)
                setActivity("Building a riser over $bars bars")
                setHands(HandTask.FILTER_KNOB, len)
            }
            "impact" -> native.impact()
            "beat_roll" -> {
                val d = c.deck
                val seq = listOf(0.0 to 1.0, len * 0.5 to 0.5, len * 0.75 to 0.25, len * 0.875 to 0.125)
                for ((at, sz) in seq) native.addAction(com.aidj.core.planning.Action(ActionType.SET_LOOP, d, at, sz, 1.0), start)
                native.addAction(com.aidj.core.planning.Action(ActionType.EXIT_LOOP, d, len), start)
                native.addRamp(Ramp(Param.FILTER, d, 0.0, len, null, 0.5f, Curve.EASE_IN), start)
                native.addRamp(Ramp(Param.FILTER, d, len, len + 0.5, null, 0f), start)
                setActivity("Beat roll")
                setHands(HandTask.LOOP_BUTTONS, len)
            }
            "extend_section" -> {
                val d = c.deck
                native.addAction(com.aidj.core.planning.Action(ActionType.SET_LOOP, d, 0.0, 32.0, 0.0), start)
                native.addAction(com.aidj.core.planning.Action(ActionType.EXIT_LOOP, d, max(32.0, len)), start)
                setActivity("Extending this section by $bars bars")
                setHands(HandTask.LOOP_BUTTONS, 4.0)
            }
            "stop_remix" -> {
                native.setParam(Param.DRUM_LEVEL, -1, 0f); native.setParam(Param.DRUM_BASS_LEVEL, -1, 0f)
                native.setParam(Param.RISER_LEVEL, -1, 0f); native.exitLoop(c.deck)
            }
        }
    }

    fun gesture(move: String, intensity: Float) { _events.tryEmit(DjEvent.Gesture(move, intensity)) }

    fun setActivity(text: String) { activity = text; _events.tryEmit(DjEvent.Activity(text)) }

    private fun setHands(task: HandTask, beats: Double) {
        handTask = task
        handTaskUntilBeat = native.readState().masterBeat + beats
    }

    // ------------------------------------------------------------------ state + events

    private var endedReported = -1
    private var lastBeat = Long.MIN_VALUE
    private var lastSection: SectionType? = null
    private var lastVocal = false
    private var lowAvg = 0f
    private var lastBassAt = 0L
    private var lastEnergy = -1f
    private var smoothEnergy = 0f

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try { tick() } catch (e: Exception) { Log.e("Performer", "tick failed", e) }
            delay(16)
        }
    }

    private fun tick() {
        val st = native.readState()
        native.collectGarbage()
        val a = active
        if (a != null) {
            val rel = st.masterBeat - a.masterStart
            if (!a.started && rel >= 0) {
                a.started = true
                _events.tryEmit(DjEvent.TransitionStarted(a.plan.style, a.plan.description))
                setActivity(a.plan.description)
            }
            if (a.started && !a.handedOver && rel >= a.plan.handoverBeat) handOver(a)
            if (a.started && rel >= a.plan.lengthBeats + 2) finish(a)
        }
        val c = current
        // Unplanned end of track (AI paused, failed plan...).
        if (c != null && st.decks[c.deck].ended && !st.decks[c.deck].playing && active == null && !paused) {
            if (endedReported != c.handleId) { endedReported = c.handleId; _events.tryEmit(DjEvent.TrackEnded(c.deck)) }
        }
        val pos = c?.let { st.decks[it.deck].positionSec } ?: 0.0
        val an = c?.analysis
        var section: SectionType? = null; var barIn = 0; var secBars = 0; var beatInBar = 0; var vocal = false
        var energy = 0f
        if (an != null) {
            val bar = an.grid.barIndexAt(pos)
            val s = an.sectionAtBar(bar)
            section = s?.type
            if (s != null) { barIn = bar - s.startBar; secBars = s.endBar - s.startBar }
            val deckBeat = floor(an.grid.beatAt(pos)).toLong()
            beatInBar = (((deckBeat - an.grid.downbeatPhase) % 4 + 4) % 4).toInt()
            vocal = (an.barVocal.getOrNull(bar) ?: 0f) > 0.6f
            val barE = an.barEnergy.getOrNull(bar) ?: an.energy
            // Live energy: track-level energy shaped by the current bar's analysed energy and the real output level.
            val liveLevel = (st.rms * 3f).coerceIn(0f, 1f)
            energy = (0.45f * an.energy + 0.4f * barE + 0.15f * liveLevel).coerceIn(0f, 1f)
        }
        smoothEnergy += (energy - smoothEnergy) * 0.05f

        if (st.decks.any { it.playing } && !paused) {
            val mb = floor(st.masterBeat).toLong()
            if (mb != lastBeat) {
                if (lastBeat != Long.MIN_VALUE) {
                    _events.tryEmit(DjEvent.Beat(beatInBar, mb, (st.low * 4f).coerceIn(0f, 1f)))
                    if (beatInBar == 0 && an != null) _events.tryEmit(DjEvent.Bar(an.grid.barIndexAt(pos), barIn % 8))
                }
                lastBeat = mb
            }
            // Real-time bass onsets from the master low band.
            lowAvg = lowAvg * 0.97f + st.low * 0.03f
            val now = System.currentTimeMillis()
            if (st.low > 0.04f && st.low > lowAvg * 1.5f && now - lastBassAt > 140) {
                lastBassAt = now
                _events.tryEmit(DjEvent.BassHit((st.low / (lowAvg + 1e-3f) / 3f).coerceIn(0f, 1f)))
            }
            if (section != lastSection && section != null) {
                _events.tryEmit(DjEvent.SectionChanged(section))
                when (section) {
                    SectionType.DROP -> _events.tryEmit(DjEvent.DropStarted)
                    SectionType.BUILDUP -> _events.tryEmit(DjEvent.BuildupStarted)
                    SectionType.BREAKDOWN -> _events.tryEmit(DjEvent.BreakdownStarted)
                    else -> {}
                }
            }
            if (vocal && !lastVocal) _events.tryEmit(DjEvent.VocalStarted)
            if (lastEnergy < 0 || abs(smoothEnergy - lastEnergy) > 0.12f) {
                if (lastEnergy >= 0) _events.tryEmit(DjEvent.EnergyChanged(smoothEnergy, smoothEnergy > lastEnergy))
                lastEnergy = smoothEnergy
            }
        }
        lastSection = section; lastVocal = vocal

        // Hands: explicit task, else derived from the running transition.
        var hands = if (st.masterBeat < handTaskUntilBeat) handTask else HandTask.NONE
        var progress = -1f
        var label = ""
        if (a != null && a.started) {
            val rel = st.masterBeat - a.masterStart
            progress = (rel / a.plan.lengthBeats).toFloat().coerceIn(0f, 1f)
            label = a.plan.description
            if (hands == HandTask.NONE) hands = when (a.plan.style) {
                TransitionStyle.BASS_SWAP, TransitionStyle.MASHUP_LAYER -> if (rel < 8) HandTask.FADERS else HandTask.EQ_KNOBS
                TransitionStyle.FILTER_SWEEP -> HandTask.FILTER_KNOB
                TransitionStyle.ECHO_OUT, TransitionStyle.DRUM_BRIDGE -> HandTask.FX_BUTTONS
                TransitionStyle.LOOP_ROLL_DROP -> HandTask.LOOP_BUTTONS
                TransitionStyle.BRAKE -> HandTask.JOG_NUDGE
                TransitionStyle.CUT, TransitionStyle.CROSSFADE -> HandTask.FADERS
            }
        } else if (a != null && !a.started && hands == HandTask.NONE) {
            val until = a.masterStart - st.masterBeat
            if (until < 16) hands = HandTask.CUE_HEADPHONES // pre-listening the next track, like a real DJ
        }
        val phase = when {
            paused -> Phase.PAUSED
            c == null -> Phase.IDLE
            a != null && a.started -> Phase.MIXING
            else -> Phase.PLAYING
        }
        val keyShift = st.decks.getOrNull(c?.deck ?: 0)?.pitchSemis?.toInt() ?: 0
        _live.value = LiveState(
            phase = phase, current = c?.track, next = a?.incoming?.track, currentDeck = currentDeck,
            positionSec = pos, durationSec = c?.analysis?.durationSec ?: 0.0, masterBpm = st.masterBpm, masterBeat = st.masterBeat,
            keyCamelot = an?.key?.transpose(keyShift)?.camelot ?: "", keyName = an?.key?.transpose(keyShift)?.name ?: "",
            energy = smoothEnergy, section = section, barInSection = barIn, sectionBars = secBars, beatInBar = beatInBar,
            transitionProgress = progress, transitionLabel = label, activity = activity, handTask = hands, engine = st,
            nextMixInSec = a?.takeIf { !it.started }?.let { (it.masterStart - st.masterBeat) * 60.0 / st.masterBpm } ?: -1.0,
            vocal = vocal,
        )
    }
}
