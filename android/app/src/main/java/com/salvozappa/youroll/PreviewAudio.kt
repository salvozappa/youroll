// Android counterpart of the editor preview's audio mix: YouRec/AudioGain.swift
// (`AudioGainMix.install`, live taps) and YouRec/CutAudioEnvelope.swift (the
// per-sample envelope tap), driven by the audio plan of YouRec/MediaRenderer.swift.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS previews an AVComposition whose audio mix applies every envelope per
// sample, so the preview mixes overlapping sounds exactly like the export.
// Media3 has no such mix for ExoPlayer. Options considered:
//
// - CompositionPlayer (Media3 1.11) previewing the export Composition. It would
//   share the export's mixer, but the picture then goes through the GL effects
//   pipeline (which already fails on the emulator for some effects), scrubbing
//   mode and the emulator's decoder workaround are not available, and the
//   export plan refuses timelines the preview must still show (speed, holds).
// - Several synced ExoPlayers, one per overlapping lane. Independent clocks
//   drift and each seek re-buffers every player; sample accuracy is not possible.
// - A custom AudioProcessor in the one preview ExoPlayer (chosen). The playlist
//   keeps its picture segments, and each segment's sound is a synthetic 48 kHz
//   stereo WAV "clock" whose samples carry their own timeline frame number
//   ([PreviewClock]). The sink's [PreviewMixProcessor] reads that number and
//   replaces every frame with the mix ([PreviewAudioMix]) of all audible audio
//   items at exactly that timeline frame, using the export's lanes and
//   [AudioEnvelopeSegment.gain] with its censor tones (CutAudioEnvelope.kt). Timing therefore never depends on the
//   playhead, the AudioTrack latency or playlist transitions, seeks are exact,
//   review speed is applied afterwards by the sink's Sonic, and a volume
//   audition ([LiveAudioGain]) reaches the next audio buffer.
//
// Sources are decoded once to raw PCM (`SourceMediaCache.pcm`) and memory
// mapped, so the audio thread never decodes. A different source rate is
// resampled linearly. A speed run is time-stretched beforehand with Sonic at
// the source rate, keeping the pitch ([SpeedStretch], cached on disk), which is
// what the export's SpeedChangingAudioProcessor does with `shouldMaintainPitch`
// (iOS uses the spectral time-pitch algorithm in both). Until its stretch is
// ready a speed run plays resampled (pitch follows speed).
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Interleaved PCM of one source, addressed by frame. */
interface PcmSamples {
    val sampleRate: Int
    val channels: Int
    val frames: Long
    /** The sample in -1…1; 0 outside the source. */
    fun sample(frame: Long, channel: Int): Float
}

/** A memory-mapped `SourceMediaCache.pcm` file: a 16-byte header then 16-bit little-endian samples. */
class SourcePcm private constructor(override val sampleRate: Int, override val channels: Int, private val data: ShortBuffer) : PcmSamples {
    override val frames: Long = (data.capacity() / channels).toLong()
    override fun sample(frame: Long, channel: Int): Float {
        if (frame < 0 || frame >= frames) return 0f
        return data.get((frame * channels).toInt() + channel) / 32768f
    }

    companion object {
        const val headerSize = 16
        private const val magic = 0x4D435054 // "TPCM"

        fun header(rate: Int, channels: Int): ByteArray =
            ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN).putInt(magic).putInt(1).putInt(rate).putInt(channels).array()

        fun open(file: File): SourcePcm? = RandomAccessFile(file, "r").use { handle ->
            val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            handle.channel.read(header, 0)
            header.flip()
            if (header.remaining() < headerSize || header.int != magic || header.int != 1) return null
            val rate = header.int
            val channels = header.int
            if (rate <= 0 || channels <= 0) return null
            // One mapping covers about three hours at 48 kHz stereo; anything later plays silent.
            val length = min(handle.length() - headerSize, Int.MAX_VALUE.toLong() - 1) / (2 * channels) * (2 * channels)
            val mapped = handle.channel.map(FileChannel.MapMode.READ_ONLY, headerSize.toLong(), length).order(ByteOrder.LITTLE_ENDIAN)
            SourcePcm(rate, channels, mapped.asShortBuffer())
        }
    }
}

