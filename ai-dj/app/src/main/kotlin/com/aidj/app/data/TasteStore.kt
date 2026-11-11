package com.aidj.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import com.aidj.core.model.LibraryTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.ln
import kotlin.math.max

/**
 * The user's listening taste, built from their own data and kept entirely on the device:
 *  - Spotify Web API (top tracks/artists, recently played) after they connect their account;
 *  - Spotify account-data exports (StreamingHistory*.json / endsong*.json);
 *  - Google Takeout YouTube / YouTube Music watch-history.json.
 *
 * Aggregated into per-(artist,title) weights; [scoresFor] turns that into a 0..1 affinity per library
 * track (exact track matches score highest, artist matches follow), which the selector adds to its
 * ranking and the cloud AI sees as a "top artists" summary. Nothing here is ever uploaded.
 */
class TasteStore(context: Context) {

    private class Db(context: Context) : SQLiteOpenHelper(context, "aidj_taste.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE taste (k TEXT PRIMARY KEY, artist TEXT NOT NULL, title TEXT NOT NULL, weight REAL NOT NULL, source TEXT NOT NULL)")
            db.execSQL("CREATE INDEX taste_artist ON taste(artist)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val db = Db(context).writableDatabase
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    // In-memory aggregates, rebuilt lazily after changes.
    private var artistWeights: Map<String, Float> = emptyMap()
    private var trackWeights: Map<String, Float> = emptyMap()
    private var maxArtist = 1f
    private var maxTrack = 1f
    @Volatile private var loaded = false
    private var scoreCacheKey: Pair<Int, Int>? = null
    private var scoreCache: Map<String, Float> = emptyMap()

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val artists = HashMap<String, Float>()
        val tracks = HashMap<String, Float>()
        db.rawQuery("SELECT artist, title, weight FROM taste", null).use { c ->
            while (c.moveToNext()) {
                val a = c.getString(0); val t = c.getString(1); val w = c.getFloat(2)
                if (a.isNotBlank()) artists[a] = (artists[a] ?: 0f) + w
                if (t.isNotBlank()) tracks["$a|$t"] = (tracks["$a|$t"] ?: 0f) + w
            }
        }
        artistWeights = artists
        trackWeights = tracks
        // Log scale: someone's #1 artist with 2000 plays shouldn't make #10 (50 plays) invisible.
        maxArtist = max(1f, artists.values.maxOfOrNull { ln(1f + it) } ?: 1f)
        maxTrack = max(1f, tracks.values.maxOfOrNull { ln(1f + it) } ?: 1f)
        loaded = true
    }

    @Synchronized
    private fun invalidate() { loaded = false; scoreCacheKey = null; _version.value = _version.value + 1 }

    fun entryCount(): Int = db.rawQuery("SELECT COUNT(*) FROM taste", null).use { it.moveToFirst(); it.getInt(0) }

