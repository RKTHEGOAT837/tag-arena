package com.aidj.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidj.app.AppGraph
import com.aidj.core.model.SourceType
import com.aidj.core.model.Track
import com.aidj.core.providers.CatalogResult
import com.aidj.core.providers.Categories
import com.aidj.core.providers.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short-lived cache of catalogue shelves so tab switches don't refetch (and survive brief network drops). */
object ShelfCache {
    private data class Entry(val at: Long, val result: CatalogResult)
    private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    fun get(key: String, maxAgeMs: Long = 15 * 60_000): CatalogResult? = map[key]?.takeIf { System.currentTimeMillis() - it.at < maxAgeMs }?.result
    fun put(key: String, r: CatalogResult) { if (r.tracks.isNotEmpty()) map[key] = Entry(System.currentTimeMillis(), r) }
    fun stale(key: String): CatalogResult? = map[key]?.result
}

@Composable
private fun rememberShelf(key: String, load: () -> CatalogResult): Pair<CatalogResult?, Boolean> {
    var res by remember(key) { mutableStateOf(ShelfCache.get(key)) }
    var loading by remember(key) { mutableStateOf(res == null) }
    LaunchedEffect(key) {
        if (res != null) return@LaunchedEffect
        loading = true
        val r = withContext(Dispatchers.IO) { runCatching(load).getOrElse { CatalogResult(emptyList(), mapOf("Network" to (it.message ?: "failed")), emptyList()) } }
        ShelfCache.put(key, r)
        res = if (r.tracks.isEmpty()) ShelfCache.stale(key) ?: r else r
        loading = false
    }
    return res to loading
}

@Composable
private fun Shelf(title: String, tracks: List<Track>, loading: Boolean, error: String? = null, onPlayAll: (() -> Unit)? = null) {
    val hub = AppGraph.get().player
    ShelfTitle(title, if (tracks.isNotEmpty()) "Play all" else null, onPlayAll ?: { hub.playAll(tracks) })
    when {
        loading && tracks.isEmpty() -> Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Neon.Cyan) }
        tracks.isEmpty() -> Text(error ?: "Nothing here yet", color = Neon.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        else -> LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackCard(t) { hub.playAll(tracks, i) } }
        }
    }
}

