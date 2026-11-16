// Port of YouRec/VideoEffects/VideoEffectCatalog.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

val VideoEffectID.historicalHelp: String?
    get() = if (this in setOf(VideoEffectID.colorCorrection, VideoEffectID.nightFix, VideoEffectID.autoBackground, VideoEffectID.freezeFrame)) null else descriptor.historyNote

val VideoEffectID.usageHelp: String
    get() = when (this) {
        VideoEffectID.colorCorrection -> "Start with Exposure and White balance, then adjust Contrast and Saturation. Use the tonal controls to refine shadows, midtones and highlights. Compare with Original while adjusting; clipped detail that was never recorded cannot be recovered."
        VideoEffectID.nightFix -> "Brighten dark footage and reduce noise after recording. Fast gives a quick result; higher quality modes use more processing time. Increase recovery gradually: strong settings can soften detail or expose color blotches."
        VideoEffectID.silentTint -> "Choose a color for the whole image. Tint colors the highlights; Tone colors the shadows while keeping whites neutral. Contrast controls the separation of light and dark."
        VideoEffectID.threeStrip -> "Use Saturation for rich color, Red depth for denser reds, and Warmth to balance the result. Lower Intensity for a gentler look and check skin tones in the preview."
        VideoEffectID.bleachBypass -> "Strength increases contrast and removes color for a dense, metallic look. Lower it if faces or shadows lose too much detail."
        VideoEffectID.dayForNight -> "Darkness and Blue make daylight footage read as night. Moonlight highlights preserve bright accents. It works best with controlled light; a bright daytime sky can still reveal the illusion."
        VideoEffectID.colorIsolation -> "Keep one color while the rest becomes black and white, or keep the person in color. Hue range widens the colors retained; Softness blends their edges. Subject mode requires a visible person."
        VideoEffectID.noir -> "Create a monochrome image with deep shadows. Adjust Contrast and Black crush while watching darker details; add Grain or a slight shadow tint for character."
        VideoEffectID.expressionism -> "Combine hard monochrome contrast with a dark vignette. Choose the iris shape and reduce Vignette if it hides important content near the edges."
        VideoEffectID.quantization -> "Reduce the image to a few colors or gray levels. From scene samples the picture; Pop palettes map brightness to fixed colors. Smoothing reduces speckles. Lock palette keeps colors stable, and Re-sample captures a new palette from the current frame."
        VideoEffectID.dollyZoom -> "During recording, tap the subject and move slowly toward or away from it; zoom tries to keep the subject the same size. Stop before reaching the lens limit. In the editor, the effect separates subject and background using imported depth or a person mask."
        VideoEffectID.crashZoom -> "A quick push toward a face or detail. Adjust Zoom amount, Speed and Hold, then choose whether to return. During recording, tap the subject and trigger the move with the on-screen control or a volume key."
        VideoEffectID.rgbOffset -> "Drag each cross to shift its color layer. The center is the original registration. The outer ring moves a channel by 12% of the picture’s shorter side. Double-tap a pad to center that channel."
        VideoEffectID.negativeStrobe -> "Alternate the picture with its color negative. One cycle includes a positive and a negative image. Use the whole range or set a shorter burst. Camera preview starts when you tap Try strobe; recording starts a fresh cycle."
        VideoEffectID.kaleidoscope -> "Two divisions mirror one half of the picture. Higher counts repeat a wedge around the center. Even counts alternate reflections. Odd counts repeat by rotation. Drag the center to move the split or the round handle to rotate it. Drag a detail to pan the source. Twist two fingers to rotate the source around the split center. The angle settles at 0° near neutral. The cyan arc marks the source wedge. The picture reflects at its edges."
        VideoEffectID.widescreen -> "Choose the frame ratio and bar style, then add horizontal highlight flares if desired. The bars remain clean above other effects. Oval bokeh needs compatible imported depth and is available only in the editor."
        VideoEffectID.freezeFrame -> "Insert a hold at the playhead. Hold duration adds time to the project; Push-in moves slowly into the still. Silence pauses dialogue until footage resumes. Continue can repeat dialogue when the original video resumes."
        VideoEffectID.stepPrinting -> "Repeat groups of frames for stuttering motion. Step controls the repetition and Smear blends motion within each group. Slow also reduces playback speed."
        VideoEffectID.undercranking -> "Choose a low frame cadence for uneven motion, then adjust Speed-up, Flicker and Gate weave. Recording previews the cadence; the editor applies the final speed and audio choice."
        VideoEffectID.halation -> "Glow softens bright areas, Halation red adds warm fringes around highlights, and Flash lifts shadows. Use small amounts to retain contrast."
        VideoEffectID.sixteenMM -> "Choose the grain size and amount, then add softness or gate movement. Grain animates with each frame. The frame edge option adds a rounded film gate."
        VideoEffectID.superEight -> "Combine faded warm color, coarse grain and an unsteady frame. Adjust Fade, Grain and Weave independently; Light leaks adds occasional warm light at the edge."
        VideoEffectID.chromaKey -> "Use a green or blue screen behind the subject. Screen color identifies the color to remove; Background chooses its replacement. Matte view shows what is kept. Despill removes colored fringes. Person hint can remove objects outside a person's silhouette, so turn it off for other subjects."
        VideoEffectID.autoBackground -> "Keep people and replace or blur what is behind them. With no person visible, the whole frame becomes the background. Edge softness and Shrink/Grow refine the outline; Light wrap blends it with the new background. Hair and fast motion can need careful adjustment."
    }

