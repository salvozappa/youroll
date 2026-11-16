// Port of YouRec/VideoTools.swift (partial: CropSettings, MirrorSettings, ImageOverlay, OutputShape, SpeedSettings, ColorLook, ColorSettings,
// PauseSettings and PauseDetector, clip source clock, tool regions, tool requests and removal for the ported tools,
// tool normalization)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// Framing is stored in the oriented source, independent of preview size.
// The output aspect belongs to the project; a crop fills that output frame.
@Serializable
data class CropSettings(
    val id: UUID = newUUID(),
    val x: Double = 0.5,
    val y: Double = 0.5,
    val zoom: Double = 1.0,
    /** Clockwise degrees, applied before output-space mirrors/overlays. */
    val rotation: Double = 0.0,
    /** `EffectAnimation?`, passed through: animated crops (A/B endpoints) are not ported (TODO.md). */
    val motion: JsonElement? = null,
) {
    private val radians: Double get() = rotation * PI / 180

    // The crop window is axis-aligned in output space. Its inverse-rotated
    // footprint must fit inside the source, including when panned to an edge.
    fun rect(source: CGSize, canvas: CGSize): CGRect {
        if (!(source.width > 0 && source.height > 0 && canvas.width > 0 && canvas.height > 0)) return CGRect.zero
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val scale = max((canvas.width * c + canvas.height * s) / source.width,
            (canvas.width * s + canvas.height * c) / source.height) * min(8.0, max(1.0, zoom))
        val width = canvas.width / scale
        val height = canvas.height / scale
        val halfX = (width * c + height * s) / 2
        val halfY = (width * s + height * c) / 2
        val cx = min(source.width - halfX, max(halfX, x * source.width))
        val cy = min(source.height - halfY, max(halfY, y * source.height))
        return CGRect(cx - width / 2, cy - height / 2, width, height)
    }

    fun footprint(source: CGSize, canvas: CGSize): CGRect {
        val area = rect(source, canvas)
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val width = area.width * c + area.height * s
        val height = area.width * s + area.height * c
        return CGRect(area.midX - width / 2, area.midY - height / 2, width, height)
    }

    /** Oriented source pixels → canvas pixels (top-left origin). */
    fun transform(source: CGSize, canvas: CGSize): CGAffineTransform {
        val area = rect(source, canvas)
        if (!(area.width > 0)) return CGAffineTransform.identity
        val scale = canvas.width / area.width
        return CGAffineTransform.translation(-area.midX, -area.midY)
            .concatenating(CGAffineTransform.rotation(radians))
            .concatenating(CGAffineTransform.scale(scale, scale))
            .concatenating(CGAffineTransform.translation(canvas.width / 2, canvas.height / 2))
    }

    fun clamped(source: CGSize, canvas: CGSize): CropSettings {
        val result = copy(zoom = min(8.0, max(1.0, zoom)), rotation = normalizedRotation(rotation))
        val rect = result.rect(source, canvas)
        return result.copy(x = rect.midX / max(1.0, source.width), y = rect.midY / max(1.0, source.height))
    }

    fun isFullFrame(source: CGSize, canvas: CGSize): Boolean {
        if (!(abs(normalizedRotation(rotation)) < 0.00001 && zoom == 1.0 && source.width > 0 && source.height > 0)) return false
        val area = rect(source, canvas)
        // Encoder-safe even dimensions can slightly change an imported image's
        // aspect. That rounding alone must not create a crop or split a range.
        return abs(area.width - source.width) < max(2.0, source.width * 0.0025) &&
            abs(area.height - source.height) < max(2.0, source.height * 0.0025)
    }

    companion object {
        fun normalizedRotation(angle: Double): Double {
            if (!angle.isFinite()) return 0.0
            val value = (angle + 180) % 360 // Swift truncatingRemainder: the sign follows the dividend.
            val normalized = (if (value < 0) value + 360 else value) - 180
            return if (normalized == -180.0 && angle > 0) 180.0 else normalized
        }
    }
}

@Serializable
data class MirrorSettings(
    val id: UUID = newUUID(),
    val horizontal: Boolean = false,
    val vertical: Boolean = false,
) {
    /** Canvas pixels → canvas pixels: the flips, applied after the crop or fit framing. */
    fun transform(canvas: CGSize): CGAffineTransform = CGAffineTransform(if (horizontal) -1.0 else 1.0, 0.0, 0.0, if (vertical) -1.0 else 1.0,
        if (horizontal) canvas.width else 0.0, if (vertical) canvas.height else 0.0)
}

@Serializable
data class ImageOverlay(
    val id: UUID = newUUID(),
    val filename: String,
    /** Source seconds of the clip the image is attached to. */
    val start: Double = 0.0,
    val end: Double = 3.0,
    val x: Double = 0.5,
    val y: Double = 0.5,
    /** Fraction of the shorter canvas edge; the original aspect is retained. */
    val width: Double = 0.4,
    val opacity: Double = 1.0,
    /** `EffectAnimation?`, passed through: animated images (A/B endpoints) are not ported (TODO.md). */
    val motion: JsonElement? = null,
) {
    /** Swift `rect(image:canvas:)`, in Core Image's y-up canvas pixels (origin at the bottom left). */
    fun rect(image: CGSize, canvas: CGSize): CGRect {
        val w = min(canvas.width, canvas.height) * width
        val h = w * image.height / max(1.0, image.width)
        return CGRect(canvas.width * x - w / 2, canvas.height * (1 - y) - h / 2, w, h)
    }
}

