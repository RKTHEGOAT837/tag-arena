package com.aidj.core.planning

import kotlinx.serialization.Serializable

/**
 * Parameter ids shared with the native engine (dj_engine.h `ParamId`). Keep in sync.
 * Per-deck params use deck 0/1; master params ignore the deck.
 */
@Serializable
enum class Param(val id: Int, val perDeck: Boolean, val min: Float, val max: Float, val default: Float) {
    DECK_VOLUME(0, true, 0f, 1f, 1f),
    EQ_LOW(1, true, 0f, 1.6f, 1f),
    EQ_MID(2, true, 0f, 1.6f, 1f),
    EQ_HIGH(3, true, 0f, 1.6f, 1f),
    /** Bipolar filter: -1 = low-pass closed, 0 = off, +1 = high-pass closed. */
    FILTER(4, true, -1f, 1f, 0f),
    ECHO_MIX(5, true, 0f, 1f, 0f),
    ECHO_FEEDBACK(6, true, 0f, 0.92f, 0.5f),
    /** Echo time in beats. */
    ECHO_BEATS(7, true, 0.125f, 4f, 0.75f),
    REVERB_MIX(8, true, 0f, 1f, 0f),
    FLANGER_MIX(9, true, 0f, 1f, 0f),
    TRIM(10, true, 0f, 2f, 1f),
    CROSSFADER(20, false, -1f, 1f, 0f),
    MASTER_BPM(21, false, 40f, 250f, 120f),
    DRUM_LEVEL(22, false, 0f, 1f, 0f),
    RISER_LEVEL(23, false, 0f, 1f, 0f),
    RISER_PITCH(24, false, 0f, 1f, 0f),
    MASTER_FILTER(25, false, -1f, 1f, 0f),
    MASTER_VOLUME(26, false, 0f, 1f, 0.9f),
    /** Voice-over ducking amount 0..1 (1 = -12 dB). */
    DUCK(27, false, 0f, 1f, 0f),
    DRUM_BASS_LEVEL(28, false, 0f, 1f, 0f);

    fun clamp(v: Float) = v.coerceIn(min, max)

    companion object {
        fun byName(name: String): Param? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

@Serializable
enum class Curve(val id: Int) { LINEAR(0), EASE_IN(1), EASE_OUT(2), S_CURVE(3), STEP(4) }

/** A parameter ramp. Beats are relative to the plan's start (converted to master beats when executed). */
@Serializable
data class Ramp(
    val param: Param,
    val deck: Int,
    val startBeat: Double,
    val endBeat: Double,
    /** null = start from whatever the value is when the ramp begins. */
    val from: Float?,
    val to: Float,
    val curve: Curve = Curve.LINEAR,
)

/** Discrete engine actions. Ids shared with native `ActionType`. */
@Serializable
enum class ActionType(val id: Int) {
    START_DECK(0), STOP_DECK(1), SET_LOOP(2), EXIT_LOOP(3), BRAKE(4), IMPACT(5), RESET_DECK(6),
    SET_SYNC(7), DRUM_PATTERN(8), ANCHOR_MASTER(9), SEEK(10)
}

@Serializable
data class Action(
    val type: ActionType,
    val deck: Int,
    val beat: Double,
    /** START_DECK/SEEK: position seconds. SET_LOOP: length beats. BRAKE: length beats. DRUM_PATTERN: pattern id. SET_SYNC: 0/1. */
    val arg0: Double = 0.0,
    /** SET_LOOP: 1 = slip mode. */
    val arg1: Double = 0.0,
)

/** Drum machine voices (native `DrumVoice`). */
enum class DrumVoice(val id: Int) { KICK(0), CLAP(1), HAT(2), OPEN_HAT(3), PERC(4), BASS(5) }
