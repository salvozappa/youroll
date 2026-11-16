// YouRoll on-device check of colour grading in the export (ColorGradeEffect,
// VideoToolCompositor.kt): the GPU grade equals its CPU twin (`ColorLUT.apply`)
// on exported pixels, bars stay black (the grade runs before the framing), and
// the grade reaches placed pictures and transition windows (the order of
// Swift `VideoToolInstruction.preparedImage` and `renderTracks`).
// Run without uninstalling the app:
//   adb shell am instrument -w -e class com.salvozappa.youroll.ColorExportDeviceTest com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
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
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class ColorExportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String): Pair<File, SourceMedia> {
        val folder = File(context.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        return folder to SourceMedia(filename = source.name, duration = 18.0)
    }

    private suspend fun export(project: Project, folder: File, name: String, seconds: Double): Bitmap {
        val destination = File(File(folder, "Exports"), "$name.mp4")
        MediaRenderer.render(context, project, folder, destination) { }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(destination.absolutePath)
        val frame = retriever.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        return frame
    }

    private fun channels(p: Int) = intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
    private fun difference(a: Int, b: Int) = channels(a).zip(channels(b)).sumOf { (x, y) -> abs(x - y) } / 3.0

    /** The CPU grade of an exported (ungraded) pixel. */
    private fun graded(settings: ColorSettings, pixel: Int): Int {
        val out = ColorLUT.apply(settings, channels(pixel).map { it / 255.0 }.toDoubleArray())
        return android.graphics.Color.rgb(Math.round(out[0] * 255).toInt(), Math.round(out[1] * 255).toInt(), Math.round(out[2] * 255).toInt())
    }

    @Test fun gradedExportMatchesTheCpuGradeAndKeepsBarsBlack(): Unit = runBlocking {
        val (folder, media) = fixture("ColorExportTest")
        val grade = ColorSettings(look = ColorLook.mutedFilm, amount = 0.8, exposure = 0.4, warmth = 0.3, highlights = -0.4)
        fun project(color: ColorSettings?): Project {
            val project = Project("Color export")
            project.append(media)
            // A 16:9 canvas over the portrait fixture: the picture is fitted with bars.
            val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 3.0, color = color)), outputSize = CGSize(1280.0, 720.0))
            edit.timeline.audio = emptyList()
            project.commit(edit)
            return project
        }
        val plain = export(project(null), folder, "plain", 1.5)
        val color = export(project(grade), folder, "graded", 1.5)
        var error = 0.0; var count = 0; var bars = 0.0; var barCount = 0; var changed = 0.0
        for (y in 4 until color.height - 4 step 6) for (x in 4 until color.width - 4 step 6) {
            val p = plain.getPixel(x, y)
            val luma = channels(p).sum()
            if (x < 40 || x > color.width - 40) {
                // Outside the fitted picture (bars): black in both.
                if (luma < 30) { bars += channels(color.getPixel(x, y)).max().toDouble(); barCount += 1 }
                continue
            }
            if (luma < 30) continue
            error += difference(color.getPixel(x, y), graded(grade, p)); count += 1
            changed += difference(color.getPixel(x, y), p)
        }
        android.util.Log.i("ColorExportTest", "grade error ${error / count}, change ${changed / count}, bar level ${bars / max(1, barCount)} over $barCount samples")
        assertTrue("the picture is graded (change ${changed / count})", changed / count > 6)
        assertTrue("the GPU grade equals the CPU grade (error ${error / count})", error / count < 5)
        assertTrue("bars stay black (level ${bars / max(1, barCount)})", barCount > 20 && bars / barCount < 10)
    }

    /** Swift `renderTracks`: each placed picture carries its own grade. */
    @Test fun placedPictureCarriesItsGrade(): Unit = runBlocking {
        val (folder, media) = fixture("ColorPlacementTest")
        fun project(color: Boolean): Project {
            val project = Project("Placed grade")
            project.append(media)
            val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 4.0),
                TimelineClip(sourceID = media.id, start = 10.0, end = 12.0, color = if (color) ColorSettings(look = ColorLook.mono) else null)))
            val overlay = edit.timeline.chronologicalVideo[1]
            edit.timeline.unlink(edit.timeline.entireSelection())
            edit.timeline.move(TimelineSelection(video = setOf(overlay.id)), EditTime.of(-3.0))
            edit.timeline.audio = emptyList(); edit.timeline.links = emptyList()
            val ids = edit.timeline.compile().first { it.video.size == 2 }.video
            edit.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(),
                videos = mapOf(ids[0] to VideoPlacement(), ids[1] to VideoPlacement.layout(3, 1, 2).copy(order = 1))))
            project.commit(edit)
            return project
        }
        val plain = export(project(false), folder, "plain", 2.0)
        val mono = export(project(true), folder, "mono", 2.0)
        val clip = TrackRenderer.placed(VideoPlacement.layout(3, 1, 2), CGSize(mono.width.toDouble(), mono.height.toDouble())).clip
        var chroma = 0.0; var inside = 0; var outside = 0.0; var outsideCount = 0; var plainChroma = 0.0
        for (y in 4 until mono.height - 4 step 6) for (x in 4 until mono.width - 4 step 6) {
            if (x > clip.minX + 6 && x < clip.maxX - 6 && y > clip.minY + 6 && y < clip.maxY - 6) {
                val c = channels(mono.getPixel(x, y)); chroma += c.max() - c.min()
                val q = channels(plain.getPixel(x, y)); plainChroma += q.max() - q.min()
                inside += 1
            } else if (x < clip.minX - 6 || x > clip.maxX + 6 || y < clip.minY - 6 || y > clip.maxY + 6) {
                outside += difference(mono.getPixel(x, y), plain.getPixel(x, y)); outsideCount += 1
            }
        }
        android.util.Log.i("ColorExportTest", "placed chroma ${chroma / inside} (plain ${plainChroma / inside}), outside error ${outside / outsideCount}")
        assertTrue("the placed picture is Mono (chroma ${chroma / inside})", inside > 50 && chroma / inside < 6 && plainChroma / inside > chroma / inside + 3)
        assertTrue("the picture behind keeps its colours (error ${outside / outsideCount})", outside / outsideCount < 6)
    }

    /** Swift `TransitionVideoInstruction`: both pictures are graded before the blend. */
    @Test fun transitionWindowBlendsGradedPictures(): Unit = runBlocking {
        val (folder, media) = fixture("ColorTransitionTest")
        fun project(color: Boolean): Project {
            val project = Project("Graded dissolve")
            project.append(media)
            val grade = if (color) ColorSettings(look = ColorLook.mono) else null
            var edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 3.0, color = grade),
                TimelineClip(sourceID = media.id, start = 6.0, end = 9.0, color = grade?.copy(id = newUUID()))))
            edit = edit.settingTransition(ClipTransition(duration = 1.0), 1)
            project.commit(edit)
            return project
        }
        val plainProject = project(false)
        val window = plainProject.edit.timeline.transitionWindows().single()
        val middle = (window.interval.start.seconds + window.interval.end.seconds) / 2
        val plain = export(plainProject, folder, "plain", middle)
        val mono = export(project(true), folder, "mono", middle)
        var chroma = 0.0; var plainChroma = 0.0; var count = 0
        for (y in 4 until mono.height - 4 step 6) for (x in 4 until mono.width - 4 step 6) {
            val c = channels(mono.getPixel(x, y)); chroma += c.max() - c.min()
            val q = channels(plain.getPixel(x, y)); plainChroma += q.max() - q.min()
            count += 1
        }
        android.util.Log.i("ColorExportTest", "dissolve chroma ${chroma / count}, plain ${plainChroma / count}")
        assertTrue("the transition window shows graded pictures (chroma ${chroma / count} vs ${plainChroma / count})",
            chroma / count < 6 && plainChroma / count > chroma / count + 3)
    }
}
