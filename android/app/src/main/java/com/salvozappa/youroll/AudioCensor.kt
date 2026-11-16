// Port of YouRec/AudioCensor.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// A censor replaces the sound of the selected audio items in a range with a
// generated tone. The tone is a pure function of the project clock, so the
// editor preview (PreviewAudio.kt) and the export (CutAudioEnvelope.kt
// `AudioEnvelopeProcessor`) produce the same samples whatever the buffer order.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

@Serializable
enum class CensorWaveform { sine, triangle, square }

@Serializable
enum class CensorEnvelope { short, soft, pulse }

@Serializable
data class AudioCensorSettings(
    val frequency: Double = 1000.0,
    val levelDB: Double = -18.0,
    val waveform: CensorWaveform = CensorWaveform.sine,
    val envelope: CensorEnvelope = CensorEnvelope.short,
) {
    val valid: Boolean get() = frequency.isFinite() && frequency in 100.0..4000.0 && levelDB.isFinite() && levelDB in -36.0..-6.0
    val amplitude: Double get() = 10.0.pow(levelDB / 20)

    /**
     * Phase is derived from time, not callback order: seeking and exporting
     * produce the same samples. Triangle harmonics stop below Nyquist; a
     * polynomial band-limited step smooths the square wave's discontinuities.
     */
    fun oscillator(time: Double, sampleRate: Double): Double {
        val phase = 2 * PI * remainder(time * frequency, 1.0)
        if (waveform == CensorWaveform.sine) return sin(phase)
        if (waveform == CensorWaveform.square) {
            val raw = remainder(time * frequency, 1.0)
            val t = if (raw < 0) raw + 1 else raw
            val dt = min(0.49, frequency / sampleRate)
            fun blep(t: Double): Double {
                if (t < dt) { val x = t / dt; return x + x - x * x - 1 }
                if (t > 1 - dt) { val x = (t - 1) / dt; return x * x + x + x + 1 }
                return 0.0
            }
            return (if (t < 0.5) 1.0 else -1.0) + blep(t) - blep(remainder(t + 0.5, 1.0))
        }
        val maximum = min(17, floor(sampleRate * 0.45 / frequency).toInt())
        var value = 0.0
        var scale = 0.0
        var harmonic = 1
        while (harmonic <= max(1, maximum)) {
            val n = harmonic.toDouble()
            val coefficient = (if ((harmonic / 2) % 2 == 0) 1.0 else -1.0) / (n * n)
            value += coefficient * sin(n * phase); scale += abs(coefficient)
            harmonic += 2
        }
        return value / max(1.0, scale)
    }

    fun envelopeValue(time: Double, duration: Double): Double {
        if (!(time >= 0 && time < duration)) return 0.0
        val fade = min(duration / 2, if (envelope == CensorEnvelope.soft) 0.06 else 0.008)
        fun smooth(value: Double): Double { val x = min(1.0, max(0.0, value)); return x * x * (3 - 2 * x) }
        var result = smooth(time / max(0.00001, fade)) * smooth((duration - time) / max(0.00001, fade))
        if (envelope == CensorEnvelope.pulse) {
            val t = remainder(time, 0.25)
            result *= smooth(t / 0.008) * smooth((0.18 - t) / 0.008)
        }
        return result
    }

}

/** Swift `truncatingRemainder(dividingBy:)` (the sign follows the dividend, like Kotlin `%`). */
private fun remainder(value: Double, divisor: Double): Double = value % divisor

@Serializable
data class AudioCensorRegion(
    val id: UUID = newUUID(),
    val interval: EditInterval,
    val phaseOrigin: EditTime,
    val settings: AudioCensorSettings,
    val envelopeInterval: EditInterval? = null,
) {
    fun sliced(part: EditInterval): AudioCensorRegion? {
        val intersection = interval.intersection(part) ?: return null
        val envelope = envelopeInterval ?: interval
        return copy(
            interval = EditInterval(intersection.start - part.start, intersection.end - part.start),
            envelopeInterval = EditInterval(envelope.start - part.start, envelope.end - part.start),
            phaseOrigin = phaseOrigin - part.start,
        )
    }
}

