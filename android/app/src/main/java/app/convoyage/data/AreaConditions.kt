package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.AreaConditionsDto
import app.convoyage.api.ConditionItem
import app.convoyage.api.ConvoyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AreaState(
    /** Police, cameras, hazards and traffic from TfNSW and Waze (our own reports come from [HazardRepository]). */
    val items: List<ConditionItem> = emptyList(),
    /** Waze is still scanning this area: more may arrive. */
    val scanning: Boolean = false,
    /** Waze is set up on this server. */
    val waze: Boolean = false,
)

/**
 * What is on the roads around the map view, for the free-roaming map: loaded for a box a bit bigger than the view,
 * refreshed every minute while it is shown, and again after a few seconds while Waze is still scanning a new area.
 */
class AreaConditions(
    private val scope: CoroutineScope,
    private val api: ConvoyApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 60_000L,
) {
    private val _state = MutableStateFlow(AreaState())
    val state: StateFlow<AreaState> = _state.asStateFlow()
    private var box: Bbox? = null
    private var at = 0L
    private var job: Job? = null
    /** The box being loaded: views inside it wait for it rather than starting again (the follow camera moves a lot). */
    private var loading: Bbox? = null

    fun clear() { job?.cancel(); box = null; loading = null; at = 0; _state.value = AreaState() }

    /** The map came to rest on [view]. Off clears what was shown. */
    fun onViewport(view: Bbox, on: Boolean) {
        if (!on) { if (_state.value.items.isNotEmpty() || box != null) clear(); return }
        val loaded = box
        if (loaded != null && loaded.covers(view) && now() - at < maxAgeMs) return
        if (job?.isActive == true && loading?.covers(view) == true) return
        // Zoomed far out there is too much to show and the server refuses: keep what we have.
        val want = view.grown(0.25).atLeast(0.06)
        if (want.north - want.south > AREA_MAX_DEG || want.east - want.west > AREA_MAX_DEG) return
        load(want)
    }

    private fun load(want: Bbox) {
        job?.cancel()
        loading = want
        job = scope.launch {
            // Waze pans its own map to a new area first: ask again while it scans (up to ~40 s).
            for (attempt in 0..8) {
                val r = api.areaConditions(want)
                when (r) {
                    is ApiResult.Ok -> {
                        box = want; at = now()
                        val pending = r.value.sources.waze == "pending"
                        val fresh = visible(r.value)
                        // While Waze scans, keep what it showed before (in this box) rather than blanking the map.
                        val kept = if (pending) _state.value.items.filter { it.source == "waze" && want.contains(it.lat, it.lng) } else emptyList()
                        _state.value = AreaState(items = (fresh + kept).distinctBy { it.id }, scanning = pending, waze = r.value.sources.waze != "off")
                        if (!pending) break
                        delay(5_000)
                    }
                    is ApiResult.Err -> break // keep what is shown; the next viewport change tries again
                }
            }
            loading = null
        }
    }

    companion object {
        /** The server's limit, degrees a side. */
        const val AREA_MAX_DEG = 0.3

        /** Pure: what the layer draws. Our own reports are already on the map, with voting. */
        fun visible(dto: AreaConditionsDto): List<ConditionItem> =
            dto.items.filter { it.source != "convoyage" }.distinctBy { it.id }
    }
}
