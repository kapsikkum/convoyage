package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos

/** One mapped road near you: its signed limit (km/h, null when unsigned) and its line as lng, lat pairs. */
data class LimitWay(val limitKmh: Int?, val name: String, val line: List<DoubleArray>)

/**
 * The signed speed limit of the road you are on, from OpenStreetMap `maxspeed` (through the server's road layer).
 * Loads the roads for a small box around you and matches each fix to the nearest one running your way; a new box
 * loads when you get near the edge. Unsigned roads show nothing rather than a guessed default.
 */
class SpeedLimits(private val scope: CoroutineScope, private val api: ConvoyApi) {
    private val _limit = MutableStateFlow<Int?>(null)
    val limit: StateFlow<Int?> = _limit.asStateFlow()
    private var ways: List<LimitWay> = emptyList()
    private var box: Bbox? = null
    private var job: Job? = null
    private var lastWay: LimitWay? = null

    fun onFix(lat: Double, lng: Double, headingDeg: Double?) {
        val b = box
        if (b == null || lat < b.south + EDGE || lat > b.north - EDGE || lng < b.west + EDGE || lng > b.east - EDGE) {
            if (job?.isActive != true) load(lat, lng)
        }
        val w = match(ways, lat, lng, headingDeg, lastWay)
        lastWay = w
        _limit.value = w?.limitKmh
    }

    fun clear() { job?.cancel(); ways = emptyList(); box = null; lastWay = null; _limit.value = null }

    private fun load(lat: Double, lng: Double) {
        val want = Bbox(lat - HALF, lng - HALF, lat + HALF, lng + HALF)
        job = scope.launch {
            when (val r = api.roads(want.south, want.west, want.north, want.east)) {
                is ApiResult.Ok -> { ways = parse(r.value); box = want }
                is ApiResult.Err -> Unit
            }
        }
    }

    companion object {
        private const val HALF = 0.03
        private const val EDGE = 0.008
        /** Further than this from every road: off the map's roads (car park, servo): no limit. */
        const val MAX_OFF_M = 25.0

        /** Pure: the server's GeoJSON road layer to ways. */
        fun parse(geojson: String): List<LimitWay> = runCatching {
            Json.parseToJsonElement(geojson).jsonObject["features"]?.jsonArray?.mapNotNull { f ->
                val o = f as? JsonObject ?: return@mapNotNull null
                val coords = o["geometry"]?.jsonObject?.get("coordinates") as? JsonArray ?: return@mapNotNull null
                val props = o["properties"]?.jsonObject
                val line = coords.mapNotNull { p ->
                    val a = p as? JsonArray ?: return@mapNotNull null
                    val x = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val y = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    doubleArrayOf(x, y)
                }
                if (line.size < 2) null
                else LimitWay(props?.get("maxspeed")?.jsonPrimitive?.intOrNull, props?.get("name")?.jsonPrimitive?.content ?: "", line)
            } ?: emptyList()
        }.getOrDefault(emptyList())

        /**
         * Pure: the way you are on. Distance to each segment in local metres, plus a penalty when the segment runs
         * across your heading (either direction along it is fine), and a small bonus for the way you were on, so a
         * side street you pass does not flicker in.
         */
        fun match(ways: List<LimitWay>, lat: Double, lng: Double, headingDeg: Double?, previous: LimitWay?): LimitWay? {
            val kx = 111_320.0 * cos(Math.toRadians(lat))
            val ky = 110_540.0
            var best: LimitWay? = null
            var bestScore = Double.MAX_VALUE
            for (w in ways) {
                for (i in 0 until w.line.size - 1) {
                    val ax = (w.line[i][0] - lng) * kx; val ay = (w.line[i][1] - lat) * ky
                    val bx = (w.line[i + 1][0] - lng) * kx; val by = (w.line[i + 1][1] - lat) * ky
                    val dx = bx - ax; val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 < 1e-6) continue
                    val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                    val d = Math.hypot(ax + dx * t, ay + dy * t)
                    if (d > MAX_OFF_M) continue
                    var score = d
                    if (headingDeg != null) {
                        val segDeg = Math.toDegrees(Math.atan2(dx, dy))
                        var diff = abs(((headingDeg - segDeg) % 180 + 180) % 180)
                        if (diff > 90) diff = 180 - diff
                        score += diff / 90.0 * 20.0
                    }
                    if (w === previous) score -= 4.0
                    if (score < bestScore) { bestScore = score; best = w }
                }
            }
            return best
        }
    }
}
