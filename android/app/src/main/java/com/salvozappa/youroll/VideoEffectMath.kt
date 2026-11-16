// Port of YouRec/VideoEffects/VideoEffectMath.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Not ported (TODO.md): `DollyZoomFollower` and `VideoEffectLiveBudget`, which
// belong to the live recorder effects.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Swift `SIMD3<Double>`. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun lengthSquared() = dot(this)
    fun distance(o: Vec3) = sqrt((this - o).lengthSquared())
    fun distanceSquared(o: Vec3) = (this - o).lengthSquared()
    fun clamped(low: Double = 0.0, high: Double = 1.0) = Vec3(min(high, max(low, x)), min(high, max(low, y)), min(high, max(low, z)))
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val zero = Vec3(0.0, 0.0, 0.0)
        fun repeating(v: Double) = Vec3(v, v, v)
    }
}

// Convert a drag on a displayed copy to a translation of the underlying source.
// The starting wedge supplies the transform for the entire gesture, even across a seam.
fun kaleidoscopeDragTransform(start: CGPoint, center: CGPoint, size: CGSize, angle: Double, divisions: Int, sourceAngle: Double = 0.0): CGAffineTransform {
    val undoSourceTurn = -sourceAngle * PI / 180
    val dx = (start.x - center.x) * size.width
    val dy = (start.y - center.y) * size.height
    if (hypot(dx, dy) <= 0.001) return CGAffineTransform.rotation(undoSourceTurn)
    val axis = (angle - 90) * PI / 180
    val turn = 2 * PI
    val span = turn / divisions
    val relative = atan2(dy, dx) - axis
    val sector = min(divisions - 1, floor((relative - turn * floor(relative / turn)) / span).toInt())
    if (divisions % 2 == 0 && sector % 2 != 0) {
        val reflection = 2 * axis + (sector + 1) * span + undoSourceTurn
        return CGAffineTransform(cos(reflection), sin(reflection), sin(reflection), -cos(reflection), 0.0, 0.0)
    }
    return CGAffineTransform.rotation(-sector * span + undoSourceTurn)
}

// A source-frame EMA initialized with the oldest retained raw matte. Fixed
// per-frame coefficients follow "two-frame EMA" and "25% previous alpha";
// they do not invent a 30 Hz time constant for variable-rate footage.
enum class VideoEffectMatteKind {
    isolation, key;

    val prior: Double get() = if (this == isolation) 1.0 / 3 else 0.25
    val capacity: Int get() = 1 + ceil(ln(0.001) / ln(prior)).toInt()
}

data class VideoEffectTemporalWindow(
    val indices: List<Int>, // Oldest first; every consumer uses this same order.
    val weights: List<Double>,
    val tailBound: Double,
) {
    companion object {
        fun make(times: List<Double>, time: Double, start: Double, kind: VideoEffectMatteKind, cuts: Set<Int> = emptySet()): VideoEffectTemporalWindow? {
            if (times.isEmpty() || !time.isFinite() || !start.isFinite() || time < start) return null
            fun lowerBound(value: Double): Int {
                var a = 0; var b = times.size
                while (a < b) { val m = (a + b) / 2; if (times[m] < value) a = m + 1 else b = m }
                return a
            }
            // A mid-frame start acts on the frame already on screen, just like
            // Step Printing. Its floor sample belongs to the new effect's window.
            val first = max(0, lowerBound(start + 0.0000001) - 1)
            val current = lowerBound(time + 0.0000001) - 1
            if (current < first || current < 0) return null
            var oldest = current
            while (oldest > first && current - oldest + 1 < kind.capacity && times[oldest] - times[oldest - 1] <= 0.25 && oldest !in cuts) oldest -= 1
            val indices = (oldest..current).toList()
            val count = indices.size
            val weights = (0 until count).map { i -> kind.prior.pow((count - 1 - i).toDouble()) * (if (i == 0) 1.0 else 1 - kind.prior) }
            val truncated = oldest > first && times[oldest] - times[oldest - 1] <= 0.25 && oldest !in cuts
            return VideoEffectTemporalWindow(indices, weights, if (truncated) weights[0] else 0.0)
        }
    }
}

