package com.aidj.core.planning

import com.aidj.core.model.ModeProfile
import com.aidj.core.model.SectionType
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.TrackAnalysis
import com.aidj.core.model.TransitionStyle
import com.aidj.core.selection.Harmonic
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

@Serializable
data class TransitionRequest(
    /** Force a style (from the user or Claude). Falls back if infeasible for these tracks. */
    val style: TransitionStyle? = null,
    /** Override blend length in bars (1..64). */
    val bars: Int? = null,
    /** 0..1: how dramatic (FX, risers, impacts) the transition should be. */
    val drama: Float? = null,
    /** Start as soon as possible (next bar / phrase) instead of waiting for the outro. */
    val asap: Boolean = false,
    val allowRemix: Boolean = true,
    val allowKeyShift: Boolean = true,
    val maxKeyShift: Int = 2,
    /** Return B to its own tempo after the mix (true) or keep the set tempo (false). */
    val glideToNativeTempo: Boolean = true,
)

@Serializable
data class TransitionPlan(
    val style: TransitionStyle,
    val description: String,
    /** Deck that is playing (A) and deck that comes in (B). */
    val outDeck: Int,
    val inDeck: Int,
    /** Beat index in A's grid where the plan starts (beat 0 of the plan). */
    val startBeatOut: Int,
    val startTimeOutSec: Double,
    /** Position (s) in B where B starts playing. */
    val inCueSec: Double,
    val lengthBeats: Double,
    /** Plan beat at which B becomes the "current" track. */
    val handoverBeat: Double,
    /** B's pitch shift (semitones) for key matching. */
    val keyShift: Int,
    /** Whether B is tempo-synced to the master clock. */
    val syncIn: Boolean,
    /** BPM the engine should use as B's grid tempo for sync (B's BPM, doubled/halved when mixed double/half-time). */
    val inSyncBpm: Double,
    val ramps: List<Ramp>,
    val actions: List<Action>,
    val usesRemix: Boolean,
    val notes: List<String>,
)

/**
 * Builds beat-accurate transition plans. Everything is expressed in beats relative to the plan start,
 * and is executed sample-accurately by the native engine against its master clock, so the mix does not
 * depend on UI-thread or network timing.
 */
class TransitionPlanner(private val random: Random = Random.Default) {

