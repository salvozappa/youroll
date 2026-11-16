// Port of YouRec/EditorController.swift (preview playback, seeking, review speed,
// change navigation, thumbnails and waveforms).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The Swift controller plays an AVComposition built by MediaRenderer. Here the
// compiled timeline becomes an ExoPlayer playlist of clipped segments
// (`PreviewPlan`): each segment shows the front picture, and its sound is the
// mix of every audible audio item, computed per sample in the audio sink from
// the export's audio plan (PreviewAudio.kt). Picture gaps show black. The
// picture is drawn inside the project canvas like the export: a cropped clip
// through its CropSettings transform, others aspect-fit, then flipped by the
// clip's Mirror (`PreviewFraming`). Pause cuts are part of each clip's source
// clock, so the playlist simply skips them, as the export does.
// Speed runs and freeze frames are retimed as in the export (see `load`), and
// their sound is the mix's pitch-preserving stretch (PreviewAudio.kt).
// A transition window plays the export's rendered transition clip
// (TransitionRenderer.kt), prepared in the project cache ("Preparing
// transitions"; the window shows black until its clip is ready). Its titles
// are inside that clip, so `titles` draws none there.
// Colour: while any clip is graded, every preview player runs ColorGradeEffect
// (the export's grade, VideoToolCompositor.kt) on its decoded pictures; the
// grade follows the playlist item being decoded (`grades`). Transition clips
// are graded when they are rendered.
// Stabilization: while any clip is stabilized, every preview player also runs
// StabilizationTransformation (the export's correction, VideoToolCompositor.kt)
// after the grade and before the video effects' framing (Swift `preparedImage`).
// Each frame finds its playlist item and source time through the player's
// PreviewFrameClock, so the exact source frame drawn gets its own correction,
// as in the export. The plans come from the cached motion analysis
// ("Applying stabilization", measured if missing); a tool preview passes its own.
// Video effects: while any clip has effects, every preview player also runs
// VideoEffectsGlEffect (MetalVideoEffects.kt), the export's effect chain. It
// then frames each picture into the canvas itself (crop or fit, Mirror), so
// PlayerSurface shows canvas-sized pictures; each frame finds its clip and
// source time from its presentation time, which ExoPlayer reports on the
// playlist's clock (the project time). Holds play their hold clips
// (VideoEffectSampling.kt), prepared like the transition clips.
// Image overlays and titles are drawn over the picture from the playhead (`titles`), with the
// export's bitmaps and placement (VideoToolCompositor.kt); the Text tool adds
// its draft through `previewTitle` (Swift `VideoToolInstruction.previewTitle`).
// While placed videos overlap, the pictures behind the front one play in
// muted, picture-only players that follow the main player's playlist item by
// item (`layers`); PlayerSurface stacks them with the export's geometry
// (TrackRenderer.placed), so the preview shows the exported layout.
//
// Not ported here (TODO.md): export/render, audio processing previews,
// Original/Processed comparison and listening levels, auditions (Preview cut,
// quick review), effect application receipts and refinement, headroom analysis.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One playlist item of the preview: a time span with its front picture
 * ([video]) and, while placed videos overlap, the pictures behind it
 * ([layers], nearest first; null where that depth shows nothing). Its sound is the timeline mix.
 */
data class PreviewSegment(
    val start: Double,
    val end: Double,
    val video: Piece?,
    val layers: List<Piece?> = emptyList(),
) {
    /**
     * A source span played over the segment: at normal speed, sped up or
     * slowed (the clip's Speed), or one held picture (a freeze frame, equal
     * ends). [item] is the timeline item it belongs to; [placement] is its
     * window while placed videos overlap (null covers the canvas).
     */
    data class Piece(val item: UUID, val source: UUID, val sourceStart: Double, val sourceEnd: Double, val placement: VideoPlacement? = null,
                     /** A transition window: the piece plays its rendered clip from [sourceStart], seconds into the window (item and source are the relation id). */
                     val transition: TransitionWindow? = null) {
        val isHold: Boolean get() = sourceStart == sourceEnd
    }
    /** Every picture, front first (index = depth = player). */
    val pictures: List<Piece?> get() = listOf(video) + layers
    /** Source seconds per timeline second of [piece] (0 for a hold). */
    fun rate(piece: Piece): Double = if (duration > 0) (piece.sourceEnd - piece.sourceStart) / duration else 1.0
    val duration: Double get() = end - start
    /** Source seconds per timeline second of the picture (0 for a hold). */
    val rate: Double get() = video?.let { if (duration > 0) (it.sourceEnd - it.sourceStart) / duration else 1.0 } ?: 1.0
    /** The segment's frames on the 48 kHz mix clock; neighbours tile the clock exactly. */
    val clockFrames: LongRange get() = PreviewAudioMix.frame(start) until PreviewAudioMix.frame(end)
}

/**
 * How the preview draws the current picture: the project canvas, the oriented
 * source size and the clip's crop (null = aspect-fit inside the canvas). The
 * same transforms as the export (Swift: the composition's layer instruction).
 */
data class PreviewFraming(val canvas: CGSize, val source: CGSize, val crop: CropSettings?, val placement: VideoPlacement? = null,
                          val mirror: MirrorSettings? = null) {
    /** Swift `TrackRenderer.placed`: where the framed picture goes on the canvas, and what of it is visible. */
    val placed: TrackRenderer.Placed
        get() = placement?.let { TrackRenderer.placed(it, canvas) }
            ?: TrackRenderer.Placed(CGAffineTransform.identity, CGRect(0.0, 0.0, canvas.width, canvas.height), CGRect(0.0, 0.0, canvas.width, canvas.height))

    /** Oriented source pixels → canvas pixels: the crop or fit, then the clip's Mirror (Swift `MediaRenderer.timeline`). */
    val transform: CGAffineTransform
        get() {
            val framing = crop?.transform(source, canvas) ?: run {
                val scale = min(canvas.width / source.width, canvas.height / source.height)
                CGAffineTransform.scale(scale, scale)
                    .concatenating(CGAffineTransform.translation((canvas.width - source.width * scale) / 2, (canvas.height - source.height * scale) / 2))
            }
            return mirror?.let { framing.concatenating(it.transform(canvas)) } ?: framing
        }

    companion object {
        /** Swift `MediaRenderer.canvas`: the fixed output size, else the reference picture's size rounded to even pixels. */
        fun canvas(edit: EditState, sizes: Map<UUID, CGSize>): CGSize? {
            edit.outputSize?.let { return it }
            val reference = sizes[edit.canvasSourceID ?: return null] ?: return null
            return CGSize(Math.round(reference.width / 2) * 2.0, Math.round(reference.height / 2) * 2.0)
        }
    }
}

/**
 * Splits the compiled timeline into playable picture segments. Pure, so it is
 * tested on the JVM. A segment's picture runs at one rate: a speed run is
 * retimed and a hold plays its one frame for the hold (see `load`), exactly as
 * the export does (MediaRenderer.kt). Its sound is the timeline mix (PreviewAudio.kt).
 */
