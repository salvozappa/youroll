package com.salvozappa.youroll

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Microphone ranking and fallback (SPEC "Microphone selection and monitoring"). */
class CaptureAudioTest {
    private val usb = "usb"
    private val builtin = "builtin"
    private val headset = "headset"

    @Test fun priorityStartsWithTheRoutedAccessoryOnAFreshInstall() {
        assertEquals(listOf(usb, builtin), RecordingMicrophoneRoute.priority(emptyList(), listOf(builtin, usb), actual = usb))
        // Saved order wins; new inputs are appended; disconnected ones are remembered.
        assertEquals(listOf(headset, builtin, usb), RecordingMicrophoneRoute.priority(listOf(headset, builtin), listOf(builtin, usb), actual = builtin))
    }

    @Test fun idleRequestsTheHighestPriorityInputUntilConfirmed() {
        val route = RecordingMicrophoneRoute()
        val first = route.resolve(listOf(usb, builtin), actual = builtin, preferred = null, inTake = false, now = 0.0)
        assertEquals(usb, first.request)
        assertEquals(0.25, first.recheckAfter!!, 0.0)
        val confirmed = route.resolve(listOf(usb, builtin), actual = usb, preferred = usb, inTake = false, now = 0.3)
        assertNull(confirmed.request); assertNull(confirmed.failed); assertNull(confirmed.recheckAfter)
    }

    @Test fun aRefusedRequestIsReportedAfterFourAttempts() {
        val route = RecordingMicrophoneRoute()
        var now = 0.0
        val requests = mutableListOf<String?>()
        var failed: String? = null
        repeat(8) {
            val d = route.resolve(listOf(usb, builtin), actual = builtin, preferred = usb, inTake = false, now = now)
            requests += d.request
            if (d.failed != null) failed = d.failed
            now += d.recheckAfter ?: 2.0
        }
        assertEquals(4, requests.count { it == usb })
        assertEquals(usb, failed)
    }

    @Test fun losingTheTakeMicrophoneFallsBackWithAWarningAndNoUpgradeOnReconnect() {
        val route = RecordingMicrophoneRoute()
        // Take starts on A (usb), the preferred input.
        val start = route.resolve(listOf(usb, builtin), actual = usb, preferred = usb, inTake = true, now = 0.0)
        assertNull(start.request)
        assertEquals(usb, route.pinnedInput)
        // A is unplugged; the system already reroutes to B.
        route.deviceDisconnected(listOf(usb))
        val waiting = route.resolve(listOf(builtin), actual = builtin, preferred = usb, inTake = true, now = 10.0)
        assertNull(waiting.disconnected)
        assertEquals(0.5, waiting.recheckAfter!!, 1e-9)
        val fallback = route.resolve(listOf(builtin), actual = builtin, preferred = usb, inTake = true, now = 10.6)
        assertEquals(usb, fallback.disconnected)
        assertEquals(builtin, route.pinnedInput)
        // A reconnects mid-take: keep B until Stop.
        val reconnected = route.resolve(listOf(usb, builtin), actual = builtin, preferred = builtin, inTake = true, now = 12.0)
        assertNull(reconnected.request); assertNull(reconnected.disconnected); assertNull(reconnected.failed)
        assertEquals(builtin, route.pinnedInput)
        // After Stop the preference order applies again.
        route.reset()
        assertEquals(usb, route.resolve(listOf(usb, builtin), actual = builtin, preferred = builtin, inTake = false, now = 20.0).request)
    }

    @Test fun whenTheLostInputLeavesNoRouteTheNextRankedInputIsRequestedAndConfirmed() {
        // The engine's AudioRecord died with the USB microphone: no routed device at all.
        val route = RecordingMicrophoneRoute()
        route.resolve(listOf(usb, headset, builtin), actual = usb, preferred = usb, inTake = true, now = 0.0)
        route.deviceDisconnected(listOf(usb))
        val waiting = route.resolve(listOf(headset, builtin), actual = null, preferred = usb, inTake = true, now = 5.0)
        assertNull(waiting.request)
        // After the grace period the next input in preference order is requested (setPreferredDevice).
        val fallback = route.resolve(listOf(headset, builtin), actual = null, preferred = usb, inTake = true, now = 5.6)
        assertEquals(headset, fallback.request)
        assertNull(fallback.disconnected)
        // getRoutedDevice confirms it: warning + microphoneFallback mark, pinned until Stop.
        val confirmed = route.resolve(listOf(headset, builtin), actual = headset, preferred = headset, inTake = true, now = 5.9)
        assertEquals(usb, confirmed.disconnected)
        assertEquals(headset, route.pinnedInput)
        val reconnected = route.resolve(listOf(usb, headset, builtin), actual = headset, preferred = headset, inTake = true, now = 9.0)
        assertNull(reconnected.request)
        assertEquals(headset, route.pinnedInput)
    }

