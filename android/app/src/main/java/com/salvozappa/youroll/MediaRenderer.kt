// Port of YouRec/MediaRenderer.swift (basic export path) and the parts of
// YouRec/ExportCheckpoint.swift and YouRec/TrackRenderer.swift (audio lanes)
// that a straight-cut timeline needs.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS builds an AVMutableComposition from the compiled TrackTimeline and encodes
// it with AVAssetExportSession. YouRoll builds a Media3 Transformer `Composition`
// from the same timeline (see ExportPlan below):
//
// - Picture: one video sequence per depth of the picture stack. Each compiled
//   interval shows its front-most picture (the iOS compositor draws the stack
//   back to front; without a placement the front picture covers the canvas),
//   and an interval with no picture is a sequence gap, which Transformer
//   renders as black. While placed videos overlap (picture-in-picture, side by
//   side, edge crops) the pictures behind the front one go into further
//   sequences, each placed in its window and stacked by DefaultVideoCompositor
//   (TrackRenderer.kt: `placed`, PlacementTransformation,
//   PlacementCompositorSettings). Every clip is fitted into the project canvas
//   (Presentation LAYOUT_SCALE_TO_FIT = iOS aspect fit), or a cropped clip
//   fills it through its CropSettings transform (CropTransformation), and
//   flipped by its Mirror (MirrorTransformation) and limited to 30 fps. Each
//   picture's image overlays and titles are drawn over its framed canvas by a
//   full-canvas bitmap overlay (`TitleOverlay`, VideoToolCompositor.kt), the
//   same frames the editor preview draws, before it is placed.
// - Pauses: a clip's pause cuts are part of its source clock (keptIntervals),
//   so its picture and sound are the runs between the cuts.
// - Sound: Transformer mixes every sequence of a Composition, but a sequence
//   plays its items one after another. Overlapping audio items therefore go in
//   separate audio-only sequences ("lanes", packed first-fit by start time like
//   TrackRenderer.audioMix), each item placed at its time with gaps. Item volume,
//   user fades, the 8 ms de-click ramps and the censors of CutAudioEnvelope are
//   applied per piece by AudioEnvelopeProcessor (CutAudioEnvelope.kt).
// - Output: H.264 + AAC MP4 at the project canvas, written to Processing/ and
//   published into Exports/ only after it was verified. Earlier exports and
//   sources are never touched.
//
// - Speed (Swift: `scaleTimeRange` on the composition track and the spectral
//   time-pitch algorithm): a piece whose run plays at rate r ≠ 1 is an
//   EditedMediaItem with a constant SpeedProvider and `shouldMaintainPitch`.
//   Transformer then retimes the picture through its SpeedChangingMediaSource
//   (slow motion repeats source frames, no frames are generated) and the sound
//   through SpeedChangingAudioProcessor (Sonic, pitch kept). The editor preview
//   uses the same media source for the picture and the same Sonic algorithm for
//   the sound (EditorController.kt, PreviewAudio.kt).
// - Freeze frames (Swift: one 1/60000 s source range scaled to the hold): the
//   held source frame plays from its hold clip (VideoEffectSampling.kt: the
//   frame repeated at 30 fps), so the Freeze Frame push-in and Print grain
//   animate; without a hold clip it is clipped as a one-frame item and slowed
//   down to last the hold ([HoldFrames] finds that frame's exact timestamps).
//   Holds are silent.
// - Video effects (Swift `MetalEffectRenderer` in the compositor): a picture
//   piece of a clip with effects gets a `VideoEffectsGlEffect` after its
//   framing and Mirror, before its overlays (MetalVideoEffects.kt). The
//   preview and transition clips run the same renderer.
// - Transitions (Swift: TransitionRenderer/VideoToolCompositor blend two layers
//   per frame): each transition window is one piece played from its rendered
//   transition clip (TransitionRenderer.kt), the same file the preview plays.
//   Outside the windows a transition's footprint shows the participant its
//   progress selects. Sound: each item's envelope carries the transition's
//   `inRamp`/`outRamp` (CutAudioEnvelope), so overlapping sound crossfades.
//
// - Colour (Swift `ColorLUT.apply` in `preparedImage`): the clip's grade is the
//   first picture effect (ColorGradeEffect, VideoToolCompositor.kt), before the
//   framing, so bars stay black and overlays are drawn over the graded picture.
//
// - Stabilization (Swift `preparedImage`: `correction`): a stabilized clip's
//   pieces correct the source picture after the grade and before the framing
//   (StabilizationTransformation, VideoToolCompositor.kt), each frame with
//   the correction of the source frame it shows. The plans come from the
//   cached motion analysis (VideoStabilizer.kt), measured here if missing.
//
// - Subtitles (Swift: `SubtitleRenderTrack` on every TrackVideoInstruction,
//   drawn after the tracks): one composition-wide video effect
//   (`SubtitleOverlay`, SubtitleRenderer.kt) draws the caption frames over the
//   composited picture, transition windows and gaps included, on the project clock.
//
// Not expressible here (the plan refuses them with a clear message, TODO.md):
// animated placements, video tools (animated colour, animated crop or image,
// the video effects listed by `VideoEffectSupport`), audio enhancements,
// HDR output, resumable export checkpoints and the audio peak measurement.
package com.salvozappa.youroll

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.media.metrics.LogSessionId
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// MARK: - Progress (Swift `ExportProgress`)

@ConsistentCopyVisibility
data class ExportProgress private constructor(val step: Step, val fraction: Double) {
    enum class Step(val title: String) {
        opening("Reading export settings"),
        audioEffects("Processing audio effects"),
        timeline("Building the video timeline"),
        audioPeak("Checking audio for clipping"),
        audio("Encoding audio"),
        picture("Rendering video"),
        assembleVideo("Assembling video sections"),
        assembleMovie("Combining video and audio"),
        movieIndex("Writing the movie index"),
        sourceFiles("Checking source files"),
        verifying("Verifying the exported movie"),
        saving("Saving the exported video"),
        complete("Exported");

        // Estimated shares of the whole job. Within each pass, use completed
        // media time, sections or bytes. Only a successfully published file gets 100%.
        val range: ClosedFloatingPointRange<Double>
            get() = when (this) {
                opening -> 0.0..0.0
                audioEffects, timeline -> 0.0..0.06
                audioPeak -> 0.06..0.09
                audio -> 0.09..0.14
                picture -> 0.14..0.90
                assembleVideo, assembleMovie -> 0.90..0.965
                movieIndex -> 0.965..0.97
                sourceFiles -> 0.97..0.98
                verifying -> 0.98..0.99
                saving -> 0.99..0.99
                complete -> 1.0..1.0
            }
    }

