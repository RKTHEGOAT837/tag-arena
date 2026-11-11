package com.aidj.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.aidj.app.AppGraph
import com.aidj.app.service.PartyService
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get()
        graph.start()
        PartyService.ensureRunning(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleShare(intent)
        // Crash recovery: offer to resume a party that was interrupted by a process death.
        val prefs = getSharedPreferences("aidj_settings", MODE_PRIVATE)
        val crashed = prefs.getBoolean("crashed", false)
        prefs.edit().putBoolean("crashed", false).apply()
        val saved = graph.settings.savedSession()?.takeIf { it.ageMs < 6 * 3600_000 && graph.brain.status.value.name == "IDLE" }

        setContent {
            AiDjTheme {
                var screen by remember { mutableStateOf("stage") }
                var resume by remember { mutableStateOf(saved) }
                BackHandler(screen != "stage") { screen = if (screen == "discover") "library" else "stage" }
                when (screen) {
                    "stage" -> StageScreen(
                        onOpenSettings = { screen = "settings" },
                        onOpenLibrary = { screen = "library" },
                        onRequestMic = { action ->
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
                            else { pendingMic = action; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                        },
                    )
                    "library" -> LibraryScreen(onBack = { screen = "stage" }, onDiscover = { screen = "discover" })
                    "discover" -> DiscoverScreen(onBack = { screen = "library" })
                    "settings" -> SettingsScreen(onBack = { screen = "stage" }, onLibrary = { screen = "library" })
                }
                resume?.let { s ->
                    val title = graph.library.get(s.trackId)?.track?.title ?: "your last track"
                    AlertDialog(
                        onDismissRequest = { resume = null; graph.settings.clearSession() },
                        title = { Text(if (crashed) "The party was interrupted" else "Resume the party?") },
                        text = { Text("Continue from “$title”?") },
                        confirmButton = { TextButton(onClick = {
                            resume = null
                            ContextCompat.startForegroundService(this, Intent(this, PartyService::class.java).setAction(PartyService.ACTION_FOREGROUND))
                            graph.brain.resumeSession(s.trackId, s.positionSec)
                        }) { Text("Resume") } },
                        dismissButton = { TextButton(onClick = { resume = null; graph.settings.clearSession() }) { Text("No") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Audio files shared into the app from other apps are imported into the library. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) CoroutineScope(Dispatchers.IO).launch { AppGraph.get().library.importDocuments(uris) }
    }
}
