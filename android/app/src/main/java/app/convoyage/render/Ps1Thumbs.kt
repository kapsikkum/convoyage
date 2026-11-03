package app.convoyage.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The two thumbnail shapes. Sizes are the low-res render, which is later drawn scaled up with NEAREST filtering. */
enum class ThumbSize(val w: Int, val h: Int, val ground: Boolean, val zoom: Float, val yawDeg: Float) {
    /** Avatar badge: square, no ground, tight on the vehicle. */
    Badge(72, 72, false, 1.5f, -32f),

    /** Cards and lists: the same framing as the garage view, with the grid floor. */
    Card(192, 128, true, 1f, -32f),
}

/**
 * Static PS1 renders for everywhere except the garage view. ONE offscreen GL context on one thread renders each build
 * once into a Bitmap; the result is cached in memory and on disk under [Ps1Spec.key], so a vehicle is only ever drawn
 * again after its build (or the renderer version) changes.
 */
class VehicleThumbs(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "ps1")
    private val memory = object : LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val glThread: CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ps1-thumbs") }.asCoroutineDispatcher()
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var offscreen: Offscreen? = null
    private var failed = false

    /** Number of vehicles actually drawn by the GPU since launch (disk and memory hits do not count). For tests and the log. */
    @Volatile var renders = 0
        private set

    private fun keyOf(spec: Ps1Spec, size: ThumbSize) = spec.key(size.name)

    /** The thumbnail if it is already in memory. Cheap enough to call while composing. */
    fun cached(spec: Ps1Spec, size: ThumbSize): ImageBitmap? = memory.get(keyOf(spec, size))

    /** The thumbnail from memory, then disk, then the GPU. Null if the device cannot do GL ES 3. */
    suspend fun get(spec: Ps1Spec, size: ThumbSize): ImageBitmap? {
        val key = keyOf(spec, size)
        memory.get(key)?.let { return it }
        // The work belongs to this object, not to whichever composable asked first: it finishes (and is cached) even if that one leaves.
        val job = synchronized(inFlight) {
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        val r = try {
                            withContext(Dispatchers.IO) { readDisk(key) } ?: renderAndStore(spec, size, key)
                        } catch (e: Exception) {
                            Log.w("Ps1Thumbs", "thumbnail failed", e)
                            null
                        }
                        if (r != null) memory.put(key, r)
                        r
                    } finally {
                        synchronized(inFlight) { inFlight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    private suspend fun renderAndStore(spec: Ps1Spec, size: ThumbSize, key: String): ImageBitmap? {
        if (failed) return null
        val bmp = withContext(glThread) {
            try {
                val o = offscreen ?: Offscreen().also { offscreen = it }
                val t0 = System.nanoTime()
                val b = o.render(spec, size)
                renders++
                Log.d("Ps1Thumbs", "rendered %s %s in %.1f ms".format(spec.kind.wire, size.name, (System.nanoTime() - t0) / 1e6))
                b
            } catch (e: Throwable) {
                Log.w("Ps1Thumbs", "offscreen GL unavailable", e)
                failed = true
                null
            }
        } ?: return null
        withContext(Dispatchers.IO) { writeDisk(key, bmp) }
        return bmp.asImageBitmap()
    }

    private fun readDisk(key: String): ImageBitmap? {
        val f = File(dir, "$key.png")
        if (!f.isFile) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(key: String, bmp: Bitmap) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(File(dir, "$key.png"))
        } catch (_: Exception) {
        }
    }

    /** An EGL pbuffer context and a [Ps1Gl], made lazily on the GL thread. */
    private class Offscreen {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val gl = Ps1Gl()

        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
            val cfgAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no ES3 pbuffer config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            gl.init()
        }

        fun render(spec: Ps1Spec, size: ThumbSize): Bitmap {
            gl.setSpec(spec)
            gl.renderLowRes(size.w, size.h, size.yawDeg, size.ground, size.zoom)
            val px = gl.readPixels(size.w, size.h)
            return Bitmap.createBitmap(px, size.w, size.h, Bitmap.Config.ARGB_8888)
        }
    }
}