// Browsing follows creative intent, independently of the fixed render pipeline.
// These groups never change EffectFamily, shader order or saved effect IDs.
enum class VideoEffectLibrarySection {
    colorStyle, cameraFrame, time, background;

    val id: String get() = name
    val title: String
        get() = when (this) { colorStyle -> "Color & style"; cameraFrame -> "Camera & frame"; time -> "Time"; background -> "Background" }
    val symbol: String
        get() = when (this) { colorStyle -> "paintpalette"; cameraFrame -> "viewfinder"; time -> "clock"; background -> "person.crop.rectangle" }
    val summary: String
        get() = when (this) {
            colorStyle -> "Color, contrast, grain and glow"
            cameraFrame -> "Zoom, mirror patterns and widescreen framing"
            time -> "Freeze, repeat or speed up motion"
            background -> "Replace or blur what is behind you"
        }
    val kinds: List<VideoEffectID>
        get() = when (this) {
            colorStyle -> listOf(VideoEffectID.nightFix, VideoEffectID.colorCorrection, VideoEffectID.rgbOffset, VideoEffectID.silentTint, VideoEffectID.threeStrip,
                VideoEffectID.bleachBypass, VideoEffectID.dayForNight, VideoEffectID.colorIsolation, VideoEffectID.noir, VideoEffectID.expressionism,
                VideoEffectID.quantization, VideoEffectID.halation, VideoEffectID.sixteenMM, VideoEffectID.superEight)
            cameraFrame -> listOf(VideoEffectID.dollyZoom, VideoEffectID.crashZoom, VideoEffectID.kaleidoscope, VideoEffectID.widescreen)
            time -> listOf(VideoEffectID.negativeStrobe, VideoEffectID.freezeFrame, VideoEffectID.stepPrinting, VideoEffectID.undercranking)
            background -> listOf(VideoEffectID.autoBackground, VideoEffectID.chromaKey)
        }

    fun effects(live: Boolean): List<VideoEffectDescriptor> = kinds.map { it.descriptor }.filter { !live || it.mode != EffectMode.edit }
}

val VideoEffectID.librarySection: VideoEffectLibrarySection get() = VideoEffectLibrarySection.entries.first { this in it.kinds }

val VideoEffectID.summary: String
    get() = when (this) {
        VideoEffectID.rgbOffset -> "Slide the red, green and blue images out of alignment."
        VideoEffectID.negativeStrobe -> "Flash between the original picture and its color negative."
        VideoEffectID.nightFix -> "Lift dark footage and clean noise. Choose fast cleanup or motion-aligned restoration. Editor only."
        VideoEffectID.colorCorrection -> "Balance exposure, contrast, white balance and color before your look."
        VideoEffectID.silentTint -> "Tint the picture with a single film color."
        VideoEffectID.threeStrip -> "Rich colors, deeper reds and dense blacks."
        VideoEffectID.bleachBypass -> "Muted color with strong, metallic contrast."
        VideoEffectID.dayForNight -> "Turn daylight into a cool, dark night look."
        VideoEffectID.colorIsolation -> "Keep one color or the person in color; make the rest monochrome."
        VideoEffectID.noir -> "Black and white with deep shadows and contrast."
        VideoEffectID.expressionism -> "Bold monochrome contrast with a dark vignette."
        VideoEffectID.quantization -> "Reduce the picture to a few colors or shades of gray."
        VideoEffectID.dollyZoom -> "Keep the subject steady while the background stretches."
        VideoEffectID.crashZoom -> "A quick push toward a face or detail."
        VideoEffectID.kaleidoscope -> "Mirror the picture or repeat a wedge around a movable center."
        VideoEffectID.widescreen -> "Change the frame ratio and add horizontal light flares."
        VideoEffectID.freezeFrame -> "Insert a held frame, with optional slow push-in."
        VideoEffectID.stepPrinting -> "Repeat and blend frames for stuttering, smeared motion."
        VideoEffectID.undercranking -> "Quick, uneven motion with a silent-film cadence."
        VideoEffectID.halation -> "Soften bright lights with a warm glow."
        VideoEffectID.sixteenMM -> "Film grain, softness and subtle frame movement."
        VideoEffectID.superEight -> "Warm, faded home-movie color and coarse grain."
        VideoEffectID.chromaKey -> "Remove a green or blue screen and choose a new background."
        VideoEffectID.autoBackground -> "Keep people and blur or replace the background."
    }

