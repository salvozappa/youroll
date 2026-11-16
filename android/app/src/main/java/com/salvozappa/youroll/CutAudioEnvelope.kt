// Port of YouRec/CutAudioEnvelope.swift (`AudioEnvelopeSegment` and the
// segment branch of `CutAudioEnvelope.State.sampleGains`)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS installs CutAudioEnvelope as an MTAudioProcessingTap on every audio track
// of the composition, for the preview and the export alike. Here the same
// per-sample function, [AudioEnvelopeSegment.gain] with its censors, is
// evaluated by the export's [AudioEnvelopeProcessor] (one per audio piece) and
// by the preview mixer (PreviewAudio.kt `PreviewAudioMix.render`).
//
// Not ported (TODO.md): the clip-boundary variant (`State(clips:)`, used by the
// single-track legacy path), the initial-owner crossfade (`initialOwner`,
// `initialVolume`, `initialGain*`), the 10 ms smoothing of a live volume change
// (`liveScale`/`liveOffset`) and `AudioEnvelopeClock` (Media3 delivers every
// frame in order, so a frame counter is the clock).
package com.salvozappa.youroll

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * The gain of one audio piece on the project clock. Times are edit seconds.
 * Ported terms: the 8 ms de-click ramps at discontinuous cuts, the item's user
 * fades (`AudioEdgeFade.gain` measured from the item's edges), its volume, and
 * the live gain window of a volume audition (`gainWindow`/`gainInside`, set by
 * `LiveAudioGain.Snapshot.applying` in the preview only), the transition
 * ramps and the item's censors ([censors], from `TrackTimeline.censorSpans`).
 * Export and preview evaluate this same function (see PreviewAudio.kt).
 */
data class AudioEnvelopeSegment(
    val start: Double,
    val end: Double,
    val fadeIn: Double,
    val fadeOut: Double,
    val clipStart: Double,
    val clipEnd: Double,
    val userFadeIn: AudioEdgeFade? = null,
    val userFadeOut: AudioEdgeFade? = null,
    val volume: Double = 1.0,
    /** The audio item this piece plays (Swift `owner`). */
    val owner: UUID? = null,
    val gainWindow: ClosedFloatingPointRange<Double>? = null,
    val gainInside: Double? = null,
    /** A transition's sound fade-in and fade-out, project seconds (Swift `inRamp`/`outRamp`). */
    val inRamp: ClosedFloatingPointRange<Double>? = null,
    val outRamp: ClosedFloatingPointRange<Double>? = null,
    /** Swift `censors`: where this piece's sound is replaced by a tone. */
    val censors: List<AudioCensorSpan> = emptyList(),
) {
    fun gain(time: Double): Double {
        if (time < start || time >= end) return 0.0
        var gain = min(1.0, max(0.0, min(if (fadeIn > 0) (time - start) / fadeIn else 1.0, if (fadeOut > 0) (end - time) / fadeOut else 1.0)))
        inRamp?.let { gain *= min(1.0, max(0.0, (time - it.start) / max(0.008, it.endInclusive - it.start))) }
        outRamp?.let { gain *= min(1.0, max(0.0, (it.endInclusive - time) / max(0.008, it.endInclusive - it.start))) }
        gain *= min(userFadeIn?.gain(time - clipStart) ?: 1.0, userFadeOut?.gain(clipEnd - time) ?: 1.0)
        val range = gainWindow ?: return gain * volume
        // A gain-only boundary is a short ramp, never a fade through silence.
        val enter = min(1.0, max(0.0, (time - range.start) / 0.01))
        val leave = min(1.0, max(0.0, (time - range.endInclusive) / 0.01))
        val weight = enter * (1 - leave)
        return gain * (volume * (1 - weight) + (gainInside ?: volume) * weight)
    }

    /** Swift `sampleGains`, censor part: how much of the original sound remains at [time] (0 inside a censor, 5 ms ramps around it). */
    fun original(time: Double): Double {
        if (censors.isEmpty()) return 1.0
        var original = 1.0
        for (censor in censors) original = min(original, censor.originalGain(time))
        return original
    }

    /** Swift `sampleGains`, censor part: the generated tone at [time], before the piece's [gain]. */
    fun tone(time: Double, sampleRate: Double): Double {
        if (censors.isEmpty()) return 0.0
        var tone = 0.0
        for (censor in censors) tone += censor.tone(time, sampleRate)
        return tone
    }
}

/**
 * Applies an [AudioEnvelopeSegment] to one audio piece (iOS installs
 * CutAudioEnvelope as an audio tap): every sample becomes
 * `sample × gain × original + gain × tone`, as in the Swift tap. The piece's
 * first sample is at `envelope.start` on the project clock.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AudioEnvelopeProcessor(private val envelope: AudioEnvelopeSegment) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val channels = format.channelCount
        val rate = format.sampleRate.toDouble()
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val count = remaining / format.bytesPerFrame
        for (frame in 0 until count) {
            val time = envelope.start + (frames + frame) / rate
            val level = envelope.gain(time)
            val gain = level * envelope.original(time)
            val tone = if (level == 0.0) 0.0 else level * envelope.tone(time, rate)
            for (channel in 0 until channels) {
                if (float) output.putFloat((input.getFloat() * gain + tone).toFloat())
                else output.putShort((input.getShort() * gain + tone * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        frames += count
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) { frames = 0 }
    override fun onReset() { frames = 0 }
}
