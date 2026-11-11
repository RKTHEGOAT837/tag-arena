package com.aidj.core.analysis

import com.aidj.core.model.BeatGrid
import com.aidj.core.model.MusicalKey
import com.aidj.core.model.Section
import com.aidj.core.model.SectionType
import com.aidj.core.model.TrackAnalysis
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline music analysis on decoded mono PCM (typically 22.05 kHz).
 *
 * Pipeline:
 *  1. STFT (1024/256) -> log spectral flux onset envelopes (full band + low band), band energies, vocal heuristic.
 *  2. Tempo: onset autocorrelation with a harmonic comb and a log-tempo prior.
 *  3. Beat grid: coarse-to-fine search of (bpm, phase) maximising onset strength on the grid.
 *  4. Downbeat: beat phase (mod 4) maximising structural novelty + kick/crash onsets.
 *  5. Key: chromagram (STFT 4096) correlated with Krumhansl-Kessler profiles -> Camelot.
 *  6. Per-bar energy/bass/high/vocal curves -> novelty segmentation -> section labels.
 *
 * Assumes a (near) constant tempo, which holds for the vast majority of produced dance/pop music.
 * [BeatGrid.confidence] reports how well that assumption fits; the DJ brain avoids beatmatched
 * transitions for low-confidence grids.
 */
class AudioAnalyzer(private val minBpm: Double = 70.0, private val maxBpm: Double = 180.0) {

    class Features(
        val fps: Double,
        val onset: FloatArray,
        val onsetLow: FloatArray,
        val onsetHigh: FloatArray,
        val eAll: FloatArray,
        val eLow: FloatArray,
        val eHigh: FloatArray,
        val vocal: FloatArray,
    )

    fun analyze(samples: FloatArray, sampleRate: Int, progress: (Float) -> Unit = {}): TrackAnalysis {
        require(sampleRate in 8000..96000) { "Unsupported sample rate $sampleRate" }
        val duration = samples.size.toDouble() / sampleRate
        require(duration >= 8.0) { "Track too short to analyse (${"%.1f".format(duration)} s)" }

        val f = computeFeatures(samples, sampleRate)
        progress(0.4f)
        val beatEnv = combinedBeatEnvelope(f)
        val bpm0 = estimateTempo(beatEnv, f.fps)
        progress(0.5f)
        val gridNoDownbeat = fitGrid(beatEnv, f.fps, bpm0)
        val grid = gridNoDownbeat.copy(downbeatPhase = findDownbeatPhase(f, gridNoDownbeat, duration))
        progress(0.6f)
        val (key, keyConf) = detectKey(samples, sampleRate)
        progress(0.85f)

        val bars = barFeatures(f, grid, duration)
        val sections = segment(bars)
        val introEnd = sections.firstOrNull { it.type == SectionType.INTRO }?.endBar ?: 0
        val outroStart = sections.lastOrNull { it.type == SectionType.OUTRO }?.startBar ?: max(0, bars.energy.size - 16)
        val drops = sections.filter { it.type == SectionType.DROP }.map { it.startBar }

        var sumSq = 0.0
        for (v in samples) sumSq += v * v
        val rms = sqrt(sumSq / samples.size)
        val loudDb = (20 * log10(max(rms, 1e-9))).toFloat()

        val energy = overallEnergy(loudDb, f, grid.bpm, duration)
        val mood = when {
            energy >= 0.65f && key.minor -> "dark, driving"
            energy >= 0.65f -> "euphoric, uplifting"
            energy < 0.38f && key.minor -> "melancholic, chilled"
            energy < 0.38f -> "warm, relaxed"
            key.minor -> "moody groove"
            else -> "feel-good groove"
        }
        progress(1f)
        return TrackAnalysis(
            durationSec = duration,
            grid = grid,
            key = key,
            keyConfidence = keyConf,
            energy = energy,
            barEnergy = bars.energy.toList(),
            barBass = bars.bass.toList(),
            barHigh = bars.high.toList(),
            barVocal = bars.vocal.toList(),
            sections = sections,
            introEndBar = introEnd,
            outroStartBar = outroStart,
            dropBars = drops,
            loudnessDb = loudDb,
            mood = mood,
            overview = overview(samples, 400),
        )
    }

    // ---------------------------------------------------------------- features

