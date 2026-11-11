package com.aidj.app.brain

import android.util.Log
import com.aidj.app.data.SettingsStore
import com.aidj.app.engine.CapabilityException
import com.aidj.app.engine.DjEvent
import com.aidj.app.engine.Performer
import com.aidj.app.engine.Phase
import com.aidj.core.model.PartyMode
import com.aidj.core.model.SectionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

/**
 * Autonomous in-track remixing: while a song plays, layer live elements on top of it at musically right
 * moments, driven by the real analysed structure (phrase boundaries, buildups, drops, breakdowns).
 *
 * Flavours are picked from the track's tags/title and the party mode:
 *  - Punjabi / Hindi / Bollywood / bhangra  → dhol percussion layers
 *  - phonk                                  → cowbell + 808 bass pattern
 *  - anything else                          → pattern suggested from tempo/genre
 * plus risers that resolve into real drops, beat rolls on phrase ends, echo throws and breakdown reverb.
 *
 * Guard rails: only tracks whose license allows remixing; respects the auto-remix toggle, AI-off and
 * paused-decisions switches; frequency scales with the remix-intensity setting; stays clear of running or
 * imminent transitions; never stacks two moves. Everything it triggers is executed beat-accurately by the
 * native engine, so none of this depends on UI timing.
 */
