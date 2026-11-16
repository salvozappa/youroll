// Port of YouRec/TitleDesign.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The title look (TitleDesign), its colours (TitleInk, TitleGradient), the
// bundled typefaces (TitleTypeface) and entrance/exit motion (TitleMotion).
// Core Text fonts become Android Typefaces loaded from the bundled
// `TitleFonts/` assets (copied from ../ThirdParty/TitleFonts at build time);
// variable weights are set through the font's `wght` axis.
//
// Not ported (TODO.md): imported fonts (`import:` faces, ImportedFonts.swift)
// and arbitrary device fonts by PostScript name (iOS `CTFontCreateWithName`);
// both fall back to the system font and are reported as missing.
package com.salvozappa.youroll

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Packed sRGB, in RRGGBBAA order. Stored values are independent of the display's color scheme. */
@Serializable
data class TitleInk(val rgba: Long) {
    val rgb: Long get() = rgba shr 8
    val alpha: Double get() = (rgba and 255).toDouble() / 255

    /** Android ARGB colour int. */
    val argb: Int get() = (((rgba and 255) shl 24) or (rgba shr 8)).toInt()

    companion object {
        /** Swift `TitleInk(_ rgb:alpha:)`. */
        fun rgb(rgb: Long, alpha: Int = 255): TitleInk = TitleInk(((rgb and 0xffffff) shl 8) or (alpha.toLong() and 255))
        fun of(color: TextColor): TitleInk {
            val (r, g, b) = color.rgb
            return rgb((swiftRound(r * 255).toLong() shl 16) or (swiftRound(g * 255).toLong() shl 8) or swiftRound(b * 255).toLong())
        }
        val white = rgb(0xffffff)
        val black = rgb(0x000000)
    }
}

@Serializable
data class TitleGradient(
    val first: TitleInk,
    val last: TitleInk,
    /** Screen degrees: 0 left to right, 90 top to bottom. */
    val angle: Double,
) {
    /** The gradient's start and end points in a box of [size] (top-left origin, y down). */
    fun axis(size: CGSize): Pair<CGPoint, CGPoint> {
        val a = angle * Math.PI / 180
        val x = cos(a)
        val y = sin(a)
        val half = (abs(x) * size.width + abs(y) * size.height) / 2
        return CGPoint(size.width / 2 - x * half, size.height / 2 - y * half) to CGPoint(size.width / 2 + x * half, size.height / 2 + y * half)
    }

    companion object {
        fun of(first: Long, last: Long, angle: Double) = TitleGradient(TitleInk.rgb(first), TitleInk.rgb(last), angle)
        val gallery: List<Pair<String, TitleGradient>> = listOf(
            "Sunset" to of(0xfff0ba, 0xf58283, 105.0), "Golden hour" to of(0xfff5d6, 0xe4a044, 85.0),
            "Ember" to of(0xffcf74, 0xc84939, 30.0), "Rose" to of(0xffdae2, 0xba5486, 120.0),
            "Lavender" to of(0xeee0ff, 0x9a87dd, 95.0), "Midnight" to of(0x91aff1, 0x45417b, 45.0),
            "Glacier" to of(0xf6ffff, 0x50afcd, 100.0), "Lagoon" to of(0x72e7df, 0x386ab1, 20.0),
            "Sea glass" to of(0xe4fadb, 0x69b7a7, 130.0), "Olive" to of(0xf4e8aa, 0x829969, 70.0),
            "Silver" to of(0xffffff, 0x9ca9b8, 90.0), "Copper" to of(0xf0c6a5, 0xa36556, 145.0),
        )
    }
}

@Serializable
enum class TitleAlignment { left, center, right }

@Serializable
enum class TitlePlate { off, block, lines }

