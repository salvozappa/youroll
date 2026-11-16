// YouRoll tests for VideoBatchImport.commit (Photo Picker insertion) and its rollback.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext

class VideoBatchImportTests {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = File(temporary.root, "Projects")

    /** A project with one saved take, so its History folder exists. */
    private fun library(): Pair<ProjectStore, Project> {
        val store = ProjectStore(root)
        val project = store.create()!!
        require(store.update(project.id) { it.append(SourceMedia(filename = "take.mov", duration = 5.0)) }, "Baseline save")
        return store to store.projects.value.first { it.id == project.id }
    }

    /** A batch with one staged clip, as `stage` leaves it. */
    private fun staged(store: ProjectStore, project: Project): Pair<VideoBatchImport, String> {
        val batch = VideoBatchImport(store.folder(project))
        val name = "photos-import-" + newUUID().uuidString + ".mp4"
        File(batch.directory, name).writeBytes(ByteArray(64))
        batch.sources += SourceMedia(filename = name, duration = 2.0)
        return batch to name
    }

    @Test fun aCommittedBatchIsSavedAndPublished() {
        val (store, project) = library()
        val (batch, name) = staged(store, project)
        val saved = batch.commit(store, project.id, 0.0, VideoInsertionSite.start)
        val folder = store.folder(project)
        require(File(folder, name).exists() && !batch.directory.exists(), "The clip is promoted and the staging folder removed")
        require(saved.sources.any { it.filename == name } && store.projects.value.first { it.id == project.id }.sources.any { it.filename == name },
            "The saved project is published")
        require(ProjectDisk(root).read(File(folder, "project.json")).sources.any { it.filename == name }, "and on disk")
    }

    @Test fun aFailedSaveLeavesNoDraftReferencingRolledBackFiles() {
        val (store, project) = library()
        val (batch, name) = staged(store, project)
        val folder = store.folder(project)
        val history = File(folder, "History")
        require(history.setWritable(false), "Simulate a failing disk")
        try {
            assertThrowing<Exception>("The save fails") { batch.commit(store, project.id, 0.0, VideoInsertionSite.start) }
            require(store.unsavedDrafts.value.isEmpty() && store.journaledDrafts.value.isEmpty() && !File(folder, ".pending-edit").exists(),
                "No unsaved draft or journal points at the rolled-back clip (Swift saves with disk.save)")
            require(store.projects.value.first { it.id == project.id }.sources.none { it.filename == name }, "The published project is unchanged")
            require(!File(folder, name).exists() && !File(batch.directory, "promoting.json").exists(), "The clip and its journal are rolled back")
        } finally {
            history.setWritable(true)
        }
        store.retrySaves()
        require(ProjectDisk(root).read(File(folder, "project.json")).sources.none { it.filename == name }, "Retry save has nothing to persist")
    }

    @Test fun aCancelDuringASavedCommitStillReportsTheInsertion() = runBlocking {
        val (store, project) = library()
        val (batch, name) = staged(store, project)
        var caller: Job? = null
        // Cancels the importing coroutine just as the commit starts, like Cancel import tapped mid-commit.
        val cancelling = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { caller?.cancel(); block.run() }
        }
        // Like AddVideoPanel.importPhotos, the importing coroutine acts on the result right after the call returns.
        var saved: Project? = null
        val commit = launch(start = CoroutineStart.LAZY) { saved = commitBatch(batch, store, project.id, 0.0, VideoInsertionSite.start, cancelling) }
        caller = commit
        commit.start()
        commit.join()
        require(commit.isCancelled, "The importing coroutine was cancelled during the commit")
        require(saved?.sources?.any { it.filename == name } == true && store.projects.value.first { it.id == project.id }.sources.any { it.filename == name },
            "A commit that saved is reported as inserted, not as cancelled")
    }

    @Test fun aCancelBeforeTheCommitInsertsNothing() = runBlocking {
        val (store, project) = library()
        val (batch, name) = staged(store, project)
        val commit = async(start = CoroutineStart.LAZY) { commitBatch(batch, store, project.id, 0.0, VideoInsertionSite.start, Dispatchers.Unconfined) }
        commit.cancel()
        assertThrowing<CancellationException>("Cancelled before the commit") { runBlocking { commit.await() } }
        require(store.projects.value.first { it.id == project.id }.sources.none { it.filename == name } && !File(store.folder(project), name).exists(),
            "Nothing is promoted or saved")
    }

    @Test fun unusedTakesInsertInTheChosenOrder() {
        val project = Project("Takes")
        val a = SourceMedia(filename = "a.mov", duration = 3.0)
        val b = SourceMedia(filename = "b.mov", duration = 2.0)
        val c = SourceMedia(filename = "c.mov", duration = 4.0)
        val music = SourceMedia(filename = "song.m4a", duration = 9.0, audioOnly = true)
        project.sources = listOf(a, b, c, music)
        project.insertVideos(listOf(a), 0.0, VideoInsertionSite.start)
        require(unusedTakes(project) == listOf(b, c), "Unused takes are the video takes no clip uses, in recording order; audio-only sources are not takes")
        project.insertVideos(listOf(c, b), project.edit.duration, VideoInsertionSite.end)
        require(project.edit.timeline.chronologicalVideo.map { it.content.sourceID } == listOf(a.id, c.id, b.id), "Takes are inserted in the tapped order")
        require(project.edit.duration == 9.0 && unusedTakes(project).isEmpty(), "and are no longer unused")
        project.undo()
        require(unusedTakes(project) == listOf(b, c), "Undo makes them unused again")
    }

    @Test fun aFailedRollbackKeepsTheJournalForStartupRecovery() {
        val (store, project) = library()
        val (batch, name) = staged(store, project)
        val folder = store.folder(project)
        val history = File(folder, "History")
        require(history.setWritable(false), "Simulate a failing disk")
        try {
            assertThrowing<Exception>("The save fails") { batch.commit(store, project.id, 0.0, VideoInsertionSite.start, remove = { false }) }
        } finally {
            history.setWritable(true)
        }
        require(File(folder, name).exists() && File(batch.directory, "promoting.json").exists(), "A clip that could not be removed keeps its journal")
        batch.discard()
        require(File(batch.directory, "promoting.json").exists(), "Discard leaves the journal to startup recovery")
        VideoBatchImport.recover(store.projects.value.first { it.id == project.id }, folder)
        require(!File(folder, name).exists(), "Startup recovery removes the orphaned clip")
    }
}
