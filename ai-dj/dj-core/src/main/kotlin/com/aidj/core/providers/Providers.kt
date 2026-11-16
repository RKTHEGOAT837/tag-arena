package com.aidj.core.providers

import com.aidj.core.model.PlaybackType
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ============================================================================ Audius

/**
 * Audius: open music platform with an official public API (no key) whose terms let third-party apps
 * stream tracks. Full-length 320 kbps MP3 with HTTP range support; many tracks carry BPM/key metadata.
 * Stream URLs are the API's /stream endpoint, which re-signs on every request, so they never go stale.
 */
class AudiusProvider(http: OkHttpClient, private val appName: String = "AIDJ") : HttpProviderBase(http), MusicProvider {
    override val source = SourceType.AUDIUS
    override val name = "Audius"
    override val setupHint: String? = null
    override fun isConfigured() = true
    private val base = "https://api.audius.co/v1"

    override fun search(query: String, limit: Int): List<Track> {
        val url = "$base/tracks/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("limit", (limit * 2).coerceAtMost(50).toString())
            .addQueryParameter("app_name", appName).build().toString()
        return parseList(getJson(url, what = name)).take(limit)
    }

    override fun trending(category: Category?, limit: Int): List<Track> {
        if (category?.audiusGenre != null) {
            val url = "$base/tracks/trending".toHttpUrl().newBuilder()
                .addQueryParameter("genre", category.audiusGenre).addQueryParameter("time", "month")
                .addQueryParameter("app_name", appName).build().toString()
            return parseList(getJson(url, what = name)).take(limit)
        }
        if (category == null) {
            val url = "$base/tracks/trending".toHttpUrl().newBuilder().addQueryParameter("time", "week")
                .addQueryParameter("app_name", appName).build().toString()
            return parseList(getJson(url, what = name)).take(limit)
        }
        // Regional shelves (Punjabi, Bollywood, …) have no Audius genre: search, then rank by plays.
        return search(category.query, 40).sortedByDescending { playCounts[it.id] ?: 0L }.take(limit)
    }

    override fun trackDetails(providerTrackId: String): Track? {
        val o = (getJson("$base/tracks/$providerTrackId?app_name=$appName", what = name) as? JsonObject)?.get("data") as? JsonObject ?: return null
        return toTrack(o)
    }

    private val playCounts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun parseList(root: kotlinx.serialization.json.JsonElement): List<Track> {
        val data = (root as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.let(::toTrack) }
    }

    private fun toTrack(o: JsonObject): Track? {
        val id = o.str("id") ?: return null
        // Only genuinely playable, publicly streamable tracks: skip deleted, unlisted-gated or pay/stream-gated ones.
        if ((o["is_streamable"] as? JsonPrimitive)?.contentOrNull == "false") return null
        if ((o["is_delete"] as? JsonPrimitive)?.contentOrNull == "true") return null
        if (o["stream_conditions"].let { it != null && it !is kotlinx.serialization.json.JsonNull }) return null
        val duration = o.num("duration") ?: return null
        if (duration < 30) return null
        val user = o["user"] as? JsonObject
        val art = (o["artwork"] as? JsonObject)?.let { it.str("480x480") ?: it.str("1000x1000") ?: it.str("150x150") }
        val tags = o.str("tags")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }.orEmpty()
        val genres = (listOfNotNull(o.str("genre")?.lowercase()) + tags).distinct().take(8)
        o.num("play_count")?.let { playCounts["audius:$id"] = it.toLong() }
        return Track(
            id = "audius:$id",
            title = o.str("title") ?: return null,
            artist = user?.str("name") ?: "",
            album = "",
            durationMs = (duration * 1000).toLong(),
            uri = "https://api.audius.co/v1/tracks/$id/stream?app_name=$appName",
            source = SourceType.AUDIUS,
            capabilities = SourceCapabilities.fromAudiusLicense(o.str("license")),
            genres = genres,
            year = o.str("release_date")?.take(4)?.toIntOrNull(),
            licenseUrl = null,
            artworkUrl = art,
            playback = PlaybackType.DIRECT_STREAM,
            bpmHint = o.num("bpm")?.takeIf { it in 40.0..250.0 },
            keyHint = o.str("musical_key"),
            providerTrackId = id,
        )
    }
}