@Composable
fun HomeScreen(onSearch: (String) -> Unit, onOpenDj: () -> Unit) {
    val g = AppGraph.get()
    val lib by g.library.tracks.collectAsState()
    val recent = remember(lib.size) { g.library.recentlyPlayed(20).map { it.track } }
    val tasteVersion by g.taste.version.collectAsState()
    val topArtists = remember(tasteVersion) { g.taste.topArtists(3).map { it.first } }
    LazyColumn(Modifier.fillMaxSize().background(Neon.Bg)) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Good vibes only", color = Neon.Text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("Search any song, queue it, or let the AI DJ take over.", color = Neon.Dim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Neon.Panel2).clickable { onSearch("") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = Neon.Dim); Text("  Songs, artists, genres…", color = Neon.Dim)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink))).clickable { onOpenDj() }.padding(14.dp)) {
                    Column {
                        Text("AI DJ", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("Beatmatched mixing of your queue + AI picks", color = Color.Black.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
            }
        }
        if (recent.isNotEmpty()) item {
            ShelfTitle("Continue listening", "Play", { g.player.playAll(recent) })
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) { itemsIndexed(recent, key = { _, t -> t.id }) { i, t -> TrackCard(t) { g.player.playAll(recent, i) } } }
        }
        if (topArtists.isNotEmpty()) item {
            val (r, loading) = rememberShelf("rec:" + topArtists.joinToString()) {
                val merged = topArtists.flatMap { a -> g.catalog.search(a, 6, setOf(SourceType.AUDIUS, SourceType.YOUTUBE)).tracks }
                CatalogResult(merged.distinctBy { it.id }, emptyMap(), emptyList())
            }
            Shelf("Recommended for you", r?.tracks.orEmpty(), loading)
        }
        item {
            val (r, loading) = rememberShelf("trending") { g.catalog.trending(null, 20) }
            Shelf("Trending now", r?.tracks.orEmpty(), loading, r?.errors?.values?.firstOrNull())
        }
        for (cat in listOf("punjabi", "bollywood", "hindi", "gujarati", "phonk", "english_pop", "edm")) item(key = cat) {
            val c = Categories.byId(cat)!!
            val (r, loading) = rememberShelf("cat:$cat") { g.catalog.trending(c, 15) }
            Shelf(c.label, r?.tracks.orEmpty(), loading, r?.errors?.values?.firstOrNull())
        }
        val added = lib.filter { it.track.source == SourceType.LOCAL_FILE && it.track.artist != com.aidj.app.brain.DEMO_ARTIST }.take(15).map { it.track }
        if (added.isNotEmpty()) item { Shelf("Recently added on this phone", added, false) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(initial: String = "") {
    val g = AppGraph.get()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf(initial) }
    var result by remember { mutableStateOf<CatalogResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<SourceType?>(null) }
    var recents by remember { mutableStateOf(g.library.recentSearches()) }
    val local = remember(submitted) {
        val q = submitted.lowercase().split(' ').filter { it.isNotBlank() }
        if (q.isEmpty()) emptyList() else g.library.tracks.value.filter { lt ->
            lt.track.source == SourceType.LOCAL_FILE && q.all { (lt.track.title + " " + lt.track.artist + " " + lt.track.genres.joinToString()).lowercase().contains(it) }
        }.map { it.track }.take(10)
    }
    // Debounced live search while typing.
    LaunchedEffect(query) {
        if (query.trim().length < 2) return@LaunchedEffect
        delay(500)
        submitted = query.trim()
    }
    LaunchedEffect(submitted) {
        if (submitted.length < 2) { result = null; return@LaunchedEffect }
        loading = true
        result = withContext(Dispatchers.IO) { g.catalog.search(submitted, 20) }
        loading = false
        g.library.addSearch(submitted); recents = g.library.recentSearches()
    }
    Column(Modifier.fillMaxSize().background(Neon.Bg)) {
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Songs, artists, albums, genres, languages…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = ""; result = null }) { Icon(Icons.Default.Clear, "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(28.dp))
        if (submitted.length < 2) {
            LazyColumn {
                if (recents.isNotEmpty()) {
                    item { ShelfTitle("Recent searches", "Clear") { g.library.clearSearches(); recents = emptyList() } }
                    items(recents) { r ->
                        Row(Modifier.fillMaxWidth().clickable { query = r; submitted = r }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null, tint = Neon.Dim, modifier = Modifier.size(18.dp)); Text("  $r", color = Neon.Text)
                        }
                    }
                }
                item { ShelfTitle("Browse") }
                item {
                    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in Categories.ALL) CategoryTile(c) { query = c.query; submitted = c.query }
                    }
                }
                item { ShelfTitle("Popular searches") }
                item {
                    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in listOf("sidhu moose wala", "arijit singh", "ap dhillon", "diljit dosanjh", "bollywood mashup", "garba", "phonk", "lofi", "karan aujla", "bhangra remix")) {
                            NeonChip(p, Neon.Cyan) { query = p; submitted = p }
                        }
                    }
                }
                item { ProvidersNote() }
            }
            return@Column
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonChip("All", if (filter == null) Neon.Cyan else Neon.Dim) { filter = null }
            for (s in listOf(SourceType.AUDIUS, SourceType.YOUTUBE, SourceType.JAMENDO, SourceType.INTERNET_ARCHIVE)) {
                val n = result?.tracks?.count { it.source == s } ?: 0
                if (n > 0) NeonChip("${s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} $n", if (filter == s) Neon.Cyan else Neon.Dim) { filter = s }
            }
        }
        val shown = result?.tracks.orEmpty().filter { filter == null || it.source == filter }
        LazyColumn(Modifier.weight(1f)) {
            if (loading) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Neon.Cyan) } }
            result?.errors?.forEach { (p, e) -> item { Text("$p: $e", color = Neon.Amber, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) } }
            if (local.isNotEmpty()) {
                item { ShelfTitle("On this phone") }
                items(local, key = { "l" + it.id }) { TrackRow(it) }
            }
            if (shown.isNotEmpty()) item { ShelfTitle("Songs", "Play all") { g.player.playAll(shown) } }
            items(shown, key = { it.id }) { TrackRow(it) }
            if (!loading && result != null && shown.isEmpty() && local.isEmpty()) item {
                Text("No playable results for “$submitted”. Try another spelling or an artist name.", color = Neon.Dim, modifier = Modifier.padding(16.dp))
            }
            item { ProvidersNote() }
        }
    }
    @Suppress("UNUSED_EXPRESSION") scope
}

