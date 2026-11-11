package com.aidj.app.stage

import com.aidj.app.engine.HandTask
import com.aidj.core.model.SectionType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything the rig needs from the music this frame (all derived from real engine state/analysis). */
data class RigInput(
    val beat: Double,            // audible master beat (latency-compensated)
    val bpm: Double,
    val playing: Boolean,
    val energy: Float,           // 0..1
    val section: SectionType?,
    val sectionProgress: Float,  // 0..1 within current section
    val bass: Float,             // live low-band level 0..1
    val hands: HandTask,
    val mixer: Console.State,
    val currentDeck: Int,
    val mixing: Boolean,
)

/** Console geometry shared by the console renderer and the rig's IK targets (DJ faces +Z, console in front). */
object Console {
    const val TOP = 0.985f
    const val Z = 0.47f
    data class State(
        val eq: Array<FloatArray> = arrayOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f)),
        val filter: FloatArray = floatArrayOf(0f, 0f),
        val fader: FloatArray = floatArrayOf(1f, 1f),
        val crossfader: Float = 0f,
        val jogAngle: FloatArray = floatArrayOf(0f, 0f),
        val playing: BooleanArray = booleanArrayOf(false, false),
        val fx: FloatArray = floatArrayOf(0f, 0f),
    )
    /** Deck 0 sits on the DJ's left (+X), deck 1 on the right (-X). */
    fun deckSide(deck: Int) = if (deck == 0) 1f else -1f
    fun jog(deck: Int) = V3(0.36f * deckSide(deck), TOP + 0.03f, Z)
    fun eqKnob(deck: Int, band: Int) = V3(0.065f * deckSide(deck), TOP + 0.02f, Z - 0.075f + band * 0.045f) // band 0 high .. 2 low
    fun filterKnob(deck: Int) = V3(0.065f * deckSide(deck), TOP + 0.02f, Z + 0.07f)
    fun fader(deck: Int, value: Float) = V3(0.065f * deckSide(deck), TOP + 0.015f, Z + 0.16f - value * 0.06f)
    fun crossfader(v: Float) = V3(-v * 0.05f, TOP + 0.015f, Z + 0.2f)
    fun fxButton(deck: Int, i: Int) = V3((0.2f + i * 0.03f) * deckSide(deck), TOP + 0.012f, Z + 0.15f)
    fun loopButton(deck: Int, i: Int) = V3((0.3f + (i % 4) * 0.035f) * deckSide(deck), TOP + 0.012f, Z + 0.17f + (i / 4) * 0.035f)
}

/**
 * Procedural performer. Joint positions are computed each frame from layered behaviours:
 *  groove (beat-locked bounce/sway/nod scaled by energy and section), work (hands on real controls via IK,
 *  chosen from what the engine is doing), gestures (drop celebrations, crowd interaction) and look-at.
 * Layers are blended with critically-damped springs so nothing snaps.
 */
class DjRig(var style: Int = 0) {
    // Output joints (world space) + head orientation for rendering.
    var pelvis = V3(0f, 1f, 0f); var chestTop = V3(0f, 1.45f, 0f); var neck = V3(0f, 1.5f, 0f); var headCenter = V3(0f, 1.62f, 0f)
    var shoulderL = V3.ZERO; var shoulderR = V3.ZERO; var elbowL = V3.ZERO; var elbowR = V3.ZERO; var handL = V3.ZERO; var handR = V3.ZERO
    var hipL = V3.ZERO; var hipR = V3.ZERO; var kneeL = V3.ZERO; var kneeR = V3.ZERO; var footL = V3(0.13f, 0.05f, 0.02f); var footR = V3(-0.13f, 0.05f, 0.02f)
    var chestMatrix = M.identity(); var headMatrix = M.identity()
    var mouthOpen = 0f; var smile = 0.5f; var eyesClosed = 0f
    var headphonesOnEar = 0f

    private val handLSpring = Spring3(V3(0.2f, 1.05f, 0.4f), 90f)
    private val handRSpring = Spring3(V3(-0.2f, 1.05f, 0.4f), 90f)
    private val lookSpring = Spring3(V3(0f, 1f, 0.6f), 25f)
    private val bounceAmp = Spring1(0f, 12f)
    private val excitement = Spring1(0f, 6f)
    private val gestureW = Spring1(0f, 30f)
    private val cueW = Spring1(0f, 20f)
    private val leanSpring = Spring1(0f, 15f)

