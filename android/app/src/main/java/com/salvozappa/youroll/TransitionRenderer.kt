// Port of YouRec/TransitionRenderer.swift (with the transition parts of
// YouRec/TrackRenderer.swift and YouRec/VideoToolCompositor.swift `renderTracks`)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS draws every transition frame in its custom AVVideoCompositor with Core
// Image (`TransitionRenderer.composite`), and the same composition feeds the
// AVPlayer preview and the export. Media3 has no pluggable compositor for
// arbitrary two-input effects, so YouRoll renders each transition window once
// into a short H.264 clip in the project cache ("transition clip"):
//
// - `transitionWindows` (pure, JVM-tested) lists, per TimelineTransition, the
//   project interval whose picture is a blend (the picture transition, plus the
//   fill colour before an opening or after a closing transition) and the two
//   layers drawn there (outgoing picture, frozen for `still`; incoming picture).
// - `TransitionRenderer.render` decodes both sources with MediaCodec into
//   SurfaceTextures, frames each into the canvas exactly like the export
//   (crop transform or aspect fit, then its Mirror), draws each layer's image
//   overlays and titles over it (Swift
//   `render(layer, overlays:)`; for split screen once over the composite,
//   shared titles drawn once), blends them with one GLSL program that
//   implements every iOS style (`TransitionComposite` is its Kotlin twin, used
//   by the JVM tests), and encodes the window at 30 fps.
// - The export plan (MediaRenderer.kt) and the preview playlist
//   (EditorController.kt) both play that same file over the window, so the
//   preview shows exactly the exported pixels. Sound needs no clip: both mix the
//   audio items with the transition ramps (`AudioEnvelopeSegment.inRamp/outRamp`).
//
// Simplified (TODO.md): Core Image's CIMotionBlur (whip) and CIBokehBlur
// (defocus) are approximated by a 25-tap line blur and a 48-sample disc with a
// ring weight; a `still` holds the last frame decoded before the freeze rather
// than the last frame on the 30 fps grid; HDR sources are not tone-mapped
// inside a transition clip; stabilisation is not drawn (that tool is not
// ported). Each picture is graded (`ColorLUT.GRADE`) before its framing, and
// its video effects are drawn on the framed picture before the blend and
// before its titles (Swift `render(layer)`: `preparedImage`, effects,
// overlays), by the same renderer as the preview and the export
// (MetalVideoEffects.kt); a cadence hold shows the frame at or before its
// boundary (iOS: the first at or after it).
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// MARK: - Plan (pure)

/**
 * One picture drawn in a transition window: a video item, framed into the
 * canvas like the export, frozen from [freezeStart] on (a `still`).
 */
data class TransitionLayer(
    val item: UUID,
    val sourceID: UUID,
    val start: EditTime,
    val mapping: SourcePlaybackMap,
    val crop: CropSettings?,
    val freezeStart: EditTime? = null,
    /** The item's titles, on its source clock. */
    val titles: List<TextOverlay> = emptyList(),
    /** The item's flips, applied after its framing (Swift `transform.concatenating(mirror.transform)`). */
    val mirror: MirrorSettings? = null,
    /** The item's image overlays, on its source clock, drawn before its titles. */
    val images: List<ImageOverlay> = emptyList(),
    /** The item's grade, applied to its source picture before the framing (Swift `preparedImage`). */
    val color: ColorSettings? = null,
    /** The item's clip, for its video effects (null without effects). */
    val clip: TimelineClip? = null,
    /** The item's `stabilizationReference`: its camera correction, after the grade and before the framing. */
    val stabilization: StabilizationSettings? = null,
) {
    val end: EditTime get() = start + mapping.duration

    /** Source seconds shown at project time [time], or null where this item has no picture. */
    fun sourceTime(time: EditTime): Double? {
        val freeze = freezeStart
        if (freeze != null && time >= freeze) {
            // The actual last visible outgoing frame: the one shown just before the freeze.
            val local = minOf(mapping.duration, maxOf(EditTime.zero, freeze - start))
            return max(0.0, (mapping.source(local) ?: return null) - 0.000001)
        }
        val local = time - start
        if (local < EditTime.zero || local >= mapping.duration) return null
        return mapping.source(local)
    }
}

/** The project interval a TimelineTransition blends, with its two layers. */
data class TransitionWindow(
    val relation: TimelineTransition,
    val interval: EditInterval,
    val first: TransitionLayer?,
    val second: TransitionLayer?,
) {
    /** The timeline the window was planned from (title animation clocks); not part of its identity. */
    var timeline: TrackTimeline = TrackTimeline()
        internal set

    val id: UUID get() = relation.id
    val timing: TransitionTiming get() = TransitionTiming.of(relation)
    /** Swift `TransitionRenderer.fill`, used for the absent neighbour at a project edge. */
    val fill: TransitionColor get() = TransitionComposite.fill(relation.settings)
    /** Swift `VideoToolCompositor`: an edge colour fade is a dissolve to or from its fill. */
    val drawnSettings: ClipTransition
        get() = if (relation.settings.style == TransitionStyle.fadeColor && relation.edge != 0) relation.settings.copy(style = TransitionStyle.dissolve) else relation.settings
    fun progress(time: EditTime): Double = timing.progress(time.seconds - relation.origin.seconds)
}

/**
 * Every transition window of [this] timeline, in time order. The picture
 * transition itself, plus the fill colour shown before an opening or after a
 * closing transition (Swift draws the fill whenever a relation has no picture
 * on that side). Windows of different relations never overlap.
 */
fun TrackTimeline.transitionWindows(): List<TransitionWindow> {
    val duration = duration
    return transitions.mapNotNull { relation ->
        val timing = TransitionTiming.of(relation)
        if (timing.videoDuration <= 0) return@mapNotNull null
        val origin = relation.origin
        val start = if (relation.edge == -1) origin + EditTime.of(timing.lower) else origin
        val end = if (relation.edge == 1) origin + EditTime.of(timing.upper) else origin + EditTime.of(timing.videoDuration)
        val interval = EditInterval(maxOf(EditTime.zero, start), minOf(duration, end))
        if (interval.isEmpty) return@mapNotNull null
        val outgoing = relation.outgoing?.let { id -> video.firstOrNull { it.id == id } }
        val incoming = relation.incoming?.let { id -> video.firstOrNull { it.id == id } }
        // TrackRenderer: a `still` freezes its outgoing picture from the relation's origin.
        val freeze = if (relation.settings.style == TransitionStyle.still && outgoing != null) maxOf(outgoing.start, minOf(outgoing.end, origin)) else null
        TransitionWindow(relation, interval,
            outgoing?.let { TransitionLayer(it.id, it.content.sourceID, it.start, it.mapping, it.content.crop, freeze, it.content.texts.orEmpty(),
                it.content.mirror?.takeIf { m -> m.horizontal || m.vertical }, it.content.images.orEmpty(), it.content.color?.takeIf { c -> !c.isIdentity },
                it.content.takeIf { c -> c.hasVideoEffects }, it.content.stabilizationReference) },
            incoming?.let { TransitionLayer(it.id, it.content.sourceID, it.start, it.mapping, it.content.crop, titles = it.content.texts.orEmpty(),
                mirror = it.content.mirror?.takeIf { m -> m.horizontal || m.vertical }, images = it.content.images.orEmpty(),
                color = it.content.color?.takeIf { c -> !c.isIdentity }, clip = it.content.takeIf { c -> c.hasVideoEffects },
                stabilization = it.content.stabilizationReference) })
    }.sortedBy { it.interval.start }.onEach { it.timeline = this }
}

