// YouRoll acceptance suite: shared harness for the SPEC acceptance flows.
//
// Every flow drives the real app through UiAutomator (content descriptions,
// test tags exposed as resource ids, and visible text) like a user would, and
// checks the result in-process: the project JSON on disk (ProjectDisk, the same
// reader the app uses), the segment and export files (MediaExtractor,
// MediaMetadataRetriever) and the recorder's live state.
//
// Run it only through `scripts/acceptance.sh`. The script runs each test method
// in its own process and force-stops the app between methods, so a later `stepN`
// method sees the app after a real process death. Without the script's
// `acceptance=true` argument every test is skipped: the suite records footage
// and creates projects, and must never run by accident against real projects.
//
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import com.salvozappa.youroll.uuidString
import android.Manifest
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.salvozappa.youroll.CaptureState
import com.salvozappa.youroll.ContentModel
import com.salvozappa.youroll.EditState
import com.salvozappa.youroll.EditStateSerializer
import com.salvozappa.youroll.MainActivity
import com.salvozappa.youroll.Project
import com.salvozappa.youroll.ProjectStore
import com.salvozappa.youroll.SwiftJSON
import com.salvozappa.youroll.UUID
import com.salvozappa.youroll.uuidOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs

/** Instrumentation arguments set by `scripts/acceptance.sh`. */
object Suite {
    const val TAG = "YouRollAcceptance"
    /** Every acceptance project's title starts with this; `--keep-data` deletes only these. */
    const val PROJECT_PREFIX = "YouRoll acceptance · "
    /** One video frame at the 30 fps the recorder and the export use. */
    const val FRAME = 1.0 / 30
    private val arguments: Bundle get() = InstrumentationRegistry.getArguments()
    val enabled: Boolean get() = arguments.getString("acceptance") == "true"
    val keepData: Boolean get() = arguments.getString("keepData") == "true"
    val housekeeping: Boolean get() = arguments.getString("housekeeping") == "true"
    val importVia: String get() = arguments.getString("importVia") ?: "picker"

    /** Messages for the person at the device: in logcat and streamed live by the script. */
    fun operator(message: String) {
        Log.w(TAG, "OPERATOR: $message")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("operator", message) })
    }

    fun note(message: String) {
        Log.i(TAG, message)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("note", message) })
    }
}

/**
 * State handed from one step method to the next one across a process death:
 * a small JSON file in the app's private files (removed by the suite's cleanup).
 */
class Marker(name: String) {
    private val file = File(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "acceptance-suite"), "$name.json")
    fun read(): JSONObject? = if (file.exists()) JSONObject(file.readText()) else null
    fun write(value: JSONObject) { file.parentFile?.mkdirs(); file.writeText(value.toString(2)) }
    fun delete() { file.delete() }

    companion object {
        fun suiteFolder(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "acceptance-suite")
    }
}

/** Evidence for the operator, pulled by the script from the app's external files folder. */
object Evidence {
    fun root(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "acceptance")
    fun folder(test: String): File = File(root(), test).apply { mkdirs() }
    fun copy(test: String, file: File, name: String = file.name): File =
        File(folder(test), name).also { file.copyTo(it, overwrite = true) }
    fun json(test: String, name: String, value: JSONObject) = File(folder(test), name).writeText(value.toString(2))
}

/** Track durations and decoded frames of a movie. */
object MediaCheck {
    data class Track(val mime: String, val seconds: Double)

