// Port of YouRec/CaptureSegmentWriter.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
//
// Design note (November 2026): how a segment is written on Android.
//
// iOS writes each segment with an AVAssetWriter fed by a video data output and an
// independent microphone engine (LiveMicrophone), on one host clock. The first
// Android port used CameraX `Recorder` with `asPersistentRecording()`. That left
// three gaps: CameraX opens its own AudioRecord on the system's default input (no
// device selection in 1.6; `Recorder.Builder.setAudioSource` picks a source preset
// only), its audio format follows the device's EncoderProfiles (AMR-NB 8 kHz on
// the emulator), and a camera switch leaves a timestamp gap in the file.
//
// Options considered:
//  (a) Keep the CameraX Recorder for video and record audio separately, then mux.
//      Remuxing after every Pause copies the whole segment (minutes of 4K), the A/V
//      offset is unknown (Recorder does not expose its first frame time) and a
//      crash loses the audio. Recorder's muxer hook (`setMuxerFactory`) is internal.
//  (b) CameraX Preview + CameraEffect/SurfaceProcessor feeding our own encoder.
//      Preview streams are capped near display size, so 4K would need tricks.
//  (c) Chosen: keep CameraX's `VideoCapture` use case (quality selection, 4K,
//      stabilisation, mirroring, target rotation and frame rate all still apply),
//      but give it our own `VideoOutput` instead of a Recorder. The output borrows
//      a Recorder's MediaSpec/capabilities for resolution choice only, and receives
//      the camera Surface plus its Timebase. Frames go through one OpenGL stage
//      (CaptureGL.kt) into a MediaCodec H.264 surface encoder; the microphone
//      engine (LiveMicrophone, `AudioRecord.setPreferredDevice`) feeds AAC-LC
//      48 kHz through the ported CaptureAudioNormalizer; MediaMuxer writes the MP4.
//      Video and audio share CLOCK_MONOTONIC, the segment starts at the first video
//      frame, Stop pads audio with silence and extends the last picture to the same
//      end, as iOS does. The GL canvas keeps the last picture, so a camera change
//      writes held frames instead of a gap, and a camera with another sensor
//      rotation is turned into the segment's canvas.
//      The VideoOutput overrides use CameraX APIs marked @RestrictTo (MediaSpec,
//      Timebase). If they change, or EGL/MediaCodec cannot start, the writer falls
//      back to the CameraX Recorder path below for the rest of the session.
//
// Not ported (see TODO.md): fragmented MP4 output, HDR/Log formats.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.salvozappa.youroll

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.MirrorMode
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.Observable
import androidx.camera.core.impl.Timebase
import androidx.camera.video.AudioStats
import androidx.camera.video.EncoderProfilesResolver
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaSpec
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.RecordingStats
import androidx.camera.video.VideoCapabilities
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoOutput
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Android addition: whether a camera frame timestamp is on the elapsed-realtime
 * (boot-time) clock rather than CLOCK_MONOTONIC. CameraX's reported `Timebase` is only
 * a hint: some phones stamp REALTIME frames while CameraX says UPTIME. Pick the
 * clock the frame is closest to, as CameraX's own `VideoTimebaseConverter` does. The
 * two clocks differ by the time the device has spent asleep since boot.
 */
fun frameClockIsRealtime(frameNanos: Long, monotonicNanos: Long, realtimeNanos: Long): Boolean =
    abs(frameNanos - realtimeNanos) < abs(frameNanos - monotonicNanos)

/**
 * The video clock of one segment (pure, JVM-tested). Times are CLOCK_MONOTONIC
 * nanoseconds. The segment starts at its first camera frame; written timestamps
 * only increase. When the camera delivers nothing for longer than [holdAfter]
 * (a camera change), [heldFrame] repeats the last picture at [frame] cadence on
 * the camera's own latency, so the file keeps a steady frame rate.
 */
class CaptureVideoTiming(val frame: Long = 33_333_333L, val holdAfter: Long = 100_000_000L) {
    data class Decision(val write: Boolean, val started: Boolean = false, val gap: Double? = null, val gapAt: Double = 0.0)

    var start: Long? = null; private set
    /** Last written timestamp, camera or held. */
    var lastVideo: Long? = null; private set
    /** Last frame the camera delivered. */
    var lastCamera: Long? = null; private set
    var heldFrames = 0; private set
    private var latency = 0L

    val lastVideoEnd: Long? get() = lastVideo?.plus(frame)

    /** A camera frame stamped [time] arrived at [arrival]. Frames at or after [end] (Stop) are not written. */
    fun camera(time: Long, arrival: Long, end: Long? = null): Decision {
        latency = max(0L, arrival - time)
        if (end != null && time >= end) return Decision(false)
        val first = start == null
        if (first) start = time
        val previous = lastCamera
        lastCamera = max(previous ?: time, time)
        val gap = if (previous != null && time - previous > holdAfter) (time - previous) / 1e9 else null
        val write = lastVideo?.let { time > it } ?: true
        if (write) lastVideo = time
        return Decision(write, first, gap, ((previous ?: time) - start!!) / 1e9)
    }

    /** The timestamp of a held frame to write now, if the camera has stalled. */
    fun heldFrame(now: Long, end: Long? = null): Long? {
        val last = lastVideo ?: return null
        val camera = lastCamera ?: return null
        val due = now - latency
        if (due - camera < holdAfter) return null
        val next = last + frame
        if (due < next || (end != null && next >= end)) return null
        lastVideo = next; heldFrames += 1
        return next
    }

