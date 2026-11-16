// Port of YouRec/VideoEffects/VideoEffectModel.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Value semantics: every type here is immutable; the Swift `mutating` functions
// return a changed copy (`settingKeyframe`, `setting`, …). Effect documents are
// shared between undo states, so nothing may be changed in place.
//
// JSON: the Swift synthesized `Codable` forms, written by hand because they use
// Swift's enum-with-associated-values encoding:
//   EffectValue          {"number":{"_0":1.5}}  {"media":{}} (nil file)
//   EffectParameterKind  {"slider":{"min":0,"max":1,"defaultValue":1,"step":0.01}}
// `VideoEffectSettings` decodes like Swift's custom `init(from:)`: bounds, labels
// and choices come from the installed catalogue; unknown effect kinds are skipped
// at collection boundaries (`SupportedVideoEffects`).
package com.salvozappa.youroll

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

// Source-time values survive trims, pauses, speed changes and clip splits.
// Categorical parameters use hold interpolation; continuous values interpolate.
enum class EffectFamily {
    key, camera, color, quantization, texture, frame, time;

    val order: Int get() = ordinal
    val title: String
        get() = when (this) {
            key -> "Background"; camera -> "Camera"; color -> "Color & tone"
            quantization -> "Quantization"; texture -> "Film texture"; frame -> "Frame"; time -> "Time"
        }
}

enum class VideoEffectQuality { full, lite }

enum class NightFixMode(val rawValue: String) {
    fast("Fast"), medium("Medium"), precise("Precise");

    val radius: Int get() = when (this) { fast -> 0; medium -> 1; precise -> 4 }
    val guideLimit: Double get() = if (this == medium) 256.0 else 512.0
    val explanation: String
        get() = when (this) {
            fast -> "Single-frame cleanup. Preview and export use this result, with no extra preparation."
            medium -> "Combines 3 motion-aligned frames for cleaner noise with less waiting."
            precise -> "Combines 9 motion-aligned frames for the strongest temporal cleanup. Takes longer to prepare."
        }

    companion object { fun of(raw: String) = entries.firstOrNull { it.rawValue == raw } }
}

enum class EffectMode { live, edit, both }

enum class RGBChannel {
    red, green, blue;

    val title: String get() = name.replaceFirstChar { it.uppercase() }
    val x: String get() = name + "X"
    val y: String get() = name + "Y"
}

enum class VideoEffectID {
    rgbOffset, negativeStrobe, nightFix, colorCorrection, silentTint, threeStrip, bleachBypass, dayForNight, colorIsolation, noir, expressionism,
    quantization, dollyZoom, crashZoom, kaleidoscope, widescreen, freezeFrame, stepPrinting, undercranking,
    halation, sixteenMM, superEight, chromaKey, autoBackground;

    val rawValue: String get() = name

    // Stable shader ABI; adding or reordering catalogue entries must not change it.
    val shaderIndex: Int
        get() = when (this) {
            rgbOffset -> 23; negativeStrobe -> 24; kaleidoscope -> 22; nightFix -> 21; colorCorrection -> 20
            silentTint -> 0; threeStrip -> 1; bleachBypass -> 2; dayForNight -> 3; colorIsolation -> 4; noir -> 5
            expressionism -> 6; quantization -> 7; dollyZoom -> 8; crashZoom -> 9; widescreen -> 11
            freezeFrame -> 12; stepPrinting -> 13; undercranking -> 14; halation -> 15; sixteenMM -> 16; superEight -> 17
            chromaKey -> 18; autoBackground -> 19
        }
    val descriptor: VideoEffectDescriptor get() = VideoEffectCatalog.all.first { it.id == this }

    companion object {
        fun of(raw: String): VideoEffectID? = entries.firstOrNull { it.name == raw }
    }
}

@kotlinx.serialization.Serializable
data class EffectRGB(val red: Double, val green: Double, val blue: Double) {
    val valid: Boolean get() = listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }

    companion object {
        operator fun invoke(hex: String): EffectRGB {
            val value = hex.replace("#", "").toLongOrNull(16) ?: 0L
            return EffectRGB(((value shr 16) and 255) / 255.0, ((value shr 8) and 255) / 255.0, (value and 255) / 255.0)
        }
    }
}

