// YouRoll tests for export receipts and publishing (ExportLibrary in MediaRenderer.kt).
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ExportReceiptTests {
    @get:Rule val temporary = TemporaryFolder()

    private fun project(): Project {
        val project = Project("Receipts")
        project.append(SourceMedia(filename = "take.mp4", duration = 4.0, sha256 = "abc"))
        return project
    }

    private fun receipt(project: Project, filename: String, createdAt: SwiftDate = SwiftDate.now(), settings: ExportSettings = ExportSettings()) =
        ExportReceipt(createdAt = createdAt, filename = filename, projectID = project.id, projectTitle = project.title,
            historyRevision = project.historyCursor, edit = project.edit, sources = project.sources,
            sourceChecksums = mapOf(project.sources[0].id.uuidString to "abc"), width = 720, height = 1280,
            settings = settings, codec = ExportSettings.Codec.h264)

    private fun publish(folder: File, receipt: ExportReceipt, bytes: ByteArray = byteArrayOf(1, 2, 3)): File {
        val scratch = File(folder, "Processing").apply { mkdirs() }
        val partial = File(scratch, newUUID().uuidString + ".mp4").apply { writeBytes(bytes) }
        val destination = File(ExportLibrary.directory(folder), receipt.filename)
        ExportLibrary.publish(partial, receipt, destination)
        require(!partial.exists(), "The temporary movie is moved, not copied")
        return destination
    }

    @Test fun publishedExportsRoundTripAndNeverOverwrite() {
        val folder = temporary.newFolder("P")
        val project = project()
        val first = receipt(project, "AAAA.mp4", SwiftDate(1000.0))
        val movie = publish(folder, first)
        require(movie.readBytes().contentEquals(byteArrayOf(1, 2, 3)) && File(movie.parentFile, "AAAA.json").exists(), "Movie and receipt are published")
        val reread = ExportLibrary.receipts(folder).single()
        require(reread == first, "The receipt round-trips with its edit snapshot")
        require(File(movie.parentFile, "AAAA.json").readText().contains("\"historyRevision\""), "Swift field names")
        assertThrowing<IOException>("An existing export is never replaced") { publish(folder, first.copy(id = newUUID()), byteArrayOf(9)) }
        require(movie.readBytes().contentEquals(byteArrayOf(1, 2, 3)), "The earlier movie is untouched")

        val second = receipt(project, "BBBB.mp4", SwiftDate(2000.0))
        publish(folder, second, byteArrayOf(4))
        require(ExportLibrary.receipts(folder).map { it.filename } == listOf("BBBB.mp4", "AAAA.mp4"), "Both exports exist, newest first")
    }

    @Test fun receiptsWithoutAMovieOrWithAPathAreIgnored() {
        val folder = temporary.newFolder("P")
        val project = project()
        val exports = ExportLibrary.directory(folder).apply { mkdirs() }
        // A crash between publishing the receipt and the movie leaves an orphan receipt.
        File(exports, "ORPHAN.json").writeBytes(ExportLibrary.encode(receipt(project, "ORPHAN.mp4")))
        File(exports, "EVIL.json").writeBytes(ExportLibrary.encode(receipt(project, "../project.json")))
        File(folder, "project.json").writeText("{}")
        File(exports, "BROKEN.json").writeText("{ not json")
        require(ExportLibrary.receipts(folder).isEmpty(), "Only complete, local exports are listed")
    }

    @Test fun anUnchangedEditMatchesItsExport() {
        val folder = temporary.newFolder("P")
        val project = project()
        publish(folder, receipt(project, "AAAA.mp4"))
        val receipts = ExportLibrary.receipts(folder)
        require(ExportLibrary.matching(project, receipts, folder)?.filename == "AAAA.mp4", "Same edit and settings: offer Share")

        val renamed = project.copy().also { it.title = "Renamed" }
        require(ExportLibrary.matching(renamed, receipts, folder) != null, "A new title does not change the movie")

        val edited = project.copy()
        val edit = edited.edit
        edit.timeline.split(et(1), edit.timeline.entireSelection())
        edit.timeline.remove(edit.timeline.selection(edit.timeline.chronologicalVideo[0].id))
        edited.commit(edit)
        require(ExportLibrary.matching(edited, receipts, folder) == null, "A different edit needs a new export")

        val recorded = project.copy()
        recorded.append(SourceMedia(filename = "more.mp4", duration = 2.0))
        require(ExportLibrary.matching(recorded, receipts, folder) == null, "New footage needs a new export")

        val other = receipt(project, "CCCC.mp4", settings = ExportSettings(codec = ExportSettings.Codec.hevc))
        publish(folder, other)
        require(ExportLibrary.matching(project, ExportLibrary.receipts(folder).filter { it.filename == "CCCC.mp4" }, folder) == null,
            "Other export settings do not match")

        File(ExportLibrary.directory(folder), "AAAA.mp4").delete()
        require(ExportLibrary.matching(project, receipts, folder) == null, "A missing movie is not offered")
    }
}
