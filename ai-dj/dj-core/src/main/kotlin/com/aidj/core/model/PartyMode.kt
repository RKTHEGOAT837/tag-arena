package com.aidj.core.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

@Serializable
enum class TransitionStyle(val label: String) {
    BASS_SWAP("Bass-swap blend"),
    FILTER_SWEEP("Filter sweep"),
    ECHO_OUT("Echo out"),
    LOOP_ROLL_DROP("Loop roll into drop"),
    CUT("Downbeat cut"),
    BRAKE("Brake stop"),
    DRUM_BRIDGE("Drum bridge"),
    MASHUP_LAYER("Mashup layer"),
    CROSSFADE("Crossfade"),
}

/**
 * A performance mode: what music fits, how energy should evolve over the set, and which
 * transition techniques the DJ favours.
 */
@Serializable
data class ModeProfile(
    val id: PartyMode,
    val genreKeywords: List<String>,
    /** Genres that are acceptable but not preferred. */
    val secondaryKeywords: List<String>,
    val bpmMin: Double,
    val bpmMax: Double,
    /** Baseline energy target and swing amplitude of the energy wave over the set. */
    val baseEnergy: Float,
    val energySwing: Float,
    /** Length (minutes) of one energy wave (build -> peak -> breathe). */
    val waveMinutes: Float,
    val styleWeights: Map<TransitionStyle, Float>,
    /** Typical blend length in bars. */
    val blendBars: Int,
    /** 0..1: how readily effects and remix elements are used. */
    val fxIntensity: Float,
    val yearMax: Int? = null,
) {
    /** Energy the set should be at after [minutes] of playing. */
    fun energyTarget(minutes: Float): Float {
        val warmup = min(1f, minutes / 12f)
        val wave = sin(2 * PI * minutes / waveMinutes).toFloat()
        return (baseEnergy * (0.75f + 0.25f * warmup) + energySwing * wave).coerceIn(0.05f, 1f)
    }
}

@Serializable
enum class PartyMode(val label: String) {
    AUTO("Auto DJ"), CLUB("Club"), BOLLYWOOD("Bollywood"), PUNJABI("Punjabi"),
    RETRO("Retro"), CHILL("Chill"), FESTIVAL("Festival"), CUSTOM("Custom");

    fun profile(custom: List<String> = emptyList()): ModeProfile = when (this) {
        AUTO -> ModeProfile(
            this, emptyList(), emptyList(), 70.0, 180.0, 0.62f, 0.15f, 30f,
            mapOf(TransitionStyle.BASS_SWAP to 1f, TransitionStyle.FILTER_SWEEP to 0.7f, TransitionStyle.ECHO_OUT to 0.5f,
                TransitionStyle.LOOP_ROLL_DROP to 0.4f, TransitionStyle.CUT to 0.3f, TransitionStyle.DRUM_BRIDGE to 0.4f,
                TransitionStyle.MASHUP_LAYER to 0.25f, TransitionStyle.BRAKE to 0.1f),
            16, 0.5f,
        )
        CLUB -> ModeProfile(
            this, listOf("house", "techno", "edm", "electronic", "dance", "tech house", "deep house", "trance", "electro"),
            listOf("disco", "funk", "pop"), 118.0, 135.0, 0.72f, 0.12f, 25f,
            mapOf(TransitionStyle.BASS_SWAP to 1f, TransitionStyle.FILTER_SWEEP to 0.8f, TransitionStyle.MASHUP_LAYER to 0.5f,
                TransitionStyle.ECHO_OUT to 0.4f, TransitionStyle.LOOP_ROLL_DROP to 0.4f, TransitionStyle.DRUM_BRIDGE to 0.3f),
            32, 0.55f,
        )
        BOLLYWOOD -> ModeProfile(
            this, listOf("bollywood", "hindi", "filmi", "indian", "desi"), listOf("bhangra", "punjabi", "indian pop", "pop"),
            85.0, 135.0, 0.68f, 0.18f, 20f,
            mapOf(TransitionStyle.ECHO_OUT to 1f, TransitionStyle.CUT to 0.8f, TransitionStyle.FILTER_SWEEP to 0.7f,
                TransitionStyle.BASS_SWAP to 0.5f, TransitionStyle.DRUM_BRIDGE to 0.6f, TransitionStyle.LOOP_ROLL_DROP to 0.4f),
            8, 0.6f,
        )
        PUNJABI -> ModeProfile(
            this, listOf("punjabi", "bhangra", "desi"), listOf("bollywood", "hip hop", "hindi"),
            85.0, 125.0, 0.74f, 0.14f, 20f,
            mapOf(TransitionStyle.CUT to 1f, TransitionStyle.ECHO_OUT to 0.8f, TransitionStyle.DRUM_BRIDGE to 0.7f,
                TransitionStyle.FILTER_SWEEP to 0.5f, TransitionStyle.BASS_SWAP to 0.4f, TransitionStyle.BRAKE to 0.3f),
            8, 0.65f,
        )
        RETRO -> ModeProfile(
            this, listOf("disco", "funk", "80s", "90s", "70s", "classic", "oldies", "soul", "retro", "synthpop"),
            listOf("pop", "rock", "r&b"), 90.0, 135.0, 0.6f, 0.15f, 30f,
            mapOf(TransitionStyle.CROSSFADE to 0.7f, TransitionStyle.BASS_SWAP to 0.8f, TransitionStyle.ECHO_OUT to 0.6f,
                TransitionStyle.CUT to 0.5f, TransitionStyle.FILTER_SWEEP to 0.4f),
            8, 0.35f, yearMax = 2005,
        )
        CHILL -> ModeProfile(
            this, listOf("chill", "lounge", "ambient", "downtempo", "lofi", "lo-fi", "jazz", "acoustic", "chillout"),
            listOf("deep house", "soul", "r&b", "indie"), 70.0, 120.0, 0.32f, 0.08f, 40f,
            mapOf(TransitionStyle.CROSSFADE to 1f, TransitionStyle.FILTER_SWEEP to 0.6f, TransitionStyle.BASS_SWAP to 0.7f,
                TransitionStyle.ECHO_OUT to 0.3f),
            32, 0.25f,
        )
        FESTIVAL -> ModeProfile(
            this, listOf("edm", "big room", "electro", "trance", "dubstep", "festival", "progressive house", "hardstyle"),
            listOf("house", "electronic", "dance", "pop"), 120.0, 150.0, 0.82f, 0.13f, 15f,
            mapOf(TransitionStyle.LOOP_ROLL_DROP to 1f, TransitionStyle.ECHO_OUT to 0.7f, TransitionStyle.FILTER_SWEEP to 0.7f,
                TransitionStyle.CUT to 0.6f, TransitionStyle.DRUM_BRIDGE to 0.4f, TransitionStyle.BRAKE to 0.2f),
            16, 0.9f,
        )
        CUSTOM -> ModeProfile(
            this, custom.map { it.lowercase() }, emptyList(), 70.0, 180.0, 0.62f, 0.15f, 30f,
            AUTO.profile().styleWeights, 16, 0.5f,
        )
    }
}
