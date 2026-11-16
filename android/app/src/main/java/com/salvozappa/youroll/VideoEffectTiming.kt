// Port of YouRec/VideoEffects/VideoEffectTiming.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// A hold is an inserted timeline segment referencing one original frame. It
// never removes or replaces the footage following the playhead.
@Serializable
data class FrozenFrameSegment(
    val sourceTime: Double,
    val duration: Double,
    val offset: Double = 0.0,
) {
    val valid: Boolean
        get() = sourceTime.isFinite() && sourceTime >= 0 && duration.isFinite() && duration > 0 && duration <= 10 &&
            offset.isFinite() && offset >= 0 && offset <= 10
}

data class EffectPlaybackSpan(val start: Double, val end: Double, val rate: Double) {
    val duration: Double get() = (end - start) / rate
}

private val timeEffects = setOf(VideoEffectID.undercranking, VideoEffectID.stepPrinting)

fun TimelineClip.sequenceRate(time: Double): Double =
    videoEffects.orEmpty().filter { it.enabled && it.start <= time && it.end > time }.fold(1.0) { result, effect ->
        if (effect.kind in timeEffects) result * effect.number("speed", time) else result
    }
val TimelineClip.sequenceRate: Double get() = sequenceRate(start)
val TimelineClip.mutesSourceAudio: Boolean get() = mutesSourceAudio(start)

fun TimelineClip.mutesSourceAudio(time: Double): Boolean {
    if (frozenFrame != null && videoEffects?.firstOrNull { it.kind == VideoEffectID.freezeFrame }?.choice("audio", time) == "Cut") return true
    return videoEffects?.any { it.enabled && it.kind == VideoEffectID.undercranking && it.start <= time && it.end > time && it.toggle("mute", time) } == true
}

fun TimelineClip.audioContinues(next: TimelineClip): Boolean =
    sourceID == next.sourceID && abs(end - next.start) < 0.00001 && mutesSourceAudio == next.mutesSourceAudio &&
        frozenFrame == null && next.frozenFrame == null

/**
 * One source clock for duration, seeking, audio and the compositor. Constant
 * ranges remain a single composition segment. Animated speed is subdivided
 * only where its change exceeds 1%; midpoint samples bound the rate steps
 * without generating thousands of segments for a long constant-speed shot.
 */
val TimelineClip.effectPlaybackSpans: List<EffectPlaybackSpan>
    get() {
        val effects = videoEffects.orEmpty().filter { it.enabled && it.kind in timeEffects && it.start < end && it.end > start }
        val kept = keptIntervals
        if (effects.isEmpty()) { val rate = rate; return kept.map { EffectPlaybackSpan(it.start, it.end, rate) } }
        val points = mutableListOf(min(start, effects.minOf { it.start }), max(end, effects.maxOf { it.end }))
        for (effect in effects) {
            points += listOf(effect.start, effect.end)
            points += effect.parameters.filter { it.id == "speed" || it.id == "mute" }.flatMap { p -> p.keyframes.map { it.time } }
        }
        // Subdivide the effect's own source intervals, then intersect the result
        // with trims/pauses. Splitting a clip cannot move the integration grid.
        val boundaries = points.toSet().sorted()
        val result = mutableListOf<EffectPlaybackSpan>()
        val speed = speed?.rate ?: 1.0
        fun add(a: Double, b: Double, depth: Int = 0) {
            if (kept.none { it.start < b && it.end > a }) return
            val r0 = min(6.0, max(0.125, speed * sequenceRate(a + 0.0000001)))
            val r1 = min(6.0, max(0.125, speed * sequenceRate(b - 0.0000001)))
            if (depth < 12 && b - a > 1.0 / 30 && abs(ln(r1 / r0)) > 0.01) {
                val middle = (a + b) / 2; add(a, middle, depth + 1); add(middle, b, depth + 1)
            } else {
                val value = min(6.0, max(0.125, speed * sequenceRate((a + b) / 2)))
                for (interval in kept) if (interval.start < b && interval.end > a) result += EffectPlaybackSpan(max(a, interval.start), min(b, interval.end), value)
            }
        }
        for ((a, b) in boundaries.zipWithNext()) if (b > a) add(a, b)
        return result
    }

/**
 * Swift `TimelineEditing.freezing(_:at:)`: inserts a three-second hold of the
 * frame at [time] and returns its Freeze Frame effect identity, or null where
 * there is no single underlying frame (inside an overlap or transition).
 */
fun TimelineEditing.freezing(state: EditState, time: Double): Pair<EditState, UUID?> {
    val frame = TimelineEditing.frame
    val position = if (time >= state.duration - frame / 2) state.duration else min(max(0.0, quantize(time)), max(0.0, state.duration - frame))
    val placement = state.layout.placement(position) ?: return state to null
    val sourceTime = min(placement.clip.end - frame, max(placement.clip.start, placement.clip.sourceTime(position - placement.start)))
    val split = split(state, position)
    // Inside an overlap there is no single underlying frame to hold.
    if (split.layout.joins.none { abs(it.time - position) < 0.001 }) return state to null
    val join = split.layout.boundary(position)
    val clips = split.clips.toMutableList()
    val index = clips.indexOfFirst { it.id == join?.incomingID }.let { if (it < 0) clips.size else it }
    val effect = VideoEffectSettings(VideoEffectID.freezeFrame, sourceTime, min(placement.clip.end, sourceTime + frame))
    val hold = placement.clip.copy(
        id = newUUID(), start = sourceTime, end = min(placement.clip.end, sourceTime + frame),
        pauses = null, speed = null, openingTransition = null, transition = null,
        frozenFrame = FrozenFrameSegment(sourceTime = sourceTime, duration = 3.0),
        videoEffects = placement.clip.videoEffects.orEmpty().filter { it.kind.descriptor.family != EffectFamily.time } + effect,
    )
    clips.add(index, hold)
    split.clips = clips
    return split.normalizedTools() to effect.id
}
