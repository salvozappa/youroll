// Port of YouRec/SourceMediaCache.swift (partial: SourceWaveform and the waveform
// cache) and of the waveform reader in YouRec/MediaRenderer.swift
// (`MediaRenderer.waveform`), plus Android disk caching of the editor's source
// thumbnails (EditorController.swift generates them with AVAssetImageGenerator).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Android also caches each source's decoded sound as raw PCM (`pcm`), which
// the editor preview mixes sample by sample (PreviewAudio.kt).
//
// Enhanced audio (Swift `preparedUniform`, `preparedPreview`, `prune`) is cached
// here as 48 kHz stereo float WAVs (AudioFiles.kt), with RNNoise speech
// probabilities for Pauses (`SpeechMeter`). Not ported (TODO.md): listening
// levels per source, cache leases and the prepared-timeline cache.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Serializable
class SourceWaveform(
    val duration: Double,
    val peaks: FloatArray,
    val rms: FloatArray? = null, // Older peak-only waveform caches are still usable by the timeline.
    val speech: String? = null,  // Swift `Data?` (base64): one RNNoise probability per 10 ms bin, Pauses only.
) {
    fun peak(at: Double): Float {
        val time = at
        if (peaks.isEmpty() || time < 0 || time >= duration) return 0f
        return peaks[min(peaks.size - 1, (time / duration * peaks.size).toInt())]
    }
}

/**
 * The binning loop of `MediaRenderer.waveform`: peak magnitude and per-channel
 * energy per bin. Fed with interleaved float PCM at any rate or channel count
 * (Swift always reads 48 kHz stereo; the louder channel's RMS is kept, as there).
 */
class WaveformAccumulator(val duration: Double, val bins: Int) {
    private val binSeconds = duration / bins
    val peaks = FloatArray(bins)
    private var energies: Array<DoubleArray> = emptyArray()
    private val counts = IntArray(bins)

    fun append(samples: FloatArray, frames: Int, channels: Int, sampleRate: Int, origin: Double) {
        if (bins <= 0 || frames <= 0 || channels <= 0) return
        if (energies.size < channels) energies = Array(channels) { energies.getOrNull(it) ?: DoubleArray(bins) }
        val rate = sampleRate.toDouble()
        var i = 0
        while (i < frames) {
            val time = origin + i / rate
            val bin = min((bins - 1).toDouble(), max(0.0, floor(time / binSeconds))).toInt()
            val boundary = ceil(((bin + 1) * binSeconds - origin) * rate)
            val end = if (bin == bins - 1) frames else min(frames.toDouble(), max((i + 1).toDouble(), boundary)).toInt()
            var peak = peaks[bin]
            for (frame in i until end) {
                for (channel in 0 until channels) {
                    val value = samples[frame * channels + channel]
                    val magnitude = if (value < 0) -value else value
                    if (magnitude > peak) peak = magnitude
                    energies[channel][bin] += (value * value).toDouble()
                }
            }
            peaks[bin] = peak
            counts[bin] += end - i
            i = end
        }
    }

    fun result(): SourceWaveform {
        // Measure the louder channel rather than downmixing away off-centre or out-of-phase speech.
        val rms = FloatArray(bins) { bin ->
            val energy = energies.maxOfOrNull { it[bin] } ?: 0.0
            sqrt(energy / max(1, counts[bin])).toFloat()
        }
        return SourceWaveform(duration, peaks.copyOf(), rms)
    }
}

/** Decodes a file's first audio track to interleaved float PCM with MediaExtractor + MediaCodec. */
object PcmDecoder {
    class Chunk(val samples: FloatArray, val frames: Int, val channels: Int, val sampleRate: Int, val origin: Double)

    /** Returns false when the file has no audio track. Stops early once [end] is passed. */
    fun decode(file: File, start: Double = 0.0, end: Double = Double.POSITIVE_INFINITY,
               active: () -> Boolean = { true }, consume: (Chunk) -> Unit): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            if (start > 0) extractor.seekTo((start * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var output = codec.outputFormat
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (active()) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(5_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: error("No decoder input buffer")
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time / 1_000_000.0 > end + 0.2) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 5_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { output = codec.outputFormat; continue }
                    if (index < 0) continue
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        val ordered = buffer.order(ByteOrder.nativeOrder())
                        val samples: FloatArray = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(info.size / 4).also { ordered.asFloatBuffer().get(it) }
                            else -> { val shorts = ShortArray(info.size / 2); ordered.asShortBuffer().get(shorts); FloatArray(shorts.size) { shorts[it] / 32768f } }
                        }
                        val origin = info.presentationTimeUs / 1_000_000.0
                        consume(Chunk(samples, samples.size / max(1, channels), channels, rate, origin))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (info.presentationTimeUs / 1_000_000.0 > end + 0.2) break
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            return true
        } finally {
            extractor.release()
        }
    }
}