    fun computeFeatures(x: FloatArray, sr: Int): Features {
        val n = 1024
        val hop = 256
        val fft = Fft(n)
        val frames = max(1, (x.size - n) / hop + 1)
        val mag = FloatArray(n / 2 + 1)
        val prevLog = FloatArray(n / 2 + 1)
        val binHz = sr.toDouble() / n
        val lowBin = max(2, (150 / binHz).toInt())
        val highBin = (4000 / binHz).toInt().coerceAtMost(n / 2)
        val vLo = (300 / binHz).toInt()
        val vHi = (3400 / binHz).toInt().coerceAtMost(n / 2)
        val onset = FloatArray(frames)
        val onsetLow = FloatArray(frames)
        val onsetHigh = FloatArray(frames)
        val eAll = FloatArray(frames)
        val eLow = FloatArray(frames)
        val eHigh = FloatArray(frames)
        val vocal = FloatArray(frames)
        for (t in 0 until frames) {
            fft.magnitude(x, t * hop, mag)
            var flux = 0f; var fluxLow = 0f; var fluxHigh = 0f
            var all = 0f; var low = 0f; var high = 0f
            var vSum = 0.0; var vLogSum = 0.0
            for (k in 1..n / 2) {
                val m = mag[k]
                val p = m * m
                all += p
                if (k <= lowBin) low += p
                if (k >= highBin) high += p
                val l = ln(1f + 100f * m)
                val d = l - prevLog[k]
                if (d > 0) {
                    flux += d
                    if (k <= lowBin) fluxLow += d
                    if (k >= highBin) fluxHigh += d
                }
                prevLog[k] = l
                if (k in vLo..vHi) { vSum += m; vLogSum += ln(m + 1e-9) }
            }
            onset[t] = flux; onsetLow[t] = fluxLow; onsetHigh[t] = fluxHigh
            eAll[t] = all; eLow[t] = low; eHigh[t] = high
            val cnt = (vHi - vLo + 1).toDouble()
            val arith = vSum / cnt
            val geo = exp(vLogSum / cnt)
            val flatness = if (arith > 1e-9) geo / arith else 1.0
            var midE = 0f
            for (k in vLo..vHi) midE += mag[k] * mag[k]
            val share = if (all > 1e-12f) midE / all else 0f
            vocal[t] = (share * (1.0 - flatness)).toFloat()
        }
        return Features(sr.toDouble() / hop, onset, onsetLow, onsetHigh, eAll, eLow, eHigh, vocal)
    }

    /** Local-mean-removed, half-wave rectified, normalised onset envelope with kick emphasis. */
    fun combinedBeatEnvelope(f: Features): FloatArray {
        val a = normalize(highpassEnvelope(f.onset, f.fps))
        val b = normalize(highpassEnvelope(f.onsetLow, f.fps))
        return FloatArray(a.size) { a[it] + 1.5f * b[it] }
    }

    private fun highpassEnvelope(e: FloatArray, fps: Double): FloatArray {
        val w = max(1, (fps * 0.4).toInt())
        val out = FloatArray(e.size)
        var sum = 0.0
        val q = ArrayDeque<Float>()
        for (i in e.indices) {
            q.addLast(e[i]); sum += e[i]
            if (q.size > 2 * w + 1) sum -= q.removeFirst()
            // centred window: value at i-w uses mean of [i-2w, i]
            val c = i - w
            if (c >= 0) out[c] = max(0f, e[c] - (sum / q.size).toFloat())
        }
        for (c in max(0, e.size - w) until e.size) out[c] = max(0f, e[c] - (sum / q.size).toFloat())
        return out
    }

    private fun normalize(e: FloatArray): FloatArray {
        var s = 0.0; var s2 = 0.0
        for (v in e) { s += v; s2 += v * v }
        val mean = s / e.size
        val std = sqrt(max(1e-12, s2 / e.size - mean * mean))
        return FloatArray(e.size) { (e[it] / std).toFloat() }
    }

    // ---------------------------------------------------------------- tempo