    fun topArtists(n: Int): List<Pair<String, Float>> {
        ensureLoaded()
        return artistWeights.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    @Synchronized
    fun clear() { db.delete("taste", null, null); invalidate() }

    @Synchronized
    fun add(artist: String, title: String, weight: Float, source: String) {
        val a = normalize(artist); val t = normalize(title)
        if (a.isBlank() && t.isBlank()) return
        val k = "$source|$a|$t"
        db.execSQL(
            "INSERT INTO taste(k, artist, title, weight, source) VALUES(?,?,?,?,?) ON CONFLICT(k) DO UPDATE SET weight = weight + excluded.weight",
            arrayOf<Any>(k, a, t, weight, source),
        )
    }

    fun addBatch(entries: List<Triple<String, String, Float>>, source: String): Int {
        db.beginTransaction()
        try {
            for ((artist, title, w) in entries) add(artist, title, w, source)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        invalidate()
        return entries.size
    }

    @Synchronized
    fun replaceSource(source: String) {
        db.delete("taste", "source = ?", arrayOf(source))
        invalidate()
    }

    /** 0..1 affinity per library track id. Cached per (taste version, library size). */
    fun scoresFor(tracks: List<LibraryTrack>): Map<String, Float> {
        synchronized(this) {
            val key = _version.value to tracks.size
            if (key == scoreCacheKey) return scoreCache
        }
        ensureLoaded()
        if (artistWeights.isEmpty() && trackWeights.isEmpty()) return emptyMap()
        val out = HashMap<String, Float>()
        for (lt in tracks) {
            val a = normalize(lt.track.artist)
            val t = normalize(lt.track.title)
            var s = 0f
            trackWeights["$a|$t"]?.let { s = max(s, 0.6f + 0.4f * (ln(1f + it) / maxTrack)) }
            // Title-only match (history entry had no artist, or artist strings differ).
            if (s == 0f && t.isNotBlank()) trackWeights["|$t"]?.let { s = max(s, 0.5f + 0.3f * (ln(1f + it) / maxTrack)) }
            if (a.isNotBlank()) artistWeights[a]?.let { s = max(s, 0.35f + 0.45f * (ln(1f + it) / maxArtist)) }
            if (s > 0f) out[lt.track.id] = s.coerceIn(0f, 1f)
        }
        synchronized(this) { scoreCacheKey = _version.value to tracks.size; scoreCache = out }
        return out
    }

    // ------------------------------------------------------------------ file imports

    class ImportResult(val entries: Int, val kind: String)

    /**
     * Imports a listening-history JSON file, auto-detecting the format per record:
     *  - Spotify account data: StreamingHistory*.json (artistName/trackName/msPlayed)
     *  - Spotify extended history: endsong*.json (master_metadata_* / ms_played)
     *  - Google Takeout: YouTube & YouTube Music watch-history.json (header/title/subtitles)
     */
    fun importJson(input: InputStream): ImportResult {
        val entries = ArrayList<Triple<String, String, Float>>()
        var youTube = 0; var spotify = 0
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) throw IllegalArgumentException("Not a history file (expected a JSON array)")
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var artist = ""; var track = ""; var header = ""; var title = ""; var channel = ""
                var msPlayed = -1L
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "artistName", "master_metadata_album_artist_name" -> artist = r.nextStringOrEmpty()
                        "trackName", "master_metadata_track_name" -> track = r.nextStringOrEmpty()
                        "msPlayed", "ms_played" -> msPlayed = r.nextLongOrZero()
                        "header" -> header = r.nextStringOrEmpty()
                        "title" -> title = r.nextStringOrEmpty()
                        "subtitles" -> channel = readFirstSubtitleName(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                when {
                    track.isNotBlank() || artist.isNotBlank() -> {
                        // Spotify: skip sub-30 s skips when play time is known.
                        if (msPlayed in 0..29_999) continue
                        entries += Triple(artist, track, 1f)
                        spotify++
                    }
                    title.startsWith("Watched ") -> {
                        val video = title.removePrefix("Watched ").trim()
                        if (video.isBlank() || video.startsWith("https://")) continue
                        val ch = channel.removeSuffix(" - Topic").trim()
                        val isMusic = header.contains("Music", ignoreCase = true) || channel.endsWith(" - Topic")
                        val weight = if (isMusic) 1f else 0.4f
                        // Music videos are usually titled "Artist - Title"; keep both splits and the raw title.
                        val dash = video.split(Regex("""\s[-–|]\s"""), limit = 2)
                        if (dash.size == 2) entries += Triple(dash[0], dash[1], weight)
                        entries += Triple(ch, video, weight * 0.8f)
                        youTube++
                    }
                }
            }
            r.endArray()
        }
        if (entries.isEmpty()) throw IllegalArgumentException("No listening entries found in this file")
        val kind = when {
            youTube > 0 && spotify > 0 -> "YouTube + Spotify history"
            youTube > 0 -> "YouTube history"
            else -> "Spotify history"
        }
        addBatch(entries, if (youTube >= spotify) "youtube" else "spotify_file")
        return ImportResult(entries.size, kind)
    }

    private fun readFirstSubtitleName(r: JsonReader): String {
        var name = ""
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return name }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            r.beginObject()
            while (r.hasNext()) { if (r.nextName() == "name" && name.isBlank()) name = r.nextStringOrEmpty() else r.skipValue() }
            r.endObject()
        }
        r.endArray()
        return name
    }

    private fun JsonReader.nextStringOrEmpty(): String = if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()
    private fun JsonReader.nextLongOrZero(): Long = if (peek() == JsonToken.NULL) { nextNull(); 0 } else nextLong()

    companion object {
        private val FEAT = Regex("""[(\[](feat|ft|with|prod)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val NOISE = Regex("""\b(official video|official music video|official audio|full video|lyrical|lyrics|lyric video|video song|audio|4k|hd|remastered)\b""", RegexOption.IGNORE_CASE)
        private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

        /** Lower-cased, feat./noise-stripped, punctuation collapsed — same form for library tags and history. */
        fun normalize(s: String): String = NON_ALNUM.replace(NOISE.replace(FEAT.replace(s.lowercase(), " "), " "), " ").trim()
    }
}
