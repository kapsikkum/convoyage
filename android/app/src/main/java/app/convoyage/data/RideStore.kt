package app.convoyage.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where a ride is in its life. A ride is only ever one of these; [Uploaded] is "done, waiting to be dismissed". */
@Serializable
enum class RideStage { Recording, Paused, Stopped, Uploaded }

/** A photo taken during the ride. [file] is a path; lat/lng are null when "Include location" was off. */
@Serializable
data class RidePhotoRecord(
    val id: String,
    val file: String,
    val takenAtMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Set once `POST /v1/media` accepted it. */
    val mediaId: String? = null,
    /** Server path of the 512 px thumbnail, once uploaded. */
    val thumbPath: String? = null,
    /** Set once `POST /v1/rides/{id}/photos` accepted it. */
    val attached: Boolean = false,
)

/** Everything about the ride that is not the trace itself. Rewritten (atomically) on every state change. */
@Serializable
data class RideMeta(
    val localId: String,
    val stage: RideStage,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** Time spent paused in earlier pauses. */
    val pausedMs: Long = 0,
    /** When the current pause began, or null if not paused. */
    val pausedSinceMs: Long? = null,
    val vehicleId: String? = null,
    val vehicleName: String? = null,
    val convoyCode: String? = null,
    /** Started by the convoy auto-record toggle, so leaving the convoy stops it. */
    val autoStarted: Boolean = false,
    val title: String = "",
    val visibility: String = "followers",
    val startLat: Double? = null,
    val startLng: Double? = null,
    /** The server's ride, once `POST /v1/rides` succeeded. */
    val serverRideId: String? = null,
    /** `POST /v1/rides/{id}/finish` succeeded. */
    val finishedOnServer: Boolean = false,
    val photos: List<RidePhotoRecord> = emptyList(),
    val lastError: String? = null,
    val postId: String? = null,
) {
    /** Active time (pauses excluded) up to [nowMs], or up to the end for a stopped ride. */
    fun elapsedMs(nowMs: Long): Long {
        val end = endedAtMs ?: nowMs
        val openPause = pausedSinceMs?.let { (end - it).coerceAtLeast(0) } ?: 0L
        return (end - startedAtMs - pausedMs - openPause).coerceAtLeast(0)
    }
}

/**
 * The ride that is being recorded (or waiting to upload), on disk so a crash or a killed process loses nothing:
 *  - `meta.json`: [RideMeta], written to a temp file and renamed, so it is never half-written.
 *  - `points.csv`: one accepted [TracePoint] per line, append-only. A line cut off by a crash is skipped on read.
 * There is at most one such ride; [clear] removes it.
 */
class RideStore(private val dir: File) {
    private val metaFile get() = File(dir, "meta.json")
    private val pointsFile get() = File(dir, "points.csv")

    /** Photos taken during the ride live here until they are uploaded. */
    val photosDir: File get() = File(dir, "photos")

    fun readMeta(): RideMeta? = try {
        metaFile.takeIf { it.isFile }?.readText()?.let { Json.decodeFromString(RideMeta.serializer(), it) }
    } catch (_: Exception) {
        null // unreadable: treated as no ride rather than crashing at startup
    }

    fun writeMeta(meta: RideMeta) {
        dir.mkdirs()
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(Json.encodeToString(RideMeta.serializer(), meta))
        if (!tmp.renameTo(metaFile)) {
            metaFile.delete()
            check(tmp.renameTo(metaFile)) { "cannot save the ride" }
        }
    }

    fun appendPoint(p: TracePoint) {
        dir.mkdirs()
        pointsFile.appendText(encode(p))
    }

    fun readPoints(): List<TracePoint> {
        val f = pointsFile
        if (!f.isFile) return emptyList()
        val out = ArrayList<TracePoint>()
        f.forEachLine { line -> decode(line)?.let(out::add) }
        return out
    }

    fun clear() {
        metaFile.delete()
        pointsFile.delete()
        File(dir, "meta.json.tmp").delete()
        photosDir.deleteRecursively()
    }

    companion object {
        /** `time,lat,lng,alt,speed,accuracy,segment` with empty fields for nulls. Ends with a newline, which marks the line complete. */
        fun encode(p: TracePoint): String =
            "${p.timeMs},${p.lat},${p.lng},${p.altM ?: ""},${p.speedMps ?: ""},${p.accuracyM},${p.segment}\n"

        fun decode(line: String): TracePoint? {
            val f = line.split(',')
            if (f.size != 7) return null
            return try {
                TracePoint(
                    timeMs = f[0].toLong(), lat = f[1].toDouble(), lng = f[2].toDouble(),
                    altM = f[3].toDoubleOrNull(), speedMps = f[4].toDoubleOrNull(),
                    accuracyM = f[5].toFloat(), segment = f[6].toInt(),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
