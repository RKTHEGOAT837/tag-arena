package com.aidj.app.stage

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ------------------------------------------------------------------ vector helpers

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) V3(0f, 1f, 0f) else this * (1f / l) }
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    companion object { val ZERO = V3(0f, 0f, 0f); val UP = V3(0f, 1f, 0f) }
}

/** Critically damped spring for natural, non-snapping motion. */
class Spring3(var pos: V3, private val stiffness: Float = 60f) {
    private var vel = V3.ZERO
    fun update(target: V3, dt: Float): V3 {
        val k = stiffness; val d = 2f * sqrt(k)
        val acc = (target - pos) * k - vel * d
        vel += acc * dt
        pos += vel * dt
        return pos
    }
}

class Spring1(var value: Float, private val stiffness: Float = 40f) {
    private var vel = 0f
    fun update(target: Float, dt: Float): Float {
        val d = 2f * sqrt(stiffness)
        vel += ((target - value) * stiffness - vel * d) * dt
        value += vel * dt
        return value
    }
}

object M {
    fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    fun mul(a: FloatArray, b: FloatArray) = FloatArray(16).also { Matrix.multiplyMM(it, 0, a, 0, b, 0) }
    fun translate(m: FloatArray, x: Float, y: Float, z: Float) = m.copyOf().also { Matrix.translateM(it, 0, x, y, z) }
    fun rotate(m: FloatArray, deg: Float, x: Float, y: Float, z: Float) = m.copyOf().also { Matrix.rotateM(it, 0, deg, x, y, z) }
    fun scale(m: FloatArray, x: Float, y: Float, z: Float) = m.copyOf().also { Matrix.scaleM(it, 0, x, y, z) }
    fun point(m: FloatArray, p: V3): V3 {
        val v = floatArrayOf(p.x, p.y, p.z, 1f); val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, m, 0, v, 0)
        return V3(o[0], o[1], o[2])
    }

    /** Model matrix placing a unit Y-axis primitive (from y=0 to y=1) between [a] and [b], with radius scale [r]. */
    fun between(a: V3, b: V3, rx: Float, rz: Float = rx): FloatArray {
        val d = b - a
        val len = d.len()
        val dir = d.norm()
        val m = translate(identity(), a.x, a.y, a.z)
        val up = V3.UP
        val axis = up.cross(dir)
        val dot = up.dot(dir).coerceIn(-1f, 1f)
        val r = if (axis.len() < 1e-5f) (if (dot < 0) rotate(m, 180f, 1f, 0f, 0f) else m)
        else rotate(m, (acos(dot) * 180.0 / PI).toFloat(), axis.x, axis.y, axis.z)
        return scale(r, rx, len, rz)
    }

    fun normalMatrix(model: FloatArray): FloatArray {
        val inv = FloatArray(16); val out = FloatArray(16)
        if (!Matrix.invertM(inv, 0, model, 0)) return model
        Matrix.transposeM(out, 0, inv, 0)
        return out
    }
}

// ------------------------------------------------------------------ meshes

