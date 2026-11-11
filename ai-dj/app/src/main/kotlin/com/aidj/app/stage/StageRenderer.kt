package com.aidj.app.stage

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.aidj.app.data.AvatarConfig
import com.aidj.app.data.GraphicsQuality
import com.aidj.app.data.LightingStyle
import com.aidj.app.data.StageTheme
import com.aidj.app.engine.DjEvent
import com.aidj.app.engine.LiveState
import com.aidj.app.engine.Phase
import com.aidj.core.model.SectionType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Inputs pushed from the app (thread-safe; read on the GL thread every frame). */
class StageInputs {
    @Volatile var live: LiveState = LiveState()
    @Volatile var latencySec: Double = 0.03
    @Volatile var avatar: AvatarConfig = AvatarConfig()
    @Volatile var theme: StageTheme = StageTheme.NEON_CLUB
    @Volatile var lighting: LightingStyle = LightingStyle.BEAT_SYNC
    @Volatile var quality: GraphicsQuality = GraphicsQuality.AUTO
    @Volatile var powerSave: Boolean = false
    val events = ConcurrentLinkedQueue<DjEvent>()
    /** Measured frame time (ms), for the AUTO quality governor and diagnostics. */
    @Volatile var frameMs: Float = 16f
    @Volatile var effectiveQuality: GraphicsQuality = GraphicsQuality.MEDIUM
}

class StageRenderer(private val inp: StageInputs) : GLSurfaceView.Renderer {
    private lateinit var lit: Program
    private lateinit var beam: Program
    private lateinit var wall: Program
    private lateinit var floor: Program
    private lateinit var particle: Program
    private lateinit var box: Mesh
    private lateinit var sphere: Mesh
    private lateinit var cyl: Mesh
    private lateinit var capsule: Mesh
    private lateinit var cone: Mesh
    private lateinit var quad: Mesh
    private val rig = DjRig()
    private val vp = FloatArray(16); private val view = FloatArray(16); private val proj = FloatArray(16)
    private var cam = V3(0f, 1.6f, 3.2f)
    private var aspect = 1f
    private var lastT = SystemClock.elapsedRealtimeNanos()
    private var time = 0f
    private val rnd = Random(7)

    // Lights (moving heads): position, colour, aim.
    private val lightPos = FloatArray(18); private val lightCol = FloatArray(18); private val lightDir = FloatArray(18)
    private val headAim = Array(6) { Spring3(V3(0f, 0f, 1.5f), 8f) }
    private var lastBarSeen = -1L
    private var dropFlash = 0f
    private var bassPulse = 0f
    private var shake = 0f
    private var jogAngle = floatArrayOf(0f, 0f)
    private var autoQ = GraphicsQuality.MEDIUM
    private var slowFrames = 0; private var fastFrames = 0