    fun tracks(file: File): List<Track> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else -1.0
                Track(format.getString(MediaFormat.KEY_MIME) ?: "?", duration)
            }
        } finally { extractor.release() }
    }

    fun video(file: File): Track = tracks(file).firstOrNull { it.mime.startsWith("video/") } ?: throw AssertionError("${file.name} has no video track")
    fun audio(file: File): Track = tracks(file).firstOrNull { it.mime.startsWith("audio/") } ?: throw AssertionError("${file.name} has no audio track")

    fun containerSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        } finally { retriever.release() }
    }

    /** Decodes the first and the last video frame; null for a frame that does not decode. */
    fun firstAndLastFrames(file: File): Triple<Int, Bitmap?, Bitmap?> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            if (count == 0) return Triple(0, null, null)
            return Triple(count, runCatching { retriever.getFrameAtIndex(0) }.getOrNull(), runCatching { retriever.getFrameAtIndex(count - 1) }.getOrNull())
        } finally { retriever.release() }
    }

    /** Audio and video durations of one file agree within [tolerance] seconds. */
    fun assertAudioVideoAligned(file: File, tolerance: Double = Suite.FRAME + 0.002): JSONObject {
        val video = video(file); val audio = audio(file)
        assertTrue("${file.name}: video duration unknown", video.seconds > 0)
        assertTrue("${file.name}: audio duration unknown", audio.seconds > 0)
        assertTrue("${file.name}: audio ${"%.4f".format(audio.seconds)} s and video ${"%.4f".format(video.seconds)} s differ by more than one frame",
            abs(audio.seconds - video.seconds) <= tolerance)
        return JSONObject().put("file", file.name).put("video", video.seconds).put("audio", audio.seconds).put("difference", audio.seconds - video.seconds)
    }
}

