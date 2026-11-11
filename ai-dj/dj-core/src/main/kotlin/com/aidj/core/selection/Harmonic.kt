package com.aidj.core.selection

import com.aidj.core.model.MusicalKey
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Harmonic-mixing and tempo-compatibility rules used by both the selector and the planner. */
object Harmonic {

    /**
     * Compatibility of playing [b] after/over [a] on the Camelot wheel, 0..1.
     * Same key 1.0, adjacent number 0.9, relative major/minor 0.85, diagonal 0.65, +2 "energy boost" 0.6.
     */
    fun camelotScore(a: MusicalKey, b: MusicalKey): Float {
        val na = a.camelotNumber; val nb = b.camelotNumber
        val d = ((nb - na) % 12 + 12) % 12 // clockwise distance
        val dist = minOf(d, 12 - d)
        val sameLetter = a.minor == b.minor
        return when {
            sameLetter && dist == 0 -> 1f
            sameLetter && dist == 1 -> 0.9f
            !sameLetter && dist == 0 -> 0.85f
            !sameLetter && dist == 1 -> 0.65f
            sameLetter && dist == 2 -> 0.6f
            sameLetter && d == 7 -> 0.5f // +1 semitone "energy boost"
            !sameLetter && dist == 2 -> 0.35f
            else -> (0.25f - 0.03f * dist).coerceAtLeast(0.05f)
        }
    }

    /**
     * Best pitch shift (semitones, within ±[maxShift]) to apply to [incoming] so it fits [current].
     * Returns the shift and the resulting compatibility. Prefers no shift on ties.
     */
    fun bestKeyShift(current: MusicalKey, incoming: MusicalKey, maxShift: Int): Pair<Int, Float> {
        var best = 0; var bestScore = camelotScore(current, incoming)
        for (s in -maxShift..maxShift) {
            if (s == 0) continue
            val sc = camelotScore(current, incoming.transpose(s)) - 0.04f * abs(s) // small preference for less shifting
            if (sc > bestScore + 0.05f) { best = s; bestScore = sc }
        }
        return best to camelotScore(current, incoming.transpose(best))
    }

    /**
     * Tempo ratio needed to bring [incomingBpm] to [targetBpm], allowing half/double-time matching.
     * Returns (ratio, effectiveIncomingBpm) where ratio is applied to the incoming deck.
     */
    fun tempoMatch(targetBpm: Double, incomingBpm: Double): Pair<Double, Double> {
        val options = listOf(incomingBpm, incomingBpm * 2, incomingBpm / 2)
        val eff = options.minBy { abs(ln(targetBpm / it)) }
        return (targetBpm / eff) to eff
    }

    /** Tempo-compatibility score 0..1 from the percentage stretch required. */
    fun tempoScore(currentBpm: Double, incomingBpm: Double): Float {
        val (ratio, _) = tempoMatch(currentBpm, incomingBpm)
        val pct = abs(ratio - 1.0) * 100
        return when {
            pct <= 2 -> 1f
            pct <= 4 -> 0.85f
            pct <= 6 -> 0.65f
            pct <= 8 -> 0.4f
            pct <= 12 -> 0.2f
            else -> 0.05f
        }
    }

    fun semitonesForRatio(ratio: Double): Double = 12 * ln(ratio) / ln(2.0)
    fun roundSemis(x: Double) = x.roundToInt()
}
