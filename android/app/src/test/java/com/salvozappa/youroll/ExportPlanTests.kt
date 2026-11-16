// YouRoll tests for the timeline → export plan mapping (MediaRenderer.kt).
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class ExportPlanTests {
    private val a = SourceMedia(filename = "a.mp4", duration = 4.0)
    private val b = SourceMedia(filename = "b.mp4", duration = 3.0)

    private fun edit(vararg clips: TimelineClip) = EditState(clips.toList())
    private fun clip(source: SourceMedia, start: Double, end: Double) = TimelineClip(sourceID = source.id, start = start, end = end)
    private val ExportPlan.Piece.isGap get() = this is ExportPlan.Piece.Gap
    private fun ExportPlan.Piece.clip() = this as ExportPlan.Piece.Clip

    /** Every lane must tile the whole movie without holes or overlaps. */
    private fun requireTiled(pieces: List<ExportPlan.Piece>, duration: EditTime, message: String) {
        var cursor = EditTime.zero
        for (piece in pieces) {
            require(piece.start == cursor && piece.duration > EditTime.zero, "$message: contiguous pieces")
            cursor = piece.end
        }
        require(cursor == duration, "$message: covers the full duration")
    }

    @Test fun straightCutsBecomeOneSequenceAndOneSoundLane() {
        val plan = ExportPlan.make(edit(clip(a, 0.0, 4.0), clip(b, 1.0, 3.0)))
        require(plan.duration == et(6), "Duration is the edit's")
        require(plan.video.size == 2 && plan.video.none { it.isGap }, "Two pictures, no gap")
        require(plan.video[0].clip().sourceID == a.id && plan.video[1].clip().sourceID == b.id, "Pictures in timeline order")
        assertEquals(1.0, plan.video[1].clip().sourceStart, 1e-9)
        assertEquals(3.0, plan.video[1].clip().sourceEnd, 1e-9)
        require(plan.audio.size == 1, "Linked sound needs one lane")
        requireTiled(plan.video, plan.duration, "Picture")
        requireTiled(plan.audio[0], plan.duration, "Sound")
        require(plan.audio[0].all { it.clip().envelope!!.volume == 1.0 }, "Unity gain")
        require(plan.sourceIDs == setOf(a.id, b.id), "Both sources are read")
    }

    @Test fun splitPiecesOfOneTakeStayOneMediaItem() {
        val state = edit(clip(a, 0.0, 4.0))
        state.timeline.split(et(1.5), state.timeline.entireSelection())
        val plan = ExportPlan.make(state)
        require(plan.video.size == 1 && plan.video[0].duration == et(4), "A split without a cut renders as one continuous item")
        require(plan.audio.size == 1 && plan.audio[0].size == 1, "and one continuous sound piece (no de-click dip at the split)")
    }

    @Test fun gapsAreBlackPictureAndSilence() {
        val state = edit(clip(a, 0.0, 4.0), clip(b, 0.0, 3.0))
        val second = state.timeline.chronologicalVideo[1]
        state.timeline.move(state.timeline.selection(second.id), et(1))
        val plan = ExportPlan.make(state)
        require(plan.duration == et(8), "The gap lengthens the movie")
        require(plan.video.map { it.isGap } == listOf(false, true, false), "Picture: clip, black gap, clip")
        require(plan.video[1].start == et(4) && plan.video[1].duration == et(1), "The gap sits between the clips")
        require(plan.audio.size == 1 && plan.audio[0].map { it.isGap } == listOf(false, true, false), "Sound: clip, silence, clip")
        requireTiled(plan.video, plan.duration, "Picture")
        requireTiled(plan.audio[0], plan.duration, "Sound")
    }

    @Test fun leadingGapStartsTheSequence() {
        val state = edit(clip(a, 0.0, 2.0))
        state.timeline.move(state.timeline.entireSelection(), et(0.5))
        val plan = ExportPlan.make(state)
        require(plan.video.first().isGap && plan.video.first().duration == et(0.5), "Black before the first picture")
        require(plan.audio[0].first().isGap, "Silence before the first sound")
        requireTiled(plan.video, plan.duration, "Picture")
    }

    @Test fun overlappingSoundUsesSeparateLanes() {
        val state = edit(clip(a, 0.0, 4.0), clip(b, 0.0, 3.0))
        state.timeline.unlink(state.timeline.entireSelection())
        val secondSound = state.timeline.audio.first { it.sourceID == b.id }
        // Pull B's sound one second under A: both sounds play from 3 to 4 s.
        state.timeline.move(TimelineSelection(audio = setOf(secondSound.id)), et(-1))
        val plan = ExportPlan.make(state)
        require(plan.duration == et(7), "The pictures still end at 7 s")
        require(plan.audio.size == 2, "Overlapping sound needs a second lane")
        plan.audio.forEach { requireTiled(it, plan.duration, "Lane") }
        val lane = plan.audio[1]
        require(lane.first().isGap && lane.first().duration == et(3), "B's sound starts at 3 s in its own lane")
        require(lane[1].clip().sourceID == b.id, "and it is B's sound")
        require(plan.video.map { it.isGap } == listOf(false, false), "The pictures are unchanged")
    }

    @Test fun overlappingPicturesShowTheFrontOne() {
        val state = edit(clip(a, 0.0, 4.0), clip(b, 0.0, 3.0))
        val second = state.timeline.chronologicalVideo[1]
        state.timeline.unlink(state.timeline.entireSelection())
        state.timeline.move(TimelineSelection(video = setOf(second.id)), et(-1))
        val plan = ExportPlan.make(state)
        val front = state.timeline.compile().first { it.interval.contains(et(3.5)) }.video.last()
        val frontSource = state.timeline.video.first { it.id == front }.content.sourceID
        val shown = plan.video.first { it.start <= et(3.5) && et(3.5) < it.end }.clip()
        require(shown.sourceID == frontSource, "The overlap shows the front picture")
        requireTiled(plan.video, plan.duration, "Picture")
    }

    @Test fun volumeFadesAndMute() {
        val state = edit(clip(a, 0.0, 4.0), clip(b, 0.0, 3.0))
        val first = state.timeline.audio.first { it.sourceID == a.id }
        state.timeline.setVolume(-6.0, false, TimelineSelection(audio = setOf(first.id)))
        state.timeline.setFade(AudioFadeEdge.fadeIn, 1.0, first.id)
        val second = state.timeline.audio.first { it.sourceID == b.id }
        state.timeline.setVolume(0.0, true, TimelineSelection(audio = setOf(second.id)))
        val plan = ExportPlan.make(state)
        val pieces = plan.audio.single().filterIsInstance<ExportPlan.Piece.Clip>()
        require(pieces.size == 1 && pieces[0].sourceID == a.id, "A muted item is silence")
        val envelope = pieces[0].envelope!!
        assertEquals(Math.pow(10.0, -6.0 / 20), envelope.volume, 1e-9)
        assertEquals(0.0, envelope.gain(0.0), 1e-9)
        assertEquals(envelope.volume * 0.5, envelope.gain(0.5), 1e-6)
        assertEquals(envelope.volume, envelope.gain(2.0), 1e-9)
        require(envelope.gain(4.0 - 0.004) < envelope.volume * 0.6, "An 8 ms de-click ramp closes the piece")
        require(envelope.gain(4.0) == 0.0 && envelope.gain(-0.1) == 0.0, "Silent outside the piece")
    }

    @Test fun emptyTimelinesAreRefused() {
        val empty = assertThrowing<ProjectError>("Nothing to export") { ExportPlan.make(EditState()) }
        require(empty.case == ProjectError.Case.emptyTimeline, "Empty timeline error")
    }

    @Test fun speedRunsKeepTheirSourceSpanAndRate() {
        // Swift VideoToolsTests: a 4 s source at 2× lasts 2 s, at 0.5× 8 s.
        for (rate in listOf(0.5, 2.0)) {
            val plan = ExportPlan.make(edit(clip(a, 0.0, 4.0).copy(speed = speedJson(rate))))
            require(plan.duration == et(4 / rate), "Speed export duration matches timeline ($rate×)")
            val picture = plan.video.single().clip()
            assertEquals(0.0, picture.sourceStart, 1e-9); assertEquals(4.0, picture.sourceEnd, 1e-9)
            assertEquals(rate, picture.rate, 1e-9)
            require(!picture.isNormalSpeed && !picture.isHold, "A speed run is retimed")
            val sound = plan.audio.single().single().clip()
            assertEquals(rate, sound.rate, 1e-9)
            require(sound.envelope!!.end == 4 / rate, "The sound's envelope is on the timeline clock")
        }
        // A 2× range inside a 1× take: three pictures, never merged across the rate change.
        val state = edit(clip(a, 0.0, 4.0))
        val scope = state.timeline.scoped(state.timeline.entireSelection(iv(1, 3)))
        val ranged = EditState(scope.timeline, null)
        ranged.clips = ranged.clips.map { if (it.id in scope.selection.video) it.copy(speed = speedJson(2.0)) else it }
        val plan = ExportPlan.make(ranged)
        require(plan.duration == et(3), "Only the marked range is faster: 1 + 1 + 1 s")
        require(plan.video.map { it.clip().rate } == listOf(1.0, 2.0, 1.0), "Pictures change rate at the range edges: ${plan.video.map { it.clip().rate }}")
        require(plan.audio.single().map { (it as ExportPlan.Piece.Clip).rate } == listOf(1.0, 2.0, 1.0), "So does the sound")
        requireTiled(plan.video, plan.duration, "Picture")
    }

    @Test fun freezeFramesHoldOnePictureInSilence() {
        val effect = VideoEffectSettings(VideoEffectID.freezeFrame, 1.0, 1.0 + TimelineEditing.frame)
        val hold = clip(a, 1.0, 1.0 + TimelineEditing.frame).copy(frozenFrame = FrozenFrameSegment(sourceTime = 1.0, duration = 3.0), videoEffects = listOf(effect))
        val plan = ExportPlan.make(edit(clip(a, 0.0, 1.0), hold, clip(a, 1.0, 4.0)))
        require(plan.duration == et(7), "The hold adds its duration")
        require(plan.video.size == 3, "Picture: footage, hold, footage")
        val held = plan.video[1].clip()
        require(held.isHold && held.sourceStart == 1.0 && held.duration == et(3), "The hold shows the source picture at 1 s for 3 s")
        require(plan.audio.single().map { it.isGap } == listOf(false, true, false), "A hold is silent")
        val other = hold.copy(videoEffects = listOf(effect, VideoEffectSettings(VideoEffectID.dollyZoom, 1.0, 2.0)))
        assertThrowing<ExportPlan.Unsupported>("Effects that need person or depth separation are refused") { ExportPlan.make(edit(other)) }
        ExportPlan.make(edit(hold.copy(videoEffects = listOf(effect, VideoEffectSettings(VideoEffectID.noir, 1.0, 2.0)))))
    }

    @Test fun holdFramesPickThePictureShownAtTheirTime() {
        val times = listOf(0L, 66_666L, 33_333L, 100_000L) // decode order
        assertEquals(HoldFrames.Window(33_333L, 66_666L), HoldFrames.window(times, 50_000L, null))
        assertEquals(HoldFrames.Window(33_333L, 66_666L), HoldFrames.window(times, 33_333L, null))
        assertEquals(HoldFrames.Window(0L, 33_333L), HoldFrames.window(times, -5L, null))
        assertEquals("The last frame lasts one frame, within the track", HoldFrames.Window(100_000L, 120_000L), HoldFrames.window(times, 110_000L, 120_000L))
        assertEquals(HoldFrames.Window(100_000L, 133_333L), HoldFrames.window(times, 110_000L, null))
    }

    @Test fun mediaPositionsUseCumulativeBoundaries() {
        // 1/3 s pieces: per-piece rounding would drift; boundaries never do.
        val third = EditTime(EditTime.scale / 3)
        var cursor = EditTime.zero
        var total = 0L
        repeat(300) {
            val next = cursor + third
            total += ExportPlan.microseconds(next) - ExportPlan.microseconds(cursor)
            cursor = next
        }
        require(total == ExportPlan.microseconds(cursor), "Lengths add up to the end position exactly")
    }

    @Test fun progressNeverRewinds() {
        val picture = ExportProgress(ExportProgress.Step.picture, 0.5)
        assertEquals(0.14 + 0.5 * 0.76, picture.fraction, 1e-9)
        val late = picture.advanced(ExportProgress(ExportProgress.Step.timeline, 1.0))
        require(late == picture, "An earlier step cannot replace a later one")
        val slower = picture.advanced(ExportProgress(ExportProgress.Step.picture, 0.2))
        require(slower.fraction == picture.fraction, "The bar never moves back")
        require(ExportProgress(ExportProgress.Step.complete).fraction == 1.0, "Only completion is 100%")
    }

    /** A 4 s picture of a with a 2 s picture of b over its middle (2…4 s overlap → b is in front). */
    private fun overlapped(): EditState {
        val state = edit(clip(a, 0.0, 4.0), clip(b, 0.0, 2.0))
        val second = state.timeline.chronologicalVideo[1]
        state.timeline.unlink(state.timeline.entireSelection())
        state.timeline.move(TimelineSelection(video = setOf(second.id)), et(-2))
        return state
    }

    @Test fun placedOverlapsStackEveryPictureInDepthLanes() {
        val state = overlapped()
        val overlap = state.timeline.compile().first { it.video.size == 2 }
        require(overlap.interval == EditInterval(et(2), et(4)), "The overlay covers 2…4 s")
        require(ExportPlan.make(state).layers.isEmpty(), "Without a placement only the front picture is rendered")
        val ids = overlap.video
        val pip = VideoPlacement.layout(3, 1, 2)
        state.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(), videos = mapOf(ids[0] to VideoPlacement(order = 0), ids[1] to pip)))
        val plan = ExportPlan.make(state)
        require(plan.layers.size == 1, "One picture behind the front one needs one more sequence")
        requireTiled(plan.video, plan.duration, "Front lane")
        requireTiled(plan.layers[0], plan.duration, "Back lane")
        val front = plan.video.first { it.start <= et(3) && et(3) < it.end }.clip()
        val back = plan.layers[0].first { it.start <= et(3) && et(3) < it.end }.clip()
        require(front.sourceID == b.id && front.placement == pip, "The PiP is in front, in its window")
        require(back.sourceID == a.id && back.placement == VideoPlacement(order = 0), "The covering picture is behind it")
        require(plan.layers[0].filter { !it.isGap }.all { it.start >= et(2) && it.end <= et(4) }, "The back lane only shows pictures during the overlap")
        val canvas = CGSize(1920.0, 1080.0)
        require(plan.showsPicture(0, 1_000_000, canvas) && !plan.showsPicture(1, 1_000_000, canvas) && plan.showsPicture(1, 3_000_000, canvas) &&
            !plan.showsPicture(1, 4_500_000, canvas), "The compositor hides a lane outside its pictures")
        // Send the PiP to the back: depth follows `order`, not the timeline layer.
        state.timeline.placements = listOf(state.timeline.placements[0].let { it.copy(videos = it.videos + (ids[1] to pip.copy(order = -1))) })
        val reordered = ExportPlan.make(state)
        require(reordered.video.first { it.start <= et(3) && et(3) < it.end }.clip().sourceID == a.id &&
            reordered.layers[0].first { it.start <= et(3) && et(3) < it.end }.clip().sourceID == b.id, "Send to back reorders the lanes")
    }

    @Test fun animatedPlacementsAreRefused() {
        val state = overlapped()
        val ids = state.timeline.compile().first { it.video.size == 2 }.video
        state.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(),
            videos = mapOf(ids[0] to VideoPlacement(), ids[1] to VideoPlacement(motion = kotlinx.serialization.json.JsonObject(emptyMap())))))
        assertThrowing<ExportPlan.Unsupported>("An animated placement is not exported as a static one") { ExportPlan.make(state) }
    }

    @Test fun placementGeometryMatchesTheSwiftCompositor() {
        val canvas = CGSize(1920.0, 1080.0)
        // Picture in picture: a 0.32 square window in the bottom-right corner, Fill.
        val pip = VideoPlacement.layout(3, 1, 2)
        val placed = TrackRenderer.placed(pip, canvas)
        val window = pip.window(canvas)
        require(abs(placed.clip.minX - window.minX) < 1e-6 && abs(placed.clip.width - window.width) < 1e-6 && abs(placed.clip.height - window.height) < 1e-6,
            "A Fill window shows exactly its window")
        val corner = CGPoint(placed.source.minX, placed.source.minY).applying(placed.transform)
        require(abs(corner.x - placed.clip.minX) < 1e-6 && abs(corner.y - placed.clip.minY) < 1e-6, "The source rect lands on the clip")
        require(abs(placed.source.width / placed.source.height - window.width / window.height) < 1e-6 && placed.source.height <= canvas.height + 1e-6,
            "Fill crops the canvas picture to the window's shape")
        // An edge crop clips without moving or scaling the picture.
        val cropped = pip.crop(PlacementCropEdge.left, window.minX / canvas.width + window.width / canvas.width * 0.25, canvas)
        val clipped = TrackRenderer.placed(cropped, canvas)
        require(clipped.transform == placed.transform && abs(clipped.clip.width - placed.clip.width * 0.75) < 1e-6 && abs(clipped.clip.maxX - placed.clip.maxX) < 1e-6,
            "An edge crop only hides the cropped margin")
        // Fit keeps the whole picture inside a window of another shape.
        val fit = VideoPlacement(x = 0.25, width = 0.5, fill = false)
        val fitted = TrackRenderer.placed(fit, canvas)
        require(abs(fitted.clip.width - 960.0) < 1e-6 && abs(fitted.clip.height - 540.0) < 1e-6 && abs(fitted.source.width - 1920.0) < 1e-6,
            "Fit shows the whole canvas picture inside the window")
        // A window partly off the canvas is clipped by the canvas.
        val off = TrackRenderer.placed(VideoPlacement(x = 1.0, width = 0.5, height = 0.5), canvas)
        require(abs(off.clip.maxX - 1920.0) < 1e-6 && abs(off.clip.width - 480.0) < 1e-6, "The canvas clips a window")
        require(!TrackRenderer.placed(VideoPlacement(x = 2.0, width = 0.2, height = 0.2), canvas).isVisible, "A window off the canvas shows nothing")
        // GL matrix: the cropped source's NDC corners land on the clip's NDC corners (y up).
        val m = PlacementTransformation.ndcMatrix(placed.clip, canvas)
        fun map(u: Float, v: Float) = Pair(m[0] * u + m[1] * v + m[2], m[3] * u + m[4] * v + m[5])
        val (left, top) = map(-1f, 1f)
        val (right, bottom) = map(1f, -1f)
        require(abs(left - (2 * placed.clip.minX / canvas.width - 1)) < 1e-5 && abs(right - (2 * placed.clip.maxX / canvas.width - 1)) < 1e-5 &&
            abs(top - (1 - 2 * placed.clip.minY / canvas.height)) < 1e-5 && abs(bottom - (1 - 2 * placed.clip.maxY / canvas.height)) < 1e-5,
            "The placement matrix maps the crop onto the visible rect")
        val bounds = PlacementTransformation.cropBounds(placed.source, canvas)
        require(bounds[0] < bounds[1] && bounds[2] < bounds[3] && bounds.all { it in -1f..1f }, "The Media3 crop is a valid NDC rect")
    }

    @Test fun previewSegmentsCarryTheSameStack() {
        val state = overlapped()
        val ids = state.timeline.compile().first { it.video.size == 2 }.video
        val pip = VideoPlacement.layout(3, 1, 2)
        state.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(), videos = mapOf(ids[0] to VideoPlacement(order = 0), ids[1] to pip)))
        val segments = PreviewPlan.segments(state.timeline)
        val plan = ExportPlan.make(state)
        require(segments.all { it.layers.size == plan.layers.size }, "The preview has one player per export lane")
        val inside = segments.first { it.start <= 3.0 && 3.0 < it.end }
        require(inside.video?.source == b.id && inside.video.placement == pip && inside.layers[0]?.source == a.id, "The preview stacks the overlap like the export")
        require(segments.filter { it.end <= 2.0 || it.start >= 4.0 }.all { it.layers.all { layer -> layer == null } }, "No picture behind outside the overlap")
        for (segment in segments) for ((lane, piece) in segment.pictures.withIndex()) {
            val exported = plan.pictures[lane].first { EditTime.of(segment.start) >= it.start && EditTime.of(segment.start) < it.end }
            require((piece == null) == exported.isGap && (piece == null || (exported.clip().sourceID == piece.source && exported.clip().placement == piece.placement)),
                "Preview and export show the same picture in every lane")
        }
    }

    /** Swift `renderTracks`: a transition's pair is one unplaced layer at its front participant's slot; a placed overlay stays in its window. */
    @Test fun transitionsAndPlacementsShareTheStack() {
        val base = edit(clip(a, 0.0, 4.0), clip(b, 0.0, 3.0)).settingTransition(ClipTransition(duration = 1.0), 1)
        require(base.toolRegions.none { it.tool == VideoTool.placement }, "A plain transition does not advertise a default Placement effect")
        val window = base.timeline.transitionWindows().single()
        val plain = ExportPlan.make(base)
        require(plain.layers.isEmpty() && plain.video.any { (it as? ExportPlan.Piece.Clip)?.transition == window.id }, "A transition alone stays one lane")
        // A picture-in-picture over the dissolve.
        val state = base.copy()
        val overlay = TimelineClip(sourceID = b.id, start = 0.0, end = 2.0)
        state.timeline.video = state.timeline.video + VideoTimelineItem(overlay.id, window.interval.start - et(0.5), overlay, 99)
        val inside = window.interval.start + EditTime.of(window.interval.duration.seconds / 2)
        val interval = state.timeline.compile().first { it.interval.contains(inside) }
        state.timeline.placements = listOf(VideoPlacementRegion(participants = interval.video.toSet(),
            videos = interval.video.associateWith { if (it == overlay.id) VideoPlacement.layout(3, 1, 2).copy(order = 5) else VideoPlacement() }))
        val plan = ExportPlan.make(state)
        require(plan.layers.size == 1, "The overlay adds one lane over the transition")
        val front = plan.video.first { it.start <= inside && inside < it.end } as ExportPlan.Piece.Clip
        val back = plan.layers[0].first { it.start <= inside && inside < it.end } as ExportPlan.Piece.Clip
        require(front.sourceID == b.id && front.placement != null && back.transition == window.id && back.placement == null,
            "The placed overlay is drawn over the unplaced transition clip")
        require(abs(back.sourceStart - (back.start - window.interval.start).seconds) < 1e-6, "The transition clip keeps its own clock inside the window")
        requireTiled(plan.video, plan.duration, "Front lane")
        requireTiled(plan.layers[0], plan.duration, "Back lane")
        val transitionPieces = plan.layers[0].filterIsInstance<ExportPlan.Piece.Clip>().filter { it.transition == window.id }
        require(transitionPieces.sumOf { it.duration.ticks } == window.interval.duration.ticks &&
            transitionPieces.all { abs(it.sourceStart - (it.start - window.interval.start).seconds) < 1e-6 }, "The whole window plays its clip on the window's clock")
        val segments = PreviewPlan.segments(state.timeline)
        val shown = segments.first { it.start <= inside.seconds && inside.seconds < it.end }
        require(shown.video?.source == b.id && shown.layers[0]?.transition?.id == window.id, "The preview stacks the same layers")
    }
}
