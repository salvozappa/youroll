// Port of YouRec/OfflineAudioPipeline.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Stage files are 48 kHz interleaved float WAVs (AudioFiles.kt) instead of
// Float32 CAFs. `AudioPreviewLevels.energies` reads an AVAudioMix on iOS; here
// it renders the preview mix (PreviewAudio.kt), see `AudioPreviewLevels.energies`.
// LavaSR (`studioVoice`) and VoiceFixer (`studioRestoration`) run Core ML models
// with no Android counterpart yet: their settings are kept, but an enabled
// stage with a non-zero amount is refused with a clear message (TODO.md).
package com.salvozappa.youroll

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioProcessingError private constructor(message: String) : Exception(message) {
    companion object {
        val unavailableModel get() = AudioProcessingError("This project's audio model is unavailable. Bypass that stage to use the original audio.")
        val deviceTooWarm get() = AudioProcessingError("Your device is too warm to continue processing. Let it cool, then try again. Completed source processing is kept.")
        val invalidFormat get() = AudioProcessingError("The audio could not be processed at its expected sample rate.")
        /** Android: a Core ML stage (LavaSR, VoiceFixer) that YouRoll can't run yet. */
        fun notAvailable(effect: AudioEffect) = AudioProcessingError(
            "${effect.kind.title} (${effect.modelName}) isn’t available in YouRoll yet. Bypass or remove that filter to process this audio; its saved settings are kept.")
    }
}

/** The model name the fader shows (Swift `FilledFaderControl.configure`). */
val AudioEffect.modelName: String
    get() = when (kind) {
        EffectKind.voiceIsolation -> if (model == AudioModel.deepFilter) "DeepFilterNet3" else "RNNoise"
        EffectKind.studioVoice -> "LavaSR"
        EffectKind.studioRestoration -> "VoiceFixer"
        else -> kind.title
    }

/** Android: true for a stage whose model has no Android runtime yet (LavaSR, VoiceFixer). */
val AudioEffect.awaitsAndroidModel: Boolean
    get() = kind == EffectKind.studioVoice || kind == EffectKind.studioRestoration

/** Heavy audio work stays off the UI thread and yields under thermal pressure. */
class AudioThermalGate(private val active: () -> Boolean = { true }) {
    private var checkedAt = Long.MIN_VALUE

    fun cooperate() {
        if (!active()) throw CancellationException("Audio processing cancelled")
        val now = System.nanoTime()
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < 200_000_000L) return
        checkedAt = now
        // PowerManager.THERMAL_STATUS_SEVERE = 3, CRITICAL = 4 (iOS .serious, .critical).
        when (thermalStatus()) {
            in 4..Int.MAX_VALUE -> throw AudioProcessingError.deviceTooWarm
            3 -> Thread.sleep(50)
        }
    }

    companion object {
        /** Installed by the app (MainActivity) from PowerManager; 0 (none) in JVM tests. */
        @Volatile var thermalStatus: () -> Int = { 0 }
    }
}

/**
 * File-backed stages bound RAM independently of recording length. Each stage
 * consumes the previous stage's exact PCM output, including repeated models.
 */
object OfflineAudioPipeline {
    // Bump adapter/DSP revisions when processing math changes. Model weights
    // and their runtime versions are also encoded in each stage's settings.
    const val cacheVersion = "source-audio-android-1|rnnoise-adapter-1|${DeepFilterNetEngine.runtime}|eq-1|float-wav-1"

    /** The first enabled stage this build cannot run, with the reason, or null. */
    fun unavailable(effects: List<AudioEffect>): AudioProcessingError? {
        val enabled = effects.filter { it.enabled }
        if (!enabled.all { it.supported }) return AudioProcessingError.unavailableModel
        enabled.firstOrNull { it.awaitsAndroidModel && it.amount != 0.0 }?.let { return AudioProcessingError.notAvailable(it) }
        return null
    }

