// SPEC "Recording and project lifecycle", Acceptance: record A, pause, record B,
// stop, close the app, reopen later, and record C. One project contains A+B+C
// without paused intervals; previously completed edits still apply.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import com.salvozappa.youroll.uuidString
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecordingLifecycleAcceptanceTest : AcceptanceTest() {
    private val pausedSeconds = 3.0

    @Test fun step1_recordAPauseRecordBStopAndEdit() {
        launchApp()
        val id = createTestProject("Lifecycle")
        waitRecorderIdle()

        // Wall time runs from the Record tap to the Pause/Stop tap: it contains the whole segment.
        val startA = SystemClock.elapsedRealtime(); startRecording()
        holdRecording(2.5)
        val endA = pauseRecording(id, 1)
        // The paused interval must not reach the video.
        SystemClock.sleep((pausedSeconds * 1000).toLong())
        val startB = SystemClock.elapsedRealtime(); startRecording()
        assertTrue("B starts after the pause", (startB - endA) / 1000.0 >= pausedSeconds)
        holdRecording(2.5)
        val endB = stopRecording(id, 2)

        // A completed edit that must survive the relaunch and the new footage.
        openEditorFromRecorder()
        val a = savedProject(id).sources.first()
        val cut = splitAt(id, a.duration * 0.4)
        val edit = savedProject(id).edit
        assertTrue("The split is saved", Edits.cuts(edit).any { abs(it - cut) < 1e-6 })

        saveMarker(json("project" to id.uuidString, "cut" to cut,
            "wallA" to (endA - startA) / 1000.0, "wallB" to (endB - startB) / 1000.0, "paused" to (startB - endA) / 1000.0))
    }

    @Test fun step2_afterKillRelaunchRecordCAndCheck() {
        val marker = markerFor("step2")
        assumeFreshProcess(marker)
        val id = uuid(marker.getString("project"))
        launchApp()
        waitRecorderIdle()
        assertEquals("The relaunched recorder continues the same project", id, onMain { store.selectedID.value })
        find(desc("Ready to continue recording"), "the recorder's “Ready to continue recording” state")

        val startC = SystemClock.elapsedRealtime(); startRecording()
        holdRecording(2.5)
        val endC = stopRecording(id, 3)
        val walls = listOf(marker.getDouble("wallA"), marker.getDouble("wallB"), (endC - startC) / 1000.0)

        val project = savedProject(id)
        val files = sourceFiles(project)
        val report = json("project" to id.uuidString, "pausedSeconds" to marker.getDouble("paused"))
        assertEquals("One project holds three segments", 3, project.sources.size)
        assertEquals("Segments are in recording order", files.sortedBy { recordedAt(it) }, files)
        val segments = jsonArray(project.sources.mapIndexed { index, source ->
            val file = files[index]
            assertTrue("${file.name} exists", file.isFile)
            val wall = walls[index]
            // The segment holds its own recording time only: never the 3 s pause or the relaunch.
            assertTrue("Segment ${"ABC"[index]} lasts ${source.duration} s, between Record and Pause/Stop taps $wall s apart",
                source.duration <= wall + 0.5 && source.duration >= 2.4)
            // Keep the segment for inspection before checking it.
            Evidence.copy(evidenceName, file, "${"ABC"[index]}-${file.name}")
            val aligned = MediaCheck.assertAudioVideoAligned(file)
            val container = MediaCheck.containerSeconds(file)
            assertEquals("${file.name}: project duration matches the file", container, source.duration, Suite.FRAME + 0.002)
            aligned.put("projectDuration", source.duration).put("wallSeconds", wall)
        })
        report.put("segments", segments)

        val edit = project.edit
        val captured = project.sources.sumOf { it.duration }
        assertEquals("Captured duration is A+B+C", captured, project.capturedDuration, 1e-9)
        assertEquals("Edited duration is A+B+C (no paused interval, nothing duplicated)", captured, edit.duration, Suite.FRAME)
        val ids = project.sources.map { it.id }
        assertEquals("Timeline order: A (split), A, B, C", listOf(ids[0], ids[0], ids[1], ids[2]), Edits.sourceOrder(edit))
        val coverage = Edits.coverage(edit)
        project.sources.forEach { source ->
            assertEquals("The timeline uses all of ${source.filename}", source.duration, coverage[source.id] ?: 0.0, Suite.FRAME)
        }
        val cut = marker.getDouble("cut")
        assertTrue("The edit made before the relaunch still applies (cut at $cut s)", Edits.cuts(edit).any { abs(it - cut) < 1e-6 })
        report.put("cuts", jsonArray(Edits.cuts(edit))).put("editedDuration", edit.duration)
        Evidence.copy(evidenceName, File(projectFolder(id), "project.json"))
        Evidence.json(evidenceName, "report.json", report)
        finishWithProject(id)
        Marker(javaClass.simpleName).delete()
    }
}