    val title: String
        get() = when {
            step == Step.opening && fraction > 0 -> "Reopening the saved export"
            step == Step.timeline && fraction > step.range.endInclusive -> "Rebuilding the video timeline"
            else -> step.title
        }

    /** Late progress callbacks must never rewind the label or the bar. */
    fun advanced(next: ExportProgress): ExportProgress {
        if (next.step.ordinal < step.ordinal) return this
        return next.copy(fraction = max(fraction, next.fraction))
    }

    companion object {
        /** Swift `ExportProgress(step, value)`. */
        operator fun invoke(step: Step, value: Double = 0.0): ExportProgress {
            val unit = if (value.isFinite()) min(1.0, max(0.0, value)) else 0.0
            return ExportProgress(step, step.range.start + unit * (step.range.endInclusive - step.range.start))
        }
    }
}

// The audio envelope (`AudioEnvelopeSegment`, `AudioEnvelopeProcessor`) is in CutAudioEnvelope.kt.

// MARK: - Export plan (pure; JVM-tested)

/**
 * How a TrackTimeline maps onto a Transformer Composition. Durations are exact
 * edit ticks; Media3 positions are derived from cumulative boundaries so that
 * microsecond rounding never accumulates over a long sequence.
 */
data class ExportPlan(
    val duration: EditTime,
    val video: List<Piece>,
    val audio: List<List<Piece>>,
    /** The transition windows whose rendered clips the video pieces play. */
    val transitions: List<TransitionWindow> = emptyList(),
    /** Swift `installTitleClocks`: one animation clock per title across cuts. */
    val titleRanges: TitleMotionRanges = TitleMotionRanges(),
    /**
     * The pictures behind [video] while placed videos overlap, nearest first.
     * Each lane spans the whole duration, with gaps where it shows nothing.
     */
    val layers: List<List<Piece>> = emptyList(),
) {
    sealed class Piece {
        abstract val start: EditTime
        abstract val duration: EditTime
        val end: EditTime get() = start + duration

        data class Gap(override val start: EditTime, override val duration: EditTime) : Piece()
        data class Clip(
            override val start: EditTime,
            override val duration: EditTime,
            val sourceID: UUID,
            /** Seconds on the source's own clock; `sourceEnd - sourceStart` is the duration times [rate]. A hold has equal ends. */
            val sourceStart: Double,
            val sourceEnd: Double,
            /** Audio pieces only. */
            val envelope: AudioEnvelopeSegment? = null,
            /** Video pieces only: the clip's static crop; null fits the picture into the canvas. */
            val crop: CropSettings? = null,
            /** A transition window (TimelineTransition id, also used as [sourceID]): plays its rendered transition clip. */
            val transition: UUID? = null,
            /** Video pieces only: the clip's titles (source-timed; drawn while the source clock is inside them). */
            val titles: List<TextOverlay> = emptyList(),
            /** Video pieces only: the clip's flips, applied after the framing (null: none). */
            val mirror: MirrorSettings? = null,
            /** Video pieces only: the clip's image overlays (source-timed), drawn before its titles. */
            val images: List<ImageOverlay> = emptyList(),
            /** Video pieces only: the picture's window while placed videos overlap; null covers the canvas. */
            val placement: VideoPlacement? = null,
            /** Video pieces only: the clip whose video effects the piece draws (null without effects). */
            val effectClip: TimelineClip? = null,
            /** Video pieces with effects: the timeline start (seconds) of the clip's item, a hold's clock origin. */
            val itemStart: Double = 0.0,
            /** Video pieces only: the clip's grade, applied to the source picture before its framing (null: none). */
            val color: ColorSettings? = null,
            /** Video pieces only: the clip's `stabilizationReference`, corrected after the grade and before the framing (null: none). */
            val stabilization: StabilizationSettings? = null,
            /**
             * Audio pieces only: `TrackRenderer.audioKey` of the enhancement chain
             * this piece plays through (its processed audio, in the source's time
             * domain at 48 kHz stereo), or null for the original sound.
             */
            val audioKey: String? = null,
        ) : Piece() {
            /** Source seconds per edit second (the clip's speed); 0 for a hold. */
            val rate: Double get() = if (duration > EditTime.zero) (sourceEnd - sourceStart) / duration.seconds else 1.0
            /** A freeze frame: one source picture held for the duration. */
            val isHold: Boolean get() = sourceStart == sourceEnd
            /** True when the piece plays at its source's own speed. */
            val isNormalSpeed: Boolean get() = !isHold && abs(rate - 1) <= rateTolerance
        }
    }

    val hasAudio: Boolean get() = audio.isNotEmpty()
    val sourceIDs: Set<UUID> get() = (video + layers.flatten() + audio.flatten()).mapNotNull { (it as? Piece.Clip)?.takeIf { clip -> clip.transition == null }?.sourceID }.toSet()
    /** Every picture sequence, front first. */
    val pictures: List<List<Piece>> get() = listOf(video) + layers

    /** True when picture lane [lane] (0 = front) shows a visible picture at [timeUs] (MediaRenderer's compositor settings). */
    fun showsPicture(lane: Int, timeUs: Long, canvas: CGSize): Boolean {
        val pieces = pictures.getOrNull(lane) ?: return false
        var low = 0
        var high = pieces.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            val piece = pieces[middle]
            when {
                timeUs < microseconds(piece.start) -> high = middle - 1
                timeUs >= microseconds(piece.end) -> low = middle + 1
                else -> return piece is Piece.Clip && (piece.placement == null || TrackRenderer.placed(piece.placement, canvas).isVisible)
            }
        }
        return false
    }

    class Unsupported(message: String) : Exception(message)

    companion object {
        private const val sourceTolerance = 0.00001
        internal const val rateTolerance = 0.0005
        private const val declick = 0.008

        fun microseconds(time: EditTime): Long = time.ticks * 1_000_000L / EditTime.scale
        fun microseconds(seconds: Double): Long = Math.round(seconds * 1_000_000.0)

        fun make(edit: EditState): ExportPlan {
            val timeline = edit.timeline
            val duration = timeline.duration
            if (duration <= EditTime.zero) throw ProjectError.emptyTimeline
            refuseUnsupported(timeline)
            val pictures = videoLayers(timeline, duration)
            return ExportPlan(duration, pictures[0], audioLanes(timeline, duration),
                transitions = timeline.transitionWindows(), titleRanges = TitleMotionRanges(edit), layers = pictures.drop(1))
        }

        private fun refuseUnsupported(timeline: TrackTimeline) {
            if (timeline.placements.any { region -> region.videos.values.any { it.motion != null && it.motion !is kotlinx.serialization.json.JsonNull } })
                throw Unsupported("Animated placements can’t be exported in YouRoll yet.")
            for (item in timeline.video) {
                val clip = item.content
                // Swift `MetalEffectRenderer`: effects that need masks, depth or offline analysis are not ported.
                clip.videoEffects.orEmpty().firstNotNullOfOrNull { VideoEffectSupport.unsupported(it) }?.let { throw Unsupported(it) }
                val tools = clip.color?.isAnimated == true ||
                    clip.images.orEmpty().any { it.motion != null && it.motion !is kotlinx.serialization.json.JsonNull } ||
                    (clip.crop?.motion != null && clip.crop.motion !is kotlinx.serialization.json.JsonNull)
                if (tools) throw Unsupported("This project uses video tools that YouRoll can’t export yet (animated colour, crop or image).")
            }
            for (item in timeline.audio) for (region in item.enhancements) {
                // Swift `OfflineAudioPipeline.process`: an unavailable model is a render error, never substituted.
                OfflineAudioPipeline.unavailable(region.effects)?.let { throw Unsupported(it.message ?: "") }
            }
        }

        /** One picture sequence: the front lane of [videoLayers]. */
        internal fun videoPieces(timeline: TrackTimeline, duration: EditTime): List<Piece> = videoLayers(timeline, duration)[0]

        /**
         * The picture sequences, front first: lane n holds layer n of each
         * compiled interval's stack (TrackRenderer.stack), with its placement,
         * and a gap where the stack is shallower. Lane 0 is black where there is
         * no picture at all. A transition window is one layer that plays its
         * rendered transition clip; outside the windows a transition's footprint
         * shows the participant its progress selects (`pictureAt`).
         */
        internal fun videoLayers(timeline: TrackTimeline, duration: EditTime): List<List<Piece>> {
            val windows = timeline.transitionWindows()
            val compiled = timeline.compile().map { it to TrackRenderer.stack(timeline, it, windows) }
            val depth = max(1, compiled.maxOfOrNull { it.second.size } ?: 1)
            val lanes = List(depth) { mutableListOf<Piece>() }
            fun add(pieces: MutableList<Piece>, piece: Piece) {
                val last = pieces.lastOrNull()
                when {
                    piece is Piece.Gap && last is Piece.Gap -> pieces[pieces.size - 1] = Piece.Gap(last.start, last.duration + piece.duration)
                    piece is Piece.Clip && last is Piece.Clip && last.end == piece.start && last.sourceID == piece.sourceID && last.crop == piece.crop &&
                        last.titles == piece.titles && last.placement == piece.placement && last.transition == piece.transition &&
                        last.mirror == piece.mirror && last.images == piece.images && last.color == piece.color &&
                        last.effectClip == piece.effectClip && last.itemStart == piece.itemStart &&
                        last.stabilization == piece.stabilization &&
                        !last.isHold && !piece.isHold && abs(last.rate - piece.rate) < rateTolerance &&
                        abs(last.sourceEnd - piece.sourceStart) < sourceTolerance ->
                        pieces[pieces.size - 1] = last.copy(duration = last.duration + piece.duration, sourceEnd = piece.sourceEnd)
                    else -> pieces += piece
                }
            }
            var covered = EditTime.zero
            for ((interval, stack) in compiled) {
                if (interval.interval.start > covered) for (lane in lanes) add(lane, Piece.Gap(covered, interval.interval.start - covered))
                for ((depthIndex, lane) in lanes.withIndex()) {
                    val layer = stack.getOrNull(depthIndex)
                    val window = layer?.window
                    val item = layer?.item
                    if (window != null) {
                        // The rendered transition clip starts at the window's start.
                        add(lane, Piece.Clip(interval.interval.start, interval.interval.duration, window.id,
                            (interval.interval.start - window.interval.start).seconds, (interval.interval.end - window.interval.start).seconds, transition = window.id))
                        continue
                    }
                    if (item == null) { add(lane, Piece.Gap(interval.interval.start, interval.interval.duration)); continue }
                    var cursor = interval.interval.start
                    val local = EditInterval(interval.interval.start - item.start, interval.interval.end - item.start)
                    for (run in item.mapping.sliced(local).runs) {
                        add(lane, Piece.Clip(cursor, run.duration, item.content.sourceID, run.sourceStart, run.sourceEnd, crop = item.content.crop,
                            titles = item.content.texts.orEmpty(), placement = layer.placement,
                            mirror = item.content.mirror?.takeIf { it.horizontal || it.vertical }, images = item.content.images.orEmpty(),
                            color = item.content.color?.takeIf { !it.isIdentity },
                            effectClip = item.content.takeIf { it.hasVideoEffects }, itemStart = if (item.content.hasVideoEffects) item.start.seconds else 0.0,
                            stabilization = item.content.stabilizationReference))
                        cursor += run.duration
                    }
                }
                covered = interval.interval.end
            }
            if (covered < duration) for (lane in lanes) add(lane, Piece.Gap(covered, duration - covered))
            return lanes
        }

        /**
         * The audio lanes the editor preview mixes (PreviewAudio.kt): the same
         * lanes and envelopes as the export, but never refused, and a split
         * take's pieces stay separate items, each with its own owner, so a
         * volume audition can target one of them.
         */
        fun previewAudio(timeline: TrackTimeline): List<List<Piece>> =
            if (timeline.duration <= EditTime.zero) emptyList() else audioLanes(timeline, timeline.duration, preview = true)

        /**
         * Audio items in non-overlapping lanes (first fit by start time), each lane padded with gaps to the full duration.
         * [preview] keeps one piece per item (see [previewAudio]); the gain is the same.
         */
        internal fun audioLanes(timeline: TrackTimeline, duration: EditTime, preview: Boolean = false): List<List<Piece>> {
            val lanes = mutableListOf<MutableList<Piece>>()
            val ends = mutableListOf<EditTime>()
            val ordered = timeline.audio.withIndex().sortedWith { a, b ->
                if (a.value.start == b.value.start) a.index.compareTo(b.index) else a.value.start.compareTo(b.value.start)
            }.map { it.value }
            fun rate(run: PlaybackRun) = if (run.duration > EditTime.zero) (run.sourceEnd - run.sourceStart) / run.duration.seconds else 1.0
            for (item in ordered) {
                if (item.muted || item.gain <= 0.0) continue
                // TrackRenderer.audioMix: an enhancement region's sound plays from its
                // processed audio ([Piece.Clip.audioKey]), so pieces also break at region edges.
                val edges = (listOf(EditTime.zero, item.mapping.duration) + item.enhancements.flatMap { listOf(it.interval.start, it.interval.end) })
                    .filter { it >= EditTime.zero && it <= item.mapping.duration }.distinct().sorted()
                val parts = edges.zipWithNext().filter { it.second > it.first }.flatMap { (a, b) ->
                    val region = item.enhancements.firstOrNull { it.interval.contains(a) }?.takeIf { region -> region.effects.any { it.enabled } }
                    val key = region?.let { TrackRenderer.audioKey(item.sourceID, it.effects) }
                    item.mapping.sliced(EditInterval(a, b)).runs.map { it to key }
                }
                // Runs that continue the same source (adjacent pieces of a split) are one envelope segment.
                val clips = mutableListOf<Piece.Clip>()
                var cursor = item.start
                var previous: PlaybackRun? = null
                for ((run, key) in parts) {
                    val runStart = cursor
                    cursor += run.duration
                    if (run.silent || run.isHold) { previous = null; continue }
                    val last = clips.lastOrNull()
                    val continuous = last != null && previous != null && last.end == runStart && abs(previous.sourceEnd - run.sourceStart) < sourceTolerance &&
                        abs(rate(previous) - rate(run)) < rateTolerance && last.audioKey == key
                    previous = run
                    if (continuous && last != null) {
                        clips[clips.size - 1] = last.copy(duration = last.duration + run.duration, sourceEnd = run.sourceEnd)
                    } else {
                        clips += Piece.Clip(runStart, run.duration, item.sourceID, run.sourceStart, run.sourceEnd, audioKey = key)
                    }
                }
                if (clips.isEmpty()) continue
                // TrackRenderer.audioMix: sound following a transition's picture fades with it.
                fun ramp(relation: TimelineTransition?, incoming: Boolean): ClosedFloatingPointRange<Double>? {
                    relation ?: return null
                    val timing = TransitionTiming.of(relation)
                    val range = if (incoming) timing.audioIn else timing.audioOut
                    return (relation.origin.seconds + range.start)..(relation.origin.seconds + range.endInclusive)
                }
                val follows = item.followsVideo
                val inRamp = ramp(timeline.transitions.firstOrNull { follows != null && it.incoming == follows }, true)
                val outRamp = ramp(timeline.transitions.firstOrNull { follows != null && it.outgoing == follows }, false)
                // TrackRenderer.audioMix: every piece of an item carries the item's censors (AudioCensor.kt).
                val censors = timeline.censorSpans(item)
                val placed = clips.map { clip ->
                    clip.copy(envelope = AudioEnvelopeSegment(
                        start = clip.start.seconds, end = clip.end.seconds, fadeIn = declick, fadeOut = declick,
                        clipStart = item.start.seconds, clipEnd = item.end.seconds,
                        userFadeIn = item.fadeIn, userFadeOut = item.fadeOut, volume = item.gain, owner = item.id,
                        inRamp = inRamp, outRamp = outRamp, censors = censors))
                }
                val first = placed.first()
                fun continues(lane: Int): Boolean {
                    val last = lanes[lane].lastOrNull() as? Piece.Clip ?: return false
                    return last.end == first.start && last.sourceID == first.sourceID && last.audioKey == first.audioKey && abs(last.sourceEnd - first.sourceStart) < sourceTolerance &&
                        abs(last.rate - first.rate) < rateTolerance && last.envelope?.inRamp == inRamp && last.envelope?.outRamp == outRamp
                }
                // Like TrackRenderer, keep a split take on the lane it continues.
                val lane = ends.indices.firstOrNull { continues(it) } ?: ends.indices.firstOrNull { ends[it] <= first.start } ?: run {
                    lanes.add(mutableListOf()); ends.add(EditTime.zero); lanes.size - 1
                }
                for ((index, clip) in placed.withIndex()) {
                    if (index == 0 && continues(lane)) {
                        // Continuous sound across an edit point: no de-click dip. One media item when the gain is the same.
                        val last = lanes[lane].last() as Piece.Clip
                        val before = last.envelope!!
                        val after = clip.envelope!!
                        if (!preview && before.volume == after.volume && before.userFadeOut == null && after.userFadeIn == null) {
                            lanes[lane][lanes[lane].size - 1] = last.copy(duration = last.duration + clip.duration, sourceEnd = clip.sourceEnd,
                                envelope = before.copy(end = after.end, fadeOut = after.fadeOut, clipEnd = after.clipEnd, userFadeOut = after.userFadeOut,
                                    censors = (before.censors + after.censors).distinct()))
                        } else {
                            lanes[lane][lanes[lane].size - 1] = last.copy(envelope = before.copy(fadeOut = 0.0))
                            lanes[lane] += clip.copy(envelope = after.copy(fadeIn = 0.0))
                        }
                        ends[lane] = clip.end
                        continue
                    }
                    if (clip.start > ends[lane]) lanes[lane] += Piece.Gap(ends[lane], clip.start - ends[lane])
                    lanes[lane] += clip
                    ends[lane] = clip.end
                }
            }
            for (index in lanes.indices) if (ends[index] < duration) lanes[index] += Piece.Gap(ends[index], duration - ends[index])
            return lanes
        }
    }
}