    @Test fun recordingChannelsFollowTheDevice() {
        assertEquals(2, CaptureMicrophones.channels(intArrayOf(1, 2)))
        assertEquals(1, CaptureMicrophones.channels(intArrayOf(1)))
        assertEquals(1, CaptureMicrophones.channels(intArrayOf()))
        assertEquals(1, CaptureMicrophones.channels(null))
    }

    // MARK: CaptureAudioNormalizer

    private val start = 10_000_000_000L
    private fun block(frame: Long, frames: Int = 1024, channels: Int = 1, value: Short = 1, offsetNanos: Long = 0) =
        CapturedAudio(ShortArray(frames * channels) { value }, frames, channels, 48_000, start + frame * 1_000_000_000L / 48_000 + offsetNanos)

    @Test fun contiguousBlocksFollowTheSampleClock() {
        val n = CaptureAudioNormalizer(start, 2)
        var frame = 0L
        repeat(50) {
            val out = n.process(block(frame))
            assertEquals(1, out.size)
            assertEquals(frame, out[0].frame)
            assertEquals(2048, out[0].samples.size) // mono duplicated to stereo
            frame += 1024
        }
        assertEquals(frame, n.nextFrame)
        assertEquals(0L, n.insertedSilence)
    }

    @Test fun audioBeforeTheFirstVideoFrameIsTrimmed() {
        val n = CaptureAudioNormalizer(start, 1)
        assertTrue(n.process(block(-2048)).isEmpty())
        val straddling = n.process(block(-500))
        assertEquals(524, straddling.single().frames)
        assertEquals(0L, straddling.single().frame)
        assertEquals(524L, n.nextFrame)
    }

    @Test fun aMissingStretchBecomesExplicitSilence() {
        val n = CaptureAudioNormalizer(start, 1)
        n.process(block(0))
        // The input reopened 0.25 s later (a route change).
        val out = n.process(block(1024 + 12_000))
        assertEquals(12_000L, n.insertedSilence)
        assertEquals(1024L, out.first().frame)
        assertTrue(out.dropLast(1).all { chunk -> chunk.samples.all { it == 0.toShort() } })
        assertEquals(1024L + 12_000, out.last().frame)
        assertEquals(1024L + 12_000 + 1024, n.nextFrame)
    }

    @Test fun overlappingAudioIsNeverShiftedEarlier() {
        val n = CaptureAudioNormalizer(start, 1)
        n.process(block(0, frames = 2048))
        // A reopened input whose first block overlaps what was written: the overlap is skipped.
        val out = n.process(block(1024, frames = 2048, value = 7))
        assertEquals(2048L, out.single().frame)
        assertEquals(1024, out.single().frames)
        assertEquals(3072L, n.nextFrame)
    }

    @Test fun stopCutsAtTheEndAndPadsWithSilence() {
        val n = CaptureAudioNormalizer(start, 2)
        val end = start + 48_000L * 1_000_000_000L / 48_000 / 10 // 0.1 s = 4800 frames
        var frame = 0L
        while (frame < 4096) { n.process(block(frame), end); frame += 1024 }
        assertEquals(4096L, n.nextFrame)
        val after = n.process(block(4096, frames = 1024), end)
        assertEquals(704, after.single().frames)
        assertEquals(4800L, n.nextFrame)
        assertTrue(n.process(block(5120), end).isEmpty())
        // A microphone that ended early is padded to the same end as the video.
        val short = CaptureAudioNormalizer(start, 1)
        short.process(block(0))
        val padding = short.pad(end)
        assertEquals(4800L - 1024, padding.sumOf { it.frames }.toLong())
        assertEquals(4800L, short.nextFrame)
    }

    @Test fun aDriftingMicrophoneClockIsCorrectedGradually() {
        // The microphone produces 0.05% fewer samples than its timestamps say (Swift corrects up to 0.1%).
        val n = CaptureAudioNormalizer(start, 1)
        var time = start
        repeat(2000) {
            n.process(CapturedAudio(ShortArray(1024), 1024, 1, 48_000, time))
            time += Math.round(1024 * 1.0005 * 1_000_000_000.0 / 48_000)
        }
        assertTrue(n.clockCorrections > 0)
        assertEquals(0L, n.insertedSilence)
        // Output stays within ~2 ms of the timestamps instead of drifting by 21 ms.
        assertTrue(abs(n.frameAt(time) - n.nextFrame) < 100)
    }