/**
 * Absolute render clock. The audible generator is owned by one selected item
 * for each shared region; overlapping selected tracks are all silenced but
 * cannot stack identical beeps and unexpectedly multiply their level.
 */
data class AudioCensorSpan(
    val start: Double,
    val end: Double,
    val phaseOrigin: Double,
    val envelopeStart: Double,
    val envelopeEnd: Double,
    val settings: AudioCensorSettings,
    val emitsTone: Boolean,
) {
    fun originalGain(time: Double): Double {
        if (time >= start && time < end) return 0.0
        return min(1.0, max(0.0, if (time < start) (start - time) / 0.005 else (time - end) / 0.005))
    }

    fun tone(time: Double, sampleRate: Double): Double {
        if (!emitsTone || time < start || time >= end) return 0.0
        return settings.amplitude * settings.envelopeValue(time - envelopeStart, envelopeEnd - envelopeStart) *
            settings.oscillator(time - phaseOrigin, sampleRate)
    }
}

/** Swift `mutating func censor(_:selection:effectID:removingOnly:)`. A null [settings] only removes. */
fun TrackTimeline.censor(settings: AudioCensorSettings?, selection: TimelineSelection, effectID: UUID = newUUID(), removingOnly: UUID? = null) {
    audio = audio.map { item ->
        if (item.id !in selection.audio) return@map item
        val range = selection.range
        val target = (if (range != null) item.interval.intersection(range) else item.interval) ?: return@map item
        val local = EditInterval(target.start - item.start, target.end - item.start)
        val regions = mutableListOf<AudioCensorRegion>()
        for (region in item.censors) {
            if (!((removingOnly == null || region.id == removingOnly) && region.interval.intersection(local) != null)) { regions += region; continue }
            val envelope = region.envelopeInterval ?: region.interval
            if (region.interval.start < local.start) {
                regions += region.copy(interval = region.interval.copy(end = local.start), envelopeInterval = EditInterval(envelope.start, local.start))
            }
            if (region.interval.end > local.end) {
                regions += region.copy(interval = region.interval.copy(start = local.end), envelopeInterval = EditInterval(local.end, envelope.end))
            }
        }
        if (settings != null) {
            // All pieces of one operation share the event's clock, even
            // when a later clip begins halfway through the censor range.
            val envelope = range?.let { EditInterval(it.start - item.start, it.end - item.start) }
            regions += AudioCensorRegion(effectID, local, envelope?.start ?: local.start, settings, envelope)
        }
        item.withCensors(regions.sortedBy { it.interval.start })
    }
}

/** Swift `censorSpans(for:)`: the item's censors on the project clock, split where a louder selected track carries the tone. */
fun TrackTimeline.censorSpans(item: AudioTimelineItem): List<AudioCensorSpan> = item.censors.flatMap { region ->
    val start = item.start + region.interval.start
    val end = item.start + region.interval.end
    val envelope = region.envelopeInterval ?: region.interval
    val itemOrder = audio.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
    val others = audio.withIndex().filter { (index, other) ->
        !other.muted && (other.volumeDB > item.volumeDB || (other.volumeDB == item.volumeDB && index < itemOrder))
    }.flatMap { (_, other) ->
        other.censors.filter { it.id == region.id && it.settings == region.settings }
            .map { EditInterval(other.start + it.interval.start, other.start + it.interval.end) }
    }.mapNotNull { it.intersection(EditInterval(start, end)) }
    val points = (listOf(start, end) + others.flatMap { listOf(it.start, it.end) }).toSet().sorted()
    points.zipWithNext { a, b ->
        AudioCensorSpan(a.seconds, b.seconds, (item.start + region.phaseOrigin).seconds,
            (item.start + envelope.start).seconds, (item.start + envelope.end).seconds, region.settings,
            emitsTone = others.none { it.contains(a) })
    }
}
