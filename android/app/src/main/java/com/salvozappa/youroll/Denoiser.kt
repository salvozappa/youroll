// Android glue for ../ThirdParty/RNNoise/YouRecDenoiser.h (the iOS adapter of
// RNNoise v0.1, built unchanged by src/main/cpp/CMakeLists.txt into libyouroll_audio).
// Swift calls the C functions directly: `yr_denoiser_create`, `yr_denoiser_process`,
// `yr_speech_probability`, `yr_denoiser_destroy`.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import java.io.Closeable

/**
 * One RNNoise state: independent model and channel state, normalized float
 * samples at 48 kHz, exactly 480 samples per call. Not thread-safe.
 */
class Denoiser private constructor(private var state: Long) : Closeable {
    /** `yr_denoiser_process`: 480 samples of [input] from [offset] into [output] from [outputOffset]. */
    fun process(input: FloatArray, offset: Int, output: FloatArray, outputOffset: Int = 0) {
        check(state != 0L) { "Denoiser closed" }
        require(offset >= 0 && offset + 480 <= input.size && outputOffset >= 0 && outputOffset + 480 <= output.size)
        nativeProcess(state, input, offset, output, outputOffset)
    }

    /** `yr_speech_probability`: one channel of a 480-frame interleaved block whose channel sample starts at [offset]. */
    fun speechProbability(interleaved: FloatArray, offset: Int, stride: Int): Float {
        check(state != 0L) { "Denoiser closed" }
        require(stride in 1..8 && offset >= 0 && offset + 479 * stride < interleaved.size)
        return nativeSpeechProbability(state, interleaved, offset, stride)
    }

    override fun close() {
        if (state != 0L) nativeDestroy(state)
        state = 0L
    }

    companion object {
        /** False when the native library cannot load (JVM unit tests). */
        val available: Boolean by lazy { runCatching { System.loadLibrary("youroll_audio") }.isSuccess }

        /** `yr_denoiser_create`. */
        fun create(): Denoiser {
            if (!available) throw AudioProcessingError.unavailableModel
            val state = nativeCreate()
            if (state == 0L) throw AudioProcessingError.invalidFormat
            return Denoiser(state)
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(state: Long)
        @JvmStatic private external fun nativeProcess(state: Long, input: FloatArray, offset: Int, output: FloatArray, outputOffset: Int)
        @JvmStatic private external fun nativeSpeechProbability(state: Long, input: FloatArray, offset: Int, stride: Int): Float
    }
}
