// YouRoll tests for the ProjectStore port (library, transactions, Recently Deleted, drafts).
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class ProjectStoreTests {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = File(temporary.root, "Projects")

    private class FakeProbe(private val duration: Double) : MediaProbe {
        override suspend fun probe(file: File) = MediaProbe.Info(duration, hasVideo = true, hasAudio = true)
    }

    @Test fun libraryCreateRenameAndSelection() {
        val preferences = InMemoryProjectPreferences()
        val store = ProjectStore(root, preferences = preferences)
        require(store.projects.value.isEmpty() && store.error.value == null, "An empty library opens cleanly")
        val first = store.create()!!
        val second = store.create()!!
        require(store.selectedID.value == second.id && store.projects.value.size == 2, "Create selects the new project")
        store.rename(first, "  Interview with a very patient guest  ")
        require(store.projects.value.first().title == "Interview with a very patient guest", "Rename trims and moves the project to the top")
        store.rename(first, "   ")
        require(store.projects.value.first { it.id == first.id }.title == "Interview with a very patient guest", "An empty name is ignored")
        store.select(first.id)
        val reopened = ProjectStore(root, preferences = preferences)
        require(reopened.selectedID.value == first.id && reopened.projects.value.map { it.id }.toSet() == setOf(first.id, second.id),
            "Library listing and the remembered selection survive relaunch")
        require(reopened.projects.value.filter { it.id != first.id }.all { it.deferredHistory != null }, "The library reads summaries only")
        require(reopened.selected?.deferredHistory == null, "The selected project is hydrated")
    }

    @Test fun updateTransactionsAndCommitConflicts() {
        val store = ProjectStore(root)
        val project = store.create()!!
        val source = SourceMedia(filename = "take.mov", duration = 6.0)
        require(store.update(project.id) { it.append(source) }, "Append through a transaction")
        val invalid = store.update(project.id) { it.sources = it.sources + source }
        require(!invalid && store.error.value != null, "An invalid change is rejected")
        require(store.projects.value.first { it.id == project.id }.sources == listOf(source), "A rejected change publishes nothing")
        val draft = store.projects.value.first { it.id == project.id }.edit
        draft.updateClip(0) { it.copy(end = 4.0) }
        require(store.commit(project.id, draft), "Commit an edit")
        val stale = store.projects.value.first { it.id == project.id }.edit
        store.update(project.id) { it.recordingInsertion = 0.0; it.addRecording(SourceMedia(filename = "here.mov", duration = 1.0)) }
        require(!store.commit(project.id, stale) && store.error.value!!.contains("new recording"), "A tool draft opened before Record here is refused")
        val saved = ProjectDisk(root).read(File(ProjectDisk(root).folder(project.id), "project.json"))
        require(saved.edit.clips.map { it.sourceID }.size == 2 && saved.canUndo, "Every transaction is on disk")
    }

    @Test fun recentlyDeletedRestoreAndErase() {
        val store = ProjectStore(root)
        val keep = store.create()!!
        val trash = store.create()!!
        store.moveToDeleted(store.projects.value.first { it.id == trash.id })
        require(store.projects.value.map { it.id } == listOf(keep.id) && store.deletedProjects.value.map { it.id } == listOf(trash.id),
            "Delete moves a project to Recently Deleted")
        require(store.deletionRecords.value[trash.id]?.dateIsKnown == true && store.selectedID.value == keep.id, "The deletion is recorded and another project selected")
        val relaunched = ProjectStore(root)
        require(relaunched.deletedProjects.value.map { it.id } == listOf(trash.id), "Recently Deleted survives relaunch")
        relaunched.restoreDeleted(relaunched.deletedProjects.value.first())
        require(relaunched.projects.value.any { it.id == trash.id } && relaunched.deletedProjects.value.isEmpty() && relaunched.selectedID.value == trash.id,
            "Restore returns the project and selects it")
        relaunched.moveToDeleted(relaunched.projects.value.first { it.id == trash.id })
        val now = SwiftDate.now()
        require(relaunched.expiredDeletions(now, ZoneOffset.UTC).isEmpty(), "A fresh deletion has not expired")
        val later = now.addingTimeInterval(31 * 86400.0)
        val expired = relaunched.expiredDeletions(later, ZoneOffset.UTC)
        require(expired.map { it.id } == listOf(trash.id), "After 30 days the deletion is offered for cleanup")
        relaunched.eraseExpiredDeletions(expired, later, ZoneOffset.UTC)
        require(relaunched.deletedProjects.value.isEmpty() && !File(ProjectDisk(root).deletedRoot, trash.id.uuidString).exists(), "Confirmed cleanup erases it")
        val another = relaunched.create()!!
        relaunched.moveToDeleted(relaunched.projects.value.first { it.id == another.id })
        relaunched.eraseDeleted(relaunched.deletedProjects.value.first())
        require(relaunched.deletedProjects.value.isEmpty(), "Erase removes a deleted project immediately")
    }

    @Test fun unsavedDraftRetryAndJournal() {
        val store = ProjectStore(root)
        val project = store.create()!!
        require(store.update(project.id) { it.append(SourceMedia(filename = "take.mov", duration = 5.0)) }, "Baseline save")
        val history = File(ProjectDisk(root).folder(project.id), "History")
        require(history.setWritable(false), "Simulate a failing disk")
        try {
            val edit = store.projects.value.first { it.id == project.id }.edit
            edit.updateClip(0) { it.copy(end = 3.0) }
            require(!store.commit(project.id, edit), "A failed save reports failure")
            require(store.unsavedDrafts.value.containsKey(project.id) && project.id in store.journaledDrafts.value &&
                store.error.value!!.startsWith("Changes not saved."), "The draft is kept in memory and journaled")
            require(store.projects.value.first { it.id == project.id }.edit.duration == 3.0, "The editor keeps showing the unsaved edit")
            // A relaunch before the disk recovers finds the journal.
            val relaunched = ProjectStore(root)
            require(relaunched.unsavedDrafts.value.containsKey(project.id) &&
                relaunched.recoveryMessages.value[project.id] == "Recovered your pending edits. Retry save to commit them.", "The journal survives relaunch")
            require(relaunched.projects.value.first { it.id == project.id }.edit.duration == 3.0, "Recovered draft has the edit")
            store.retrySaves()
            require(store.unsavedDrafts.value.containsKey(project.id) && store.error.value != null, "Retry fails while the disk still fails")
        } finally {
            history.setWritable(true)
        }
        store.retrySaves()
        require(store.unsavedDrafts.value.isEmpty() && store.journaledDrafts.value.isEmpty() && store.error.value == null, "Retry succeeds")
        val saved = ProjectDisk(root).read(File(ProjectDisk(root).folder(project.id), "project.json"))
        require(saved.edit.duration == 3.0 && saved.canUndo, "The retried edit is the saved history")
        require(!File(ProjectDisk(root).folder(project.id), ".pending-edit").exists(), "The journal is cleared after the commit")
        require(ProjectStore(root).unsavedDrafts.value.isEmpty(), "No stale draft after relaunch")
    }

    @Test fun duplicateKeepsHistory() = runBlocking {
        val store = ProjectStore(root)
        val project = store.create()!!
        store.rename(project, "Interview")
        val take = SourceMedia(filename = "take.mov", duration = 5.0)
        File(ProjectDisk(root).folder(project.id), take.filename).writeText("movie")
        store.update(project.id) { it.append(take) }
        val edit = store.projects.value.first { it.id == project.id }.edit
        edit.updateClip(0) { it.copy(start = 1.0) }
        store.commit(project.id, edit)
        store.update(project.id) { it.undo() }
        val copy = store.duplicate(store.projects.value.first { it.id == project.id })!!
        require(copy.title == "Interview copy" && store.selectedID.value == copy.id, "Duplicate gets a copy title and is selected")
        val second = store.duplicate(store.projects.value.first { it.id == project.id })!!
        require(second.title == "Interview copy 2", "Copy titles count up")
        val reopened = ProjectDisk(root).read(File(ProjectDisk(root).folder(copy.id), "project.json"))
        require(reopened.canRedo && reopened.history.size == 2 && File(ProjectDisk(root).folder(copy.id), take.filename).readText() == "movie",
            "The copy keeps undo, redo and its own media")
    }

    @Test fun addRecordingIndexesATake() = runBlocking {
        val store = ProjectStore(root, mediaProbe = FakeProbe(4.0))
        val project = store.create()!!
        val folder = ProjectDisk(root).folder(project.id)
        val segment = newUUID()
        val movie = File(folder, "1790000000000-${segment.uuidString}.mov")
        movie.writeText("not really a movie")
        val events = listOf(CaptureEvent(time = 1.0, kind = "bookmark", detail = ""), CaptureEvent(time = 9.0, kind = "bookmark", detail = "late"))
        File(folder, movie.nameWithoutExtension + ".events.json").writeText(SwiftJSON.encodeToString(ListSerializer(CaptureEvent.serializer()), events))
        require(store.addRecording(movie, project.id), "The take is indexed")
        val saved = store.projects.value.first { it.id == project.id }
        val source = saved.sources.single()
        require(source.filename == movie.name && source.duration == 4.0 && source.sha256 == ProjectStore.fingerprint(movie) && source.events?.size == 1,
            "Source facts come from the probe, the file hash and the marks sidecar")
        require(saved.edit.clips.single().sourceID == source.id && !saved.canUndo && saved.edit.timeline.resolvedBookmarks.size == 1,
            "Recording appends outside Undo with its capture bookmark")
        require(store.addRecording(movie, project.id) && store.projects.value.first { it.id == project.id }.sources.size == 1, "Indexing twice is a no-op")
        val noProbe = ProjectStore(root)
        require(!noProbe.addRecording(movie, project.id) && noProbe.error.value != null, "Without a MediaProbe indexing fails clearly")
    }
}
