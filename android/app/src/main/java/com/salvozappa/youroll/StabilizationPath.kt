// Port of YouRec/StabilizationPath.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The model of editor stabilization (levels, the per-clip settings, the
// measured camera path) and the smoothing that turns a measured path into a
// per-frame correction (`StabilizationPath.make`). Pure, JVM-tested
// (StabilizationTests.kt, a port of Tests/StabilizationTests.swift).
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

@Serializable
enum class StabilizationLevel {
    off, light, standard, strong;

    val id: String get() = name
    val rawValue: String get() = name
    val title: String get() = name.replaceFirstChar { it.uppercase() }
    val detail: String
        get() = when (this) {
            off -> "Keep the original camera movement."
            light -> "Soften small shakes. Keep more of the frame."
            standard -> "Smooth handheld movement with a balanced crop."
            strong -> "Steadier movement with a tighter crop."
        }
    val sigma: Double
        get() = when (this) { off -> 0.0; light -> 0.12; standard -> 0.28; strong -> 0.5 }
    val maximumZoom: Double
        get() = when (this) { off -> 1.0; light -> 1.045; standard -> 1.10; strong -> 1.18 }
}

@Serializable
data class StabilizationSettings(
    val level: StabilizationLevel,
    val version: Int = 1,
    val id: UUID? = null,
    // Preserve the analyzed take range through splits and ripple cuts. Adjacent
    // pieces inherit the same path/crop, with no new analysis or framing jump.
    val referenceStart: Double? = null,
    val referenceEnd: Double? = null,
)

val TimelineClip.stabilizationStart: Double get() = min(start, stabilization?.referenceStart ?: start)
val TimelineClip.stabilizationEnd: Double get() = max(end, stabilization?.referenceEnd ?: end)
fun TimelineClip.stabilizing(level: StabilizationLevel): StabilizationSettings =
    StabilizationSettings(level = level, referenceStart = stabilizationStart, referenceEnd = stabilizationEnd)

/**
 * What a rendered picture of this clip needs: its level and analysed reference
 * range, without the effect identity (null when the clip is not stabilized).
 * Pieces of a split take share it, so they share one plan and framing.
 */
val TimelineClip.stabilizationReference: StabilizationSettings?
    get() = stabilization?.takeIf { it.level != StabilizationLevel.off }
        ?.let { StabilizationSettings(it.level, it.version, null, stabilizationStart, stabilizationEnd) }

/** A similarity about the center of the encoded (unrotated) frame. */
@Serializable
data class StabilizationPose(val x: Double = 0.0, val y: Double = 0.0, val angle: Double = 0.0, val logScale: Double = 0.0) {
    val scale: Double get() = exp(logScale)

    fun applying(other: StabilizationPose): StabilizationPose {
        val a = scale * cos(angle)
        val b = scale * sin(angle)
        return StabilizationPose(x = a * other.x - b * other.y + x, y = b * other.x + a * other.y + y,
            angle = angle + other.angle, logScale = logScale + other.logScale)
    }

    val inverse: StabilizationPose
        get() {
            val a = cos(angle) / scale
            val b = sin(angle) / scale
            return StabilizationPose(x = -a * x - b * y, y = b * x - a * y, angle = -angle, logScale = -logScale)
        }

    fun attenuated(amount: Double) = StabilizationPose(x * amount, y * amount, angle * amount, logScale * amount)

    fun transform(size: CGSize, zoom: Double = 1.0): CGAffineTransform {
        val a = scale * cos(angle) * zoom
        val b = scale * sin(angle) * zoom
        val cx = size.width / 2
        val cy = size.height / 2
        return CGAffineTransform(a, b, -b, a, cx - a * cx + b * cy + x * zoom, cy - b * cx - a * cy + y * zoom)
    }