// One set of capability/dependency rules for the live and editor inspectors.
object VideoEffectParameterVisibility {
    fun visible(parameter: EffectParameter, effect: VideoEffectSettings, time: Double = 0.0, live: Boolean = false, hasDepth: Boolean = false): Boolean {
        if (effect.kind == VideoEffectID.negativeStrobe && parameter.id == "burstLength") return effect.toggle("timedBurst", time)
        val id = parameter.id
        if (id == "ovalBokeh") return !live && hasDepth
        if (effect.kind == VideoEffectID.quantization) {
            if (id == "palette" && effect.toggle("monochrome", time)) return false
            if (id == "faceDetail" && effect.number("colors", time) > 4) return false
        }
        if (effect.kind == VideoEffectID.chromaKey) {
            val hasPlate = (effect.value("cleanPlate", time) as? EffectValue.media)?.file != null
            if (id in setOf("screenColor", "autoScreen") && hasPlate) return false
            if (id == "screenColor") return !effect.toggle("autoScreen", time)
            if (id == "screenType") return hasPlate || effect.toggle("autoScreen", time)
        }
        if (effect.kind == VideoEffectID.widescreen && id == "flareColor") return effect.number("flares", time) > 0
        if (effect.kind == VideoEffectID.halation && id == "flashColor") return effect.number("flash", time) > 0
        if (effect.kind == VideoEffectID.sixteenMM && id == "grainSize") return effect.number("grain", time) > 0
        if (effect.kind == VideoEffectID.expressionism && id == "iris") return effect.number("vignette", time) > 0
        if (effect.kind == VideoEffectID.colorIsolation && effect.choice("mode", time) == "The subject" && id in setOf("color", "range")) return false
        if (id == "backgroundColor") return effect.choice("background", time) == "Solid"
        if (id in setOf("media", "fit", "backgroundX", "backgroundY", "backgroundZoom", "backgroundAudio")) {
            val mode = effect.choice("background", time)
            if (id == "media") return mode in setOf("Image", "Video")
            if ((effect.value("media", time) as? EffectValue.media)?.file == null) return false
            return if (id == "backgroundAudio") mode == "Video" else mode in setOf("Image", "Video")
        }
        if (id == "backgroundBlur" && effect.choice("background", time) in setOf("Solid", "Blur original"))
            return effect.kind == VideoEffectID.chromaKey && effect.choice("background", time) == "Blur original"
        if (id in setOf("backgroundBlur", "backgroundBrightness", "matchColor", "matchStrength") && effect.choice("background", time) in setOf("Image", "Video")) {
            if ((effect.value("media", time) as? EffectValue.media)?.file == null) return false
        }
        if (id in setOf("matchColor", "matchStrength") && effect.choice("background", time) == "Blur original") return false
        if (id == "portraitBlur") return effect.choice("background", time) == "Blur original"
        if (id == "matchStrength") return effect.toggle("matchColor", time)
        if (effect.kind == VideoEffectID.crashZoom) {
            if (id in setOf("targetX", "targetY") && (live || effect.toggle("trackFace", time))) return false
            if (id in setOf("hold", "return") && effect.releaseTime != null) return false
            if (id == "hold" && !effect.toggle("return", time)) return false
        }
        if (live && effect.kind == VideoEffectID.dollyZoom && id in setOf("strength", "targetX", "targetY", "easing")) return false
        return true
    }

    fun choices(parameter: EffectParameter, effect: VideoEffectSettings): List<String> {
        val kind = parameter.kind as? EffectParameterKind.picker ?: return emptyList()
        return kind.options.filter { !(effect.kind == VideoEffectID.quantization && parameter.id == "palette" && it == "Saved" && effect.palette == null) }
    }
}

object IsolationHue {
    fun accepts(color: EffectRGB): Boolean {
        val high = max(color.red, max(color.green, color.blue)); val low = min(color.red, min(color.green, color.blue))
        return high > 0.001 && (high - low) / high > 0.08
    }
    fun degrees(c: EffectRGB): Double {
        val high = max(c.red, max(c.green, c.blue)); val low = min(c.red, min(c.green, c.blue)); val delta = high - low
        if (delta <= 0) return 0.0
        val phase = if (high == c.red) (c.green - c.blue) / delta else if (high == c.green) 2 + (c.blue - c.red) / delta else 4 + (c.red - c.green) / delta
        return (phase * 60 + 360) % 360
    }
    fun color(degrees: Double): EffectRGB {
        val h = (degrees % 360) / 60
        val x = 1 - abs((h % 2) - 1)
        return when (h.toInt()) {
            0 -> EffectRGB(1.0, x, 0.0); 1 -> EffectRGB(x, 1.0, 0.0); 2 -> EffectRGB(0.0, 1.0, x)
            3 -> EffectRGB(0.0, x, 1.0); 4 -> EffectRGB(x, 0.0, 1.0); else -> EffectRGB(1.0, 0.0, x)
        }
    }
    const val invalidSample = "Choose a colored area. Black, white and gray have no hue to isolate."
}