// MARK: - Exports folder (receipts; pure file logic, JVM-tested)

/**
 * The `Exports/` folder of a project: `<UUID>.mp4` movies, each published
 * together with a `<UUID>.json` ExportReceipt. iOS keeps this logic in
 * EditorController (`refreshExports`, `matchingExport`) and MediaRenderer.render.
 */
object ExportLibrary {
    fun directory(folder: File) = File(folder, "Exports")

    /** Swift `refreshExports`: readable receipts whose movie exists, newest first. */
    fun receipts(folder: File): List<ExportReceipt> {
        val directory = directory(folder)
        val files = directory.listFiles()?.toList() ?: emptyList()
        return files.filter { it.extension == "json" }.mapNotNull { file ->
            val receipt = runCatching { SwiftJSON.decodeFromString(ExportReceipt.serializer(), file.readText()) }.getOrNull() ?: return@mapNotNull null
            if (receipt.filename != File(receipt.filename).name || !File(directory, receipt.filename).exists()) return@mapNotNull null
            receipt
        }.sortedByDescending { it.createdAt }
    }

    /** The settings an Android export is rendered with. Other formats are a TODO; see MovieExportPanel. */
    fun effectiveSettings(@Suppress("UNUSED_PARAMETER") project: Project) = ExportSettings()

