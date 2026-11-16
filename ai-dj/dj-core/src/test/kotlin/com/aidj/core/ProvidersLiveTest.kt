package com.aidj.core

import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.MusicalKey
import com.aidj.core.model.PlaybackType
import com.aidj.core.providers.AudiusProvider
import com.aidj.core.providers.Categories
import com.aidj.core.providers.HttpProviderBase
import com.aidj.core.providers.InternetArchiveProvider
import com.aidj.core.providers.MusicCatalog
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Live tests against the real providers (skipped with -Doffline / OFFLINE=1). These are the
 * "search for real songs and confirm real audio comes back" checks, minus a phone speaker.
 */
class ProvidersLiveTest {
    private val http = HttpProviderBase.defaultClient()
    private fun online() = System.getenv("OFFLINE") == null

    @Test
    fun audiusSearchReturnsPlayableRegionalMusic() {
        assumeTrue(online())
        val audius = AudiusProvider(http)
        for (q in listOf("punjabi", "sidhu moose wala", "bollywood", "arijit singh", "phonk", "gujarati")) {
            val r = audius.search(q, 8)
            println("audius '$q': ${r.size} -> ${r.take(3).joinToString { "${it.artist} – ${it.title} (${it.durationMs / 1000}s, bpm=${it.bpmHint}, key=${it.keyHint})" }}")
            assertTrue("results for $q", r.isNotEmpty())
            assertTrue(r.all { it.playback == PlaybackType.DIRECT_STREAM && it.uri.startsWith("https://") && it.durationMs >= 30_000 })
        }
        val trending = audius.trending(Categories.byId("punjabi"), 10)
        println("trending punjabi: ${trending.take(5).map { it.title }}")
        assertTrue(trending.isNotEmpty())
        val genreTrend = audius.trending(Categories.byId("edm"), 10)
        assertTrue(genreTrend.isNotEmpty())
    }

    @Test
    fun audiusStreamIsRealSeekableAudio() {
        assumeTrue(online())
        val t = AudiusProvider(http).search("sidhu moose wala 295", 3).first()
        // Range request, exactly like a player seeking: must return 206 + audio bytes.
        http.newCall(Request.Builder().url(t.uri).header("Range", "bytes=0-65535").build()).execute().use { r ->
            println("stream ${t.title}: HTTP ${r.code} ${r.header("Content-Type")} ${r.header("Content-Range")}")
            assertTrue(r.code == 206 || r.code == 200)
            assertTrue(r.header("Content-Type")!!.startsWith("audio/"))
            val head = r.body!!.bytes()
            assertTrue("ID3 or MPEG frame sync", (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte()) || (head[0].toInt() and 0xFF) == 0xFF)
        }
    }

    /** Full chain on a real song: stream -> decode (ffmpeg) -> our analyser; BPM must agree with Audius' own metadata. */
    @Test
    fun realStreamDecodesAndAnalysisMatchesProviderBpm() {
        assumeTrue(online())
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).exists() }
        assumeTrue("ffmpeg available", ffmpeg != null)
        val candidates = AudiusProvider(http).search("phonk", 25).filter { it.bpmHint != null && it.durationMs in 60_000..360_000 }
        assumeTrue(candidates.isNotEmpty())
        var checked = 0; var agreed = 0
        for (t in candidates.take(3)) {
            val out = File.createTempFile("aidj", ".f32")
            val p = ProcessBuilder(ffmpeg, "-v", "error", "-i", t.uri, "-ac", "1", "-ar", "22050", "-f", "f32le", "-y", out.path).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val bytes = out.readBytes(); out.delete()
            if (bytes.size < 22050 * 4 * 30) { println("decode failed for ${t.title}: $log"); continue }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = FloatArray(bytes.size / 4) { bb.float }
            val rms = sqrt(pcm.fold(0.0) { a, v -> a + v * v } / pcm.size)
            // Same call the app makes: provider BPM as a hint, verified against the audio.
            val a = AudioAnalyzer().analyze(pcm, 22050, bpmHint = t.bpmHint)
            val hint = t.bpmHint!!
            val ratio = a.bpm / hint
            val octaveOk = listOf(1.0, 2.0, 0.5, 1.5, 2.0 / 3).any { abs(ratio - it) < 0.03 }
            println("REAL '${t.artist} – ${t.title}': ${pcm.size / 22050}s decoded, rms=${"%.3f".format(rms)}; analyser ${"%.1f".format(a.bpm)} BPM (Audius says $hint), key ${a.key.name} (Audius: ${t.keyHint}, parsed ${t.keyHint?.let { MusicalKey.parse(it)?.name }}), grid conf ${a.grid.confidence}, sections ${a.sections.map { it.type.name.lowercase() }}")
            assertTrue("real audio, not silence", rms > 0.01)
            if (octaveOk) agreed++
            checked++
        }
        assertTrue(checked > 0)
        // Provider BPM isn't ground truth (on the 29-song eval set librosa sided with our analyser where they
        // disagreed), so require agreement on most tracks rather than all.
        assertTrue("tempo agreement $agreed/$checked", agreed >= checked - 1)
    }

    @Test
    fun internetArchiveResultsResolveToPlayableFiles() {
        assumeTrue(online())
        val r = InternetArchiveProvider(http).search("bollywood", 5)
        println("IA: ${r.map { "${it.title} (${it.durationMs / 1000}s) ${it.capabilities.note}" }}")
        assertTrue(r.isNotEmpty())
        val t = r.first()
        http.newCall(Request.Builder().url(t.uri).header("Range", "bytes=0-1023").build()).execute().use { resp ->
            assertTrue("IA file reachable: ${resp.code}", resp.code == 206 || resp.code == 200)
        }
    }

    @Test
    fun catalogFansOutAndReportsErrors() {
        assumeTrue(online())
        val broken = object : com.aidj.core.providers.MusicProvider {
            override val source = com.aidj.core.model.SourceType.JAMENDO
            override val name = "Broken"
            override val setupHint: String? = null
            override fun isConfigured() = true
            override fun search(query: String, limit: Int): List<com.aidj.core.model.Track> = throw com.aidj.core.providers.ProviderException("Broken is down")
        }
        val res = MusicCatalog(listOf(AudiusProvider(http), broken)).search("punjabi", 5)
        assertTrue(res.tracks.isNotEmpty())
        assertTrue(res.errors["Broken"] == "Broken is down")
    }
}
