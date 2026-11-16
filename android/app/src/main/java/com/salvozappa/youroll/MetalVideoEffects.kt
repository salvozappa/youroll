// Port of YouRec/VideoEffects/MetalVideoEffects.swift (editor rendering)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS renders the effect chain with Metal compute kernels inside its custom
// AVVideoCompositor, and the same compositor feeds the AVPlayer preview and the
// export. YouRoll runs the GLSL port of those kernels (VideoEffectsShaders.kt)
// through one renderer, `MetalEffectRenderer`, in three places, so preview ==
// export:
//
// - the export: one `VideoEffectsGlEffect` per picture piece of the Media3
//   Composition, after the framing (crop or fit) and Mirror, before titles,
//   image overlays and placement (Swift `preparedImage`, then the effects,
//   then `VideoOverlayRenderer.apply`, then `TrackRenderer.placed`);
// - the preview: the same effect installed on every ExoPlayer (main and
//   placement layers). While the project has effects it also does the picture
//   framing in GL, and finds each frame's clip from its presentation time, which
//   ExoPlayer reports on the playlist's clock (the project time);
// - transition windows: TransitionRenderer.kt applies the renderer to each
//   participant before blending.
//
// `render` follows Swift `MetalEffectRenderer.render`: the active effects in
// the fixed family order (key, camera, time, color, quantization, texture,
// frame), each `encode`d with its auxiliary passes (blur, highlights, bilateral
// smoothing, grain lattice). Quantization analyses its scene palette from the
// picture it receives (Swift `VideoEffectAnalysis.palette` + `frameContexts`),
// every 0.2 s of source time with the track's temporal smoothing.
//
// Cadence holds (Undercranking, Super 8: `VideoEffectSamplePlan`): the shader
// program keeps the rendered look of the first frame of each cadence group and
// repeats it until the next group, so the held picture, parameters and grain
// stay frozen as on iOS.
//
// Not ported (TODO.md): person masks, depth, mattes and keys (Dolly Zoom,
// Auto Background, Chroma Key, Color Isolation's subject mode and temporal
// matte, Quantization's face detail, Widescreen's oval bokeh), Night fix, Step
// Printing's frame blocks, the HDR tone map, Crash Zoom's face tracking, live
// recorder effects and GPU profiling.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VideoEffectError(message: String) : Exception(message)

/** Which editor effects YouRoll can render (the rest stay in the project and are refused by the export). */
object VideoEffectSupport {
    /** Effects that need person segmentation, depth, a background key or offline analysis YouRoll does not have yet. */
    val unavailableKinds = setOf(VideoEffectID.nightFix, VideoEffectID.dollyZoom, VideoEffectID.chromaKey, VideoEffectID.autoBackground, VideoEffectID.stepPrinting)

    fun available(kind: VideoEffectID): Boolean = kind !in unavailableKinds

    private fun everValue(effect: VideoEffectSettings, id: String): List<EffectValue> =
        effect.parameters.firstOrNull { it.id == id }?.let { listOf(it.value) + it.keyframes.map { k -> k.value } }.orEmpty()

    /** Null when the renderer can draw [effect]; otherwise the reason the export refuses it. */
    fun unsupported(effect: VideoEffectSettings): String? {
        if (!effect.enabled) return null
        if (effect.kind in unavailableKinds) return "${effect.kind.descriptor.displayName} needs ${
            when (effect.kind) {
                VideoEffectID.nightFix -> "motion-aligned restoration"
                VideoEffectID.stepPrinting -> "frame-block printing"
                VideoEffectID.chromaKey -> "background keying"
                else -> "person or depth separation"
            }}, which YouRoll can’t render yet."
        if (effect.kind == VideoEffectID.colorIsolation && everValue(effect, "mode").contains(EffectValue.choice("The subject")))
            return "Color Isolation’s subject mode needs person separation, which YouRoll can’t render yet."
        if (effect.kind == VideoEffectID.crashZoom && everValue(effect, "trackFace").contains(EffectValue.toggle(true)))
            return "Crash Zoom’s Follow face needs face tracking, which YouRoll can’t render yet. Turn off Follow face."
        return null
    }
}