/** Swift `enum OutputShape`: the project's video shape presets in Crop. */
enum class OutputShape(val title: String, val aspect: Double?) {
    original("Original", null),
    landscape("16:9", 16.0 / 9),
    portrait("9:16", 9.0 / 16),
    square("1:1", 1.0),
    feed("4:5", 4.0 / 5);

    val id: String get() = name

    companion object {
        fun canvas(source: CGSize, aspect: Double?): CGSize {
            if (aspect == null) return source
            // Keep the source's short-edge resolution, rounded to encoder-safe pixels.
            val edge = min(source.width, source.height)
            fun even(value: Double) = max(2.0, Math.round(value / 2).toDouble() * 2) // Swift .rounded(): half away from zero
            return CGSize(even(if (aspect >= 1) edge * aspect else edge), even(if (aspect >= 1) edge else edge / aspect))
        }
    }
}

@Serializable
data class SourceInterval(val start: Double, val end: Double) {
    val duration: Double get() = max(0.0, end - start)
}

@Serializable
data class SpeedSettings(
    val id: UUID = newUUID(),
    val rate: Double = 1.0,
)

/** Swift `ColorLook`: the built-in creative looks (VideoToolCompositor.kt `ColorLUT`). */
@Serializable
enum class ColorLook(val title: String) {
    natural("Natural"), warm("Warm"), cool("Cool shadows"), cinema("Cinema"), mono("Mono"), portrait("Portrait"),
    daylight("Daylight"), goldenHour("Golden hour"), mutedFilm("Muted film"), richContrast("Rich contrast"), pastel("Pastel"),
    night("Night"), silver("Silver"), ink("Ink");

    val id: String get() = name
    val rawValue: String get() = name
}

/** Swift `ColorSettings` (Codable keys kept). Rendering: `ColorLUT` (VideoToolCompositor.kt). */
@Serializable
data class ColorSettings(
    val id: UUID = newUUID(),
    val look: ColorLook = ColorLook.natural,
    val amount: Double = 1.0,
    val imported: ImportedLUT? = null,
    val exposure: Double? = null,
    val highlights: Double? = null,
    val localRecovery: Double? = null,
    val warmth: Double? = null,
    val tint: Double? = null,
    /** `EffectAnimation?`, passed through: animated grades (A/B endpoints) are not ported (TODO.md). */
    val motion: JsonElement? = null,
) {
    val title: String get() = imported?.name ?: if (look == ColorLook.natural && hasBalance) "Color balance" else look.title
    val hasBalance: Boolean get() = (exposure ?: 0.0) != 0.0 || (highlights ?: 0.0) != 0.0 || (localRecovery ?: 0.0) != 0.0 ||
        (warmth ?: 0.0) != 0.0 || (tint ?: 0.0) != 0.0
    /** True while a grade animates between two endpoints (a JSON payload this port passes through). */
    val isAnimated: Boolean get() = motion != null && motion !is kotlinx.serialization.json.JsonNull
    // A zero-strength look remains editable; only an empty grade is removed.
    val isIdentity: Boolean get() = !isAnimated && !hasBalance && look == ColorLook.natural && imported == null
    val valid: Boolean
        get() {
            fun inside(value: Double?, low: Double, high: Double) = (value ?: 0.0).let { it.isFinite() && it >= low && it <= high }
            return amount.isFinite() && amount in 0.0..1.0 && inside(exposure, -4.0, 4.0) && inside(highlights, -1.0, 1.0) &&
                inside(localRecovery, 0.0, 1.0) && inside(warmth, -1.0, 1.0) && inside(tint, -1.0, 1.0) && (imported?.valid ?: true)
        }

    /**
     * Swift `lutAssets` (EffectAnimation.swift): every LUT payload the grade
     * references, including the endpoints of a passed-through animation.
     */
    val lutAssets: List<ImportedLUT>
        get() = listOfNotNull(imported) + listOf("a", "b").mapNotNull { endpoint ->
            val lut = (motion as? JsonObject)?.get(endpoint)?.let { (it as? JsonObject)?.get("lut") } ?: return@mapNotNull null
            runCatching { SwiftJSON.decodeFromJsonElement(ImportedLUT.serializer(), lut) }.getOrNull()
        }
}

/** `speed?.rate ?? 1`. */
val TimelineClip.speedRate: Double get() = speed?.rate ?: 1.0

/** `pauses?.cuts ?? []`. */
val TimelineClip.pauseCuts: List<SourceInterval> get() = pauses?.cuts.orEmpty()

val TimelineClip.rate: Double get() = min(6.0, max(0.125, speedRate * sequenceRate))
val TimelineClip.sourceDuration: Double get() = max(0.0, end - start)

val TimelineClip.keptIntervals: List<SourceInterval>
    get() {
        val result = mutableListOf<SourceInterval>()
        var cursor = start
        for (cut in pauseCuts.sortedBy { it.start }) {
            val a = max(start, cut.start)
            val b = min(end, cut.end)
            if (b <= a) continue
            if (a > cursor) result += SourceInterval(cursor, a)
            cursor = max(cursor, b)
        }
        if (cursor < end) result += SourceInterval(cursor, end)
        return result
    }

