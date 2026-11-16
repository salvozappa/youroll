// Copyright (c) 2026 YouRoll contributors. MIT License.
// Calls into every native library once, so a JNI name that no longer matches its Kotlin
// class (UnsatisfiedLinkError at first call) fails here instead of in the app.
package com.salvozappa.youroll

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class NativeBindingsDeviceTest {
    @Test fun denoiser() {
        assertTrue("youroll_audio did not load", Denoiser.available)
        Denoiser.create().use { denoiser ->
            val input = FloatArray(480) { Random(1).nextFloat() * 2000f - 1000f }
            val output = FloatArray(480)
            denoiser.process(input, 0, output)
            val probability = denoiser.speechProbability(input, 0, 1)
            assertTrue(probability in 0f..1f)
        }
    }

    @Test fun stabilizationMotion() {
        val width = 64; val height = 64
        val random = Random(2)
        val texture = ByteArray((width + 8) * height) { random.nextInt(256).toByte() }
        fun frame(shift: Int) = ByteBuffer.allocateDirect(width * height).also { buffer ->
            for (y in 0 until height) for (x in 0 until width) buffer.put(texture[y * (width + 8) + x + shift])
            buffer.rewind()
        }
        val motion = StabilizationMotion.estimate(frame(0), frame(3), width, height)
        assertTrue("motion ${motion}", motion.x.isFinite() && motion.y.isFinite())
        val plane = ByteBuffer.allocateDirect(128 * 128).also { repeat(128 * 128) { i -> it.put((i % 251).toByte()) }; it.rewind() }
        val small = ByteBuffer.allocateDirect(32 * 32)
        assertTrue(StabilizationMotion.downsample(plane, 128, 1, 0, 0, 128, 128, small, 32, 32))
    }

    /** Needs the Qwen3-ASR weights and a 16 kHz mono float speech clip in the app's private
     *  files folder (`files/qwen/`, with `speech.f32`; copy them in with `run-as`); skipped otherwise. */
    @Test fun qwenAsr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = File(context.cacheDir, "no-model").apply { mkdirs() }
        assertEquals(0L, QwenASR.nativeLoad(missing.path, 1))
        val folder = File(context.filesDir, "qwen")
        assumeTrue("Qwen3-ASR weights not pushed to $folder", File(folder, "model.safetensors").exists())
        val handle = QwenASR.nativeLoad(folder.path, QwenASR.threads)
        assertNotEquals(0L, handle)
        try {
            assertTrue(QwenASR.nativeSetLanguage(handle, "English"))
            val bytes = File(folder, "speech.f32").readBytes()
            val samples = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            val text = QwenASR.nativeTranscribe(handle, samples)?.toString(Charsets.UTF_8) ?: ""
            android.util.Log.i("YouRollNative", "Transcribed: $text")
            assertTrue("transcript: $text", text.contains("roll", ignoreCase = true))
        } finally {
            QwenASR.nativeFree(handle)
        }
    }
}
