package com.aidj.engine

import com.aidj.core.planning.Action
import com.aidj.core.planning.ActionType
import com.aidj.core.planning.DrumPatterns
import com.aidj.core.planning.Param
import com.aidj.core.planning.Ramp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/** Snapshot of the native engine, published once per audio callback. */
data class DeckState(
    val positionSec: Double, val playing: Boolean, val ended: Boolean, val looping: Boolean, val synced: Boolean,
    val rate: Double, val effectiveBpm: Double, val vu: Float, val trackId: Int, val durationSec: Double,
    val keylock: Boolean, val pitchSemis: Double, val loopBeats: Double, val phaseErrorBeats: Double,
    val volume: Float, val stretching: Boolean,
)

data class EngineState(
    val masterBeat: Double, val masterBpm: Double, val framesRendered: Long, val sampleRate: Int, val cpuLoad: Float,
    val xruns: Int, val limiterGain: Float, val rms: Float, val peak: Float, val low: Float, val mid: Float, val high: Float,
    val pendingAutomation: Int, val appliedSeq: Long, val bands: FloatArray, val decks: List<DeckState>,
) {
    companion object {
        val EMPTY = EngineState(0.0, 120.0, 0, 48000, 0f, 0, 1f, 0f, 0f, 0f, 0f, 0f, 0, 0, FloatArray(16),
            List(2) { DeckState(0.0, false, false, false, false, 1.0, 0.0, 0f, 0, 0.0, true, 0.0, 0.0, 0.0, 1f, false) })
    }
}

class TrackHandle(val id: Int, val sampleRate: Int, val capacityFrames: Long, val pcm: ShortBuffer)

/**
 * Kotlin facade over the C++ engine. All methods are thread-safe and non-blocking (they enqueue
 * commands consumed by the audio thread at the start of its next callback).
 */
class NativeEngine {
    private val handle: Long = nativeCreate()
    private val stateBuf = DoubleArray(64)
    @Volatile private var destroyed = false

    init { uploadDrumPatterns() }

    fun start(): Boolean = nativeStart(handle)
    fun stop() = nativeStop(handle)
    val isRunning: Boolean get() = nativeIsRunning(handle)
    val latencyMs: Double get() = nativeLatencyMs(handle)
    val sampleRate: Int get() = nativeSampleRate(handle)

    fun destroy() { if (!destroyed) { destroyed = true; nativeDestroy(handle) } }

