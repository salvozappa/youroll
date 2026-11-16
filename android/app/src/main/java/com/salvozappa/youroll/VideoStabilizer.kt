// Port of YouRec/VideoStabilizer.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Motion analysis of a take and the smoothed correction plans built from it.
// iOS reads 8-bit luma with AVAssetReader and downsamples it with vImage;
// here MediaCodec decodes the encoded (unrotated) frames to YUV images, the
// JNI wrapper downsamples the Y plane, and the unchanged iOS C++ estimator
// measures each frame pair (StabilizationMotion.kt). Analyses are cached as
// JSON in the project's Cache/Stabilization folder (excluded from archives,
// rebuilt when missing), bounded to 64 MB; in memory, LRU caches hold up to
// three analyses and sixteen plans, each capped at 360,000 samples.
//
// Not ported (TODO.md): resumable checkpoints of an interrupted analysis
// (`Checkpoint`, only used by iOS's effect application receipts, which are
// not ported) and `StabilizationDiagnostics`. The cache key uses the
// Android source key (`SourceMediaCache.key`) as its fingerprint.
package com.salvozappa.youroll

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

object VideoStabilizer {
    /** Swift `VideoEffectError.analysisPending`: the analysis is not cached and must not be generated now. */
    class AnalysisPending : Exception("The motion analysis is not ready yet.")

    private val lock = Mutex()
    private val building = HashMap<File, CompletableDeferred<Unit>>()
    private val analyses = LinkedHashMap<File, StabilizationAnalysis>()
    private val plans = LinkedHashMap<String, StabilizationPlan>()

