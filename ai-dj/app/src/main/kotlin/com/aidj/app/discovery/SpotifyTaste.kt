package com.aidj.app.discovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.aidj.app.data.SettingsStore
import com.aidj.app.data.TasteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Reads the user's Spotify listening history (top tracks, top artists, recently played) into the local
 * [TasteStore], via Spotify's official Web API with Authorization Code + PKCE — made for mobile apps,
 * no client secret involved. The user supplies their own (free) Spotify developer Client ID; tokens are
 * stored in Android-Keystore-encrypted preferences and used only against api.spotify.com.
 *
 * This reads taste only. Spotify audio is never downloaded, decoded or mixed.
 */
class SpotifyTaste(private val context: Context, private val settings: SettingsStore, private val taste: TasteStore) {

    data class State(val connected: Boolean = false, val busy: Boolean = false, val message: String = "", val lastSync: Long = 0)

    private val _state = MutableStateFlow(State(connected = settings.spotifyRefreshToken.isNotBlank()))
    val state: StateFlow<State> = _state
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Opens Spotify's consent page in the browser. The redirect lands back in MainActivity (aidj://spotify-callback). */
    fun beginAuth(clientId: String) {
        val id = clientId.trim()
        if (id.isBlank()) { _state.value = _state.value.copy(message = "Enter your Spotify Client ID first"); return }
        settings.spotifyClientId = id
        val verifier = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { b64url(it) }
        settings.spotifyVerifier = verifier
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", "user-top-read user-read-recently-played")
            .build()
        _state.value = _state.value.copy(message = "Waiting for Spotify…")
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Called by MainActivity with the aidj://spotify-callback redirect. */
    suspend fun handleCallback(uri: Uri) {
        uri.getQueryParameter("error")?.let { _state.value = _state.value.copy(message = "Spotify: $it"); return }
        val code = uri.getQueryParameter("code") ?: run { _state.value = _state.value.copy(message = "Spotify: no code returned"); return }
        _state.value = _state.value.copy(busy = true, message = "Connecting to Spotify…")
        try {
            val tokens = token(FormBody.Builder()
                .add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", REDIRECT).add("client_id", settings.spotifyClientId)
                .add("code_verifier", settings.spotifyVerifier).build())
            settings.spotifyRefreshToken = tokens.second.ifBlank { settings.spotifyRefreshToken }
            sync(tokens.first)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = "Spotify connect failed: ${e.message}")
        }
    }

    /** Re-pull history with the stored refresh token (the "Sync now" button). */
    suspend fun syncNow() {
        if (settings.spotifyRefreshToken.isBlank()) { _state.value = _state.value.copy(message = "Connect Spotify first"); return }
        _state.value = _state.value.copy(busy = true, message = "Syncing Spotify history…")
        try {
            val tokens = token(FormBody.Builder()
                .add("grant_type", "refresh_token").add("refresh_token", settings.spotifyRefreshToken)
                .add("client_id", settings.spotifyClientId).build())
            if (tokens.second.isNotBlank()) settings.spotifyRefreshToken = tokens.second
            sync(tokens.first)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, message = "Spotify sync failed: ${e.message}")
        }
    }

    fun disconnect() {
        settings.spotifyRefreshToken = ""
        taste.replaceSource(SOURCE)
        _state.value = State(connected = false, message = "Spotify disconnected; its taste data removed")
    }

    /** Returns (accessToken, refreshToken-or-blank). */
    private suspend fun token(body: FormBody): Pair<String, String> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val desc = runCatching { json.parseToJsonElement(text).jsonObject["error_description"]?.jsonPrimitive?.content }.getOrNull()
                throw IOException(desc ?: "HTTP ${r.code}")
            }
            val o = json.parseToJsonElement(text).jsonObject
            (o["access_token"]?.jsonPrimitive?.content ?: throw IOException("No access token")) to
                (o["refresh_token"]?.jsonPrimitive?.content ?: "")
        }
    }

    private suspend fun sync(accessToken: String) = withContext(Dispatchers.IO) {
        val entries = ArrayList<Triple<String, String, Float>>()
        fun get(path: String): String = http.newCall(
            Request.Builder().url("https://api.spotify.com$path").header("Authorization", "Bearer $accessToken").build(),
        ).execute().use { r ->
            if (r.code == 403) throw IOException("Spotify refused (add yourself as a user of your Spotify app in its dashboard)")
            if (!r.isSuccessful) throw IOException("Spotify HTTP ${r.code} on $path")
            r.body?.string().orEmpty()
        }
        fun addTracks(body: String, weight: Float, itemPath: (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject?) {
            val items = json.parseToJsonElement(body).jsonObject["items"]?.jsonArray ?: return
            for (it in items) {
                val t = itemPath(it.jsonObject) ?: continue
                val name = t["name"]?.jsonPrimitive?.content ?: continue
                val artist = t["artists"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                entries += Triple(artist, name, weight)
            }
        }
        addTracks(get("/v1/me/top/tracks?time_range=short_term&limit=50"), 3f) { it }
        addTracks(get("/v1/me/top/tracks?time_range=medium_term&limit=50"), 2f) { it }
        addTracks(get("/v1/me/player/recently-played?limit=50"), 1f) { it["track"]?.jsonObject }
        val artists = json.parseToJsonElement(get("/v1/me/top/artists?limit=50")).jsonObject["items"]?.jsonArray
        for (a in artists ?: emptyList()) {
            a.jsonObject["name"]?.jsonPrimitive?.content?.let { entries += Triple(it, "", 4f) }
        }
        // Replace (not accumulate) so repeated syncs reflect current taste instead of inflating it.
        taste.replaceSource(SOURCE)
        taste.addBatch(entries, SOURCE)
        _state.value = State(connected = true, busy = false, lastSync = System.currentTimeMillis(),
            message = "Synced ${entries.size} entries from Spotify")
    }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        const val REDIRECT = "aidj://spotify-callback"
        const val SOURCE = "spotify_api"
    }
}