/** True when the clip has an enabled video effect (its picture goes through the effect renderer). */
val TimelineClip.hasVideoEffects: Boolean get() = videoEffects.orEmpty().any { it.enabled }

/** Swift `FrameContext` (the fields the ported effects use). */
data class EffectFrame(
    /** Source seconds whose parameter values are drawn (Swift `presentationTime` in `render`). */
    val sourceTime: Double,
    /** Seconds that number the grain frames: the source sample time, or the composition time of a hold. */
    val presentationTime: Double,
    val frameRate: Double = 30.0,
    /** Elapsed seconds into a freeze (Swift `holdTime`), for Freeze Frame and Negative Strobe. */
    val holdTime: Double? = null,
)

/**
 * One picture's effect clock: its clip, how its source maps to the timeline,
 * and (preview only) the picture framing the renderer applies first.
 */
class VideoEffectLayer(
    val clip: TimelineClip,
    /** The piece's timeline interval and source mapping (seconds). */
    val start: Double,
    val end: Double,
    val sourceStart: Double,
    val rate: Double,
    /** Timeline start of the clip's item: a hold's clock origin. */
    val itemStart: Double,
    /** Preview only: the clip's framing (crop or fit, Mirror) into the canvas. */
    val framing: PreviewFraming? = null,
) {
    val effects: List<VideoEffectSettings> = clip.videoEffects.orEmpty().filter { it.kind != VideoEffectID.stepPrinting && it.kind != VideoEffectID.nightFix }
    val clock: VideoEffectCadenceClock? by lazy { VideoEffectSequenceClock(clip).cadence.takeUnless { it.isEmpty } }

    fun frame(timeUs: Long): EffectFrame {
        val time = timeUs / 1_000_000.0
        val hold = clip.frozenFrame
        if (hold != null) return EffectFrame(hold.sourceTime, time, holdTime = hold.offset + max(0.0, time - itemStart))
        val source = sourceStart + (min(time, end) - start) * rate
        return EffectFrame(source, source)
    }
}

/** Swift `VideoEffectSequenceClock` (VideoEffectSampling.swift): keep the trim bounds, integrate from the effect's own origin. */
class VideoEffectSequenceClock(clip: TimelineClip) {
    val lower = clip.start
    val upper = clip.end
    val cadence: VideoEffectCadenceClock

    init {
        val effects = clip.videoEffects.orEmpty().filter { it.enabled && (it.kind == VideoEffectID.superEight || it.kind == VideoEffectID.undercranking) }
        val extended = clip.copy(pauses = null, start = min(clip.start, effects.minOfOrNull { it.start } ?: clip.start),
            end = max(clip.end, effects.maxOfOrNull { it.end } ?: clip.end))
        cadence = VideoEffectCadenceClock(effects, extended.effectPlaybackSpans.map { Triple(it.start, it.end, it.rate) })
    }
}

/**
 * Swift `MetalEffectRenderer`: GL programs and scratch textures for one GL
 * context (GLES 3.0). Confined to the thread that owns that context.
 */
class MetalEffectRenderer : AutoCloseable {
    private class Program(val id: Int) {
        val uniforms = HashMap<String, Int>()
        fun u(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
    }
    class Texture(val id: Int, val fbo: Int, val width: Int, val height: Int)

    private val programs = HashMap<String, Program>()
    private val scratch = HashMap<String, MutableList<Texture>>()
    private val scratchCounts = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).also { it.position(0) }
    private var halfFloat: Boolean? = null
    private val blurWeights = HashMap<Float, FloatArray>()
    /** Scene palettes per effect, analysed every 0.2 s of source time (Swift `EffectPaletteTrack`). */
    private class PaletteState(val time: Double, val palette: EffectPalette, val locked: Boolean)
    private val palettes = HashMap<UUID, PaletteState>()
    /** Analysed palettes by effect, for "Save palette from this frame" and "Lock palette". */
    val analyzedPalettes = HashMap<UUID, EffectPalette>()