    fun estimateTempo(env: FloatArray, fps: Double): Double {
        val maxLag = (fps * 60 / 40).toInt() + 2
        val minLag = max(2, (fps * 60 / 260).toInt())
        val acf = DoubleArray(maxLag * 4 + 4)
        var mean = 0.0
        for (v in env) mean += v
        mean /= env.size
        val lim = min(acf.size, env.size / 2)
        for (lag in minLag until lim) {
            var s = 0.0
            var i = 0
            val n = env.size - lag
            while (i < n) { s += (env[i] - mean) * (env[i + lag] - mean); i++ }
            acf[lag] = s / n
        }
        fun acfAt(lag: Double): Double {
            if (lag < 1 || lag >= lim - 1) return 0.0
            val i = floor(lag).toInt(); val fr = lag - i
            return acf[i] * (1 - fr) + acf[i + 1] * fr
        }
        var best = 120.0; var bestScore = Double.NEGATIVE_INFINITY
        var bpm = minBpm
        while (bpm <= maxBpm) {
            val lag = 60 * fps / bpm
            // Harmonic comb: beat, 2 beats, bar (4 beats), half bar; small credit for half-beat.
            val s = acfAt(lag) + 0.5 * acfAt(2 * lag) + 0.35 * acfAt(4 * lag) + 0.2 * acfAt(3 * lag) + 0.15 * acfAt(lag / 2)
            val prior = exp(-0.5 * (log2(bpm / 122.0) / 0.75).pow(2))
            val score = s * (0.35 + 0.65 * prior)
            if (score > bestScore) { bestScore = score; best = bpm }
            bpm += 0.25
        }
        return best
    }

    // ---------------------------------------------------------------- beat grid

    private fun envAt(env: FloatArray, frame: Double): Float {
        if (frame < 0 || frame >= env.size - 1) return 0f
        val i = frame.toInt(); val fr = (frame - i).toFloat()
        return env[i] * (1 - fr) + env[i + 1] * fr
    }

    private fun gridScore(env: FloatArray, fps: Double, bpm: Double, phaseSec: Double): Double {
        val periodF = 60.0 / bpm * fps
        var t = phaseSec * fps
        var s = 0.0; var n = 0
        while (t < env.size - 1) {
            // Small tolerance window: take the max of a ±1 frame neighbourhood (smoothed envelope).
            s += max(envAt(env, t), 0.6f * max(envAt(env, t - 1), envAt(env, t + 1)))
            n++
            t += periodF
        }
        return if (n == 0) 0.0 else s / n
    }

    fun fitGrid(env: FloatArray, fps: Double, bpm0: Double): BeatGrid {
        val smooth = FloatArray(env.size) { i ->
            val a = env.getOrElse(i - 1) { 0f }; val b = env[i]; val c = env.getOrElse(i + 1) { 0f }
            0.25f * a + 0.5f * b + 0.25f * c
        }
        var bestBpm = bpm0; var bestPhase = 0.0; var bestScore = -1.0
        // Coarse: ±2% at 0.05 BPM, 48 phase steps.
        var b = bpm0 * 0.98
        while (b <= bpm0 * 1.02) {
            val p = 60.0 / b
            for (k in 0 until 48) {
                val ph = p * k / 48
                val s = gridScore(smooth, fps, b, ph)
                if (s > bestScore) { bestScore = s; bestBpm = b; bestPhase = ph }
            }
            b += 0.05
        }
        // Fine: ±0.08 BPM at 0.004, phase ±1/48 beat at 24 steps.
        val cb = bestBpm; val cp = bestPhase
        b = cb - 0.08
        while (b <= cb + 0.08) {
            val p = 60.0 / b
            for (k in -12..12) {
                val ph = cp + p / 48 * k / 12.0
                if (ph < 0) continue
                val s = gridScore(smooth, fps, b, ph)
                if (s > bestScore) { bestScore = s; bestBpm = b; bestPhase = ph }
            }
            b += 0.004
        }
        // Snap to a "clean" BPM when the evidence is equivalent (most productions use integer/half BPMs).
        val rounded = (bestBpm * 2).roundToInt() / 2.0
        if (abs(rounded - bestBpm) < 0.06) {
            val p = 60.0 / rounded
            var rb = -1.0; var rp = bestPhase
            for (k in -24..24) {
                val ph = bestPhase + p / 96 * k / 4.0
                if (ph < 0) continue
                val s = gridScore(smooth, fps, rounded, ph)
                if (s > rb) { rb = s; rp = ph }
            }
            if (rb >= bestScore * 0.995) { bestBpm = rounded; bestPhase = rp; bestScore = max(bestScore, rb) }
        }
        var mean = 0.0
        for (v in smooth) mean += v
        mean /= smooth.size
        val ratio = bestScore / max(1e-9, mean)
        val conf = ((ratio - 1.2) / 2.3).coerceIn(0.0, 1.0).toFloat()
        // Envelope frame t covers samples [t*hop, t*hop+n); onset energy is centred ~n/2 later.
        val latency = 512.0 / 256.0 / fps * 0.5
        val period = 60.0 / bestBpm
        var first = bestPhase + latency
        while (first - period >= 0) first -= period
        return BeatGrid(bpm = bestBpm, firstBeatSec = first, downbeatPhase = 0, confidence = conf)
    }

