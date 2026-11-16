// Port of YouRec/SubtitleTranscriber.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS reads the edited mix with AVAssetReaderAudioMixOutput at 16 kHz mono and
// runs Qwen3-ASR on MLX. YouRoll reads the same edited mix the preview plays
// (`PreviewAudioMix`: every audible item with its gain, fades, censors and
// pitch-kept speed stretches, 48 kHz stereo), folds it to mono and decimates
// it to 16 kHz with a windowed-sinc low-pass, then runs the same model through
// antirez's qwen-asr C engine (`QwenASR`, cpp/QwenAsrJNI.c). The speech plan,
// chunking, word estimates, checkpoints and messages follow Swift. Inference
// runs on the CPU only; no audio or text leaves the device.
package com.salvozappa.youroll

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TranscriptionCheckpoint(
    val range: ClosedFloatingPointRange<Double>,
    val language: String,
    val plan: List<SubtitleSpeechChunk>,
    val completed: Int,
    val words: List<SubtitleWord>,
)

/** antirez's qwen-asr behind JNI (cpp/QwenAsrJNI.c). One model per handle; not thread-safe. */
object QwenASR {
    init { System.loadLibrary("youroll_qwen_asr") }

    @JvmStatic external fun nativeLoad(directory: String, threads: Int): Long
    @JvmStatic external fun nativeSetLanguage(handle: Long, language: String?): Boolean
    @JvmStatic external fun nativeTranscribe(handle: Long, samples: FloatArray): ByteArray?
    @JvmStatic external fun nativeFree(handle: Long)

    /** Worker threads: the big cores of a phone, all four of the emulator. */
    val threads: Int get() = min(4, max(1, Runtime.getRuntime().availableProcessors()))
}

class SubtitleTranscriber private constructor() {
    companion object {
        val shared = SubtitleTranscriber()
        private const val rate = 16_000
    }

    private val busy = Mutex()
    /** Wall seconds per second of audio on this device. */
    private var recentDecodingRate: Double? = null

    suspend fun transcribe(context: Context, mix: PreviewAudioMix, range: ClosedFloatingPointRange<Double>, language: String,
                           resuming: TranscriptionCheckpoint? = null,
                           saveCheckpoint: suspend (TranscriptionCheckpoint) -> Unit = {},
                           progress: (Double, String) -> Unit): List<SubtitleWord> {
        if (!busy.tryLock()) throw SubtitleError("A transcription is already running.")
        try {
            if (!SubtitleModelPack.installed(context)) throw SubtitleError("Download the speech model first.")
            val scratch = File(context.cacheDir, "youroll-subtitles-" + newUUID().uuidString + ".pcm")
            try {
                progress(0.0, "Reading selected audio…")
                val levels = extract(mix, range, scratch) { progress(0.05 * it, "Reading selected audio…") }
                coroutineContext.ensureActive()
                val chunks = SubtitleSpeechPlan.chunks(levels, range.endInclusive - range.start)
                if (chunks.isEmpty()) throw SubtitleError("No speech was detected in this range. Try a range with audible speech.")
                progress(0.05, "Loading speech model…")
                // No model is retained after this job, including cancellation, and no service/network is used for inference.
                return withContext(Dispatchers.Default) {
                    val handle = QwenASR.nativeLoad(SubtitleModelPack.directory(context).path, QwenASR.threads)
                    if (handle == 0L) throw SubtitleError("The speech model could not be loaded. Remove it and download it again.")
                    try {
                        if (language != "Auto" && !QwenASR.nativeSetLanguage(handle, language)) QwenASR.nativeSetLanguage(handle, null)
                        decode(handle, scratch, chunks, range, language, resuming, saveCheckpoint, progress)
                    } finally {
                        QwenASR.nativeFree(handle)
                    }
                }
            } finally {
                scratch.delete()
            }
        } finally {
            busy.unlock()
        }
    }

    private suspend fun decode(handle: Long, scratch: File, chunks: List<SubtitleSpeechChunk>, range: ClosedFloatingPointRange<Double>, language: String,
                               checkpoint: TranscriptionCheckpoint?, saveCheckpoint: suspend (TranscriptionCheckpoint) -> Unit,
                               progress: (Double, String) -> Unit): List<SubtitleWord> {
        val resume = checkpoint?.takeIf { it.range == range && it.language == language && it.plan == chunks }
        if (checkpoint != null && resume == null) progress(0.08, "Audio changed. Restarting transcription…")
        val words = (resume?.words ?: emptyList()).toMutableList()
        val first = resume?.completed ?: 0
        val totalAudio = chunks.drop(first).sumOf { it.range.endInclusive - it.range.start }
        val started = System.nanoTime()
        var decoded = 0.0
        RandomAccessFile(scratch, "r").use { file ->
            for (index in first until chunks.size) {
                coroutineContext.ensureActive()
                val chunk = chunks[index]
                val count = max(1, Math.round((chunk.range.endInclusive - chunk.range.start) * rate).toInt())
                val position = Math.round(chunk.range.start * rate)
                val samples = FloatArray(count)
                val bytes = ByteArray(count * 4)
                file.seek(position * 4)
                val read = max(0, file.read(bytes))
                ByteBuffer.wrap(bytes, 0, read - read % 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples, 0, read / 4)
                var message = "Transcribing section ${index + 1} of ${chunks.size}"
                val elapsed = (System.nanoTime() - started) / 1e9
                val pace = if (decoded > 0) elapsed / decoded else recentDecodingRate
                message += if (pace != null) " · about " + remaining(pace * max(0.0, totalAudio - decoded)) + " left" else " · estimating time…"
                progress(0.08 + 0.90 * index / chunks.size, message)
                val text = QwenASR.nativeTranscribe(handle, samples)?.toString(Charsets.UTF_8)?.trim() ?: ""
                coroutineContext.ensureActive()
                decoded += chunk.range.endInclusive - chunk.range.start
                if (decoded > 0) recentDecodingRate = (System.nanoTime() - started) / 1e9 / decoded
                val speech = chunk.speech.map { (it.start + range.start)..(it.endInclusive + range.start) }
                words += SubtitleTiming.estimate(text, speech)
                saveCheckpoint(TranscriptionCheckpoint(range, language, chunks, index + 1, words.toList()))
            }
        }
        if (words.isEmpty()) throw SubtitleError("The model did not find spoken words. Your previous subtitles are unchanged.")
        progress(1.0, "Subtitles ready")
        return words
    }

