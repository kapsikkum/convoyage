package app.convoyage.render

import kotlin.math.floor
import kotlin.math.pow

/**
 * The PS1 colour pipeline in plain Kotlin. The fragment shader in [Ps1Shaders] does the same arithmetic on the GPU;
 * keeping a copy here is what lets the unit tests pin it down.
 */
object Ps1Math {
    /** 4x4 Bayer ordered-dither matrix, row-major (the same table the shader indexes). */
    val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** Threshold in [0, 1) for the pixel at ([x], [y]). */
    fun bayer(x: Int, y: Int): Float = BAYER4[(y and 3) * 4 + (x and 3)] / 16f

    /** The dither offset added to a channel before quantising: -0.5/24 up to +0.5/24 (about +-0.65 of a 5-bit step). */
    fun ditherOffset(x: Int, y: Int): Float = (bayer(x, y) - 0.5f) / 24f

    /** 15-bit colour: each channel rounded to one of 32 levels. */
    fun quantise15(c: Float): Float = floor(c.coerceIn(0f, 1f) * 31f + 0.5f) / 31f

    /** One channel through the whole output stage: dither, then 15-bit quantise. */
    fun shade(c: Float, x: Int, y: Int): Float = quantise15(c + ditherOffset(x, y))

    /** The 5-bit level (0..31) a channel ends up at. */
    fun level5(c: Float): Int = floor(c.coerceIn(0f, 1f) * 31f + 0.5f).toInt()

    /** Vertex snap: a clip-space coordinate already divided by w, moved to the nearest pixel centre of a [resolution]-pixel axis. */
    fun snap(ndc: Float, resolution: Int): Float {
        val g = resolution * 0.5f
        return floor(ndc * g + 0.5f) / g
    }

    /** sRGB byte (0..255) to linear 0..1. ps1.html feeds linear colours to the output unencoded, which is where the moody look comes from. */
    fun srgbToLinear(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Integer scale from the screen to the low-res target for a view [viewHeightPx] tall: about 240 internal rows. */
    fun pixelScale(viewHeightPx: Int, targetRows: Int = 240): Int = maxOf(1, Math.round(viewHeightPx / targetRows.toFloat()))
}
