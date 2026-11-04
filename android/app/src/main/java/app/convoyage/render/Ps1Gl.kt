package app.convoyage.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

object Ps1Shaders {
    /**
     * Vertex stage: snap the clip-space position to the low-res pixel grid (the PS1 jitter), light per vertex (Gouraud,
     * with the finish's specular term and pearl hue shift on paint), and pass UV * w so the fragment stage can map
     * textures affinely.
     */
    const val VERTEX = """#version 300 es
precision highp float;
uniform mat4 uMV; uniform mat4 uP; uniform vec2 uRes; uniform vec3 uLight;
uniform float uSpec; uniform float uShine; uniform float uMetal; uniform float uPearl; uniform vec3 uSpecCol;
in vec3 aPos; in vec3 aNrm; in vec2 aUv; in vec3 aTint; in vec2 aMat;
out vec3 vUvW; out vec3 vCol; out vec3 vSpec; flat out float vTex;
out vec3 vN; out vec3 vP; flat out vec2 vKind; flat out vec3 vUp;
vec3 hue(vec3 c, float a) {
  vec3 k = vec3(0.57735);
  return c * cos(a) + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cos(a));
}
void main() {
  vec4 vp = uMV * vec4(aPos, 1.0);
  vec4 p = uP * vp;
  vec2 g = uRes * 0.5;
  p.xyz /= p.w; p.xy = floor(p.xy * g + 0.5) / g; p.xyz *= p.w;
  gl_Position = p;
  vec3 n = normalize(mat3(uMV) * aNrm);
  vec3 L = normalize(uLight);
  float d = max(dot(n, L), 0.0);
  float rim = pow(1.0 - max(n.z, 0.0), 2.0) * 0.25;
  float glass = step(2.5, aMat.y);
  float unlit = step(1.5, aMat.y) * (1.0 - glass);
  float paint = step(0.5, aMat.y) * (1.0 - unlit) * (1.0 - glass);
  float l = mix(0.38 + 0.85 * d + rim, 1.0, unlit);
  vec3 tint = aTint;
  tint *= 1.0 - 0.28 * uMetal * paint;
  tint = mix(tint, hue(tint, uPearl * (n.x * 0.9 + n.y * 0.5 - 0.1)), paint);
  vec3 V = normalize(-vp.xyz);
  float s = pow(max(dot(n, normalize(L + V)), 0.0), uShine);
  vCol = tint * l;
  vSpec = vec3(0.0);
  // Paint and glass shine per pixel (see the fragment stage); here only their diffuse base.
  vCol = mix(vCol, tint * (0.3 + 0.25 * d), glass);
  vN = n; vP = vp.xyz; vKind = vec2(paint, glass);
  vUp = normalize(mat3(uMV) * vec3(0.0, 1.0, 0.0));
  vUvW = vec3(aUv * p.w, p.w);
  vTex = aMat.x;
}
"""