// Original implementation of the published Oklab equations (Björn Ottosson,
// https://bottosson.github.io/posts/oklab/). Values use linear sRGB / BT.709.
object EffectColorMath {
    fun linear(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    fun encoded(x: Double): Double = if (x <= 0.0031308) x * 12.92 else 1.055 * max(0.0, x).pow(1 / 2.4) - 0.055
    fun linear(c: EffectRGB): Vec3 = Vec3(linear(c.red), linear(c.green), linear(c.blue))
    fun rgb(c: Vec3): EffectRGB = EffectRGB(min(1.0, max(0.0, encoded(c.x))), min(1.0, max(0.0, encoded(c.y))), min(1.0, max(0.0, encoded(c.z))))
    fun lab(c: Vec3): Vec3 {
        val l = cbrt(0.4122214708 * c.x + 0.5363325363 * c.y + 0.0514459929 * c.z)
        val m = cbrt(0.2119034982 * c.x + 0.6806995451 * c.y + 0.1073969566 * c.z)
        val s = cbrt(0.0883024619 * c.x + 0.2817188376 * c.y + 0.6299787005 * c.z)
        return Vec3(0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s, 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s)
    }
    fun linearFromLab(c: Vec3): Vec3 {
        val l = (c.x + 0.3963377774 * c.y + 0.2158037573 * c.z).pow(3)
        val m = (c.x - 0.1055613458 * c.y - 0.0638541728 * c.z).pow(3)
        val s = (c.x - 0.0894841775 * c.y - 1.291485548 * c.z).pow(3)
        return Vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s)
    }
    fun luminance(c: Vec3): Double = c.dot(Vec3(0.2126, 0.7152, 0.0722))
    fun prepared(c: Vec3): Vec3 {
        val y = luminance(c)
        val s = y * y * (3 - 2 * y)
        val target = y + (s - y) * 0.2
        return (c + Vec3.repeating(target - y)).clamped()
    }
}

@Serializable
data class EffectPalette(val colors: List<EffectRGB>, val thresholds: List<Double>) {
    val valid: Boolean
        get() = colors.size in 2..16 && colors.all { it.valid } && thresholds.size == colors.size - 1 &&
            thresholds.all { it.isFinite() && it in 0.0..1.0 } && thresholds.zipWithNext().all { (a, b) -> a <= b }
}

@Serializable
data class EffectPalettePreset(val id: String, val name: String, val colors: List<String>) {
    fun sampled(count: Int): List<EffectRGB> {
        val sorted = colors.map { EffectRGB(it) }.sortedBy { EffectColorMath.lab(EffectColorMath.linear(it)).x }
        if (sorted.size < 2 || count < 2) return sorted
        return (0 until count).map { sorted[swiftRound(it.toDouble() * (sorted.size - 1) / (count - 1)).toInt()] }
    }
}

/** Swift `EffectRandom` (splitmix64), with unsigned 64-bit wrapping arithmetic. */
class EffectRandom(var state: ULong) {
    fun next(): Double {
        state += 0x9E3779B97F4A7C15uL
        var x = state
        x = (x xor (x shr 30)) * 0xBF58476D1CE4E5B9uL
        x = (x xor (x shr 27)) * 0x94D049BB133111EBuL
        return ((x xor (x shr 31)) shr 11).toDouble() / 9007199254740992.0
    }
}

object EffectPaletteAnalysis {
    fun analyze(samples: List<Vec3>, count: Int, previous: EffectPalette? = null, elapsed: Double = 0.2, seed: ULong = 0uL): EffectPalette {
        val count = max(2, min(16, count))
        val rgb = samples.filter { it.isFinite }.map { EffectColorMath.prepared(it.clamped()) }
        if (rgb.isEmpty()) return EffectPalette(listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)), listOf(0.5))
        val labs = rgb.map { EffectColorMath.lab(it) }
        val ys = rgb.map { EffectColorMath.luminance(it) }.sorted()
        val thresholds = (1 until count).map { i -> ys[min(ys.size - 1, (i.toDouble() * ys.size / count).toInt())] }
        val random = EffectRandom(seed)
        var centers = mutableListOf<Vec3>()
        if (previous != null && previous.colors.size == count) centers = previous.colors.map { EffectColorMath.lab(EffectColorMath.linear(it)) }.toMutableList()
        else {
            centers += labs[(random.next() * labs.size).toInt() % labs.size]
            while (centers.size < count) {
                val distances = labs.map { p -> centers.minOf { p.distanceSquared(it) } }
                val sum = distances.sum()
                val target = random.next() * sum
                var cumulative = 0.0
                var index = labs.size - 1
                for (i in labs.indices) { cumulative += distances[i]; if (cumulative >= target) { index = i; break } }
                centers += labs[index]
            }
        }
        repeat(6) {
            val sums = MutableList(count) { Vec3.zero }
            val weights = IntArray(count)
            for (p in labs) {
                var best = 0
                var distance = Double.POSITIVE_INFINITY
                for (i in centers.indices) { val d = p.distanceSquared(centers[i]); if (d < distance) { distance = d; best = i } }
                sums[best] = sums[best] + p; weights[best] += 1
            }
            val populated = mutableListOf<Vec3>()
            for (i in centers.indices) {
                if (weights[i] <= 0) continue
                centers[i] = sums[i] / weights[i].toDouble()
                populated += centers[i]
            }
            if (populated.size < count) {
                // A blank first frame can seed identical gray centers. Revive empty
                // clusters from the least represented real pixel when color appears.
                val residual = labs.map { p -> populated.minOf { p.distanceSquared(it) } }.toMutableList()
                for (i in centers.indices) {
                    if (weights[i] != 0) continue
                    val farthest = residual.indices.maxByOrNull { residual[it] } ?: break
                    centers[i] = labs[farthest]
                    if (residual[farthest] < 1e-10) continue
                    for (j in labs.indices) residual[j] = min(residual[j], labs[j].distanceSquared(centers[i]))
                }
            }
        }
        centers.sortBy { it.x }
        var bands = thresholds
        if (previous != null && previous.colors.size == count) {
            val old = previous.colors.map { EffectColorMath.lab(EffectColorMath.linear(it)) }.sortedBy { it.x }
            val cut = centers.zip(old).any { (a, b) -> a.distance(b) > 0.25 }
            if (!cut) {
                val alpha = 1 - exp(-max(0.0, elapsed) / 0.5)
                centers = old.zip(centers).map { (o, n) -> o + (n - o) * alpha }.toMutableList()
                if (previous.thresholds.size == bands.size) bands = previous.thresholds.zip(bands).map { (o, n) -> o + (n - o) * alpha }
            }
        }
        return EffectPalette(centers.map { EffectColorMath.rgb(EffectColorMath.linearFromLab(it)) }, bands)
    }
}

