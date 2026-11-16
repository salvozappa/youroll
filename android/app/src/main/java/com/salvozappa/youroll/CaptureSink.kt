// YouRoll-specific: the boundary between the recorder and project storage.
// On iOS this is `CaptureController.onSegment` plus `ContentView.startRecording`
// (`camera.start(in: store.folder(project))` and `store.addRecording(url, to:)`).
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * A capture event inside one segment, with the field names of Swift's
 * `CaptureEvent` (Project.swift): `id`, `time` (seconds from the segment start),
 * `kind` and `detail`. Kinds written by the recorder: `cameraSwitch`, `videoHold`,
 * `microphoneFallback`, `bookmark`, `audioGap`.
 * `ProjectStore.addRecording` reads these from the `.events.json` side file as
 * Project.kt `CaptureEvent` values (same JSON), so no conversion is needed.
 */
@Serializable
data class CaptureSegmentEvent(
    val id: String = newUUID().uuidString,
    val time: Double,
    val kind: String,
    val detail: String,
)

/**
 * The capture settings in effect when the segment started. Camera changes inside
 * the segment are `cameraSwitch` events (their detail names the new camera).
 */
@Serializable
data class CaptureSegmentSettings(
    val cameraID: String,
    val cameraName: String,
    val quality: String,           // CaptureQuality raw value: "hd1080" or "ultraHD4K"
    val mirroredFront: Boolean,     // "Save mirrored selfies"
    val stabilized: Boolean,
    val microphoneID: String,
    val microphoneName: String,
)

/** One finalised source file of a take. */
data class CapturedSegment(
    val file: File,
    /** Recorded media duration in seconds, as reported by the encoder. */
    val duration: Double,
    val events: List<CaptureSegmentEvent>,
    val settings: CaptureSegmentSettings,
    /** All segments of one Record…Stop take share this identifier; Pause starts a new segment of the same take. */
    val takeID: String,
    /** 0-based index of the segment inside its take. */
    val sequence: Int,
    /** Wall-clock start, milliseconds since the epoch (also the filename prefix). */
    val recordedAt: Long,
    /** Non-null when the encoder reported an error but left a playable file. */
    val error: String?,
)

/**
 * Receives recorder output. The recorder never decides where projects live.
 *
 * - [recordingDirectory] is asked on every Record and Resume, on the main thread.
 *   Return the project's media folder (created). Segment files are named
 *   `<epochMillis>-<UUID>.mp4`, with a `<same>.events.json` side file.
 * - [segmentFinalized] is called on the main thread after each segment is closed
 *   (Pause, Stop, camera failure, background). Call [completion] exactly once,
 *   on any thread: `true` once the segment is part of the project, `false` if it
 *   could not be added (the take is then left paused and the file is kept).
 */
interface CaptureSink {
    fun recordingDirectory(): File
    fun segmentFinalized(segment: CapturedSegment, completion: (Boolean) -> Unit)
}

/**
 * The recorder's sink for the app: iOS `camera.onSegment` in ContentView.swift.
 *
 * A take belongs to the project folder the writer opened, even if the user has
 * since chosen another recording destination, so the project is derived from
 * the segment's folder name exactly as on iOS. Segments are indexed strictly in
 * the order they were finalised: `ProjectStore.addRecording` suspends while it
 * hashes the file, and a later segment must never overtake an earlier one.
 *
 * Use from the main thread; [scope] must run on it too (ProjectStore is single-threaded).
 */
class ProjectCaptureSink(
    private val store: ProjectStore,
    private val scope: CoroutineScope,
) : CaptureSink {
    private class Pending(val file: File, val project: UUID, val completion: (Boolean) -> Unit)
    private val queue = Channel<Pending>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (next in queue) {
                val saved = try { store.addRecording(next.file, next.project) } catch (failure: CancellationException) { throw failure } catch (_: Exception) { false }
                next.completion(saved)
            }
        }
    }

    /** The selected project's folder, creating a project when the library is empty (iOS `store.selected ?? store.create()`). */
    override fun recordingDirectory(): File {
        val project = store.selected ?: store.create() ?: throw IllegalStateException("No project is available for recording.")
        return store.folder(project).also { it.mkdirs() }
    }

    override fun segmentFinalized(segment: CapturedSegment, completion: (Boolean) -> Unit) {
        val id = uuidOrNull(segment.file.absoluteFile.parentFile?.name ?: "")
        if (id == null) { completion(false); return }
        // The writer saves marks as they happen; keep the in-memory copy if that file is missing.
        val marks = CaptureSegmentWriter.eventsFile(segment.file)
        if (!marks.exists() && segment.events.isNotEmpty()) {
            runCatching { marks.writeText(SwiftJSON.encodeToString(ListSerializer(CaptureSegmentEvent.serializer()), segment.events)) }
        }
        if (queue.trySend(Pending(segment.file, id, completion)).isFailure) completion(false)
    }
}
