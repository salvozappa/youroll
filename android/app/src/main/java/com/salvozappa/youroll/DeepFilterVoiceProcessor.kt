// Port of YouRec/DeepFilterVoiceProcessor.swift, plus the Android model loader
// (ONNX Runtime with the official DeepFilterNet3 ONNX export, assets/DeepFilterNet3).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** Independent recurrent state per channel, bounded file-backed processing. The engine drains its lookahead; dry mixing follows the output sample clock. */
object DeepFilterVoiceProcessor {
    /** Model files: the app installs an asset reader (MainActivity); tests install a fake model. */
    interface Resources {
        fun dsp(): ByteArray
        fun model(): DeepFilterNetModel
    }

    @Volatile var resources: Resources? = null

    fun process(input: File, output: File, amount: Float, gate: AudioThermalGate = AudioThermalGate(), progress: (Double) -> Unit = {}) {
        val resources = resources ?: throw AudioProcessingError.unavailableModel
        val source = AudioFile.Reader(input)
        val dry = AudioFile.Reader(input)
        try {
            val channels = source.channels
            if (source.info.sampleRate != 48000 || channels !in 1..2 || !amount.isFinite() || amount !in 0f..1f) throw AudioProcessingError.invalidFormat
            val dsp = resources.dsp()
            gate.cooperate()
            // The ONNX graphs are stateless between calls (the engine keeps every
            // stream state), so the channels share one loaded model.
            val model = resources.model()
            try {
                val engines = (0 until channels).map { DeepFilterNetEngine(dsp, model).also { engine -> engine.prepare() } }
                AudioFile.Writer(output, channels).use { target ->
                    var written = 0L
                    var dryBuffer = FloatArray(0)
                    var rendered = FloatArray(0)
                    // Every engine emits the same number of samples per hop.
                    fun write(samples: List<FloatArray>) {
                        val first = samples.firstOrNull() ?: return
                        if (!samples.all { it.size == first.size }) throw AudioProcessingError.invalidFormat
                        val count = minOf(first.size.toLong(), source.length - written).toInt()
                        if (count <= 0) return
                        if (dryBuffer.size < count * channels) { dryBuffer = FloatArray(count * channels); rendered = FloatArray(count * channels) }
                        if (dry.read(dryBuffer, count) != count) throw AudioProcessingError.invalidFormat
                        for (channel in 0 until channels) for (frame in 0 until count) {
                            val original = dryBuffer[frame * channels + channel]; val enhanced = samples[channel][frame]
                            val value = if (amount == 1f) enhanced else original + amount * (enhanced - original)
                            if (!value.isFinite()) throw AudioProcessingError.invalidFormat
                            rendered[frame * channels + channel] = value
                        }
                        target.write(rendered, count); written += count
                    }
                    val input = FloatArray(480 * channels)
                    val hop = FloatArray(480)
                    while (source.framePosition < source.length) {
                        gate.cooperate()
                        val count = source.read(input, 480)
                        if (count <= 0) throw AudioProcessingError.invalidFormat
                        val outputs = engines.mapIndexed { channel, engine ->
                            hop.fill(0f)
                            for (frame in 0 until count) hop[frame] = input[frame * channels + channel]
                            engine.processHop(hop)
                        }
                        write(outputs)
                        progress(source.framePosition.toDouble() / maxOf(1, source.length))
                    }
                    gate.cooperate()
                    write(engines.map { it.flush() })
                    if (written != source.length) throw AudioProcessingError.invalidFormat
                }
            } finally {
                model.close()
            }
        } catch (failure: Throwable) {
            output.delete(); throw failure
        } finally {
            source.close(); dry.close()
        }
    }
}

/** The official ONNX export run by ONNX Runtime on the CPU. */
class OnnxDeepFilterNetModel(encoder: ByteArray, erbDecoder: ByteArray, dfDecoder: ByteArray) : DeepFilterNetModel {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val enc = environment.createSession(encoder, options)
    private val erbDec = environment.createSession(erbDecoder, options)
    private val dfDec = environment.createSession(dfDecoder, options)

    override fun run(featErb: FloatArray, featSpec: FloatArray, frames: Int): DeepFilterNetModel.Output {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(featErb), longArrayOf(1, 1, frames.toLong(), 32)).use { erb ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(featSpec), longArrayOf(1, 2, frames.toLong(), 96)).use { spec ->
                enc.run(mapOf("feat_erb" to erb, "feat_spec" to spec)).use { encoded ->
                    fun value(name: String) = encoded.get(name).orElseThrow { DeepFilterNetError("DeepFilterNet3 encoder output $name is missing") } as OnnxTensor
                    val mask = erbDec.run(mapOf("emb" to value("emb"), "e3" to value("e3"), "e2" to value("e2"), "e1" to value("e1"), "e0" to value("e0"))).use { result ->
                        (result.get(0) as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
                    }
                    val coefs = dfDec.run(mapOf("emb" to value("emb"), "c0" to value("c0")), setOf("coefs")).use { result ->
                        (result.get(0) as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
                    }
                    return DeepFilterNetModel.Output(mask, coefs)
                }
            }
        }
    }

    override fun close() {
        enc.close(); erbDec.close(); dfDec.close(); options.close()
    }

    /** Reads `assets/DeepFilterNet3` (copied at build time from ThirdParty/DeepFilterNet3-ONNX and ../ThirdParty/DeepFilterNet3). */
    class Assets(private val context: android.content.Context) : DeepFilterVoiceProcessor.Resources {
        private fun read(name: String) = context.assets.open("DeepFilterNet3/$name").use { it.readBytes() }
        override fun dsp() = read("DeepFilterNet3-DSP.json")
        override fun model(): DeepFilterNetModel {
            val files = listOf("enc.onnx", "erb_dec.onnx", "df_dec.onnx").map(::read)
            for ((bytes, expected) in files.zip(onnxSHA256)) if (DeepFilterNetEngine.sha256(bytes) != expected) throw DeepFilterNetError("The bundled DeepFilterNet3 model does not match this project’s model version. Its audio has been preserved.")
            return try {
                OnnxDeepFilterNetModel(files[0], files[1], files[2])
            } catch (failure: UnsatisfiedLinkError) {
                throw DeepFilterNetError("DeepFilterNet3 isn’t available on this device (no ONNX Runtime for its processor).")
            }
        }
    }

    companion object {
        /** SHA-256 of enc.onnx, erb_dec.onnx and df_dec.onnx (PROVENANCE.md). */
        val onnxSHA256 = listOf(
            "7c5399d3da8a50ebef1c1a0ae421b33376aa5e45d0e92df16da7e83c9c131916",
            "ab669a1d10afe20911728b33053a452071042317a90581092b325da7b2f9d895",
            "23114ce3b0f6464b763ee62f7bb8aab6b2a129a21eabd5bcfe59413db05f278a")
    }
}
