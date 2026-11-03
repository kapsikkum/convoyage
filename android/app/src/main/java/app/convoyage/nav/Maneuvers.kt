package app.convoyage.nav

import java.util.Locale

/** The arrow glyphs the maneuver card can show. The drawing lives in `design/CvIcons`; this is the choice, kept pure for tests. */
enum class ManeuverIcon {
    Depart, Arrive, Straight,
    SlightLeft, Left, SharpLeft, SlightRight, Right, SharpRight,
    UTurn, Roundabout, ForkLeft, ForkRight, Merge,
}

/** OSRM maneuver `type` + `modifier` to an icon, a spoken phrase and a "then ..." line. */
object Maneuvers {
    fun icon(type: String, modifier: String?): ManeuverIcon {
        val m = modifier?.lowercase(Locale.ROOT)
        return when (type.lowercase(Locale.ROOT)) {
            "arrive" -> ManeuverIcon.Arrive
            "depart" -> ManeuverIcon.Depart
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> ManeuverIcon.Roundabout
            "fork" -> when (m) {
                "left", "slight left", "sharp left" -> ManeuverIcon.ForkLeft
                "right", "slight right", "sharp right" -> ManeuverIcon.ForkRight
                else -> ManeuverIcon.Straight
            }
            "merge" -> ManeuverIcon.Merge
            // turn, end of road, on ramp, off ramp, new name, continue, notification, and anything OSRM adds later.
            else -> byModifier(m)
        }
    }

    private fun byModifier(m: String?): ManeuverIcon = when (m) {
        "uturn" -> ManeuverIcon.UTurn
        "sharp left" -> ManeuverIcon.SharpLeft
        "left" -> ManeuverIcon.Left
        "slight left" -> ManeuverIcon.SlightLeft
        "sharp right" -> ManeuverIcon.SharpRight
        "right" -> ManeuverIcon.Right
        "slight right" -> ManeuverIcon.SlightRight
        else -> ManeuverIcon.Straight
    }

    fun icon(step: RouteStep): ManeuverIcon = icon(step.type, step.modifier)

    /** "Right onto Spring St" style headline for the card. */
    fun headline(step: RouteStep): String {
        val action = when (icon(step)) {
            ManeuverIcon.Arrive -> return "Arrive" + if (step.name.isNotBlank()) " at ${step.name}" else ""
            ManeuverIcon.Depart -> "Head"
            ManeuverIcon.Straight -> "Continue"
            ManeuverIcon.SlightLeft -> "Slight left"
            ManeuverIcon.Left -> "Left"
            ManeuverIcon.SharpLeft -> "Sharp left"
            ManeuverIcon.SlightRight -> "Slight right"
            ManeuverIcon.Right -> "Right"
            ManeuverIcon.SharpRight -> "Sharp right"
            ManeuverIcon.UTurn -> "U-turn"
            ManeuverIcon.Roundabout -> "Roundabout"
            ManeuverIcon.ForkLeft -> "Keep left"
            ManeuverIcon.ForkRight -> "Keep right"
            ManeuverIcon.Merge -> "Merge"
        }
        return if (step.name.isBlank()) action else "$action onto ${step.name}"
    }

    /** The text to say for a maneuver, without a distance: the server's instruction, else one built from the step. */
    fun spoken(step: RouteStep): String {
        val text = step.instruction.trim()
        if (text.isNotEmpty()) return text
        return when (icon(step)) {
            ManeuverIcon.Arrive -> "You have arrived"
            ManeuverIcon.UTurn -> "Make a U-turn"
            ManeuverIcon.Roundabout -> "At the roundabout, take the exit" + onto(step)
            ManeuverIcon.Straight, ManeuverIcon.Depart -> "Continue straight" + onto(step)
            else -> "Turn " + headline(step).substringBefore(" onto").lowercase(Locale.ROOT) + onto(step)
        }
    }

    private fun onto(step: RouteStep) = if (step.name.isBlank()) "" else " onto ${step.name}"

    /** "then left · Flinders St" for the line under the card. */
    fun then(step: RouteStep): String {
        val what = when (icon(step)) {
            ManeuverIcon.Arrive -> "arrive"
            ManeuverIcon.UTurn -> "U-turn"
            ManeuverIcon.Roundabout -> "roundabout"
            ManeuverIcon.Straight, ManeuverIcon.Depart -> "straight"
            ManeuverIcon.ForkLeft -> "keep left"
            ManeuverIcon.ForkRight -> "keep right"
            ManeuverIcon.Merge -> "merge"
            else -> headline(step).substringBefore(" onto").lowercase(Locale.ROOT)
        }
        return "then $what" + if (step.name.isNotBlank()) " · ${step.name}" else ""
    }
}