/**
 * Every audible audio item of one edit, mixed at 48 kHz stereo on the timeline
 * clock. [lanes] come from `ExportPlan.previewAudio`, so each piece's gain is
 * the export's [AudioEnvelopeSegment]. Pure apart from reading [sources].
 */
class PreviewAudioMix(lanes: List<List<ExportPlan.Piece>>, private val sources: Map<UUID, PcmSamples>,
                      private val stretched: Map<SpeedStretch.Key, PcmSamples> = emptyMap(),
                      /** Enhanced sound by `TrackRenderer.audioKey` (OfflineAudioPipeline.kt), on the source's clock. */
                      private val processed: Map<String, PcmSamples> = emptyMap()) {
    private val clips: List<ExportPlan.Piece.Clip> = lanes.flatten().filterIsInstance<ExportPlan.Piece.Clip>().sortedBy { it.start }
    private val latestEnd: List<Double> = clips.runningFold(Double.NEGATIVE_INFINITY) { acc, clip -> max(acc, clip.end.seconds) }.drop(1)
    /** The source IDs this mix reads. */
    val sourceIDs: Set<UUID> get() = clips.map { it.sourceID }.toSet()

    /** Pieces audible somewhere in [start, end) seconds. */
    fun clips(start: Double, end: Double): List<ExportPlan.Piece.Clip> {
        // Skip the prefix that ended before [start] (latestEnd is non-decreasing).
        var lower = 0
        var upper = clips.size
        while (lower < upper) { val middle = (lower + upper) / 2; if (latestEnd[middle] <= start) lower = middle + 1 else upper = middle }
        val result = mutableListOf<ExportPlan.Piece.Clip>()
        for (index in lower until clips.size) {
            val clip = clips[index]
            if (clip.start.seconds >= end) break
            if (clip.end.seconds > start) result += clip
        }
        return result
    }

    /**
     * Writes [frames] stereo frames starting at timeline frame [first] into
     * [out] (interleaved, from [offset]), replacing what was there.
     * Returns how many pieces contributed.
     */
    fun render(first: Long, frames: Int, out: FloatArray, offset: Int = 0, live: LiveAudioGain.Snapshot = LiveAudioGain.Snapshot()): Int {
        java.util.Arrays.fill(out, offset, offset + frames * 2, 0f)
        val active = clips(first / rate, (first + frames) / rate)
        for (clip in active) {
            // A speed run reads its pitch-preserving stretch, which starts at the piece and runs at the timeline rate.
            val stretch = if (clip.isNormalSpeed) null else stretched[SpeedStretch.Key(clip)]
            // An enhanced piece plays its processed sound (Swift: the processed region's URL).
            val source = stretch ?: clip.audioKey?.let { processed[it] } ?: sources[clip.sourceID] ?: continue
            val envelope = live.applying(clip.envelope ?: continue)
            val clipStart = clip.start.seconds
            val from = max(first, ceil(clipStart * rate).toLong() - 1)
            val to = min(first + frames, ceil(clip.end.seconds * rate).toLong() + 1)
            val step = (if (stretch != null) 1.0 else clip.rate) * source.sampleRate
            val origin = if (stretch != null) 0.0 else clip.sourceStart * source.sampleRate
            val right = if (source.channels > 1) 1 else 0
            for (frame in from until to) {
                val time = frame / rate
                val level = envelope.gain(time)
                if (level == 0.0) continue
                // CutAudioEnvelope: a censor replaces the sound with its tone (AudioEnvelopeProcessor does the same in the export).
                val gain = (level * envelope.original(time)).toFloat()
                val tone = if (envelope.censors.isEmpty()) 0f else (level * envelope.tone(time, rate)).toFloat()
                val position = origin + (time - clipStart) * step
                val index = floor(position).toLong()
                val fraction = (position - index).toFloat()
                val l = source.sample(index, 0) * (1 - fraction) + source.sample(index + 1, 0) * fraction
                val r = if (right == 0) l else source.sample(index, right) * (1 - fraction) + source.sample(index + 1, right) * fraction
                val at = offset + ((frame - first) * 2).toInt()
                out[at] += l * gain + tone
                out[at + 1] += r * gain + tone
            }
        }
        return active.size
    }

    companion object {
        const val sampleRate = 48_000
        private const val rate = sampleRate.toDouble()
        fun frame(seconds: Double): Long = Math.round(seconds * rate)
    }
}