    /** Swift `matchingExport`: an export of exactly this edit, settings and sources. */
    fun matching(project: Project, receipts: List<ExportReceipt>, folder: File): ExportReceipt? {
        val edit = project.edit
        val ids = edit.clips.map { it.sourceID }.toSet() + edit.timeline.audio.map { it.sourceID }
        val sources = project.sources.filter { it.id in ids }
        val settings = effectiveSettings(project)
        return receipts.firstOrNull { receipt ->
            (receipt.settings ?: ExportSettings()) == settings && receipt.edit == edit &&
                receipt.sources.filter { it.id in ids } == sources && File(directory(folder), receipt.filename).exists()
        }
    }

    fun encode(receipt: ExportReceipt): ByteArray =
        SwiftJSON.encodeToJsonElement(ExportReceipt.serializer(), receipt).sortedKeys().encodedBytes()

    /**
     * Publish a finished movie. The receipt goes first: a crash may leave an
     * ignored receipt, but never a completed video without its reproducible
     * edit snapshot. Never replaces an existing file.
     */
    fun publish(partial: File, receipt: ExportReceipt, destination: File) {
        val receiptFile = File(destination.parentFile, destination.nameWithoutExtension + ".json")
        if (destination.exists() || receiptFile.exists()) throw IOException("An export with this name already exists.")
        Files.createDirectories(destination.parentFile.toPath())
        val receiptPartial = File(partial.parentFile, newUUID().uuidString + ".json")
        try {
            receiptPartial.writeBytes(encode(receipt))
            RecoveryFile.syncFile(receiptPartial)
            Files.move(receiptPartial.toPath(), receiptFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            receiptPartial.delete()
        }
        try {
            RecoveryFile.syncFile(partial)
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            receiptFile.delete(); throw failure
        }
        runCatching { RecoveryFile.syncDirectory(destination.parentFile) }
    }
}

// MARK: - Renderer

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object MediaRenderer {
    /** Swift `canvas(project:folder:)`: the edit's output size, else the reference picture's oriented size rounded to even. */
    fun canvas(project: Project, folder: File): CGSize {
        val edit = project.edit
        edit.outputSize?.let { return it }
        val reference = edit.canvasSourceID ?: return CGSize(1920.0, 1080.0)
        val source = project.sources.firstOrNull { it.id == reference } ?: throw ProjectError.invalidMedia
        val info = VideoGeometry.read(File(folder, source.filename)) ?: throw ProjectError.invalidMedia
        fun even(value: Int) = Math.round(value / 2.0) * 2.0
        return CGSize(even(info.orientedWidth), even(info.orientedHeight))
    }

    /**
     * Render [project] (a fixed snapshot: later edits cannot affect it) to
     * [destination] and return its receipt. The movie is written to
     * `Processing/` and moved into place only after it was verified.
     */
    suspend fun render(context: Context, project: Project, folder: File, destination: File,
                       progress: (ExportProgress) -> Unit): ExportReceipt {
        val receiptFile = File(destination.parentFile, destination.nameWithoutExtension + ".json")
        if (destination.exists() || receiptFile.exists()) throw IOException("An export with this name already exists.")
        val settings = ExportLibrary.effectiveSettings(project)
        TitleTypeface.install(context)
        progress(ExportProgress(ExportProgress.Step.opening))
        val edit = project.edit
        val plan = ExportPlan.make(edit)
        val canvas = withContext(Dispatchers.IO) { canvas(project, folder) }
        val width = canvas.width.toInt()
        val height = canvas.height.toInt()
        val files = HashMap<UUID, File>()
        for (id in plan.sourceIDs) {
            val source = project.sources.firstOrNull { it.id == id } ?: throw ProjectError.missingMedia
            val file = File(folder, source.filename)
            if (!file.exists()) throw ProjectError.missingMedia
            files[id] = file
        }
        // Swift `MediaRenderer.timeline` throws `missingImage` for an overlay whose file is gone.
        withContext(Dispatchers.IO) {
            // Swift `render`: every LUT payload of the edit must load.
            for (clip in edit.clips) for (lut in clip.color?.lutAssets.orEmpty()) LUTAssets.load(lut, folder)
            val images = plan.pictures.flatten().flatMap { (it as? ExportPlan.Piece.Clip)?.images.orEmpty() } +
                plan.transitions.flatMap { listOfNotNull(it.first, it.second) }.flatMap { it.images }
            if (images.any { ImageBitmaps.image(folder, it.filename) == null }) throw ProjectError.missingImage
        }
        // Swift `SourceMediaCache.preparedTimeline`: each enhanced source and chain is
        // processed once in the source's time domain, the same cached file the preview plays.
        val audioFiles = HashMap<String, File>()
        val requests = TrackRenderer.enhancementRequests(project, plan.audio.flatten())
        if (requests.isNotEmpty()) progress(ExportProgress(ExportProgress.Step.audioEffects))
        for ((index, entry) in requests.entries.sortedBy { it.key }.withIndex()) {
            val (source, effects) = entry.value
            val processed = SourceMediaCache.processedAudio(source, folder, effects) { value ->
                progress(ExportProgress(ExportProgress.Step.audioEffects, 0.85 * (index + value) / requests.size))
            }
            if (processed != null) audioFiles[entry.key] = processed.first
        }
        progress(ExportProgress(ExportProgress.Step.timeline))
        // Transition windows play their rendered clips, shared with the preview (cached per window).
        val jobs = TransitionRenderJob.all(edit.timeline, project.sources, folder, width, height, settings.frameRate.rawValue)
        if (jobs.size != plan.transitions.size) throw ProjectError.missingMedia
        for ((index, job) in jobs.withIndex()) {
            progress(ExportProgress(ExportProgress.Step.timeline, index.toDouble() / jobs.size))
            files[job.window.id] = TransitionRenderer.render(job, folder)
        }
        // Swift `MediaRenderer.timeline`: every stabilized take's plan (its analysis is measured once and cached).
        val references = plan.pictures.flatten().filterIsInstance<ExportPlan.Piece.Clip>()
            .mapNotNull { piece -> piece.stabilization?.let { piece.sourceID to it } }.distinct()
        val corrections = HashMap<Pair<UUID, StabilizationSettings>, StabilizationCorrection>()
        for ((index, reference) in references.withIndex()) {
            val source = project.sources.firstOrNull { it.id == reference.first } ?: throw ProjectError.missingMedia
            VideoStabilizer.correction(source, reference.second, folder) { value ->
                progress(ExportProgress(ExportProgress.Step.timeline, (index + value) / references.size))
            }?.let { corrections[reference] = it }
        }
        // Held pictures play from their hold clips, shared with the preview (VideoEffectSampling.kt).
        val holdClips = HashMap<HoldFrames.Key, File>()
        for (clip in VideoEffectHoldClip.all(edit.timeline, project.sources, folder)) {
            if (plan.pictures.flatten().none { it is ExportPlan.Piece.Clip && it.isHold && it.transition == null && it.sourceID == clip.sourceID && it.sourceStart == clip.sourceTime }) continue
            holdClips[HoldFrames.Key(clip.sourceID, clip.sourceTime)] = VideoEffectHoldRenderer.render(clip, folder)
        }
        val rates = withContext(Dispatchers.IO) { files.mapValues { VideoGeometry.frameRate(it.value) } }
        val holds = withContext(Dispatchers.IO) {
            plan.pictures.flatten().filterIsInstance<ExportPlan.Piece.Clip>().filter { it.isHold && it.transition == null && HoldFrames.Key(it.sourceID, it.sourceStart) !in holdClips }.associate { piece ->
                HoldFrames.Key(piece.sourceID, piece.sourceStart) to (HoldFrames.read(files.getValue(piece.sourceID), piece.sourceStart) ?: throw ProjectError.invalidMedia)
            }
        }
        // Swift `TrackRenderer`: one caption track shared by every instruction.
        val captions = edit.timeline.subtitles?.let { SubtitleRenderTrack(edit.timeline.subtitleCues, it, folder) }?.takeUnless { it.isEmpty }
        val composition = composition(plan, files, width, height, settings.frameRate.rawValue, rates, holds, folder, holdClips, corrections, audioFiles, captions)
        android.util.Log.i("MediaRenderer", "Export canvas ${width}×$height, ${plan.video.count { (it as? ExportPlan.Piece.Clip)?.crop != null }} cropped picture pieces, ${plan.pictures.size} picture lanes")
        val scratch = File(folder, "Processing")
        val partial = File(scratch, newUUID().uuidString + ".mp4")
        try {
            withContext(Dispatchers.IO) { Files.createDirectories(scratch.toPath()) }
            progress(ExportProgress(ExportProgress.Step.picture))
            encode(context, composition, partial) { progress(ExportProgress(ExportProgress.Step.picture, it)) }
            val ids = edit.clips.map { it.sourceID }.toSet() + edit.timeline.audio.map { it.sourceID }
            val usedSources = project.sources.filter { it.id in ids }
            val checksums = HashMap<String, String>()
            for ((index, source) in usedSources.withIndex()) {
                progress(ExportProgress(ExportProgress.Step.sourceFiles, index.toDouble() / usedSources.size))
                checksums[source.id.uuidString] = withContext(Dispatchers.IO) { ProjectStore.fingerprint(File(folder, source.filename)) }
            }
            progress(ExportProgress(ExportProgress.Step.verifying))
            withContext(Dispatchers.IO) { verify(partial, plan, settings.frameRate.rawValue) }
            progress(ExportProgress(ExportProgress.Step.saving))
            val receipt = ExportReceipt(filename = destination.name, projectID = project.id, projectTitle = project.title,
                historyRevision = project.historyCursor, edit = edit, sources = usedSources, sourceChecksums = checksums,
                width = width, height = height, framesPerSecond = settings.frameRate.rawValue, settings = settings,
                codec = settings.outputCodec(false), audioPeak = null)
            withContext(Dispatchers.IO) { ExportLibrary.publish(partial, receipt, destination) }
            progress(ExportProgress(ExportProgress.Step.complete))
            return receipt
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { partial.delete() }
        }
    }

    /** The source span of [piece] (a hold: its one picture, [hold]). */
    private fun clipItem(file: File, piece: ExportPlan.Piece.Clip, hold: HoldFrames.Window? = null): MediaItem {
        val start = hold?.startUs ?: ExportPlan.microseconds(piece.sourceStart)
        // Length from the exact edit clock (times the speed), so the sequence keeps its cumulative timing.
        val length = hold?.let { it.endUs - it.startUs } ?: Math.round(gapLength(piece) * piece.rate)
        return MediaItem.Builder().setUri(Uri.fromFile(file))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(start)
                .setEndPositionUs(start + length)
                .build())
            .build()
    }