object VideoEffectTime {
    // Fractional cadence: 18fps on a 30fps output must remain 18fps, not 15.
    fun heldTime(presentationTime: Double, fps: Double): Double = floor(max(0.0, presentationTime) * fps + 0.000001) / max(1.0, fps)
    fun frameIndex(time: Double, fps: Double): Int = max(0, floor(max(0.0, time) * fps + 0.000001).toInt())
    // Capture selects the first available frame on/after a cadence boundary.
    // Choosing the preceding frame on export produces a different motion phase.
    fun firstFrame(atOrAfter: Double, fps: Double): Double = ceil(max(0.0, atOrAfter) * max(1.0, fps) - 0.0001) / max(1.0, fps)
    // Capture explicitly writes a 600-tick video clock. Evaluate its live look
    // on that same clock, including real camera drift.
    fun captureTime(time: Double): Double = swiftRound(max(0.0, time) * 600) / 600
    fun cubic(time: Double): Double { val t = min(1.0, max(0.0, time)); return if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2 }
    fun dollyTarget(zoom: Double, referenceHeight: Double, observedHeight: Double, range: ClosedFloatingPointRange<Double>): Double {
        if (!(observedHeight > 0.00001) || !observedHeight.isFinite()) return zoom
        return min(range.endInclusive, max(range.start, zoom * referenceHeight / observedHeight))
    }
}

// Integrated frame phase, shared by capture (rate 1) and editor/export. The
// slower active cadence wins. Integrating fps/rate keeps both animated cadence
// and speed monotonic; changing a control cannot rewind the shown source frame.
class VideoEffectCadenceClock(effects: List<VideoEffectSettings>, rates: List<Triple<Double, Double, Double>> = emptyList()) {
    data class Segment(val start: Double, val end: Double, val phase: Double, val frequency: Double, val groupStart: Double) {
        val endPhase: Double get() = phase + (end - start) * frequency
    }
    data class Hold(val index: Int, val boundary: Double)

    private val segments: List<Segment>
    val isEmpty: Boolean get() = segments.isEmpty()

    init {
        val effects = effects.filter { it.enabled && (it.kind == VideoEffectID.superEight || it.kind == VideoEffectID.undercranking) }
        var points = effects.flatMap { e -> listOf(e.start, e.end) + e.parameters.filter { it.id == "fps" || it.id == "intensity" }.flatMap { p -> p.keyframes.map { it.time } } }
        points = points + rates.flatMap { listOf(it.first, it.second) }
        points = points.toSet().sorted()
        val values = mutableListOf<Segment>()
        var phase = 0.0
        var group = 0.0
        var active = false
        for ((a, b) in points.zipWithNext()) {
            if (b <= a) continue
            val middle = (a + b) / 2
            val fps = effects.filter { it.start <= middle && it.end > middle && (!it.kind.descriptor.supportsIntensity || it.number("intensity", middle) > 0) }
                .map { it.number("fps", middle) }.minOrNull() ?: 0.0
            if (fps <= 0) { active = false; continue }
            if (!active) { phase = ceil(phase - 0.000001); group = a }
            active = true
            val rate = rates.firstOrNull { it.first <= middle && it.second > middle }?.third ?: 1.0
            val segment = Segment(a, b, phase, fps / max(0.001, rate), group)
            values += segment; phase = segment.endPhase
        }
        segments = values
    }

