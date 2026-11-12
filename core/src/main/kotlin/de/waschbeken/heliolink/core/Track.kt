package de.waschbeken.heliolink.core

import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A position of a workout, recorded with the phone's GPS (the strap has none). */
data class TrackPoint(
    /** Epoch seconds. */
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    /** Metres as the phone reports them, null without a height fix. */
    val altitude: Double? = null,
    /** Radius in metres the true position lies in (68 %), null when unknown. */
    val accuracy: Float? = null,
)

/** Distance, pace, splits and a drawing of a recorded track. */
object Track {
    /** Fixes worse than this are not used. */
    const val MAX_ACCURACY = 35f

    /** Shorter steps are the position wandering while standing, not movement. */
    const val MIN_STEP = 5.0

    /** Faster than this (126 km/h) is a jump of the GPS. */
    const val MAX_SPEED = 35.0

    private const val EARTH_RADIUS = 6_371_000.0

    /** Metres between two positions (haversine). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(a)))
    }

    fun distance(a: TrackPoint, b: TrackPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /**
     * Whether [next] continues the track after [last]: accurate enough, really moved on (standing
     * still the position wanders within its accuracy, which would add up to metres never walked)
     * and no impossible jump.
     */
    fun accepts(last: TrackPoint?, next: TrackPoint): Boolean {
        val accuracy = next.accuracy ?: 0f
        if (accuracy > MAX_ACCURACY) return false
        if (last == null) return true
        val dt = next.timestamp - last.timestamp
        if (dt <= 0) return false
        val d = distance(last, next)
        if (d < maxOf(MIN_STEP, accuracy * 0.5)) return false
        return d / dt <= MAX_SPEED
    }

    /** Metres along the track. */
    fun length(points: List<TrackPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distance(points[i - 1], points[i])
        return sum
    }

    /** Seconds in motion: stretches slower than 0.4 m/s (a break at a traffic light, a rest) do not count. */
    fun movingSeconds(points: List<TrackPoint>): Long {
        var sum = 0L
        for (i in 1 until points.size) {
            val dt = points[i].timestamp - points[i - 1].timestamp
            if (dt > 0 && distance(points[i - 1], points[i]) / dt >= 0.4) sum += dt
        }
        return sum
    }

    /** Seconds per kilometre, null for a stretch too short to say. */
    fun pace(metres: Double, seconds: Long): Int? =
        if (metres < 50 || seconds <= 0) null else (seconds / (metres / 1000)).roundToInt()

    /** "5:32" for 332 seconds per kilometre. */
    fun paceText(secondsPerKm: Int): String = "%d:%02d".format(secondsPerKm / 60, secondsPerKm % 60)

    fun kmh(metres: Double, seconds: Long): Double = if (seconds <= 0) 0.0 else metres / seconds * 3.6

    /** "5,23 km" or "840 m". */
    fun distanceText(metres: Double): String =
        if (metres >= 1000) String.format(Locale.GERMANY, "%.2f km", metres / 1000) else "${metres.roundToInt()} m"

    /** One kilometre of the track (the last one may be shorter). */
    data class Split(val number: Int, val metres: Double, val seconds: Double, val avgHr: Int?) {
        /** Seconds per kilometre. */
        val pace: Int get() = (seconds / (metres / 1000)).roundToInt()
    }

    /**
     * The track cut every [every] metres; the time at each mark is interpolated between the two
     * positions around it. [hr] (epoch seconds to bpm) gives the mean heart rate of each part.
     * A rest of less than 50 m at the end is left out.
     */
    fun splits(points: List<TrackPoint>, hr: List<Pair<Long, Int>> = emptyList(), every: Double = 1000.0): List<Split> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Split>()
        var splitStart = points.first().timestamp.toDouble()
        var covered = 0.0 // metres in the current split
        fun close(end: Double, metres: Double) {
            val beats = hr.filter { it.first >= splitStart && it.first < end }.map { it.second }
            out += Split(out.size + 1, metres, end - splitStart, beats.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
            splitStart = end
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = distance(a, b)
            if (d <= 0) continue
            var used = 0.0 // metres of this segment already given to finished splits
            while (covered + (d - used) >= every) {
                used += every - covered
                close(a.timestamp + (b.timestamp - a.timestamp) * (used / d), every)
                covered = 0.0
            }
            covered += d - used
        }
        if (covered >= 50) close(points.last().timestamp.toDouble(), covered)
        return out
    }

