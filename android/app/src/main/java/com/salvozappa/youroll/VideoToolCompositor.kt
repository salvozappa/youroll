// Port of YouRec/VideoToolCompositor.swift (partial: TextRasterizer, the
// image and title parts of VideoOverlayRenderer, and ColorLUT)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS composites image overlays and titles in its one custom compositor, for
// the editor and the export alike. Here the same functions serve both: the
// editor preview draws the frames over the player in the canvas window
// (PlayerSurface), and the export draws them into a full-canvas bitmap overlay
// on the GPU (`TitleOverlay`, a Media3 BitmapOverlay after the framing and
// mirror effects). Images come first, then titles, as in Swift.
//
// The grade (`ColorLUT`) is one GLSL program shared by the export, the preview
// players and transition clips, with a CPU twin (see `ColorLUT`).
//
// Stabilization (`StabilizationCorrection`, `StabilizationTransformation`)
// corrects the source picture after the grade and before the framing.
//
// Not ported (TODO.md): animated images and grades (EffectAnimation),
// video effects and the rest of the compositor; overlays of a
// picture hidden behind another one (only the front picture is shown without
// picture-in-picture placement).
package com.salvozappa.youroll

import java.io.File

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

object TextRasterizer {
    /** The title's bitmap at the canvas resolution, or null for empty text. */
    fun image(text: TextOverlay, canvas: CGSize): Bitmap? {
        if (text.text.isEmpty() || !(canvas.width > 0 && canvas.height > 0)) return null
        val fontSize = max(8.0, min(canvas.width, canvas.height) * text.size)
        text.appearance?.let { design ->
            val layout = StyledTextLayout(text.text, design, fontSize, canvas.width * 0.94)
            return layout.image(min(1.0, min(canvas.width / layout.bounds.width, canvas.height / layout.bounds.height)))
        }
        // Titles saved before TitleDesign: one of four styles in one of six colours.
        val (r, g, b) = text.color.rgb
        val color = android.graphics.Color.argb(255, swiftRound(r * 255).toInt(), swiftRound(g * 255).toInt(), swiftRound(b * 255).toInt())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (text.style == TextStyle.clean) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            textSize = fontSize.toFloat()
            this.color = if (text.style == TextStyle.highlight) android.graphics.Color.argb(255, 8, 8, 8) else color
        }
        val padding = fontSize * 0.35
        val maxWidth = canvas.width * 0.84 - padding * 2
        fun layout(width: Int) = StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val fitting = layout(ceil(maxWidth).toInt())
        val fittingWidth = (0 until fitting.lineCount).maxOfOrNull { fitting.getLineWidth(it).toDouble() } ?: 0.0
        val frameWidth = min(maxWidth, max(fontSize, fittingWidth))
        val width = ceil(frameWidth + 2 * padding).toInt()
        val height = ceil(min(canvas.height * 0.8, fitting.height.toDouble()) + 2 * padding).toInt()
        if (width <= 0 || height <= 0) return null
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val context = Canvas(result)
        if (text.style == TextStyle.box || text.style == TextStyle.highlight) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (text.style == TextStyle.box) android.graphics.Color.argb(209, 5, 5, 5) else color
            }
            val corner = (padding * 0.7).toFloat()
            context.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), corner, corner, box)
        } else {
            // Core Graphics blur b ≈ a Gaussian of sigma b/2; Skia's shadow radius r has sigma 0.57735 r + 0.5.
            val sigma = fontSize * 0.12 / 2
            paint.setShadowLayer(max(0.01, (sigma - 0.5) / 0.57735).toFloat(), 0f, (fontSize * 0.04).toFloat(), android.graphics.Color.argb(230, 0, 0, 0))
        }
        context.save()
        context.clipRect(padding.toFloat(), padding.toFloat(), (width - padding).toFloat(), (height - padding).toFloat())
        context.translate(padding.toFloat(), padding.toFloat())
        layout(ceil(frameWidth).toInt()).draw(context)
        context.restore()
        return result
    }

    /**
     * Swift `origin(_:imageSize:canvas:)`: the bitmap's lower-left corner in
     * y-up canvas pixels. Clamped to the recorded frame, not the device screen or its safe area.
     */
    fun origin(text: TextOverlay, imageSize: CGSize, canvas: CGSize): CGPoint = CGPoint(
        max(0.0, min(canvas.width - imageSize.width, text.x * canvas.width - imageSize.width / 2)),
        max(0.0, min(canvas.height - imageSize.height, (1 - text.y) * canvas.height - imageSize.height / 2)))
}

/** Swift `VideoToolInstruction.PreviewTitle`: the draft title of the Text tool, with its source range on each clip. */
data class PreviewTitle(val text: TextOverlay, val clips: Map<UUID, ClosedFloatingPointRange<Double>>, val ranges: TitleMotionRanges)

/**
 * Rasterized titles by look and canvas (Swift `TextRasterizer.image` results
 * held per instruction, and the inspector's `rasterKey`). Position, timing and
 * motion do not change the bitmap.
 */
class TitleBitmaps(private val capacity: Int = 24) {
    private data class Key(val text: TextOverlay, val canvas: CGSize)
    private val cache = object : LinkedHashMap<Key, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap?>?) = size > capacity
    }

    @Synchronized fun image(text: TextOverlay, canvas: CGSize): Bitmap? {
        val key = Key(text.copy(id = UUID(0, 0), x = 0.5, y = 0.5, start = 0.0, end = 0.0, motion = null), canvas)
        if (cache.containsKey(key)) return cache[key]
        return TextRasterizer.image(text, canvas).also { cache[key] = it }
    }
}

/**
 * Decoded overlay images by file (Swift `MediaRenderer.timeline`'s `imageCache`
 * of CGImages). Shared by the editor preview, the export and the transition
 * renderer, so they all draw the same pixels. Null for a missing or unreadable file.
 */
