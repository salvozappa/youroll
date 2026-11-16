// SPEC "Export and persistent editing history": export twice from the editor.
// Both exports are new, distinct files; H.264 video and AAC audio; each lasts as
// long as the edit it rendered (within one frame) with audio and video aligned;
// the sources and the earlier export are unchanged (SHA-256), and the editing
// history stays available.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import com.salvozappa.youroll.uuidString
import com.salvozappa.youroll.ExportLibrary
import com.salvozappa.youroll.ExportProgress
import com.salvozappa.youroll.ProjectStore
import com.salvozappa.youroll.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExportAcceptanceTest : AcceptanceTest() {
    /** Editor → Export → Export video; waits for the finished movie and closes the panel. Returns its file. */
    private fun exportFromEditor(id: UUID): File {
        val before = onMain { ExportLibrary.receipts(projectFolder(id)).map { it.filename }.toSet() }
        tapDesc("Export")
        // The export sheet is its own window and does not expose its test tags (`export.start`,
        // `export.done`) as resource ids, so its controls are found by their text.
        tap(text("Export video"), "Export video")
        waitForApp("the export to finish", 180_000) {
            model.exportController.error?.let { throw AssertionError("Export failed: $it") }
            !model.exportController.rendering && model.exportController.exportProgress.step == ExportProgress.Step.complete &&
                ExportLibrary.receipts(projectFolder(id)).size == before.size + 1
        }
        find(text("Exported"), "the Exported confirmation")
        tap(text("Done"), "Done")
        waitForApp("the export panel to close") { model.exportingProject == null }
        val receipt = onMain { ExportLibrary.receipts(projectFolder(id)).first { it.filename !in before } }
        return File(ExportLibrary.directory(projectFolder(id)), receipt.filename)
    }

    @Test fun exportTwice() {
        launchApp()
        val id = createTestProject("Export")
        startRecording(); holdRecording(4.0); stopRecording(id, 1)
        val project = savedProject(id)
        val sources = sourceFiles(project)
        val sourceHashes = sources.map { ProjectStore.fingerprint(it) }
        assertEquals("Source hash matches the one recorded when it was indexed", project.sources.map { it.sha256 }, sourceHashes)

        openEditorFromRecorder()
        splitAt(id, project.sources.single().duration * 0.5)
        val first = exportFromEditor(id)
        val firstHash = ProjectStore.fingerprint(first)

        // A second edit, then a second export: a new file next to the first one.
        deleteRange(id, project.sources.single().duration * 0.15, project.sources.single().duration * 0.35)
        val second = exportFromEditor(id)

        val receipts = onMain { ExportLibrary.receipts(projectFolder(id)) }
        assertEquals("Two exports", 2, receipts.size)
        assertNotEquals("Two distinct files", first.canonicalPath, second.canonicalPath)
        assertTrue("Both exports exist", first.isFile && second.isFile)
        assertEquals("The first export is unchanged by the second", firstHash, ProjectStore.fingerprint(first))
        assertEquals("Sources are unchanged by exporting", sourceHashes, sources.map { ProjectStore.fingerprint(it) })

        val report = json("project" to id.uuidString)
        for ((name, movie) in listOf("first" to first, "second" to second)) {
            val receipt = receipts.first { it.filename == movie.name }
            val tracks = MediaCheck.tracks(movie)
            assertEquals("$name export: H.264 video", "video/avc", tracks.first { it.mime.startsWith("video/") }.mime)
            assertEquals("$name export: AAC audio", "audio/mp4a-latm", tracks.first { it.mime.startsWith("audio/") }.mime)
            val container = MediaCheck.containerSeconds(movie)
            val video = MediaCheck.video(movie).seconds
            val audio = MediaCheck.audio(movie).seconds
            Evidence.copy(evidenceName, movie, "$name-${movie.name}")
            report.put(name, json("file" to movie.name, "edit" to receipt.edit.duration, "container" to container, "video" to video,
                "audio" to audio, "sha256" to ProjectStore.fingerprint(movie)))
            Evidence.json(evidenceName, "report.json", report)
            assertEquals("$name export: video lasts as long as the edit", receipt.edit.duration, video, Suite.FRAME + 0.002)
            // AAC ends on whole 1024-sample packets: the audio track may run up to two packets
            // (43 ms at 48 kHz) past the picture at the end. Both tracks start at zero.
            assertEquals("$name export: audio lasts as long as the edit (audio ${"%.4f".format(audio)} s)",
                receipt.edit.duration, audio, 2 * 1024 / 48000.0 + 0.003)
            assertTrue("$name export: audio does not end early (audio ${"%.4f".format(audio)} s, video ${"%.4f".format(video)} s)",
                audio >= video - Suite.FRAME)
        }
        val saved = savedProject(id)
        assertTrue("Editing history remains available after exporting", saved.canUndo)
        assertTrue("Undo is enabled in the editor", isEnabled(desc("Undo"), "the Undo button"))
        undo(id)
        assertEquals("Undo after exporting restores the earlier edit", Edits.comparable(receipts.first { it.filename == first.name }.edit), Edits.comparable(currentEdit(id)))
        Evidence.json(evidenceName, "report.json", report)
        finishWithProject(id)
    }
}