    private fun gapLength(piece: ExportPlan.Piece) = ExportPlan.microseconds(piece.end) - ExportPlan.microseconds(piece.start)

    /**
     * Media seconds per presentation second of [piece], or null at normal
     * speed. A hold slows its one picture down to the hold's length.
     */
    internal fun speed(piece: ExportPlan.Piece.Clip, hold: HoldFrames.Window? = null): Float? = when {
        hold != null -> ((hold.endUs - hold.startUs).toDouble() / max(1L, gapLength(piece))).toFloat()
        piece.isNormalSpeed -> null
        else -> piece.rate.toFloat()
    }

    /** Swift's spectral time-pitch: Sonic time stretching that keeps the pitch. */
    private fun EditedMediaItem.Builder.speed(value: Float?): EditedMediaItem.Builder =
        if (value == null) this else setSpeed(SpeedParameters(ConstantSpeed(value), true))

    /**
     * Picture effects for one source: fit into the canvas, and drop frames from
     * sources faster than the export rate (60 → 30 fps). The simple frame
     * dropper keeps every n-th frame; Media3's default dropper fails on the
     * emulator's GL (0x506 in DefaultFrameDroppingShaderProgram). Sources at or
     * below the export rate keep their own frame timing; iOS instead renders a
     * constant 30 fps clock, duplicating frames of slower sources (TODO.md).
     */
    private fun pictureEffects(width: Int, height: Int, fps: Int, sourceRate: Float?, crop: CropSettings?, titles: TitleOverlay? = null,
                               placement: List<androidx.media3.common.Effect> = emptyList(), mirror: MirrorSettings? = null,
                               color: ColorGrade? = null, videoEffects: VideoEffectsGlEffect? = null,
                               stabilization: StabilizationTransformation? = null): Effects {
        // Swift `timeline`: a cropped clip uses `crop.transform`, others are aspect-fit with bars.
        val framing = if (crop != null) CropTransformation(crop, CGSize(width.toDouble(), height.toDouble()))
            else Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        // Swift `preparedImage`: the source picture is graded first, then framed over black, so bars stay black.
        val effects = mutableListOf<androidx.media3.common.Effect>()
        if (color != null) effects += ColorGradeEffect(color)
        // Swift `preparedImage`: the camera correction acts on the source picture, before canvas fitting (SPEC: "applying stabilization before canvas fitting").
        if (stabilization != null) effects += stabilization
        effects += framing
        // Swift: `transform.concatenating(mirror.transform(canvas:))`, after the crop or fit and before the overlays.
        if (mirror != null && (mirror.horizontal || mirror.vertical)) effects += MirrorTransformation(mirror)
        // Swift `processed`: the effect chain draws on the framed picture, before overlays.
        if (videoEffects != null) effects += videoEffects
        if (sourceRate != null && sourceRate > fps + 0.5f) effects += FrameDropEffect.createSimpleFrameDropEffect(sourceRate, fps.toFloat())
        // Swift: text is composited last, after framing, so the title stays in output coordinates.
        if (titles != null) effects += androidx.media3.effect.OverlayEffect(listOf(titles))
        // Swift `TrackRenderer.placed`: the finished layer picture (titles included) moves into its window last.
        effects += placement
        return Effects(emptyList(), effects)
    }