object ChromaScreen {
    // Match the keyer's linear-light channel-difference test. A generic RGB
    // picker would also offer neutral/red screens this renderer cannot remove.
    fun accepts(color: EffectRGB): Boolean {
        fun linear(x: Double) = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        val r = linear(color.red); val g = linear(color.green); val b = linear(color.blue)
        return g - max(r, b) > 0.02 || b - max(r, g) > 0.02
    }
    const val explanation = "Requires a green or blue screen. For an ordinary room or wall, use Auto Background."
    const val invalidSample = "Choose a green or blue area of the screen. For an ordinary background, use Auto Background."
}

enum class VideoEffectParameterSection {
    general, screen, edges, cleanPlate, background;

    val title: String get() = when (this) { general -> "Effect"; screen -> "Screen"; edges -> "Edges"; cleanPlate -> "Clean plate"; background -> "Background" }

    companion object {
        fun sections(effect: VideoEffectSettings, time: Double = 0.0, live: Boolean = false): List<VideoEffectParameterSection> = entries.filter { section ->
            effect.parameters.any { VideoEffectParameterVisibility.visible(it, effect, time, live) && section(it, effect) == section }
        }
        fun section(parameter: EffectParameter, effect: VideoEffectSettings): VideoEffectParameterSection {
            if (effect.kind != VideoEffectID.chromaKey && effect.kind != VideoEffectID.autoBackground) return general
            val id = parameter.id
            if (id == "cleanPlate") return cleanPlate
            if (id in setOf("balance", "blackClip", "whiteClip", "softness", "grow", "despill", "wrap")) return edges
            if (id.startsWith("background") || id in setOf("media", "fit", "portraitBlur", "matchColor", "matchStrength")) return background
            if (id == "intensity") return general
            return if (effect.kind == VideoEffectID.autoBackground) general else screen
        }
    }
}

// Original effect definitions. Parameter IDs and units are part of the project format.
object VideoEffectCatalog {
    private fun p(id: String, title: String, kind: EffectParameterKind, animatable: Boolean = true) = EffectParameter(id, title, kind, animatable = animatable)
    private fun slider(min: Double, max: Double, defaultValue: Double, step: Double) = EffectParameterKind.slider(min, max, defaultValue, step)
    private fun toggle(value: Boolean) = EffectParameterKind.toggle(value)
    private fun stepper(values: List<Double>, value: Double) = EffectParameterKind.stepper(values, value)
    private fun picker(options: List<String>, value: String) = EffectParameterKind.picker(options, value)
    private fun color(hex: String) = EffectParameterKind.color(EffectRGB(hex))
    private fun d(vararg values: Number) = values.map { it.toDouble() }