    private val format: Int
        get() {
            val known = halfFloat ?: run {
                val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
                val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
                val supported = "GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions || "OpenGL ES 3.2" in version
                halfFloat = supported
                supported
            }
            return if (known) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        }

    private fun program(name: String, pass: VideoEffectsShaders.Pass, kind: Int = -1): Program = programs.getOrPut(name) {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw VideoEffectError("The video effect could not be prepared: $name ($log)")
            }
            return shader
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VideoEffectsShaders.vertex)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, VideoEffectsShaders.fragment(pass, kind))
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex); GLES20.glAttachShader(id, fragment)
        GLES20.glBindAttribLocation(id, 0, "aPosition")
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw VideoEffectError("The video effect could not be prepared: $name (${GLES20.glGetProgramInfoLog(id)})")
        Program(id)
    }

    fun texture(width: Int, height: Int, internal: Int = format): Texture {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val type = if (internal == GLES30.GL_RGBA16F) GLES30.GL_HALF_FLOAT else GLES20.GL_UNSIGNED_BYTE
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internal, width, height, 0, GLES20.GL_RGBA, type, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[0], 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            if (internal == GLES30.GL_RGBA16F) {
                // Half-float colour buffers are optional on GLES 3.0: fall back to 8 bits.
                GLES20.glDeleteFramebuffers(1, fbos, 0); GLES20.glDeleteTextures(1, ids, 0)
                halfFloat = false
                return texture(width, height, GLES30.GL_RGBA8)
            }
            throw VideoEffectError("The video effect could not allocate its working image.")
        }
        return Texture(ids[0], fbos[0], width, height)
    }

    /** Swift `temporary`: per-frame scratch textures, reused between ordered stages. */
    private fun temporary(width: Int, height: Int): Texture {
        val key = "${width}x$height"
        val index = scratchCounts[key] ?: 0
        scratchCounts[key] = index + 1
        val list = scratch.getOrPut(key) { mutableListOf() }
        while (list.size <= index) list += texture(width, height)
        return list[index]
    }

    private fun draw(program: Program, target: Texture?, width: Int, height: Int, inputs: List<Pair<String, Int>>, uniforms: (Program) -> Unit = {}) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target?.fbo ?: drawFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glUseProgram(program.id)
        for ((index, input) in inputs.withIndex()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.second)
            GLES20.glUniform1i(program.u(input.first), index)
        }
        GLES20.glUniform2f(program.u("outSize"), width.toFloat(), height.toFloat())
        uniforms(program)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        for (index in inputs.indices.reversed()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    /** The framebuffer that a `null` target draws into (the caller's output). */
    private var drawFramebuffer = 0

    private fun pass(name: String, pass: VideoEffectsShaders.Pass, input: Texture, output: Texture, uniforms: (Program) -> Unit = {}) =
        draw(program(name, pass), output, output.width, output.height, listOf("input0" to input.id), uniforms)

    private fun reduce(input: Texture, output: Texture) = pass("reduce", VideoEffectsShaders.Pass.reduce, input, output)

    /** Swift `blur(_:sigma:size:horizontalOnly:)`. */
    fun blur(input: Texture, sigma: Double, size: Pair<Int, Int>? = null, horizontalOnly: Boolean = false): Texture {
        val requestedWidth = size?.first ?: input.width
        val requestedHeight = size?.second ?: input.height
        val downscale = min(1.0, 14 / max(0.25, sigma * requestedWidth / input.width))
        val width = max(2, (requestedWidth * downscale).toInt())
        val height = max(2, (requestedHeight * downscale).toInt())
        var source = input
        if (width < input.width || height < input.height) {
            source = temporary(width, height)
            reduce(input, source)
        }
        val a = temporary(width, height)
        val b = temporary(width, height)
        val radius = (sigma * width / input.width).toFloat()
        gaussian(source, a, radius, true)
        gaussian(a, b, radius, horizontalOnly)
        return b
    }

    private fun gaussian(source: Texture, output: Texture, requested: Float, horizontal: Boolean) {
        val sigma = max(0.25f, requested)
        val weights = blurWeights.getOrPut(sigma) {
            val radius = min(48, ceil(sigma * 3.0).toInt())
            val raw = (0..radius).map { exp(-(it * it).toFloat() / (2 * sigma * sigma)) }
            val total = raw[0] + 2 * raw.drop(1).sum()
            val values = FloatArray(25 * 4)
            values[0] = raw[0] / total; values[1] = ((radius + 1) / 2).toFloat()
            var i = 1
            while (i <= radius) {
                val a = raw[i]; val b = if (i + 1 <= radius) raw[i + 1] else 0f
                val k = (i + 1) / 2
                values[k * 4] = i + b / (a + b); values[k * 4 + 1] = (a + b) / total
                i += 2
            }
            if (blurWeights.size > 256) blurWeights.clear()
            values
        }
        pass("blur", VideoEffectsShaders.Pass.blur, source, output) { p ->
            GLES20.glUniform4f(p.u("header"), weights[0], weights[1], if (horizontal) 1f else 0f, if (horizontal) 0f else 1f)
            GLES20.glUniform4fv(p.u("pairs"), 24, weights, 4)
        }
    }

    /**
     * Swift `render(_:effects:sourceTime:...)` into [outputFramebuffer]
     * ([outputWidth]×[outputHeight]), from the SDR texture [input]. [matrix]
     * (column-major mat3) maps top-down output pixels to top-down input uv.
     * Returns the working texture holding the result (valid until the next call).
     */
    fun render(input: Int, matrix: FloatArray, effects: List<VideoEffectSettings>, frame: EffectFrame,
               outputFramebuffer: Int, outputWidth: Int, outputHeight: Int, present: Boolean = true): Texture {
        drawFramebuffer = outputFramebuffer
        scratchCounts.clear()
        val time = frame.sourceTime
        val enabled = VideoEffectSettings.ordered(effects).filter { effect ->
            if (!(effect.enabled && time >= effect.start && time < effect.end)) return@filter false
            if (VideoEffectSupport.unsupported(effect) != null) return@filter false
            when (effect.kind) {
                VideoEffectID.negativeStrobe -> effect.strobeShowsNegative(time, frame.frameRate, frame.holdTime)
                VideoEffectID.rgbOffset -> RGBChannel.entries.any { effect.number(it.x, time) != 0.0 || effect.number(it.y, time) != 0.0 }
                else -> true
            }
        }
        val active = enabled.filter { !it.kind.descriptor.supportsIntensity || it.number("intensity", time) > 0 }
        val decoded = temporary(outputWidth, outputHeight)
        draw(program("decode", VideoEffectsShaders.Pass.decode), decoded, outputWidth, outputHeight, listOf("input0" to input)) { p ->
            GLES20.glUniformMatrix3fv(p.u("matrix"), 1, false, matrix, 0)
        }
        val mapped = temporary(outputWidth, outputHeight)
        val other = temporary(outputWidth, outputHeight)
        val base = HashMap(scratchCounts)
        var current = decoded
        // Sequence sampling is handled by the timeline. Its pixel decorations
        // precede Frame so a held push-in or weave never shifts the clean bars.
        val stages = listOf(EffectFamily.key, EffectFamily.camera, EffectFamily.time, EffectFamily.color, EffectFamily.quantization, EffectFamily.texture, EffectFamily.frame)
        val pixelOrder = stages.flatMap { stage -> active.filter { it.kind.descriptor.family == stage } }
        for (settings in pixelOrder) {
            scratchCounts.clear(); scratchCounts.putAll(base) // Reuse auxiliary textures between ordered GPU stages.
            val output = if (current === mapped) other else mapped
            val effectTime = (if (settings.kind == VideoEffectID.freezeFrame) frame.holdTime else null)
                ?: max(0.0, time - settings.start + (settings.phaseOffset ?: 0.0))
            encode(settings, current, output, frame, effectTime)
            current = output
        }
        if (present) draw(program("encode", VideoEffectsShaders.Pass.encode), null, outputWidth, outputHeight, listOf("input0" to current.id))
        return current
    }

    /** Draws a rendered working texture into the output framebuffer (cadence holds). */
    fun present(texture: Texture, outputFramebuffer: Int, outputWidth: Int, outputHeight: Int) {
        drawFramebuffer = outputFramebuffer
        draw(program("encode", VideoEffectsShaders.Pass.encode), null, outputWidth, outputHeight, listOf("input0" to texture.id))
    }

    /** Copies [texture] into [target] (both working space). */
    fun copy(texture: Texture, target: Texture) = pass("copy", VideoEffectsShaders.Pass.copy, texture, target)

    private fun grainNoise(width: Int, height: Int, frameIndex: Int): Texture {
        val noise = temporary(width, height)
        draw(program("grainNoise", VideoEffectsShaders.Pass.grainNoise), noise, width, height, emptyList()) { p ->
            GLES30.glUniform1ui(p.u("uFrame"), frameIndex)
        }
        return noise
    }

    /** Swift `encode(_:input:output:frame:commandBuffer:)` for the ported kinds. */
    private fun encode(settings: VideoEffectSettings, input: Texture, output: Texture, frame: EffectFrame, effectTime: Double) {
        val width = input.width; val height = input.height
        val scale = min(width, height) / 1080.0
        val time = frame.sourceTime
        val frameIndex = VideoEffectTime.frameIndex(frame.presentationTime, frame.frameRate)
        val grainSize: Double? = when (settings.kind) {
            VideoEffectID.noir -> if (settings.toggle("grain", time)) 1.0 else null
            VideoEffectID.freezeFrame -> if (settings.choice("style", time) == "Print") 1.5 else null
            VideoEffectID.sixteenMM -> mapOf("Fine" to 1.0, "Medium" to 1.5, "Coarse" to 2.2)[settings.choice("grainSize", time)] ?: 1.5
            VideoEffectID.superEight -> 2.2
            else -> null
        }
        var noise = input
        if (grainSize != null) noise = grainNoise(ceil(width / (scale * grainSize)).toInt() + 1, ceil(height / (scale * grainSize)).toInt() + 1, frameIndex)
        var aux = input; var background = input; var extra = input
        var palette: EffectPalette? = null
        when (settings.kind) {
            VideoEffectID.quantization -> {
                palette = palette(settings, input, frame)
                if (settings.number("smoothing", time) > 0) {
                    val factor = 1 / max(1.0, scale)
                    val w = max(2, (width * factor).toInt()); val h = max(2, (height * factor).toInt())
                    val intermediate = temporary(w, h); aux = temporary(w, h)
                    val sigma = ((1 + 4 * settings.number("smoothing", time)) * scale * factor).toFloat()
                    pass("bilateral", VideoEffectsShaders.Pass.bilateral, input, intermediate) { p -> GLES20.glUniform4f(p.u("values"), sigma, 1f, 0f, 0f) }
                    pass("bilateral", VideoEffectsShaders.Pass.bilateral, intermediate, aux) { p -> GLES20.glUniform4f(p.u("values"), sigma, 0f, 1f, 0f) }
                }
            }
            VideoEffectID.sixteenMM -> if (settings.number("softness", time) > 0) aux = blur(input, 1.5 * scale)
            VideoEffectID.superEight -> aux = blur(input, 2 * scale)
            VideoEffectID.halation, VideoEffectID.widescreen -> {
                val small = temporary(max(2, width / 4), max(2, height / 4))
                val wide = settings.kind == VideoEffectID.widescreen
                pass("highlights", VideoEffectsShaders.Pass.highlights, input, small) { p ->
                    GLES20.glUniform4f(p.u("values"), if (wide) 0.9f else 0.75f, if (wide) 0.05f else 0.1f, 0f, 0f)
                }
                if (wide) {
                    aux = blur(small, width / 24.0 / 4, horizontalOnly = true)
                    if (settings.choice("bars", time) == "Blur fill") background = blur(input, 12 * scale, max(2, width / 4) to max(2, height / 4))
                } else {
                    aux = blur(small, width * 0.01 / 4); background = blur(small, width * 0.006 / 4); extra = blur(small, width * 0.04 / 4)
                }
            }
            else -> {}
        }
        val vectors = uniforms(settings, time)
        val header = intArrayOf(settings.kind.shaderIndex, frameIndex, if (settings.releaseTime == null) 0 else 4, palette?.colors?.size ?: 0)
        palette?.let { value ->
            for ((index, color) in value.colors.take(16).withIndex()) {
                val c = EffectColorMath.lab(EffectColorMath.linear(color))
                vectors[(40 + index) * 4] = c.x.toFloat(); vectors[(40 + index) * 4 + 1] = c.y.toFloat(); vectors[(40 + index) * 4 + 2] = c.z.toFloat(); vectors[(40 + index) * 4 + 3] = 1f
            }
            for ((index, threshold) in value.thresholds.take(15).withIndex()) vectors[(56 + index / 4) * 4 + index % 4] = threshold.toFloat()
        }
        val intensity = if (settings.kind.descriptor.supportsIntensity) settings.number("intensity", time).toFloat() else 1f
        draw(program("effect-${settings.kind.shaderIndex}", VideoEffectsShaders.Pass.effect, settings.kind.shaderIndex), output, output.width, output.height,
            listOf("input0" to input.id, "aux" to aux.id, "mask" to input.id, "background" to background.id, "extra" to extra.id,
                "wrapImage" to input.id, "noiseLattice" to noise.id)) { p ->
            GLES30.glUniform1ui(p.u("uKind"), header[0]); GLES30.glUniform1ui(p.u("uFrame"), header[1])
            GLES30.glUniform1ui(p.u("uFlags"), header[2]); GLES30.glUniform1ui(p.u("uCount"), header[3])
            GLES20.glUniform1f(p.u("uTime"), effectTime.toFloat()); GLES20.glUniform1f(p.u("uIntensity"), intensity)
            GLES20.glUniform1f(p.u("uDuration"), (settings.end - settings.start).toFloat()); GLES20.glUniform1f(p.u("uFps"), frame.frameRate.toFloat())
            GLES20.glUniform4fv(p.u("p"), 40, vectors, 0)
            GLES20.glUniform4fv(p.u("palette"), 16, vectors, 40 * 4)
            GLES20.glUniform4fv(p.u("thresholds"), 4, vectors, 56 * 4)
        }
    }

    companion object {
        /**
         * Swift `uniforms(_:frame:)`: one vec4 per descriptor parameter (in
         * catalogue order, without intensity), then the palette and thresholds.
         */
        fun uniforms(settings: VideoEffectSettings, time: Double): FloatArray {
            val vectors = FloatArray((40 + 16 + 4) * 4)
            for ((index, definition) in settings.kind.descriptor.parameters.withIndex()) {
                when (val value = settings.value(definition.id, time) ?: definition.value) {
                    is EffectValue.number -> vectors[index * 4] = value.value.toFloat()
                    is EffectValue.toggle -> vectors[index * 4] = if (value.value) 1f else 0f
                    is EffectValue.choice -> (definition.kind as? EffectParameterKind.picker)?.let { vectors[index * 4] = max(0, it.options.indexOf(value.value)).toFloat() }
                    is EffectValue.color -> {
                        val c = EffectColorMath.linear(value.value)
                        vectors[index * 4] = c.x.toFloat(); vectors[index * 4 + 1] = c.y.toFloat(); vectors[index * 4 + 2] = c.z.toFloat(); vectors[index * 4 + 3] = 1f
                    }
                    is EffectValue.media -> {}
                }
            }
            settings.releaseTime?.let { vectors[39 * 4] = max(0.0, it - settings.start + (settings.phaseOffset ?: 0.0)).toFloat() }
            return vectors
        }
    }

    /**
     * The palette Quantization draws with (Swift `frameContexts` +
     * `displayPalette`): the scene palette of the picture it receives, analysed
     * on a 64×36 reduction every 0.2 s of source time and smoothed like the
     * palette track; a locked palette is not re-analysed.
     */
    private fun palette(settings: VideoEffectSettings, input: Texture, frame: EffectFrame): EffectPalette? {
        val time = frame.sourceTime
        val count = settings.number("colors", time).toInt()
        val locked = settings.toggle("lockPalette", time)
        val state = palettes[settings.id]
        val fresh = state == null || state.palette.colors.size != count || time < state.time - 0.000001 || time - state.time >= 0.2 - 0.00001 ||
            (state.locked != locked)
        val analyzed = if (!fresh) state!!.palette else if (locked && state?.locked == true && state.palette.colors.size == count) state.palette else {
            val previous = state?.takeIf { it.palette.colors.size == count && time >= it.time && time - it.time < 1.0 }
            val samples = samples(input)
            EffectPaletteAnalysis.analyze(samples, count, previous?.palette, previous?.let { time - it.time } ?: 0.2,
                VideoEffectTime.frameIndex(frame.presentationTime, 30.0).toULong()).also {
                palettes[settings.id] = PaletteState(time, it, locked)
            }
        }
        analyzedPalettes[settings.id] = analyzed
        return VideoEffectInputs.displayPalette(settings, time, analyzed)
    }

    /** Swift `VideoEffectAnalysis.samples`: the picture reduced to 64×36 linear colours. */
    private fun samples(input: Texture): List<Vec3> {
        val (tw, th) = VideoEffectAnalysis.sampleSize(input.width, input.height)
        var current = input
        while (current.width > tw * 4 || current.height > th * 4) {
            val next = temporary(max(tw, ceil(current.width / 4.0).toInt()), max(th, ceil(current.height / 4.0).toInt()))
            reduce(current, next); current = next
        }
        val reduced = temporary(tw, th)
        reduce(current, reduced)
        val bytes = readback ?: texture(tw, th, GLES30.GL_RGBA8).also { readback = it }
        val target = if (bytes.width == tw && bytes.height == th) bytes else texture(tw, th, GLES30.GL_RGBA8).also { readback = it }
        draw(program("encode", VideoEffectsShaders.Pass.encode), target, tw, th, listOf("input0" to reduced.id))
        val buffer = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo)
        GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val result = ArrayList<Vec3>(tw * th)
        for (i in 0 until tw * th) {
            fun channel(k: Int) = EffectColorMath.linear((buffer.get(i * 4 + k).toInt() and 255) / 255.0)
            result += Vec3(channel(0), channel(1), channel(2))
        }
        return result
    }
    private var readback: Texture? = null

    override fun close() {
        for (list in scratch.values) for (texture in list) release(texture)
        scratch.clear()
        readback?.let { release(it) }; readback = null
        for (program in programs.values) GLES20.glDeleteProgram(program.id)
        programs.clear()
    }

    fun release(texture: Texture) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(texture.fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture.id), 0)
    }

    /** Forgets the streaming palette state (after a seek or a new project). */
    fun reset() { palettes.clear() }
}

