// YouRoll on-device checks of the video effect chain (MetalVideoEffects.kt) in
// exported movies: an effect frame against the expected shader output (a CPU
// twin of the Silent Tint kernel applied to the same movie exported without
// the effect), the Freeze Frame push-in over a hold clip, Undercranking's
// retimed duration, and the shared renderer drawing a frame like the export.
//   adb shell am instrument -w -e class com.salvozappa.youroll.VideoEffectsDeviceTest com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class VideoEffectsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(name: String): Triple<File, Project, SourceMedia> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project(name)
        val media = SourceMedia(filename = source.name, duration = 18.0)
        project.append(media)
        return Triple(folder, project, media)
    }

    private suspend fun export(folder: File, project: Project, name: String): File {
        val destination = File(File(folder, "Exports"), "$name.mp4")
        MediaRenderer.render(context, project, folder, destination) { }
        return destination
    }

    private fun frame(file: File, time: Double): Bitmap {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(Math.round(time * 1_000_000), MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        return frame
    }

    private fun duration(file: File): Double {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        retriever.release()
        return value
    }

    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun enc(c: Double): Double { val v = min(1.0, max(0.0, c)); return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055 }

    /** CPU twin of the Silent Tint kernel (kind 0, process Tint): contrast on perceptual luma, then the linear dye. */
    private fun silentTint(pixel: Int, tint: DoubleArray, contrast: Double): Triple<Double, Double, Double> {
        val r = lin(((pixel shr 16) and 255) / 255.0); val g = lin(((pixel shr 8) and 255) / 255.0); val b = lin((pixel and 255) / 255.0)
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val l = lin(min(1.0, max(0.0, (enc(y) - 0.5) * contrast + 0.5)))
        return Triple(enc(l * lin(tint[0])), enc(l * lin(tint[1])), enc(l * lin(tint[2])))
    }

    @Test fun silentTintExportMatchesTheShader(): Unit = runBlocking {
        val (folder, project, media) = fixture("EffectsTint")
        val clip = TimelineClip(sourceID = media.id, start = 0.0, end = 3.0)
        project.commit(EditState(listOf(clip)))
        val plain = export(folder, project, "plain")
        val tint = VideoEffectSettings(VideoEffectID.silentTint, 0.0, 3.0)
        project.commit(EditState(listOf(clip.copy(videoEffects = listOf(tint)))))
        project.validate()
        val effected = export(folder, project, "tint")
        val a = frame(plain, 1.0)
        val b = frame(effected, 1.0)
        var error = 0.0
        var tinted = 0.0
        var count = 0
        for (y in 4 until a.height step 16) for (x in 4 until a.width step 16) {
            val (er, eg, eb) = silentTint(a.getPixel(x, y), doubleArrayOf(.85098, .643137, .254902), 1.15)
            val p = b.getPixel(x, y)
            error += abs(er * 255 - ((p shr 16) and 255)) + abs(eg * 255 - ((p shr 8) and 255)) + abs(eb * 255 - (p and 255))
            val q = a.getPixel(x, y)
            tinted += abs(((q shr 16) and 255) - ((p shr 16) and 255)) + abs(((q shr 8) and 255) - ((p shr 8) and 255)) + abs((q and 255) - (p and 255))
            count += 3
        }
        val mean = error / count
        android.util.Log.i("VideoEffectsDeviceTest", "Silent Tint: mean error ${"%.2f".format(mean)}, change ${"%.2f".format(tinted / count)}")
        assertTrue("The exported tint follows the shader (mean error $mean)", mean < 6)
        assertTrue("and visibly changes the picture", tinted / count > 10)
        folder.deleteRecursively()
    }

    @Test fun freezeFramePushInAndUndercranking(): Unit = runBlocking {
        val (folder, project, media) = fixture("EffectsTime")
        val (frozen, id) = TimelineEditing.freezing(EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 3.0))), 1.0)
        assertTrue("A hold is inserted", id != null)
        frozen.clips = frozen.clips.map { clip ->
            clip.copy(videoEffects = clip.videoEffects?.map { if (it.id == id) it.setting("push", EffectValue.number(0.15)) else it })
        }
        val crank = VideoEffectSettings(VideoEffectID.undercranking, 1.0, 3.0).setting("speed", EffectValue.number(1.5))
        frozen.clips = frozen.clips.map { if (it.frozenFrame == null && it.start >= 1.0) it.copy(videoEffects = listOf(crank)) else it }
        project.commit(frozen)
        project.validate()
        assertEquals("1 s + 3 s hold + 2 s at 1.5×", 1 + 3 + 2 / 1.5, frozen.duration, 0.001)
        val movie = export(folder, project, "time")
        assertEquals("The export keeps the retimed duration", frozen.duration, duration(movie), 0.12)
        // The push-in zooms into the held picture: its start and end differ; two frames late in the hold match closely.
        fun difference(x: Bitmap, y: Bitmap): Double {
            var total = 0.0; var count = 0
            for (py in 0 until x.height step 12) for (px in 0 until x.width step 12) {
                val p = x.getPixel(px, py); val q = y.getPixel(px, py)
                total += abs(((p shr 8) and 255) - ((q shr 8) and 255)); count += 1
            }
            return total / count
        }
        val early = frame(movie, 1.05); val late = frame(movie, 3.9); val later = frame(movie, 3.95)
        val pushed = difference(early, late)
        android.util.Log.i("VideoEffectsDeviceTest", "Push-in: start/end difference ${"%.2f".format(pushed)}, adjacent ${"%.2f".format(difference(late, later))}")
        assertTrue("The push-in moves the held picture ($pushed)", pushed > 2)
        assertTrue("and stays on one picture", difference(late, later) < pushed)
        folder.deleteRecursively()
    }

    @Test fun effectsApplyInsideTransitionWindows(): Unit = runBlocking {
        val (folder, project, media) = fixture("EffectsTransition")
        val noir = { start: Double, end: Double -> VideoEffectSettings(VideoEffectID.noir, start, end).setting("grain", EffectValue.toggle(false)) }
        val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 2.0, videoEffects = listOf(noir(0.0, 2.0))),
            TimelineClip(sourceID = media.id, start = 4.0, end = 6.0, videoEffects = listOf(noir(4.0, 6.0))))).settingTransition(ClipTransition(duration = 1.0), 1)
        project.commit(edit)
        project.validate()
        val movie = export(folder, project, "transition")
        val window = edit.timeline.transitionWindows().single()
        val middle = (window.interval.start.seconds + window.interval.end.seconds) / 2
        // Noir is monochrome: every pixel of the blended window stays neutral.
        val picture = frame(movie, middle)
        var chroma = 0.0; var count = 0; var level = 0.0
        for (y in 0 until picture.height step 16) for (x in 0 until picture.width step 16) {
            val p = picture.getPixel(x, y)
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            chroma += max(r, max(g, b)) - min(r, min(g, b)); level += g; count += 1
        }
        android.util.Log.i("VideoEffectsDeviceTest", "Transition window at $middle s: mean chroma ${"%.2f".format(chroma / count)}, level ${"%.2f".format(level / count)}")
        assertTrue("Both pictures of the dissolve are drawn through Noir (${chroma / count})", chroma / count < 8)
        assertTrue("and the window shows a picture (level ${level / count})", level / count > 8)
        folder.deleteRecursively()
    }

    /** Every effect YouRoll offers compiles and draws through the shared renderer (GLES 3, like the export and preview). */
    @Test fun everyAvailableEffectRenders() {
        VideoEffectInputs.install(context)
        val egl = TransitionEGL()
        val renderer = MetalEffectRenderer()
        try {
            val width = 320; val height = 180
            // A colour ramp as the SDR input picture.
            val pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4)
            for (y in 0 until height) for (x in 0 until width) {
                pixels.put((x * 255 / width).toByte()); pixels.put((y * 255 / height).toByte()); pixels.put(((x + y) % 256).toByte()); pixels.put(-1)
            }
            pixels.position(0)
            val input = renderer.texture(width, height, android.opengl.GLES30.GL_RGBA8)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, input.id)
            android.opengl.GLES20.glTexSubImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, pixels)
            val output = renderer.texture(width, height, android.opengl.GLES30.GL_RGBA8)
            val identity = videoEffectInputMatrix(null, width, height, width, height)
            for (kind in VideoEffectID.entries.filter { VideoEffectSupport.available(it) }) {
                var effect = VideoEffectSettings(kind, 0.0, 10.0)
                if (kind == VideoEffectID.crashZoom) effect = effect.setting("trackFace", EffectValue.toggle(false))
                if (kind == VideoEffectID.rgbOffset) effect = effect.setting("redX", EffectValue.number(0.3))
                if (kind == VideoEffectID.colorCorrection) effect = effect.setting("exposure", EffectValue.number(1.0))
                val frame = EffectFrame(1.07, 1.07, holdTime = if (kind == VideoEffectID.freezeFrame) 1.0 else null)
                renderer.render(input.id, identity, listOf(effect), frame, output.fbo, width, height)
                val read = java.nio.ByteBuffer.allocateDirect(width * height * 4)
                android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, output.fbo)
                android.opengl.GLES20.glReadPixels(0, 0, width, height, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, read)
                var changed = 0
                for (i in 0 until width * height) {
                    for (c in 0..2) if (abs((read.get(i * 4 + c).toInt() and 255) - (pixels.get(i * 4 + c).toInt() and 255)) > 3) { changed += 1; break }
                }
                assertEquals("No GL error drawing $kind", android.opengl.GLES20.GL_NO_ERROR, android.opengl.GLES20.glGetError())
                android.util.Log.i("VideoEffectsDeviceTest", "$kind changed $changed of ${width * height} pixels")
                // Geometric decorations (push-in, gate weave) move a smooth ramp only slightly.
                assertTrue("$kind changes the picture", changed > 100)
            }
        } finally {
            renderer.close()
            egl.close()
        }
    }
}