    /**
     * Metres climbed. The height from GPS wobbles by a few metres, so a rise only counts once it
     * is at least [threshold] metres above the last low point.
     */
    fun ascent(points: List<TrackPoint>, threshold: Double = 5.0): Double {
        var ref: Double? = null
        var gain = 0.0
        for (p in points) {
            val alt = p.altitude ?: continue
            val r = ref
            when {
                r == null -> ref = alt
                alt >= r + threshold -> {
                    gain += alt - r
                    ref = alt
                }
                alt <= r - threshold -> ref = alt
            }
        }
        return gain
    }

    /** Position, height and metres covered at a moment of the track. */
    data class Moment(val lat: Double, val lon: Double, val altitude: Double?, val metres: Double)

    /** Metres covered up to every point. */
    fun cumulative(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distance(points[i - 1], points[i])
        return out
    }

    /** Where the track was at [time] (interpolated between the positions around it), null before the first or after the last one. */
    fun at(points: List<TrackPoint>, cumulative: DoubleArray, time: Long): Moment? {
        if (points.isEmpty() || time < points.first().timestamp || time > points.last().timestamp) return null
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].timestamp <= time) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = (b.timestamp - a.timestamp).toDouble()
        val f = if (span <= 0) 0.0 else (time - a.timestamp) / span
        val alt = if (a.altitude != null && b.altitude != null) a.altitude + (b.altitude - a.altitude) * f else a.altitude ?: b.altitude
        return Moment(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, alt, cumulative[lo] + (cumulative[hi] - cumulative[lo]) * f)
    }

    /**
     * The track fitted into a box of [width] × [height] with [padding] around it: north is up and
     * the proportions are true (a degree of longitude is shorter than one of latitude). Returns x/y per point.
     */
    fun project(points: List<TrackPoint>, width: Float, height: Float, padding: Float = 0f): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        val latMin = points.minOf { it.lat }
        val latMax = points.maxOf { it.lat }
        val lonMin = points.minOf { it.lon }
        val lonMax = points.maxOf { it.lon }
        val squeeze = cos(Math.toRadians((latMin + latMax) / 2))
        val w = (lonMax - lonMin) * squeeze
        val h = latMax - latMin
        // A track without extent in one direction (a straight line, a single point) is only centred there.
        val fitW = if (w > 1e-9) (width - 2 * padding) / w else Double.MAX_VALUE
        val fitH = if (h > 1e-9) (height - 2 * padding) / h else Double.MAX_VALUE
        val scale = min(fitW, fitH).let { if (it == Double.MAX_VALUE) 0.0 else it }
        val left = (width - w * scale) / 2
        val top = (height - h * scale) / 2
        return points.map { p ->
            (left + (p.lon - lonMin) * squeeze * scale).toFloat() to (top + (latMax - p.lat) * scale).toFloat()
        }
    }

    /** Metres per pixel of [project] with the same arguments, for a scale bar. */
    fun metresPerPixel(points: List<TrackPoint>, width: Float, height: Float, padding: Float = 0f): Double {
        if (points.size < 2) return 0.0
        val latMin = points.minOf { it.lat }
        val latMax = points.maxOf { it.lat }
        val lonMin = points.minOf { it.lon }
        val lonMax = points.maxOf { it.lon }
        val mid = (latMin + latMax) / 2
        val wMetres = distance(mid, lonMin, mid, lonMax).coerceAtLeast(1e-3)
        val hMetres = distance(latMin, lonMin, latMax, lonMin).coerceAtLeast(1e-3)
        return maxOf(wMetres / (width - 2 * padding), hMetres / (height - 2 * padding))
    }
}

/** Writes a recorded track as GPX 1.1 with the heart rate per point (Garmin's TrackPointExtension), for maps and other apps. */
object Gpx {
    fun write(name: String, points: List<TrackPoint>, hr: List<Pair<Long, Int>> = emptyList()): String {
        val beats = hr.sortedBy { it.first }
        var j = 0
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="HelioLink" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""",
        ).append('\n')
        sb.append("  <trk><name>").append(name.replace("&", "&amp;").replace("<", "&lt;")).append("</name><trkseg>\n")
        for (p in points) {
            // the newest heart rate up to this point, if it is not older than 15 s
            while (j + 1 < beats.size && beats[j + 1].first <= p.timestamp) j++
            val bpm = beats.getOrNull(j)?.takeIf { it.first <= p.timestamp && p.timestamp - it.first <= 15 }?.second
            sb.append(String.format(Locale.US, """    <trkpt lat="%.6f" lon="%.6f">""", p.lat, p.lon))
            p.altitude?.let { sb.append(String.format(Locale.US, "<ele>%.1f</ele>", it)) }
            sb.append("<time>").append(Instant.ofEpochSecond(p.timestamp)).append("</time>")
            if (bpm != null) sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$bpm</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }
}
