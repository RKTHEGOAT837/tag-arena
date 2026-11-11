package com.aidj.app.discovery

import android.content.Context
import android.net.Uri
import com.aidj.app.data.LibraryRepository
import com.aidj.app.data.SettingsStore
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A track found online, with its license and what the app may do with it. */
@Serializable
data class OnlineTrack(
    val source: SourceType,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val genres: List<String>,
    val year: Int?,
    val licenseUrl: String?,
    val licenseName: String,
    val imageUrl: String?,
    /** The source explicitly permits downloading the file (required before we store/process it). */
    val downloadAllowed: Boolean,
    val downloadUrl: String?,
    val capabilities: SourceCapabilities,
) {
    val libraryId: String get() = LibraryRepository.idFor("${source.name}:$sourceId")
}

class DiscoveryException(message: String) : IOException(message)

/**
 * Legitimate online music sources. Both catalogues publish per-track Creative Commons licenses and
 * offer sanctioned downloads, so tracks can be analysed and (license permitting) mixed and remixed.
 *
 * Not integrated as audio sources, by design: Spotify, YouTube Music and SoundCloud. Their terms and
 * SDKs do not allow decoding, re-processing or mixing the streams in a third-party audio engine, and
 * their content is DRM/stream-protected. (See docs/LICENSING.md.)
 */
