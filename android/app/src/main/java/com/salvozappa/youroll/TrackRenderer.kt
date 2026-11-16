// Port of YouRec/TrackRenderer.swift (partial: `placed`, the picture stack of
// `timeline`'s instructions and VideoToolCompositor `renderTracks`' order)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS composes each instruction in its custom compositor: every picture is
// rendered into a canvas-sized layer image (framing, crop, titles), then
// `placed` moves it into its placement window and clips it to the visible part
// of that window, and the layers are drawn back to front. YouRoll does the same
// with one pure geometry function shared by the export and the preview:
//
// - Export (MediaRenderer.kt): each depth of the picture stack is one Media3
//   video sequence ([ExportPlan.layers]). A placed piece ends its effect chain
//   with [PlacementTransformation], so its frame is the canvas with the picture
//   in its window and transparent elsewhere; [PlacementCompositorSettings]
//   stacks the sequences in DefaultVideoCompositor (input 0, the front picture,
//   is drawn last) and hides a sequence while it is in a gap.
// - Preview (EditorController.kt, PlayerSurface in EditorView.kt): one player
//   per depth, each view moved by the same transform and clipped to the same rect.
// - Transitions (TransitionRenderer.kt): a transition window is one layer of
//   the stack, its rendered clip, at the slot of its front-most participant.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.max
import kotlin.math.min

object TrackRenderer {
    /** Swift `audioKey(source:effects:)`: one processed audio per source and enabled chain. */
    fun audioKey(source: UUID, effects: List<AudioEffect>): String =
        source.uuidString + "|" + toBase64(SwiftJSON.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AudioEffect.serializer()),
            effects.filter { it.enabled }).sortedKeys().encodedBytes())

    /**
     * Swift `preparedTimeline`'s requests: the source and chain of every
     * enhancement region that [pieces] play, by [audioKey].
     */
    fun enhancementRequests(project: Project, pieces: List<ExportPlan.Piece>): Map<String, Pair<SourceMedia, List<AudioEffect>>> {
        val used = pieces.mapNotNull { (it as? ExportPlan.Piece.Clip)?.audioKey }.toSet()
        val result = HashMap<String, Pair<SourceMedia, List<AudioEffect>>>()
        for (item in project.edit.timeline.audio) for (region in item.enhancements) {
            if (region.effects.none { it.enabled }) continue
            val key = audioKey(item.sourceID, region.effects)
            if (key !in used || key in result) continue
            val source = project.sources.firstOrNull { it.id == item.sourceID } ?: throw ProjectError.missingMedia
            result[key] = source to region.effects
        }
        return result
    }

    /**
     * Swift `placed(_:settings:canvas:)` as geometry, in canvas pixels with a
     * top-left origin. [transform] maps the layer image (the picture framed on
     * the canvas) into its window; only [clip] of the result is visible.
     * [source] is the part of the layer image that lands in [clip].
     */
    data class Placed(val transform: CGAffineTransform, val clip: CGRect, val source: CGRect) {
        val isVisible: Boolean get() = clip.width > 0.5 && clip.height > 0.5
    }

    fun placed(settings: VideoPlacement, canvas: CGSize): Placed {
        val bounds = CGRect(0.0, 0.0, canvas.width, canvas.height)
        val window = settings.window(canvas)
        val crop = settings.framing.crop(canvas, if (settings.fill) window.size else canvas)
        val scale = if (settings.fill) max(window.width / crop.width, window.height / crop.height)
            else min(window.width / crop.width, window.height / crop.height)
        if (!(scale.isFinite() && scale > 0)) return Placed(CGAffineTransform.identity, CGRect.zero, CGRect.zero)
        val transform = CGAffineTransform.translation(-crop.midX, -crop.midY)
            .concatenating(CGAffineTransform.scale(scale, scale))
            .concatenating(CGAffineTransform.translation(window.midX, window.midY))
        // The image is cropped to its framing rect before it is moved, then to the visible window and the canvas.
        val picture = CGRect(window.midX - crop.width * scale / 2, window.midY - crop.height * scale / 2, crop.width * scale, crop.height * scale)
        val clip = intersection(intersection(picture, settings.visibleWindow(canvas)), bounds)
        val source = CGRect(crop.midX + (clip.minX - window.midX) / scale, crop.midY + (clip.minY - window.midY) / scale,
            clip.width / scale, clip.height / scale)
        return Placed(transform, clip, source)
    }

    fun intersection(a: CGRect, b: CGRect): CGRect {
        val x = max(a.minX, b.minX)
        val y = max(a.minY, b.minY)
        val right = min(a.maxX, b.maxX)
        val bottom = min(a.maxY, b.maxY)
        return if (right > x && bottom > y) CGRect(x, y, right - x, bottom - y) else CGRect(x, y, 0.0, 0.0)
    }

    /** One layer of a picture stack: a timeline picture (with its window while placed), or a rendered transition window. */
    data class Layer(val item: VideoTimelineItem?, val placement: VideoPlacement?, val window: TransitionWindow? = null)

    /**
     * The layers one compiled interval shows, front first (Swift: the
     * instruction's `order` sorted by `VideoPlacement.ordered` and drawn back
     * to front by `renderTracks`). A transition's two pictures are composited
     * together, unplaced, at the slot of the front-most one: its rendered clip
     * inside the transition window, else the participant its progress selects
     * (`pictureAt`). Without a placement the front layer covers the canvas, so
     * it is the only one decoded; with one, every picture comes with its window.
     */
    fun stack(timeline: TrackTimeline, interval: CompiledTimelineInterval, windows: List<TransitionWindow> = timeline.transitionWindows()): List<Layer> {
        val byID = timeline.video.associateBy { it.id }
        val region = interval.placementID?.let { id -> timeline.placements.firstOrNull { it.id == id } }
        val window = windows.firstOrNull { it.interval.contains(interval.interval.start) }
        val relation = window?.relation ?: interval.transitionIDs.firstNotNullOfOrNull { id -> timeline.transitions.firstOrNull { it.id == id } }
        val participants = setOfNotNull(relation?.outgoing, relation?.incoming)
        val order = if (region != null) VideoPlacement.ordered(interval.video, region.videos) else interval.video
        val pairTop = order.lastOrNull { it in participants }
        val shown = if (window == null && relation != null) timeline.pictureAt(interval)?.takeIf { it.id in participants } ?: pairTop?.let { byID[it] } else null
        val layers = mutableListOf<Layer>() // back to front
        // A fill colour (before an opening or after a closing transition) with no participant on screen.
        if (window != null && pairTop == null) layers += Layer(null, null, window)
        for (id in order) {
            if (id in participants) {
                if (id == pairTop) layers += if (window != null) Layer(null, null, window) else Layer(shown, null)
                continue
            }
            val item = byID[id] ?: continue
            layers += Layer(item, region?.videos?.get(id))
        }
        val front = layers.asReversed().filter { it.item != null || it.window != null }
        return if (region == null) front.take(1) else front
    }
}

