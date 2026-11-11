package com.aidj.core.model

import kotlinx.serialization.Serializable

/** Where a track came from. Determines which rights the app can assume. */
@Serializable
enum class SourceType {
    /** A file the user imported or that lives in the device's media library. */
    LOCAL_FILE,
    /** Creative-Commons track downloaded from Jamendo (license recorded per track). */
    JAMENDO,
    /** Audio item downloaded from the Internet Archive (license recorded per item). */
    INTERNET_ARCHIVE,
}

/**
 * The source compatibility layer: what the app is allowed to do with a track.
 * Every DJ operation checks these flags before it touches audio.
 */
@Serializable
data class SourceCapabilities(
    val playback: Boolean,
    /** Decode the audio locally to compute BPM/key/structure. */
    val analysis: Boolean,
    /** Tempo/pitch change, EQ, filters, effects, beatmatched transitions. */
    val processing: Boolean,
    /** Derivative operations: loops that extend sections, drum layers, mashups. */
    val remix: Boolean,
    /** The audio may be stored on the device for offline use. */
    val offline: Boolean,
    /** Human-readable reason, shown in the UI. */
    val note: String,
) {
    companion object {
        /**
         * Files the user put on the device. The user is responsible for holding the rights;
         * private performance processing (DJ mixing) of owned files is permitted by default.
         */
        val LOCAL = SourceCapabilities(
            playback = true, analysis = true, processing = true, remix = true, offline = true,
            note = "Your own file: playback, analysis and private DJ processing enabled",
        )

        /**
         * Map a Creative Commons license URL to capabilities. ND ("no derivatives") licenses
         * forbid remixing; every CC license permits playback, analysis and storing a copy.
         * Tempo/EQ mixing is treated as processing, while loops/drum layers/mashups count as remix.
         */
        fun fromCreativeCommons(licenseUrl: String?): SourceCapabilities {
            val url = licenseUrl?.lowercase().orEmpty()
            if (url.isBlank()) {
                return SourceCapabilities(
                    playback = true, analysis = true, processing = false, remix = false, offline = true,
                    note = "License unknown: playback only",
                )
            }
            val isCc = "creativecommons.org" in url
            val isPublicDomain = "publicdomain" in url
            val noDerivs = Regex("""(^|[/-])nd([/-]|$)""").containsMatchIn(url.substringAfter("licenses/", ""))
            return when {
                isPublicDomain -> SourceCapabilities(true, true, true, true, true, "Public domain")
                isCc && noDerivs -> SourceCapabilities(
                    playback = true, analysis = true, processing = false, remix = false, offline = true,
                    note = "CC No-Derivatives: played unmodified (no tempo change, effects or remixing)",
                )
                isCc -> SourceCapabilities(
                    playback = true, analysis = true, processing = true, remix = true, offline = true,
                    note = "Creative Commons (derivatives allowed): ${licenseShortName(url)}",
                )
                else -> SourceCapabilities(
                    playback = true, analysis = true, processing = false, remix = false, offline = true,
                    note = "Non-CC license: playback only",
                )
            }
        }

        fun licenseShortName(url: String): String {
            val u = url.lowercase()
            if ("publicdomain" in u) return "Public Domain"
            val part = u.substringAfter("licenses/", "").trim('/').split('/')
            if (part.isEmpty() || part[0].isBlank()) return "Unknown"
            val v = part.getOrNull(1)?.let { " $it" } ?: ""
            return "CC " + part[0].uppercase() + v
        }
    }
}

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long,
    /** content:// or file:// URI the decoder can open. */
    val uri: String,
    val source: SourceType,
    val capabilities: SourceCapabilities,
    /** Genre tags from file metadata or the source API. Lower-case. */
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val licenseUrl: String? = null,
    val artworkUrl: String? = null,
    val explicit: Boolean = false,
    val language: String? = null,
) {
    val displayName: String get() = if (artist.isBlank()) title else "$artist – $title"
}
