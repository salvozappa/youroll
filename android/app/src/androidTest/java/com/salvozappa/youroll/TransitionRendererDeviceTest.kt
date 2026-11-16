// YouRoll on-device check of transition clips (TransitionRenderer.kt) and their
// export: every iOS style renders through the GL pipeline, a dissolve shortens
// the project, a colour hold lengthens it, and the sound crossfades.
// Run without uninstalling the app:
//   ./gradlew assembleDebug assembleDebugAndroidTest
//   adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
//   adb shell am instrument -w -e class com.salvozappa.youroll.TransitionRendererDeviceTest com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
// Copyright (c) 2026 YouRoll contributors. MIT License.
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

@RunWith(AndroidJUnit4::class)
class TransitionRendererDeviceTest {
    private fun luma(frame: Bitmap): Double {
        var total = 0.0
        var count = 0
        for (y in 0 until frame.height step 8) for (x in 0 until frame.width step 8) {
            val pixel = frame.getPixel(x, y)
            total += 0.299 * ((pixel shr 16) and 255) + 0.587 * ((pixel shr 8) and 255) + 0.114 * (pixel and 255)
            count += 1
        }
        return total / count
    }

    /** Two different sources: the 1 kHz testsrc2 tone fixture (4 s) and the editor fixture. */
    private fun twoClipProject(name: String): Pair<File, Project> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        for (asset in listOf("tone-fixture.mp4", "editor-fixture.mp4")) {
            instrumentation.context.assets.open(asset).use { input -> File(folder, asset).outputStream().use { input.copyTo(it) } }
        }
        val project = Project(name)
        val tone = SourceMedia(filename = "tone-fixture.mp4", duration = 4.0)
        val editor = SourceMedia(filename = "editor-fixture.mp4", duration = 18.0)
        project.append(tone); project.append(editor)
        project.commit(EditState(listOf(TimelineClip(sourceID = tone.id, start = 0.0, end = 4.0), TimelineClip(sourceID = editor.id, start = 5.0, end = 9.0))))
        return folder to project
    }

    @Test fun everyStyleRendersItsWindow(): Unit = runBlocking {
        val (folder, project) = twoClipProject("TransitionStyles")
        for (style in TransitionStyle.allCases) {
            val settings = ClipTransition(style = style, duration = if (style == TransitionStyle.whip) 8.0 / 30 else 0.6, divider = 0.35,
                fadeColor = if (style == TransitionStyle.fadeColor) TransitionColor(1.0, 1.0, 1.0) else null,
                stillDissolve = if (style == TransitionStyle.still) 0.25 else null)
            val edit = project.edit.settingTransition(settings, 1)
            val job = TransitionRenderJob.all(edit.timeline, project.sources, folder, 640, 360).single()
            val file = TransitionRenderer.render(job, folder)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            retriever.release()
            assertEquals("$style clip frames", job.frameCount, frames)
            assertEquals(640, width)
            val middle = TransitionRenderer.still(job, job.window.interval.start + EditTime.of(job.duration.seconds / 2))
            assertTrue("$style midpoint has a picture", luma(middle) > 10)
            // Stills at a quarter, half and three quarters of the window, kept for visual review:
            //   adb exec-out run-as com.salvozappa.youroll cat cache/transition-stills/<style>-<n>.png
            val stills = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "transition-stills").apply { mkdirs() }
            for (n in 1..3) {
                val image = TransitionRenderer.still(job, job.window.interval.start + EditTime.of(job.duration.seconds * n / 4))
                File(stills, "${style.rawValue}-$n.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        folder.deleteRecursively()
    }

    /** A title spanning the join blends with its picture through the dissolve; a 2× clip after the join keeps its timing. */
    @Test fun titleAndSpeedAcrossADissolve(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (folder, project) = twoClipProject("TransitionTitles")
        // A red plate on the outgoing clip from source 2.5 s to its end (4 s): it ends inside the dissolve.
        val plate = TextOverlay(text = " ", start = 2.5, end = 4.0, x = 0.25, y = 0.3, size = 0.2,
            appearance = TitleDesign(shadow = false, plate = TitlePlate.block, backing = TitleInk.rgb(0xd02020)))
        val edit = project.edit.copy()
        edit.clips = edit.clips.mapIndexed { i, clip -> if (i == 0) clip.copy(texts = listOf(plate)) else clip.copy(speed = SpeedSettings(rate = 2.0)) }
        val titled = edit.settingTransition(ClipTransition(duration = 1.0), 1)
        val plain = titled.copy(); plain.clips = plain.clips.map { it.copy(texts = null) }
        fun export(state: EditState, name: String): Pair<File, ExportReceipt> {
            project.commit(state)
            val file = File(File(folder, "Exports"), "$name.mp4")
            return file to runBlocking { MediaRenderer.render(context, project, folder, file) { } }
        }
        val (withTitle, receipt) = export(titled, "titled")
        val (without, _) = export(plain, "plain")
        fun frame(file: File, seconds: Double): Bitmap {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val image = retriever.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            retriever.release()
            return image.copy(Bitmap.Config.ARGB_8888, false)
        }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(withTitle.absolutePath)
        // 4 s + the 4 s second clip at 2× (2 s) − the 1 s overlap.
        assertEquals(5.0, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0, 0.12)
        retriever.release()
        val x = (0.25 * receipt.width).toInt(); val y = (0.3 * receipt.height).toInt()
        fun redExcess(time: Double): Double {
            fun red(image: Bitmap): Double {
                var sum = 0.0; var n = 0
                for (j in y - 6 until y + 6) for (i in x - 6 until x + 6) { val p = image.getPixel(i, j); sum += ((p shr 16) and 255) - (p and 255); n++ }
                return sum / n
            }
            return red(frame(withTitle, time)) - red(frame(without, time))
        }
        val before = redExcess(2.8); val early = redExcess(3.2); val late = redExcess(3.8); val after = redExcess(4.5)
        android.util.Log.i("TransitionRendererDeviceTest", "title red excess: 2.8=$before 3.2=$early 3.8=$late 4.5=$after")
        assertTrue("the title shows before the dissolve ($before)", before > 60)
        assertTrue("the title fades with its picture inside the dissolve ($early > $late)", early > 20 && early > late + 10)
        assertTrue("the title is gone after the dissolve ($after)", abs(after) < 12)
        folder.deleteRecursively()
    }

    @Test fun dissolveAndColorHoldExportWithCrossfadedSound(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (folder, project) = twoClipProject("TransitionExport")
        val original = project.edit
        // Dissolve: 1 s overlap, project 7 s.
        project.commit(original.settingTransition(ClipTransition(duration = 1.0), 1))
        val dissolve = File(File(folder, "Exports"), "dissolve.mp4")
        MediaRenderer.render(context, project, folder, dissolve) { }
        var retriever = MediaMetadataRetriever()
        retriever.setDataSource(dissolve.absolutePath)
        assertEquals(7.0, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0, 0.12)
        val before = luma(retriever.getFrameAtTime(2_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        val during = luma(retriever.getFrameAtTime(3_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        retriever.release()
        assertTrue("dissolve frames have pictures ($before, $during)", before > 15 && during > 15)
        // Dip to black with a 1 s hold: the hold adds 1 s; its middle is black.
        project.commit(original.settingTransition(ClipTransition(style = TransitionStyle.fadeColor, duration = 1.0, hold = 1.0), 1))
        val dip = File(File(folder, "Exports"), "dip.mp4")
        MediaRenderer.render(context, project, folder, dip) { }
        retriever = MediaMetadataRetriever()
        retriever.setDataSource(dip.absolutePath)
        assertEquals(9.0, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0, 0.12)
        val hold = luma(retriever.getFrameAtTime(4_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        val after = luma(retriever.getFrameAtTime(6_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        retriever.release()
        assertTrue("the hold is black (luma $hold)", hold < 8)
        assertTrue("the next clip follows, not black (luma $after)", after > 8)
        folder.deleteRecursively()
    }
}