fun TimelineClip.sourceTime(timelineOffset: Double): Double = sourceTime(timelineOffset, effectPlaybackSpans)

fun TimelineClip.sourceTime(timelineOffset: Double, spans: List<EffectPlaybackSpan>): Double {
    frozenFrame?.let { return it.sourceTime }
    var remaining = max(0.0, timelineOffset)
    for ((index, span) in spans.withIndex()) {
        if (remaining < span.duration - 0.000001 || index == spans.size - 1) return min(span.end, span.start + remaining * span.rate)
        remaining -= span.duration
    }
    return end
}

fun TimelineClip.timelineTime(forSource: Double): Double {
    frozenFrame?.let { return if (forSource <= it.sourceTime) 0.0 else it.duration }
    return effectPlaybackSpans.fold(0.0) { total, span -> total + max(0.0, min(span.end, forSource) - span.start) / span.rate }
}

/** Swift `rate.formatted(.number.precision(.fractionLength(0...2)))`: "2", "1.5", "0.25". */
fun rateText(rate: Double): String {
    val text = java.math.BigDecimal(rate).setScale(2, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}

/** Swift `struct ToolRegion`: one effect's span on the timeline. */
data class ToolRegion(
    val tool: VideoTool,
    val effectID: UUID,
    val clipID: UUID,
    val range: ClosedFloatingPointRange<Double>,
    val label: String,
) {
    val id: String get() = "${tool.rawValue}-$effectID-$clipID" +
        if (tool == VideoTool.placement) "-${EditTime.of(range.start).ticks}-${EditTime.of(range.endInclusive).ticks}" else ""
}

/**
 * Swift `EditState.toolRegions`, for the tools whose settings this port models
 * (Subtitles, Mirror, Image, video effects, Crop, Speed, Color, Stabilize, Pauses, Text, Placement, transitions and audio censors). TODO(port):
 * audio enhancements/volume
 * (their settings are passed-through JSON).
 */
val EditState.toolRegions: List<ToolRegion>
    get() {
        val result = mutableListOf<ToolRegion>()
        timeline.subtitles?.let { settings ->
            val cues = timeline.subtitleCues
            val first = cues.firstOrNull()
            val end = cues.maxOfOrNull { it.end }
            if (first != null && end != null) result += ToolRegion(VideoTool.subtitles, settings.id, first.audioID, first.start..end, "Subtitles")
        }
        for (placement in layout.placements) {
            val clip = placement.clip
            val range = placement.range
            val cursor = placement.start
            clip.mirror?.let { mirror ->
                result += ToolRegion(VideoTool.mirror, mirror.id, clip.id, range,
                    if (mirror.horizontal && mirror.vertical) "Flip H + V" else if (mirror.horizontal) "Flip horizontal" else "Flip vertical")
            }
            for (image in clip.images.orEmpty()) {
                if (!(image.start < clip.end && image.end > clip.start)) continue
                val a = cursor + clip.timelineTime(max(clip.start, image.start))
                val b = cursor + clip.timelineTime(min(clip.end, image.end))
                if (b > a) result += ToolRegion(VideoTool.image, image.id, clip.id, a..b, "Image")
            }
            for (effect in clip.videoEffects.orEmpty()) {
                if (!(effect.start < clip.end && effect.end > clip.start)) continue
                val a = cursor + clip.timelineTime(max(clip.start, effect.start))
                val b = cursor + clip.timelineTime(min(clip.end, effect.end))
                if (b > a) result += ToolRegion(VideoTool.videoEffects, effect.id, clip.id, a..b, effect.kind.descriptor.displayName)
            }
            clip.crop?.let { crop ->
                val label = if (abs(crop.rotation) > 0.00001) "Crop · ${rateText(Math.round(crop.rotation * 10) / 10.0)}°"
                    else if (crop.zoom == 1.0) "Fill" else "%.1f×".format(java.util.Locale.ROOT, crop.zoom)
                result += ToolRegion(VideoTool.crop, crop.id, clip.id, range, label)
            }
            clip.speed?.let { speed -> result += ToolRegion(VideoTool.speed, speed.id, clip.id, range, rateText(speed.rate) + "×") }
            clip.color?.let { color -> result += ToolRegion(VideoTool.color, color.id, clip.id, range, color.title) }
            clip.stabilization?.let { stabilization ->
                if (stabilization.level != StabilizationLevel.off) result += ToolRegion(VideoTool.stabilize, stabilization.id ?: clip.id, clip.id, range, "Stabilize")
            }
            clip.pauses?.let { pauses -> result += ToolRegion(VideoTool.pauses, pauses.id, clip.id, range, "Pauses") }
            for (text in clip.texts.orEmpty()) {
                if (!(text.start < clip.end && text.end > clip.start)) continue
                val a = placement.start + clip.timelineTime(max(clip.start, text.start))
                val b = placement.start + clip.timelineTime(min(clip.end, text.end))
                if (b > a) result += ToolRegion(VideoTool.text, text.id, clip.id, a..b, text.text)
            }
        }
        for (plan in timeline.compile()) {
            if (plan.video.size <= 1) continue
            // A plain transition is edited through its own control. Do not
            // present its render overlap as an extra, unconfigured Placement.
            if (plan.placementID == null && plan.video.size == 2 && timeline.transitions.any { relation ->
                    relation.id in plan.transitionIDs && setOfNotNull(relation.outgoing, relation.incoming) == plan.video.toSet()
                }) continue
            val id = plan.placementID ?: plan.video[0]
            result += ToolRegion(VideoTool.placement, id, plan.video[0], plan.interval.seconds, if (plan.placementID == null) "Placement · default" else "Placement")
        }
        for (join in layout.joins) {
            val transition = join.transition ?: continue
            result += ToolRegion(VideoTool.transition, transition.id, join.outgoingID ?: join.incomingID ?: clips[0].id, join.range, transition.style.title)
        }
        for (audio in timeline.audio) {
            for (region in audio.censors) {
                result += ToolRegion(VideoTool.censorAudio, region.id, audio.id,
                    (audio.start + region.interval.start).seconds..(audio.start + region.interval.end).seconds, "Censor audio")
            }
            for (region in audio.enhancements) {
                result += ToolRegion(VideoTool.enhanceAudio, region.id, audio.id,
                    (audio.start.seconds + region.interval.start.seconds)..(audio.start.seconds + region.interval.end.seconds), "Enhance audio")
            }
        }
        return result
    }

/**
 * Swift `EditState.request(for:selection:)`. Effect identity preserves
 * animation across cuts. It is not edit scope: a badge targets its segment
 * unless the user explicitly selects more.
 */
fun EditState.request(region: ToolRegion, selection: TimelineSelection = TimelineSelection()): ToolRequest {
    if (region.tool == VideoTool.subtitles) return ToolRequest(VideoTool.subtitles, 0.0..duration, "Project subtitles", effectID = region.effectID)
    val audio = region.tool in setOf(VideoTool.enhanceAudio, VideoTool.volume, VideoTool.censorAudio)
    val matching = toolRegions.filter { it.tool == region.tool && it.effectID == region.effectID }
    val selected = if (audio) selection.audio else selection.video
    val scoped = matching.filter { it.clipID in selected &&
        (selection.range == null || selection.range.intersection(EditInterval.of(it.range.start, it.range.endInclusive)) != null) }
    // One censor action can silence several simultaneous tracks. Its single
    // badge reopens that event together, but never reaches across a split
    // into the adjacent, non-overlapping section.
    val event = mutableListOf(region)
    if (region.tool == VideoTool.censorAudio) {
        // A continuous music bed can bridge two split voice items. Include
        // the whole connected event rather than leaving a hidden old beep
        // on the far side of that split.
        while (true) {
            val additions = matching.filter { candidate ->
                event.none { it.clipID == candidate.clipID && it.range == candidate.range } &&
                    event.any { EditInterval.of(it.range.start, it.range.endInclusive).intersection(EditInterval.of(candidate.range.start, candidate.range.endInclusive)) != null }
            }
            if (additions.isEmpty()) break
            event += additions
        }
    }
    val regions = scoped.ifEmpty { event }
    val ids = regions.map { it.clipID }.toSet()
    val bounds = EditInterval.of(regions.minOfOrNull { it.range.start } ?: region.range.start, regions.maxOfOrNull { it.range.endInclusive } ?: region.range.endInclusive)
    val range = if (scoped.isEmpty()) bounds else selection.range?.intersection(bounds) ?: bounds
    val title = if (ids.size == 1) (if (audio) "This audio section" else "This clip") else "Selected · ${ids.size} ${if (audio) "audio sections" else "clips"}"
    val targets = TimelineSelection(video = if (audio) emptySet() else ids, audio = if (audio) ids else emptySet(),
        range = if (audio && region.tool in setOf(VideoTool.enhanceAudio, VideoTool.censorAudio)) range else if (scoped.isEmpty()) null else selection.range)
    return ToolRequest(region.tool, range.seconds, title, targets = targets, effectID = region.effectID, clipIDs = if (audio) null else ids)
}

/**
 * Swift `EditState.removingTool`: badge removal stays on its segment. Only an
 * explicit marked range may split that segment further; unrelated effects and
 * source media stay intact. Ported for the modelled clip tools (Crop, Speed, Color, Stabilize, Text, Image, Mirror, Pauses, video effects), Placement and audio censors.
 */
fun EditState.removingTool(request: ToolRequest): EditState {
    val id = request.effectID ?: return this
    if (request.tool == VideoTool.subtitles) {
        val result = copy()
        result.timeline.removeSubtitles()
        return result
    }
    if (request.tool == VideoTool.placement) {
        val result = copy()
        result.timeline.placements = result.timeline.placements.filter { it.id != id }
        return result
    }
    if (request.tool == VideoTool.transition) {
        val join = layout.joins.firstOrNull { it.transition?.id == id } ?: return this
        return restoringStraightCut(join.index)
    }
    if (request.tool == VideoTool.censorAudio) {
        val result = copy()
        val targets = request.targets ?: TimelineSelection(audio = timeline.audio.map { it.id }.toSet(), range = EditInterval.of(request.range.start, request.range.endInclusive))
        result.timeline.censor(null, targets, removingOnly = id)
        return result
    }
    if (request.tool == VideoTool.enhanceAudio) {
        val result = copy()
        val targets = request.targets ?: TimelineSelection(audio = timeline.audio.map { it.id }.toSet(), range = EditInterval.of(request.range.start, request.range.endInclusive))
        result.timeline.audio = result.timeline.audio.map { item ->
            if (item.id !in targets.audio) return@map item
            val scope = targets.range ?: item.interval
            item.copy(enhancements = item.enhancements.flatMap { region ->
                val part = EditInterval(item.start + region.interval.start, item.start + region.interval.end).intersection(scope)
                if (region.id != id || part == null) return@flatMap listOf(region)
                val kept = mutableListOf<AudioEnhancementRegion>()
                if (item.start + region.interval.start < part.start) kept += region.copy(interval = region.interval.copy(end = part.start - item.start))
                if (item.start + region.interval.end > part.end) kept += region.copy(id = if (kept.isEmpty()) region.id else newUUID(), interval = region.interval.copy(start = part.end - item.start))
                kept
            })
        }
        return result
    }
    val (scopedState, scoped) = if (request.targets?.range != null) request.videoScope(this) else copy() to (request.clipIDs ?: clips.map { it.id }.toSet())
    val result = scopedState.copy()
    if (request.tool == VideoTool.videoEffects) {
        result.clips = result.clips.filterNot { clip ->
            clip.id in scoped && clip.frozenFrame != null && clip.videoEffects.orEmpty().any { it.id == id && it.kind == VideoEffectID.freezeFrame }
        }
    }
    result.clips = result.clips.map { clip ->
        if (clip.id !in scoped) return@map clip
        when (request.tool) {
            VideoTool.speed -> if (clip.speed?.id == id) clip.copy(speed = null) else clip
            VideoTool.crop -> if (clip.crop?.id == id) clip.copy(crop = null) else clip
            VideoTool.text -> clip.copy(texts = clip.texts?.filter { it.id != id }?.ifEmpty { null })
            VideoTool.image -> clip.copy(images = clip.images?.filter { it.id != id }?.ifEmpty { null })
            VideoTool.mirror -> if (clip.mirror?.id == id) clip.copy(mirror = null) else clip
            VideoTool.color -> if (clip.color?.id == id) clip.copy(color = null) else clip
            VideoTool.stabilize -> if ((clip.stabilization?.id ?: clip.id) == id) clip.copy(stabilization = null) else clip
            VideoTool.pauses -> if (clip.pauses?.id == id) clip.copy(pauses = null) else clip
            VideoTool.videoEffects -> clip.copy(videoEffects = clip.videoEffects?.filter { it.id != id }?.ifEmpty { null })
            else -> clip // TODO(port): the other tools' settings are passed-through JSON.
        }
    }
    return result.normalizedTools()
}

// Split only at scope boundaries, retaining source effects and identities.
/** Swift `TimelineEditing.scoped(_:range:)`: refuses a range edge inside a transition. */
fun TimelineEditing.scoped(state: EditState, range: ClosedFloatingPointRange<Double>): Pair<EditState, Set<UUID>> {
    for (time in listOf(range.start, range.endInclusive)) {
        if (state.layout.boundary(time) == null && state.layout.transition(time) != null) return state to emptySet()
    }
    val scoped = state.timeline.scoped(state.timeline.entireSelection(EditInterval.of(range.start, range.endInclusive)))
    return EditState(scoped.timeline, state.outputSize) to scoped.selection.video
}

/** Swift `TimelineEditing.scope(_:selection:clipID:)`. */
fun TimelineEditing.scope(state: EditState, selection: ClosedFloatingPointRange<Double>?, clipID: UUID?): ClosedFloatingPointRange<Double> {
    if (selection != null) return max(0.0, selection.start)..min(state.duration, selection.endInclusive)
    val clip = state.clips.firstOrNull { it.id == clipID }
    if (clip != null) return state.layout.placements.first { it.clip.id == clip.id }.range
    return 0.0..state.duration
}

/** Keep each tool's stored analysis within its clip. Only Pauses has such data. */
fun EditState.normalizedTools(): EditState {
    val result = copy()
    val clips = result.clips
    val normalized = clips.map { clip ->
        val pauses = clip.pauses ?: return@map clip
        val analyzed = pauses.analyzed?.let { analyzed ->
            val a = max(clip.start, analyzed.start)
            val b = min(clip.end, analyzed.end)
            if (b > a) SourceInterval(a, b) else null
        }
        val value = pauses.copy(
            cuts = pauses.cuts.filter { it.start < clip.end && it.end > clip.start },
            candidates = pauses.candidates?.filter { it.raw.start < clip.end && it.raw.end > clip.start },
            analyzed = analyzed)
        if (value == pauses) clip else clip.copy(pauses = value)
    }
    if (normalized != clips) result.clips = normalized
    result.timeline.pruneRelations()
    return result
}

// ---- Text overlays (Swift TextStyle … TextOverlay, TitleMotionRanges, ToolRegion) ----

@Serializable
enum class TextStyle { clean, bold, box, highlight }

@Serializable
enum class TextColor {
    white, black, yellow, coral, mint, blue;

    val rgb: Triple<Double, Double, Double>
        get() = when (this) {
            white -> Triple(1.0, 1.0, 1.0)
            black -> Triple(0.03, 0.03, 0.04)
            yellow -> Triple(1.0, 0.85, 0.16)
            coral -> Triple(1.0, 0.3, 0.36)
            mint -> Triple(0.35, 0.94, 0.72)
            blue -> Triple(0.35, 0.66, 1.0)
        }
}

object EditorDurationPresets {
    val wholeSeconds: List<Double> = (1..10).map { it.toDouble() } + listOf(15.0, 30.0, 60.0)
    val transitionSeconds: List<Double> = listOf(0.5) + wholeSeconds

    fun fits(seconds: Double, within: Double): Boolean = EditTime.of(seconds) <= EditTime.of(within)
}

/** Overlay trimming uses the editor clock. A source boundary can be between frames. */
class OverlayTiming(range: ClosedFloatingPointRange<Double>, val bounds: ClosedFloatingPointRange<Double>) {
    enum class Edge { start, end }

    var range: ClosedFloatingPointRange<Double>
        private set
    val duration: Double get() = range.endInclusive - range.start
    val available: Double get() = bounds.endInclusive - range.start

    init {
        val start = min(bounds.endInclusive, max(bounds.start, range.start))
        this.range = start..min(bounds.endInclusive, max(start, range.endInclusive))
    }

    fun copy(): OverlayTiming = OverlayTiming(range, bounds)

    fun limits(edge: Edge): ClosedFloatingPointRange<Double> = when (edge) {
        Edge.start -> bounds.start..max(bounds.start, range.endInclusive - TimelineEditing.frame)
        Edge.end -> min(bounds.endInclusive, range.start + TimelineEditing.frame)..bounds.endInclusive
    }

    fun time(fraction: Double, edge: Edge): Double {
        val limits = limits(edge)
        if (fraction <= 0) return limits.start
        if (fraction >= 1) return limits.endInclusive
        val offset = (limits.endInclusive - limits.start) * fraction
        val frames = swiftRound(offset / TimelineEditing.frame)
        return min(limits.endInclusive, limits.start + frames * TimelineEditing.frame)
    }

    fun set(edge: Edge, value: Double) {
        if (!value.isFinite()) return
        val limits = limits(edge)
        val time = min(limits.endInclusive, max(limits.start, value))
        range = if (edge == Edge.start) time..range.endInclusive else range.start..time
    }

    override fun equals(other: Any?) = other is OverlayTiming && other.range == range && other.bounds == bounds
    override fun hashCode() = range.hashCode() * 31 + bounds.hashCode()
}

@Serializable
data class TextOverlay(
    val id: UUID = newUUID(),
    val text: String = "Your text",
    /** Source seconds of the clip the title is attached to. */
    val start: Double = 0.0,
    val end: Double = 3.0,
    val x: Double = 0.5,
    val y: Double = 0.5,
    /** Font size relative to the shorter canvas dimension; consistent on rotation. */
    val size: Double = 0.065,
    val style: TextStyle = TextStyle.bold,
    val color: TextColor = TextColor.white,
    val appearance: TitleDesign? = null,
    val motion: TitleMotion? = null,
) {
    val design: TitleDesign get() = appearance ?: TitleDesign.legacy(style, color)
}

@Serializable
data class DetectedPause(val raw: SourceInterval, val enabled: Boolean = true)

@Serializable
enum class PauseLevelMode { automatic, manual }

/**
 * Swift `PauseSettings`. A missing `levelMode` decodes as nil (a saved
 * peak-based detection), while a new value defaults to `.automatic`; see
 * [PauseSettingsSerializer].
 */
@Serializable(with = PauseSettingsSerializer::class)
data class PauseSettings(
    val id: UUID = newUUID(),
    val thresholdDB: Double = -38.0,
    val minimum: Double = 0.6,
    val before: Double = 0.12,
    val after: Double = 0.18,
    val cuts: List<SourceInterval> = emptyList(),
    val candidates: List<DetectedPause>? = null,
    val analyzed: SourceInterval? = null,
    /** Absent in saved peak-based detections. */
    val levelMode: PauseLevelMode? = PauseLevelMode.automatic,
)

/** The Codable shape of [PauseSettings]: Swift's synthesized decoder reads an absent optional as nil, not as its default. */
@Serializable
private data class PauseSettingsCoded(
    val id: UUID,
    val thresholdDB: Double,
    val minimum: Double,
    val before: Double,
    val after: Double,
    val cuts: List<SourceInterval>,
    val candidates: List<DetectedPause>? = null,
    val analyzed: SourceInterval? = null,
    val levelMode: PauseLevelMode? = null,
)

object PauseSettingsSerializer : kotlinx.serialization.KSerializer<PauseSettings> {
    private val coded = PauseSettingsCoded.serializer()
    override val descriptor = coded.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: PauseSettings) = encoder.encodeSerializableValue(coded,
        PauseSettingsCoded(value.id, value.thresholdDB, value.minimum, value.before, value.after, value.cuts, value.candidates, value.analyzed, value.levelMode))
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): PauseSettings = decoder.decodeSerializableValue(coded).let {
        PauseSettings(it.id, it.thresholdDB, it.minimum, it.before, it.after, it.cuts, it.candidates, it.analyzed, it.levelMode)
    }
}