/**
 * The picture of [interval] outside transition windows: inside a relation's
 * footprint the participant shown by its progress (outgoing before, incoming
 * after the picture transition), else the front-most picture.
 */
fun TrackTimeline.pictureAt(interval: CompiledTimelineInterval): VideoTimelineItem? {
    val time = interval.interval.start
    for (id in interval.transitionIDs) {
        val relation = transitions.firstOrNull { it.id == id } ?: continue
        val progress = TransitionTiming.of(relation).progress(time.seconds - relation.origin.seconds)
        val participant = if (progress <= 0) relation.outgoing else relation.incoming
        val item = participant?.let { p -> video.firstOrNull { it.id == p } }
        if (item != null && item.interval.contains(time)) return item
    }
    return interval.video.lastOrNull()?.let { id -> video.firstOrNull { it.id == id } }
}

/** Splits [0, duration) into transition windows and the plain spans between them. */
fun TrackTimeline.pictureSpans(): List<Pair<EditInterval, TransitionWindow?>> {
    val result = mutableListOf<Pair<EditInterval, TransitionWindow?>>()
    var cursor = EditTime.zero
    for (window in transitionWindows()) {
        val start = maxOf(cursor, window.interval.start)
        if (start > cursor) result += EditInterval(cursor, start) to null
        if (window.interval.end > start) result += EditInterval(start, window.interval.end) to window
        cursor = maxOf(cursor, window.interval.end)
    }
    if (duration > cursor) result += EditInterval(cursor, duration) to null
    return result
}

// MARK: - Composite (pure twin of the GLSL program)

/**
 * Swift `TransitionRenderer.composite`, one pixel at a time. Coordinates are
 * Core Image's: pixels from the bottom-left corner of the canvas. The images
 * are sRGB-encoded; blends happen in linear light, like Core Image's working
 * space. The GLSL program in [TransitionRenderer] evaluates the same formulas
 * with the same [Parameters].
 */
object TransitionComposite {
    data class Rgb(val r: Double, val g: Double, val b: Double) {
        operator fun plus(o: Rgb) = Rgb(r + o.r, g + o.g, b + o.b)
        operator fun times(s: Double) = Rgb(r * s, g * s, b * s)
    }

    fun fill(settings: ClipTransition): TransitionColor =
        if (settings.style == TransitionStyle.fadeColor) settings.resolvedFadeColor else TransitionColor.black

    /** Swift `splitRects`, in Core Image coordinates: (outgoing, incoming). */
    fun splitRects(settings: ClipTransition, canvas: CGSize): Pair<CGRect, CGRect> {
        if (settings.axis == TransitionAxis.horizontal) {
            val split = canvas.width * settings.divider
            return CGRect(0.0, 0.0, split, canvas.height) to CGRect(split, 0.0, canvas.width - split, canvas.height)
        }
        val split = canvas.height * settings.divider
        return CGRect(0.0, canvas.height - split, canvas.width, split) to CGRect(0.0, 0.0, canvas.width, canvas.height - split)
    }

    /** Swift `framed`: the part of [content] (Core Image rect) shown in [viewport]. */
    fun framedCrop(framing: TransitionFraming, viewport: CGRect, content: CGRect): CGRect {
        val top = framing.crop(content.size, viewport.size)
        return CGRect(content.minX + top.minX, content.maxY - top.maxY, top.width, top.height)
    }

    /** Swift `contentBounds`: the canvas rect (Core Image coordinates) a framed picture covers, without bars. */
    fun contentBounds(transform: CGAffineTransform, source: CGSize, canvas: CGSize): CGRect {
        val corners = listOf(CGPoint(0.0, 0.0), CGPoint(source.width, 0.0), CGPoint(0.0, source.height), CGPoint(source.width, source.height))
            .map { it.applying(transform) }
        val minX = max(0.0, corners.minOf { it.x }); val maxX = min(canvas.width, corners.maxOf { it.x })
        val minY = max(0.0, corners.minOf { it.y }); val maxY = min(canvas.height, corners.maxOf { it.y })
        if (maxX <= minX || maxY <= minY) return CGRect(0.0, 0.0, canvas.width, canvas.height)
        return CGRect(minX, canvas.height - maxY, maxX - minX, maxY - minY)
    }

    /** The values the shader receives as uniforms. */
    class Parameters(
        val style: Int,
        val t: Double,
        val eased: Double,
        val fill: Rgb,
        val flashSecond: Boolean,
        val wipeOrigin: CGPoint, val wipeDirection: CGPoint, val wipeFeather: Double,
        val hitchWeights: Rgb, val hitchBias: Double,
        val whipVertical: Boolean, val whipFirstOffset: Double, val whipSecondOffset: Double, val whipRadius: Double,
        val defocusRadius: Double, val defocusBlend: Double,
        val splitFirst: CGRect, val splitSecond: CGRect, val splitFirstCrop: CGRect, val splitSecondCrop: CGRect, val splitLine: CGRect,
    ) {
        companion object {
            const val DISSOLVE = 0; const val FADE = 1; const val WIPE = 2; const val SPLIT = 3; const val HITCHCOCK = 4
            const val WHIP = 5; const val FLASH = 6; const val DEFOCUS = 7
        }
    }

