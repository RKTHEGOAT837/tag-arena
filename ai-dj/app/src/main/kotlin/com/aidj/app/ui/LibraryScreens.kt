package com.aidj.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidj.app.AppGraph
import com.aidj.app.discovery.OnlineTrack
import com.aidj.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Neon.Text) }
        Text(title, color = Neon.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LibraryScreen(onBack: () -> Unit, onDiscover: () -> Unit) {
    val g = AppGraph.get()
    val tracks by g.library.tracks.collectAsState()
    val progress by g.analysis.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { val n = withContext(Dispatchers.IO) { g.library.importDocuments(uris) }; msg = "Imported $n track(s); analysing in the background" }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { val n = withContext(Dispatchers.IO) { g.library.scanMediaStore() }; msg = "Found $n new track(s) on this device" }
        else msg = "Permission denied – use Import files instead"
    }
    Column(Modifier.fillMaxSize().background(Neon.Bg)) {
        ScreenHeader("Music library", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("audio/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) { Text("Import files", color = Color.Black) }
            Button(onClick = { perm.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) },
                colors = ButtonDefaults.buttonColors(containerColor = Neon.Violet)) { Text("Scan device") }
            Button(onClick = onDiscover, colors = ButtonDefaults.buttonColors(containerColor = Neon.Pink)) { Text("Discover", color = Color.Black) }
        }
        Text("MP3, WAV, FLAC, AAC/M4A, OGG supported. DRM-protected files can't be used.", color = Neon.Dim, fontSize = 11.sp, modifier = Modifier.padding(14.dp, 6.dp))
        if (progress.trackId != null) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                Text("Analysing “${progress.title}” · ${progress.remaining} left", color = Neon.Dim, fontSize = 11.sp)
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Neon.Cyan)
            }
        }
        msg?.let { Text(it, color = Neon.Lime, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search title, artist, genre") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(14.dp, 6.dp))
        val q = query.lowercase()
        val shown = tracks.filter { q.isBlank() || (it.track.title + it.track.artist + it.track.genres.joinToString()).lowercase().contains(q) }
        Text("${tracks.size} tracks · ${tracks.count { it.analysis != null }} analysed", color = Neon.Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.track.id }) { lt ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(lt.track, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(lt.track.title, color = Neon.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Text(lt.track.artist.ifBlank { "Unknown" } + (lt.track.genres.firstOrNull()?.let { " · $it" } ?: ""), color = Neon.Dim, maxLines = 1, fontSize = 11.sp)
                        val a = lt.analysis
                        Text(when {
                            a != null -> "${"%.1f".format(a.bpm)} BPM · ${a.key.camelot} · energy ${"%.2f".format(a.energy)} · ${a.mood}"
                            lt.analysisError != null -> "Analysis failed: ${lt.analysisError}"
                            else -> "Waiting for analysis…"
                        }, color = if (lt.analysisError != null) Neon.Red else Neon.Cyan, fontSize = 11.sp, maxLines = 1)
                        Text(lt.track.capabilities.note, color = Neon.Dim, fontSize = 10.sp, maxLines = 1)
                    }
                    IconButton(onClick = { g.library.setLiked(lt.track.id, !lt.liked) }) {
                        Icon(if (lt.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like", tint = if (lt.liked) Neon.Pink else Neon.Dim)
                    }
                    IconButton(onClick = { g.brain.enqueue(lt.track.id); msg = "Queued ${lt.track.title}" }, enabled = lt.analysis != null) {
                        Icon(Icons.Default.PlaylistAdd, "Queue", tint = if (lt.analysis != null) Neon.Cyan else Neon.Dim)
                    }
                    IconButton(onClick = { g.library.delete(lt.track.id) }) { Icon(Icons.Default.Delete, "Remove", tint = Neon.Dim) }
                }
            }
        }
    }
}

@Composable
fun DiscoverScreen(onBack: () -> Unit) {
    val g = AppGraph.get()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("house") }
    var source by remember { mutableStateOf(SourceType.JAMENDO) }
    var results by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloads = remember { mutableStateMapOf<String, Float>() }
    fun search() {
        loading = true; error = null
        scope.launch {
            runCatching { g.discovery.search(query, source, 25) }.onSuccess { results = it }.onFailure { error = it.message }
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().background(Neon.Bg)) {
        ScreenHeader("Discover music", onBack)
        Text("Creative Commons catalogues with per-track licenses. Tracks you download are stored on the phone and mixed according to their license.",
            color = Neon.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
        Row(Modifier.padding(14.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonChip("Jamendo", if (source == SourceType.JAMENDO) Neon.Cyan else Neon.Dim) { source = SourceType.JAMENDO }
            NeonChip("Internet Archive", if (source == SourceType.INTERNET_ARCHIVE) Neon.Cyan else Neon.Dim) { source = SourceType.INTERNET_ARCHIVE }
        }
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Genre, mood, artist…") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { search() }, colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) { Text("Search", color = Color.Black) }
        }
        if (loading) CircularProgressIndicator(Modifier.padding(16.dp), color = Neon.Cyan)
        error?.let { Text(it, color = Neon.Red, fontSize = 12.sp, modifier = Modifier.padding(14.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.source.name + it.sourceId }) { t ->
                val inLib = g.library.get(t.libraryId) != null
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Neon.Panel).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = Neon.Text, maxLines = 1, fontSize = 14.sp)
                            Text(t.artist + (t.genres.take(3).joinToString(prefix = " · ").takeIf { t.genres.isNotEmpty() } ?: ""), color = Neon.Dim, fontSize = 11.sp, maxLines = 1)
                        }
                        val p = downloads[t.libraryId]
                        when {
                            inLib -> NeonChip("In library", Neon.Lime)
                            p != null -> CircularProgressIndicator(progress = { p }, modifier = Modifier.size(28.dp), color = Neon.Cyan)
                            t.downloadAllowed -> IconButton(onClick = {
                                downloads[t.libraryId] = 0f
                                scope.launch {
                                    runCatching { g.discovery.download(t) { f -> downloads[t.libraryId] = f } }
                                        .onFailure { error = it.message }
                                    downloads.remove(t.libraryId)
                                }
                            }) { Icon(Icons.Default.Download, "Download", tint = Neon.Cyan) }
                            else -> NeonChip("No download", Neon.Dim)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NeonChip(t.licenseName, Neon.Violet)
                        NeonChip(if (t.capabilities.playback) "Playback ✓" else "No playback", if (t.capabilities.playback) Neon.Lime else Neon.Red)
                        NeonChip(if (t.capabilities.processing) "Mixing ✓" else "Unmodified only", if (t.capabilities.processing) Neon.Lime else Neon.Amber)
                        NeonChip(if (t.capabilities.remix) "Remix ✓" else "No remix", if (t.capabilities.remix) Neon.Lime else Neon.Amber)
                    }
                }
            }
            item {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle("Why not Spotify / YouTube Music / SoundCloud?")
                    Text("Their terms and SDKs don't permit decoding, time-stretching, mixing or remixing their streams in a third-party audio engine, and streams are DRM-protected. AI DJ never bypasses DRM, so those services can't be used as mixable sources.",
                        color = Neon.Dim, fontSize = 12.sp)
                }
            }
        }
    }
}
