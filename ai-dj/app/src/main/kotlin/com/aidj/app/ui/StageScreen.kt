package com.aidj.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aidj.app.AppGraph
import com.aidj.app.brain.AiState
import com.aidj.app.brain.BrainStatus
import com.aidj.app.service.PartyService
import com.aidj.app.stage.StageInputs
import com.aidj.app.stage.StageView

@Composable
fun rememberOnline(context: Context): Boolean {
    var online by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun check() { online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
        check()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check()
            override fun onLost(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = check()
        }
        cm.registerDefaultNetworkCallback(cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }
    return online
}

@Composable
fun StageScreen(onOpenSettings: () -> Unit, onOpenLibrary: () -> Unit, onRequestMic: (() -> Unit) -> Unit) {
    val g = remember { AppGraph.get() }
    val ctx = LocalContext.current
    val live by g.performer.live.collectAsState()
    val status by g.brain.status.collectAsState()
    val ai by g.brain.aiState.collectAsState()
    val settings by g.settings.settings.collectAsState()
    val aiOn by g.brain.aiEnabled.collectAsState()
    val locked by g.brain.locked.collectAsState()
    val voice by g.voice.state.collectAsState()
    val decision by g.brain.lastDecision.collectAsState()
    val online = rememberOnline(ctx)
    var sheet by remember { mutableStateOf<String?>(null) }
    val inputs = remember { StageInputs() }

    // Feed the stage: live state every frame-ish, events as they happen, latency for audio/visual alignment.
    LaunchedEffect(Unit) { g.performer.events.collect { inputs.events.add(it); if (inputs.events.size > 64) inputs.events.poll() } }
    inputs.live = live
    inputs.avatar = settings.avatar; inputs.theme = settings.stageTheme; inputs.lighting = settings.lighting; inputs.quality = settings.graphics
    LaunchedEffect(live.engine.sampleRate) { val l = g.engine.latencyMs; if (l > 0) inputs.latencySec = l / 1000.0 }

    Box(Modifier.fillMaxSize().background(Neon.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neon.Cyan, Neon.Pink))), contentAlignment = Alignment.Center) {
                    Text("AI", fontWeight = FontWeight.Black, color = Color.Black, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(settings.partyName, color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (c, label) = when {
                            !aiOn -> Neon.Dim to "AI DJ off"
                            ai == AiState.THINKING -> Neon.Amber to ai.label
                            ai == AiState.CLAUDE -> Neon.Lime to ai.label
                            ai == AiState.ERROR -> Neon.Red to ai.label
                            else -> Neon.Cyan to (if (settings.claudeEnabled && g.claude.isConfigured()) ai.label else "Local AI")
                        }
                        StatusDot(c); Spacer(Modifier.width(6.dp))
                        Text("$label · ${settings.mode.label}", color = Neon.Dim, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Icon(if (online) Icons.Default.Wifi else Icons.Default.WifiOff, "internet", tint = if (online) Neon.Lime else Neon.Red, modifier = Modifier.size(18.dp))
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = Neon.Text) }
            }

            // ------------------------------------------------ stage (centre attraction)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                var stageView by remember { mutableStateOf<StageView?>(null) }
                AndroidView(factory = { c -> StageView(c, inputs).also { stageView = it } }, modifier = Modifier.fillMaxSize(), update = { it.refreshPowerState() })
                // Pause GL rendering when not visible (battery); audio continues in the service.
                DisposableEffect(lifecycle) {
                    val obs = LifecycleEventObserver { _, e ->
                        when (e) {
                            Lifecycle.Event.ON_PAUSE -> stageView?.onPause()
                            Lifecycle.Event.ON_RESUME -> stageView?.onResume()
                            else -> {}
                        }
                    }
                    lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
                }
                Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    NeonChip(live.activity, Neon.Cyan)
                    if (live.transitionProgress >= 0f) {
                        Spacer(Modifier.height(6.dp))
                        Text("MIXING ${(live.transitionProgress * 100).toInt()}%", color = Neon.Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else if (live.nextMixInSec > 0 && live.next != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Next mix in ${fmtTime(live.nextMixInSec)} → ${live.next?.track?.title}", color = Neon.Dim, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Visualizer(live.engine.bands, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).padding(horizontal = 12.dp))
                AnimatedVisibility(voice.listening || voice.partial.isNotBlank(), Modifier.align(Alignment.Center)) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Neon.Panel.copy(alpha = 0.9f)).border(1.dp, Neon.Pink, RoundedCornerShape(14.dp)).padding(16.dp)) {
                        Text(if (voice.partial.isBlank()) "Listening…" else voice.partial, color = Neon.Text)
                    }
                }
                if (status == BrainStatus.IDLE) {
                    Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(40.dp))
                        .background(Brush.horizontalGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink)))
                        .clickable { PartyService.startParty(ctx) }.padding(horizontal = 30.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Celebration, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text("START PARTY", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                    val analysed = g.library.tracks.collectAsState().value.count { it.analysis != null }
                    val total = g.library.tracks.collectAsState().value.size
                    if (analysed < 2) Text(if (total == 0) "Add music first: Library → Import or Discover" else "Analysing your library… ($analysed/$total ready)",
                        color = Neon.Dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(top = 110.dp).clickable { onOpenLibrary() })
                }
            }

            // ------------------------------------------------ now playing
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(live.current?.track, Modifier.size(56.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(live.current?.track?.title ?: "Nothing playing", color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(live.current?.track?.artist?.ifBlank { "Unknown artist" } ?: "Press START PARTY", color = Neon.Dim, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (live.current != null) {
                            NeonChip("%.1f BPM".format(live.masterBpm), Neon.Cyan)
                            NeonChip("${live.keyCamelot} · ${live.keyName}", Neon.Violet)
                            live.section?.let { NeonChip(it.name.lowercase(), Neon.Pink) }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("-${fmtTime(live.remainingSec)}", color = Neon.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("energy", color = Neon.Dim, fontSize = 10.sp)
                    EnergyBar(live.energy, Modifier.width(64.dp))
                }
            }
            if (decision.isNotBlank()) Text(decision, color = Neon.Dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
            if (voice.reply.isNotBlank()) Text("🎙 ${voice.reply}", color = Neon.Lime, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp))

            // ------------------------------------------------ controls
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CtrlButton(Icons.Default.SkipPrevious, "Previous") { g.brain.previous() }
                Box(Modifier.size(62.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neon.Cyan, Neon.Pink))).clickable {
                    if (status == BrainStatus.IDLE) PartyService.startParty(ctx) else g.brain.togglePlay()
                }, contentAlignment = Alignment.Center) {
                    Icon(if (status == BrainStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(34.dp))
                }
                CtrlButton(Icons.Default.SkipNext, "Skip") { g.brain.skip() }
                CtrlButton(Icons.Default.Mic, "Voice", tint = if (voice.listening) Neon.Pink else Neon.Text) { onRequestMic { g.voice.listen() } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LabeledToggle(Icons.Default.AutoAwesome, if (aiOn) "AI DJ ON" else "AI DJ OFF", aiOn) { g.brain.aiEnabled.value = !aiOn; g.brain.replanIfNotStarted() }
                LabeledToggle(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, if (locked) "Locked" else "Lock song", locked) { if (locked) g.brain.unlock() else g.brain.locked.value = true }
                LabeledToggle(Icons.Default.QueueMusic, "Queue", false) { sheet = "queue" }
                LabeledToggle(Icons.Default.Tune, "DJ controls", false) { sheet = "controls" }
                LabeledToggle(Icons.Default.Celebration, "Party mode", false) { sheet = "modes" }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, "Volume", tint = Neon.Dim, modifier = Modifier.size(18.dp))
                Slider(value = settings.masterVolume, onValueChange = { v -> g.settings.update { it.copy(masterVolume = v) }; g.performer.setMasterVolume(v) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp), colors = SliderDefaults.colors(thumbColor = Neon.Cyan, activeTrackColor = Neon.Cyan))
            }
            Spacer(Modifier.height(6.dp))
        }
        when (sheet) {
            "queue" -> QueueSheet(onDismiss = { sheet = null }, onOpenLibrary = { sheet = null; onOpenLibrary() })
            "controls" -> ControlsSheet(onDismiss = { sheet = null })
            "modes" -> ModesSheet(onDismiss = { sheet = null })
        }
    }
}

@Composable
private fun CtrlButton(icon: ImageVector, desc: String, tint: Color = Neon.Text, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, desc, tint = tint, modifier = Modifier.size(30.dp)) }
}

@Composable
private fun LabeledToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (active) Neon.Cyan else Neon.Text, modifier = Modifier.size(22.dp))
        Text(label, color = if (active) Neon.Cyan else Neon.Dim, fontSize = 10.sp, maxLines = 1)
    }
}
