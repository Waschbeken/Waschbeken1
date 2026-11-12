package de.waschbeken.heliolink.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackTest {
    /** A straight line north from 52° N: one point every [step] seconds, [metres] apart. */
    private fun line(count: Int, metres: Double, step: Long, start: Long = 1_000_000): List<TrackPoint> {
        val degrees = metres / 111_194.93 // metres per degree of latitude on the sphere used
        return List(count) { i -> TrackPoint(start + i * step, 52.0 + i * degrees, 13.0, altitude = 40.0 + i) }
    }

    @Test
    fun distanceOfKnownStretches() {
        // one degree of latitude
        assertEquals(111_195.0, Track.distance(52.0, 13.0, 53.0, 13.0), 5.0)
        // one degree of longitude at 60° N is half as long
        assertEquals(55_597.0, Track.distance(60.0, 10.0, 60.0, 11.0), 30.0)
        assertEquals(0.0, Track.distance(52.0, 13.0, 52.0, 13.0), 1e-6)
    }

    @Test
    fun lengthAndPace() {
        val track = line(count = 301, metres = 10.0, step = 3) // 3 km in 15 min
        assertEquals(3000.0, Track.length(track), 1.0)
        assertEquals(300, Track.pace(Track.length(track), 900))
        assertEquals("5:00", Track.paceText(300))
        assertEquals(12.0, Track.kmh(3000.0, 900), 0.01)
        assertNull(Track.pace(20.0, 60))
        assertEquals("3,00 km", Track.distanceText(3000.4))
        assertEquals("840 m", Track.distanceText(840.2))
    }

    @Test
    fun acceptsOnlyRealMovement() {
        val a = TrackPoint(100, 52.0, 13.0, accuracy = 8f)
        fun north(metres: Double, t: Long, accuracy: Float = 8f) = TrackPoint(t, 52.0 + metres / 111_194.93, 13.0, accuracy = accuracy)
        assertTrue(Track.accepts(null, a))
        assertFalse(Track.accepts(null, a.copy(accuracy = 60f)), "a fix that is too vague")
        assertFalse(Track.accepts(a, north(3.0, 105)), "wandering while standing")
        assertTrue(Track.accepts(a, north(12.0, 105)))
        assertFalse(Track.accepts(a, north(12.0, 105, accuracy = 30f)), "a step within the fix's own uncertainty")
        assertFalse(Track.accepts(a, north(400.0, 105)), "a jump of 80 m/s")
        assertFalse(Track.accepts(a, north(12.0, 100)), "no time passed")
    }

    @Test
    fun splitsInterpolateTheKilometreMarks() {
        // 2.5 km: the first kilometre at 4 m/s, the rest at 2 m/s
        val fast = line(count = 251, metres = 4.0, step = 1) // 1000 m in 250 s
        val last = fast.last()
        val slow = List(750) { i -> TrackPoint(last.timestamp + i + 1, last.lat + (i + 1) * 2.0 / 111_194.93, 13.0) } // 1500 m in 750 s
        val hr = (fast + slow).map { it.timestamp to if (it.timestamp < last.timestamp) 160 else 120 }
        val splits = Track.splits(fast + slow, hr)
        assertEquals(3, splits.size)
        assertEquals(250.0, splits[0].seconds, 1.5)
        assertEquals(500.0, splits[1].seconds, 1.5)
        assertEquals(500.0, splits[2].metres, 2.0)
        assertTrue(abs(splits[0].pace - 250) <= 2, "${splits[0].pace}")
        assertTrue(abs(splits[1].pace - 500) <= 2, "${splits[1].pace}")
        assertTrue(abs(splits[2].pace - 500) <= 3, "${splits[2].pace}")
        assertEquals(160, splits[0].avgHr)
        assertEquals(120, splits[1].avgHr)
        assertEquals(listOf(1, 2, 3), splits.map { it.number })
    }

    @Test
    fun shortRestIsNotASplit() {
        val splits = Track.splits(line(count = 104, metres = 10.0, step = 3)) // 1030 m
        assertEquals(1, splits.size)
        assertTrue(Track.splits(listOf(TrackPoint(1, 52.0, 13.0))).isEmpty())
    }

    @Test
    fun movingTimeLeavesOutBreaks() {
        val first = line(count = 11, metres = 10.0, step = 3) // 30 s moving
        // two minutes at a red light, then on (the next kept point is 10 m further)
        val after = line(count = 11, metres = 10.0, step = 3, start = first.last().timestamp + 120).map { it.copy(lat = it.lat + 100.0 / 111_194.93) }
        assertEquals(60, Track.movingSeconds(first + after))
    }

    @Test
    fun ascentIgnoresWobble() {
        fun heights(vararg h: Double) = h.mapIndexed { i, v -> TrackPoint(i.toLong(), 52.0, 13.0, altitude = v) }
        assertEquals(0.0, Track.ascent(heights(100.0, 102.0, 99.0, 103.0, 100.0)), 1e-6)
        assertEquals(30.0, Track.ascent(heights(100.0, 110.0, 120.0, 130.0, 110.0)), 1e-6)
        // down 20, up 25 again: only the 25 up count
        assertEquals(25.0, Track.ascent(heights(100.0, 80.0, 105.0)), 1e-6)
        assertEquals(0.0, Track.ascent(listOf(TrackPoint(1, 52.0, 13.0))), 1e-6)
    }

    @Test
    fun momentIsInterpolated() {
        val track = line(count = 3, metres = 100.0, step = 10)
        val sums = Track.cumulative(track)
        assertEquals(200.0, sums.last(), 0.5)
        val m = assertNotNull(Track.at(track, sums, track[0].timestamp + 15))
        assertEquals(150.0, m.metres, 0.5)
        assertEquals((track[1].lat + track[2].lat) / 2, m.lat, 1e-9)
        assertEquals(41.5, m.altitude!!, 1e-6)
        assertNull(Track.at(track, sums, track[0].timestamp - 1))
        assertNull(Track.at(track, sums, track[2].timestamp + 1))
        assertNull(Track.at(emptyList(), DoubleArray(0), 5))
    }

    @Test
    fun projectionKeepsProportionsAndNorthUp() {
        // 1000 m north, then 500 m east
        val up = 1000.0 / 111_194.93
        val right = 500.0 / (111_194.93 * Math.cos(Math.toRadians(52.0)))
        val track = listOf(TrackPoint(0, 52.0, 13.0), TrackPoint(1, 52.0 + up, 13.0), TrackPoint(2, 52.0 + up, 13.0 + right))
        val xy = Track.project(track, 300f, 300f, padding = 20f)
        val height = xy[0].second - xy[1].second
        val width = xy[2].first - xy[1].first
        assertEquals(260f, height, 0.5f)
        assertEquals(2.0, (height / width).toDouble(), 0.02)
        assertTrue(xy[1].second < xy[0].second, "north is up")
        assertTrue(abs(xy[0].first - xy[1].first) < 0.01f)
        // centred: the 130 px wide drawing leaves 85 px on both sides
        assertEquals(85f, xy[0].first, 0.6f)
        assertEquals(1000.0 / 260, Track.metresPerPixel(track, 300f, 300f, padding = 20f), 0.05)
        assertTrue(Track.project(emptyList(), 100f, 100f).isEmpty())
        // a single point lands in the middle instead of dividing by zero
        val one = Track.project(listOf(track[0]), 100f, 100f).single()
        assertEquals(50f, one.first, 0.5f)
        assertEquals(50f, one.second, 0.5f)
    }

    @Test
    fun gpxAndTcxCarryTheRoute() {
        val track = line(count = 4, metres = 50.0, step = 10)
        val t0 = track[0].timestamp
        val gpx = Gpx.write("Laufen & mehr", track, listOf(t0 to 120, t0 + 10 to 130, t0 + 30 to 150))
        assertTrue(gpx.contains("""<trkpt lat="52.000000" lon="13.000000"><ele>40.0</ele>"""), gpx)
        assertTrue(gpx.contains("<name>Laufen &amp; mehr</name>"))
        assertEquals(4, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<gpxtpx:hr>130</gpxtpx:hr>"))
        // the third point (20 s) takes the value of second 10; nothing is older than 15 s here
        assertEquals(4, Regex("<gpxtpx:hr>").findAll(gpx).count())

        val tcx = Tcx.write("Running", t0, t0 + 30, listOf(Tcx.Point(t0 + 5, 125), Tcx.Point(t0 + 40, 140)), 200, track)
        assertTrue(tcx.contains("<DistanceMeters>150</DistanceMeters><Calories>200</Calories>"), tcx)
        assertTrue(tcx.contains("<DistanceMeters>25.0</DistanceMeters><HeartRateBpm><Value>125</Value>"), tcx)
        // after the end of the route: heart rate only
        assertTrue(tcx.contains("</Time><HeartRateBpm><Value>140</Value>"), tcx)
        // without a route it stays the heart rate file it was
        val plain = Tcx.write("Other", t0, t0 + 30, listOf(Tcx.Point(t0 + 5, 125)), 200)
        assertTrue(plain.contains("<DistanceMeters>0</DistanceMeters>"))
        assertFalse(plain.contains("<Position>"))
    }
}