    /**
     * Swift `process(input:effects:output:checkpoints:progress:)`. [active]
     * stops the work (CancellationException) like Swift task cancellation.
     */
    fun process(input: File, effects: List<AudioEffect>, output: File, checkpoints: List<File> = emptyList(),
                active: () -> Boolean = { true }, progress: (Double) -> Unit = {}): Float {
        unavailable(effects)?.let { throw it }
        val stages = effects.filter { it.enabled && !(it.kind.isNeural && it.amount == 0.0) }
        if (output.exists()) throw FileAlreadyExistsException(output.path)
        val inputInfo = AudioFile.info(input) ?: throw AudioProcessingError.invalidFormat
        val gate = AudioThermalGate(active)
        val intermediates = mutableListOf<File>()
        try {
            var current = input
            for ((index, effect) in stages.withIndex()) {
                gate.cooperate()
                val checkpoint = checkpoints.getOrNull(index)
                if (checkpoint != null) {
                    val cached = AudioFile.info(checkpoint)
                    val peak = runCatching { peakFile(checkpoint).readText().toFloat() }.getOrNull()
                    if (cached != null && cached.frames == inputInfo.frames && cached.sampleRate == AudioFile.sampleRate &&
                        cached.channels == inputInfo.channels && peak != null && peak.isFinite()) {
                        if (current in intermediates) current.delete()
                        current = checkpoint; progress((index + 1).toDouble() / max(1, stages.size)); continue
                    }
                }
                val next = File(output.parentFile, newUUID().uuidString + ".wav")
                intermediates += next
                val report: (Double) -> Unit = { progress((index + it) / max(1, stages.size)) }
                when (effect.kind) {
                    EffectKind.voiceIsolation -> when (effect.model) {
                        AudioModel.deepFilter -> DeepFilterVoiceProcessor.process(current, next, effect.amount.toFloat(), gate, report)
                        AudioModel.rnnoise -> denoise(current, next, effect.amount.toFloat(), gate, report)
                        else -> throw AudioProcessingError.unavailableModel
                    }
                    EffectKind.studioVoice, EffectKind.studioRestoration -> throw AudioProcessingError.notAvailable(effect)
                    else -> dsp(current, next, effect, gate, report)
                }
                if (current in intermediates) current.delete()
                if (checkpoint != null) {
                    val measured = peak(next, gate)
                    try {
                        Files.move(next.toPath(), checkpoint.toPath())
                    } catch (failure: FileAlreadyExistsException) {
                        // A different chain may have completed this same prefix.
                        // Never replace its immutable audio, including open players.
                        val existing = AudioFile.info(checkpoint)
                        if (existing == null || existing.frames != inputInfo.frames || existing.channels != inputInfo.channels) throw failure
                        next.delete()
                    }
                    writeAtomically(peakFile(checkpoint), measured.toString())
                    current = checkpoint
                } else current = next
            }
            gate.cooperate()
            val measuredPeak = peak(current, gate)
            gate.cooperate()
            // Copy refuses to overwrite; this also makes zero amount/bypass exact.
            if (current == input || current in checkpoints) Files.copy(current.toPath(), output.toPath())
            else Files.move(current.toPath(), output.toPath())
            progress(1.0)
            return measuredPeak
        } finally {
            for (file in intermediates) if (file != output) file.delete()
        }
    }

    fun peakFile(stage: File) = File(stage.parentFile, stage.nameWithoutExtension + ".peak")

    fun writeAtomically(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + "." + newUUID().uuidString + ".tmp")
        temporary.writeText(text)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun files(input: File, output: File): Pair<AudioFile.Reader, AudioFile.Writer> {
        val source = AudioFile.Reader(input)
        if (source.info.sampleRate != AudioFile.sampleRate || source.channels <= 0) { source.close(); throw AudioProcessingError.invalidFormat }
        return source to AudioFile.Writer(output, source.channels)
    }

    private fun dsp(input: File, output: File, effect: AudioEffect, gate: AudioThermalGate, progress: (Double) -> Unit) {
        val (source, target) = files(input, output)
        source.use { target.use {
            val channels = source.channels
            val buffer = FloatArray(4096 * channels)
            val processor = AudioChainProcessor(listOf(effect), channels = channels)
            while (source.framePosition < source.length) {
                gate.cooperate()
                val count = source.read(buffer, 4096)
                if (count <= 0) throw AudioProcessingError.invalidFormat
                for (frame in 0 until count) for (channel in 0 until channels) {
                    val index = frame * channels + channel
                    buffer[index] = processor.sample(buffer[index], channel)
                }
                target.write(buffer, count)
                progress(source.framePosition.toDouble() / max(1, source.length))
            }
        } }
    }

    private fun denoise(input: File, output: File, amount: Float, gate: AudioThermalGate, progress: (Double) -> Unit) {
        val (source, target) = files(input, output)
        source.use { target.use {
            val channels = source.channels
            val states = ArrayList<Denoiser>()
            try {
                repeat(channels) { states += Denoiser.create() }
                val interleaved = FloatArray(480 * channels)
                val data = Array(channels) { FloatArray(480) }
                val previous = Array(channels) { FloatArray(480) }
                val wet = FloatArray(480)
                val rendered = FloatArray(480 * channels)
                var previousCount = 0
                // The first model frame is latency. Feeding one final padded frame
                // drains the final real samples, including a partial (<480) input frame.
                while (source.framePosition < source.length || previousCount > 0) {
                    gate.cooperate()
                    java.util.Arrays.fill(interleaved, 0f)
                    val count = if (source.framePosition < source.length) source.read(interleaved, 480) else 0
                    for (channel in 0 until channels) {
                        for (frame in 0 until 480) data[channel][frame] = interleaved[frame * channels + channel]
                        states[channel].process(data[channel], 0, wet)
                        for (frame in 0 until previousCount) {
                            val dry = previous[channel][frame]
                            rendered[frame * channels + channel] = dry + amount * (wet[frame] - dry)
                        }
                        data[channel].copyInto(previous[channel])
                    }
                    if (previousCount > 0) target.write(rendered, previousCount)
                    previousCount = count
                    progress(source.framePosition.toDouble() / max(1, source.length))
                }
            } finally {
                for (state in states) state.close()
            }
        } }
    }