    fun findDownbeatPhase(f: Features, grid: BeatGrid, duration: Double): Int {
        val nBeats = ((duration - grid.firstBeatSec) / grid.beatPeriodSec).toInt()
        if (nBeats < 16) return 0
        val logE = FloatArray(f.eAll.size) { ln(1e-6f + f.eAll[it]) }
        val logL = FloatArray(f.eLow.size) { ln(1e-6f + f.eLow[it]) }
        val logH = FloatArray(f.eHigh.size) { ln(1e-6f + f.eHigh[it]) }
        fun frameOf(beat: Int) = ((grid.beatTime(beat.toDouble())) * f.fps)
        fun meanRange(a: FloatArray, from: Double, to: Double): Float {
            val s = max(0, from.toInt()); val e = min(a.size, to.toInt())
            if (e <= s) return 0f
            var sum = 0f
            for (i in s until e) sum += a[i]
            return sum / (e - s)
        }
        val lowOn = normalize(f.onsetLow); val highOn = normalize(f.onsetHigh)
        val score = DoubleArray(4)
        for (beat in 4 until nBeats - 4) {
            val fb = frameOf(beat)
            val fPrev = frameOf(beat - 4); val fNext = frameOf(beat + 4)
            val nov = abs(meanRange(logE, fb, fNext) - meanRange(logE, fPrev, fb)) +
                abs(meanRange(logL, fb, fNext) - meanRange(logL, fPrev, fb)) +
                abs(meanRange(logH, fb, fNext) - meanRange(logH, fPrev, fb))
            val i = fb.roundToInt().coerceIn(0, lowOn.size - 1)
            var lo = 0f; var hi = 0f
            for (d in -2..2) { val j = (i + d).coerceIn(0, lowOn.size - 1); lo = max(lo, lowOn[j]); hi = max(hi, highOn[j]) }
            score[beat % 4] += nov + 0.08 * lo + 0.08 * hi
        }
        var best = 0
        for (p in 1..3) if (score[p] > score[best]) best = p
        return best
    }

    // ---------------------------------------------------------------- key

    fun detectKey(x: FloatArray, sr: Int): Pair<MusicalKey, Float> {
        val n = 4096
        val hop = 2048
        val fft = Fft(n)
        val mag = FloatArray(n / 2 + 1)
        val binHz = sr.toDouble() / n
        val binPc = IntArray(n / 2 + 1) { -1 }
        val binW = FloatArray(n / 2 + 1)
        for (k in 1..n / 2) {
            val hz = k * binHz
            if (hz < 60 || hz > 2200) continue
            val midi = 69 + 12 * log2(hz / 440.0)
            val nearest = midi.roundToInt()
            val dev = abs(midi - nearest)
            if (dev > 0.35) continue // between semitones: ignore
            binPc[k] = nearest.mod(12)
            binW[k] = (1.0 - dev / 0.35).toFloat()
        }
        val chroma = DoubleArray(12)
        val frame = DoubleArray(12)
        var t = 0
        while (t + n <= x.size) {
            fft.magnitude(x, t, mag)
            frame.fill(0.0)
            for (k in 1..n / 2) { val pc = binPc[k]; if (pc >= 0) frame[pc] += binW[k] * ln(1.0 + 50 * mag[k]) }
            val mx = frame.max()
            if (mx > 1e-6) for (i in 0 until 12) chroma[i] += frame[i] / mx
            t += hop
        }
        // Krumhansl-Kessler key profiles.
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val scores = ArrayList<Pair<MusicalKey, Double>>()
        for (tonic in 0 until 12) {
            scores += MusicalKey(tonic, false) to pearson(chroma, major, tonic)
            scores += MusicalKey(tonic, true) to pearson(chroma, minor, tonic)
        }
        scores.sortByDescending { it.second }
        val best = scores[0]; val second = scores[1]
        val conf = ((best.second - second.second) / 0.15 * 0.5 + best.second * 0.5).coerceIn(0.0, 1.0).toFloat()
        return best.first to conf
    }

