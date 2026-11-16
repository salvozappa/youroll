// SPEC "Zoom controls and memory": choose a transition duration in the
// recorder's settings, tap a zoom preset and check that the zoom ramps over that
// duration (not a jump); then each camera keeps its own zoom across a switch to
// another camera and back.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

class ZoomAcceptanceTest : AcceptanceTest() {
    private val rampSeconds = 2.0

    private fun displayZoom(): Double = onMain { model.camera.readings.zoom * model.camera.displayZoomMultiplier }

    private fun presetTag(zoom: Double) = "recorder.zoom.preset.${(zoom * 10).toInt()}"

    private fun hasPreset(zoom: Double): Boolean {
        find(res("recorder.zoom.duration"), "the zoom presets")
        return device.hasObject(res(presetTag(zoom)))
    }

    /** Camera settings → More settings → Zoom → Transition → [label]. */
    private fun chooseTransition(label: String) {
        tapDesc("Camera settings")
        tap(res("recorder.moreSettings"), "More settings")
        scrollUntil(By.scrollable(true), res("recorder.transition"), "the zoom Transition setting").click()
        tap(text(label), "“$label” in the transition menu")
        waitGone(text("30 seconds"), "the transition menu")   // a label only the open menu shows
        tap(res("recorder.moreSettingsDone"), "Done")
        waitGone(res("recorder.moreSettingsDone"), "the settings")
    }

    /** Taps the preset and samples the live zoom until it settles on [target]. */
    private fun ramp(target: Double): List<Pair<Double, Double>> {
        val samples = mutableListOf<Pair<Double, Double>>()
        val tapped = SystemClock.uptimeMillis()
        tap(res(presetTag(target)), "the ${target}× preset")
        val deadline = tapped + ((rampSeconds + 4) * 1000).toLong()
        while (SystemClock.uptimeMillis() < deadline) {
            val zoom = displayZoom()
            samples += (SystemClock.uptimeMillis() - tapped) / 1000.0 to zoom
            if (abs(zoom - target) < 0.005 && samples.size > 2 && abs(samples[samples.size - 2].second - target) < 0.005) break
            SystemClock.sleep(20)
        }
        return samples
    }

    private fun waitZoom(target: Double, what: String) = waitForApp(what, 10_000) {
        abs(model.camera.readings.zoom * model.camera.displayZoomMultiplier - target) < 0.02
    }

    @Test fun rampAndPerCameraMemory() {
        launchApp()
        waitRecorderIdle()
        chooseTransition("2 seconds")
        tap(res("recorder.zoom"), "the zoom button")
        find(desc("Zoom transition duration"), "the zoom presets")
        assertTrue("The recorder shows the 2 s transition", device.hasObject(text("2s")))

        // A camera with a 2× preset (the emulator's rear camera has none; its front cameras do).
        val cameras = cameraMenuChoices()
        val zoomable = mutableListOf<String>()
        for (name in cameras) {
            selectCamera(name)
            if (hasPreset(2.0)) zoomable += name
        }
        assumeTrue("No camera offers a 2× zoom preset", zoomable.isNotEmpty())
        val first = zoomable.first()
        val second = cameras.firstOrNull { it != first }
        assumeTrue("Zoom memory needs a second camera", second != null)
        second!!

        // Ramp from 1× to 2× on the first camera.
        selectCamera(first)
        if (abs(displayZoom() - 1.0) > 0.02) {
            tap(res(presetTag(1.0)), "the 1× preset")
            waitZoom(1.0, "the zoom to return to 1×")
        }
        val samples = ramp(2.0)
        val start = samples.first().second
        val moving = samples.firstOrNull { abs(it.second - start) > 0.01 } ?: throw AssertionError("The zoom never moved: $samples")
        val reached = samples.firstOrNull { abs(it.second - 2.0) < 0.01 } ?: throw AssertionError("The zoom never reached 2×: ${samples.last()}")
        val rampTime = reached.first - moving.first
        val between = samples.count { it.second > start + 0.02 && it.second < 2.0 - 0.02 }
        Evidence.json(evidenceName, "ramp.json", json("camera" to first, "transitionSeconds" to rampSeconds, "measuredSeconds" to rampTime,
            "samples" to jsonArray(samples.map { jsonArray(listOf(it.first, it.second)) })))
        assertTrue("The zoom ramps over the transition (moved for $rampTime s of $rampSeconds s)", rampTime >= rampSeconds * 0.7 && rampTime <= rampSeconds + 0.6)
        assertTrue("The ramp passes through intermediate zooms ($between samples)", between >= 10)
        find(desc("Zoom, 2 times"), "the zoom button showing 2×")

        // Per-camera memory: the other camera keeps its own zoom; switching back restores 2×.
        selectCamera(second)
        val secondTarget = if (hasPreset(3.0)) 3.0 else null
        if (secondTarget != null) {
            tap(res(presetTag(secondTarget)), "the 3× preset")
            waitZoom(secondTarget, "the second camera to reach 3×")
        }
        val secondZoom = displayZoom()
        assertTrue("$second does not take over $first's 2× zoom", secondTarget != null || abs(secondZoom - 2.0) > 0.05)
        selectCamera(first)
        waitZoom(2.0, "$first to restore its 2× zoom")
        find(desc("Zoom, 2 times"), "the zoom button showing the remembered 2×")
        selectCamera(second)
        waitZoom(secondZoom, "$second to restore its own zoom (${"%.1f".format(secondZoom)}×)")
        Evidence.json(evidenceName, "memory.json", json("first" to first, "firstZoom" to 2.0, "second" to second, "secondZoom" to secondZoom))

        // Leave the defaults for the other flows: 1× and instant zoom.
        selectCamera(first)
        tap(res(presetTag(1.0)), "the 1× preset")
        waitZoom(1.0, "the zoom to return to 1×")
        chooseTransition("Instant")
        assertEquals("Instant transition restored", true, device.wait(Until.hasObject(text("Instant")), 5_000))
    }
}
