package com.aidj.core

import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.PartyMode
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.TransitionStyle
import com.aidj.core.planning.TransitionPlanner
import com.aidj.core.planning.TransitionRequest
import com.aidj.core.providers.AudiusProvider
import com.aidj.core.providers.HttpProviderBase
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * Real-music DJ test, step 1: stream two real songs from Audius, decode them, analyse them with the
 * app's analyser and export analyses + transition plans. native-tests/run.sh then executes the plans
 * in the real C++ engine (step 2). Runs only with REAL_DJ_TEST=1 (needs network + ffmpeg).
 */
class RealPlanExportTest {
    private fun decode(url: String, sr: Int): FloatArray {
        val out = File.createTempFile("aidj", ".f32")
        ProcessBuilder("ffmpeg", "-v", "error", "-i", url, "-ac", "1", "-ar", sr.toString(), "-f", "f32le", "-y", out.path)
            .redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor() }
        val b = out.readBytes(); out.delete()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }

    @Test
    fun exportRealMusicPlans() {
        assumeTrue(System.getenv("REAL_DJ_TEST") != null)
        val dir = File(System.getProperty("user.dir"), "build/integration-real").apply { mkdirs() }
        val audius = AudiusProvider(HttpProviderBase.defaultClient())
        // Two real tempo-compatible songs from a regional search (punjabi / bhangra), with provider BPM.
        val pool = (audius.search("bhangra", 25) + audius.search("punjabi", 25))
            .filter { it.bpmHint != null && it.durationMs in 120_000..330_000 }.distinctBy { it.id }
        var a: com.aidj.core.model.Track? = null; var b: com.aidj.core.model.Track? = null
        outer@ for (x in pool) for (y in pool) if (x.id != y.id && abs(x.bpmHint!! - y.bpmHint!!) / x.bpmHint!! < 0.04) { a = x; b = y; break@outer }
        assumeTrue("found a tempo-compatible pair", a != null && b != null)
        val analyzer = AudioAnalyzer()
        val tracks = listOf("a" to a!!, "b" to b!!)
        val analyses = tracks.associate { (name, t) ->
            val hi = decode(t.uri, 44100)
            ByteBuffer.allocate(hi.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { bb -> hi.forEach { bb.putFloat(it) } }.array().let { File(dir, "$name.f32").writeBytes(it) }
            val an = analyzer.analyze(decode(t.uri, 22050), 22050, bpmHint = t.bpmHint)
            println("REAL $name: ${t.artist} – ${t.title}: ${"%.2f".format(an.bpm)} BPM (Audius ${t.bpmHint}), key ${an.key.camelot}, conf ${an.grid.confidence}, sections ${an.sections.map { "${it.type.name.lowercase()}@${it.startBar}" }}")
            name to an
        }
        File(dir, "tracks.txt").printWriter().use { w ->
            for ((name, t) in tracks) { val an = analyses.getValue(name); w.println("$name ${an.bpm} ${an.grid.firstBeatSec} ${an.grid.downbeatPhase} ${an.durationSec} 0 ${t.bpmHint}") }
        }
        // Treat them as user-owned-equivalent for the engine test (exercises sync + FX); licensing gates are unit-tested separately.
        val caps = SourceCapabilities.LOCAL
        val planner = TransitionPlanner(Random(7))
        File(dir, "plans.txt").printWriter().use { w ->
            for (style in listOf(TransitionStyle.BASS_SWAP, TransitionStyle.ECHO_OUT, TransitionStyle.FILTER_SWEEP, TransitionStyle.CUT)) {
                val p = planner.plan(analyses.getValue("a"), caps, 20.0, analyses.getValue("b"), caps, analyses.getValue("a").bpm,
                    PartyMode.PUNJABI.profile(), TransitionRequest(style = style, drama = 0.7f), 0, 1)
                w.println("PLAN ${p.style} a b ${p.startBeatOut} ${p.inCueSec} ${p.lengthBeats} ${p.handoverBeat} ${p.keyShift} ${if (p.syncIn) 1 else 0} ${p.inSyncBpm}")
                for (r in p.ramps) w.println("R ${r.param.id} ${if (r.param.perDeck) r.deck else -1} ${r.startBeat} ${r.endBeat} ${r.from ?: "nan"} ${r.to} ${r.curve.id}")
                for (act in p.actions) w.println("A ${act.type.id} ${act.deck} ${act.beat} ${act.arg0} ${act.arg1}")
                w.println("END")
                println("plan ${p.style}: ${p.description}; mix starts at ${"%.1f".format(p.startTimeOutSec)}s of A")
            }
        }
    }
}
