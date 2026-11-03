package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.Build
import app.convoyage.api.ConvoyApi
import app.convoyage.api.CreateVehicle
import app.convoyage.api.Decal
import app.convoyage.api.RideSummary
import app.convoyage.api.UpdateVehicle
import app.convoyage.api.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GarageState(
    val vehicles: List<Vehicle> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    /** Vehicles the person still has, primary first. */
    val owned: List<Vehicle> get() = vehicles.filter { !it.isSold }.sortedByDescending { it.isPrimary }
    val sold: List<Vehicle> get() = vehicles.filter { it.isSold }
    val primary: Vehicle? get() = owned.firstOrNull { it.isPrimary } ?: owned.firstOrNull()
}

/** The signed-in person's garage (`/v1/vehicles`). Mutations answer with the server's vehicle and then reload, because the primary flag moves. */
class GarageRepository(private val scope: CoroutineScope, private val api: ConvoyApi) {
    private val _state = MutableStateFlow(GarageState())
    val state: StateFlow<GarageState> = _state.asStateFlow()

    private var catalogueLoaded = false

    /** The garage catalogue (paints, finishes, wheels) into [app.convoyage.api.VehicleOptions]; once per launch. */
    fun loadCatalogue() {
        if (catalogueLoaded) return
        catalogueLoaded = true
        scope.launch {
            when (val r = api.catalogue()) {
                is ApiResult.Ok -> app.convoyage.api.VehicleOptions.apply(r.value)
                is ApiResult.Err -> catalogueLoaded = false
            }
        }
    }

    fun refresh() {
        loadCatalogue()
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = api.listVehicles()) {
                is ApiResult.Ok -> _state.value = GarageState(vehicles = r.value, loaded = true)
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun clear() {
        _state.value = GarageState()
    }

    suspend fun create(body: CreateVehicle): ApiResult<Vehicle> = api.createVehicle(body).also { reloadIf(it) }

    suspend fun update(id: String, body: UpdateVehicle): ApiResult<Vehicle> = api.updateVehicle(id, body).also { reloadIf(it) }

    suspend fun makePrimary(id: String): ApiResult<Vehicle> = api.makePrimary(id).also { reloadIf(it) }

    suspend fun markSold(id: String): ApiResult<Vehicle> = api.markSold(id).also { reloadIf(it) }

    suspend fun delete(id: String): ApiResult<*> = api.deleteVehicle(id).also { reloadIf(it) }

    private fun reloadIf(r: ApiResult<*>) {
        if (r is ApiResult.Ok) {
            _state.update { it.copy(loading = false) }
            refresh()
        }
    }
}

/** Lifetime numbers over a set of finished rides. */
data class LifetimeStats(
    val rides: Int = 0,
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    val topSpeedMps: Double = 0.0,
    val longestM: Double = 0.0,
)

object RideTotals {
    /** Finished rides only; [vehicleId] = null means all of them. */
    fun of(rides: List<RideSummary>, vehicleId: String? = null): LifetimeStats {
        var n = 0
        var dist = 0.0
        var dur = 0L
        var top = 0.0
        var longest = 0.0
        for (r in rides) {
            if (!r.isFinished) continue
            if (vehicleId != null && r.vehicleId != vehicleId) continue
            n++
            val d = r.distanceM ?: 0.0
            dist += d
            dur += r.durationS ?: 0L
            top = maxOf(top, r.maxSpeedMps ?: 0.0)
            longest = maxOf(longest, d)
        }
        return LifetimeStats(n, dist, dur, top, longest)
    }
}

/** Build <-> editor fields. */
object BuildText {
    /** Decals are free text in the editor: comma-separated, each at most 64 characters, at most 24. */
    fun decalsOf(text: String): List<Decal> =
        text.split(',').map { it.trim().take(64) }.filter { it.isNotEmpty() }.distinct().take(24).map { Decal(id = it, slot = "text") }

    fun textOf(build: Build): String = build.decals.orEmpty().joinToString(", ") { it.id }
}
