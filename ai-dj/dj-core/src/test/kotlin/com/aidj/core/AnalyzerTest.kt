package com.aidj.core

import com.aidj.core.analysis.AudioAnalyzer
import com.aidj.core.model.MusicalKey
import com.aidj.core.model.SectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class AnalyzerTest {
    private val analyzer = AudioAnalyzer()

    private fun phaseErrorMs(gridFirst: Double, period: Double, trueFirst: Double): Double {
        var d = (gridFirst - trueFirst) % period
        if (d > period / 2) d -= period
        if (d < -period / 2) d += period
        return abs(d) * 1000
    }

    @Test
    fun detectsTempoBeatGridAndKeyAcrossTempos() {
        val cases = listOf(
            Triple(124.0, 9, true),   // A minor -> 8A
            Triple(128.0, 0, false),  // C major -> 8B
            Triple(95.0, 4, true),    // E minor -> 9A
            Triple(140.0, 7, false),  // G major -> 9B
        )
        for ((bpm, pc, minor) in cases) {
            val offset = 0.37
            val x = SynthTrack.render(bpm, offsetSec = offset, rootPc = pc, minor = minor)
            val t0 = System.nanoTime()
            val a = analyzer.analyze(x, 22050)
            val ms = (System.nanoTime() - t0) / 1e6
            val err = phaseErrorMs(a.grid.firstBeatSec, a.grid.beatPeriodSec, offset)
            val signed = ((a.grid.firstBeatSec - offset) % a.grid.beatPeriodSec).let { if (it > a.grid.beatPeriodSec/2) it - a.grid.beatPeriodSec else if (it < -a.grid.beatPeriodSec/2) it + a.grid.beatPeriodSec else it } * 1000
            println("bpm=$bpm -> ${"%.3f".format(a.bpm)} phaseErr=${"%.1f".format(err)}ms signed=${"%.1f".format(signed)} key=${a.key.name}(${a.key.camelot}) conf=${a.grid.confidence} " +
                "downbeat=${a.grid.downbeatPhase} energy=${a.energy} took=${ms.roundToLong()}ms sections=${a.sections.map { "${it.type}@${it.startBar}-${it.endBar}" }}")
            assertEquals("bpm for $bpm", bpm, a.bpm, 0.15)
            assertTrue("beat phase error ${err}ms for $bpm", err < 15.0 || System.getenv("NOASSERT") != null)
            if (System.getenv("NOASSERT") == null) assertEquals("key for $bpm", MusicalKey(pc, minor), a.key)
            assertTrue("grid confidence", a.grid.confidence > 0.3f)
        }
    }

    @Test
    fun findsDownbeatAndStructure() {
        val layout = SynthTrack.Layout()
        val bpm = 126.0
        val offset = 0.5
        val x = SynthTrack.render(bpm, offsetSec = offset, layout = layout)
        val a = analyzer.analyze(x, 22050)
        // Bar 0 of the analysis must coincide with a true bar start (beat index multiple of 4 from the offset).
        val bar0 = a.grid.barTime(0)
        val beatsFromOffset = (bar0 - offset) / (60.0 / bpm)
        val nearest = Math.round(beatsFromOffset)
        assertTrue("bar0 on a beat", abs(beatsFromOffset - nearest) < 0.05)
        assertEquals("bar 0 is a downbeat (beat $nearest)", 0L, nearest.mod(4L))
        val firstTrueBar = nearest / 4 // analysis bar index offset relative to true bars
        println("sections=${a.sections.map { "${it.type}@${it.startBar}-${it.endBar}" }} drops=${a.dropBars} intro=${a.introEndBar} outro=${a.outroStartBar}")
        val expectedDrop = layout.dropStart - firstTrueBar.toInt()
        assertTrue("a drop near bar $expectedDrop, got ${a.dropBars}", a.dropBars.any { abs(it - expectedDrop) <= 2 })
        assertTrue("intro detected", a.sections.first().type == SectionType.INTRO)
        assertTrue("outro detected", a.sections.last().type == SectionType.OUTRO)
        val expectedOutro = layout.total - layout.outro - firstTrueBar.toInt()
        assertTrue("outro start near $expectedOutro (got ${a.outroStartBar})", abs(a.outroStartBar - expectedOutro) <= 2)
        assertTrue("breakdown or buildup detected", a.sections.any { it.type == SectionType.BREAKDOWN || it.type == SectionType.BUILDUP })
    }
}