    @Test fun anInputRunningAwayFromItsTimestampsIsReanchoredWithinOneVideoFrame() {
        // 5% more samples than the timestamps allow (seen from the emulator microphone).
        val n = CaptureAudioNormalizer(start, 1)
        var time = start
        var worst = 0L
        repeat(300) {
            n.process(CapturedAudio(ShortArray(1024), 1024, 1, 48_000, time))
            time += Math.round(1024 * 0.95 * 1_000_000_000.0 / 48_000)
            worst = maxOf(worst, abs(n.frameAt(time) - n.nextFrame))
        }
        assertTrue(n.resyncs > 0)
        assertTrue("worst offset $worst frames", worst < 1440 + 1024)
        assertEquals(0L, n.insertedSilence) // audio is skipped, never shifted earlier
    }

    @Test fun channelMaps() {
        assertEquals(listOf<Short>(5, 5, -3, -3), CapturePCM.remix(shortArrayOf(5, -3), 2, 1, 2).toList())
        assertEquals(listOf<Short>(2, -1), CapturePCM.remix(shortArrayOf(1, 3, -2, 0), 2, 2, 1).toList())
        // Inserting duplicates the quietest frame; removing drops at it.
        assertEquals(listOf<Short>(9, 0, 0, 9), CapturePCM.adjust(shortArrayOf(9, 0, 9), 3, 1, 1).toList())
        assertEquals(listOf<Short>(9, 9), CapturePCM.adjust(shortArrayOf(9, 0, 9), 3, 1, -1).toList())
    }

    @Test fun anUnreportedDisappearanceWaitsThreeSecondsForNegotiation() {
        val route = RecordingMicrophoneRoute()
        route.select(usb)
        val early = route.resolve(listOf(builtin), actual = builtin, preferred = usb, inTake = false, now = 0.0)
        assertEquals(3.0, early.recheckAfter!!, 1e-9)
        assertNull(early.request)
        val later = route.resolve(listOf(builtin), actual = builtin, preferred = usb, inTake = false, now = 3.1)
        // Outside a take the quick selection is released and the next input is used.
        assertNull(later.disconnected)
        assertNull(route.pinnedInput)
    }

    @Test fun aQuickSelectionPinsTheInput() {
        val route = RecordingMicrophoneRoute()
        route.select(builtin)
        val d = route.resolve(listOf(usb, builtin), actual = usb, preferred = usb, inTake = true, now = 0.0)
        assertEquals(builtin, d.request)
    }

    @Test fun gainIsRoundedToHalfDecibelsInsideItsRange() {
        assertEquals(1.5, RecordingGain.normalized(1.3), 0.0)
        assertEquals(12.0, RecordingGain.normalized(40.0), 0.0)
        assertEquals(-40.0, RecordingGain.normalized(-90.0), 0.0)
        assertEquals(0.0, RecordingGain.normalized(Double.NaN), 0.0)
    }

    @Test fun inputsAreListedOncePerPortWithStableIdentifiers() {
        val ports = CaptureMicrophones.ports(listOf(
            Triple(AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom", "Phone"),
            Triple(AudioDeviceInfo.TYPE_BUILTIN_MIC, "back", "Phone"),
            Triple(AudioDeviceInfo.TYPE_USB_DEVICE, "card=1;device=0", "Wireless GO II RX"),
            Triple(AudioDeviceInfo.TYPE_TELEPHONY, "", null),
            Triple(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "0", null),
        ))
        assertEquals(listOf("builtin", "${AudioDeviceInfo.TYPE_USB_DEVICE}:card=1;device=0"), ports.map { it.uid })
        assertEquals(listOf("Built-in Microphone", "Wireless GO II RX"), ports.map { it.portName })
    }

    @Test fun meterHasFastAttackAndSlowRelease() {
        val meter = LevelMeter()
        meter.process(1f, 0.0)
        assertEquals(0f, meter.measuredPeak, 1e-4f)
        assertTrue(meter.read(0.1).clipping)
        meter.process(0f, 0.05)
        assertEquals(-1.5f, meter.measuredPeak, 1e-4f) // release, not a drop to silence
        val stale = meter.read(1.0)
        assertTrue(!stale.arriving)
        assertEquals(-160f, stale.level, 0f)
        assertEquals(0.5f, LevelMeter.peak(shortArrayOf(100, -16384, 3), 3), 1e-6f)
    }
}
