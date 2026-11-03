package app.convoyage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.convoyage.AppContainer
import app.convoyage.api.ApiResult
import app.convoyage.data.LatLngD
import app.convoyage.data.Link
import app.convoyage.data.Names
import app.convoyage.nav.NavPhase
import app.convoyage.nav.Place
import app.convoyage.protocol.Destination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The destination search box: what was typed, and what the search service answered. */
data class SearchUi(
    val loading: Boolean = false,
    val results: List<Place> = emptyList(),
    val error: String? = null,
    /** The text the results are for. */
    val forQuery: String = "",
)

/** Screen-only state of the navigation screen. */
data class NavLocal(
    val follow: Boolean = true,
    val buildings3d: Boolean = true,
    val recenterTick: Int = 0,
    /** "Invite friends" was pressed: open the share sheet as soon as the convoy exists. */
    val shareWhenReady: Boolean = false,
    val hint: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NavViewModel(private val container: AppContainer) : ViewModel() {
    private val nav = container.nav
    val query = MutableStateFlow("")

    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search.asStateFlow()

    private val _local = MutableStateFlow(NavLocal())
    val local: StateFlow<NavLocal> = _local.asStateFlow()

    init {
        // Typing is debounced here, and NominatimSearch spaces the requests out to one a second on top of that.
        viewModelScope.launch {
            query.debounce(700).map { it.trim() }.distinctUntilChanged().collectLatest { q ->
                if (q.length < 3) {
                    _search.value = SearchUi()
                    return@collectLatest
                }
                _search.update { it.copy(loading = true, error = null, forQuery = q) }
                val near = container.tracker.lastKnown()?.let { LatLngD(it.lat, it.lng) }
                when (val r = container.places.search(q, near)) {
                    is ApiResult.Ok -> _search.value = SearchUi(false, r.value, if (r.value.isEmpty()) "Nothing found for \"$q\"" else null, q)
                    is ApiResult.Err -> _search.value = SearchUi(false, emptyList(), r.message, q)
                }
            }
        }
    }

    fun select(place: Place) {
        query.value = ""
        _search.value = SearchUi()
        _local.update { it.copy(follow = true) }
        if (place.name != "Dropped pin") container.savedPlaces.remember(place)
        nav.navigateTo(place, container.tracker.lastKnown())
    }

    /** A long press on the map: route there now, and look up the address in the background to name it. */
    fun pick(lat: Double, lng: Double) {
        val pin = Place("Dropped pin", "%.4f, %.4f".format(java.util.Locale.ROOT, lat, lng), lat, lng)
        select(pin)
        viewModelScope.launch {
            val r = container.places.reverse(lat, lng)
            if (r is ApiResult.Ok) {
                nav.relabel(lat, lng, r.value.name, r.value.detail)
                container.savedPlaces.remember(Place(r.value.name, r.value.detail, lat, lng))
            }
        }
    }

    fun start() {
        _local.update { it.copy(follow = true, recenterTick = it.recenterTick + 1) }
        nav.start()
    }

    fun end() {
        nav.end()
        _local.update { NavLocal(buildings3d = it.buildings3d) }
    }

    fun userMovedMap() = _local.update { if (it.follow) it.copy(follow = false) else it }
    fun recenter() = _local.update { it.copy(follow = true, recenterTick = it.recenterTick + 1) }
    fun toggle3d() = _local.update { it.copy(buildings3d = !it.buildings3d) }
    fun toggleVoice() = container.session.setNavVoice(!container.session.navVoice.value)
    fun clearError() = nav.clearError()
    fun shareHandled() = _local.update { it.copy(shareWhenReady = false) }

    private var hintJob: kotlinx.coroutines.Job? = null
    fun hint(text: String) {
        _local.update { it.copy(hint = text) }
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3_000)
            _local.update { it.copy(hint = null) }
        }
    }

    /**
     * "Invite friends": start a convoy with this destination and route and keep navigating; the share sheet opens when
     * the convoy exists. Already in a convoy: the leader's destination becomes this one, and the share sheet opens.
     */
    fun inviteFriends() {
        val s = nav.state.value
        val dest = s.destination ?: return
        val route = s.route ?: return
        val convoy = container.convoy.state.value
        if (convoy.link != Link.Online) {
            hint("Not connected to the convoy server")
            return
        }
        val fix = container.tracker.lastKnown()
        val destination = Destination(dest.lat, dest.lng, dest.name.take(80))
        val room = convoy.active
        if (room == null) {
            _local.update { it.copy(shareWhenReady = true) }
            container.convoy.startConvoyWithRoute(Names.defaultConvoyName(container.session.name.value).take(40), destination, route, fix)
        } else {
            if (convoy.meId == room.leader) container.convoy.setDestination(destination, fix, route)
            _local.update { it.copy(shareWhenReady = true) }
        }
    }

    val isNavigating: Boolean get() = nav.state.value.phase == NavPhase.Active
}
