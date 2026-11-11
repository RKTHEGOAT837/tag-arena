package com.aidj.core.selection

import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.ModeProfile
import com.aidj.core.model.TrackAnalysis
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/** User/AI steering applied on top of the mode profile. All fields optional. */
@Serializable
data class SelectionSteering(
    /** Override the energy target (0..1). */
    val energyTarget: Float? = null,
    /** Relative energy nudge, e.g. +0.15 for "more energetic". */
    val energyDelta: Float = 0f,
    /** Genre keywords requested for the next tracks. */
    val genres: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val bpmMin: Double? = null,
    val bpmMax: Double? = null,
    val allowExplicit: Boolean = true,
    /** Free-text the AI attached ("crowd-pleaser", "peak time"...), shown in the UI. */
    val note: String? = null,
)

@Serializable
data class ScoredCandidate(
    val trackId: String,
    val title: String,
    val artist: String,
    val bpm: Double,
    val key: String,
    val energy: Float,
    val genres: List<String>,
    val score: Float,
    val tempoScore: Float,
    val keyScore: Float,
    val energyScore: Float,
    val genreScore: Float,
    val keyShift: Int,
    val reasons: List<String>,
)

/** Context of the set so far. */
data class SetContext(
    val current: LibraryTrack?,
    /** Track ids played this session, most recent last. */
    val history: List<String>,
    val historyArtists: List<String>,
    val minutesPlayed: Float,
    val allowKeyShift: Boolean,
    val maxKeyShift: Int = 2,
    /** Per-track 0..1 affinity from the user's listening history (Spotify / YouTube Takeout imports). */
    val tasteScores: Map<String, Float> = emptyMap(),
)

/**
 * Ranks the library for "what should play next". Purely local and deterministic except for a small
 * seeded jitter that keeps sets from being predictable. Claude (when available) chooses among the
 * top-ranked candidates and may override with its own reasoning; without Claude the top candidate plays.
 */
class TrackSelector(private val random: Random = Random.Default) {

    fun rank(
        library: List<LibraryTrack>,
        ctx: SetContext,
        profile: ModeProfile,
        steering: SelectionSteering = SelectionSteering(),
        limit: Int = 12,
    ): List<ScoredCandidate> {
        val cur = ctx.current
        val curA = cur?.analysis
        val baseTarget = steering.energyTarget ?: profile.energyTarget(ctx.minutesPlayed)
        val target = (baseTarget + steering.energyDelta).coerceIn(0.05f, 1f)
        val recent = ctx.history.takeLast(maxOf(10, ctx.history.size / 2)).toSet()
        val recentArtists = ctx.historyArtists.takeLast(4).map { it.lowercase() }.toSet()
        val wantGenres = (steering.genres.ifEmpty { profile.genreKeywords }).map { it.lowercase() }
        val secondary = profile.secondaryKeywords

        return library.asSequence()
            .filter { it.analysis != null && it.track.capabilities.playback }
            .filter { it.track.id != cur?.track?.id }
            .filter { it.track.id !in recent }
            .filter { steering.allowExplicit || !it.track.explicit }
            .mapNotNull { lt ->
                val a = lt.analysis!!
                val reasons = ArrayList<String>()
                // Tempo
                val tempo = if (curA != null) Harmonic.tempoScore(curA.bpm, a.bpm) else 1f
                val bpmLo = steering.bpmMin ?: profile.bpmMin
                val bpmHi = steering.bpmMax ?: profile.bpmMax
                val inRange = a.bpm in bpmLo..bpmHi || a.bpm * 2 in bpmLo..bpmHi || a.bpm / 2 in bpmLo..bpmHi
                val rangeScore = if (inRange) 1f else 0.35f
                // Key (with optional pitch shift when processing is allowed)
                var shift = 0
                val keyScore = if (curA != null) {
                    if (ctx.allowKeyShift && lt.track.capabilities.processing && cur.track.capabilities.processing) {
                        val (s, sc) = Harmonic.bestKeyShift(curA.key, a.key, ctx.maxKeyShift)
                        shift = s; sc
                    } else Harmonic.camelotScore(curA.key, a.key)
                } else 1f
                // Energy
                val energyScore = (1f - abs(a.energy - target) * 1.8f).coerceIn(0f, 1f)
                // Genre / era
                val tags = lt.track.genres.map { it.lowercase() }
                val genreScore = when {
                    wantGenres.isEmpty() -> 0.7f
                    tags.any { t -> wantGenres.any { g -> t.contains(g) || g.contains(t) } } -> 1f
                    tags.any { t -> secondary.any { g -> t.contains(g) } } -> 0.6f
                    tags.isEmpty() -> 0.45f
                    else -> 0.15f
                }
                val year = lt.track.year
                val yearOk = when {
                    year == null -> 0.8f
                    steering.yearFrom != null && year < steering.yearFrom -> 0.2f
                    steering.yearTo != null && year > steering.yearTo -> 0.2f
                    profile.yearMax != null && year > profile.yearMax -> 0.3f
                    else -> 1f
                }
                // Preferences
                val taste = ctx.tasteScores[lt.track.id] ?: 0f
                val pref = (if (lt.liked) 0.15f else 0f) - 0.08f * minOf(lt.skipCount, 4) + 0.02f * minOf(lt.playCount, 5) + 0.14f * taste
                val artistPenalty = if (lt.track.artist.lowercase() in recentArtists) 0.25f else 0f
                val jitter = random.nextFloat() * 0.06f

                val score = 0.22f * tempo + 0.2f * keyScore + 0.22f * energyScore + 0.2f * genreScore +
                    0.06f * rangeScore + 0.1f * yearOk + pref - artistPenalty + jitter

                if (tempo >= 0.85f) reasons += "tempo-compatible (${"%.1f".format(a.bpm)} BPM)"
                if (keyScore >= 0.85f) reasons += if (shift != 0) "harmonic after ${signed(shift)} st key shift" else "harmonic match ${a.key.camelot}"
                if (energyScore >= 0.8f) reasons += "fits energy target ${"%.2f".format(target)}"
                if (genreScore >= 1f) reasons += "genre fit"
                if (lt.liked) reasons += "you liked this"
                if (taste >= 0.5f) reasons += "matches your listening history"
                ScoredCandidate(
                    trackId = lt.track.id, title = lt.track.title, artist = lt.track.artist, bpm = a.bpm,
                    key = a.key.camelot, energy = a.energy, genres = lt.track.genres, score = score,
                    tempoScore = tempo, keyScore = keyScore, energyScore = energyScore, genreScore = genreScore,
                    keyShift = shift, reasons = reasons,
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    companion object {
        /** Section-aware "where are we" summary used in AI state snapshots. */
        fun describePosition(a: TrackAnalysis, posSec: Double): String {
            val bar = a.grid.barIndexAt(posSec)
            val s = a.sectionAtBar(bar) ?: return "unknown"
            return "${s.type.name.lowercase()} (bar ${bar - s.startBar + 1}/${s.endBar - s.startBar})"
        }
    }
}
