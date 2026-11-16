// Port of YouRec/CaptureController.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
//
// In scope: the take state machine, camera discovery/choices, switching mid-take,
// per-camera zoom and focus memory, zoom ramps, focus, quality, microphones,
// interruptions and capture events. Effects, dual cameras, HDR/Log, horizon
// levelling, face following, manual framing (pan), front landscape and Dolly/Crash
// zoom are listed in TODO.md.
package com.salvozappa.youroll

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.AudioStats
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// MARK: - Pure model (JVM-tested)

enum class CaptureQuality(val rawValue: String) {
    hd1080("hd1080"), ultraHD4K("ultraHD4K");
    val title: String get() = if (this == hd1080) "1080p · 30 fps" else "4K · 30 fps"
    companion object {
        fun from(raw: String?): CaptureQuality = entries.firstOrNull { it.rawValue == raw } ?: hd1080
    }
}

/** Android counterpart of `AVCaptureDevice.DeviceType`, plus Auto for a logical multi-camera. */
enum class CameraLens { auto, wide, ultraWide, tele }

data class CameraChoice(
    val id: String,
    val name: String,
    val front: Boolean,
    val available: Boolean = true,
    val supportsLandscape: Boolean = false,
    val lens: CameraLens = CameraLens.wide,
    /** Camera2 id bound through CameraX. */
    val logicalID: String = id,
    /** Physical lens inside [logicalID]; null lets the system choose (Auto) or for a single camera. */
    val physicalID: String? = null,
)

data class MicrophoneChoice(val id: String, val name: String)

/** What discovery reads from Camera2 for one camera; a logical camera lists its physical lenses. */
data class CameraDescriptor(
    val id: String,
    val front: Boolean,
    /** 35 mm equivalent focal length, when the characteristics expose it. */
    val equivalentFocal: Double?,
    val supports4K: Boolean = true,
    val physical: List<CameraDescriptor> = emptyList(),
)

object CameraCatalog {
    fun equivalentFocal(focal: Float?, sensorWidth: Float?, sensorHeight: Float?): Double? {
        if (focal == null || sensorWidth == null || sensorHeight == null || focal <= 0) return null
        val diagonal = hypot(sensorWidth.toDouble(), sensorHeight.toDouble())
        return if (diagonal > 0) focal * 43.27 / diagonal else null
    }

    private fun lensName(lens: CameraLens) = when (lens) {
        CameraLens.auto -> "auto"; CameraLens.wide -> "main"; CameraLens.ultraWide -> "ultrawide"; CameraLens.tele -> "tele"
    }

    /** Classify lenses relative to the lens closest to a 26 mm main camera on the same side. */
    private fun classify(focals: List<Double?>): List<CameraLens> {
        val known = focals.filterNotNull()
        if (known.size < 2) return focals.map { CameraLens.wide }
        val main = known.minBy { abs(it - 26.0) }
        return focals.map { f ->
            when {
                f == null -> CameraLens.wide
                f / main < 0.75 -> CameraLens.ultraWide
                f / main > 1.5 -> CameraLens.tele
                else -> CameraLens.wide
            }
        }
    }

    /**
     * One choice per usable camera. A logical multi-camera gives an Auto choice
     * plus one choice per physical lens; a physical lens that the system also
     * exposes as its own camera id is listed once (deduplicated).
     */
    fun choices(cameras: List<CameraDescriptor>, quality: CaptureQuality): List<CameraChoice> {
        val standalone = cameras.map { it.id }.toSet()
        val result = mutableListOf<CameraChoice>()
        for (front in listOf(false, true)) {
            val side = if (front) "Front" else "Rear"
            data class Entry(val descriptor: CameraDescriptor, val logical: String, val physical: String?)
            val autos = mutableListOf<CameraDescriptor>()
            val lenses = mutableListOf<Entry>()
            for (camera in cameras.filter { it.front == front }) {
                val physical = camera.physical.filter { it.id !in standalone }
                if (camera.physical.size > 1) {
                    autos.add(camera)
                    physical.forEach { lenses.add(Entry(it, camera.id, it.id)) }
                } else lenses.add(Entry(camera, camera.id, null))
            }
            val kinds = classify(lenses.map { it.descriptor.equivalentFocal })
            for (camera in autos) {
                result.add(CameraChoice(camera.id, "$side auto", front, available = quality == CaptureQuality.hd1080 || camera.supports4K,
                    lens = CameraLens.auto, logicalID = camera.id))
            }
            val order = listOf(CameraLens.ultraWide, CameraLens.wide, CameraLens.tele)
            val sorted = lenses.indices.sortedWith(compareBy({ order.indexOf(kinds[it]) }, { lenses[it].descriptor.equivalentFocal ?: 0.0 }))
            val used = mutableMapOf<String, Int>()
            for (index in sorted) {
                val entry = lenses[index]
                val base = "$side ${lensName(kinds[index])}"
                val count = (used[base] ?: 0) + 1
                used[base] = count
                val id = entry.physical?.let { "${entry.logical}/$it" } ?: entry.logical
                result.add(CameraChoice(id, if (count == 1) base else "$base $count", front,
                    available = quality == CaptureQuality.hd1080 || entry.descriptor.supports4K,
                    lens = kinds[index], logicalID = entry.logical, physicalID = entry.physical))
            }
        }
        return result
    }

    /** The most recently selected usable camera on the other side, else the first one there. */
    fun oppositeCamera(cameras: List<CameraChoice>, cameraID: String, lastFront: String?, lastRear: String?): CameraChoice? {
        val current = cameras.firstOrNull { it.id == cameraID } ?: return null
        val choices = cameras.filter { it.front != current.front && it.available }
        val remembered = if (current.front) lastRear else lastFront
        return choices.firstOrNull { it.id == remembered } ?: choices.firstOrNull()
    }

    /** The rear main lens, when the rear ultrawide is selected (the "1× Main" preset). */
    fun mainLensAtOneX(cameras: List<CameraChoice>, cameraID: String): CameraChoice? {
        if (cameras.none { it.id == cameraID && !it.front && it.lens == CameraLens.ultraWide }) return null
        return cameras.firstOrNull { !it.front && it.lens == CameraLens.wide && it.available }
    }

    /**
     * RecorderZoomPresets.presets. Android logical cameras report their widest
     * ratio (e.g. 0.67×) instead of 0.5×, so that ratio, rounded up to
     * a tenth, replaces the missing 0.5× preset.
     */
    fun zoomPresets(minZoom: Double, maxZoom: Double, multiplier: Double = 1.0): List<Double> {
        val lo = minZoom * multiplier - 1e-6; val hi = maxZoom * multiplier + 1e-6
        val presets = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 6.0).filter { it in lo..hi }.toMutableList()
        if (minZoom * multiplier < 0.95 && presets.none { it < 1 }) {
            val widest = ceil(minZoom * multiplier * 10 - 1e-6) / 10
            if (widest < 1 && widest in lo..hi) presets.add(0, widest)
        }
        return presets
    }
}

/** Key-value storage standing in for UserDefaults. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

/** The Swift `"camera." + uniqueID` dictionary: `zoom`, `locked`, `lens`. */
@Serializable
data class CameraSettings(val zoom: Double? = null, val locked: Boolean = false, val lens: Double = 0.5)