    /**
     * Every audio piece is brought to 48 kHz stereo before mixing, like the iOS
     * mix (`AVAssetReaderAudioMixOutput` at 48 kHz, 2 channels). Otherwise the
     * mix inherits the first input's format: 8 kHz mono from the emulator's
     * AMR-NB camera audio, whose AAC priming then lengthens the movie.
     */
    private fun soundProcessors(envelope: AudioEnvelopeSegment): List<AudioProcessor> {
        val stereo = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) {
                // Mono to both sides, stereo unchanged, 5.1 by Media3's downmix; other layouts fold evenly.
                val matrix = runCatching { androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(channels, 2) }.getOrNull()
                    ?: androidx.media3.common.audio.ChannelMixingMatrix(channels, 2, FloatArray(channels * 2) { 2f / channels })
                putChannelMixingMatrix(matrix)
            }
        }
        val rate = androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
        return listOf(stereo, rate, AudioEnvelopeProcessor(envelope))
    }

    internal fun composition(plan: ExportPlan, files: Map<UUID, File>, width: Int, height: Int, fps: Int, rates: Map<UUID, Float?> = emptyMap(),
                             holds: Map<HoldFrames.Key, HoldFrames.Window> = emptyMap(), folder: File? = null,
                             holdClips: Map<HoldFrames.Key, File> = emptyMap(),
                             stabilization: Map<Pair<UUID, StabilizationSettings>, StabilizationCorrection> = emptyMap(),
                             audioFiles: Map<String, File> = emptyMap(),
                             subtitles: SubtitleRenderTrack? = null): Composition {
        val canvas = CGSize(width.toDouble(), height.toDouble())
        val bitmaps = TitleBitmaps()
        // Picture sequences first, front first: their indices are the compositor's input IDs.
        val sequences = mutableListOf<EditedMediaItemSequence>()
        for (lane in plan.pictures) {
            val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            for (piece in lane) when (piece) {
                is ExportPlan.Piece.Gap -> video.addGap(gapLength(piece))
                is ExportPlan.Piece.Clip -> {
                    val holdClip = if (piece.isHold) holdClips[HoldFrames.Key(piece.sourceID, piece.sourceStart)] else null
                    val hold = if (piece.isHold && holdClip == null) holds.getValue(HoldFrames.Key(piece.sourceID, piece.sourceStart)) else null
                    val speed = if (holdClip != null) null else speed(piece, hold)
                    // The frame dropper sees the retimed clock: 2× of a 30 fps source arrives at 60 fps.
                    val pictureRate = if (piece.isHold) null else rates[piece.sourceID]?.let { it * (speed ?: 1f) }
                    // A hold shows one source time; a run maps its clock linearly (TitleOverlay).
                    val titled = piece.titles.any { it.text.isNotEmpty() && it.start < max(piece.sourceEnd, piece.sourceStart + 0.00002) && it.end > piece.sourceStart } ||
                        piece.images.any { it.start < max(piece.sourceEnd, piece.sourceStart + 0.00002) && it.end > piece.sourceStart }
                    val image = { overlay: ImageOverlay -> folder?.let { ImageBitmaps.image(it, overlay.filename) } }
                    val overlay = if (titled) TitleOverlay(piece, canvas, plan.titleRanges, bitmaps, image) else null
                    // A window entirely off the canvas keeps its timing and is hidden by the compositor settings.
                    val placement = piece.placement?.let { PlacementTransformation.effects(it, canvas) }.orEmpty()
                    // Swift `sampleTime`: the source frame shown at a sequence time (a hold shows its one frame).
                    val correction = piece.stabilization?.let { stabilization[piece.sourceID to it] }?.let { value ->
                        StabilizationTransformation { timeUs ->
                            value.ndc(if (piece.isHold) piece.sourceStart else piece.sourceStart + (timeUs / 1_000_000.0 - piece.start.seconds) * piece.rate)
                        }
                    }
                    val effects = piece.effectClip?.let { clip ->
                        val layer = VideoEffectLayer(clip, piece.start.seconds, piece.end.seconds, piece.sourceStart, piece.rate, piece.itemStart)
                        VideoEffectsGlEffect(object : VideoEffectSource { override fun layer(presentationTimeUs: Long) = layer })
                    }
                    val item = if (holdClip != null) MediaItem.Builder().setUri(Uri.fromFile(holdClip)).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionUs(0).setEndPositionUs(gapLength(piece)).build()).build()
                        else clipItem(files.getValue(piece.sourceID), piece, hold)
                    video.addItem(EditedMediaItem.Builder(item).speed(speed)
                        .setRemoveAudio(true).setEffects(pictureEffects(width, height, fps, pictureRate, piece.crop, overlay, placement, piece.mirror,
                            ColorGrade.load(piece.color, folder), effects, correction)).build())
                }
            }
            // A sequence declared with its track types may start with a gap (no force flags needed).
            sequences += video.build()
        }
        for (lane in plan.audio) {
            val sound = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            for (piece in lane) when (piece) {
                is ExportPlan.Piece.Gap -> sound.addGap(gapLength(piece))
                // An enhanced piece reads its processed audio, which keeps the source's clock.
                is ExportPlan.Piece.Clip -> sound.addItem(EditedMediaItem.Builder(clipItem(piece.audioKey?.let { audioFiles[it] } ?: files.getValue(piece.sourceID), piece)).speed(speed(piece))
                    .setRemoveVideo(true)
                    .setEffects(Effects(soundProcessors(piece.envelope!!), emptyList())).build())
            }
            sequences += sound.build()
        }
        // H.264 is SDR: tone-map any HDR source (HDR export is a TODO).
        val builder = Composition.Builder(sequences).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (plan.layers.isNotEmpty()) builder.setVideoCompositorSettings(PlacementCompositorSettings(canvas) { lane, timeUs -> plan.showsPicture(lane, timeUs, canvas) })
        // Swift `VideoToolCompositor`: subtitles are drawn last, over the finished picture.
        if (subtitles != null && plan.pictures.isNotEmpty()) {
            builder.setEffects(Effects(emptyList(), listOf(androidx.media3.effect.OverlayEffect(listOf(SubtitleOverlay(subtitles, canvas))))))
        }
        return builder.build()
    }

    /** Swift `encode`: run the job, report 0…1 progress, cancel the job when the coroutine is cancelled. */
    private suspend fun encode(context: Context, composition: Composition, output: File, progress: (Double) -> Unit) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val main = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                lateinit var poll: Runnable
                val transformer = Transformer.Builder(context)
                    .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoders(context), Clock.DEFAULT, null as LogSessionId?))
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacks(poll)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            main.removeCallbacks(poll)
                            android.util.Log.e("MediaRenderer", "Export failed", exportException)
                            if (continuation.isActive) continuation.resumeWithException(IOException(
                                "The video could not be exported. " + (exportException.message ?: exportException.errorCodeName), exportException))
                        }
                    })
                    .build()
                poll = Runnable {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100.0)
                    main.postDelayed(poll, 200)
                }
                continuation.invokeOnCancellation {
                    main.post { main.removeCallbacks(poll); transformer.cancel(); output.delete() }
                }
                transformer.start(composition, output.absolutePath)
                main.postDelayed(poll, 200)
            }
        }

    /**
     * Decoders for the job. The emulator's goldfish H.264 decoder rejects
     * Transformer's input buffers (`queueCSDInputBuffer failed w/ error 14`),
     * so software decoders go first there; devices keep the platform order.
     * Decoder fallback is enabled in both cases.
     */
    private fun decoders(context: Context): DefaultDecoderFactory {
        val emulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (emulator) infos.sortedBy { if (it.softwareOnly) 0 else 1 } else infos
        }
        return DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).setMediaCodecSelector(selector).build()
    }

    /** Swift's post-export checks: duration within a frame or two, tracks present, first and last pictures decodable. */
    private fun verify(file: File, plan: ExportPlan, fps: Int) {
        val expected = plan.duration.seconds
        var video = false
        var audio = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) video = true
                if (mime.startsWith("audio/")) audio = true
            }
        } finally { extractor.release() }
        if (!video || (plan.hasAudio && !audio)) throw ProjectError.invalidMedia
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val actual = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1) / 1000.0
            if (!(actual >= 0 && abs(actual - expected) <= max(0.12, 2.0 / fps))) {
                throw IOException("The exported movie lasts %.2f s instead of %.2f s.".format(java.util.Locale.ROOT, actual, expected))
            }
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: throw ProjectError.invalidMedia
            if (actual > 0.1) {
                val last = ((min(actual, expected) - 1.0 / fps) * 1_000_000).toLong().coerceAtLeast(0)
                retriever.getFrameAtTime(last, MediaMetadataRetriever.OPTION_CLOSEST) ?: throw ProjectError.invalidMedia
            }
        } finally { retriever.release() }
    }
}

