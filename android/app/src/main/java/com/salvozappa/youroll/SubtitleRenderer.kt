// Port of YouRec/SubtitleRenderer.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Caption typography is laid out once in a canonical canvas with a 1080-pixel
// short edge; resizing scales that layout rather than changing line breaks.
// The editor preview (EditorController / PlayerSurface) and the export
// (`SubtitleOverlay`, a composition-wide Media3 BitmapOverlay) draw the same
// `SubtitleLayout.Frame`s from the same `SubtitleRenderTrack`, after every
// picture, image and title, so preview == export.
//
// Core Text is replaced by StaticLayout (the legacy Plain/Bold/Box/Highlight
// path) and StyledTextLayout (an `appearance` design). Titles share
// `SubtitleLayout.Frame` for their positioned, animated bitmaps.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Layout in a canonical video canvas, independent of preview resolution. Line
 * breaks stay fixed while resizing; even the longest caption must fit in full.
 * Measure once per transcript/font/style, never on each drag or playback frame.
 */
class SubtitleLayout(cues: List<SubtitleCue>, settings: SubtitleSettings, canvas: CGSize, @Suppress("UNUSED_PARAMETER") folder: File? = null) {
    companion object {
        const val referenceSize = 0.052
    }

    val canvas: CGSize
    val cues: List<SubtitleCue> = cues
    val largest: CGSize
    val maximumSize: Double
    val minimumSize: Double get() = min(0.012, maximumSize)
    private val types: Map<String, CaptionType>
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var lastPaint: TitleDesign? = null
    private val gap = 12.0

    init {
        val factor = 1080 / max(1.0, min(canvas.width, canvas.height))
        this.canvas = CGSize(max(1.0, canvas.width * factor), max(1.0, canvas.height * factor))
        val types = HashMap<String, CaptionType>()
        for (cue in cues) if (types[cue.text] == null) types[cue.text] = CaptionType(cue.text, settings, this.canvas)
        this.types = types
        // Two simultaneous speech tracks are rendered as a single centered stack.
        // Include their combined bounds, not just the largest individual caption.
        var width = 1.0
        var height = 1.0
        val active = sortedSetOf<Int>()
        data class Event(val time: Double, val starting: Boolean, val index: Int)
        val events = cues.indices.flatMap { listOf(Event(cues[it].start, true, it), Event(cues[it].end, false, it)) }
            .sortedWith { a, b -> if (a.time == b.time) (if (!a.starting && b.starting) -1 else if (a.starting && !b.starting) 1 else 0) else a.time.compareTo(b.time) }
        for (event in events) {
            if (event.starting) active += event.index else active -= event.index
            val bounds = active.take(2).mapNotNull { types[cues[it].text]?.bounds?.size }
            width = max(width, bounds.maxOfOrNull { it.width } ?: 0.0)
            height = max(height, bounds.sumOf { it.height } + if (bounds.size > 1) gap else 0.0)
        }
        settings.motion?.let { motion ->
            if (motion.exit == TitleMove.scale) { width *= 1.08; height *= 1.08 }
            val drift = listOf(motion.entrance to motion.inDirection, motion.exit to motion.outDirection)
                .filter { it.first == TitleMove.drift }.map { it.second ?: TitleDirection.up }
            val margin = height * 0.64
            if (TitleDirection.left in drift || TitleDirection.right in drift) width += margin
            if (TitleDirection.up in drift || TitleDirection.down in drift) height += margin
        }
        largest = CGSize(width, height)
        maximumSize = max(0.0001, min(0.30, referenceSize * min(this.canvas.width * 0.94 / width, this.canvas.height * 0.94 / height)))
    }

    fun constrained(value: SubtitleSettings): SubtitleSettings {
        val size = min(maximumSize, max(minimumSize, value.size))
        val scale = size / referenceSize
        val halfW = largest.width * scale / canvas.width / 2 + 0.025
        val halfH = largest.height * scale / canvas.height / 2 + 0.025
        return value.copy(size = size, x = min(1 - halfW, max(halfW, value.centerX)), y = min(1 - halfH, max(halfH, value.y)))
    }

    /**
     * Normalized, top-left coordinates. This is the gesture boundary and guide,
     * including every phrase in the project, even outside the selected range.
     */
    fun envelope(value: SubtitleSettings): CGRect {
        val settings = constrained(value)
        val scale = settings.size / referenceSize
        val w = largest.width * scale / canvas.width
        val h = largest.height * scale / canvas.height
        return CGRect(settings.centerX - w / 2, settings.y - h / 2, w, h)
    }