sealed class EffectValue {
    data class number(val value: Double) : EffectValue()
    data class toggle(val value: Boolean) : EffectValue()
    data class choice(val value: String) : EffectValue()
    data class color(val value: EffectRGB) : EffectValue()
    data class media(val file: String?) : EffectValue()

    fun interpolated(other: EffectValue, fraction: Double): EffectValue {
        val t = min(1.0, max(0.0, fraction))
        if (this is number && other is number) return number(value + (other.value - value) * t)
        if (this is color && other is color) return color(EffectRGB(value.red + (other.value.red - value.red) * t,
            value.green + (other.value.green - value.green) * t, value.blue + (other.value.blue - value.blue) * t))
        return if (t < 1) this else other
    }

    fun json(): JsonElement = when (this) {
        is number -> buildJsonObject { put("number", buildJsonObject { put("_0", value) }) }
        is toggle -> buildJsonObject { put("toggle", buildJsonObject { put("_0", value) }) }
        is choice -> buildJsonObject { put("choice", buildJsonObject { put("_0", value) }) }
        is color -> buildJsonObject { put("color", buildJsonObject { put("_0", SwiftJSON.encodeToJsonElement(EffectRGB.serializer(), value)) }) }
        is media -> buildJsonObject { put("media", buildJsonObject { file?.let { put("_0", it) } }) }
    }

