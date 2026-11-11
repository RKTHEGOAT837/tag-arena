package com.aidj.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.aidj.core.model.LibraryTrack
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import com.aidj.core.model.TrackAnalysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private class DbHelper(context: Context) : SQLiteOpenHelper(context, "aidj.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY, track TEXT NOT NULL, analysis TEXT, analysis_error TEXT,
                play_count INTEGER NOT NULL DEFAULT 0, skip_count INTEGER NOT NULL DEFAULT 0,
                liked INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE TABLE history (ts INTEGER NOT NULL, track_id TEXT NOT NULL, session TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * Local music library: tracks, cached analysis (JSON), and listening feedback in SQLite.
 * Exposes an in-memory StateFlow snapshot for the UI and the DJ brain.
 */
class LibraryRepository(private val context: Context) {
    private val db = DbHelper(context).writableDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()

    init { reload() }

    @Synchronized
    fun reload() {
        val out = ArrayList<LibraryTrack>()
        db.rawQuery("SELECT track, analysis, analysis_error, play_count, skip_count, liked, last_played FROM tracks ORDER BY added_at DESC", null).use { c ->
            while (c.moveToNext()) {
                val t = runCatching { json.decodeFromString(Track.serializer(), c.getString(0)) }.getOrNull() ?: continue
                val a = c.getString(1)?.let { s -> runCatching { json.decodeFromString(TrackAnalysis.serializer(), s) }.getOrNull() }
                    ?.takeIf { it.version == TrackAnalysis.VERSION }
                out += LibraryTrack(t, a, c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5) != 0, c.getLong(6))
            }
        }
        _tracks.value = out
    }

    fun get(id: String): LibraryTrack? = _tracks.value.firstOrNull { it.track.id == id }

    @Synchronized
    fun upsert(track: Track) {
        val cv = ContentValues().apply {
            put("id", track.id); put("track", json.encodeToString(Track.serializer(), track)); put("added_at", System.currentTimeMillis())
        }
        val updated = db.update("tracks", ContentValues().apply { put("track", cv.getAsString("track")) }, "id=?", arrayOf(track.id))
        if (updated == 0) db.insert("tracks", null, cv)
        reload()
    }

    @Synchronized
    fun saveAnalysis(id: String, analysis: TrackAnalysis?, error: String?) {
        db.update("tracks", ContentValues().apply {
            put("analysis", analysis?.let { json.encodeToString(TrackAnalysis.serializer(), it) })
            put("analysis_error", error)
        }, "id=?", arrayOf(id))
        reload()
    }

    @Synchronized
    fun recordPlay(id: String, session: String) {
        db.execSQL("UPDATE tracks SET play_count = play_count + 1, last_played = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
        db.insert("history", null, ContentValues().apply { put("ts", System.currentTimeMillis()); put("track_id", id); put("session", session) })
        reload()
    }

    @Synchronized
    fun recordSkip(id: String) { db.execSQL("UPDATE tracks SET skip_count = skip_count + 1 WHERE id = ?", arrayOf(id)); reload() }

    @Synchronized
    fun setLiked(id: String, liked: Boolean) { db.execSQL("UPDATE tracks SET liked = ? WHERE id = ?", arrayOf<Any>(if (liked) 1 else 0, id)); reload() }

    @Synchronized
    fun delete(id: String) {
        val t = get(id)?.track
        db.delete("tracks", "id=?", arrayOf(id))
        // Remove downloaded copies we own; never delete the user's own files.
        if (t != null && t.source != SourceType.LOCAL_FILE) runCatching { Uri.parse(t.uri).path?.let { java.io.File(it).delete() } }
        reload()
    }

    /** Import user-picked documents (SAF). Keeps read permission across reboots. */
    fun importDocuments(uris: List<Uri>): Int {
        var n = 0
        for (uri in uris) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val t = readMetadata(uri, idFor(uri.toString())) ?: continue
            upsert(t); n++
        }
        return n
    }

    /** Index the device's music library (MediaStore). Requires READ_MEDIA_AUDIO. */
    fun scanMediaStore(): Int {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.YEAR,
        )
        var n = 0
        val existing = _tracks.value.map { it.track.id }.toHashSet()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 60000", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val id = idFor(uri.toString())
                if (id in existing) continue
                val genre = genreOf(uri)
                val t = Track(
                    id = id, title = c.getString(1) ?: "Unknown", artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: "",
                    album = c.getString(3) ?: "", durationMs = c.getLong(4), uri = uri.toString(), source = SourceType.LOCAL_FILE,
                    capabilities = SourceCapabilities.LOCAL, genres = genre, year = c.getInt(5).takeIf { it > 0 },
                )
                db.insert("tracks", null, ContentValues().apply {
                    put("id", t.id); put("track", json.encodeToString(Track.serializer(), t)); put("added_at", System.currentTimeMillis())
                })
                n++
            }
        }
        reload()
        return n
    }

    private fun genreOf(uri: Uri): List<String> = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            splitGenres(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
        }
    }.getOrDefault(emptyList())

    fun readMetadata(uri: Uri, id: String, source: SourceType = SourceType.LOCAL_FILE, caps: SourceCapabilities = SourceCapabilities.LOCAL): Track? =
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration < 20_000) return null
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track"
                Track(
                    id = id,
                    title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name,
                    artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                    album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                    durationMs = duration, uri = uri.toString(), source = source, capabilities = caps,
                    genres = splitGenres(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                    year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                )
            }
        }.getOrNull()

    /** Embedded album art (bytes), if any. */
    fun artwork(track: Track): ByteArray? = runCatching {
        MediaMetadataRetriever().use { r -> r.setDataSource(context, Uri.parse(track.uri)); r.embeddedPicture }
    }.getOrNull()

    fun historyIds(session: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT track_id FROM history WHERE session = ? ORDER BY ts", arrayOf(session)).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    companion object {
        fun idFor(key: String): String = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
        fun splitGenres(raw: String?): List<String> = raw.orEmpty().split(',', ';', '/', '|').map { it.trim().lowercase() }.filter { it.isNotBlank() && it.length < 40 }.distinct()
    }
}
