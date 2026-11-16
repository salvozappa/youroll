// Port of ThirdParty/DeepFilterNet3/DeepFilterNetEngine.swift
// (adapted from Kyle Howells' DeepFilterNet-mlx, Apache-2.0 / MIT; see
// ../ThirdParty/DeepFilterNet3/LICENSE-MIT, LICENSE-APACHE and NOTICE).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS runs a stateful per-hop Core ML conversion (`DeepFilterNet3-Streaming.mlpackage`)
// whose GRU states are inputs and outputs. Android has no Core ML; YouRoll runs the
// official ONNX export of the same DeepFilterNet3 weights (Rikorose/DeepFilterNet
// v0.5.6, `models/DeepFilterNet3_onnx.tar.gz`: enc, erb_dec, df_dec; see
// ThirdParty/DeepFilterNet3-ONNX/PROVENANCE.md) with ONNX Runtime. That export
// takes whole feature sequences and keeps its recurrent state internal, so:
//
// - The DSP is the Swift engine's, unchanged: Vorbis-window STFT (960/480),
//   ERB features with the exponential mean and unit normalisations and the
//   initial states of DeepFilterNet3-DSP.json, the ERB mask expanded by the
//   inverse filterbank, the order-5 deep filter with two frames of lookahead,
//   overlap-add with the same delay compensation and the same end-of-stream drain.
// - The network sees the features shifted by the two conv-lookahead frames
//   (the batch model's `pad_feat`), which is what the streaming graph's
//   ten-frame history gives it per hop.
// - Inference runs on blocks of [chunk] target frames, each preceded by
//   [warmup] frames of context whose outputs are discarded, so the recurrent
//   state is rebuilt across block boundaries with bounded memory.
//
// Measured on the Mac against the iOS Core ML model, hop by hop (a harmonic
// voice with noise, 4 s): same alignment (lag 0), 1.3 % relative difference in
// voiced parts, noise in pauses −70.7 dB here vs −65.2 dB on iOS (from −34 dB).
package com.salvozappa.youroll

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.Closeable
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The three ONNX graphs of DeepFilterNet3 for [frames] positions. */
interface DeepFilterNetModel : Closeable {
    class Output(
        /** ERB mask, `[frames][32]`. */
        val mask: FloatArray,
        /** Deep filter coefficients, `[frames][96][order 5][re, im]` (the export's `coefs` layout). */
        val coefs: FloatArray,
    )

    /**
     * [featErb] is `[frames][32]`, [featSpec] is `[2][frames][96]` (real, then
     * imaginary), both already shifted by the conv lookahead.
     */
    fun run(featErb: FloatArray, featSpec: FloatArray, frames: Int): Output
}