/**
 * The sound of one speed run on the timeline clock: the source span pushed
 * through Media3's Sonic with the run's speed and the pitch kept, at the
 * source's own rate and channels. The export's SpeedChangingAudioProcessor
 * (`SpeedParameters(…, shouldMaintainPitch = true)`) runs the same algorithm
 * on the same 16-bit samples before resampling to 48 kHz.
 */
object SpeedStretch {
    data class Key(val source: UUID, val sourceStart: Double, val sourceEnd: Double, val duration: EditTime, val audioKey: String? = null) {
        constructor(piece: ExportPlan.Piece.Clip) : this(piece.sourceID, piece.sourceStart, piece.sourceEnd, piece.duration, piece.audioKey)
        val rate: Double get() = if (duration > EditTime.zero) (sourceEnd - sourceStart) / duration.seconds else 1.0
        /** A file name part that identifies the span and speed exactly. */
        val name: String get() = "%d-%d-%d".format(java.util.Locale.ROOT, Math.round(sourceStart * 1_000_000), Math.round(sourceEnd * 1_000_000), duration.ticks) +
            (audioKey?.let { "-" + DeepFilterNetEngine.sha256(it.toByteArray()).take(16) } ?: "")
    }

    /**
     * Streams the stretched samples of [key] from [source] into [write]
     * (interleaved 16-bit, native order buffers). [active] stops early.
     */
    fun render(source: PcmSamples, key: Key, active: () -> Boolean = { true }, write: (ByteBuffer) -> Unit) {
        val sonic = androidx.media3.common.audio.SonicAudioProcessor()
        sonic.setSpeed(key.rate.toFloat())
        sonic.setPitch(1f)
        sonic.configure(AudioProcessor.AudioFormat(source.sampleRate, source.channels, C.ENCODING_PCM_16BIT))
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        fun drain() {
            while (true) {
                val output = sonic.output
                if (!output.hasRemaining()) break
                write(output)
                output.position(output.limit())
            }
        }
        val first = Math.round(key.sourceStart * source.sampleRate)
        val last = Math.round(key.sourceEnd * source.sampleRate)
        val chunk = 4096
        val buffer = ByteBuffer.allocateDirect(chunk * source.channels * 2).order(ByteOrder.nativeOrder())
        var frame = first
        while (frame < last && active()) {
            val count = min(chunk.toLong(), last - frame).toInt()
            buffer.clear()
            for (index in 0 until count) for (channel in 0 until source.channels) {
                buffer.putShort((source.sample(frame + index, channel) * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
            buffer.flip()
            sonic.queueInput(buffer)
            drain()
            frame += count
        }
        sonic.queueEndOfStream()
        while (!sonic.isEnded && active()) drain()
        drain()
        sonic.reset()
    }
}

/** Interleaved 16-bit samples held in memory (tests and short stretches). */
class MemoryPcm(override val sampleRate: Int, override val channels: Int, private val data: ShortArray) : PcmSamples {
    override val frames: Long = (data.size / channels).toLong()
    override fun sample(frame: Long, channel: Int): Float {
        if (frame < 0 || frame >= frames) return 0f
        return data[(frame * channels).toInt() + channel] / 32768f
    }
}

/**
 * A synthetic WAV (48 kHz, stereo, 16-bit) for one preview segment. Frame k
 * carries the timeline frame number `start + k`: low 16 bits on the left
 * channel, high 16 bits on the right. It is never heard; the sink's
 * [PreviewMixProcessor] replaces each frame with the mix at that number.
 */
object PreviewClock {
    const val scheme = "youroll-clock"
    private const val headerSize = 44

    fun uri(start: Long, frames: Long): Uri = Uri.parse("$scheme://frames/$start/$frames")

    fun frameNumber(left: Short, right: Short): Long = (left.toLong() and 0xFFFF) or ((right.toLong() and 0xFFFF) shl 16)

    private fun header(frames: Long): ByteArray {
        val data = frames * 4
        return ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(PreviewAudioMix.sampleRate)
            .putInt(PreviewAudioMix.sampleRate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(data.toInt()).array()
    }

    /** Serves [uri]s; any other URI fails to open. */
    class Source : DataSource {
        private var uri: Uri? = null
        private var start = 0L
        private var header = ByteArray(0)
        private var position = 0L
        private var end = 0L

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            val parts = dataSpec.uri.pathSegments
            if (dataSpec.uri.scheme != scheme || parts.size != 2) throw java.io.IOException("Not a preview clock: ${dataSpec.uri}")
            uri = dataSpec.uri
            start = parts[0].toLong()
            val frames = parts[1].toLong()
            header = header(frames)
            val total = headerSize + frames * 4
            position = dataSpec.position
            end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) min(total, position + dataSpec.length) else total
            return end - position
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= end) return C.RESULT_END_OF_INPUT
            val count = min(length.toLong(), end - position).toInt()
            for (i in 0 until count) {
                val at = position + i
                buffer[offset + i] = if (at < headerSize) header[at.toInt()] else {
                    val byte = at - headerSize
                    val value = start + byte / 4
                    // L low, L high, R low, R high: bytes 0..3 of the 32-bit frame number.
                    (value ushr (8 * (byte % 4).toInt())).toByte()
                }
            }
            position += count
            return count
        }

        override fun getUri(): Uri? = uri
        override fun close() { uri = null }
    }
}

/**
 * The preview sink's first processor: turns [PreviewClock] frames into the mix
 * of [mix] at those frames. Anything that is not the clock format is refused,
 * so the clock itself can never reach the speaker.
 */
class PreviewMixProcessor(private val live: LiveAudioGain, private val report: Boolean = false) : BaseAudioProcessor() {
    @Volatile var mix: PreviewAudioMix? = null
    private var scratch = FloatArray(0)
    private var lastReport = 0L
    /** Debug probe for on-device checks: the last mixed frame, the peak and the piece count. */
    @Volatile var lastFrame = -1L
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2 ||
            inputAudioFormat.sampleRate != PreviewAudioMix.sampleRate) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frames = remaining / 4
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val numbers = LongArray(frames) { frameNumber(input) }
        inputBuffer.position(inputBuffer.position() + (remaining - frames * 4)) // Never a partial frame in practice.
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val current = mix
        val snapshot = live.snapshot()
        var pieces = 0
        var discontinuities = 0
        var run = 0
        while (run < frames) {
            var length = 1
            while (run + length < frames && numbers[run + length] == numbers[run] + length) length++
            if (run > 0) discontinuities++
            if (current != null) pieces = max(pieces, current.render(numbers[run], length, scratch, run * 2, snapshot))
            else java.util.Arrays.fill(scratch, run * 2, (run + length) * 2, 0f)
            run += length
        }
        val output = replaceOutputBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        for (i in 0 until frames * 2) {
            val value = scratch[i]
            if (value > peak) peak = value else if (-value > peak) peak = -value
            output.putShort((value * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
        lastFrame = numbers[frames - 1]
        val now = System.nanoTime()
        if (report && now - lastReport > 500_000_000L) {
            lastReport = now
            android.util.Log.d("YouRollPreviewAudio", "mix t=%.3f..%.3f frames=%d pieces=%d peak=%.3f breaks=%d live=%s".format(java.util.Locale.ROOT,
                numbers[0] / PreviewAudioMix.sampleRate.toDouble(), (numbers[frames - 1] + 1) / PreviewAudioMix.sampleRate.toDouble(),
                frames, pieces, peak, discontinuities, snapshot.gain?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "-"))
        }
    }

    private fun frameNumber(input: ByteBuffer): Long {
        val left = input.short
        val right = input.short
        return PreviewClock.frameNumber(left, right)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {}
}