/** A constant speed for a whole media item (Media3 asks with item-relative times). */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/**
 * The exact source picture of a freeze frame. Swift inserts a 1/60000 s
 * source range at the hold's time and scales it to the hold, so the picture is
 * the frame presented at that time. Here the hold plays that one frame,
 * clipped from its own timestamp to the next frame's, slowed to the hold's
 * length (preview and export alike).
 */
object HoldFrames {
    data class Key(val source: UUID, val time: Double)
    /** One frame: its presentation time and the next frame's (or the track end), in microseconds. */
    data class Window(val startUs: Long, val endUs: Long)

    /**
     * The frame shown at [timeUs] among presentation [times] (any order): the
     * last one at or before it, else the first. [endUs] closes the last frame.
     */
    fun window(times: List<Long>, timeUs: Long, endUs: Long?): Window? {
        val sorted = times.distinct().sorted()
        if (sorted.isEmpty()) return null
        val index = sorted.indexOfLast { it <= timeUs }.coerceAtLeast(0)
        val start = sorted[index]
        val typical = if (sorted.size > 1) sorted.zipWithNext { a, b -> b - a }.sorted()[(sorted.size - 1) / 2] else 33_333L
        val next = sorted.getOrNull(index + 1) ?: min(start + typical, endUs?.takeIf { it > start } ?: (start + typical))
        return Window(start, next)
    }