    data class Frame(
        val image: Bitmap,
        val opacity: Double = 1.0,
        /** Normalized, top-left coordinates, as in the editor. */
        val rect: CGRect,
        /** The visible part of [image] in pixels (a wipe); null shows all of it. */
        val source: Rect? = null,
    ) {
        fun animated(motion: TitleMotion?, elapsed: Double, duration: Double, canvas: CGSize): Frame? {
            if (motion == null || !motion.active) return this
            val pose = motion.pose(elapsed, duration)
            val visible = pose.crop(image.width, image.height) ?: return null
            // Apply motion in square pixels so horizontal and vertical drift travel the same distance.
            val pixels = CGRect(rect.x * canvas.width, rect.y * canvas.height, rect.width * canvas.width, rect.height * canvas.height)
            val moved = pose.rect(pixels)
            val cropped = if (visible[0] == 0 && visible[1] == 0 && visible[2] == image.width && visible[3] == image.height) null
                else Rect(visible[0], visible[1], visible[0] + visible[2], visible[1] + visible[3])
            return Frame(image, pose.opacity, CGRect(moved.x / canvas.width, moved.y / canvas.height, moved.width / canvas.width, moved.height / canvas.height), cropped)
        }

        /** Swift `composite(over:canvas:displayBlend:)`: draws onto [target], whose coordinates are canvas pixels. */
        fun composite(target: Canvas, canvas: CGSize, paint: Paint) {
            if (opacity <= 0) return
            paint.alpha = Math.round(255 * opacity.coerceIn(0.0, 1.0)).toInt()
            val destination = RectF((rect.minX * canvas.width).toFloat(), (rect.minY * canvas.height).toFloat(),
                (rect.maxX * canvas.width).toFloat(), (rect.maxY * canvas.height).toFloat())
            target.drawBitmap(image, source, destination, paint)
        }
    }

    /** The caption frames at project [time] for a picture of [output] pixels. */
    @Synchronized fun frames(time: Double, value: SubtitleSettings, output: CGSize): List<Frame> {
        // A replacement preview can render the same layout while the export reads it.
        val settings = constrained(value)
        val scale = settings.size / referenceSize
        if (lastPaint != settings.appearance) { images.evictAll(); lastPaint = settings.appearance }
        val active = cues.filter { it.start <= time && time < it.end }.take(2)
        val height = active.sumOf { types[it.text]?.bounds?.height ?: 0.0 } + if (active.size > 1) gap else 0.0
        var top = settings.y - height * scale / canvas.height / 2
        val result = mutableListOf<Frame>()
        for (cue in active) {
            val type = types[cue.text] ?: continue
            val key = "${cue.text}|${settings.size}|${settings.color.name}|${output.width}x${output.height}"
            val bitmap = images.get(key) ?: (type.image(scale * output.width / canvas.width, settings.color, settings.appearance) ?: continue)
                .also { images.put(key, it) }
            // Bitmap rounding is contained in the 0.5% safety reserve.
            val w = bitmap.width / output.width
            val h = bitmap.height / output.height
            val frame = Frame(bitmap, rect = CGRect(settings.centerX - w / 2, top, w, h))
            frame.animated(settings.motion, time - cue.start, cue.end - cue.start, output)?.let { result += it }
            top += (type.bounds.height + gap) * scale / canvas.height
        }
        return result
    }

    /** Used by layout tests: a caption must never silently truncate. */
    val containsEveryCharacter: Boolean get() = types.values.all { it.visibleLength == it.length }
}

private class CaptionType(text: String, settings: SubtitleSettings, canvas: CGSize) {
    private val styled: StyledTextLayout?
    private val layout: StaticLayout?
    val bounds: CGRect
    val fontSize: Double = min(canvas.width, canvas.height) * SubtitleLayout.referenceSize
    val captionStyle: TextStyle = settings.style
    val length: Int = text.length
    val visibleLength: Int
    private val padding = fontSize * 0.35

    init {
        val design = settings.appearance
        if (design != null) {
            val layout = StyledTextLayout(text, design, fontSize, canvas.width * 0.84)
            styled = layout; this.layout = null; bounds = layout.bounds; visibleLength = layout.length
        } else {
            styled = null
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = TitleTypeface.subtitleTypeface(settings.resolvedFont, if (captionStyle == TextStyle.clean) 400 else 700)
                textSize = fontSize.toFloat()
                color = android.graphics.Color.WHITE
            }
            fun build(width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            val measure = build(ceil(canvas.width * 0.84 - padding * 2).toInt())
            val measured = (0 until measure.lineCount).maxOfOrNull { measure.getLineWidth(it).toDouble() } ?: 0.0
            val width = ceil(max(fontSize, measured))
            val laid = build(width.toInt())
            layout = laid
            bounds = CGRect(0.0, 0.0, width + padding * 2 + 2, ceil(laid.height.toDouble()) + padding * 2 + 2)
            visibleLength = if (laid.lineCount == 0) 0 else laid.getLineEnd(laid.lineCount - 1)
        }
    }

