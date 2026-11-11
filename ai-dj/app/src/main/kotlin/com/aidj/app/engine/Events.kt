package com.aidj.app.engine

import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.SectionType
import com.aidj.core.model.TransitionStyle
import com.aidj.engine.DeckState
import com.aidj.engine.EngineState

/** Shared performance events: produced from real engine state + analysis, consumed by the AI, visuals and voice. */
sealed class DjEvent {
    data class Beat(val beatInBar: Int, val masterBeat: Long, val strength: Float) : DjEvent()
    data class Bar(val barIndex: Int, val barInPhrase: Int) : DjEvent()
    data class BassHit(val strength: Float) : DjEvent()
    data class SectionChanged(val type: SectionType) : DjEvent()
    data object VocalStarted : DjEvent()
    data object BuildupStarted : DjEvent()
    data object DropStarted : DjEvent()
    data object BreakdownStarted : DjEvent()
    data class TransitionStarted(val style: TransitionStyle, val description: String) : DjEvent()
    data object TransitionCompleted : DjEvent()
    data class TrackChanged(val track: LibraryTrack) : DjEvent()
    data class EnergyChanged(val energy: Float, val rising: Boolean) : DjEvent()
    data class TrackEnded(val deck: Int) : DjEvent()
    data class Gesture(val move: String, val intensity: Float) : DjEvent()
    data class Activity(val text: String) : DjEvent()
}

enum class Phase { IDLE, LOADING, PLAYING, MIXING, PAUSED }

/** What the DJ's hands should be doing right now (drives the 3D character). */
enum class HandTask { NONE, EQ_KNOBS, FILTER_KNOB, FADERS, JOG_NUDGE, FX_BUTTONS, CUE_HEADPHONES, LOOP_BUTTONS }

data class LiveState(
    val phase: Phase = Phase.IDLE,
    val current: LibraryTrack? = null,
    val next: LibraryTrack? = null,
    val currentDeck: Int = 0,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val masterBpm: Double = 0.0,
    val masterBeat: Double = 0.0,
    val keyCamelot: String = "",
    val keyName: String = "",
    val energy: Float = 0f,
    val section: SectionType? = null,
    val barInSection: Int = 0,
    val sectionBars: Int = 0,
    val beatInBar: Int = 0,
    val transitionProgress: Float = -1f,
    val transitionLabel: String = "",
    val activity: String = "Waiting to start the party",
    val handTask: HandTask = HandTask.NONE,
    val engine: EngineState = EngineState.EMPTY,
    val nextMixInSec: Double = -1.0,
    val vocal: Boolean = false,
) {
    val remainingSec: Double get() = (durationSec - positionSec).coerceAtLeast(0.0)
    val deck: DeckState? get() = engine.decks.getOrNull(currentDeck)
}
