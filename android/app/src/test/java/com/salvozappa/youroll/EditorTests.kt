// JVM tests for the editor logic that does not need Compose or a device:
// trim semantics, move scopes, viewport mapping, timeline geometry,
// waveform binning/downsampling and the preview playlist plan.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class EditorTests {
    private val source = SourceMedia(filename = "a.mp4", duration = 20.0)
    private val other = SourceMedia(filename = "b.mp4", duration = 10.0)

    /** One 20 s source used as three adjacent clips: 0–4, 4–8 (source 6–10), 8–12 (source 12–16). */
    private fun threeClips(): EditState = EditState(listOf(
        TimelineClip(sourceID = source.id, start = 0.0, end = 4.0),
        TimelineClip(sourceID = source.id, start = 6.0, end = 10.0),
        TimelineClip(sourceID = source.id, start = 12.0, end = 16.0),
    ))

    private fun close(a: Double, b: Double, message: String, tolerance: Double = 1e-6) = assertEquals(message, a, b, tolerance)

    // ---------------------------------------------------------------- Trim

    @Test fun trimmingTheStartShrinksTheLinkedGroupAndClosesTheGap() {
        val edit = threeClips()
        val middle = edit.timeline.video[1]
        val result = EditorOperations.trim(edit, listOf(source), middle.id, left = true, delta = 1.0, preserving = 0.0)!!
        val timeline = result.state.timeline
        require(timeline.video.size == 3 && timeline.audio.size == 3, "Trim keeps one picture and one sound per clip")
        close(timeline.duration.seconds, 11.0, "Removing one second of a linked group closes the gap")
        val trimmed = timeline.video.sortedBy { it.start }[1]
        close(trimmed.start.seconds, 4.0, "The trimmed clip still starts at the cut")
        close(trimmed.mapping.runs.first().sourceStart, 7.0, "Its first second of source is gone")
        val sound = timeline.audio.sortedBy { it.start }[1]
        close(sound.mapping.runs.first().sourceStart, 7.0, "The linked sound is trimmed with the picture")
        require(result.feedback == "Trimmed · Undo available", "Trim feedback")
    }

    @Test fun trimmingTheEndNeverRemovesLessThanAFrameOrTheWholeClip() {
        val edit = threeClips()
        val first = edit.timeline.video[0]
        val result = EditorOperations.trim(edit, listOf(source), first.id, left = false, delta = -100.0, preserving = 0.0)!!
        val kept = result.state.timeline.video.minByOrNull { it.start }!!
        close(kept.interval.duration.seconds, TimelineEditing.frame, "A trim always keeps at least one frame", 1e-5)
        assertNull("A zero-length trim is not an edit", EditorOperations.trim(edit, listOf(source), first.id, left = false, delta = 0.0, preserving = 0.0))
    }

    @Test fun draggingAHandleOutwardRevealsTrimmedMaterial() {
        val edit = threeClips()
        val middle = edit.timeline.video[1]
        val result = EditorOperations.trim(edit, listOf(source), middle.id, left = true, delta = -1.5, preserving = 0.0)!!
        require(result.feedback == "Trim extended · Undo available", "Outward drags extend")
        val revealed = result.state.timeline.video.first { it.occurrenceID == middle.occurrenceID }
        close(revealed.mapping.runs.first().sourceStart, 4.5, "1.5 s of earlier source is revealed")
        // Other items keep their positions: revealing into an occupied interval overlaps.
        close(result.state.timeline.video.first { it.id == edit.timeline.video[0].id }.start.seconds, 0.0, "The neighbour does not move")
    }

    @Test fun deletingAnUnlinkedPartLeavesAGapWhileALinkedGroupCloses() {
        val edit = threeClips()
        val linked = EditorOperations.remove(edit, edit.timeline.selection(of = edit.timeline.video[1].id), 0.0)!!
        close(linked.state.duration, 8.0, "A complete picture+sound group closes its gap")
        val unlinked = edit.copy()
        unlinked.timeline.unlink(unlinked.timeline.selection(of = unlinked.timeline.video[1].id))
        val pictureOnly = TimelineSelection(video = setOf(unlinked.timeline.video[1].id))
        val gap = EditorOperations.remove(unlinked, pictureOnly, 0.0)!!
        close(gap.state.duration, 12.0, "Deleting only the picture leaves a gap")
        require(gap.state.timeline.video.size == 2 && gap.state.timeline.audio.size == 3, "The sound stays")
        require(gap.state.timeline.gap(et(5)) == null, "Sound still covers the picture gap, so it is not an empty gap")
    }

    // ------------------------------------------------------------ Move scopes

    @Test fun moveScopesExtendToTheFirstGapOnEachSide() {
        val edit = threeClips()
        // A fourth clip after a two-second gap.
        val inserted = TimelineEditing.inserting(listOf(TimelineClip(sourceID = other.id, start = 0.0, end = 3.0)), edit, 12.0)
        val timeline = inserted.timeline
        timeline.move(timeline.selection(of = timeline.video.first { it.content.sourceID == other.id }.id), et(2))
        val middle = timeline.selection(of = timeline.video.first { it.mapping.runs.first().sourceStart == 6.0 }.id)
        val choices = timeline.moveChoices(middle)
        fun starts(selection: TimelineSelection) = timeline.video.filter { it.id in selection.video }.map { it.start.seconds }.sorted()
        assertEquals(listOf(4.0), starts(choices.selected))
        assertEquals("With left adds the adjoining clip", listOf(0.0, 4.0), starts(choices.left))
        assertEquals("With right stops at the gap", listOf(4.0, 8.0), starts(choices.right))
        assertEquals(listOf(0.0, 4.0, 8.0), starts(choices.both))
        require(TimelineMoveScope.entries.all { choices.available(it) }, "All scopes differ here")
        // The last clip has nothing adjoining on the right.
        val last = timeline.moveChoices(timeline.selection(of = timeline.video.first { it.content.sourceID == other.id }.id))
        require(!last.available(TimelineMoveScope.left) && !last.available(TimelineMoveScope.right), "A clip alone between gaps moves alone")
        require(last.available(TimelineMoveScope.selected), "Selected only is always available")
    }

    @Test fun moveScopeGlyphFlagsMatchTheirNames() {
        require(!TimelineMoveScope.selected.includesLeft && !TimelineMoveScope.selected.includesRight, "selected")
        require(TimelineMoveScope.left.includesLeft && !TimelineMoveScope.left.includesRight, "left")
        require(!TimelineMoveScope.right.includesLeft && TimelineMoveScope.right.includesRight, "right")
        require(TimelineMoveScope.both.includesLeft && TimelineMoveScope.both.includesRight, "both")
    }

    // ---------------------------------------------------- Zoom and pixel mapping

    @Test fun viewportMapsTimeAndPixelsBothWays() {
        val view = TimelineViewport(width = 400.0, duration = 18.0, requestedScale = 60.0)
        require(!view.fits, "18 s at 60 pt/s does not fit 400 pt")
        close(view.inset, 200.0, "A scrolling viewport centres the playhead")
        val offset = 5.0 * view.scale // playhead at 5 s
        close(view.x(5.0, offset), 200.0, "The playhead is at the centre")
        close(view.x(6.0, offset), 260.0, "One second is 60 points")
        for (time in listOf(0.0, 2.5, 5.0, 17.9)) close(view.time(view.x(time, offset), offset), time, "x ↔ time round trip")
        close(view.time(-1000.0, offset), 0.0, "Times clamp at zero")
        close(view.time(10000.0, offset), 18.0, "Times clamp at the end")
    }

    @Test fun zoomClampsBetweenWholeProjectAnd720PointsPerSecond() {
        val view = TimelineViewport(400.0, 18.0, 0.001)
        close(view.scale, view.minimumScale, "Zooming out stops at the whole project")
        require(view.fits, "The whole project fits")
        close(view.inset, TimelineViewport.edgeInset, "At fit each edge keeps room for an add button")
        close(view.x(0.0, 0.0), 48.0, "The project starts after the inset")
        close(view.x(18.0, 0.0), 352.0, "and ends before the other inset")
        close(TimelineViewport(400.0, 18.0, 5000.0).scale, 720.0, "Zooming in stops at 720 pt/s")
    }

    @Test fun laneGeometryGivesSpareRoomToPictures() {
        val edit = threeClips()
        val lanes = timelineLanes(edit.timeline)
        val geometry = LaneGeometry(lanes, laneAreaHeight = 144.0)
        assertEquals(listOf(82.0, 46.0), geometry.heights)
        assertEquals(0, geometry.row(10.0)); assertEquals(1, geometry.row(90.0)); assertEquals(2, geometry.row(200.0))
        close(geometry.top(1), 82.0, "The sound row starts below the picture row")
    }

    @Test fun insertionButtonsSitAtTheEndsAndInPictureGaps() {
        val edit = threeClips()
        edit.timeline.closeGap(et(0)) // no gap: stays the same
        val view = TimelineViewport(400.0, edit.duration, 1.0) // fits
        val span = Rect(352f, 22f, 396f, 66f)
        val markers = insertionMarkers(400.0, 164.0, 82.0, Offset.Zero, view, edit.timeline.videoCoverage, edit.duration, span, null)
        require(markers.any { it.site == VideoInsertionSite.start && it.frame.right <= view.x(0.0, 0.0) }, "Start + sits left of the first clip")
        require(markers.any { it.site == VideoInsertionSite.end }, "End + is offered")
        require(markers.none { it.frame.overlaps(span) }, "No + overlaps the span button")
        val gapped = edit.copy()
        gapped.timeline.remove(TimelineSelection(video = setOf(gapped.timeline.video[1].id)))
        val withGap = insertionMarkers(400.0, 164.0, 82.0, Offset.Zero, view, gapped.timeline.videoCoverage, gapped.duration, span, null)
        require(withGap.any { it.site is VideoInsertionSite.gap }, "A picture gap gets its own +")
    }

    @Test fun editorTimesRoundToFrames() {
        assertEquals("0:00.00", editorTime(0.0))
        assertEquals("0:04.83", editorTime(4.833))
        assertEquals("0:18.00", editorTime(18.0))
        assertEquals("1:05.50", editorTime(65.5))
        assertEquals("0:00.00", editorTime(Double.NaN))
        val a = EditTime(EditTime.scale)
        val b = EditTime(EditTime.scale + 10)
        assertEquals("0:01.00", moveBoundaryLabel(a, listOf(a)))
        require(moveBoundaryLabel(a, listOf(a, b)) != moveBoundaryLabel(b, listOf(a, b)), "Sub-frame neighbours get distinct labels")
    }

    // -------------------------------------------------------------- Waveforms

    @Test fun waveformBinsHoldPeakAndLouderChannelRms() {
        val rate = 48000; val duration = 1.0
        val frames = rate
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            samples[i * 2] = (0.5 * sin(2 * PI * 440 * i / rate)).toFloat()   // left: 0.5 sine
            samples[i * 2 + 1] = if (i >= frames / 2) 0.25f else 0f           // right: silent, then DC
        }
        val accumulator = WaveformAccumulator(duration, bins = 100)
        // Feed in uneven chunks, as a decoder would.
        var start = 0
        for (size in generateSequence(1000) { it + 777 }) {
            if (start >= frames) break
            val count = minOf(size, frames - start)
            accumulator.append(samples.copyOfRange(start * 2, (start + count) * 2), count, 2, rate, start.toDouble() / rate)
            start += count
        }
        val wave = accumulator.result()
        assertEquals(100, wave.peaks.size)
        require(wave.peaks.all { abs(it - 0.5f) < 0.01f }, "Every 10 ms bin of a 0.5 sine peaks at 0.5")
        close(wave.rms!![10].toDouble(), 0.5 / sqrt(2.0), "The sine channel's RMS", 0.01)
        close(wave.rms[90].toDouble(), 0.5 / sqrt(2.0), "The louder channel wins over the 0.25 DC channel", 0.01)
        close(wave.peak(0.505).toDouble(), 0.5, "peak(at:) reads the bin under a time", 0.01)
        close(wave.peak(1.5).toDouble(), 0.0, "Outside the source the peak is zero", 0.0)
    }

    @Test fun pyramidPeakEqualsTheMaximumOverTheRange() {
        val random = Random(7)
        val peaks = FloatArray(1801) { random.nextFloat() }
        val wave = SourceWaveform(18.01, peaks)
        val pyramid = PeakPyramid()
        repeat(500) {
            val from = random.nextDouble(0.0, 18.0)
            val to = from + random.nextDouble(0.0, 3.0)
            val a = maxOf(0, minOf(peaks.size - 1, (from / wave.duration * peaks.size).toInt()))
            val b = maxOf(a + 1, minOf(peaks.size, (to / wave.duration * peaks.size).toInt()))
            val expected = (a until b).maxOf { peaks[it] }
            assertEquals("max over [$a, $b)", expected, pyramid.peak("s", wave, from, to), 0f)
        }
    }

    @Test fun detailTilesKeepTheSignedEnvelopePerMillisecond() {
        val rate = 48000
        val frames = rate / 10 // 100 ms
        val samples = FloatArray(frames) { i -> if (i < frames / 2) 0.8f else -0.4f }
        val tile = WaveformTile.build(1, sequenceOf(PcmDecoder.Chunk(samples, frames, 1, rate, 4.0)))
        assertEquals(4000, tile.low.size)
        val first = tile.envelope(4.0, 4.04)!!
        close(first.second.toDouble(), 0.8, "Positive half", 1e-6); close(first.first.toDouble(), 0.0, "No negative samples yet", 1e-6)
        val both = tile.envelope(4.0, 4.1)!!
        close(both.first.toDouble(), -0.4, "Negative half", 1e-6)
        assertNull("Another tile's time is not answered", tile.envelope(8.5, 8.6))
    }

    // ---------------------------------------------------------- Preview plan

    @Test fun previewPlanPlaysPicturesAndBlackGaps() {
        val edit = threeClips()
        val plain = PreviewPlan.segments(edit.timeline)
        assertEquals("One segment per picture span", 3, plain.size)
        close(plain[1].video!!.sourceStart, 6.0, "Second clip starts at source 6 s")
        val gapped = edit.copy()
        gapped.timeline.unlink(gapped.timeline.selection(of = gapped.timeline.video[1].id))
        gapped.timeline.remove(TimelineSelection(video = setOf(gapped.timeline.video[1].id)))
        val segments = PreviewPlan.segments(gapped.timeline)
        val hole = segments.first { it.start <= 5.0 && 5.0 < it.end }
        assertNull("A picture gap shows black", hole.video)
        close(segments.sumOf { it.duration }, gapped.duration, "Segments cover the whole edit")
        // The mix clock is tiled exactly: each segment starts where the previous one ended.
        for ((a, b) in segments.zipWithNext()) assertEquals("Clock frames are contiguous", a.clockFrames.last + 1, b.clockFrames.first)
        val sound = ExportPlan.previewAudio(gapped.timeline).flatten().filterIsInstance<ExportPlan.Piece.Clip>()
        require(sound.any { it.start.seconds <= 5.0 && 5.0 < it.end.seconds && abs(it.sourceStart - 6.0) < 1e-9 }, "and the gap keeps playing its sound")
    }

    @Test fun previewPlanRetimesSpeedRunsAndHoldsOneFrame() {
        val a = SourceMedia(filename = "a.mp4", duration = 8.0)
        val effect = VideoEffectSettings(VideoEffectID.freezeFrame, 4.0, 4.0 + TimelineEditing.frame)
        val edit = EditState(listOf(
            TimelineClip(sourceID = a.id, start = 0.0, end = 4.0, speed = SpeedSettings(rate = 2.0)),
            TimelineClip(sourceID = a.id, start = 4.0, end = 4.0 + TimelineEditing.frame, frozenFrame = FrozenFrameSegment(4.0, 1.5), videoEffects = listOf(effect)),
            TimelineClip(sourceID = a.id, start = 4.0, end = 6.0, speed = SpeedSettings(rate = 0.5)),
        ))
        val segments = PreviewPlan.segments(edit.timeline)
        assertEquals("Fast, hold, slow", 3, segments.size)
        close(segments[0].duration, 2.0, "4 s at 2× lasts 2 s"); close(segments[0].rate, 2.0, "and plays two source seconds per second")
        require(segments[1].video!!.isHold && segments[1].video!!.sourceStart == 4.0, "The hold shows the source picture at 4 s")
        close(segments[1].duration, 1.5, "for its duration")
        close(segments[2].duration, 4.0, "2 s at 0.5× lasts 4 s"); close(segments[2].rate, 0.5, "at half speed")
        close(segments.sumOf { it.duration }, edit.duration, "Segments cover the whole edit")
    }

    @Test fun speedStretchKeepsThePitchAndFollowsTheTimeline() {
        // Swift VideoToolsTests: the 1 kHz tone stays at 1 kHz at 0.5× and 2×.
        val rate = 48_000
        val tone = ShortArray(rate * 4) { (Math.sin(2 * Math.PI * 1000 * it / rate) * 16_000).toInt().toShort() }
        val source = MemoryPcm(rate, 1, tone)
        for (speed in listOf(0.5, 2.0)) {
            val key = SpeedStretch.Key(newUUID(), 0.0, 4.0, et(4 / speed))
            val out = java.io.ByteArrayOutputStream()
            SpeedStretch.render(source, key) { buffer -> val bytes = ByteArray(buffer.remaining()); buffer.duplicate().get(bytes); out.write(bytes) }
            val samples = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            val frames = samples.remaining()
            close(frames / rate.toDouble(), 4 / speed, "The stretch lasts the timeline length at ${speed}×", tolerance = 0.03)
            var crossings = 0
            val first = rate / 2
            for (index in first + 1 until first + rate) if (samples.get(index - 1) <= 0 && samples.get(index) > 0) crossings += 1
            close(crossings.toDouble(), 1000.0, "Pitch stays at 1 kHz for ${speed}×", tolerance = 15.0)
        }
    }

    /** A source whose every sample is [value]: the mix output is then value × the summed gain. */
    private class ConstantSource(val value: Float, override val sampleRate: Int = 48_000, override val channels: Int = 2, override val frames: Long = 48_000L * 60) : PcmSamples {
        override fun sample(frame: Long, channel: Int) = if (frame in 0 until frames) value else 0f
    }

    /** Two sources whose sounds overlap from 3 to 4 s, with volume and both kinds of fade. */
    private fun overlappingSound(): EditState {
        val a = SourceMedia(filename = "a.mp4", duration = 4.0)
        val b = SourceMedia(filename = "b.mp4", duration = 3.0)
        val state = EditState(listOf(TimelineClip(sourceID = a.id, start = 0.0, end = 4.0), TimelineClip(sourceID = b.id, start = 0.0, end = 3.0)))
        state.timeline.unlink(state.timeline.entireSelection())
        val second = state.timeline.audio.first { it.sourceID == b.id }
        state.timeline.move(TimelineSelection(audio = setOf(second.id)), et(-1))
        state.timeline.audio = state.timeline.audio.map {
            if (it.sourceID == a.id) it.copy(volumeDB = -6.0).settingFade(AudioFadeEdge.fadeOut, 1.5)
            else it.settingFade(AudioFadeEdge.fadeIn, 0.5)
        }
        return state
    }

    @Test fun previewMixesOverlappingSoundWithTheExportGainSampleBySample() {
        val edit = overlappingSound()
        val export = ExportPlan.make(edit)
        require(export.audio.size == 2, "The export needs two lanes for the overlap")
        val lanes = ExportPlan.previewAudio(edit.timeline)
        val sources = edit.timeline.audio.associate { it.sourceID to (ConstantSource(0.5f) as PcmSamples) }
        val mix = PreviewAudioMix(lanes, sources)
        val exportEnvelopes = export.audio.flatten().mapNotNull { (it as? ExportPlan.Piece.Clip)?.envelope }
        fun exportGain(time: Double) = exportEnvelopes.sumOf { it.gain(time) }
        // Fade boundaries: A's fade-out starts at 2.5 s, B starts (fade-in) at 3.0 s and ends its fade at 3.5 s, A ends at 4 s.
        val out = FloatArray(2)
        for (boundary in listOf(0.0, 2.5, 3.0, 3.5, 4.0, 6.0)) {
            for (offset in -3..3) {
                val frame = PreviewAudioMix.frame(boundary) + offset
                if (frame < 0) continue
                val pieces = mix.render(frame, 1, out)
                val time = frame / 48_000.0
                close(out[0].toDouble(), 0.5 * exportGain(time), "Left at frame $frame (t=$time, $pieces pieces)", 2e-6)
                close(out[1].toDouble(), out[0].toDouble(), "Right equals left", 0.0)
            }
        }
        // Inside the overlap both sounds are heard.
        val inside = PreviewAudioMix.frame(3.75)
        require(mix.render(inside, 1, out) == 2, "Two pieces play at 3.75 s")
        close(out[0].toDouble(), 0.5 * (0.501 * (0.25 / 1.5) + 1.0), "A at −6 dB, a quarter s before its end, plus B at full level", 2e-3)
    }

    @Test fun previewGainFollowsVolumeMuteFadesAndAuditions() {
        val edit = threeClips()
        val first = edit.timeline.audio.minByOrNull { it.start }!!
        edit.timeline.audio = edit.timeline.audio.map { if (it.id == first.id) it.copy(volumeDB = -6.0).settingFade(AudioFadeEdge.fadeIn, 2.0) else it }
        val sources = mapOf<UUID, PcmSamples>(source.id to ConstantSource(1f))
        val out = FloatArray(2)
        fun level(timeline: TrackTimeline, time: Double, live: LiveAudioGain.Snapshot = LiveAudioGain.Snapshot()): Double {
            PreviewAudioMix(ExportPlan.previewAudio(timeline), sources).render(PreviewAudioMix.frame(time), 1, out, live = live)
            return out[0].toDouble()
        }
        close(level(edit.timeline, 1.0), 0.5 * 0.501, "Half-way through a 2 s fade at −6 dB", 1e-3)
        close(level(edit.timeline, 3.0), 0.501, "After the fade", 1e-3)
        val audition = LiveAudioGain().apply { update(0.0, TimelineSelection(audio = setOf(first.id))) }.snapshot()
        close(level(edit.timeline, 3.0, audition), 1.0, "A waveform drag previews its level", 1e-4)
        close(level(edit.timeline, 5.0, audition), 1.0, "Other parts keep theirs", 1e-4)
        val ranged = LiveAudioGain().apply { update(-40.0, TimelineSelection(audio = setOf(first.id), range = iv(2.0, 3.0))) }.snapshot()
        close(level(edit.timeline, 3.5, ranged), 0.501, "Outside a ranged audition the level is unchanged", 1e-3)
        close(level(edit.timeline, 2.5, ranged), 0.01, "Inside it the auditioned level applies", 1e-3)
        edit.timeline.audio = edit.timeline.audio.map { if (it.id == first.id) it.copy(muted = true) else it }
        close(level(edit.timeline, 3.0), 0.0, "Muted", 0.0)
    }

    @Test fun previewClockCarriesItsTimelineFrame() {
        for (frame in listOf(0L, 1L, 65_535L, 65_536L, 48_000L * 3600 + 17)) {
            val left = (frame and 0xFFFF).toInt().toShort()
            val right = ((frame ushr 16) and 0xFFFF).toInt().toShort()
            assertEquals(frame, PreviewClock.frameNumber(left, right))
        }
    }

    // ------------------------------------------------------------ Bookmarks

    @Test fun bookmarksFollowTheirSourceThroughSplitsTrimsMovesAndDeletes() {
        var edit = threeClips() // 0–4 (source 0–4), 4–8 (source 6–10), 8–12 (source 12–16)
        edit.timeline.addBookmark(et(5.5))
        val mark = edit.timeline.bookmarks.single()
        require(mark.anchor == BookmarkAnchor.source(edit.timeline.video[1].occurrenceID, source.id, 7.5), "A mark on footage is anchored to its source time")
        fun time() = edit.timeline.resolvedBookmarks.single().time.seconds
        edit = TimelineEditing.split(edit, 5.0)
        close(time(), 5.5, "A split before the mark keeps it in place")
        edit = EditorOperations.trim(edit, listOf(source), edit.timeline.video.minByOrNull { it.start }!!.id, left = true, delta = 1.0, preserving = 0.0)!!.state
        close(time(), 4.5, "Trimming earlier footage moves it with its source")
        val owner = edit.timeline.video.first { it.start.seconds <= time() && time() < it.end.seconds }
        edit.timeline.move(edit.timeline.selection(of = owner.id), et(2))
        close(time(), 6.5, "Moving its clip carries the mark")
        val first = edit.timeline.video.minByOrNull { it.start }!!
        edit = EditorOperations.remove(edit, edit.timeline.selection(of = first.id), 0.0)!!.state
        close(time(), 3.5, "Deleting earlier footage closes up to the mark")
        val holder = edit.timeline.video.first { it.start.seconds <= time() && time() < it.end.seconds }
        edit = EditorOperations.trim(edit, listOf(source), holder.id, left = true, delta = time() - holder.start.seconds + 0.5, preserving = 0.0)!!.state
        require(edit.timeline.resolvedBookmarks.isEmpty(), "Trimming the marked moment away hides the mark")
        require(edit.timeline.bookmarks.size == 1, "but keeps it, so extending the clip again shows it")
    }

    @Test fun captureMarksResolveAtTheirRecordedTime() {
        val take = source.copy(events = listOf(CaptureEvent(time = 2.4, kind = "bookmark", detail = "Bookmark"), CaptureEvent(time = 7.0, kind = "videoHold", detail = "")))
        val edit = EditState(listOf(TimelineClip(sourceID = take.id, start = 0.0, end = take.duration)))
        edit.timeline.includeCaptureBookmarks(take)
        val marks = edit.timeline.resolvedBookmarks
        require(marks.size == 1 && marks[0].id == take.events!![0].id, "Only bookmark events become marks, keeping the event identity")
        close(marks[0].time.seconds, 2.4, "A recorder mark resolves at its recorded source time", 1e-5)
    }

    @Test fun waveformGainDragSnapsToHalfDecibelsWithAZeroDetent() {
        val drag = AudioGainDrag(0.0, 0.0..0.0)
        drag.update(3.0) // 0.375 dB: inside the zero detent
        assertEquals(0.0, drag.value!!, 0.0)
        drag.update(16.0) // +2 dB
        assertEquals(2.0, drag.value!!, 0.0)
        drag.update(-1000.0)
        assertEquals(-40.0, drag.value!!, 0.0)
        val mixed = AudioGainDrag(null, -6.0..3.0)
        mixed.update(8.0)
        assertNull("A mixed selection stays untouched until a deliberate drag", mixed.value)
        mixed.update(20.0)
        assertEquals(4.0, mixed.value!!, 0.0)
        assertEquals(12.0, AudioGainGrid.snap(40.0), 0.0); assertEquals(-1.5, AudioGainGrid.snap(-1.4), 0.0)
    }
}