@Serializable
data class TitleDesign(
    val face: String = "system",
    val weight: Int = 700,
    val italic: Boolean = false,
    val alignment: TitleAlignment = TitleAlignment.center,
    val tracking: Double = 0.0,
    val lineSpacing: Double = 0.0,
    val fill: TitleInk = TitleInk.white,
    val gradient: TitleGradient? = null,
    val outlined: Boolean = false,
    val border: TitleInk = TitleInk.black,
    /** Thickness in em, outside the glyph. */
    val borderWidth: Double = 0.045,
    val shadow: Boolean = true,
    val shade: TitleInk = TitleInk.rgb(0x000000, alpha = 210),
    val shadowBlur: Double = 0.12,
    val shadowOffset: Double = 0.05,
    val plate: TitlePlate = TitlePlate.off,
    val backing: TitleInk = TitleInk.rgb(0x111218, alpha = 205),
    val padding: Double = 0.28,
    val radius: Double = 0.14,
) {
    val layoutKey: TitleDesign
        get() = copy(fill = TitleInk.white, gradient = null, border = TitleInk.black, shade = TitleInk.black, backing = TitleInk.black)

    /** Outline is a separate tool, including when switching between saved channel looks. */
    fun applyingLook(look: TitleDesign): TitleDesign = look.copy(outlined = outlined, border = border, borderWidth = borderWidth)

    val valid: Boolean
        get() = face.isNotEmpty() && face.toByteArray(Charsets.UTF_8).size <= 255 && weight in 100..1000 && tracking in -0.05..0.25 &&
            lineSpacing in 0.0..0.8 && borderWidth in 0.0..0.20 && shadowBlur in 0.0..0.6 &&
            shadowOffset in -0.5..0.5 && padding in 0.0..0.8 && radius in 0.0..0.8 &&
            (gradient?.angle ?: 0.0) in 0.0..360.0

    companion object {
        /** Swift `legacy(_:color:font:)`: a subtitle font is stored by its iOS PostScript name (Android stand-ins in [TitleTypeface.font]). */
        fun legacy(style: TextStyle, color: TextColor, font: SubtitleFont? = null): TitleDesign {
            var d = TitleDesign(face = font?.postScriptName(style != TextStyle.clean) ?: "system", weight = if (style == TextStyle.clean) 400 else 700,
                fill = if (style == TextStyle.highlight) TitleInk.black else TitleInk.of(color))
            if (style == TextStyle.box || style == TextStyle.highlight) {
                d = d.copy(shadow = false, plate = TitlePlate.block,
                    backing = if (style == TextStyle.highlight) TitleInk.of(color) else TitleInk.rgb(0x050505, alpha = 209))
            }
            return d
        }

        val looks: List<Pair<String, TitleDesign>> by lazy {
            val film = TitleDesign(face = "sourceserif4", weight = 500)
            val lower = TitleDesign(face = "barlowcondensed", plate = TitlePlate.block, shadow = false, alignment = TitleAlignment.left)
            val heavy = TitleDesign(face = "archivoblack")
            val note = TitleDesign(face = "caveat", weight = 600)
            val label = TitleDesign(face = "ibmplexmono", weight = 500, tracking = 0.06)
            val editorial = TitleDesign(face = "fraunces", gradient = TitleGradient.gallery[0].second)
            val clear = TitleDesign(face = "atkinsonhyperlegiblenext", weight = 600)
            val sunsetMono = label.copy(gradient = TitleGradient.gallery[0].second)
            listOf("Clean" to clear, "Film title" to film, "Lower third" to lower, "Headline" to heavy,
                "Field note" to note, "Dateline" to label, "Warm editorial" to editorial, "Sunset mono" to sunsetMono)
        }
    }
}

/**
 * A resolved font: the typeface, the `font-variation-settings` to apply to the
 * Paint (empty for static faces) and the PostScript name iOS would load
 * (for tests and labels).
 */
data class TitleFont(val typeface: Typeface, val variation: String, val postScriptName: String, val bundled: Boolean)