    val all: List<VideoEffectDescriptor> by lazy {
        listOf(
            // History: Kodak Essential Reference Guide for Filmmakers, Color Timing/Color Correction.
            VideoEffectDescriptor(VideoEffectID.rgbOffset, "RGB Offset", "Color dye-transfer printing aligned three separate matrices to register the color image.", EffectFamily.color, EffectMode.both,
                RGBChannel.entries.flatMap { channel ->
                    listOf(p(channel.x, channel.title + " X", slider(-1.0, 1.0, 0.0, 0.001)), p(channel.y, channel.title + " Y", slider(-1.0, 1.0, 0.0, 0.001)))
                }),
            VideoEffectDescriptor(VideoEffectID.negativeStrobe, "Negative Strobe", "Experimental flicker films of the 1960s intercut positive and negative images.", EffectFamily.color, EffectMode.both, listOf(
                p("frequency", "Cycles per second", stepper(d(1, 2, 4, 6, 8, 10, 12, 15), 8.0)),
                p("timedBurst", "Limit duration", toggle(false), animatable = false),
                p("burstLength", "Burst length (s)", slider(0.1, 10.0, 1.0, 0.05), animatable = false))),
            VideoEffectDescriptor(VideoEffectID.nightFix, "Night fix", "Digital film restoration combines motion-aligned frames to separate image detail from noise before correcting exposure.", EffectFamily.color, EffectMode.edit, listOf(
                p("mode", "Mode", picker(NightFixMode.entries.map { it.rawValue }, "Fast"), animatable = false),
                p("brightness", "Shadow brightness", slider(0.0, 8.0, 2.5, 0.1)),
                p("reduction", "Noise reduction", slider(0.0, 3.0, 1.0, 0.05)),
                p("detail", "Preserve detail", slider(0.0, 1.0, 0.5, 0.05)))),
            VideoEffectDescriptor(VideoEffectID.colorCorrection, "Color Correction", "Film laboratories balanced each scene with color timing; digital grading added precise control of contrast and individual tones.", EffectFamily.color, EffectMode.both, listOf(
                p("exposure", "Exposure", slider(-4.0, 4.0, 0.0, 0.05)),
                p("contrast", "Contrast", slider(0.25, 2.0, 1.0, 0.01)),
                p("shadows", "Shadows", slider(-1.0, 1.0, 0.0, 0.01)),
                p("highlights", "Highlights", slider(-1.0, 1.0, 0.0, 0.01)),
                p("blacks", "Blacks", slider(-1.0, 1.0, 0.0, 0.01)),
                p("whites", "Whites", slider(-1.0, 1.0, 0.0, 0.01)),
                p("pivot", "Contrast pivot", slider(0.1, 0.9, 0.5, 0.01)),
                p("temperature", "Temperature", slider(-1.0, 1.0, 0.0, 0.01)),
                p("tint", "Tint", slider(-1.0, 1.0, 0.0, 0.01)),
                p("saturation", "Saturation", slider(0.0, 2.0, 1.0, 0.01)),
                p("vibrance", "Vibrance", slider(-1.0, 1.0, 0.0, 0.01)),
                p("hue", "Hue", slider(-180.0, 180.0, 0.0, 1.0)))),
            VideoEffectDescriptor(VideoEffectID.silentTint, "Silent Tint", "Silent cinema used tinting and toning to color whole shots and suggest mood or time of day.", EffectFamily.color, EffectMode.both, listOf(
                p("tint", "Tint", picker(listOf("Night Blue", "Amber", "Fire Red", "Nature Green", "Sepia"), "Amber")),
                p("mode", "Process", picker(listOf("Tint", "Tone"), "Tint")),
                p("contrast", "Contrast", slider(0.8, 1.4, 1.15, 0.01)))),
            VideoEffectDescriptor(VideoEffectID.threeStrip, "Three-Strip", "Three-strip dye-transfer printing layered cyan, yellow and magenta dyes to create rich cinema color.", EffectFamily.color, EffectMode.both, listOf(
                p("saturation", "Saturation", slider(1.0, 1.8, 1.4, 0.01)),
                p("redDepth", "Red depth", slider(0.0, 1.0, 0.6, 0.01)),
                p("warmth", "Warmth", slider(-1.0, 1.0, 0.2, 0.01)))),
            VideoEffectDescriptor(VideoEffectID.bleachBypass, "Bleach Bypass", "Retaining silver during color-film processing produces desaturated color and dense contrast.", EffectFamily.color, EffectMode.both, listOf(
                p("strength", "Strength", slider(0.0, 1.0, 0.7, 0.01)))),
            VideoEffectDescriptor(VideoEffectID.dayForNight, "Day for Night", "Day-for-night photography uses exposure and color filtration to make daylight scenes read as night.", EffectFamily.color, EffectMode.both, listOf(
                p("darkness", "Darkness", slider(0.0, 1.0, 0.6, 0.01)),
                p("blue", "Blue", slider(0.0, 1.0, 0.7, 0.01)),
                p("moonlight", "Moonlight highlights", toggle(true)))),
            VideoEffectDescriptor(VideoEffectID.colorIsolation, "Color Isolation", "Selective color contrasts a colored detail with monochrome surroundings, as in Rumble Fish (1983).", EffectFamily.color, EffectMode.both, listOf(
                p("mode", "Keep", picker(listOf("A color", "The subject"), "A color")),
                p("color", "Hue", color("#C0392B")),
                p("range", "Hue range", slider(5.0, 60.0, 22.0, 1.0)),
                p("softness", "Softness", slider(0.0, 1.0, 0.4, 0.01)))),
            VideoEffectDescriptor(VideoEffectID.noir, "Noir", "Film noir uses low-key lighting and strong shadows; colored lens filters also shaped black-and-white tones.", EffectFamily.color, EffectMode.both, listOf(
                p("contrast", "Contrast", slider(1.0, 2.0, 1.5, 0.01)),
                p("crush", "Black crush", slider(0.0, 0.3, 0.12, 0.01)),
                p("grain", "Grain", toggle(true)),
                p("shadowColor", "Blue shadows", toggle(false)))),
            VideoEffectDescriptor(VideoEffectID.expressionism, "Expressionism", "German Expressionist cinema used stylized shadows and distorted sets, notably in The Cabinet of Dr. Caligari (1920).", EffectFamily.color, EffectMode.both, listOf(
                p("contrast", "Contrast", slider(1.5, 3.0, 2.2, 0.01)),
                p("vignette", "Vignette", slider(0.0, 1.0, 0.7, 0.01)),
                p("iris", "Iris", picker(listOf("Oval", "Circle"), "Oval")),
                p("tint", "Tint", toggle(false)))),
            VideoEffectDescriptor(VideoEffectID.quantization, "Quantization", "Posterization reduces continuous tones to flat bands, echoing photographic silkscreen prints of the 1960s.", EffectFamily.quantization, EffectMode.both, listOf(
                p("colors", "Colors", stepper(d(2, 3, 4, 5, 6, 8, 12, 16), 4.0)),
                p("monochrome", "B&W", toggle(false)),
                p("palette", "Palette", picker(listOf("From scene", "Pop 1", "Pop 2", "Pop 3", "Saved"), "From scene")),
                p("smoothing", "Smoothing", slider(0.0, 1.0, 0.5, 0.01)),
                p("dithering", "Dithering", picker(listOf("Off", "Ordered 4x4", "Ordered 8x8"), "Off")),
                p("faceDetail", "Face detail", toggle(true)),
                p("lockPalette", "Lock palette", toggle(false)))),
            VideoEffectDescriptor(VideoEffectID.dollyZoom, "Dolly Zoom", "Opposing a camera move with zoom changes perspective while keeping the subject the same size, as in Vertigo (1958).", EffectFamily.camera, EffectMode.both, listOf(
                p("direction", "Direction", picker(listOf("Stretch", "Compress"), "Stretch")),
                p("strength", "Strength", slider(0.1, 0.5, 0.3, 0.01)),
                p("duration", "Duration", slider(1.0, 5.0, 2.5, 0.1), animatable = false),
                p("easing", "Easing", picker(listOf("In-out cubic", "Linear"), "In-out cubic")),
                p("targetX", "Subject X", slider(0.0, 1.0, 0.5, 0.01)),
                p("targetY", "Subject Y", slider(0.0, 1.0, 0.5, 0.01)))),
            VideoEffectDescriptor(VideoEffectID.crashZoom, "Crash Zoom", "Fast lens zooms became an expressive device in 1960s and 1970s westerns and martial-arts cinema.", EffectFamily.camera, EffectMode.both, listOf(
                p("zoom", "Zoom amount", slider(1.5, 4.0, 2.5, 0.1)),
                p("speed", "Push duration", slider(0.1, 0.5, 0.18, 0.01), animatable = false),
                p("hold", "Hold", slider(0.0, 3.0, 1.0, 0.1), animatable = false),
                p("return", "Return", toggle(false)),
                p("motionBlur", "Motion blur", slider(0.0, 1.0, 0.6, 0.01)),
                p("targetX", "Target X", slider(0.0, 1.0, 0.5, 0.01)),
                p("targetY", "Target Y", slider(0.0, 1.0, 0.5, 0.01)),
                p("trackFace", "Follow face", toggle(true)))),
            VideoEffectDescriptor(VideoEffectID.kaleidoscope, "Kaleidoscope", "A camera fitted with mirrors turned ordinary workshop scenes into moving patterns in Kaleidoscope Shop (1959).", EffectFamily.camera, EffectMode.both, listOf(
                p("divisions", "Divisions", stepper(d(2, 3, 4, 5, 6, 7, 8, 9), 2.0)),
                p("centerX", "Split center X", slider(0.0, 1.0, 0.5, 0.001)),
                p("centerY", "Split center Y", slider(0.0, 1.0, 0.5, 0.001)),
                p("splitAngle", "Split rotation", slider(-180.0, 180.0, 0.0, 1.0)),
                p("sourcePanX", "Source pan X", slider(-1.0, 1.0, 0.0, 0.001)),
                p("sourcePanY", "Source pan Y", slider(-1.0, 1.0, 0.0, 0.001)),
                p("sourceAngle", "Source rotation", slider(-180.0, 180.0, 0.0, 1.0)))),
            VideoEffectDescriptor(VideoEffectID.widescreen, "Widescreen 1953", "The widescreen expansion of the 1950s brought broad compositions, anamorphic flares and distinctive lens rendering.", EffectFamily.frame, EffectMode.both, listOf(
                p("ratio", "Ratio", picker(listOf("2.39:1", "2.20:1", "1.85:1", "1.33:1"), "2.39:1")),
                p("bars", "Bars", picker(listOf("Black", "Blur fill"), "Black")),
                p("flares", "Flares", slider(0.0, 1.0, 0.4, 0.01)),
                p("flareColor", "Flare color", picker(listOf("Blue", "Amber", "Neutral"), "Blue")),
                p("ovalBokeh", "Oval bokeh", toggle(false)))),
            VideoEffectDescriptor(VideoEffectID.freezeFrame, "Freeze Frame", "A held frame can suspend a film at a decisive moment, as at the end of The 400 Blows (1959).", EffectFamily.time, EffectMode.edit, listOf(
                p("hold", "Hold duration", slider(0.5, 10.0, 3.0, 0.1), animatable = false),
                p("push", "Push-in", slider(0.0, 0.15, 0.05, 0.01)),
                p("style", "Style", picker(listOf("Clean", "Print"), "Clean")),
                p("audio", "Audio", picker(listOf("Cut", "Continue", "Fade out over hold"), "Cut")))),
            VideoEffectDescriptor(VideoEffectID.stepPrinting, "Step Printing", "Repeating printed frames produces stuttering motion, combined with blur in Chungking Express (1994).", EffectFamily.time, EffectMode.edit, listOf(
                p("step", "Step frames", stepper(d(2, 3, 4, 5, 6), 3.0)),
                p("smear", "Smear", slider(0.0, 1.0, 0.7, 0.01)),
                p("speed", "Speed", stepper(d(0.5, 1), 1.0)))),
            VideoEffectDescriptor(VideoEffectID.undercranking, "Undercranking", "Shooting fewer frames than the projection rate gives the quick, uneven movement associated with silent comedy.", EffectFamily.time, EffectMode.both, listOf(
                p("fps", "Frame rate", stepper(d(12, 16, 18), 16.0)),
                p("speed", "Speed-up", slider(1.0, 1.5, 1.25, 0.05)),
                p("flicker", "Flicker", slider(0.0, 1.0, 0.5, 0.01)),
                p("weave", "Gate weave", slider(0.0, 1.0, 0.4, 0.01)),
                p("mute", "Mute audio", toggle(false)))),
            VideoEffectDescriptor(VideoEffectID.halation, "Halation", "Film halation spreads warm light around bright highlights; diffusion and pre-exposure can soften contrast.", EffectFamily.texture, EffectMode.both, listOf(
                p("glow", "Glow", slider(0.0, 1.0, 0.4, 0.01)),
                p("red", "Halation red", slider(0.0, 1.0, 0.5, 0.01)),
                p("flash", "Shadow lift", slider(0.0, 1.0, 0.2, 0.01)),
                p("flashColor", "Flash color", picker(listOf("Neutral", "Warm", "Cool"), "Warm")))),
            VideoEffectDescriptor(VideoEffectID.sixteenMM, "16mm", "Lightweight 16mm cameras helped filmmakers work outside studios and became central to documentary production.", EffectFamily.texture, EffectMode.both, listOf(
                p("grain", "Grain", slider(0.0, 1.0, 0.5, 0.01)),
                p("grainSize", "Grain size", picker(listOf("Fine", "Medium", "Coarse"), "Medium")),
                p("softness", "Softness", slider(0.0, 1.0, 0.3, 0.01)),
                p("weave", "Gate weave", slider(0.0, 1.0, 0.2, 0.01)),
                p("frameEdge", "Frame edge", toggle(false)),
                p("color", "Color", picker(listOf("Color", "B&W"), "Color")))),
            VideoEffectDescriptor(VideoEffectID.superEight, "Super 8", "Introduced in 1965, the small-gauge cartridge format made home-movie filmmaking more accessible.", EffectFamily.texture, EffectMode.both, listOf(
                p("fade", "Fade", slider(0.0, 1.0, 0.5, 0.01)),
                p("grain", "Grain", slider(0.0, 1.0, 0.7, 0.01)),
                p("weave", "Weave", slider(0.0, 1.0, 0.5, 0.01)),
                p("fps", "Frame rate", stepper(d(18, 24), 18.0)),
                p("leaks", "Light leaks", slider(0.0, 1.0, 0.2, 0.01)),
                p("border", "Frame border", toggle(true)))),
            VideoEffectDescriptor(VideoEffectID.chromaKey, "Chroma Key", "Color-difference travelling mattes separate a subject photographed against a blue or green backing.", EffectFamily.key, EffectMode.both, listOf(
                p("screenColor", "Manual screen color", color("#00FF00")),
                p("screenType", "Screen type", picker(listOf("Auto", "Green", "Blue"), "Auto")),
                p("autoScreen", "Detect green / blue screen", toggle(true)),
                p("strength", "Strength", slider(0.0, 2.0, 1.0, 0.01)),
                p("balance", "Balance", slider(0.0, 1.0, 0.5, 0.01)),
                p("blackClip", "Black clip", slider(0.0, 0.5, 0.05, 0.01)),
                p("whiteClip", "White clip", slider(0.5, 1.0, 0.9, 0.01)),
                p("softness", "Edge softness", slider(0.0, 1.0, 0.3, 0.01)),
                p("grow", "Shrink / grow", slider(-5.0, 5.0, 0.0, 0.1)),
                p("despill", "Despill", slider(0.0, 1.0, 0.8, 0.01)),
                p("wrap", "Light wrap", slider(0.0, 1.0, 0.25, 0.01)),
                p("personHint", "Use person hint", toggle(false)),
                p("cleanPlate", "Clean plate", EffectParameterKind.media(image = true, video = false)),
                p("view", "View", picker(listOf("Result", "Matte", "Original"), "Result")),
                p("background", "Background", picker(listOf("Solid", "Image", "Video", "Blur original", "Studio", "Window"), "Solid")),
                p("backgroundColor", "Background color", color("#20252D")),
                p("media", "Background media", EffectParameterKind.media(image = true, video = true)),
                p("fit", "Framing", picker(listOf("Fill", "Fit"), "Fill")),
                p("backgroundX", "Background X", slider(0.0, 1.0, 0.5, 0.01)),
                p("backgroundY", "Background Y", slider(0.0, 1.0, 0.5, 0.01)),
                p("backgroundZoom", "Background zoom", slider(1.0, 4.0, 1.0, 0.05)),
                p("backgroundBlur", "Background blur", slider(0.0, 20.0, 0.0, 1.0)),
                p("backgroundBrightness", "Background brightness", slider(-1.0, 1.0, 0.0, 0.01)),
                p("backgroundAudio", "Background sound", toggle(false)),
                p("matchColor", "Match color", toggle(false)),
                p("matchStrength", "Match strength", slider(0.0, 1.0, 0.3, 0.01)))),
            VideoEffectDescriptor(VideoEffectID.autoBackground, "Auto Background", "The travelling-matte tradition continues with a subject mask made by the camera instead of a colored screen.", EffectFamily.key, EffectMode.both, listOf(
                p("softness", "Edge softness", slider(0.0, 1.0, 0.3, 0.01)),
                p("grow", "Shrink / grow", slider(-5.0, 5.0, 0.0, 0.1)),
                p("wrap", "Light wrap", slider(0.0, 1.0, 0.25, 0.01)),
                p("portraitBlur", "Portrait blur", slider(0.0, 40.0, 12.0, 1.0)),
                p("view", "View", picker(listOf("Result", "Matte", "Original"), "Result")),
                p("background", "Background", picker(listOf("Solid", "Image", "Video", "Blur original", "Studio", "Window"), "Blur original")),
                p("backgroundColor", "Background color", color("#20252D")),
                p("media", "Background media", EffectParameterKind.media(image = true, video = true)),
                p("fit", "Framing", picker(listOf("Fill", "Fit"), "Fill")),
                p("backgroundX", "Background X", slider(0.0, 1.0, 0.5, 0.01)),
                p("backgroundY", "Background Y", slider(0.0, 1.0, 0.5, 0.01)),
                p("backgroundZoom", "Background zoom", slider(1.0, 4.0, 1.0, 0.05)),
                p("backgroundBlur", "Background blur", slider(0.0, 20.0, 0.0, 1.0)),
                p("backgroundBrightness", "Background brightness", slider(-1.0, 1.0, 0.0, 0.01)),
                p("backgroundAudio", "Background sound", toggle(false)),
                p("matchColor", "Match color", toggle(false)),
                p("matchStrength", "Match strength", slider(0.0, 1.0, 0.3, 0.01)))),
        )
    }
}