/**
 * Swift `enum PauseDetector`: silent gaps in a source's 10 ms waveform bins,
 * on the clip's playback clock. Speech probabilities (`wave.speech`, RNNoise
 * on iOS) are honoured when a waveform carries them; Android does not compute
 * them yet (TODO.md), so automatic detection uses the energy test alone.
 */
object PauseDetector {
    private fun db(value: Float): Double = 20 * log10(max(1e-8, value.toDouble()))

    fun levelDB(wave: SourceWaveform, settings: PauseSettings): Double {
        val levels = wave.rms
        if (settings.levelMode != PauseLevelMode.automatic || levels == null || levels.isEmpty()) return settings.thresholdDB
        val sorted = levels.sorted()
        fun db(percentile: Int) = db(sorted[(sorted.size - 1) * percentile / 100])
        val floorDB = db(10)
        var speechDB = db(90)
        // A take can be mostly steady room tone with only a few spoken words.
        // A sustained louder passage qualifies as a reference; a lone click cannot.
        if (speechDB - floorDB < 10 && db(50) - floorDB < 5 && wave.duration > 0) {
            val block = max(1, ceil(0.2 * levels.size / wave.duration).toInt())
            var loudest = 0f
            if (block <= levels.size) {
                var i = 0
                while (i <= levels.size - block) {
                    var quietest = levels[i]
                    for (j in i until i + block) quietest = min(quietest, levels[j])
                    loudest = max(loudest, quietest)
                    i += block
                }
            }
            speechDB = max(speechDB, db(loudest))
        }
        // Leave headroom above room tone and stay well below the stronger voice.
        return swiftRound(max(-56.0, minOf(-27.0, floorDB + 8, speechDB - 18)))
    }