    /** Stop: video and audio both end at the later of the last picture's end and the Stop request. */
    fun end(stop: Long): Long = max(lastVideoEnd ?: stop, stop)

    /**
     * Where the final held picture goes so the video track lasts until [end]
     * (Swift appends `lastPixel` timed up to `end`). MediaMuxer gives the last
     * sample the previous sample's duration, so the picture goes halfway between
     * the last frame and [end]: the track then ends exactly at [end].
     */
    fun finalFrame(end: Long): Long? {
        val last = lastVideo ?: return null
        if (end - last < 2_000_000L) return null
        return (last + end) / 2
    }
}

/**
 * Owns encoding independently of camera reconfiguration. A camera switch only
 * rebinds [videoCapture]; the segment keeps recording (audio included) while the
 * new camera starts. See the design note at the top of this file.
 */
@SuppressLint("RestrictedApi")
class CaptureSegmentWriter(private val context: Context) {
    /** The custom path could not start; the caller may switch to the CameraX recorder. */
    class CustomPathUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
    /** Android addition: Record pressed before the (re)bound camera delivered its first frame. The controller retries briefly. */
    class CameraNotReady : IllegalStateException("The camera is not delivering frames yet. Try Record again.")

    var onStart: ((File) -> Unit)? = null
    /** CameraX fallback path only: recording statistics for the meter. */
    var onStatus: ((RecordingStats) -> Unit)? = null
    /** file, recorded seconds, error text (null on a clean finish). */
    var onFinish: ((File, Double, String?) -> Unit)? = null
    var onEmpty: ((File) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { prettyPrint = false }
    private var pipeline: SegmentPipeline? = try { SegmentPipeline() } catch (e: Exception) {
        Log.w(TAG, "Own video pipeline unavailable, using the CameraX recorder", e); null
    }
    private var recorder: Recorder? = null
    var videoCapture: VideoCapture<*>; private set
    private var recording: Recording? = null
    @Volatile private var destination: File? = null
    @Volatile private var finishing = false
    private val eventsLock = Any()
    private var events = mutableListOf<CaptureSegmentEvent>()
    @Volatile private var recordedNanos = 0L
    // CameraX fallback only (Swift stamps a bookmark with the host time of the tap): CameraX reports
    // the encoded duration, which trails the tap by the encoder pipeline and Status delivery, so the
    // take's start is estimated on the elapsed-realtime clock as the earliest `receipt − duration`.
    // The own pipeline stamps from its own clock.
    private var startEstimateNanos = Long.MAX_VALUE
    private var holdStarted: Long? = null
    private var holdAt = 0.0
    private var settings = Triple(CaptureQuality.hd1080, false, false)
    var lastAudioStats: AudioStats? = null; private set

    /** True when this writer records the microphone engine's audio (our own pipeline). */
    val usesOwnAudio: Boolean get() = pipeline != null
    val isRecording: Boolean get() = destination != null && !finishing
    val hasDestination: Boolean get() = destination != null
    val recordedSeconds: Double get() = recordedNanos / 1e9
    val currentEvents: List<CaptureSegmentEvent> get() = synchronized(eventsLock) { events.toList() }

    init { videoCapture = build(CaptureQuality.hd1080, mirrorFront = false, stabilize = false) }

    private fun build(quality: CaptureQuality, mirrorFront: Boolean, stabilize: Boolean): VideoCapture<*> {
        settings = Triple(quality, mirrorFront, stabilize)
        val profile = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality.cameraXQuality, FallbackStrategy.lowerQualityOrHigherThan(quality.cameraXQuality)))
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .build()
        val own = pipeline
        recorder = if (own == null) profile else null
        val builder = if (own != null) VideoCapture.Builder<VideoOutput>(PipelineOutput(profile, own)) else VideoCapture.Builder(profile)
        return builder
            .setMirrorMode(if (mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .setTargetFrameRate(Range(30, 30))
            .setVideoStabilizationEnabled(stabilize)
            .build()
    }

    /** Replace the use case for a new quality/mirroring/stabilisation. Only between takes. */
    fun configure(quality: CaptureQuality, mirrorFront: Boolean, stabilize: Boolean): Boolean {
        if (destination != null) return false
        videoCapture = build(quality, mirrorFront, stabilize)
        return true
    }

    /**
     * Use the CameraX recorder for the rest of the session. The caller must bind
     * the new [videoCapture]. Returns false during a segment.
     */
    fun useSystemRecorder(reason: String): Boolean {
        if (destination != null) return false
        Log.w(TAG, "Switching to the CameraX recorder: $reason")
        pipeline?.release(); pipeline = null
        videoCapture = build(settings.first, settings.second, settings.third)
        return true
    }

    fun release() { pipeline?.release(); pipeline = null }

    /**
     * Start a segment. With the own pipeline, [channels] is the AAC channel count
     * (the microphone's, fixed for the segment). Throws [CustomPathUnavailable] when
     * the encoders cannot start, IllegalStateException when the camera is not streaming.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(file: File, audio: Boolean, channels: Int = 2) {
        if (destination != null || finishing) return
        synchronized(eventsLock) { events = mutableListOf() }
        recordedNanos = 0; holdStarted = null; lastAudioStats = null
        startEstimateNanos = Long.MAX_VALUE
        val own = pipeline
        if (own != null) {
            destination = file
            try { own.start(file, channels.coerceIn(1, 2)) } catch (e: Exception) { destination = null; throw e }
            return
        }
        val recorder = recorder ?: throw IllegalStateException("The recorder is not configured.")
        destination = file
        val pending = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .let { if (audio) it.withAudioEnabled() else it }
            .asPersistentRecording()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event -> handle(file, event) }
    }

    /** Microphone PCM from any thread (own pipeline only). */
    fun appendAudio(block: CapturedAudio) { pipeline?.audio(block) }

    private fun handle(file: File, event: VideoRecordEvent) {
        if (destination != file) return
        recordedNanos = event.recordingStats.recordedDurationNanos
        if (event is VideoRecordEvent.Resume) startEstimateNanos = Long.MAX_VALUE
        if (event is VideoRecordEvent.Status && recordedNanos > 0) {
            startEstimateNanos = minOf(startEstimateNanos, SystemClock.elapsedRealtimeNanos() - recordedNanos)
        }
        lastAudioStats = event.recordingStats.audioStats
        when (event) {
            is VideoRecordEvent.Start -> onStart?.invoke(file)
            is VideoRecordEvent.Status -> onStatus?.invoke(event.recordingStats)
            is VideoRecordEvent.Finalize -> finalize(file, event)
            else -> {}
        }
    }

    private fun finalize(file: File, event: VideoRecordEvent.Finalize) {
        val error = if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) null else errorMessage(event.error, event.cause)
        val noData = event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
        completed(file, event.recordingStats.recordedDurationNanos / 1e9, if (noData) "empty" else error, empty = noData)
    }

