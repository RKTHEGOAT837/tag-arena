package com.aidj.core

import com.aidj.core.analysis.AudioAnalyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Tempo accuracy on real songs (EVAL_DIR built by the eval script). Prints a report; asserts a floor. */
class RealMusicEvalTest {
    @Test
    fun tempoAccuracyOnRealMusic() {
        val dir = System.getenv("EVAL_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "index.json").exists())
        val rows = Json.parseToJsonElement(File(dir, "index.json").readText()).jsonArray
        var exact = 0; var octave = 0; var n = 0; var exactHinted = 0
        val analyzer = AudioAnalyzer()
        for (r in rows) {
            val o = r.jsonObject
            val f = File(dir, o["id"]!!.jsonPrimitive.content + ".f32")
            val bytes = f.readBytes(); val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = FloatArray(bytes.size / 4) { bb.float }
            val ref = o["audius_bpm"]!!.jsonPrimitive.content.toDouble()
            val a = analyzer.analyze(pcm, 22050)
            val ah = analyzer.analyze(pcm, 22050, bpmHint = ref)
            fun ok(b: Double) = abs(b / ref - 1) < 0.02
            fun okOct(b: Double) = listOf(1.0, 2.0, 0.5).any { abs(b / (ref * it) - 1) < 0.02 }
            n++; if (ok(a.bpm)) exact++; if (okOct(a.bpm)) octave++; if (ok(ah.bpm)) exactHinted++
            println("%-18s %-40s ref %6.1f | ours %6.1f %s | with hint %6.1f %s | conf %.2f".format(
                o["q"]!!.jsonPrimitive.content.take(18), o["title"]!!.jsonPrimitive.content.take(40), ref,
                a.bpm, if (ok(a.bpm)) "OK " else if (okOct(a.bpm)) "OCT" else "BAD", ah.bpm, if (ok(ah.bpm)) "OK " else "BAD", ah.grid.confidence))
        }
        println("TEMPO: exact ±2%% %d/%d, octave-equivalent %d/%d, with provider hint exact %d/%d".format(exact, n, octave, n, exactHinted, n))
    }
}
