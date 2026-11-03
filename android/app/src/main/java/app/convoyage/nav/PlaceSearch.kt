package app.convoyage.nav

import app.convoyage.api.ApiResult
import app.convoyage.data.LatLngD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Turns typed text into places, and a dropped pin into a name. */
interface PlaceSearch {
    suspend fun search(query: String, near: LatLngD?): ApiResult<List<Place>>
    suspend fun reverse(lat: Double, lng: Double): ApiResult<Place>
}

/**
 * Nominatim (OpenStreetMap) search. The usage policy asks for an identifying User-Agent and at most one request per
 * second, so every call waits its turn behind [minGapMs] and the UI debounces on top of that.
 */
class NominatimSearch(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val minGapMs: Long = 1_100,
    private val now: () -> Long = System::currentTimeMillis,
) : PlaceSearch {
    private val gate = Mutex()
    private var lastAt = 0L

    override suspend fun search(query: String, near: LatLngD?): ApiResult<List<Place>> {
        val q = query.trim()
        if (q.length < 2) return ApiResult.Ok(emptyList())
        val url = searchUrl(baseUrl, q, near)
        return call(url) { body -> ApiResult.Ok(parseSearch(body)) }
    }

    override suspend fun reverse(lat: Double, lng: Double): ApiResult<Place> {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("reverse")
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", String.format(Locale.US, "%.6f", lat))
            .addQueryParameter("lon", String.format(Locale.US, "%.6f", lng))
            .addQueryParameter("zoom", "18")
            .build()
        return call(url) { body ->
            val obj = (JSON.parseToJsonElement(body) as? JsonObject)
            val place = obj?.let { toPlace(it) }
            if (place == null) ApiResult.Err(404, "No address here") else ApiResult.Ok(place.copy(lat = lat, lng = lng))
        }
    }

    private suspend fun <T> call(url: HttpUrl, parse: (String) -> ApiResult<T>): ApiResult<T> = gate.withLock {
        val wait = minGapMs - (now() - lastAt)
        if (wait > 0) delay(wait)
        lastAt = now()
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en")
                    .build()
                client.newCall(request).execute().use { res ->
                    val text = res.body.string()
                    when {
                        res.code == 429 -> ApiResult.Err(429, "Search is busy, try again in a moment")
                        !res.isSuccessful -> ApiResult.Err(res.code, "Search answered ${res.code}")
                        else -> try {
                            parse(text)
                        } catch (_: Exception) {
                            ApiResult.Err(502, "Unexpected search response")
                        }
                    }
                }
            } catch (e: IOException) {
                ApiResult.Err(0, "Cannot reach the search service")
            }
        }
    }

    companion object {
        const val USER_AGENT = "Convoyage/0.1"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Roughly +-1.5 degrees (about 150 km) around the user: results inside it come first. */
        private const val BIAS_DEG = 1.5

        fun searchUrl(baseUrl: String, query: String, near: LatLngD?): HttpUrl {
            val b = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("q", query)
                .addQueryParameter("limit", "8")
            if (near != null) {
                // viewbox = left,top,right,bottom as lon,lat. Not bounded: it only biases the ranking.
                b.addQueryParameter(
                    "viewbox",
                    String.format(
                        Locale.US, "%.4f,%.4f,%.4f,%.4f",
                        near.lng - BIAS_DEG, near.lat + BIAS_DEG, near.lng + BIAS_DEG, near.lat - BIAS_DEG,
                    ),
                )
            }
            return b.build()
        }

        fun parseSearch(body: String): List<Place> {
            val arr = JSON.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            return arr.mapNotNull { (it as? JsonObject)?.let(::toPlace) }
        }

        private fun toPlace(o: JsonObject): Place? {
            val lat = (o["lat"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
            val lng = (o["lon"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
            val display = (o["display_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: display.substringBefore(',').trim()
            if (name.isEmpty()) return null
            val detail = display.substringAfter(',', "").trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
            return Place(name, detail, lat, lng)
        }
    }
}