/**
 * The column-major mat3 that maps top-down output pixels to top-down input uv:
 * the inverse of [canvasFromSource] (input pixels → output pixels), then a
 * division by the input size. Null maps the output 1:1 onto the input.
 */
fun videoEffectInputMatrix(canvasFromSource: CGAffineTransform?, inputWidth: Int, inputHeight: Int, outputWidth: Int, outputHeight: Int): FloatArray {
    val toSource = canvasFromSource?.inverted() ?: CGAffineTransform.scale(inputWidth.toDouble() / outputWidth, inputHeight.toDouble() / outputHeight)
    val m = toSource.concatenating(CGAffineTransform.scale(1.0 / inputWidth, 1.0 / inputHeight))
    // (u, v) = (a·x + c·y + tx, b·x + d·y + ty); column-major columns: (a, b, 0), (c, d, 0), (tx, ty, 1).
    return floatArrayOf(m.a.toFloat(), m.b.toFloat(), 0f, m.c.toFloat(), m.d.toFloat(), 0f, m.tx.toFloat(), m.ty.toFloat(), 1f)
}

/** Where a shader program finds the effect clock of each frame. */
interface VideoEffectSource {
    /** The layer drawn at [presentationTimeUs], or null to pass the picture through. */
    fun layer(presentationTimeUs: Long): VideoEffectLayer?
    /** The output size, or null to keep the input size (the export's input is already framed). */
    val outputSize: CGSize? get() = null
    /** Called on the GL thread after a frame is drawn, with the renderer (palette sampling). */
    fun rendered(renderer: MetalEffectRenderer, layer: VideoEffectLayer?) {}
}

