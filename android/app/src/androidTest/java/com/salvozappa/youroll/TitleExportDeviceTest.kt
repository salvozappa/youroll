// YouRoll on-device check of exported titles (VideoToolCompositor.kt `TitleOverlay`):
// a title appears only in its source range, in place, with its plate colour
// and translucency blended like the preview (straight alpha).
// Run without uninstalling the app:
//   ./gradlew assembleDebug assembleDebugAndroidTest
//   adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
//   adb shell am instrument -w -e class com.salvozappa.youroll.TitleExportDeviceTest com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TitleExportDeviceTest {
    /** Mean R, G, B over a square of [half] pixels around ([x], [y]). */
    private fun mean(frame: Bitmap, x: Int, y: Int, half: Int = 12): DoubleArray {
        val sum = DoubleArray(3)
        var n = 0
        for (j in y - half until y + half) for (i in x - half until x + half) {
            val p = frame.getPixel(i, j)
            sum[0] += ((p shr 16) and 255).toDouble(); sum[1] += ((p shr 8) and 255).toDouble(); sum[2] += (p and 255).toDouble(); n++
        }
        return DoubleArray(3) { sum[it] / n }
    }

    @Test fun titlesFollowTheirFootageWithPreviewColours(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "TitleExportTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project("Title export")
        val media = SourceMedia(filename = source.name, duration = 18.0)
        project.append(media)
        // A single space: the title is its plate alone, so its colour can be measured exactly.
        val solid = TextOverlay(text = " ", start = 5.5, end = 6.5, x = 0.25, y = 0.3, size = 0.2,
            appearance = TitleDesign(shadow = false, plate = TitlePlate.block, backing = TitleInk.rgb(0xd02020)))
        val translucent = TextOverlay(text = " ", start = 5.5, end = 6.5, x = 0.75, y = 0.3, size = 0.2,
            appearance = TitleDesign(shadow = false, plate = TitlePlate.block, backing = TitleInk.rgb(0xffffff, alpha = 128)))
        val plain = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 2.0), TimelineClip(sourceID = media.id, start = 5.0, end = 7.0)))
        val titled = plain.copy()
        titled.clips = titled.clips.mapIndexed { i, clip -> if (i == 1) clip.copy(texts = listOf(solid, translucent)) else clip }
        project.commit(plain)
        val reference = File(File(folder, "Exports"), "plain.mp4")
        MediaRenderer.render(context, project, folder, reference) { }
        project.commit(titled)
        val destination = File(File(folder, "Exports"), "titled.mp4")
        val receipt = MediaRenderer.render(context, project, folder, destination) { }
        val width = receipt.width
        val height = receipt.height

        fun frames(file: File, seconds: Double): Bitmap {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            retriever.release()
            return frame.copy(Bitmap.Config.ARGB_8888, false)
        }
        val left = (0.25 * width).toInt()
        val right = (0.75 * width).toInt()
        val top = (0.3 * height).toInt()
        // Clip 2 starts at edit 2 s with source 5 s: the titles cover edit 2.5…3.5 s.
        for (time in listOf(1.0, 2.2, 3.0, 3.8)) {
            val withTitle = frames(destination, time)
            val without = frames(reference, time)
            val red = mean(withTitle, left, top)
            val white = mean(withTitle, right, top)
            val base = mean(without, right, top)
            val inside = time == 3.0
            android.util.Log.i("TitleExportDeviceTest", "t=$time red=${red.toList()} white=${white.toList()} under=${base.toList()}")
            if (inside) {
                assertTrue("Opaque plate colour at $time s: ${red.toList()}", abs(red[0] - 0xd0) < 14 && abs(red[1] - 0x20) < 14 && abs(red[2] - 0x20) < 14)
                for (c in 0..2) {
                    val expected = 128.0 / 255 * 255 + (1 - 128.0 / 255) * base[c]
                    assertTrue("50 % white plate blends as straight alpha at $time s: ${white.toList()} over ${base.toList()}", abs(white[c] - expected) < 12)
                }
            } else {
                val plainLeft = mean(without, left, top)
                for (c in 0..2) {
                    assertTrue("No title outside its range at $time s", abs(red[c] - plainLeft[c]) < 10 && abs(white[c] - base[c]) < 10)
                }
            }
        }
        folder.deleteRecursively()
    }

    /** Swift `colors(_:)`: strongly red, green and blue pixels among the opaque ones. */
    private fun colors(image: Bitmap, reference: Bitmap? = null): Triple<Int, Int, Int> {
        var red = 0; var green = 0; var blue = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getPixel(x, y)
            if ((p ushr 24) <= 140) continue
            if (reference != null) {
                val q = reference.getPixel(x, y)
                if (abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255)) < 90) continue
            }
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (r > g + 70 && r > b + 30) red++
            if (g > r + 70 && g > b + 70) green++
            if (b > g + 70 && b > r + 30) blue++
        }
        return Triple(red, green, blue)
    }

    private val design = TitleDesign(face = "archivoblack", shadow = false, outlined = true, border = TitleInk.rgb(0x00ff00), borderWidth = 0.10,
        gradient = TitleGradient.of(0xff0000, 0x0000ff, 0.0))

    /** TitleDesignTests.swift: every bundled family, slant and weight loads its own file, and variable weights change the glyphs. */
    @Test fun bundledFontsLoad() {
        TitleTypeface.install(InstrumentationRegistry.getInstrumentation().targetContext)
        for (family in TitleTypeface.families.drop(1)) {
            for (italic in if (family.slanted == null) listOf(false) else listOf(false, true)) {
                val widths = HashSet<Float>()
                val faces = HashSet<String>()
                for (weight in family.weights) {
                    val font = TitleTypeface.font(TitleDesign(face = family.id, italic = italic, weight = weight), 64.0)
                    assertTrue("An unbundled font was substituted for ${family.name} / ${font.postScriptName}", font.bundled && font.typeface != android.graphics.Typeface.DEFAULT)
                    val paint = android.graphics.Paint().apply { typeface = font.typeface; textSize = 64f; if (font.variation.isNotEmpty()) fontVariationSettings = font.variation }
                    widths += paint.measureText("Handgloves")
                    faces += font.postScriptName + font.variation
                }
                // Static families switch files (Plex Mono is monospaced, so only the file shows it); variable ones change the glyphs.
                assertTrue("${family.name} weights change the font", family.weights.size == 1 || faces.size > 1)
                if (family.id != "ibmplexmono") assertTrue("${family.name} weights change the glyphs", family.weights.size == 1 || widths.size > 1)
            }
        }
    }

    /** TitleDesignTests.swift: gradient ends and outline are drawn; a transparent fill shows only the ring. */
    @Test fun outlineAndGradient() {
        TitleTypeface.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val canvas = CGSize(640.0, 360.0)
        val title = TextOverlay(text = "HORIZON", start = 0.0, end = 1.5, size = 0.18, appearance = design)
        val full = colors(TextRasterizer.image(title, canvas)!!)
        assertTrue("Title is missing a gradient end or its outline: $full", minOf(full.first, full.second, full.third) > 100)
        val hollow = title.copy(appearance = design.copy(gradient = null, fill = TitleInk.rgb(0, alpha = 0)))
        val ring = colors(TextRasterizer.image(hollow, canvas)!!).second
        assertTrue("Transparent fill exposed the interior of the stroke: $ring vs ${full.second}", abs(ring - full.second) < maxOf(100, full.second / 10))
    }

    /** TitleDesignTests.swift: an animated title spanning a cut fades in, holds across the cut and wipes out in the export. */
    @Test fun animatedTitleAcrossACutExports(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "TitleMotionTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project("Animated title across a cut")
        val media = SourceMedia(filename = source.name, duration = 18.0)
        project.append(media)
        val title = TextOverlay(text = "HORIZON", start = 0.0, end = 1.5, size = 0.18, appearance = design,
            motion = TitleMotion(entrance = TitleMove.fade, exit = TitleMove.wipe, inSeconds = 0.75, outSeconds = 0.75))
        val plain = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 1.5), TimelineClip(sourceID = media.id, start = 0.0, end = 1.5)))
        project.commit(plain)
        val reference = File(File(folder, "Exports"), "plain.mp4")
        MediaRenderer.render(context, project, folder, reference) { }
        val titled = plain.copy()
        titled.clips = titled.clips.map { it.copy(texts = listOf(title)) }
        project.commit(titled)
        assertTrue(TitleMotionRanges(project.edit).range(title, 1.5) == 0.0..3.0)
        val destination = File(File(folder, "Exports"), "titled.mp4")
        MediaRenderer.render(context, project, folder, destination) { }
        val withTitle = MediaMetadataRetriever().apply { setDataSource(destination.absolutePath) }
        val without = MediaMetadataRetriever().apply { setDataSource(reference.absolutePath) }
        val n = listOf(0.0, 0.3, 1.45, 1.5, 2.7, 2.96).map { t ->
            val us = (t * 1_000_000).toLong()
            val a = withTitle.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
            val b = without.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
            colors(a, b).let { it.first + it.second + it.third }
        }
        withTitle.release(); without.release()
        android.util.Log.i("TitleExportDeviceTest", "Title frame pixel counts: $n")
        assertTrue("Incorrect entrance, cut boundary or exit in rendered frames: $n",
            n[0] < 50 && n[1] > 100 && n[2] > 1000 && abs(n[2] - n[3]) < n[2] / 8 && n[4] < n[3] && n[5] < n[4] / 4)
        folder.deleteRecursively()
    }
}