object PreviewPlan {
    fun segments(timeline: TrackTimeline): List<PreviewSegment> {
        val result = mutableListOf<PreviewSegment>()
        val windows = timeline.transitionWindows()
        val compiled = timeline.compile().map { it to TrackRenderer.stack(timeline, it, windows) }
        // The same depths as the export's picture sequences (ExportPlan.videoLayers).
        val depth = max(1, compiled.maxOfOrNull { it.second.size } ?: 1)
        for ((interval, stack) in compiled) {
            // Subdivide at run boundaries so every piece maps linearly.
            val edges = sortedSetOf(interval.interval.start, interval.interval.end)
            for (layer in stack) {
                val item = layer.item ?: continue
                var cursor = item.start
                for (run in item.mapping.runs) {
                    cursor += run.duration
                    if (cursor > interval.interval.start && cursor < interval.interval.end) edges += cursor
                }
            }
            for ((a, b) in edges.toList().zipWithNext()) {
                if (b <= a) continue
                val pictures = (0 until depth).map { index ->
                    val layer = stack.getOrNull(index)
                    val window = layer?.window
                    val item = layer?.item
                    when {
                        window != null -> PreviewSegment.Piece(window.id, window.id, (a - window.interval.start).seconds, (b - window.interval.start).seconds, transition = window)
                        item != null -> piece(item.id, item.content.sourceID, item.start, item.mapping, a, b, layer.placement)
                        else -> null
                    }
                }
                result += PreviewSegment(a.seconds, b.seconds, pictures[0], pictures.drop(1))
            }
        }
        return merged(result)
    }

    private fun piece(id: UUID, source: UUID, start: EditTime, mapping: SourcePlaybackMap, a: EditTime, b: EditTime,
                      placement: VideoPlacement? = null): PreviewSegment.Piece? {
        var cursor = start
        for (run in mapping.runs) {
            val end = cursor + run.duration
            if (a >= cursor && a < end) return PreviewSegment.Piece(id, source, run.source(a - cursor), run.source(minOf(b, end) - cursor), placement)
            cursor = end
        }
        return null
    }

    /** Join neighbours that continue the same source spans, so the playlist stays short. */
    private fun merged(segments: List<PreviewSegment>): List<PreviewSegment> {
        val result = mutableListOf<PreviewSegment>()
        fun continues(a: PreviewSegment, b: PreviewSegment): Boolean = a.pictures.size == b.pictures.size && a.pictures.indices.all { index ->
            val x = a.pictures[index]
            val y = b.pictures[index]
            (x == null && y == null) || (x != null && y != null && x.item == y.item && x.placement == y.placement && x.transition?.id == y.transition?.id &&
                !x.isHold && !y.isHold && abs(x.sourceEnd - y.sourceStart) < 0.0005 && abs(a.rate(x) - b.rate(y)) < 0.0005)
        }
        for (segment in segments) {
            val last = result.lastOrNull()
            if (last != null && abs(last.end - segment.start) < 1e-9 && continues(last, segment)) {
                val joined = last.pictures.zip(segment.pictures) { x, y -> x?.copy(sourceEnd = y!!.sourceEnd) }
                result[result.size - 1] = PreviewSegment(last.start, segment.end, joined[0], joined.drop(1))
            } else result += segment
        }
        return result
    }
}

