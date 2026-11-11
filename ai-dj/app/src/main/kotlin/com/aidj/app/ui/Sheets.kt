package com.aidj.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidj.app.AppGraph
import com.aidj.app.brain.TransitionOverrides
import com.aidj.core.model.PartyMode
import com.aidj.core.model.TransitionStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Neon.Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Neon.Dim) }
            }
            content()
        }
    }
}

@Composable
fun QueueSheet(onDismiss: () -> Unit, onOpenLibrary: () -> Unit) {
    val g = AppGraph.get()
    val queue by g.brain.manualQueue.collectAsState()
    val cands by g.brain.candidates.collectAsState()
    val live by g.performer.live.collectAsState()
    Sheet("Music queue", onDismiss) {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            item { SectionTitle("Now playing") }
            item { Text(live.current?.track?.displayName ?: "—", color = Neon.Text) }
            item { SectionTitle("Mixing in next") }
            item { Text(live.next?.track?.displayName ?: "AI is choosing…", color = Neon.Cyan) }
            item { SectionTitle("Your requests (${queue.size})") }
            items(queue) { id ->
                val t = g.library.get(id)?.track
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t?.displayName ?: id, color = Neon.Text, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { g.brain.playNext(id) }) { Text("Play now", color = Neon.Cyan) }
                    TextButton(onClick = { g.brain.dequeue(id) }) { Text("Remove", color = Neon.Red) }
                }
            }
            item { TextButton(onClick = onOpenLibrary) { Text("+ Add tracks from library", color = Neon.Cyan) } }
            item { SectionTitle("AI candidates (ranked)") }
            items(cands.take(10)) { c ->
                Row(Modifier.fillMaxWidth().clickable { g.brain.enqueue(c.trackId) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${c.artist} – ${c.title}", color = Neon.Text, maxLines = 1, fontSize = 13.sp)
                        Text("${"%.1f".format(c.bpm)} BPM · ${c.key} · energy ${"%.2f".format(c.energy)}${if (c.keyShift != 0) " · key ${c.keyShift} st" else ""}", color = Neon.Dim, fontSize = 11.sp)
                    }
                    Text("%.0f%%".format(c.score * 100), color = Neon.Lime, fontSize = 12.sp)
                    TextButton(onClick = { g.brain.playNext(c.trackId) }) { Text("Play", color = Neon.Cyan) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsSheet(onDismiss: () -> Unit) {
    val g = AppGraph.get()
    val live by g.performer.live.collectAsState()
    val ov by g.brain.overrides.collectAsState()
    val paused by g.brain.decisionsPaused.collectAsState()
    val settings by g.settings.settings.collectAsState()
    val steering by g.brain.steering.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableFloatStateOf(live.masterBpm.toFloat().coerceIn(60f, 200f)) }
    fun guard(block: () -> Unit) { err = runCatching(block).exceptionOrNull()?.message }
    Sheet("DJ controls", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 620.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pause AI decisions", color = Neon.Text, modifier = Modifier.weight(1f))
                Switch(paused, { g.brain.decisionsPaused.value = it; if (it) g.performer.cancelPlan() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic remixing (loops, drums, risers)", color = Neon.Text, modifier = Modifier.weight(1f))
                Switch(settings.autoRemix, { v -> g.settings.update { it.copy(autoRemix = v) }; g.brain.replanIfNotStarted(keepTrack = true) })
            }
            SectionTitle("Next transition")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NeonChip("Auto", if (ov.style == null) Neon.Cyan else Neon.Dim) { g.brain.setOverrides(ov.copy(style = null)) }
                for (s in TransitionStyle.entries) NeonChip(s.label, if (ov.style == s) Neon.Cyan else Neon.Dim) { g.brain.setOverrides(ov.copy(style = s)) }
            }
            Text("Length: ${ov.bars?.let { "$it bars" } ?: "auto"}", color = Neon.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            Slider(value = (ov.bars ?: 0).toFloat(), onValueChange = { g.brain.overrides.value = ov.copy(bars = it.toInt().takeIf { b -> b > 0 }) },
                onValueChangeFinished = { g.brain.setOverrides(g.brain.overrides.value) }, valueRange = 0f..64f, steps = 15)
            Text("Drama: ${"%.0f".format((ov.drama ?: settings.remixIntensity) * 100)}%", color = Neon.Dim, fontSize = 12.sp)
            Slider(value = ov.drama ?: settings.remixIntensity, onValueChange = { g.brain.overrides.value = ov.copy(drama = it) },
                onValueChangeFinished = { g.brain.setOverrides(g.brain.overrides.value) })
            OutlinedButton(onClick = { g.brain.setOverrides(ov.copy(asap = true)); g.brain.skip() }) { Text("Mix now", color = Neon.Pink) }

            SectionTitle("Energy & genre")
            Text("Energy nudge: ${"%+.0f".format(steering.energyDelta * 100)}%", color = Neon.Dim, fontSize = 12.sp)
            Slider(value = steering.energyDelta, onValueChange = { g.brain.steering.value = steering.copy(energyDelta = it) },
                onValueChangeFinished = { g.brain.steer(g.brain.steering.value) }, valueRange = -0.5f..0.5f)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val genres = listOf("house", "techno", "edm", "bollywood", "punjabi", "hip hop", "pop", "disco", "funk", "chill", "rock", "latin")
                NeonChip("Any", if (steering.genres.isEmpty()) Neon.Cyan else Neon.Dim) { g.brain.steer(steering.copy(genres = emptyList())) }
                for (gn in genres) NeonChip(gn, if (gn in steering.genres) Neon.Cyan else Neon.Dim) {
                    g.brain.steer(steering.copy(genres = if (gn in steering.genres) steering.genres - gn else steering.genres + gn))
                }
            }

            SectionTitle("Tempo")
            Text("Master BPM ${"%.1f".format(bpm)} (now ${"%.1f".format(live.masterBpm)})", color = Neon.Dim, fontSize = 12.sp)
            Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 60f..200f, onValueChangeFinished = { guard { g.performer.setBpm(bpm, 16f) } })

            SectionTitle("Effects (current deck, beat-synced)")
            val deck = live.currentDeck
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FxPad("Echo") { guard { g.performer.applyEffect(deck, "echo", 0.6f, 0.75f, 2f) } }
                FxPad("Reverb") { guard { g.performer.applyEffect(deck, "reverb", 0.5f, 1f, 2f) } }
                FxPad("Flanger") { guard { g.performer.applyEffect(deck, "flanger", 0.7f, 1f, 4f) } }
                FxPad("Low-pass") { guard { g.performer.applyEffect(deck, "filter_lowpass", 0.75f, 1f, 2f) } }
                FxPad("High-pass") { guard { g.performer.applyEffect(deck, "filter_highpass", 0.6f, 1f, 2f) } }
                FxPad("Brake") { guard { g.performer.applyEffect(deck, "brake", 1f, 2f, 1f) } }
                FxPad("Impact") { guard { g.performer.applyEffect(deck, "impact", 1f, 1f, 1f) } }
                FxPad("FX off") { guard { g.performer.applyEffect(deck, "none", 0f, 1f, 1f) } }
            }
            SectionTitle("Loops & remix")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)) FxPad(if (b < 1) "1/${(1 / b).toInt()}" else "${b.toInt()} beat") { guard { g.performer.loop(deck, b, slip = b < 1, release = false) } }
                FxPad("Exit loop") { guard { g.performer.loop(deck, 0.0, false, true) } }
                FxPad("Drum layer") { guard { g.performer.remix("drum_layer", null, 0.7f, 16) } }
                FxPad("Riser 8 bars") { guard { g.performer.remix("riser", null, 0.7f, 8) } }
                FxPad("Beat roll") { guard { g.performer.remix("beat_roll", null, 1f, 2) } }
                FxPad("Extend section") { guard { g.performer.remix("extend_section", null, 1f, 8) } }
                FxPad("Stop remix") { guard { g.performer.remix("stop_remix", null, 0f, 1) } }
            }
            err?.let { Text(it, color = Neon.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = TransitionOverrides()
}

@Composable
private fun FxPad(label: String, onClick: () -> Unit) {
    Text(label, color = Neon.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Neon.Panel2).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModesSheet(onDismiss: () -> Unit) {
    val g = AppGraph.get()
    val settings by g.settings.settings.collectAsState()
    var custom by remember { mutableStateOf(settings.customGenres.joinToString(", ")) }
    Sheet("Party mode", onDismiss) {
        val desc = mapOf(
            PartyMode.AUTO to "AI controls everything, reading your library and the room.",
            PartyMode.CLUB to "Long, smooth bass-swap blends of house, techno and dance music.",
            PartyMode.BOLLYWOOD to "Bollywood & Indian party anthems with quick, punchy mixes.",
            PartyMode.PUNJABI to "Bhangra & Punjabi bangers: cuts, echo-outs and dhol drum bridges.",
            PartyMode.RETRO to "Classic hits, smooth crossfades.",
            PartyMode.CHILL to "Relaxed, low-energy grooves and long crossfades.",
            PartyMode.FESTIVAL to "Dramatic buildups, loop rolls, risers and huge drops.",
            PartyMode.CUSTOM to "Your genres, AI handles the rest.",
        )
        for (m in PartyMode.entries) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (settings.mode == m) Neon.Panel2 else Neon.Panel)
                .clickable { g.brain.setMode(m, custom.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }); onDismiss() }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, color = if (settings.mode == m) Neon.Cyan else Neon.Text, fontWeight = FontWeight.Bold)
                    Text(desc[m] ?: "", color = Neon.Dim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        androidx.compose.material3.OutlinedTextField(custom, { custom = it }, label = { Text("Custom genres (comma separated)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(4.dp).width(1.dp))
    }
}
