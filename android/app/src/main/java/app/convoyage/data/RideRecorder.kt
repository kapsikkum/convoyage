package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.AttachPhoto
import app.convoyage.api.CreateRide
import app.convoyage.api.FinishRide
import app.convoyage.api.RideApi
import app.convoyage.api.UpdateRide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/** How the upload of a stopped ride is going. */
sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data object Done : UploadState

    /** [retryable]: worth trying again by itself (offline, server busy). Otherwise the person has to act. */
    data class Failed(val message: String, val retryable: Boolean) : UploadState
}

enum class StartResult { Started, Busy }

/** What the UI shows. The trace itself is not in here (it can be 15 000 points); ask [RideRecorder.points]. */
data class RecorderState(
    val meta: RideMeta? = null,
    /** Active time so far (pauses excluded), seconds. Ticks once a second while recording. */
    val elapsedS: Long = 0,
    val stats: RideStats = RideStats.EMPTY,
    val pointCount: Int = 0,
    val upload: UploadState = UploadState.Idle,
) {
    val stage: RideStage? get() = meta?.stage
    val isRecording: Boolean get() = stage == RideStage.Recording
    val isPaused: Boolean get() = stage == RideStage.Paused

    /** Recording or paused: a ride is open and can be paused/resumed/stopped. */
    val isActive: Boolean get() = isRecording || isPaused

    /** Stopped or uploaded: there is a recap to show. */
    val hasRecap: Boolean get() = stage == RideStage.Stopped || stage == RideStage.Uploaded
}

/**
 * Records one ride at a time: start, pause/resume, stop, then upload through the rides API.
 *
 *  - Fixes come from [fixes] (the location tracker) while recording; [TraceFilter] drops bad ones.
 *  - Everything is saved to [store] as it happens, so a crash loses nothing: [restore] brings the ride back
 *    paused, and a ride that was stopped but not uploaded is uploaded again.
 *  - Upload is create-then-finish, then the photos. Each step is saved, so a retry only repeats what is missing.
 *
 * Foreground only. TODO(background): a foreground service with ACCESS_BACKGROUND_LOCATION so recording survives the
 * screen being off or another app in front; today fixes stop arriving when the app is hidden.
 */
