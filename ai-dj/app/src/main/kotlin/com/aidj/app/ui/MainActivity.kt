package com.aidj.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aidj.app.AppGraph
import com.aidj.app.playback.Engine
import com.aidj.app.playback.PlaybackService
import com.aidj.app.service.PartyService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingMic: (() -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingMic?.invoke()
        pendingMic = null
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get()
        graph.start()
        PartyService.ensureRunning(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        val prefs = getSharedPreferences("aidj_settings", MODE_PRIVATE)
        val crashed = prefs.getBoolean("crashed", false)
        prefs.edit().putBoolean("crashed", false).apply()
        val saved = graph.settings.savedSession()?.takeIf { it.ageMs < 6 * 3600_000 && graph.brain.status.value.name == "IDLE" }

        setContent {
            AiDjTheme {
                var tab by remember { mutableStateOf("home") }
                var searchSeed by remember { mutableStateOf("") }
                var showPlayer by remember { mutableStateOf(false) }
                var resume by remember { mutableStateOf(saved) }
                val snack = remember { SnackbarHostState() }
                val now by graph.player.now.collectAsState()
                LaunchedEffect(Unit) { graph.player.messages.collect { snack.showSnackbar(it) } }
                // YouTube may only play in a visible player: open it automatically.
                LaunchedEffect(now.engine, now.item?.uid) { if (now.engine == Engine.YOUTUBE) showPlayer = true }
                BackHandler(showPlayer || tab != "home") { if (showPlayer) closePlayer { showPlayer = false } else tab = "home" }

                Box(Modifier.fillMaxSize().background(Neon.Bg).safeDrawingPadding()) {
                    Scaffold(
                        containerColor = Neon.Bg,
                        snackbarHost = { SnackbarHost(snack) },
                        bottomBar = {
                            Column {
                                MiniPlayer { showPlayer = true }
                                NavigationBar(containerColor = Neon.Panel) {
                                    for ((id, label, icon) in listOf(
                                        Triple("home", "Home", Icons.Default.Home), Triple("search", "Search", Icons.Default.Search),
                                        Triple("dj", "AI DJ", Icons.Default.Speaker), Triple("queue", "Queue", Icons.AutoMirrored.Filled.QueueMusic),
                                        Triple("library", "Library", Icons.Default.LibraryMusic), Triple("settings", "Settings", Icons.Default.Settings),
                                    )) NavigationBarItem(
                                        selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, label) },
                                        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Neon.Cyan, selectedTextColor = Neon.Cyan, indicatorColor = Neon.Panel2,
                                            unselectedIconColor = Neon.Dim, unselectedTextColor = Neon.Dim),
                                    )
                                }
                            }
                        },
                    ) { pad ->
                        Box(Modifier.padding(pad)) {
                            when (tab) {
                                "home" -> HomeScreen(onSearch = { searchSeed = it; tab = "search" }, onOpenDj = { tab = "dj" })
                                "search" -> SearchScreen(searchSeed)
                                "dj" -> StageScreen(
                                    onOpenSettings = { tab = "settings" },
                                    onOpenLibrary = { tab = "search" },
                                    onRequestMic = { action ->
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
                                        else { pendingMic = action; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                                    },
                                )
                                "queue" -> QueueScreen()
                                "library" -> LibraryHome { LibraryScreen(onBack = { tab = "home" }, onDiscover = { tab = "search" }) }
                                "settings" -> SettingsScreen(onBack = { tab = "home" }, onLibrary = { tab = "library" })
                            }
                        }
                    }
                    if (showPlayer) NowPlayingScreen(onClose = { closePlayer { showPlayer = false } })
                }

                resume?.let { s ->
                    val title = graph.library.get(s.trackId)?.track?.title ?: "your last track"
                    AlertDialog(
                        onDismissRequest = { resume = null; graph.settings.clearSession() },
                        title = { Text(if (crashed) "The party was interrupted" else "Resume the DJ set?") },
                        text = { Text("Continue from “$title”?") },
                        confirmButton = { TextButton(onClick = {
                            resume = null
                            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, PartyService::class.java).setAction(PartyService.ACTION_FOREGROUND))
                            graph.brain.resumeSession(s.trackId, s.positionSec)
                            tab = "dj"
                        }) { Text("Resume") } },
                        dismissButton = { TextButton(onClick = { resume = null; graph.settings.clearSession() }) { Text("No") } },
                    )
                }
            }
        }
    }

    /** Closing the full player while YouTube plays pauses it (YouTube's embed must stay visible). */
    private fun closePlayer(done: () -> Unit) {
        val hub = AppGraph.get().player
        if (hub.engine == Engine.YOUTUBE) hub.pause()
        done()
    }

    override fun onStart() {
        super.onStart()
        // Bind a controller so the Media3 session service is connected and can manage its foreground state.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        AppGraph.get().player.onAppBackgrounded()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "aidj" && data.host == "spotify-callback") {
            CoroutineScope(Dispatchers.IO).launch { AppGraph.get().spotify.handleCallback(data) }
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) CoroutineScope(Dispatchers.IO).launch { AppGraph.get().library.importShared(uris) }
    }
}