/**
 * Swift `MediaRenderer.SpeechMeter`: one RNNoise state per channel; each 10 ms
 * frame of 48 kHz stereo yields the larger of the two speech probabilities,
 * quantized to a byte, kept as the maximum of its waveform bin. Decoded chunks
 * at another rate or channel count are first brought to 48 kHz stereo
 * (linear interpolation; mono on both sides), as the iOS reader delivers them.
 */
class SpeechMeter(bins: Int, private val binSeconds: Double) : java.io.Closeable {
    private val left = Denoiser.create()
    private val right = Denoiser.create()
    private val frame = FloatArray(960)
    private var filled = 0
    private var origin = 0L
    val probabilities = ByteArray(bins)

    /** Appends a decoded chunk placed at its presentation time. */
    fun append(chunk: PcmDecoder.Chunk) {
        val rate = chunk.sampleRate.toDouble()
        if (chunk.frames <= 0 || rate <= 0) return
        val first = ceil(chunk.origin * 48000 - 1e-6).toLong().coerceAtLeast(0)
        val end = ceil((chunk.origin + chunk.frames / rate) * 48000 - 1e-6).toLong()
        if (end <= first) return
        val count = (end - first).toInt()
        val stereo = FloatArray(count * 2)
        val channels = chunk.channels
        for (i in 0 until count) {
            val position = ((first + i) / 48000.0 - chunk.origin) * rate
            val index = min(chunk.frames - 1, max(0, floor(position).toInt()))
            val next = min(chunk.frames - 1, index + 1)
            val fraction = (position - index).toFloat().coerceIn(0f, 1f)
            for (channel in 0 until 2) {
                val c = min(channel, channels - 1)
                stereo[i * 2 + channel] = chunk.samples[index * channels + c] * (1 - fraction) + chunk.samples[next * channels + c] * fraction
            }
        }
        append(stereo, count, first)
    }

    fun append(samples: FloatArray, count: Int, start: Long) {
        if (start != origin + filled) flush()
        var offset = 0
        while (offset < count) {
            if (filled == 0) origin = start + offset
            val take = min(480 - filled, count - offset)
            System.arraycopy(samples, 2 * offset, frame, 2 * filled, 2 * take)
            offset += take; filled += take
            if (filled == 480) flush()
        }
    }

    fun flush() {
        if (filled == 0) return
        if (filled < 480) java.util.Arrays.fill(frame, filled * 2, 960, 0f)
        // Protect speech in either channel, including stereo with opposite polarity.
        val p = max(left.speechProbability(frame, 0, 2), right.speechProbability(frame, 1, 2))
        val time = (origin + filled / 2).toDouble() / 48000
        val bin = min(probabilities.size - 1, max(0, (time / binSeconds).toInt()))
        val value = Math.round(min(1f, max(0f, p)) * 255).toInt()
        if (value > (probabilities[bin].toInt() and 0xFF)) probabilities[bin] = value.toByte()
        filled = 0
    }

    override fun close() { left.close(); right.close() }
}

fun toBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

data class SourceThumbnail(val time: Double, val image: Bitmap)

// Each cached result is computed in the original source's time domain, so
// trims and reorders reuse it.
object SourceMediaCache {
    private val lock = Mutex()
    private val waves = HashMap<String, SourceWaveform>()

    /** Swift `fingerprint(_:)`; the stored SHA-256 avoids rehashing large originals. */
    fun key(source: SourceMedia, file: File): String =
        source.sha256 ?: "${file.name}-${file.length()}-${file.lastModified()}"

    private fun cacheFolder(folder: File): File = ProjectCacheLocation.root(folder).also { it.mkdirs() }

