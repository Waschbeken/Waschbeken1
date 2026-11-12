package de.waschbeken.heliolink.core

import java.time.Instant
import java.util.Locale

/** Writes a workout as Garmin TCX, which Strava and most platforms import: the heart rate, and the route when one was recorded. */
object Tcx {
    data class Point(val epochSeconds: Long, val bpm: Int)

    /**
     * [track] is the route from the phone's GPS (empty for a workout without). Every heart rate
     * value then gets the position and the metres covered at its second, interpolated along the route.
     */
    fun write(sport: String, start: Long, end: Long, points: List<Point>, calories: Int, track: List<TrackPoint> = emptyList()): String {
        val sb = StringBuilder()
        val startIso = Instant.ofEpochSecond(start).toString()
        val avg = points.map { it.bpm }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        val max = points.maxOfOrNull { it.bpm } ?: 0
        val cumulative = Track.cumulative(track)
        val metres = cumulative.lastOrNull() ?: 0.0
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""").append('\n')
        sb.append("""  <Activities><Activity Sport="$sport"><Id>$startIso</Id>""").append('\n')
        sb.append("""    <Lap StartTime="$startIso"><TotalTimeSeconds>${end - start}</TotalTimeSeconds>""")
        sb.append("<DistanceMeters>${Math.round(metres)}</DistanceMeters><Calories>$calories</Calories>")
        sb.append("<AverageHeartRateBpm><Value>$avg</Value></AverageHeartRateBpm>")
        sb.append("<MaximumHeartRateBpm><Value>$max</Value></MaximumHeartRateBpm>")
        sb.append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod><Track>\n")
        for (p in points) {
            sb.append("      <Trackpoint><Time>${Instant.ofEpochSecond(p.epochSeconds)}</Time>")
            Track.at(track, cumulative, p.epochSeconds)?.let { m ->
                sb.append(String.format(Locale.US, "<Position><LatitudeDegrees>%.6f</LatitudeDegrees><LongitudeDegrees>%.6f</LongitudeDegrees></Position>", m.lat, m.lon))
                m.altitude?.let { sb.append(String.format(Locale.US, "<AltitudeMeters>%.1f</AltitudeMeters>", it)) }
                sb.append(String.format(Locale.US, "<DistanceMeters>%.1f</DistanceMeters>", m.metres))
            }
            sb.append("<HeartRateBpm><Value>${p.bpm}</Value></HeartRateBpm></Trackpoint>\n")
        }
        sb.append("    </Track></Lap></Activity></Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }
}