object ImageBitmaps {
    private const val capacity = 6
    private val cache = object : LinkedHashMap<String, Bitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > capacity
    }

    private fun key(file: File) = "${file.path}|${file.length()}|${file.lastModified()}"

    /** The decoded image if it was already read (`first` is false when it was not read yet), without decoding. */
    @Synchronized fun peek(folder: File, filename: String): Pair<Boolean, Bitmap?> {
        val file = File(folder, filename)
        if (!file.exists()) return true to null
        val key = key(file)
        return cache.containsKey(key) to cache[key]
    }

    @Synchronized fun image(folder: File, filename: String): Bitmap? {
        val file = File(folder, filename)
        if (!file.exists()) return null
        val key = key(file)
        if (cache.containsKey(key)) return cache[key]
        val options = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return android.graphics.BitmapFactory.decodeFile(file.path, options).also { cache[key] = it }
    }
}

object VideoOverlayRenderer {
    /**
     * Swift `VideoOverlayRenderer.apply` for the image overlays of one picture
     * whose source clock reads [sourceTime]: each active image at its `rect`
     * with its opacity (a straight alpha blend, like `ImageOverlayRenderer`).
     * An image shared by several layers is drawn once ([seen]). Animated
     * images (`motion`) are drawn at their stored pose (TODO.md).
     */
    fun images(images: List<ImageOverlay>, sourceTime: Double, canvas: CGSize, seen: MutableSet<UUID>,
               bitmap: (ImageOverlay) -> Bitmap?): List<SubtitleLayout.Frame> {
        val result = mutableListOf<SubtitleLayout.Frame>()
        for (overlay in images) {
            if (!(sourceTime >= overlay.start - 0.00001 && sourceTime < overlay.end - 0.00001) || overlay.id in seen) continue
            seen += overlay.id
            val image = bitmap(overlay) ?: continue
            result += frame(overlay, CGSize(image.width.toDouble(), image.height.toDouble()), canvas, image)
        }
        return result
    }

    /** Where [overlay] goes: its y-up `rect` turned into the frame's normalized top-left coordinates. */
    fun frame(overlay: ImageOverlay, size: CGSize, canvas: CGSize, image: Bitmap): SubtitleLayout.Frame {
        val rect = overlay.rect(size, canvas)
        return SubtitleLayout.Frame(image, overlay.opacity,
            CGRect(rect.minX / canvas.width, (canvas.height - rect.maxY) / canvas.height, rect.width / canvas.width, rect.height / canvas.height))
    }

    /** Swift `VideoOverlayRenderer.apply`: a picture's images, then its titles, sharing [seen]. */
    fun overlays(images: List<ImageOverlay>, texts: List<TextOverlay>, sourceTime: Double, time: Double, ranges: TitleMotionRanges, canvas: CGSize,
                 seen: MutableSet<UUID>, image: (ImageOverlay) -> Bitmap?, title: (TextOverlay) -> Bitmap?): List<SubtitleLayout.Frame> =
        images(images, sourceTime, canvas, seen, image) + titles(texts, sourceTime, time, ranges, canvas, seen, title)

    /**
     * The title frames of one picture at project [time], whose source clock reads
     * [sourceTime]. A title shared by several layers is drawn once ([seen]).
     */
    fun titles(texts: List<TextOverlay>, sourceTime: Double, time: Double, ranges: TitleMotionRanges, canvas: CGSize,
               seen: MutableSet<UUID>, bitmap: (TextOverlay) -> Bitmap?): List<SubtitleLayout.Frame> {
        val result = mutableListOf<SubtitleLayout.Frame>()
        for (text in texts) {
            if (!(sourceTime >= text.start - 0.00001 && sourceTime < text.end - 0.00001) || text.id in seen) continue
            seen += text.id
            val global = ranges.range(text, time)
            val range = global ?: (text.start..text.end)
            val clock = if (global == null) sourceTime else time
            val image = bitmap(text) ?: continue
            text.drawFrame(image, canvas, clock, range)?.let { result += it }
        }
        return result
    }

    /** Draws [frames] onto [target], whose coordinate space is the canvas in pixels. */
    fun draw(target: Canvas, frames: List<SubtitleLayout.Frame>, canvas: CGSize) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (frame in frames) frame.composite(target, canvas, paint)
    }
}

