// Port of YouRec/EffectAnimation.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The A/B endpoint animation shared by Color Correction (video effects), Crop,
// Image, Placement and Color. YouRoll uses it for Color Correction; crops, images
// and placements still pass their `motion` through as JSON (TODO.md), and the
// colour grade's `look`/`lut` endpoint fields are kept as JSON here.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class EffectEndpoint(val rawValue: String) { a("A"), b("B") }

@Serializable
enum class EffectCurve {
    linear, easeIn, easeOut, smooth, riseAndHold, riseHoldReturn;

    val title: String
        get() = when (this) {
            linear -> "Linear"; easeIn -> "Ease in"; easeOut -> "Ease out"; smooth -> "Ease in & out"
            riseAndHold -> "Rise & hold"; riseHoldReturn -> "Rise, hold & return"
        }
    val hasRise: Boolean get() = this == riseAndHold || this == riseHoldReturn
}

@Serializable
enum class RampShape {
    linear, easeIn, easeOut, smooth;

    val title: String get() = when (this) { linear -> "Linear"; easeIn -> "Ease in"; easeOut -> "Ease out"; smooth -> "Ease in & out" }

    fun sample(x: Double): Double = when (this) {
        linear -> x
        easeIn -> x * x * x
        easeOut -> 1 - (1 - x).pow(3)
        smooth -> x * x * (3 - 2 * x)
    }
}

@Serializable
data class EffectTiming(
    val curve: EffectCurve = EffectCurve.linear,
    val rise: Double = 1.0,
    val release: Double = 1.0,
    val ramp: RampShape = RampShape.linear,
) {
    data class Phases(val rise: Double, val hold: Double, val release: Double)

    fun phases(duration: Double): Phases {
        val d = max(0.0, duration)
        val up = min(d, max(0.0, rise))
        val down = if (curve == EffectCurve.riseHoldReturn) min(d - up, max(0.0, release)) else 0.0
        return Phases(up, max(0.0, d - up - down), down)
    }

    fun endpoint(endpoint: EffectEndpoint, duration: Double, frame: Double): Double {
        if (endpoint != EffectEndpoint.b) return 0.0
        val last = max(0.0, duration - frame)
        return if (curve.hasRise) min(last, ceil(phases(duration).rise / frame - 0.000001) * frame) else last
    }

    /** Explicitly choosing B follows its peak as the timing changes. Scrubbing
     *  pins a frame even if the user later drags the peak across that frame. */
    fun previewPosition(position: Double, following: EffectEndpoint?, duration: Double, frame: Double): Double =
        following?.let { endpoint(it, duration, frame) } ?: position

    fun fraction(elapsed: Double, duration: Double, frame: Double): Double {
        val last = max(0.0, duration - frame)
        val x = min(1.0, max(0.0, elapsed / max(0.000001, last)))
        return when (curve) {
            EffectCurve.linear -> x
            EffectCurve.easeIn -> x * x * x
            EffectCurve.easeOut -> 1 - (1 - x).pow(3)
            EffectCurve.smooth -> x * x * (3 - 2 * x)
            EffectCurve.riseAndHold, EffectCurve.riseHoldReturn -> {
                val p = phases(duration)
                val peak = min(last, p.rise)
                if (elapsed < peak && peak > 0) return ramp.sample(max(0.0, elapsed / peak))
                if (curve == EffectCurve.riseHoldReturn && p.release > 0) {
                    if (elapsed >= last && peak < last) return 0.0
                    val falling = min(last, max(peak, duration - p.release))
                    if (elapsed > falling) return 1 - ramp.sample(min(1.0, (elapsed - falling) / max(0.000001, last - falling)))
                }
                1.0
            }
        }
    }
}

// Only animatable controls are captured. Media identity, scope, and layer order
// continue to belong to the tool that owns the animation.
@Serializable
data class EffectPose(
    val values: Map<String, Double>,
    /** `ColorLook?` (colour grading), passed through. */
    val look: JsonElement? = null,
    /** `ImportedLUT?` (colour grading), passed through. */
    val lut: JsonElement? = null,
) {
    operator fun get(key: String): Double = values[key] ?: 0.0
}