    fun plan(
        a: TrackAnalysis, aCaps: SourceCapabilities, aPosSec: Double,
        b: TrackAnalysis, bCaps: SourceCapabilities,
        masterBpm: Double, profile: ModeProfile, req: TransitionRequest = TransitionRequest(),
        outDeck: Int = 0, inDeck: Int = 1,
    ): TransitionPlan {
        val notes = ArrayList<String>()
        val bpmDiffPct = abs(Harmonic.tempoMatch(masterBpm, b.bpm).first - 1.0) * 100
        val canProcessA = aCaps.processing
        val canProcessB = bCaps.processing
        val gridsOk = a.grid.confidence >= 0.25f && b.grid.confidence >= 0.25f
        val canSync = canProcessB && gridsOk && bpmDiffPct <= 8.0
        val canRemix = req.allowRemix && aCaps.remix && bCaps.remix
        val drama = (req.drama ?: profile.fxIntensity).coerceIn(0f, 1f)

        if (!gridsOk) notes += "Low beat-grid confidence: avoiding long beatmatched blends"
        if (!canProcessB) notes += "Incoming track license forbids processing: unsynced, unmodified entry"
        if (!canProcessA) notes += "Outgoing track license forbids processing: no effects on it"

        // ---- feasible styles
        val feasible = TransitionStyle.entries.filter { s ->
            when (s) {
                TransitionStyle.BASS_SWAP -> canSync && canProcessA
                TransitionStyle.FILTER_SWEEP -> canSync && canProcessA
                TransitionStyle.MASHUP_LAYER -> canSync && canRemix && canProcessA &&
                    Harmonic.camelotScore(a.key, b.key) >= 0.85f
                TransitionStyle.ECHO_OUT -> canProcessA
                TransitionStyle.LOOP_ROLL_DROP -> canProcessA && canRemix && b.dropBars.isNotEmpty()
                TransitionStyle.BRAKE -> canProcessA
                TransitionStyle.DRUM_BRIDGE -> canRemix && canProcessA && canProcessB
                TransitionStyle.CUT, TransitionStyle.CROSSFADE -> true
            }
        }
        val style = req.style?.takeIf { it in feasible } ?: run {
            if (req.style != null) notes += "${req.style.label} not possible here; choosing an alternative"
            chooseStyle(feasible, profile, drama, bpmDiffPct, canSync)
        }

        // ---- key shift (only meaningful when B is processed/synced)
        val keyShift = if (req.allowKeyShift && canProcessB && style != TransitionStyle.CUT && style != TransitionStyle.BRAKE)
            Harmonic.bestKeyShift(a.key, b.key, req.maxKeyShift).first else 0
        if (keyShift != 0) notes += "Shifting incoming key ${if (keyShift > 0) "+" else ""}$keyShift st to ${b.key.transpose(keyShift).camelot}"

        // ---- length
        val defaultBars = when (style) {
            TransitionStyle.BASS_SWAP -> profile.blendBars
            TransitionStyle.FILTER_SWEEP -> min(16, profile.blendBars)
            TransitionStyle.MASHUP_LAYER -> 32
            TransitionStyle.CROSSFADE -> if (canSync) min(16, profile.blendBars) else 2
            TransitionStyle.ECHO_OUT -> 2
            TransitionStyle.LOOP_ROLL_DROP -> 8
            TransitionStyle.CUT -> 1
            TransitionStyle.BRAKE -> 1
            TransitionStyle.DRUM_BRIDGE -> 8
        }
        var bars = (req.bars ?: defaultBars).coerceIn(1, 64)

        // ---- where in A to start
        val curBar = a.grid.barIndexAt(aPosSec)
        val aBars = a.bars
        val minStart = curBar + if (req.asap) 1 else 2
        bars = min(bars, max(1, aBars - minStart))
        var startBar = if (req.asap) nextPhrase(curBar + 1, 4) else run {
            // Aim to finish the blend as the outro ends: start = outro start, or earlier if the outro is short.
            val ideal = min(a.outroStartBar, aBars - bars)
            max(minStart, phraseAlign(ideal, a))
        }
        if (startBar + bars > aBars) startBar = max(minStart, aBars - bars)
        if (startBar < minStart) startBar = minStart
        val startBeatOut = a.grid.barStartBeat(startBar)
        val startTime = a.grid.beatTime(startBeatOut.toDouble())

        // ---- where in B to start
        val L = bars * 4.0
        val bIntroBars = b.introEndBar
        var inBar = 0
        var extendIntroBeats = 0.0
        when (style) {
            TransitionStyle.LOOP_ROLL_DROP -> inBar = b.dropBars.first()
            TransitionStyle.CUT, TransitionStyle.BRAKE, TransitionStyle.ECHO_OUT ->
                // Land on the first strong section of B (skip a sparse intro) for impact.
                inBar = if (drama > 0.5f) (b.dropBars.firstOrNull() ?: bIntroBars) else bIntroBars.coerceAtMost(16)
            else -> {
                if (bIntroBars > bars) inBar = phraseFloor(bIntroBars - bars) // groove arrives right as the blend ends
                else if (bIntroBars in 1 until bars / 2 && canRemix) {
                    // Intro too short for the blend: extend it with a 4-bar loop (genuine intro extension).
                    extendIntroBeats = ((bars - bIntroBars) * 4).toDouble().coerceAtMost(L - 4)
                    notes += "Extending B's intro by ${(extendIntroBeats / 4).toInt()} bars with a loop"
                }
            }
        }
        inBar = inBar.coerceIn(0, max(0, b.bars - 1))
        val inCue = max(0.0, b.grid.barTime(inBar))

        val r = ArrayList<Ramp>()
        val act = ArrayList<Action>()
        val A = outDeck; val B = inDeck
        fun ramp(p: Param, d: Int, s: Double, e: Double, to: Float, from: Float? = null, c: Curve = Curve.LINEAR) {
            r += Ramp(p, d, s, e, from, to, c)
        }
        fun at(t: ActionType, d: Int, beat: Double, x: Double = 0.0, y: Double = 0.0) { act += Action(t, d, beat, x, y) }

        var handover: Double
        var usesRemix = false
        var bStartBeat = 0.0
        when (style) {
            TransitionStyle.BASS_SWAP -> {
                ramp(Param.EQ_LOW, B, 0.0, 0.0, 0f, c = Curve.STEP)
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 0f, c = Curve.STEP)
                at(ActionType.START_DECK, B, 0.0, inCue)
                ramp(Param.DECK_VOLUME, B, 0.0, L * 0.4, 1f, c = Curve.EASE_OUT)
                ramp(Param.EQ_HIGH, A, L * 0.25, L * 0.5, 0.55f, c = Curve.S_CURVE)
                // The swap itself: on the middle phrase boundary, over one beat.
                val swap = phraseMid(L)
                ramp(Param.EQ_LOW, A, swap, swap + 1, 0f, c = Curve.S_CURVE)
                ramp(Param.EQ_LOW, B, swap, swap + 1, 1f, c = Curve.S_CURVE)
                ramp(Param.EQ_MID, A, swap, L * 0.85, 0.6f)
                ramp(Param.DECK_VOLUME, A, L * 0.7, L, 0f, c = Curve.EASE_IN)
                if (drama > 0.6f) ramp(Param.FILTER, A, L * 0.6, L, 0.5f)
                handover = swap
            }
            TransitionStyle.FILTER_SWEEP -> {
                ramp(Param.FILTER, B, 0.0, 0.0, -0.85f, c = Curve.STEP)
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 0f, c = Curve.STEP)
                at(ActionType.START_DECK, B, 0.0, inCue)
                ramp(Param.DECK_VOLUME, B, 0.0, L * 0.35, 1f, c = Curve.EASE_OUT)
                ramp(Param.FILTER, A, 0.0, L, 0.9f, c = Curve.EASE_IN)
                ramp(Param.FILTER, B, L * 0.25, L, 0f, c = Curve.EASE_OUT)
                ramp(Param.EQ_LOW, A, L * 0.45, L * 0.5, 0f)
                ramp(Param.DECK_VOLUME, A, L * 0.75, L, 0f)
                if (drama > 0.4f) {
                    ramp(Param.ECHO_BEATS, A, 0.0, 0.0, 0.5f, c = Curve.STEP)
                    ramp(Param.ECHO_FEEDBACK, A, 0.0, 0.0, 0.55f, c = Curve.STEP)
                    ramp(Param.ECHO_MIX, A, L * 0.6, L * 0.9, 0.45f)
                }
                handover = L * 0.5
            }
            TransitionStyle.MASHUP_LAYER -> {
                usesRemix = true
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 0f, c = Curve.STEP)
                ramp(Param.EQ_LOW, B, 0.0, 0.0, 0f, c = Curve.STEP)
                ramp(Param.EQ_MID, B, 0.0, 0.0, 0.7f, c = Curve.STEP)
                at(ActionType.START_DECK, B, 0.0, inCue)
                ramp(Param.DECK_VOLUME, B, 0.0, 16.0, 0.9f, c = Curve.EASE_OUT)
                // Mashup body: A keeps its vocals/mids, B supplies the low end from bar 8.
                ramp(Param.EQ_LOW, A, 32.0, 33.0, 0f, c = Curve.S_CURVE)
                ramp(Param.EQ_LOW, B, 32.0, 33.0, 1f, c = Curve.S_CURVE)
                ramp(Param.EQ_HIGH, A, 32.0, 64.0, 0.6f)
                ramp(Param.EQ_MID, B, 64.0, 96.0, 1f)
                ramp(Param.DECK_VOLUME, B, 96.0, 100.0, 1f)
                ramp(Param.DECK_VOLUME, A, 96.0, L, 0f, c = Curve.EASE_IN)
                ramp(Param.FILTER, A, 96.0, L, 0.6f)
                handover = 64.0
            }
            TransitionStyle.CROSSFADE -> {
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 0f, c = Curve.STEP)
                at(ActionType.START_DECK, B, 0.0, inCue)
                ramp(Param.DECK_VOLUME, B, 0.0, L, 1f, c = Curve.EASE_OUT)
                ramp(Param.DECK_VOLUME, A, 0.0, L, 0f, c = Curve.EASE_IN)
                handover = L / 2
            }
            TransitionStyle.ECHO_OUT -> {
                // Echo throw on A's last beat, cut A on the downbeat, let the tail ring into B.
                bars = 2
                ramp(Param.ECHO_BEATS, A, 0.0, 0.0, if (drama > 0.6f) 0.75f else 1f, c = Curve.STEP)
                ramp(Param.ECHO_FEEDBACK, A, 0.0, 0.0, 0.62f + 0.2f * drama, c = Curve.STEP)
                ramp(Param.ECHO_MIX, A, 2.0, 3.0, 0.7f)
                ramp(Param.EQ_LOW, A, 3.0, 4.0, 0f)
                ramp(Param.DECK_VOLUME, A, 4.0, 4.0, 0f, c = Curve.STEP)
                if (drama > 0.5f && canProcessA) ramp(Param.REVERB_MIX, A, 2.0, 4.0, 0.35f)
                bStartBeat = 4.0
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 1f, c = Curve.STEP)
                at(ActionType.START_DECK, B, bStartBeat, inCue)
                ramp(Param.ECHO_MIX, A, 8.0, 8.0, 0f, c = Curve.STEP)
                ramp(Param.REVERB_MIX, A, 8.0, 8.0, 0f, c = Curve.STEP)
                handover = bStartBeat
            }
            TransitionStyle.LOOP_ROLL_DROP -> {
                usesRemix = true
                // 8-bar build: progressively shorter slip-loops, HPF sweep and a riser, then slam into B's drop.
                at(ActionType.SET_LOOP, A, 16.0, 4.0, 1.0)
                at(ActionType.SET_LOOP, A, 24.0, 2.0, 1.0)
                at(ActionType.SET_LOOP, A, 28.0, 1.0, 1.0)
                at(ActionType.SET_LOOP, A, 30.0, 0.5, 1.0)
                at(ActionType.SET_LOOP, A, 31.0, 0.25, 1.0)
                at(ActionType.SET_LOOP, A, 31.5, 0.125, 1.0)
                ramp(Param.FILTER, A, 0.0, 32.0, 0.7f, c = Curve.EASE_IN)
                ramp(Param.EQ_LOW, A, 24.0, 30.0, 0f)
                ramp(Param.RISER_PITCH, 0, 0.0, 0.0, 0f, c = Curve.STEP)
                ramp(Param.RISER_LEVEL, 0, 0.0, 32.0, 0.55f * (0.5f + drama), c = Curve.EASE_IN)
                ramp(Param.RISER_PITCH, 0, 0.0, 32.0, 1f, c = Curve.EASE_IN)
                ramp(Param.RISER_LEVEL, 0, 32.0, 32.0, 0f, c = Curve.STEP)
                at(ActionType.EXIT_LOOP, A, 32.0)
                at(ActionType.STOP_DECK, A, 32.0)
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 1f, c = Curve.STEP)
                bStartBeat = 32.0
                at(ActionType.START_DECK, B, bStartBeat, inCue)
                at(ActionType.IMPACT, 0, 32.0)
                bars = 8
                handover = 32.0
            }
            TransitionStyle.CUT -> {
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 1f, c = Curve.STEP)
                if (canProcessA && drama > 0.3f) {
                    ramp(Param.ECHO_BEATS, A, 0.0, 0.0, 0.5f, c = Curve.STEP)
                    ramp(Param.ECHO_FEEDBACK, A, 0.0, 0.0, 0.45f, c = Curve.STEP)
                    ramp(Param.ECHO_MIX, A, 0.0, 0.0, 0.5f, c = Curve.STEP)
                    ramp(Param.ECHO_MIX, A, 4.0, 4.0, 0f, c = Curve.STEP)
                }
                ramp(Param.DECK_VOLUME, A, 0.0, 0.0, 0f, c = Curve.STEP)
                at(ActionType.START_DECK, B, 0.0, inCue)
                if (drama > 0.6f) at(ActionType.IMPACT, 0, 0.0)
                handover = 0.0
                bars = 1
            }
            TransitionStyle.BRAKE -> {
                at(ActionType.BRAKE, A, 0.0, 2.0)
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 1f, c = Curve.STEP)
                bStartBeat = 4.0
                at(ActionType.START_DECK, B, bStartBeat, inCue)
                if (drama > 0.4f) at(ActionType.IMPACT, 0, 4.0)
                handover = 4.0
                bars = 1
            }
            TransitionStyle.DRUM_BRIDGE -> {
                usesRemix = true
                // A echoes out over the drum machine, the drums carry the tempo to B's BPM, B enters on top.
                val pattern = DrumPatterns.FOUR_ON_FLOOR
                at(ActionType.DRUM_PATTERN, 0, 0.0, pattern.id.toDouble())
                ramp(Param.DRUM_LEVEL, 0, 0.0, 4.0, 0.85f, from = 0f, c = Curve.EASE_OUT)
                ramp(Param.ECHO_BEATS, A, 0.0, 0.0, 0.75f, c = Curve.STEP)
                ramp(Param.ECHO_FEEDBACK, A, 0.0, 0.0, 0.6f, c = Curve.STEP)
                ramp(Param.ECHO_MIX, A, 2.0, 4.0, 0.6f)
                ramp(Param.DECK_VOLUME, A, 4.0, 8.0, 0f, c = Curve.EASE_IN)
                at(ActionType.STOP_DECK, A, 8.0)
                val bridgeEnd = 24.0
                // Tempo glide carried by the drums (the only sound source not tied to a track's native tempo).
                ramp(Param.MASTER_BPM, 0, 8.0, bridgeEnd, b.bpm.toFloat(), c = Curve.S_CURVE)
                ramp(Param.ECHO_MIX, A, 12.0, 12.0, 0f, c = Curve.STEP)
                ramp(Param.DECK_VOLUME, B, 0.0, 0.0, 0f, c = Curve.STEP)
                bStartBeat = bridgeEnd
                at(ActionType.START_DECK, B, bStartBeat, inCue)
                ramp(Param.DECK_VOLUME, B, bridgeEnd, bridgeEnd + 4, 1f, c = Curve.EASE_OUT)
                ramp(Param.DRUM_LEVEL, 0, bridgeEnd + 4, bridgeEnd + 8, 0f, c = Curve.EASE_IN)
                bars = 8
                handover = bridgeEnd
            }
        }

        // Intro extension: loop B's first 4 bars until the blend no longer needs it.
        if (extendIntroBeats > 0 && style in setOf(TransitionStyle.BASS_SWAP, TransitionStyle.FILTER_SWEEP, TransitionStyle.CROSSFADE)) {
            at(ActionType.SET_LOOP, B, 0.0, 16.0, 0.0)
            at(ActionType.EXIT_LOOP, B, extendIntroBeats - (extendIntroBeats % 16))
            usesRemix = true
        }

        val totalBeats = max(bars * 4.0, max(handover, bStartBeat) + 4)
        // Finish: stop A, reset its params for the next load, restore B to neutral.
        at(ActionType.STOP_DECK, A, totalBeats)
        at(ActionType.RESET_DECK, A, totalBeats + 0.01)
        val endNeutral = totalBeats
        for (p in listOf(Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH)) ramp(p, B, endNeutral, endNeutral + 2, 1f)
        ramp(Param.FILTER, B, endNeutral, endNeutral + 2, 0f)
        ramp(Param.DECK_VOLUME, B, endNeutral, endNeutral + 1, 1f)

        // After the mix, glide the master tempo to B's own BPM (DJs bring the new record back to 0% pitch).
        val syncIn = canSync || style == TransitionStyle.DRUM_BRIDGE
        if (syncIn && req.glideToNativeTempo && style != TransitionStyle.DRUM_BRIDGE) {
            val nativeEff = Harmonic.tempoMatch(masterBpm, b.bpm).second
            if (abs(nativeEff - masterBpm) > 0.3) {
                ramp(Param.MASTER_BPM, 0, totalBeats + 4, totalBeats + 4 + 64, nativeEff.toFloat(), c = Curve.S_CURVE)
            }
        }
        if (!syncIn) {
            // Unsynced entry: B plays at native speed; re-anchor the master clock to B when it starts.
            at(ActionType.SET_SYNC, B, bStartBeat - 0.001, 0.0)
            at(ActionType.ANCHOR_MASTER, B, bStartBeat + 0.01)
        } else {
            at(ActionType.SET_SYNC, B, bStartBeat - 0.001, 1.0)
        }

        val desc = buildString {
            append(style.label)
            append(" over ${bars} bar${if (bars > 1) "s" else ""}")
            if (bpmDiffPct > 0.5 && syncIn) append(", beatmatched (${"%.1f".format(bpmDiffPct)}% tempo change)")
            if (keyShift != 0) append(", key ${if (keyShift > 0) "+" else ""}$keyShift st")
            if (usesRemix) append(", live remix elements")
        }
        return TransitionPlan(
            style = style, description = desc, outDeck = A, inDeck = B,
            startBeatOut = startBeatOut, startTimeOutSec = startTime, inCueSec = inCue,
            lengthBeats = totalBeats, handoverBeat = handover, keyShift = keyShift, syncIn = syncIn,
            inSyncBpm = if (style == TransitionStyle.DRUM_BRIDGE) b.bpm else Harmonic.tempoMatch(masterBpm, b.bpm).second,
            ramps = r.map { it.copy(to = it.param.clamp(it.to), from = it.from?.let(it.param::clamp)) },
            actions = act.sortedBy { it.beat }, usesRemix = usesRemix, notes = notes,
        )
    }

    private fun chooseStyle(
        feasible: List<TransitionStyle>, profile: ModeProfile, drama: Float, bpmDiffPct: Double, canSync: Boolean,
    ): TransitionStyle {
        if (bpmDiffPct > 8 && TransitionStyle.DRUM_BRIDGE in feasible && random.nextFloat() < 0.6f) return TransitionStyle.DRUM_BRIDGE
        val weights = feasible.associateWith { s ->
            var w = profile.styleWeights[s] ?: 0.05f
            when (s) {
                TransitionStyle.LOOP_ROLL_DROP, TransitionStyle.BRAKE -> w *= 0.4f + 1.4f * drama
                TransitionStyle.ECHO_OUT, TransitionStyle.CUT -> w *= if (!canSync) 2f else 0.6f + 0.6f * drama
                TransitionStyle.BASS_SWAP, TransitionStyle.CROSSFADE -> w *= 1.4f - 0.8f * drama
                else -> {}
            }
            w
        }
        val total = weights.values.sum()
        if (total <= 0f) return feasible.firstOrNull() ?: TransitionStyle.CUT
        var x = random.nextFloat() * total
        for ((s, w) in weights) { x -= w; if (x <= 0f) return s }
        return weights.keys.last()
    }

    private fun phraseMid(L: Double): Double {
        val half = L / 2
        return (half / 4).toInt() * 4.0 // on a bar boundary
    }

    private fun nextPhrase(bar: Int, unit: Int) = ceil(bar / unit.toDouble()).toInt() * unit

    private fun phraseFloor(bar: Int) = (bar / 4) * 4

    /** Snap a bar to the start of a section or an 8-bar phrase near it. */
    private fun phraseAlign(bar: Int, a: TrackAnalysis): Int {
        val starts = a.sections.map { it.startBar }
        val near = starts.minByOrNull { abs(it - bar) }
        if (near != null && abs(near - bar) <= 4 && near <= bar) return near
        return (bar / 8) * 8
    }

    companion object {
        /** True when a section type is a good moment to begin mixing out. */
        fun isMixOutSection(t: SectionType?) = t == SectionType.OUTRO || t == SectionType.BREAKDOWN || t == SectionType.GROOVE
    }
}