class LiveRemixer(
    private val scope: CoroutineScope,
    private val performer: Performer,
    private val settings: SettingsStore,
    private val brain: DjBrain,
) {
    private val rnd = Random(System.nanoTime())
    private var lastMoveBar = -100
    private var busyUntilBar = -1
    private var layersThisTrack = 0

    fun start() {
        scope.launch(Dispatchers.Default) {
            performer.events.collect { e ->
                try {
                    on(e)
                } catch (_: CapabilityException) {
                    // License forbids this move on this track: silently skip.
                } catch (ex: Exception) {
                    Log.w("LiveRemixer", "remix move failed: ${ex.message}")
                }
            }
        }
    }

    private enum class Flavor { DHOL, PHONK, GENERIC }

    private fun flavor(c: Performer.Loaded): Flavor {
        val text = (c.track.track.genres.joinToString(" ") + " " + c.track.track.title + " " + c.track.track.artist +
            " " + c.track.track.album + " " + brain.steering.value.genres.joinToString(" ")).lowercase()
        val mode = settings.current.mode
        return when {
            "phonk" in text -> Flavor.PHONK
            listOf("punjabi", "bhangra", "dhol", "desi", "bollywood", "hindi", "filmi").any { it in text } ||
                mode == PartyMode.PUNJABI || mode == PartyMode.BOLLYWOOD -> Flavor.DHOL
            else -> Flavor.GENERIC
        }
    }

    /** The playing track, if remixing it is allowed right now. */
    private fun ready(): Performer.Loaded? {
        val s = settings.current
        if (!s.autoRemix || !brain.aiEnabled.value || brain.decisionsPaused.value) return null
        if (performer.planStarted) return null
        val live = performer.live.value
        if (live.phase != Phase.PLAYING) return null
        // A transition is scheduled to start soon: leave the stage to it.
        if (live.nextMixInSec in 0.0..30.0) return null
        val c = performer.current ?: return null
        if (!c.track.track.capabilities.remix) return null
        return c
    }

    /** Longest layer (bars) that still finishes before a scheduled transition begins. */
    private fun maxBars(wanted: Int): Int {
        val live = performer.live.value
        val mixIn = live.nextMixInSec
        if (mixIn <= 0 || live.masterBpm <= 0) return wanted
        val barSec = 240.0 / live.masterBpm
        return minOf(wanted, ((mixIn - 8.0) / barSec).toInt()).coerceAtLeast(0)
    }

    private fun on(e: DjEvent) {
        when (e) {
            is DjEvent.TrackChanged -> { lastMoveBar = -100; busyUntilBar = -1; layersThisTrack = 0 }
            is DjEvent.Bar -> onBar(e.barIndex, e.barInPhrase)
            is DjEvent.BuildupStarted -> onBuildup()
            is DjEvent.DropStarted -> onDrop()
            else -> {}
        }
    }

    private fun onBar(bar: Int, barInPhrase: Int) {
        val c = ready() ?: return
        if (bar <= busyUntilBar) return
        val f = flavor(c)
        val intensity = settings.current.remixIntensity
        if (intensity <= 0.02f) return
        val boost = if (f == Flavor.GENERIC) 1f else 1.8f
        val live = performer.live.value
        val section = live.section ?: return

        // Flourish: beat roll on the last bar of an 8-bar phrase, occasionally, mid-groove.
        if (barInPhrase == 7 && section == SectionType.GROOVE && bar - lastMoveBar >= 8 &&
            rnd.nextFloat() < 0.10f * intensity * boost
        ) {
            performer.remix("beat_roll", null, 1f, 1)
            mark(bar, bar + 1)
            return
        }
        if (barInPhrase != 0) return // main moves land on phrase boundaries only

        val minGap = max(6, ((26 - 18 * intensity) / boost).toInt())
        if (bar - lastMoveBar < minGap) return
        val sectionBarsLeft = (live.sectionBars - live.barInSection).coerceAtLeast(0)

        when (section) {
            SectionType.GROOVE, SectionType.DROP -> {
                val roll = rnd.nextFloat()
                val layerChance = ((0.25f + 0.55f * intensity) * boost).coerceAtMost(0.95f)
                if (roll < layerChance && layersThisTrack < 3) {
                    val bars = maxBars(minOf(16, max(8, sectionBarsLeft - 1)))
                    if (bars < 4) return
                    val pattern = when (f) { Flavor.DHOL -> "dhol"; Flavor.PHONK -> "phonk"; Flavor.GENERIC -> null }
                    performer.remix("drum_layer", pattern, 0.45f + 0.4f * intensity, bars)
                    layersThisTrack++
                    mark(bar, bar + bars)
                } else if (roll < layerChance + 0.15f && c.track.track.capabilities.processing) {
                    performer.applyEffect(c.deck, "echo", 0.3f + 0.3f * intensity, 0.75f, 1f)
                    mark(bar, bar + 1)
                }
            }
            SectionType.BREAKDOWN -> if (c.track.track.capabilities.processing && rnd.nextFloat() < 0.3f * intensity) {
                performer.applyEffect(c.deck, "reverb", 0.25f + 0.25f * intensity, 1f, 4f)
                mark(bar, bar + 4)
            }
            else -> {}
        }
    }

    /** A buildup in the track itself: add a riser that peaks exactly where the buildup ends (the drop). */
    private fun onBuildup() {
        val c = ready() ?: return
        val intensity = settings.current.remixIntensity
        if (rnd.nextFloat() >= 0.35f + 0.6f * intensity) return
        val live = performer.live.value
        val barsLeft = maxBars((live.sectionBars - live.barInSection).coerceIn(4, 16))
        if (barsLeft < 4) return
        performer.remix("riser", null, 0.35f + 0.4f * intensity, barsLeft)
        val bar = c.analysis.grid.barIndexAt(live.positionSec)
        mark(bar, bar + barsLeft)
    }

    private fun onDrop() {
        val c = ready() ?: return
        val intensity = settings.current.remixIntensity
        if (rnd.nextFloat() < 0.25f + 0.45f * intensity) {
            performer.remix("impact", null, 1f, 1)
            val bar = c.analysis.grid.barIndexAt(performer.live.value.positionSec)
            // Don't immediately pile a layer on top of the impact; give the drop two bars to breathe.
            if (bar > busyUntilBar) busyUntilBar = bar + 2
        }
    }

    private fun mark(bar: Int, until: Int) { lastMoveBar = bar; busyUntilBar = until }
}