class Mesh(vertices: FloatArray, indices: ShortArray) {
    val vao = IntArray(1)
    private val vbo = IntArray(2)
    val count = indices.size

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glGenBuffers(2, vbo, 0)
        val vb: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        val ib: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(indices).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    companion object {
        /** Unit cube centred at origin (size 1). */
        fun box(): Mesh {
            val v = ArrayList<Float>(); val idx = ArrayList<Short>()
            val faces = arrayOf(
                floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f), floatArrayOf(1f, 0f, 0f),
                floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
            )
            for (n in faces) {
                val nv = V3(n[0], n[1], n[2])
                val u = if (kotlin.math.abs(nv.y) > 0.5f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)
                val t = u.cross(nv)
                val base = (v.size / 6).toShort()
                for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                    val p = nv * 0.5f + t * (a * 0.5f) + u * (b * 0.5f)
                    v.addAll(listOf(p.x, p.y, p.z, nv.x, nv.y, nv.z))
                }
                idx.addAll(listOf(base, (base + 1).toShort(), (base + 2).toShort(), base, (base + 2).toShort(), (base + 3).toShort()))
            }
            return Mesh(v.toFloatArray(), idx.toShortArray())
        }

        /** Unit sphere (radius 0.5) centred at origin. */
        fun sphere(seg: Int = 20, rings: Int = 14): Mesh {
            val v = ArrayList<Float>(); val idx = ArrayList<Short>()
            for (r in 0..rings) {
                val phi = PI * r / rings
                for (s in 0..seg) {
                    val th = 2 * PI * s / seg
                    val n = V3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
                    v.addAll(listOf(n.x * 0.5f, n.y * 0.5f, n.z * 0.5f, n.x, n.y, n.z))
                }
            }
            for (r in 0 until rings) for (s in 0 until seg) {
                val a = (r * (seg + 1) + s).toShort(); val b = (a + seg + 1).toShort()
                idx.addAll(listOf(a, b, (a + 1).toShort(), (a + 1).toShort(), b, (b + 1).toShort()))
            }
            return Mesh(v.toFloatArray(), idx.toShortArray())
        }

        /** Unit cylinder: radius 1, from y=0 to y=1, capped. */
        fun cylinder(seg: Int = 24): Mesh {
            val v = ArrayList<Float>(); val idx = ArrayList<Short>()
            for (s in 0..seg) {
                val th = 2 * PI * s / seg
                val x = cos(th).toFloat(); val z = sin(th).toFloat()
                v.addAll(listOf(x, 0f, z, x, 0f, z)); v.addAll(listOf(x, 1f, z, x, 0f, z))
            }
            for (s in 0 until seg) {
                val a = (s * 2).toShort()
                idx.addAll(listOf(a, (a + 1).toShort(), (a + 2).toShort(), (a + 2).toShort(), (a + 1).toShort(), (a + 3).toShort()))
            }
            for ((y, ny) in listOf(0f to -1f, 1f to 1f)) {
                val c = (v.size / 6).toShort()
                v.addAll(listOf(0f, y, 0f, 0f, ny, 0f))
                for (s in 0..seg) { val th = 2 * PI * s / seg; v.addAll(listOf(cos(th).toFloat(), y, sin(th).toFloat(), 0f, ny, 0f)) }
                for (s in 0 until seg) idx.addAll(listOf(c, (c + 1 + s).toShort(), (c + 2 + s).toShort()))
            }
            return Mesh(v.toFloatArray(), idx.toShortArray())
        }

        /** Capsule along Y from 0 to 1 with radius 1 (hemispherical ends scaled with the transform). */
        fun capsule(seg: Int = 16, rings: Int = 6): Mesh {
            val v = ArrayList<Float>(); val idx = ArrayList<Short>()
            // Build as a sphere whose upper/lower halves are pushed to y=1/y=0 respectively; radius in xz = 1, cap height 0.12 of length.
            val cap = 0.12f
            val total = rings * 2 + 1
            for (r in 0..total) {
                val upper = r <= rings
                val phi = if (upper) (PI / 2) * r / rings else PI / 2 + (PI / 2) * (r - rings - 1) / rings
                for (s in 0..seg) {
                    val th = 2 * PI * s / seg
                    val n = V3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
                    val y = if (upper) 1f - cap + n.y * cap else cap + n.y * cap
                    v.addAll(listOf(n.x, y, n.z, n.x, n.y, n.z))
                }
            }
            for (r in 0 until total) for (s in 0 until seg) {
                val a = (r * (seg + 1) + s).toShort(); val b = (a + seg + 1).toShort()
                idx.addAll(listOf(a, b, (a + 1).toShort(), (a + 1).toShort(), b, (b + 1).toShort()))
            }
            return Mesh(v.toFloatArray(), idx.toShortArray())
        }

        /** Open cone along -Y from apex (0,0,0) to base radius 1 at y=-1 (for light beams). */
        fun cone(seg: Int = 24): Mesh {
            val v = ArrayList<Float>(); val idx = ArrayList<Short>()
            for (s in 0..seg) {
                val th = 2 * PI * s / seg
                val x = cos(th).toFloat(); val z = sin(th).toFloat()
                v.addAll(listOf(0f, 0f, 0f, x, 0f, z)); v.addAll(listOf(x, -1f, z, x, 0f, z))
            }
            for (s in 0 until seg) { val a = (s * 2).toShort(); idx.addAll(listOf(a, (a + 1).toShort(), (a + 3).toShort())) }
            return Mesh(v.toFloatArray(), idx.toShortArray())
        }

        /** Unit quad in XY (-0.5..0.5), normal +Z. */
        fun quad(): Mesh = Mesh(
            floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0f, 1f, 0.5f, -0.5f, 0f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 0f, 0f, 1f, -0.5f, 0.5f, 0f, 0f, 0f, 1f),
            shortArrayOf(0, 1, 2, 0, 2, 3),
        )
    }
}