class Discovery(private val context: Context, private val settings: SettingsStore, private val library: LibraryRepository) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, source: SourceType, limit: Int = 20): List<OnlineTrack> = withContext(Dispatchers.IO) {
        when (source) {
            SourceType.JAMENDO -> jamendo(query, limit)
            SourceType.INTERNET_ARCHIVE -> archive(query, limit)
            SourceType.LOCAL_FILE -> emptyList()
        }
    }

    // ------------------------------------------------------------------ Jamendo
    private fun jamendo(query: String, limit: Int): List<OnlineTrack> {
        val own = settings.jamendoClientId
        val url = if (own.isNotBlank()) {
            "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", own)
        } else {
            val base = settings.current.backendUrl.trimEnd('/')
            if (base.isBlank()) throw DiscoveryException("Jamendo needs either the AI DJ backend URL or your own Jamendo client id (Settings → Online)")
            "$base/v1/jamendo/tracks".toHttpUrl().newBuilder()
        }.addQueryParameter("format", "json").addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .addQueryParameter("search", query).addQueryParameter("include", "musicinfo licenses")
            .addQueryParameter("audiodlformat", "mp32").addQueryParameter("order", "relevance")
            .build()
        val req = Request.Builder().url(url).apply {
            if (own.isBlank()) header("Authorization", "Bearer ${settings.backendToken}")
        }.build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw DiscoveryException("Jamendo search failed (HTTP ${r.code})")
            r.body?.string() ?: ""
        }
        val root = json.parseToJsonElement(body).jsonObject
        val headers = root["headers"]?.jsonObject
        if (headers?.get("status")?.jsonPrimitive?.contentOrNull == "failed") {
            throw DiscoveryException("Jamendo: ${headers["error_message"]?.jsonPrimitive?.contentOrNull}")
        }
        return (root["results"] as? JsonArray).orEmpty().map { it.jsonObject }.map { o ->
            val license = o.str("license_ccurl")
            val allowed = o["audiodownload_allowed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val genres = o["musicinfo"]?.jsonObject?.get("tags")?.jsonObject?.get("genres")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val caps = SourceCapabilities.fromCreativeCommons(license)
            OnlineTrack(
                source = SourceType.JAMENDO, sourceId = o.str("id") ?: "", title = o.str("name") ?: "Untitled",
                artist = o.str("artist_name") ?: "", album = o.str("album_name") ?: "",
                durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0, genres = genres,
                year = o.str("releasedate")?.take(4)?.toIntOrNull(), licenseUrl = license,
                licenseName = license?.let { SourceCapabilities.licenseShortName(it) } ?: "Unknown",
                imageUrl = o.str("image"), downloadAllowed = allowed && !o.str("audiodownload").isNullOrBlank(),
                downloadUrl = o.str("audiodownload"), capabilities = if (allowed) caps else caps.copy(offline = false, processing = false, remix = false, analysis = false, note = "Artist does not allow downloads"),
            )
        }.filter { it.sourceId.isNotBlank() }
    }

    // ------------------------------------------------------------------ Internet Archive
    private fun archive(query: String, limit: Int): List<OnlineTrack> {
        val q = "(${query.replace("\"", "")}) AND mediatype:(audio) AND licenseurl:(*creativecommons* OR *publicdomain*)"
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title").addQueryParameter("fl[]", "creator")
            .addQueryParameter("fl[]", "licenseurl").addQueryParameter("fl[]", "subject").addQueryParameter("fl[]", "year")
            .addQueryParameter("rows", limit.coerceIn(1, 50).toString()).addQueryParameter("output", "json")
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw DiscoveryException("Internet Archive search failed (HTTP ${r.code})")
            r.body?.string() ?: ""
        }
        val docs = json.parseToJsonElement(body).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()
        return docs.map { it.jsonObject }.map { o ->
            val license = o.str("licenseurl")
            val subj = when (val s = o["subject"]) {
                is JsonArray -> s.mapNotNull { it.jsonPrimitive.contentOrNull }
                is JsonPrimitive -> s.content.split(';', ',')
                else -> emptyList()
            }.map { it.trim().lowercase() }.filter { it.isNotBlank() }.take(6)
            val creator = when (val c = o["creator"]) { is JsonArray -> c.firstOrNull()?.jsonPrimitive?.contentOrNull; is JsonPrimitive -> c.content; else -> null }
            OnlineTrack(
                source = SourceType.INTERNET_ARCHIVE, sourceId = o.str("identifier") ?: "", title = o.str("title") ?: "Untitled",
                artist = creator ?: "", album = "", durationSec = 0, genres = subj, year = o.str("year")?.take(4)?.toIntOrNull(),
                licenseUrl = license, licenseName = license?.let { SourceCapabilities.licenseShortName(it) } ?: "Unknown",
                imageUrl = "https://archive.org/services/img/${o.str("identifier")}", downloadAllowed = true, downloadUrl = null,
                capabilities = SourceCapabilities.fromCreativeCommons(license),
            )
        }.filter { it.sourceId.isNotBlank() }
    }

    /** Resolve the first MP3 file of an Internet Archive item. */
    private fun archiveFileUrl(identifier: String): Pair<String, Int> {
        val body = http.newCall(Request.Builder().url("https://archive.org/metadata/$identifier").build()).execute().use { r ->
            if (!r.isSuccessful) throw DiscoveryException("Internet Archive metadata failed (HTTP ${r.code})")
            r.body?.string() ?: ""
        }
        val files = json.parseToJsonElement(body).jsonObject["files"]?.jsonArray ?: throw DiscoveryException("Item has no files")
        val f = files.map { it.jsonObject }.firstOrNull { (it.str("format") ?: "").contains("MP3", true) && (it.str("length")?.toDoubleOrNull() ?: 0.0) >= 60 }
            ?: files.map { it.jsonObject }.firstOrNull { (it.str("format") ?: "").contains("MP3", true) }
            ?: throw DiscoveryException("No MP3 in this item")
        val name = f.str("name")!!
        val len = f.str("length")?.toDoubleOrNull()?.toInt() ?: 0
        return "https://archive.org/download/$identifier/${Uri.encode(name, "/")}" to len
    }

    /**
     * Download a track the source permits downloading, store it in app-private storage and add it to
     * the library with its license-derived capabilities.
     */
    suspend fun download(t: OnlineTrack, progress: (Float) -> Unit): Track = withContext(Dispatchers.IO) {
        if (!t.downloadAllowed) throw DiscoveryException("The artist/source does not permit downloading this track")
        val (url, len) = when (t.source) {
            SourceType.JAMENDO -> (t.downloadUrl ?: throw DiscoveryException("No download URL")) to t.durationSec
            SourceType.INTERNET_ARCHIVE -> archiveFileUrl(t.sourceId)
            else -> throw DiscoveryException("Unsupported source")
        }
        val dir = File(context.filesDir, "music").apply { mkdirs() }
        val file = File(dir, "${t.source.name.lowercase()}_${t.sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.mp3")
        val tmp = File(dir, file.name + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw DiscoveryException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw DiscoveryException("Empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); read += n
                        if (total > 0) progress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (!tmp.renameTo(file)) throw DiscoveryException("Could not save file")
        val track = Track(
            id = t.libraryId, title = t.title, artist = t.artist, album = t.album,
            durationMs = (if (len > 0) len else t.durationSec) * 1000L, uri = Uri.fromFile(file).toString(),
            source = t.source, capabilities = t.capabilities, genres = t.genres.map { it.lowercase() }, year = t.year,
            licenseUrl = t.licenseUrl, artworkUrl = t.imageUrl,
        )
        val withDuration = library.readMetadata(Uri.fromFile(file), track.id, t.source, t.capabilities)?.let { md ->
            track.copy(durationMs = md.durationMs)
        } ?: track
        library.upsert(withDuration)
        withDuration
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
