// Port of YouRec/LogColor.swift (partial: the Apple Log curve and primaries, the
// viewing shoulder and selective Highlights as pure functions; their GLSL twins
// are in `ColorLUT` in VideoToolCompositor.kt)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Not ported (TODO.md): Log capture and Log source detection. Android cameras
// do not record Apple Log and Media3 does not expose the Log transfer
// metadata of an imported iPhone recording, so `profile` always answers null:
// every source is display-referred Rec.709, the viewing transform and local
// highlight recovery never run, and `LogThumbnailSource` is not needed. An
// imported LUT that expects Apple Log still receives the scene encoded in its
// profile (`encode`), as on iOS.
package com.salvozappa.youroll

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class CaptureColorMode(val title: String) {
    sdr("SDR"), hdr("HDR"), log("Log");

    val id: String get() = name
}

/**
 * Explicit Apple Log color management: decode camera code values, transform
 * their primaries into linear Rec.709 working RGB, and back. Profile constants:
 * Apple Log Profile (2023), Apple Log 2 Profile (2025).
 */
object LogColor {
    /** Swift `profile(_:)`: the Log interpretation of a source. Not detectable on Android (see the file header). */
    @Suppress("UNUSED_PARAMETER")
    fun profile(file: java.io.File): LUTInput? = null

    fun decodeValue(x: Double): Double =
        if (x >= 47.28711236 * (0.01 + 0.05641088).pow(2)) 2.0.pow((x - 0.69336945) / 0.08550479) - 0.00964052
        else sqrt(max(0.0, x) / 47.28711236) - 0.05641088

    fun encodeValue(x: Double): Double =
        if (x >= 0.01) ln(x + 0.00964052) / ln(2.0) * 0.08550479 + 0.69336945 else 47.28711236 * max(0.0, x + 0.05641088).pow(2)

    /** The GPU kernel's encode (`encodeKernel`): branch on the scene value, with a floor inside the logarithm. */
    fun encodeKernel(x: Double): Double {
        val t = max(x + 0.05641088, 0.0)
        return if (x >= 0.01) ln(max(x + 0.00964052, 0.00000001)) / ln(2.0) * 0.08550479 + 0.69336945 else 47.28711236 * t * t
    }

    /** The GPU kernel's decode (`decodeKernel`), with its 0.20855531595 threshold. */
    fun decodeKernel(x: Double): Double =
        if (x >= 0.20855531595) 2.0.pow((x - 0.69336945) / 0.08550479) - 0.00964052 else sqrt(max(x, 0.0) / 47.28711236) - 0.05641088

    /**
     * Swift `highlights` (selectiveHighlights kernel): scene-linear selective
     * correction before display conversion or any LUT. Values at or below 0.35
     * luma are unchanged; zero is an exact bypass.
     */
    fun highlights(rgb: DoubleArray, amount: Double): DoubleArray {
        if (!amount.isFinite() || amount == 0.0) return rgb
        val a = max(-1.0, min(1.0, amount))
        val y = max(0.0, rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722)
        val delta = max(0.0, y - 0.35)
        val strength = max(0.000001, -a * 6.0)
        val down = ln(1.0 + strength * delta) / strength
        val up = delta * (1.0 + 2.0 * a * delta / (delta + 0.35))
        val mapped = if (y <= 0.35) y else 0.35 + (if (a < 0.0) down else up)
        val scale = if (y > 0.000001) mapped / y else 1.0
        return doubleArrayOf(rgb[0] * scale, rgb[1] * scale, rgb[2] * scale)
    }

    /** Swift `display` (logDisplay kernel): the original SDR viewing transform for Log scenes. */
    fun display(rgb: DoubleArray): DoubleArray {
        val y = max(0.0, rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722)
        val excess = max(0.0, y - 0.6)
        val mapped = if (y <= 0.6) y else 0.6 + 0.4 * excess / (excess + 0.4)
        val scale = if (y > 0.000001) mapped / y else 1.0
        val chroma = DoubleArray(3) { rgb[it] * scale - mapped }
        val hi = chroma.max()
        val lo = chroma.min()
        val fit = min(1.0, min((1.0 - mapped) / max(hi, 0.000001), mapped / max(-lo, 0.000001)))
        return DoubleArray(3) { min(1.0, max(0.0, mapped + chroma[it] * fit)) }
    }

    private fun xyz(r: DoubleArray, g: DoubleArray, b: DoubleArray): Array<DoubleArray> {
        fun column(p: DoubleArray) = doubleArrayOf(p[0] / p[1], 1.0, (1 - p[0] - p[1]) / p[1])
        val columns = listOf(column(r), column(g), column(b))
        val matrix = Array(3) { row -> DoubleArray(3) { col -> columns[col][row] } }
        val white = doubleArrayOf(0.3127 / 0.3290, 1.0, (1 - 0.3127 - 0.3290) / 0.3290)
        val s = Matrix3.times(Matrix3.inverse(matrix), white)
        return Array(3) { row -> DoubleArray(3) { col -> matrix[row][col] * s[col] } }
    }

    private val rec709 = xyz(doubleArrayOf(0.64, 0.33), doubleArrayOf(0.30, 0.60), doubleArrayOf(0.15, 0.06))
    private val bt2020 = xyz(doubleArrayOf(0.708, 0.292), doubleArrayOf(0.170, 0.797), doubleArrayOf(0.131, 0.046))
    private val apple = xyz(doubleArrayOf(0.725, 0.301), doubleArrayOf(0.221, 0.814), doubleArrayOf(0.068, -0.076))

    /** Swift `matrix(_:)`: the profile's primaries → linear Rec.709 (row-major). */
    fun matrix(profile: LUTInput): Array<DoubleArray> =
        Matrix3.times(Matrix3.inverse(rec709), if (profile == LUTInput.appleLog2) apple else bt2020)

    /** Swift `decode(_:profile:)` on one value. */
    fun decode(codes: DoubleArray, profile: LUTInput): DoubleArray =
        Matrix3.times(matrix(profile), DoubleArray(3) { decodeKernel(codes[it]) })

    /** Swift `encode(_:profile:)` on one value: Rec.709 scene → the profile's primaries → Log codes. */
    fun encode(scene: DoubleArray, profile: LUTInput): DoubleArray =
        Matrix3.times(Matrix3.inverse(matrix(profile)), scene).let { rgb -> DoubleArray(3) { encodeKernel(rgb[it]) } }
}

/** Row-major 3×3 helpers (Swift `simd_double3x3`). */
object Matrix3 {
    fun times(m: Array<DoubleArray>, v: DoubleArray): DoubleArray = DoubleArray(3) { r -> m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2] }
    fun times(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { r -> DoubleArray(3) { c -> a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] } }
    fun inverse(m: Array<DoubleArray>): Array<DoubleArray> {
        val (a, b, c) = Triple(m[0][0], m[0][1], m[0][2])
        val (d, e, f) = Triple(m[1][0], m[1][1], m[1][2])
        val (g, h, i) = Triple(m[2][0], m[2][1], m[2][2])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det))
    }
    fun diagonal(v: DoubleArray): Array<DoubleArray> = Array(3) { r -> DoubleArray(3) { c -> if (r == c) v[r] else 0.0 } }
    /** Column-major floats for a GLSL `mat3` uniform. */
    fun gl(m: Array<DoubleArray>): FloatArray = FloatArray(9) { m[it % 3][it / 3].toFloat() }
}
