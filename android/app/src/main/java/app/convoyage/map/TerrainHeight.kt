package app.convoyage.map

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Ground height in metres from the same Terrarium tiles the 3D terrain uses (height = R*256 + G + B/256 - 32768).
 * One z12 tile (~10 km across) covers a lot of driving, and the last few are kept in memory.
 */
object TerrainHeight {
    private const val Z = 12
    private val http = OkHttpClient()
    private val tiles = object : LinkedHashMap<String, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 6
    }

    /** Pure decode of one Terrarium pixel; public for tests. */
    fun decode(argb: Int): Double = ((argb shr 16) and 0xFF) * 256.0 + ((argb shr 8) and 0xFF) + (argb and 0xFF) / 256.0 - 32768.0

    /** Tile x/y and the pixel inside a 256 px tile for a point at zoom [Z]. */
    fun locate(lat: Double, lng: Double): IntArray {
        val n = 1 shl Z
        val x = (lng + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val y = (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n
        val tx = floor(x).toInt(); val ty = floor(y).toInt()
        return intArrayOf(tx, ty, ((x - tx) * 256).toInt().coerceIn(0, 255), ((y - ty) * 256).toInt().coerceIn(0, 255))
    }

    suspend fun at(lat: Double, lng: Double): Double? = withContext(Dispatchers.IO) {
        val (tx, ty, px, py) = locate(lat, lng).let { listOf(it[0], it[1], it[2], it[3]) }
        val key = "$tx/$ty"
        val pixels = synchronized(tiles) { tiles[key] } ?: runCatching {
            val url = MapNightStyle.DEM_TILES.replace("{z}", "$Z").replace("{x}", "$tx").replace("{y}", "$ty")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val bmp = BitmapFactory.decodeStream(r.body?.byteStream()) ?: return@runCatching null
                IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            }
        }.getOrNull()?.also { synchronized(tiles) { tiles[key] = it } } ?: return@withContext null
        decode(pixels[py * 256 + px])
    }
}
