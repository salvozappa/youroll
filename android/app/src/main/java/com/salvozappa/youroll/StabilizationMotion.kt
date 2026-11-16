// Port of YouRec/StabilizationMotion.h (the Swift bridging of `yr_estimate_motion`)
// Copyright (c) 2026 Salvatore Sanfilippo (C++ original), YouRoll contributors (Kotlin/JNI binding). MIT License.
//
// The motion estimator itself is ../YouRec/StabilizationMotion.cpp, compiled
// unchanged by the NDK (app/src/main/cpp), so the maths is the iOS maths.
// The JNI wrapper also downsamples decoded luma (iOS: vImageScale_Planar8).
package com.salvozappa.youroll

import java.nio.ByteBuffer

/** Swift/C `YRMotion`: previous → current, in centered, top-left image coordinates. */
data class YRMotion(val x: Double, val y: Double, val angle: Double, val logScale: Double,
                    val inlierRatio: Double, val inliers: Int, val valid: Boolean)

object StabilizationMotion {
    init { System.loadLibrary("youroll_stabilization") }

    /** `yr_estimate_motion` on two dense [width]×[height] luma frames (direct buffers). */
    fun estimate(previous: ByteBuffer, current: ByteBuffer, width: Int, height: Int): YRMotion {
        val values = DoubleArray(6)
        val valid = nativeEstimate(previous, current, width, height, values)
        return YRMotion(values[0], values[1], values[2], values[3], values[4], values[5].toInt(), valid)
    }

    /** Area-average downsampling of a plane's crop window into a dense [outWidth]×[outHeight] direct buffer. */
    fun downsample(plane: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, cropWidth: Int, cropHeight: Int,
                   output: ByteBuffer, outWidth: Int, outHeight: Int): Boolean =
        nativeDownsample(plane, rowStride, pixelStride, left, top, cropWidth, cropHeight, output, outWidth, outHeight)

    @JvmStatic private external fun nativeEstimate(previous: ByteBuffer, current: ByteBuffer, width: Int, height: Int, result: DoubleArray): Boolean
    @JvmStatic private external fun nativeDownsample(plane: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int,
                                                     cropWidth: Int, cropHeight: Int, output: ByteBuffer, outWidth: Int, outHeight: Int): Boolean
}