    /**
     * Exact corner inclusion for a rotated/scaled rectangle. This is stronger
     * than bounding-box overlap, which can leave black triangular corners.
     */
    fun requiredZoom(size: CGSize): Double {
        val c = cos(angle)
        val s = sin(angle)
        val hw = size.width / 2
        val hh = size.height / 2
        val dx = scale * hw - abs(c * x + s * y)
        val dy = scale * hh - abs(-s * x + c * y)
        if (!(dx > 0 && dy > 0)) return Double.POSITIVE_INFINITY
        return maxOf(1.0, (abs(c) * hw + abs(s) * hh) / dx, (abs(s) * hw + abs(c) * hh) / dy)
    }

    val valid: Boolean get() = listOf(x, y, angle, logScale).all { it.isFinite() } && abs(logScale) < 8

    companion object { val identity = StabilizationPose() }
}

@Serializable
data class StabilizationSample(val time: Double, val pose: StabilizationPose, val segment: Int)

@Serializable
data class StabilizationAnalysis(
    val width: Int,
    val height: Int,
    val duration: Double,
    val samples: List<StabilizationSample>,
    val trackedFrames: Int,
) {
    val trackingFraction: Double get() = trackedFrames.toDouble() / max(1, samples.size - 1).toDouble()
    val valid: Boolean
        get() = width > 0 && height > 0 && duration.isFinite() && duration > 0 && samples.isNotEmpty() &&
            samples.all { it.time.isFinite() && it.time >= 0 && it.time < duration + 0.001 && it.pose.valid } &&
            samples.zipWithNext().all { (a, b) -> a.time < b.time && a.segment <= b.segment }

    companion object {
        // Swift `StabilizationAnalysis.version` (the cache key): the same algorithm and format.
        const val version = "yourec-stabilization-2"
    }
}

data class StabilizationPlan(
    val size: CGSize,
    val samples: List<StabilizationSample>,
    val zoom: Double,
    val strengthLimited: Boolean,
    val trackingFraction: Double,
) {
    val cropPercent: Int get() = Math.round((1 - 1 / zoom) * 100).toInt()

    fun transform(time: Double): CGAffineTransform {
        val first = samples.firstOrNull() ?: return CGAffineTransform.identity
        if (time <= first.time) return first.pose.transform(size, zoom)
        var low = 0
        var high = samples.size
        while (low < high) {
            val middle = (low + high) / 2
            if (samples[middle].time <= time + 0.000001) low = middle + 1 else high = middle
        }
        val before = samples[max(0, low - 1)]
        // AVFoundation holds the source frame whose PTS precedes this output
        // time. Correct that exact image, not an interpolated nonexistent frame.
        return before.pose.transform(size, zoom)
    }
}

