package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.convoyage.api.Vehicle
import app.convoyage.api.VehicleOptions
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.design.cutPanel

fun kindIcon(kind: String): ImageVector = when (kind) {
    "motorcycle" -> CvIcons.Bike
    "ute" -> CvIcons.Ute
    "4wd" -> CvIcons.Fourwd
    else -> CvIcons.Car
}

/** `#RRGGBB` to a colour; null for anything else. */
fun parsePaint(hex: String?): Color? {
    val h = hex?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
}

/**
 * PLACEHOLDER for the 3D vehicle renderer (PS1-style low-poly model, 240p target, drag to rotate, pinch to zoom).
 * Until it exists this is a big chamfered card in the vehicle's paint colour with the vehicle kind's icon;
 * the finish only changes the sheen. Callers give the live editor values or a saved [Vehicle]'s build.
 *
 * TODO(garage-3d): replace the body with the renderer view (GLSurfaceView / Filament / custom GL) and feed it the
 * model for [kind], the paint, finish, wheel, ride height and decals.
 */
@Composable
fun VehicleViewport(
    kind: String,
    paintHex: String?,
    finish: String?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    caption: String? = null,
) {
    val c = Cv.colors
    val paint = parsePaint(paintHex) ?: c.surface300
    val ink = if (paint.luminance() > 0.45f) Color(0xFF05080D) else Color(0xFFE8F1FA)
    val sheen = when (finish) {
        "gloss" -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent, Color.Black.copy(alpha = 0.18f)))
        "satin" -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent, Color.Black.copy(alpha = 0.10f)))
        "metallic" -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Black.copy(alpha = 0.12f), Color.White.copy(alpha = 0.22f)))
        "pearl" -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.40f), c.signal.copy(alpha = 0.16f), Color(0xFFFF3D8B).copy(alpha = 0.14f)))
        else -> null // matte: flat
    }
    Box(modifier.cutPanel(paint, c.lineStrong, CutMShape)) {
        if (sheen != null) Box(Modifier.fillMaxSize().clip(CutMShape).background(sheen))
        Column(
            Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            CvIcon(kindIcon(kind), ink, size = if (large) 96.dp else 56.dp)
            if (large) CvText(VehicleOptions.kindLabel(kind).uppercase(), Cv.type.label, ink.copy(alpha = 0.8f))
        }
        Box(Modifier.align(Alignment.TopStart).padding(Space.s2).cutPanel(c.surface200, c.line, CutSShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
            // Marked on purpose: this is not the renderer.
            Eyebrow(if (large) "PLACEHOLDER · 3D RENDERER GOES HERE" else "PLACEHOLDER")
        }
        if (caption != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(Space.s2).cutPanel(c.surface200, c.line, CutSShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Eyebrow(caption)
            }
        }
    }
}

@Composable
fun VehicleViewport(vehicle: Vehicle, modifier: Modifier = Modifier, large: Boolean = false) =
    VehicleViewport(
        vehicle.kind, vehicle.build.paint, vehicle.build.finish, modifier, large,
        caption = vehicle.build.finish?.uppercase(),
    )

/** "Pearl white · Mesh · -30 mm" from a vehicle's build, for list rows. */
fun buildSummary(v: Vehicle): String {
    val b = v.build
    return listOfNotNull(
        VehicleOptions.paintName(b.paint).takeIf { b.paint != null },
        b.finish?.replaceFirstChar { it.uppercase() },
        b.wheel?.let { VehicleOptions.wheelLabel(it) },
        b.rideHeightMm?.takeIf { it != 0 }?.let { (if (it > 0) "+" else "") + "$it mm" },
    ).joinToString(" · ").ifBlank { "Stock" }
}

/** Mono number + small unit, like the feed's stat row. */
@Composable
fun StatText(value: String, unit: String) {
    val c = Cv.colors
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
        CvText(value, Cv.type.telemetry.copy(fontWeight = FontWeight.W600), c.ink)
        CvText(" $unit", Cv.type.label, c.inkMuted)
    }
}
