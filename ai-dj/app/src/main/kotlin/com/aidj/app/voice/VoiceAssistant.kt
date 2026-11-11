package com.aidj.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.aidj.app.brain.Announcer
import com.aidj.app.brain.DjBrain
import com.aidj.app.brain.ClaudeOrchestrator
import com.aidj.app.brain.TransitionOverrides
import com.aidj.app.data.SettingsStore
import com.aidj.core.model.TransitionStyle
import com.aidj.core.selection.SelectionSteering
import com.aidj.core.voice.CommandParser
import com.aidj.core.voice.VoiceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Push-to-talk voice control. Speech is recognised by Android's SpeechRecognizer; common commands are
 * executed locally (work offline), anything else is handed to Claude with the DJ tools.
 */
class VoiceAssistant(
    private val context: Context,
    private val scope: CoroutineScope,
    private val brain: DjBrain,
    private val announcer: Announcer,
    private val settings: SettingsStore,
) {
    var claude: ClaudeOrchestrator? = null
    data class UiState(val listening: Boolean = false, val partial: String = "", val lastHeard: String = "", val reply: String = "")
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread. */
    fun listen() {
        if (!available) { _state.value = _state.value.copy(reply = "Speech recognition isn't available on this device"); return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { _state.value = _state.value.copy(listening = true, partial = "") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { _state.value = _state.value.copy(listening = false) }
                override fun onError(error: Int) {
                    _state.value = _state.value.copy(listening = false, reply = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "I didn't catch that" else "Voice error $error")
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    _state.value = _state.value.copy(listening = false, lastHeard = text, partial = "")
                    if (text.isNotBlank()) handle(text)
                }
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _state.value = _state.value.copy(partial = it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        // Duck the music while listening so the mic hears the user.
        brain.performer.native.setParam(com.aidj.core.planning.Param.DUCK, -1, 0.7f)
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        brain.performer.native.setParam(com.aidj.core.planning.Param.DUCK, -1, 0f)
    }

    /** Handle a typed or spoken request. */
    fun handle(text: String) {
        brain.performer.native.setParam(com.aidj.core.planning.Param.DUCK, -1, 0f)
        scope.launch(Dispatchers.Default) {
            val reply = execute(CommandParser.parse(text), text)
            _state.value = _state.value.copy(reply = reply)
            announcer.reply(reply)
        }
    }

    private fun execute(cmd: VoiceCommand, raw: String): String {
        val st = brain.steering.value
        return when (cmd) {
            is VoiceCommand.Energy -> {
                brain.steer(st.copy(energyDelta = (st.energyDelta + cmd.delta).coerceIn(-0.5f, 0.5f)))
                if (cmd.delta > 0) "Turning up the energy!" else "Bringing it down a little."
            }
            is VoiceCommand.SetMode -> { brain.setMode(cmd.mode); "Switching to ${cmd.mode.label} mode." }
            is VoiceCommand.Genres -> { brain.steer(st.copy(genres = cmd.genres)); "Mixing ${cmd.genres.joinToString(" with ")} coming up." }
            is VoiceCommand.Era -> { brain.steer(st.copy(yearFrom = cmd.from, yearTo = cmd.to)); "Taking it back to the ${cmd.from % 100}s." }
            is VoiceCommand.Drama -> {
                brain.setOverrides(brain.overrides.value.copy(drama = cmd.drama, style = if (cmd.drama > 0.7f) TransitionStyle.LOOP_ROLL_DROP else TransitionStyle.BASS_SWAP))
                if (cmd.drama > 0.5f) "Next transition is going to be big." else "Keeping the next mix smooth."
            }
            is VoiceCommand.BpmChange -> {
                val bpm = (brain.performer.live.value.masterBpm + cmd.delta).toFloat()
                runCatching { brain.performer.setBpm(bpm, 16f) }.fold({ "Tempo to ${bpm.toInt()}." }, { it.message ?: "Can't change tempo on this track." })
            }
            VoiceCommand.RemixNow -> {
                val c = brain.performer.current ?: return "Nothing is playing."
                runCatching {
                    settings.update { it.copy(autoRemix = true) }
                    brain.performer.remix("drum_layer", null, 0.5f + 0.4f * settings.current.remixIntensity, 16)
                }.fold({ "Remixing it live!" }, { it.message ?: "This track's license doesn't allow remixing." })
            }
            VoiceCommand.RemixStop -> {
                settings.update { it.copy(autoRemix = false) }
                runCatching { brain.performer.remix("stop_remix", null, 0f, 1) }
                "Okay, playing it straight."
            }
            VoiceCommand.PowerDrop -> {
                brain.setOverrides(TransitionOverrides(style = TransitionStyle.LOOP_ROLL_DROP, drama = 1f))
                brain.steer(st.copy(energyDelta = (st.energyDelta + 0.15f).coerceAtMost(0.5f)))
                "Building to a massive drop!"
            }
            VoiceCommand.CrowdPleaser -> {
                brain.steer(st.copy(energyTarget = 0.72f, genres = emptyList(), note = "crowd pleasers"))
                "Something everyone can dance to, coming right up."
            }
            VoiceCommand.Skip -> { brain.skip(); "Next one!" }
            VoiceCommand.Previous -> { brain.previous(); "Going back." }
            VoiceCommand.Pause -> { brain.pause(); "Paused." }
            VoiceCommand.Resume -> { brain.resume(); "Here we go!" }
            VoiceCommand.Lock -> { brain.locked.value = true; "Locked. This one stays on." }
            VoiceCommand.Unlock -> { brain.unlock(); "Unlocked." }
            is VoiceCommand.Volume -> {
                val v = (settings.current.masterVolume + cmd.delta).coerceIn(0.1f, 1f)
                settings.update { it.copy(masterVolume = v) }; brain.performer.setMasterVolume(v)
                if (cmd.delta > 0) "Louder!" else "Quieter."
            }
            is VoiceCommand.Effect -> {
                val c = brain.performer.current ?: return "Nothing is playing."
                val fx = when (cmd.effect) { "filter" -> "filter_highpass"; else -> cmd.effect }
                runCatching { brain.performer.applyEffect(c.deck, fx, 0.6f, 0.75f, 2f) }.fold({ "${cmd.effect.replaceFirstChar { it.uppercase() }}!" }, { it.message ?: "Can't do that." })
            }
            is VoiceCommand.PlayTrack -> {
                val q = cmd.query.lowercase()
                val hit = com.aidj.app.AppGraph.get().library.tracks.value.firstOrNull { lt ->
                    lt.analysis != null && (lt.track.title.lowercase().contains(q) || "${lt.track.artist} ${lt.track.title}".lowercase().contains(q))
                }
                if (hit != null) { brain.playNext(hit.track.id); "Playing ${hit.track.title}." }
                else claude?.command(raw) ?: "I couldn't find that in your library."
            }
            is VoiceCommand.Unknown -> claude?.command(raw) ?: "I can do energy, genres, eras, drops, skips and effects offline. For anything else, connect Claude in Settings."
        }
    }

    fun release() { recognizer?.destroy(); recognizer = null }
}