    fun hold(time: Double): Hold? {
        var a = 0; var b = segments.size
        while (a < b) { val m = (a + b) / 2; if (segments[m].start <= time) a = m + 1 else b = m }
        if (a <= 0) return null
        val current = segments[a - 1]
        if (time >= current.end) return null
        val index = floor(current.phase + (time - current.start) * current.frequency + 0.000001)
        a = 0; b = segments.size
        while (a < b) { val m = (a + b) / 2; if (segments[m].endPhase < index - 0.000001) a = m + 1 else b = m }
        val source = segments[min(a, segments.size - 1)]
        return Hold(index.toInt(), max(current.groupStart, source.start + (index - source.phase) / source.frequency))
    }
}

// Forward, normalized top-down camera geometry, shared with target picking.
// These curves mirror the geometric sampling in the shaders; color/frame effects do
// not move the subject. A held camera frame keeps its source-time phase.
object VideoEffectGeometry {
    fun transform(effects: List<VideoEffectSettings>, time: Double, size: CGSize, targets: Map<UUID, CGPoint> = emptyMap()): CGAffineTransform {
        if (!(size.width > 0 && size.height > 0)) return CGAffineTransform.identity
        var result = CGAffineTransform.identity
        for (effect in effects) {
            if (!(effect.enabled && effect.start <= time && effect.end > time)) continue
            val t = max(0.0, time - effect.start + (effect.phaseOffset ?: 0.0))
            var next = CGAffineTransform.identity
            if (effect.kind == VideoEffectID.crashZoom) {
                val push = effect.number("speed", time); val amount = effect.number("zoom", time)
                val release = effect.releaseTime?.let { it - effect.start + (effect.phaseOffset ?: 0.0) } ?: (push + 0.08 + effect.number("hold", time))
                val zoom = when {
                    t < push -> 1 + (amount - 1) * (t / push).pow(5)
                    t < push + 0.08 -> amount * (1 + 0.03 * sin((t - push) / 0.08 * PI))
                    (effect.releaseTime != null || effect.toggle("return", time)) && t > release -> {
                        val f = min(1.0, max(0.0, (t - release) / push)); amount + (1 - amount) * f * f * (3 - 2 * f)
                    }
                    else -> amount
                }
                val target = (targets[effect.id] ?: CGPoint(effect.number("targetX", time), effect.number("targetY", time))).applying(result)
                next = CGAffineTransform(zoom, 0.0, 0.0, zoom, target.x * (1 - zoom), target.y * (1 - zoom))
            }
            result = result.concatenating(next)
        }
        return result
    }
}

sealed class VideoEffectPlacementGesture {
    data class pan(val delta: CGPoint) : VideoEffectPlacementGesture()
    data class pinch(val factor: Double, val from: CGPoint, val to: CGPoint) : VideoEffectPlacementGesture()
    data object ended : VideoEffectPlacementGesture()
}

data class VideoEffectBackgroundPlacement(
    val x: Double = 0.5,
    val y: Double = 0.5,
    val zoom: Double = 1.0,
    val canvas: CGSize,
    val media: CGSize,
    val fill: Boolean,
) {
    private fun size(zoom: Double): CGSize {
        val sx = canvas.width / max(1.0, media.width); val sy = canvas.height / max(1.0, media.height)
        val scale = (if (fill) max(sx, sy) else min(sx, sy)) * zoom
        return CGSize(media.width * scale / max(1.0, canvas.width), media.height * scale / max(1.0, canvas.height))
    }
    val movementLocked: Boolean get() { val s = size(zoom); return abs(1 - s.width) < 0.0001 && abs(1 - s.height) < 0.0001 }

    fun applying(gesture: VideoEffectPlacementGesture): VideoEffectBackgroundPlacement {
        val old = size(zoom)
        fun align(value: Double, span: Double, previous: Double): Double = if (abs(span) < 0.0001) previous else min(1.0, max(0.0, value / span))
        return when (gesture) {
            is VideoEffectPlacementGesture.pan -> copy(
                x = align((1 - old.width) * x + gesture.delta.x, 1 - old.width, x),
                y = align((1 - old.height) * y + gesture.delta.y, 1 - old.height, y))
            is VideoEffectPlacementGesture.pinch -> {
                if (!gesture.factor.isFinite() || gesture.factor <= 0) return this
                val originX = (1 - old.width) * x; val originY = (1 - old.height) * y
                val zoom = min(4.0, max(1.0, zoom * gesture.factor))
                val next = size(zoom)
                copy(zoom = zoom,
                    x = align(gesture.to.x - (gesture.from.x - originX) * next.width / old.width, 1 - next.width, x),
                    y = align(gesture.to.y - (gesture.from.y - originY) * next.height / old.height, 1 - next.height, y))
            }
            VideoEffectPlacementGesture.ended -> this
        }
    }
}
