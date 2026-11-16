// Port of YouRec/ZoomTransition.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A normalised framing centre; (0.5, 0.5) is the middle of the lens. */
data class FramingPoint(val x: Double, val y: Double)

private fun log2(v: Double) = ln(v) / ln(2.0)
private fun exp2(v: Double) = exp(v * ln(2.0))

/**
 * Time-based easing in magnification space. Both velocity and acceleration
 * reach zero at either end, and late timer callbacks still meet the deadline.
 *
 * The sensor-handoff fields of the Swift type (`compensatesSensorSwitch`,
 * `stabilizedSwitches`, `returningSensorStart`) drive AVFoundation's
 * secondary native resolutions and have no CameraX counterpart; they are not ported.
 */
data class ZoomTransition(
    val start: Double,
    val target: Double,
    val began: Double,
    val duration: Double,
    val startCenter: FramingPoint? = null,
    val targetCenter: FramingPoint? = null,
) {
    fun isFinished(time: Double): Boolean = duration <= 0 || time >= began + duration

    private fun progress(time: Double): Double {
        if (isFinished(time)) return 1.0
        if (time <= began) return 0.0
        val t = (time - began) / duration
        return t * t * t * (10 + t * (-15 + 6 * t))
    }

    fun value(time: Double): Double {
        if (isFinished(time)) return target
        if (time <= began) return start
        return exp2(log2(start) + log2(target / start) * progress(time))
    }

    fun center(time: Double): FramingPoint? {
        val from = startCenter ?: return null
        val to = targetCenter ?: return null
        val t = progress(time)
        return FramingPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    companion object {
        fun frameInterval(seconds: Double): Double =
            if (seconds.isFinite() && seconds > 0) seconds else 1.0 / 30

        val durationPresets: List<Double> = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0)

        /** Matches Swift's `Double.formatted()`: no trailing ".0". */
        fun formatted(seconds: Double): String =
            if (seconds == Math.floor(seconds) && abs(seconds) < 1e9) seconds.toLong().toString() else seconds.toString()

        fun durationLabel(seconds: Double): String =
            if (seconds == 0.0) "Instant" else "${formatted(seconds)} ${if (seconds == 1.0) "second" else "seconds"}"

        fun boundedDuration(value: Double): Double {
            if (!value.isFinite()) return 0.0
            return min(30.0, max(0.0, (value * 2).roundToLong() / 2.0))
        }
    }
}
