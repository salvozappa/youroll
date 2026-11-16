// Port of YouRec/AudioGain.swift (partial: AudioGainGrid, AudioGainDrag, LiveAudioGain)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// AudioGainMix (AVAudioMix taps) has an Android counterpart in PreviewAudio.kt.
// TODO(port): ExportAudioLevel, AudioHeadroom (clipping analysis) and
// HeadroomSlider's clipping tint. See TODO.md.
package com.salvozappa.youroll

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// All gain controls share the same absolute grid; zero is never an implicit mute.
object AudioGainGrid {
    fun snap(db: Double): Double = min(12.0, max(-40.0, swiftRound(db * 2) / 2))
    fun common(items: List<AudioTimelineItem>): Double? {
        val first = items.firstOrNull()?.volumeDB ?: return null
        return if (items.all { abs(it.volumeDB - first) < 0.001 }) first else null
    }
}

// Shared absolute half-dB grid with a small zero detent. Mixed selections stay
// untouched until an intentional drag chooses a common level.
class AudioGainDrag(value: Double?, val levels: ClosedFloatingPointRange<Double>) {
    val initial: Double? = value
    var value: Double? = value
        private set
    var continued = 0.0
        private set

    fun update(distance: Double) {
        val base = initial ?: (if (distance >= 0) levels.endInclusive else levels.start)
        val delta = if (initial == null) max(0.0, abs(distance) - 12) * (if (distance >= 0) 0.125 else -0.125) else distance * 0.125
        val raw = base + delta + continued
        var next: Double? = if (initial == null && abs(distance) < 12 && continued == 0.0) null else AudioGainGrid.snap(raw)
        val current = value
        if (current != null && next != null && abs(raw - current) < (if (current == 0.0) 0.6 else 0.4)) next = current
        value = next
    }

    fun stepAtEdge(delta: Double) {
        val next = AudioGainGrid.snap((value ?: (if (delta > 0) levels.endInclusive else levels.start)) + delta)
        if (next == value) return
        continued += delta; value = next
    }
}

// One control per preview player. The audio thread snapshots it once per buffer;
// the lock only protects a small immutable value, never decoding or UI work.
// Swift additionally smooths a level change over 10 ms (`CutAudioEnvelope`
// `liveScale`/`liveOffset`); here a new level applies from the next buffer.
class LiveAudioGain {
    data class Snapshot(
        val revision: Int = 0,
        val selection: TimelineSelection = TimelineSelection(),
        val gain: Double? = null,
    ) {
        fun applying(source: AudioEnvelopeSegment): AudioEnvelopeSegment {
            val level = gain ?: return source
            if (source.owner == null || source.owner !in selection.audio) return source
            return source.copy(gainWindow = selection.range?.let { it.start.seconds..it.end.seconds }
                ?: Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY, gainInside = level)
        }
    }

    private val lock = Any()
    private var value = Snapshot()

    fun update(db: Double?, selection: TimelineSelection) = synchronized(lock) {
        value = Snapshot(value.revision + 1, selection, db?.let { 10.0.pow(it / 20) })
    }

    fun snapshot(): Snapshot = synchronized(lock) { value }
}