/**
 * The titles of one exported picture piece, drawn by Media3 over the framed
 * canvas. [piece] gives the source clock: the frame at edit time t shows
 * source time `sourceStart + (t − start) × rate`. Media3 hands the overlay each
 * frame's presentation time on the composition clock, so t = pts.
 *
 * The overlay bitmap is the whole canvas, uploaded only when the drawn frames
 * change. Media3 blends overlays as straight (unpremultiplied) alpha, so the
 * bitmap is handed over unpremultiplied.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TitleOverlay(
    private val piece: ExportPlan.Piece.Clip,
    private val canvas: CGSize,
    private val ranges: TitleMotionRanges,
    private val bitmaps: TitleBitmaps,
    /** The piece's image overlays (Swift draws them first, then the titles). */
    private val image: (ImageOverlay) -> Bitmap? = { null },
) : BitmapOverlay() {
    private var drawn: List<Any>? = null
    private var bitmap: Bitmap? = null
    private var logged = false

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val time = presentationTimeUs / 1_000_000.0
        val sourceTime = piece.sourceStart + (time - piece.start.seconds) * piece.rate
        if (!logged) {
            logged = true
            android.util.Log.i("TitleOverlay", "Piece ${piece.start.seconds}s–${piece.end.seconds}s: first frame at $time s, source $sourceTime s")
        }
        val frames = VideoOverlayRenderer.overlays(piece.images, piece.titles, sourceTime, time, ranges, canvas, HashSet(), image) { bitmaps.image(it, canvas) }
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

// MARK: - Color (Swift `ColorLUT`, the `graded` step of `VideoToolInstruction.preparedImage`)

/**
 * Swift `enum ColorLUT`: the built-in looks and the grade of one picture.
 *
 * iOS grades the decoded source picture in Core Image's linear working space
 * before framing, so bars stay black and overlays are drawn over the graded
 * picture. YouRoll runs the same steps in one GLSL program ([GRADE]): the first
 * effect of every exported picture ([ColorGradeEffect]), the preview players'
 * effect, and the layer shader of transition clips. [apply] is its CPU twin
 * (JVM tests, cover frames, look thumbnails).
 *
 * Simplified (TODO.md): pictures are display-referred sRGB-encoded values, linearised
 * with the sRGB curve (iOS: the Rec.709 video transfer of `VideoColor.space`);
 * CITemperatureAndTint is a Bradford adaptation between the DNG temperature/tint
 * white points; CIColorCube's extrapolation extends the edge cell along each axis;
 * the LUT texture holds 8-bit values (imported LUT values outside 0…1 are clamped).
 */
object ColorLUT {
    const val dimension = 33

    // Authored SDR creative LUTs. No external weights, licensed looks or Log
    // camera transforms. Identity-to-look interpolation controls the strength.
    private val cubes = HashMap<ColorLook, FloatArray>()

    /** The look's 33³ RGBA cube, red fastest (Swift `cubes[look]`). */
    fun cube(look: ColorLook): FloatArray = synchronized(cubes) { cubes.getOrPut(look) { makeCube(look) } }

    private fun makeCube(look: ColorLook): FloatArray {
        val n = dimension
        val values = FloatArray(n * n * n * 4)
        var index = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            var rgb = doubleArrayOf(r / (n - 1.0), g / (n - 1.0), b / (n - 1.0))
            val luma = rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
            fun map(f: (Double) -> Double) { val old = rgb; rgb = DoubleArray(3) { f(old[it]) } }
            when (look) {
                ColorLook.natural -> {}
                ColorLook.warm -> { rgb[0] = rgb[0].pow(0.92); rgb[2] = rgb[2].pow(1.10) }
                ColorLook.cool -> { rgb[0] = rgb[0].pow(1.08); rgb[2] = rgb[2].pow(0.92) }
                ColorLook.mono -> rgb = doubleArrayOf(luma, luma, luma)
                ColorLook.cinema -> {
                    map { min(1.0, max(0.0, 0.5 + (it - 0.5) * 1.12)) }
                    rgb[0] += 0.035 * luma; rgb[1] += 0.012 * (1 - luma); rgb[2] += 0.035 * (1 - luma)
                    map { luma + (it - luma) * 0.85 }
                }
                ColorLook.portrait -> {
                    map { luma + (it - luma) * 0.92 }
                    rgb[0] += 0.018 * luma * (1 - luma); rgb[2] -= 0.025 * luma * (1 - luma)
                    map { it * 0.95 + 0.025 }
                }
                ColorLook.daylight -> {
                    map { 0.5 + (it - 0.5) * 1.04 }
                    map { luma + (it - luma) * 1.08 }
                }
                ColorLook.goldenHour -> {
                    rgb[0] += 0.12 * luma * (1 - luma); rgb[1] += 0.025 * luma
                    rgb[2] *= 0.86; map { max(0.0, it).pow(0.95) }
                }
                ColorLook.mutedFilm -> {
                    map { 0.055 + 0.89 * (luma + (it - luma) * 0.72) }
                    rgb[2] += 0.018 * (1 - luma)
                }
                ColorLook.richContrast -> map { val x = min(1.0, max(0.0, it)); x * x * (3 - 2 * x) * 0.55 + x * 0.45 }
                ColorLook.pastel -> map { 0.075 + 0.88 * max(0.0, luma + (it - luma) * 0.65).pow(0.91) }
                ColorLook.night -> {
                    map { max(0.0, it * 0.94 - 0.012) }
                    rgb[2] += 0.045 * (1 - luma); rgb[1] += 0.012 * (1 - luma)
                }
                ColorLook.silver -> {
                    val y = max(0.0, rgb[0] * 0.3 + rgb[1] * 0.6 + rgb[2] * 0.1).pow(0.92)
                    rgb = doubleArrayOf(y * 0.98, y, min(1.0, y * 1.015))
                }
                ColorLook.ink -> {
                    val y = min(1.0, max(0.0, (luma - 0.08) / 0.84)); val dense = y * y * (3 - 2 * y)
                    rgb = doubleArrayOf(dense, dense, dense)
                }
            }
            for (c in 0..2) values[index++] = min(1.0, max(0.0, rgb[c])).toFloat()
            values[index++] = 1f
        }
        return values
    }

    /**
     * CIColorCube on one value: trilinear in a red-fastest RGBA cube. Outside
     * 0…1, [extrapolate] continues the edge cell along each axis, otherwise the
     * input is clamped.
     */
    fun trilinear(cube: FloatArray, n: Int, x: DoubleArray, extrapolate: Boolean): DoubleArray {
        fun at(r: Int, g: Int, b: Int, c: Int) = cube[((b * n + g) * n + r) * 4 + c].toDouble()
        fun sample(p: DoubleArray): DoubleArray {
            val f = DoubleArray(3) { min(1.0, max(0.0, p[it])) * (n - 1) }
            val i = IntArray(3) { min(n - 2, floor(f[it]).toInt()) }
            val t = DoubleArray(3) { f[it] - i[it] }
            return DoubleArray(3) { c ->
                var sum = 0.0
                for (corner in 0 until 8) {
                    val dr = corner and 1; val dg = (corner shr 1) and 1; val db = (corner shr 2) and 1
                    val w = (if (dr == 1) t[0] else 1 - t[0]) * (if (dg == 1) t[1] else 1 - t[1]) * (if (db == 1) t[2] else 1 - t[2])
                    sum += w * at(i[0] + dr, i[1] + dg, i[2] + db, c)
                }
                sum
            }
        }
        val clamped = DoubleArray(3) { min(1.0, max(0.0, x[it])) }
        val result = sample(clamped)
        if (!extrapolate) return result
        val h = 1.0 / (n - 1)
        val edge = result.copyOf()
        for (axis in 0..2) {
            val excess = x[axis] - clamped[axis]
            if (excess == 0.0) continue
            val s = if (excess > 0) 1.0 else -1.0
            val inner = sample(clamped.copyOf().also { it[axis] -= s * h })
            for (c in 0..2) result[c] += excess * (edge[c] - inner[c]) / (s * h)
        }
        return result
    }

    // The pictures' transfer curve (sRGB, extended to values outside 0…1 by symmetry).
    fun linear(c: Double): Double { val a = abs(c); val v = if (a <= 0.04045) a / 12.92 else ((a + 0.055) / 1.055).pow(2.4); return if (c < 0) -v else v }
    fun encoded(c: Double): Double { val a = abs(c); val v = if (a <= 0.0031308) a * 12.92 else 1.055 * a.pow(1 / 2.4) - 0.055; return if (c < 0) -v else v }

    /**
     * CITemperatureAndTint with neutral (6500, 0) and target neutral
     * (6500 + 2500·warmth, 100·tint): linear Rec.709 → linear Rec.709, row-major.
     * Positive warmth warms, positive tint adds magenta.
     */
    fun balanceMatrix(warmth: Double, tint: Double): Array<DoubleArray> {
        val source = WhiteBalance.xy(6500 + warmth * 2500, tint * 100)
        val target = WhiteBalance.xy(6500.0, 0.0)
        return Matrix3.times(WhiteBalance.xyzToRec709, Matrix3.times(WhiteBalance.bradford(source, target), WhiteBalance.rec709ToXYZ))
    }

    /**
     * Swift `apply(_:to:hdr:folder:sourceLog:)` on one display-encoded
     * picture value (0…1), returning the encoded output clamped to 0…1.
     * [cube] is the loaded imported LUT ([LUTAssets.load]); without it an
     * imported grade keeps only its balance, as iOS does when the payload is unreadable.
     */
    fun apply(settings: ColorSettings, rgb: DoubleArray, cube: CubeLUT? = null): DoubleArray {
        var balanced = DoubleArray(3) { linear(rgb[it]) }
        settings.exposure?.takeIf { it != 0.0 }?.let { ev -> val k = 2.0.pow(ev); val old = balanced; balanced = DoubleArray(3) { old[it] * k } }
        val warmth = settings.warmth ?: 0.0
        val tint = settings.tint ?: 0.0
        if (warmth != 0.0 || tint != 0.0) balanced = Matrix3.times(balanceMatrix(warmth, tint), balanced)
        balanced = LogColor.highlights(balanced, settings.highlights ?: 0.0)
        // No Log sources on Android: local recovery and the viewing transform never run (LogColor.kt).
        val viewed = balanced
        fun output(linear: DoubleArray) = DoubleArray(3) { min(1.0, max(0.0, encoded(linear[it]))) }
        if (!(settings.amount > 0 && (settings.imported != null || settings.look != ColorLook.natural))) return output(viewed)
        // LUT entries are display RGB, not linear-light RGB: enter and leave the LUT domain explicitly.
        val encodedInput = DoubleArray(3) { encoded(viewed[it]) }
        val imported = settings.imported
        val mapped = when {
            imported != null && cube != null -> cube.lookup(if (imported.input == LUTInput.rec709) encodedInput else LogColor.encode(balanced, imported.input))
            imported == null -> trilinear(cube(settings.look), dimension, encodedInput, extrapolate = true)
            else -> return output(viewed)
        }
        val graded = DoubleArray(3) { linear(mapped[it]) }
        if (settings.amount >= 1) return output(graded)
        return output(DoubleArray(3) { viewed[it] * (1 - settings.amount) + graded[it] * settings.amount })
    }

    /**
     * Grades an ARGB_8888 [bitmap] in place on the CPU (cover frames, look
     * thumbnails); the GPU paths use [GRADE]. Encoded inputs go through a
     * 256-entry table per channel combination only where the grade is per channel.
     */
    fun apply(settings: ColorSettings, bitmap: android.graphics.Bitmap, cube: CubeLUT? = null) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val memo = HashMap<Int, Int>()
        for (i in pixels.indices) {
            val p = pixels[i]
            val key = p and 0xFFFFFF
            pixels[i] = (p and -0x1000000) or memo.getOrPut(key) {
                val out = apply(settings, doubleArrayOf(((p shr 16) and 255) / 255.0, ((p shr 8) and 255) / 255.0, (p and 255) / 255.0), cube)
                (Math.round(out[0] * 255).toInt() shl 16) or (Math.round(out[1] * 255).toInt() shl 8) or Math.round(out[2] * 255).toInt()
            }
            if (memo.size > 200_000) memo.clear()
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** The uniforms of [GRADE] for one grade (pure, so the CPU and GPU paths share their inputs). */
    class Parameters(
        val exposure: Float, val balance: FloatArray, val highlights: Float, val amount: Float,
        /** 0: no LUT, 1: cube, 2: 1D curves. */
        val lutKind: Int, val lutInput: Int, val logMatrix: FloatArray,
        val domainMin: FloatArray, val domainScale: FloatArray, val extrapolate: Boolean,
    )

    fun parameters(settings: ColorSettings, cube: CubeLUT?): Parameters {
        val warmth = settings.warmth ?: 0.0
        val tint = settings.tint ?: 0.0
        val identity = Matrix3.diagonal(doubleArrayOf(1.0, 1.0, 1.0))
        val balance = if (warmth != 0.0 || tint != 0.0) balanceMatrix(warmth, tint) else identity
        val imported = settings.imported
        val active = settings.amount > 0 && (imported != null || settings.look != ColorLook.natural)
        val kind = when {
            !active -> 0
            imported != null -> if (cube == null) 0 else if (cube.curves != null) 2 else 1
            else -> 1
        }
        val input = imported?.input ?: LUTInput.rec709
        val logMatrix = if (input == LUTInput.rec709) identity else Matrix3.inverse(LogColor.matrix(input))
        val low = if (imported != null && cube != null) cube.domainMin else doubleArrayOf(0.0, 0.0, 0.0)
        val high = if (imported != null && cube != null) cube.domainMax else doubleArrayOf(1.0, 1.0, 1.0)
        return Parameters(2.0.pow(settings.exposure ?: 0.0).toFloat(), Matrix3.gl(balance), (settings.highlights ?: 0.0).toFloat(),
            settings.amount.toFloat(), kind, input.ordinal, Matrix3.gl(logMatrix),
            FloatArray(3) { low[it].toFloat() }, FloatArray(3) { (1 / (high[it] - low[it])).toFloat() }, extrapolate = imported == null)
    }

    /**
     * The LUT texture of a grade: a cube as a grid of blue slices (each n×n,
     * red across, green down), or a curve as rows of samples; RGBA 8-bit.
     */
    class Texture(val width: Int, val height: Int, val pixels: ByteArray, val size: Int, val columns: Int, val curve: Boolean)

    fun texture(settings: ColorSettings, cube: CubeLUT?): Texture? {
        val imported = settings.imported
        fun byte(v: Float) = Math.round(min(1f, max(0f, v)) * 255f).coerceIn(0, 255).toByte()
        val curves = cube?.curves
        if (imported != null && cube != null && curves != null) {
            val n = cube.dimension
            val width = min(n, 1024)
            val height = (n + width - 1) / width
            val pixels = ByteArray(width * height * 4)
            for (i in 0 until n) { for (c in 0..2) pixels[i * 4 + c] = byte(curves[i * 3 + c]); pixels[i * 4 + 3] = -1 }
            return Texture(width, height, pixels, n, width, curve = true)
        }
        val data: FloatArray
        val n: Int
        when {
            imported != null -> { data = (cube ?: return null).rgba; n = cube.dimension }
            settings.look != ColorLook.natural -> { data = cube(settings.look); n = dimension }
            else -> return null
        }
        val columns = ceil(sqrt(n.toDouble())).toInt()
        val rows = (n + columns - 1) / columns
        val width = columns * n
        val height = rows * n
        val pixels = ByteArray(width * height * 4)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val x = (b % columns) * n + r
            val y = (b / columns) * n + g
            val source = ((b * n + g) * n + r) * 4
            val target = (y * width + x) * 4
            for (c in 0..2) pixels[target + c] = byte(data[source + c])
            pixels[target + 3] = -1
        }
        return Texture(width, height, pixels, n, columns, curve = false)
    }

    /** Media3's vertex shader contract (`aFramePosition`, `uTransformationMatrix`, `uTexTransformationMatrix`). */
    const val VERTEX = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

    /** [GRADE] as a Media3 fragment shader over its input texture. */
    val FRAGMENT: String get() = "#version 100\nprecision highp float;\nuniform sampler2D uTexSampler;\nvarying vec2 vTexSamplingCoord;\n" + GRADE +
        "\nvoid main() {\n  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);\n  gl_FragColor = vec4(grade(color.rgb), color.a);\n}\n"

    /**
     * `vec3 grade(vec3 encoded)`: the CPU [apply] in GLSL (ES 1.00). Shared by
     * [ColorGradeEffect] and the transition clips' layer shader.
     */
    const val GRADE = """
uniform int uGradeEnabled;
uniform float uExposure;
uniform mat3 uBalance;
uniform float uHighlights;
uniform float uAmount;
uniform int uLutKind;
uniform int uLutInput;
uniform mat3 uLogMatrix;
uniform vec3 uDomainMin;
uniform vec3 uDomainScale;
uniform int uExtrapolate;
uniform sampler2D uLut;
uniform vec2 uLutSize;
uniform float uLutLength;
uniform float uLutColumns;

float gradeLinear1(float c) { float a = abs(c); float v = a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4); return c < 0.0 ? -v : v; }
float gradeEncoded1(float c) { float a = abs(c); float v = a <= 0.0031308 ? a * 12.92 : 1.055 * pow(a, 1.0 / 2.4) - 0.055; return c < 0.0 ? -v : v; }
vec3 gradeLinear(vec3 c) { return vec3(gradeLinear1(c.r), gradeLinear1(c.g), gradeLinear1(c.b)); }
vec3 gradeEncoded(vec3 c) { return vec3(gradeEncoded1(c.r), gradeEncoded1(c.g), gradeEncoded1(c.b)); }

vec3 gradeHighlights(vec3 p, float amount) {
  if (amount == 0.0) return p;
  float y = max(0.0, dot(p, vec3(0.2126, 0.7152, 0.0722)));
  float delta = max(0.0, y - 0.35);
  float strength = max(0.000001, -amount * 6.0);
  float down = log(1.0 + strength * delta) / strength;
  float up = delta * (1.0 + 2.0 * amount * delta / (delta + 0.35));
  float mapped = y <= 0.35 ? y : 0.35 + (amount < 0.0 ? down : up);
  return p * (y > 0.000001 ? mapped / y : 1.0);
}

vec3 gradeLogEncode(vec3 scene) {
  vec3 p = uLogMatrix * scene;
  vec3 t = max(p + 0.05641088, vec3(0.0));
  vec3 lo = 47.28711236 * t * t;
  vec3 hi = log2(max(p + 0.00964052, vec3(0.00000001))) * 0.08550479 + 0.69336945;
  return mix(lo, hi, step(vec3(0.01), p));
}

vec3 gradeSlice(vec2 rg, float slice) {
  float n = uLutLength;
  float column = mod(slice, uLutColumns);
  float row = floor(slice / uLutColumns);
  vec2 texel = vec2(column * n + 0.5 + rg.x * (n - 1.0), row * n + 0.5 + rg.y * (n - 1.0));
  return texture2D(uLut, texel / uLutSize).rgb;
}

vec3 gradeCube(vec3 p) {
  vec3 c = clamp(p, 0.0, 1.0);
  float f = c.b * (uLutLength - 1.0);
  float i = min(uLutLength - 2.0, floor(f));
  return mix(gradeSlice(c.rg, i), gradeSlice(c.rg, i + 1.0), f - i);
}

vec3 gradeCurveSample(float index) {
  vec2 texel = vec2(mod(index, uLutColumns) + 0.5, floor(index / uLutColumns) + 0.5);
  return texture2D(uLut, texel / uLutSize).rgb;
}

vec3 gradeCurves(vec3 p) {
  vec3 f = clamp(p, 0.0, 1.0) * (uLutLength - 1.0);
  vec3 i = min(vec3(uLutLength - 2.0), floor(f));
  vec3 t = f - i;
  float r = mix(gradeCurveSample(i.r).r, gradeCurveSample(i.r + 1.0).r, t.r);
  float g = mix(gradeCurveSample(i.g).g, gradeCurveSample(i.g + 1.0).g, t.g);
  float b = mix(gradeCurveSample(i.b).b, gradeCurveSample(i.b + 1.0).b, t.b);
  return vec3(r, g, b);
}

vec3 gradeLookup(vec3 p) {
  if (uLutKind == 2) return gradeCurves(p);
  vec3 c = clamp(p, 0.0, 1.0);
  vec3 edge = gradeCube(c);
  if (uExtrapolate == 0) return edge;
  float h = 1.0 / (uLutLength - 1.0);
  vec3 excess = p - c;
  vec3 result = edge;
  if (excess.r != 0.0) { float s = sign(excess.r); result += excess.r * (edge - gradeCube(c - vec3(s * h, 0.0, 0.0))) / (s * h); }
  if (excess.g != 0.0) { float s = sign(excess.g); result += excess.g * (edge - gradeCube(c - vec3(0.0, s * h, 0.0))) / (s * h); }
  if (excess.b != 0.0) { float s = sign(excess.b); result += excess.b * (edge - gradeCube(c - vec3(0.0, 0.0, s * h))) / (s * h); }
  return result;
}

vec3 grade(vec3 encodedColor) {
  if (uGradeEnabled == 0) return encodedColor;
  vec3 balanced = uBalance * (gradeLinear(encodedColor) * uExposure);
  balanced = gradeHighlights(balanced, uHighlights);
  vec3 viewed = balanced;
  vec3 result = viewed;
  if (uLutKind != 0) {
    vec3 lutInput = uLutInput == 0 ? gradeEncoded(viewed) : gradeLogEncode(balanced);
    vec3 mapped = gradeLookup((lutInput - uDomainMin) * uDomainScale);
    vec3 graded = gradeLinear(mapped);
    result = uAmount >= 1.0 ? graded : mix(viewed, graded, uAmount);
  }
  return clamp(gradeEncoded(result), 0.0, 1.0);
}
"""
}

