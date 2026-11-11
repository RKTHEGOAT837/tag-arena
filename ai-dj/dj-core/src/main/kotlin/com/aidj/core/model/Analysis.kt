package com.aidj.core.model

import kotlinx.serialization.Serializable
import kotlin.math.floor

/** Musical key as pitch class (0 = C) + mode. */
@Serializable
data class MusicalKey(val pitchClass: Int, val minor: Boolean) {
    val name: String get() = NAMES[pitchClass.mod(12)] + if (minor) "m" else ""

    /** Camelot wheel number 1..12 (A = minor, B = major). */
    val camelotNumber: Int
        get() = if (minor) MINOR_CAMELOT[pitchClass.mod(12)] else MAJOR_CAMELOT[pitchClass.mod(12)]
    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"

    fun transpose(semitones: Int) = MusicalKey((pitchClass + semitones).mod(12), minor)

    companion object {
        val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        // Camelot numbers indexed by pitch class.
        private val MAJOR_CAMELOT = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        private val MINOR_CAMELOT = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        fun parse(s: String): MusicalKey? {
            val t = s.trim()
            Regex("""^(\d{1,2})([ABab])$""").matchEntire(t)?.let { m ->
                val n = m.groupValues[1].toInt()
                val minor = m.groupValues[2].equals("A", true)
                val table = if (minor) MINOR_CAMELOT else MAJOR_CAMELOT
                val pc = table.indexOf(n)
                return if (pc >= 0) MusicalKey(pc, minor) else null
            }
            val m = Regex("""^([A-Ga-g])([#b]?)\s*(m|min|minor|maj|major)?$""").matchEntire(t) ?: return null
            var pc = NAMES.indexOf(m.groupValues[1].uppercase())
            if (m.groupValues[2] == "#") pc += 1 else if (m.groupValues[2] == "b") pc -= 1
            val minor = m.groupValues[3].startsWith("m") && !m.groupValues[3].startsWith("maj")
            return MusicalKey(pc.mod(12), minor)
        }
    }
}

@Serializable
enum class SectionType { INTRO, GROOVE, BUILDUP, DROP, BREAKDOWN, OUTRO }

/** A structural section, in bars counted from the first downbeat. */
@Serializable
data class Section(val type: SectionType, val startBar: Int, val endBar: Int, val energy: Float, val vocal: Boolean)

/**
 * Constant-tempo beat grid. Beat n sits at firstBeatSec + n * beatPeriodSec.
 * downbeatPhase = index (0..3) of the first beat that is a bar's beat 1.
 */
@Serializable
data class BeatGrid(
    val bpm: Double,
    val firstBeatSec: Double,
    val downbeatPhase: Int,
    /** 0..1: how well onsets line up with the grid across the whole track. */
    val confidence: Float,
) {
    val beatPeriodSec: Double get() = 60.0 / bpm

    fun beatTime(beat: Double): Double = firstBeatSec + beat * beatPeriodSec
    fun beatAt(timeSec: Double): Double = (timeSec - firstBeatSec) / beatPeriodSec

    /** Beat index (in the grid's numbering) where bar [bar] starts. Bar 0 = first full bar. */
    fun barStartBeat(bar: Int): Int = downbeatPhase + bar * 4
    fun barTime(bar: Int): Double = beatTime(barStartBeat(bar).toDouble())
    fun barAt(timeSec: Double): Double = (beatAt(timeSec) - downbeatPhase) / 4.0
    fun barIndexAt(timeSec: Double): Int = floor(barAt(timeSec)).toInt()
}

@Serializable
data class TrackAnalysis(
    val version: Int = VERSION,
    val durationSec: Double,
    val grid: BeatGrid,
    val key: MusicalKey,
    val keyConfidence: Float,
    /** 0..1 overall energy. */
    val energy: Float,
    /** Per-bar curves (bar 0 = first full bar), each 0..1 normalised within the track. */
    val barEnergy: List<Float>,
    val barBass: List<Float>,
    val barHigh: List<Float>,
    val barVocal: List<Float>,
    val sections: List<Section>,
    /** Bar where the intro ends (first bar of the main groove). */
    val introEndBar: Int,
    /** Bar where the outro begins. */
    val outroStartBar: Int,
    val dropBars: List<Int>,
    val loudnessDb: Float,
    /** Heuristic labels derived from analysis (+ tags where available). */
    val mood: String,
    /** Coarse waveform overview for the UI (peak 0..1). */
    val overview: List<Float>,
) {
    val bpm: Double get() = grid.bpm
    val bars: Int get() = barEnergy.size

    fun sectionAtBar(bar: Int): Section? = sections.firstOrNull { bar >= it.startBar && bar < it.endBar }
    fun sectionAt(timeSec: Double): Section? = sectionAtBar(grid.barIndexAt(timeSec))

    companion object { const val VERSION = 3 }
}

/** Library entry: a track plus (optional) cached analysis and the user's feedback. */
@Serializable
data class LibraryTrack(
    val track: Track,
    val analysis: TrackAnalysis? = null,
    val analysisError: String? = null,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val liked: Boolean = false,
    val lastPlayedAt: Long = 0,
)
