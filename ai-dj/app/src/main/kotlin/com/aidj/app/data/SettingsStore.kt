package com.aidj.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.aidj.app.BuildConfig
import com.aidj.core.model.PartyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

@Serializable enum class GraphicsQuality { AUTO, LOW, MEDIUM, HIGH }
@Serializable enum class StageTheme(val label: String) { NEON_CLUB("Neon club"), SUNSET_FESTIVAL("Sunset festival"), ROOFTOP("Midnight rooftop"), WAREHOUSE("Warehouse rave") }
@Serializable enum class LightingStyle(val label: String) { BEAT_SYNC("Beat-synced beams"), SMOOTH("Smooth washes"), LASERS("Lasers & strobes") }
@Serializable enum class Personality(val label: String) { HYPE("Hype MC"), SMOOTH("Smooth selector"), MINIMAL("Let the music talk") }
@Serializable enum class InternetUsage(val label: String) { ALWAYS("Wi-Fi and mobile data"), WIFI_ONLY("Wi-Fi only"), OFFLINE("Offline (local AI only)") }
@Serializable enum class AudioQuality(val label: String) { STUDIO("Studio: key-lock always on"), BALANCED("Balanced"), BATTERY("Battery saver: vinyl-style tempo (no key-lock)") }

@Serializable
data class AvatarConfig(
    val skinTone: Int = 2,         // index into palette
    val outfit: Int = 0,           // outfit colour scheme
    val hair: Int = 1,             // 0 bald, 1 short, 2 cap, 3 long
    val headphones: Int = 0,       // colour index
    val style: Int = 0,            // 0 energetic, 1 cool, 2 technical (animation personality)
)

@Serializable
data class Settings(
    val partyName: String = "House Party",
    val mode: PartyMode = PartyMode.AUTO,
    val customGenres: List<String> = emptyList(),
    val preferredGenres: List<String> = emptyList(),
    val allowExplicit: Boolean = true,
    val remixIntensity: Float = 0.5f,
    val autoRemix: Boolean = true,
    /** 0 = automatic (mode default), else bars. */
    val transitionBars: Int = 0,
    val keyMatching: Boolean = true,
    val personality: Personality = Personality.HYPE,
    val voiceAnnouncements: Boolean = true,
    val announcementEveryTracks: Int = 3,
    val audioQuality: AudioQuality = AudioQuality.STUDIO,
    val graphics: GraphicsQuality = GraphicsQuality.AUTO,
    val stageTheme: StageTheme = StageTheme.NEON_CLUB,
    val lighting: LightingStyle = LightingStyle.BEAT_SYNC,
    val avatar: AvatarConfig = AvatarConfig(),
    val internet: InternetUsage = InternetUsage.ALWAYS,
    val claudeEnabled: Boolean = true,
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val mcpServerEnabled: Boolean = false,
    val mcpPort: Int = 8765,
    val masterVolume: Float = 0.9f,
)

/** Persistent settings (plain prefs) + secrets (Android Keystore-backed encrypted prefs). */
class SettingsStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs: SharedPreferences = context.getSharedPreferences("aidj_settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "aidj_secrets", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore unavailable (rare, broken devices): fall back to private prefs rather than crash.
        context.getSharedPreferences("aidj_secrets_fallback", Context.MODE_PRIVATE)
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    private fun load(): Settings = prefs.getString("settings", null)?.let {
        runCatching { json.decodeFromString<Settings>(it) }.getOrNull()
    } ?: Settings()

    fun update(block: (Settings) -> Settings) {
        val s = block(_settings.value)
        _settings.value = s
        prefs.edit().putString("settings", json.encodeToString(s)).apply()
    }

    /** Device token for the AI DJ backend (issued by whoever runs the backend). */
    var backendToken: String
        get() = secrets.getString("backend_token", "") ?: ""
        set(v) { secrets.edit().putString("backend_token", v.trim()).apply() }

    /** Optional personal Jamendo client id (otherwise the backend proxies Jamendo with its own). */
    var jamendoClientId: String
        get() = secrets.getString("jamendo_client_id", "") ?: ""
        set(v) { secrets.edit().putString("jamendo_client_id", v.trim()).apply() }

    /** Bearer token MCP clients must present to the on-device MCP server. Generated once. */
    val mcpToken: String
        get() = secrets.getString("mcp_token", null) ?: run {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val t = bytes.joinToString("") { "%02x".format(it) }
            secrets.edit().putString("mcp_token", t).apply()
            t
        }

    fun regenerateMcpToken() { secrets.edit().remove("mcp_token").apply() }

    // Crash recovery: what was playing, so the party can resume after a process death.
    fun saveSession(trackId: String?, positionSec: Double, playing: Boolean) {
        prefs.edit().putString("session_track", trackId).putFloat("session_pos", positionSec.toFloat())
            .putBoolean("session_playing", playing).putLong("session_ts", System.currentTimeMillis()).apply()
    }

    data class SavedSession(val trackId: String, val positionSec: Double, val ageMs: Long)

    fun savedSession(): SavedSession? {
        val id = prefs.getString("session_track", null) ?: return null
        if (!prefs.getBoolean("session_playing", false)) return null
        return SavedSession(id, prefs.getFloat("session_pos", 0f).toDouble(), System.currentTimeMillis() - prefs.getLong("session_ts", 0))
    }

    fun clearSession() { prefs.edit().putBoolean("session_playing", false).apply() }

    @Suppress("unused") private fun encode(s: Settings) = json.encodeToString(s)
}