/** White points and chromatic adaptation for `ColorLUT.balanceMatrix` (CITemperatureAndTint). */
object WhiteBalance {
    // Robertson's isotemperature lines (as in the DNG SDK's dng_temperature): reciprocal megakelvin, u, v, slope.
    private val table = arrayOf(
        doubleArrayOf(0.0, 0.18006, 0.26352, -0.24341), doubleArrayOf(10.0, 0.18066, 0.26589, -0.25479),
        doubleArrayOf(20.0, 0.18133, 0.26846, -0.26876), doubleArrayOf(30.0, 0.18208, 0.27119, -0.28539),
        doubleArrayOf(40.0, 0.18293, 0.27407, -0.30470), doubleArrayOf(50.0, 0.18388, 0.27709, -0.32675),
        doubleArrayOf(60.0, 0.18494, 0.28021, -0.35156), doubleArrayOf(70.0, 0.18611, 0.28342, -0.37915),
        doubleArrayOf(80.0, 0.18740, 0.28668, -0.40955), doubleArrayOf(90.0, 0.18880, 0.28997, -0.44278),
        doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888), doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
        doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471), doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
        doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182), doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
        doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512), doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
        doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637), doubleArrayOf(325.0, 0.24702, 0.34655, -2.4681),
        doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641), doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
        doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633), doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
        doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262), doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
        doubleArrayOf(500.0, 0.30505, 0.35907, -11.324), doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
        doubleArrayOf(550.0, 0.32129, 0.36011, -23.325), doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
        doubleArrayOf(600.0, 0.33724, 0.36051, -116.45))
    private const val tintScale = -3000.0

    /** The xy chromaticity of a white balance setting (temperature in kelvin, tint in DNG units). */
    fun xy(temperature: Double, tint: Double): DoubleArray {
        val r = 1.0e6 / temperature
        val offset = tint / tintScale
        for (index in 0..29) {
            if (!(r < table[index + 1][0] || index == 29)) continue
            val f = (table[index + 1][0] - r) / (table[index + 1][0] - table[index][0])
            var u = table[index][1] * f + table[index + 1][1] * (1 - f)
            var v = table[index][2] * f + table[index + 1][2] * (1 - f)
            val len1 = sqrt(1 + table[index][3] * table[index][3])
            val len2 = sqrt(1 + table[index + 1][3] * table[index + 1][3])
            var uu = (1 / len1) * f + (1 / len2) * (1 - f)
            var vv = (table[index][3] / len1) * f + (table[index + 1][3] / len2) * (1 - f)
            val len3 = sqrt(uu * uu + vv * vv)
            uu /= len3; vv /= len3
            u += uu * offset; v += vv * offset
            return doubleArrayOf(1.5 * u / (u - 4 * v + 2), v / (u - 4 * v + 2))
        }
        return doubleArrayOf(0.3127, 0.3290)
    }

    val rec709ToXYZ: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.4124564, 0.3575761, 0.1804375), doubleArrayOf(0.2126729, 0.7151522, 0.0721750), doubleArrayOf(0.0193339, 0.1191920, 0.9503041))
    val xyzToRec709: Array<DoubleArray> = Matrix3.inverse(rec709ToXYZ)
    private val cone = arrayOf(doubleArrayOf(0.8951, 0.2664, -0.1614), doubleArrayOf(-0.7502, 1.7135, 0.0367), doubleArrayOf(0.0389, -0.0685, 1.0296))

    /** Bradford adaptation from white [source] to white [target] (xy), in XYZ. */
    fun bradford(source: DoubleArray, target: DoubleArray): Array<DoubleArray> {
        fun white(xy: DoubleArray) = doubleArrayOf(xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1])
        val s = Matrix3.times(cone, white(source))
        val t = Matrix3.times(cone, white(target))
        return Matrix3.times(Matrix3.inverse(cone), Matrix3.times(Matrix3.diagonal(DoubleArray(3) { t[it] / s[it] }), cone))
    }
}