@Serializable
data class EffectAnimation(
    val a: EffectPose,
    val b: EffectPose,
    val timing: EffectTiming = EffectTiming(),
    val duration: Double,
    val frame: Double = TimelineEditing.frame,
    val clock: List<StrobeTimePoint> = emptyList(),
) {
    operator fun get(endpoint: EffectEndpoint): EffectPose = if (endpoint == EffectEndpoint.a) a else b
    fun setting(endpoint: EffectEndpoint, pose: EffectPose): EffectAnimation = if (endpoint == EffectEndpoint.a) copy(a = pose) else copy(b = pose)

    fun anchored(clip: TimelineClip, offset: Double): EffectAnimation {
        val hold = clip.frozenFrame
        if (hold != null) {
            val start = hold.sourceTime + hold.offset
            return copy(clock = listOf(StrobeTimePoint(start, offset), StrobeTimePoint(start + hold.duration, offset + hold.duration)))
        }
        val points = mutableListOf<StrobeTimePoint>()
        var time = offset
        for (span in clip.effectPlaybackSpans) {
            if (points.lastOrNull()?.source != span.start) points += StrobeTimePoint(span.start, time)
            time += span.duration
            points += StrobeTimePoint(span.end, time)
        }
        return copy(clock = points)
    }

    fun placed(clip: TimelineClip, offset: Double, replacing: EffectAnimation?): EffectAnimation =
        if (replacing != null && hasSameSettings(replacing)) replacing else anchored(clip, offset)

    fun hasSameSettings(other: EffectAnimation): Boolean = a == other.a && b == other.b && timing == other.timing

    fun elapsed(source: Double): Double {
        val first = clock.firstOrNull() ?: return source
        val last = clock.last()
        if (source <= first.source) return first.elapsed
        if (source >= last.source) return last.elapsed
        var lower = 0
        var upper = clock.size - 1
        while (upper - lower > 1) {
            val mid = (upper + lower) / 2
            if (clock[mid].source < source) lower = mid else upper = mid
        }
        val l = clock[lower]; val r = clock[upper]
        return l.elapsed + (r.elapsed - l.elapsed) * (source - l.source) / (r.source - l.source)
    }

    fun fraction(source: Double): Double = timing.fraction(elapsed(source), duration, frame)

    fun valid(template: EffectPose, accepts: (EffectPose) -> Boolean): Boolean =
        duration.isFinite() && duration > 0 && frame.isFinite() && frame > 0 &&
            timing.rise.isFinite() && timing.rise >= 0 && timing.release.isFinite() && timing.release >= 0 &&
            clock.all { it.source.isFinite() && it.elapsed.isFinite() } &&
            clock.zipWithNext().all { (x, y) -> x.source < y.source && x.elapsed <= y.elapsed } &&
            listOf(a, b).all { p ->
                p.values.keys == template.values.keys && p.values.values.all { it.isFinite() } &&
                    (template.look != null || (p.look == null && p.lut == null)) && accepts(p)
            }

    fun value(key: String, fraction: Double): Double {
        val start = a[key]
        val end = b[key]
        if (key == "rotation" || key == "hue") return CropSettings.normalizedRotation(start + fraction * CropSettings.normalizedRotation(end - start))
        if (key == "zoom" && start > 0 && end > 0) return start * (end / start).pow(fraction)
        return start + (end - start) * fraction
    }

    fun pose(source: Double): EffectPose {
        val f = fraction(source)
        if (f <= 0) return a
        if (f >= 1) return b
        return EffectPose(a.values.keys.associateWith { value(it, f) })
    }

    companion object {
        /** Swift `EffectAnimation(_:duration:)`. */
        fun of(pose: EffectPose, duration: Double) = EffectAnimation(a = pose, b = pose, duration = duration)
    }
}

val CropSettings.pose: EffectPose get() = EffectPose(mapOf("x" to x, "y" to y, "zoom" to zoom, "rotation" to rotation))
fun CropSettings.applying(p: EffectPose): CropSettings = copy(x = p["x"], y = p["y"], zoom = p["zoom"], rotation = p["rotation"], motion = null)

val ImageOverlay.pose: EffectPose get() = EffectPose(mapOf("x" to x, "y" to y, "width" to width, "opacity" to opacity))
fun ImageOverlay.applying(p: EffectPose): ImageOverlay = copy(x = p["x"], y = p["y"], width = p["width"], opacity = p["opacity"], motion = null)

/** Swift `correctionPose(at:)`: every numeric control's value at [time]. */
fun VideoEffectSettings.correctionPose(time: Double): EffectPose =
    EffectPose(parameters.mapNotNull { p -> (p.value(time) as? EffectValue.number)?.let { p.id to it.value } }.toMap())
