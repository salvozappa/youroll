// Port of YouRec/AudioDSP.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Biquad stages retain their own channel state across decoder buffers. Audio is
// processed at 48 kHz. Bypass is exact; no model inference is implied by EQ.
class AudioChainProcessor(effects: List<AudioEffect>, sampleRate: Double = 48000.0, channels: Int = 2) {
    class Stage(effect: AudioEffect, sampleRate: Double, channels: Int) {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)

        init {
            val frequency = min(sampleRate * 0.45, max(20.0, effect.frequency))
            val w = 2 * Math.PI * frequency / sampleRate
            val c = cos(w); val s = sin(w)
            when (effect.kind) {
                EffectKind.gain -> b0 = 10.0.pow(effect.amount / 20)
                EffectKind.highPass -> {
                    val alpha = s / (2 * sqrt(0.5)); val a0 = 1 + alpha
                    b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0
                    a1 = -2 * c / a0; a2 = (1 - alpha) / a0
                }
                EffectKind.equalizer -> {
                    val amplitude = 10.0.pow(effect.amount / 40); val alpha = s / 2
                    val a0 = 1 + alpha / amplitude
                    b0 = (1 + alpha * amplitude) / a0; b1 = -2 * c / a0
                    b2 = (1 - alpha * amplitude) / a0
                    a1 = -2 * c / a0; a2 = (1 - alpha / amplitude) / a0
                }
                else -> error("Unsupported direct DSP stage")
            }
        }

        fun sample(input: Double, channel: Int): Double {
            val result = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * result + z2[channel]
            z2[channel] = b2 * input - a2 * result
            return result
        }
    }

    private val stages: List<Stage> = effects.filter { it.enabled }.map { Stage(it, sampleRate, channels) }
    var peak = 0f
        private set

    fun sample(value: Float, channel: Int): Float {
        var output = value.toDouble()
        for (stage in stages) output = stage.sample(output, channel)
        val result = if (output.isFinite()) output.toFloat() else 0f
        peak = max(peak, abs(result))
        return result
    }
}