    private fun pearson(chroma: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        val a = DoubleArray(12) { chroma[(it + tonic) % 12] }
        val ma = a.average(); val mb = profile.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) { num += (a[i] - ma) * (profile[i] - mb); da += (a[i] - ma).pow(2); db += (profile[i] - mb).pow(2) }
        return if (da < 1e-12) 0.0 else num / sqrt(da * db)
    }

    // ---------------------------------------------------------------- structure

    class Bars(val energy: FloatArray, val bass: FloatArray, val high: FloatArray, val vocal: FloatArray, val onset: FloatArray)

    fun barFeatures(f: Features, grid: BeatGrid, duration: Double): Bars {
        val count = max(1, floor(grid.barAt(duration)).toInt())
        val rms = FloatArray(count); val low = FloatArray(count); val high = FloatArray(count)
        val voc = FloatArray(count); val ons = FloatArray(count)
        for (b in 0 until count) {
            val s = (grid.barTime(b) * f.fps).toInt().coerceIn(0, f.eAll.size - 1)
            val e = (grid.barTime(b + 1) * f.fps).toInt().coerceIn(s + 1, f.eAll.size)
            var a = 0.0; var l = 0.0; var h = 0.0; var v = 0.0; var o = 0.0
            for (i in s until e) { a += f.eAll[i]; l += f.eLow[i]; h += f.eHigh[i]; v += f.vocal[i]; o += f.onset[i] }
            val n = (e - s).toDouble()
            rms[b] = ln(1e-9 + a / n).toFloat(); low[b] = ln(1e-9 + l / n).toFloat(); high[b] = ln(1e-9 + h / n).toFloat()
            voc[b] = (v / n).toFloat(); ons[b] = (o / n).toFloat()
        }
        val rN = pctNorm(rms); val lN = pctNorm(low); val hN = pctNorm(high); val vN = pctNorm(voc); val oN = pctNorm(ons)
        val energy = FloatArray(count) { (0.45f * rN[it] + 0.25f * lN[it] + 0.15f * hN[it] + 0.15f * oN[it]).coerceIn(0f, 1f) }
        return Bars(energy, lN, hN, vN, oN)
    }

    private fun pctNorm(a: FloatArray): FloatArray {
        if (a.isEmpty()) return a
        val s = a.sortedArray()
        val lo = s[(s.size * 0.05).toInt().coerceIn(0, s.size - 1)]
        val hi = s[(s.size * 0.95).toInt().coerceIn(0, s.size - 1)]
        val r = max(1e-6f, hi - lo)
        return FloatArray(a.size) { ((a[it] - lo) / r).coerceIn(0f, 1f) }
    }

    fun segment(bars: Bars): List<Section> {
        val n = bars.energy.size
        if (n < 8) return listOf(Section(SectionType.GROOVE, 0, n, bars.energy.average().toFloat(), bars.vocal.average() > 0.55))
        // Novelty between 4-bar windows either side of each bar boundary.
        val nov = FloatArray(n)
        for (i in 4..n - 4) {
            var d = 0f
            for (arr in arrayOf(bars.energy, bars.bass, bars.high)) {
                var a = 0f; var b = 0f
                for (j in i - 4 until i) a += arr[j]
                for (j in i until i + 4) b += arr[j]
                d += abs(a - b) / 4f
            }
            var va = 0f; var vb = 0f
            for (j in i - 4 until i) va += bars.vocal[j]
            for (j in i until i + 4) vb += bars.vocal[j]
            d += 0.5f * abs(va - vb) / 4f
            // Phrase prior: boundaries usually fall on 8-bar (and 4-bar) multiples.
            val prior = when { i % 8 == 0 -> 1.25f; i % 4 == 0 -> 1.1f; else -> 0.8f }
            nov[i] = d * prior
        }
        val mean = nov.average().toFloat()
        val std = sqrt(nov.map { (it - mean) * (it - mean) }.average()).toFloat()
        val thresh = mean + 0.6f * std
        val candidates = (4..n - 4).filter { nov[it] > thresh && nov[it] >= nov[it - 1] && nov[it] >= nov.getOrElse(it + 1) { 0f } }
            .sortedByDescending { nov[it] }
        val bounds = sortedSetOf(0, n)
        for (c in candidates) {
            if (bounds.all { abs(it - c) >= 8 }) bounds += c
            if (bounds.size > 14) break
        }
        val b = bounds.toList()
        data class Seg(val s: Int, val e: Int, val en: Float, val bass: Float, val vocal: Float, val slope: Float)
        val segs = (0 until b.size - 1).map { i ->
            val s = b[i]; val e = b[i + 1]
            var en = 0f; var ba = 0f; var vo = 0f
            for (j in s until e) { en += bars.energy[j]; ba += bars.bass[j]; vo += bars.vocal[j] }
            val len = (e - s).toFloat()
            val half = (e - s) / 2
            var h1 = 0f; var h2 = 0f
            for (j in s until s + half) h1 += bars.high[j] + bars.onset[j]
            for (j in s + half until e) h2 += bars.high[j] + bars.onset[j]
            Seg(s, e, en / len, ba / len, vo / len, if (half > 0) (h2 - h1) / half else 0f)
        }
        val sortedE = segs.map { it.en }.sorted()
        val highE = sortedE[(sortedE.size * 0.6).toInt().coerceIn(0, sortedE.size - 1)]
        val types = Array(segs.size) { SectionType.GROOVE }
        for ((i, s) in segs.withIndex()) {
            val prev = segs.getOrNull(i - 1)
            types[i] = when {
                i == 0 && segs.size > 1 && (s.en < segs[1].en - 0.08f || s.bass < 0.45f) -> SectionType.INTRO
                i == segs.size - 1 && i > 0 && s.en <= (prev?.en ?: 1f) - 0.05f -> SectionType.OUTRO
                s.bass < 0.38f && s.en < highE -> SectionType.BREAKDOWN
                s.en >= highE && s.bass >= 0.5f && prev != null && prev.en < s.en - 0.12f -> SectionType.DROP
                else -> SectionType.GROOVE
            }
        }
        val out = ArrayList<Section>()
        for ((i, s) in segs.withIndex()) {
            val voc = s.vocal > 0.55f
            val nextIsDrop = types.getOrNull(i + 1) == SectionType.DROP
            if (nextIsDrop && (types[i] == SectionType.BREAKDOWN || types[i] == SectionType.GROOVE) && s.slope > 0.02f) {
                // Split the rising tail off as a buildup (at most 8 bars).
                val buildLen = min(8, (s.e - s.s))
                val split = s.e - buildLen
                if (split > s.s) out += Section(types[i], s.s, split, s.en, voc)
                out += Section(SectionType.BUILDUP, split, s.e, s.en, voc)
            } else {
                out += Section(types[i], s.s, s.e, s.en, voc)
            }
        }
        // Cap absurdly long intros/outros (e.g. a quiet track) so the planner has a sane mix window.
        return out.mapIndexed { i, s ->
            when {
                s.type == SectionType.INTRO && s.endBar - s.startBar > 64 -> s.copy(endBar = 32).also { }
                else -> s
            }
        }.let { list ->
            // Re-stitch if an intro was capped.
            val fixed = ArrayList<Section>()
            for ((i, s) in list.withIndex()) {
                fixed += s
                val next = list.getOrNull(i + 1)
                if (next != null && s.endBar < next.startBar) fixed += Section(SectionType.GROOVE, s.endBar, next.startBar, s.energy, s.vocal)
            }
            fixed
        }
    }

    private fun overallEnergy(loudDb: Float, f: Features, bpm: Double, duration: Double): Float {
        val loudN = ((loudDb + 24f) / 16f).coerceIn(0f, 1f)
        // Onset rate: peaks in the normalised onset envelope per second.
        val env = normalize(highpassEnvelope(f.onset, f.fps))
        var peaks = 0
        for (i in 1 until env.size - 1) if (env[i] > 1.5f && env[i] >= env[i - 1] && env[i] > env[i + 1]) peaks++
        val rate = peaks / duration
        val rateN = ((rate - 1.0) / 4.0).coerceIn(0.0, 1.0).toFloat()
        var all = 0.0; var hi = 0.0; var lo = 0.0
        for (i in f.eAll.indices) { all += f.eAll[i]; hi += f.eHigh[i]; lo += f.eLow[i] }
        val hiShare = (hi / max(1e-12, all)).toFloat()
        val hiN = ((hiShare - 0.01f) / 0.12f).coerceIn(0f, 1f)
        val bpmN = (((bpm - 80) / 60.0).coerceIn(0.0, 1.0)).toFloat()
        return (0.35f * loudN + 0.25f * rateN + 0.15f * hiN + 0.25f * bpmN).coerceIn(0f, 1f)
    }

    private fun overview(x: FloatArray, points: Int): List<Float> {
        val chunk = max(1, x.size / points)
        val out = ArrayList<Float>(points)
        var i = 0
        while (i < x.size && out.size < points) {
            var p = 0f
            val e = min(x.size, i + chunk)
            for (j in i until e) p = max(p, abs(x[j]))
            out += min(1f, p)
            i += chunk
        }
        return out
    }
}