    fun raw(wave: SourceWaveform, clip: TimelineClip, settings: PauseSettings, levelDB: Double? = null): List<SourceInterval> =
        detect(wave, clip, settings.copy(before = 0.0, after = 0.0), levelDB)

    fun padded(raw: SourceInterval, rate: Double, settings: PauseSettings): SourceInterval {
        val a = min(raw.end, raw.start + settings.before * rate)
        return SourceInterval(a, max(a, raw.end - settings.after * rate))
    }

    fun padded(raw: SourceInterval, clip: TimelineClip, settings: PauseSettings): SourceInterval {
        val full = clip.copy(pauses = null)
        val a = min(raw.end, full.sourceTime(full.timelineTime(raw.start) + settings.before))
        val b = max(a, full.sourceTime(max(0.0, full.timelineTime(raw.end) - settings.after)))
        return SourceInterval(a, b)
    }

    fun effectiveCuts(candidates: List<DetectedPause>, clip: TimelineClip, settings: PauseSettings): List<SourceInterval> {
        val frame = TimelineEditing.frame * clip.rate
        val proposed = candidates.filter { it.enabled }.map { candidate ->
            val padded = padded(candidate.raw, clip, settings)
            SourceInterval(max(clip.start, padded.start), min(clip.end, padded.end))
        }.filter { it.duration >= frame - 0.000001 }.sortedBy { it.start }
        val cuts = mutableListOf<SourceInterval>()
        for (cut in proposed) {
            val last = cuts.lastOrNull()
            if (last != null && cut.start <= last.end) cuts[cuts.size - 1] = last.copy(end = max(last.end, cut.end))
            else cuts += cut
        }
        // Preserve one addressable frame even with zero padding on a silent
        // clip. The UI summary and the saved edit use these same intervals.
        val excess = cuts.sumOf { it.duration } - max(0.0, clip.sourceDuration - frame)
        if (excess > 0 && cuts.isNotEmpty()) cuts[cuts.size - 1] = cuts.last().let { it.copy(end = it.end - excess) }
        return cuts.filter { it.duration >= frame - 0.000001 }
    }