data class TitleTypeface(
    val id: String,
    val name: String,
    val upright: String,
    val slanted: String? = null,
    val weights: List<Int> = listOf(400, 500, 600, 700, 800),
) {
    companion object {
        val families: List<TitleTypeface> = listOf(
            TitleTypeface("system", "System", "", ""),
            TitleTypeface("atkinsonhyperlegiblenext", "Atkinson Hyperlegible Next", "AtkinsonHyperlegibleNext-Regular", "AtkinsonHyperlegibleNext-Italic"),
            TitleTypeface("barlowcondensed", "Barlow Condensed", "BarlowCondensed-Regular", "BarlowCondensed-Italic", listOf(400, 700)),
            TitleTypeface("archivoblack", "Archivo Black", "ArchivoBlack-Regular", weights = listOf(400)),
            TitleTypeface("sourceserif4", "Source Serif 4", "SourceSerif4Roman-Regular", "SourceSerif4Italic-Italic"),
            TitleTypeface("fraunces", "Fraunces", "Fraunces-9ptBlack", "Fraunces-9ptBlackItalic"),
            TitleTypeface("ibmplexmono", "IBM Plex Mono", "IBMPlexMono-Regular", "IBMPlexMono-Italic", listOf(400, 500, 700)),
            TitleTypeface("caveat", "Caveat", "Caveat-Regular", weights = listOf(400, 500, 600, 700)),
        )

        /**
         * The bundled file for each PostScript name, and the fixed axes of its
         * named instance (Fraunces "9pt Black" pins the optical size). Files
         * with a `wght` axis are listed as variable.
         */
        private data class Face(val file: String, val variable: Boolean, val fixed: String = "", val opticalSize: ClosedFloatingPointRange<Double>? = null)
        private val faces = mapOf(
            "AtkinsonHyperlegibleNext-Regular" to Face("atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext[wght].ttf", true),
            "AtkinsonHyperlegibleNext-Italic" to Face("atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext-Italic[wght].ttf", true),
            "BarlowCondensed-Regular" to Face("barlowcondensed/BarlowCondensed-Regular.ttf", false),
            "BarlowCondensed-Italic" to Face("barlowcondensed/BarlowCondensed-Italic.ttf", false),
            "BarlowCondensed-Bold" to Face("barlowcondensed/BarlowCondensed-Bold.ttf", false),
            "BarlowCondensed-BoldItalic" to Face("barlowcondensed/BarlowCondensed-BoldItalic.ttf", false),
            "ArchivoBlack-Regular" to Face("archivoblack/ArchivoBlack-Regular.ttf", false),
            // Core Text sizes optical axes automatically; Android does not, so the size is set explicitly.
            "SourceSerif4Roman-Regular" to Face("sourceserif4/SourceSerif4[opsz,wght].ttf", true, opticalSize = 8.0..60.0),
            "SourceSerif4Italic-Italic" to Face("sourceserif4/SourceSerif4-Italic[opsz,wght].ttf", true, opticalSize = 8.0..60.0),
            "Fraunces-9ptBlack" to Face("fraunces/Fraunces[SOFT,WONK,opsz,wght].ttf", true, "'opsz' 9"),
            "Fraunces-9ptBlackItalic" to Face("fraunces/Fraunces-Italic[SOFT,WONK,opsz,wght].ttf", true, "'opsz' 9"),
            "IBMPlexMono-Regular" to Face("ibmplexmono/IBMPlexMono-Regular.ttf", false),
            "IBMPlexMono-Italic" to Face("ibmplexmono/IBMPlexMono-Italic.ttf", false),
            "IBMPlexMono-Medium" to Face("ibmplexmono/IBMPlexMono-Medium.ttf", false),
            "IBMPlexMono-MediumItalic" to Face("ibmplexmono/IBMPlexMono-MediumItalic.ttf", false),
            "IBMPlexMono-Bold" to Face("ibmplexmono/IBMPlexMono-Bold.ttf", false),
            "IBMPlexMono-BoldItalic" to Face("ibmplexmono/IBMPlexMono-BoldItalic.ttf", false),
            "Caveat-Regular" to Face("caveat/Caveat[wght].ttf", true),
        )

        @Volatile private var assets: AssetManager? = null
        private val loaded = HashMap<String, Typeface>()

        /** Swift's process-wide `registered`: the bundled fonts live in the app's assets. */
        fun install(context: Context) { if (assets == null) assets = context.applicationContext.assets }

        private fun typeface(file: String): Typeface? = synchronized(loaded) {
            loaded[file] ?: runCatching { Typeface.createFromAsset(assets ?: return null, "TitleFonts/$file") }.getOrNull()?.also { loaded[file] = it }
        }

        /** The PostScript name Core Text would load for [design] (Swift `font`, before variations). */
        fun postScriptName(design: TitleDesign): String? {
            val family = families.firstOrNull { it.id == design.face } ?: return null
            if (family.id == "system") return null
            var name = if (design.italic) family.slanted ?: family.upright else family.upright
            if (family.id == "barlowcondensed" || family.id == "ibmplexmono") {
                val suffix = if (design.weight >= 600) "Bold" else if (family.id == "ibmplexmono" && design.weight >= 500) "Medium" else ""
                name = family.upright.split("-")[0] + "-" + suffix + (if (design.italic) "Italic" else if (suffix.isEmpty()) "Regular" else "")
            }
            return name
        }

        /** Swift `font(_:size:folder:)`. [size] is the point (pixel) size the text is set at. */
        fun font(design: TitleDesign, size: Double): TitleFont {
            val family = families.firstOrNull { it.id == design.face }
            // The iOS subtitle fonts (Avenir Next, Georgia, Menlo, DIN Condensed) are not on Android: closest stand-ins.
            if (family == null) SubtitleFont.fromPostScriptName(design.face)?.let { (subtitle, bold) ->
                return TitleFont(subtitleTypeface(subtitle, if (bold) 700 else 400), "", design.face, false)
            }
            if (family == null || family.id == "system") {
                // Swift maps 400…800 onto UI font weight traits; Android's system face takes the CSS weight directly.
                val weight = if (family == null) 400 else min(1000, max(1, design.weight))
                return TitleFont(Typeface.create(Typeface.DEFAULT, weight, family != null && design.italic), "", "system", family != null)
            }
            val name = postScriptName(design)!!
            val face = faces[name] ?: return TitleFont(Typeface.DEFAULT, "", name, false)
            val typeface = typeface(face.file) ?: return TitleFont(Typeface.DEFAULT, "", name, false)
            if (!face.variable) return TitleFont(typeface, "", name, true)
            val axes = listOfNotNull(face.fixed.takeIf { it.isNotEmpty() }, "'wght' ${design.weight}",
                face.opticalSize?.let { "'opsz' %.1f".format(java.util.Locale.ROOT, min(it.endInclusive, max(it.start, size))) })
            return TitleFont(typeface, axes.joinToString(", "), name, true)
        }

        /**
         * Android stand-ins for the iOS subtitle fonts: Modern (Avenir Next) →
         * Atkinson Hyperlegible Next (bundled), Classic (Georgia) → the system
         * serif, Mono (Menlo) → the system monospace, Narrow (DIN Condensed) →
         * the system condensed sans.
         */
        fun subtitleTypeface(font: SubtitleFont, weight: Int): Typeface = when (font) {
            SubtitleFont.system -> Typeface.create(Typeface.DEFAULT, weight, false)
            SubtitleFont.modern -> typeface("atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext[wght].ttf")
                ?.let { Typeface.create(it, if (weight >= 600) 600 else 400, false) } ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
            SubtitleFont.classic -> Typeface.create(Typeface.SERIF, weight, false)
            SubtitleFont.mono -> Typeface.create(Typeface.MONOSPACE, weight, false)
            SubtitleFont.condensed -> Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false)
        }

        fun label(id: String): String {
            families.firstOrNull { it.id == id }?.let { return it.name }
            SubtitleFont.fromPostScriptName(id)?.let { return it.first.title }
            // TODO(port): imported faces (`import:`) and device fonts show their stored identifier.
            return if (id.startsWith("import:")) id.substringAfterLast(":") else id
        }

        fun missingOnDevice(id: String): Boolean = families.none { it.id == id }
    }
}

