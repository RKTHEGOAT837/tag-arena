package com.aidj.core.demo

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a synthetic dance track with known ground truth: tempo, first beat time, key and
 * section layout (in bars): intro (kick+hats), groove (+bass+pad), breakdown (pad only),
 * buildup (pad + snare roll), drop (everything), outro (kick+hats).
 */
object DemoMusic {
    data class Layout(val intro: Int = 16, val groove: Int = 16, val breakdown: Int = 8, val buildup: Int = 8, val drop: Int = 16, val outro: Int = 16) {
        val total get() = intro + groove + breakdown + buildup + drop + outro
        val dropStart get() = intro + groove + breakdown + buildup
    }

    /** [rootPc] pitch class of the tonic; [minor] mode. Returns mono samples. */
    fun render(
        bpm: Double, sr: Int = 22050, offsetSec: Double = 0.37, rootPc: Int = 9, minor: Boolean = true,
        layout: Layout = Layout(), seed: Int = 1,
    ): FloatArray {
        val rnd = Random(seed)
        val beat = 60.0 / bpm
        val totalSec = offsetSec + layout.total * 4 * beat + 1.0
        val out = FloatArray((totalSec * sr).toInt())
        fun midiHz(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)
        val third = if (minor) 3 else 4
        // i - iv - V - i (harmonic minor) or I - IV - V - I (major): tonally unambiguous cadences.
        val prog = intArrayOf(0, 5, 7, 0)
        val chordQual = if (minor) booleanArrayOf(true, true, false, true) else booleanArrayOf(false, false, false, false)
        val rootMidi = 57 + ((rootPc - 9).mod(12)) // around A3

        fun section(bar: Int): String {
            var b = bar
            if (b < layout.intro) return "intro"; b -= layout.intro
            if (b < layout.groove) return "groove"; b -= layout.groove
            if (b < layout.breakdown) return "breakdown"; b -= layout.breakdown
            if (b < layout.buildup) return "buildup"; b -= layout.buildup
            if (b < layout.drop) return "drop"
            return "outro"
        }

        for (bar in 0 until layout.total) {
            val sec = section(bar)
            val chordIdx = (bar / 2) % 4
            val chordRoot = rootMidi + prog[chordIdx]
            val chordMinor = chordQual[chordIdx]
            for (b in 0 until 4) {
                val t0 = offsetSec + (bar * 4 + b) * beat
                val i0 = (t0 * sr).toInt()
                val kick = sec in setOf("intro", "groove", "drop", "outro") || (sec == "buildup")
                if (kick) for (i in 0 until (0.25 * sr).toInt()) {
                    val t = i.toDouble() / sr
                    val f = 50 + 110 * exp(-t * 30)
                    val ph = 2 * PI * (50 * t + 110 * (1 - exp(-t * 30)) / 30)
                    add(out, i0 + i, (0.9 * sin(ph) * exp(-t * 9)).toFloat())
                    if (f < 0) break
                }
                // Off-beat hats.
                if (sec in setOf("intro", "groove", "drop", "outro")) {
                    val h0 = ((t0 + beat / 2) * sr).toInt()
                    for (i in 0 until (0.05 * sr).toInt()) add(out, h0 + i, ((rnd.nextFloat() * 2 - 1) * 0.25 * exp(-i / (0.012 * sr))).toFloat())
                }
                // Clap on 2 and 4 in drop/groove; snare roll in buildup.
                if ((sec == "drop" || sec == "groove") && (b == 1 || b == 3)) {
                    for (i in 0 until (0.12 * sr).toInt()) add(out, i0 + i, ((rnd.nextFloat() * 2 - 1) * 0.35 * exp(-i / (0.03 * sr))).toFloat())
                }
                if (sec == "buildup") {
                    val div = if (bar % layout.buildup >= layout.buildup / 2) 4 else 2
                    for (k in 0 until div) {
                        val s0 = ((t0 + k * beat / div) * sr).toInt()
                        for (i in 0 until (0.05 * sr).toInt()) add(out, s0 + i, ((rnd.nextFloat() * 2 - 1) * 0.3 * exp(-i / (0.015 * sr))).toFloat())
                    }
                }
                // Bass on every 8th in groove/drop.
                if (sec == "groove" || sec == "drop") {
                    val hz = midiHz((chordRoot - 24).toDouble())
                    for (half in 0 until 2) {
                        val s0 = ((t0 + half * beat / 2) * sr).toInt()
                        for (i in 0 until (beat / 2 * sr * 0.9).toInt()) add(out, s0 + i, (0.35 * sin(2 * PI * hz * i / sr) * exp(-i / (0.2 * sr))).toFloat())
                    }
                }
            }
            // Pad (sustained triad) everywhere except intro/outro.
            if (sec != "intro" && sec != "outro") {
                val notes = intArrayOf(0, if (chordMinor) 3 else 4, 7).map { midiHz((chordRoot + it).toDouble()) } +
                    listOf(midiHz((chordRoot + 12).toDouble()))
                val s0 = ((offsetSec + bar * 4 * beat) * sr).toInt()
                val len = (4 * beat * sr).toInt()
                val amp = if (sec == "breakdown" || sec == "buildup") 0.16 else 0.09
                for (i in 0 until len) {
                    var v = 0.0
                    for (hz in notes) v += sin(2 * PI * hz * i / sr) + 0.3 * sin(4 * PI * hz * i / sr)
                    val env = minOf(1.0, i / (0.02 * sr)) * minOf(1.0, (len - i) / (0.02 * sr))
                    add(out, s0 + i, (amp * v * env / notes.size).toFloat())
                }
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = third
        return out
    }

    private fun add(a: FloatArray, i: Int, v: Float) { if (i in a.indices) a[i] += v }
}
