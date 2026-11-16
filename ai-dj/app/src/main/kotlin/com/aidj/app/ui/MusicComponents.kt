package com.aidj.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidj.app.AppGraph
import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Artwork: in-memory LRU + 60 MB OkHttp disk cache, embedded art for local files. */
object ArtworkCache {
    private val mem = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Volatile private var client: OkHttpClient? = null
    private fun http(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder().cache(Cache(File(AppGraph.appContext.cacheDir, "artwork"), 60L * 1024 * 1024)).build().also { client = it }
    }

    fun cached(key: String): ImageBitmap? = mem.get(key)

    suspend fun load(track: Track, sizePx: Int = 320): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = track.id + "@" + sizePx
        mem.get(key)?.let { return@withContext it }
        val bytes = runCatching {
            if (track.source == SourceType.LOCAL_FILE && track.artworkUrl == null) AppGraph.get().library.artwork(track)
            else track.artworkUrl?.let { url -> http().newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }
        }.getOrNull() ?: return@withContext null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= sizePx && opts.outHeight / (sample * 2) >= sizePx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        bmp?.also { mem.put(key, it) }
    }
}

@Composable
fun Cover(track: Track?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    var bmp by remember(track?.id) { mutableStateOf(track?.let { ArtworkCache.cached(it.id + "@320") }) }
    LaunchedEffect(track?.id) { if (track != null && bmp == null) bmp = ArtworkCache.load(track) }
    Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(Neon.Violet, Neon.Pink))), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.8f))
    }
}

fun sourceLabel(t: Track): String = when (t.source) {
    SourceType.AUDIUS -> "Audius"; SourceType.YOUTUBE -> "YouTube"; SourceType.JAMENDO -> "Jamendo"
    SourceType.INTERNET_ARCHIVE -> "Archive"; SourceType.LOCAL_FILE -> if (t.artist == com.aidj.app.brain.DEMO_ARTIST) "Demo" else "On phone"
}

fun sourceColor(t: Track): Color = when (t.source) {
    SourceType.AUDIUS -> Neon.Violet; SourceType.YOUTUBE -> Neon.Red; SourceType.JAMENDO -> Neon.Lime
    SourceType.INTERNET_ARCHIVE -> Neon.Amber; SourceType.LOCAL_FILE -> Neon.Cyan
}

fun fmtMs(ms: Long): String = fmtTime(ms / 1000.0)

@Composable
fun SmallBadge(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** A track row with Play + Queue buttons and a menu (play next, add to playlist, favourite). */
@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    error: String? = null,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val g = AppGraph.get()
    val player = g.player
    var menu by remember { mutableStateOf(false) }
    var pickPlaylist by remember { mutableStateOf(false) }
    val liked = g.library.tracks.collectAsState().value.firstOrNull { it.track.id == track.id }?.liked == true
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (highlighted) Neon.Panel2 else Color.Transparent)
            .clickable { onClick?.invoke() ?: player.playNow(track) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track, 48.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(track.title, color = if (highlighted) Neon.Cyan else Neon.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SmallBadge(sourceLabel(track), sourceColor(track))
                Text((subtitle ?: track.artist.ifBlank { "Unknown artist" }) + if (track.durationMs > 0) " · ${fmtMs(track.durationMs)}" else "",
                    color = Neon.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (error != null) Text("⚠ $error", color = Neon.Red, fontSize = 10.sp, maxLines = 2)
        }
        if (trailing != null) trailing() else {
            IconButton(onClick = { player.playNow(track) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.PlayArrow, "Play", tint = Neon.Cyan) }
            IconButton(onClick = { player.addToQueue(track) }, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", tint = Neon.Text) }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "More", tint = Neon.Dim) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.Default.SkipNext, null) }, onClick = { menu = false; player.addNext(track) })
                DropdownMenuItem(text = { Text("Add to playlist…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }, onClick = { menu = false; pickPlaylist = true })
                DropdownMenuItem(text = { Text(if (liked) "Remove from favourites" else "Add to favourites") },
                    leadingIcon = { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                    onClick = { menu = false; g.library.remember(track); g.library.setLiked(track.id, !liked) })
                Text(track.capabilities.note, color = Neon.Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).width(260.dp))
            }
        }
    }
    if (pickPlaylist) PlaylistPicker(track) { pickPlaylist = false }
}

@Composable
fun PlaylistPicker(track: Track, onDone: () -> Unit) {
    val g = AppGraph.get()
    val lists by g.library.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to playlist") },
        text = {
            Column {
                for (p in lists) Text(p.name + "  (${p.trackIds.size})", color = Neon.Text, modifier = Modifier.fillMaxWidth().clickable {
                    g.library.addToPlaylist(p.id, track); g.player.let { }; onDone()
                }.padding(vertical = 10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newName, { newName = it }, label = { Text("New playlist name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (newName.isNotBlank()) { val id = g.library.createPlaylist(newName); g.library.addToPlaylist(id, track) }
            onDone()
        }) { Text(if (newName.isNotBlank()) "Create & add" else "Close") } },
    )
}

@Composable
fun ShelfTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Neon.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = Neon.Cyan, fontSize = 12.sp) }
    }
}

/** Square card for horizontal shelves. */
@Composable
fun TrackCard(track: Track, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(6.dp)) {
        Box {
            Cover(track, 128.dp, corner = 10.dp)
            Box(Modifier.align(Alignment.TopStart).padding(5.dp)) { SmallBadge(sourceLabel(track), sourceColor(track)) }
        }
        Text(track.title, color = Neon.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(track.artist, color = Neon.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
