package de.waschbeken.heliolink.core

import java.time.Instant

/** Writes a heart rate only workout as Garmin TCX, which Strava and most platforms import. */
object Tcx {
    data class Point(val epochSeconds: Long, val bpm: Int)

    fun write(sport: String, start: Long, end: Long, points: List<Point>, calories: Int): String {
        val sb = StringBuilder()
        val startIso = Instant.ofEpochSecond(start).toString()
        val avg = points.map { it.bpm }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        val max = points.maxOfOrNull { it.bpm } ?: 0
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""").append('\n')
        sb.append("""  <Activities><Activity Sport="$sport"><Id>$startIso</Id>""").append('\n')
        sb.append("""    <Lap StartTime="$startIso"><TotalTimeSeconds>${end - start}</TotalTimeSeconds>""")
        sb.append("<DistanceMeters>0</DistanceMeters><Calories>$calories</Calories>")
        sb.append("<AverageHeartRateBpm><Value>$avg</Value></AverageHeartRateBpm>")
        sb.append("<MaximumHeartRateBpm><Value>$max</Value></MaximumHeartRateBpm>")
        sb.append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod><Track>\n")
        for (p in points) {
            sb.append("      <Trackpoint><Time>${Instant.ofEpochSecond(p.epochSeconds)}</Time>")
            sb.append("<HeartRateBpm><Value>${p.bpm}</Value></HeartRateBpm></Trackpoint>\n")
        }
        sb.append("    </Track></Lap></Activity></Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }
}