    fun parameters(settings: ClipTransition, progress: Double, canvas: CGSize, duration: Double? = null, framesPerSecond: Double = 30.0,
                   fill: TransitionColor = fill(settings), firstContent: CGRect = CGRect(0.0, 0.0, canvas.width, canvas.height),
                   secondContent: CGRect = CGRect(0.0, 0.0, canvas.width, canvas.height)): Parameters {
        val t = min(1.0, max(0.0, progress))
        val eased = t * t * (3 - 2 * t)
        val style = when (settings.style) {
            TransitionStyle.dissolve, TransitionStyle.still -> Parameters.DISSOLVE
            TransitionStyle.fadeColor -> Parameters.FADE; TransitionStyle.wipe -> Parameters.WIPE; TransitionStyle.split -> Parameters.SPLIT
            TransitionStyle.hitchcock -> Parameters.HITCHCOCK; TransitionStyle.whip -> Parameters.WHIP; TransitionStyle.flashCut -> Parameters.FLASH
            TransitionStyle.defocus -> Parameters.DEFOCUS
        }
        // Wipe: a linear gradient mask moving along the angle (degrees clockwise on screen).
        val angle = settings.angle * PI / 180
        val dx = cos(angle); val dy = -sin(angle)
        val span = abs(dx) * canvas.width + abs(dy) * canvas.height
        val feather = if (settings.hardEdge == true) 0.5 else max(1.0, span * 0.035)
        val distance = (span + 2 * feather) * eased - span / 2 - feather
        val center = CGPoint(canvas.width / 2 + dx * distance, canvas.height / 2 + dy * distance)
        val p0 = CGPoint(center.x - dx * feather, center.y - dy * feather)
        // Hitchcock: perceptual luminance key of the outgoing picture.
        val keyFeather = 0.08
        val threshold = eased * (1 + 2 * keyFeather) - keyFeather
        val sign = if (settings.highlightsFirst == true) 1.0 else -1.0
        val weights = Rgb(sign * 0.2126 / keyFeather, sign * 0.7152 / keyFeather, sign * 0.0722 / keyFeather)
        val bias = (if (settings.highlightsFirst == true) threshold - 1 else threshold) / keyFeather + 0.5
        // Whip pan: both pictures slide along one axis under a motion blur.
        val vertical = settings.axis == TransitionAxis.vertical
        val whipDistance = (if (vertical) canvas.height else canvas.width) * 0.65 * (if (settings.reverse) -1.0 else 1.0)
        val whipRadius = min(canvas.width, canvas.height) * 0.12 * sin(PI * t)
        // Flash Cut: exact single frames of the incoming picture.
        val length = duration ?: settings.duration
        val frame = floor(t * length * framesPerSecond + 0.000001).toInt()
        val flash = frame in settings.flashFrames(length, framesPerSecond)
        // Defocus.
        val defocusRadius = min(canvas.width, canvas.height) * 0.05 * (settings.blurAmount ?: 0.75) * sin(PI * t)
        val defocusBlend = min(1.0, max(0.0, (t - 0.35) / 0.3))
        // Split screen.
        val (a, b) = splitRects(settings, canvas)
        val lineWidth = max(1.0, min(canvas.width, canvas.height) * 0.002)
        val line = if (settings.axis == TransitionAxis.horizontal) CGRect(a.maxX - lineWidth / 2, 0.0, lineWidth, canvas.height)
            else CGRect(0.0, a.minY - lineWidth / 2, canvas.width, lineWidth)
        return Parameters(style, t, eased, Rgb(fill.red, fill.green, fill.blue), flash,
            p0, CGPoint(dx, dy), feather, weights, bias,
            vertical, -eased * whipDistance, (1 - eased) * whipDistance, whipRadius,
            defocusRadius, defocusBlend,
            a, b, framedCrop(settings.first, a, firstContent), framedCrop(settings.second, b, secondContent), line)
    }

    fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    fun encoded(c: Double): Double { val v = min(1.0, max(0.0, c)); return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055 }
    private fun lin(c: Rgb) = Rgb(linear(c.r), linear(c.g), linear(c.b))
    private fun enc(c: Rgb) = Rgb(encoded(c.r), encoded(c.g), encoded(c.b))
    private fun mixLinear(a: Rgb, b: Rgb, t: Double): Rgb = enc(lin(a) * (1 - t) + lin(b) * t)
    private fun clamp01(v: Double) = min(1.0, max(0.0, v))

    /** The 48 disc offsets (unit radius) and weights of the defocus kernel; the shader unrolls the same spiral. */
    val discSamples: List<Triple<Double, Double, Double>> = (0 until 48).map { i ->
        val r = sqrt((i + 0.5) / 48)
        val theta = i * 2.399963229728653
        Triple(r * cos(theta), r * sin(theta), if (r > 0.9) 1.12 else 1.0)
    }

    /** One composited pixel at Core Image position ([x], [y]); [first]/[second] sample the framed pictures there. */
    fun pixel(first: (Double, Double) -> Rgb, second: (Double, Double) -> Rgb, settings: ClipTransition, progress: Double, canvas: CGSize,
              x: Double, y: Double, duration: Double? = null, framesPerSecond: Double = 30.0, fill: TransitionColor = fill(settings)): Rgb {
        val p = parameters(settings, progress, canvas, duration, framesPerSecond, fill)
        fun clampedSample(image: (Double, Double) -> Rgb, px: Double, py: Double) =
            image(min(canvas.width - 0.5, max(0.5, px)), min(canvas.height - 0.5, max(0.5, py)))
        if (p.t <= 0) return first(x, y)
        if (p.t >= 1) return second(x, y)
        val a = first(x, y); val b = second(x, y)
        return when (p.style) {
            Parameters.FADE -> if (p.t < 0.5) mixLinear(a, p.fill, min(1.0, p.t * 2)) else mixLinear(p.fill, b, max(0.0, p.t * 2 - 1))
            Parameters.WIPE -> {
                val s = clamp01(((x - p.wipeOrigin.x) * p.wipeDirection.x + (y - p.wipeOrigin.y) * p.wipeDirection.y) / (2 * p.wipeFeather))
                mixLinear(a, b, 1 - s)
            }
            Parameters.HITCHCOCK -> {
                val key = Rgb(clamp01(a.r), clamp01(a.g), clamp01(a.b))
                val mask = clamp01(key.r * p.hitchWeights.r + key.g * p.hitchWeights.g + key.b * p.hitchWeights.b + p.hitchBias)
                mixLinear(a, b, mask)
            }
            Parameters.WHIP -> {
                fun pan(image: (Double, Double) -> Rgb, offset: Double): Rgb {
                    var sum = Rgb(0.0, 0.0, 0.0); var total = 0.0
                    for (i in -12..12) {
                        val u = i / 12.0
                        val w = kotlin.math.exp(-2.0 * u * u)
                        val along = u * p.whipRadius
                        val sx = if (p.whipVertical) x else x - offset + along
                        val sy = if (p.whipVertical) y + offset + along else y
                        sum += lin(clampedSample(image, sx, sy)) * w; total += w
                    }
                    return sum * (1 / total)
                }
                val panned = pan(first, p.whipFirstOffset) * (1 - p.eased) + pan(second, p.whipSecondOffset) * p.eased
                enc(panned)
            }
            Parameters.FLASH -> if (p.flashSecond) b else a
            Parameters.DEFOCUS -> {
                fun optical(image: (Double, Double) -> Rgb): Rgb {
                    var sum = Rgb(0.0, 0.0, 0.0); var total = 0.0
                    for ((ox, oy, w) in discSamples) {
                        sum += lin(clampedSample(image, x + ox * p.defocusRadius, y + oy * p.defocusRadius)) * w; total += w
                    }
                    return sum * (1 / total)
                }
                if (p.defocusBlend <= 0) enc(optical(first)) else if (p.defocusBlend >= 1) enc(optical(second))
                else enc(optical(first) * (1 - p.defocusBlend) + optical(second) * p.defocusBlend)
            }
            Parameters.SPLIT -> {
                fun inside(r: CGRect) = x >= r.minX && x < r.maxX && y >= r.minY && y < r.maxY
                fun framed(image: (Double, Double) -> Rgb, viewport: CGRect, crop: CGRect): Rgb {
                    val scale = viewport.width / max(0.001, crop.width)
                    return clampedSample(image, crop.minX + (x - viewport.minX) / scale, crop.minY + (y - viewport.minY) / scale)
                }
                var pair = when {
                    inside(p.splitSecond) -> framed(second, p.splitSecond, p.splitSecondCrop)
                    inside(p.splitFirst) -> framed(first, p.splitFirst, p.splitFirstCrop)
                    else -> Rgb(0.0, 0.0, 0.0)
                }
                if (inside(p.splitLine)) pair = Rgb(0.25, 0.25, 0.25)
                // Brief, restrained entry/exit; most of the interval holds both views.
                val edge = 0.15
                if (p.t < edge) mixLinear(a, pair, p.t / edge) else if (p.t > 1 - edge) mixLinear(pair, b, (p.t - 1 + edge) / edge) else pair
            }
            else -> mixLinear(a, b, p.eased)
        }
    }
}