    /** Fragment stage: affine texture lookup, a 4x4 Bayer dither, then 15-bit colour. Mirrors [Ps1Math]. */
    const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uBody; uniform sampler2D uRim; uniform sampler2D uGround;
uniform vec3 uLight; uniform float uSpec; uniform float uShine; uniform float uMetal; uniform vec3 uSpecCol;
in vec3 vUvW; in vec3 vCol; in vec3 vSpec; flat in float vTex;
in vec3 vN; in vec3 vP; flat in vec2 vKind; flat in vec3 vUp;
// The world a car reflects, Gran Turismo style: bright sky over a crisp horizon over dark ground.
vec3 env(vec3 r, vec3 up) {
  float h = dot(r, up);
  if (h > 0.0) return mix(vec3(0.95, 0.97, 1.0), vec3(0.3, 0.45, 0.68), pow(h, 0.45));
  return mix(vec3(0.42, 0.4, 0.38), vec3(0.05, 0.05, 0.06), min(1.0, -h * 7.0));
}
out vec4 o;
float bayer(vec2 f) {
  int x = int(mod(f.x, 4.0)); int y = int(mod(f.y, 4.0));
  int m[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
  return float(m[y * 4 + x]) / 16.0;
}
void main() {
  vec2 uv = vUvW.xy / vUvW.z;
  vec4 t = vec4(1.0);
  if (vTex > 2.5) t = textureLod(uGround, uv, 0.0);
  else if (vTex > 1.5) t = textureLod(uRim, uv, 0.0);
  else if (vTex > 0.5) t = textureLod(uBody, uv, 0.0);
  vec3 c = t.rgb * vCol + vSpec;
  if (vKind.x + vKind.y > 0.5) {
    vec3 n = normalize(vN); vec3 V = normalize(-vP);
    if (dot(n, V) < 0.0) n = -n;
    vec3 e = env(reflect(-V, n), vUp);
    float fres = 0.15 + 0.85 * pow(1.0 - max(dot(n, V), 0.0), 4.0);
    float gloss = clamp(uSpec, 0.0, 1.0);
    float hl = pow(max(dot(n, normalize(normalize(uLight) + V)), 0.0), uShine * 4.0 + 8.0);
    if (vKind.y > 0.5) {
      c = c + e * (0.22 + 0.7 * fres) + vec3(1.0) * hl * 0.8;
    } else {
      // Clear coat: the reflection rides on top of the colour; metallic flake tints it.
      vec3 coat = mix(e, e * (vCol * 1.6 + 0.2), 0.5 * uMetal);
      float cf = 0.3 + 0.7 * fres;
      c = c * (1.0 - 0.3 * cf * gloss) + coat * cf * (0.1 + 0.55 * gloss) + uSpecCol * hl * (0.4 + 1.4 * gloss);
    }
  }
  c += (bayer(gl_FragCoord.xy) - 0.5) / 40.0;
  c = pow(clamp(c, 0.0, 1.0), vec3(0.8));
  c = floor(c * 31.0 + 0.5) / 31.0;
  o = vec4(c, 1.0);
}
"""

    /** A full-screen triangle that shows the low-res target with NEAREST filtering. */
    const val BLIT_VERTEX = """#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
    const val BLIT_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 o;
void main() { o = texture(uTex, vUv); }
"""
}

/** Camera and scene numbers that depend only on the framing, not on GL. */
object Ps1Camera {
    const val FOV_DEG = 30f
    const val ELEVATION = 0.17f

    /** Distance at which ps1.html frames its car (12.2) scaled to the vehicle's size, and backed off for tall, narrow views. */
    fun distance(frame: Ps1Frame, aspect: Float, zoom: Float = 1f): Float =
        12.2f * frame.fitRadius / 3.6f * max(1f, 1.43f / aspect) / zoom

    fun distance(body: Ps1Body, aspect: Float, zoom: Float = 1f): Float = distance(Ps1Models.frame(body), aspect, zoom)

    /** Eye height above the look-at point (ps1.html: sin(elevation) * distance + 0.4 against a target at 0.35). */
    fun eyeY(distance: Float, lookY: Float): Float = kotlin.math.sin(ELEVATION) * distance + lookY + 0.05f

    fun eyeZ(distance: Float): Float = kotlin.math.cos(ELEVATION) * distance
}

/** How a finish looks to the vertex shader. */
class Ps1Look(val spec: Float, val shine: Float, val metal: Float, val pearl: Float, val specCol: FloatArray) {
    companion object {
        fun of(finish: Ps1Finish, paint: Int): Ps1Look {
            val p = Ps1Textures.linear(paint)
            return when (finish) {
                Ps1Finish.Gloss -> Ps1Look(0.55f, 28f, 0f, 0f, floatArrayOf(1f, 1f, 1f))
                Ps1Finish.Satin -> Ps1Look(0.22f, 8f, 0f, 0f, floatArrayOf(1f, 1f, 1f))
                Ps1Finish.Matte -> Ps1Look(0f, 1f, 0f, 0f, floatArrayOf(0f, 0f, 0f))
                Ps1Finish.Metallic -> Ps1Look(0.9f, 14f, 1f, 0f, floatArrayOf(0.45f + 0.55f * p[0], 0.45f + 0.55f * p[1], 0.45f + 0.55f * p[2]))
                Ps1Finish.Pearl -> Ps1Look(0.5f, 18f, 0f, 0.75f, floatArrayOf(0.9f, 0.95f, 1f))
            }
        }
    }
}

/**
 * The GL side of the renderer. Both the live garage view and the shared thumbnail renderer use it, on whichever GL
 * thread they own. Every method must be called with that thread's context current.
 *
 * It draws a scene into a small RGBA target ([renderLowRes]) and then either shows it with NEAREST filtering
 * ([blit]) or reads it back ([readPixels]).
 */
class Ps1Gl {
    private var prog = 0
    private var blitProg = 0
    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboDepth = 0
    private var fboW = 0
    private var fboH = 0
    private var texBody = 0
    private var texRim = 0
    private var texGround = 0
    private var emptyVao = 0

    private var mesh: Ps1Mesh? = null
    private var meshKey: String? = null
    private var rimId: String? = null
    private var look = Ps1Look.of(Ps1Finish.Gloss, 0)
    private var frame = Ps1Models.frame(Ps1Body.Sedan)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)

    /** Vertices and indices of the current model (for the performance log). */
    val triangleCount: Int get() = (mesh?.indices?.size ?: 0) / 3

    fun init() {
        prog = link(Ps1Shaders.VERTEX, Ps1Shaders.FRAGMENT)
        blitProg = link(Ps1Shaders.BLIT_VERTEX, Ps1Shaders.BLIT_FRAGMENT)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0); emptyVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glGenBuffers(1, ids, 0); ibo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val stride = Ps1Mesh.STRIDE * 4
        fun attrib(name: String, size: Int, offset: Int) {
            val loc = GLES30.glGetAttribLocation(prog, name)
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, stride, offset * 4)
        }
        attrib("aPos", 3, 0); attrib("aNrm", 3, 3); attrib("aUv", 2, 6); attrib("aTint", 3, 8); attrib("aMat", 2, 11)
        GLES30.glBindVertexArray(0)
        texBody = texture(Ps1Textures.body(), repeat = false)
        texGround = texture(Ps1Textures.ground(), repeat = true)
        texRim = texture(Ps1Textures.rim(Ps1Wheels.ALL[0]), repeat = false)
        rimId = Ps1Wheels.ALL[0].id
    }

    /** Builds and uploads the model for [spec] unless it is already the current one. */
    fun setSpec(spec: Ps1Spec) {
        val key = spec.key()
        look = Ps1Look.of(spec.finish, spec.paint)
        frame = Ps1Models.frame(spec)
        if (key == meshKey) return
        meshKey = key
        val m = Ps1Models.build(spec)
        mesh = m
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val vb = ByteBuffer.allocateDirect(m.vertices.size * 4).order(ByteOrder.nativeOrder())
        vb.asFloatBuffer().put(m.vertices)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vb.capacity(), vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val ib = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder())
        ib.asShortBuffer().put(m.indices)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib.capacity(), ib, GLES30.GL_STATIC_DRAW)
        if (rimId != spec.wheel.id) {
            rimId = spec.wheel.id
            upload(texRim, Ps1Textures.rim(spec.wheel), repeat = false)
        }
    }

    /** Draws the current model into the low-res target of [w] x [h] pixels, turned [yawDeg] degrees. The target is left bound. */
    fun renderLowRes(w: Int, h: Int, yawDeg: Float, ground: Boolean, zoom: Float = 1f, topDown: Boolean = false) {
        val m = mesh ?: return
        ensureTarget(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)

        val aspect = w / h.toFloat()
        val dist = Ps1Camera.distance(frame, aspect, zoom)
        if (topDown) {
            // Straight down with the nose (+x) at the top of the image, orthographic so it reads as a plan view.
            val halfH = frame.fitRadius * 1.02f
            val halfW = halfH * aspect
            Matrix.orthoM(proj, 0, -halfW, halfW, -halfH, halfH, 1f, 60f)
            Matrix.setLookAtM(view, 0, 0f, 30f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        } else {
            Matrix.perspectiveM(proj, 0, Ps1Camera.FOV_DEG, aspect, 1f, 60f)
            Matrix.setLookAtM(view, 0, 0f, Ps1Camera.eyeY(dist, frame.lookY), Ps1Camera.eyeZ(dist), 0f, frame.lookY, 0f, 0f, 1f, 0f)
        }

        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(loc("uP"), 1, false, proj, 0)
        GLES30.glUniform2f(loc("uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform3f(loc("uLight"), 0.5f, 0.9f, 0.7f)
        GLES30.glUniform1f(loc("uSpec"), look.spec)
        GLES30.glUniform1f(loc("uShine"), look.shine)
        GLES30.glUniform1f(loc("uMetal"), look.metal)
        GLES30.glUniform1f(loc("uPearl"), look.pearl)
        GLES30.glUniform3f(loc("uSpecCol"), look.specCol[0], look.specCol[1], look.specCol[2])
        bindTex(0, texBody, "uBody"); bindTex(1, texRim, "uRim"); bindTex(2, texGround, "uGround")
        GLES30.glBindVertexArray(vao)

        if (ground) {
            GLES30.glUniformMatrix4fv(loc("uMV"), 1, false, view, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.groundIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        Matrix.setRotateM(model, 0, yawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        GLES30.glUniformMatrix4fv(loc("uMV"), 1, false, mv, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indices.size - m.groundIndexCount, GLES30.GL_UNSIGNED_SHORT, m.groundIndexCount * 2)
        GLES30.glBindVertexArray(0)
    }

    /**
     * A map-marker frame: orthographic, looking down at [elevationDeg] above the horizon, the vehicle turned so that
     * [relHeadingDeg] (clockwise from "driving away from the camera") is the way it faces. Origin at the middle.
     */
    fun renderAngle(w: Int, h: Int, relHeadingDeg: Float, elevationDeg: Float) {
        val m = mesh ?: return
        ensureTarget(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        val half = frame.fitRadius * 1.0f
        Matrix.orthoM(proj, 0, -half, half, -half, half, 1f, 80f)
        val e = Math.toRadians(elevationDeg.toDouble())
        // The camera sits towards +z (south of the vehicle on screen) and up; screen up is away from it.
        Matrix.setLookAtM(view, 0, 0f, (30 * kotlin.math.sin(e)).toFloat(), (30 * kotlin.math.cos(e)).toFloat(), 0f, 0f, 0f, 0f, 1f, 0f)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(loc("uP"), 1, false, proj, 0)
        GLES30.glUniform2f(loc("uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform3f(loc("uLight"), 0.5f, 0.9f, 0.7f)
        GLES30.glUniform1f(loc("uSpec"), look.spec)
        GLES30.glUniform1f(loc("uShine"), look.shine)
        GLES30.glUniform1f(loc("uMetal"), look.metal)
        GLES30.glUniform1f(loc("uPearl"), look.pearl)
        GLES30.glUniform3f(loc("uSpecCol"), look.specCol[0], look.specCol[1], look.specCol[2])
        bindTex(0, texBody, "uBody"); bindTex(1, texRim, "uRim"); bindTex(2, texGround, "uGround")
        GLES30.glBindVertexArray(vao)
        // Model +x is forward; facing away from the camera (-z) is a 90 degree turn, clockwise headings turn it back.
        Matrix.setRotateM(model, 0, 90f - relHeadingDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        GLES30.glUniformMatrix4fv(loc("uMV"), 1, false, mv, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indices.size - m.groundIndexCount, GLES30.GL_UNSIGNED_SHORT, m.groundIndexCount * 2)
        GLES30.glBindVertexArray(0)
    }

    /** Shows the low-res target on the bound surface (default framebuffer), centred and scaled by a whole [scale] with NEAREST filtering. */
    fun blit(viewW: Int, viewH: Int, w: Int, h: Int, scale: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport((viewW - w * scale) / 2, (viewH - h * scale) / 2, w * scale, h * scale)
        GLES30.glUseProgram(blitProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProg, "uTex"), 0)
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    /** The low-res target as ARGB ints, top row first. Call right after [renderLowRes]. */
    fun readPixels(w: Int, h: Int): IntArray {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = (h - 1 - y) * w
            for (x in 0 until w) {
                val o = (row + x) * 4
                val r = buf.get(o).toInt() and 0xFF; val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF; val a = buf.get(o + 3).toInt() and 0xFF
                out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    fun release() {
        val one = IntArray(1)
        fun del(f: (Int, IntArray, Int) -> Unit, id: Int) { if (id != 0) { one[0] = id; f(1, one, 0) } }
        del(GLES30::glDeleteBuffers, vbo); del(GLES30::glDeleteBuffers, ibo)
        del(GLES30::glDeleteVertexArrays, vao); del(GLES30::glDeleteVertexArrays, emptyVao)
        del(GLES30::glDeleteTextures, texBody); del(GLES30::glDeleteTextures, texRim); del(GLES30::glDeleteTextures, texGround)
        del(GLES30::glDeleteTextures, fboTex); del(GLES30::glDeleteFramebuffers, fbo); del(GLES30::glDeleteRenderbuffers, fboDepth)
        if (prog != 0) GLES30.glDeleteProgram(prog)
        if (blitProg != 0) GLES30.glDeleteProgram(blitProg)
        prog = 0; blitProg = 0; vbo = 0; ibo = 0; vao = 0; emptyVao = 0; texBody = 0; texRim = 0; texGround = 0
        fboTex = 0; fbo = 0; fboDepth = 0; fboW = 0; fboH = 0; mesh = null; meshKey = null; rimId = null
    }

    // ---- plumbing ----

    private fun loc(name: String) = GLES30.glGetUniformLocation(prog, name)

    private fun bindTex(unit: Int, tex: Int, uniform: String) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc(uniform), unit)
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        val one = IntArray(1)
        if (fbo != 0) {
            one[0] = fboTex; GLES30.glDeleteTextures(1, one, 0)
            one[0] = fboDepth; GLES30.glDeleteRenderbuffers(1, one, 0)
            one[0] = fbo; GLES30.glDeleteFramebuffers(1, one, 0)
        }
        GLES30.glGenTextures(1, one, 0); fboTex = one[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenRenderbuffers(1, one, 0); fboDepth = one[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, fboDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        GLES30.glGenFramebuffers(1, one, 0); fbo = one[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, fboDepth)
        fboW = w; fboH = h
    }

    private fun texture(t: Ps1Texture, repeat: Boolean): Int {
        val one = IntArray(1)
        GLES30.glGenTextures(1, one, 0)
        upload(one[0], t, repeat)
        return one[0]
    }

    private fun upload(id: Int, t: Ps1Texture, repeat: Boolean) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        val bytes = ByteBuffer.allocateDirect(t.argb.size * 4).order(ByteOrder.nativeOrder()).put(t.rgbaBytes()).also { it.position(0) }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, t.size, t.size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "PS1 shader link failed: " + GLES30.glGetProgramInfoLog(p) }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "PS1 shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }
}
