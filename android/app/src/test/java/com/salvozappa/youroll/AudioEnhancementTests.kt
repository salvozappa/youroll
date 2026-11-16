// YouRoll JVM tests for Enhance audio. The signal checks port `audioFilterAudit`
// in ../Tests/DeepFilterTests.swift (gain, disabled gain bit-exact, low-cut,
// EQ, chain checkpoints) and the DeepFilter wrapper checks of `deepFilterTests`
// that do not need the model (exact bypass, partial hops, lengths); the
// DeepFilterNet3 DSP runs here with an identity network instead of ONNX Runtime.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class AudioEnhancementTests {
    private val folder: File = Files.createTempDirectory("youroll-audio").toFile().also { it.deleteOnExit() }

    private fun save(name: String, channels: List<FloatArray>): File {
        val file = File(folder, "$name.wav")
        AudioFile.Writer(file, channels.size).use { writer ->
            val frames = channels[0].size
            val interleaved = FloatArray(frames * channels.size) { channels[it % channels.size][it / channels.size] }
            writer.write(interleaved, frames)
        }
        return file
    }

    private fun read(file: File): List<FloatArray> = AudioFile.Reader(file).use { reader ->
        val buffer = FloatArray((reader.length * reader.channels).toInt())
        reader.read(buffer, reader.length.toInt())
        List(reader.channels) { channel -> FloatArray(reader.length.toInt()) { buffer[it * reader.channels + channel] } }
    }

    private fun process(input: File, name: String, effects: List<AudioEffect>, checkpoints: List<File> = emptyList()): List<FloatArray> {
        val output = File(folder, "$name.wav")
        output.delete()
        OfflineAudioPipeline.process(input, effects, output, checkpoints)
        return read(output)
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / max(1, x.size))
    private fun db(ratio: Double) = 20 * log10(max(1e-12, ratio))

    /** Swift `audioFilterAudit`'s fixture: 40 Hz, 1.8 kHz and 9 kHz at 0.03 each, two seconds, stereo. */
    private val tones: File by lazy {
        val value = FloatArray(96000) { i -> val t = i / 48000.0; listOf(40.0, 1800.0, 9000.0).sumOf { 0.03 * sin(2 * PI * it * t) }.toFloat() }
        save("tones", listOf(value, value.copyOf()))
    }

    private fun amplitude(x: FloatArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in 24000 until 72000) { val a = 2 * PI * hz * i / 48000; re += x[i] * cos(a); im += x[i] * sin(a) }
        return hypot(re, im) / 24000
    }

    @Test fun gainIsExact() {
        val raw = read(tones)[0]
        for (gain in listOf(-18.0, -6.0, 0.0, 6.0, 18.0)) {
            val x = process(tones, "gain-$gain", listOf(AudioEffect(kind = EffectKind.gain, amount = gain)))[0]
            assertEquals("Gain $gain dB", gain, db(rms(x) / rms(raw)), 0.0001)
        }
        val disabled = AudioEffect(kind = EffectKind.gain, amount = 18.0, enabled = false)
        val bypass = process(tones, "disabled-gain", listOf(disabled))
        assertArrayEquals("Disabled gain is bit-exact", raw, bypass[0], 0f)
        assertArrayEquals("Disabled gain keeps file bytes", tones.readBytes(), File(folder, "disabled-gain.wav").readBytes())
    }

    @Test fun lowCutAndEqualizer() {
        val raw = read(tones)[0]
        val lowcut = process(tones, "lowcut-200Hz", listOf(AudioEffect(kind = EffectKind.highPass, frequency = 200.0)))[0]
        val cutDB = db(amplitude(lowcut, 40.0) / amplitude(raw, 40.0))
        val passDB = db(amplitude(lowcut, 1800.0) / amplitude(raw, 1800.0))
        assertTrue("Low-cut removes rumble: $cutDB", cutDB < -27)
        assertTrue("Low-cut preserves speech band: $passDB", abs(passDB) < 0.01)
        val eq = process(tones, "eq-plus6-1800Hz", listOf(AudioEffect(kind = EffectKind.equalizer, amount = 6.0, frequency = 1800.0)))[0]
        assertEquals("EQ delivers its actual centre-frequency gain", 6.0, db(amplitude(eq, 1800.0) / amplitude(raw, 1800.0)), 0.01)
        // A stage chain multiplies: EQ +6 dB at 1.8 kHz then −6 dB gain leaves 1.8 kHz unchanged and lowers 9 kHz.
        val chain = process(tones, "eq-gain", listOf(AudioEffect(kind = EffectKind.equalizer, amount = 6.0, frequency = 1800.0), AudioEffect(kind = EffectKind.gain, amount = -6.0)))[0]
        assertEquals(0.0, db(amplitude(chain, 1800.0) / amplitude(raw, 1800.0)), 0.02)
        assertTrue(db(amplitude(chain, 9000.0) / amplitude(raw, 9000.0)) < -5)
    }

    @Test fun processorMatchesSwiftCoefficients() {
        // AudioChainProcessor: a gain stage is a pure scale, and peaks are tracked after the chain.
        val processor = AudioChainProcessor(listOf(AudioEffect(kind = EffectKind.gain, amount = 20.0)))
        assertEquals(10f, processor.sample(1f, 0), 1e-5f)
        assertEquals(10f, processor.peak, 1e-5f)
        // A non-finite intermediate is replaced by silence.
        assertEquals(0f, AudioChainProcessor(listOf(AudioEffect(kind = EffectKind.gain, amount = 0.0))).sample(Float.NaN, 0), 0f)
        // Highpass at DC: z-transform gain 0 (the steady-state sample tends to 0).
        val highpass = AudioChainProcessor(listOf(AudioEffect(kind = EffectKind.highPass, frequency = 80.0)), channels = 1)
        var last = 1f
        repeat(48000) { last = highpass.sample(1f, 0) }
        assertEquals(0f, last, 1e-4f)
    }

    @Test fun checkpointsReuseChainPrefixes() {
        val stages = listOf(AudioEffect(kind = EffectKind.equalizer, amount = 3.0, frequency = 2200.0), AudioEffect(kind = EffectKind.gain, amount = -6.0))
        val checkpoints = listOf(File(folder, "prefix-1.wav"), File(folder, "prefix-2.wav"))
        val first = process(tones, "chain-first", stages, checkpoints)
        assertTrue(checkpoints.all { it.exists() && OfflineAudioPipeline.peakFile(it).exists() })
        // A cached prefix is reused byte for byte: corrupt the original input's samples and the result is unchanged.
        val stamp = checkpoints[0].lastModified()
        val second = process(tones, "chain-second", stages, checkpoints)
        assertArrayEquals(first[0], second[0], 0f)
        assertEquals(stamp, checkpoints[0].lastModified())
        // Changing only the last stage keeps the first checkpoint and replaces the result.
        val changed = listOf(stages[0], AudioEffect(kind = EffectKind.gain, amount = -12.0))
        val third = process(tones, "chain-third", changed, listOf(checkpoints[0], File(folder, "prefix-2b.wav")))
        assertEquals(-6.0, db(rms(third[0]) / rms(first[0])), 0.001)
        // Output never overwrites.
        val existing = File(folder, "chain-first.wav")
        assertTrue(runCatching { OfflineAudioPipeline.process(tones, stages, existing) }.exceptionOrNull() is java.nio.file.FileAlreadyExistsException)
    }

    @Test fun neuralZeroAmountIsExactBypassAndUnavailableModelsAreRefused() {
        val raw = read(tones)
        val zero = AudioEffect.make(EffectKind.voiceIsolation).copy(amount = 0.0)
        assertArrayEquals(raw[1], process(tones, "rn-zero", listOf(zero))[1], 0f)
        // LavaSR and VoiceFixer keep their settings, but an active stage is refused with a clear reason.
        val lava = AudioEffect.make(EffectKind.studioVoice)
        val error = OfflineAudioPipeline.unavailable(listOf(lava))
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("LavaSR"))
        assertNull(OfflineAudioPipeline.unavailable(listOf(lava.copy(enabled = false))))
        assertNull(OfflineAudioPipeline.unavailable(listOf(lava.copy(amount = 0.0))))
        assertArrayEquals(raw[0], process(tones, "lava-zero", listOf(lava.copy(amount = 0.0)))[0], 0f)
        // An unknown model identity is never substituted.
        val unknown = AudioEffect.make(EffectKind.voiceIsolation).copy(model = AudioModel("other", "", ""))
        assertTrue(OfflineAudioPipeline.unavailable(listOf(unknown))?.message?.contains("unavailable") == true)
        // ExportPlan refuses the same chains.
        val edit = EditState()
        val id = edit.timeline.insertAudio(SourceMedia(filename = "v.wav", duration = 4.0, audioOnly = true), EditTime.zero)
        edit.timeline.enhance(listOf(lava), TimelineSelection(audio = setOf(id)))
        assertTrue(runCatching { ExportPlan.make(edit) }.exceptionOrNull() is ExportPlan.Unsupported)
    }

    @Test fun listeningGainsMatchSwift() {
        // Equal windows: no correction.
        assertEquals(1f to 1f, AudioPreviewLevels.gains(floatArrayOf(0.01f, 0.01f), floatArrayOf(0.01f, 0.01f)))
        // Processed 4× the energy: processed plays at half amplitude, original unchanged.
        val (original, processed) = AudioPreviewLevels.gains(floatArrayOf(0.01f, 0.01f), floatArrayOf(0.04f, 0.04f))
        assertEquals(1f, original, 1e-6f); assertEquals(0.5f, processed, 1e-6f)
        // Heavily suppressed (denoised) and near-silent windows are no reference.
        assertEquals(1f to 1f, AudioPreviewLevels.gains(floatArrayOf(0.01f), floatArrayOf(0.00001f)))
        assertEquals(1f to 1f, AudioPreviewLevels.gains(floatArrayOf(0f), floatArrayOf(0f)))
        // Never below −18 dB.
        assertEquals(0.125f, AudioPreviewLevels.gains(floatArrayOf(0.0001f), floatArrayOf(1f)).second, 1e-6f)
        assertTrue(AudioPreviewLevels.allowsMatching(listOf(AudioEffect.make(EffectKind.voiceIsolation))))
        assertTrue(!AudioPreviewLevels.allowsMatching(listOf(AudioEffect.make(EffectKind.gain))))
        assertTrue(AudioPreviewLevels.allowsMatching(listOf(AudioEffect.make(EffectKind.gain).copy(enabled = false))))
    }

    @Test fun planPiecesFollowEnhancementRegions() {
        val source = SourceMedia(filename = "v.wav", duration = 8.0, audioOnly = true)
        val edit = EditState()
        val id = edit.timeline.insertAudio(source, EditTime.zero)
        val chain = listOf(AudioEffect(kind = EffectKind.gain, amount = -12.0))
        edit.timeline.enhance(chain, TimelineSelection(audio = setOf(id), range = EditInterval.of(2.0, 6.0)))
        val clips = ExportPlan.make(edit).audio.flatten().filterIsInstance<ExportPlan.Piece.Clip>()
        val key = TrackRenderer.audioKey(source.id, chain)
        assertEquals(listOf(null, key, null), clips.map { it.audioKey })
        assertEquals(listOf(0.0, 2.0, 6.0), clips.map { it.sourceStart })
        // The key ignores bypassed stages and is stable.
        assertEquals(key, TrackRenderer.audioKey(source.id, chain + AudioEffect(kind = EffectKind.gain, amount = 3.0, enabled = false)))
        val project = Project(title = "Enhance").also { it.sources = listOf(source) }
        project.history = listOf(edit); project.historyCursor = 0
        assertEquals(setOf(key), TrackRenderer.enhancementRequests(project, clips).keys)
        // Removing the chain restores one original piece.
        edit.timeline.enhance(emptyList(), TimelineSelection(audio = setOf(id)))
        assertEquals(listOf<String?>(null), ExportPlan.make(edit).audio.flatten().filterIsInstance<ExportPlan.Piece.Clip>().map { it.audioKey })
    }

    @Test fun previewMixPlaysProcessedSound() {
        // The mixer reads an enhanced piece from its processed samples on the source clock.
        val source = SourceMedia(filename = "v.wav", duration = 1.0, audioOnly = true)
        val edit = EditState()
        val id = edit.timeline.insertAudio(source, EditTime.zero)
        val chain = listOf(AudioEffect(kind = EffectKind.gain, amount = -6.0))
        edit.timeline.enhance(chain, TimelineSelection(audio = setOf(id), range = EditInterval.of(0.5, 1.0)))
        val original = MemoryPcm(48000, 2, ShortArray(96000) { 8192 })
        val processed = MemoryPcm(48000, 2, ShortArray(96000) { 4096 })
        val mix = PreviewAudioMix(ExportPlan.previewAudio(edit.timeline), mapOf(source.id to original), processed = mapOf(TrackRenderer.audioKey(source.id, chain) to processed))
        val out = FloatArray(2 * 48000)
        mix.render(0, 48000, out)
        assertEquals(0.25f, out[2 * 12000], 1e-4f)
        assertEquals(0.125f, out[2 * 36000], 1e-4f)
    }

    // ---- DeepFilterNet3 DSP (identity network) ----

    private val dsp: ByteArray by lazy { File("../../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json").readBytes() }

    /** A network whose mask passes every band and whose deep filter keeps the target frame. */
    private class IdentityModel : DeepFilterNetModel {
        var calls = 0
        override fun run(featErb: FloatArray, featSpec: FloatArray, frames: Int): DeepFilterNetModel.Output {
            calls++
            assertEquals(frames * 32, featErb.size)
            assertEquals(2 * frames * 96, featSpec.size)
            val coefs = FloatArray(frames * 96 * 5 * 2)
            for (t in 0 until frames) for (f in 0 until 96) coefs[((t * 96 + f) * 5 + 2) * 2] = 1f
            return DeepFilterNetModel.Output(FloatArray(frames * 32) { 1f }, coefs)
        }
        override fun close() {}
    }

    @Test fun fftMatchesDirectTransform() {
        val fft = ComplexFFT(960)
        val re = FloatArray(960) { sin(it * 0.37f) + 0.2f * cos(it * 2.1f) }
        val im = FloatArray(960) { 0.1f * sin(it * 0.05f) }
        val outRe = FloatArray(960); val outIm = FloatArray(960)
        fft.transform(re, im, outRe, outIm, inverse = false)
        for (k in listOf(0, 1, 7, 100, 480, 959)) {
            var sr = 0.0; var si = 0.0
            for (n in 0 until 960) { val a = -2 * PI * k * n / 960; sr += re[n] * cos(a) - im[n] * sin(a); si += re[n] * sin(a) + im[n] * cos(a) }
            assertEquals(sr, outRe[k].toDouble(), 1e-3); assertEquals(si, outIm[k].toDouble(), 1e-3)
        }
        val backRe = FloatArray(960); val backIm = FloatArray(960)
        fft.transform(outRe, outIm, backRe, backIm, inverse = true)
        for (n in 0 until 960) assertEquals(re[n], backRe[n] / 960, 1e-4f)
    }

    @Test fun deepFilterDspReconstructsAndDrains() {
        // Swift `deepFilterTests`: a final partial hop, exact length, and the inverse of the
        // analysis: with an identity network the engine returns its input, sample-aligned.
        val frames = 12347
        val left = FloatArray(frames) { (sin(it * 0.033) * 0.3 + cos(it * 0.117) * 0.13).toFloat() }
        val model = IdentityModel()
        // Small blocks exercise block boundaries, warm-up context and frame release.
        val engine = DeepFilterNetEngine(dsp, model, chunk = 7, warmup = 3)
        engine.prepare()
        fun render(): FloatArray {
            var output = FloatArray(0)
            for (start in 0 until frames step 480) {
                val hop = FloatArray(480)
                left.copyInto(hop, 0, start, minOf(frames, start + 480))
                output += engine.processHop(hop)
            }
            output += engine.flush()
            return output.copyOf(frames)
        }
        val first = render()
        assertTrue(model.calls > 3)
        // Bins above the deep filter pass through the ERB mask; the inverse filterbank sums to one per bin.
        for (i in 960 until frames - 960) assertEquals("sample $i", left[i], first[i], 2e-4f)
        assertEquals(0, engine.flush().size)
        assertTrue(runCatching { engine.processHop(FloatArray(480)) }.isFailure)
        engine.reset()
        assertArrayEquals("Reset clears all state", first, render(), 0f)
        engine.reset()
        assertTrue(runCatching { engine.processHop(FloatArray(480) { Float.NaN }) }.isFailure)
        // A damaged DSP file is refused.
        assertTrue(runCatching { DeepFilterNetEngine(dsp + byteArrayOf(32), model).prepare() }.isFailure)
    }

    @Test fun deepFilterWrapperMixesWithoutShift() {
        // Swift `deepFilterTests`: stereo independence, aligned half mix, exact bypass and the final partial hop.
        DeepFilterVoiceProcessor.resources = object : DeepFilterVoiceProcessor.Resources {
            override fun dsp() = dsp
            override fun model(): DeepFilterNetModel = object : DeepFilterNetModel by IdentityModel() {
                // A network that halves every band: wet = dry / 2 (up to the STFT round trip).
                override fun run(featErb: FloatArray, featSpec: FloatArray, frames: Int): DeepFilterNetModel.Output {
                    val coefs = FloatArray(frames * 96 * 5 * 2)
                    for (t in 0 until frames) for (f in 0 until 96) coefs[((t * 96 + f) * 5 + 2) * 2] = 0.5f
                    return DeepFilterNetModel.Output(FloatArray(frames * 32) { 0.5f }, coefs)
                }
            }
        }
        try {
            val frames = 12347
            val left = FloatArray(frames) { (sin(it * 0.033) * 0.3 + cos(it * 0.117) * 0.13).toFloat() }
            val right = FloatArray(frames) { (sin(it * 0.027) * 0.25).toFloat() }
            val input = save("dfn-input", listOf(left, right))
            val effect = AudioEffect.make(EffectKind.voiceIsolation).copy(model = AudioModel.deepFilter)
            val wet = process(input, "dfn-wet", listOf(effect))
            assertEquals(frames, wet[0].size)
            val half = process(input, "dfn-half", listOf(effect.copy(amount = 0.5)))
            process(input, "dfn-bypass", listOf(effect.copy(amount = 0.0)))
            assertArrayEquals("Zero DeepFilter amount preserves exact source bytes", input.readBytes(), File(folder, "dfn-bypass.wav").readBytes())
            val swapped = process(save("dfn-swapped-input", listOf(right, left)), "dfn-swapped", listOf(effect))
            val silent = process(save("dfn-silent-input", listOf(left, FloatArray(frames))), "dfn-silent", listOf(effect))
            for (channel in 0..1) {
                val dry = if (channel == 0) left else right
                for (i in 0 until frames) {
                    assertEquals("DeepFilter stereo state is independent and repeatable", wet[channel][i], swapped[1 - channel][i], 0f)
                    assertEquals("Dry/wet mixing has no lookahead shift", dry[i] + 0.5f * (wet[channel][i] - dry[i]), half[channel][i], 1e-6f)
                    assertEquals("DeepFilter keeps a silent channel silent", 0f, silent[1][i], 0f)
                }
                for (i in 960 until frames - 960) assertEquals(dry[i] * 0.5f, wet[channel][i], 2e-4f)
            }
        } finally {
            DeepFilterVoiceProcessor.resources = null
        }
    }
}