/**
 * The export half of `placed`: moves the framed layer picture (canvas sized)
 * into its window and clips it there. Two matrix steps, so that Media3 clips
 * the picture between them: a [androidx.media3.effect.Crop] to the visible part
 * of the layer image, then this matrix, which maps that part onto the visible
 * rect of a canvas-sized, otherwise transparent frame.
 */
class PlacementTransformation(private val clip: CGRect, private val canvas: CGSize) : androidx.media3.effect.MatrixTransformation {
    private val matrix = android.graphics.Matrix().apply { setValues(ndcMatrix(clip, canvas)) }

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size =
        androidx.media3.common.util.Size(canvas.width.toInt(), canvas.height.toInt())

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = matrix

    companion object {
        /** Input NDC (the cropped source) → output NDC (y up), placing it on [clip]. Pure; JVM-tested. */
        fun ndcMatrix(clip: CGRect, canvas: CGSize): FloatArray = floatArrayOf(
            (clip.width / canvas.width).toFloat(), 0f, (2 * clip.midX / canvas.width - 1).toFloat(),
            0f, (clip.height / canvas.height).toFloat(), (1 - 2 * clip.midY / canvas.height).toFloat(),
            0f, 0f, 1f)

        /** The Media3 `Crop` bounds (left, right, bottom, top in input NDC) of [source] on the layer canvas. */
        fun cropBounds(source: CGRect, canvas: CGSize): FloatArray {
            fun x(value: Double) = (2 * value / canvas.width - 1).coerceIn(-1.0, 1.0).toFloat()
            fun y(value: Double) = (1 - 2 * value / canvas.height).coerceIn(-1.0, 1.0).toFloat()
            return floatArrayOf(x(source.minX), x(source.maxX), y(source.maxY), y(source.minY))
        }

        /** The effects that end a placed piece's chain, or null when the picture is entirely outside the canvas. */
        fun effects(settings: VideoPlacement, canvas: CGSize): List<androidx.media3.common.Effect>? {
            val placed = TrackRenderer.placed(settings, canvas)
            if (!placed.isVisible) return null
            val bounds = cropBounds(placed.source, canvas)
            if (!(bounds[1] > bounds[0] && bounds[3] > bounds[2])) return null
            return listOf(androidx.media3.effect.Crop(bounds[0], bounds[1], bounds[2], bounds[3]), PlacementTransformation(placed.clip, canvas))
        }
    }
}

/**
 * Stacks the picture sequences of an export: the output is the canvas, every
 * input keeps its canvas-sized frame (identity placement), and an input is
 * transparent while its sequence shows nothing ([visible] says when it does).
 * DefaultVideoCompositor draws input 0 last, so sequence 0 is the front picture.
 */
class PlacementCompositorSettings(private val canvas: CGSize, private val visible: (Int, Long) -> Boolean) : VideoCompositorSettings {
    private val shown: OverlaySettings = StaticOverlaySettings.Builder().build()
    private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

    override fun getOutputSize(inputSizes: MutableList<androidx.media3.common.util.Size>): androidx.media3.common.util.Size =
        androidx.media3.common.util.Size(canvas.width.toInt(), canvas.height.toInt())

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
        if (visible(inputId, presentationTimeUs)) shown else hidden
}