// ------------------------------------------------------------------ shaders

class Program(vs: String, fs: String) {
    val id: Int = GLES30.glCreateProgram()
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs); val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(id, v); GLES30.glAttachShader(id, f)
        GLES30.glBindAttribLocation(id, 0, "aPos"); GLES30.glBindAttribLocation(id, 1, "aNormal")
        GLES30.glLinkProgram(id)
        val ok = IntArray(1); GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Stage", "link: " + GLES30.glGetProgramInfoLog(id))
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Stage", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun mat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)
    fun vec3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)
    fun vec3(name: String, v: FloatArray) = GLES30.glUniform3fv(loc(name), v.size / 3, v, 0)
    fun float(name: String, x: Float) = GLES30.glUniform1f(loc(name), x)
    fun floats(name: String, v: FloatArray) = GLES30.glUniform1fv(loc(name), v.size, v, 0)
    fun int(name: String, x: Int) = GLES30.glUniform1i(loc(name), x)
}

object Shaders {
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uNormal;
out vec3 vPos; out vec3 vN;
void main(){ vec4 w = uModel * vec4(aPos,1.0); vPos = w.xyz; vN = normalize(mat3(uNormal) * aNormal); gl_Position = uVP * w; }"""

    // Physically-inspired lighting: ambient + up to 6 coloured spot/point lights + rim from the LED wall + emissive + fog.
    const val LIT_FS = """#version 300 es
precision mediump float;
in vec3 vPos; in vec3 vN; out vec4 o;
uniform vec3 uColor; uniform vec3 uEmissive; uniform float uRough; uniform float uMetal;
uniform vec3 uCam; uniform vec3 uAmbient; uniform vec3 uRim;
uniform int uLights; uniform vec3 uLightPos[6]; uniform vec3 uLightCol[6]; uniform vec3 uLightDir[6];
uniform float uFog;
void main(){
  vec3 n = normalize(vN); vec3 v = normalize(uCam - vPos);
  vec3 c = uAmbient * uColor * (0.6 + 0.4 * n.y);
  for (int i = 0; i < 6; i++) {
    if (i >= uLights) break;
    vec3 L = uLightPos[i] - vPos; float d = length(L); L /= d;
    float spot = smoothstep(0.80, 0.96, dot(-L, normalize(uLightDir[i])));
    float att = (0.35 + 0.65 * spot) / (1.0 + 0.08 * d * d);
    float diff = max(dot(n, L), 0.0);
    vec3 h = normalize(L + v);
    float sh = mix(8.0, 96.0, 1.0 - uRough);
    float spec = pow(max(dot(n, h), 0.0), sh) * mix(0.25, 1.0, uMetal) * (1.0 - uRough * 0.7);
    c += uLightCol[i] * att * (diff * uColor + spec);
  }
  float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
  c += uRim * rim * 0.9;
  c += uEmissive;
  float fog = clamp(length(uCam - vPos) * uFog, 0.0, 0.85);
  c = mix(c, uAmbient * 0.6, fog);
  o = vec4(c, 1.0);
}"""

    // Additive volumetric beam: bright at the apex, fading along the cone and towards its edges.
    const val BEAM_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal;
uniform mat4 uVP; uniform mat4 uModel; out float vT; out vec3 vN; out vec3 vW;
void main(){ vT = -aPos.y; vec4 w = uModel * vec4(aPos,1.0); vW = w.xyz; vN = normalize(mat3(uModel) * aNormal); gl_Position = uVP * w; }"""
    const val BEAM_FS = """#version 300 es
precision mediump float; in float vT; in vec3 vN; in vec3 vW; out vec4 o;
uniform vec3 uColor; uniform float uIntensity; uniform vec3 uCam;
void main(){ vec3 v = normalize(uCam - vW); float edge = pow(abs(dot(normalize(vN), v)), 1.6);
  float a = uIntensity * (1.0 - vT) * (1.0 - vT) * edge; o = vec4(uColor * a, 1.0); }"""