    fun detect(wave: SourceWaveform, clip: TimelineClip, settings: PauseSettings, levelDB: Double? = null): List<SourceInterval> {
        val levels = (if (settings.levelMode == null) wave.peaks else wave.rms) ?: FloatArray(0)
        if (levels.isEmpty() || !(wave.duration > 0)) return emptyList()
        val step = wave.duration / levels.size
        val threshold = 10.0.pow((levelDB ?: levelDB(wave, settings)) / 20).toFloat()
        val result = mutableListOf<SourceInterval>()
        var beginning: Int? = null
        val full = clip.copy(pauses = null)
        fun append(lower: Int, upper: Int) {
            val start = lower * step
            val time = min(clip.end, upper * step)
            if (!(full.timelineTime(time) - full.timelineTime(start) >= settings.minimum)) return
            val interval = padded(SourceInterval(start, time), full, settings)
            if (full.timelineTime(interval.end) - full.timelineTime(interval.start) >= TimelineEditing.frame) result += interval
        }
        val first = max(0, ceil(clip.start / step).toInt())
        val last = min(levels.size, floor(clip.end / step).toInt())
        if (first < last) {
            // Establish that this is dialogue before relaxing the energy gate. For music,
            // steady room tone or an unvoiced selection, keep the ordinary silence test.
            val voice = wave.speech?.let { runCatching { fromBase64(it) }.getOrNull() }
            val speech = if (settings.levelMode == PauseLevelMode.automatic && voice?.size == levels.size) voice else null
            var hasDialogue = false
            if (speech != null) {
                var voiced = 0
                val reference = max(1, ceil(0.16 / step).toInt())
                for (i in first until last) {
                    voiced = if ((speech[i].toInt() and 0xFF) >= 179) voiced + 1 else 0
                    if (voiced >= reference) { hasDialogue = true; break }
                }
            }
            // Let steady noise exceed the normal threshold by up to 12 dB, bounded
            // at -15 dBFS. A likely voice protects even a quiet syllable.
            val noiseLimit = min(0.177828f, threshold * 3.981072f)
            fun close(end: Int) {
                val start = beginning ?: return
                beginning = null
                val count = end - start
                if (!(hasDialogue && count >= 5 && (start until end).any { levels[it] > threshold })) { append(start, end); return }
                // A faint utterance can slip past the voice classifier. Protect sustained
                // onsets that rise 7 dB above this gap's own background. Three of five
                // 10 ms bins distinguish a short syllable from an isolated noise spike.
                val sorted = levels.copyOfRange(start, end).sorted()
                val onset = max(threshold, sorted[sorted.size / 2] * 2.23872f)
                var elevated = 0
                var cursor = start
                for (i in start until end + 2) {
                    if (i < end && levels[i] > onset) elevated += 1
                    if (i - 5 >= start && levels[i - 5] > onset) elevated -= 1
                    if (i >= start + 2 && elevated >= 3) {
                        val kept = i - 2
                        if (cursor < kept) append(cursor, kept)
                        cursor = kept + 1
                    }
                }
                if (cursor < end) append(cursor, end)
            }
            for (index in first until last) {
                var quiet = levels[index] <= threshold
                if (hasDialogue && speech != null) {
                    val probability = speech[index].toInt() and 0xFF
                    quiet = (quiet && probability < 102) || (levels[index] <= noiseLimit && probability < 31)
                }
                if (quiet) { if (beginning == null) beginning = index } else close(index)
            }
            close(last)
        }
        return result
    }
}