// MARK: - Color on the GPU (Media3 effect; Android glue)

/**
 * One grade for [ColorGradeEffect]: the settings and the loaded imported LUT
 * (null for a built-in look or when the payload is unreadable).
 */
data class ColorGrade(val settings: ColorSettings, val cube: CubeLUT? = null) {
    /** The parts that change the LUT texture. */
    val lutKey: Any? get() = if (settings.imported != null) settings.imported to System.identityHashCode(cube) else settings.look

    companion object {
        /** [settings] with its imported LUT loaded from the project [folder] (an unreadable payload keeps only the balance). */
        fun load(settings: ColorSettings?, folder: File?): ColorGrade? {
            settings ?: return null
            if (settings.isIdentity) return null
            val cube = settings.imported?.let { lut -> folder?.let { runCatching { LUTAssets.load(lut, it) }.getOrNull() } }
            return ColorGrade(settings, cube)
        }
    }
}

/**
 * The grade as a Media3 effect, first in a picture's effects so it acts on the
 * decoded source before framing (Swift `preparedImage`: `graded`, then the
 * layer transform). [grade] is asked once per frame with the frame's
 * presentation time: a fixed grade for an exported piece, the playlist item's
 * grade in the preview.
 */
@UnstableApi
class ColorGradeEffect(private val grade: (Long) -> ColorGrade?) : androidx.media3.effect.GlEffect {
    constructor(fixed: ColorGrade) : this({ fixed })

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): androidx.media3.effect.GlShaderProgram =
        ColorGradeShaderProgram(grade)
}

