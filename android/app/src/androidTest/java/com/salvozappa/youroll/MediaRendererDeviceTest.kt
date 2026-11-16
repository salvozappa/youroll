// YouRoll on-device check of the Transformer export (MediaRenderer.kt): black
// gaps, overlapping sound lanes, timing, publishing, placed overlaps, Mirror and image overlays, censors and added audio.
// Run without uninstalling the app:
//   ./gradlew assembleDebug assembleDebugAndroidTest
//   adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
//   adb shell am instrument -w -e class com.salvozappa.youroll.MediaRendererDeviceTest com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
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
class MediaRendererDeviceTest {
    private fun luma(frame: Bitmap): Double {
        var total = 0.0
        val step = 8
        var count = 0
        for (y in 0 until frame.height step step) for (x in 0 until frame.width step step) {
            val pixel = frame.getPixel(x, y)
            total += 0.299 * ((pixel shr 16) and 255) + 0.587 * ((pixel shr 8) and 255) + 0.114 * (pixel and 255)
            count += 1
        }
        return total / count
    }

    @Test fun gapsRenderBlackAndOverlappingSoundIsMixed(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "RendererTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val before = ProjectStore.fingerprint(source)

        val project = Project("Device export")
        val media = SourceMedia(filename = source.name, duration = 18.0)
        project.append(media)
        val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 2.0), TimelineClip(sourceID = media.id, start = 5.0, end = 7.0)))
        val second = edit.timeline.chronologicalVideo[1]
        edit.timeline.move(edit.timeline.selection(second.id), EditTime.of(1.0))          // black gap 2…3 s
        edit.timeline.unlink(edit.timeline.entireSelection())
        val secondSound = edit.timeline.audio.first { it.start == EditTime.of(3.0) }
        edit.timeline.move(TimelineSelection(audio = setOf(secondSound.id)), EditTime.of(-1.5)) // sound overlaps 1.5…2 s
        project.commit(edit)
        val plan = ExportPlan.make(project.edit)
        assertEquals(2, plan.audio.size)

        val destination = File(File(folder, "Exports"), "first.mp4")
        val steps = mutableListOf<ExportProgress.Step>()
        val receipt = MediaRenderer.render(context, project, folder, destination) { steps += it.step }
        assertTrue(destination.exists() && File(folder, "Exports/first.json").exists())
        assertEquals(ExportProgress.Step.complete, steps.last())
        assertEquals(720, receipt.width); assertEquals(1280, receipt.height)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(destination.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        assertEquals(5.0, duration, 0.12)
        val picture = luma(retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        val gap = luma(retriever.getFrameAtTime(2_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        retriever.release()
        assertTrue("the gap is black (luma $gap)", gap < 8)
        assertTrue("the clip is not black (luma $picture)", picture > 15)

        val extractor = MediaExtractor()
        extractor.setDataSource(destination.absolutePath)
        val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        extractor.release()
        val audio = formats.first { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
        val video = formats.first { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
        assertEquals(48000, audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(2, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertTrue(video.containsKey(MediaFormat.KEY_WIDTH))

        // A second export is a new file; the first and the source are untouched.
        val firstBytes = ProjectStore.fingerprint(destination)
        val again = File(File(folder, "Exports"), "second.mp4")
        MediaRenderer.render(context, project, folder, again) { }
        assertTrue(again.exists())
        assertEquals(firstBytes, ProjectStore.fingerprint(destination))
        assertEquals(before, ProjectStore.fingerprint(source))
        assertEquals(2, ExportLibrary.receipts(folder).size)
        folder.deleteRecursively()
    }

    /** The 1 kHz tone fixture (640×360, 30 fps, 4 s) in a fresh folder. */
    private fun toneProject(name: String): Triple<File, Project, SourceMedia> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "tone.mp4")
        instrumentation.context.assets.open("tone-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project(name)
        val media = SourceMedia(filename = source.name, duration = 4.0)
        project.append(media)
        return Triple(folder, project, media)
    }

    /** Zero crossings per second of the export's first channel over [from, from + 1) s. */
    private fun frequency(file: File, from: Double): Double {
        var previous = 0f
        var crossings = 0
        var frames = 0
        PcmDecoder.decode(file, start = from, end = from + 1.0) { chunk ->
            for (frame in 0 until chunk.frames) {
                val time = chunk.origin + frame.toDouble() / chunk.sampleRate
                if (time < from || time >= from + 1.0) continue
                val value = chunk.samples[frame * chunk.channels]
                if (previous <= 0f && value > 0f) crossings += 1
                previous = value; frames += 1
            }
        }
        return crossings * 48_000.0 / frames
    }

    // Swift VideoToolsTests: 0.5× and 2× exports last 4 / rate seconds and keep the 1 kHz pitch.
    @Test fun speedExportKeepsDurationAndPitch(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (rate in listOf(0.5, 2.0)) {
            val (folder, project, media) = toneProject("RendererSpeed$rate")
            project.commit(EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 4.0, speed = SpeedSettings(rate = rate)))))
            val destination = File(File(folder, "Exports"), "speed.mp4")
            MediaRenderer.render(context, project, folder, destination) { }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destination.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
            retriever.release()
            assertEquals("Speed export duration matches timeline ($rate×)", 4 / rate, duration, 0.08)
            val pitch = frequency(destination, 0.5)
            assertEquals("Pitch stays at 1 kHz for $rate× export, measured $pitch", 1000.0, pitch, 15.0)
            folder.deleteRecursively()
        }
    }

    /** Amplitude of [hz] in the export's first channel over [from, to) s (one-bin DFT). */
    private fun component(file: File, from: Double, to: Double, hz: Double): Double {
        var real = 0.0; var imaginary = 0.0; var count = 0
        PcmDecoder.decode(file, start = from, end = to) { chunk ->
            for (frame in 0 until chunk.frames) {
                val time = chunk.origin + frame.toDouble() / chunk.sampleRate
                if (time < from || time >= to) continue
                val value = chunk.samples[frame * chunk.channels]
                real += value * Math.cos(2 * Math.PI * hz * time); imaginary += value * Math.sin(2 * Math.PI * hz * time); count += 1
            }
        }
        return 2 * Math.hypot(real, imaginary) / count
    }

    // Swift `audioImportCensorTests` (encoded export): a 440 Hz censor replaces the speech, through a pause cut,
    // and an added audio layer plays after the picture ends.
    @Test fun censorAndAddedAudioExport(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (folder, project, media) = toneProject("RendererCensor")
        val music = File(folder, "music.mp4")
        File(folder, media.filename).copyTo(music)
        val layer = SourceMedia(filename = music.name, duration = 4.0, audioOnly = true, originalName = "music.m4a")
        project.sources = project.sources + layer
        val edit = project.edit.copy()
        val speech = edit.timeline.audio.single().id
        val settings = AudioCensorSettings(frequency = 440.0)
        edit.timeline.censor(settings, TimelineSelection(audio = setOf(speech), range = EditInterval.of(1.0, 2.0)))
        edit.clips = edit.clips.map { it.copy(pauses = PauseSettings(cuts = listOf(SourceInterval(0.25, 0.75)))) }
        edit.timeline.insertAudio(layer, EditTime.of(3.0))
        project.commit(edit)
        project.validate()
        val destination = File(File(folder, "Exports"), "censor.mp4")
        MediaRenderer.render(context, project, folder, destination) { }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(destination.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        retriever.release()
        assertEquals("The added layer lasts past the picture", 7.0, duration, 0.08)
        val speechLevel = component(destination, 2.0, 3.0, 1000.0)
        assertTrue("Speech outside the censor is kept ($speechLevel)", speechLevel > 0.05)
        val beep = component(destination, 0.7, 1.3, 440.0)
        val leak = component(destination, 0.7, 1.3, 1000.0)
        assertEquals("The export carries the -18 dBFS beep where the censor moved with the speech", settings.amplitude, beep, 0.01)
        assertTrue("No censored speech leaks ($leak)", leak < 0.002)
        assertTrue("No beep outside the censor", component(destination, 2.0, 3.0, 440.0) < 0.002)
        val layerLevel = component(destination, 4.0, 6.5, 1000.0)
        assertTrue("The added layer plays after the picture ($layerLevel)", layerLevel > 0.05)
        folder.deleteRecursively()
    }

    @Test fun freezeFrameExportHoldsOnePicture(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (folder, project, media) = toneProject("RendererHold")
        val effect = VideoEffectSettings(VideoEffectID.freezeFrame, 1.0, 1.0 + TimelineEditing.frame).setting("push", EffectValue.number(0.0))
        val hold = TimelineClip(sourceID = media.id, start = 1.0, end = 1.0 + TimelineEditing.frame,
            frozenFrame = FrozenFrameSegment(sourceTime = 1.0, duration = 2.0), videoEffects = listOf(effect))
        project.commit(EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 1.0), hold, TimelineClip(sourceID = media.id, start = 1.0, end = 2.0))))
        project.validate()
        val destination = File(File(folder, "Exports"), "hold.mp4")
        MediaRenderer.render(context, project, folder, destination) { }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(destination.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        val a = retriever.getFrameAtTime(1_300_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        val b = retriever.getFrameAtTime(2_700_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        assertEquals("1 s + 2 s hold + 1 s", 4.0, duration, 0.12)
        // testsrc2 moves every frame: an identical picture across the hold means it is held.
        var differing = 0
        for (y in 0 until a.height step 8) for (x in 0 until a.width step 8) {
            if (Math.abs((a.getPixel(x, y) and 255) - (b.getPixel(x, y) and 255)) > 24) differing += 1
        }
        assertTrue("the hold shows one picture ($differing differing samples)", differing < 40)
        folder.deleteRecursively()
    }

    @Test fun aLeadingGapStartsWithBlack(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "RendererLeadTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project("Leading gap")
        val media = SourceMedia(filename = source.name, duration = 18.0)
        project.append(media)
        val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 2.0)))
        edit.timeline.move(edit.timeline.entireSelection(), EditTime.of(1.0))
        project.commit(edit)
        val destination = File(File(folder, "Exports"), "lead.mp4")
        MediaRenderer.render(context, project, folder, destination) { }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(destination.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        val gap = luma(retriever.getFrameAtTime(400_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        val picture = luma(retriever.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!)
        retriever.release()
        assertEquals(3.0, duration, 0.12)
        assertTrue("the leading gap is black (luma $gap)", gap < 8)
        assertTrue("the clip follows (luma $picture)", picture > 15)
        folder.deleteRecursively()
    }

    /**
     * Placement: a cropped picture-in-picture over a full-canvas picture. The
     * placed export must equal the overlay's own export moved by
     * `TrackRenderer.placed` inside the visible window, and the base picture's
     * own export everywhere else.
     */
    @Test fun placedOverlapComposesPictureInPicture(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "PlacementTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val media = SourceMedia(filename = source.name, duration = 18.0)
        fun project(placed: Boolean?): Project {
            val project = Project("Placement export")
            project.append(media)
            // Base 0…4 s of the source; overlay 10…12 s of the source over 1…3 s.
            val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 4.0), TimelineClip(sourceID = media.id, start = 10.0, end = 12.0)))
            val overlay = edit.timeline.chronologicalVideo[1]
            edit.timeline.unlink(edit.timeline.entireSelection())
            edit.timeline.move(TimelineSelection(video = setOf(overlay.id)), EditTime.of(-3.0))
            edit.timeline.audio = emptyList()
            edit.timeline.links = emptyList()
            when (placed) {
                null -> edit.timeline.video = edit.timeline.video.filter { it.id != overlay.id } // the base alone
                false -> {}                                                                       // no placement: the overlay covers
                true -> {
                    val ids = edit.timeline.compile().first { it.video.size == 2 }.video
                    val pip = VideoPlacement.layout(3, 1, 2).crop(PlacementCropEdge.left, 0.7, CGSize(720.0, 1280.0))
                    edit.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(), videos = mapOf(ids[0] to VideoPlacement(), ids[1] to pip.copy(order = 1))))
                }
            }
            project.commit(edit)
            return project
        }
        suspend fun frame(placed: Boolean?, name: String): Bitmap {
            val destination = File(File(folder, "Exports"), "$name.mp4")
            MediaRenderer.render(context, project(placed), folder, destination) { }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destination.absolutePath)
            val frame = retriever.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            retriever.release()
            return frame
        }
        val placedProject = project(true)
        assertEquals(1, ExportPlan.make(placedProject.edit).layers.size)
        val base = frame(null, "base")
        val over = frame(false, "over")
        val placed = frame(true, "placed")
        val canvas = CGSize(placed.width.toDouble(), placed.height.toDouble())
        val settings = placedProject.edit.timeline.placements[0].videos.values.first { it.width < 1 }
        val geometry = TrackRenderer.placed(settings, canvas)
        val inverse = geometry.transform.inverted()
        fun difference(a: Int, b: Int) = (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))) / 3.0
        var inside = 0.0; var insideCount = 0
        var outside = 0.0; var outsideCount = 0
        for (y in 4 until placed.height - 4 step 6) for (x in 4 until placed.width - 4 step 6) {
            val clip = geometry.clip
            val margin = 4.0
            if (x > clip.minX + margin && x < clip.maxX - margin && y > clip.minY + margin && y < clip.maxY - margin) {
                val from = CGPoint(x.toDouble(), y.toDouble()).applying(inverse)
                inside += difference(placed.getPixel(x, y), over.getPixel(from.x.toInt().coerceIn(0, over.width - 1), from.y.toInt().coerceIn(0, over.height - 1)))
                insideCount += 1
            } else if (x < clip.minX - margin || x > clip.maxX + margin || y < clip.minY - margin || y > clip.maxY + margin) {
                outside += difference(placed.getPixel(x, y), base.getPixel(x, y)); outsideCount += 1
            }
        }
        android.util.Log.i("PlacementTest", "clip ${geometry.clip}, inside error ${inside / insideCount}, outside error ${outside / outsideCount}")
        assertTrue("inside the window the overlay is placed and scaled (error ${inside / insideCount})", inside / insideCount < 14)
        assertTrue("outside the window the base picture shows (error ${outside / outsideCount})", outside / outsideCount < 6)
        assertTrue("the window is not empty", insideCount > 50)
    }

    /** A placed overlay over a dissolve: the transition clip shows outside the window, unplaced (Swift `renderTracks`). */
    @Test fun placedOverlayOverATransition(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "PlacementTransitionTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val media = SourceMedia(filename = source.name, duration = 18.0)
        fun project(overlay: Boolean): Project {
            val project = Project("Placement over transition")
            project.append(media)
            var edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 3.0), TimelineClip(sourceID = media.id, start = 6.0, end = 9.0)))
            edit = edit.settingTransition(ClipTransition(duration = 1.0), 1)
            if (overlay) {
                val clip = TimelineClip(sourceID = media.id, start = 12.0, end = 16.0)
                edit.timeline.video = edit.timeline.video + VideoTimelineItem(clip.id, EditTime.zero, clip, 99)
                val window = edit.timeline.transitionWindows().single()
                val ids = edit.timeline.compile().first { it.interval.contains(window.interval.start) }.video
                edit.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(),
                    videos = ids.associateWith { if (it == clip.id) VideoPlacement.layout(3, 1, 2).copy(order = 9) else VideoPlacement() }))
            }
            project.commit(edit)
            return project
        }
        val plain = project(false)
        val window = plain.edit.timeline.transitionWindows().single()
        val at = ((window.interval.start.seconds + window.interval.end.seconds) / 2 * 1_000_000).toLong()
        suspend fun frame(project: Project, name: String): Bitmap {
            val destination = File(File(folder, "Exports"), "$name.mp4")
            MediaRenderer.render(context, project, folder, destination) { }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destination.absolutePath)
            val frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)!!
            retriever.release()
            return frame
        }
        val placedProject = project(true)
        val plan = ExportPlan.make(placedProject.edit)
        assertTrue("the transition is a layer behind the overlay", plan.layers.size == 1 && plan.layers[0].any { (it as? ExportPlan.Piece.Clip)?.transition != null })
        val dissolve = frame(plain, "dissolve")
        val placed = frame(placedProject, "placed")
        val clip = TrackRenderer.placed(VideoPlacement.layout(3, 1, 2), CGSize(placed.width.toDouble(), placed.height.toDouble())).clip
        var outside = 0.0; var count = 0
        for (y in 4 until placed.height - 4 step 6) for (x in 4 until placed.width - 4 step 6) {
            if (x > clip.minX - 4 && x < clip.maxX + 4 && y > clip.minY - 4 && y < clip.maxY + 4) continue
            val a = placed.getPixel(x, y); val b = dissolve.getPixel(x, y)
            outside += (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))) / 3.0
            count += 1
        }
        android.util.Log.i("PlacementTest", "transition outside error ${outside / count}")
        assertTrue("outside the window the dissolve shows unchanged (error ${outside / count})", outside / count < 6)
    }

    /**
     * Mirror and an image overlay (VideoTools.swift `MirrorSettings`, `ImageOverlay`):
     * the exported picture is the plain export flipped about the canvas centre,
     * and the opaque image covers its rect, unflipped, over the flipped footage.
     */
    @Test fun mirrorFlipsAndImagesOverlayTheExport(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "MirrorImageTest").apply { deleteRecursively(); mkdirs() }
        val source = File(folder, "fixture.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val media = SourceMedia(filename = source.name, duration = 18.0)
        val swatch = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(255, 0, 255)) }
        File(folder, "image-swatch.png").outputStream().use { swatch.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val image = ImageOverlay(filename = "image-swatch.png", start = 0.0, end = 4.0, x = 0.25, y = 0.3, width = 0.3)
        fun project(tools: Boolean): Project {
            val project = Project("Mirror and image export")
            project.append(media)
            val edit = EditState(listOf(TimelineClip(sourceID = media.id, start = 0.0, end = 3.0,
                mirror = if (tools) MirrorSettings(horizontal = true) else null, images = if (tools) listOf(image) else null)))
            edit.timeline.audio = emptyList()
            project.commit(edit)
            return project
        }
        suspend fun frame(tools: Boolean): Bitmap {
            val destination = File(File(folder, "Exports"), "tools-$tools.mp4")
            MediaRenderer.render(context, project(tools), folder, destination) { }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destination.absolutePath)
            val frame = retriever.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            retriever.release()
            return frame
        }
        val plain = frame(false)
        val tools = frame(true)
        val canvas = CGSize(tools.width.toDouble(), tools.height.toDouble())
        val rect = image.rect(CGSize(64.0, 32.0), canvas)
        val top = canvas.height - rect.maxY
        fun difference(a: Int, b: Int) = (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))) / 3.0
        var mirrored = 0.0; var mirroredCount = 0
        var covered = 0.0; var coveredCount = 0
        for (y in 4 until tools.height - 4 step 6) for (x in 4 until tools.width - 4 step 6) {
            val margin = 4
            if (x > rect.minX + margin && x < rect.maxX - margin && y > top + margin && y < top + rect.height - margin) {
                covered += difference(tools.getPixel(x, y), android.graphics.Color.rgb(255, 0, 255)); coveredCount += 1
            } else if (x < rect.minX - margin || x > rect.maxX + margin || y < top - margin || y > top + rect.height + margin) {
                mirrored += difference(tools.getPixel(x, y), plain.getPixel(tools.width - 1 - x, y)); mirroredCount += 1
            }
        }
        android.util.Log.i("MirrorImageTest", "image rect $rect, image error ${covered / coveredCount}, mirror error ${mirrored / mirroredCount}")
        assertTrue("the image covers its rect (error ${covered / coveredCount})", coveredCount > 50 && covered / coveredCount < 12)
        assertTrue("the footage is flipped horizontally (error ${mirrored / mirroredCount})", mirrored / mirroredCount < 8)
    }
}