    private fun remember(analysis: StabilizationAnalysis, file: File) {
        analyses.remove(file); analyses[file] = analysis
        while (analyses.size > 1 && (analyses.size > 3 || analyses.values.sumOf { it.samples.size } > 360_000)) {
            analyses.remove(analyses.keys.first())
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Swift `analysis(source:clip:folder:generate:progress:)`. */
    suspend fun analysis(source: SourceMedia, clip: TimelineClip, folder: File, generate: Boolean = true,
                         progress: (Double) -> Unit = {}): StabilizationAnalysis =
        analysis(File(folder, source.filename), SourceMediaCache.key(source, File(folder, source.filename)), clip.stabilizationStart,
            clip.stabilizationEnd, folder, generate, progress)

    /** The analysis of [url] between [start] and [end] (source seconds), cached under [fingerprint]. */
    suspend fun analysis(url: File, fingerprint: String, start: Double, end: Double, folder: File, generate: Boolean = true,
                         progress: (Double) -> Unit = {}): StabilizationAnalysis {
        currentCoroutineContext().ensureActive()
        if (!url.exists()) throw ProjectError.missingMedia
        val identity = "${StabilizationAnalysis.version}|$fingerprint|$start|$end"
        val cache = File(ProjectCacheLocation.root(folder), "Stabilization")
        val file = File(cache, sha256(identity) + ".json")
        val done = CompletableDeferred<Unit>()
        while (true) {
            // Wait for another caller measuring the same range, then use its result.
            val wait = lock.withLock {
                building[file]?.let { pending -> if (!generate) throw AnalysisPending(); pending } ?: run {
                    analyses[file]?.let { remember(it, file); progress(1.0); return it }
                    null
                }
            }
            if (wait != null) { wait.await(); continue }
            val stored = withContext(Dispatchers.IO) {
                runCatching { SwiftJSON.decodeFromString(StabilizationAnalysis.serializer(), file.readText()) }.getOrNull()
            }
            if (stored != null && stored.valid && kotlin.math.abs(stored.duration - (end - start)) < 0.00001) {
                lock.withLock { remember(stored, file) }
                progress(1.0); return stored
            }
            if (!generate) throw AnalysisPending()
            val claimed = lock.withLock { if (building.containsKey(file)) false else { building[file] = done; true } }
            if (claimed) break
        }
        try {
            val job = currentCoroutineContext()[Job]
            val analysis = withContext(Dispatchers.Default) { measure(url, start, end - start, { job?.ensureActive() }, progress) }
            currentCoroutineContext().ensureActive()
            if (!analysis.valid) throw ProjectError.invalidMedia
            withContext(Dispatchers.IO) {
                cache.mkdirs()
                val temporary = File(cache, file.name + ".tmp")
                temporary.writeText(SwiftJSON.encodeToString(StabilizationAnalysis.serializer(), analysis))
                if (!temporary.renameTo(file)) throw IOException("The motion analysis could not be saved.")
                // These are small replaceable trajectories, never originals. Bound
                // growth from repeated trims without interfering with audio caches.
                val records = cache.listFiles { entry -> entry.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
                var bytes = records.sumOf { it.length() }
                val active = lock.withLock { building.keys.toSet() }
                for (old in records) {
                    if (bytes <= 64_000_000) break
                    if (old == file || old in active) continue
                    bytes -= old.length(); old.delete()
                }
            }
            lock.withLock { remember(analysis, file) }
            progress(1.0)
            return analysis
        } finally {
            lock.withLock { building.remove(file) }
            done.complete(Unit)
        }
    }

    /**
     * Swift `plan(source:clip:folder:progress:)`. [inspectionWindow] stands in
     * for Swift's `VideoEffectPreparation.inspectionWindow` task local: a tool
     * preview that must not generate the whole take's analysis measures just
     * its visible sample instead.
     */
    suspend fun plan(source: SourceMedia, clip: TimelineClip, folder: File, inspectionWindow: EffectPreviewWindow? = null,
                     progress: (Double) -> Unit = {}): StabilizationPlan? {
        currentCoroutineContext().ensureActive()
        val settings = clip.stabilization
        if (settings == null || settings.level == StabilizationLevel.off) return null
        if (settings.version != 1) throw ProjectError.invalidDocument
        val url = File(folder, source.filename)
        val fingerprint = SourceMediaCache.key(source, url)
        val key = "$fingerprint|${clip.stabilizationStart}|${clip.stabilizationEnd}|${settings.level.rawValue}"
        lock.withLock { plans.remove(key)?.let { plans[key] = it; progress(1.0); return it } }
        val measured = try {
            analysis(url, fingerprint, clip.stabilizationStart, clip.stabilizationEnd, folder, generate = inspectionWindow == null, progress = progress)
        } catch (pending: AnalysisPending) {
            val sample = inspectionWindow?.analysisClip(clip) ?: return null
            val measuredSample = analysis(url, fingerprint, sample.start, sample.end, folder, progress = progress)
            val job = currentCoroutineContext()[Job]
            val plan = withContext(Dispatchers.Default) { StabilizationPath.make(measuredSample, settings.level) { job?.ensureActive() } }
            val offset = sample.start - clip.stabilizationStart
            // Instructions still use the original clip clock. Only the sample
            // path was measured; never cache it under the full take's key.
            return plan.copy(samples = plan.samples.map { it.copy(time = it.time + offset) })
        }
        val job = currentCoroutineContext()[Job]
        val plan = withContext(Dispatchers.Default) { StabilizationPath.make(measured, settings.level) { job?.ensureActive() } }
        lock.withLock {
            plans.remove(key); plans[key] = plan
            while (plans.size > 1 && (plans.size > 16 || plans.values.sumOf { it.samples.size } > 360_000)) plans.remove(plans.keys.first())
        }
        return plan
    }

    /**
     * The correction a rendered picture applies for [reference] (a
     * `stabilizationReference`) of [source]; null when the plan is empty.
     */
    suspend fun correction(source: SourceMedia, reference: StabilizationSettings, folder: File, progress: (Double) -> Unit = {}): StabilizationCorrection? {
        val start = reference.referenceStart ?: return null
        val end = reference.referenceEnd ?: return null
        val clip = TimelineClip(sourceID = source.id, start = start, end = end, stabilization = reference)
        val plan = plan(source, clip, folder, progress = progress) ?: return null
        val rotation = withContext(Dispatchers.IO) { VideoGeometry.read(File(folder, source.filename))?.rotation ?: 0 }
        return StabilizationCorrection(plan, start, rotation)
    }

    /** A plan already in memory (no analysis, no I/O), for synchronous callers such as cover frames. */
    fun cachedPlan(source: SourceMedia, clip: TimelineClip, folder: File): StabilizationPlan? {
        val settings = clip.stabilization ?: return null
        if (settings.level == StabilizationLevel.off) return null
        val key = "${SourceMediaCache.key(source, File(folder, source.filename))}|${clip.stabilizationStart}|${clip.stabilizationEnd}|${settings.level.rawValue}"
        return if (lock.tryLock()) try { plans[key] } finally { lock.unlock() } else null
    }

    private fun decoderName(format: MediaFormat): String {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.getCapabilitiesForType(mime).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        // The emulator's goldfish decoders misbehave while other decoders run; prefer software there (as MediaRenderer).
        val emulator = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")
        val ordered = if (emulator) infos.sortedBy { if (it.isSoftwareOnly) 0 else 1 } else infos
        return ordered.firstOrNull()?.name ?: throw ProjectError.invalidMedia
    }

    /**
     * Swift `measure(url:start:duration:checkpoint:progress:)`: every distinct
     * source frame in [start], [start] + [duration], with the frame shown at
     * [start] as the first sample (time 0).
     */
    private fun measure(url: File, start: Double, duration: Double, checkCancellation: () -> Unit,
                        progress: (Double) -> Unit): StabilizationAnalysis {
        val geometry = VideoGeometry.read(url) ?: throw ProjectError.invalidMedia
        val size = CGSize(geometry.width.toDouble(), geometry.height.toDouble())
        val ratio = min(1.0, 640 / max(size.width, size.height))
        val width = max(64, Math.round(size.width * ratio).toInt())
        val height = max(64, Math.round(size.height * ratio).toInt())
        var previous: ByteBuffer? = null
        var previousTime = Double.NEGATIVE_INFINITY
        val samples = ArrayList<StabilizationSample>()
        var pose = StabilizationPose.identity
        var segment = 0
        var tracked = 0
        // Two luma buffers swap roles; a third holds the frame shown at the start.
        var current = ByteBuffer.allocateDirect(width * height)
        var spare = ByteBuffer.allocateDirect(width * height)
        var lead = ByteBuffer.allocateDirect(width * height)
        var leadTime: Double? = null

        fun process(gray: ByteBuffer, sourceTime: Double): ByteBuffer {
            val time = max(0.0, sourceTime)
            val last = previous
            if (last != null) {
                val motion = StabilizationMotion.estimate(last, gray, width, height)
                if (motion.valid && time - previousTime < 0.15) {
                    // Uniform analysis scaling preserves a similarity. Account
                    // for fractional size rounding in translations separately.
                    val step = StabilizationPose(motion.x * size.width / width, motion.y * size.height / height, motion.angle, motion.logScale)
                    val next = step.applying(pose)
                    if (next.valid) { pose = next; tracked += 1 } else { segment += 1; pose = StabilizationPose.identity }
                } else { segment += 1; pose = StabilizationPose.identity }
            }
            samples += StabilizationSample(if (samples.isEmpty()) 0.0 else time, pose, segment)
            previousTime = time
            progress(time / duration)
            // The frame just measured becomes `previous`; hand back its old buffer for reuse.
            val reuse = last ?: ByteBuffer.allocateDirect(width * height)
            previous = gray
            return reuse
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(url.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw ProjectError.invalidMedia
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createByCodecName(decoderName(format))
            try {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                format.setInteger(MediaFormat.KEY_ROTATION, 0)
                codec.configure(format, null, null, 0)
                codec.start()
                extractor.seekTo(Math.round(start * 1_000_000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var finished = false
                var stalled = System.nanoTime()
                progress(0.0)
                while (!finished) {
                    checkCancellation()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(5_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0 || extractor.sampleTime > Math.round((start + duration) * 1_000_000) + 1_000_000) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 5_000)
                    if (index < 0) {
                        if (System.nanoTime() - stalled > 10_000_000_000L) throw IOException("The video decoder stopped responding.")
                        continue
                    }
                    stalled = System.nanoTime()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finished = true
                    if (info.size <= 0 && !finished) { codec.releaseOutputBuffer(index, false); continue }
                    val sourceTime = info.presentationTimeUs / 1_000_000.0 - start
                    if (info.size > 0 && sourceTime < duration && sourceTime > previousTime) {
                        val image = codec.getOutputImage(index) ?: throw ProjectError.invalidMedia
                        val target = if (sourceTime < 0) lead else current
                        try {
                            val plane = image.planes[0]
                            val crop = image.cropRect
                            target.clear()
                            if (!StabilizationMotion.downsample(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top,
                                    crop.width(), crop.height(), target, width, height)) throw ProjectError.invalidMedia
                        } finally { image.close() }
                        if (sourceTime < 0) {
                            leadTime = sourceTime
                        } else {
                            // The frame shown at the start precedes it (AVAssetReader includes it at time 0).
                            if (leadTime != null && sourceTime > 0) {
                                val reuse = process(lead, leadTime!!)
                                lead = reuse
                            }
                            leadTime = null
                            val reuse = process(current, sourceTime)
                            current = spare; spare = reuse
                        }
                    } else if (info.size > 0 && sourceTime >= duration) {
                        finished = true
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                // A range shorter than one frame: the frame shown at its start.
                if (samples.isEmpty() && leadTime != null) process(lead, leadTime!!)
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
        } finally { extractor.release() }
        checkCancellation()
        if (samples.isEmpty()) throw ProjectError.invalidMedia
        return StabilizationAnalysis(geometry.width, geometry.height, duration, samples, tracked)
    }
}
