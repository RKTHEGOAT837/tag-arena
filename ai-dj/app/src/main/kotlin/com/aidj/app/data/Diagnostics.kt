package com.aidj.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.aidj.app.AppGraph
import com.aidj.app.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap bug report for phone-only debugging: everything needed to diagnose a problem remotely is
 * written into a single text file the user can share straight into the Claude chat.
 * Contains no secrets (no tokens, no keys) — settings are summarised, never dumped raw.
 */
object Diagnostics {

    fun buildReport(context: Context): File {
        val g = AppGraph.get()
        val sb = StringBuilder(64 * 1024)
        fun line(s: String = "") = sb.append(s).append('\n')
        fun section(t: String) { line(); line("=== $t ===") }

        line("AI DJ diagnostic report")
        line("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        line("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        section("Engine")
        runCatching {
            val st = g.engine.readState()
            line("running: ${g.engine.isRunning}, sampleRate: ${st.sampleRate} Hz, latency: ${"%.0f".format(g.engine.latencyMs)} ms")
            line("cpu: ${"%.1f".format(st.cpuLoad * 100)} %, underruns: ${st.xruns}, masterBpm: ${"%.2f".format(st.masterBpm)}, limiterGain: ${"%.2f".format(st.limiterGain)}")
            st.decks.forEachIndexed { i, d ->
                line("deck $i: playing=${d.playing} pos=${"%.1f".format(d.positionSec)}s/${"%.1f".format(d.durationSec)}s synced=${d.synced} " +
                    "rate=${"%.4f".format(d.rate)} keylock=${d.keylock} pitch=${d.pitchSemis} loop=${d.loopBeats} phaseErr=${"%.4f".format(d.phaseErrorBeats)} stretch=${d.stretching}")
            }
        }.onFailure { line("engine state failed: $it") }

        section("Performance")
        runCatching {
            val live = g.performer.live.value
            line("phase: ${live.phase}, activity: ${live.activity}")
            line("current: ${live.current?.track?.displayName ?: "-"} (${live.keyCamelot}, section ${live.section}, energy ${"%.2f".format(live.energy)})")
            line("next: ${live.next?.track?.displayName ?: "-"}, transition: ${live.transitionLabel.ifBlank { "-" }} ${if (live.transitionProgress >= 0) "${(live.transitionProgress * 100).toInt()}%" else ""}")
            line("brain: ${g.brain.status.value}, ai: ${g.brain.aiState.value}, decision: ${g.brain.lastDecision.value.take(300)}")
            line("history: ${g.brain.history.size} tracks, queue: ${g.brain.manualQueue.value.size}")
        }.onFailure { line("live state failed: $it") }

        section("Library")
        runCatching {
            val t = g.library.tracks.value
            line("tracks: ${t.size}, analysed: ${t.count { it.analysis != null }}, failed: ${t.count { it.analysisError != null }}")
            t.filter { it.analysisError != null }.take(10).forEach { line("  analysis failed: '${it.track.title}' (${it.track.source}): ${it.analysisError}") }
            line("taste entries: ${g.taste.entryCount()}, top: ${g.taste.topArtists(5).joinToString { it.first }}")
        }.onFailure { line("library failed: $it") }

        section("Configuration (no secrets)")
        runCatching {
            val s = g.settings.current
            line("mode: ${s.mode}, autoRemix: ${s.autoRemix}, remixIntensity: ${s.remixIntensity}, keyMatching: ${s.keyMatching}")
            line("cloudAi: ${s.claudeEnabled}, backendUrl set: ${s.backendUrl.isNotBlank()}, token set: ${g.settings.backendToken.isNotBlank()}")
            line("internet: ${s.internet}, graphics: ${s.graphics} (effective frame ${"%.1f".format(0f)} ms), audioQuality: ${s.audioQuality}")
            line("spotify connected: ${g.settings.spotifyRefreshToken.isNotBlank()}, mcpServer: ${s.mcpServerEnabled}")
            g.claude.lastError?.let { line("last cloud AI error: $it") }
        }.onFailure { line("settings failed: $it") }

        section("Last crash")
        val crash = File(context.filesDir, "last_crash.txt")
        if (crash.exists()) sb.append(crash.readText().take(12_000)).append('\n') else line("(none recorded)")

        section("Recent log (this app only)")
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"))
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                val keep = ArrayDeque<String>()
                for (l in lines) { keep.addLast(l); if (keep.size > 1500) keep.removeFirst() }
                keep.forEach { line(it) }
            }
        }.onFailure { line("logcat unavailable: $it") }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("aidj-report") }?.forEach { it.delete() }
        val f = File(dir, "aidj-report-${System.currentTimeMillis()}.txt")
        f.writeText(sb.toString())
        return f
    }

    /** Builds the report and opens the system share sheet (share it into the Claude app chat). */
    fun share(context: Context) {
        val f = buildReport(context)
        val uri = FileProvider.getUriForFile(context, "com.aidj.app.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AI DJ diagnostic report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun recordCrash(context: Context, t: Thread, e: Throwable) {
        runCatching {
            File(context.filesDir, "last_crash.txt").writeText(
                "time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}\n" +
                    "app: ${BuildConfig.VERSION_NAME}\nthread: ${t.name}\n" + android.util.Log.getStackTraceString(e),
            )
        }
    }
}