    /**
     * Swift `waveform(source:folder:needsRMS:needsSpeech:progress:)`: [needsRMS]
     * (Pauses) rereads an older peak-only cache; [needsSpeech] (automatic
     * Pauses) measures RNNoise speech probabilities when the cache lacks them.
     */
    suspend fun waveform(source: SourceMedia, folder: File, needsRMS: Boolean = false, needsSpeech: Boolean = false,
                         progress: ((Double) -> Unit)? = null): SourceWaveform = withContext(Dispatchers.IO) {
        val file = File(folder, source.filename)
        val key = key(source, file) + "-wave-v2"
        // Without the native library (JVM tests) speech cannot be measured; energy detection still works.
        val speech = needsSpeech && Denoiser.available
        fun usable(wave: SourceWaveform) = wave.duration == source.duration && (!needsRMS || wave.rms?.size == wave.peaks.size) &&
            (!speech || wave.speech?.let { runCatching { fromBase64(it).size }.getOrNull() } == wave.peaks.size)
        lock.withLock { waves[key] }?.takeIf(::usable)?.let { return@withContext it }
        val destination = File(cacheFolder(folder), "$key.json")
        val cached = runCatching { SwiftJSON.decodeFromString(SourceWaveform.serializer(), destination.readText()) }.getOrNull()
        if (cached != null && usable(cached) && cached.peaks.all { it.isFinite() && it >= 0 } &&
            (cached.rms?.let { rms -> rms.size == cached.peaks.size && rms.all { it.isFinite() && it >= 0 } } ?: true)) {
            lock.withLock { waves[key] = cached }
            return@withContext cached
        }
        // 10 ms bins support silence placement even when zoomed to frame level.
        val wave = waveform(file, source.duration, max(1, ceil(source.duration * 100).toInt()), currentCoroutineContext(), measureSpeech = speech, progress = progress)
        // A concurrent timeline drawing request must never overwrite a completed speech scan.
        lock.withLock { waves[key] }?.let { existing -> if (usable(existing) && existing.speech != null) return@withContext existing }
        val temporary = File(destination.path + ".tmp")
        temporary.writeText(SwiftJSON.encodeToString(SourceWaveform.serializer(), wave))
        temporary.renameTo(destination)
        lock.withLock { if (waves.size >= 12) waves.clear(); waves[key] = wave }
        wave
    }