// ============================================================================ Internet Archive

/**
 * Internet Archive audio items that carry a Creative Commons / public-domain licence. Each result is
 * resolved to a concrete MP3 file (verified to exist) before it is returned, so every result plays.
 */
class InternetArchiveProvider(http: OkHttpClient) : HttpProviderBase(http), MusicProvider {
    override val source = SourceType.INTERNET_ARCHIVE
    override val name = "Internet Archive"
    override val setupHint: String? = null
    override fun isConfigured() = true
    private val resolver = Executors.newFixedThreadPool(6) { r -> Thread(r, "ia-resolve").apply { isDaemon = true } }

    override fun search(query: String, limit: Int): List<Track> {
        val q = "(${query.replace("\"", "")}) AND mediatype:(audio) AND licenseurl:(*creativecommons* OR *publicdomain*)"
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .apply { for (f in listOf("identifier", "title", "creator", "licenseurl", "subject", "year", "downloads")) addQueryParameter("fl[]", f) }
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", (limit.coerceAtMost(12)).toString()).addQueryParameter("output", "json").build().toString()
        val docs = ((getJson(url, what = name) as? JsonObject)?.get("response") as? JsonObject)?.get("docs") as? JsonArray ?: return emptyList()
        val futures = docs.mapNotNull { it as? JsonObject }.map { d -> resolver.submit(Callable { runCatching { resolve(d) }.getOrNull() }) }
        return futures.mapNotNull { runCatching { it.get(12, TimeUnit.SECONDS) }.getOrNull() }.take(limit)
    }

    private fun resolve(d: JsonObject): Track? {
        val ident = d.str("identifier") ?: return null
        val meta = getJson("https://archive.org/metadata/$ident", what = name) as? JsonObject ?: return null
        val files = (meta["files"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val mp3 = files.filter { (it.str("format") ?: "").contains("MP3", true) }
            .firstOrNull { (it.str("length")?.toDoubleOrNull() ?: parseClock(it.str("length")) ?: 0.0) >= 60 } ?: return null
        val fileName = mp3.str("name") ?: return null
        val seconds = mp3.str("length")?.toDoubleOrNull() ?: parseClock(mp3.str("length")) ?: 0.0
        val license = d.str("licenseurl")
        val subjects = when (val s = d["subject"]) {
            is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> s.content.split(';', ',')
            else -> emptyList()
        }.map { it.trim().lowercase() }.filter { it.isNotBlank() }.take(6)
        val creator = when (val c = d["creator"]) { is JsonArray -> (c.firstOrNull() as? JsonPrimitive)?.contentOrNull; is JsonPrimitive -> c.contentOrNull; else -> null }
        val encoded = fileName.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return Track(
            id = "internet_archive:$ident/$fileName",
            title = mp3.str("title") ?: d.str("title") ?: fileName.substringBeforeLast('.'),
            artist = mp3.str("artist") ?: mp3.str("creator") ?: creator ?: "",
            album = d.str("title") ?: "",
            durationMs = (seconds * 1000).toLong(),
            uri = "https://archive.org/download/$ident/$encoded",
            source = SourceType.INTERNET_ARCHIVE,
            capabilities = SourceCapabilities.fromCreativeCommons(license).copy(offline = false),
            genres = subjects,
            year = d.str("year")?.take(4)?.toIntOrNull(),
            licenseUrl = license,
            artworkUrl = "https://archive.org/services/img/$ident",
            playback = PlaybackType.DIRECT_STREAM,
            providerTrackId = ident,
        )
    }

    private fun parseClock(s: String?): Double? {
        if (s == null || ':' !in s) return null
        return s.split(':').fold(0.0) { acc, part -> acc * 60 + (part.toDoubleOrNull() ?: return null) }
    }
}

// ============================================================================ Jamendo

/**
 * Jamendo: Creative Commons catalogue whose API returns direct stream URLs (no download needed).
 * Needs a free client id — either the user's own or via the AI DJ backend's proxy.
 */
class JamendoProvider(
    http: OkHttpClient,
    private val clientId: () -> String,
    private val backendUrl: () -> String,
    private val backendToken: () -> String,
) : HttpProviderBase(http), MusicProvider {
    override val source = SourceType.JAMENDO
    override val name = "Jamendo"
    override val setupHint = "Free Jamendo client id (devportal.jamendo.com) in Settings → Music providers, or an AI DJ backend with JAMENDO_CLIENT_ID"
    override fun isConfigured() = clientId().isNotBlank() || (backendUrl().isNotBlank() && backendToken().isNotBlank())

    override fun search(query: String, limit: Int): List<Track> = query(limit) { it.addQueryParameter("search", query).addQueryParameter("order", "relevance") }

    override fun trending(category: Category?, limit: Int): List<Track> = query(limit) {
        it.addQueryParameter("order", "popularity_month")
        if (category != null) it.addQueryParameter("search", category.query)
    }

    private fun query(limit: Int, extra: (okhttp3.HttpUrl.Builder) -> Unit): List<Track> {
        val own = clientId()
        val b = if (own.isNotBlank()) "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder().addQueryParameter("client_id", own)
        else "${backendUrl().trimEnd('/')}/v1/jamendo/tracks".toHttpUrl().newBuilder()
        b.addQueryParameter("format", "json").addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .addQueryParameter("include", "musicinfo licenses").addQueryParameter("audioformat", "mp32")
        extra(b)
        val headers = if (own.isBlank()) mapOf("Authorization" to "Bearer ${backendToken()}") else emptyMap()
        val root = getJson(b.build().toString(), headers, name) as? JsonObject ?: return emptyList()
        val hdr = root["headers"] as? JsonObject
        if (hdr?.str("status") == "failed") throw ProviderException("Jamendo: ${hdr.str("error_message") ?: "request failed"}", retryable = false)
        return (root["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val stream = o.str("audio") ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val license = o.str("license_ccurl")
            val genres = ((o["musicinfo"] as? JsonObject)?.get("tags") as? JsonObject)?.get("genres")?.let { g ->
                (g as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }.orEmpty()
            Track(
                id = "jamendo:$id", title = o.str("name") ?: "Untitled", artist = o.str("artist_name") ?: "", album = o.str("album_name") ?: "",
                durationMs = ((o.num("duration") ?: 0.0) * 1000).toLong(), uri = stream, source = SourceType.JAMENDO,
                capabilities = SourceCapabilities.fromCreativeCommons(license).copy(offline = false),
                genres = genres.map { it.lowercase() }, year = o.str("releasedate")?.take(4)?.toIntOrNull(),
                licenseUrl = license, artworkUrl = o.str("image"), playback = PlaybackType.DIRECT_STREAM, providerTrackId = id,
            )
        }
    }
}

// ============================================================================ YouTube

/**
 * YouTube: discovery via the official YouTube Data API v3 (needs the user's own API key), playback only
 * through YouTube's official embedded IFrame player — never stream extraction. Only videos whose owners
 * allow embedding are returned. Embedded playback is foreground-only and cannot be mixed by the DJ.
 */
class YouTubeProvider(http: OkHttpClient, private val apiKey: () -> String) : HttpProviderBase(http), MusicProvider {
    override val source = SourceType.YOUTUBE
    override val name = "YouTube"
    override val setupHint = "YouTube Data API v3 key (free, Google Cloud Console) in Settings → Music providers"
    override fun isConfigured() = apiKey().isNotBlank()

    override fun search(query: String, limit: Int): List<Track> {
        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet").addQueryParameter("type", "video").addQueryParameter("videoCategoryId", "10")
            .addQueryParameter("videoEmbeddable", "true").addQueryParameter("maxResults", limit.coerceIn(1, 25).toString())
            .addQueryParameter("q", query).addQueryParameter("key", apiKey()).build().toString()
        val items = ((getJson(url, what = name) as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val ids = items.mapNotNull { (it["id"] as? JsonObject)?.str("videoId") }
        if (ids.isEmpty()) return emptyList()
        return details(ids)
    }

    override fun trending(category: Category?, limit: Int): List<Track> {
        if (category != null) return search(category.query + " songs", limit)
        val url = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet,contentDetails,status").addQueryParameter("chart", "mostPopular")
            .addQueryParameter("videoCategoryId", "10").addQueryParameter("regionCode", "IN")
            .addQueryParameter("maxResults", limit.coerceIn(1, 25).toString()).addQueryParameter("key", apiKey()).build().toString()
        return parseVideos(getJson(url, what = name) as? JsonObject)
    }

    override fun trackDetails(providerTrackId: String): Track? = details(listOf(providerTrackId)).firstOrNull()

    private fun details(ids: List<String>): List<Track> {
        val url = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet,contentDetails,status").addQueryParameter("id", ids.joinToString(","))
            .addQueryParameter("key", apiKey()).build().toString()
        val byId = parseVideos(getJson(url, what = name) as? JsonObject).associateBy { it.providerTrackId }
        return ids.mapNotNull { byId[it] }
    }

    private fun parseVideos(root: JsonObject?): List<Track> = (root?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { v ->
        val id = v.str("id") ?: return@mapNotNull null
        val sn = v["snippet"] as? JsonObject ?: return@mapNotNull null
        val st = v["status"] as? JsonObject
        if (st != null && (st["embeddable"] as? JsonPrimitive)?.contentOrNull == "false") return@mapNotNull null
        if ((sn.str("liveBroadcastContent") ?: "none") != "none") return@mapNotNull null
        val dur = parseIsoDuration((v["contentDetails"] as? JsonObject)?.str("duration"))
        if (dur < 30) return@mapNotNull null
        val thumbs = sn["thumbnails"] as? JsonObject
        val art = listOf("high", "medium", "default").firstNotNullOfOrNull { (thumbs?.get(it) as? JsonObject)?.str("url") }
        val rawTitle = sn.str("title")?.let(::unescape) ?: return@mapNotNull null
        val channel = sn.str("channelTitle")?.removeSuffix(" - Topic") ?: ""
        val (artist, title) = splitArtistTitle(rawTitle, channel)
        Track(
            id = "youtube:$id", title = title, artist = artist, album = "", durationMs = dur * 1000L,
            uri = "https://www.youtube.com/watch?v=$id", source = SourceType.YOUTUBE, capabilities = SourceCapabilities.YOUTUBE_EMBED,
            genres = (sn["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }?.take(6).orEmpty(),
            year = sn.str("publishedAt")?.take(4)?.toIntOrNull(), artworkUrl = art, playback = PlaybackType.EMBEDDED_PLAYER,
            providerTrackId = id,
        )
    }

    companion object {
        fun parseIsoDuration(s: String?): Int {
            val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(s ?: return 0) ?: return 0
            val (h, mi, se) = m.destructured
            return (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (se.toIntOrNull() ?: 0)
        }

        fun splitArtistTitle(raw: String, channel: String): Pair<String, String> {
            val clean = raw.replace(Regex("""\s*[\(\[](official|full|lyrical|lyric|video|audio|hd|4k)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "").trim()
            val parts = clean.split(Regex("""\s[-–|]\s"""), limit = 2)
            return if (parts.size == 2) parts[0].trim() to parts[1].trim() else channel to clean
        }

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    }
}