    // Particles
    private val maxParticles = 900
    private val pPos = FloatArray(maxParticles * 3); private val pVel = FloatArray(maxParticles * 3); private val pCol = FloatArray(maxParticles * 3)
    private val pLife = FloatArray(maxParticles)
    private var pVao = IntArray(1); private var pVbo = IntArray(2)
    private val pBufPos = ByteBuffer.allocateDirect(maxParticles * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pBufCol = ByteBuffer.allocateDirect(maxParticles * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val crowd = List(14) { i -> V3(-2.4f + (i % 7) * 0.8f + rnd.nextFloat() * 0.3f, 0f, 2.0f + (i / 7) * 0.55f + rnd.nextFloat() * 0.2f) }
    private val crowdPhase = FloatArray(14) { rnd.nextFloat() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lit = Program(Shaders.LIT_VS, Shaders.LIT_FS)
        beam = Program(Shaders.BEAM_VS, Shaders.BEAM_FS)
        wall = Program(Shaders.LIT_VS, Shaders.WALL_FS)
        floor = Program(Shaders.LIT_VS, Shaders.FLOOR_FS)
        particle = Program(Shaders.PARTICLE_VS, Shaders.PARTICLE_FS)
        box = Mesh.box(); sphere = Mesh.sphere(); cyl = Mesh.cylinder(); capsule = Mesh.capsule(); cone = Mesh.cone(); quad = Mesh.quad()
        GLES30.glGenVertexArrays(1, pVao, 0); GLES30.glGenBuffers(2, pVbo, 0)
        GLES30.glBindVertexArray(pVao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pVbo[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * 12, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pVbo[1]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * 12, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        lastT = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
    }

    // ------------------------------------------------------------------ theme

    private fun palette(t: StageTheme): Pair<V3, V3> = when (t) {
        StageTheme.NEON_CLUB -> V3(0.0f, 0.9f, 1.0f) to V3(1.0f, 0.18f, 0.6f)
        StageTheme.SUNSET_FESTIVAL -> V3(1.0f, 0.55f, 0.1f) to V3(0.9f, 0.1f, 0.4f)
        StageTheme.ROOFTOP -> V3(0.35f, 0.4f, 1.0f) to V3(0.6f, 0.2f, 1.0f)
        StageTheme.WAREHOUSE -> V3(1.0f, 0.9f, 0.7f) to V3(0.2f, 1.0f, 0.4f)
    }

    private val skinTones = arrayOf(V3(0.98f, 0.82f, 0.7f), V3(0.87f, 0.68f, 0.52f), V3(0.68f, 0.47f, 0.33f), V3(0.49f, 0.32f, 0.22f), V3(0.33f, 0.21f, 0.15f))
    private val outfits = arrayOf(V3(0.08f, 0.08f, 0.1f) to V3(0.0f, 0.85f, 1.0f), V3(0.9f, 0.9f, 0.92f) to V3(0.1f, 0.1f, 0.12f),
        V3(0.5f, 0.05f, 0.25f) to V3(1f, 0.85f, 0.2f), V3(0.08f, 0.25f, 0.12f) to V3(0.9f, 0.4f, 0.1f))
    private val phoneColors = arrayOf(V3(0.05f, 0.05f, 0.06f), V3(0.95f, 0.95f, 0.95f), V3(0.9f, 0.1f, 0.2f), V3(0.0f, 0.7f, 1f))

    // ------------------------------------------------------------------ frame

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = ((now - lastT) / 1e9f).coerceIn(0.001f, 0.1f)
        lastT = now
        time += dt
        val frameStart = SystemClock.elapsedRealtimeNanos()
        val live = inp.live
        val quality = qualityLevel()
        val (colA, colB) = palette(inp.theme)

        // Audible beat: engine beat minus output latency, so visuals line up with what you hear.
        val bpm = if (live.masterBpm > 0) live.masterBpm else 120.0
        val playing = live.phase == Phase.PLAYING || live.phase == Phase.MIXING
        val beat = live.masterBeat - inp.latencySec * bpm / 60.0
        val phase = (beat - floor(beat)).toFloat()
        val bar = floor(beat / 4.0).toLong()
        bassPulse = max(bassPulse * exp(-dt * 8f), (live.engine.low * 5f).coerceIn(0f, 1f))

        // Events
        while (true) {
            val e = inp.events.poll() ?: break
            when (e) {
                is DjEvent.DropStarted -> { dropFlash = 1f; shake = 0.6f; burst(if (quality == GraphicsQuality.HIGH) 400 else 160, colA, colB); rig.trigger("celebrate", beat, 4.0) }
                is DjEvent.BuildupStarted -> rig.trigger("hype_crowd", beat, 4.0)
                is DjEvent.TransitionStarted -> rig.trigger("mix_focus", beat, 2.0)
                is DjEvent.Gesture -> rig.trigger(e.move, beat, 4.0 + 4.0 * e.intensity)
                is DjEvent.TrackChanged -> if (live.phase != Phase.IDLE) burst(60, colA, colB)
                else -> {}
            }
        }
        dropFlash *= exp(-dt * 2.5f)
        shake *= exp(-dt * 3f)

        // Rig
        val st = live.engine
        for (d in 0..1) {
            val ds = st.decks.getOrNull(d)
            if (ds != null && ds.playing) jogAngle[d] += (ds.rate * dt * 360.0 * 33.3 / 60.0).toFloat()
        }
        val mixer = Console.State(
            eq = Array(2) { d -> st.decks.getOrNull(d)?.mixer?.let { floatArrayOf(it[2], it[1], it[0]) } ?: floatArrayOf(1f, 1f, 1f) },
            filter = FloatArray(2) { d -> st.decks.getOrNull(d)?.mixer?.get(3) ?: 0f },
            fader = FloatArray(2) { d -> st.decks.getOrNull(d)?.mixer?.get(7) ?: 1f },
            crossfader = st.crossfader, jogAngle = jogAngle.copyOf(),
            playing = BooleanArray(2) { d -> st.decks.getOrNull(d)?.playing == true },
            fx = FloatArray(2) { d -> st.decks.getOrNull(d)?.mixer?.let { max(it[4], max(it[5], it[6])) } ?: 0f },
        )
        rig.style = inp.avatar.style
        val secProg = if (live.sectionBars > 0) (live.barInSection.toFloat() / live.sectionBars) else 0f
        rig.update(RigInput(beat, bpm, playing, live.energy, live.section, secProg, (live.engine.low * 4f).coerceIn(0f, 1f), live.handTask, mixer, live.currentDeck, live.phase == Phase.MIXING), dt, time)

        // Camera: slow orbit, dolly in on buildups, shake on drops.
        val build = if (live.section == SectionType.BUILDUP) secProg else 0f
        val orbit = 0.32f * sin(time * 0.07f) + 0.1f * sin(time * 0.023f)
        val dist = 3.3f - 0.6f * build + 0.25f * dropFlash
        val sx = (rnd.nextFloat() - 0.5f) * 0.05f * shake; val sy = (rnd.nextFloat() - 0.5f) * 0.05f * shake
        cam = V3(sin(orbit) * dist + sx, 1.62f + 0.12f * sin(time * 0.05f) + sy, cos(orbit) * dist)
        Matrix.perspectiveM(proj, 0, 42f - 6f * build, aspect, 0.1f, 40f)
        Matrix.setLookAtM(view, 0, cam.x, cam.y, cam.z, 0f, 1.3f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // Moving heads: re-aim on bars (beat-synced style) or sweep (smooth/lasers).
        val nLights = when (quality) { GraphicsQuality.LOW -> 2; GraphicsQuality.MEDIUM -> 4; else -> 6 }
        if (bar != lastBarSeen) {
            lastBarSeen = bar
            for (i in 0 until 6) headAim[i].pos = headAim[i].pos // keep continuity
        }
        for (i in 0 until 6) {
            val x = -2.5f + i * 1.0f
            val p = V3(x, 3.4f, -0.8f)
            val target = when (inp.lighting) {
                LightingStyle.BEAT_SYNC -> {
                    val k = ((bar + i) % 4).toInt()
                    when (k) { 0 -> V3(0f, 1.2f, 0.2f); 1 -> V3(x * 0.6f, 0f, 2.4f); 2 -> V3(-x, 0f, 1.5f); else -> V3(sin(i.toFloat()) * 2f, 2.5f, 4f) }
                }
                LightingStyle.SMOOTH -> V3(sin(time * 0.3f + i) * 2.5f, 0.2f, 1.8f + cos(time * 0.25f + i) * 1.2f)
                LightingStyle.LASERS -> V3(sin(time * 1.4f + i * 0.9f) * 4f, 1.4f + sin(time * 0.9f + i) * 0.8f, 5f)
            }
            val aim = headAim[i].update(target, dt)
            val dir = (aim - p).norm()
            val hueMix = ((sin(time * 0.2f + i * 0.7f) * 0.5f + 0.5f))
            val c = colA.lerp(colB, hueMix)
            val inten = if (playing) (0.55f + 0.9f * bassPulse * live.energy + 1.5f * dropFlash) else 0.35f
            lightPos[i * 3] = p.x; lightPos[i * 3 + 1] = p.y; lightPos[i * 3 + 2] = p.z
            lightDir[i * 3] = dir.x; lightDir[i * 3 + 1] = dir.y; lightDir[i * 3 + 2] = dir.z
            lightCol[i * 3] = c.x * inten * 3f; lightCol[i * 3 + 1] = c.y * inten * 3f; lightCol[i * 3 + 2] = c.z * inten * 3f
        }
        // Two fixed warm key lights on the DJ (index 0..1 replaced with front spots for readability).
        setLight(0, V3(1.2f, 3.0f, 2.6f), V3(0f, 1.5f, 0f), V3(1.0f, 0.92f, 0.85f) * 2.2f)
        setLight(1, V3(-1.6f, 2.6f, 2.2f), V3(0f, 1.4f, 0f), colB * (1.2f + bassPulse))

        GLES30.glClearColor(0.01f, 0.008f, 0.02f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)

        // Floor
        floor.use(); floor.mat4("uVP", vp)
        drawWith(floor, M.mul(M.rotate(M.identity(), -90f, 1f, 0f, 0f), M.scale(M.identity(), 14f, 14f, 1f)))
        floor.float("uBeat", beat.toFloat()); floor.float("uBass", bassPulse * if (playing) 1f else 0.2f)
        floor.vec3("uA", colA.x, colA.y, colA.z); floor.vec3("uB", colB.x, colB.y, colB.z); floor.vec3("uCam", cam.x, cam.y, cam.z); floor.float("uTime", time)
        quad.draw()

        // LED wall
        wall.use(); wall.mat4("uVP", vp)
        val wallM = M.scale(M.translate(M.identity(), 0f, 1.9f, -1.7f), 6.4f, 3.4f, 1f)
        drawWith(wall, wallM)
        val bands = FloatArray(16) { live.engine.bands.getOrElse(it) { 0f } * if (playing) 1f else 0.1f }
        wall.floats("uBands", bands); wall.float("uBeat", beat.toFloat()); wall.float("uTime", time)
        wall.float("uEnergy", live.energy); wall.float("uDrop", dropFlash * if (inp.lighting == LightingStyle.LASERS || live.section == SectionType.DROP) 1f else 0.4f)
        wall.vec3("uA", colA.x, colA.y, colA.z); wall.vec3("uB", colB.x, colB.y, colB.z)
        GLES30.glUniform2f(wall.loc("uSize"), 6.4f, 3.4f); wall.vec3("uCenter", 0f, 1.9f, -1.7f); wall.int("uPattern", 0)
        quad.draw()

        // Lit geometry
        lit.use()
        lit.mat4("uVP", vp); lit.vec3("uCam", cam.x, cam.y, cam.z)
        lit.vec3("uAmbient", 0.08f + colA.x * 0.04f, 0.07f + colA.y * 0.04f, 0.12f + colA.z * 0.05f)
        lit.vec3("uRim", colA.x * 0.5f + colB.x * 0.3f, colA.y * 0.5f + colB.y * 0.3f, colA.z * 0.5f + colB.z * 0.3f)
        GLES30.glUniform1i(lit.loc("uLights"), nLights)
        GLES30.glUniform3fv(lit.loc("uLightPos"), 6, lightPos, 0)
        GLES30.glUniform3fv(lit.loc("uLightCol"), 6, lightCol, 0)
        GLES30.glUniform3fv(lit.loc("uLightDir"), 6, lightDir, 0)
        lit.float("uFog", 0.035f)

        drawTruss(colA)
        drawConsole(mixer, colA, colB, beat, playing, live)
        drawDj(colA)
        if (quality != GraphicsQuality.LOW) drawCrowd(beat, live.energy, playing)

        // Additive beams + particles
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        beam.use(); beam.mat4("uVP", vp); beam.vec3("uCam", cam.x, cam.y, cam.z)
        val beamCount = when (quality) { GraphicsQuality.LOW -> 2; GraphicsQuality.MEDIUM -> 4; else -> 6 }
        for (i in 2 until 2 + beamCount) {
            val li = i % 6
            val p = V3(-2.5f + li * 1.0f, 3.4f, -0.8f)
            val dir = V3(lightDir[li * 3], lightDir[li * 3 + 1], lightDir[li * 3 + 2])
            val len = 7f
            val narrow = if (inp.lighting == LightingStyle.LASERS) 0.03f else 0.35f
            val m = M.between(p, p + dir * len, narrow * len * 0.18f, narrow * len * 0.18f)
            // The cone mesh runs from its apex (y=0) to its base (y=-1); mirror Y so the apex sits at the light.
            val flip = M.mul(m, M.scale(M.identity(), 1f, -1f, 1f))
            beam.mat4("uModel", flip)
            val c = V3(lightCol[li * 3], lightCol[li * 3 + 1], lightCol[li * 3 + 2]) * (1f / 3f)
            beam.vec3("uColor", c.x, c.y, c.z)
            beam.float("uIntensity", if (playing) 0.18f + 0.25f * bassPulse + 0.3f * dropFlash else 0.06f)
            cone.draw()
        }
        updateParticles(dt, quality)
        drawParticles()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Quality governor (AUTO): keep frame time within budget; audio is never affected (separate thread).
        val ms = (SystemClock.elapsedRealtimeNanos() - frameStart) / 1e6f
        inp.frameMs = inp.frameMs * 0.95f + ms * 0.05f
        if (inp.quality == GraphicsQuality.AUTO) {
            if (dt > 0.024f) { slowFrames++; fastFrames = 0 } else if (dt < 0.018f) { fastFrames++; slowFrames = 0 }
            if (slowFrames > 90 && autoQ != GraphicsQuality.LOW) { autoQ = if (autoQ == GraphicsQuality.HIGH) GraphicsQuality.MEDIUM else GraphicsQuality.LOW; slowFrames = 0 }
            if (fastFrames > 600 && autoQ != GraphicsQuality.HIGH && !inp.powerSave) { autoQ = if (autoQ == GraphicsQuality.LOW) GraphicsQuality.MEDIUM else GraphicsQuality.HIGH; fastFrames = 0 }
        }
        inp.effectiveQuality = quality
    }

    private fun qualityLevel(): GraphicsQuality = when {
        inp.powerSave -> GraphicsQuality.LOW
        inp.quality == GraphicsQuality.AUTO -> autoQ
        else -> inp.quality
    }

    private fun setLight(i: Int, p: V3, target: V3, c: V3) {
        val d = (target - p).norm()
        lightPos[i * 3] = p.x; lightPos[i * 3 + 1] = p.y; lightPos[i * 3 + 2] = p.z
        lightDir[i * 3] = d.x; lightDir[i * 3 + 1] = d.y; lightDir[i * 3 + 2] = d.z
        lightCol[i * 3] = c.x; lightCol[i * 3 + 1] = c.y; lightCol[i * 3 + 2] = c.z
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun drawWith(p: Program, model: FloatArray) {
        p.mat4("uModel", model); p.mat4("uNormal", M.normalMatrix(model))
    }

    private fun material(color: V3, emissive: V3 = V3.ZERO, rough: Float = 0.6f, metal: Float = 0f) {
        lit.vec3("uColor", color.x, color.y, color.z); lit.vec3("uEmissive", emissive.x, emissive.y, emissive.z)
        lit.float("uRough", rough); lit.float("uMetal", metal)
    }

    private fun solid(mesh: Mesh, model: FloatArray, color: V3, emissive: V3 = V3.ZERO, rough: Float = 0.6f, metal: Float = 0f) {
        material(color, emissive, rough, metal); drawWith(lit, model); mesh.draw()
    }

    private fun boxAt(c: V3, s: V3, color: V3, emissive: V3 = V3.ZERO, rough: Float = 0.6f, metal: Float = 0f, rotY: Float = 0f) {
        var m = M.translate(M.identity(), c.x, c.y, c.z)
        if (rotY != 0f) m = M.rotate(m, rotY, 0f, 1f, 0f)
        solid(box, M.scale(m, s.x, s.y, s.z), color, emissive, rough, metal)
    }

    private fun limb(a: V3, b: V3, r: Float, color: V3, rough: Float = 0.7f) = solid(capsule, M.between(a, b, r), color, V3.ZERO, rough)

    private fun ball(c: V3, r: Float, color: V3, emissive: V3 = V3.ZERO) = solid(sphere, M.scale(M.translate(M.identity(), c.x, c.y, c.z), r * 2, r * 2, r * 2), color, emissive, 0.5f)

    // ------------------------------------------------------------------ scene parts

    private fun drawTruss(colA: V3) {
        val metal = V3(0.25f, 0.25f, 0.28f)
        boxAt(V3(0f, 3.5f, -0.8f), V3(6.2f, 0.12f, 0.12f), metal, rough = 0.3f, metal = 1f)
        boxAt(V3(-3.1f, 1.75f, -0.8f), V3(0.12f, 3.5f, 0.12f), metal, rough = 0.3f, metal = 1f)
        boxAt(V3(3.1f, 1.75f, -0.8f), V3(0.12f, 3.5f, 0.12f), metal, rough = 0.3f, metal = 1f)
        for (i in 0 until 6) {
            val p = V3(-2.5f + i * 1.0f, 3.38f, -0.8f)
            val c = V3(lightCol[i * 3], lightCol[i * 3 + 1], lightCol[i * 3 + 2]) * 0.15f
            boxAt(p, V3(0.16f, 0.18f, 0.16f), V3(0.08f, 0.08f, 0.09f), c, 0.4f)
        }
        // Speaker stacks
        for (sx in listOf(-2.6f, 2.6f)) {
            boxAt(V3(sx, 0.55f, 0.2f), V3(0.6f, 1.1f, 0.55f), V3(0.05f, 0.05f, 0.06f), rough = 0.9f)
            ball(V3(sx, 0.75f, 0.48f), 0.17f + 0.015f * bassPulse, V3(0.12f, 0.12f, 0.13f))
            ball(V3(sx, 0.32f, 0.48f), 0.12f + 0.01f * bassPulse, V3(0.12f, 0.12f, 0.13f))
        }
        @Suppress("UNUSED_VARIABLE") val unused = colA
    }

    private fun drawConsole(mx: Console.State, colA: V3, colB: V3, beat: Double, playing: Boolean, live: LiveState) {
        // Booth table with LED strip.
        boxAt(V3(0f, 0.47f, 0.55f), V3(1.7f, 0.94f, 0.55f), V3(0.04f, 0.04f, 0.05f), rough = 0.8f)
        val stripPulse = 0.4f + 0.6f * bassPulse
        boxAt(V3(0f, 0.6f, 0.83f), V3(1.66f, 0.03f, 0.01f), colA, colA * stripPulse)
        boxAt(V3(0f, 0.3f, 0.83f), V3(1.66f, 0.03f, 0.01f), colB, colB * stripPulse)
        // Controller body
        boxAt(V3(0f, Console.TOP - 0.02f, Console.Z + 0.03f), V3(1.0f, 0.05f, 0.42f), V3(0.07f, 0.07f, 0.08f), rough = 0.35f, metal = 0.4f)
        for (d in 0..1) {
            val j = Console.jog(d)
            // Jog wheel: rotates with the deck's real playback rate.
            val baseM = M.translate(M.identity(), j.x, Console.TOP - 0.003f, j.z)
            solid(cyl, M.scale(baseM, 0.12f, 0.02f, 0.12f), V3(0.15f, 0.15f, 0.17f), rough = 0.2f, metal = 0.9f)
            val ringCol = if (mx.playing[d]) (if (d == live.currentDeck) colA else colB) else V3(0.1f, 0.1f, 0.1f)
            solid(cyl, M.scale(M.translate(M.identity(), j.x, Console.TOP + 0.017f, j.z), 0.125f, 0.004f, 0.125f), ringCol * 0.3f, ringCol * (0.5f + 0.5f * bassPulse))
            val ang = mx.jogAngle[d]
            val mark = M.rotate(M.translate(M.identity(), j.x, Console.TOP + 0.02f, j.z), ang, 0f, 1f, 0f)
            solid(box, M.scale(M.translate(mark, 0.08f, 0f, 0f), 0.04f, 0.005f, 0.008f), V3(0.9f, 0.9f, 0.9f), V3(0.4f, 0.4f, 0.4f))
            // Performance pads light up with the beat on the playing deck.
            for (i in 0 until 8) {
                val p = Console.loopButton(d, i)
                val lit = mx.playing[d] && ((floor(beat).toInt() % 8) == i)
                val c = if (d == 0) colA else colB
                boxAt(p, V3(0.028f, 0.008f, 0.028f), c * 0.2f, if (lit) c * 1.2f else c * 0.08f)
            }
            for (i in 0 until 3) {
                val p = Console.fxButton(d, i)
                boxAt(p, V3(0.02f, 0.008f, 0.02f), V3(0.2f, 0.2f, 0.22f), colB * (0.1f + mx.fx[d] * 1.2f))
            }
            // EQ + filter knobs: their rotation shows the engine's actual values.
            for (band in 0 until 3) {
                val k = Console.eqKnob(d, band)
                knob(k, (mx.eq[d][band] / 1.6f) * 270f - 135f, colA)
            }
            knob(Console.filterKnob(d), mx.filter[d] * 135f, colB)
            // Channel fader: cap position = deck volume.
            val f = Console.fader(d, mx.fader[d])
            boxAt(V3(f.x, Console.TOP + 0.002f, Console.Z + 0.13f), V3(0.006f, 0.004f, 0.075f), V3(0.02f, 0.02f, 0.02f))
            boxAt(f, V3(0.022f, 0.016f, 0.012f), V3(0.8f, 0.8f, 0.82f), rough = 0.3f)
        }
        val xf = Console.crossfader(mx.crossfader)
        boxAt(V3(0f, Console.TOP + 0.002f, xf.z), V3(0.11f, 0.004f, 0.006f), V3(0.02f, 0.02f, 0.02f))
        boxAt(xf, V3(0.012f, 0.016f, 0.022f), V3(0.8f, 0.8f, 0.82f), rough = 0.3f)
        // Laptop with a glowing screen behind the controller.
        boxAt(V3(0.0f, Console.TOP - 0.01f, Console.Z - 0.28f), V3(0.34f, 0.015f, 0.23f), V3(0.6f, 0.6f, 0.63f), rough = 0.3f, metal = 0.8f)
        val scr = M.rotate(M.translate(M.identity(), 0f, Console.TOP + 0.1f, Console.Z - 0.39f), -12f, 1f, 0f, 0f)
        solid(box, M.scale(scr, 0.34f, 0.22f, 0.01f), V3(0.05f, 0.05f, 0.06f), colA * (0.25f + 0.25f * (if (playing) bassPulse else 0f)))
    }

    private fun knob(p: V3, angleDeg: Float, accent: V3) {
        val base = M.translate(M.identity(), p.x, Console.TOP - 0.002f, p.z)
        solid(cyl, M.scale(base, 0.013f, 0.022f, 0.013f), V3(0.12f, 0.12f, 0.13f), rough = 0.4f)
        val ind = M.rotate(M.translate(M.identity(), p.x, Console.TOP + 0.021f, p.z), -angleDeg, 0f, 1f, 0f)
        solid(box, M.scale(M.translate(ind, 0f, 0f, -0.007f), 0.003f, 0.003f, 0.012f), accent, accent * 0.8f)
    }

    private fun drawDj(colA: V3) {
        val av = inp.avatar
        val skin = skinTones[av.skinTone.coerceIn(0, skinTones.size - 1)]
        val (shirt, accent) = outfits[av.outfit.coerceIn(0, outfits.size - 1)]
        val pants = V3(0.06f, 0.06f, 0.08f)
        val phones = phoneColors[av.headphones.coerceIn(0, phoneColors.size - 1)]
        val r = rig
        // Legs
        limb(r.hipL, r.kneeL, 0.075f, pants); limb(r.kneeL, r.footL, 0.06f, pants)
        limb(r.hipR, r.kneeR, 0.075f, pants); limb(r.kneeR, r.footR, 0.06f, pants)
        boxAt(r.footL + V3(0f, -0.02f, 0.05f), V3(0.1f, 0.07f, 0.24f), V3(0.9f, 0.9f, 0.9f))
        boxAt(r.footR + V3(0f, -0.02f, 0.05f), V3(0.1f, 0.07f, 0.24f), V3(0.9f, 0.9f, 0.9f))
        // Pelvis + torso (tapered: wide chest ellipsoid + belly capsule), oriented by the chest frame.
        solid(sphere, M.scale(M.translate(M.translate(M.identity(), r.pelvis.x, r.pelvis.y, r.pelvis.z), 0f, 0.02f, 0f), 0.34f, 0.2f, 0.22f), pants)
        solid(capsule, M.scale(M.translate(r.chestMatrix, 0f, 0.02f, 0f), 0.15f, 0.34f, 0.11f), shirt, rough = 0.85f)
        solid(sphere, M.scale(M.translate(r.chestMatrix, 0f, 0.33f, 0f), 0.42f, 0.24f, 0.24f), shirt, rough = 0.85f)
        // Shirt accent stripe (glow) across the chest.
        solid(box, M.scale(M.translate(r.chestMatrix, 0f, 0.3f, 0.115f), 0.3f, 0.025f, 0.01f), accent, accent * (0.4f + 0.6f * bassPulse))
        // Arms
        limb(r.shoulderL, r.elbowL, 0.058f, shirt); limb(r.elbowL, r.handL, 0.045f, skin)
        limb(r.shoulderR, r.elbowR, 0.058f, shirt); limb(r.elbowR, r.handR, 0.045f, skin)
        ball(r.shoulderL, 0.065f, shirt); ball(r.shoulderR, 0.065f, shirt)
        ball(r.handL, 0.045f, skin); ball(r.handR, 0.045f, skin)
        // Neck + head
        limb(r.neck, M.point(r.headMatrix, V3(0f, 0.02f, 0f)), 0.045f, skin)
        val hm = r.headMatrix
        solid(sphere, M.scale(M.translate(hm, 0f, 0.1f, 0f), 0.2f, 0.25f, 0.22f), skin, rough = 0.6f)
        // Face: eyes (blink), brows, mouth (opens on drops/hype, smiles with energy).
        val eyeH = 0.022f * (1f - r.eyesClosed) + 0.003f
        for (sx in listOf(0.045f, -0.045f)) {
            solid(sphere, M.scale(M.translate(hm, sx, 0.12f, 0.098f), 0.028f, eyeH, 0.012f), V3(0.02f, 0.02f, 0.02f))
            solid(box, M.scale(M.translate(hm, sx, 0.155f + 0.01f * r.smile, 0.104f), 0.04f, 0.007f, 0.01f), V3(0.08f, 0.05f, 0.04f))
        }
        val mouthH = 0.006f + 0.03f * r.mouthOpen
        solid(sphere, M.scale(M.translate(hm, 0f, 0.04f - 0.005f * r.smile, 0.1f), 0.05f + 0.02f * r.smile, mouthH, 0.012f), V3(0.25f, 0.05f, 0.06f))
        // Hair / cap
        when (av.hair) {
            1 -> solid(sphere, M.scale(M.translate(hm, 0f, 0.17f, -0.01f), 0.215f, 0.17f, 0.23f), V3(0.07f, 0.05f, 0.04f), rough = 0.9f)
            2 -> {
                solid(sphere, M.scale(M.translate(hm, 0f, 0.18f, 0f), 0.215f, 0.15f, 0.23f), accent * 0.6f, rough = 0.8f)
                solid(box, M.scale(M.translate(hm, 0f, 0.17f, 0.13f), 0.17f, 0.012f, 0.1f), accent * 0.6f)
            }
            3 -> {
                solid(sphere, M.scale(M.translate(hm, 0f, 0.15f, -0.02f), 0.23f, 0.21f, 0.24f), V3(0.1f, 0.06f, 0.03f), rough = 0.9f)
                solid(capsule, M.scale(M.translate(hm, 0f, -0.12f, -0.07f), 0.1f, 0.25f, 0.05f), V3(0.1f, 0.06f, 0.03f), rough = 0.9f)
            }
            else -> {}
        }
        // Headphones: on the neck normally, one cup pressed to the ear while cueing.
        val cueLift = r.headphonesOnEar
        val leftCup = M.point(hm, V3(0.115f, 0.08f, 0.0f)).lerp(M.point(r.chestMatrix, V3(0.1f, 0.52f, 0.06f)), 1f - cueLift)
        val rightCup = M.point(r.chestMatrix, V3(-0.1f, 0.52f, 0.06f))
        val band = M.point(r.chestMatrix, V3(0f, 0.55f, -0.08f)).lerp(M.point(hm, V3(0f, 0.24f, 0f)), cueLift * 0.5f)
        limb(leftCup, band, 0.012f, phones, 0.3f); limb(rightCup, band, 0.012f, phones, 0.3f)
        ball(leftCup, 0.05f, phones, colA * 0.15f); ball(rightCup, 0.05f, phones, colA * 0.15f)
    }

    private fun drawCrowd(beat: Double, energy: Float, playing: Boolean) {
        for ((i, base) in crowd.withIndex()) {
            val ph = ((beat + crowdPhase[i] * 0.3) % 1.0).toFloat()
            val jump = if (playing) (0.04f + 0.12f * energy) * (0.5f - 0.5f * cos(2f * PI.toFloat() * ph)) else 0f
            val c = V3(0.02f, 0.02f, 0.03f)
            val y = 1.15f + (i % 3) * 0.08f + jump
            limb(V3(base.x, 0.0f, base.z), V3(base.x, y, base.z), 0.17f, c, 0.9f)
            ball(V3(base.x, y + 0.2f, base.z), 0.12f, c)
            if (playing && energy > 0.7f && (i + floor(beat / 4).toInt()) % 3 == 0) {
                limb(V3(base.x + 0.12f, y - 0.05f, base.z), V3(base.x + 0.2f, y + 0.45f + jump, base.z - 0.05f), 0.04f, c)
            }
        }
    }

    // ------------------------------------------------------------------ particles

    private fun burst(n: Int, a: V3, b: V3) {
        var spawned = 0
        for (i in 0 until maxParticles) {
            if (pLife[i] > 0f) continue
            val c = if (rnd.nextBoolean()) a else b
            val o = i * 3
            pPos[o] = (rnd.nextFloat() - 0.5f) * 3f; pPos[o + 1] = 3.3f; pPos[o + 2] = (rnd.nextFloat() - 0.2f) * 2f
            pVel[o] = (rnd.nextFloat() - 0.5f) * 1.5f; pVel[o + 1] = rnd.nextFloat() * 1.5f; pVel[o + 2] = rnd.nextFloat() * 1.2f
            pCol[o] = c.x; pCol[o + 1] = c.y; pCol[o + 2] = c.z
            pLife[i] = 2.5f + rnd.nextFloat() * 2f
            if (++spawned >= n) break
        }
    }

    private var aliveCount = 0
    private fun updateParticles(dt: Float, q: GraphicsQuality) {
        val limit = when (q) { GraphicsQuality.LOW -> 150; GraphicsQuality.MEDIUM -> 450; else -> maxParticles }
        pBufPos.clear(); pBufCol.clear(); aliveCount = 0
        for (i in 0 until limit) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dt
            val o = i * 3
            pVel[o + 1] -= 1.2f * dt
            pVel[o] *= 0.99f; pVel[o + 2] *= 0.99f
            pPos[o] += pVel[o] * dt + sin(time * 3f + i) * 0.003f; pPos[o + 1] += pVel[o + 1] * dt; pPos[o + 2] += pVel[o + 2] * dt
            if (pPos[o + 1] < 0.02f) { pPos[o + 1] = 0.02f; pVel[o + 1] = 0f }
            val fade = min(1f, pLife[i])
            pBufPos.put(pPos[o]).put(pPos[o + 1]).put(pPos[o + 2])
            pBufCol.put(pCol[o] * fade).put(pCol[o + 1] * fade).put(pCol[o + 2] * fade)
            aliveCount++
        }
    }

    private fun drawParticles() {
        if (aliveCount == 0) return
        pBufPos.position(0); pBufCol.position(0)
        particle.use(); particle.mat4("uVP", vp); particle.float("uSize", 90f)
        GLES30.glBindVertexArray(pVao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pVbo[0]); GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, aliveCount * 12, pBufPos)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pVbo[1]); GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, aliveCount * 12, pBufCol)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, aliveCount)
        GLES30.glBindVertexArray(0)
    }
}