    /**
     * The source's first audio track decoded once to 16-bit PCM at its own rate
     * and channel count ([SourcePcm] layout), or null without sound. Chunks are
     * placed by presentation time: priming before 0 is dropped, holes are silent.
     */
    suspend fun pcm(source: SourceMedia, folder: File): File? = withContext(Dispatchers.IO) {
        val file = File(folder, source.filename)
        val destination = File(cacheFolder(folder), key(source, file) + "-pcm-v1.raw")
        val none = File(destination.path + ".none")
        if (destination.exists() && destination.length() >= SourcePcm.headerSize) return@withContext destination
        if (none.exists()) return@withContext null
        val context = currentCoroutineContext()
        val temporary = File(destination.path + ".tmp")
        var written = 0L
        var rate = 0
        var channels = 0
        val found = java.io.BufferedOutputStream(temporary.outputStream(), 1 shl 16).use { out ->
            out.write(ByteArray(SourcePcm.headerSize))
            val scratch = java.nio.ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            fun put(value: Float) {
                if (scratch.remaining() < 2) { out.write(scratch.array(), 0, scratch.position()); scratch.clear() }
                scratch.putShort((value * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            val result = PcmDecoder.decode(file, active = { context.isActive }) { chunk ->
                if (rate == 0) { rate = chunk.sampleRate; channels = chunk.channels }
                if (chunk.sampleRate != rate || chunk.channels != channels) return@decode // Mid-stream format changes are not expected.
                var skip = 0
                val at = Math.round(chunk.origin * rate)
                if (at > written) { repeat(((at - written) * channels).toInt()) { put(0f) }; written = at }
                else if (at < written) skip = min(chunk.frames.toLong(), written - at).toInt()
                for (index in skip * channels until chunk.frames * channels) put(chunk.samples[index])
                written += chunk.frames - skip
            }
            out.write(scratch.array(), 0, scratch.position())
            result
        }
        context.ensureActive()
        if (!found || rate == 0) { temporary.delete(); none.writeText(""); return@withContext null }
        java.io.RandomAccessFile(temporary, "rw").use { it.write(SourcePcm.header(rate, channels)) }
        if (!temporary.renameTo(destination)) { temporary.delete(); return@withContext null }
        destination
    }

    /**
     * A speed run's pitch-preserving stretch of [source] (whose decoded PCM is
     * [samples]), cached next to its PCM in the same format (PreviewAudio.kt).
     */
    suspend fun stretchedPcm(source: SourceMedia, folder: File, samples: PcmSamples, key: SpeedStretch.Key): File? = withContext(Dispatchers.IO) {
        val file = File(folder, source.filename)
        val destination = File(cacheFolder(folder), key(source, file) + "-speed-" + key.name + "-v1.raw")
        if (destination.exists() && destination.length() >= SourcePcm.headerSize) return@withContext destination
        val context = currentCoroutineContext()
        val temporary = File(destination.path + ".tmp")
        java.io.BufferedOutputStream(temporary.outputStream(), 1 shl 16).use { out ->
            out.write(SourcePcm.header(samples.sampleRate, samples.channels))
            val bytes = ByteArray(1 shl 16)
            // Sonic writes native order, which is little-endian on every Android ABI, like SourcePcm.
            SpeedStretch.render(samples, key, active = { context.isActive }) { buffer ->
                val view = buffer.duplicate()
                while (view.hasRemaining()) {
                    val count = min(bytes.size, view.remaining())
                    view.get(bytes, 0, count)
                    out.write(bytes, 0, count)
                }
            }
        }
        context.ensureActive()
        if (!temporary.renameTo(destination)) { temporary.delete(); return@withContext null }
        destination
    }

    /**
     * `MediaRenderer.waveform(asset:duration:bins:measureSpeech:)`. With
     * [measureSpeech] the sound is also brought to 48 kHz stereo (as the iOS
     * reader delivers it) and RNNoise's speech probability of each 10 ms frame
     * is kept per bin ([SpeechMeter]).
     */
    fun waveform(file: File, duration: Double, bins: Int, context: CoroutineContext? = null, measureSpeech: Boolean = false,
                 progress: ((Double) -> Unit)? = null): SourceWaveform {
        if (!(duration.isFinite() && duration > 0 && bins > 0)) return SourceWaveform(duration, FloatArray(0), FloatArray(0), if (measureSpeech) "" else null)
        val accumulator = WaveformAccumulator(duration, bins)
        val meter = if (measureSpeech) SpeechMeter(bins, duration / bins) else null
        try {
            val found = PcmDecoder.decode(file, active = { context?.isActive ?: true }) { chunk ->
                accumulator.append(chunk.samples, chunk.frames, chunk.channels, chunk.sampleRate, chunk.origin)
                meter?.append(chunk)
                progress?.invoke(chunk.origin / duration)
            }
            context?.ensureActive()
            if (!found) return SourceWaveform(duration, FloatArray(0), FloatArray(0), if (measureSpeech) "" else null)
            val wave = accumulator.result()
            meter?.flush()
            return SourceWaveform(wave.duration, wave.peaks, wave.rms, meter?.let { toBase64(it.probabilities) })
        } finally {
            meter?.close()
        }
    }

    // ---- Enhanced audio (Swift `preparedUniform`, `preparedPreview`, `prune`) ----

    private val building = HashSet<File>()
    private val buildingLock = Mutex()

    /** Waits until none of [files] is being built by another request, then reserves them. */
    private suspend fun reserve(files: Set<File>) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val reserved = buildingLock.withLock { if (building.none { it in files }) { building += files; true } else false }
            if (reserved) return
            kotlinx.coroutines.delay(50)
        }
    }

    private suspend fun unreserve(files: Set<File>) = buildingLock.withLock { building -= files }

    private fun sha256(text: ByteArray): String = DeepFilterNetEngine.sha256(text)

    private fun chainJSON(effects: List<AudioEffect>): ByteArray =
        SwiftJSON.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AudioEffect.serializer()), effects).sortedKeys().encodedBytes()

    /** Frames of [duration] seconds at 48 kHz, as Swift `Int64((duration * 48000).rounded())`. */
    private fun frames48k(duration: Double): Long = Math.round(duration * AudioFile.sampleRate)

    private fun usable(file: File, frames: Long): Boolean {
        val info = AudioFile.info(file) ?: return false
        val peak = runCatching { OfflineAudioPipeline.peakFile(file).readText().toFloat() }.getOrNull()
        return info.frames == frames && info.sampleRate == AudioFile.sampleRate && info.channels == 2 && peak != null && peak.isFinite() && peak >= 0
    }

    /**
     * Swift `MediaRenderer.processedAudio(asset:duration:effects: [])`: the
     * source's sound as 48 kHz stereo float, [frames] long from source frame
     * [first] (48 kHz). Read from the decoded PCM cache and resampled linearly,
     * like the preview mixer; mono plays on both sides. Silent past the end.
     */
    private fun writeSourceAudio(samples: PcmSamples?, first: Long, frames: Long, output: File, active: () -> Boolean, progress: (Double) -> Unit) {
        AudioFile.Writer(output, 2).use { writer ->
            val block = 4096
            val buffer = FloatArray(block * 2)
            var done = 0L
            val step = (samples?.sampleRate ?: AudioFile.sampleRate).toDouble() / AudioFile.sampleRate
            while (done < frames) {
                if (!active()) throw kotlin.coroutines.cancellation.CancellationException("Audio decoding cancelled")
                val count = min(block.toLong(), frames - done).toInt()
                if (samples == null) java.util.Arrays.fill(buffer, 0f) else {
                    val right = if (samples.channels > 1) 1 else 0
                    for (i in 0 until count) {
                        val position = (first + done + i) * step
                        val index = floor(position).toLong()
                        val fraction = (position - index).toFloat()
                        buffer[i * 2] = samples.sample(index, 0) * (1 - fraction) + samples.sample(index + 1, 0) * fraction
                        buffer[i * 2 + 1] = if (right == 0) buffer[i * 2] else samples.sample(index, right) * (1 - fraction) + samples.sample(index + 1, right) * fraction
                    }
                }
                writer.write(buffer, count)
                done += count
                progress(done.toDouble() / max(1L, frames))
            }
        }
    }

    /**
     * Swift `preparedUniform`'s audio part: [source]'s whole sound through
     * [effects], cached in the source's time domain under a hash of the source,
     * the pipeline version and the active chain, with one checkpoint per chain
     * prefix so a change at the end of the chain reuses the earlier (neural)
     * stages. Returns the processed 48 kHz stereo float WAV and its peak, or
     * null when the source has no sound or no stage is enabled.
     */
    suspend fun processedAudio(source: SourceMedia, folder: File, effects: List<AudioEffect>, progress: (Double) -> Unit = {}): Pair<File, Float>? = withContext(Dispatchers.IO) {
        val enabled = effects.filter { it.enabled }
        OfflineAudioPipeline.unavailable(effects)?.let { throw it }
        if (enabled.isEmpty()) return@withContext null
        val active = enabled.filter { !(it.kind.isNeural && it.amount == 0.0) }
        val sourceFile = File(folder, source.filename)
        val pcmFile = pcm(source, folder) ?: return@withContext null
        val cache = cacheFolder(folder)
        val identity = (key(source, sourceFile) + "|" + OfflineAudioPipeline.cacheVersion + "|" + source.duration + "|").toByteArray()
        val keyHash = sha256(identity + chainJSON(active))
        val destination = File(cache, "$keyHash.wav")
        val checkpoints = active.indices.map { index -> File(cache, sha256(identity + chainJSON(active.take(index + 1))) + ".wav") }
        val frames = frames48k(source.duration)
        // Reserve the whole prefix set: chains sharing a stage never rewrite each other's files.
        val reservations = (checkpoints + destination).toSet()
        reserve(reservations)
        try {
            if (usable(destination, frames)) {
                destination.setLastModified(System.currentTimeMillis())
                progress(1.0)
                return@withContext destination to OfflineAudioPipeline.peakFile(destination).readText().toFloat()
            }
            // Remove unusable derived stages, so an interrupted write can be regenerated from the original.
            for (file in reservations) if (file.exists() && !usable(file, frames)) {
                file.delete()
                for (suffix in listOf("peak", "levels.json", "priority")) File(cache, file.nameWithoutExtension + "." + suffix).delete()
            }
            for ((index, file) in checkpoints.withIndex()) {
                // Prefer retaining a neural boundary over its cheap EQ/gain derivatives.
                val cost = active.take(index + 1).count { it.kind.isNeural } * 100 + if (active[index].kind.isNeural) 10 else 0
                OfflineAudioPipeline.writeAtomically(File(cache, file.nameWithoutExtension + ".priority"), cost.toString())
                if (file.exists()) file.setLastModified(System.currentTimeMillis())
            }
            prune(cache, reserving = frames * 8 * (active.size + 3), keep = reservations)
            val context = currentCoroutineContext()
            val raw = File(cache, newUUID().uuidString + ".working.wav")
            val processed = File(cache, newUUID().uuidString + ".working.wav")
            try {
                val samples = SourcePcm.open(pcmFile)
                writeSourceAudio(samples, 0, frames, raw, { context.isActive }) { progress(it * 0.1) }
                val peak = OfflineAudioPipeline.process(raw, enabled, processed, checkpoints, active = { context.isActive }) { progress(0.1 + it * 0.9) }
                context.ensureActive()
                if (usable(destination, frames)) return@withContext destination to OfflineAudioPipeline.peakFile(destination).readText().toFloat()
                destination.delete()
                java.nio.file.Files.move(processed.toPath(), destination.toPath())
                OfflineAudioPipeline.writeAtomically(OfflineAudioPipeline.peakFile(destination), peak.toString())
                destination to peak
            } finally {
                raw.delete(); processed.delete()
            }
        } finally {
            unreserve(reservations)
        }
    }

    /**
     * Swift `preparedPreview`: inspector auditions process only the audible
     * source windows, [spans] in source seconds, never a whole-take render.
     * Each window is cached under the source metadata, the span and the chain.
     * Returns one [FloatPcm] per span, placed at its source frame.
     */
    suspend fun processedWindows(source: SourceMedia, folder: File, effects: List<AudioEffect>, spans: List<ClosedFloatingPointRange<Double>>,
                                 progress: (Double) -> Unit = {}): List<FloatPcm> = withContext(Dispatchers.IO) {
        OfflineAudioPipeline.unavailable(effects)?.let { throw it }
        val sourceFile = File(folder, source.filename)
        val pcmFile = pcm(source, folder) ?: return@withContext emptyList()
        val cache = cacheFolder(folder)
        val context = currentCoroutineContext()
        val result = ArrayList<FloatPcm>()
        for ((index, span) in spans.withIndex()) {
            context.ensureActive()
            val first = frames48k(span.start)
            val frames = frames48k(span.endInclusive) - first
            if (frames <= 0) continue
            val identity = ("audio-window-android-1|${sourceFile.path}|${sourceFile.length()}|${sourceFile.lastModified()}|$first|$frames|${OfflineAudioPipeline.cacheVersion}|").toByteArray()
            val destination = File(cache, sha256(identity + chainJSON(effects)) + ".wav")
            reserve(setOf(destination))
            try {
                if (!usable(destination, frames)) {
                    prune(cache, reserving = frames * 8 * (effects.size + 3), keep = setOf(destination))
                    val raw = File(cache, newUUID().uuidString + ".working.wav")
                    val output = File(cache, newUUID().uuidString + ".working.wav")
                    try {
                        writeSourceAudio(SourcePcm.open(pcmFile), first, frames, raw, { context.isActive }) { progress((index + it * 0.15) / spans.size) }
                        val peak = OfflineAudioPipeline.process(raw, effects, output, active = { context.isActive }) { progress((index + 0.15 + it * 0.8) / spans.size) }
                        context.ensureActive()
                        destination.delete()
                        java.nio.file.Files.move(output.toPath(), destination.toPath())
                        OfflineAudioPipeline.writeAtomically(OfflineAudioPipeline.peakFile(destination), peak.toString())
                    } finally {
                        raw.delete(); output.delete()
                    }
                } else destination.setLastModified(System.currentTimeMillis())
            } finally {
                unreserve(setOf(destination))
            }
            FloatPcm.open(destination, origin = first)?.let { result += it }
            progress((index + 1).toDouble() / spans.size)
        }
        result
    }

    /**
     * Swift `prune(_:reserving:)`: keeps derived audio within a quota of 2–8 GB
     * (a tenth of the free space) and frees room for [reserving] bytes, oldest
     * and cheapest stages first. Files being built ([keep]) are never removed;
     * an open preview keeps reading a removed file's data (POSIX unlink).
     */
    private fun prune(cache: File, reserving: Long, keep: Set<File>) {
        val files = cache.listFiles()?.filter { it.extension == "wav" && it.nameWithoutExtension.length == 64 } ?: return
        class Record(val file: File, val bytes: Long, val modified: Long, val priority: Int)
        val records = files.map { file ->
            Record(file, file.length(), file.lastModified(), runCatching { File(cache, file.nameWithoutExtension + ".priority").readText().trim().toInt() }.getOrDefault(0))
        }
        var total = records.sumOf { it.bytes }
        var available = cache.usableSpace
        val quota = max(2_000_000_000L, min(8_000_000_000L, available / 10 + total / 10))
        for (record in records.sortedWith(compareBy<Record> { it.priority }.thenBy { it.modified })) {
            if (total <= quota && available >= reserving + 256_000_000) break
            if (record.file in keep) continue
            record.file.delete()
            for (suffix in listOf("peak", "levels.json", "priority")) File(cache, record.file.nameWithoutExtension + "." + suffix).delete()
            total -= record.bytes; available += record.bytes
        }
        if (available < reserving + 256_000_000) throw java.io.IOException("There is not enough free space to process this take. Free some storage and try again.")
    }

    /** Removes crash leftovers of the processing scratch files. */
    fun removeWorkingFiles(folder: File) {
        ProjectCacheLocation.root(folder).listFiles()?.filter { it.name.endsWith(".working.wav") }?.forEach { it.delete() }
    }

    /** `MediaRenderer.waveform(asset:duration:bins:)` of a processed float file (its own overview). */
    suspend fun processedWaveform(file: File, duration: Double): SourceWaveform = withContext(Dispatchers.IO) {
        val key = file.nameWithoutExtension + "-wave-v2"
        lock.withLock { waves[key] }?.let { return@withContext it }
        val bins = max(1, ceil(duration * 100).toInt())
        val accumulator = WaveformAccumulator(duration, bins)
        val context = currentCoroutineContext()
        AudioFile.Reader(file).use { reader ->
            val buffer = FloatArray(4096 * reader.channels)
            while (reader.framePosition < reader.length) {
                context.ensureActive()
                val origin = reader.framePosition.toDouble() / reader.info.sampleRate
                val count = reader.read(buffer, 4096)
                if (count <= 0) break
                accumulator.append(buffer, count, reader.channels, reader.info.sampleRate, origin)
            }
        }
        val wave = accumulator.result()
        lock.withLock { if (waves.size >= 12) waves.clear(); waves[key] = wave }
        wave
    }

    /**
     * Frames every `max(1, duration / budget)` seconds, 160 px at most, as in
     * EditorController.load. Android keeps them as JPEGs in the project cache so
     * reopening the editor does not decode the source again. [partial] receives
     * the first frame and every eighth, like the Swift progressive publishing.
     */
    suspend fun thumbnails(source: SourceMedia, folder: File, budget: Int, partial: (List<SourceThumbnail>) -> Unit): List<SourceThumbnail> =
        withContext(Dispatchers.IO) {
            val file = File(folder, source.filename)
            val directory = File(cacheFolder(folder), key(source, file) + "-thumbs-$budget")
            val step = max(1.0, source.duration / budget)
            val times = generateSequence(0.0) { it + step }.takeWhile { it < source.duration }.toList()
            val done = File(directory, "complete")
            if (done.exists()) {
                val images = times.mapIndexedNotNull { index, time ->
                    BitmapFactory.decodeFile(File(directory, "$index.jpg").path)?.let { SourceThumbnail(time, it) }
                }
                if (images.size == times.size) return@withContext images
            }
            directory.mkdirs()
            val retriever = MediaMetadataRetriever()
            val images = mutableListOf<SourceThumbnail>()
            try {
                retriever.setDataSource(file.path)
                for ((index, time) in times.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val frame = retriever.getScaledFrameAtTime((time * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, 160, 160)
                        ?: retriever.getFrameAtTime((time * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: continue
                    runCatching { File(directory, "$index.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                    images += SourceThumbnail(time, frame)
                    if (images.size == 1 || images.size % 8 == 0) partial(images.toList())
                }
                if (images.size == times.size) runCatching { done.writeText("") }
            } finally {
                retriever.release()
            }
            images
        }
}