/** Zoom and focus remembered independently for every camera choice, including Auto and each physical lens. */
class CameraSettingsMemory(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    fun settingsKey(cameraID: String) = "camera.$cameraID"
    fun load(cameraID: String): CameraSettings =
        store.getString(settingsKey(cameraID))?.let { runCatching { json.decodeFromString<CameraSettings>(it) }.getOrNull() } ?: CameraSettings()
    fun save(cameraID: String, settings: CameraSettings) = store.putString(settingsKey(cameraID), json.encodeToString(settings))
    fun saveZoom(cameraID: String, zoom: Double) = save(cameraID, load(cameraID).copy(zoom = zoom))
    fun saveFocus(cameraID: String, locked: Boolean, lens: Double?) {
        val old = load(cameraID)
        save(cameraID, old.copy(locked = locked, lens = lens?.coerceIn(0.0, 1.0) ?: old.lens))
    }
    /**
     * The zoom to apply on this camera now. A format that cannot reach the saved
     * value is clamped without overwriting the preference; native framing (1× Main) is saved.
     */
    fun restoredZoom(cameraID: String, minZoom: Double, maxZoom: Double, nativeZoom: Boolean): Double {
        if (nativeZoom) {
            val native = 1.0.coerceIn(minZoom, maxZoom)
            saveZoom(cameraID, native); return native
        }
        return (load(cameraID).zoom ?: 1.0).coerceIn(minZoom, max(minZoom, maxZoom))
    }
}

enum class CaptureState(val title: String) {
    idle("Ready"), starting("Starting"), recording("Recording"), saving("Saving"), paused("Paused")
}

/**
 * The take state machine of CaptureController.swift, without the camera: the
 * capture-queue flags (`captureActive`, `finalizing`, `next`, `requestedStop`,
 * `takeHasMedia`, `retainMicrophone`) and the published state.
 */
class CaptureTake(private val onState: (CaptureState) -> Unit = {}) {
    enum class Next { stop, pause }

    var state = CaptureState.idle
        private set(value) { field = value; onState(value) }
    var captureActive = false; private set
    var finalizing = false; private set
    var next = Next.stop; private set
    var requestedStop = false; private set
    var takeHasMedia = false; private set
    var retainMicrophone = false; private set
    var takeID: String = newID(); private set
    var segmentSequence = 0; private set
    var directory: File? = null; private set

    val explicitStopRequested: Boolean get() = next == Next.stop && requestedStop
    val busy: Boolean get() = state == CaptureState.starting || state == CaptureState.saving
    val hasActiveTake: Boolean get() = state == CaptureState.recording || busy

    /** Record or Resume. Returns false if a segment is still active or finishing. */
    fun begin(directory: File, blocked: Boolean = false): Boolean {
        if (captureActive || finalizing || blocked) return false
        val continuing = state == CaptureState.paused
        if (!continuing || this.directory != directory) { takeID = newID(); segmentSequence = 0 }
        this.directory = directory
        return true
    }

    /** beginSegment: returns this segment's sequence number. */
    fun segmentStarting(): Int {
        val sequence = segmentSequence
        segmentSequence += 1
        captureActive = true; retainMicrophone = true; requestedStop = false; next = Next.stop
        state = CaptureState.starting
        return sequence
    }

    /** recordingStarted: true when the writer must stop immediately (Stop/Pause came first). */
    fun recordingStarted(wantsPreview: Boolean): Boolean {
        if (!wantsPreview || requestedStop) return true
        state = CaptureState.recording
        return false
    }

    /** stop(pause:). Stop wins over an automatic pause while the writer is finishing. */
    fun requestStop(pause: Boolean): Boolean {
        if (!captureActive) return false
        if (pause && explicitStopRequested) return false
        next = if (pause) Next.pause else Next.stop
        requestedStop = true
        state = CaptureState.saving
        return true
    }

    /** An interruption pauses (never stops) the take unless Stop was already requested. */
    fun interrupt(): Boolean {
        if (!captureActive || finalizing) return false
        if (!explicitStopRequested) next = Next.pause
        requestedStop = true
        state = CaptureState.saving
        return true
    }

    /** saveFinishedRecording, before the project accepts the file. */
    fun finished(success: Boolean) {
        captureActive = false; finalizing = true
        state = CaptureState.saving
        if (!success && !explicitStopRequested) next = Next.pause
    }

    /** The project accepted (or refused) the segment. Returns true when the take stays paused. */
    fun saved(saved: Boolean): Boolean {
        finalizing = false
        if (saved) takeHasMedia = true
        val paused = !saved || next != Next.stop
        retainMicrophone = paused
        if (!paused) takeHasMedia = false
        state = if (paused) CaptureState.paused else CaptureState.idle
        return paused
    }

    /** onEmpty: the writer closed without any media. Returns true when the take stays paused. */
    fun empty(): Boolean {
        captureActive = false; finalizing = false
        val paused = takeHasMedia && next == Next.pause
        if (!paused) { retainMicrophone = false; takeHasMedia = false }
        state = if (paused) CaptureState.paused else CaptureState.idle
        return paused
    }

    /** failedSegmentStart. Returns true when earlier segments keep the take paused. */
    fun failedStart(): Boolean {
        captureActive = false; retainMicrophone = takeHasMedia
        state = if (takeHasMedia) CaptureState.paused else CaptureState.idle
        return takeHasMedia
    }

    fun finishPaused(): Boolean {
        if (captureActive || finalizing) return false
        retainMicrophone = false; takeHasMedia = false
        state = CaptureState.idle
        return true
    }

    private companion object { fun newID() = UUID.randomUUID().toString().uppercase() }
}

/**
 * Fast sensor readings are observed only by the recorder's changing labels.
 * They must not invalidate menus or settings while a finger is selecting a row.
 */
@Stable
class CaptureReadings {
    var zoom by mutableStateOf(1.0); private set
    var focusLocked by mutableStateOf(false); private set
    var lensPosition by mutableStateOf(0.5f); private set
    var level by mutableStateOf(-160f); private set
    var audioArriving by mutableStateOf(false); private set
    var audioClipping by mutableStateOf(false); private set

    fun update(zoom: Double, focusLocked: Boolean, lensPosition: Float, level: Float, audioArriving: Boolean, audioClipping: Boolean) {
        if (this.zoom != zoom) this.zoom = zoom
        if (this.focusLocked != focusLocked) this.focusLocked = focusLocked
        if (this.lensPosition != lensPosition) this.lensPosition = lensPosition
        if (this.level != level) this.level = level
        if (this.audioArriving != audioArriving) this.audioArriving = audioArriving
        if (this.audioClipping != audioClipping) this.audioClipping = audioClipping
    }
}

/** Where a tap asked the camera to focus, in preview pixels. */
data class FocusMarker(val id: Long, val point: Offset)

// MARK: - Controller

/**
 * All CameraX calls and published values live on the main thread (the Swift
 * capture queue and `publish` collapse into one serial context here).
 */