    /** Main thread: a segment closed (either path). */
    private fun completed(file: File, seconds: Double, error: String?, empty: Boolean = false) {
        if (destination != file) return
        recording = null; destination = null; finishing = false
        val playable = !empty && file.exists() && file.length() > 0
        if (!playable) {
            file.delete(); eventsFile(file).delete()
            onEmpty?.invoke(file)
            return
        }
        onFinish?.invoke(file, seconds, error)
    }

    fun stopRecording() {
        if (destination == null || finishing) return
        val own = pipeline
        if (own != null) {
            finishing = true
            own.stop(System.nanoTime())
            return
        }
        val active = recording ?: return
        finishing = true
        holdVideo(false)
        active.stop()
    }

    fun recordEvent(kind: String, detail: String) {
        val file = destination ?: return
        saveEvent(file, kind, detail, recordedSeconds)
    }

    fun bookmark() {
        val file = destination ?: return
        if (finishing) return
        val encoded = recordedSeconds
        val tapped = if (pipeline != null || startEstimateNanos == Long.MAX_VALUE) encoded
            else (SystemClock.elapsedRealtimeNanos() - startEstimateNanos) / 1e9
        saveEvent(file, "bookmark", "Bookmark", maxOf(encoded, tapped))
    }

    /**
     * Camera changes pause video delivery while audio keeps recording. The own
     * pipeline measures the hold from frame timestamps and writes held frames;
     * on the CameraX path the hold is timed here, and the container keeps the gap.
     */
    fun holdVideo(hold: Boolean) {
        pipeline?.let { if (hold && destination != null) it.expectHold(); return }
        if (hold) {
            if (destination != null && holdStarted == null) { holdStarted = SystemClock.elapsedRealtime(); holdAt = recordedSeconds }
            return
        }
        val started = holdStarted ?: return
        holdStarted = null
        val gap = (SystemClock.elapsedRealtime() - started) / 1000.0
        val file = destination ?: return
        if (gap > 0.1) saveEvent(file, "videoHold", String.format(java.util.Locale.US, "Image held for %.2f s", gap), holdAt)
    }

    /** Any thread. Events of a finished segment are ignored. */
    private fun saveEvent(file: File, kind: String, detail: String, time: Double) {
        synchronized(eventsLock) {
            if (destination != file) return
            events.add(CaptureSegmentEvent(time = maxOf(0.0, time), kind = kind, detail = detail))
            try {
                val target = eventsFile(file)
                val temp = File(target.path + ".tmp")
                temp.writeText(json.encodeToString(events.toList()))
                temp.renameTo(target)
            } catch (_: Exception) { }
        }
    }

    // MARK: Own pipeline

    /**
     * CameraX's VideoCapture asks this output for a Surface per camera binding.
     * Resolution choice is the Recorder's ([profile]); frames go to [pipeline].
     */
    private class PipelineOutput(private val profile: Recorder, private val pipeline: SegmentPipeline) : VideoOutput {
        override fun onSurfaceRequested(request: SurfaceRequest) = pipeline.accept(request, Timebase.UPTIME)
        override fun onSurfaceRequested(request: SurfaceRequest, timebase: Timebase, hasGlProcessing: Boolean) =
            pipeline.accept(request, timebase)
        override fun getMediaSpec(): Observable<MediaSpec> = profile.mediaSpec
        override fun getMediaCapabilities(cameraInfo: CameraInfo, sessionType: Int): VideoCapabilities =
            profile.getMediaCapabilities(cameraInfo, sessionType)
        override fun getEncoderProfilesResolver(cameraInfo: CameraInfo, sessionType: Int): EncoderProfilesResolver =
            profile.getEncoderProfilesResolver(cameraInfo, sessionType)
        override fun isQualitySelectorDefault(): Boolean = profile.isQualitySelectorDefault
    }

    /** One camera Surface; CameraX replaces it on every camera change. */
    private class Source(val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface, val width: Int, val height: Int, val reported: Timebase) {
        /** Measured from the first frame ([frameClockIsRealtime]); null until then. */
        var realtime: Boolean? = null
        @Volatile var info: SurfaceRequest.TransformationInfo? = null
        var released = false
        val transform = FloatArray(16)
        val crop: Rect get() = info?.cropRect?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
        val rotation: Int get() = info?.rotationDegrees ?: 0
        val mirror: Boolean get() = info?.isMirroring == true
    }