    // ---- track memory (lives in native memory, not the Java heap)
    fun allocTrack(frames: Long, sampleRate: Int): TrackHandle? {
        val id = nativeAllocTrack(handle, frames, sampleRate)
        if (id < 0) return null
        val bb: ByteBuffer = nativeTrackBuffer(handle, id) ?: return null
        return TrackHandle(id, sampleRate, frames, bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
    }
    fun finalizeTrack(t: TrackHandle, frames: Long) = nativeFinalizeTrack(handle, t.id, frames)
    fun releaseTrack(id: Int) = nativeReleaseTrack(handle, id)
    fun collectGarbage() = nativeCollectGarbage(handle)

    // ---- deck control
    fun load(deck: Int, t: TrackHandle, bpm: Double, firstBeatSec: Double) { nativeLoad(handle, deck, t.id, bpm, firstBeatSec) }
    fun play(deck: Int, atSec: Double? = null, anchorIfAlone: Boolean = true) =
        cmd(C_PLAY, deck, if (anchorIfAlone) 1 else 0, if (atSec != null) 1 else 0, atSec ?: 0.0)
    fun pause(deck: Int) = cmd(C_PAUSE, deck)
    fun seek(deck: Int, sec: Double) = cmd(C_SEEK, deck, d0 = sec)
    fun setParam(p: Param, deck: Int, value: Float) = cmd(C_SET_PARAM, deck, p.id, d0 = p.clamp(value).toDouble())
    fun setSync(deck: Int, on: Boolean) = cmd(C_SET_SYNC, deck, if (on) 1 else 0)
    fun setKeylock(deck: Int, on: Boolean) = cmd(C_SET_KEYLOCK, deck, if (on) 1 else 0)
    fun setPitch(deck: Int, semis: Double) = cmd(C_SET_PITCH, deck, d0 = semis)
    fun setRate(deck: Int, rate: Double) = cmd(C_SET_RATE, deck, d0 = rate)
    fun setGrid(deck: Int, bpm: Double, firstBeatSec: Double) = cmd(C_SET_GRID, deck, d0 = bpm, d1 = firstBeatSec)
    fun setLoop(deck: Int, beats: Double, slip: Boolean) = cmd(C_SET_LOOP, deck, if (slip) 1 else 0, d0 = beats)
    fun exitLoop(deck: Int) = cmd(C_EXIT_LOOP, deck)
    fun brake(deck: Int, beats: Double) = cmd(C_BRAKE, deck, d0 = beats)
    fun resetDeck(deck: Int) = cmd(C_RESET_DECK, deck)
    fun impact() = cmd(C_IMPACT, 0)
    fun anchor(deck: Int) = cmd(C_ANCHOR, deck)
    fun unload(deck: Int) = cmd(C_UNLOAD, deck)

    // ---- automation (beats are absolute master-clock beats)
    fun addRamp(r: Ramp, beatOffset: Double, deckMap: (Int) -> Int = { it }) = cmd(
        C_ADD_RAMP, if (r.param.perDeck) deckMap(r.deck) else -1, r.param.id, r.curve.id,
        r.startBeat + beatOffset, r.endBeat + beatOffset, r.from?.toDouble() ?: Double.NaN, r.to.toDouble(),
    )
    fun addAction(a: Action, beatOffset: Double, deckMap: (Int) -> Int = { it }) =
        cmd(C_ADD_ACTION, deckMap(a.deck), a.type.id, 0, a.beat + beatOffset, a.arg0, a.arg1)
    fun clearAutomation() = cmd(C_CLEAR_AUTOMATION, 0)
    fun clearDeckAutomation(deck: Int) = cmd(C_CLEAR_DECK_AUTOMATION, deck)

    fun setDrumPattern(id: Int) = cmd(C_DRUM_PATTERN, 0, id)
    fun setDrumRoot(midi: Int) = cmd(C_DRUM_ROOT, 0, midi)

    private fun uploadDrumPatterns() {
        for (p in DrumPatterns.all) {
            for ((voice, steps) in p.steps) for (s in 0 until 16) cmd(C_DRUM_STEP, 0, p.id, voice.id, s.toDouble(), steps[s].toDouble())
            cmd(C_DRUM_SWING, 0, p.id, d0 = p.swing.toDouble())
        }
    }

    fun readState(): EngineState = synchronized(stateBuf) {
        nativeReadState(handle, stateBuf)
        val o = stateBuf
        val decks = (0 until 2).map { d ->
            val b = 32 + d * 16
            DeckState(o[b], o[b + 1] > 0.5, o[b + 2] > 0.5, o[b + 3] > 0.5, o[b + 4] > 0.5, o[b + 5], o[b + 6], o[b + 7].toFloat(),
                o[b + 8].toInt(), o[b + 9], o[b + 10] > 0.5, o[b + 11], o[b + 12], o[b + 13], o[b + 14].toFloat(), o[b + 15] > 0.5)
        }
        EngineState(o[0], o[1], o[2].toLong(), o[3].toInt(), o[4].toFloat(), o[5].toInt(), o[6].toFloat(), o[7].toFloat(),
            o[8].toFloat(), o[9].toFloat(), o[10].toFloat(), o[11].toFloat(), o[12].toInt(), o[13].toLong(),
            FloatArray(16) { o[14 + it].toFloat() }, decks)
    }

    private fun cmd(type: Int, deck: Int, i0: Int = 0, i1: Int = 0, d0: Double = 0.0, d1: Double = 0.0, d2: Double = 0.0, d3: Double = 0.0) {
        if (!destroyed) nativeCommand(handle, type, deck, i0, i1, d0, d1, d2, d3)
    }

    @Suppress("unused")
    private fun actionId(t: ActionType) = t.id

    companion object {
        init { System.loadLibrary("aidj_engine") }

        // Command ids: keep in sync with engine.h CmdType.
        const val C_LOAD = 0; const val C_UNLOAD = 1; const val C_PLAY = 2; const val C_PAUSE = 3; const val C_SEEK = 4
        const val C_SET_PARAM = 5; const val C_SET_SYNC = 6; const val C_SET_KEYLOCK = 7; const val C_SET_PITCH = 8
        const val C_SET_GRID = 9; const val C_SET_LOOP = 10; const val C_EXIT_LOOP = 11; const val C_BRAKE = 12
        const val C_ADD_RAMP = 13; const val C_ADD_ACTION = 14; const val C_CLEAR_AUTOMATION = 15
        const val C_CLEAR_DECK_AUTOMATION = 16; const val C_ANCHOR = 17; const val C_DRUM_STEP = 18; const val C_DRUM_SWING = 19
        const val C_DRUM_PATTERN = 20; const val C_DRUM_ROOT = 21; const val C_IMPACT = 22; const val C_SET_RATE = 23
        const val C_RESET_DECK = 24

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(h: Long)
        @JvmStatic private external fun nativeStart(h: Long): Boolean
        @JvmStatic private external fun nativeStop(h: Long)
        @JvmStatic private external fun nativeIsRunning(h: Long): Boolean
        @JvmStatic private external fun nativeLatencyMs(h: Long): Double
        @JvmStatic private external fun nativeFramesPerBurst(h: Long): Int
        @JvmStatic private external fun nativeAllocTrack(h: Long, frames: Long, sr: Int): Int
        @JvmStatic private external fun nativeTrackBuffer(h: Long, id: Int): ByteBuffer?
        @JvmStatic private external fun nativeFinalizeTrack(h: Long, id: Int, frames: Long)
        @JvmStatic private external fun nativeReleaseTrack(h: Long, id: Int)
        @JvmStatic private external fun nativeCollectGarbage(h: Long)
        @JvmStatic private external fun nativeLoad(h: Long, deck: Int, id: Int, bpm: Double, firstBeat: Double): Long
        @JvmStatic private external fun nativeCommand(h: Long, type: Int, deck: Int, i0: Int, i1: Int, d0: Double, d1: Double, d2: Double, d3: Double): Long
        @JvmStatic private external fun nativeReadState(h: Long, out: DoubleArray)
        @JvmStatic private external fun nativeSampleRate(h: Long): Int
    }
}