class EditorController(context: Context) {
    private val appContext = context.applicationContext.also { TitleTypeface.install(it) }
    private val liveGain = LiveAudioGain()
    private val mixProcessor = PreviewMixProcessor(liveGain,
        report = appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
    /** Per player (0 = [player], then the layers): maps an effect's frame time to its playlist item (PreviewFrameClock.kt). */
    private val frameClocks = java.util.concurrent.CopyOnWriteArrayList(listOf(PreviewFrameClock()))
    private fun frameClock(depth: Int): PreviewFrameClock {
        while (frameClocks.size <= depth) frameClocks.add(PreviewFrameClock())
        return frameClocks[depth]
    }
    val player: ExoPlayer = ExoPlayer.Builder(appContext, object : ClockedRenderersFactory(appContext, frameClocks[0]) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
            // The mix runs first, on the timeline clock; review speed (Sonic) follows it.
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(mixProcessor)).build()
    }.setEnableDecoderFallback(true).setMediaCodecSelector { mimeType, secure, tunneling ->
        // The emulator's goldfish H.264 decoder fails while thumbnails decode in
        // parallel; prefer the software codec there. Real devices are unaffected.
        val decoders = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
        if (android.os.Build.HARDWARE == "ranchu") decoders.sortedBy { it.name.contains("goldfish") } else decoders
    }).build()

    /**
     * Picture-only players for the pictures behind the front one (depth 1, 2, …)
     * while placed videos overlap. They play the same playlist items as
     * [player], muted, and are seeked, started and paused with it.
     */
    private val layerPlayers = mutableListOf<ExoPlayer>()
    private var lastLayerSync = 0L

    private fun makeLayerPlayer(): ExoPlayer = ExoPlayer.Builder(appContext, ClockedRenderersFactory(appContext, frameClock(layerPlayers.size + 1)).setEnableDecoderFallback(true)
        .setMediaCodecSelector { mimeType, secure, tunneling ->
            val decoders = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
            if (android.os.Build.HARDWARE == "ranchu") decoders.sortedBy { it.name.contains("goldfish") } else decoders
        }).build().also {
        it.setSeekParameters(SeekParameters.EXACT)
        it.volume = 0f
        it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        it.setPlaybackSpeed(playbackSpeed.toFloat())
        val depth = layerPlayers.size + 1
        it.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { gradeItems[depth] = it.currentMediaItemIndex }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { frameClock(depth).timeline = timeline }
        })
        if (gradeEffects || stabilizationEffects || effectsMode) it.setVideoEffects(playerEffects(depth))
    }

    // ---- Colour (Swift: `ColorLUT.apply` inside the editor's compositor)

    /** The grade of every playlist item, per depth (0 = [player]); null where a piece is ungraded. */
    @Volatile private var grades: List<List<ColorGrade?>> = emptyList()
    /** The playlist item each depth's decoder is on (set at seeks and item transitions). */
    private val gradeItems = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** True once a graded edit was loaded: the players then keep the grade effect. */
    private var gradeEffects = false

    private fun gradeEffect(depth: Int) = ColorGradeEffect { _ ->
        grades.getOrNull(depth)?.getOrNull(gradeItems[depth] ?: 0)
    }

    /** Each playlist item's grade, per depth (the front picture, then the layers), from the loaded timeline. */
    private fun setGrades(folder: File) {
        val depthCount = 1 + (segments.maxOfOrNull { it.layers.size } ?: 0)
        val timeline = loadedTimeline
        val graded = (0 until depthCount).map { depth ->
            segments.map { segment ->
                val piece = segment.pictures.getOrNull(depth)
                if (piece == null || piece.transition != null) null
                else ColorGrade.load(timeline?.video?.firstOrNull { it.id == piece.item }?.content?.color, folder)
            }
        }
        grades = graded
        if (graded.any { lane -> lane.any { it != null } }) enableGrades()
    }

    /** Installs the grade effect on every player the first time a graded clip appears (an ungraded project never pays for it). */
    private fun enableGrades() {
        if (gradeEffects) return
        gradeEffects = true
        installEffects()
    }

    // ---- Stabilization (Swift: `VideoToolInstruction.stabilization` in the editor's compositor)

    /** Each stabilized item's correction, by item (clip) id; read on the players' GL threads. */
    @Volatile private var corrections: Map<UUID, StabilizationCorrection> = emptyMap()
    /** The playlist the corrections were computed for (one item per segment). */
    @Volatile private var correctionSegments: List<PreviewSegment> = emptyList()
    /** True once a stabilized edit was loaded: the players then keep the correction effect. */
    private var stabilizationEffects = false
    private var stabilizationJob: Job? = null
    /** Swift `MediaRenderer.timeline(stabilizationPlans:)`: a tool preview's own plans by clip id (null: from the analyses). */
    private var providedPlans: Map<UUID, StabilizationPlan>? = null
    /** Swift `videoProgress`: the fraction of the current preparation, shown with [loadingLabel] (null: indeterminate). */
    var videoProgress by mutableStateOf<Double?>(null)
        private set

    private fun stabilizationEffect(depth: Int) = StabilizationTransformation { timeUs -> correction(depth, timeUs) }

    /** The NDC correction of the frame the player at [depth] draws at effect time [timeUs] (null: none). */
    private fun correction(depth: Int, timeUs: Long): FloatArray? {
        val all = corrections
        if (all.isEmpty()) return null
        val (index, periodUs) = frameClock(depth).locate(timeUs) ?: return null
        val segment = correctionSegments.getOrNull(index) ?: return null
        val piece = segment.pictures.getOrNull(depth) ?: return null
        if (piece.transition != null) return null
        val correction = all[piece.item] ?: return null
        // The period clock of `setPlaylist`'s clipped source: source time at normal speed, divided by the speed from the clip start otherwise.
        val source = if (piece.isHold) piece.sourceStart else {
            val startUs = (piece.sourceStart * 1_000_000).toLong()
            val rate = segment.rate(piece)
            val speed = if (abs(rate - 1) > ExportPlan.rateTolerance) rate else 1.0
            (startUs + (periodUs - startUs) * speed) / 1_000_000.0
        }
        return correction.ndc(source)
    }

    /**
     * Swift `EditorController.load`: the plans of the stabilized clips of
     * [edit] ("Applying stabilization"), then the players' correction effect.
     */
    private fun prepareStabilization(edit: EditState, project: Project, folder: File) {
        stabilizationJob?.cancel()
        correctionSegments = segments
        val items = edit.timeline.video.filter { it.content.stabilizationReference != null }
        if (items.isEmpty()) { corrections = emptyMap(); return }
        val plans = providedPlans
        val groups = items.groupBy { it.content.sourceID to it.content.stabilizationReference!! }
        if (plans == null) { loading = true; loadingLabel = "Applying stabilization"; videoProgress = 0.0 }
        stabilizationJob = scope.launch {
            val result = HashMap<UUID, StabilizationCorrection>()
            for ((index, entry) in groups.entries.withIndex()) {
                val source = project.sources.firstOrNull { it.id == entry.key.first } ?: continue
                val reference = entry.key.second
                try {
                    val correction = if (plans != null) {
                        entry.value.firstNotNullOfOrNull { plans[it.id] }?.let { plan ->
                            val rotation = kotlinx.coroutines.withContext(Dispatchers.IO) { VideoGeometry.read(File(folder, source.filename))?.rotation ?: 0 }
                            StabilizationCorrection(plan, reference.referenceStart ?: plan.samples.firstOrNull()?.time ?: 0.0, rotation)
                        }
                    } else VideoStabilizer.correction(source, reference, folder) { value ->
                            handler.post { if (!released && plans == null) videoProgress = (index + value) / groups.size }
                        }
                    if (correction != null) for (item in entry.value) result[item.id] = correction
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Stabilization failed for ${source.filename}", failure)
                    error = "Stabilization could not be prepared. Your recording and edits are preserved."
                }
            }
            corrections = result
            videoProgress = null
            if (!stabilizationEffects && result.isNotEmpty()) {
                stabilizationEffects = true
                installEffects()
                setPlaylist(project, folder)
            } else if (!playing) redraw()
            loading = audioJob?.isActive == true || transitionJob?.isActive == true
        }
    }

    /**
     * The picture effects of the player at [depth]: the grade on the source picture, the stabilization
     * correction, then the video effects with their framing (Swift `preparedImage`, then `render`).
     */
    private fun playerEffects(depth: Int): List<androidx.media3.common.Effect> =
        listOfNotNull(if (gradeEffects) gradeEffect(depth) else null, if (stabilizationEffects) stabilizationEffect(depth) else null,
            if (effectsMode) VideoEffectsGlEffect(effectSource(depth)) else null)

    /** The effect chain the players run (installed with [installEffects]). */
    private var installedEffectsMode = false

    private fun installEffects() {
        installedEffectsMode = effectsMode
        // A renderer takes new effects only when it is enabled again: stop first (the caller then sets a fresh playlist).
        player.stop(); player.setVideoEffects(playerEffects(0))
        for ((index, layer) in layerPlayers.withIndex()) { layer.stop(); layer.setVideoEffects(playerEffects(index + 1)) }
    }

    // ---- Video effects (Swift: `MetalEffectRenderer` inside the editor's compositor)

    /** Each playlist item's effect clock, per depth; null where a picture has none (a gap or a transition clip). */
    @Volatile private var effectLayers: List<List<VideoEffectLayer?>> = emptyList()
    @Volatile private var effectStarts: DoubleArray = DoubleArray(0)
    @Volatile private var effectCanvas: CGSize? = null
    /** True while the loaded edit has video effects: the players then run the effect chain and frame the pictures. */
    var effectsMode by mutableStateOf(false)
        private set
    /** The effect editor's palette sampling: called with each drawn layer's renderer on the GL thread. */
    @Volatile var renderedEffects: ((MetalEffectRenderer, VideoEffectLayer?) -> Unit)? = null

    private fun effectSource(depth: Int) = object : VideoEffectSource {
        override fun layer(presentationTimeUs: Long): VideoEffectLayer? {
            val starts = effectStarts
            val time = presentationTimeUs / 1_000_000.0 + 1e-7
            var low = 0
            var high = starts.size - 1
            var found = -1
            while (low <= high) {
                val middle = (low + high) / 2
                if (starts[middle] <= time) { found = middle; low = middle + 1 } else high = middle - 1
            }
            return effectLayers.getOrNull(depth)?.getOrNull(max(0, found))
        }
        override val outputSize: CGSize? get() = effectCanvas
        override fun rendered(renderer: MetalEffectRenderer, layer: VideoEffectLayer?) { if (depth == 0) renderedEffects?.invoke(renderer, layer) }
    }

    /** Recomputes the effect clocks of [segments]; returns true when the players' effects must be reinstalled. */
    private fun setEffectLayers(): Boolean {
        val canvas = canvas
        val timeline = loadedTimeline
        val wanted = canvas != null && timeline != null && timeline.video.any { it.content.hasVideoEffects }
        val depthCount = 1 + (segments.maxOfOrNull { it.layers.size } ?: 0)
        effectLayers = if (!wanted) emptyList() else (0 until depthCount).map { depth ->
            segments.map { segment ->
                val piece = segment.pictures.getOrNull(depth)
                if (piece == null || piece.transition != null) return@map null
                val item = timeline!!.video.firstOrNull { it.id == piece.item } ?: return@map null
                val clip = item.content
                VideoEffectLayer(clip, segment.start, segment.end, piece.sourceStart, segment.rate(piece), item.start.seconds,
                    PreviewFraming(canvas!!, canvas, clip.crop, mirror = clip.mirror?.takeIf { it.horizontal || it.vertical }))
            }
        }
        effectStarts = segments.map { it.start }.toDoubleArray()
        effectCanvas = canvas
        if (wanted == effectsMode) return false
        effectsMode = wanted
        return true
    }

    /** The hold clips of the loaded playlist (VideoEffectSampling.kt), by source and held time. */
    private var playlistHolds: Map<HoldFrames.Key, File> = emptyMap()

    /** One picture behind the front one, as PlayerSurface draws it. */
    data class PreviewLayer(val player: Player, val framing: PreviewFraming, val titles: List<SubtitleLayout.Frame>)

    /** The pictures behind the front one at the playhead, nearest first (empty without a placed overlap). */
    var layers by mutableStateOf<List<PreviewLayer>>(emptyList())
        private set

    // Inspector-only bounds. The main editor leaves these null.
    var previewRange: ClosedFloatingPointRange<Double>? = null
    private val playbackRange: ClosedFloatingPointRange<Double>
        get() {
            val lower = min(duration, max(0.0, previewRange?.start ?: 0.0))
            return lower..max(lower, min(duration, previewRange?.endInclusive ?: duration))
        }

    var waveforms by mutableStateOf<Map<UUID, SourceWaveform>>(emptyMap())
        private set
    var thumbnails by mutableStateOf<Map<UUID, List<SourceThumbnail>>>(emptyMap())
        private set
    var navigation by mutableStateOf(TimelineNavigation())
        private set
    var playhead by mutableDoubleStateOf(0.0)
        private set
    var playbackSpeed by mutableDoubleStateOf(1.0)
        @JvmName("storePlaybackSpeed") private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingLabel by mutableStateOf("Preparing preview")
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    /** The project canvas and the current picture's framing; null until the source sizes are read. */
    var framing by mutableStateOf<PreviewFraming?>(null)
        private set
    var canvas by mutableStateOf<CGSize?>(null)
        private set
    /** Oriented picture sizes by source file. */
    private val sourceSizes = HashMap<String, CGSize>()
    private var geometryJob: Job? = null
    private var framingSizes: Map<UUID, CGSize> = emptyMap()
    /** The titles over the picture at the playhead, in canvas coordinates. */
    var titles by mutableStateOf<List<SubtitleLayout.Frame>>(emptyList())
        private set
    /** The edited sound as the preview plays it (Swift `item.audioMix`), for transcription; null until installed. */
    val audioMix: PreviewAudioMix? get() = mixProcessor.mix
    /** True while sources or speed stretches of the mix are still being decoded. */
    val preparingAudio: Boolean get() = audioJob?.isActive == true
    /** The captions at the playhead in canvas coordinates, drawn over every picture (Swift `SubtitleRenderTrack.render`). */
    var subtitles by mutableStateOf<List<SubtitleLayout.Frame>>(emptyList())
        private set
    /** The subtitle editor's draft captions (Swift `updatePreview`), drawn instead of the loaded timeline's. */
    var previewCaptions: SubtitleRenderTrack? = null
        set(value) { field = value; tick() }
    private var timelineCaptions: Pair<TrackTimeline, SubtitleRenderTrack?>? = null
    /** The caption track of the loaded timeline, built once per loaded edit. */
    private fun captionTrack(): SubtitleRenderTrack? {
        previewCaptions?.let { return it }
        val timeline = loadedTimeline ?: return null
        timelineCaptions?.let { (source, track) -> if (source === timeline) return track }
        val track = timeline.subtitles?.let { SubtitleRenderTrack(timeline.subtitleCues, it, loadedFolder) }
        timelineCaptions = timeline to track
        return track
    }
    /** The Text tool's draft title (Swift `previewTitle`), drawn as if it were applied. */
    var previewTitle: PreviewTitle? = null
        set(value) { field = value; tick() }
    private val titleBitmaps = TitleBitmaps()
    private var titleRanges = TitleMotionRanges()
    /** False while the playhead is in a picture gap (the preview shows black). */
    var showsPicture by mutableStateOf(true)
        private set
    /** Bumps whenever the timeline's audio gains change, like Swift `gainRevision`. */
    var gainRevision by mutableStateOf(0)
        private set
    var scrubbing = false
        private set

    private var duration = 0.0
    private var segments: List<PreviewSegment> = emptyList()
    private var loadedEdit: EditState? = null
    private var loadedTimeline: TrackTimeline? = null
    private var latestEdit: EditState? = null
    /** Decoded source sound by source ID (memory mapped), with the project folder it belongs to. */
    private val pcm = HashMap<UUID, Pair<File, SourcePcm>>()
    /** Pitch-preserving stretches of speed runs, with their project folder. */
    private val stretches = HashMap<SpeedStretch.Key, Pair<File, SourcePcm>>()
    private var audioJob: Job? = null
    /** Renders the transition clips of the loaded edit (TransitionRenderer.kt). */
    private var transitionJob: Job? = null
    /** The rendered transition clips the current playlist plays, by window. */
    private var playlistTransitions: Map<UUID, File> = emptyMap()
    private var loadedProject: Project? = null
    private var loadedFolder: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detailJob: Job? = null
    private val completedThumbnails = HashSet<UUID>()
    private var lastScrubSeek = 0L
    private var scrubSeek: Job? = null
    private var released = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        player.setSeekParameters(SeekParameters.EXACT)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                gradeItems[0] = player.currentMediaItemIndex
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { frameClocks[0].timeline = timeline }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause(); layerPlayers.forEach { it.pause() }; playing = false; playhead = playbackRange.endInclusive
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                pause()
                this@EditorController.error = "The video preview could not be displayed. " + (error.message ?: "Please reopen the project.")
            }
        })
        scope.launch {
            while (isActive) {
                tick()
                delay(33)
            }
        }
    }

    private fun tick() {
        if (released) return
        val advancing = player.isPlaying
        if (advancing && !scrubbing) {
            val index = player.currentMediaItemIndex
            val segment = segments.getOrNull(index)
            if (segment != null) {
                val time = segment.start + player.currentPosition / 1000.0
                playhead = min(playbackRange.endInclusive, max(playbackRange.start, min(segment.end, time)))
                if (previewRange != null && playhead >= playbackRange.endInclusive - 0.001) pause()
            }
        }
        val segment = segment(playhead)
        // An empty edit, a picture gap or a transition still being prepared shows black, never the last decoded frame.
        showsPicture = segment?.video != null && segment.video.transition.let { it == null || it.id in playlistTransitions }
        // The framing follows the playlist item being rendered.
        val shown = segments.getOrNull(player.currentMediaItemIndex)?.takeIf { player.mediaItemCount == segments.size } ?: segment
        fun framing(piece: PreviewSegment.Piece?): PreviewFraming? = canvas?.let { canvas ->
            piece ?: return@let null
            // A transition clip is already rendered on the canvas.
            if (piece.transition != null) return@let PreviewFraming(canvas, canvas, null)
            // The effect chain already framed the picture into the canvas.
            if (effectsMode) return@let PreviewFraming(canvas, canvas, null, piece.placement)
            val source = framingSizes[piece.source] ?: return@let null
            val content = loadedTimeline?.video?.firstOrNull { it.id == piece.item }?.content
            PreviewFraming(canvas, source, content?.crop, piece.placement, content?.mirror?.takeIf { it.horizontal || it.vertical })
        }
        val nextFraming = framing(shown?.video)
        if (nextFraming != null && nextFraming != framing) framing = nextFraming
        val nextTitles = titleFrames(playhead, segment, segment?.video)
        if (nextTitles != titles) titles = nextTitles
        val nextSubtitles = canvas?.let { canvas -> captionTrack()?.frames(playhead, canvas) }.orEmpty()
        if (nextSubtitles != subtitles) subtitles = nextSubtitles
        // Swift `renderTracks`: the pictures behind the front one, each with its own framing and titles.
        val nextLayers = shown?.layers.orEmpty().withIndex().mapNotNull { (index, piece) ->
            val layerPlayer = layerPlayers.getOrNull(index) ?: return@mapNotNull null
            val layerFraming = framing(piece) ?: return@mapNotNull null
            PreviewLayer(layerPlayer, layerFraming, titleFrames(playhead, segment, segment?.layers?.getOrNull(index)))
        }
        if (nextLayers != layers) layers = nextLayers
        if (advancing && !scrubbing) syncLayers(force = false)
    }

    /**
     * Keeps the layer players on the main player's item and position. They
     * start together; a layer that drifts more than about three frames (or
     * missed an item change) is moved back, at most twice a second.
     */
    private fun syncLayers(force: Boolean) {
        if (layerPlayers.isEmpty()) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val now = System.currentTimeMillis()
        if (!force && now - lastLayerSync < 500) return
        for (layer in layerPlayers) {
            if (layer.mediaItemCount != player.mediaItemCount) continue
            val drift = if (layer.currentMediaItemIndex == index) abs(layer.currentPosition - position) else Long.MAX_VALUE
            if (force || (drift > 100 && layer.playbackState == Player.STATE_READY)) {
                layer.seekTo(index, position); lastLayerSync = now
            }
        }
    }

    /**
     * Swift `VideoOverlayRenderer.apply`: [piece]'s image overlays and titles,
     * and the Text tool's draft, at [time].
     */
    private fun titleFrames(time: Double, segment: PreviewSegment?, piece: PreviewSegment.Piece?): List<SubtitleLayout.Frame> {
        val canvas = canvas ?: return emptyList()
        segment ?: return emptyList()
        piece ?: return emptyList()
        val item = loadedTimeline?.video?.firstOrNull { it.id == piece.item } ?: return emptyList()
        val draft = previewTitle
        if (item.content.texts.isNullOrEmpty() && item.content.images.isNullOrEmpty() && draft == null) return emptyList()
        val rate = if (segment.duration > 0) (piece.sourceEnd - piece.sourceStart) / segment.duration else 1.0
        val sourceTime = piece.sourceStart + (min(time, segment.end) - segment.start) * rate
        val seen = HashSet<UUID>()
        val image = { text: TextOverlay -> titleBitmaps.image(text, canvas) }
        val folder = loadedFolder
        val result = VideoOverlayRenderer.overlays(item.content.images.orEmpty(), item.content.texts.orEmpty(), sourceTime, time, titleRanges, canvas, seen,
            { overlay -> folder?.let { overlayImage(it, overlay.filename) } }, image).toMutableList()
        draft?.clips?.get(item.id)?.let { range ->
            result += VideoOverlayRenderer.titles(listOf(draft.text.copy(start = range.start, end = range.endInclusive)), sourceTime, time, draft.ranges, canvas, seen, image)
        }
        return result
    }

    private val imageLoads = HashSet<String>()

    /** An overlay image, decoded off the main thread on first use (the frame shows without it until then). */
    private fun overlayImage(folder: File, filename: String): android.graphics.Bitmap? {
        val (known, bitmap) = ImageBitmaps.peek(folder, filename)
        if (known) return bitmap
        if (imageLoads.add(filename)) scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) { ImageBitmaps.image(folder, filename) }
            imageLoads.remove(filename); tick()
        }
        return null
    }

    /** Reads the oriented sizes of the pictures in [edit] (cached per file), then sets the canvas. */
    private fun loadGeometry(edit: EditState, project: Project, folder: File) {
        geometryJob?.cancel()
        val ids = edit.timeline.video.map { it.content.sourceID }.toSet()
        val sources = project.sources.filter { it.id in ids }
        geometryJob = scope.launch {
            val sizes = HashMap<UUID, CGSize>()
            for (source in sources) {
                val file = File(folder, source.filename)
                val size = sourceSizes[file.path] ?: kotlinx.coroutines.withContext(Dispatchers.IO) {
                    VideoGeometry.read(file)?.let { CGSize(it.orientedWidth.toDouble(), it.orientedHeight.toDouble()) }
                }?.also { sourceSizes[file.path] = it }
                if (size != null) sizes[source.id] = size
            }
            framingSizes = sizes
            canvas = PreviewFraming.canvas(edit, sizes)
            if (canvas == null) framing = null
            // The effect chain needs the canvas: install it now if the edit has effects.
            if (loadedEdit == edit && (canvas != null) && (loadedTimeline?.video?.any { it.content.hasVideoEffects } == true) != effectsMode) setPlaylist(project, folder)
            tick()
            prepareTransitions(edit, project, folder)
        }
    }

    /** The rendered clip of [window] on the current canvas, if it exists. */
    private fun transitionClip(window: TransitionWindow, project: Project, folder: File): File? {
        val canvas = canvas ?: return null
        val job = TransitionRenderJob.of(window, project.sources, folder, canvas.width.toInt(), canvas.height.toInt()) ?: return null
        return job.file(folder).takeIf { it.exists() }
    }

    /**
     * Renders the missing transition clips of [edit] (the export renders the
     * same files), then puts them in the playlist. Needs the canvas.
     */
    private fun prepareTransitions(edit: EditState, project: Project, folder: File) {
        transitionJob?.cancel()
        val canvas = canvas ?: return
        val jobs = TransitionRenderJob.all(edit.timeline, project.sources, folder, canvas.width.toInt(), canvas.height.toInt())
        fun ready() = jobs.mapNotNull { job -> job.file(folder).takeIf { it.exists() }?.let { job.window.id to it } }.toMap()
        val holds = VideoEffectHoldClip.all(edit.timeline, project.sources, folder)
        fun readyHolds() = holds.mapNotNull { hold -> hold.output(folder).takeIf { it.exists() }?.let { HoldFrames.Key(hold.sourceID, hold.sourceTime) to it } }.toMap()
        if ((ready() != playlistTransitions || readyHolds() != playlistHolds) && loadedEdit == edit) setPlaylist(project, folder)
        val missing = jobs.filterNot { it.file(folder).exists() }
        val missingHolds = holds.filterNot { it.output(folder).exists() }
        if (missing.isEmpty() && missingHolds.isEmpty()) return
        loading = true; loadingLabel = if (missing.isEmpty()) "Preparing effects" else "Preparing transitions"
        transitionJob = scope.launch {
            for (hold in missingHolds) {
                try {
                    VideoEffectHoldRenderer.render(hold, folder)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Hold clip failed", failure)
                    error = "The freeze frame could not be prepared. Your recording and edits are preserved."
                }
            }
            if (loadedEdit == edit && readyHolds() != playlistHolds) setPlaylist(project, folder)
            for (job in missing) {
                try {
                    TransitionRenderer.render(job, folder)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Transition clip failed", failure)
                    error = "The transition could not be prepared. Your recording and edits are preserved."
                }
            }
            if (loadedEdit == edit && (ready() != playlistTransitions || readyHolds() != playlistHolds)) setPlaylist(project, folder)
            loading = audioJob?.isActive == true || stabilizationJob?.isActive == true
        }
    }

    private fun segment(time: Double): PreviewSegment? {
        if (segments.isEmpty()) return null
        val index = segments.indexOfLast { it.start <= time + 1e-9 }.coerceAtLeast(0)
        return segments[index]
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel(); handler.removeCallbacksAndMessages(null)
        player.release()
        layerPlayers.forEach { it.release() }; layerPlayers.clear()
        mixProcessor.mix = null; pcm.clear(); stretches.clear()
    }

    /** Swift `stop()`: forget the loaded edit, so the next `load` rebuilds the preview. */
    fun stop() {
        pause()
        detailJob?.cancel(); audioJob?.cancel(); transitionJob?.cancel(); stabilizationJob?.cancel()
        loadedEdit = null; loadedTimeline = null; segments = emptyList()
        player.clearMediaItems()
        layerPlayers.forEach { it.clearMediaItems() }; layers = emptyList()
    }

    fun pause() {
        if (player.isPlaying && !scrubbing) {
            segments.getOrNull(player.currentMediaItemIndex)?.let { segment ->
                playhead = min(playbackRange.endInclusive, max(playbackRange.start, segment.start + player.currentPosition / 1000.0))
            }
        }
        player.pause(); playing = false
        layerPlayers.forEach { it.pause() }
    }

    fun setPlaybackSpeed(value: Double) {
        if (value !in listOf(1.0, 1.5, 2.0, 2.5, 3.0)) return
        playbackSpeed = value
        // ExoPlayer's Sonic time stretching keeps the voice pitch.
        player.setPlaybackSpeed(value.toFloat())
        layerPlayers.forEach { it.setPlaybackSpeed(value.toFloat()) }
    }

    fun seek(time: Double) {
        playhead = min(playbackRange.endInclusive, max(playbackRange.start, time))
        queueSeek(playhead)
    }

    /** The model changes before the new playlist is ready: clamp against the new edit's duration. */
    fun seekAfterEdit(time: Double, duration: Double) {
        this.duration = duration
        seek(time)
    }

    fun seekPaused(time: Double) { pause(); seek(time) }

    fun setScrubbing(active: Boolean) {
        if (active == scrubbing) return
        if (active) {
            pause(); scrubbing = true
            player.setScrubbingModeEnabled(true)
            layerPlayers.forEach { it.setScrubbingModeEnabled(true) }
        } else {
            scrubbing = false
            player.setScrubbingModeEnabled(false)
            layerPlayers.forEach { it.setScrubbingModeEnabled(false) }
            scrubSeek?.cancel(); scrubSeek = null
            queueSeek(playhead, force = true)
        }
    }

    // One seek at a time while a finger moves (Swift QA1820 pattern): the
    // latest requested position wins, at most every 40 ms.
    private fun queueSeek(time: Double, force: Boolean = false) {
        if (segments.isEmpty()) return
        if (scrubbing && !force) {
            val now = System.currentTimeMillis()
            if (now - lastScrubSeek < 40) {
                if (scrubSeek == null) scrubSeek = scope.launch {
                    delay(40 - (now - lastScrubSeek))
                    scrubSeek = null
                    performSeek(playhead)
                }
                return
            }
        }
        performSeek(time)
    }

    private fun performSeek(time: Double) {
        if (segments.isEmpty()) return
        lastScrubSeek = System.currentTimeMillis()
        var index = segments.indexOfLast { it.start <= time + 1e-9 }.coerceAtLeast(0)
        // At the very end, show the last frame of the last segment.
        if (time >= segments[index].end - 1e-6 && index == segments.lastIndex) {
            seekAll(index, max(0L, ((segments[index].duration) * 1000).toLong() - 1))
            return
        }
        if (time >= segments[index].end && index < segments.lastIndex) index += 1
        seekAll(index, max(0L, ((time - segments[index].start) * 1000).toLong()))
    }

    /**
     * Draws the paused frame again (a new grade): ExoPlayer ignores a seek to
     * its current position, so seek a millisecond beside it (the same frame
     * at the preview's 30 fps clock).
     */
    fun redraw() {
        if (segments.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, segments.lastIndex)
        val position = player.currentPosition
        val segmentLength = (segments[index].duration * 1000).toLong()
        seekAll(index, if (position > 0) position - 1 else min(position + 1, max(0L, segmentLength - 1)))
    }

    private fun seekAll(index: Int, position: Long) {
        for (depth in 0..layerPlayers.size) gradeItems[depth] = index
        player.seekTo(index, position)
        for (layer in layerPlayers) if (layer.mediaItemCount == player.mediaItemCount) layer.seekTo(index, position)
    }

    val stationaryPlayhead: Double get() = playhead

    fun jumpChange(forward: Boolean, effectsOnly: Boolean = false) {
        val anchor = stationaryPlayhead
        pause()
        navigation.destination(anchor, forward, effectsOnly)?.let { seek(it) }
    }

    fun togglePlay() {
        if (playing) { pause(); return }
        if (segments.isEmpty()) return
        val restart = playhead >= playbackRange.endInclusive - 0.05 || playhead < playbackRange.start
        if (restart) playhead = playbackRange.start
        performSeek(playhead)
        player.playWhenReady = true
        player.play()
        layerPlayers.forEach { it.play() }
        playing = true
    }

    /** Swift `snapped(_:clips:)`: prefer a quiet point within ±20 ms of a mark. */
    fun snapped(time: Double, clips: List<TimelineClip>): Double {
        for (placement in TimelineLayout(clips).placements) {
            val clip = placement.clip
            val cursor = placement.start
            if (!(time >= placement.lower && time <= placement.upper)) continue
            val wave = waveforms[clip.sourceID] ?: continue
            val candidates = (-2..2).map { min(cursor + clip.duration, max(cursor, time + it * 0.01)) }
            val quiet = candidates.minByOrNull { wave.peak(clip.sourceTime(it - cursor)) }
            if (quiet != null && wave.peak(clip.sourceTime(quiet - cursor)) < 0.01) return quiet
            break
        }
        return time
    }

    /** Waveform drags preview a gain on part of the timeline without committing it. */
    fun auditionGain(db: Double?, selection: TimelineSelection) {
        liveGain.update(db, selection)
    }

    /** The installed preview mix (Swift: the player item's audio mix). */
    val mix: PreviewAudioMix? get() = mixProcessor.mix

    /**
     * Another edit's mix over the sound already decoded and processed here, for
     * measurements (Swift prepares the original composition for level matching).
     */
    fun mixFor(timeline: TrackTimeline): PreviewAudioMix =
        PreviewAudioMix(ExportPlan.previewAudio(timeline), pcm.mapValues { it.value.second }, stretches.mapValues { it.value.second },
            processed.values.associate { it.third to it.second })

    /** One enhanced sound the mix needs: a source's chain, whole ([spans] null) or as inspector windows. */
    private data class EnhancementJob(val key: String, val source: SourceMedia, val effects: List<AudioEffect>, val spans: List<ClosedFloatingPointRange<Double>>?) {
        val id: String get() = key + "|" + (spans?.joinToString(",") { "${it.start}-${it.endInclusive}" } ?: "whole")
    }

    /** Processed sound by [EnhancementJob.id]: project folder, samples, audio key. */
    private val processed = HashMap<String, Triple<File, PcmSamples, String>>()

    /**
     * Swift `load(…, audioPreviewRange:)`: an inspector processes only the
     * source windows audible in this timeline range instead of whole takes.
     */
    var audioPreviewRange: ClosedFloatingPointRange<Double>? = null

    /**
     * Swift `preparedTimeline` (whole sources) or `preparedPreview` (windows of
     * [range], merged per source and chain, never bridging a removed section).
     */
    private fun enhancementJobs(project: Project, timeline: TrackTimeline, lanes: List<List<ExportPlan.Piece>>,
                                range: ClosedFloatingPointRange<Double>?): List<EnhancementJob> {
        val requests = TrackRenderer.enhancementRequests(project, lanes.flatten())
        for ((_, request) in requests) OfflineAudioPipeline.unavailable(request.second)?.let { throw it }
        if (range == null) return requests.map { (key, request) -> EnhancementJob(key, request.first, request.second, null) }
        val window = EditInterval.of(range.start, range.endInclusive)
        val spans = HashMap<String, MutableList<ClosedFloatingPointRange<Double>>>()
        for (item in timeline.audio) {
            if (item.muted) continue
            val part = item.interval.intersection(window) ?: continue
            val local = EditInterval(part.start - item.start, part.end - item.start)
            for (region in item.enhancements) {
                if (region.effects.none { it.enabled }) continue
                val scope = region.interval.intersection(local) ?: continue
                val key = TrackRenderer.audioKey(item.sourceID, region.effects)
                spans.getOrPut(key) { mutableListOf() } += item.mapping.sliced(scope).runs.filter { !it.silent && !it.isHold }.map { it.sourceStart..it.sourceEnd }
            }
        }
        return requests.mapNotNull { (key, request) ->
            val merged = mutableListOf<ClosedFloatingPointRange<Double>>()
            for (span in spans[key].orEmpty().sortedBy { it.start }) {
                val last = merged.lastOrNull()
                if (last != null && span.start <= last.endInclusive + 0.00001) merged[merged.size - 1] = last.start..max(last.endInclusive, span.endInclusive)
                else merged += span
            }
            if (merged.isEmpty()) null else EnhancementJob(key, request.first, request.second, merged)
        }
    }

    /**
     * Installs the mix of [timeline] in the sink. Sources not decoded yet are
     * decoded first (once per source; the cache survives relaunches) while the
     * preview shows "Preparing audio" and cannot play.
     */
    private fun installMix(timeline: TrackTimeline, project: Project, folder: File) {
        audioJob?.cancel()
        val lanes = ExportPlan.previewAudio(timeline)
        val needed = lanes.flatten().mapNotNull { (it as? ExportPlan.Piece.Clip)?.sourceID }.toSet()
        val sources = project.sources.filter { it.id in needed }
        // Speed runs play their pitch-preserving stretch (PreviewAudio.kt `SpeedStretch`).
        val speedRuns = lanes.flatten().filterIsInstance<ExportPlan.Piece.Clip>().filter { !it.isNormalSpeed && !it.isHold }
            .map { SpeedStretch.Key(it) }.distinct()
        stretches.keys.retainAll(speedRuns.toSet()) // Older drafts' stretches stay on disk, not mapped.
        // Enhanced sound (Swift `SourceMediaCache.prepared`): the whole source through each
        // chain, or in an inspector only the audible windows of its preview range.
        val window = audioPreviewRange
        val enhancements = runCatching { enhancementJobs(project, timeline, lanes, window) }.getOrElse { failure ->
            error = failure.message; emptyList()
        }
        processed.keys.retainAll(enhancements.map { it.id }.toSet())
        fun ready(source: SourceMedia) = pcm[source.id]?.first == folder
        fun stretchReady(key: SpeedStretch.Key) = stretches[key]?.first == folder
        fun enhancementReady(job: EnhancementJob) = processed[job.id]?.first == folder
        fun install() {
            mixProcessor.mix = PreviewAudioMix(lanes, sources.filter(::ready).associate { it.id to pcm.getValue(it.id).second },
                speedRuns.filter(::stretchReady).associate { it to stretches.getValue(it).second },
                enhancements.filter(::enhancementReady).associate { it.key to processed.getValue(it.id).second })
        }
        val missing = sources.filterNot(::ready)
        install()
        if (missing.isEmpty() && speedRuns.all(::stretchReady) && enhancements.all(::enhancementReady)) return
        loading = true; loadingLabel = "Preparing audio"
        audioJob = scope.launch {
            // Swift `load`: "Applying audio filter" (or "Preparing N s preview") with its progress.
            for ((index, job) in enhancements.filterNot(::enhancementReady).withIndex()) {
                loadingLabel = window?.let { "Preparing " + EffectPreviewWindow.label(it) } ?: "Applying audio filter"
                videoProgress = 0.0
                try {
                    val report: (Double) -> Unit = { value -> handler.post { if (audioJob?.isActive == true) videoProgress = value } }
                    val samples: PcmSamples? = if (job.spans == null) {
                        SourceMediaCache.processedAudio(job.source, folder, job.effects, report)?.let { (file, _) ->
                            kotlinx.coroutines.withContext(Dispatchers.IO) { FloatPcm.open(file) }
                        }
                    } else WindowedPcm(SourceMediaCache.processedWindows(job.source, folder, job.effects, job.spans, report))
                    if (samples != null) processed[job.id] = Triple(folder, samples, job.key)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Audio enhancement failed for ${job.source.filename}", failure)
                    error = failure.message ?: AudioProcessingError.invalidFormat.message
                }
                if (index == enhancements.lastIndex) videoProgress = null
            }
            videoProgress = null
            loadingLabel = "Preparing audio"
            for (source in missing) {
                try {
                    val file = SourceMediaCache.pcm(source, folder) ?: continue
                    val samples = kotlinx.coroutines.withContext(Dispatchers.IO) { SourcePcm.open(file) } ?: continue
                    pcm[source.id] = folder to samples
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Preview audio failed for ${source.filename}", failure)
                }
            }
            for (key in speedRuns.filterNot(::stretchReady)) {
                val source = sources.firstOrNull { it.id == key.source } ?: continue
                // An enhanced speed run stretches its processed sound.
                val enhanced = key.audioKey?.let { audioKey -> enhancements.firstOrNull { it.key == audioKey }?.let { processed[it.id] }?.takeIf { it.first == folder }?.second }
                val samples = enhanced ?: pcm[key.source]?.takeIf { it.first == folder }?.second ?: continue
                try {
                    val file = SourceMediaCache.stretchedPcm(source, folder, samples, key) ?: continue
                    val stretched = kotlinx.coroutines.withContext(Dispatchers.IO) { SourcePcm.open(file) } ?: continue
                    stretches[key] = folder to stretched
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Preview speed audio failed for ${source.filename}", failure)
                }
            }
            install()
            loading = transitionJob?.isActive == true || stabilizationJob?.isActive == true
        }
    }

    /**
     * Swift `load(_:folder:includeTimelineDetails:)`. Rebuilds the playlist only
     * when the rendered edit changed (bookmarks alone never rebuild it), then
     * fills in waveforms and thumbnails of the sources in use.
     */
    fun load(project: Project, folder: File, includeTimelineDetails: Boolean = true, stabilizationPlans: Map<UUID, StabilizationPlan>? = null) {
        if (released) return
        val edit = project.edit
        if (stabilizationPlans != providedPlans) { providedPlans = stabilizationPlans; loadedEdit = null }
        if (latestEdit != edit) navigation = TimelineNavigation(edit)
        latestEdit = edit
        fun renderingState(value: EditState?): EditState? = value?.copy()?.also { it.timeline.bookmarks = emptyList() }
        val changed = renderingState(loadedEdit) != renderingState(edit)
        if (loadedTimeline?.audio?.map { Triple(it.id, it.volumeDB, it.muted) to (it.fadeIn to it.fadeOut) } !=
            edit.timeline.audio.map { Triple(it.id, it.volumeDB, it.muted) to (it.fadeIn to it.fadeOut) }) gainRevision += 1
        duration = edit.duration
        // A grade or effect-look change keeps the playlist: only the players' grades and effect clocks
        // change, and the paused frame is drawn again. Effects that retime the clip rebuild it.
        fun uncolored(value: EditState?): EditState? = renderingState(value)?.also { state -> state.clips = state.clips.map { it.copy(color = null, videoEffects = null) } }
        if (changed && segments.isNotEmpty() && loadedFolder == folder && uncolored(loadedEdit) == uncolored(edit) && PreviewPlan.segments(edit.timeline) == segments) {
            loadedEdit = edit.copy(); loadedTimeline = edit.timeline.copy()
            val enabled = gradeEffects
            setGrades(folder)
            // The players take a newly installed effect only with a fresh playlist.
            if (setEffectLayers() || gradeEffects != enabled) setPlaylist(project, folder) else if (!playing) redraw()
            transitionJob?.cancel()
            prepareTransitions(edit, project, folder)
            tick()
            if (includeTimelineDetails) loadTimelineDetails(edit, project, folder, changed = false)
            return
        }
        if (changed) {
            val wasPlaying = playing
            pause()
            transitionJob?.cancel()
            loading = true; loadingLabel = "Opening timeline"
            segments = PreviewPlan.segments(edit.timeline)
            loadedEdit = edit.copy(); loadedTimeline = edit.timeline.copy()
            titleRanges = TitleMotionRanges(edit)
            installMix(edit.timeline, project, folder)
            loadGeometry(edit, project, folder)
            setPlaylist(project, folder)
            prepareStabilization(edit, project, folder)
            if (wasPlaying && previewRange != null && segments.isNotEmpty()) togglePlay()
            loading = audioJob?.isActive == true || transitionJob?.isActive == true || stabilizationJob?.isActive == true
            tick()
        }
        if (!includeTimelineDetails) return
        loadTimelineDetails(edit, project, folder, changed)
    }

    /** Builds the playlist of [segments] and seeks to the playhead. */
    private fun setPlaylist(project: Project, folder: File) {
        loadedProject = project; loadedFolder = folder
        val transitions = HashMap<UUID, File>()
        val holdClips = HashMap<HoldFrames.Key, File>()
        if (segments.isEmpty()) {
            player.clearMediaItems(); playhead = 0.0
            layerPlayers.forEach { it.release() }; layerPlayers.clear()
        } else {
                val sources = project.sources.associateBy { it.id }
                val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(appContext))
                // The export's timing (MediaRenderer.clipItem/speed): a speed run is
                // retimed by Media3's own speed-changing source, and a hold plays
                // its one frame slowed to the hold's length.
                fun clipped(segment: PreviewSegment, type: Int?, piece: PreviewSegment.Piece? = segment.video): MediaSource? {
                    piece ?: return null
                    piece.transition?.let { window ->
                        // The export's rendered transition clip, played from the segment's offset into the window.
                        val file = transitionClip(window, project, folder) ?: return null
                        transitions[window.id] = file
                        val clip = ClippingMediaSource.Builder(factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(file))))
                            .setStartPositionUs(Math.round(piece.sourceStart * 1_000_000)).setEndPositionUs(Math.round(piece.sourceEnd * 1_000_000)).build()
                        return if (type == null) clip else FilteringMediaSource(clip, type)
                    }
                    val source = sources[piece.source] ?: return null
                    val file = File(folder, source.filename)
                    if (piece.isHold) {
                        // The export's hold clip (VideoEffectSampling.kt): the held picture at 30 fps, so its effects animate.
                        val key = HoldFrames.Key(piece.source, piece.sourceStart)
                        VideoEffectHoldClip(piece.source, file, piece.sourceStart).output(folder).takeIf { it.exists() }?.let { held ->
                            holdClips[key] = held
                            val clip = ClippingMediaSource.Builder(factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(held))))
                                .setStartPositionUs(0).setEndPositionUs(Math.round(segment.duration * 1_000_000)).build()
                            return if (type == null) clip else FilteringMediaSource(clip, type)
                        }
                    }
                    val item = MediaItem.fromUri(Uri.fromFile(file))
                    val hold = if (piece.isHold) HoldFrames.read(file, piece.sourceStart) ?: return null else null
                    val startUs = hold?.startUs ?: (piece.sourceStart * 1_000_000).toLong()
                    // A hold keeps only its frame's timestamp (1 µs) and slows it so far that
                    // every position in the segment, including a seek into its middle, maps
                    // back to that timestamp: the renderer never treats the frame as before
                    // the seek target. The merged clock ends the item after the hold.
                    val endUs = if (hold != null) startUs + 1 else (piece.sourceEnd * 1_000_000).toLong()
                    var clip: MediaSource = ClippingMediaSource.Builder(factory.createMediaSource(item))
                        .setStartPositionUs(startUs)
                        .setEndPositionUs(endUs)
                        .setEnableInitialDiscontinuity(true)
                        .build()
                    val speed = if (hold != null) 1.0 / (4.0 * max(1.0, segment.duration * 1_000_000)) else segment.rate(piece)
                    if (hold != null || abs(speed - 1) > ExportPlan.rateTolerance) {
                        clip = androidx.media3.transformer.PreviewSpeedSource.create(clip, ConstantSpeed(speed.toFloat()), startUs)
                    }
                    return if (type == null) clip else FilteringMediaSource(clip, type)
                }
                val clocks = ProgressiveMediaSource.Factory(DataSource.Factory { PreviewClock.Source() }, ExtractorsFactory { arrayOf(WavExtractor()) })
                val playlist = segments.map { segment ->
                    val frames = segment.clockFrames
                    val clock = clocks.createMediaSource(MediaItem.fromUri(PreviewClock.uri(frames.first, max(1L, frames.last - frames.first + 1))))
                    clipped(segment, C.TRACK_TYPE_VIDEO)?.let { MergingMediaSource(true, true, it, clock) } ?: clock
                }
                setGrades(folder)
                setEffectLayers()
                if (effectsMode != installedEffectsMode) installEffects()
                player.setMediaSources(playlist)
                player.prepare()
                // The pictures behind the front one: the same items, picture only.
                val depth = segments.maxOf { it.layers.size }
                while (layerPlayers.size < depth) layerPlayers += makeLayerPlayer()
                while (layerPlayers.size > depth) layerPlayers.removeAt(layerPlayers.lastIndex).release()
                for ((index, layer) in layerPlayers.withIndex()) {
                    layer.setMediaSources(segments.map { segment ->
                        val frames = segment.clockFrames
                        val clock = clocks.createMediaSource(MediaItem.fromUri(PreviewClock.uri(frames.first, max(1L, frames.last - frames.first + 1))))
                        clipped(segment, C.TRACK_TYPE_VIDEO, segment.layers.getOrNull(index))?.let { MergingMediaSource(true, true, it, clock) } ?: clock
                    })
                    layer.prepare()
                }
                playhead = min(playhead, duration)
                performSeek(playhead)
        }
        playlistTransitions = transitions
        playlistHolds = holdClips
    }

    private fun loadTimelineDetails(edit: EditState, project: Project, folder: File, changed: Boolean) {
        val pictureSources = edit.timeline.video.map { it.content.sourceID }.toSet()
        val allSources = pictureSources + edit.timeline.audio.map { it.sourceID }
        if (!changed && pictureSources.all { it in completedThumbnails } && allSources.all { it in waveforms }) return
        detailJob?.cancel()
        detailJob = scope.launch {
            val budget = max(1, min(120, 360 / max(1, pictureSources.size)))
            thumbnails = thumbnails.filterKeys { it in pictureSources }
            waveforms = waveforms.filterKeys { it in allSources }
            completedThumbnails.retainAll(pictureSources)
            for (source in project.sources) {
                if (source.id !in allSources) continue
                try {
                    if (source.id !in waveforms) waveforms = waveforms + (source.id to SourceMediaCache.waveform(source, folder))
                    if (source.id in pictureSources && source.id !in completedThumbnails) {
                        val images = SourceMediaCache.thumbnails(source, folder, budget) { partial ->
                            handler.post { if (!released) thumbnails = thumbnails + (source.id to partial) }
                        }
                        thumbnails = thumbnails + (source.id to images); completedThumbnails += source.id
                    }
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    android.util.Log.w("YouRoll", "Timeline details failed for ${source.filename}", failure)
                }
            }
        }
    }
}
