package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepEditTest {
    private val t0 = 1_800_000_000L / 60 * 60

    private fun session(vararg stages: SleepStage) = SleepSession(
        start = t0,
        end = t0 + stages.size * 60L,
        stages = stages.mapIndexed { i, s -> t0 + i * 60L to s },
        avgHeartRate = 55,
        minHeartRate = 48,
        strapScore = 80,
    )

    @Test
    fun rangesRoundTrip() {
        val edit = SleepEdit(1, t0, t0 + 600, listOf(StageRange(t0, t0 + 120, SleepStage.DEEP), StageRange(t0 + 300, t0 + 360, SleepStage.AWAKE)))
        assertEquals(edit.ranges, SleepEdit.parseRanges(edit.rangesText()))
        assertEquals(emptyList(), SleepEdit.parseRanges("kaputt;1:0:DEEP"))
    }

    @Test
    fun rangeOverridesStagesAndScoreIsRecalculated() {
        val s = session(SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.LIGHT)
        val edited = SleepEditor.apply(s, SleepEdit(1, s.start, s.end, listOf(StageRange(t0 + 60, t0 + 180, SleepStage.DEEP))), emptyList())!!
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.DEEP, SleepStage.LIGHT), edited.stages.map { it.second })
        assertEquals(null, edited.strapScore)
        assertTrue(edited.edited)
    }

    @Test
    fun longerNightKeepsKnownStagesAndFillsTheRest() {
        val s = session(SleepStage.DEEP, SleepStage.REM)
        val edited = SleepEditor.apply(s, SleepEdit(1, t0 - 120, s.end + 60), emptyList())!!
        assertEquals(5, edited.durationMinutes)
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM, SleepStage.LIGHT), edited.stages.map { it.second })
    }

    @Test
    fun deletedNightIsGone() {
        assertNull(SleepEditor.apply(session(SleepStage.LIGHT), SleepEdit(1, t0, t0 + 60, deleted = true), emptyList()))
    }

    @Test
    fun shortAwakeBecomesLightButNotAtTheEdges() {
        val s = session(SleepStage.AWAKE, SleepStage.LIGHT, SleepStage.AWAKE, SleepStage.AWAKE, SleepStage.DEEP, SleepStage.AWAKE)
        val out = SleepEditor.ignoreShortAwake(s, 3).stages.map { it.second }
        assertEquals(listOf(SleepStage.AWAKE, SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.AWAKE), out)
    }

    @Test
    fun segmentsJoinEqualMinutes() {
        val seg = SleepEditor.segments(session(SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.DEEP))
        assertEquals(listOf(StageRange(t0, t0 + 120, SleepStage.LIGHT), StageRange(t0 + 120, t0 + 180, SleepStage.DEEP)), seg)
    }
}