@SuppressLint("UnsafeOptInUsageError")
@ExperimentalCamera2Interop
class CaptureController(private val context: Context) {
    var state by mutableStateOf(CaptureState.idle); private set
    var quality by mutableStateOf(CaptureQuality.hd1080); private set
    var cameras by mutableStateOf<List<CameraChoice>>(emptyList()); private set
    var cameraID by mutableStateOf(""); private set
    var switchingCamera by mutableStateOf(false); private set
    var microphones by mutableStateOf<List<MicrophoneChoice>>(emptyList()); private set
    var microphonePriority by mutableStateOf<List<MicrophoneChoice>>(emptyList()); private set
    var microphoneID by mutableStateOf(""); private set
    var microphoneName by mutableStateOf("Microphone unavailable"); private set
    var meterAvailable by mutableStateOf(false); private set
    var displayZoomMultiplier by mutableStateOf(1.0); private set
    var maxZoom by mutableStateOf(1.0); private set
    var minZoom by mutableStateOf(1.0); private set
    var focusHint by mutableStateOf<String?>(null); private set
    var focusMarker by mutableStateOf<FocusMarker?>(null); private set
    var autofocusSupported by mutableStateOf(false); private set
    var focusAdjustable by mutableStateOf(false); private set
    var focusLockSupported by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var startedAt by mutableStateOf<Long?>(null); private set // elapsedRealtime ms
    var stabilizationEnabled by mutableStateOf(false); private set
    var singleStabilizationSupported by mutableStateOf(false); private set
    private var warningState by mutableStateOf<String?>(null)
    private var errorState by mutableStateOf<String?>(null)
    /** Shown in the recorder banner; every new message is also logged. */
    var warning: String?
        get() = warningState
        set(value) { if (value != null && value != warningState) Log.w(TAG, "Warning shown: $value"); warningState = value }
    /** Shown as an alert; every new message is also logged. */
    var error: String?
        get() = errorState
        set(value) { if (value != null && value != errorState) Log.e(TAG, "Error shown: $value"); errorState = value }
    /** Last frame shown over the preview while the camera changes; `previewPaused` after a failed resume. */
    var frozenFrame by mutableStateOf<Bitmap?>(null); private set
    var previewPaused by mutableStateOf(false); private set

    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    private val defaults = SharedPreferencesStore(prefs)
    private val memory = CameraSettingsMemory(defaults)
    private val json = Json { ignoreUnknownKeys = true }

    var mirrorPreview by mutableStateOf(prefs.getBoolean("mirrorFrontPreview", true))
        private set
    var mirrorRecording by mutableStateOf(prefs.getBoolean("mirrorFrontRecording", false))
        private set

    val readings = CaptureReadings()
    /** iOS `onSegment`: receives every finalised segment. */
    var sink: CaptureSink? = null

    val busy: Boolean get() = state == CaptureState.starting || state == CaptureState.saving
    val hasActiveTake: Boolean get() = state == CaptureState.recording || busy
    val fixedFocus: Boolean get() = !autofocusSupported && !focusAdjustable
    val currentCamera: CameraChoice? get() = cameras.firstOrNull { it.id == cameraID }
    val frontPreviewMirrored: Boolean get() = mirrorPreview && currentCamera?.front == true
    val oppositeCamera: CameraChoice? get() = CameraCatalog.oppositeCamera(cameras, cameraID, lastFrontCameraID, lastRearCameraID)
    val mainLensAtOneX: CameraChoice? get() = CameraCatalog.mainLensAtOneX(cameras, cameraID)

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val movie = CaptureSegmentWriter(context)
    private val meterValues = LevelMeter()
    private val microphoneEngine = LiveMicrophone(meterValues)
    private val take = CaptureTake { state = it }
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var descriptors = mapOf<String, LensTraits>()
    private var configured = false
    private var configuring = false
    private var wantsPreview = false
    private var changingCamera = false
    private var selectedQuality = CaptureQuality.from(prefs.getString("captureQuality", null))
    private var lastFrontCameraID = prefs.getString("lastFrontCamera", null)
    private var lastRearCameraID = prefs.getString("lastRearCamera", null)
    private var targetRotation = Surface.ROTATION_0
    private var selectedZoom = 1.0
    private var lastSavedZoom = -1.0
    private var zoomTransition: ZoomTransition? = null
    private var zoomJob: Job? = null
    private var manualFocus = false
    private var manualLens: Float? = null
    @Volatile private var lastFocusDistance: Float? = null
    private var focusTarget: Pair<Long, Long>? = null // marker id, started (uptime ms)
    private var focusHintJob: Job? = null
    private var meterJob: Job? = null
    private var segment: SegmentInfo? = null
    private var captureRequestedAt = 0.0
    private var bytesPerSecond = 0.0
    private var storageSample = 0.0 to 0L
    private var lastStorageCheck = Double.NEGATIVE_INFINITY
    private var microphoneRoute = RecordingMicrophoneRoute()
    private var microphoneRouteCheck: Runnable? = null
    private var microphoneRetry: Runnable? = null
    private var recoveringMic = false
    private var lastMicIssue: String? = null
    private var preferredMicrophone: String? = null
    private var lastActualMicrophone: String? = null
    private var lastMicrophoneRestart = 0.0
    private var loggedRoute: String? = null
    private var switchCompletion: ((Boolean) -> Unit)? = null
    private var streamObserver: Observer<PreviewView.StreamState>? = null
    private var stateObserver: Pair<CameraInfo, Observer<CameraState>>? = null