/**
 * Like the visual fragments, a clock can span a straight cut or overlap. A real
 * gap or a separately changed motion starts a new run of that animation.
 */
class TitleMotionRanges() {
    private data class Key(val id: UUID, val motion: TitleMotion)
    private val intervals = HashMap<Key, List<ClosedFloatingPointRange<Double>>>()
    val isEmpty: Boolean get() = intervals.isEmpty()

    constructor(edit: EditState) : this() {
        if (edit.clips.none { clip -> clip.texts.orEmpty().any { it.motion?.active == true } }) return
        val parts = HashMap<Key, MutableList<ClosedFloatingPointRange<Double>>>()
        for (placement in edit.layout.placements) {
            val clip = placement.clip
            for (title in clip.texts.orEmpty()) {
                if (!(title.end > clip.start && title.start < clip.end)) continue
                val motion = title.motion ?: continue
                if (!motion.active) continue
                val a = placement.start + clip.timelineTime(max(clip.start, title.start))
                val b = placement.start + clip.timelineTime(min(clip.end, title.end))
                if (b > a) parts.getOrPut(Key(title.id, motion)) { mutableListOf() } += a..b
            }
        }
        for ((key, list) in parts) {
            val runs = mutableListOf<ClosedFloatingPointRange<Double>>()
            for (part in list.sortedBy { it.start }) {
                val previous = runs.lastOrNull()
                if (previous != null && part.start <= previous.endInclusive + 1.0 / EditTime.scale) {
                    runs[runs.size - 1] = previous.start..max(previous.endInclusive, part.endInclusive)
                } else runs += part
            }
            intervals[key] = runs
        }
    }