// MARK: - Render jobs (Android)

/** One transition clip to render: a window on the project canvas, with its source files. */
data class TransitionRenderJob(
    val window: TransitionWindow,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    /** Source files by source ID (only the window's layers). */
    val files: Map<UUID, File>,
    /** Swift `installTitleClocks`: the titles' project-wide animation clocks. */
    val titleRanges: TitleMotionRanges = TitleMotionRanges(),
    /** The layers' overlay image files by filename. */
    val images: Map<String, File> = emptyMap(),
    /** The layers' imported LUT files by filename. */
    val luts: Map<String, File> = emptyMap(),
    /** The layers' sources (stabilized layers look up their cached motion analysis). */
    val sources: Map<UUID, SourceMedia> = emptyMap(),
) {
    val duration: EditTime get() = window.interval.duration
    val frameCount: Int get() = max(1, ceil(duration.seconds * framesPerSecond - 0.000001).toInt())

    /**
     * Cache name: everything that changes a pixel of the clip, relative to the
     * window, so a transition that only moves in time (an edit elsewhere)
     * keeps its clip. Preview and export compute it from the same timeline and
     * canvas, so they share the file.
     */
    val key: String
        get() {
            val start = window.interval.start
            val relation = window.relation
            val text = buildString {
                append("transition-v3|").append(width).append('x').append(height).append('@').append(framesPerSecond)
                append('|').append(SwiftJSON.encodeToString(ClipTransition.serializer(), relation.settings.copy(id = UUID(0, 0), nextClipID = null)))
                append('|').append(relation.factor).append('|').append(relation.edge)
                append('|').append((relation.origin - start).ticks).append('|').append(window.interval.duration.ticks)
                for (layer in listOf(window.first, window.second)) {
                    append('|')
                    if (layer == null) { append("fill"); continue }
                    val file = files[layer.sourceID]
                    append(file?.name).append(':').append(file?.length()).append(':').append((layer.start - start).ticks)
                    // Only the part of the item inside the window is drawn.
                    val local = EditInterval(maxOf(EditTime.zero, start - layer.start), minOf(layer.mapping.duration, window.interval.end - layer.start))
                    append(':').append(if (local.isEmpty) "-" else SwiftJSON.encodeToString(SourcePlaybackMap.serializer(), layer.mapping.sliced(local)))
                    append(':').append(layer.crop?.let { SwiftJSON.encodeToString(CropSettings.serializer(), it.copy(id = UUID(0, 0))) })
                    append(':').append(layer.freezeStart?.let { "${(it - start).ticks}/${layer.sourceTime(it)}" })
                    layer.mirror?.let { append(":mirror").append(it.horizontal).append(it.vertical) }
                    layer.stabilization?.let { append(":stabilize").append(SwiftJSON.encodeToString(StabilizationSettings.serializer(), it)) }
                    layer.color?.let { color ->
                        append(":color").append(SwiftJSON.encodeToString(ColorSettings.serializer(), color.copy(id = UUID(0, 0))))
                        for (lut in color.lutAssets) luts[lut.filename]?.let { append('/').append(it.length()) }
                    }
                    for (image in layer.images) {
                        val file = images[image.filename]
                        append(":image").append(SwiftJSON.encodeToString(ImageOverlay.serializer(), image.copy(id = UUID(0, 0))))
                            .append(file?.length()).append('/').append(file?.lastModified())
                    }
                    layer.clip?.videoEffects?.let { effects ->
                        append(":effects").append(SwiftJSON.encodeToString(SupportedVideoEffects, effects)).append(layer.clip.frozenFrame)
                    }
                    for (title in layer.titles) {
                        append(':').append(SwiftJSON.encodeToString(TextOverlay.serializer(), title))
                        for (time in listOf(start, window.interval.end)) {
                            titleRanges.range(title, time.seconds)?.let { append('@').append(it.start - start.seconds).append('/').append(it.endInclusive - start.seconds) }
                        }
                    }
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }

    fun file(folder: File): File = File(ProjectCacheLocation.root(folder), "transition-$key.mp4")

    companion object {
        /** Jobs for every window of [timeline]; null when a source file is missing. */
        fun all(timeline: TrackTimeline, sources: List<SourceMedia>, folder: File, width: Int, height: Int, fps: Int = 30): List<TransitionRenderJob> =
            TitleMotionRanges(EditState(timeline)).let { ranges -> timeline.transitionWindows().mapNotNull { of(it, sources, folder, width, height, fps, ranges) } }

        fun of(window: TransitionWindow, sources: List<SourceMedia>, folder: File, width: Int, height: Int, fps: Int = 30,
               titleRanges: TitleMotionRanges? = null): TransitionRenderJob? {
            val files = HashMap<UUID, File>()
            for (layer in listOfNotNull(window.first, window.second)) {
                val source = sources.firstOrNull { it.id == layer.sourceID } ?: return null
                files[layer.sourceID] = File(folder, source.filename)
            }
            val ranges = titleRanges ?: if (listOfNotNull(window.first, window.second).any { it.titles.isNotEmpty() }) TitleMotionRanges(EditState(window.timeline)) else TitleMotionRanges()
            val images = listOfNotNull(window.first, window.second).flatMap { it.images }.associate { it.filename to File(folder, it.filename) }
            val luts = listOfNotNull(window.first, window.second).flatMap { it.color?.lutAssets.orEmpty() }.associate { it.filename to File(folder, it.filename) }
            val media = sources.filter { source -> listOfNotNull(window.first, window.second).any { it.sourceID == source.id && it.stabilization != null } }.associateBy { it.id }
            return TransitionRenderJob(window, width, height, fps, files, ranges, images, luts, media)
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object TransitionRenderer {
    private val lock = Mutex()

    /**
     * Swift `TransitionRenderer.layer(... stabilization:)`: each stabilized
     * layer's correction, from the cached motion analysis (measured if missing).
     */
    private suspend fun corrections(job: TransitionRenderJob, folder: File): List<StabilizationCorrection?> =
        listOf(job.window.first, job.window.second).map { layer ->
            val reference = layer?.stabilization ?: return@map null
            val source = job.sources[layer.sourceID] ?: return@map null
            VideoStabilizer.correction(source, reference, folder)
        }

    /** Renders [job] into its cache file unless it already exists, and returns the file. */
    suspend fun render(job: TransitionRenderJob, folder: File): File = lock.withLock {
        val destination = job.file(folder)
        if (destination.exists() && destination.length() > 0) return@withLock destination
        val context = coroutineContext
        val corrections = corrections(job, folder)
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + ".partial")
            try {
                TransitionPipeline(job, corrections).use { it.encode(partial) { context.ensureActiveCompat() } }
                if (!partial.renameTo(destination)) throw IOException("The transition could not be prepared.")
            } finally { partial.delete() }
        }
        destination
    }

    /** One composited frame at project [time] (the editor's still). */
    suspend fun still(job: TransitionRenderJob, time: EditTime, folder: File? = null): Bitmap = lock.withLock {
        val corrections = if (folder != null) corrections(job, folder) else listOf(null, null)
        withContext(Dispatchers.IO) { TransitionPipeline(job, corrections).use { it.still(time) } }
    }

    private fun kotlin.coroutines.CoroutineContext.ensureActiveCompat() {
        val job = this[kotlinx.coroutines.Job]
        if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException("Transition render cancelled")
    }

    internal const val VERTEX = """
attribute vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
"""

    /**
     * A decoded source frame (OES) framed into the canvas; black outside the
     * picture. The picture is graded first (Swift `preparedImage`: `graded`,
     * then composited over black), with the export's grade (`ColorLUT.GRADE`).
     */
    internal val LAYER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTexture;
uniform mat3 uMap;
uniform mat4 uSurface;
uniform vec2 uCanvas;
""" + ColorLUT.GRADE + """
void main() {
    vec2 c = vec2(gl_FragCoord.x, uCanvas.y - gl_FragCoord.y);
    vec3 q = uMap * vec3(c, 1.0);
    if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec4 t = uSurface * vec4(q.x, 1.0 - q.y, 0.0, 1.0);
    gl_FragColor = vec4(grade(texture2D(uTexture, t.xy).rgb), 1.0);
}
"""

    /** A canvas-sized title bitmap (premultiplied), rows from the top. */
    internal const val OVERLAY = """
precision highp float;
uniform sampler2D uOverlay;
uniform vec2 uCanvas;
void main() { gl_FragColor = texture2D(uOverlay, vec2(gl_FragCoord.x / uCanvas.x, 1.0 - gl_FragCoord.y / uCanvas.y)); }
"""

    /** `TransitionComposite.pixel` in GLSL. gl_FragCoord uses Core Image's bottom-left origin. */
    internal val COMPOSITE: String = """
precision highp float;
uniform sampler2D uFirst;
uniform sampler2D uSecond;
uniform vec2 uCanvas;
uniform int uStyle;
uniform float uT;
uniform float uEased;
uniform vec3 uFill;
uniform float uFlashSecond;
uniform vec2 uWipeOrigin;
uniform vec2 uWipeDirection;
uniform float uWipeFeather;
uniform vec3 uHitchWeights;
uniform float uHitchBias;
uniform float uWhipVertical;
uniform float uWhipFirst;
uniform float uWhipSecond;
uniform float uWhipRadius;
uniform float uDefocusRadius;
uniform float uDefocusBlend;
uniform vec4 uSplitFirst;
uniform vec4 uSplitSecond;
uniform vec4 uSplitFirstCrop;
uniform vec4 uSplitSecondCrop;
uniform vec4 uSplitLine;
uniform vec3 uDisc[48];

vec3 toLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }
vec3 toEncoded(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec3 sampleFirst(vec2 p) { p = clamp(p, vec2(0.5), uCanvas - 0.5); return texture2D(uFirst, p / uCanvas).rgb; }
vec3 sampleSecond(vec2 p) { p = clamp(p, vec2(0.5), uCanvas - 0.5); return texture2D(uSecond, p / uCanvas).rgb; }
vec3 mixLinear(vec3 a, vec3 b, float t) { return toEncoded(mix(toLinear(a), toLinear(b), t)); }
bool inside(vec2 p, vec4 r) { return p.x >= r.x && p.x < r.x + r.z && p.y >= r.y && p.y < r.y + r.w; }

vec3 whipFirst(vec2 p) {
    vec3 sum = vec3(0.0); float total = 0.0;
    for (int i = -12; i <= 12; i++) {
        float u = float(i) / 12.0; float w = exp(-2.0 * u * u); float along = u * uWhipRadius;
        vec2 s = uWhipVertical > 0.5 ? vec2(p.x, p.y + uWhipFirst + along) : vec2(p.x - uWhipFirst + along, p.y);
        sum += toLinear(sampleFirst(s)) * w; total += w;
    }
    return sum / total;
}
vec3 whipSecond(vec2 p) {
    vec3 sum = vec3(0.0); float total = 0.0;
    for (int i = -12; i <= 12; i++) {
        float u = float(i) / 12.0; float w = exp(-2.0 * u * u); float along = u * uWhipRadius;
        vec2 s = uWhipVertical > 0.5 ? vec2(p.x, p.y + uWhipSecond + along) : vec2(p.x - uWhipSecond + along, p.y);
        sum += toLinear(sampleSecond(s)) * w; total += w;
    }
    return sum / total;
}
vec3 opticalFirst(vec2 p) {
    vec3 sum = vec3(0.0); float total = 0.0;
    for (int i = 0; i < 48; i++) { vec3 d = uDisc[i]; sum += toLinear(sampleFirst(p + d.xy * uDefocusRadius)) * d.z; total += d.z; }
    return sum / total;
}
vec3 opticalSecond(vec2 p) {
    vec3 sum = vec3(0.0); float total = 0.0;
    for (int i = 0; i < 48; i++) { vec3 d = uDisc[i]; sum += toLinear(sampleSecond(p + d.xy * uDefocusRadius)) * d.z; total += d.z; }
    return sum / total;
}

void main() {
    vec2 p = gl_FragCoord.xy;
    vec3 a = sampleFirst(p);
    vec3 b = sampleSecond(p);
    vec3 result;
    if (uT <= 0.0) result = a;
    else if (uT >= 1.0) result = b;
    else if (uStyle == 1) result = uT < 0.5 ? mixLinear(a, uFill, min(1.0, uT * 2.0)) : mixLinear(uFill, b, max(0.0, uT * 2.0 - 1.0));
    else if (uStyle == 2) {
        float s = clamp(dot(p - uWipeOrigin, uWipeDirection) / (2.0 * uWipeFeather), 0.0, 1.0);
        result = mixLinear(a, b, 1.0 - s);
    } else if (uStyle == 3) {
        vec3 pair = vec3(0.0);
        if (inside(p, uSplitSecond)) {
            float scale = uSplitSecond.z / max(0.001, uSplitSecondCrop.z);
            pair = sampleSecond(uSplitSecondCrop.xy + (p - uSplitSecond.xy) / scale);
        } else if (inside(p, uSplitFirst)) {
            float scale = uSplitFirst.z / max(0.001, uSplitFirstCrop.z);
            pair = sampleFirst(uSplitFirstCrop.xy + (p - uSplitFirst.xy) / scale);
        }
        if (inside(p, uSplitLine)) pair = vec3(0.25);
        if (uT < 0.15) result = mixLinear(a, pair, uT / 0.15);
        else if (uT > 0.85) result = mixLinear(pair, b, (uT - 0.85) / 0.15);
        else result = pair;
    } else if (uStyle == 4) {
        vec3 key = clamp(a, 0.0, 1.0);
        float mask = clamp(dot(key, uHitchWeights) + uHitchBias, 0.0, 1.0);
        result = mixLinear(a, b, mask);
    } else if (uStyle == 5) {
        result = toEncoded(whipFirst(p) * (1.0 - uEased) + whipSecond(p) * uEased);
    } else if (uStyle == 6) {
        result = uFlashSecond > 0.5 ? b : a;
    } else if (uStyle == 7) {
        if (uDefocusBlend <= 0.0) result = toEncoded(opticalFirst(p));
        else if (uDefocusBlend >= 1.0) result = toEncoded(opticalSecond(p));
        else result = toEncoded(opticalFirst(p) * (1.0 - uDefocusBlend) + opticalSecond(p) * uDefocusBlend);
    } else result = mixLinear(a, b, uEased);
    gl_FragColor = vec4(result, 1.0);
}
"""
}

// MARK: - GL pipeline (Android only)

/** EGL context with a recordable config; current on the thread that created it. Never terminates the shared display. */
internal class TransitionEGL : AutoCloseable {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IOException("EGL could not start.")
        // GLES 3 for the video effect shaders (GLSL ES 3.00); the transition programs are GLSL ES 1.00.
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) throw IOException("No recordable EGL configuration.")
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IOException("EGL context could not be created.")
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
    }

    fun windowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0).also {
            if (it == null || it == EGL14.EGL_NO_SURFACE) throw IOException("The encoder surface could not be attached (EGL ${EGL14.eglGetError()}).")
        }

    fun makeCurrent(surface: EGLSurface?) {
        val target = surface ?: pbuffer
        if (!EGL14.eglMakeCurrent(display, target, target, context)) throw IOException("EGL error ${EGL14.eglGetError()}.")
    }

    fun present(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
        if (!EGL14.eglSwapBuffers(display, surface)) throw IOException("The video encoder refused a frame (EGL ${EGL14.eglGetError()}).")
    }

    fun destroy(surface: EGLSurface) { makeCurrent(null); EGL14.eglDestroySurface(display, surface) }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}

/**
 * Sequential MediaCodec decoding into a SurfaceTexture. `frame(us)` shows the
 * frame presented at that source time (the last one at or before it).
 */
internal class TransitionFrameReader(file: File, val texture: Int) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val surfaceTexture = SurfaceTexture(texture)
    private val surface: Surface
    private val thread = HandlerThread("TransitionFrames").also { it.start() }
    private val signal = Object()
    private var available = false
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var shownUs: Long? = null
    private var pending: Pair<Int, Long>? = null
    /** The presentation time of the frame on the texture (the source frame a correction must match). */
    val shownTime: Long? get() = shownUs
    val rotation: Int
    val codedWidth: Int
    val codedHeight: Int
    val surfaceTransform = FloatArray(16)

    init {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IOException("${file.name} has no picture.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val geometry = VideoGeometry.read(file)
        rotation = geometry?.rotation ?: (if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0)
        codedWidth = geometry?.width ?: format.getInteger(MediaFormat.KEY_WIDTH)
        codedHeight = geometry?.height ?: format.getInteger(MediaFormat.KEY_HEIGHT)
        surfaceTexture.setOnFrameAvailableListener({ synchronized(signal) { available = true; signal.notifyAll() } }, Handler(thread.looper))
        surface = Surface(surfaceTexture)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createByCodecName(decoderName(mime))
        // The decoder applies no rotation to a Surface; the layer program does.
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    private fun decoderName(mime: String): String {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        // The emulator's goldfish decoders misbehave while other decoders run; prefer software there (as MediaRenderer).
        val emulator = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")
        val ordered = if (emulator) infos.sortedBy { if (it.isSoftwareOnly) 0 else 1 } else infos
        return ordered.firstOrNull()?.name ?: throw IOException("No decoder for $mime.")
    }

    private fun seek(targetUs: Long) {
        pending?.let { codec.releaseOutputBuffer(it.first, false) }
        pending = null
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false; outputDone = false; shownUs = null
    }

    /** The next decoded frame (output buffer index, presentation time), or null at the end. */
    private fun next(): Pair<Int, Long>? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!outputDone) {
            if (System.nanoTime() > deadline) throw IOException("The video decoder stopped responding.")
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(2_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 2_000)
            if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (info.size > 0) return index to info.presentationTimeUs
                codec.releaseOutputBuffer(index, false)
            }
        }
        return null
    }

    private fun show(frame: Pair<Int, Long>) {
        synchronized(signal) { available = false }
        codec.releaseOutputBuffer(frame.first, true)
        synchronized(signal) {
            val deadline = System.currentTimeMillis() + 2_500
            while (!available) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw IOException("A video frame did not arrive.")
                signal.wait(wait)
            }
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(surfaceTransform)
        shownUs = frame.second
    }

    /** Shows the frame presented at [targetUs]. Returns false when the file has no frame at all. */
    fun frame(targetUs: Long): Boolean {
        val shown = shownUs
        if (shown == null || targetUs < shown || targetUs - shown > 3_000_000) {
            if (!(shown != null && targetUs == shown)) seek(targetUs)
        }
        var candidate: Pair<Int, Long>? = null
        while (true) {
            val frame = pending ?: next() ?: break
            pending = null
            if (frame.second <= targetUs || (shownUs == null && candidate == null)) {
                candidate?.let { codec.releaseOutputBuffer(it.first, false) }
                candidate = frame
                if (frame.second > targetUs) break
            } else { pending = frame; break }
        }
        candidate?.let(::show)
        return shownUs != null
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        extractor.release(); surface.release(); surfaceTexture.release(); thread.quitSafely()
    }
}

/** Renders one TransitionRenderJob: two framed layers, the composite program, and the H.264 encoder. */
private class TransitionPipeline(val job: TransitionRenderJob,
                                 /** Each layer's stabilization correction (null: none). */
                                 private val corrections: List<StabilizationCorrection?> = listOf(null, null)) : AutoCloseable {
    private val egl = TransitionEGL()
    private val width = job.width
    private val height = job.height
    private val canvas = CGSize(width.toDouble(), height.toDouble())
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val layerProgram = program(TransitionRenderer.VERTEX, TransitionRenderer.LAYER)
    private val compositeProgram = program(TransitionRenderer.VERTEX, TransitionRenderer.COMPOSITE)
    private val overlayProgram = program(TransitionRenderer.VERTEX, TransitionRenderer.OVERLAY)
    private val overlayTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }
    private val titleBitmaps = TitleBitmaps()
    private val textures = IntArray(2)
    private val framebuffers = IntArray(2)
    private val oes = IntArray(2)
    private val effects = MetalEffectRenderer()
    private val readers = arrayOfNulls<TransitionFrameReader>(2)
    private val grades = listOf(job.window.first, job.window.second).map { layer ->
        ColorGrade.load(layer?.color, null)?.let { grade ->
            grade.settings.imported?.let { lut -> ColorGrade(grade.settings, job.luts[lut.filename]?.let { runCatching { LUTAssets.load(lut, it.parentFile ?: it) }.getOrNull() }) } ?: grade
        }
    }
    private val lutTextures = arrayOf(ColorLutTexture(), ColorLutTexture())
    private val maps = arrayOfNulls<FloatArray>(2)
    private val framings = arrayOfNulls<CGAffineTransform>(2)
    private val contents = arrayOf(CGRect(0.0, 0.0, canvas.width, canvas.height), CGRect(0.0, 0.0, canvas.width, canvas.height))

    init {
        GLES20.glGenTextures(2, textures, 0)
        GLES20.glGenFramebuffers(2, framebuffers, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            for ((name, value) in listOf(GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
                    GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE)) {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, name, value)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i], 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glGenTextures(2, oes, 0)
        val layers = listOf(job.window.first, job.window.second)
        for (i in 0..1) {
            val layer = layers[i] ?: continue
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes[i])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val file = job.files[layer.sourceID] ?: throw ProjectError.missingMedia
            val reader = TransitionFrameReader(file, oes[i])
            readers[i] = reader
            val oriented = if (reader.rotation % 180 != 0) CGSize(reader.codedHeight.toDouble(), reader.codedWidth.toDouble())
                else CGSize(reader.codedWidth.toDouble(), reader.codedHeight.toDouble())
            // The export's framing (MediaRenderer.pictureEffects): crop transform, else aspect fit, then the mirror.
            val framing = PreviewFraming(canvas, oriented, layer.crop, mirror = layer.mirror).transform
            contents[i] = TransitionComposite.contentBounds(framing, oriented, canvas)
            framings[i] = framing
            maps[i] = canvasToCoded(framing, reader.rotation, reader.codedWidth.toDouble(), reader.codedHeight.toDouble())
        }
    }

    /**
     * Canvas pixels (top-down) → normalized coded picture coordinates (top-down), as a GLSL column-major mat3.
     * Swift `preparedImage` draws coded pixels through the stabilization [correction], then the orientation and
     * framing; this is its inverse.
     */
    private fun canvasToCoded(framing: CGAffineTransform, rotation: Int, codedWidth: Double, codedHeight: Double,
                              correction: CGAffineTransform = CGAffineTransform.identity): FloatArray {
        val unrotate = StabilizationCorrection.orientedToCoded(rotation, CGSize(codedWidth, codedHeight))
        val m = framing.inverted().concatenating(unrotate).concatenating(correction.inverted())
            .concatenating(CGAffineTransform.scale(1 / codedWidth, 1 / codedHeight))
        return floatArrayOf(m.a.toFloat(), m.b.toFloat(), 0f, m.c.toFloat(), m.d.toFloat(), 0f, m.tx.toFloat(), m.ty.toFloat(), 1f)
    }

    private fun program(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) throw IOException("Transition shader: " + GLES20.glGetShaderInfoLog(shader))
            return shader
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IOException("Transition program: " + GLES20.glGetProgramInfoLog(program))
        return program
    }

    private fun drawQuad() {
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Draws layer [index] at project [time] into its canvas texture (fill colour for an absent neighbour, black where the item has no picture). */
    private fun drawLayer(index: Int, time: EditTime) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[index])
        GLES20.glViewport(0, 0, width, height)
        val layer = if (index == 0) job.window.first else job.window.second
        val reader = readers[index]
        val source = layer?.sourceTime(time)
        if (layer == null) {
            val fill = job.window.fill
            GLES20.glClearColor(fill.red.toFloat(), fill.green.toFloat(), fill.blue.toFloat(), 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        // A cadence hold (Undercranking, Super 8) shows its group's frame with its look.
        val clip = layer.clip
        val hold = if (clip != null && clip.frozenFrame == null && source != null) VideoEffectSequenceClock(clip).cadence.hold(source) else null
        val shown = hold?.boundary ?: source
        if (shown == null || reader == null || !reader.frame(Math.round(shown * 1_000_000))) {
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glUseProgram(layerProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes[index])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(layerProgram, "uTexture"), 0)
        // The correction of the source frame actually shown (Swift: `sampleTime`, the held frame's PTS).
        val correction = corrections.getOrNull(index)
        val shownFrame = reader.shownTime
        val map = if (correction != null && shownFrame != null) {
            canvasToCoded(framings[index]!!, reader.rotation, reader.codedWidth.toDouble(), reader.codedHeight.toDouble(), correction.coded(shownFrame / 1_000_000.0))
        } else maps[index]
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(layerProgram, "uMap"), 1, false, map, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(layerProgram, "uSurface"), 1, false, reader.surfaceTransform, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(layerProgram, "uCanvas"), width.toFloat(), height.toFloat())
        // The layer's grade (ColorLUT.GRADE), its LUT on texture unit 1.
        val grade = grades[index]
        val lut = lutTextures[index].bind(grade)
        for ((name, value) in ColorGradeUniforms.set(grade, lut) { name, value -> GLES20.glUniform1i(GLES20.glGetUniformLocation(layerProgram, name), value) }) {
            val location = GLES20.glGetUniformLocation(layerProgram, name)
            when (value.size) {
                1 -> GLES20.glUniform1fv(location, 1, value, 0)
                2 -> GLES20.glUniform2fv(location, 1, value, 0)
                3 -> GLES20.glUniform3fv(location, 1, value, 0)
                else -> GLES20.glUniformMatrix3fv(location, 1, false, value, 0)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextures[index].id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(layerProgram, "uLut"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        drawQuad()
        // Swift `processed`: the clip's video effects on the framed picture, before its overlays.
        if (clip != null) {
            val frozen = clip.frozenFrame
            val frame = if (frozen != null) EffectFrame(frozen.sourceTime, time.seconds, holdTime = frozen.offset + max(0.0, (time - layer.start).seconds))
                else EffectFrame(shown, shown)
            val effects = clip.videoEffects.orEmpty().filter { it.kind != VideoEffectID.stepPrinting && it.kind != VideoEffectID.nightFix }
            this.effects.render(textures[index], videoEffectInputMatrix(null, width, height, width, height), effects, frame, framebuffers[index], width, height)
            GLES20.glUseProgram(0)
        }
        // Swift `render(layer, overlays: style != .split)`: each picture carries its own titles into the blend.
        if (job.window.relation.settings.style != TransitionStyle.split) drawTitles(listOf(layer), time, framebuffers[index])
    }

    /**
     * Draws the image overlays and titles of [layers] at project [time] over
     * [target] (Swift `VideoOverlayRenderer.apply`); an overlay shared by several layers is drawn once.
     */
    private fun drawTitles(layers: List<TransitionLayer>, time: EditTime, target: Int) {
        val seen = HashSet<UUID>()
        val frames = layers.flatMap { layer ->
            val source = layer.sourceTime(time) ?: return@flatMap emptyList()
            VideoOverlayRenderer.overlays(layer.images, layer.titles, source, time.seconds, job.titleRanges, canvas, seen,
                { image -> job.images[image.filename]?.let { ImageBitmaps.image(it.parentFile ?: it, it.name) } }) { titleBitmaps.image(it, canvas) }
        }
        if (frames.isEmpty()) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        VideoOverlayRenderer.draw(android.graphics.Canvas(bitmap), frames, canvas)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES20.glUseProgram(overlayProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "uOverlay"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(overlayProgram, "uCanvas"), width.toFloat(), height.toFloat())
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // Android bitmaps are premultiplied.
        drawQuad()
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Composites project [time] into the current surface (or framebuffer [target]). */
    private fun drawFrame(time: EditTime, target: Int = 0) {
        val window = job.window
        val progress = window.progress(time)
        // Only the pictures a frame shows are decoded.
        if (progress < 1) drawLayer(0, time)
        if (progress > 0) drawLayer(1, time)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target)
        GLES20.glViewport(0, 0, width, height)
        val p = TransitionComposite.parameters(window.drawnSettings, progress, canvas, window.timing.videoDuration, job.framesPerSecond.toDouble(),
            window.fill, contents[0], contents[1])
        val program = compositeProgram
        GLES20.glUseProgram(program)
        fun u(name: String) = GLES20.glGetUniformLocation(program, name)
        fun rect(name: String, r: CGRect) = GLES20.glUniform4f(u(name), r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]); GLES20.glUniform1i(u("uFirst"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1]); GLES20.glUniform1i(u("uSecond"), 1)
        GLES20.glUniform2f(u("uCanvas"), width.toFloat(), height.toFloat())
        GLES20.glUniform1i(u("uStyle"), p.style)
        GLES20.glUniform1f(u("uT"), p.t.toFloat()); GLES20.glUniform1f(u("uEased"), p.eased.toFloat())
        GLES20.glUniform3f(u("uFill"), p.fill.r.toFloat(), p.fill.g.toFloat(), p.fill.b.toFloat())
        GLES20.glUniform1f(u("uFlashSecond"), if (p.flashSecond) 1f else 0f)
        GLES20.glUniform2f(u("uWipeOrigin"), p.wipeOrigin.x.toFloat(), p.wipeOrigin.y.toFloat())
        GLES20.glUniform2f(u("uWipeDirection"), p.wipeDirection.x.toFloat(), p.wipeDirection.y.toFloat())
        GLES20.glUniform1f(u("uWipeFeather"), p.wipeFeather.toFloat())
        GLES20.glUniform3f(u("uHitchWeights"), p.hitchWeights.r.toFloat(), p.hitchWeights.g.toFloat(), p.hitchWeights.b.toFloat())
        GLES20.glUniform1f(u("uHitchBias"), p.hitchBias.toFloat())
        GLES20.glUniform1f(u("uWhipVertical"), if (p.whipVertical) 1f else 0f)
        GLES20.glUniform1f(u("uWhipFirst"), p.whipFirstOffset.toFloat()); GLES20.glUniform1f(u("uWhipSecond"), p.whipSecondOffset.toFloat())
        GLES20.glUniform1f(u("uWhipRadius"), p.whipRadius.toFloat())
        GLES20.glUniform1f(u("uDefocusRadius"), p.defocusRadius.toFloat()); GLES20.glUniform1f(u("uDefocusBlend"), p.defocusBlend.toFloat())
        rect("uSplitFirst", p.splitFirst); rect("uSplitSecond", p.splitSecond)
        rect("uSplitFirstCrop", p.splitFirstCrop); rect("uSplitSecondCrop", p.splitSecondCrop); rect("uSplitLine", p.splitLine)
        GLES20.glUniform3fv(u("uDisc"), 48, TransitionComposite.discSamples.flatMap { listOf(it.first.toFloat(), it.second.toFloat(), it.third.toFloat()) }.toFloatArray(), 0)
        drawQuad()
        // Swift: split-screen titles stay over the full output frame, shared identities drawn once.
        if (window.relation.settings.style == TransitionStyle.split) drawTitles(listOfNotNull(window.first, window.second), time, target)
    }

    /** Encodes the whole window as H.264 at the job's rate into [output]. */
    fun encode(output: File, checkCancelled: () -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, max(2_000_000, width * height * 12))
            setInteger(MediaFormat.KEY_FRAME_RATE, job.framesPerSecond)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = encoder.createInputSurface()
        encoder.start()
        val surface = egl.windowSurface(input)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end) return; if (System.nanoTime() > deadline) throw IOException("The transition encoder stalled.") }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { data.position(info.offset); data.limit(info.offset + info.size); muxer.writeSampleData(track, data, info) }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        try {
            egl.makeCurrent(surface)
            val start = job.window.interval.start
            for (k in 0 until job.frameCount) {
                checkCancelled()
                val offset = k.toDouble() / job.framesPerSecond
                drawFrame(start + EditTime.of(offset))
                egl.present(surface, Math.round(offset * 1_000_000_000))
                drain(false)
            }
            encoder.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { egl.destroy(surface) }
            runCatching { encoder.stop() }; encoder.release(); input.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** One composited frame at project [time], as an upright bitmap. */
    fun still(time: EditTime): Bitmap {
        val target = IntArray(1); val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, target, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture[0], 0)
        drawFrame(time, target[0])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0])
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glDeleteFramebuffers(1, target, 0); GLES20.glDeleteTextures(1, texture, 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels.position(0)
        bitmap.copyPixelsFromBuffer(pixels)
        // GL rows start at the bottom.
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, flip, false)
    }

    override fun close() {
        runCatching { effects.close() }
        for (reader in readers) runCatching { reader?.close() }
        runCatching {
            GLES20.glDeleteFramebuffers(2, framebuffers, 0); GLES20.glDeleteTextures(2, textures, 0); GLES20.glDeleteTextures(2, oes, 0)
            GLES20.glDeleteProgram(layerProgram); GLES20.glDeleteProgram(compositeProgram); GLES20.glDeleteProgram(overlayProgram)
            GLES20.glDeleteTextures(1, overlayTexture, 0)
        }
        egl.close()
    }
}