/** EditState ⇄ the Swift-compatible JSON the project files use, to compare states across processes. */
object Edits {
    fun encode(state: EditState): String = SwiftJSON.encodeToString(EditStateSerializer, state)
    private val longDecimal = Regex("-?\\d+\\.\\d{10,}(?:[eE]-?\\d+)?")
    /** [encode] with every number rounded to 1 ns: saving quantizes times, which changes the last bits of a Double. */
    fun comparable(state: EditState): String = encode(state).replace(longDecimal) { match ->
        java.math.BigDecimal(match.value).setScale(9, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    }
    fun decode(json: String): EditState = SwiftJSON.decodeFromString(EditStateSerializer, json)
    /** The edit points: start times of every video clip but the first, in seconds. */
    fun cuts(state: EditState): List<Double> = state.timeline.chronologicalVideo.map { it.start.seconds }.filter { it > 1e-6 }.sorted()
    fun sourceOrder(state: EditState): List<UUID> = state.timeline.chronologicalVideo.map { it.content.sourceID }
    /** Seconds of each source used by the timeline's video. */
    fun coverage(state: EditState): Map<UUID, Double> =
        state.timeline.chronologicalVideo.groupBy { it.content.sourceID }.mapValues { (_, items) -> items.sumOf { it.content.duration } }
}

@OptIn(ExperimentalCamera2Interop::class)
abstract class AcceptanceTest {
    protected open val timeoutSeconds: Long = 360

    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val testName = TestName()
    @get:Rule(order = 2) val timeout: Timeout get() = Timeout.seconds(timeoutSeconds)
    /** Innermost: on failure, keep a screenshot and the UI tree while the app is still open; then close it. */
    @get:Rule(order = 3) val app = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (failure: Throwable) {
                    if (failure !is org.junit.AssumptionViolatedException) captureFailure(description.methodName)
                    throw failure
                } finally {
                    tearDownAcceptance()
                }
            }
        }
    }

    private fun captureFailure(method: String) = runCatching {
        val folder = Evidence.folder(evidenceName)
        device.takeScreenshot(File(folder, "$method-failure.png"))
        val out = ByteArrayOutputStream(); device.dumpWindowHierarchy(out)
        File(folder, "$method-failure.xml").writeBytes(out.toByteArray())
        Log.e(Suite.TAG, "$method failed: ${appSummary()}")
    }

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    protected lateinit var model: ContentModel
    protected val store: ProjectStore get() = model.store ?: throw AssertionError("The project store is not loaded")
    protected val evidenceName: String get() = javaClass.simpleName

    @Before fun setUpAcceptance() {
        assumeTrue("Acceptance tests run only through scripts/acceptance.sh: they record footage and create projects.", Suite.enabled)
        // The recorder's meter and clock change many times a second: never wait for the
        // accessibility tree to go quiet; every wait below is an explicit condition with a timeout.
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().waitForSelectorTimeout = 0
        device.wakeUp()
        Suite.note("${javaClass.simpleName}.${testName.methodName} in process ${Process.myPid()}")
    }

    protected fun tearDownAcceptance() {
        val active = runCatching { onMain { model.camera.hasActiveTake || model.camera.state == CaptureState.paused } }.getOrDefault(false)
        if (active) runCatching {
            // Never leave a take running: stop it, or finish a paused take, before closing.
            if (onMain { model.camera.state == CaptureState.recording }) onMain { model.camera.stop() }
            waitForApp("the take to finish saving", 20_000) { !model.camera.busy }
            onMain { model.camera.finishPausedTake() }
        }
        scenario?.close(); scenario = null
    }

    // MARK: App lifecycle

    protected fun launchApp(): ContentModel {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        var found: ContentModel? = null
        launched.onActivity { found = ViewModelProvider(it)[ContentModel::class.java] }
        model = found ?: throw AssertionError("ContentModel is not available")
        waitForApp("the project store to load", 30_000) { model.store != null && model.startupRecoveryComplete }
        return model
    }

    /** A step that needs the app killed first (the script force-stops it between methods). */
    protected fun assumeFreshProcess(marker: JSONObject) {
        assumeTrue("This step needs a process death since the previous step; run the class through scripts/acceptance.sh",
            marker.optInt("pid") != Process.myPid())
    }

    protected fun markerFor(step: String): JSONObject =
        Marker(javaClass.simpleName).read() ?: throw AssertionError("No state from the previous step; run the whole class (scripts/acceptance.sh --only ${javaClass.simpleName}) before $step")

    protected fun saveMarker(values: JSONObject) = Marker(javaClass.simpleName).write(values.put("pid", Process.myPid()))

    // MARK: Waiting

    protected fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Fails at once when YouRoll shows an error alert, with its message. */
    private fun checkAppErrors(what: String) {
        if (!::model.isInitialized) return
        val message = onMain { model.camera.error ?: model.store?.error?.value } ?: return
        throw AssertionError("While waiting for $what, YouRoll reported: $message")
    }

    /** Polls [condition] on the test thread until it is true or [timeoutMs] passes. */
    protected fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            checkAppErrors(what)
            if (condition()) return
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out after ${timeoutMs / 1000.0} s waiting for $what. ${appSummary()}")
            SystemClock.sleep(50)
        }
    }

    /** Polls [condition] on the main thread (app state) until it is true or [timeoutMs] passes. */
    protected fun waitForApp(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) = waitFor(what, timeoutMs) { onMain(condition) }

    /** Keeps the take recording for [seconds] of wall time; fails if the recorder stops by itself. */
    protected fun holdRecording(seconds: Double) {
        val end = SystemClock.elapsedRealtime() + (seconds * 1000).toLong()
        while (SystemClock.elapsedRealtime() < end) {
            checkAppErrors("the take to keep recording")
            val state = onMain { model.camera.state to model.camera.warning }
            if (state.first != CaptureState.recording) throw AssertionError("Recording stopped by itself (${state.first}); warning: ${state.second}")
            SystemClock.sleep(50)
        }
    }

    protected fun appSummary(): String = runCatching {
        if (!::model.isInitialized) return@runCatching ""
        onMain {
            val camera = model.camera
            "screen=${model.screen} camera=${camera.state} ready=${camera.ready} busy=${camera.busy} switching=${camera.switchingCamera} " +
                "cameraName=${camera.currentCamera?.name} warning=${camera.warning} selected=${model.store?.selected?.title}"
        }
    }.getOrDefault("")

    // MARK: UI

    protected fun find(selector: BySelector, what: String, timeoutMs: Long = 10_000): UiObject2 {
        var found: UiObject2? = null
        waitFor(what, timeoutMs) { found = device.findObject(selector); found != null }
        return found!!
    }

    protected fun desc(text: String) = By.desc(text)
    protected fun descStarting(prefix: String) = By.desc(Pattern.compile(Pattern.quote(prefix) + ".*", Pattern.DOTALL))
    protected fun res(tag: String) = By.res(tag)
    protected fun text(value: String) = By.text(value)

    protected fun tap(selector: BySelector, what: String, timeoutMs: Long = 10_000) {
        val target = find(selector, what, timeoutMs)
        waitFor("$what to be enabled", 10_000) { runCatching { target.isEnabled }.getOrDefault(false) }
        // On a real device sheets slide in for a few hundred ms; a tap during the
        // animation lands where the control used to be. Tap once it stops moving.
        var previous: android.graphics.Rect? = null
        waitFor("$what to stop moving", 5_000) {
            val bounds = runCatching { target.visibleBounds }.getOrNull()
            val settled = bounds != null && bounds == previous
            previous = bounds
            if (!settled) Thread.sleep(120)
            settled
        }
        target.click()
    }

    protected fun tapDesc(description: String, timeoutMs: Long = 10_000) = tap(desc(description), "“$description”", timeoutMs)

    protected fun isEnabled(selector: BySelector, what: String): Boolean = find(selector, what).isEnabled

    protected fun waitGone(selector: BySelector, what: String, timeoutMs: Long = 10_000) {
        if (!device.wait(Until.gone(selector), timeoutMs)) throw AssertionError("Timed out waiting for $what to close")
    }

    /** Presses system Back only to dismiss a popup that is showing [content]. */
    protected fun dismissPopup(content: BySelector, what: String) {
        if (device.findObject(content) == null) return
        device.pressBack()
        waitGone(content, what)
    }

    // MARK: Projects

    protected fun selectedProject(): Project = onMain { store.selected } ?: throw AssertionError("No project is selected")

    /** Waits until the project's newest state is on disk, then reads it like a relaunch would. */
    protected fun savedProject(id: UUID): Project {
        val store = this.store
        val saved = runBlocking { withTimeoutOrNull(30_000) { withContext(Dispatchers.Main) { store.awaitSaved(id) } } }
        assertTrue("The project was not saved (Changes not saved)", saved == true)
        return store.disk.read(File(store.disk.folder(id), "project.json"))
    }

    protected fun projectFolder(id: UUID): File = store.disk.folder(id)

    protected fun sourceCount(id: UUID): Int = onMain { store.projects.value.firstOrNull { it.id == id }?.sources?.size ?: -1 }

    /** Projects → New project, like a user, then tag the title so `--keep-data` can find and delete it. */
    protected fun createTestProject(label: String): UUID {
        goToProjects()
        val before = onMain { store.projects.value.map { it.id }.toSet() }
        tap(text("New project"), "the New project button")
        waitForApp("the new project to open in the recorder") { model.screen == 0 && store.selectedID.value?.let { it !in before } == true }
        val project = selectedProject()
        onMain { store.rename(project, Suite.PROJECT_PREFIX + label) }
        savedProject(project.id)
        Suite.note("Project “${Suite.PROJECT_PREFIX + label}” is ${project.id.uuidString}")
        return project.id
    }

    /** Deletes the test project (Recently deleted, then permanently) and only that project. */
    protected fun deleteTestProject(id: UUID) {
        val project = onMain { store.projects.value.firstOrNull { it.id == id } } ?: return
        assertTrue("Refusing to delete “${project.title}”: not an acceptance project", project.title.startsWith(Suite.PROJECT_PREFIX))
        savedProject(id)
        onMain { store.moveToDeleted(project) }
        waitForApp("the test project to move to Recently deleted") { store.deletedProjects.value.any { it.id == id } }
        onMain { store.eraseDeleted(store.deletedProjects.value.first { it.id == id }) }
        waitForApp("the test project to be erased") { store.deletedProjects.value.none { it.id == id } }
        Suite.note("Deleted test project ${project.title}")
    }

    /** End of a flow: with `--keep-data` the test project is removed; otherwise it stays for inspection. */
    protected fun finishWithProject(id: UUID) {
        if (Suite.keepData) deleteTestProject(id)
    }

    protected fun goToProjects() {
        when (onMain { model.screen }) {
            0 -> {
                waitRecorderIdle()
                tapDesc("Projects")
            }
            2 -> tapDesc("Back to projects")
        }
        waitForApp("the project list") { model.screen == 1 }
        find(text("New project"), "the project list")
    }

    protected fun goToRecorder() {
        if (onMain { model.screen } == 2) tapDesc("Back to projects")
        if (onMain { model.screen } == 1) tap(res("catalogue.tab.0"), "the Record tab")
        waitForApp("the recorder") { model.screen == 0 }
        waitRecorderIdle()
    }

    // MARK: Recorder

    protected fun waitRecorderIdle(timeoutMs: Long = 30_000) = waitForApp("the camera to be ready", timeoutMs) {
        model.screen == 0 && model.camera.ready && !model.camera.busy && !model.camera.switchingCamera
    }

    /** Taps Record (or Resume) and waits until frames are being written; returns the start (elapsedRealtime ms). */
    protected fun startRecording(): Long {
        waitRecorderIdle()
        val paused = onMain { model.camera.state == CaptureState.paused }
        tap(desc(if (paused) "Resume recording" else "Record into this project"), if (paused) "Resume" else "Record")
        waitForApp("recording to start", 20_000) { model.camera.state == CaptureState.recording && model.camera.startedAt != null }
        return onMain { model.camera.startedAt!! }
    }

    /** Pause: the segment is finalised and added to the project. Returns the tap time (elapsedRealtime ms). */
    protected fun pauseRecording(project: UUID, expectedSources: Int): Long {
        tapDesc("Pause recording")
        val tapped = SystemClock.elapsedRealtime()
        waitForApp("the paused segment to be saved", 30_000) { model.camera.state == CaptureState.paused && sourceCount(project) == expectedSources }
        return tapped
    }

    /** Stop: the take ends and its last segment is added. Returns the tap time (elapsedRealtime ms). */
    protected fun stopRecording(project: UUID, expectedSources: Int): Long {
        tapDesc("Stop recording")
        val tapped = SystemClock.elapsedRealtime()
        waitForApp("the take to finish saving", 30_000) { model.camera.state == CaptureState.idle && !model.camera.busy && sourceCount(project) == expectedSources }
        return tapped
    }

    /** Opens the camera menu and returns the names it offers (enabled entries, in menu order). */
    protected fun cameraMenuChoices(): List<String> {
        waitRecorderIdle()
        val cameras = onMain { model.camera.cameras.toList() }
        tap(res("recorder.camera"), "the camera menu")
        val names = cameras.filter { it.available }.map { it.name }
        waitFor("the camera menu to list the cameras") { names.isNotEmpty() && device.hasObject(text(names.first())) }
        val shown = names.filter { device.hasObject(text(it)) }
        dismissPopup(text(names.first()), "the camera menu")
        assertEquals("The camera menu shows every available camera", names, shown)
        return shown
    }

    /** Picks [name] from the camera menu and waits until that camera delivers frames again. */
    protected fun selectCamera(name: String) {
        waitForApp("the recorder to accept a camera change") { model.camera.ready && !model.camera.busy && !model.camera.switchingCamera }
        if (onMain { model.camera.currentCamera?.name } == name) return
        tap(res("recorder.camera"), "the camera menu")
        tap(text(name), "“$name” in the camera menu")
        waitForApp("the switch to $name", 20_000) {
            model.camera.currentCamera?.name == name && !model.camera.switchingCamera && model.camera.ready && !model.camera.previewPaused
        }
        val warning = onMain { model.camera.warning }
        assertTrue("Camera switch to $name warned: $warning", warning?.contains("did not resume") != true)
    }

    // MARK: Editor

    protected fun openEditorFromRecorder() {
        waitRecorderIdle()
        tapDesc("Edit this project")
        waitForEditor()
    }

    protected fun waitForEditor() {
        waitForApp("the editor") { model.screen == 2 }
        find(res("editor.timeline"), "the editor timeline", 20_000)
        waitFor("the editor to show the playhead time") { playheadOrNull() != null }
        // Snap to cuts pulls the playhead to the nearest edit point when a scrub ends;
        // turn the magnet off (its button, like a user) so the playhead lands where it is dragged.
        if (device.hasObject(desc("Snap to cuts, On"))) {
            tapDesc("Snap to cuts, On")
            find(desc("Snap to cuts, Off"), "snapping turned off")
        }
    }

    private val timePattern = Pattern.compile("^\\d+:\\d{2}\\.\\d{2}$")

    private fun parseTime(value: String): Double {
        val (minutes, seconds) = value.split(":")
        return minutes.toInt() * 60 + seconds.toDouble()
    }

    /** The playhead readout: the leftmost m:ss.cc above the timeline. */
    private fun playheadOrNull(): Double? = runCatching {
        val timeline = device.findObject(res("editor.timeline"))?.visibleBounds ?: return null
        device.findObjects(By.text(timePattern)).filter { it.visibleBounds.bottom <= timeline.top }
            .minByOrNull { it.visibleBounds.left }?.text?.let(::parseTime)
    }.getOrNull()

    protected fun playhead(): Double = playheadOrNull() ?: throw AssertionError("The editor shows no playhead time")

    private fun settledPlayhead(): Double {
        var last = playhead()
        waitFor("the timeline to stop scrolling", 10_000) {
            SystemClock.sleep(150)
            val now = playhead(); val still = abs(now - last) < 0.005; last = now; still
        }
        return last
    }

    /**
     * Moves the playhead like a user until it is within [tolerance] of [seconds], by
     * tapping the timeline's time ruler (a tap there seeks to the time under the finger;
     * EditorTimeline.kt `tapped`, top 20 dp). Taps have no touch slop and no fling.
     *
     * Two layouts (EditorTimeline.kt): when the edit fits on screen, x maps to time
     * directly; otherwise the content scrolls so the playhead stays at a fixed x, and a
     * tap moves it by its distance from that x. Tapping the same x twice tells them apart.
     */
    protected fun scrubTo(seconds: Double, tolerance: Double = 0.05): Double {
        val timeline = find(res("editor.timeline"), "the editor timeline").visibleBounds
        val rulerY = timeline.top + (10 * context.resources.displayMetrics.density).toInt()
        val trace = mutableListOf(settledPlayhead())
        fun tapAt(x: Int): Double {
            device.click(x.coerceIn(timeline.left + 4, timeline.right - 4), rulerY)
            return settledPlayhead().also { trace += it }
        }
        if (abs(trace.last() - seconds) <= tolerance) return trace.last()
        val x1 = timeline.left + timeline.width() * 3 / 8
        val x2 = timeline.left + timeline.width() * 5 / 8
        val t2 = tapAt(x2)
        val t2again = tapAt(x2)
        val fits = abs(t2again - t2) < 0.01
        val t1 = tapAt(x1)
        var position = t1
        // Fit: t = (x - origin) / scale. Scroll: t moves by (x - anchor) / scale from the current time.
        val scale: Double
        val reference: Double
        if (fits) {
            scale = (x2 - x1) / (t2 - t1).let { if (abs(it) < 0.01) 0.01 else it }
            reference = x1 - t1 * scale                       // x of time zero
        } else {
            val d2 = t2again - t2; val d1 = t1 - t2again
            scale = (x2 - x1) / (d2 - d1).let { if (abs(it) < 0.01) 0.01 else it }
            reference = x1 - d1 * scale                       // the playhead's fixed x
        }
        repeat(10) {
            if (abs(position - seconds) <= tolerance) return position
            val x = if (fits) reference + seconds * scale else reference + (seconds - position) * scale
            position = tapAt(x.toInt().coerceIn(timeline.left + 4, timeline.right - 4))
        }
        if (abs(position - seconds) <= tolerance) return position
        Log.w(Suite.TAG, "scrub to $seconds (fits=$fits scale=$scale reference=$reference): $trace")
        throw AssertionError("Could not scrub to $seconds s (fits=$fits, playhead positions $trace)")
    }

    protected fun currentEdit(id: UUID): EditState = onMain { store.projects.value.first { it.id == id }.edit.copy() }

    protected fun canRedo(id: UUID): Boolean = onMain { store.projects.value.first { it.id == id }.canRedo }
    protected fun canUndo(id: UUID): Boolean = onMain { store.projects.value.first { it.id == id }.canUndo }

    /** Splits at the playhead after scrubbing to [seconds]; returns the cut time. */
    protected fun splitAt(id: UUID, seconds: Double): Double {
        val time = scrubTo(seconds)
        val before = currentEdit(id)
        tapDesc("Split")
        waitForApp("the split to apply") { store.projects.value.first { it.id == id }.edit != before }
        val after = currentEdit(id)
        assertEquals("Split adds one clip", before.timeline.chronologicalVideo.size + 1, after.timeline.chronologicalVideo.size)
        assertEquals("Split keeps the duration", before.duration, after.duration, 1e-6)
        return Edits.cuts(after).minByOrNull { abs(it - time) }!!
    }

    /** Mark start, scrub, Mark end, then Delete: an explicit range deletion. Returns the deleted length. */
    protected fun deleteRange(id: UUID, from: Double, to: Double): Double {
        val start = scrubTo(from)
        tapDesc("Mark start")
        find(desc("Mark end"), "the Mark end tool")
        val end = scrubTo(to)
        tapDesc("Mark end")
        val before = currentEdit(id)
        tap(By.desc(Pattern.compile("^Delete.*")), "the Delete selection action")
        waitForApp("the range deletion to apply") { store.projects.value.first { it.id == id }.edit != before }
        val removed = before.duration - currentEdit(id).duration
        assertEquals("Delete removes the marked range", end - start, removed, 0.1)
        return removed
    }

    protected fun undo(id: UUID) = history(id, "Undo")
    protected fun redo(id: UUID) = history(id, "Redo")

    private fun history(id: UUID, action: String) {
        val cursor = onMain { store.projects.value.first { it.id == id }.historyCursor }
        tapDesc(action)
        val expected = if (action == "Undo") cursor - 1 else cursor + 1
        waitForApp("$action to apply") { store.projects.value.first { it.id == id }.historyCursor == expected }
    }

    // MARK: Files

    protected fun sourceFiles(project: Project): List<File> = project.sources.map { File(store.disk.folder(project.id), it.filename) }

    /** Segment start time from its `<epochMillis>-<UUID>.mp4` name. */
    protected fun recordedAt(file: File): Long = file.name.substringBefore('-').toLongOrNull() ?: 0L

    protected fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { for ((k, v) in pairs) put(k, v) }
    protected fun jsonArray(values: Iterable<Any?>): JSONArray = JSONArray().apply { values.forEach { put(it) } }
    protected fun uuid(value: String): UUID = uuidOrNull(value) ?: throw AssertionError("Bad project id $value")

    protected fun scrollUntil(container: BySelector, target: BySelector, what: String): UiObject2 {
        device.findObject(target)?.let { return it }
        val scrollable = find(container, "a scrollable list")
        repeat(10) {
            device.findObject(target)?.let { return it }
            scrollable.scroll(Direction.DOWN, 0.6f)
        }
        return find(target, what, 2_000)
    }
}