    companion object {
        fun of(json: JsonElement): EffectValue {
            val obj = json as? JsonObject ?: throw SerializationException("EffectValue")
            val (case, payload) = obj.entries.singleOrNull() ?: throw SerializationException("EffectValue case")
            val value = payload.field("_0")
            return when (case) {
                "number" -> number((value as? JsonPrimitive)?.doubleOrNull ?: throw SerializationException("number"))
                "toggle" -> toggle((value as? JsonPrimitive)?.booleanOrNull ?: throw SerializationException("toggle"))
                "choice" -> choice((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SerializationException("choice"))
                "color" -> color(SwiftJSON.decodeFromJsonElement(EffectRGB.serializer(), value ?: throw SerializationException("color")))
                "media" -> media((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
                else -> throw SerializationException("Unknown EffectValue $case")
            }
        }
    }
}

sealed class EffectParameterKind {
    data class slider(val min: Double, val max: Double, val defaultValue: Double, val step: Double) : EffectParameterKind()
    data class toggle(val defaultValue: Boolean) : EffectParameterKind()
    data class stepper(val values: List<Double>, val defaultValue: Double) : EffectParameterKind()
    data class color(val defaultValue: EffectRGB) : EffectParameterKind()
    data class picker(val options: List<String>, val defaultValue: String) : EffectParameterKind()
    data class media(val image: Boolean, val video: Boolean) : EffectParameterKind()

    /** Swift `EffectParameterKind.defaultValue` (renamed: Kotlin cases already have a `defaultValue` field). */
    val initialValue: EffectValue
        get() = when (this) {
            is slider -> EffectValue.number(defaultValue)
            is stepper -> EffectValue.number(defaultValue)
            is toggle -> EffectValue.toggle(defaultValue)
            is color -> EffectValue.color(defaultValue)
            is picker -> EffectValue.choice(defaultValue)
            is media -> EffectValue.media(null)
        }
    val continuous: Boolean get() = this is slider || this is color

    fun accepts(value: EffectValue): Boolean = when {
        this is slider && value is EffectValue.number -> value.value.isFinite() && value.value in min..max
        this is stepper && value is EffectValue.number -> values.contains(value.value)
        this is toggle && value is EffectValue.toggle -> true
        this is color && value is EffectValue.color -> value.value.valid
        this is picker && value is EffectValue.choice -> options.contains(value.value)
        this is media && value is EffectValue.media -> value.file?.let { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            name.isNotEmpty() && name == java.io.File(name).name && !name.startsWith(".") && !name.contains("\\") &&
                (image && ext in setOf("png", "jpg", "jpeg", "heic", "heif", "tif", "tiff", "webp") || video && ext in setOf("mov", "mp4", "m4v"))
        } ?: true
        else -> false
    }

    fun json(): JsonElement = when (this) {
        is slider -> buildJsonObject { put("slider", buildJsonObject { put("min", min); put("max", max); put("defaultValue", defaultValue); put("step", step) }) }
        is toggle -> buildJsonObject { put("toggle", buildJsonObject { put("defaultValue", defaultValue) }) }
        is stepper -> buildJsonObject { put("stepper", buildJsonObject { put("values", JsonArray(values.map { JsonPrimitive(it) })); put("defaultValue", defaultValue) }) }
        is color -> buildJsonObject { put("color", buildJsonObject { put("defaultValue", SwiftJSON.encodeToJsonElement(EffectRGB.serializer(), defaultValue)) }) }
        is picker -> buildJsonObject { put("picker", buildJsonObject { put("options", JsonArray(options.map { JsonPrimitive(it) })); put("defaultValue", defaultValue) }) }
        is media -> buildJsonObject { put("media", buildJsonObject { put("image", image); put("video", video) }) }
    }

    companion object {
        fun of(json: JsonElement): EffectParameterKind {
            val obj = json as? JsonObject ?: throw SerializationException("EffectParameterKind")
            val (case, p) = obj.entries.singleOrNull() ?: throw SerializationException("EffectParameterKind case")
            fun d(name: String) = p.double(name) ?: throw SerializationException(name)
            fun b(name: String) = p.bool(name) ?: throw SerializationException(name)
            return when (case) {
                "slider" -> slider(d("min"), d("max"), d("defaultValue"), d("step"))
                "toggle" -> toggle(b("defaultValue"))
                "stepper" -> stepper(p.field("values").array().map { (it as? JsonPrimitive)?.doubleOrNull ?: throw SerializationException("values") }, d("defaultValue"))
                "color" -> color(SwiftJSON.decodeFromJsonElement(EffectRGB.serializer(), p.field("defaultValue") ?: throw SerializationException("color")))
                "picker" -> picker(p.field("options").array().map { (it as? JsonPrimitive)?.content ?: throw SerializationException("options") },
                    p.string("defaultValue") ?: throw SerializationException("defaultValue"))
                "media" -> media(b("image"), b("video"))
                else -> throw SerializationException("Unknown parameter kind $case")
            }
        }
    }
}

data class EffectKeyframe(val id: UUID = newUUID(), val time: Double, val value: EffectValue) {
    fun json(): JsonElement = buildJsonObject { put("id", id.uuidString); put("time", time); put("value", value.json()) }

    companion object {
        fun of(json: JsonElement) = EffectKeyframe(
            json.string("id")?.let(::uuidOrNull) ?: throw SerializationException("keyframe id"),
            json.double("time") ?: throw SerializationException("keyframe time"),
            EffectValue.of(json.field("value") ?: throw SerializationException("keyframe value")))
    }
}

data class EffectParameter(
    val id: String,
    val title: String,
    val kind: EffectParameterKind,
    val value: EffectValue = kind.initialValue,
    val keyframes: List<EffectKeyframe> = emptyList(),
    val animatable: Boolean = true,
) {
    fun value(time: Double): EffectValue {
        val first = keyframes.firstOrNull() ?: return value
        if (time <= first.time) return first.value
        for ((a, b) in keyframes.zipWithNext()) {
            if (time >= b.time) continue
            val fraction = (time - a.time) / (b.time - a.time)
            if ((id == "splitAngle" || id == "sourceAngle") && a.value is EffectValue.number && b.value is EffectValue.number) {
                val start = a.value.value
                val difference = b.value.value - start
                val travel = if (abs(difference) > 180) difference - 360 * sign(difference) else difference
                return EffectValue.number(wrappedDegrees(start + travel * fraction))
            }
            return if (kind.continuous) a.value.interpolated(b.value, fraction) else a.value
        }
        return keyframes.last().value
    }

    /** Swift `mutating setKeyframe(at:value:)`. */
    fun settingKeyframe(time: Double, value: EffectValue): EffectParameter {
        if (!animatable || !time.isFinite() || time < 0 || !kind.accepts(value)) return this
        val kept = keyframes.filter { abs(it.time - time) >= 1.0 / 60000 }
        return copy(keyframes = (kept + EffectKeyframe(time = time, value = value)).sortedBy { it.time })
    }

    val valid: Boolean
        get() = kind.accepts(value) && (animatable || keyframes.isEmpty()) && keyframes.size <= 100_000 &&
            keyframes.map { it.id }.toSet().size == keyframes.size &&
            keyframes.all { it.time.isFinite() && it.time in 0.0..604800.0 && kind.accepts(it.value) } &&
            keyframes.zipWithNext().all { (a, b) -> a.time < b.time }

    fun json(): JsonElement = buildJsonObject {
        put("id", id); put("title", title); put("kind", kind.json()); put("value", value.json())
        put("keyframes", JsonArray(keyframes.map { it.json() })); put("animatable", animatable)
    }

    companion object {
        fun wrappedDegrees(value: Double): Double = value - 360 * floor((value + 180) / 360)

        fun of(json: JsonElement): EffectParameter {
            val kind = EffectParameterKind.of(json.field("kind") ?: throw SerializationException("parameter kind"))
            return EffectParameter(
                json.string("id") ?: throw SerializationException("parameter id"),
                json.string("title") ?: throw SerializationException("parameter title"),
                kind,
                EffectValue.of(json.field("value") ?: throw SerializationException("parameter value")),
                (json.field("keyframes") as? JsonArray ?: throw SerializationException("keyframes")).map { EffectKeyframe.of(it) },
                json.bool("animatable") ?: throw SerializationException("animatable"))
        }
    }
}

class VideoEffectDescriptor(
    val id: VideoEffectID,
    val displayName: String,
    val historyNote: String,
    val family: EffectFamily,
    val mode: EffectMode,
    val parameters: List<EffectParameter>,
) {
    val supportsIntensity: Boolean get() = family != EffectFamily.time && family != EffectFamily.camera
}

@kotlinx.serialization.Serializable
data class StrobeTimePoint(val source: Double, val elapsed: Double)

@kotlinx.serialization.Serializable(with = VideoEffectSettingsSerializer::class)
data class VideoEffectSettings(
    val id: UUID = newUUID(),
    val kind: VideoEffectID,
    val enabled: Boolean = true,
    val version: Int = 1,
    val start: Double = 0.0,
    val end: Double = 604800.0,
    val parameters: List<EffectParameter>,
    // Persisted analysis/palette assets are immutable, project-local files.
    val analysisFile: String? = null,
    val analysisDataFile: String? = null,
    val capturedEffectID: UUID? = null,
    val palette: List<EffectRGB>? = null,
    val lockedPalette: EffectPalette? = null,
    val motion: EffectAnimation? = null,
    val algorithm: Int? = null, // Absent in legacy recipes; preserves their rendering.
    val strobeTimeMap: List<StrobeTimePoint>? = null, // Playback seconds at authoring time for sped-up or cut source material.
    val phaseOffset: Double? = null, // Elapsed time carried into a captured camera move or an editor strobe across clips.
    val releaseTime: Double? = null, // Source-time manual return of a captured Crash Zoom.
) {
    val analysisID: UUID get() = capturedEffectID ?: id
    val captureIdentity: VideoEffectSettings get() = copy(id = analysisID, capturedEffectID = null)
    val nightFixMode: NightFixMode get() = NightFixMode.of(choice("mode")) ?: NightFixMode.fast

    val requiresOfflineAnalysis: Boolean
        get() {
            if (!enabled) return false
            return when (kind) {
                VideoEffectID.nightFix -> nightFixMode != NightFixMode.fast
                VideoEffectID.quantization, VideoEffectID.autoBackground, VideoEffectID.dollyZoom -> true
                VideoEffectID.crashZoom -> toggle("trackFace", start) || parameters.any { it.id == "trackFace" && it.keyframes.isNotEmpty() }
                VideoEffectID.colorIsolation, VideoEffectID.chromaKey -> usesModernPersonMask
                else -> false
            }
        }
    val usesTemporalMatte: Boolean get() = algorithm == 2 && kind in setOf(VideoEffectID.colorIsolation, VideoEffectID.chromaKey)
    val usesModernPersonMask: Boolean get() = algorithm == 1 || usesTemporalMatte
    val usesAutoMaskStream: Boolean get() = kind == VideoEffectID.autoBackground && algorithm == 1

    fun isActive(time: Double): Boolean =
        enabled && time >= start && time < end && (!kind.descriptor.supportsIntensity || number("intensity", time) > 0)

    fun value(id: String, time: Double = 0.0): EffectValue? {
        val motion = motion
        if (motion != null && motion.a.values[id] != null) return EffectValue.number(motion.value(id, motion.fraction(time)))
        return parameters.firstOrNull { it.id == id }?.value(time)
    }
    fun number(id: String, time: Double = 0.0): Double = (value(id, time) as? EffectValue.number)?.value ?: 0.0
    fun toggle(id: String, time: Double = 0.0): Boolean = (value(id, time) as? EffectValue.toggle)?.value ?: false
    fun choice(id: String, time: Double = 0.0): String = (value(id, time) as? EffectValue.choice)?.value ?: ""
    fun color(id: String, time: Double = 0.0): EffectRGB = (value(id, time) as? EffectValue.color)?.value ?: EffectRGB(0.0, 0.0, 0.0)

    /** Swift `mutating set(_:_:)`. */
    fun setting(id: String, value: EffectValue): VideoEffectSettings {
        val index = parameters.indexOfFirst { it.id == id }
        if (index < 0 || !parameters[index].kind.accepts(value)) return this
        return copy(parameters = parameters.updated(index) { it.copy(value = value) })
    }

    /** Applies [transform] to the parameter [id], if present. */
    fun updatingParameter(id: String, transform: (EffectParameter) -> EffectParameter): VideoEffectSettings {
        val index = parameters.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(parameters = parameters.updated(index, transform))
    }

    val recordingResult: VideoEffectSettings
        get() = updatingParameter("view") { it.copy(value = EffectValue.choice("Result"), keyframes = emptyList()) }

    /** Swift `mutating sanitizeBackgroundMedia(at:)`. */
    fun sanitizingBackgroundMedia(time: Double): VideoEffectSettings {
        val mode = choice("background", time)
        if (mode !in setOf("Image", "Video")) return this
        val file = (value("media", time) as? EffectValue.media)?.file ?: return this
        val index = parameters.indexOfFirst { it.id == "media" }
        if (index < 0) return this
        val isVideo = file.substringAfterLast('.', "").lowercase() in setOf("mov", "mp4", "m4v")
        if ((mode == "Video") == isVideo) return this
        return if (parameters[index].keyframes.isNotEmpty() || parameters.firstOrNull { it.id == "background" }?.keyframes?.isEmpty() == false) {
            copy(parameters = parameters.updated(index) { it.settingKeyframe(time, EffectValue.media(null)) })
        } else copy(parameters = parameters.updated(index) { it.copy(value = EffectValue.media(null)) })
    }

    val mediaFiles: List<String>
        get() {
            val files = listOfNotNull(analysisFile, analysisDataFile).toMutableList()
            for (p in parameters) for (v in listOf(p.value) + p.keyframes.map { it.value }) if (v is EffectValue.media && v.file != null) files += v.file
            return files.toSet().sorted()
        }

    val valid: Boolean
        get() {
            val reference = VideoEffectSettings(kind).parameters
            if (motion != null) {
                if (kind != VideoEffectID.colorCorrection) return false
                if (!motion.valid(correctionPose(start)) { p -> reference.all { it.kind.accepts(EffectValue.number(p[it.id])) } }) return false
            }
            val strobeOK = strobeTimeMap?.let { points ->
                kind == VideoEffectID.negativeStrobe && points.size >= 2 && points.size <= 100_000 &&
                    points.all { it.source.isFinite() && it.elapsed.isFinite() && it.elapsed in 0.0..604800.0 } &&
                    points.zipWithNext().all { (a, b) -> a.source < b.source && a.elapsed <= b.elapsed }
            } ?: true
            return (algorithm == null || algorithm == 1 || usesTemporalMatte) && version == 1 && start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= 604800 &&
                parameters.size == reference.size && parameters.map { it.id }.toSet().size == parameters.size &&
                parameters.all { p -> p.valid && reference.any { it.id == p.id && it.kind == p.kind && it.animatable == p.animatable } } &&
                strobeOK &&
                (releaseTime?.let { kind == VideoEffectID.crashZoom && it.isFinite() && it >= start - (phaseOffset ?: 0.0) && it < end } ?: true) &&
                (phaseOffset?.let { (kind == VideoEffectID.crashZoom || kind == VideoEffectID.negativeStrobe) && it.isFinite() && it in 0.0..604800.0 } ?: true) &&
                (lockedPalette?.valid ?: true) &&
                (palette?.let { it.size in 2..16 && it.all { c -> c.valid } } ?: true) &&
                mediaFiles.all { !it.startsWith(".") && !it.contains("\\") && it == java.io.File(it).name }
        }

    fun strobeShowsNegative(time: Double, fps: Double, holdTime: Double? = null): Boolean {
        fun elapsed(t: Double): Double {
            val points = strobeTimeMap
            if (points.isNullOrEmpty()) return max(0.0, t - start)
            val first = points.first(); val last = points.last()
            if (t <= first.source) return first.elapsed
            if (t >= last.source) return last.elapsed
            var lo = 0; var hi = points.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (points[mid].source <= t) lo = mid else hi = mid
            }
            val a = points[lo]; val b = points[hi]
            return a.elapsed + (b.elapsed - a.elapsed) * (t - a.source) / (b.source - a.source)
        }
        val offset = phaseOffset ?: 0.0
        val clock = offset + (holdTime ?: elapsed(time))
        if (toggle("timedBurst") && clock >= number("burstLength") - 1e-8) return false
        val frequency = parameters.firstOrNull { it.id == "frequency" } ?: return false
        val ceiling = max(0.5, fps * 0.5)
        fun rate(value: EffectValue): Double = if (value is EffectValue.number) min(ceiling, value.value) else 8.0
        // Integrate held rate keys. Changing speed cannot rewind the phase,
        // and seeking to a frame produces exactly the same polarity as playing it.
        var hz = rate(frequency.value(start))
        var last = 0.0
        var cycles = 0.0
        for (key in frequency.keyframes) {
            if (key.time <= start) continue
            if (key.time > time) break
            val next = offset + elapsed(key.time)
            cycles += (next - last) * hz; last = next; hz = rate(key.value)
        }
        cycles += (clock - last) * hz
        return floor(cycles * 2 + 1e-7) % 2.0 == 1.0
    }

    // Captured moves may be retimed in the editor. Never leave their old end
    // cutting off an extended push/hold, and never overlap a later trigger.
    fun retimingCapturedCrash(previous: VideoEffectSettings, limit: Double): VideoEffectSettings {
        val timing = setOf("speed", "hold", "return")
        if (parameters.filter { it.id in timing } == previous.parameters.filter { it.id in timing }) return this
        return refreshingCapturedCrashEnd(limit)
    }

    fun refreshingCapturedCrashEnd(limit: Double): VideoEffectSettings {
        if (kind != VideoEffectID.crashZoom || phaseOffset == null || limit <= start) return this
        fun maximum(id: String): Double {
            val p = parameters.firstOrNull { it.id == id } ?: return 0.0
            return (listOf(p.value) + p.keyframes.map { it.value }).mapNotNull { (it as? EffectValue.number)?.value }.maxOrNull() ?: 0.0
        }
        val push = maximum("speed"); val hold = maximum("hold"); val origin = start - (phaseOffset)
        val allReturn = parameters.firstOrNull { it.id == "return" }?.let { p -> (listOf(p.value) + p.keyframes.map { it.value }).all { it == EffectValue.toggle(true) } } ?: false
        val desired = releaseTime?.let { it + push } ?: if (allReturn) origin + 2 * push + 0.08 + hold else limit
        val end = max(start + min(1.0 / 30, limit - start), min(limit, desired))
        val release = releaseTime?.takeIf { it < end }
        return copy(end = end, releaseTime = release)
    }

    companion object {
        /** Swift `VideoEffectSettings(_:start:end:)`. */
        operator fun invoke(kind: VideoEffectID, start: Double = 0.0, end: Double = 604800.0): VideoEffectSettings {
            var parameters = kind.descriptor.parameters
            if (kind.descriptor.supportsIntensity) parameters = listOf(EffectParameter("intensity", "Intensity", EffectParameterKind.slider(0.0, 1.0, 1.0, 0.01))) + parameters
            return VideoEffectSettings(kind = kind, start = start, end = end, parameters = parameters,
                algorithm = if (kind == VideoEffectID.colorIsolation || kind == VideoEffectID.chromaKey) 2 else 1)
        }

        fun capturedRecipe(effects: List<VideoEffectSettings>, duration: Double): List<VideoEffectSettings> = effects.mapNotNull { effect ->
            if (effect.start >= duration) return@mapNotNull null
            var result = effect.copy(end = min(duration, effect.end))
            result.releaseTime?.let { if (it >= result.end) result = result.copy(releaseTime = null) }
            if (result.end > result.start) result else null
        }

        fun ordered(effects: List<VideoEffectSettings>): List<VideoEffectSettings> = effects.withIndex().sortedWith { a, b ->
            val x = a.value.kind; val y = b.value.kind
            when {
                (x == VideoEffectID.nightFix) != (y == VideoEffectID.nightFix) -> if (x == VideoEffectID.nightFix) -1 else 1
                x.descriptor.family.order != y.descriptor.family.order -> x.descriptor.family.order.compareTo(y.descriptor.family.order)
                (x == VideoEffectID.kaleidoscope) != (y == VideoEffectID.kaleidoscope) -> if (y == VideoEffectID.kaleidoscope) -1 else 1
                // Primary balance precedes creative color looks, regardless of add order.
                (x == VideoEffectID.colorCorrection) != (y == VideoEffectID.colorCorrection) -> if (x == VideoEffectID.colorCorrection) -1 else 1
                else -> a.index.compareTo(b.index)
            }
        }.map { it.value }

        // ---- JSON (Swift synthesized encoder; custom decoder) ----

        fun json(value: VideoEffectSettings): JsonElement = buildJsonObject {
            value.motion?.let { put("motion", SwiftJSON.encodeToJsonElement(EffectAnimation.serializer(), it)) }
            put("id", value.id.uuidString); put("kind", value.kind.rawValue); put("enabled", value.enabled); put("version", value.version)
            put("start", value.start); put("end", value.end); put("parameters", JsonArray(value.parameters.map { it.json() }))
            value.analysisFile?.let { put("analysisFile", it) }
            value.analysisDataFile?.let { put("analysisDataFile", it) }
            value.capturedEffectID?.let { put("capturedEffectID", it.uuidString) }
            value.palette?.let { p -> put("palette", JsonArray(p.map { SwiftJSON.encodeToJsonElement(EffectRGB.serializer(), it) })) }
            value.lockedPalette?.let { put("lockedPalette", SwiftJSON.encodeToJsonElement(EffectPalette.serializer(), it)) }
            value.releaseTime?.let { put("releaseTime", it) }
            value.phaseOffset?.let { put("phaseOffset", it) }
            value.algorithm?.let { put("algorithm", it) }
            value.strobeTimeMap?.let { m -> put("strobeTimeMap", JsonArray(m.map { SwiftJSON.encodeToJsonElement(StrobeTimePoint.serializer(), it) })) }
        }

        fun of(json: JsonElement): VideoEffectSettings {
            val kind = VideoEffectID.of(json.string("kind") ?: throw SerializationException("kind")) ?: throw SerializationException("Unknown effect kind")
            val base = VideoEffectSettings(kind)
            val reference = base.parameters
            val stored = (json.field("parameters") as? JsonArray ?: throw SerializationException("parameters")).map { EffectParameter.of(it) }
            // Bounds, labels and supported choices belong to the installed catalogue.
            // Old projects retain values and animation when the catalogue gains a
            // choice or enables animation; they cannot redefine the allowed bounds.
            val parameters = stored.map { old ->
                val canonical = reference.firstOrNull { it.id == old.id } ?: return@map old
                canonical.copy(value = old.value, keyframes = old.keyframes)
            } + reference.filter { p -> stored.none { it.id == p.id } }
            val number = { name: String -> (json.field(name) as? JsonPrimitive)?.doubleOrNull }
            return base.copy(
                id = json.string("id")?.let(::uuidOrNull) ?: throw SerializationException("id"),
                enabled = json.bool("enabled") ?: throw SerializationException("enabled"),
                version = (json.field("version") as? JsonPrimitive)?.intOrNull ?: throw SerializationException("version"),
                start = number("start") ?: throw SerializationException("start"),
                end = number("end") ?: throw SerializationException("end"),
                parameters = parameters,
                analysisFile = json.string("analysisFile"),
                analysisDataFile = json.string("analysisDataFile"),
                capturedEffectID = json.string("capturedEffectID")?.let { uuidOrNull(it) ?: throw SerializationException("capturedEffectID") },
                palette = json.field("palette")?.let { p -> p.array().map { SwiftJSON.decodeFromJsonElement(EffectRGB.serializer(), it) } },
                lockedPalette = json.field("lockedPalette")?.let { SwiftJSON.decodeFromJsonElement(EffectPalette.serializer(), it) },
                releaseTime = number("releaseTime"),
                strobeTimeMap = json.field("strobeTimeMap")?.let { m -> m.array().map { SwiftJSON.decodeFromJsonElement(StrobeTimePoint.serializer(), it) } },
                phaseOffset = number("phaseOffset"),
                motion = json.field("motion")?.let { SwiftJSON.decodeFromJsonElement(EffectAnimation.serializer(), it) },
                algorithm = (json.field("algorithm") as? JsonPrimitive)?.intOrNull,
            )
        }
    }
}

object VideoEffectSettingsSerializer : KSerializer<VideoEffectSettings> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: VideoEffectSettings) =
        (encoder as JsonEncoder).encodeJsonElement(VideoEffectSettings.json(value))
    override fun deserialize(decoder: Decoder): VideoEffectSettings = VideoEffectSettings.of((decoder as JsonDecoder).decodeJsonElement())
}

/**
 * Swift `SupportedVideoEffects`: unknown catalogue IDs are ignored at collection
 * boundaries. Known effects still decode and validate normally: corrupt
 * recognized settings are not hidden.
 */
object SupportedVideoEffects : KSerializer<List<VideoEffectSettings>> {
    private val delegate = ListSerializer(JsonElement.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<VideoEffectSettings>) =
        (encoder as JsonEncoder).encodeJsonElement(JsonArray(value.map { VideoEffectSettings.json(it) }))
    override fun deserialize(decoder: Decoder): List<VideoEffectSettings> = values((decoder as JsonDecoder).decodeJsonElement())

    fun values(json: JsonElement): List<VideoEffectSettings> {
        val items = json as? JsonArray ?: throw SerializationException("videoEffects")
        return items.mapNotNull { item ->
            val name = item.string("kind") ?: throw SerializationException("kind")
            if (VideoEffectID.of(name) == null) null else VideoEffectSettings.of(item)
        }
    }
}