    fun peak(file: File, gate: AudioThermalGate = AudioThermalGate()): Float {
        AudioFile.Reader(file).use { source ->
            val buffer = FloatArray(4096 * source.channels)
            var peak = 0f
            while (source.framePosition < source.length) {
                gate.cooperate()
                val count = source.read(buffer, 4096)
                if (count <= 0) throw AudioProcessingError.invalidFormat
                for (index in 0 until count * source.channels) {
                    val value = buffer[index]
                    if (!value.isFinite()) throw AudioProcessingError.invalidFormat
                    peak = max(peak, abs(value))
                }
            }
            return peak
        }
    }
}

/**
 * 400 ms energy windows for a speech-oriented, gated comparison. This is a
 * preview loudness estimate, not a claimed LUFS/EBU broadcast measurement.
 */
@kotlinx.serialization.Serializable
class SourceListeningLevels(val original: FloatArray, val processed: FloatArray) {
    data class Window(val original: Float, val processed: Float, val weight: Double)

    companion object {
        const val window = 0.4

        fun energies(file: File, active: () -> Boolean = { true }): FloatArray {
            AudioFile.Reader(file).use { source ->
                val frames = (source.info.sampleRate * window).toInt()
                val buffer = FloatArray(frames * source.channels)
                val result = ArrayList<Float>()
                val gate = AudioThermalGate(active)
                while (source.framePosition < source.length) {
                    gate.cooperate()
                    val count = source.read(buffer, frames)
                    if (count <= 0) break
                    var sum = 0f
                    for (index in 0 until count * source.channels) sum += buffer[index] * buffer[index]
                    result += sum / (count * source.channels)
                }
                return result.toFloatArray()
            }
        }

        /** Swift `gains(windows:)`. */
        fun gains(windows: List<Window>): Pair<Float, Float> {
            // Compare audio that survives in BOTH versions. Noise removed by a
            // denoiser is not a voice-level reference: matching it would attenuate
            // Original toward silence and hide the very difference being auditioned.
            // The -60 dBFS floor rejects near-silence; >20 dB reduction identifies
            // heavily suppressed windows. This is an energy gate, not a speech VAD.
            val audible = windows.filter { it.original > 0.000001f && it.processed > 0.000001f && it.processed >= it.original * 0.01f }
            val duration = audible.sumOf { it.weight }
            if (!(duration > 0)) return 1f to 1f
            val mean = audible.sumOf { it.original.toDouble() * it.weight } / duration
            val processedMean = audible.sumOf { it.processed.toDouble() * it.weight } / duration
            val speech = audible.filter { it.original.toDouble() >= mean * 0.1 && it.processed.toDouble() >= processedMean * 0.1 }
            val original = speech.sumOf { it.original.toDouble() * it.weight }
            val processed = speech.sumOf { it.processed.toDouble() * it.weight }
            if (!(original > 0 && processed > 0)) return 1f to 1f
            val target = min(original, processed)
            return max(0.125, sqrt(target / original)).toFloat() to max(0.125, sqrt(target / processed)).toFloat()
        }
    }
}

/**
 * Comparison must never cancel an explicitly requested gain. Measurements
 * cover the actual audible mix in the inspector's range, including overlaps,
 * trims, track volume and other effects left active by Bypass.
 */
object AudioPreviewLevels {
    fun allowsMatching(effects: List<AudioEffect>): Boolean = effects.none { it.enabled && it.kind == EffectKind.gain }

    /**
     * Swift `energies(asset:mix:range:)`: mean square energy of the stereo mix
     * per 400 ms window over [range] seconds. Android renders the preview mix
     * itself, which is the signal the inspector plays.
     */
    fun energies(mix: PreviewAudioMix, range: ClosedFloatingPointRange<Double>, active: () -> Boolean = { true }): FloatArray {
        if (!(range.endInclusive > range.start)) return FloatArray(0)
        val first = PreviewAudioMix.frame(range.start)
        val last = PreviewAudioMix.frame(range.endInclusive)
        val window = (PreviewAudioMix.sampleRate * SourceListeningLevels.window).toInt()
        val buffer = FloatArray(window * 2)
        val result = ArrayList<Float>()
        var frame = first
        while (frame < last) {
            if (!active()) throw CancellationException("Level measurement cancelled")
            val count = min(window.toLong(), last - frame).toInt()
            mix.render(frame, count, buffer)
            var energy = 0.0
            for (index in 0 until count * 2) energy += buffer[index].toDouble() * buffer[index]
            result += (energy / (count * 2)).toFloat()
            frame += count
        }
        return result.toFloatArray()
    }

    fun gains(original: FloatArray, processed: FloatArray): Pair<Float, Float> =
        SourceListeningLevels.gains(original.zip(processed).map { SourceListeningLevels.Window(it.first, it.second, SourceListeningLevels.window) })
}

/** The number of 400 ms listening windows of [duration] seconds. */
fun listeningWindows(duration: Double): Int = ceil(duration / SourceListeningLevels.window).toInt()
