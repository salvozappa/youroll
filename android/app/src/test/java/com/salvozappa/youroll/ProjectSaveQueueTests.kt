// YouRoll tests for ProjectStore saving off the main thread: the single writer,
// failed saves, the pending-edit journal and history decoded on the IO dispatcher.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSaveQueueTests {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = File(temporary.root, "Projects")

    /** Runs dispatched tasks only when the test says so, in the order it chooses. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending: Int get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
        fun runLatest() { tasks.removeLast().run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }

    /** Counts the tasks it hands to a real background thread. */
    private class CountingDispatcher(private val target: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatched = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) { dispatched.incrementAndGet(); target.dispatch(context, block) }
    }

    private class FakeProbe(private val duration: Double) : MediaProbe {
        override suspend fun probe(file: File) = MediaProbe.Info(duration, hasVideo = true, hasAudio = true)
    }

    private fun ProjectStore.project(id: UUID) = projects.value.first { it.id == id }
    private fun head(id: UUID) = File(ProjectDisk(root).folder(id), "project.json")
    private fun onDisk(id: UUID): Project = ProjectDisk(root).read(head(id))
    private fun journal(id: UUID) = File(ProjectDisk(root).folder(id), ".pending-edit")

    /** A split one second further along each time, committed through the store. */
    private fun ProjectStore.split(id: UUID, at: Double): Boolean = commit(id, TimelineEditing.split(project(id).edit, at))

    @Test fun rapidEditsKeepTheirOrderOnDisk() = runBlocking {
        // A real writer thread; results are applied on this test thread whenever it suspends.
        val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val owner = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        try {
            val store = ProjectStore(root, saveDispatchers = SaveDispatchers(writer, owner))
            val id = store.create()!!.id
            require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 60.0)) } && store.awaitSaved(id), "Baseline saved")
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val watching = AtomicBoolean(true)
            val watcher = thread {
                while (watching.get()) runCatching { (parseJson(head(id).readBytes()) as JsonObject)["historyCursor"]!!.jsonPrimitive.int }.getOrNull()?.let(seen::add)
            }
            for (step in 1..40) {
                val before = store.project(id).historyCursor
                require(store.split(id, step.toDouble()), "Split $step is accepted")
                require(store.project(id).historyCursor == before + 1 && store.project(id).edit.clips.size == step + 1, "and visible at once")
                if (step % 7 == 0) yield() // Let some results arrive mid-sequence.
            }
            require(store.awaitSaved(id), "Every split is saved")
            watching.set(false); watcher.join()
            require(seen.isNotEmpty() && seen.zipWithNext().all { (a, b) -> a <= b }, "The head on disk only moves forward: $seen")
            val memory = store.project(id)
            val saved = onDisk(id)
            require(saved.historyCursor == 40 && saved.history == memory.history && saved.edit.clips.size == 41, "The disk holds every split, in order")

            store.update(id) { it.undo() }; store.update(id) { it.undo() }; store.update(id) { it.undo() }; store.update(id) { it.redo() }
            require(store.project(id).historyCursor == 38, "Undo and redo are visible at once")
            require(store.awaitSaved(id), "Undo and redo are saved")
            val reopened = ProjectStore(root).project(id)
            require(reopened.historyCursor == 38 && reopened.canRedo && reopened.history == memory.history && store.unsavedDrafts.value.isEmpty(),
                "Undo and redo persist in order, keeping redo")
        } finally {
            writer.close()
        }
    }

    @Test fun aNewerSaveIsNeverOverwrittenByAnOlderOne() {
        val writer = ManualDispatcher()
        val owner = ManualDispatcher()
        val store = ProjectStore(root, saveDispatchers = SaveDispatchers(writer, owner))
        val id = store.create()!!.id
        require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 20.0)) }, "Baseline accepted")
        writer.runAll(); owner.runAll()
        require(onDisk(id).sources.size == 1, "Baseline saved")
        require(store.split(id, 1.0) && store.split(id, 2.0) && writer.pending == 2, "Two splits wait for the writer")
        require(onDisk(id).edit.clips.size == 1, "Nothing is written on the calling thread")
        // Even if the writer took them newest first, the older split must not replace the newer head.
        writer.runLatest(); writer.runAll(); owner.runAll()
        require(onDisk(id).edit.clips.size == 3 && onDisk(id).historyCursor == 2, "The newer split stays on disk")
        // An owner-thread save (the batch import commit) after a queued split includes it; the queued save is then skipped.
        require(store.split(id, 3.0), "A third split is accepted")
        val photo = SourceMedia(filename = "photos-import-" + newUUID().uuidString + ".mp4", duration = 2.0)
        store.saveDirectly(id) { project -> project.sources = project.sources + photo; project.insertVideos(listOf(photo), project.edit.duration, VideoInsertionSite.end) }
        writer.runAll(); owner.runAll()
        val saved = onDisk(id)
        require(saved.edit.clips.size == 5 && saved.sources.size == 2 && saved.history == store.project(id).history, "The queued split did not move the head back")
        require(store.unsavedDrafts.value.isEmpty() && store.error.value == null, "No draft or error")
    }

    @Test fun aFailedSaveMidSequenceKeepsTheDraftAndJournalAndRetrySucceeds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ProjectStore(root, saveDispatchers = SaveDispatchers(dispatcher, dispatcher))
        val id = store.create()!!.id
        require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 20.0)) }, "Baseline accepted")
        require(store.split(id, 1.0), "First split accepted")
        advanceUntilIdle()
        require(onDisk(id).historyCursor == 1, "First split saved")
        val history = File(ProjectDisk(root).folder(id), "History")
        require(history.setWritable(false), "Simulate a failing disk")
        try {
            require(store.split(id, 2.0), "A split is accepted while the disk fails")
            require(store.project(id).historyCursor == 2 && store.unsavedDrafts.value.isEmpty() && store.error.value == null,
                "It is visible at once; the failure is not known yet")
            advanceUntilIdle()
            require(store.unsavedDrafts.value[id] === store.project(id) && id in store.journaledDrafts.value &&
                store.error.value!!.startsWith("Changes not saved. A recovery copy of the edits was written."), "The failed save leaves the draft, journal and error")
            require(onDisk(id).historyCursor == 1 && journal(id).isDirectory, "The head keeps the last saved split; the journal holds the edit")
            // Editing continues on the draft.
            require(store.split(id, 3.0), "Another split is accepted")
            require(store.unsavedDrafts.value[id] === store.project(id) && store.project(id).historyCursor == 3, "The draft is the newest state at once")
            advanceUntilIdle()
            require(store.unsavedDrafts.value[id]?.historyCursor == 3 && id in store.journaledDrafts.value, "Its save fails too and is journaled")
            // The process dies here: a relaunch finds the newest journaled edit.
            val relaunched = ProjectStore(root)
            require(relaunched.unsavedDrafts.value[id]?.historyCursor == 3 && relaunched.project(id).edit == store.project(id).edit &&
                relaunched.recoveryMessages.value[id] == "Recovered your pending edits. Retry save to commit them.", "The journal survives relaunch")
            store.retrySaves()
            require(store.unsavedDrafts.value.containsKey(id) && store.error.value != null, "Retry fails while the disk still fails")
        } finally {
            history.setWritable(true)
        }
        store.retrySaves()
        require(store.unsavedDrafts.value.isEmpty() && store.journaledDrafts.value.isEmpty() && store.error.value == null, "Retry succeeds")
        advanceUntilIdle()
        require(store.unsavedDrafts.value.isEmpty() && store.error.value == null, "A queued result from before the retry does not bring the draft back")
        val saved = onDisk(id)
        require(saved.historyCursor == 3 && saved.history == store.project(id).history && !journal(id).exists(), "The retried edits are the saved history; the journal is cleared")
        require(store.split(id, 4.0), "Editing continues")
        advanceUntilIdle()
        require(onDisk(id).historyCursor == 4 && ProjectStore(root).unsavedDrafts.value.isEmpty(), "and saves normally, with no stale draft after relaunch")
    }

    @Test fun anOlderFailureIsSettledByTheNewerSave() {
        val writer = ManualDispatcher()
        val owner = ManualDispatcher()
        val store = ProjectStore(root, saveDispatchers = SaveDispatchers(writer, owner))
        val id = store.create()!!.id
        require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 20.0)) }, "Baseline accepted")
        writer.runAll(); owner.runAll()
        val history = File(ProjectDisk(root).folder(id), "History")
        require(history.setWritable(false), "Simulate a failing disk")
        try {
            require(store.split(id, 1.0), "A split is accepted")
            writer.runNext() // Its save fails and is journaled; the result has not reached the owner yet.
            require(journal(id).isDirectory && owner.pending == 1, "The failed save is journaled before anything else is written")
        } finally {
            history.setWritable(true)
        }
        require(store.split(id, 2.0), "A newer split is accepted before the failure arrives")
        owner.runAll()
        require(store.unsavedDrafts.value.isEmpty() && store.error.value == null, "The older failure is moot: the newer state is queued")
        writer.runAll(); owner.runAll()
        require(onDisk(id).historyCursor == 2 && store.unsavedDrafts.value.isEmpty() && store.error.value == null, "The newer save includes both splits")
        require(ProjectStore(root).unsavedDrafts.value.isEmpty() && ProjectStore(root).project(id).historyCursor == 2,
            "A relaunch ignores the superseded journal")
    }

    @Test fun aRetryAfterAQueuedFailureWins() {
        val writer = ManualDispatcher()
        val owner = ManualDispatcher()
        val store = ProjectStore(root, saveDispatchers = SaveDispatchers(writer, owner))
        val id = store.create()!!.id
        require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 20.0)) }, "Baseline accepted")
        writer.runAll(); owner.runAll()
        val history = File(ProjectDisk(root).folder(id), "History")
        require(history.setWritable(false), "Simulate a failing disk")
        try {
            require(store.split(id, 1.0), "A split is accepted")
            writer.runAll(); owner.runAll()
            require(store.unsavedDrafts.value.containsKey(id), "Its save failed: a draft")
            require(store.split(id, 2.0), "Editing continues on the draft")
            writer.runAll() // This save fails too; its result waits for the owner thread.
            require(owner.pending == 1, "The failure has not arrived")
        } finally {
            history.setWritable(true)
        }
        store.retrySaves() // Retry save runs on the owner thread before the queued result.
        require(store.unsavedDrafts.value.isEmpty() && store.error.value == null, "Retry saved the newest state")
        owner.runAll()
        require(store.unsavedDrafts.value.isEmpty() && store.error.value == null, "The older failure result does not bring the draft back")
        require(onDisk(id).historyCursor == 2 && ProjectStore(root).unsavedDrafts.value.isEmpty(), "Both splits are saved")
    }

    @Test fun anUnwrittenEditIsTheOnlyLossWhenTheProcessDies() {
        val writer = ManualDispatcher()
        val owner = ManualDispatcher()
        val store = ProjectStore(root, saveDispatchers = SaveDispatchers(writer, owner))
        val id = store.create()!!.id
        require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 20.0)) } && store.split(id, 1.0), "Edits accepted")
        writer.runAll(); owner.runAll()
        require(store.split(id, 2.0) && writer.pending == 1, "A split waits for the writer")
        // The process dies before the writer runs: the disk holds the previous saved state, whole.
        val relaunched = ProjectStore(root).project(id)
        require(relaunched.historyCursor == 1 && relaunched.history == onDisk(id).history && relaunched.edit.clips.size == 2,
            "The previous saved state reopens intact")
    }

    @Test fun moveToDeletedWritesAQueuedStateFirst() {
        val writer = ManualDispatcher()
        val owner = ManualDispatcher()
        val store = ProjectStore(root, saveDispatchers = SaveDispatchers(writer, owner))
        val keep = store.create()!!.id
        val id = store.create()!!.id
        require(store.update(id) { it.append(SourceMedia(filename = "take.mov", duration = 20.0)) } && store.split(id, 1.0), "Edits accepted")
        store.moveToDeleted(store.project(id))
        writer.runAll(); owner.runAll()
        require(store.projects.value.map { it.id } == listOf(keep) && !ProjectDisk(root).folder(id).exists(), "The project moved; the writer did not recreate its folder")
        val deleted = ProjectDisk(root).read(File(File(ProjectDisk(root).deletedRoot, id.uuidString), "project.json"))
        require(deleted.historyCursor == 1 && deleted.edit.clips.size == 2, "The queued edits moved with it")
    }

    @Test fun aTakeIndexedDuringAnEditKeepsBoth() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val io = ManualDispatcher()
        val store = ProjectStore(root, FakeProbe(4.0), ioDispatcher = io, saveDispatchers = SaveDispatchers(dispatcher, dispatcher))
        val id = store.create()!!.id
        val first = SourceMedia(filename = "a.mov", duration = 5.0)
        require(store.update(id) { it.append(first) }, "First take")
        advanceUntilIdle()
        val movie = File(ProjectDisk(root).folder(id), "1790000000000-${newUUID().uuidString}.mov").apply { writeText("take") }
        var indexed: Boolean? = null
        launch { indexed = store.addRecording(movie, id) }
        testScheduler.runCurrent()
        require(io.pending == 1 && indexed == null, "addRecording waits for the IO dispatcher")
        val edit = store.project(id).edit
        edit.timeline.updateAudio(0) { it.copy(volumeDB = -4.0) }
        require(store.commit(id, edit), "An edit is made while the take is being indexed")
        repeat(20) { if (indexed == null) { io.runAll(); advanceUntilIdle() } }
        require(indexed == true, "The take is indexed once its save finished")
        for (project in listOf(store.project(id), onDisk(id))) {
            require(project.sources.map { it.filename } == listOf("a.mov", movie.name) && project.edit.clips.size == 2 &&
                project.edit.timeline.audio[0].volumeDB == -4.0, "Both the edit and the take are kept")
            require(project.canUndo && project.historyCursor == 1, "The take is not an Undo step; the edit is")
        }
    }

    @Test fun historyDecodedOffTheCallingThreadIsTheSameProject() = runBlocking {
        val preferences = InMemoryProjectPreferences()
        val setup = ProjectStore(root, preferences = preferences)
        val id = setup.create()!!.id
        val other = setup.create()!!.id
        require(setup.update(id) { it.append(SourceMedia(filename = "a.mov", duration = 10.0)) } && setup.split(id, 2.0) && setup.split(id, 4.0), "Edits")
        setup.update(id) { it.undo() }
        setup.select(other) // So this project reopens as a summary.
        val inline = ProjectStore(root, preferences = preferences)
        require(inline.project(id).deferredHistory != null, "The library reads a summary")
        require(inline.openProject(id), "Open on the calling thread, as before")
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val counting = CountingDispatcher(io)
            val background = ProjectStore(root, preferences = preferences, ioDispatcher = counting)
            require(background.project(id).deferredHistory != null, "Again a summary")
            require(background.selectPrepared(id) && background.selectedID.value == id, "Open and select with the history decoded on the IO dispatcher")
            require(counting.dispatched.get() > 0, "The decoding ran on the IO dispatcher")
            val a = inline.project(id)
            val b = background.project(id)
            require(b.deferredHistory == null && Project.encode(a).contentEquals(Project.encode(b)) && a.history == b.history &&
                a.canUndo == b.canUndo && a.canRedo && b.canRedo, "Both give the same project")
        } finally {
            io.close()
        }
    }

    @Test fun anEditDuringHydrationIsNotOverwritten() = runBlocking {
        val preferences = InMemoryProjectPreferences()
        val setup = ProjectStore(root, preferences = preferences)
        val id = setup.create()!!.id
        val other = setup.create()!!.id
        require(setup.update(id) { it.append(SourceMedia(filename = "a.mov", duration = 10.0)) } && setup.split(id, 2.0), "Edits")
        setup.select(other)
        val io = ManualDispatcher()
        val store = ProjectStore(root, preferences = preferences, ioDispatcher = io)
        require(store.project(id).deferredHistory != null, "The library reads a summary")
        var opened: Boolean? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { opened = store.prepareProject(id) }
        require(io.pending == 1 && opened == null, "The history is being decoded")
        io.runAll() // The old history is decoded; publishing it waits for this thread.
        store.rename(store.project(id), "Renamed while opening")
        job.join()
        require(opened == true && store.project(id).title == "Renamed while opening" && store.project(id).deferredHistory == null,
            "The decoded history does not replace the newer edit")
        require(onDisk(id).title == "Renamed while opening" && onDisk(id).historyCursor == 1, "and the rename is saved")
    }

    /** SPEC "Recording and project lifecycle" through the store with background saves. */
    @Test fun specHistoryFlowsWithBackgroundSaves() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        fun store() = ProjectStore(root, saveDispatchers = SaveDispatchers(dispatcher, dispatcher))
        val store = store()
        val id = store.create()!!.id
        val a = SourceMedia(filename = "a.mov", duration = 3.0)
        val b = SourceMedia(filename = "b.mov", duration = 4.0)
        val c = SourceMedia(filename = "c.mov", duration = 5.0)
        require(store.update(id) { it.append(a) } && store.update(id) { it.append(b) }, "A and B are accepted")
        val edit = store.project(id).edit
        edit.timeline.updateAudio(0) { it.copy(volumeDB = -2.0) }
        require(store.commit(id, edit), "Edit before stopping")
        advanceUntilIdle()
        val relaunched = store()
        require(relaunched.update(id) { it.append(c) }, "C is appended after relaunch")
        relaunched.update(id) { it.undo() }
        advanceUntilIdle()
        val final = store()
        require(final.prepareProject(id), "Open the project")
        val full = final.project(id)
        require(full.edit.clips.map { it.sourceID } == listOf(a.id, b.id, c.id) && full.edit.duration == 12.0, "Undo all keeps A+B+C in order")
        require(full.edit.timeline.audio[0].volumeDB == 0.0 && full.canRedo && !full.canUndo, "The undone edit can be redone")
        require(final.update(id) { it.redo() }, "Redo")
        advanceUntilIdle()
        require(onDisk(id).edit.timeline.audio[0].volumeDB == -2.0 && onDisk(id).edit.clips.size == 3, "Redo restores the edit and keeps the new footage")
    }
}
