package com.salvozappa.youroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class ZoomTransitionTest {
    private val ramp = ZoomTransition(start = 1.0, target = 4.0, began = 10.0, duration = 2.0)

    @Test fun endpointsAreExact() {
        assertEquals(1.0, ramp.value(9.0), 0.0)
        assertEquals(1.0, ramp.value(10.0), 0.0)
        assertEquals(4.0, ramp.value(12.0), 0.0)
        assertTrue(ramp.isFinished(12.0))
    }

    @Test fun lateCallbacksStillMeetTheDeadline() {
        assertEquals(4.0, ramp.value(17.5), 0.0)
        assertTrue(ramp.isFinished(12.0001))
    }

    @Test fun halfwayIsTheGeometricMeanInMagnificationSpace() {
        assertEquals(sqrt(1.0 * 4.0), ramp.value(11.0), 1e-12)
    }

    @Test fun motionIsMonotonicAndEasesAtBothEnds() {
        var previous = ramp.value(10.0)
        for (step in 1..200) {
            val value = ramp.value(10.0 + step * 0.01)
            assertTrue("step $step", value >= previous)
            previous = value
        }
        // Quintic smootherstep: velocity reaches zero at both ends (in log2 stops).
        val stops = ln(4.0) / ln(2.0)
        val early = ln(ramp.value(10.02)) / ln(2.0)
        val late = stops - ln(ramp.value(11.98)) / ln(2.0)
        assertTrue("early $early", early < 0.0001 * stops * 10)
        assertTrue("late $late", late < 0.0001 * stops * 10)
        // Faster than linear in the middle.
        val middleSpeed = (ln(ramp.value(11.01)) - ln(ramp.value(10.99))) / 0.02
        assertTrue(middleSpeed > ln(4.0) / 2.0)
    }

    @Test fun zoomingOutUsesTheSameCurve() {
        val out = ZoomTransition(start = 4.0, target = 1.0, began = 0.0, duration = 1.0)
        assertEquals(2.0, out.value(0.5), 1e-12)
        assertEquals(1.0, out.value(1.0), 0.0)
    }

    @Test fun instantTransitionIsFinishedImmediately() {
        val instant = ZoomTransition(start = 1.0, target = 2.0, began = 5.0, duration = 0.0)
        assertTrue(instant.isFinished(5.0))
        assertEquals(2.0, instant.value(5.0), 0.0)
    }

    @Test fun centersInterpolateWithTheSameProgress() {
        val pan = ramp.copy(startCenter = FramingPoint(0.5, 0.5), targetCenter = FramingPoint(0.7, 0.3))
        assertEquals(FramingPoint(0.6, 0.4).x, pan.center(11.0)!!.x, 1e-12)
        assertEquals(0.4, pan.center(11.0)!!.y, 1e-12)
        assertNull(ramp.center(11.0))
    }

    @Test fun durationsAreHalfSecondStepsUpToThirtySeconds() {
        assertEquals(0.5, ZoomTransition.boundedDuration(0.3), 0.0)
        assertEquals(1.0, ZoomTransition.boundedDuration(0.76), 0.0)
        assertEquals(30.0, ZoomTransition.boundedDuration(45.0), 0.0)
        assertEquals(0.0, ZoomTransition.boundedDuration(-2.0), 0.0)
        assertEquals(0.0, ZoomTransition.boundedDuration(Double.NaN), 0.0)
        assertEquals("Instant", ZoomTransition.durationLabel(0.0))
        assertEquals("1 second", ZoomTransition.durationLabel(1.0))
        assertEquals("2.5 seconds", ZoomTransition.durationLabel(2.5))
        assertEquals(listOf(0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0), ZoomTransition.durationPresets)
    }

    @Test fun pinchHasANeutralZoneThenFollowsTheFingers() {
        assertEquals(2.0, RecorderPinch.zoom(2.0, 100f, 102f, 1.0, 10.0), 1e-9) // 2% jitter: no zoom step
        assertEquals(4.0, RecorderPinch.zoom(2.0, 100f, 200f, 1.0, 10.0), 1e-9) // doubled span
        assertEquals(10.0, RecorderPinch.zoom(8.0, 100f, 400f, 1.0, 10.0), 1e-9) // clamped
        assertEquals("2.0×  ·  3s on release", RecorderPinch.badge(2.0, 1.0, 3.0))
        assertEquals("1.5×", RecorderPinch.badge(1.5, 1.0, 0.0))
    }
}