@Serializable
enum class TitleDirection { up, down, left, right }

@Serializable
enum class TitleMove { none, fade, drift, scale, wipe }

@Serializable
data class TitleMotion(
    val entrance: TitleMove = TitleMove.none,
    val exit: TitleMove = TitleMove.none,
    val inSeconds: Double = 0.35,
    val outSeconds: Double = 0.35,
    val inDirection: TitleDirection? = null,
    val outDirection: TitleDirection? = null,
) {
    val active: Boolean get() = entrance != TitleMove.none || exit != TitleMove.none
    val valid: Boolean get() = inSeconds in 0.05..5.0 && outSeconds in 0.05..5.0

    data class Pose(
        val opacity: Double = 1.0, val scale: Double = 1.0, val rise: Double = 0.0, val shift: Double = 0.0,
        val left: Double = 0.0, val right: Double = 1.0, val top: Double = 0.0, val bottom: Double = 1.0,
    ) {
        /** [rect] in square pixels, top-left origin. */
        fun rect(rect: CGRect): CGRect {
            val width = rect.width * scale
            val height = rect.height * scale
            return CGRect(rect.midX - width / 2 + width * left + shift * rect.height,
                rect.midY - height / 2 + rise * rect.height + height * top,
                width * (right - left), height * (bottom - top))
        }

        /** The visible part of an image of [width]×[height] pixels (x, y, width, height), or null when nothing shows. */
        fun crop(width: Int, height: Int): IntArray? {
            if (!(left > 0 || right < 1 || top > 0 || bottom < 1)) return intArrayOf(0, 0, width, height)
            val a = Math.floor(left * width)
            val b = Math.ceil(right * width)
            val y = Math.floor(top * height)
            val endY = Math.ceil(bottom * height)
            if (!(b > a && endY > y)) return null
            return intArrayOf(a.toInt(), y.toInt(), (b - a).toInt(), (endY - y).toInt())
        }
    }

    private fun fittedEnds(duration: Double): Pair<Double, Double> {
        val a = if (entrance == TitleMove.none) 0.0 else inSeconds
        val b = if (exit == TitleMove.none) 0.0 else outSeconds
        if (!(a + b > 0)) return 0.0 to 0.0
        // Keep a fully readable interval even if both requested transitions exceed the cue.
        val hold = min(0.1, max(0.0, duration * 0.2))
        val fit = min(1.0, max(0.0, duration - hold) / (a + b))
        return a * fit to b * fit
    }

    fun previewWindow(exit: Boolean, duration: Double): ClosedFloatingPointRange<Double> {
        val ends = fittedEnds(duration)
        if (exit) return max(0.0, duration - ends.second - 0.35)..max(0.0, duration)
        return 0.0..max(0.0, min(duration, ends.first + 0.35))
    }

    fun pose(time: Double, duration: Double): Pose {
        val ends = fittedEnds(duration)
        val entering = ends.first > 0 && time < ends.first
        val leaving = ends.second > 0 && time > duration - ends.second
        if (!(entering || leaving)) return Pose()
        val fraction = if (entering) time / ends.first else (duration - time) / ends.second
        val t = min(1.0, max(0.0, fraction))
        val eased = t * t * (3 - 2 * t)
        var p = Pose()
        val direction = if (entering) inDirection else outDirection
        when (if (entering) entrance else exit) {
            TitleMove.none -> {}
            TitleMove.fade -> p = p.copy(opacity = eased)
            TitleMove.drift -> {
                p = p.copy(opacity = eased)
                val offset = (if (entering) -0.32 else 0.32) * (1 - eased)
                p = when (direction ?: TitleDirection.up) {
                    TitleDirection.up -> p.copy(rise = -offset)
                    TitleDirection.down -> p.copy(rise = offset)
                    TitleDirection.left -> p.copy(shift = -offset)
                    TitleDirection.right -> p.copy(shift = offset)
                }
            }
            TitleMove.scale -> p = p.copy(opacity = eased, scale = 1 + (if (entering) -0.12 else 0.08) * (1 - eased))
            TitleMove.wipe -> p = when (direction ?: TitleDirection.right) {
                TitleDirection.right -> if (entering) p.copy(right = eased) else p.copy(left = 1 - eased)
                TitleDirection.left -> if (entering) p.copy(left = 1 - eased) else p.copy(right = eased)
                TitleDirection.down -> if (entering) p.copy(bottom = eased) else p.copy(top = 1 - eased)
                TitleDirection.up -> if (entering) p.copy(top = 1 - eased) else p.copy(bottom = eased)
            }
        }
        return p
    }
}
