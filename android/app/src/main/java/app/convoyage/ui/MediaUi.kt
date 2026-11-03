package app.convoyage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.convoyage.data.ImageSource
import app.convoyage.data.LatLngD
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
import app.convoyage.design.Eyebrow
import app.convoyage.design.cutPanel
import app.convoyage.design.gridBackdrop
import kotlin.math.cos
import kotlin.math.max

/** A picture from the phone or the server, decoded to about [maxEdgePx] and cached. Blank grey until it arrives. */
@Composable
fun CvImage(
    source: ImageSource,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = 600,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val loader = LocalContainer.current.images
    var bitmap by remember(source.key, maxEdgePx) { mutableStateOf(loader.cached(source, maxEdgePx)) }
    LaunchedEffect(source.key, maxEdgePx) {
        if (bitmap == null) bitmap = loader.load(source, maxEdgePx)
    }
    Box(modifier.background(Cv.colors.surface300), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}

/**
 * The route as a line in signal cyan on the grid backdrop, scaled to fit. [points] null = still loading.
 * This is the "static preview" for feed cards and the Routes grid: a Canvas path, no map view per card.
 */
@Composable
fun RoutePreview(points: List<LatLngD>?, modifier: Modifier = Modifier, showEnds: Boolean = true) {
    val c = Cv.colors
    Box(modifier.cutPanel(c.surface100, c.line, CutMShape).gridBackdrop(c.line.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        when {
            points == null -> Eyebrow("LOADING ROUTE")
            points.size < 2 -> Eyebrow("NO TRACE")
            else -> Canvas(Modifier.fillMaxSize()) {
                val pad = 16.dp.toPx()
                val pts = RouteProjection.project(points, size.width - 2 * pad, size.height - 2 * pad)
                if (pts.isEmpty()) return@Canvas
                val path = Path().apply {
                    pts.forEachIndexed { i, p ->
                        val x = p.x + pad
                        val y = p.y + pad
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(path, c.signal.copy(alpha = 0.18f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, c.signal, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (showEnds) {
                    val a = pts.first()
                    val b = pts.last()
                    drawCircle(c.go, 5.dp.toPx(), Offset(a.x + pad, a.y + pad))
                    drawCircle(c.pulse, 5.dp.toPx(), Offset(b.x + pad, b.y + pad))
                }
            }
        }
    }
}

/** Fits a lat/lng line into a box without squashing it (longitude shrinks with latitude). */
object RouteProjection {
    fun project(points: List<LatLngD>, width: Float, height: Float): List<Offset> {
        if (points.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        val k = cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        val w = ((maxLng - minLng) * k).toFloat()
        val h = (maxLat - minLat).toFloat()
        val span = max(max(w, h), 1e-9f)
        val scale = minOf(width / (if (w > 0) w else span), height / (if (h > 0) h else span)).let { s ->
            // A straight line has zero width or height: scale by the long side only.
            if (w == 0f || h == 0f) minOf(width, height) / span else s
        }
        val offX = (width - w * scale) / 2
        val offY = (height - h * scale) / 2
        return points.map { Offset(((it.lng - minLng) * k).toFloat() * scale + offX, (maxLat - it.lat).toFloat() * scale + offY) }
    }
}
