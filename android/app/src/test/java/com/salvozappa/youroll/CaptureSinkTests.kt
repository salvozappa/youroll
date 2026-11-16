// YouRoll tests for the recorder → project wiring (ProjectCaptureSink in CaptureSink.kt).
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSinkTests {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = File(temporary.root, "Projects")

    /** The first probe is slow, so a naive sink would index the second segment first. */
    private class SlowFirstProbe(private val durations: Map<String, Double>) : MediaProbe {
        var calls = 0
        override suspend fun probe(file: File): MediaProbe.Info {
            calls += 1
            if (calls == 1) delay(300)
            return MediaProbe.Info(durations.getValue(file.name), hasVideo = true, hasAudio = true)
        }
    }

    private fun segment(file: File, sequence: Int) = CapturedSegment(
        file = file, duration = 1.0, events = listOf(CaptureSegmentEvent(time = 0.5, kind = "bookmark", detail = "Bookmark")),
        settings = CaptureSegmentSettings("0", "Main", "hd1080", false, false, "mic", "Mic"),
        takeID = "TAKE", sequence = sequence, recordedAt = 0, error = null)

    private fun take(folder: File, name: String) = File(folder, name).apply { writeBytes(name.toByteArray()) }

    private fun finalize(sink: CaptureSink, file: File, sequence: Int): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        sink.segmentFinalized(segment(file, sequence)) { done.complete(it) }
        return done
    }

    @Test fun segmentsAreIndexedInFinalisationOrderAcrossRelaunch() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val preferences = InMemoryProjectPreferences()
        val probe = SlowFirstProbe(mapOf("1000-A.mp4" to 2.0, "2000-B.mp4" to 3.0, "3000-C.mp4" to 1.5))
        val store = ProjectStore(root, probe, preferences)
        val sink = ProjectCaptureSink(store, scope)
        val folder = sink.recordingDirectory()
        val project = store.selected!!
        require(folder == store.folder(project) && folder.isDirectory, "An empty library gets a project to record into")

        // A then B finalised back to back (Pause, Resume, Stop) while A is still being probed.
        val a = finalize(sink, take(folder, "1000-A.mp4"), 0)
        val b = finalize(sink, take(folder, "2000-B.mp4"), 1)
        withTimeout(5000) { require(a.await() && b.await(), "Both segments are added") }
        require(store.selected!!.sources.map { it.filename } == listOf("1000-A.mp4", "2000-B.mp4"), "A before B")
        require(File(folder, "1000-A.events.json").exists(), "Marks are kept beside the take for the project")
        require(store.selected!!.sources[0].events?.single()?.kind == "bookmark", "and indexed as CaptureEvents")

        // Kill and relaunch: the remembered project continues with C.
        val relaunched = ProjectStore(root, probe, preferences)
        val sink2 = ProjectCaptureSink(relaunched, scope)
        require(relaunched.selectedID.value == project.id && sink2.recordingDirectory() == folder, "Recording continues the same project")
        val c = finalize(sink2, take(folder, "3000-C.mp4"), 0)
        withTimeout(5000) { require(c.await(), "C is added") }
        val reopened = ProjectStore(root, probe, preferences).selected!!
        require(reopened.sources.map { it.filename } == listOf("1000-A.mp4", "2000-B.mp4", "3000-C.mp4"), "One project holds A+B+C")
        require(reopened.edit.clips.map { reopened.sources.first { s -> s.id == it.sourceID }.filename } ==
            listOf("1000-A.mp4", "2000-B.mp4", "3000-C.mp4"), "and its timeline plays A, B, C in order")
        require(reopened.edit.duration == 6.5, "without gaps: 2 + 3 + 1.5 s")
        scope.cancel()
    }

    @Test fun segmentsOutsideAProjectFolderAreRejected() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val store = ProjectStore(root, SlowFirstProbe(mapOf("x.mp4" to 1.0)))
        val sink = ProjectCaptureSink(store, scope)
        val stray = File(temporary.newFolder("NotAProject"), "x.mp4").apply { writeBytes(byteArrayOf(1)) }
        val done = finalize(sink, stray, 0)
        require(!done.await(), "A take outside a project folder is not claimed by another project")
        scope.cancel()
    }
}