@UnstableApi
private class ColorGradeShaderProgram(private val grade: (Long) -> ColorGrade?) : androidx.media3.effect.BaseGlShaderProgram(false, 1) {
    private val program = androidx.media3.common.util.GlProgram(ColorLUT.VERTEX, ColorLUT.FRAGMENT)
    private val lut = ColorLutTexture()

    init {
        program.setBufferAttribute("aFramePosition", androidx.media3.common.util.GlUtil.getNormalizedCoordinateBounds(),
            androidx.media3.common.util.GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = androidx.media3.common.util.GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int) = androidx.media3.common.util.Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        val current = grade(presentationTimeUs)
        val texture = lut.bind(current)
        ColorGradeUniforms.set(current, texture) { name, value -> program.setIntUniform(name, value) }.let { floats ->
            for ((name, value) in floats) program.setFloatsUniformIfPresent(name, value)
        }
        program.setSamplerTexIdUniform("uLut", lut.id, 1)
        program.bindAttributesAndUniforms()
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        program.delete()
        lut.release()
    }
}

/** The uniforms of [ColorLUT.GRADE] for [grade] (null: pass the picture through). */
object ColorGradeUniforms {
    /** Sets the int uniforms through [int] and returns the float ones (name → values). */
    fun set(grade: ColorGrade?, texture: ColorLUT.Texture?, int: (String, Int) -> Unit): List<Pair<String, FloatArray>> {
        if (grade == null) {
            int("uGradeEnabled", 0); int("uLutKind", 0); int("uLutInput", 0); int("uExtrapolate", 0)
            val identity = Matrix3.gl(Matrix3.diagonal(doubleArrayOf(1.0, 1.0, 1.0)))
            return listOf("uExposure" to floatArrayOf(1f), "uBalance" to identity, "uHighlights" to floatArrayOf(0f), "uAmount" to floatArrayOf(0f),
                "uLogMatrix" to identity, "uDomainMin" to FloatArray(3), "uDomainScale" to floatArrayOf(1f, 1f, 1f),
                "uLutSize" to floatArrayOf(1f, 1f), "uLutLength" to floatArrayOf(2f), "uLutColumns" to floatArrayOf(1f))
        }
        val p = ColorLUT.parameters(grade.settings, grade.cube)
        val kind = if (texture == null) 0 else p.lutKind
        int("uGradeEnabled", 1); int("uLutKind", kind); int("uLutInput", p.lutInput); int("uExtrapolate", if (p.extrapolate) 1 else 0)
        return listOf("uExposure" to floatArrayOf(p.exposure), "uBalance" to p.balance, "uHighlights" to floatArrayOf(p.highlights),
            "uAmount" to floatArrayOf(p.amount), "uLogMatrix" to p.logMatrix, "uDomainMin" to p.domainMin, "uDomainScale" to p.domainScale,
            "uLutSize" to floatArrayOf((texture?.width ?: 1).toFloat(), (texture?.height ?: 1).toFloat()),
            "uLutLength" to floatArrayOf((texture?.size ?: 2).toFloat()), "uLutColumns" to floatArrayOf((texture?.columns ?: 1).toFloat()))
    }
}

