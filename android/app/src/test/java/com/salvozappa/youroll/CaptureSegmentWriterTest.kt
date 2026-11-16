package com.salvozappa.youroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Segment clock, A/V alignment and canvas placement of the own writer (CaptureSegmentWriter.kt, CaptureGL.kt). */
class CaptureSegmentWriterTest {
    private val ms = 1_000_000L
    private val frame = 33_333_333L

    @Test fun theSegmentStartsAtItsFirstFrameAndTimestampsOnlyIncrease() {
        val timing = CaptureVideoTiming()
        val first = timing.camera(time = 1000 * ms, arrival = 1050 * ms)
        assertTrue(first.write); assertTrue(first.started)
        assertEquals(1000 * ms, timing.start)
        assertTrue(timing.camera(1033 * ms, 1083 * ms).write)
        // A frame stamped at or before the last written one is dropped.
        val late = timing.camera(1020 * ms, 1090 * ms)
        assertFalse(late.write); assertFalse(late.started)
        assertEquals(1033 * ms, timing.lastVideo)
    }

    @Test fun aCameraChangeIsMeasuredFromFrameTimestampsAndFilledWithHeldFrames() {
        val timing = CaptureVideoTiming()
        timing.camera(0, 40 * ms)
        timing.camera(frame, frame + 40 * ms)
        // Within the hold threshold nothing is repeated.
        assertNull(timing.heldFrame(frame + 120 * ms))
        // 150 ms of camera time without frames: repeat at the frame cadence, on the camera's latency.
        val held = mutableListOf<Long>()
        var now = frame + 40 * ms
        while (now < frame + 640 * ms) { timing.heldFrame(now)?.let { held += it }; now += 15 * ms }
        assertTrue(held.size >= 14)
        held.zipWithNext().forEach { (a, b) -> assertEquals(frame, b - a) }
        assertTrue(held.last() <= frame + 600 * ms)
        assertEquals(held.size, timing.heldFrames)
        // The new camera resumes: the hold is reported once, measured between real frames.
        val resumed = timing.camera(frame + 640 * ms, frame + 680 * ms)
        assertTrue(resumed.write)
        assertEquals(0.64, resumed.gap!!, 1e-9)
        assertEquals(frame / 1e9, resumed.gapAt, 1e-9)
        assertNull(timing.camera(frame + 640 * ms + frame, frame + 720 * ms + frame).gap)
    }

    @Test fun heldFramesNeverPassTheStopTime() {
        val timing = CaptureVideoTiming()
        timing.camera(0, 30 * ms)
        val end = 200 * ms
        val held = generateSequence(0L) { it + 10 * ms }.takeWhile { it < 2000 * ms }.mapNotNull { timing.heldFrame(it, end) }.toList()
        assertTrue(held.isNotEmpty())
        assertTrue(held.all { it < end })
        // Frames from the camera after Stop are not written.
        assertFalse(timing.camera(end, end + 30 * ms, end).write)
    }

    @Test fun stopEndsVideoAtTheRequestAndTheLastPictureFillsTheRest() {
        val timing = CaptureVideoTiming()
        timing.camera(0, 50 * ms)
        timing.camera(frame, frame + 50 * ms)
        // Stop requested before the last picture's end: the end is that picture's end.
        assertEquals(2 * frame, timing.end(40 * ms))
        val end = timing.end(500 * ms)
        assertEquals(500 * ms, end)
        val final = timing.finalFrame(end)!!
        // MediaMuxer gives the last sample the previous interval, so the track ends at `end`.
        assertEquals(end.toDouble(), (final + (final - timing.lastVideo!!)).toDouble(), 1.0)
        assertNull(CaptureVideoTiming().finalFrame(end))
    }

