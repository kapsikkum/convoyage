package app.convoyage.data

/** A WGS84 position without the extras a [TracePoint] carries. */
data class LatLngD(val lat: Double, val lng: Double)

/**
 * Google encoded polyline codec, the trace format of `POST /v1/rides/{id}/finish` and `Ride.polyline`
 * (server/src/api/polyline.ts is the reference). [precision] is 5 (about 1 m) or 6 (about 0.1 m).
 */
object Polyline {
    fun encode(points: List<LatLngD>, precision: Int = 5): String {
        require(precision == 5 || precision == 6) { "precision must be 5 or 6" }
        val factor = Math.pow(10.0, precision.toDouble())
        val out = StringBuilder(points.size * 8)
        var prevLat = 0L
        var prevLng = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lng = Math.round(p.lng * factor)
            put(out, lat - prevLat)
            put(out, lng - prevLng)
            prevLat = lat
            prevLng = lng
        }
        return out.toString()
    }

    /** @throws IllegalArgumentException for a truncated string, a character outside `?`..`~`, or an out-of-range point. */
    fun decode(encoded: String, precision: Int = 5): List<LatLngD> {
        require(precision == 5 || precision == 6) { "precision must be 5 or 6" }
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<LatLngD>(encoded.length / 6)
        var index = 0
        var lat = 0L
        var lng = 0L

        fun next(): Long {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                require(index < encoded.length) { "truncated polyline" }
                b = encoded[index++].code - 63
                require(b in 0..63) { "bad polyline character" }
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                require(shift <= 35) { "polyline number too long" }
            } while (b >= 0x20)
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }

        while (index < encoded.length) {
            lat += next()
            lng += next()
            val p = LatLngD(lat / factor, lng / factor)
            require(Math.abs(p.lat) <= 90 && Math.abs(p.lng) <= 180) { "polyline point out of range" }
            points += p
        }
        return points
    }

    private fun put(out: StringBuilder, value: Long) {
        var n = if (value < 0) (value shl 1).inv() else value shl 1
        while (n >= 0x20) {
            out.append((((n and 0x1f) or 0x20) + 63).toInt().toChar())
            n = n shr 5
        }
        out.append((n + 63).toInt().toChar())
    }
}
