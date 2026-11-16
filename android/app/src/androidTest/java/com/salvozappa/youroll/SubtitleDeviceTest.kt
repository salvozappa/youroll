// YouRoll on-device subtitle checks: the layout and render assertions of
// ../Tests/SubtitleTests.swift that need text layout (android.graphics), and
// an export whose caption pixels equal the preview's frames drawn over the
// uncaptioned export (preview == export).
// Run without uninstalling the app:
//   ./gradlew assembleDebug assembleDebugAndroidTest
//   adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
//   adb shell am instrument -w -e class com.salvozappa.youroll.SubtitleDeviceTest com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.Canvas
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
class SubtitleDeviceTest {
    private fun require(condition: Boolean, message: String) = assertTrue(message, condition)
    private fun CGRect.contains(other: CGRect) = other.minX >= minX - 1e-9 && other.minY >= minY - 1e-9 && other.maxX <= maxX + 1e-9 && other.maxY <= maxY + 1e-9
    private fun CGRect.intersects(other: CGRect) = minX < other.maxX && other.minX < maxX && minY < other.maxY && other.minY < maxY

    /**
     * Placement is constrained by the entire series, including long captions,
     * pasted paragraphs and simultaneous speech, in portrait and landscape.
     */
    @Test fun layoutConstrainsTheWholeSeries() {
        TitleTypeface.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val layoutCues = listOf(
            SubtitleCue("short", newUUID(), emptyList(), "Ciao", 0.0, 1.0),
            SubtitleCue("long", newUUID(), emptyList(), "How are you? I'm fine. This longer subtitle needs more than one line.", 1.0, 2.0),
            SubtitleCue("overlap", newUUID(), emptyList(), "Another speaker talking at the same time.", 1.0, 2.0),
            SubtitleCue("paragraph", newUUID(), emptyList(), "Long corrected sentence with punctuation, accents: città. ".repeat(30), 2.0, 3.0),
        )
        for (canvas in listOf(CGSize(1920.0, 1080.0), CGSize(1080.0, 1920.0), CGSize(1080.0, 1080.0))) {
            for (font in SubtitleFont.entries) {
                require(TitleTypeface.subtitleTypeface(font, 700) != null, "Subtitle font stand-in exists: $font")
                for (style in TextStyle.entries) {
                    var settings = SubtitleSettings(font = font, style = style)
                    val layout = SubtitleLayout(layoutCues, settings, canvas)
                    val short = SubtitleLayout(layoutCues.take(1), settings, canvas)
                    require(layout.containsEveryCharacter, "No text truncated in $font/$style/$canvas")
                    require(layout.maximumSize < short.maximumSize, "Short caption cannot determine series size limit")
                    for (point in listOf(0.0 to 0.0, 1.0 to 1.0, 0.5 to 0.5)) {
                        settings = settings.copy(size = 0.3, x = point.first, y = point.second)
                        val constrained = layout.constrained(settings)
                        require(constrained.valid, "Safe layout settings remain valid even for a pasted paragraph")
                        val box = layout.envelope(constrained)
                        require(CGRect(0.024, 0.024, 0.952, 0.952).contains(box), "Largest caption envelope stays within video: $box")
                        for (time in listOf(0.5, 1.5, 2.5)) {
                            val frames = layout.frames(time, constrained, canvas)
                            require(frames.size == (if (time == 1.5) 2 else 1), "Every active caption is rendered")
                            require(frames.all { CGRect(0.0, 0.0, 1.0, 1.0).contains(it.rect) }, "Every glyph bitmap stays within frame, even at placement limits: ${frames.map { it.rect }}")
                            if (frames.size == 2) require(!frames[0].rect.intersects(frames[1].rect), "Simultaneous captions never overlap")
                        }
                    }
                }
            }
        }
        // An appearance design takes the styled path with the same guarantees.
        val styled = SubtitleSettings(appearance = TitleDesign(face = "sourceserif4", outlined = true), motion = TitleMotion(entrance = TitleMove.drift, exit = TitleMove.scale))
        val layout = SubtitleLayout(layoutCues, styled, CGSize(1920.0, 1080.0))
        require(layout.containsEveryCharacter && CGRect(0.024, 0.024, 0.952, 0.952).contains(layout.envelope(styled.copy(size = 0.3))), "Styled captions fit")
    }

