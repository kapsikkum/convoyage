package app.convoyage.nav

import app.convoyage.data.Geo
import app.convoyage.data.LatLngD
import app.convoyage.data.LocalRoute
import app.convoyage.data.Polyline
import app.convoyage.protocol.Room

/** Which line the convoy map draws for a room. */
object RouteLine {
    /**
     * In order of preference:
     *  1. the room's `routeGeometry` (road-following, same for everybody);
     *  2. the road route this client fetched for the destination, if it is for this room and this destination;
     *  3. the shared stops (the leader's sampled route, or a straight leader-to-destination line).
     * An unreadable geometry is skipped rather than crashing the map.
     */
    fun pick(room: Room?, local: LocalRoute?): List<LatLngD> {
        if (room == null) return emptyList()
        room.routeGeometry?.takeIf { it.isNotBlank() }?.let { g ->
            val pts = try { Polyline.decode(g, 6) } catch (_: IllegalArgumentException) { emptyList() }
            if (pts.size >= 2) return pts
        }
        val dest = room.destination
        if (local != null && local.code == room.code && dest != null && Geo.distanceM(local.destination.lat, local.destination.lng, dest.lat, dest.lng) < 50.0) {
            return local.points
        }
        return room.route?.stops.orEmpty().filter { it.size >= 2 }.map { LatLngD(it[1], it[0]) }
    }
}