    /** Swift `Duration.formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated, maximumUnitCount: 1))`. */
    private fun remaining(seconds: Double): String {
        val value = max(1.0, Math.round(seconds).toDouble())
        return when {
            value >= 3600 -> "${Math.round(value / 3600)} hr"
            value >= 60 -> "${Math.round(value / 60)} min"
            else -> "${value.toLong()} sec"
        }
    }

    /**
     * The edited mix of [range] as 16 kHz mono float PCM in [output], with
     * 20 ms RMS levels. Gaps are silence, so offsets match project time.
     */
    private suspend fun extract(mix: PreviewAudioMix, range: ClosedFloatingPointRange<Double>, output: File, progress: (Double) -> Unit): List<Float> =
        withContext(Dispatchers.IO) {
            val decimator = Decimator()
            val limit = Math.round((range.endInclusive - range.start) * rate).toInt()
            val first = PreviewAudioMix.frame(range.start)
            val total = PreviewAudioMix.frame(range.endInclusive) - first
            val levels = ArrayList<Float>(limit / 320 + 1)
            var energy = 0.0
            var energyCount = 0
            var written = 0
            val block = PreviewAudioMix.sampleRate / 2
            val stereo = FloatArray(block * 2)
            val mono = FloatArray(block)
            val out = FloatArray(block / 3 + 2)
            val bytes = ByteBuffer.allocate(out.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            output.outputStream().buffered(1 shl 16).use { stream ->
                fun append(values: FloatArray, count: Int) {
                    val n = min(count, limit - written)
                    if (n <= 0) return
                    bytes.clear()
                    for (i in 0 until n) {
                        val sample = if (values[i].isFinite()) values[i] else 0f
                        bytes.putFloat(sample)
                        energy += sample * sample; energyCount += 1
                        if (energyCount == 320) { levels += sqrt(energy / 320).toFloat(); energy = 0.0; energyCount = 0 }
                    }
                    stream.write(bytes.array(), 0, n * 4)
                    written += n
                }
                var done = 0L
                while (done < total) {
                    ensureActive()
                    val frames = min(block.toLong(), total - done).toInt()
                    mix.render(first + done, frames, stereo)
                    for (i in 0 until frames) mono[i] = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f
                    append(out, decimator.push(mono, frames, out))
                    done += frames
                    progress(done.toDouble() / max(1L, total))
                }
                append(out, decimator.flush(out))
                while (written < limit) { java.util.Arrays.fill(out, 0f); append(out, min(out.size, limit - written)) }
            }
            if (energyCount > 0) levels += sqrt(energy / energyCount).toFloat()
            levels
        }

    /** 48 kHz → 16 kHz: a 63-tap Blackman-windowed sinc low-pass at 7.2 kHz, every third sample. */
    private class Decimator {
        private val taps: FloatArray = run {
            val n = 63
            val cutoff = 7_200.0 / 48_000.0
            val values = DoubleArray(n) { i ->
                val m = i - (n - 1) / 2.0
                val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
                val window = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
                sinc * window
            }
            val sum = values.sum()
            FloatArray(n) { (values[it] / sum).toFloat() }
        }
        private val delay = (taps.size - 1) / 2
        private val history = FloatArray(taps.size - 1)
        private var phase = 0
        private var primed = 0

        /** Filters [count] samples of [input] into [output]; returns how many output samples were written. */
        fun push(input: FloatArray, count: Int, output: FloatArray): Int {
            val signal = FloatArray(history.size + count)
            System.arraycopy(history, 0, signal, 0, history.size)
            System.arraycopy(input, 0, signal, history.size, count)
            var produced = 0
            for (i in 0 until count) {
                // Output aligned with the input clock: skip the filter's group delay once.
                if (primed < delay) { primed += 1; continue }
                if (phase == 0) {
                    var acc = 0f
                    val end = i + history.size
                    for (t in taps.indices) acc += taps[t] * signal[end - t]
                    output[produced++] = acc
                }
                phase = (phase + 1) % 3
            }
            System.arraycopy(signal, count, history, 0, history.size)
            return produced
        }

        fun flush(output: FloatArray): Int = push(FloatArray(delay), delay, output)
    }
}
