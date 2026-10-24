package com.roadsurvey.dashtrack

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Road inventory feature types shown as buttons during survey. Edit this list to suit the project. */
val TAG_TYPES = listOf(
    "Signboard", "Streetlight", "Pothole", "Speed breaker", "Bus stop", "Median opening",
    "Footpath", "Encroachment", "Junction", "Drain / culvert", "Ped. crossing", "Other"
)

val TAG_COLORS = intArrayOf(
    0xFF1565C0.toInt(), 0xFFF9A825.toInt(), 0xFFC62828.toInt(), 0xFF6A1B9A.toInt(),
    0xFF2E7D32.toInt(), 0xFF00838F.toInt(), 0xFF5D4037.toInt(), 0xFFD84315.toInt(),
    0xFF283593.toInt(), 0xFF455A64.toInt(), 0xFFAD1457.toInt(), 0xFF616161.toInt()
)

fun tagColor(type: String): Int {
    val i = TAG_TYPES.indexOf(type).let { if (it < 0) TAG_TYPES.size - 1 else it }
    return TAG_COLORS[i % TAG_COLORS.size]
}

/** One GPS fix. tMs = milliseconds from the start of the video (the sync key). */
data class Fix(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float,
    val heading: Float,
    val accuracy: Float,
    val utcMs: Long,
    val distM: Double
) {
    fun toCsv(): String = String.format(
        Locale.US, "%d,%.7f,%.7f,%.2f,%.1f,%.1f,%d,%.1f",
        tMs, lat, lon, speedKmh, heading, accuracy, utcMs, distM
    )

    companion object {
        const val HEADER = "video_t_ms,lat,lon,speed_kmh,heading_deg,accuracy_m,utc_ms,dist_m"
        fun parse(line: String): Fix? = runCatching {
            val p = line.split(',')
            Fix(
                p[0].trim().toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toFloat(),
                p[4].toFloat(), p[5].toFloat(), p[6].trim().toLong(), p[7].toDouble()
            )
        }.getOrNull()
    }
}

/** One tagged road-inventory feature. */
data class Tag(
    val tMs: Long,
    val type: String,
    val lat: Double,
    val lon: Double,
    val chainageM: Double,
    val utcMs: Long
) {
    fun toCsv(): String = String.format(
        Locale.US, "%d,%s,%.7f,%.7f,%.1f,%d",
        tMs, type.replace(',', ' '), lat, lon, chainageM, utcMs
    )

    companion object {
        const val HEADER = "video_t_ms,type,lat,lon,chainage_m,utc_ms"
        fun parse(line: String): Tag? = runCatching {
            val p = line.split(',')
            Tag(p[0].trim().toLong(), p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].trim().toLong())
        }.getOrNull()
    }
}

object RunIO {
    const val TRACK = "track.csv"
    const val TAGS = "tags.csv"
    const val VIDEO = "video.mp4"

    /** New runs save the video as RUN_xxx.mp4 (unique name for OneDrive); old runs used video.mp4. */
    fun videoFile(dir: File): File {
        val named = File(dir, "${dir.name}.mp4")
        val legacy = File(dir, VIDEO)
        return if (!named.exists() && legacy.exists()) legacy else named
    }

    fun surveysDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "surveys").apply { mkdirs() }

    fun listRuns(ctx: Context): List<File> =
        surveysDir(ctx).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun readTrack(dir: File): List<Fix> {
        val f = File(dir, TRACK)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { Fix.parse(it) }.sortedBy { it.tMs }
    }

    fun readTags(dir: File): List<Tag> {
        val f = File(dir, TAGS)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { Tag.parse(it) }.sortedBy { it.tMs }
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }
}