    private fun frame(file: File, seconds: Double): Bitmap {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        return frame.copy(Bitmap.Config.ARGB_8888, true)
    }

    @Test fun exportDrawsThePreviewCaptions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "SubtitleExportTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project("Subtitle render QA")
        project.append(SourceMedia(filename = source.name, duration = 4.0))
        val plain = project.edit.copy()
        plain.clips = plain.clips.map { it.copy(end = 4.0) }
        project.commit(plain)
        val reference = File(File(folder, "Exports"), "plain.mp4")
        MediaRenderer.render(context, project, folder, reference) { }
        val state = project.edit.copy()
        state.timeline.installSubtitles(SubtitleTiming.estimate("A visible subtitle", listOf(0.2..3.5)), 0.0..4.0, "English")
        state.timeline.subtitles = state.timeline.subtitles!!.copy(font = SubtitleFont.classic, x = 0.3, y = 0.2, size = 0.095)
        project.commit(state)
        require(state.timeline.subtitleCues.isNotEmpty(), "Captions are attached to the take's sound")
        val destination = File(File(folder, "Exports"), "captions.mp4")
        val receipt = MediaRenderer.render(context, project, folder, destination) { }
        val retriever = MediaMetadataRetriever().apply { setDataSource(destination.absolutePath) }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
        retriever.release()
        require(abs(duration - 4) < 0.08, "Subtitles do not alter exported duration: $duration")
        val canvas = CGSize(receipt.width.toDouble(), receipt.height.toDouble())
        // The editor preview draws exactly these frames over the picture (EditorController.subtitles → PlayerSurface).
        val track = SubtitleRenderTrack(state.timeline.subtitleCues, state.timeline.subtitles!!)
        for (time in listOf(1.0, 3.0)) {
            val exported = frame(destination, time)
            val expected = frame(reference, time)
            val frames = track.frames(time, canvas)
            require(frames.isNotEmpty(), "A caption is active at $time s")
            VideoOverlayRenderer.draw(Canvas(expected), frames, canvas)
            var changed = 0
            var differing = 0
            var compared = 0
            for (y in 0 until receipt.height step 2) for (x in 0 until receipt.width step 2) {
                val a = exported.getPixel(x, y)
                val b = expected.getPixel(x, y)
                compared++
                val d = maxOf(abs(((a shr 16) and 255) - ((b shr 16) and 255)), abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))
                if (d > 48) differing++
            }
            val withoutCaption = frame(reference, time)
            for (y in 0 until receipt.height step 2) for (x in 0 until receipt.width step 2) {
                val a = exported.getPixel(x, y)
                val b = withoutCaption.getPixel(x, y)
                if (abs(((a shr 16) and 255) - ((b shr 16) and 255)) > 70 || abs(((a shr 8) and 255) - ((b shr 8) and 255)) > 70) changed++
            }
            android.util.Log.i("SubtitleDeviceTest", "t=$time caption pixels=$changed differing from the preview composite=$differing of $compared")
            require(changed > 200, "Export contains visible caption pixels, not just a decodable video")
            require(differing < changed / 20 + 20, "Exported captions match the preview frames ($differing of $changed differ)")
            val box = frames[0].rect
            // A wide caption keeps its envelope inside the picture: x 0.3 is pushed inwards, y 0.2 is kept.
            require(abs(box.midY - 0.2) < 0.01 && box.minX >= 0.024 && box.maxX <= 0.976, "Caption centred on y 0.2, inside the picture: $box")
        }
    }
}
