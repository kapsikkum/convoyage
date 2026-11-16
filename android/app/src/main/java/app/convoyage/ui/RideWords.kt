package app.convoyage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.AppContainer
import app.convoyage.api.Vehicle
import app.convoyage.data.RideMode

/**
 * What to call the trip for the vehicle in use: a motorcycle is ridden, a car is driven. Every screen takes its
 * wording from here, so one vehicle never gets both. Cars do not lean, so their lean readouts are hidden.
 */
data class RideWords(val bike: Boolean) {
    val noun: String get() = if (bike) "ride" else "drive"
    @get:JvmName("nounTitle") val Noun: String get() = if (bike) "Ride" else "Drive"
    val NOUN: String get() = if (bike) "RIDE" else "DRIVE"
    val verb: String get() = if (bike) "ride" else "drive"
    @get:JvmName("verbTitle") val Verb: String get() = if (bike) "Ride" else "Drive"
    val record: String get() = if (bike) "Record a ride" else "Record a drive"
    val track: String get() = if (bike) "Track ride" else "Track drive"
    val hud: String get() = if (bike) "Ride HUD" else "Drive HUD"
    val last: String get() = if (bike) "LAST RIDE" else "LAST DRIVE"
    val tab: String get() = if (bike) "Ride" else "Drive"
    /** One person, and people in general, on this vehicle. */
    val person: String get() = if (bike) "rider" else "driver"
    @get:JvmName("personTitle") val Person: String get() = if (bike) "Rider" else "Driver"
    val people: String get() = if (bike) "riders" else "drivers"
    @get:JvmName("peopleTitle") val People: String get() = if (bike) "Riders" else "Drivers"
    val recap: String get() = if (bike) "RIDE RECAP" else "DRIVE RECAP"
    val photos: String get() = if (bike) {
        "NO PHOTOS YET. RIDE PHOTOS LIVE ON EACH RIDE'S RECAP."
    } else {
        "NO PHOTOS YET. DRIVE PHOTOS LIVE ON EACH DRIVE'S RECAP."
    }

    /** Lean is a motorcycle readout; a car cannot lean. */
    val showsLean: Boolean get() = bike

    companion object {
        /**
         * [kind] is the primary garage vehicle's kind (`SocialDtos.Vehicle.kind`): "motorcycle" is a bike, any other
         * kind is a car. With no vehicle (or no kind), the saved onboarding mode decides.
         */
        fun of(kind: String?, mode: RideMode): RideWords =
            RideWords(if (kind == null) mode == RideMode.Ride else kind.equals("motorcycle", ignoreCase = true))

        /** The primary garage vehicle wins over the onboarding mode. */
        fun of(vehicle: Vehicle?, mode: RideMode): RideWords = of(vehicle?.kind, mode)
    }
}

/**
 * The words for the vehicle in use: the primary garage vehicle's kind, else the onboarding mode. Recomposes when
 * either changes (the garage loading after sign-in, or a new primary).
 */
@Composable
fun rideWords(): RideWords {
    val container = LocalContainer.current
    val mode by container.session.mode.collectAsStateWithLifecycle()
    val garage by container.garage.state.collectAsStateWithLifecycle()
    return remember(mode, garage.primary?.kind) { RideWords.of(garage.primary, mode) }
}

/** [rideWords] for code that is not composable (the foreground service and its notification). */
fun AppContainer.rideWordsNow(): RideWords = RideWords.of(garage.state.value.primary, session.mode.value)
