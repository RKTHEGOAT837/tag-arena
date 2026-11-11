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
        // Remove copies we own (downloads, shared-in files in app storage); never delete the user's original files.
        if (t != null) runCatching {
            val u = Uri.parse(t.uri)
            if (u.scheme == "file" && u.path?.startsWith(context.filesDir.path) == true) java.io.File(u.path!!).delete()
        }
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

    /**
     * Import audio shared from another app. Those URIs carry a temporary read grant only, so the file is
     * copied into app-private storage first (it is the user's own file, so it keeps LOCAL capabilities).
     */
    fun importShared(uris: List<Uri>): Int {
        val dir = java.io.File(context.filesDir, "music").apply { mkdirs() }
        var n = 0
        for (uri in uris) {
            val id = idFor(uri.toString())
            val name = uri.lastPathSegment?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "shared"
            val file = java.io.File(dir, "shared_${id}_${name.takeLast(60)}")
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!ok) continue
            val t = readMetadata(Uri.fromFile(file), id) ?: run { file.delete(); null } ?: continue
            upsert(t); n++
        }
        return n
    }

    /**
     * Generate royalty-free demo tracks on the device (synthesised house grooves in different keys/tempos),
     * so the party can start before the user has imported any music.
     */
    fun addDemoTracks(): Int {
        val dir = java.io.File(context.filesDir, "music").apply { mkdirs() }
        data class Spec(val title: String, val bpm: Double, val pc: Int, val minor: Boolean, val seed: Int)
        val specs = listOf(
            Spec("Neon Arrival", 122.0, 9, true, 11), Spec("Midnight Circuit", 124.0, 4, true, 12),
            Spec("Golden Hour", 125.0, 0, false, 13), Spec("Pulse Drive", 126.0, 2, true, 14),
        )
        var n = 0
        for (sp in specs) {
            val id = idFor("demo:${sp.title}")
            if (get(id) != null) continue
            val file = java.io.File(dir, "demo_${sp.seed}.wav")
            if (!file.exists()) {
                val pcm = com.aidj.core.demo.DemoMusic.render(sp.bpm, sr = 44100, offsetSec = 0.1, rootPc = sp.pc, minor = sp.minor, seed = sp.seed)
                writeWav(file, pcm, 44100)
            }
            upsert(Track(
                id = id, title = sp.title, artist = "AI DJ Demo", album = "Demo set", durationMs = (java.io.File(file.path).length() - 44) / 2 * 1000 / 44100,
                uri = Uri.fromFile(file).toString(), source = SourceType.LOCAL_FILE,
                capabilities = SourceCapabilities.LOCAL.copy(note = "Demo track generated on this phone (royalty-free)"),
                genres = listOf("house", "electronic", "dance"),
            ))
            n++
        }
        return n
    }

    private fun writeWav(f: java.io.File, x: FloatArray, sr: Int) {
        val bb = java.nio.ByteBuffer.allocate(44 + x.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(x.size * 2)
        var peak = 1e-6f
        for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        val g = 0.89f / peak
        for (v in x) bb.putShort((v * g * 32767f).toInt().coerceIn(-32767, 32767).toShort())
        f.writeBytes(bb.array())
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