class RideRecorder(
    private val scope: CoroutineScope,
    private val store: RideStore,
    private val fixes: Flow<Fix?>,
    private val api: RideApi,
    private val acquireTracker: () -> Unit = {},
    private val releaseTracker: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Shrinks a photo that is too big for the server (15 MB); returns the file to upload. */
    private val preparePhoto: (File) -> File = { it },
    private val retryDelayMs: (attempt: Int) -> Long = { (5_000L shl it.coerceAtMost(6)).coerceAtMost(300_000L) },
) {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var meta: RideMeta? = null
    private val trace = ArrayList<TracePoint>()
    private var filter = TraceFilter()
    private var acc = StatsAccumulator()
    private var finalStats: RideStats? = null
    private var upload: UploadState = UploadState.Idle

    /** Fixes older than this belong to before the ride (or before the resume). */
    private var acceptFrom = 0L
    private var holdingTracker = false
    private var collector: Job? = null
    private var ticker: Job? = null
    private var syncJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0

    /** One network pipeline at a time: the create/finish/photos steps must not interleave. */
    private val net = Mutex()

    // ---- reading ----------------------------------------------------------------------------------

    /** A copy of the accepted trace points, oldest first (unfilled). */
    fun points(): List<TracePoint> = trace.toList()

    /** The trace as it is uploaded and drawn: gaps under 30 s filled, duplicates dropped. */
    fun route(): List<LatLngD> = TraceMath.routeOf(TraceMath.fillGaps(trace))

    // ---- lifecycle --------------------------------------------------------------------------------

    /** Loads a ride saved by an earlier run. Call once at startup. A ride that was recording comes back paused. */
    fun restore() {
        if (meta != null) return
        val m = store.readMeta() ?: return
        trace += store.readPoints()
        trace.forEach(acc::add)
        filter.restoreFrom(trace.lastOrNull())
        meta = m
        when (m.stage) {
            RideStage.Recording -> {
                // The process died while recording. Count the dead time as a pause, from the last point we have.
                val since = (trace.lastOrNull()?.timeMs ?: m.startedAtMs).coerceAtLeast(m.startedAtMs)
                saveMeta(m.copy(stage = RideStage.Paused, pausedSinceMs = since))
            }
            RideStage.Paused -> Unit
            RideStage.Stopped -> {
                finalStats = computeFinal()
                publish()
                syncNow()
            }
            RideStage.Uploaded -> {
                finalStats = computeFinal()
                upload = UploadState.Done
            }
        }
        publish()
    }

    fun start(
        vehicleId: String?,
        vehicleName: String?,
        convoyCode: String?,
        startFix: Fix?,
        auto: Boolean = false,
        title: String = "",
    ): StartResult {
        val cur = meta
        if (cur != null) {
            if (cur.stage != RideStage.Uploaded) return StartResult.Busy
            clearLocal() // the previous ride is safe on the server: its recap is no longer needed
        }
        val t = now()
        trace.clear()
        acc = StatsAccumulator()
        filter = TraceFilter()
        finalStats = null
        upload = UploadState.Idle
        retryAttempt = 0
        store.clear()
        val fresh = startFix?.takeIf { t - it.timeMs < 30_000 }
        saveMeta(
            RideMeta(
                localId = UUID.randomUUID().toString(), stage = RideStage.Recording, startedAtMs = t,
                vehicleId = vehicleId, vehicleName = vehicleName, convoyCode = convoyCode, autoStarted = auto,
                title = title, startLat = fresh?.lat, startLng = fresh?.lng,
            ),
        )
        beginCollecting()
        publish()
        // Tell the server now, so the ride exists while it is being ridden. Failure is fine: finish creates it.
        scope.launch { net.withLock { ensureServerRide() } }
        return StartResult.Started
    }

    fun pause() {
        val m = meta ?: return
        if (m.stage != RideStage.Recording) return
        stopCollecting()
        saveMeta(m.copy(stage = RideStage.Paused, pausedSinceMs = now()))
        publish()
    }

    fun resume() {
        val m = meta ?: return
        if (m.stage != RideStage.Paused) return
        val t = now()
        val paused = m.pausedSinceMs?.let { (t - it).coerceAtLeast(0) } ?: 0L
        saveMeta(m.copy(stage = RideStage.Recording, pausedMs = m.pausedMs + paused, pausedSinceMs = null))
        filter.startNewSegment()
        beginCollecting()
        publish()
    }

    /** Ends the ride and starts uploading it. A paused ride ends at the moment it was paused. */
    fun stop() {
        val m = meta ?: return
        if (!(m.stage == RideStage.Recording || m.stage == RideStage.Paused)) return
        stopCollecting()
        val end = if (m.stage == RideStage.Paused) (m.pausedSinceMs ?: now()) else now()
        saveMeta(m.copy(stage = RideStage.Stopped, endedAtMs = end.coerceAtLeast(m.startedAtMs)))
        finalStats = computeFinal()
        publish()
        syncNow()
    }

    /** Throws the ride away, here and (best effort) on the server. */
    fun discard() {
        val id = meta?.serverRideId
        stopCollecting()
        clearLocal()
        publish()
        if (id != null) scope.launch { net.withLock { api.deleteRide(id) } }
    }

    /** Leaves the recap of an uploaded ride; a ride that is not uploaded yet stays so it can be retried. */
    fun dismiss() {
        if (meta?.stage == RideStage.Uploaded) {
            clearLocal()
            publish()
        }
    }

    fun setDetails(title: String? = null, visibility: String? = null) {
        val m = meta ?: return
        saveMeta(m.copy(title = title?.trim()?.take(120) ?: m.title, visibility = visibility ?: m.visibility))
        publish()
    }

    /** Sends a changed title/visibility to a ride the server already holds as finished. Ok when there is nothing to send. */
    suspend fun pushDetails(): ApiResult<Unit> {
        val m = meta ?: return ApiResult.Ok(Unit)
        val id = m.serverRideId
        if (id == null || !m.finishedOnServer) return ApiResult.Ok(Unit)
        return when (val r = net.withLock { api.updateRide(id, UpdateRide(m.title, m.visibility)) }) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }
    }

    fun setPostId(postId: String) {
        val m = meta ?: return
        saveMeta(m.copy(postId = postId))
        publish()
    }

    // ---- photos -----------------------------------------------------------------------------------

    /**
     * Adds a photo (a file the camera just wrote) to the open ride. The file is moved into the ride's own folder.
     * [location] is the fix to pin it with, or null for "Include location" off.
     */
    suspend fun addPhoto(source: File, location: Fix?): RidePhotoRecord? {
        val m = meta ?: return null
        if (!(m.stage == RideStage.Recording || m.stage == RideStage.Paused)) return null
        val id = UUID.randomUUID().toString()
        val dest = File(store.photosDir, "$id.jpg")
        withContext(io) {
            store.photosDir.mkdirs()
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
        val rec = RidePhotoRecord(id = id, file = dest.path, takenAtMs = now(), lat = location?.lat, lng = location?.lng)
        val cur = meta ?: return rec
        saveMeta(cur.copy(photos = cur.photos + rec))
        publish()
        if (cur.serverRideId != null) scope.launch { net.withLock { uploadPhotos(quiet = true) } }
        return rec
    }

    // ---- upload -----------------------------------------------------------------------------------

    /** Starts (or restarts) the upload of a stopped ride; does nothing if one is already running. */
    fun syncNow() {
        if (meta?.stage != RideStage.Stopped) return
        if (syncJob?.isActive == true) return
        retryJob?.cancel()
        syncJob = scope.launch { net.withLock { runSync() } }
    }

    private suspend fun runSync() {
        val m = meta ?: return
        if (m.stage != RideStage.Stopped) return
        setUpload(UploadState.Uploading)
        val failure = pipeline()
        if (failure == null) {
            meta?.let { saveMeta(it.copy(stage = RideStage.Uploaded, lastError = null)) }
            retryAttempt = 0
            setUpload(UploadState.Done)
        } else {
            setUpload(failure)
            if (failure.retryable) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val wait = retryDelayMs(retryAttempt++)
        retryJob = scope.launch {
            delay(wait)
            if (isActive) syncNow()
        }
    }

    /** Runs the steps that are still missing. Null = everything is on the server. */
    private suspend fun pipeline(): UploadState.Failed? {
        ensureServerRide()?.let { return it }
        finishOnServer()?.let { return it }
        return uploadPhotos(quiet = false)
    }

    /** Creates the ride on the server if it is not there yet. */
    private suspend fun ensureServerRide(): UploadState.Failed? {
        var m = meta ?: return null
        if (m.serverRideId != null || !(m.stage == RideStage.Recording || m.stage == RideStage.Paused || m.stage == RideStage.Stopped)) return null
        var droppedVehicle = false
        while (true) {
            val body = CreateRide(
                vehicleId = m.vehicleId, title = m.title.ifBlank { null }, visibility = m.visibility,
                startedAt = iso(m.startedAtMs), startLat = m.startLat, startLng = m.startLng, convoyCode = m.convoyCode,
            )
            when (val r = api.createRide(body)) {
                is ApiResult.Ok -> {
                    if (!edit(m.localId) { it.copy(serverRideId = r.value.id) }) {
                        api.deleteRide(r.value.id) // the ride was discarded while the request was in flight
                    }
                    return null
                }
                is ApiResult.Err -> {
                    // The vehicle was deleted since: ride without it rather than not at all.
                    if (r.status == 400 && m.vehicleId != null && !droppedVehicle && r.message.contains("vehicle", ignoreCase = true)) {
                        droppedVehicle = true
                        m = meta?.takeIf { it.localId == m.localId }?.copy(vehicleId = null) ?: return null
                        saveMeta(m)
                        continue
                    }
                    return failure(r)
                }
            }
        }
    }

    private suspend fun finishOnServer(): UploadState.Failed? {
        val m = meta ?: return null
        val id = m.serverRideId ?: return null
        if (m.finishedOnServer) return null
        val filled = TraceMath.fillGaps(trace)
        val s = TraceMath.stats(filled, m.elapsedMs(now()))
        val body = FinishRide(
            endedAt = iso(m.endedAtMs ?: now()),
            distanceM = s.distanceM,
            durationS = s.durationS,
            maxSpeedMps = s.maxSpeedMps.coerceAtMost(120.0),
            avgSpeedMps = s.avgSpeedMps.coerceAtMost(120.0),
            elevationGainM = s.elevationGainM,
            maxLeanDeg = s.maxLeanDeg,
            polyline = Polyline.encode(TraceMath.routeOf(filled, 5), 5),
            polylinePrecision = 5,
            title = m.title.ifBlank { null },
            visibility = m.visibility,
        )
        return when (val r = api.finishRide(id, body)) {
            is ApiResult.Ok -> {
                edit(m.localId) { it.copy(finishedOnServer = true) }
                null
            }
            is ApiResult.Err -> when (r.status) {
                409 -> { // already finished (an earlier attempt got through but its answer was lost)
                    edit(m.localId) { it.copy(finishedOnServer = true) }
                    null
                }
                404 -> { // the ride is gone on the server: create it again next time
                    edit(m.localId) { it.copy(serverRideId = null) }
                    UploadState.Failed("The server lost this ride, trying again", retryable = true)
                }
                else -> failure(r)
            }
        }
    }

    /** Uploads each photo that is not on the server yet and pins it to the ride. [quiet] = mid-ride, no state changes. */
    private suspend fun uploadPhotos(quiet: Boolean): UploadState.Failed? {
        val rideId = meta?.serverRideId ?: return null
        var dropped = 0
        for (photo in (meta?.photos ?: emptyList())) {
            if (photo.attached) continue
            val cur = meta?.photos?.firstOrNull { it.id == photo.id } ?: continue
            if (cur.attached) continue
            val file = File(cur.file)
            if (!file.isFile) {
                replacePhoto(cur.copy(attached = true))
                continue
            }
            var rec = cur
            if (rec.mediaId == null) {
                val upload = withContext(io) { preparePhoto(file) }
                when (val r = api.uploadMedia(upload, "image/jpeg")) {
                    is ApiResult.Ok -> {
                        rec = rec.copy(mediaId = r.value.id, thumbPath = r.value.thumbUrl)
                        replacePhoto(rec)
                    }
                    is ApiResult.Err -> {
                        if (isTransient(r)) return if (quiet) null else failure(r)
                        // Refused for good (too big, not an image): skip it so the ride can finish.
                        dropped++
                        replacePhoto(rec.copy(attached = true))
                        continue
                    }
                }
            }
            when (val r = api.attachRidePhoto(rideId, AttachPhoto(rec.mediaId!!, rec.lat, rec.lng, iso(rec.takenAtMs)))) {
                is ApiResult.Ok -> replacePhoto(rec.copy(attached = true))
                is ApiResult.Err -> {
                    if (isTransient(r)) return if (quiet) null else failure(r)
                    dropped++
                    replacePhoto(rec.copy(attached = true))
                }
            }
        }
        if (dropped > 0) meta?.let { m -> edit(m.localId) { it.copy(lastError = "$dropped photo(s) could not be uploaded") } }
        return null
    }

    private fun replacePhoto(updated: RidePhotoRecord) {
        val m = meta ?: return
        saveMeta(m.copy(photos = m.photos.map { if (it.id == updated.id) updated else it }))
        publish()
    }

    private fun isTransient(r: ApiResult.Err) = r.isNetwork || r.status >= 500 || r.status == 429 || r.status == 408 || r.status == 401

    private fun failure(r: ApiResult.Err): UploadState.Failed = UploadState.Failed(
        when {
            r.isNetwork -> "No connection. Your ride is saved and will upload when the server is reachable."
            r.status == 401 -> "Sign in again to upload this ride."
            else -> r.message
        },
        retryable = r.isNetwork || r.status >= 500 || r.status == 429 || r.status == 408,
    )

    private fun setUpload(u: UploadState) {
        upload = u
        publish()
    }

    // ---- internals --------------------------------------------------------------------------------

    private fun beginCollecting() {
        acceptFrom = now() - 2_000
        if (!holdingTracker) {
            holdingTracker = true
            acquireTracker()
        }
        collector?.cancel()
        collector = scope.launch { fixes.filterNotNull().collect(::onFix) }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                publish()
            }
        }
    }

    private fun stopCollecting() {
        collector?.cancel()
        collector = null
        ticker?.cancel()
        ticker = null
        if (holdingTracker) {
            holdingTracker = false
            releaseTracker()
        }
    }

    private fun onFix(fix: Fix) {
        val m = meta ?: return
        if (m.stage != RideStage.Recording || fix.timeMs < acceptFrom) return
        val p = filter.offer(fix) ?: return
        trace += p
        acc.add(p)
        store.appendPoint(p)
        if (m.startLat == null) saveMeta(m.copy(startLat = p.lat, startLng = p.lng))
        publish()
    }

    private fun computeFinal(): RideStats {
        val m = meta ?: return RideStats.EMPTY
        return TraceMath.stats(TraceMath.fillGaps(trace), m.elapsedMs(now()))
    }

    private fun saveMeta(m: RideMeta) {
        meta = m
        store.writeMeta(m)
    }

    /** Changes the open ride only if it is still the ride [localId] (not discarded or replaced meanwhile). */
    private fun edit(localId: String, change: (RideMeta) -> RideMeta): Boolean {
        val cur = meta?.takeIf { it.localId == localId } ?: return false
        saveMeta(change(cur))
        return true
    }

    private fun clearLocal() {
        retryJob?.cancel()
        syncJob?.cancel()
        store.clear()
        meta = null
        trace.clear()
        acc = StatsAccumulator()
        filter = TraceFilter()
        finalStats = null
        upload = UploadState.Idle
    }

    private fun publish() {
        val m = meta
        val elapsed = m?.elapsedMs(now()) ?: 0L
        _state.value = RecorderState(
            meta = m,
            elapsedS = elapsed / 1000,
            stats = if (m != null && m.stage >= RideStage.Stopped) finalStats ?: acc.snapshot(elapsed) else acc.snapshot(elapsed),
            pointCount = trace.size,
            upload = upload,
        )
    }

    private companion object {
        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
    }
}