@Composable
private fun CategoryTile(c: Category, onClick: () -> Unit) {
    val colors = listOf(Neon.Pink, Neon.Violet, Neon.Cyan, Neon.Amber, Neon.Lime, Neon.Red)
    val col = colors[(c.id.hashCode() and 0x7fffffff) % colors.size]
    Box(Modifier.size(width = 104.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.35f)))).clickable { onClick() }.padding(10.dp)) {
        Text(c.label, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

@Composable
private fun ProvidersNote() {
    val g = AppGraph.get()
    val missing = g.catalog.providers.filter { !it.isConfigured() }
    Column(Modifier.padding(16.dp)) {
        Text("Searching: " + g.catalog.configured().joinToString { it.name }, color = Neon.Dim, fontSize = 11.sp)
        for (p in missing) Text("${p.name} is off – needs ${p.setupHint}", color = Neon.Dim, fontSize = 11.sp)
        Text("Every result here really plays. Spotify and SoundCloud aren't searchable: Spotify doesn't let other apps play its audio, and SoundCloud isn't accepting new API apps.", color = Neon.Dim, fontSize = 11.sp)
    }
}

@Composable
fun QueueScreen() {
    val g = AppGraph.get()
    val hub = g.player
    val queue by hub.queue.collectAsState()
    val index by hub.index.collectAsState()
    var save by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Neon.Bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", color = Neon.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { save = true }, enabled = queue.isNotEmpty()) { Text("Save as playlist", color = Neon.Cyan) }
            TextButton(onClick = { hub.clearUpcoming() }, enabled = queue.size > 1) { Text("Clear", color = Neon.Red) }
        }
        if (queue.isEmpty()) {
            Text("Your queue is empty. Search for songs and tap the queue button, or start the AI DJ and it will pick songs for you.", color = Neon.Dim, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn {
            itemsIndexed(queue, key = { _, q -> q.uid }) { i, q ->
                val past = i < index
                TrackRow(q.track, highlighted = i == index, error = q.error,
                    subtitle = (if (i == index) "▶ Now playing · " else if (past) "Played · " else "") + q.track.artist + " · added by ${q.addedBy}",
                    onClick = { hub.jumpTo(q.uid) },
                    trailing = {
                        IconButton(onClick = { hub.move(q.uid, -1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = Neon.Dim) }
                        IconButton(onClick = { hub.move(q.uid, +1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down", tint = Neon.Dim) }
                        IconButton(onClick = { hub.remove(q.uid) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Remove", tint = Neon.Dim) }
                    })
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    if (save) AlertDialog(onDismissRequest = { save = false }, title = { Text("Save queue as playlist") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { g.library.createPlaylist(name.ifBlank { "Queue " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date()) }, queue.map { it.track.id }); save = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { save = false }) { Text("Cancel") } })
}

@Composable
fun LibraryHome(onLocal: @Composable () -> Unit) {
    val g = AppGraph.get()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Favourites", "History", "On this phone")
    Column(Modifier.fillMaxSize().background(Neon.Bg)) {
        TabRow(selectedTabIndex = tab, containerColor = Neon.Bg, contentColor = Neon.Cyan) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 12.sp, maxLines = 1) }) }
        }
        when (tab) {
            0 -> PlaylistsTab()
            1 -> {
                val favs = g.library.tracks.collectAsState().value.filter { it.liked }.map { it.track }
                LazyColumn {
                    if (favs.isNotEmpty()) item { ShelfTitle("${favs.size} favourites", "Play all") { g.player.playAll(favs) } }
                    else item { Text("Tap ♡ on any song to add it here.", color = Neon.Dim, modifier = Modifier.padding(16.dp)) }
                    items(favs, key = { it.id }) { TrackRow(it) }
                }
            }
            2 -> {
                val lib by g.library.tracks.collectAsState()
                val hist = remember(lib) { g.library.historyEntries(200) }
                LazyColumn {
                    if (hist.isEmpty()) item { Text("Songs you play show up here.", color = Neon.Dim, modifier = Modifier.padding(16.dp)) }
                    items(hist, key = { it.first.toString() + it.second.track.id }) { (ts, lt) ->
                        TrackRow(lt.track, subtitle = lt.track.artist + " · " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts)))
                    }
                }
            }
            else -> onLocal()
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val g = AppGraph.get()
    val lists by g.library.playlists.collectAsState()
    val lib by g.library.tracks.collectAsState()
    var open by remember { mutableStateOf<Long?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val opened = lists.firstOrNull { it.id == open }
    if (opened != null) {
        val tracks = opened.trackIds.mapNotNull { id -> lib.firstOrNull { it.track.id == id }?.track }
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { open = null }) { Text("‹ Playlists", color = Neon.Cyan) }
                    Text(opened.name, color = Neon.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { g.player.playAll(tracks) }) { Icon(Icons.Default.PlayArrow, "Play", tint = Neon.Cyan) }
                    IconButton(onClick = { g.library.deletePlaylist(opened.id); open = null }) { Icon(Icons.Default.Delete, "Delete playlist", tint = Neon.Dim) }
                }
            }
            if (tracks.isEmpty()) item { Text("Empty. Use ⋮ → Add to playlist on any song.", color = Neon.Dim, modifier = Modifier.padding(16.dp)) }
            itemsIndexed(tracks) { i, t ->
                TrackRow(t, onClick = { g.player.playAll(tracks, i) }, trailing = {
                    IconButton(onClick = { g.library.removeFromPlaylist(opened.id, i) }) { Icon(Icons.Default.Close, "Remove", tint = Neon.Dim) }
                })
            }
        }
        return
    }
    LazyColumn {
        item {
            TextButton(onClick = { create = true }, modifier = Modifier.padding(8.dp)) { Text("+ New playlist", color = Neon.Cyan) }
        }
        if (lists.isEmpty()) item { Text("No playlists yet. Create one, or save your queue as a playlist.", color = Neon.Dim, modifier = Modifier.padding(16.dp)) }
        items(lists, key = { it.id }) { p ->
            val first = p.trackIds.firstNotNullOfOrNull { id -> lib.firstOrNull { it.track.id == id }?.track }
            Row(Modifier.fillMaxWidth().clickable { open = p.id }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(first, 52.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(p.name, color = Neon.Text, fontWeight = FontWeight.SemiBold)
                    Text("${p.trackIds.size} songs", color = Neon.Dim, fontSize = 12.sp)
                }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { g.library.createPlaylist(name); name = ""; create = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { create = false }) { Text("Cancel") } })
}
