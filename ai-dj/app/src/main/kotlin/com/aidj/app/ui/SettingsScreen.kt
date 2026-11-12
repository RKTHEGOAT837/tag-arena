package com.aidj.app.ui

import android.net.wifi.WifiManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidj.app.AppGraph
import com.aidj.app.BuildConfig
import com.aidj.app.data.AudioQuality
import com.aidj.app.data.GraphicsQuality
import com.aidj.app.data.InternetUsage
import com.aidj.app.data.LightingStyle
import com.aidj.app.data.Personality
import com.aidj.app.data.StageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Neon.Text, modifier = Modifier.weight(1f), fontSize = 14.sp); Switch(value, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) NeonChip(label(o), if (o == selected) Neon.Cyan else Neon.Dim) { onSelect(o) }
    }
}

private fun lanIp(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
}.getOrNull() ?: "phone-ip"

@Composable
fun SettingsScreen(onBack: () -> Unit, onLibrary: () -> Unit) {
    val g = AppGraph.get()
    val s by g.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(g.settings.backendToken) }
    var jamendo by remember { mutableStateOf(g.settings.jamendoClientId) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    fun upd(block: (com.aidj.app.data.Settings) -> com.aidj.app.data.Settings) = g.settings.update(block)

    Column(Modifier.fillMaxSize().background(Neon.Bg)) {
        ScreenHeader("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Party")
            OutlinedTextField(s.partyName, { v -> upd { it.copy(partyName = v) } }, label = { Text("Party / session name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow("Allow explicit tracks", s.allowExplicit) { v -> upd { it.copy(allowExplicit = v) } }
            OutlinedTextField(s.preferredGenres.joinToString(", "), { v -> upd { it.copy(preferredGenres = v.split(',').map { x -> x.trim().lowercase() }.filter { x -> x.isNotBlank() }) } },
                label = { Text("Preferred genres") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onLibrary) { Text("Manage local library →", color = Neon.Cyan) }

            SectionTitle("Mixing")
            Text("Remix intensity: ${(s.remixIntensity * 100).toInt()}%", color = Neon.Dim, fontSize = 12.sp)
            Slider(s.remixIntensity, { v -> upd { it.copy(remixIntensity = v) } })
            SwitchRow("Automatic remixing (loops, drum layers, risers)", s.autoRemix) { v -> upd { it.copy(autoRemix = v) } }
            Text("Transition length: ${if (s.transitionBars == 0) "automatic" else "${s.transitionBars} bars"}", color = Neon.Dim, fontSize = 12.sp)
            Slider(s.transitionBars.toFloat(), { v -> upd { it.copy(transitionBars = v.toInt()) } }, valueRange = 0f..64f, steps = 15)
            SwitchRow("Harmonic key matching (pitch-shift up to ±2 st)", s.keyMatching) { v -> upd { it.copy(keyMatching = v) } }
            Text("Audio quality", color = Neon.Dim, fontSize = 12.sp)
            Choice(AudioQuality.entries, s.audioQuality, { it.label }) { v -> upd { it.copy(audioQuality = v) } }

            SectionTitle("AI DJ personality & voice")
            Choice(Personality.entries, s.personality, { it.label }) { v -> upd { it.copy(personality = v) } }
            SwitchRow("Voice announcements", s.voiceAnnouncements) { v -> upd { it.copy(voiceAnnouncements = v) } }
            Text("Speak at most every ${s.announcementEveryTracks} tracks", color = Neon.Dim, fontSize = 12.sp)
            Slider(s.announcementEveryTracks.toFloat(), { v -> upd { it.copy(announcementEveryTracks = v.toInt().coerceAtLeast(1)) } }, valueRange = 1f..10f, steps = 8)

            SectionTitle("Virtual DJ")
            Text("Skin tone", color = Neon.Dim, fontSize = 12.sp)
            Choice((0..4).toList(), s.avatar.skinTone, { "Tone ${it + 1}" }) { v -> upd { it.copy(avatar = it.avatar.copy(skinTone = v)) } }
            Text("Outfit", color = Neon.Dim, fontSize = 12.sp)
            Choice((0..3).toList(), s.avatar.outfit, { listOf("Black/cyan", "White tee", "Plum/gold", "Forest/orange")[it] }) { v -> upd { it.copy(avatar = it.avatar.copy(outfit = v)) } }
            Text("Hair", color = Neon.Dim, fontSize = 12.sp)
            Choice((0..3).toList(), s.avatar.hair, { listOf("Shaved", "Short", "Cap", "Long")[it] }) { v -> upd { it.copy(avatar = it.avatar.copy(hair = v)) } }
            Text("Headphones", color = Neon.Dim, fontSize = 12.sp)
            Choice((0..3).toList(), s.avatar.headphones, { listOf("Black", "White", "Red", "Blue")[it] }) { v -> upd { it.copy(avatar = it.avatar.copy(headphones = v)) } }
            Text("Performance style", color = Neon.Dim, fontSize = 12.sp)
            Choice((0..2).toList(), s.avatar.style, { listOf("Energetic", "Cool", "Technical")[it] }) { v -> upd { it.copy(avatar = it.avatar.copy(style = v)) } }

            SectionTitle("Stage")
            Choice(StageTheme.entries, s.stageTheme, { it.label }) { v -> upd { it.copy(stageTheme = v) } }
            Spacer(Modifier.height(6.dp))
            Choice(LightingStyle.entries, s.lighting, { it.label }) { v -> upd { it.copy(lighting = v) } }
            Text("Graphics quality (Auto lowers it if frames drop; battery saver forces Low)", color = Neon.Dim, fontSize = 12.sp)
            Choice(GraphicsQuality.entries, s.graphics, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v -> upd { it.copy(graphics = v) } }

            SectionTitle("Your music taste (steers song selection)")
            val taste = g.taste
            val spotify by g.spotify.state.collectAsState()
            var spotifyId by remember { mutableStateOf(g.settings.spotifyClientId) }
            var tasteCount by remember { mutableStateOf(taste.entryCount()) }
            var tasteMsg by remember { mutableStateOf<String?>(null) }
            val historyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    tasteMsg = withContext(Dispatchers.IO) {
                        runCatching {
                            val r = ctx.contentResolver.openInputStream(uri)!!.use { taste.importJson(it) }
                            "Imported ${r.entries} entries (${r.kind})"
                        }.getOrElse { "Import failed: ${it.message}" }
                    }
                    tasteCount = withContext(Dispatchers.IO) { taste.entryCount() }
                }
            }
            Text("Spotify: connect your account to read your top tracks, top artists and recently played (official API; needs your free Spotify developer Client ID with redirect URI ${com.aidj.app.discovery.SpotifyTaste.REDIRECT}). YouTube: Google removed API access to watch history, so export it with Google Takeout (YouTube → history → JSON) and import the watch-history.json here. Spotify's account-data export files work too. Everything stays on this phone.",
                color = Neon.Dim, fontSize = 12.sp)
            OutlinedTextField(spotifyId, { spotifyId = it; g.settings.spotifyClientId = it }, label = { Text("Spotify Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { g.spotify.beginAuth(spotifyId) }, enabled = !spotify.busy) {
                    Text(if (spotify.connected) "Re-connect Spotify" else "Connect Spotify", color = Neon.Lime)
                }
                if (spotify.connected) {
                    OutlinedButton(onClick = { scope.launch { g.spotify.syncNow(); tasteCount = withContext(Dispatchers.IO) { taste.entryCount() } } }, enabled = !spotify.busy) { Text("Sync now", color = Neon.Cyan) }
                    OutlinedButton(onClick = { g.spotify.disconnect(); tasteCount = taste.entryCount() }) { Text("Disconnect", color = Neon.Red) }
                }
            }
            if (spotify.message.isNotBlank()) Text(spotify.message, color = Neon.Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { historyPicker.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) { Text("Import history file (Takeout / Spotify)", color = Neon.Cyan) }
                if (tasteCount > 0) OutlinedButton(onClick = { taste.clear(); tasteCount = 0; tasteMsg = "Taste data cleared" }) { Text("Clear", color = Neon.Red) }
            }
            tasteMsg?.let { Text(it, color = Neon.Lime, fontSize = 12.sp) }
            if (tasteCount > 0) {
                val top = remember(tasteCount) { taste.topArtists(6).joinToString { it.first } }
                Text("$tasteCount taste entries · top artists: $top", color = Neon.Text, fontSize = 12.sp)
            }

            SectionTitle("Gemini (direct, free tier)")
            var geminiKey by remember { mutableStateOf(g.settings.geminiApiKey) }
            Text("With your own Gemini API key the phone talks to Gemini directly – no backend needed. Key is stored encrypted on this phone.", color = Neon.Dim, fontSize = 12.sp)
            OutlinedTextField(geminiKey, { geminiKey = it; g.settings.geminiApiKey = it }, label = { Text("Gemini API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (geminiKey.isNotBlank()) Text("Active: Gemini ${com.aidj.app.brain.ClaudeOrchestrator.GEMINI_MODEL} is the cloud brain", color = Neon.Lime, fontSize = 12.sp)

            SectionTitle("Cloud AI brain (Claude or Gemini, via your backend)")
            SwitchRow("Use the cloud AI for decisions & requests", s.claudeEnabled) { v -> upd { it.copy(claudeEnabled = v) } }
            Text("The backend holds the API key (Anthropic, or a free-tier Google Gemini key with LLM_PROVIDER=gemini) — it never ships in the app. Without a backend the local AI DJ still runs everything.",
                color = Neon.Dim, fontSize = 12.sp)
            OutlinedTextField(s.backendUrl, { v -> upd { it.copy(backendUrl = v.trim()) } }, label = { Text("Backend URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it; g.settings.backendToken = it }, label = { Text("Device token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                testResult = "Testing…"
                scope.launch {
                    testResult = withContext(Dispatchers.IO) {
                        runCatching {
                            OkHttpClient().newCall(Request.Builder().url(s.backendUrl.trimEnd('/') + "/health").header("Authorization", "Bearer ${g.settings.backendToken}").build())
                                .execute().use { r -> if (r.isSuccessful) "Backend OK: ${r.body?.string()?.take(120)}" else "HTTP ${r.code}" }
                        }.getOrElse { "Failed: ${it.message}" }
                    }
                }
            }) { Text("Test connection", color = Neon.Cyan) }
            testResult?.let { Text(it, color = Neon.Dim, fontSize = 12.sp) }
            g.claude.lastError?.let { Text("Last Claude error: $it", color = Neon.Red, fontSize = 11.sp) }
            Text("Internet usage", color = Neon.Dim, fontSize = 12.sp)
            Choice(InternetUsage.entries, s.internet, { it.label }) { v -> upd { it.copy(internet = v) } }
            OutlinedTextField(jamendo, { jamendo = it; g.settings.jamendoClientId = it }, label = { Text("Own Jamendo client id (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionTitle("MCP server (control from other MCP clients)")
            SwitchRow("Expose DJ tools over MCP on this Wi-Fi", s.mcpServerEnabled) { v -> upd { it.copy(mcpServerEnabled = v) } }
            if (s.mcpServerEnabled) {
                Text("Endpoint: http://${lanIp()}:${s.mcpPort}/mcp", color = Neon.Text, fontSize = 12.sp)
                Text("Bearer token: ${g.settings.mcpToken}", color = Neon.Text, fontSize = 12.sp)
                TextButton(onClick = { g.settings.regenerateMcpToken(); upd { it.copy() } }) { Text("Regenerate token", color = Neon.Pink) }
            }

            SectionTitle("Diagnostics")
            val live by g.performer.live.collectAsState()
            KeyValueRow("Engine sample rate", "${live.engine.sampleRate} Hz")
            KeyValueRow("Output latency", "%.0f ms".format(g.engine.latencyMs))
            KeyValueRow("Audio CPU load", "%.1f %%".format(live.engine.cpuLoad * 100))
            KeyValueRow("Buffer underruns", "${live.engine.xruns}")
            KeyValueRow("Version", BuildConfig.VERSION_NAME)
            OutlinedButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { runCatching { com.aidj.app.data.Diagnostics.share(ctx) } } }
            }) { Text("Share diagnostic report (send it to Claude to get bugs fixed)", color = Neon.Cyan) }
            Spacer(Modifier.height(40.dp))
        }
    }
    @Suppress("UNUSED_VARIABLE") val unusedWifi = WifiManager::class
}