    /**
     * A take with a 0.6 s camera change: continuous 48 kHz audio across the change,
     * and video and audio durations within one frame of each other at Stop.
     */
    @Test fun aTakeWithACameraSwitchKeepsAudioContinuousAndDurationsAligned() {
        val timing = CaptureVideoTiming()
        val t0 = 5_000 * ms
        val latency = 60 * ms
        val stop = t0 + 4_000 * ms
        // Camera: 30 fps, nothing between 2.0 s and 2.6 s (the switch).
        val cameraTimes = generateSequence(t0) { it + frame }.takeWhile { it < stop + 200 * ms }
            .filter { it - t0 < 2_000 * ms || it - t0 >= 2_600 * ms }.toList()
        // Microphone: 1024-frame blocks from before the take until after Stop.
        val blockNanos = 1024 * 1_000_000_000L / 48_000
        val audioTimes = generateSequence(t0 - 300 * ms) { it + blockNanos }.takeWhile { it < stop + 300 * ms }.toList()
        var normalizer: CaptureAudioNormalizer? = null
        val pending = ArrayDeque<CapturedAudio>()
        val written = mutableListOf<Long>()
        var end: Long? = null
        var audioFrames = 0L
        fun feed(block: CapturedAudio) {
            pending.addLast(block)
            val n = normalizer ?: return
            while (pending.isNotEmpty()) audioFrames += n.process(pending.removeFirst(), end).sumOf { it.frames }
        }
        // Run the media thread's events in arrival order, with the 15 ms hold tick.
        data class Event(val at: Long, val run: () -> Unit)
        val events = mutableListOf<Event>()
        for (c in cameraTimes) events += Event(c + latency) {
            val d = timing.camera(c, c + latency, end)
            if (d.started) normalizer = CaptureAudioNormalizer(timing.start!!, 2)
            if (d.write) written += c
        }
        for (a in audioTimes) events += Event(a + blockNanos + 5 * ms) { feed(CapturedAudio(ShortArray(1024), 1024, 1, 48_000, a)) }
        generateSequence(t0) { it + 15 * ms }.takeWhile { it < stop + 300 * ms }.forEach { tick ->
            events += Event(tick) { timing.heldFrame(tick, end)?.let { written += it } }
        }
        events += Event(stop) { end = timing.end(stop) }
        events.sortedBy { it.at }.forEach { it.run() }
        val n = normalizer!!
        val finalEnd = end!!
        audioFrames += n.pad(finalEnd).sumOf { it.frames }
        timing.finalFrame(finalEnd)?.let { written += it }
        val start = timing.start!!
        assertEquals(0L, n.insertedSilence)            // no gap in the audio across the switch
        assertEquals(n.nextFrame, audioFrames)
        val sorted = written.sorted()
        val videoDuration = sorted.last() + (sorted.last() - sorted[sorted.size - 2]) - start
        val audioDuration = audioFrames * 1_000_000_000L / 48_000
        assertTrue("video ${videoDuration / 1e6} ms, audio ${audioDuration / 1e6} ms", abs(videoDuration - audioDuration) < frame)
        // The switch is filled with held frames, so no interval is longer than ~ a frame plus a tick.
        val largestGap = sorted.zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue("largest gap ${largestGap / 1e6} ms", largestGap < frame + 20 * ms)
        assertTrue(timing.heldFrames >= 14)
    }

    private fun apply(m: FloatArray, u: Float, v: Float) = Pair(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])

    @Test fun theCameraTransformOfTheSurfaceTextureIsUndone() {
        // The plain SurfaceTexture matrix (vertical flip) needs nothing.
        val flip = CaptureCanvas.identity().also { it[5] = -1f; it[13] = 1f }
        assertTrue(CaptureCanvas.identity().contentEquals(CaptureCanvas.cameraTransformUndo(flip)))
        // A sensor turned a quarter (or three) and a mirrored front camera: the product is the plain flip again.
        for (quarter in 0..3) for (mirror in listOf(false, true)) {
            val camera = CaptureCanvas.multiply(flip, CaptureCanvas.centreTurn(quarter, mirror))
            val raw = CaptureCanvas.multiply(camera, CaptureCanvas.cameraTransformUndo(camera))
            for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 0.25f to 0.75f)) {
                val (x, y) = apply(raw, u, v)
                assertEquals("quarter $quarter mirror $mirror", u, x, 1e-5f)
                assertEquals("quarter $quarter mirror $mirror", 1 - v, y, 1e-5f)
            }
        }
        // centreTurn keeps the centre and turns a corner a quarter counter-clockwise.
        val (cx, cy) = apply(CaptureCanvas.centreTurn(1, false), 0.5f, 0.5f)
        assertEquals(0.5f, cx, 1e-6f); assertEquals(0.5f, cy, 1e-6f)
        val (x, y) = apply(CaptureCanvas.centreTurn(1, false), 1f, 0.5f)
        assertEquals(0.5f, x, 1e-6f); assertEquals(1f, y, 1e-6f)
    }

    @Test fun aCameraWithAnotherSensorRotationIsTurnedAndFittedIntoTheCanvas() {
        // Same rotation, same size: fills the canvas.
        assertEquals(CaptureCanvas.Fit(0, 1.0, 1.0), CaptureCanvas.fit(1920, 1080, 90, 90, 1920, 1080))
        // Front 270° after rear 90°: turned upside down, still filling.
        assertEquals(CaptureCanvas.Fit(180, 1.0, 1.0), CaptureCanvas.fit(1920, 1080, 270, 90, 1920, 1080))
        // A quarter turn is fitted with bars (pillarbox inside a landscape canvas).
        val quarter = CaptureCanvas.fit(1920, 1080, 0, 90, 1920, 1080)
        assertEquals(270, quarter.turn)
        assertEquals(1080.0 * (1080.0 / 1920) / 1920, quarter.scaleX, 1e-9)
        assertEquals(1.0, quarter.scaleY, 1e-9)
        // A smaller 4:3 frame is fitted, not stretched.
        val small = CaptureCanvas.fit(1440, 1080, 90, 90, 1920, 1080)
        assertEquals(0.75, small.scaleX, 1e-9); assertEquals(1.0, small.scaleY, 1e-9)
        assertNotNull(small)
    }
}

class FrameClockTest {
    // Seen on a real phone: CameraX reported UPTIME, frames were on the boot-time clock.
    @org.junit.Test fun pixelFramesOnRealtimeAreDetected() {
        val monotonic = 175_128_024_000_000L; val realtime = 435_335_610_000_000L
        org.junit.Assert.assertTrue(frameClockIsRealtime(435_335_563_141_700L, monotonic, realtime))
    }
    @org.junit.Test fun uptimeFramesStayOnMonotonic() {
        val monotonic = 175_128_024_000_000L; val realtime = 435_335_610_000_000L
        org.junit.Assert.assertFalse(frameClockIsRealtime(175_127_990_000_000L, monotonic, realtime))
    }
}
