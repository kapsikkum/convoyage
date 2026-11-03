package app.convoyage.render

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/** Tiny procedural textures, sampled with NEAREST. [argb] is row-major with row 0 at v = 0 (the bottom). */
class Ps1Texture(val size: Int, val argb: IntArray) {
    /** RGBA bytes for glTexImage2D. */
    fun rgbaBytes(): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val p = argb[i]
            out[i * 4] = (p shr 16).toByte()
            out[i * 4 + 1] = (p shr 8).toByte()
            out[i * 4 + 2] = p.toByte()
            out[i * 4 + 3] = (p ushr 24).toByte()
        }
        return out
    }
}

object Ps1Textures {
    const val BODY = 1
    const val RIM = 2
    const val GROUND = 3

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Fills a texture from a function of canvas coordinates (y = 0 is the top row, as in a 2D canvas). */
    private fun canvas(size: Int, f: (x: Int, y: Int) -> Int): Ps1Texture {
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[(size - 1 - y) * size + x] = f(x, y)
        return Ps1Texture(size, px)
    }

    /** Paint: speckle, a darker rocker panel along the bottom and a pale stripe at the beltline. Read through the paint tint. */
    fun body(): Ps1Texture = canvas(32) { x, y ->
        when (y) {
            in 26..31 -> rgb(150, 150, 150)
            17, 18 -> rgb(255, 255, 255)
            else -> {
                val v = 225 + ((x * 7 + y * 13) % 5) * 6
                rgb(v, v, v)
            }
        }
    }

    /** Dark navy tiles with a teal line down the left and top of each, tiled by the ground's UVs. */
    fun ground(): Ps1Texture = canvas(32) { x, y ->
        when {
            x == 0 && y == 0 -> rgb(0x2D, 0xE2, 0xE6)
            x == 0 || y == 0 -> rgb(0x2A, 0x7F, 0x8C)
            else -> rgb(0x16, 0x26, 0x3A)
        }
    }

    /**
     * A wheel face, tinted by the style's rim colour: white lip and spokes, dark gaps, grey hub, black outside the disc.
     * Drawn once per wheel style; UVs map the face as a circle, so the centre is (0.5, 0.5).
     */
    fun rim(w: Ps1Wheel): Ps1Texture = canvas(32) { x, y ->
        val dx = (x + 0.5f - 16f) / 16f
        val dy = (y + 0.5f - 16f) / 16f
        val r = sqrt(dx * dx + dy * dy)
        when {
            r > 1f -> rgb(17, 17, 17)
            r >= w.lip -> rgb(255, 255, 255)
            r < 0.16f -> rgb(110, 110, 110)
            w.solid -> {
                // Steel wheel: four slots round a dished face.
                val a = atan2(dy, dx)
                val phase = ((a / (2 * PI) * 4) % 1.0 + 1.0) % 1.0
                if (r in 0.42..0.62 && phase < 0.22) rgb(34, 34, 34) else rgb(225, 225, 225)
            }
            else -> {
                val a = atan2(dy, dx)
                val phase = ((a / (2 * PI) * w.spokes) % 1.0 + 1.0) % 1.0
                val spokeWidth = if (w.spokes >= 10) 0.5 else 0.38
                if (phase < spokeWidth) rgb(255, 255, 255) else rgb(30, 30, 30)
            }
        }
    }

    /** Linear colour (0..1 per channel) of an sRGB 0xRRGGBB, for the tints the shader multiplies. */
    fun linear(rgb: Int): FloatArray = floatArrayOf(
        Ps1Math.srgbToLinear((rgb shr 16) and 0xFF),
        Ps1Math.srgbToLinear((rgb shr 8) and 0xFF),
        Ps1Math.srgbToLinear(rgb and 0xFF),
    )
}
