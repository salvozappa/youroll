// Port of YouRec/StyledTextRenderer.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS shapes the whole string with Core Text, then paints the plate, the
// lettering (outline ring + fill or gradient, clipped to the glyph mask) and one
// shadow cast by the finished lettering. Android shapes with StaticLayout and
// paints the same layers with android.graphics:
//
// - the outline ring (Core Image `CIMorphologyMaximum` minus the glyph) is the
//   glyph stroked at twice the border width with round joins, minus the glyph;
// - the shadow is the lettering's alpha, blurred (Core Graphics blur b ≈ a
//   Gaussian of sigma b/2) and offset down;
// - left/right alignment are absolute only for left-to-right text.
//
// The same bitmap is drawn by the editor preview and by the export
// (VideoToolCompositor.kt), so preview and export match.
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Text is shaped completely before either colour or outline is applied. */
class StyledTextLayout(text: String, val design: TitleDesign, val fontSize: Double, maxWidth: Double) {
    /** The image's size in layout points (pixels at scale 1). */
    val bounds: CGRect
    val length: Int = text.length
    private val inset: Double
    private val paint: TextPaint
    private val layout: StaticLayout

    init {
        val font = TitleTypeface.font(design, fontSize)
        paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font.typeface
            textSize = fontSize.toFloat()
            if (font.variation.isNotEmpty()) fontVariationSettings = font.variation
            letterSpacing = design.tracking.toFloat() // em, like kern = tracking × size
            color = android.graphics.Color.WHITE
        }
        val effectPad = (if (design.outlined) design.borderWidth else 0.0) +
            (if (design.shadow) 2 * design.shadowBlur + abs(design.shadowOffset) else 0.0)
        inset = ceil(fontSize * (max(effectPad, if (design.plate == TitlePlate.off) 0.0 else design.padding) + 0.14)) + 2
        val constraint = max(fontSize, maxWidth - 2 * inset)
        val measure = build(text, ceil(constraint).toInt())
        val measured = (0 until measure.lineCount).maxOfOrNull { measure.getLineWidth(it).toDouble() } ?: 0.0
        val width = ceil(max(fontSize, measured)) + 2
        val height = ceil(max(fontSize, measure.height.toDouble())) + 2
        bounds = CGRect(0.0, 0.0, width + 2 * inset, height + 2 * inset)
        layout = build(text, width.toInt())
    }

    private fun build(text: String, width: Int): StaticLayout {
        val alignment = when (design.alignment) {
            TitleAlignment.left -> Layout.Alignment.ALIGN_NORMAL
            TitleAlignment.right -> Layout.Alignment.ALIGN_OPPOSITE
            TitleAlignment.center -> Layout.Alignment.ALIGN_CENTER
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(alignment)
            .setLineSpacing((design.lineSpacing * fontSize).toFloat(), 1f)
            .setIncludePad(false)
            .build()
    }

    /** Swift `image(scale:paint:)`: [paint] repaints the same layout with other colours. */
    fun image(scale: Double, paint: TitleDesign? = null): Bitmap? {
        val design = paint ?: this.design
        if (!(scale.isFinite() && scale > 0)) return null
        val w = ceil(bounds.width * scale)
        val h = ceil(bounds.height * scale)
        if (!(w <= 16384 && h <= 16384 && w * h <= 32_000_000)) return null
        val result = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val context = Canvas(result)
        context.scale(scale.toFloat(), scale.toFloat())
        if (design.plate != TitlePlate.off) {
            val pad = design.padding * fontSize
            val radius = design.radius * fontSize
            val plates = Path()
            fun plate(box: RectF) {
                val r = min(radius, min(box.width().toDouble(), box.height().toDouble()) / 2).toFloat()
                plates.addRoundRect(box, r, r, Path.Direction.CW)
            }
            if (design.plate == TitlePlate.block) {
                val d = (inset - pad).toFloat()
                plate(RectF(d, d, bounds.width.toFloat() - d, bounds.height.toFloat() - d))
            } else {
                val metrics = this.paint.fontMetrics
                for (line in 0 until layout.lineCount) {
                    val width = layout.getLineWidth(line) // excludes trailing whitespace
                    if (width <= 0) continue
                    val left = inset + layout.getLineLeft(line) - pad
                    val baseline = inset + layout.getLineBaseline(line)
                    plate(RectF(left.toFloat(), (baseline + metrics.ascent - pad).toFloat(), (left + width + 2 * pad).toFloat(),
                        (baseline + metrics.descent + pad).toFloat()))
                }
            }
            context.drawPath(plates, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = design.backing.argb })
        }
        // The lettering: outline ring under the fill, as one layer that casts one shadow.
        val letters = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val lettering = Canvas(letters)
        lettering.scale(scale.toFloat(), scale.toFloat())
        lettering.translate(inset.toFloat(), inset.toFloat())
        val p = layout.paint
        if (design.outlined && design.borderWidth > 0) {
            p.style = Paint.Style.FILL_AND_STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = (2 * design.borderWidth * fontSize).toFloat()
            p.color = design.border.argb
            layout.draw(lettering)
            // Only the part outside the glyph: a transparent fill never shows the stroke's inside.
            p.style = Paint.Style.FILL
            p.strokeWidth = 0f
            p.color = android.graphics.Color.BLACK
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            layout.draw(lettering)
            p.xfermode = null
        }
        val gradient = design.gradient
        if (gradient != null) {
            val ink = CGSize(bounds.width - 2 * inset, bounds.height - 2 * inset)
            val (a, b) = gradient.axis(ink)
            // Already translated by the inset: the ink box starts at the origin.
            p.shader = LinearGradient(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), gradient.first.argb, gradient.last.argb, Shader.TileMode.CLAMP)
            p.color = android.graphics.Color.BLACK
        } else {
            p.color = design.fill.argb
        }
        layout.draw(lettering)
        p.shader = null
        p.color = android.graphics.Color.WHITE
        // All subsequent geometry is in output pixels.
        context.setMatrix(null)
        if (design.shadow) {
            val alpha = letters.extractAlpha()
            val sigma = design.shadowBlur * fontSize * scale / 2
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = design.shade.argb
                if (sigma > 0.5) maskFilter = BlurMaskFilter(((sigma - 0.5) / 0.57735).toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            context.drawBitmap(alpha, 0f, (design.shadowOffset * fontSize * scale).toFloat(), shade)
            alpha.recycle()
        }
        context.drawBitmap(letters, 0f, 0f, null)
        letters.recycle()
        return result
    }

    /** The lines as laid out (for tests). */
    val lineCount: Int get() = layout.lineCount
}

/** Swift `TextOverlay.drawFrame`: where [bitmap] goes at [time] within its clock [range], in normalized top-left coordinates. */
fun TextOverlay.drawFrame(bitmap: Bitmap, canvas: CGSize, time: Double, range: ClosedFloatingPointRange<Double>): SubtitleLayout.Frame? {
    val size = CGSize(bitmap.width.toDouble(), bitmap.height.toDouble())
    val origin = TextRasterizer.origin(this, size, canvas)
    val rect = CGRect(origin.x / canvas.width, 1 - (origin.y + size.height) / canvas.height, size.width / canvas.width, size.height / canvas.height)
    return SubtitleLayout.Frame(bitmap, rect = rect).animated(motion, time - range.start, range.endInclusive - range.start, canvas)
}