/** The Media3 effect wrapping [MetalEffectRenderer] (Swift `ShaderVideoEffect` inside the compositor). */
class VideoEffectsGlEffect(private val source: VideoEffectSource) : GlEffect {
    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram {
        VideoEffectInputs.install(context)
        return VideoEffectsShaderProgram(source)
    }
}

class VideoEffectsShaderProgram(private val source: VideoEffectSource) : BaseGlShaderProgram(false, 1) {
    private val renderer = MetalEffectRenderer()
    private var inputWidth = 0
    private var inputHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    // Cadence holds: the rendered look of the current group's first frame.
    private var held: MetalEffectRenderer.Texture? = null
    private var heldKey: Pair<VideoEffectLayer, Int>? = null
    private var lastTimeUs = Long.MIN_VALUE
    private var logged = false

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth; this.inputHeight = inputHeight
        val output = source.outputSize
        outputWidth = output?.width?.roundToInt() ?: inputWidth
        outputHeight = output?.height?.roundToInt() ?: inputHeight
        return Size(outputWidth, outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val binding = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, binding, 0)
        val layer = source.layer(presentationTimeUs)
        if (presentationTimeUs < lastTimeUs) { renderer.reset(); heldKey = null }
        lastTimeUs = presentationTimeUs
        if (!logged) {
            logged = true
            android.util.Log.i("VideoEffects", "First frame at ${presentationTimeUs / 1e6} s: ${inputWidth}×$inputHeight → ${outputWidth}×$outputHeight, " +
                "${layer?.effects?.size ?: 0} effects (${GLES20.glGetString(GLES20.GL_VERSION)})")
        }
        // The preview's framing of this picture (its crop or fit, then Mirror) from its decoded size.
        val framing = layer?.framing?.let { PreviewFraming(it.canvas, CGSize(inputWidth.toDouble(), inputHeight.toDouble()), it.crop, mirror = it.mirror) }
        val matrix = videoEffectInputMatrix(framing?.transform ?: source.outputSize?.let {
            // A picture without its framing (a transition clip, or before the canvas is known): fit it.
            val scale = min(outputWidth.toDouble() / inputWidth, outputHeight.toDouble() / inputHeight)
            CGAffineTransform.scale(scale, scale).concatenating(CGAffineTransform.translation((outputWidth - inputWidth * scale) / 2, (outputHeight - inputHeight * scale) / 2))
        }, inputWidth, inputHeight, outputWidth, outputHeight)
        if (layer == null) {
            renderer.render(inputTexId, matrix, emptyList(), EffectFrame(0.0, 0.0), binding[0], outputWidth, outputHeight)
            source.rendered(renderer, null)
            return
        }
        val frame = layer.frame(presentationTimeUs)
        // A cadence hold freezes the entire rendered look (Swift `VideoEffectSamplePlan`).
        val hold = if (layer.clip.frozenFrame == null) layer.clock?.hold(frame.sourceTime) else null
        val key = hold?.let { layer to it.index }
        val cached = held
        if (key != null && key == heldKey && cached != null) {
            renderer.present(cached, binding[0], outputWidth, outputHeight)
        } else {
            val result = renderer.render(inputTexId, matrix, layer.effects, frame, binding[0], outputWidth, outputHeight)
            if (key != null) {
                val target = cached?.takeIf { it.width == result.width && it.height == result.height }
                    ?: renderer.texture(result.width, result.height).also { texture -> cached?.let { renderer.release(it) }; held = texture }
                renderer.copy(result, target)
                heldKey = key
            } else heldKey = null
        }
        source.rendered(renderer, layer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, binding[0])
    }

    override fun release() {
        super.release()
        held?.let { renderer.release(it) }; held = null
        renderer.close()
    }
}