/** The GL texture of a grade's LUT, uploaded again only when the look or LUT changes. Current GL context only. */
class ColorLutTexture {
    var id: Int = 0
        private set
    private var key: Any? = Unit
    private var texture: ColorLUT.Texture? = null

    /** Uploads [grade]'s LUT if it changed and returns its description (null: no LUT). */
    fun bind(grade: ColorGrade?): ColorLUT.Texture? {
        if (id == 0) {
            val ids = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, ids, 0)
            id = ids[0]
            upload(ColorLUT.Texture(1, 1, byteArrayOf(0, 0, 0, -1), 2, 1, curve = true))
        }
        val next = grade?.lutKey
        if (grade != null && next != key) {
            key = next
            texture = ColorLUT.texture(grade.settings, grade.cube)
            texture?.let { upload(it) }
        }
        return if (grade == null) null else texture
    }

    private fun upload(texture: ColorLUT.Texture) {
        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE1)
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, id)
        android.opengl.GLES20.glPixelStorei(android.opengl.GLES20.GL_UNPACK_ALIGNMENT, 1)
        android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES20.GL_RGBA, texture.width, texture.height, 0,
            android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(texture.pixels))
        val filter = if (texture.curve) android.opengl.GLES20.GL_NEAREST else android.opengl.GLES20.GL_LINEAR
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, filter)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, filter)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
    }

    fun release() {
        if (id != 0) android.opengl.GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0; key = Unit; texture = null
    }
}