    private data class LensTraits(val minFocus: Float, val afModes: IntArray, val supports4K: Boolean, val stabilization: Boolean)
    private data class SegmentInfo(val file: File, val takeID: String, val sequence: Int, val recordedAt: Long, val camera: CameraChoice, val microphone: MicrophoneChoice)

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleMicrophoneRefresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            microphoneRoute.deviceDisconnected(CaptureMicrophones.ports(arrayOf(*removedDevices)).map { it.uid })
            scheduleMicrophoneRefresh()
        }
    }

    init {
        movie.onStart = { recordingStarted(it) }
        movie.onFinish = { file, seconds, failure -> recordingFinished(file, seconds, failure) }
        movie.onEmpty = { recordingEmpty() }
        movie.onStatus = { stats ->
            // CameraX fallback path: its own input feeds the meter during a segment.
            val audio = stats.audioStats
            if (audio.audioState == AudioStats.AUDIO_STATE_ACTIVE) {
                meterValues.process(audio.audioAmplitude.toFloat().coerceIn(0f, 1f), uptime())
            }
        }
        // As on iOS, the microphone engine feeds the writer (own pipeline only).
        microphoneEngine.onSample = { movie.appendAudio(it) }
        microphoneEngine.onRouting = { scheduleMicrophoneRefresh() }
        stabilizationEnabled = prefs.getBoolean("stabilization", false)
        quality = selectedQuality
        movie.configure(selectedQuality, mirrorRecording, stabilizationEnabled)
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        microphoneEngine.onSample = null
        microphoneEngine.stop()
        movie.release()
        scope.coroutineContext[Job]?.cancel()
    }

    // MARK: Lifecycle

    fun attachPreviewView(view: PreviewView, lifecycleOwner: LifecycleOwner) {
        previewView = view
        preview?.surfaceProvider = view.surfaceProvider
        streamObserver?.let { view.previewStreamState.removeObserver(it) }
        var sawIdle = false
        val observer = Observer<PreviewView.StreamState> { stream ->
            if (stream == PreviewView.StreamState.IDLE) sawIdle = true
            if (stream == PreviewView.StreamState.STREAMING && sawIdle) { sawIdle = false; switchCompletion?.invoke(true) }
        }
        streamObserver = observer
        view.previewStreamState.observe(lifecycleOwner, observer)
    }

    fun prepare(lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        wantsPreview = true
        val granted = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) { error = "Allow Camera and Microphone access in Settings to record."; return }
        configure()
    }

    private fun configure() {
        if (!wantsPreview) return
        if (configured) { resumeSession(); return }
        if (configuring) return
        configuring = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            configuring = false
            try {
                val provider = future.get()
                this.provider = provider
                discover(provider)
                val initial = cameras.firstOrNull { !it.front && it.available } ?: cameras.firstOrNull { it.available }
                    ?: throw IllegalStateException("No camera is available.")
                if (!bind(initial)) return@addListener
                configured = true
                applyCameraSettings(initial)
                recoverMicrophone()
                startMeter()
            } catch (e: Exception) { error = e.localizedMessage ?: "The camera could not start." }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun resumeSession() {
        val choice = currentCamera
        if (camera == null && choice != null) { if (bind(choice)) applyCameraSettings(choice) }
        recoverMicrophone()
        startMeter()
    }

    private fun discover(provider: ProcessCameraProvider) {
        val traits = mutableMapOf<String, LensTraits>()
        fun traitsOf(info: CameraInfo, fallback: CameraInfo?): LensTraits {
            val c2 = Camera2CameraInfo.from(info)
            val capabilities = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
                ?: fallback?.let { runCatching { Recorder.getVideoCapabilities(it) }.getOrNull() }
            return LensTraits(
                minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                afModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(),
                supports4K = capabilities?.getSupportedQualities(DynamicRange.SDR)?.contains(Quality.UHD) == true,
                stabilization = capabilities?.isStabilizationSupported == true,
            )
        }
        fun descriptor(info: CameraInfo, front: Boolean, id: String, physical: List<CameraDescriptor>, supports4K: Boolean): CameraDescriptor {
            val c2 = Camera2CameraInfo.from(info)
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            return CameraDescriptor(id, front, CameraCatalog.equivalentFocal(focal, size?.width, size?.height), supports4K, physical)
        }
        val list = provider.availableCameraInfos.mapNotNull { info ->
            val facing = info.lensFacing
            if (facing != CameraSelector.LENS_FACING_BACK && facing != CameraSelector.LENS_FACING_FRONT) return@mapNotNull null
            val id = Camera2CameraInfo.from(info).cameraId
            val front = facing == CameraSelector.LENS_FACING_FRONT
            val logicalTraits = traitsOf(info, null)
            traits[id] = logicalTraits
            val physical = runCatching { info.physicalCameraInfos }.getOrDefault(emptySet()).mapNotNull { p ->
                val pid = runCatching { Camera2CameraInfo.from(p).cameraId }.getOrNull() ?: return@mapNotNull null
                val t = traitsOf(p, info).let { it.copy(supports4K = it.supports4K || logicalTraits.supports4K) }
                traits["$id/$pid"] = t
                descriptor(p, front, pid, emptyList(), t.supports4K)
            }.sortedBy { it.id }
            descriptor(info, front, id, physical, logicalTraits.supports4K)
        }
        descriptors = traits
        cameras = CameraCatalog.choices(list, selectedQuality)
    }

    private fun selector(choice: CameraChoice): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == choice.logicalID } }
            .apply { choice.physicalID?.let { setPhysicalCameraId(it) } }
            .build()

    /** Bind preview and the persistent video use case to [choice]. A recording in progress continues. */
    private fun bind(choice: CameraChoice): Boolean {
        val provider = provider ?: return false
        val owner = owner ?: return false
        val builder = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
        Camera2Interop.Extender(builder).setSessionCaptureCallback(captureCallback)
        val nextPreview = builder.build()
        previewView?.let { nextPreview.surfaceProvider = it.surfaceProvider }
        movie.videoCapture.targetRotation = targetRotation
        return try {
            stateObserver?.let { (info, observer) -> info.cameraState.removeObserver(observer) }
            provider.unbindAll()
            val bound = provider.bindToLifecycle(owner, selector(choice), nextPreview, movie.videoCapture)
            camera = bound; preview = nextPreview
            val observer = Observer<CameraState> { cameraStateChanged(it) }
            bound.cameraInfo.cameraState.observe(owner, observer)
            stateObserver = bound.cameraInfo to observer
            true
        } catch (e: Exception) {
            camera = null
            error = "This camera is unavailable with the current capture settings. " + (e.localizedMessage ?: "")
            false
        }
    }

    private fun cameraStateChanged(cameraState: CameraState) {
        val failure = cameraState.error
        if (failure != null) {
            val reason = when (failure.code) {
                CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Another app is using the camera."
                CameraState.ERROR_CAMERA_DISABLED -> "The camera is disabled on this device."
                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Do Not Disturb is blocking the camera."
                CameraState.ERROR_CAMERA_FATAL_ERROR -> "The camera stopped with an error."
                else -> "The camera was interrupted."
            }
            val interrupted = take.captureActive && !take.finalizing
            if (interrupted) interruptTake("$reason Recording paused; review the last take.")
            else if (failure.type == CameraState.ErrorType.CRITICAL) warning = reason
        }
        val open = cameraState.type == CameraState.Type.OPEN
        if (ready != (open && wantsPreview)) ready = open && wantsPreview
    }

    /**
     * Leave the recorder or the foreground. An active take is paused, never lost.
     * With [forPlayback] (Projects/Editor are showing) the camera device is also
     * released once no take is finalising, as iOS stops its capture session;
     * `prepare` binds it again.
     */
    fun suspend(forPlayback: Boolean = false) {
        cancelZoomTransition()
        saveCameraSettings()
        wantsPreview = false
        if (take.captureActive) {
            if (take.interrupt()) {
                warning = "Recording paused because YouRoll left the screen. Resume when ready."
                movie.stopRecording()
            }
        } else if (!take.finalizing) {
            stopSession()
            if (forPlayback) releaseCamera()
        }
        ready = false
    }

    private fun releaseCamera() {
        stateObserver?.let { (info, observer) -> info.cameraState.removeObserver(observer) }
        stateObserver = null
        runCatching { provider?.unbindAll() }
        camera = null
    }

    private fun stopSession() {
        saveCameraSettings()
        cancelZoomTransition()
        microphoneRouteCheck?.let { main.removeCallbacks(it) }; microphoneRouteCheck = null
        microphoneRetry?.let { main.removeCallbacks(it) }; microphoneRetry = null; recoveringMic = false
        microphoneEngine.stop()
        meterJob?.cancel(); meterJob = null
        ready = false
    }

    /** RecorderView reports the device posture; the recorded rotation is frozen during a take, as on iOS. */
    fun setOrientation(rotation: Int) {
        if (hasActiveTake || state == CaptureState.paused || take.captureActive) return
        if (targetRotation == rotation) return
        targetRotation = rotation
        movie.videoCapture.targetRotation = rotation
    }

    // MARK: Takes

    fun start(directory: File) {
        if (!configured || camera == null || changingCamera) {
            Log.i(TAG, "Record ignored: configured $configured, camera ${camera != null}, changing camera $changingCamera"); return
        }
        if (!take.begin(directory)) { Log.i(TAG, "Record ignored: the take is ${take.state}"); return }
        Log.i(TAG, "Record: camera ${currentCamera?.name} ($cameraID), ${selectedQuality.rawValue}, microphone ${microphoneName.ifEmpty { "default" }}")
        beginSegment()
    }

    private fun beginSegment() {
        val directory = take.directory
        val choice = currentCamera
        if (directory == null || choice == null || !wantsPreview || camera == null || !ready) {
            failedSegmentStart("Recording could not start. Check the camera and try again.", warning = true); return
        }
        if (!microphoneEngine.isRunning) startMicrophoneEngine()
        if (!microphoneEngine.isRunning) {
            failedSegmentStart("Waiting for the microphone. Try Record when the meter is active.", warning = true); return
        }
        directory.mkdirs()
        val required = if (selectedQuality == CaptureQuality.ultraHD4K) 170_000_000L else 75_000_000L
        if (freeBytes(directory) < required) {
            failedSegmentStart("Not enough free storage to record. Free some space and try again."); return
        }
        val recordedAt = System.currentTimeMillis()
        val file = File(directory, "$recordedAt-${UUID.randomUUID().toString().uppercase()}.mp4")
        refreshMicrophones()
        val sequence = take.segmentStarting()
        segment = SegmentInfo(file, take.takeID, sequence, recordedAt, choice, MicrophoneChoice(microphoneID, microphoneName))
        bytesPerSecond = if (selectedQuality == CaptureQuality.ultraHD4K) 7_000_000.0 else 2_000_000.0
        storageSample = uptime() to 0L
        captureRequestedAt = uptime()
        lastMicrophoneRestart = captureRequestedAt
        startSegmentWriter(file, attempt = 0)
    }

    /**
     * Android addition: right after the camera opens (launch, project change, camera
     * switch) its first frame can be a few hundred ms away. Record waits up to 3 s
     * for it instead of failing; Stop or leaving the screen meanwhile ends the
     * segment empty.
     */
    private fun startSegmentWriter(file: File, attempt: Int) {
        if (segment?.file != file) return
        if (attempt > 0 && (take.requestedStop || !wantsPreview)) {
            Log.i(TAG, "Segment ${file.name} cancelled while waiting for the camera"); recordingEmpty(); return
        }
        try {
            if (movie.usesOwnAudio) {
                val routed = CaptureMicrophones.port(microphoneEngine.routedDevice)
                if (attempt == 0) Log.i(TAG, "Recording microphone: ${routed?.portName ?: "unknown"} (${routed?.uid ?: "-"}), " +
                    "${microphoneEngine.channels} channel(s), requested ${preferredMicrophone ?: "default"}")
                try { movie.startRecording(file, audio = true, channels = microphoneEngine.channels) }
                catch (e: CaptureSegmentWriter.CustomPathUnavailable) { startWithSystemRecorder(file, e.localizedMessage ?: "encoder unavailable") }
            } else {
                // CameraX opens its own AudioRecord; release the meter's input first.
                microphoneEngine.stop()
                movie.startRecording(file, audio = true)
            }
        } catch (e: CaptureSegmentWriter.CameraNotReady) {
            if (attempt < 30) {
                if (attempt == 0) Log.i(TAG, "Record: waiting for the camera's first frame")
                main.postDelayed({ startSegmentWriter(file, attempt + 1) }, 100)
            } else failedSegmentStart("Recording could not start. " + (e.localizedMessage ?: ""))
        } catch (e: Exception) { failedSegmentStart("Recording could not start. " + (e.localizedMessage ?: "")) }
    }

    /**
     * The own encoder could not start: rebind the camera with the CameraX recorder
     * for the rest of the session and record this segment with it.
     */
    private fun startWithSystemRecorder(file: File, reason: String) {
        val choice = currentCamera ?: throw IllegalStateException("No camera is selected.")
        if (!movie.useSystemRecorder(reason)) throw IllegalStateException(reason)
        if (!bind(choice)) throw IllegalStateException("The camera could not be restarted.")
        applyCameraSettings(choice)
        warning = "Recording with the system recorder because the encoder could not start. The selected microphone may not be used."
        microphoneEngine.stop()
        movie.startRecording(file, audio = true)
    }

    private fun failedSegmentStart(message: String, warning: Boolean = false) {
        val paused = take.failedStart()
        if (!paused) microphoneRoute.reset()
        segment = null
        if (warning) this.warning = message else error = message
        recoverMicrophone()
    }

    private fun recordingStarted(file: File) {
        Log.i(TAG, "Segment started: ${file.name}")
        if (take.recordingStarted(wantsPreview)) { movie.stopRecording(); return }
        startedAt = SystemClock.elapsedRealtime()
    }

    fun stop(pause: Boolean = false) {
        Log.i(TAG, if (pause) "Pause requested" else "Stop requested")
        if (take.requestStop(pause)) movie.stopRecording()
    }

    fun finishPausedTake() {
        if (!take.finishPaused()) return
        microphoneRoute.reset()
        refreshMicrophones()
    }

    /** Pause the take with a visible reason. Never resumes by itself. */
    private fun interruptTake(reason: String) {
        cancelZoomTransition()
        Log.w(TAG, "Interrupting the take: $reason")
        if (take.interrupt()) {
            warning = reason
            movie.stopRecording()
        }
    }

    private fun recordingEmpty() {
        Log.w(TAG, "Segment ${segment?.file?.name} ended empty")
        startedAt = null; segment = null
        val paused = take.empty()
        if (!paused) microphoneRoute.reset()
        afterSegment()
    }

    private fun recordingFinished(file: File, seconds: Double, failure: String?) {
        Log.i(TAG, "Segment finished: ${file.name}, %.3f s".format(Locale.US, seconds) + (failure?.let { ", failure: $it" } ?: ""))
        startedAt = null
        val info = segment
        segment = null
        take.finished(success = failure == null)
        if (failure != null) warning = "Recording interrupted: $failure The footage until then was saved."
        val sink = sink
        if (sink == null || info == null) {
            take.saved(false)
            error = "No project is available to save this recording. Its file has been preserved."
            afterSegment(); return
        }
        val settings = CaptureSegmentSettings(
            cameraID = info.camera.id, cameraName = info.camera.name, quality = selectedQuality.rawValue,
            mirroredFront = mirrorRecording, stabilized = stabilizationEnabled,
            microphoneID = microphoneID.ifEmpty { info.microphone.id }, microphoneName = microphoneName)
        val captured = CapturedSegment(file, seconds, movie.currentEvents.ifEmpty { readEvents(file) }, settings,
            info.takeID, info.sequence, info.recordedAt, failure)
        var answered = false
        sink.segmentFinalized(captured) { saved ->
            main.post {
                if (answered) return@post
                answered = true
                val paused = take.saved(saved)
                if (!saved) error = "The recording could not be added to the project. Its file has been preserved."
                if (!paused) microphoneRoute.reset()
                afterSegment()
            }
        }
    }

    private fun readEvents(file: File): List<CaptureSegmentEvent> = runCatching {
        json.decodeFromString<List<CaptureSegmentEvent>>(CaptureSegmentWriter.eventsFile(file).readText())
    }.getOrDefault(emptyList())

    private fun afterSegment() {
        if (!wantsPreview) stopSession() else { recoverMicrophone(); refreshMicrophones() }
    }

    fun addBookmark() = movie.bookmark()

    // MARK: Cameras

    private fun rememberCamera(id: String, front: Boolean) {
        if (front) lastFrontCameraID = id else lastRearCameraID = id
        prefs.edit().putString(if (front) "lastFrontCamera" else "lastRearCamera", id).apply()
    }

    fun flipCamera() {
        if (busy || switchingCamera) return
        val target = oppositeCamera ?: return
        switchCamera(target.id)
    }

    /**
     * Switch lens without ending the take: freeze the last frame over the preview,
     * rebind the persistent recording to the new camera, restore that camera's own
     * zoom and focus, and record a `cameraSwitch` event and the video hold.
     */
    fun switchCamera(id: String, nativeZoom: Boolean = false) {
        if (take.finalizing || changingCamera || !configured) return
        if (id == cameraID && !nativeZoom) return
        val choice = cameras.firstOrNull { it.id == id } ?: run { error = "The requested camera is unavailable."; return }
        if (!choice.available) {
            warning = "This camera does not support the selected quality. Stop the take and choose 1080p to use it."; return
        }
        val previous = currentCamera
        Log.i(TAG, "Switch camera: ${previous?.name} ($cameraID) -> ${choice.name} ($id), recording ${segment != null}, native zoom $nativeZoom")
        saveCameraSettings()
        cancelZoomTransition()
        changingCamera = true; switchingCamera = true
        frozenFrame = previewView?.bitmap; previewPaused = false
        if (id == cameraID) {
            applyCameraSettings(choice, nativeZoom)
            finishSwitch(true); return
        }
        movie.holdVideo(true)
        movie.recordEvent("cameraSwitch", "Camera changed to " + choice.name)
        if (!bind(choice)) {
            if (previous != null && bind(previous)) applyCameraSettings(previous)
            else interruptTake("The camera could not be changed. Recording paused; review the last take.")
            finishSwitch(false); return
        }
        applyCameraSettings(choice, nativeZoom)
        refreshMicrophones()
        val token = Any()
        switchToken = token
        switchCompletion = { resumed -> if (switchToken === token) finishSwitch(resumed) }
        main.postDelayed({
            if (switchToken === token) {
                warning = "Camera preview did not resume. Try selecting another camera."
                previewPaused = true
                finishSwitch(false, keepFrame = true)
            }
        }, 5000)
    }
    private var switchToken: Any? = null

    private fun finishSwitch(resumed: Boolean, keepFrame: Boolean = false) {
        Log.i(TAG, "Camera switch finished: ${currentCamera?.name}, preview resumed $resumed")
        switchToken = null; switchCompletion = null
        movie.holdVideo(false)
        changingCamera = false; switchingCamera = false
        if (!keepFrame) { frozenFrame = null; previewPaused = false }
        if (resumed && previewPaused) previewPaused = false
    }

    private fun saveCameraSettings() {
        val id = cameraID.ifEmpty { return }
        if (camera == null) return
        memory.saveZoom(id, selectedZoom)
        lastSavedZoom = selectedZoom
        memory.saveFocus(id, manualFocus, if (manualFocus) (manualLens ?: currentLensPosition()).toDouble() else null)
    }

    private fun applyCameraSettings(choice: CameraChoice, nativeZoom: Boolean = false) {
        val bound = camera ?: return
        val zoomState = bound.cameraInfo.zoomState.value
        val lo = (zoomState?.minZoomRatio ?: 1f).toDouble()
        val hi = (zoomState?.maxZoomRatio ?: 1f).toDouble()
        minZoom = lo; maxZoom = min(10.0, max(lo, hi))
        cameraID = choice.id
        rememberCamera(choice.id, choice.front)
        val zoom = memory.restoredZoom(choice.id, minZoom, maxZoom, nativeZoom)
        applyZoom(zoom)
        lastSavedZoom = zoom
        val traits = descriptors[choice.id]
        val minFocus = traits?.minFocus ?: 0f
        val afModes = traits?.afModes ?: intArrayOf()
        autofocusSupported = afModes.any { it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO || it == CaptureRequest.CONTROL_AF_MODE_AUTO }
        focusAdjustable = minFocus > 0f
        focusLockSupported = focusAdjustable
        singleStabilizationSupported = traits?.stabilization == true
        val saved = memory.load(choice.id)
        manualFocus = saved.locked && focusLockSupported
        manualLens = if (manualFocus) saved.lens.toFloat() else null
        lastFocusDistance = null
        applyFocus()
        updateReadings()
    }

    @JvmName("setQualityRequest")
    fun setQuality(quality: CaptureQuality) {
        if (take.captureActive || take.finalizing || take.retainMicrophone || changingCamera) return
        val choice = currentCamera ?: return
        if (quality == CaptureQuality.ultraHD4K && descriptors[choice.id]?.supports4K != true) {
            warning = "This camera does not support ${quality.title}."; return
        }
        saveCameraSettings(); cancelZoomTransition()
        selectedQuality = quality
        prefs.edit().putString("captureQuality", quality.rawValue).apply()
        this.quality = quality
        rebuildVideo()
        cameras = cameras.map { it.copy(available = quality == CaptureQuality.hd1080 || descriptors[it.id]?.supports4K == true) }
        if (cameras.any { !it.available }) warning = "Some cameras need 1080p. They are marked in the camera menu."
    }

    fun setStabilization(enabled: Boolean) {
        if (hasActiveTake || switchingCamera || (enabled && !singleStabilizationSupported)) return
        stabilizationEnabled = enabled
        prefs.edit().putBoolean("stabilization", enabled).apply()
        rebuildVideo()
    }

    @JvmName("setMirrorPreviewRequest")
    fun setMirrorPreview(value: Boolean) {
        mirrorPreview = value
        prefs.edit().putBoolean("mirrorFrontPreview", value).apply()
    }

    @JvmName("setMirrorRecordingRequest")
    fun setMirrorRecording(value: Boolean) {
        if (hasActiveTake || state == CaptureState.paused) return
        mirrorRecording = value
        prefs.edit().putBoolean("mirrorFrontRecording", value).apply()
        rebuildVideo()
    }

    /** Quality, stabilisation and mirroring are properties of the video use case; change them between takes. */
    private fun rebuildVideo() {
        if (!movie.configure(selectedQuality, mirrorRecording, stabilizationEnabled)) return
        val choice = currentCamera ?: return
        if (camera != null && bind(choice)) applyCameraSettings(choice)
    }

    // MARK: Zoom

    private fun applyZoom(value: Double) {
        selectedZoom = value
        camera?.cameraControl?.setZoomRatio(value.toFloat())
    }

    fun setZoom(target: Double, seconds: Double, lens: String? = null, persist: Boolean = true) {
        if (!target.isFinite() || !seconds.isFinite()) return
        if (!wantsPreview || camera == null || changingCamera) return
        if (lens != null && lens != cameraID) return
        cancelZoomTransition()
        val bounded = target.coerceIn(minZoom, max(minZoom, maxZoom))
        val id = cameraID
        if (seconds <= 0 || abs(selectedZoom - bounded) <= 0.001) {
            applyZoom(bounded)
            if (persist) { memory.saveZoom(id, bounded); lastSavedZoom = bounded }
            updateReadings()
            return
        }
        val transition = ZoomTransition(start = selectedZoom, target = bounded, began = uptime(), duration = seconds)
        zoomTransition = transition
        zoomJob = scope.launch {
            while (true) {
                val now = uptime()
                applyZoom(transition.value(now))
                if (transition.isFinished(now)) break
                delay(16)
            }
            // Persist only the final applied value, not every frame.
            memory.saveZoom(id, selectedZoom); lastSavedZoom = selectedZoom
            zoomTransition = null; zoomJob = null
            updateReadings()
        }
    }

    /** A new request or a camera change supersedes a ramp; the last applied zoom is remembered. */
    private fun cancelZoomTransition() {
        val hadMove = zoomTransition != null
        zoomJob?.cancel(); zoomJob = null; zoomTransition = null
        if (hadMove && cameraID.isNotEmpty()) { memory.saveZoom(cameraID, selectedZoom); lastSavedZoom = selectedZoom }
    }

    // MARK: Focus

    fun showFocusHint(text: String) {
        focusHintJob?.cancel()
        focusHint = text
        focusHintJob = scope.launch { delay(1800); focusHint = null }
    }

    private fun currentLensPosition(): Float {
        manualLens?.let { if (manualFocus) return it }
        val minFocus = descriptors[cameraID]?.minFocus ?: 0f
        val distance = lastFocusDistance ?: return 0.5f
        return if (minFocus > 0) (1 - distance / minFocus).coerceIn(0f, 1f) else 1f
    }

    private fun applyFocus() {
        val control = camera?.cameraControl?.let { Camera2CameraControl.from(it) } ?: return
        if (manualFocus) {
            val minFocus = descriptors[cameraID]?.minFocus ?: 0f
            val lens = manualLens ?: currentLensPosition()
            control.setCaptureRequestOptions(CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, (1 - lens) * minFocus)
                .build())
        } else control.clearCaptureRequestOptions()
    }

    /** Tap to focus. [point] is in the preview's metering space, [marker] in preview pixels. */
    fun autofocus(point: MeteringPoint, marker: Offset) {
        if (!ready || busy || switchingCamera || readings.focusLocked) return
        if (fixedFocus) { showFocusHint("This lens has fixed focus"); return }
        if (!autofocusSupported) { showFocusHint("This lens chooses its own focus point"); return }
        val bound = camera ?: return
        val id = SystemClock.uptimeMillis()
        focusMarker = FocusMarker(id, marker)
        focusTarget = id to id
        val result = bound.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(5, TimeUnit.SECONDS).build())
        result.addListener({
            val target = focusTarget ?: return@addListener
            if (target.first != id) return@addListener
            val wait = max(0L, 850 - (SystemClock.uptimeMillis() - id))
            main.postDelayed({ dismissFocus(id) }, wait)
        }, ContextCompat.getMainExecutor(context))
        main.postDelayed({ dismissFocus(id) }, 4000)
    }

    private fun dismissFocus(id: Long) {
        if (focusTarget?.first == id) focusTarget = null
        if (focusMarker?.id == id) focusMarker = null
    }

    fun setFocus(locked: Boolean, position: Float? = null, persist: Boolean = true) {
        if (camera == null || cameraID.isEmpty()) return
        manualFocus = locked && focusLockSupported
        manualLens = if (manualFocus) (position?.coerceIn(0f, 1f) ?: currentLensPosition()) else null
        if (manualFocus) { focusMarker = null; focusTarget = null }
        applyFocus()
        if (persist) memory.saveFocus(cameraID, manualFocus, manualLens?.toDouble())
        updateReadings()
    }

    // MARK: Microphones

    private fun scheduleMicrophoneRefresh() {
        if (!configured || !wantsPreview) return
        if (recoveringMic) return
        microphoneRetry?.let { main.removeCallbacks(it) }
        val work = Runnable { recoverMicrophone() }
        microphoneRetry = work
        main.postDelayed(work, 120)
    }

    private fun preferredDevice(): AudioDeviceInfo? {
        val id = preferredMicrophone ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { CaptureMicrophones.uid(it.type, it.address.orEmpty()) == id }
    }

    private fun startMicrophoneEngine(): Boolean = try {
        microphoneEngine.start(preferredDevice()); true
    } catch (_: Exception) { false }

    /**
     * Keep the microphone engine running: it meters and, with the own writer,
     * records the take. On the CameraX fallback path CameraX owns the input during a segment.
     */
    private fun recoverMicrophone(attempt: Int = 0) {
        if (!configured || !wantsPreview) return
        microphoneRetry?.let { main.removeCallbacks(it) }; microphoneRetry = null
        recoveringMic = true
        refreshMicrophones()
        if (movie.hasDestination && !movie.usesOwnAudio) { recoveringMic = false; return }
        if (startMicrophoneEngine()) {
            recoveringMic = false
            val resolved = lastMicIssue; lastMicIssue = null
            meterAvailable = true
            if (resolved != null && warning == resolved) warning = null
            refreshMicrophones()
            return
        }
        val delays = listOf(120L, 250L, 500L, 800L, 1000L)
        if (attempt < delays.size) {
            val work = Runnable { recoverMicrophone(attempt + 1) }
            microphoneRetry = work
            main.postDelayed(work, delays[attempt])
            return
        }
        recoveringMic = false
        val message = "Microphone unavailable. Check the input in the microphone menu."
        lastMicIssue = message
        meterAvailable = false; warning = message
    }

    fun selectMicrophone(id: String) {
        if (!configured || !wantsPreview) return
        val ports = CaptureMicrophones.ports(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
        if (ports.none { it.uid == id }) { warning = "That microphone is no longer available."; refreshMicrophones(); return }
        microphoneRoute.select(id)
        refreshMicrophones()
    }

    fun moveMicrophonePreference(id: String, offset: Int) {
        val order = loadList("microphonePriority").toMutableList()
        val index = order.indexOf(id)
        if (index < 0 || index + offset !in order.indices) return
        order[index] = order[index + offset].also { order[index + offset] = order[index] }
        saveList("microphonePriority", order)
        refreshMicrophones()
    }

    private fun loadList(key: String): List<String> =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()
    private fun saveList(key: String, value: List<String>) = prefs.edit().putString(key, json.encodeToString(value)).apply()

    /**
     * The input the system actually routes (`AudioRecord.getRoutedDevice` of the
     * microphone engine); CameraX's own input during a fallback-path segment.
     */
    private fun actualMicrophone(): MicrophonePort? {
        val device = if (movie.hasDestination && !movie.usesOwnAudio) audioManager.activeRecordingConfigurations.firstOrNull()?.audioDevice
            else microphoneEngine.routedDevice
        return CaptureMicrophones.port(device)
    }

    private fun refreshMicrophones() {
        if (!configured || !wantsPreview) return
        val ports = CaptureMicrophones.ports(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
        val actualPort = actualMicrophone()
        // Before CameraX reports its route, keep the last confirmed input instead of "none".
        val actual = actualPort?.uid ?: if (take.retainMicrophone) lastActualMicrophone else null
        if (actualPort != null) lastActualMicrophone = actualPort.uid
        val saved = loadList("microphonePriority")
        val preferences = RecordingMicrophoneRoute.priority(saved, ports.map { it.uid }, actual)
        if (preferences != saved) saveList("microphonePriority", preferences)
        val savedNames = prefs.getString("microphoneNames", null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()
        val known = savedNames.toMutableMap()
        for (port in ports) known[port.uid] = port.portName
        if (known != savedNames) prefs.edit().putString("microphoneNames", json.encodeToString(known.toMap())).apply()
        val available = preferences.mapNotNull { id -> ports.firstOrNull { it.uid == id } }
        val decision = microphoneRoute.resolve(available.map { it.uid }, actual, preferredMicrophone,
            inTake = take.retainMicrophone, now = uptime())
        microphoneRouteCheck?.let { main.removeCallbacks(it) }
        decision.recheckAfter?.let { delay ->
            val check = Runnable {
                if (!wantsPreview) return@Runnable
                if (recoveringMic || (movie.hasDestination && !movie.usesOwnAudio)) refreshMicrophones() else recoverMicrophone()
            }
            microphoneRouteCheck = check
            main.postDelayed(check, (delay * 1000).toLong())
        }
        decision.request?.let { id ->
            if (available.any { it.uid == id }) {
                preferredMicrophone = id
                // setPreferredDevice through the engine; resolve() confirms the routed
                // input. With the own writer this also applies during a segment (the
                // writer fills the reopening gap with silence). On the CameraX fallback
                // path CameraX opens the default route for its segment.
                if (movie.usesOwnAudio && movie.hasDestination) startMicrophoneEngine()
                else if (!movie.hasDestination && microphoneEngine.isRunning) startMicrophoneEngine()
            }
        }
        val actualName = (actualPort?.portName ?: actual?.let { known[it] })
        decision.failed?.let { id ->
            val desired = known[id] ?: "the selected microphone"
            warning = "Could not switch to $desired." + (actualName?.let { " Using $it." } ?: "")
        }
        val disconnected = decision.disconnected
        if (disconnected != null && actualName != null) {
            val message = (known[disconnected] ?: "Microphone") + " disconnected. Using $actualName."
            movie.recordEvent("microphoneFallback", message)
            warning = message
            Log.i(TAG, "Microphone fallback: $message")
        }
        if (movie.hasDestination && actual != loggedRoute) {
            loggedRoute = actual
            Log.i(TAG, "Recording input routed to ${actualName ?: "none"} (${actual ?: "-"})")
        } else if (!movie.hasDestination) loggedRoute = null
        microphones = available.map { MicrophoneChoice(it.uid, it.portName) }
        microphonePriority = preferences.map { MicrophoneChoice(it, known[it] ?: "Disconnected microphone") }
        microphoneID = actual ?: ""
        microphoneName = actualName ?: "Microphone unavailable"
    }

    // MARK: Readings

    private fun startMeter() {
        if (meterJob != null) return
        meterJob = scope.launch {
            var ticks = 0
            while (true) {
                updateReadings()
                if (++ticks % 20 == 0 && configured && wantsPreview && movie.hasDestination) refreshMicrophones()
                delay(50)
            }
        }
    }

    private fun updateReadings() {
        val now = uptime()
        if (zoomTransition == null && camera != null && cameraID.isNotEmpty() && selectedZoom != lastSavedZoom) {
            lastSavedZoom = selectedZoom
            memory.saveZoom(cameraID, selectedZoom)
        }
        val reading = meterValues.read(now)
        if (take.captureActive && !take.requestedStop && movie.isRecording) {
            val audio = movie.lastAudioStats
            val silenced = if (movie.usesOwnAudio) reading.audioAge > 4 else audio != null && (audio.audioState == AudioStats.AUDIO_STATE_SOURCE_SILENCED ||
                audio.audioState == AudioStats.AUDIO_STATE_SOURCE_ERROR || audio.audioState == AudioStats.AUDIO_STATE_ENCODER_ERROR)
            if (movie.usesOwnAudio && reading.audioAge > 1 && now - lastMicrophoneRestart > 1.5 && !recoveringMic) {
                // The engine stalled or died (route lost): reopen it on the current choice;
                // the writer keeps the picture in sync with silence and an audioGap mark.
                lastMicrophoneRestart = now
                Log.w(TAG, "No microphone audio for %.1f s; reopening the input".format(Locale.US, reading.audioAge))
                microphoneEngine.stop()
                recoverMicrophone()
            }
            if (silenced && reading.audioAge > 1 && now - captureRequestedAt > (if (movie.usesOwnAudio) 4 else 1)) {
                interruptTake("No microphone audio is arriving. Recording paused; check the microphone before resuming.")
            } else if (now - lastStorageCheck > 5) {
                lastStorageCheck = now
                val file = segment?.file
                if (file != null && now > storageSample.first) {
                    val bytes = file.length()
                    val rate = max(0L, bytes - storageSample.second) / (now - storageSample.first)
                    bytesPerSecond = max(bytesPerSecond, rate)
                    storageSample = now to bytes
                }
                val directory = take.directory
                if (directory != null && freeBytes(directory) < 32_000_000 + 15 * bytesPerSecond) {
                    interruptTake("Recording paused to finish saving safely. Free some storage before resuming.")
                }
            }
        }
        val locked = manualFocus
        readings.update(zoom = selectedZoom, focusLocked = locked, lensPosition = currentLensPosition(),
            level = reading.level, audioArriving = reading.arriving, audioClipping = reading.clipping)
    }

    private fun freeBytes(directory: File): Long = runCatching { StatFs(directory.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    private fun uptime(): Double = SystemClock.uptimeMillis() / 1000.0

    companion object {
        private const val TAG = "YouRollCapture"

        fun clock(seconds: Double): String {
            val value = max(0, if (seconds.isFinite()) seconds.toInt() else 0)
            return String.format(Locale.US, "%02d:%02d", value / 60, value % 60)
        }
    }
}

/** CameraPreview (UIViewRepresentable in Swift): the live picture inside the zoom viewport. */
@ExperimentalCamera2Interop
@Composable
fun CameraPreview(camera: CaptureController, fill: Boolean, pinchSeconds: Double, modifier: Modifier = Modifier) {
    RecorderZoomViewport(camera = camera, seconds = pinchSeconds, fill = fill, modifier = modifier)
}
