package com.aidj.app.brain

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aidj.app.data.Personality
import com.aidj.app.data.SettingsStore
import com.aidj.app.engine.Performer
import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.TransitionStyle
import com.aidj.core.planning.Curve
import com.aidj.core.planning.Param
import com.aidj.core.planning.Ramp
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * The DJ's voice. Speaks rarely and at musically sensible moments (track changes, the occasional drop),
 * ducking the music under the voice with a smooth engine ramp. Claude can also request lines via the
 * `announce` tool; those pass through the same rate limit.
 */
class Announcer(context: Context, private val performer: Performer, private val settings: SettingsStore) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSpokenAt = 0L
    private var tracksSinceLast = 0
    private var dropsSinceLast = 0
    private val rnd = Random(System.nanoTime())

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault().takeIf { tts?.isLanguageAvailable(it) ?: -1 >= 0 } ?: Locale.US
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setSpeechRate(1.02f); tts?.setPitch(0.92f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = unduck()
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) = unduck()
                    override fun onError(utteranceId: String?, errorCode: Int) = unduck()
                })
                ready = true
            }
        }
    }

    private fun duck() {
        val now = performer.native.readState().masterBeat
        performer.native.addRamp(Ramp(Param.DUCK, -1, 0.0, 1.0, null, 0.8f, Curve.EASE_OUT), now)
    }

    private fun unduck() {
        val now = performer.native.readState().masterBeat
        performer.native.addRamp(Ramp(Param.DUCK, -1, 0.0, 2.0, null, 0f, Curve.EASE_IN), now)
    }

    /** Speak now (subject to settings and a minimum gap). */
    fun say(text: String, fromAi: Boolean = false, force: Boolean = false): Boolean {
        val s = settings.current
        if (!ready || (!s.voiceAnnouncements && !force)) return false
        val gap = System.currentTimeMillis() - lastSpokenAt
        if (!force && gap < (if (fromAi) 60_000 else 90_000)) return false
        lastSpokenAt = System.currentTimeMillis()
        tracksSinceLast = 0; dropsSinceLast = 0
        duck()
        tts?.speak(text.take(200), TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString())
        return true
    }

    /** Replies to the user always get spoken (they asked). */
    fun reply(text: String) = say(text, force = true)

    private fun personality() = settings.current.personality

    fun onPartyStart(first: LibraryTrack) {
        val p = settings.current.partyName
        val line = when (personality()) {
            Personality.HYPE -> "Welcome to $p! Your AI DJ is on the decks. Let's go!"
            Personality.SMOOTH -> "Good evening and welcome to $p. Sit back, I've got the music."
            Personality.MINIMAL -> return
        }
        say(line, force = true)
    }

    fun onTrackChanged(track: LibraryTrack, played: Int) {
        tracksSinceLast++
        if (personality() == Personality.MINIMAL) return
        if (tracksSinceLast < settings.current.announcementEveryTracks) return
        if (rnd.nextFloat() > 0.6f) return
        val artist = track.track.artist.ifBlank { "this one" }
        val lines = when (personality()) {
            Personality.HYPE -> listOf("Make some noise for $artist!", "Here comes $artist, hands up!", "Turn it up! $artist on the decks!", "Keep that energy! This is ${track.track.title}.")
            Personality.SMOOTH -> listOf("Next up, ${track.track.title} by $artist.", "Something special from $artist.", "Let's keep the groove going with $artist.")
            Personality.MINIMAL -> emptyList()
        }
        lines.randomOrNull(rnd)?.let { say(it) }
    }

    fun onDrop() {
        dropsSinceLast++
        if (personality() != Personality.HYPE || dropsSinceLast < 3 || rnd.nextFloat() > 0.3f) return
        say(listOf("Here it comes!", "Drop it!", "Let's go!").random(rnd))
    }

    fun onTransition(style: TransitionStyle) { /* Real DJs stay quiet during the mix itself. */ }

    fun shutdown() { tts?.shutdown(); tts = null }
}