object StabilizationPath {
    /** [checkCancellation] stands in for Swift's `Task.checkCancellation()` (throws to stop). */
    fun make(analysis: StabilizationAnalysis, level: StabilizationLevel, checkCancellation: () -> Unit = {}): StabilizationPlan {
        val size = CGSize(analysis.width.toDouble(), analysis.height.toDouble())
        if (level == StabilizationLevel.off) {
            return StabilizationPlan(size, emptyList(), 1.0, false, analysis.trackingFraction)
        }
        val source = analysis.samples
        val corrections = ArrayList<StabilizationSample>(source.size)
        // Gaussian local-linear regression preserves intentional constant-speed
        // pans, including the ends of a clip (no clamped endpoint slowdown).
        var left = 0
        var right = 0
        var segmentStart = 0
        var segmentEnd = 0
        fun fade(seconds: Double): Double {
            val x = min(1.0, max(0.0, seconds / 0.3)); return x * x * (3 - 2 * x)
        }
        for (i in source.indices) {
            if (i % 128 == 0) checkCancellation()
            val current = source[i]
            val radius = level.sigma * 3
            if (i == 0 || source[i - 1].segment != current.segment) {
                segmentStart = i; segmentEnd = i + 1
                while (segmentEnd < source.size && source[segmentEnd].segment == current.segment) segmentEnd += 1
            }
            while (left < i && (source[left].time < current.time - radius || source[left].segment != current.segment)) left += 1
            right = max(i + 1, right)
            while (right < source.size && source[right].segment == current.segment && source[right].time <= current.time + radius) right += 1
            // A segment change can leave the old right edge behind us, never
            // let a regression borrow the first frame of the next shot.
            var sw = 0.0
            var st = 0.0
            var stt = 0.0
            val sums = DoubleArray(4)
            val timeSums = DoubleArray(4)
            for (j in left until right) {
                if (source[j].segment != current.segment) continue
                val dt = source[j].time - current.time
                val w = exp(-0.5 * (dt / level.sigma).pow(2))
                sw += w; st += w * dt; stt += w * dt * dt
                // Smooth camera position in the reference/world coordinates,
                // not feature displacement in the zooming image. Otherwise a
                // zoom after a pan magnifies past translation into false drift.
                val p = source[j].pose.inverse
                val values = doubleArrayOf(p.x, p.y, p.angle, p.logScale)
                for (k in 0 until 4) { sums[k] += w * values[k]; timeSums[k] += w * dt * values[k] }
            }
            val determinant = sw * stt - st * st
            val values = DoubleArray(4) { if (determinant > 1e-10) (sums[it] * stt - timeSums[it] * st) / determinant else sums[it] / max(sw, 1e-10) }
            // Measured scale helps estimate camera motion, but never retime a
            // deliberate recorded zoom. Correct translation and rotation only.
            val smooth = StabilizationPose(values[0], values[1], values[2], current.pose.inverse.logScale).inverse
            var confidence = 1.0
            if (segmentStart > 0) confidence = min(confidence, fade(current.time - source[segmentStart].time))
            if (segmentEnd < source.size) confidence = min(confidence, fade(source[segmentEnd - 1].time - current.time))
            val correction = smooth.applying(current.pose.inverse).attenuated(confidence)
            corrections += StabilizationSample(current.time, correction, current.segment)
        }
        // One crop for the entire reference range, with a smoothly anticipated
        // reduction around large bumps. One outlier cannot weaken a whole take.
        val pixelMargin = 1 + 3 / min(analysis.width, analysis.height).toDouble()
        val allowed = corrections.map { sample ->
            if (sample.pose.requiredZoom(size) * pixelMargin <= level.maximumZoom) return@map 1.0
            var low = 0.0
            var high = 1.0
            repeat(24) {
                val middle = (low + high) / 2
                if (sample.pose.attenuated(middle).requiredZoom(size) * pixelMargin <= level.maximumZoom) low = middle else high = middle
            }
            low
        }
        var strengthLimited = false
        var zoom = 1.0
        left = 0; right = 0
        for (i in corrections.indices) {
            checkCancellation()
            val t = corrections[i].time
            while (left < i && corrections[left].time < t - 0.7) left += 1
            right = max(i + 1, right)
            while (right < corrections.size && corrections[right].time <= t + 0.7) right += 1
            var strength = allowed[i]
            for (j in left until right) {
                if (!(allowed[j] < 1 && corrections[j].segment == corrections[i].segment)) continue
                val d = min(1.0, abs(t - corrections[j].time) / 0.7)
                strength = min(strength, allowed[j] + (1 - allowed[j]) * d * d * (3 - 2 * d))
            }
            strengthLimited = strengthLimited || strength < 0.97
            corrections[i] = corrections[i].copy(pose = corrections[i].pose.attenuated(strength))
            zoom = max(zoom, corrections[i].pose.requiredZoom(size))
        }
        // No pointless crop on a static or entirely untrackable clip.
        if (zoom > 1.00001) zoom = min(level.maximumZoom, zoom * pixelMargin)
        return StabilizationPlan(size, corrections, zoom, strengthLimited, analysis.trackingFraction)
    }
}
