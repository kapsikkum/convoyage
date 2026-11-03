package app.convoyage.data

import android.content.Context
import app.convoyage.nav.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The two saved places the Drive dock offers one tap away (Konvoi's "Home / Work"). */
enum class SavedSlot(val label: String) { Home("Home"), Work("Work") }

@Serializable
private data class StoredPlace(val name: String, val detail: String = "", val lat: Double, val lng: Double) {
    fun toPlace() = Place(name, detail, lat, lng)
}

private fun Place.stored() = StoredPlace(name, detail, lat, lng)

/** Pure: put [p] first, drop any earlier entry within ~50 m of it, keep at most [max]. */
fun pushRecent(list: List<Place>, p: Place, max: Int = 8): List<Place> =
    (listOf(p) + list.filterNot { Geo.distanceM(it.lat, it.lng, p.lat, p.lng) < 50.0 }).take(max)

/** Saved places and recent destinations, on this device only. */
class PlacesStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(key: String): Place? = prefs.getString(key, null)?.let { runCatching { json.decodeFromString(StoredPlace.serializer(), it).toPlace() }.getOrNull() }

    private val _saved = MutableStateFlow(SavedSlot.entries.mapNotNull { s -> read("slot.${s.name}")?.let { s to it } }.toMap())
    val saved: StateFlow<Map<SavedSlot, Place>> = _saved.asStateFlow()

    private val _recent = MutableStateFlow(
        prefs.getString("recent", null)?.let { s -> runCatching { json.decodeFromString(ListSerializer(StoredPlace.serializer()), s).map { it.toPlace() } }.getOrNull() }.orEmpty(),
    )
    val recent: StateFlow<List<Place>> = _recent.asStateFlow()

    fun save(slot: SavedSlot, place: Place) {
        prefs.edit().putString("slot.${slot.name}", json.encodeToString(StoredPlace.serializer(), place.stored())).apply()
        _saved.value = _saved.value + (slot to place)
    }

    fun clear(slot: SavedSlot) {
        prefs.edit().remove("slot.${slot.name}").apply()
        _saved.value = _saved.value - slot
    }

    fun remember(place: Place) {
        val next = pushRecent(_recent.value, place)
        _recent.value = next
        prefs.edit().putString("recent", json.encodeToString(ListSerializer(StoredPlace.serializer()), next.map { it.stored() })).apply()
    }

    fun forget(place: Place) {
        val next = _recent.value.filterNot { it == place }
        _recent.value = next
        prefs.edit().putString("recent", json.encodeToString(ListSerializer(StoredPlace.serializer()), next.map { it.stored() })).apply()
    }
}