    fun image(scale: Double, color: TextColor, paint: TitleDesign?): Bitmap? {
        styled?.let { return it.image(scale, paint) }
        val layout = layout ?: return null
        val width = max(1, ceil(bounds.width * scale).toInt())
        val height = max(1, ceil(bounds.height * scale).toInt())
        if (!(width <= 16384 && height <= 16384)) return null
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val context = Canvas(result)
        val (r, g, b) = color.rgb
        val ink = android.graphics.Color.argb(255, Math.round(r * 255).toInt(), Math.round(g * 255).toInt(), Math.round(b * 255).toInt())
        if (captionStyle == TextStyle.box || captionStyle == TextStyle.highlight) {
            context.save(); context.scale(scale.toFloat(), scale.toFloat())
            val radius = (padding * 0.7).toFloat()
            context.drawRoundRect(RectF(1f, 1f, (bounds.width - 1).toFloat(), (bounds.height - 1).toFloat()), radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = if (captionStyle == TextStyle.box) android.graphics.Color.argb(Math.round(0.82 * 255).toInt(), 5, 5, 5) else ink
                })
            context.restore()
        }
        // The lettering on its own layer, so a Plain/Bold shadow is cast by the finished glyphs.
        val letters = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val lettering = Canvas(letters)
        lettering.scale(scale.toFloat(), scale.toFloat())
        lettering.translate(padding.toFloat(), padding.toFloat())
        layout.paint.color = if (captionStyle == TextStyle.highlight) android.graphics.Color.rgb(8, 8, 8) else ink
        layout.draw(lettering)
        layout.paint.color = android.graphics.Color.WHITE
        if (captionStyle != TextStyle.box && captionStyle != TextStyle.highlight) {
            // Core Graphics: offset (0, −0.04 em) in a y-up context, blur 0.12 em, black at 0.9.
            val alpha = letters.extractAlpha()
            val sigma = fontSize * 0.12 * scale / 2
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.argb(Math.round(0.9 * 255).toInt(), 0, 0, 0)
                if (sigma > 0.5) maskFilter = BlurMaskFilter(((sigma - 0.5) / 0.57735).toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            context.drawBitmap(alpha, 0f, (fontSize * 0.04 * scale).toFloat(), shade)
            alpha.recycle()
        }
        context.drawBitmap(letters, 0f, 0f, null)
        letters.recycle()
        return result
    }
}

/** Preview and export consume exactly the same caption layout and bitmaps. */
class SubtitleRenderTrack(private var cues: List<SubtitleCue>, private var settings: SubtitleSettings, private val folder: File? = null) {
    private var layout: SubtitleLayout? = null
    private var canvas = CGSize(0.0, 0.0)

    @Synchronized fun updatePreview(layout: SubtitleLayout?, settings: SubtitleSettings, canvas: CGSize) {
        this.cues = layout?.cues ?: emptyList()
        this.settings = settings
        this.layout = layout
        this.canvas = canvas
    }

    /** Swift `render(at:over:canvas:)`: the frames to draw over a [canvas]-sized picture at [time]. */
    @Synchronized fun frames(time: Double, canvas: CGSize): List<SubtitleLayout.Frame> {
        if (this.canvas != canvas) { this.canvas = canvas; layout = SubtitleLayout(cues, settings, canvas, folder) }
        return layout?.frames(time, settings, canvas) ?: emptyList()
    }

    val isEmpty: Boolean @Synchronized get() = cues.isEmpty()
}

/**
 * The captions of an export, drawn by Media3 over the finished composition
 * (every picture lane, transition window and gap), on the composition clock.
 * Like `TitleOverlay`, the bitmap is the whole canvas, uploaded only when the
 * drawn frames change, and handed over unpremultiplied.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SubtitleOverlay(private val track: SubtitleRenderTrack, private val canvas: CGSize) : BitmapOverlay() {
    private var drawn: List<Any>? = null
    private var bitmap: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frames = track.frames(presentationTimeUs / 1_000_000.0, canvas)
        val key = frames.map { listOf(System.identityHashCode(it.image), it.opacity, it.rect, it.source.toString()) }
        bitmap?.let { if (key == drawn) return it }
        val width = canvas.width.toInt()
        val height = canvas.height.toInt()
        val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        VideoOverlayRenderer.draw(Canvas(picture), frames, canvas)
        val pixels = IntArray(width * height)
        picture.getPixels(pixels, 0, width, 0, 0, width, height) // unpremultiplied ARGB
        picture.recycle()
        val straight = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        straight.isPremultiplied = false
        straight.setPixels(pixels, 0, width, 0, 0, width, height)
        drawn = key; bitmap = straight
        return straight
    }
}
