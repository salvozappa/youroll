// Port of YouRec/EffectApplication.swift (partial: EffectPreviewWindow)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Not ported (TODO.md): `offlineVideoEffects` and `EffectApplicationReceipt`
// (the editor's resumable effect application).
package com.salvozappa.youroll

import kotlin.math.max
import kotlin.math.min

/**
 * An inspector samples the draft near the playhead. Its source ranges are
 * scoped by timeline occurrence, so a repeated or overlapping take stays distinct.
 */
class EffectPreviewWindow(val range: ClosedFloatingPointRange<Double>, timeline: TrackTimeline) {
    private val sources: Map<UUID, ClosedFloatingPointRange<Double>>

    init {
        val window = EditInterval.of(range.start, range.endInclusive)
        val spans = HashMap<UUID, ClosedFloatingPointRange<Double>>()
        for (item in timeline.video) {
            val part = item.interval.intersection(window) ?: continue
            val runs = item.mapping.sliced(EditInterval(part.start - item.start, part.end - item.start)).runs
            val start = runs.firstOrNull()?.sourceStart ?: continue
            val end = runs.lastOrNull()?.sourceEnd ?: continue
            spans[item.id] = start..max(start, end)
        }
        sources = spans
    }

    fun analysisClip(clip: TimelineClip): TimelineClip? {
        val span = sources[clip.id] ?: return null
        if (clip.frozenFrame != null) return clip
        val sample = clip.copy(start = max(clip.start, span.start), end = min(clip.end, span.endInclusive))
        return if (sample.start < sample.end) sample else null
    }

    companion object {
        fun range(position: Double, selection: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double> {
            val anchor = min(selection.endInclusive, max(selection.start, position))
            return max(selection.start, anchor - 5)..min(selection.endInclusive, anchor + 5)
        }

        /** Swift `label(_:)`: "10 s preview", "0.4 s preview". */
        fun label(range: ClosedFloatingPointRange<Double>): String {
            val seconds = range.endInclusive - range.start
            val value = if (seconds < 1) "%.1f".format(java.util.Locale.ROOT, seconds) else Math.round(seconds).toString()
            return "$value s preview"
        }
    }
}

/** Swift `AudioPreviewWindow.listeningRange(at:selection:)` (SourceMediaCache.swift). */
object AudioPreviewWindow {
    fun listeningRange(position: Double, selection: ClosedFloatingPointRange<Double>) = EffectPreviewWindow.range(position, selection)
}