// Shared display semantics for editor and recording controls. Temperature and
// tint are relative corrections, never a claim about the camera's Kelvin value.
object ColorCorrectionControls {
    val light = listOf("exposure", "contrast", "shadows", "highlights")
    val advanced = listOf("blacks", "whites", "pivot")
    val color = listOf("saturation", "vibrance", "temperature", "tint", "hue")

    fun display(id: String, raw: Double): String {
        val value = if (abs(raw) < 0.00001) 0.0 else raw
        val l = java.util.Locale.ROOT
        return when (id) {
            "exposure" -> String.format(l, "%+.2f EV", value)
            "contrast", "saturation", "intensity", "pivot" -> String.format(l, "%.0f%%", value * 100)
            "hue" -> if (swiftRound(value) == 0.0) "0°" else String.format(l, "%+.0f°", value)
            else -> if (swiftRound(value * 100) == 0.0) "0" else String.format(l, "%+.0f", value * 100)
        }
    }

    fun help(id: String): String = when (id) {
        "exposure" -> "Brightens or darkens the whole picture in stops. +1 EV doubles the light. This grades the image; it does not change the camera exposure. Highlights roll off gently. Like the other video effects, this grades HDR after the app’s SDR tone map; it cannot recover HDR headroom discarded by that map."
        "contrast" -> "Separates light and dark tones around the contrast pivot. 100% is unchanged. Color proportions are preserved."
        "shadows" -> "Raises or lowers darker tones, while keeping the black endpoint. Use Blacks to lift or deepen the very darkest areas."
        "highlights" -> "Raises or lowers brighter tones, while keeping the white endpoint. Use Whites to change the brightest areas."
        "blacks" -> "Lifts or deepens the darkest endpoint. Positive values soften black; negative values deepen black with a gentle toe."
        "whites" -> "Raises or lowers the brightest endpoint. Positive values brighten whites with a gentle roll-off."
        "pivot" -> "The tone that stays fixed as contrast changes. 50% is a middle display tone. Has no effect at 100% contrast."
        "temperature" -> "Relative white balance: negative is cooler (blue), positive is warmer (amber). Zero preserves the recorded balance; very bright whites stay white. This is not a Kelvin setting."
        "tint" -> "Relative white balance: negative adds green, positive adds magenta. Useful for correcting a color cast."
        "saturation" -> "Controls all color intensity. 100% is unchanged; 0% is black and white."
        "vibrance" -> "Adjusts muted colors more than already vivid ones. Positive adjustments are gentler on common skin hues."
        "hue" -> "Rotates all colors around the color wheel, preserving luminance. Zero preserves the original hues."
        else -> "Blends this correction with its input. 100% applies the full correction; 0% bypasses it."
    }
}
