package com.aidj.core

import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.PartyMode
import com.aidj.core.model.SourceCapabilities
import com.aidj.core.model.TransitionStyle
import com.aidj.core.planning.TransitionPlanner
import com.aidj.core.planning.TransitionRequest
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Exports real analyses + transition plans for synthetic tracks so the native integration test
 * (native-tests/test_integration.cpp) can execute them in the actual C++ engine.
 */
class PlanExportTest {
    private fun writePcm(f: File, x: FloatArray) {
        val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) bb.putFloat(v)
        f.writeBytes(bb.array())
    }

    @Test
    fun exportPlans() {
        val dir = File(System.getProperty("user.dir"), "build/integration").apply { mkdirs() }
        val analyzer = AudioAnalyzer()
        data class T(val name: String, val bpm: Double, val pc: Int, val minor: Boolean, val seed: Int, val offset: Double)
        val tracks = listOf(T("a", 124.0, 9, true, 1, 0.37), T("b", 126.0, 4, true, 2, 0.21), T("c", 100.0, 6, false, 3, 0.5))
        val analyses = tracks.associate { t ->
            val hi = SynthTrack.render(t.bpm, sr = 44100, offsetSec = t.offset, rootPc = t.pc, minor = t.minor, seed = t.seed)
            writePcm(File(dir, "${t.name}.f32"), hi)
            val lo = SynthTrack.render(t.bpm, sr = 22050, offsetSec = t.offset, rootPc = t.pc, minor = t.minor, seed = t.seed)
            t.name to analyzer.analyze(lo, 22050)
        }
        File(dir, "tracks.txt").printWriter().use { w ->
            for (t in tracks) {
                val a = analyses.getValue(t.name)
                w.println("${t.name} ${a.bpm} ${a.grid.firstBeatSec} ${a.grid.downbeatPhase} ${a.durationSec} ${t.offset} ${t.bpm}")
            }
        }
        val planner = TransitionPlanner(Random(11))
        val cases = listOf(
            Triple("a", "b", TransitionStyle.BASS_SWAP), Triple("a", "b", TransitionStyle.ECHO_OUT),
            Triple("a", "b", TransitionStyle.LOOP_ROLL_DROP), Triple("a", "b", TransitionStyle.FILTER_SWEEP),
            Triple("a", "c", TransitionStyle.DRUM_BRIDGE), Triple("a", "b", TransitionStyle.CUT),
        )
        File(dir, "plans.txt").printWriter().use { w ->
            for ((from, to, style) in cases) {
                val A = analyses.getValue(from); val B = analyses.getValue(to)
                val p = planner.plan(A, SourceCapabilities.LOCAL, 20.0, B, SourceCapabilities.LOCAL, A.bpm, PartyMode.AUTO.profile(),
                    TransitionRequest(style = style, drama = 0.8f), 0, 1)
                w.println("PLAN ${p.style} $from $to ${p.startBeatOut} ${p.inCueSec} ${p.lengthBeats} ${p.handoverBeat} ${p.keyShift} ${if (p.syncIn) 1 else 0} ${p.inSyncBpm}")
                for (r in p.ramps) w.println("R ${r.param.id} ${if (r.param.perDeck) r.deck else -1} ${r.startBeat} ${r.endBeat} ${r.from ?: "nan"} ${r.to} ${r.curve.id}")
                for (a in p.actions) w.println("A ${a.type.id} ${a.deck} ${a.beat} ${a.arg0} ${a.arg1}")
                w.println("END")
                println("exported ${p.style}: ${p.description}")
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = DataOutputStream::class
    }
}
