// SPEC "Camera support and switching" and the UI acceptance ("switch camera"):
// during one take, switch through every camera the camera menu offers (on the
// a phone: the rear lenses and the front camera) and back to the first one.
// The take stays one source with a `cameraSwitch` event per change, its audio
// has no gap, and the file plays from its first to its last frame.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CameraSwitchAcceptanceTest : AcceptanceTest() {
    @Test fun switchEveryCameraMidTake() {
        launchApp()
        val id = createTestProject("Camera switch")
        val cameras = cameraMenuChoices()
        assumeTrue("The camera menu offers only ${cameras.size} camera(s); a switch needs two", cameras.size >= 2)
        val first = onMain { model.camera.currentCamera?.name } ?: cameras.first()
        val visits = cameras.filter { it != first } + first
        Suite.note("Cameras: $cameras; recording on $first then switching to $visits")

        startRecording()
        holdRecording(1.5)
        for (name in visits) {
            selectCamera(name)
            holdRecording(1.5)
        }
        stopRecording(id, 1)

        val project = savedProject(id)
        assertEquals("The switches stay in one take", 1, project.sources.size)
        val source = project.sources.single()
        val events = source.events ?: emptyList()
        val switches = events.filter { it.kind == "cameraSwitch" }
        assertEquals("One cameraSwitch event per change", visits.size, switches.size)
        assertEquals("The events name the cameras in order", visits.map { "Camera changed to $it" }, switches.map { it.detail })
        assertTrue("Switch times increase inside the take", switches.zipWithNext().all { (a, b) -> a.time < b.time })
        val gaps = events.filter { it.kind == "audioGap" }
        assertTrue("Audio is continuous across switches; gaps: ${gaps.map { it.detail }}", gaps.isEmpty())

        val file = sourceFiles(project).single()
        val (frames, firstFrame, lastFrame) = MediaCheck.firstAndLastFrames(file)
        assertTrue("The file has video frames", frames > 0)
        assertNotNull("The first frame decodes", firstFrame)
        assertNotNull("The last frame decodes (frame ${frames - 1})", lastFrame)
        Evidence.copy(evidenceName, file)
        val aligned = MediaCheck.assertAudioVideoAligned(file)
        Evidence.json(evidenceName, "report.json", json("cameras" to jsonArray(cameras), "visits" to jsonArray(visits),
            "events" to jsonArray(events.map { json("time" to it.time, "kind" to it.kind, "detail" to it.detail) }),
            "frames" to frames, "media" to aligned))
        finishWithProject(id)
    }
}