class DeepFilterNetEngine(private val dsp: ByteArray, private val model: DeepFilterNetModel,
                          private val chunk: Int = 1000, private val warmup: Int = 300) {
    // MARK: - Config constants
    private val fftSize = 960
    private val hopSize = 480
    private val freqBins = 481
    private val nbErb = 32
    private val nbDf = 96
    private val dfOrder = 5
    private val dfLookahead = 2
    private val convLookahead = 2
    private val normAlpha = 0.99f
    private val wnorm = 1.0f / (fftSize * fftSize).toFloat() * (2 * hopSize).toFloat()

    // MARK: - DSP state
    private val vorbisWindow = FloatArray(fftSize) { index ->
        val inner = sin(0.5f * PI.toFloat() * (index + 0.5f) / (fftSize / 2).toFloat())
        sin(0.5f * PI.toFloat() * inner * inner)
    }
    private val fft = ComplexFFT(fftSize)
    private var erbFB = FloatArray(0)      // [freqBins, nbErb]
    private var erbInvFB = FloatArray(0)   // [nbErb, freqBins]
    private var meanNormStateInit = FloatArray(0)
    private var unitNormStateInit = FloatArray(0)
    private val meanNormState = FloatArray(nbErb)
    private val unitNormState = FloatArray(nbDf)
    private val analysisMem = FloatArray(fftSize - hopSize)
    private val synthesisMem = FloatArray(fftSize - hopSize)

    // MARK: - Streaming state: frames by absolute index from `base`.
    private var base = 0
    private val spectra = ArrayList<FloatArray>()   // [re 481, im 481] per frame
    private val erbFeatures = ArrayList<FloatArray>()
    private val specFeatures = ArrayList<FloatArray>() // [re 96, im 96]
    private var nextTarget = 0
    private var inputSamples = 0L
    private var outputSamples = 0L
    private var delaySamplesToDrop = fftSize - hopSize
    private var finished = false
    private var prepared = false

    /** Loads and validates the DSP parameters; resets the stream. */
    fun prepare() {
        if (!prepared) {
            if (dsp.size >= 1_000_000 || sha256(dsp) != dspSHA256) throw DeepFilterNetError("The bundled DeepFilterNet3 model does not match this project’s model version. Its audio has been preserved.")
            val arrays = SwiftJSON.decodeFromString(MapSerializer(String.serializer(), ListSerializer(Float.serializer())), dsp.decodeToString())
            fun values(name: String, count: Int): FloatArray {
                val list = arrays[name]
                if (list == null || list.size != count || !list.all { it.isFinite() }) throw DeepFilterNetError("DeepFilterNet3 DSP parameters do not match the model")
                return list.toFloatArray()
            }
            erbFB = values("erb_fb", 481 * 32)
            erbInvFB = values("erb_inv_fb", 32 * 481)
            meanNormStateInit = values("mean_norm_state", 32)
            unitNormStateInit = values("unit_norm_state", 96)
            prepared = true
        }
        reset()
    }

    /** Clears recurrent, normalization, lookahead, and overlap-add state. */
    fun reset() {
        check(prepared) { "DeepFilterNet3 is not prepared" }
        analysisMem.fill(0f); synthesisMem.fill(0f)
        meanNormStateInit.copyInto(meanNormState); unitNormStateInit.copyInto(unitNormState)
        base = 0; spectra.clear(); erbFeatures.clear(); specFeatures.clear()
        nextTarget = 0; inputSamples = 0; outputSamples = 0
        delaySamplesToDrop = fftSize - hopSize
        finished = false
    }

    /**
     * Processes exactly one 480-sample, 48 kHz mono hop. Output arrives in
     * blocks once a whole inference block (plus lookahead) is available.
     */
    fun processHop(samples: FloatArray): FloatArray {
        if (finished) throw DeepFilterNetError("Reset the DeepFilterNet3 engine before starting another stream.")
        if (samples.size != hopSize) throw DeepFilterNetError("DeepFilterNet3 requires $hopSize samples per hop; received ${samples.size}")
        if (!samples.all { it.isFinite() }) throw DeepFilterNetError("DeepFilterNet3 received non-finite audio samples.")
        inputSamples += samples.size
        analyze(samples)
        val output = if (frameCount - convLookahead >= nextTarget + chunk) infer(nextTarget + chunk) else FloatArray(0)
        outputSamples += output.size
        return output
    }

    /** Returns delayed output at the end of a stream. */
    fun flush(): FloatArray {
        if (finished) return FloatArray(0)
        finished = true
        if (inputSamples == 0L) return FloatArray(0)
        repeat(convLookahead + 1) { analyze(FloatArray(hopSize)) }
        var tail = FloatArray(0)
        while (nextTarget < frameCount - convLookahead) tail += infer(min(frameCount - convLookahead, nextTarget + chunk))
        val remaining = max(0L, inputSamples - outputSamples).toInt()
        if (tail.size > remaining) tail = tail.copyOf(remaining)
        outputSamples += tail.size
        return tail
    }

    private val frameCount get() = base + spectra.size

    // MARK: - Analysis

    private val frame = FloatArray(fftSize)
    private val zeroImag = FloatArray(fftSize)
    private val outRe = FloatArray(fftSize)
    private val outIm = FloatArray(fftSize)

    private fun analyze(hop: FloatArray) {
        // STFT forward with the Vorbis window, 481 unique bins scaled by wnorm.
        val overlap = fftSize - hopSize
        for (i in 0 until overlap) frame[i] = analysisMem[i] * vorbisWindow[i]
        for (i in 0 until hopSize) frame[overlap + i] = hop[i] * vorbisWindow[overlap + i]
        System.arraycopy(hop, hopSize - overlap, analysisMem, 0, overlap)
        zeroImag.fill(0f)
        fft.transform(frame, zeroImag, outRe, outIm, inverse = false)
        val spectrum = FloatArray(2 * freqBins)
        for (k in 0 until freqBins) { spectrum[k] = outRe[k] * wnorm; spectrum[freqBins + k] = outIm[k] * wnorm }
        // ERB power features in dB, mean-normalized.
        val erb = FloatArray(nbErb)
        for (k in 0 until freqBins) {
            val re = spectrum[k]; val im = spectrum[freqBins + k]
            val power = re * re + im * im
            if (power == 0f) continue
            val row = k * nbErb
            for (b in 0 until nbErb) erb[b] += power * erbFB[row + b]
        }
        for (b in 0 until nbErb) {
            val x = 10f * log10(erb[b] + 1e-10f)
            meanNormState[b] = x * (1f - normAlpha) + meanNormState[b] * normAlpha
            erb[b] = (x - meanNormState[b]) / 40f
        }
        // Unit-normalized complex features of the deep-filter bins.
        val spec = FloatArray(2 * nbDf)
        for (f in 0 until nbDf) {
            val re = spectrum[f]; val im = spectrum[freqBins + f]
            val mag = sqrt(re * re + im * im)
            unitNormState[f] = mag * (1f - normAlpha) + unitNormState[f] * normAlpha
            val norm = sqrt(max(unitNormState[f], 1e-10f))
            spec[f] = re / norm; spec[nbDf + f] = im / norm
        }
        spectra += spectrum; erbFeatures += erb; specFeatures += spec
    }

    // MARK: - Inference and synthesis

    /** Enhances target frames `nextTarget until end` and returns their delayed audio. */
    private fun infer(end: Int): FloatArray {
        val start = nextTarget
        // Warm-up positions start no earlier than the oldest feature frame still kept.
        val first = min(start, maxOf(0, start - warmup, base - convLookahead))
        val frames = end - first
        // Position t sees the features of frame t + convLookahead (zero past the stream end).
        val featErb = FloatArray(frames * nbErb)
        val featSpec = FloatArray(2 * frames * nbDf)
        for (t in 0 until frames) {
            val source = first + t + convLookahead
            if (source >= frameCount) continue
            erbFeatures[source - base].copyInto(featErb, t * nbErb)
            val spec = specFeatures[source - base]
            System.arraycopy(spec, 0, featSpec, t * nbDf, nbDf)
            System.arraycopy(spec, nbDf, featSpec, frames * nbDf + t * nbDf, nbDf)
        }
        val output = model.run(featErb, featSpec, frames)
        if (output.mask.size != frames * nbErb || output.coefs.size != frames * nbDf * dfOrder * 2) throw DeepFilterNetError("DeepFilterNet3 output shape does not match the model")
        val audio = FloatArray((end - start) * hopSize)
        var written = 0
        val enhanced = FloatArray(2 * freqBins)
        for (target in start until end) {
            val t = target - first
            val spectrum = spectra[target - base]
            // ERB mask expanded to every bin.
            for (k in 0 until freqBins) {
                var gain = 0f
                for (b in 0 until nbErb) gain += output.mask[t * nbErb + b] * erbInvFB[b * freqBins + k]
                enhanced[k] = spectrum[k] * gain; enhanced[freqBins + k] = spectrum[freqBins + k] * gain
            }
            // Deep filter over frames target-2 … target+2 for the low bins.
            for (f in 0 until nbDf) {
                var sumRe = 0f; var sumIm = 0f
                for (order in 0 until dfOrder) {
                    val index = target - (dfOrder - dfLookahead - 1) + order
                    if (index < base || index >= frameCount || index < 0) continue
                    val s = spectra[index - base]
                    val c = ((t * nbDf + f) * dfOrder + order) * 2
                    val cr = output.coefs[c]; val ci = output.coefs[c + 1]
                    val sr = s[f]; val si = s[freqBins + f]
                    sumRe += sr * cr - si * ci
                    sumIm += sr * ci + si * cr
                }
                enhanced[f] = sumRe; enhanced[freqBins + f] = sumIm
            }
            written += synthesize(enhanced, audio, written)
        }
        nextTarget = end
        // Keep the frames the next block's warm-up and deep filter still read.
        val keep = max(0, min(nextTarget - warmup, nextTarget - (dfOrder - dfLookahead - 1)) - base)
        if (keep > 0) {
            spectra.subList(0, keep).clear(); erbFeatures.subList(0, keep).clear(); specFeatures.subList(0, keep).clear()
            base += keep
        }
        return if (written == audio.size) audio else audio.copyOf(written)
    }

    private val fullRe = FloatArray(fftSize)
    private val fullIm = FloatArray(fftSize)

    /** Inverse STFT of one frame with overlap-add; returns the samples appended to [output]. */
    private fun synthesize(spectrum: FloatArray, output: FloatArray, offset: Int): Int {
        for (k in 0 until freqBins) { fullRe[k] = spectrum[k] / wnorm; fullIm[k] = spectrum[freqBins + k] / wnorm }
        for (k in 1 until fftSize / 2) { fullRe[fftSize - k] = fullRe[k]; fullIm[fftSize - k] = -fullIm[k] }
        fft.transform(fullRe, fullIm, outRe, outIm, inverse = true)
        val scale = 1f / fftSize
        val overlap = fftSize - hopSize
        var count = 0
        for (i in 0 until hopSize) {
            var value = outRe[i] * scale * vorbisWindow[i]
            if (i < overlap) value += synthesisMem[i]
            if (delaySamplesToDrop > 0) { delaySamplesToDrop -= 1; continue }
            output[offset + count++] = value
        }
        for (i in 0 until overlap) synthesisMem[i] = outRe[hopSize + i] * scale * vorbisWindow[hopSize + i]
        return count
    }

    companion object {
        const val dspSHA256 = "3dd66deaeafd7612f7208bfbbccb9d1d9691296acbab313e17fb099d5cdfdf29"
        /** Part of the audio cache identity: model files, runtime and block layout. */
        const val runtime = "dfn3-onnx-v0.5.6-c94d91f7-ort-chunk1000-warm300"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

class DeepFilterNetError(message: String) : Exception(message)

/**
 * A mixed-radix (2, 3, 5) complex FFT, unscaled in both directions like
 * vDSP_DFT_zop (`vDSP_DFT_Execute`), which iOS uses for the non-power-of-two 960.
 */
class ComplexFFT(private val n: Int) {
    private val cosTable = DoubleArray(n) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n) { sin(2 * PI * it / n) }
    private val factors: IntArray
    private val workRe = DoubleArray(n)
    private val workIm = DoubleArray(n)
    private val scratchRe = DoubleArray(n)
    private val scratchIm = DoubleArray(n)
    private val tempRe = DoubleArray(8)
    private val tempIm = DoubleArray(8)

    init {
        val list = ArrayList<Int>()
        var rest = n
        for (p in intArrayOf(5, 3, 2)) while (rest % p == 0) { list += p; rest /= p }
        require(rest == 1 && n > 0) { "FFT size must be a product of 2, 3 and 5" }
        factors = list.toIntArray()
    }

    fun transform(inRe: FloatArray, inIm: FloatArray, outRe: FloatArray, outIm: FloatArray, inverse: Boolean) {
        for (i in 0 until n) { scratchRe[i] = inRe[i].toDouble(); scratchIm[i] = inIm[i].toDouble() }
        recurse(0, 1, n, 0, 0, if (inverse) 1 else -1)
        for (i in 0 until n) { outRe[i] = workRe[i].toFloat(); outIm[i] = workIm[i].toFloat() }
    }

    /** DFT of the [size] samples scratch[offset + k·stride] into work[out …], decimation in time. */
    private fun recurse(offset: Int, stride: Int, size: Int, out: Int, depth: Int, sign: Int) {
        if (size == 1) { workRe[out] = scratchRe[offset]; workIm[out] = scratchIm[offset]; return }
        val p = factors[depth]
        val m = size / p
        for (q in 0 until p) recurse(offset + q * stride, stride * p, m, out + q * m, depth + 1, sign)
        val step = n / size
        for (k in 0 until m) {
            for (q in 0 until p) { tempRe[q] = workRe[out + q * m + k]; tempIm[q] = workIm[out + q * m + k] }
            for (r in 0 until p) {
                val bin = k + r * m
                var sumRe = 0.0; var sumIm = 0.0
                for (q in 0 until p) {
                    val angle = (q * bin * step) % n
                    val c = cosTable[angle]; val s = sign * sinTable[angle]
                    sumRe += tempRe[q] * c - tempIm[q] * s
                    sumIm += tempRe[q] * s + tempIm[q] * c
                }
                workRe[out + bin] = sumRe; workIm[out + bin] = sumIm
            }
        }
    }
}
