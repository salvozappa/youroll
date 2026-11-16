// SPEC "Export and persistent editing history", Acceptance:
// - make edits A, B (splits) and C (an explicit range deletion); undo C; close;
//   reopen; redo C. Then undo C and make D: redo of C is no longer offered.
// - record two segments, edit, undo all, then make a new edit: both recordings remain.
// - after editing, undo twice, record more footage, and redo: the edits and the
//   added footage both survive.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import com.salvozappa.youroll.uuidString
import com.salvozappa.youroll.EditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EditHistoryAcceptanceTest : AcceptanceTest() {
    private fun assertSameEdit(message: String, expected: EditState, actual: EditState) =
        assertEquals(message, Edits.comparable(expected), Edits.comparable(actual))

    private fun assertHasCut(message: String, edit: EditState, time: Double) =
        assertTrue("$message (cut at $time s; cuts ${Edits.cuts(edit)})", Edits.cuts(edit).any { abs(it - time) < 1e-6 })

    private fun redoEnabled() = isEnabled(desc("Redo"), "the Redo button")
    private fun undoEnabled() = isEnabled(desc("Undo"), "the Undo button")

    @Test fun step1_splitTwiceDeleteRangeUndo() {
        launchApp()
        val id = createTestProject("Edit history")
        startRecording(); holdRecording(3.0); pauseRecording(id, 1)
        startRecording(); holdRecording(3.0); stopRecording(id, 2)
        val sources = savedProject(id).sources
        val (d1, d2) = sources[0].duration to sources[1].duration

        openEditorFromRecorder()
        val a = splitAt(id, d1 * 0.3)                       // A
        val stateA = currentEdit(id)
        val b = splitAt(id, d1 * 0.65)                      // B
        val stateB = currentEdit(id)
        val removed = deleteRange(id, d1 + d2 * 0.25, d1 + d2 * 0.6)   // C
        val stateC = currentEdit(id)
        assertEquals("C shortens the edit", stateB.duration - removed, stateC.duration, 1e-6)
        assertFalse("Nothing to redo after C", redoEnabled())

        undo(id)                                            // undo C
        assertSameEdit("Undo C returns to B", stateB, currentEdit(id))
        assertTrue("Redo C is offered", redoEnabled())
        val saved = savedProject(id)
        assertTrue("The saved project keeps the redo of C", saved.canRedo)
        assertSameEdit("The saved edit is B", stateB, saved.edit)

        saveMarker(json("project" to id.uuidString, "d1" to d1, "d2" to d2, "a" to a, "b" to b,
            "stateA" to Edits.encode(stateA), "stateB" to Edits.encode(stateB), "stateC" to Edits.encode(stateC)))
    }

    @Test fun step2_afterKillRedoCThenUndoCAndMakeD() {
        val marker = markerFor("step2")
        assumeFreshProcess(marker)
        val id = uuid(marker.getString("project"))
        val stateB = Edits.decode(marker.getString("stateB"))
        val stateC = Edits.decode(marker.getString("stateC"))
        launchApp()
        waitRecorderIdle()
        assertEquals("The relaunched app keeps the project selected", id, onMain { store.selectedID.value })
        openEditorFromRecorder()
        assertSameEdit("The reopened project shows B", stateB, currentEdit(id))
        assertTrue("Redo of C survives the relaunch", redoEnabled())

        redo(id)
        assertSameEdit("Redo restores C after the relaunch", stateC, currentEdit(id))
        undo(id)
        assertSameEdit("Undo C again returns to B", stateB, currentEdit(id))
        val d = splitAt(id, marker.getDouble("d1") + marker.getDouble("d2") * 0.8)   // D
        assertFalse("After D, redo of C is no longer offered", redoEnabled())
        val saved = savedProject(id)
        assertFalse("The saved project has no redo after D", saved.canRedo)
        assertHasCut("D is saved", saved.edit, d)
        assertTrue("Undo stays available", undoEnabled())
        saveMarker(marker.put("d", d).put("stateD", Edits.encode(saved.edit)))
    }

    @Test fun step3_undoAllKeepsRecordingsThenRecordMoreAndRedo() {
        val marker = markerFor("step3")
        val id = uuid(marker.getString("project"))
        val d1 = marker.getDouble("d1"); val d2 = marker.getDouble("d2")
        launchApp()
        waitRecorderIdle()
        openEditorFromRecorder()
        val recordings = onMain { store.projects.value.first { it.id == id }.sources.map { it.id } }

        // Undo everything: both recordings stay in the timeline (recording is outside undo).
        var guard = 0
        while (undoEnabled() && guard++ < 30) undo(id)
        assertFalse("Everything is undone", canUndo(id))
        val origin = currentEdit(id)
        assertEquals("Undo all keeps both recordings in the timeline", recordings.toSet(), Edits.sourceOrder(origin).toSet())
        assertEquals("Undo all keeps their full length", d1 + d2, origin.duration, Suite.FRAME)
        val e = splitAt(id, d1 * 0.5)                       // a new edit after undo-all
        val afterNew = currentEdit(id)
        assertEquals("After a new edit both recordings remain", recordings.toSet(), Edits.sourceOrder(afterNew).toSet())
        assertFalse("The new edit replaces the abandoned redo path", canRedo(id))

        // Edits F1, F2, F3; undo twice; record more; redo twice.
        val f1 = splitAt(id, d1 * 0.2)
        val f2 = splitAt(id, d1 + d2 * 0.3)
        val f3 = splitAt(id, d1 + d2 * 0.7)
        undo(id); undo(id)
        assertTrue("Two redos are offered", canRedo(id))
        goToRecorder()
        startRecording(); holdRecording(2.5); stopRecording(id, 3)
        val added = savedProject(id).sources.last()
        assertTrue("Recording keeps the redo path", savedProject(id).canRedo)
        openEditorFromRecorder()
        redo(id); redo(id)
        val final = currentEdit(id)
        listOf("E" to e, "F1" to f1, "F2" to f2, "F3" to f3).forEach { (name, cut) -> assertHasCut("Edit $name survives", final, cut) }
        assertEquals("The added footage is at the end of the redone edit", added.id, Edits.sourceOrder(final).last())
        assertEquals("The added footage is complete", added.duration, Edits.coverage(final)[added.id] ?: 0.0, Suite.FRAME)
        assertEquals("All three recordings are in the timeline", (recordings + added.id).toSet(), Edits.sourceOrder(final).toSet())
        val saved = savedProject(id)
        assertSameEdit("The redone edit is saved", final, saved.edit)
        assertTrue("Undo still works after redo", saved.canUndo)
        undo(id)
        assertFalse("Undo after redo removes F3", Edits.cuts(currentEdit(id)).any { abs(it - f3) < 1e-6 })
        assertTrue("Undo never removes the new footage", Edits.sourceOrder(currentEdit(id)).contains(added.id))

        Evidence.copy(evidenceName, File(projectFolder(id), "project.json"))
        Evidence.json(evidenceName, "report.json", json("project" to id.uuidString, "cuts" to jsonArray(Edits.cuts(final)),
            "historyCursor" to saved.historyCursor, "historyCount" to saved.history.size))
        finishWithProject(id)
        Marker(javaClass.simpleName).delete()
    }
}