    private var gesture: String? = null
    private var gestureStart = 0.0
    private var gestureBeats = 4.0
    private var idleTask = 0
    private var idleTaskBar = -1L
    private var lastCrowdBar = 0L
    private var blinkT = 0f

    fun trigger(move: String, beat: Double, beats: Double = 4.0) { gesture = move; gestureStart = beat; gestureBeats = beats }

    fun update(inp: RigInput, dt: Float, time: Float) {
        val b = inp.beat
        val phase = (b - floor(b)).toFloat()
        val bar = floor(b / 4.0).toLong()
        val e = if (inp.playing) inp.energy else 0f
        // Section-dependent intensity: buildups ramp up, drops explode, breakdowns breathe.
        val sectionBoost = when (inp.section) {
            SectionType.DROP -> 0.35f
            SectionType.BUILDUP -> 0.1f + 0.35f * inp.sectionProgress
            SectionType.BREAKDOWN -> -0.25f
            SectionType.INTRO, SectionType.OUTRO -> -0.1f
            else -> 0f
        }
        val ex = excitement.update((e + sectionBoost).coerceIn(0f, 1.2f), dt)
        val amp = bounceAmp.update(if (inp.playing) 0.012f + 0.05f * ex else 0.004f, dt)

        // Auto crowd interaction during high-energy grooves (not while mixing).
        if (inp.playing && !inp.mixing && gesture == null && ex > 0.7f && bar - lastCrowdBar >= 16 && (bar % 16L) == 0L) {
            lastCrowdBar = bar
            trigger(if ((bar / 16) % 2L == 0L) "point_crowd" else "hands_up", b, 4.0)
        }
        // Gesture lifetime.
        val gT = if (gesture != null) ((b - gestureStart) / gestureBeats).toFloat() else 2f
        if (gT >= 1f) gesture = null
        val gw = gestureW.update(if (gesture != null && gT in 0f..0.9f) 1f else 0f, dt)

        // ---------- body (FK)
        val beatPulse = (0.5f - 0.5f * cos(2f * PI.toFloat() * phase))       // smooth bounce
        val kick = exp(-phase * 7f)                                           // sharp on-beat accent
        val styleSway = when (style) { 1 -> 0.6f; 2 -> 0.35f; else -> 1f }
        val pelvisY = 1.0f - amp * (0.6f * beatPulse + 0.4f * kick) - 0.02f
        val sway = sin(PI.toFloat() * b.toFloat() / 2f) * (0.015f + 0.04f * ex) * styleSway
        val twist = sin(PI.toFloat() * b.toFloat() / 4f) * 0.08f * ex * styleSway
        val workLean = leanSpring.update(if (inp.hands != HandTask.NONE || inp.mixing) 0.22f else 0.12f + 0.05f * ex, dt)
        pelvis = V3(sway * 0.6f, pelvisY, -0.02f)
        var cm = M.translate(M.identity(), pelvis.x, pelvis.y, pelvis.z)
        cm = M.rotate(cm, Math.toDegrees(twist.toDouble()).toFloat(), 0f, 1f, 0f)
        cm = M.rotate(cm, Math.toDegrees(sway * 2.5).toFloat(), 0f, 0f, 1f)
        cm = M.rotate(cm, Math.toDegrees((workLean + 0.04f * kick * ex).toDouble()).toFloat(), 1f, 0f, 0f)
        chestMatrix = cm
        chestTop = M.point(cm, V3(0f, 0.46f, 0f))
        neck = M.point(cm, V3(0f, 0.5f, 0f))
        val shrug = 0.02f * kick * ex
        shoulderL = M.point(cm, V3(0.19f, 0.43f + shrug, 0f))
        shoulderR = M.point(cm, V3(-0.19f, 0.43f + shrug, 0f))
        hipL = M.point(M.translate(M.identity(), pelvis.x, pelvis.y, pelvis.z), V3(0.1f, -0.02f, 0f))
        hipR = M.point(M.translate(M.identity(), pelvis.x, pelvis.y, pelvis.z), V3(-0.1f, -0.02f, 0f))

        // ---------- hands: work targets from what the engine is doing
        val d = inp.currentDeck; val other = 1 - d
        val mx = inp.mixer
        val tapping = if (phase < 0.15f) -0.012f else 0f
        var tL: V3; var tR: V3
        var lookAt = V3(0f, 0.98f, 0.5f)
        var cue = 0f
        when (inp.hands) {
            HandTask.EQ_KNOBS -> { tL = Console.eqKnob(0, 2); tR = Console.eqKnob(1, 2) }
            HandTask.FILTER_KNOB -> { tR = Console.filterKnob(other); tL = Console.filterKnob(d) }
            HandTask.FADERS -> { tL = Console.fader(0, mx.fader[0]); tR = Console.fader(1, mx.fader[1]) }
            HandTask.JOG_NUDGE -> {
                val a = time * 3f
                tL = Console.jog(0) + V3(cos(a) * 0.07f, 0.01f, sin(a) * 0.07f); tR = Console.jog(1) + V3(0f, 0.01f, 0.05f)
            }
            HandTask.FX_BUTTONS -> { tL = Console.fxButton(0, (bar % 3).toInt()) + V3(0f, tapping, 0f); tR = Console.fxButton(1, ((bar + 1) % 3).toInt()) + V3(0f, tapping, 0f) }
            HandTask.LOOP_BUTTONS -> { tL = Console.loopButton(0, (b.toLong() % 8).toInt()) + V3(0f, tapping, 0f); tR = Console.loopButton(1, 2) }
            HandTask.CUE_HEADPHONES -> {
                cue = 1f
                tL = M.point(headMatrix, V3(0.11f, 0.0f, 0.0f)) // hand holding the ear cup
                tR = Console.eqKnob(other, 1)
                lookAt = Console.jog(other) + V3(0f, 0f, 0.2f)
            }
            HandTask.NONE -> {
                // "Busy" idle: every 2 bars pick a small job — tweak a knob, touch a jog, hover over the mixer.
                if (bar / 2 != idleTaskBar) { idleTaskBar = bar / 2; idleTask = ((bar * 7919L) % 6).toInt() }
                when (idleTask) {
                    0 -> { tL = Console.eqKnob(d, 1); tR = V3(-0.25f, Console.TOP + 0.06f, Console.Z + 0.1f) }
                    1 -> { tL = V3(0.25f, Console.TOP + 0.06f, Console.Z + 0.12f); tR = Console.filterKnob(d) }
                    2 -> { tL = Console.jog(0) + V3(0f, 0.02f, 0.06f); tR = Console.jog(1) + V3(0f, 0.02f, 0.06f) }
                    3 -> { tL = Console.fader(d, mx.fader[d]); tR = V3(-0.22f, Console.TOP + 0.05f, Console.Z + 0.08f) }
                    4 -> { tL = V3(0.28f, Console.TOP + 0.08f + 0.03f * kick * ex, Console.Z + 0.05f); tR = V3(-0.28f, Console.TOP + 0.08f + 0.03f * kick * ex, Console.Z + 0.05f) }
                    else -> { tL = Console.eqKnob(0, 0); tR = Console.eqKnob(1, 0) }
                }
                if (!inp.playing) { tL = V3(0.22f, Console.TOP + 0.03f, Console.Z + 0.12f); tR = V3(-0.22f, Console.TOP + 0.03f, Console.Z + 0.12f) }
            }
        }
        // Knob twisting/hovering micro-motion while working.
        val micro = V3(sin(time * 5.3f) * 0.006f, (0.008f + 0.01f * ex) * kick, cos(time * 4.1f) * 0.004f)
        tL += micro; tR += micro * -1f

        // ---------- gestures (blend over work targets)
        if (gesture != null) {
            val fp = 0.12f * (1f - phase)   // fist pump on the beat
            val (gL, gR, look) = when (gesture) {
                "hands_up", "celebrate" -> Triple(V3(0.25f, 2.05f + 0.05f * kick, 0.15f), V3(-0.25f, 2.05f + 0.05f * kick, 0.15f), V3(0f, 1.9f, 2f))
                "point_crowd" -> Triple(tL, V3(-0.3f, 1.65f, 0.75f), V3(-0.6f, 1.4f, 3f))
                "wave" -> Triple(tL, V3(-0.35f + sin(time * 6f) * 0.15f, 1.95f, 0.2f), V3(0f, 1.5f, 3f))
                "headbang" -> Triple(tL, tR, V3(0f, 0.6f, 0.8f))
                "mix_focus" -> Triple(Console.eqKnob(0, 2), Console.eqKnob(1, 2), V3(0f, 0.9f, 0.45f))
                "chill_sway" -> Triple(V3(0.24f, Console.TOP + 0.05f, 0.4f), V3(-0.24f, Console.TOP + 0.05f, 0.4f), V3(0f, 1.6f, 2f))
                "hype_crowd" -> Triple(V3(0.15f, 1.75f + fp, 0.45f), V3(-0.3f, 1.95f + fp, 0.3f), V3(0f, 1.7f, 3f))
                else -> Triple(tL, tR, lookAt)
            }
            tL = tL.lerp(gL, gw); tR = tR.lerp(gR, gw); lookAt = lookAt.lerp(look, gw)
        }
        // Drops: fist pump with the free hand on top of the work pose.
        if (inp.section == SectionType.DROP && ex > 0.8f && gesture == null && inp.hands == HandTask.NONE && (bar % 4L) >= 2L) {
            tR = V3(-0.22f, 1.75f + 0.14f * (1f - phase), 0.35f)
            lookAt = V3(0f, 1.6f, 3f)
        }

        val cw = cueW.update(cue, dt)
        headphonesOnEar = cw
        handL = handLSpring.update(tL, dt)
        handR = handRSpring.update(tR, dt)

        // ---------- head
        val look = lookSpring.update(lookAt, dt)
        val headBase = M.point(cm, V3(0f, 0.58f, 0.0f))
        val dir = (look - headBase).norm()
        val yaw = Math.toDegrees(kotlin.math.atan2(dir.x, dir.z).toDouble()).toFloat().coerceIn(-50f, 50f)
        val pitch = Math.toDegrees(-kotlin.math.asin(dir.y.coerceIn(-1f, 1f)).toDouble()).toFloat().coerceIn(-35f, 50f)
        val headbang = if (gesture == "headbang" || (inp.section == SectionType.DROP && ex > 0.9f && style == 0)) 1f else 0f
        val nod = (6f + 14f * ex + 18f * headbang) * kick * if (inp.playing) 1f else 0f
        var hm = M.translate(M.identity(), headBase.x, headBase.y, headBase.z)
        hm = M.rotate(hm, yaw, 0f, 1f, 0f)
        hm = M.rotate(hm, pitch + nod, 1f, 0f, 0f)
        hm = M.rotate(hm, 8f * cw + sway * 40f, 0f, 0f, 1f) // tilt into the headphone when cueing
        headMatrix = hm
        headCenter = M.point(hm, V3(0f, 0.09f, 0f))

        // ---------- arm IK (two-bone, elbows out and slightly back)
        val upper = 0.29f; val fore = 0.27f
        elbowL = solveIk(shoulderL, handL, upper, fore, V3(1f, -0.6f, -0.6f)).also { handL = clampReach(shoulderL, handL, upper + fore) }
        elbowR = solveIk(shoulderR, handR, upper, fore, V3(-1f, -0.6f, -0.6f)).also { handR = clampReach(shoulderR, handR, upper + fore) }

        // ---------- legs: feet planted, knees bend with the bounce
        kneeL = solveIk(hipL, footL, 0.46f, 0.46f, V3(0f, 0f, 1f))
        kneeR = solveIk(hipR, footR, 0.46f, 0.46f, V3(0f, 0f, 1f))

        // ---------- face
        blinkT -= dt
        if (blinkT < -0.15f) blinkT = 2f + (time * 13.7f % 3f)
        eyesClosed = if (blinkT < 0f) 1f else if (inp.section == SectionType.BREAKDOWN && ex < 0.4f) 0.6f else 0f
        mouthOpen = min(1f, gw * (if (gesture == "hands_up" || gesture == "celebrate" || gesture == "hype_crowd") 1f else 0.2f) + if (inp.section == SectionType.DROP) 0.3f * kick else 0f)
        smile = (0.3f + 0.7f * ex).coerceIn(0f, 1f)
    }

    private fun clampReach(s: V3, t: V3, reach: Float): V3 {
        val d = t - s
        val l = d.len()
        return if (l > reach * 0.999f) s + d * (reach * 0.999f / l) else t
    }

    companion object {
        /** Analytic two-bone IK: returns the middle joint position. */
        fun solveIk(root: V3, target: V3, a: Float, b: Float, pole: V3): V3 {
            val toT = target - root
            val d = toT.len().coerceIn(abs(a - b) + 1e-3f, a + b - 1e-3f)
            val dir = toT.norm()
            val cosA = ((a * a + d * d - b * b) / (2f * a * d)).coerceIn(-1f, 1f)
            val ang = acos(cosA)
            // Component of the pole perpendicular to the root->target direction.
            var perp = pole - dir * pole.dot(dir)
            if (perp.len() < 1e-4f) perp = V3(0f, 0f, 1f).let { it - dir * it.dot(dir) }
            perp = perp.norm()
            return root + dir * (a * cos(ang)) + perp * (a * sin(ang))
        }
    }

    @Suppress("unused") private fun unused() = max(0, sqrt(1.0).toInt())
}