    private class Sample(val track: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private class Segment(
        val file: File, val width: Int, val height: Int, val rotation: Int, val channels: Int,
        val video: MediaCodec, val input: Surface, val surface: EGLSurface, val audio: MediaCodec, val muxer: MediaMuxer,
    ) {
        val timing = CaptureVideoTiming()
        var normalizer: CaptureAudioNormalizer? = null
        val pendingAudio = ArrayDeque<CapturedAudio>()
        val readyAudio = ArrayDeque<NormalizedAudio>()
        var readyOffset = 0
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        val pendingSamples = ArrayList<Sample>()
        var finishing = false
        var closing = false
        var requestedEnd = Long.MAX_VALUE
        var paddingDone = false
        var failure: String? = null
        var lastAudioEnd = Long.MIN_VALUE
        var lastAudioPts = 0L
        var reportedSilence = 0L
        var audioBlocks = 0L
        var droppedBlocks = 0L
        var videoDone = false
        var audioDone = false
        var drainScheduled = false
        var cameraFrames = 0
        var expectedHoldUntil = 0L
        var audioOutFrames = 0L
        val audioDelay = AacDelay.samples.toLong()
        var placementKey: Any? = null
        var placement: CameraPlacement? = null
    }

    /** Owns EGL, the camera Surfaces and the encoders, all on one media thread (Swift `queue`). */
    private inner class SegmentPipeline {
        private val thread = HandlerThread("youroll-media").apply { start() }
        private val handler = Handler(thread.looper)
        private val executor = Executor { handler.post(it) }
        private lateinit var egl: CaptureEGL
        private lateinit var renderer: CaptureFrameRenderer
        private val sources = mutableListOf<Source>()
        private var current: Source? = null
        private var segment: Segment? = null
        private var released = false
        private val tick = object : Runnable {
            override fun run() { holdTick(); if (segment != null) handler.postDelayed(this, 15) }
        }

        init {
            try {
                onMedia {
                    egl = CaptureEGL()
                    renderer = CaptureFrameRenderer()
                }
            } catch (e: Exception) { thread.quitSafely(); throw e }
            handler.post { AacDelay.calibrate() }
        }

        private fun <T> onMedia(work: () -> T): T {
            if (Looper.myLooper() == thread.looper) return work()
            val task = FutureTask(work)
            handler.post(task)
            return try { task.get(4, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        }

        fun accept(request: SurfaceRequest, timebase: Timebase) {
            handler.post {
                if (released) { request.willNotProvideSurface(); return@post }
                val texture = renderer.createCameraTexture()
                val surfaceTexture = SurfaceTexture(texture)
                val size = request.resolution
                surfaceTexture.setDefaultBufferSize(size.width, size.height)
                val source = Source(texture, surfaceTexture, Surface(surfaceTexture), size.width, size.height, timebase)
                sources.add(source)
                request.setTransformationInfoListener(executor) { source.info = it }
                surfaceTexture.setOnFrameAvailableListener({ frame(source) }, handler)
                Log.i(TAG, "Camera surface ${size.width}x${size.height}, timebase $timebase")
                request.provideSurface(source.surface, executor) { releaseSource(source) }
            }
        }

        private fun releaseSource(source: Source) {
            if (source.released) return
            source.released = true
            sources.remove(source)
            if (current === source) current = null
            try { egl.makeCurrent(null) } catch (_: Exception) { }
            source.surfaceTexture.setOnFrameAvailableListener(null)
            source.surfaceTexture.release()
            source.surface.release()
            renderer.deleteTexture(source.texture)
        }

        /** Media thread: one camera frame. */
        private fun frame(source: Source) {
            if (source.released || released) return
            val segment = segment
            try {
                if (segment == null || segment.closing) egl.makeCurrent(null) else egl.makeCurrent(segment.surface)
                source.surfaceTexture.updateTexImage()
            } catch (e: Exception) { Log.w(TAG, "Camera frame dropped", e); return }
            current = source
            if (segment == null || segment.closing || segment.failure != null) return
            val arrival = System.nanoTime()
            var time = source.surfaceTexture.timestamp
            val realtime = source.realtime ?: run {
                val monotonic = System.nanoTime(); val boot = SystemClock.elapsedRealtimeNanos()
                frameClockIsRealtime(time, monotonic, boot).also {
                    source.realtime = it
                    val measured = if (it) "REALTIME" else "UPTIME"
                    val message = "Camera frame clock: $measured (CameraX reported ${source.reported}); frame %.3f s, monotonic %.3f s, realtime %.3f s, asleep since boot %.1f s"
                        .format(java.util.Locale.US, time / 1e9, monotonic / 1e9, boot / 1e9, (boot - monotonic) / 1e9)
                    if (measured != source.reported.name) Log.w(TAG, message) else Log.i(TAG, message)
                }
            }
            if (realtime) time -= SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            val decision = segment.timing.camera(time, arrival, if (segment.finishing) segment.requestedEnd else null)
            val start = segment.timing.start ?: return
            if (decision.started) {
                segment.normalizer = CaptureAudioNormalizer(start, segment.channels)
                val file = segment.file
                main.post { if (destination == file) onStart?.invoke(file) }
                handler.post(tick)
            }
            decision.gap?.let { gap ->
                // Measured from the camera's own timestamps, as iOS does. A slow
                // device (the emulator) delivers irregular frames, so a gap outside a
                // camera change is marked only when it is clearly visible.
                val expected = arrival < segment.expectedHoldUntil
                if (expected || gap > UNEXPECTED_HOLD) {
                    saveEvent(segment.file, "videoHold", String.format(java.util.Locale.US, "Image held for %.2f s", gap), decision.gapAt)
                }
                if (expected) segment.expectedHoldUntil = 0
            }
            if (!decision.write) return
            try {
                source.surfaceTexture.getTransformMatrix(source.transform)
                val key = listOf(source, source.crop, source.rotation, source.mirror)
                if (segment.placementKey != key) {
                    segment.placementKey = key
                    segment.placement = CameraPlacement.of(source.crop, source.width, source.height, source.rotation,
                        segment.rotation, source.mirror, segment.width, segment.height)
                }
                renderer.drawCamera(source.texture, source.transform, segment.placement!!)
                renderer.drawCanvas()
                egl.present(segment.surface, time - start)
                segment.cameraFrames += 1
                recordedNanos = time - start
                drain(segment)
            } catch (e: Exception) { fail(segment, "The video encoder failed.", e) }
        }

        /** Main thread: a camera change starts; the next frame gap is the switch's hold (Swift `expectedVideoHold`). */
        fun expectHold() {
            val until = System.nanoTime() + 5_000_000_000L
            handler.post { segment?.expectedHoldUntil = until }
        }

        /** Repeat the last picture while the camera is changing (a few per tick when the thread fell behind). */
        private fun holdTick() {
            val segment = segment ?: return
            if (segment.closing || segment.failure != null) return
            val start = segment.timing.start ?: return
            repeat(4) {
                val held = segment.timing.heldFrame(System.nanoTime(), if (segment.finishing) segment.requestedEnd else null) ?: return
                try {
                    egl.makeCurrent(segment.surface)
                    renderer.drawCanvas()
                    egl.present(segment.surface, held - start)
                    recordedNanos = held - start
                    drain(segment)
                } catch (e: Exception) { fail(segment, "The video encoder failed.", e); return }
            }
        }

        fun start(file: File, channels: Int) = onMedia {
            if (released) throw CustomPathUnavailable("The video pipeline was released.")
            val source = current ?: throw CameraNotReady()
            val (width, height) = CaptureCanvas.canvasSize(source.crop)
            if (width < 16 || height < 16) throw CameraNotReady()
            var video: MediaCodec? = null
            var audio: MediaCodec? = null
            var input: Surface? = null
            var surface: EGLSurface? = null
            var muxer: MediaMuxer? = null
            try {
                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = video.createInputSurface()
                video.start()
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, CaptureAudioNormalizer.RATE, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 192_000 else 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096 * 2 * channels)
                }
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audio.start()
                surface = egl.windowSurface(input)
                egl.makeCurrent(surface)
                renderer.ensureCanvas(width, height)
                muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer.setOrientationHint(source.rotation)
                segment = Segment(file, width, height, source.rotation, channels, video, input, surface, audio, muxer)
                Log.i(TAG, "Segment ${file.name}: ${width}x$height H.264 (${video.name}), rotation ${source.rotation}, " +
                    "AAC-LC 48 kHz ${channels}ch (${audio.name})")
            } catch (e: Exception) {
                runCatching { surface?.let { egl.releaseSurface(it) } }
                runCatching { video?.stop() }; runCatching { video?.release() }; runCatching { input?.release() }
                runCatching { audio?.stop() }; runCatching { audio?.release() }
                runCatching { muxer?.release() }
                file.delete()
                throw CustomPathUnavailable(e.localizedMessage ?: "The encoders could not start.", e)
            }
        }

        /** Any thread: microphone PCM. Blocks before the first video frame wait (Swift `pendingAudio`). */
        fun audio(block: CapturedAudio) {
            handler.post {
                val segment = segment ?: return@post
                if (segment.closing || segment.failure != null) return@post
                segment.lastAudioEnd = max(segment.lastAudioEnd, block.timeNanos + Math.round(block.frames * 1e9 / block.sampleRate))
                segment.pendingAudio.addLast(block)
                // Before the first video frame only the last second can matter.
                if (segment.normalizer == null) while (segment.pendingAudio.size > 50) segment.pendingAudio.removeFirst()
                if (segment.pendingAudio.size > 200) {
                    fail(segment, "The encoder could not keep up with the microphone. Recording was paused; completed footage was preserved.")
                    return@post
                }
                feedAudio(segment)
            }
        }

        private fun feedAudio(segment: Segment) {
            val normalizer = segment.normalizer ?: return
            try {
                while (segment.pendingAudio.isNotEmpty() || segment.readyAudio.isNotEmpty()) {
                    if (segment.readyAudio.isEmpty()) {
                        val block = segment.pendingAudio.removeFirst()
                        if (segment.audioBlocks++ == 0L) segment.timing.start?.let { start ->
                            // A large offset here means the microphone and camera clocks disagree.
                            Log.i(TAG, "First microphone block for ${segment.file.name}: %+.3f s from the first frame, %d frames, %d ch"
                                .format(java.util.Locale.US, (block.timeNanos - start) / 1e9, block.frames, block.channels))
                        }
                        val beforeSilence = normalizer.insertedSilence
                        val beforeFrame = normalizer.nextFrame
                        segment.readyAudio.addAll(normalizer.process(block, if (segment.finishing) segment.requestedEnd else null))
                        val missing = normalizer.insertedSilence - segment.reportedSilence
                        if (missing > CaptureAudioNormalizer.RATE / 10 && !segment.finishing) {
                            val at = (normalizer.nextFrame - missing).toDouble() / CaptureAudioNormalizer.RATE
                            saveEvent(segment.file, "audioGap",
                                String.format(java.util.Locale.US, "Microphone audio missing for %.2f s; silence keeps the picture in sync.",
                                    missing.toDouble() / CaptureAudioNormalizer.RATE), at)
                        }
                        segment.reportedSilence = normalizer.insertedSilence
                        // No microphone samples kept from this block: before the segment start or after Stop.
                        if (normalizer.nextFrame - beforeFrame == normalizer.insertedSilence - beforeSilence && segment.droppedBlocks++ % 50 == 0L) {
                            Log.w(TAG, "Microphone block %d dropped (${segment.droppedBlocks} so far) for ${segment.file.name}: at %+.3f s, writer at %.3f s"
                                .format(java.util.Locale.US, segment.audioBlocks, (block.timeNanos - (segment.timing.start ?: 0)) / 1e9,
                                    normalizer.nextFrame.toDouble() / CaptureAudioNormalizer.RATE))
                        }
                        continue
                    }
                    if (!queueAudio(segment)) break
                }
                drain(segment)
            } catch (e: Exception) { fail(segment, "Audio could not be encoded.", e); return }
            if ((segment.pendingAudio.isNotEmpty() || segment.readyAudio.isNotEmpty()) && !segment.drainScheduled) {
                segment.drainScheduled = true
                handler.postDelayed({ segment.drainScheduled = false; if (this.segment === segment) feedAudio(segment) }, 5)
            }
        }

        /** Copy ready PCM into one encoder input buffer. False when the encoder has no free buffer. */
        private fun queueAudio(segment: Segment): Boolean {
            val index = segment.audio.dequeueInputBuffer(0)
            if (index < 0) { drain(segment); return false }
            val buffer = segment.audio.getInputBuffer(index)!!
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder())
            val frameBytes = 2 * segment.channels
            val first = segment.readyAudio.first()
            val pts = (first.frame + segment.readyOffset) * 1_000_000L / CaptureAudioNormalizer.RATE
            var bytes = 0
            while (segment.readyAudio.isNotEmpty()) {
                val chunk = segment.readyAudio.first()
                val room = (buffer.remaining() / frameBytes)
                if (room == 0) break
                val count = min(room, chunk.frames - segment.readyOffset)
                val shorts = buffer.asShortBuffer()
                shorts.put(chunk.samples, segment.readyOffset * segment.channels, count * segment.channels)
                buffer.position(buffer.position() + count * frameBytes)
                bytes += count * frameBytes
                segment.readyOffset += count
                if (segment.readyOffset == chunk.frames) {
                    segment.readyAudio.removeFirst(); segment.readyOffset = 0
                    // Keep one input buffer on a contiguous run of frames.
                    if (segment.readyAudio.isNotEmpty() && segment.readyAudio.first().frame != chunk.frame + chunk.frames) break
                }
            }
            segment.lastAudioPts = pts
            segment.audio.queueInputBuffer(index, 0, bytes, pts, 0)
            return true
        }

        private fun drain(segment: Segment) {
            drainCodec(segment, segment.video, true, 0)
            drainCodec(segment, segment.audio, false, 0)
        }

        /** Move encoded samples to the muxer; [timeoutUs] > 0 waits for end of stream. */
        private fun drainCodec(segment: Segment, codec: MediaCodec, isVideo: Boolean, timeoutUs: Long) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (if (isVideo) segment.videoDone else segment.audioDone) return
                val index = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val track = segment.muxer.addTrack(codec.outputFormat)
                        Log.i(TAG, "Encoder output format: ${codec.outputFormat}")
                        if (isVideo) segment.videoTrack = track else segment.audioTrack = track
                        if (segment.videoTrack >= 0 && segment.audioTrack >= 0 && !segment.muxerStarted) {
                            segment.muxer.start(); segment.muxerStarted = true
                            for (sample in segment.pendingSamples) {
                                segment.muxer.writeSampleData(if (sample.track) segment.videoTrack else segment.audioTrack, sample.data, sample.info)
                            }
                            segment.pendingSamples.clear()
                        }
                    }
                    index >= 0 -> {
                        val data = codec.getOutputBuffer(index)!!
                        var keep = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0
                        if (keep && !isVideo) {
                            // The PCM stream is contiguous from segment frame 0. AAC frame k
                            // decodes to input frames from k * 1024 - delay: retime it and
                            // drop the priming frames, so the picture and sound line up
                            // (iOS gets the same from the edit list AVAssetWriter writes).
                            val shifted = segment.audioOutFrames++ * AAC_FRAME - segment.audioDelay
                            info.presentationTimeUs = shifted * 1_000_000L / CaptureAudioNormalizer.RATE
                            keep = shifted >= 0
                        }
                        if (keep) {
                            data.position(info.offset); data.limit(info.offset + info.size)
                            if (segment.muxerStarted) {
                                segment.muxer.writeSampleData(if (isVideo) segment.videoTrack else segment.audioTrack, data, info)
                            } else {
                                val copy = ByteBuffer.allocateDirect(info.size).put(data).also { it.flip() }
                                val copied = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                                segment.pendingSamples.add(Sample(isVideo, copy, copied))
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (isVideo) segment.videoDone = true else segment.audioDone = true
                            return
                        }
                    }
                }
            }
        }

        /** Main thread: Stop or Pause at CLOCK_MONOTONIC [stop]. */
        fun stop(stop: Long) {
            handler.post {
                val segment = segment ?: return@post
                if (segment.finishing) return@post
                segment.finishing = true
                if (segment.timing.start == null || segment.failure != null) { close(segment); return@post }
                segment.requestedEnd = segment.timing.end(stop)
                finishWhenDrained(segment, SystemClock.uptimeMillis() + 300)
            }
        }

        /**
         * An audio block may arrive after Stop with the final words before Stop, and
         * camera frames before Stop are still in flight. Wait a bounded time for both,
         * then pad the audio with silence and extend the picture to the same end.
         */
        private fun finishWhenDrained(segment: Segment, deadline: Long) {
            if (this.segment !== segment || segment.closing) return
            feedAudio(segment)
            val waitingForAudio = segment.lastAudioEnd < segment.requestedEnd
            val waitingForVideo = (segment.timing.lastCamera ?: Long.MIN_VALUE) + segment.timing.frame < segment.requestedEnd
            val waitingForEncoder = segment.pendingAudio.isNotEmpty() || segment.readyAudio.isNotEmpty()
            if (segment.failure == null && (waitingForAudio || waitingForVideo || waitingForEncoder) && SystemClock.uptimeMillis() < deadline) {
                handler.postDelayed({ finishWhenDrained(segment, deadline) }, 5)
                return
            }
            close(segment)
        }

        private fun close(segment: Segment) {
            if (segment.closing) return
            segment.closing = true
            handler.removeCallbacks(tick)
            val start = segment.timing.start
            var error = segment.failure
            val end = segment.requestedEnd
            if (start != null && error == null) {
                try {
                    val normalizer = segment.normalizer!!
                    // Blocks still queued were cut at the requested end; then silence.
                    while (segment.pendingAudio.isNotEmpty()) segment.readyAudio.addAll(normalizer.process(segment.pendingAudio.removeFirst(), end))
                    val previous = normalizer.insertedSilence
                    segment.readyAudio.addAll(normalizer.pad(end))
                    val padded = normalizer.insertedSilence - previous
                    if (padded > 2400) saveEvent(segment.file, "audioGap", "Microphone ended before Stop; silence preserves video timing.",
                        (end - start) / 1e9 - padded.toDouble() / CaptureAudioNormalizer.RATE)
                    if (padded > 24000) error = "The microphone ended before Stop. The remaining picture was saved with silence."
                    if (normalizer.clockCorrections != 0L || normalizer.resyncs > 0) {
                        saveEvent(segment.file, "audioClockCorrection", "Microphone clock aligned by ${normalizer.clockCorrections} samples; " +
                            "maximum offset ${normalizer.maximumClockOffset} samples at 48 kHz." +
                            (if (normalizer.resyncs > 0) " Re-aligned to its timestamps ${normalizer.resyncs} times." else ""), (end - start) / 1e9)
                    }
                    val deadline = SystemClock.uptimeMillis() + 2000
                    while (segment.readyAudio.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
                        if (!queueAudio(segment)) drainCodec(segment, segment.audio, false, 5_000)
                    }
                    if (segment.readyAudio.isNotEmpty()) error = "Audio could not be completely saved. The playable recording was preserved."
                    // Extend the last picture to the end (Swift appends `lastPixel` with timing up to `end`).
                    segment.timing.finalFrame(end)?.let { held ->
                        egl.makeCurrent(segment.surface)
                        renderer.drawCanvas()
                        egl.present(segment.surface, held - start)
                    }
                    segment.video.signalEndOfInputStream()
                    // A busy software encoder can need seconds to empty its queue; frames it
                    // still holds would be cut from the end of the segment.
                    val flushDeadline = SystemClock.uptimeMillis() + 8000
                    var index = -1
                    while (index < 0 && SystemClock.uptimeMillis() < flushDeadline) {
                        index = segment.audio.dequeueInputBuffer(5_000)
                        if (index < 0) drainCodec(segment, segment.audio, false, 0)
                    }
                    if (index >= 0) {
                        val eosPts = max(segment.lastAudioPts, (normalizer.nextFrame * 1_000_000L) / CaptureAudioNormalizer.RATE)
                        segment.audio.queueInputBuffer(index, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    while ((!segment.videoDone || !segment.audioDone) && SystemClock.uptimeMillis() < flushDeadline) {
                        drainCodec(segment, segment.video, true, 5_000)
                        drainCodec(segment, segment.audio, false, 5_000)
                    }
                    if (!segment.videoDone || !segment.audioDone) {
                        Log.w(TAG, "Encoders did not finish in time (video ${segment.videoDone}, audio ${segment.audioDone})")
                        error = error ?: "The end of the recording could not be completely saved."
                    }
                    Log.i(TAG, "Segment ${segment.file.name} closed: video ${segment.cameraFrames} camera + ${segment.timing.heldFrames} held frames, " +
                        "end %.3f s, audio %d frames (%.3f s), inserted silence %d, clock corrections %d, resyncs %d, max offset %d".format(java.util.Locale.US,
                            (end - start) / 1e9, normalizer.nextFrame, normalizer.nextFrame.toDouble() / CaptureAudioNormalizer.RATE,
                            normalizer.insertedSilence, normalizer.clockCorrections, normalizer.resyncs, normalizer.maximumClockOffset))
                } catch (e: Exception) {
                    Log.e(TAG, "Segment close failed", e)
                    error = error ?: "The recording could not be completely saved."
                }
            }
            if (error != null) Log.w(TAG, "Segment ${segment.file.name} finished with error: $error " +
                "(${segment.audioBlocks} microphone blocks received, ${segment.droppedBlocks} dropped, ${segment.cameraFrames} camera frames)")
            var playable = segment.muxerStarted
            if (segment.muxerStarted) {
                try { segment.muxer.stop() } catch (e: Exception) { Log.e(TAG, "Muxer stop failed", e); playable = false }
            }
            runCatching { segment.muxer.release() }
            runCatching { egl.releaseSurface(segment.surface) }
            runCatching { segment.video.stop() }; runCatching { segment.video.release() }; runCatching { segment.input.release() }
            runCatching { segment.audio.stop() }; runCatching { segment.audio.release() }
            this.segment = null
            val seconds = if (start != null && end != Long.MAX_VALUE) (end - start) / 1e9 else 0.0
            val file = segment.file
            main.post { completed(file, seconds, error, empty = !playable) }
        }

        private fun fail(segment: Segment, message: String, cause: Exception? = null) {
            Log.e(TAG, message, cause)
            if (segment.failure != null) return
            segment.failure = message
            // The controller pauses the take when a segment ends with an error.
            if (!segment.finishing) {
                segment.finishing = true
                segment.requestedEnd = segment.timing.lastVideoEnd ?: Long.MAX_VALUE
            }
            close(segment)
        }

        fun release() {
            handler.post {
                released = true
                segment?.let { fail(it, "The recorder was closed.") }
                for (source in sources.toList()) releaseSource(source)
                runCatching { renderer.release() }
                runCatching { egl.release() }
                thread.quitSafely()
            }
        }
    }

    companion object {
        private const val TAG = "YouRollCapture"
        private const val AAC_FRAME = 1024L
        /** Seconds; a longer gap outside a camera change is marked as a hold. */
        private const val UNEXPECTED_HOLD = 0.25

        fun eventsFile(movie: File): File = File(movie.parentFile, movie.nameWithoutExtension + ".events.json")

        fun errorMessage(code: Int, cause: Throwable?): String = when (code) {
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "The camera stopped delivering video."
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Storage is full."
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "The file size limit was reached."
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "The duration limit was reached."
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "The video encoder failed."
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "The recorder failed."
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "The recording file could not be created."
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "The recording was released unexpectedly."
            else -> cause?.localizedMessage ?: "The recording ended unexpectedly."
        }
    }
}

