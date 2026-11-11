package com.aidj.core.voice

import com.aidj.core.model.PartyMode

/** Commands understood fully offline. Anything else is forwarded to Claude when online. */
sealed class VoiceCommand {
    data class Energy(val delta: Float) : VoiceCommand()
    data class SetMode(val mode: PartyMode) : VoiceCommand()
    data class Genres(val genres: List<String>) : VoiceCommand()
    data class Era(val from: Int, val to: Int) : VoiceCommand()
    data class Drama(val drama: Float) : VoiceCommand()
    data class BpmChange(val delta: Double) : VoiceCommand()
    data object PowerDrop : VoiceCommand()
    data object RemixNow : VoiceCommand()
    data object RemixStop : VoiceCommand()
    data object CrowdPleaser : VoiceCommand()
    data object Skip : VoiceCommand()
    data object Previous : VoiceCommand()
    data object Pause : VoiceCommand()
    data object Resume : VoiceCommand()
    data object Lock : VoiceCommand()
    data object Unlock : VoiceCommand()
    data class Volume(val delta: Float) : VoiceCommand()
    data class Effect(val effect: String) : VoiceCommand()
    data class PlayTrack(val query: String) : VoiceCommand()
    data class Unknown(val text: String) : VoiceCommand()
}

object CommandParser {
    private val modeWords = mapOf(
        "bollywood" to PartyMode.BOLLYWOOD, "hindi" to PartyMode.BOLLYWOOD,
        "punjabi" to PartyMode.PUNJABI, "bhangra" to PartyMode.PUNJABI,
        "club" to PartyMode.CLUB, "house" to PartyMode.CLUB, "techno" to PartyMode.CLUB,
        "retro" to PartyMode.RETRO, "classic" to PartyMode.RETRO, "oldies" to PartyMode.RETRO,
        "chill" to PartyMode.CHILL, "lounge" to PartyMode.CHILL,
        "festival" to PartyMode.FESTIVAL, "edm" to PartyMode.FESTIVAL,
        "auto" to PartyMode.AUTO,
    )

    fun parse(raw: String): VoiceCommand {
        val t = raw.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        fun has(vararg w: String) = w.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }

        // Mixing genres: "mix some punjabi with edm"
        Regex("""\bmix (?:some |a bit of )?([a-z ]+?) (?:with|and|into) ([a-z ]+)$""").find(t)?.let { m ->
            return VoiceCommand.Genres(listOf(m.groupValues[1].trim(), m.groupValues[2].trim().removeSuffix(" music")))
        }
        // Eras: "songs from the 2000s", "90s", "eighties"
        Regex("""\b(19|20)?([0-9])0s\b""").find(t)?.let { m ->
            val century = m.groupValues[1].ifEmpty { if (m.groupValues[2].toInt() <= 2) "20" else "19" }
            val start = (century + m.groupValues[2] + "0").toInt()
            return VoiceCommand.Era(start, start + 9)
        }
        mapOf("seventies" to 1970, "eighties" to 1980, "nineties" to 1990).forEach { (w, y) -> if (has(w)) return VoiceCommand.Era(y, y + 9) }

        if (has("skip", "next song", "next track", "change the song", "change song")) return VoiceCommand.Skip
        if (has("previous", "go back", "last song")) return VoiceCommand.Previous
        if (has("pause", "stop the music", "stop music")) return VoiceCommand.Pause
        if (has("resume", "continue", "unpause") || t == "play") return VoiceCommand.Resume
        if (has("unlock")) return VoiceCommand.Unlock
        if (has("lock", "keep this song", "keep playing this")) return VoiceCommand.Lock
        if (has("stop remixing", "stop the remix", "no remix", "no remixing")) return VoiceCommand.RemixStop
        if (has("remix", "remixing", "mashup", "mash it up")) return VoiceCommand.RemixNow
        if (has("drop")) return VoiceCommand.PowerDrop
        if (has("dramatic", "crazy", "wild transition", "big transition")) return VoiceCommand.Drama(1f)
        if (has("smooth", "smoother", "seamless")) return VoiceCommand.Drama(0.1f)
        if (has("everyone can dance", "crowd pleaser", "dance floor", "dancefloor", "everybody dance")) return VoiceCommand.CrowdPleaser
        if (has("louder", "volume up", "turn it up loud")) return VoiceCommand.Volume(+0.1f)
        if (has("quieter", "volume down", "softer", "lower the volume")) return VoiceCommand.Volume(-0.1f)
        if (has("faster", "speed up", "increase the tempo", "increase bpm")) return VoiceCommand.BpmChange(+4.0)
        if (has("slower tempo", "reduce bpm", "decrease the tempo", "slow down the tempo")) return VoiceCommand.BpmChange(-4.0)
        if (has("slow things down", "slow it down", "calm", "relax", "less energy", "tone it down", "chill out")) return VoiceCommand.Energy(-0.25f)
        if (has("more energetic", "more energy", "pump it", "turn it up", "go harder", "hype", "party harder", "energetic")) return VoiceCommand.Energy(+0.2f)
        for (fx in listOf("echo", "reverb", "flanger", "filter", "brake")) if (has(fx)) return VoiceCommand.Effect(fx)

        // Mode switch: "switch to bollywood", "play some punjabi", "club mode"
        if (has("switch to", "play some", "play", "mode", "put on", "give me")) {
            for ((w, mode) in modeWords) if (has(w)) return VoiceCommand.SetMode(mode)
        }
        Regex("""^play (.+)$""").find(t)?.let { return VoiceCommand.PlayTrack(it.groupValues[1]) }
        return VoiceCommand.Unknown(raw)
    }
}