    private val cache = object : LinkedHashMap<String, Window>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Window>?) = size > 64
    }

    /** Reads the video sample times around [time] (no decoding). */
    fun read(file: File, time: Double): Window? {
        val key = "${file.path}|${file.length()}|${file.lastModified()}|$time"
        synchronized(cache) { cache[key]?.let { return it } }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            val trackEnd = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            extractor.selectTrack(track)
            val target = Math.round(time * 1_000_000)
            // From the sync sample before the time to well past it: presentation order differs from decode order.
            extractor.seekTo(max(0L, target - 1_000_000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val times = mutableListOf<Long>()
            while (true) {
                val sample = extractor.sampleTime
                if (sample < 0) break
                times += sample
                if (sample > target + 1_000_000) break
                if (!extractor.advance()) break
            }
            return window(times, target, trackEnd)?.also { synchronized(cache) { cache[key] = it } }
        } catch (failure: IOException) {
            return null
        } finally { extractor.release() }
    }
}

/**
 * A static crop on the GPU: maps the upright decoded picture into the canvas
 * with `CropSettings.transform` (Swift applies the same transform as the
 * composition layer instruction). Media3 matrices work in normalized device
 * coordinates (y up), so the pixel transform is conjugated into that space.
 * The input size comes from the decoder, so it is the oriented source size.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CropTransformation(private val crop: CropSettings, private val canvas: CGSize) : androidx.media3.effect.MatrixTransformation {
    private var matrix = android.graphics.Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        matrix = android.graphics.Matrix().apply { setValues(ndcMatrix(crop, CGSize(inputWidth.toDouble(), inputHeight.toDouble()), canvas)) }
        return androidx.media3.common.util.Size(canvas.width.toInt(), canvas.height.toInt())
    }

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = matrix

    companion object {
        /** `android.graphics.Matrix` values (row-major 3×3) mapping input NDC to output NDC. Pure; JVM-tested. */
        fun ndcMatrix(crop: CropSettings, source: CGSize, canvas: CGSize): FloatArray {
            val fromInput = CGAffineTransform(source.width / 2, 0.0, 0.0, -source.height / 2, source.width / 2, source.height / 2)
            val toOutput = CGAffineTransform(2 / canvas.width, 0.0, 0.0, -2 / canvas.height, -1.0, 1.0)
            val m = fromInput.concatenating(crop.transform(source, canvas)).concatenating(toOutput)
            return floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.tx.toFloat(), m.b.toFloat(), m.d.toFloat(), m.ty.toFloat(), 0f, 0f, 1f)
        }
    }
}

/**
 * Swift `MirrorSettings.transform(canvas:)` on the GPU, after the framing: the
 * flips about the canvas centre are a sign change of the normalized device
 * coordinates, so the output keeps the canvas size.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MirrorTransformation(private val mirror: MirrorSettings) : androidx.media3.effect.MatrixTransformation {
    private val matrix = android.graphics.Matrix().apply { setValues(ndcMatrix(mirror)) }

    override fun configure(inputWidth: Int, inputHeight: Int) = androidx.media3.common.util.Size(inputWidth, inputHeight)
    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = matrix

    companion object {
        /** Row-major 3×3: the canvas-pixel mirror conjugated into NDC (pure; JVM-tested). */
        fun ndcMatrix(mirror: MirrorSettings): FloatArray =
            floatArrayOf(if (mirror.horizontal) -1f else 1f, 0f, 0f, 0f, if (mirror.vertical) -1f else 1f, 0f, 0f, 0f, 1f)
    }
}