    fun range(text: TextOverlay, time: Double): ClosedFloatingPointRange<Double>? {
        val motion = text.motion ?: return null
        if (!motion.active) return null
        return intervals[Key(text.id, motion)]?.firstOrNull { time >= it.start - 0.00001 && time <= it.endInclusive + 0.00001 }
    }
}

/** Swift `enum VideoTool`: every editor tool, with its title, SF Symbol name and help text. */
enum class VideoTool(val title: String, val symbol: String, val detail: String) {
    subtitles("Subtitles", "captions.bubble", "Transcribe speech into editable captions, offline"),
    stabilize("Stabilize", "level", "Smooth camera movement"),
    text("Text", "textformat", "Add words to the picture"),
    color("Color LUTs", "camera.filters", "Choose a look and strength"),
    pauses("Pauses", "waveform.path", "Review and shorten silence"),
    speed("Speed", "speedometer", "Change pace, keep voice pitch"),
    crop("Crop", "crop", "Rotate and choose what stays in frame"),
    mirror("Mirror", "arrow.left.and.right.righttriangle.left.righttriangle.right", "Flip horizontally or vertically"),
    image("Image", "photo", "Place a picture over the video"),
    addVideo("Video or photo", "video.badge.plus", "Insert photos and videos or record here"),
    addAudio("Add audio", "waveform.badge.plus", "Record or import sound at the playhead"),
    censorAudio("Censor audio", "waveform.slash", "Replace sound in a marked range with a beep"),
    transition("Transition", "square.on.square", "Join two clips, or fade to and from black"),
    videoEffects("Video effects", "sparkles.tv", "Film techniques, backgrounds and animated looks"),
    enhanceAudio("Enhance audio", "waveform.badge.mic", "Apply voice filters to selected sound"),
    volume("Volume", "speaker.wave.2", "Adjust the selected audio level"),
    placement("Placement", "square.3.layers.3d", "Arrange overlapping videos");

    val rawValue: String get() = name
}
