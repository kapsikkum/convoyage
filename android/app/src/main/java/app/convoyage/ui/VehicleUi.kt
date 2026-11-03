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
import app.convoyage.render.Ps1Spec
import app.convoyage.render.ThumbSize
import androidx.compose.ui.layout.ContentScale
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
 * The vehicle on its grid floor in a chamfered panel: the PS1 renderer.
 *
 * [live] is the garage view (a GL surface that turns on its own and under a finger, following [spec] as the build is
 * edited). Everything else passes false and gets the static render from the shared offscreen renderer.
 */
@Composable
fun VehicleViewport(
    spec: Ps1Spec,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    caption: String? = null,
) {
    val c = Cv.colors
    Box(modifier.cutPanel(c.void, c.lineStrong, CutMShape)) {
        if (live) {
            Ps1Live(spec, Modifier.fillMaxSize().padding(2.dp))
        } else {
            Ps1Thumb(
                spec, ThumbSize.Card, Modifier.fillMaxSize().padding(2.dp), ContentScale.Fit,
                fallback = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CvIcon(kindIcon(spec.kind.wire), parsePaint("#%06x".format(spec.paint)) ?: c.inkMuted, size = 48.dp)
                    }
                },
            )
        }
        if (live) {
            Box(Modifier.align(Alignment.TopStart).padding(Space.s2).cutPanel(c.surface200, c.line, CutSShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Eyebrow("DRAG TO TURN")
            }
        }
        if (caption != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(Space.s2).cutPanel(c.surface200, c.line, CutSShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Eyebrow(caption)
            }
        }
    }
}

@Composable
fun VehicleViewport(vehicle: Vehicle, modifier: Modifier = Modifier, live: Boolean = false) =
    VehicleViewport(vehicle.ps1(), modifier, live, caption = vehicle.build.finish?.uppercase())

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
