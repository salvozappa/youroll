// YouRoll tests for the SPEC.md acceptance flows on persistent editing history
// ("Export and persistent editing history", "Recording and project lifecycle").
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class HistoryAcceptanceTests {
    @get:Rule val temporary = TemporaryFolder()

    private fun reopen(disk: ProjectDisk, project: Project): Project = disk.read(File(disk.folder(project.id), "project.json"))

    private fun trimmedEnd(project: Project, seconds: Double) {
        val edit = project.edit
        edit.updateClip(0) { it.copy(end = it.end - seconds) }
        project.commit(edit)
    }

    /** Make edits A, B and C; undo C; close; reopen; redo C. Then undo C and make D: redo of C is gone. */
    @Test fun undoRedoAcrossReopenAndDivergentEdit() {
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        var project = Project("Acceptance")
        project.append(SourceMedia(filename = "take.mov", duration = 20.0))
        trimmedEnd(project, 1.0) // A
        val afterA = project.edit
        trimmedEnd(project, 1.0) // B
        val afterB = project.edit
        trimmedEnd(project, 1.0) // C
        val afterC = project.edit
        project.undo()
        require(project.edit == afterB && project.canRedo, "Undo C")
        disk.save(project)
        project = reopen(disk, project)
        require(project.edit == afterB && project.canUndo && project.canRedo, "Undo and redo both survive closing and reopening")
        project.redo()
        require(project.edit == afterC && !project.canRedo, "Redo C after reopening")
        project.undo()
        val d = project.edit; d.timeline.updateAudio(0) { it.copy(volumeDB = -6.0) }; project.commit(d)
        require(!project.canRedo, "A new edit after undo replaces the abandoned redo path")
        disk.save(project)
        project = reopen(disk, project)
        require(!project.canRedo && project.edit.timeline.audio[0].volumeDB == -6.0, "The divergent edit persists without a redo of C")
        project.undo(); require(project.edit == afterB, "Undo D returns to B")
        project.undo(); require(project.edit == afterA, "Undo B returns to A")
        project.redo(); project.redo(); require(project.edit.timeline.audio[0].volumeDB == -6.0, "Redo returns to D, not C")
    }

    /** Record two segments, edit, undo all, then make a new edit: both recordings remain. */
    @Test fun recordingsSurviveUndoAll() {
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        var project = Project("Two segments")
        val first = SourceMedia(filename = "a.mov", duration = 5.0)
        val second = SourceMedia(filename = "b.mov", duration = 7.0)
        project.append(first); project.append(second)
        require(!project.canUndo, "Recording is not an undo step")
        trimmedEnd(project, 2.0)
        val removal = project.edit; removal.clips = removal.clips.drop(1); project.commit(removal)
        disk.save(project); project = reopen(disk, project)
        while (project.canUndo) project.undo()
        require(project.edit.clips.map { it.sourceID } == listOf(first.id, second.id) && abs(project.edit.duration - 12.0) < 1e-9,
            "Undo all keeps both recordings")
        val next = project.edit; next.timeline.updateAudio(1) { it.copy(muted = true) }; project.commit(next)
        require(project.edit.clips.map { it.sourceID } == listOf(first.id, second.id), "A new edit after undo all keeps both recordings")
        disk.save(project); project = reopen(disk, project)
        require(project.edit.clips.size == 2 && project.edit.timeline.audio[1].muted, "The result survives reopening")
    }

    /** After editing, undo twice, record more footage and redo: the edits and the added footage both survive. */
    @Test fun recordingDuringUndoKeepsRedo() {
        val disk = ProjectDisk(File(temporary.root, "Projects"))
        var project = Project("Continue recording")
        val a = SourceMedia(filename = "a.mov", duration = 10.0)
        val b = SourceMedia(filename = "b.mov", duration = 10.0)
        project.append(a)
        trimmedEnd(project, 1.0)
        trimmedEnd(project, 1.0)
        trimmedEnd(project, 1.0)
        project.undo(); project.undo()
        disk.save(project); project = reopen(disk, project) // close the app, reopen later
        project.append(b)
        require(project.canRedo && project.edit.clips.map { it.sourceID } == listOf(a.id, b.id), "Recording does not move the cursor or discard redo")
        disk.save(project); project = reopen(disk, project)
        project.redo(); project.redo()
        require(project.edit.clips.map { it.sourceID } == listOf(a.id, b.id) && project.edit.clips[0].end == 7.0,
            "Redo restores the edits and keeps the new footage")
        while (project.canUndo) project.undo()
        require(project.edit.clips.map { it.sourceID } == listOf(a.id, b.id), "Undo never removes a newly captured segment")
        // Explicit deletion and its undo still work.
        project.redo()
        val deletion = project.edit; deletion.timeline.remove(deletion.timeline.selection(of = deletion.timeline.video[1].id)); project.commit(deletion)
        require(project.edit.clips.map { it.sourceID } == listOf(a.id), "Explicit deletion of a take")
        project.undo()
        require(project.edit.clips.map { it.sourceID } == listOf(a.id, b.id), "Undo restores the deleted take")
    }

    /** Record A, pause, B, stop, reopen later, C: one project contains A+B+C in order; earlier edits still apply. */
    @Test fun continueRecordingAfterRelaunch() {
        val root = File(temporary.root, "Projects")
        val store = ProjectStore(root)
        val project = store.create()!!
        val a = SourceMedia(filename = "a.mov", duration = 3.0)
        val b = SourceMedia(filename = "b.mov", duration = 4.0)
        val c = SourceMedia(filename = "c.mov", duration = 5.0)
        require(store.update(project.id) { it.append(a) } && store.update(project.id) { it.append(b) }, "A and B are saved")
        val edit = store.projects.value.first { it.id == project.id }.edit
        edit.timeline.updateAudio(0) { it.copy(volumeDB = -2.0) }
        require(store.commit(project.id, edit), "Edit before stopping")
        val relaunched = ProjectStore(root)
        require(relaunched.update(project.id) { it.append(c) }, "C is appended after relaunch")
        val final = ProjectStore(root)
        val summary = ProjectDisk(root).read(File(ProjectDisk(root).folder(project.id), "project.json"), summary = true)
        require(final.openProject(project.id), "Open the project")
        val full = final.projects.value.first { it.id == project.id }
        require(summary.deferredHistory != null && summary.edit == full.edit && full.deferredHistory == null,
            "Library summaries show the current edit, the editor hydrates the history")
        require(full.edit.clips.map { it.sourceID } == listOf(a.id, b.id, c.id) && full.edit.duration == 12.0, "A+B+C in order without gaps")
        require(full.edit.timeline.audio[0].volumeDB == -2.0 && full.canUndo, "Earlier edits still apply and stay undoable")
    }
}