// MARK: - Stabilization (Swift `preparedImage`: `correction`, before the framing)

/**
 * Swift `stabilization?.transform(at: sampleTime - clip.stabilizationStart)`:
 * the correction of the source picture shown at a source time. iOS corrects
 * encoded-frame (unrotated) coordinates before the orientation and the canvas
 * fitting; Media3 effects and the preview receive upright pictures, so the
 * same correction is conjugated by the source's rotation ([upright]). Pure
 * and JVM-tested; the export, the preview players and transition clips all
 * use it, so preview == export.
 */
data class StabilizationCorrection(val plan: StabilizationPlan, val referenceStart: Double, val rotation: Int) {
    /** Coded-frame pixels → coded-frame pixels at source time [sourceTime] (Swift's `correction`). */
    fun coded(sourceTime: Double): CGAffineTransform = plan.transform(sourceTime - referenceStart + timestampTolerance)

    /** The same correction on the upright picture (oriented pixels → oriented pixels). */
    fun upright(sourceTime: Double): CGAffineTransform {
        val unrotate = orientedToCoded(rotation, plan.size)
        return unrotate.concatenating(coded(sourceTime)).concatenating(unrotate.inverted())
    }

    /** The upright picture's size in the analysis's pixels (the coded size, turned by the rotation). */
    val orientedSize: CGSize get() = if (((rotation % 180) + 180) % 180 != 0) CGSize(plan.size.height, plan.size.width) else plan.size

    /**
     * The correction in normalized device coordinates. NDC does not depend on
     * the decoded frame's resolution: a frame Media3 rescales (non-square
     * pixels, a smaller decode) still spans the same picture, so the
     * analysis's own pixels convert exactly.
     */
    fun ndc(sourceTime: Double): FloatArray = ndcMatrix(upright(sourceTime), orientedSize)

    companion object {
        /**
         * Media3 reports frame times in whole microseconds, so a clip clock
         * mapped back to the source can fall a microsecond before the frame's
         * own analysed time. A fraction of a frame (0.2 ms) absorbs that,
         * always choosing the frame actually shown, never its neighbour.
         */
        const val timestampTolerance = 0.0002

        /** Oriented (upright) pixels → coded pixels for a clockwise display [rotation] (TransitionRenderer's `unrotate`). */
        fun orientedToCoded(rotation: Int, coded: CGSize): CGAffineTransform = when (((rotation % 360) + 360) % 360) {
            90 -> CGAffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, coded.height)
            180 -> CGAffineTransform(-1.0, 0.0, 0.0, -1.0, coded.width, coded.height)
            270 -> CGAffineTransform(0.0, 1.0, -1.0, 0.0, coded.width, 0.0)
            else -> CGAffineTransform.identity
        }

        /** Row-major 3×3 for `android.graphics.Matrix`: an upright pixel transform conjugated into NDC (as CropTransformation). */
        fun ndcMatrix(upright: CGAffineTransform, size: CGSize): FloatArray {
            val fromInput = CGAffineTransform(size.width / 2, 0.0, 0.0, -size.height / 2, size.width / 2, size.height / 2)
            val toOutput = CGAffineTransform(2 / size.width, 0.0, 0.0, -2 / size.height, -1.0, 1.0)
            val m = fromInput.concatenating(upright).concatenating(toOutput)
            return floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.tx.toFloat(), m.b.toFloat(), m.d.toFloat(), m.ty.toFloat(), 0f, 0f, 1f)
        }
    }
}

/**
 * The stabilization correction as a Media3 effect, after the grade and before
 * the framing (Swift `preparedImage`). [correction] gives the NDC correction
 * (`StabilizationCorrection.ndc`) of the frame presented at a time (null:
 * none); the picture keeps its size, magnified by the plan's crop zoom, so the
 * framing that follows is unchanged.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class StabilizationTransformation(private val correction: (Long) -> FloatArray?) : androidx.media3.effect.MatrixTransformation {
    private val identity = android.graphics.Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int) = androidx.media3.common.util.Size(inputWidth, inputHeight)

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val values = correction(presentationTimeUs) ?: return identity
        return android.graphics.Matrix().apply { setValues(values) }
    }
}
