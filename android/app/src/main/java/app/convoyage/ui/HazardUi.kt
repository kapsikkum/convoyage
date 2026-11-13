package app.convoyage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.HazardKinds
import app.convoyage.data.Geo
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CvIcons
import app.convoyage.design.PinKind
import app.convoyage.map.MapPinData
import app.convoyage.protocol.Hazard
import app.convoyage.protocol.isPolice
import kotlinx.coroutines.delay

/** "Debris", "Speed camera", "Road works"... from the server's kind + subkind. */
fun hazardLabel(kind: String, subkind: String): String {
    if (subkind.isNotBlank()) {
        return subkind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    return when (kind) {
        HazardKinds.POLICE -> "Police"
        HazardKinds.HAZARD -> "Hazard"
        HazardKinds.WORKS -> "Road works"
        HazardKinds.FUEL -> "Fuel"
        HazardKinds.REST -> "Rest stop"
        HazardKinds.CRASH -> "Crash"
        else -> kind.replaceFirstChar { it.uppercase() }
    }
}

/** The map pin for a hazard: hazard amber, or police blue for police reports. */
fun Hazard.toPin() = MapPinData(
    id = "hazard-$id",
    kind = if (isPolice) PinKind.Police else PinKind.Hazard,
    label = "",
    lat = lat,
    lng = lng,
)

/**
 * Glass banner for the newest hazard a convoy member reported: hazard amber (police blue for police) with
 * Confirm ("still there") and Dismiss ("not there"). Shows nothing when there is no unanswered report.
 */
@Composable
fun HazardAlertBanner(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val alert by container.hazards.alert.collectAsStateWithLifecycle()
    val fix by container.tracker.fix.collectAsStateWithLifecycle()
    val a = alert ?: return
    val h = a.hazard

    // Nobody is going to answer a banner they did not see; do not leave it up for ever.
    LaunchedEffect(h.id) {
        delay(30_000)
        container.hazards.clearAlert(h.id)
    }

    val away = fix?.let { Geo.formatDistance(Geo.distanceM(it.lat, it.lng, h.lat, h.lng)) + " away" }
    Banner(
        tone = if (h.isPolice) BannerTone.Police else BannerTone.Hazard,
        title = hazardLabel(h.kind, h.subkind) + " reported",
        sub = a.error ?: listOfNotNull(away, "Still there?").joinToString(" · "),
        icon = CvIcons.Alert,
        modifier = modifier,
        actionLabel = "Confirm",
        onAction = { container.hazards.confirm(h.id) },
        secondaryLabel = "Dismiss",
        onSecondary = { container.hazards.dismiss(h.id) },
    )
}
