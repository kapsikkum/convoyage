package app.convoyage.render

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * The live PS1 view for the garage: a turntable that also turns under a finger. It draws the scene at about 240 rows
 * and shows it with NEAREST filtering, so the pixels stay chunky at any screen size.
 */
class Ps1SurfaceView(context: Context) : GLSurfaceView(context) {
    private val renderer = Ps1ViewRenderer()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
    }

    /** Hands the renderer a new build; takes effect on the next frame. */
    fun setSpec(spec: Ps1Spec) {
        renderer.pending = spec
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; dragging = false
                renderer.touching = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    renderer.addYaw((e.x - lastX) * 0.6f)
                    lastX = e.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.touching = false
                renderer.lastTouch = SystemClock.uptimeMillis()
                dragging = false
            }
        }
        return true
    }
}

private class Ps1ViewRenderer : GLSurfaceView.Renderer {
    @Volatile var pending: Ps1Spec? = null
    @Volatile var touching = false
    @Volatile var lastTouch = 0L
    @Volatile private var yawDeg = -32f

    private var gl: Ps1Gl? = null
    private var viewW = 1
    private var viewH = 1
    private var lastFrame = 0L
    private var frames = 0
    private var cpuNanos = 0L

    fun addYaw(d: Float) {
        yawDeg += d
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        gl = Ps1Gl().also { it.init() }
        lastFrame = 0L
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
    }

    override fun onDrawFrame(unused: GL10?) {
        val g = gl ?: return
        val t0 = System.nanoTime()
        pending?.let { g.setSpec(it) }
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L && !touching && now - lastTouch > 1200) yawDeg += (now - lastFrame) * 0.022f // about 22 degrees a second
        lastFrame = now

        val scale = Ps1Math.pixelScale(viewH)
        val w = maxOf(1, viewW / scale)
        val h = maxOf(1, viewH / scale)
        g.renderLowRes(w, h, yawDeg, ground = true)
        g.blit(viewW, viewH, w, h, scale)

        cpuNanos += System.nanoTime() - t0
        if (++frames == 300) {
            Log.d("Ps1View", "avg CPU per frame %.2f ms (%dx%d low-res, %d triangles)".format(cpuNanos / 300_000_000.0, w, h, g.triangleCount))
            frames = 0; cpuNanos = 0
        }
    }
}
