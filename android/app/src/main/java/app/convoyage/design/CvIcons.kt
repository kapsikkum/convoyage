package app.convoyage.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun stroke(name: String, vararg d: String, fill: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = if (fill) null else SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** Line icons on a 24-unit grid, drawn after the mockup set. Tinted at the call site. */
object CvIcons {
    val Home = stroke("home", "M3.5 11 12 4l8.5 7", "M5.5 9.5V20h13V9.5")
    val Feed = stroke("feed", "M4 4h16v7H4z", "M4 14h16v6H4z")
    val Convoy = stroke(
        "convoy", circle(9f, 8f, 3.5f), "M2.5 20c.8-3.6 3.4-5.5 6.5-5.5s5.7 1.9 6.5 5.5",
        "M15.5 4.8a3.5 3.5 0 0 1 0 6.4", "M18 14.8c1.9.8 3 2.6 3.5 5.2",
    )
    val Garage = stroke("garage", "M3 21V9l9-5.5L21 9v12", "M7 21v-8h10v8M7 16h10")
    val Profile = stroke("profile", circle(12f, 8f, 4f), "M4 21c1-4 4-6 8-6s7 2 8 6")
    val Car = stroke(
        "car", "M3 16.5V13l2-5.2A2 2 0 0 1 6.9 6.5h10.2a2 2 0 0 1 1.9 1.3L21 13v3.5",
        "M3 13h18M2.5 16.5h19", circle(7f, 17.5f, 1.8f), circle(17f, 17.5f, 1.8f),
    )
    val Bike = stroke(
        "bike", circle(5.5f, 16.5f, 3.5f), circle(18.5f, 16.5f, 3.5f),
        "M5.5 16.5 9 10h5l4.5 6.5M9 10 7.5 7H5M14 10l-1.8-3H15M12 16.5l2-6.5",
    )
    val Chevron = stroke("chevron", "m9 6 6 6-6 6")
    val Back = stroke("back", "M15 5l-7 7 7 7")
    val Close = stroke("close", "M6 6l12 12M18 6 6 18")
    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val Navigate = stroke("navigate", "M12 2.5 19.5 21 12 16.8 4.5 21z", fill = true)
    val Alert = stroke("alert", "M12 3.5 22 20H2z", "M12 10v4.5M12 17.2v.3")
    val Stop = stroke("stop", "M5 5h14v14H5z")
    val Mic = stroke("mic", "M9 3h6v10a3 3 0 0 1-6 0z", "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")
    val Crown = stroke("crown", "M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5z", fill = true)
    val Locate = stroke("locate", circle(12f, 12f, 6f), "M12 2v4M12 18v4M2 12h4M18 12h4")
    val Settings = stroke("settings", circle(12f, 12f, 3f), "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M18.7 5.3l-2.1 2.1M7.4 16.6l-2.1 2.1")
    val Heart = stroke("heart", "M12 20S4 15 4 9.5A4.2 4.2 0 0 1 12 8a4.2 4.2 0 0 1 8 1.5C20 15 12 20 12 20z")
    val HeartFilled = stroke("heart_fill", "M12 20S4 15 4 9.5A4.2 4.2 0 0 1 12 8a4.2 4.2 0 0 1 8 1.5C20 15 12 20 12 20z", fill = true)
    val Comment = stroke("comment", "M4 5h16v11H9l-5 4z")
    val Camera = stroke("camera", "M3 8h4l2-3h6l2 3h4v11H3z", circle(12f, 13.5f, 3.5f))
    val Lock = stroke("lock", "M5 11h14v10H5z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Mail = stroke("mail", "M3 6h18v13H3z", "m3 7 9 7 9-7")
    val Cube = stroke("cube", "M12 3 4 7.5v9L12 21l8-4.5v-9z", "M4 7.5 12 12l8-4.5M12 12v9")
    val Share = stroke("share", "M12 4v12M7 9l5-5 5 5M5 20h14")
    val Minus = stroke("minus", "M5 12h14")
    val Rec = stroke("rec", circle(12f, 12f, 7f), fill = true)
    val Pause = stroke("pause", "M8.5 5v14M15.5 5v14")
    val Play = stroke("play", "M7 4.5v15l12.5-7.5z", fill = true)
    val Trash = stroke("trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6")
    val Edit = stroke("edit", "M4 20l1-5L16 4l4 4L9 19z", "M13.5 6.5l4 4")
    val Flag = stroke("flag", "M5 21V4M5 5h13l-2.5 4L18 13H5")
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7")
    val Image = stroke("image", "M3 5h18v14H3z", circle(8.5f, 10f, 1.8f), "M3 17l5-5 4 4 3-3 6 5")
    val Ute = stroke(
        "ute", "M2.5 17v-4.5L6 11l2.5-4H13v4h8.5V17M2.5 17h19", circle(7f, 17.5f, 1.8f), circle(17f, 17.5f, 1.8f),
    )
    val Fourwd = stroke(
        "fourwd", "M3 17V11l2-4.5h11l3.5 4.5H21v6M3 17h18M5 11h14.5M10.5 6.5V11", circle(7.5f, 17.5f, 2.2f), circle(16.5f, 17.5f, 2.2f),
    )
    // QR code glyph: three finder squares (each with its dot) and a few data modules, like the real thing.
    val Qr = stroke(
        "qr", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z",
        "M7 7h.01", "M17 7h.01", "M7 17h.01",
        "M14 14h2v2h-2z", "M18 14h2M14 18h2v2M18 18h2v2",
    )
    val Search = stroke("search", circle(10.5f, 10.5f, 6f), "M15 15l5.5 5.5")
    val VolumeOn = stroke("volume_on", "M4 9.5v5h4l5 4v-13l-5 4z", "M16.5 9a4 4 0 0 1 0 6", "M19 6.5a7.5 7.5 0 0 1 0 11")
    val VolumeOff = stroke("volume_off", "M4 9.5v5h4l5 4v-13l-5 4z", "M17 9.5l5 5M22 9.5l-5 5")

    // Maneuver arrows for the navigation card (24 grid, heading up the screen).
    val ArrowStraight = stroke("arrow_straight", "M12 20V5", "M6.5 10.5 12 5l5.5 5.5")
    val ArrowRight = stroke("arrow_right", "M7 20v-8a3 3 0 0 1 3-3h8", "M14 5l4 4-4 4")
    val ArrowLeft = stroke("arrow_left", "M17 20v-8a3 3 0 0 0-3-3H6", "M10 5 6 9l4 4")
    val ArrowSlightRight = stroke("arrow_slight_right", "M8 20v-5l8-8", "M10.5 6.5H17V13")
    val ArrowSlightLeft = stroke("arrow_slight_left", "M16 20v-5L8 7", "M13.5 6.5H7V13")
    val ArrowSharpRight = stroke("arrow_sharp_right", "M6 6l10 10", "M16 9v7H9")
    val ArrowSharpLeft = stroke("arrow_sharp_left", "M18 6 8 16", "M8 9v7h7")
    val ArrowUTurn = stroke("arrow_uturn", "M7 20V9a4 4 0 0 1 8 0v8", "M11.5 13.5 15 17l3.5-3.5")
    val ArrowRoundabout = stroke("arrow_roundabout", circle(12f, 15.5f, 4f), "M12 11.5V4.5", "M9 7.5l3-3 3 3")
    val ArrowForkLeft = stroke("arrow_fork_left", "M12 20v-7l-5-5", "M12 13l5-5", "M7 13V8h5")
    val ArrowForkRight = stroke("arrow_fork_right", "M12 20v-7l5-5", "M12 13 7 8", "M17 13V8h-5")
    val ArrowMerge = stroke("arrow_merge", "M8 20v-4l4-4V5", "M16 20v-4l-4-4", "M8.5 8.5 12 5l3.5 3.5")
    val Pin = stroke("pin", "M12 21s-6-5.6-6-10.5a6 6 0 0 1 12 0C18 15.4 12 21 12 21z", circle(12f, 10.5f, 2f))
    val Route = stroke("route", circle(6f, 18f, 2.5f), circle(18f, 6f, 2.5f), "M8.5 18H15a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h6.5")
}

/** Draws a [CvIcons] vector tinted with [tint]. */
@Composable
fun CvIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