    // LED wall: band visualiser + beat pulses + theme gradients, all from real audio meters.
    const val WALL_FS = """#version 300 es
precision mediump float; in vec3 vPos; in vec3 vN; out vec4 o;
uniform float uBands[16]; uniform float uBeat; uniform float uTime; uniform float uEnergy; uniform float uDrop;
uniform vec3 uA; uniform vec3 uB; uniform vec2 uSize; uniform vec3 uCenter; uniform int uPattern;
void main(){
  vec2 p = (vPos.xy - uCenter.xy) / uSize + 0.5;
  vec2 cell = floor(p * vec2(64.0, 32.0)); vec2 f = fract(p * vec2(64.0, 32.0));
  float led = smoothstep(0.5, 0.35, length(f - 0.5));
  float pulse = exp(-fract(uBeat) * 5.0);
  vec3 col = vec3(0.0);
  int bi = int(clamp(floor(abs(p.x - 0.5) * 2.0 * 16.0), 0.0, 15.0));
  float level = clamp(uBands[bi] * 6.0, 0.0, 1.0);
  float bar = step(abs(p.y - 0.5) * 2.0, level);
  vec3 grad = mix(uA, uB, p.y);
  if (uPattern == 0) col = grad * bar;
  float ring = smoothstep(0.03, 0.0, abs(length((p - 0.5) * vec2(2.0, 1.0)) - fract(uBeat) * 0.9));
  col += mix(uB, uA, 0.5) * ring * (0.4 + uEnergy);
  float sweep = 0.5 + 0.5 * sin(p.x * 12.0 - uTime * 2.0 + cell.y * 0.3);
  col += grad * sweep * 0.15 * uEnergy;
  col *= 0.55 + 0.6 * pulse;
  col += vec3(1.0) * uDrop * step(0.5, fract(uBeat * 2.0)) * 0.6;
  o = vec4(col * led * 1.4 + grad * 0.03, 1.0);
}"""

    // Glossy floor with LED tiles that ripple out from the DJ on bass hits.
    const val FLOOR_FS = """#version 300 es
precision mediump float; in vec3 vPos; in vec3 vN; out vec4 o;
uniform float uBeat; uniform float uBass; uniform vec3 uA; uniform vec3 uB; uniform vec3 uCam; uniform float uTime;
void main(){
  vec2 g = vPos.xz * 2.0; vec2 cell = floor(g); vec2 f = fract(g);
  float tile = smoothstep(0.02, 0.06, min(min(f.x, f.y), min(1.0 - f.x, 1.0 - f.y)));
  float r = length(vPos.xz);
  float wave = exp(-abs(r - fract(uBeat) * 6.0) * 2.5) * uBass;
  float chk = mod(cell.x + cell.y, 2.0);
  vec3 c = mix(uA, uB, chk) * (0.05 + wave * 0.9) * tile;
  float fres = pow(1.0 - abs(normalize(uCam - vPos).y), 4.0);
  c += vec3(0.02, 0.02, 0.04) + fres * 0.08 * mix(uA, uB, 0.5);
  float fog = clamp(length(uCam - vPos) * 0.06, 0.0, 0.9);
  o = vec4(c * (1.0 - fog), 1.0);
}"""

    const val PARTICLE_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aCol;
uniform mat4 uVP; uniform float uSize; out vec3 vCol;
void main(){ vCol = aCol; vec4 p = uVP * vec4(aPos, 1.0); gl_Position = p; gl_PointSize = uSize / max(p.w, 0.3); }"""
    const val PARTICLE_FS = """#version 300 es
precision mediump float; in vec3 vCol; out vec4 o;
void main(){ vec2 d = gl_PointCoord - 0.5; float a = smoothstep(0.5, 0.0, length(d)); o = vec4(vCol * a, 1.0); }"""
}