/**
 * The AAC encoder's priming delay, measured end to end (encoder plus decoder, as
 * a player hears it) with a tone burst through the device's own codecs. The
 * Android FDK AAC-LC encoder delays by 2048 samples; the measurement keeps other
 * encoders honest. Swift needs nothing similar: AVAssetWriter writes an edit list.
 */
internal object AacDelay {
    @Volatile var samples = 2048; private set
    @Volatile var measured = false; private set

    fun calibrate() {
        if (measured) return
        val result = runCatching { measure() }.onFailure { Log.w("YouRollCapture", "AAC delay not measured", it) }.getOrNull()
        if (result != null && result in 0..4096) samples = result
        measured = true
        Log.i("YouRollCapture", "AAC encoder delay: $samples samples" + if (result == null) " (default)" else " (measured)")
    }

    /** Index of the first loud sample after decoding minus where the burst was encoded. */
    private fun measure(): Int? {
        val rate = CaptureAudioNormalizer.RATE
        val onset = 4096
        val pcm = ShortArray(16 * 1024)
        for (i in 0 until 960) pcm[onset + i] = (16000 * kotlin.math.cos(2 * Math.PI * 1000 * i / rate)).toInt().toShort()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val frames = mutableListOf<ByteArray>()
        var csd: ByteBuffer? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            var fed = 0
            var eos = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + 2000
            while (SystemClock.uptimeMillis() < deadline) {
                if (!eos) {
                    val index = encoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.nativeOrder()) }
                        val count = min(1024, pcm.size - fed)
                        if (count <= 0) { encoder.queueInputBuffer(index, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else {
                            buffer.asShortBuffer().put(pcm, fed, count)
                            encoder.queueInputBuffer(index, 0, count * 2, fed * 1_000_000L / rate, 0)
                            fed += count
                        }
                    }
                }
                val out = encoder.dequeueOutputBuffer(info, 5_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) csd = encoder.outputFormat.getByteBuffer("csd-0")
                if (out >= 0) {
                    val data = encoder.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        data.position(info.offset); data.limit(info.offset + info.size)
                        frames.add(ByteArray(info.size).also { data.get(it) })
                    }
                    encoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { encoder.stop() }; encoder.release() }
        val config = csd ?: return null
        val decoded = ArrayList<Short>()
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            decoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setByteBuffer("csd-0", config)
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
            }, null, null, 0)
            decoder.start()
            var next = 0
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + 2000
            while (SystemClock.uptimeMillis() < deadline) {
                if (next <= frames.size) {
                    val index = decoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        if (next == frames.size) decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            decoder.getInputBuffer(index)!!.apply { clear(); put(frames[next]) }
                            decoder.queueInputBuffer(index, 0, frames[next].size, next * 1024L * 1_000_000L / rate, 0)
                        }
                        next += 1
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 5_000)
                if (out >= 0) {
                    val data = decoder.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    data.position(info.offset); data.limit(info.offset + info.size)
                    val shorts = data.asShortBuffer()
                    val channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    var i = 0
                    while (i + channels <= shorts.remaining()) { decoded.add(shorts.get(i)); i += channels }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { decoder.stop() }; decoder.release() }
        val first = decoded.indexOfFirst { kotlin.math.abs(it.toInt()) > 8000 }
        return if (first < 0) null else first - onset
    }
}

val CaptureQuality.cameraXQuality: Quality get() = if (this == CaptureQuality.hd1080) Quality.FHD else Quality.UHD
