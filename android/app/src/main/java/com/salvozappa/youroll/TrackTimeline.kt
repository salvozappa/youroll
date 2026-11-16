// Port of YouRec/TrackTimeline.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Value semantics: every item type is an immutable data class. TrackTimeline is
// mutable (Swift `mutating func`s mutate in place); copy it with `copy()`
// wherever Swift would copy the struct by assignment.
//
// Out of scope for the first port (TODO.md): animated placements.
// Their JSON is kept and passed through; logic keeps the "none present" path.
// Subtitle words and settings are ported in Subtitles.kt.
// Censor regions (`AudioCensorRegion`) live in AudioCensor.kt. Transitions (`TimelineTransition`, generated regions)
// and placements (`VideoPlacement`) are ported.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/// The edit clock is exact for 48 kHz sound and 24/25/30/50/60 fps,
/// including 24000/1001, 30000/1001 and 60000/1001. Source PTS remains separate.
@Serializable
data class EditTime(val ticks: Long) : Comparable<EditTime> {
    val seconds: Double get() = ticks.toDouble() / scale.toDouble()
    override fun compareTo(other: EditTime) = ticks.compareTo(other.ticks)
    operator fun plus(other: EditTime) = EditTime(ticks + other.ticks)
    operator fun minus(other: EditTime) = EditTime(ticks - other.ticks)
    override fun toString() = "EditTime($seconds s)"

    companion object {
        const val scale: Long = 240_000
        val zero = EditTime(0)
        /** Swift `EditTime(_ seconds: Double)`. */
        fun of(seconds: Double): EditTime =
            EditTime(if (seconds.isFinite()) swiftRound(min(604_800.0, max(-604_800.0, seconds)) * scale.toDouble()).toLong() else 0)
    }
}

@Serializable
data class EditInterval(val start: EditTime, val end: EditTime) {
    val duration: EditTime get() = end - start
    val isEmpty: Boolean get() = end <= start
    fun contains(time: EditTime) = start <= time && time < end
    fun intersection(other: EditInterval): EditInterval? {
        val result = EditInterval(maxOf(start, other.start), minOf(end, other.end))
        return if (result.isEmpty) null else result
    }
    val seconds: ClosedFloatingPointRange<Double> get() = start.seconds..max(start.seconds, end.seconds)

    companion object {
        /** Swift `EditInterval(_ range: ClosedRange<Double>)`. */
        fun of(lower: Double, upper: Double) = EditInterval(EditTime.of(lower), EditTime.of(upper))
    }
}

/// Each run maps an exact local interval to original source timestamps. A hold
/// has equal source endpoints and is silent; no duplicate dialogue is inserted.
@Serializable
data class PlaybackRun(
    val duration: EditTime,
    val sourceStart: Double,
    val sourceEnd: Double,
    val silent: Boolean = false,
) {
    val isHold: Boolean get() = sourceStart == sourceEnd
    fun source(offset: EditTime): Double {
        if (duration <= EditTime.zero) return sourceStart
        return sourceStart + (sourceEnd - sourceStart) * min(1.0, max(0.0, offset.seconds / duration.seconds))
    }
}

@Serializable
data class SourcePlaybackMap(val runs: List<PlaybackRun>) {
    val duration: EditTime get() = EditTime(runs.fold(0L) { total, run -> total + run.duration.ticks })

    constructor(clip: TimelineClip) : this(runsFor(clip))

    fun applyingMute(clip: TimelineClip): SourcePlaybackMap {
        val boundaries = clip.effectPlaybackSpans.flatMap { listOf(it.start, it.end) }
        val result = mutableListOf<PlaybackRun>()
        for (run in runs) {
            if (run.isHold) { result += run.copy(silent = true); continue }
            val points = (listOf(run.sourceStart, run.sourceEnd) + boundaries.filter { it > run.sourceStart && it < run.sourceEnd }).distinct().sorted()
            var prior = EditTime.zero
            for ((a, b) in points.zipWithNext()) {
                val end = if (b == run.sourceEnd) run.duration else EditTime.of(run.duration.seconds * (b - run.sourceStart) / (run.sourceEnd - run.sourceStart))
                if (end > prior) result += PlaybackRun(end - prior, a, b, clip.mutesSourceAudio((a + b) / 2))
                prior = end
            }
        }
        return SourcePlaybackMap(result)
    }

    fun source(local: EditTime): Double? {
        var cursor = EditTime.zero
        for (run in runs) {
            if (local < cursor + run.duration) return run.source(maxOf(EditTime.zero, local - cursor))
            cursor += run.duration
        }
        return if (local == duration) runs.lastOrNull()?.sourceEnd else null
    }

    fun local(forSource: Double): EditTime? {
        var cursor = EditTime.zero
        for (run in runs) {
            if (!run.isHold && forSource >= run.sourceStart && forSource < run.sourceEnd) {
                return cursor + EditTime.of(run.duration.seconds * (forSource - run.sourceStart) / (run.sourceEnd - run.sourceStart))
            }
            cursor += run.duration
        }
        return null
    }

    fun sliced(interval: EditInterval): SourcePlaybackMap {
        var cursor = EditTime.zero
        val result = mutableListOf<PlaybackRun>()
        for (run in runs) {
            val end = cursor + run.duration
            EditInterval(cursor, end).intersection(interval)?.let { part ->
                result += PlaybackRun(part.duration, run.source(part.start - cursor), run.source(part.end - cursor), run.silent)
            }
            cursor = end
        }
        return SourcePlaybackMap(result)
    }

    companion object {
        private fun runsFor(clip: TimelineClip): List<PlaybackRun> {
            clip.frozenFrame?.let { hold ->
                return listOf(PlaybackRun(EditTime.of(hold.duration), hold.sourceTime, hold.sourceTime, silent = true))
            }
            // Round cumulative boundaries, not each run's length, to carry the
            // rounding residual across a long sequence of speed changes/cuts.
            var total = 0.0
            var prior = EditTime.zero
            return clip.effectPlaybackSpans.map { span ->
                total += span.duration
                val boundary = EditTime.of(total)
                val duration = boundary - prior
                prior = boundary
                PlaybackRun(duration, span.start, span.end, clip.mutesSourceAudio((span.start + span.end) / 2))
            }.filter { it.duration > EditTime.zero }
        }
    }
}

@Serializable
data class VideoTimelineItem(
    val occurrenceID: UUID,
    val start: EditTime,
    val content: TimelineClip,
    val layer: Int = 0,
    val localOrigin: EditTime = EditTime.zero,
    val mapping: SourcePlaybackMap,
) {
    constructor(occurrenceID: UUID, start: EditTime, content: TimelineClip, layer: Int = 0) :
        this(occurrenceID, start, content, layer, EditTime.zero, SourcePlaybackMap(content))

    val id: UUID get() = content.id
    val end: EditTime get() = start + mapping.duration
    val interval: EditInterval get() = EditInterval(start, end)
}

@Serializable
data class AudioEnhancementRegion(
    val id: UUID = newUUID(),
    /// Local coordinates, independent of the item's placement in the project.
    val interval: EditInterval,
    val effects: List<AudioEffect>,
)

/// A split inside a fade inherits its slope and its level at the cut.
@Serializable
data class AudioEdgeFade(val seconds: Double, val elapsed: Double = 0.0) {
    val valid: Boolean get() = seconds > 0 && seconds <= 604800 && elapsed >= 0 && elapsed < seconds
    fun remaining(length: Double): Double = min(length, seconds - elapsed)
    fun trimming(amount: EditTime): AudioEdgeFade? {
        val part = copy(elapsed = (EditTime.of(elapsed) + amount).seconds)
        return if (part.elapsed < seconds) part else null
    }
    fun fitted(oldLength: EditTime, newLength: EditTime): AudioEdgeFade {
        val visible = remaining(oldLength.seconds)
        if (!(newLength > EditTime.zero && newLength.seconds < visible)) return this
        val scale = newLength.seconds / visible
        return AudioEdgeFade(seconds * scale, elapsed * scale)
    }
    fun gain(distanceFromEdge: Double): Double = min(1.0, max(0.0, (elapsed + distanceFromEdge) / seconds))
}

enum class AudioFadeEdge {
    fadeIn, fadeOut;

    val title: String get() = if (this == fadeIn) "Fade in" else "Fade out"

    fun index(audio: List<AudioTimelineItem>, selected: Set<UUID>): Int? {
        // Select just one item if several share an edge. The lowest timeline
        // audio number wins the tie, and is named alongside the control.
        val candidates = audio.indices.filter { audio[it].id in selected }
        return if (this == fadeIn) candidates.minWithOrNull(compareBy { audio[it].start })
        else candidates.minWithOrNull { a, b -> audio[b].end.compareTo(audio[a].end) }
    }
}

@Serializable
data class AudioTimelineItem(
    val id: UUID = newUUID(),
    val occurrenceID: UUID,
    val sourceID: UUID,
    val start: EditTime,
    val mapping: SourcePlaybackMap,
    val localOrigin: EditTime = EditTime.zero,
    val followsVideo: UUID? = null,
    val volumeDB: Double = 0.0,
    val muted: Boolean = false,
    val fadeIn: AudioEdgeFade? = null,
    val fadeOut: AudioEdgeFade? = null,
    val enhancements: List<AudioEnhancementRegion> = emptyList(),
    val censorRegions: List<AudioCensorRegion>? = null,
    val subtitleWords: List<SubtitleWord>? = null,
) {
    val censors: List<AudioCensorRegion> get() = censorRegions ?: emptyList()
    fun withCensors(value: List<AudioCensorRegion>) = copy(censorRegions = value.ifEmpty { null })
    val end: EditTime get() = start + mapping.duration
    val interval: EditInterval get() = EditInterval(start, end)
    val gain: Double get() = if (muted) 0.0 else 10.0.pow(volumeDB / 20)

    fun fadeSeconds(edge: AudioFadeEdge): Double =
        (if (edge == AudioFadeEdge.fadeIn) fadeIn else fadeOut)?.remaining(mapping.duration.seconds) ?: 0.0

    /** Swift `mutating func setFade`; returns the changed item. */
    fun settingFade(edge: AudioFadeEdge, seconds: Double): AudioTimelineItem {
        val length = minOf(mapping.duration, maxOf(EditTime.zero, EditTime.of(seconds))).seconds
        val fade = if (length > 0) AudioEdgeFade(length) else null
        return if (edge == AudioFadeEdge.fadeIn) copy(fadeIn = fade) else copy(fadeOut = fade)
    }

    fun sliced(part: EditInterval, id: UUID): AudioTimelineItem = copy(
        id = id,
        start = start + part.start,
        localOrigin = localOrigin + part.start,
        mapping = mapping.sliced(part),
        fadeIn = fadeIn?.trimming(part.start),
        fadeOut = fadeOut?.trimming(mapping.duration - part.end),
        enhancements = enhancements.mapNotNull { region ->
            val intersection = region.interval.intersection(part) ?: return@mapNotNull null
            region.copy(interval = EditInterval(intersection.start - part.start, intersection.end - part.start))
        },
    ).withCensors(censors.mapNotNull { it.sliced(part) })
}

@Serializable
data class TimelineLink(val id: UUID = newUUID(), val members: Set<UUID>)

data class TimelineSelection(
    val video: Set<UUID> = emptySet(),
    val audio: Set<UUID> = emptySet(),
    val range: EditInterval? = null,
) {
    val ids: Set<UUID> get() = video + audio
    val isEmpty: Boolean get() = ids.isEmpty()
    val title: String get() = if (video.isEmpty()) "Audio" else if (audio.isEmpty()) "Video" else "Video + audio"
    val countTitle: String
        get() = listOfNotNull(
            if (video.isEmpty()) null else "${video.size} video${if (video.size == 1) "" else "s"}",
            if (audio.isEmpty()) null else "${audio.size} audio part${if (audio.size == 1) "" else "s"}",
        ).joinToString(" + ")
}

enum class TimelineMoveScope {
    selected, left, right, both;

    val includesLeft: Boolean get() = this == left || this == both
    val includesRight: Boolean get() = this == right || this == both
}

// Picture cuts and independent sound cuts at the same time are different joins.
private data class MoveBoundary(val audio: Boolean, val time: EditTime)

data class TimelineMoveChoices(
    val selected: TimelineSelection = TimelineSelection(),
    val left: TimelineSelection = TimelineSelection(),
    val right: TimelineSelection = TimelineSelection(),
    val both: TimelineSelection = TimelineSelection(),
) {
    operator fun get(scope: TimelineMoveScope): TimelineSelection = when (scope) {
        TimelineMoveScope.selected -> selected
        TimelineMoveScope.left -> left
        TimelineMoveScope.right -> right
        TimelineMoveScope.both -> both
    }

    fun available(scope: TimelineMoveScope): Boolean = when (scope) {
        TimelineMoveScope.selected -> true
        TimelineMoveScope.left -> left != selected
        TimelineMoveScope.right -> right != selected
        TimelineMoveScope.both -> both != left && both != right
    }
}

class TimelineLinkError private constructor(val case: Case) : Exception(case.description) {
    enum class Case(val description: String) {
        tooFew("Tap a part to select it, then long-press other video or audio parts to add them. Long-press again to remove a part from the selection."),
        nonContiguous("Non-contiguous parts selected. Each video or audio sequence must meet edge to edge, without gaps or overlaps. Move the parts together with Snap, then try Link again."),
        noIntersection("The selected video and audio do not overlap in time. Move them to a shared interval before linking."),
        confirmationRequired("Video and audio cover different times. Link only their intersection?"),
        transitionBoundary("The shared time starts or ends inside a transition. Adjust or remove that transition before linking the intersection."),
    }

    val errorDescription: String get() = case.description

    companion object {
        val tooFew get() = TimelineLinkError(Case.tooFew)
        val nonContiguous get() = TimelineLinkError(Case.nonContiguous)
        val noIntersection get() = TimelineLinkError(Case.noIntersection)
        val confirmationRequired get() = TimelineLinkError(Case.confirmationRequired)
        val transitionBoundary get() = TimelineLinkError(Case.transitionBoundary)
    }
}

data class TimelineLinkPlan(
    val selection: TimelineSelection,
    val video: EditInterval?,
    val audio: EditInterval?,
) {
    val needsIntersection: Boolean get() = video != null && audio != null && video != audio
    val intersection: EditInterval?
        get() {
            val video = video ?: return null
            val audio = audio ?: return null
            return video.intersection(audio)
        }
}

/// The start of the explicitly picked material stays the alignment point even
/// when Move includes neighbors. Targets use the original edit clock for the
/// entire gesture session, so moving the draft never creates another stop.
class TimelineMoveBoundaries private constructor(val anchor: EditTime, val times: List<EditTime>) {
    constructor() : this(EditTime.zero, emptyList())

    fun previous(time: EditTime): EditTime? = times.lastOrNull { it < time }
    fun next(time: EditTime): EditTime? = times.firstOrNull { it > time }

    companion object {
        operator fun invoke(timeline: TrackTimeline, picked: TimelineSelection, moving: TimelineSelection): TimelineMoveBoundaries {
            val original = timeline.interval(picked) ?: return TimelineMoveBoundaries()
            val group = timeline.interval(moving) ?: return TimelineMoveBoundaries()
            val layout = if (timeline.transitions.isEmpty()) null else TimelineLayout(timeline)
            val members = moving.ids
            val stops = linkedSetOf(EditTime.zero, original.start, timeline.duration)
            for ((id, span) in displayIntervals(timeline, layout)) {
                if (id in members) continue
                stops += span.start; stops += span.end
            }
            for (join in layout?.joins ?: emptyList()) {
                if (join.transition == null) continue
                val partners = listOfNotNull(join.outgoingID, join.incomingID)
                if (partners.any { it !in members }) stops += EditTime.of(join.time)
            }
            val earliest = original.start - group.start
            val latest = original.start + EditTime.of(604800.0) - group.end
            return TimelineMoveBoundaries(original.start, stops.filter { earliest <= it && it <= latest }.sorted())
        }
    }
}

data class TimelineMoveSnap(val target: EditTime, val edge: EditTime)
data class TimelineMoveResult(val delta: EditTime, val snap: TimelineMoveSnap? = null)

/** Swift enum with associated values; Codable form `{"case":{"label":value}}`. */
@Serializable(with = BookmarkAnchorSerializer::class)
sealed class BookmarkAnchor {
    data class source(val occurrence: UUID, val source: UUID, val time: Double) : BookmarkAnchor()
    data class local(val occurrence: UUID, val offset: EditTime) : BookmarkAnchor()
    data class project(val time: EditTime) : BookmarkAnchor()
}

object BookmarkAnchorSerializer : KSerializer<BookmarkAnchor> {
    @Serializable private class Source(val occurrence: UUID, val source: UUID, val time: Double)
    @Serializable private class Local(val occurrence: UUID, val offset: EditTime)
    @Serializable private class Project(@SerialName("_0") val value: EditTime)

    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: BookmarkAnchor) {
        val json = encoder as JsonEncoder
        val element = when (value) {
            is BookmarkAnchor.source -> JsonObject(mapOf("source" to json.json.encodeToJsonElement(Source.serializer(), Source(value.occurrence, value.source, value.time))))
            is BookmarkAnchor.local -> JsonObject(mapOf("local" to json.json.encodeToJsonElement(Local.serializer(), Local(value.occurrence, value.offset))))
            is BookmarkAnchor.project -> JsonObject(mapOf("project" to json.json.encodeToJsonElement(Project.serializer(), Project(value.time))))
        }
        json.encodeJsonElement(element)
    }
    override fun deserialize(decoder: Decoder): BookmarkAnchor {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("BookmarkAnchor")
        if (element.size != 1) throw SerializationException("BookmarkAnchor needs exactly one case")
        val (case, payload) = element.entries.first()
        return when (case) {
            "source" -> json.json.decodeFromJsonElement(Source.serializer(), payload).let { BookmarkAnchor.source(it.occurrence, it.source, it.time) }
            "local" -> json.json.decodeFromJsonElement(Local.serializer(), payload).let { BookmarkAnchor.local(it.occurrence, it.offset) }
            "project" -> BookmarkAnchor.project(json.json.decodeFromJsonElement(Project.serializer(), payload).value)
            else -> throw SerializationException("Unknown BookmarkAnchor case $case")
        }
    }
}

@Serializable
data class TimelineBookmark(val id: UUID = newUUID(), val label: String = "", val anchor: BookmarkAnchor)

data class ResolvedBookmark(val bookmark: TimelineBookmark, val time: EditTime) {
    val id: UUID get() = bookmark.id
}

enum class PlacementCropEdge {
    left, right, top, bottom;
    val horizontal: Boolean get() = this == left || this == right
}

@Serializable
data class PlacementEdgeCrop(
    val left: Double = 0.0,
    val right: Double = 0.0,
    val top: Double = 0.0,
    val bottom: Double = 0.0,
) {
    val isEmpty: Boolean get() = left == 0.0 && right == 0.0 && top == 0.0 && bottom == 0.0
    val valid: Boolean
        get() = listOf(left, right, top, bottom).all { it.isFinite() && it >= 0 } &&
            left + right <= 1 - minimumSpan + 0.0000001 && top + bottom <= 1 - minimumSpan + 0.0000001

    operator fun get(edge: PlacementCropEdge): Double = when (edge) {
        PlacementCropEdge.left -> left
        PlacementCropEdge.right -> right
        PlacementCropEdge.top -> top
        PlacementCropEdge.bottom -> bottom
    }

    /** Swift's subscript setter. */
    fun with(edge: PlacementCropEdge, value: Double): PlacementEdgeCrop = when (edge) {
        PlacementCropEdge.left -> copy(left = value)
        PlacementCropEdge.right -> copy(right = value)
        PlacementCropEdge.top -> copy(top = value)
        PlacementCropEdge.bottom -> copy(bottom = value)
    }

    companion object { const val minimumSpan = 0.02 }
}

/**
 * Swift `VideoPlacement`: one picture's window on the canvas while videos
 * overlap. Swift's `mutating func`s return the changed copy here.
 */
@Serializable
data class VideoPlacement(
    val x: Double = 0.5,
    val y: Double = 0.5,
    val width: Double = 1.0,
    val height: Double = 1.0,
    val framing: TransitionFraming = TransitionFraming(),
    val fill: Boolean = true,
    val order: Int = 0,
    // Independent clipping after framing/placement. It must never participate
    // in Fit/Fill or recenter/rescale the picture when an edge is dragged.
    /** `EffectAnimation?`, passed through: animated placements (A/B endpoints) are not ported (TODO.md). */
    val motion: JsonElement? = null,
    val edgeCrop: PlacementEdgeCrop? = null,
) {
    fun window(canvas: CGSize): CGRect =
        CGRect((x - width / 2) * canvas.width, (y - height / 2) * canvas.height, width * canvas.width, height * canvas.height)

    fun visibleWindow(canvas: CGSize): CGRect {
        val full = pictureWindow(canvas)
        val crop = edgeCrop ?: PlacementEdgeCrop()
        return CGRect(full.minX + crop.left * full.width, full.minY + crop.top * full.height,
            full.width * (1 - crop.left - crop.right), full.height * (1 - crop.top - crop.bottom))
    }

    /// Fit can leave transparent space inside the window. Handles and margins
    /// follow the picture, never that empty band. Scaling remains crop-independent.
    fun pictureWindow(canvas: CGSize): CGRect {
        val full = window(canvas)
        if (fill) return full
        val scale = min(width, height)
        return CGRect(full.midX - canvas.width * scale / 2, full.midY - canvas.height * scale / 2, canvas.width * scale, canvas.height * scale)
    }

    /// Translate the actual source crop by the finger distance in the preview.
    /// Framing stores a source-space center. Resolve its actual clamped center
    /// first, then account for window scale; screen fractions drift at zoom and
    /// can leave a long dead zone when reversing a drag at the image edge.
    fun reframe(by: CGSize, canvas: CGSize): VideoPlacement {
        val window = window(canvas)
        val crop = framing.crop(canvas, if (fill) window.size else canvas)
        val scale = if (fill) max(window.width / crop.width, window.height / crop.height) else min(window.width / crop.width, window.height / crop.height)
        if (!(scale.isFinite() && scale > 0)) return this
        return copy(framing = framing.copy(
            x = min(canvas.width - crop.width / 2, max(crop.width / 2, crop.midX - by.width / scale)) / canvas.width,
            y = min(canvas.height - crop.height / 2, max(crop.height / 2, crop.midY - by.height / scale)) / canvas.height))
    }

    fun crop(edge: PlacementCropEdge, to: Double, canvas: CGSize = CGSize(1920.0, 1080.0), minimumPoints: Double = 0.0): VideoPlacement {
        val position = to
        if (!position.isFinite()) return this
        val picture = pictureWindow(canvas)
        val full = CGRect(picture.minX / canvas.width, picture.minY / canvas.height, picture.width / canvas.width, picture.height / canvas.height)
        val crop = edgeCrop ?: PlacementEdgeCrop()
        val maximum = 1 - min(1.0, max(PlacementEdgeCrop.minimumSpan, minimumPoints / (if (edge.horizontal) picture.width else picture.height)))
        val result = when (edge) {
            PlacementCropEdge.left -> crop.copy(left = min(max(crop.left, maximum - crop.right), max(0.0, (position - full.minX) / full.width)))
            PlacementCropEdge.right -> crop.copy(right = min(max(crop.right, maximum - crop.left), max(0.0, (full.maxX - position) / full.width)))
            PlacementCropEdge.top -> crop.copy(top = min(max(crop.top, maximum - crop.bottom), max(0.0, (position - full.minY) / full.height)))
            PlacementCropEdge.bottom -> crop.copy(bottom = min(max(crop.bottom, maximum - crop.top), max(0.0, (full.maxY - position) / full.height)))
        }
        return copy(edgeCrop = if (result.isEmpty) null else result)
    }

    /** Swift `validGeometry` in `TrackTimeline.validate`. */
    val validGeometry: Boolean
        get() = listOf(x, y, width, height, framing.x, framing.y, framing.zoom).all { it.isFinite() } &&
            x in -2.0..3.0 && y in -2.0..3.0 && width in 0.01..3.0 && height in 0.01..3.0 &&
            framing.x in 0.0..1.0 && framing.y in 0.0..1.0 && framing.zoom in 1.0..8.0 && (edgeCrop?.valid ?: true)

    companion object {
        /** Layout presets: 0 Cover, 1 Side by side, 2 Top and bottom, 3 Picture in picture. */
        fun layout(kind: Int, index: Int, count: Int): VideoPlacement {
            var value = VideoPlacement(order = index)
            if (kind == 1) { val width = 1.0 / max(1, count); value = value.copy(width = width, x = (index + 0.5) * width) }
            if (kind == 2) { val height = 1.0 / max(1, count); value = value.copy(height = height, y = (index + 0.5) * height) }
            if (kind == 3 && index > 0) {
                val total = max(1, count - 1)
                val columns = if (total <= 3) 1 else kotlin.math.ceil(kotlin.math.sqrt(total.toDouble())).toInt()
                val rows = kotlin.math.ceil(total.toDouble() / columns).toInt()
                val gap = 0.02
                val side = min(0.32, (0.9 - (max(columns, rows) - 1) * gap) / max(columns, rows))
                value = value.copy(width = side, height = side,
                    x = 0.94 - side / 2 - ((index - 1) % columns) * (side + gap),
                    y = 0.94 - side / 2 - ((index - 1) / columns) * (side + gap))
            }
            return value
        }

        /// One stable order for the compositor, inspector stills and hit testing.
        fun ordered(ids: List<UUID>, settings: Map<UUID, VideoPlacement>): List<UUID> {
            val fallback = ids.withIndex().associate { it.value to it.index }
            return ids.sortedWith { p, q ->
                val a = settings[p]?.order ?: fallback.getValue(p)
                val b = settings[q]?.order ?: fallback.getValue(q)
                if (a == b) fallback.getValue(p).compareTo(fallback.getValue(q)) else a.compareTo(b)
            }
        }
    }
}

@Serializable
data class VideoPlacementRegion(
    val id: UUID = newUUID(),
    val participants: Set<UUID>,
    @Serializable(with = VideoPlacementMapSerializer::class) val videos: Map<UUID, VideoPlacement>,
)

/** Swift `[UUID: VideoPlacement]`: a flat `[key, value, ...]` array (see UUIDKeyedJsonMapSerializer). */
object VideoPlacementMapSerializer : KSerializer<Map<UUID, VideoPlacement>> {
    override val descriptor: SerialDescriptor = UUIDKeyedJsonMapSerializer.descriptor
    override fun serialize(encoder: Encoder, value: Map<UUID, VideoPlacement>) {
        val json = (encoder as? JsonEncoder)?.json ?: SwiftJSON
        UUIDKeyedJsonMapSerializer.serialize(encoder, value.mapValues { json.encodeToJsonElement(VideoPlacement.serializer(), it.value) })
    }
    override fun deserialize(decoder: Decoder): Map<UUID, VideoPlacement> {
        val json = (decoder as? JsonDecoder)?.json ?: SwiftJSON
        return UUIDKeyedJsonMapSerializer.deserialize(decoder).mapValues { json.decodeFromJsonElement(VideoPlacement.serializer(), it.value) }
    }
}

@Serializable
data class TimelineTransition(
    val outgoing: UUID? = null,
    val incoming: UUID? = null,
    val settings: ClipTransition,
    /// Persist the resolved picture/sound placement. Rendering never ripples.
    val origin: EditTime,
    val factor: Double = 1.0,
) {
    val id: UUID get() = settings.id
    /** -1 opening (no outgoing picture), +1 closing (no incoming), 0 a join between two pictures. */
    val edge: Int get() = if (outgoing == null) -1 else if (incoming == null) 1 else 0
}

@Serializable
data class TimelineGeneratedRegion(
    val id: UUID,
    val owner: UUID,
    val interval: EditInterval,
    val freezeVideo: UUID? = null,
)

data class CompiledTimelineInterval(
    val interval: EditInterval,
    val video: List<UUID>,
    val audio: List<UUID>,
    val placementID: UUID?,
    val transitionIDs: List<UUID>,
)

data class CutNeighbor(val id: UUID, val occurrence: UUID)

/** Swift returns `(timeline: Self, selection: TimelineSelection)`. */
data class ScopedTimeline(val timeline: TrackTimeline, val selection: TimelineSelection)

private val backToFrontOrder: Comparator<VideoTimelineItem> =
    Comparator { a, b -> if (a.layer == b.layer) a.id.uuidString.compareTo(b.id.uuidString) else a.layer.compareTo(b.layer) }

/// Canonical independent-item edit. There is no inferred magnetic track: ripple
/// is an explicit edit command and a link records selection membership only.
@Serializable
class TrackTimeline(
    var video: List<VideoTimelineItem> = emptyList(),
    var audio: List<AudioTimelineItem> = emptyList(),
    var links: List<TimelineLink> = emptyList(),
    var bookmarks: List<TimelineBookmark> = emptyList(),
    var placements: List<VideoPlacementRegion> = emptyList(),
    var transitions: List<TimelineTransition> = emptyList(),
    var generated: List<TimelineGeneratedRegion> = emptyList(),
    /** Project captions (Subtitles.kt); the words live on the audio items. */
    var subtitles: SubtitleSettings? = null,
) {
    // Transient provenance follows tool drafts; it is not rendering data.
    @Transient var captureBase: Int? = null

    /** The Swift struct copy (including transient provenance). */
    fun copy(): TrackTimeline =
        TrackTimeline(video, audio, links, bookmarks, placements, transitions, generated, subtitles).also { it.captureBase = captureBase }

    override fun equals(other: Any?): Boolean {
        if (other !is TrackTimeline) return false
        return video == other.video && audio == other.audio && links == other.links && bookmarks == other.bookmarks &&
            placements == other.placements && transitions == other.transitions && generated == other.generated && subtitles == other.subtitles
    }
    override fun hashCode(): Int = listOf(video, audio, links, bookmarks, placements, transitions, generated, subtitles).hashCode()
    override fun toString(): String = "TrackTimeline(video=$video, audio=$audio, links=$links, bookmarks=$bookmarks)"

    val duration: EditTime
        get() = maxOf(
            video.maxOfOrNull { it.end } ?: EditTime.zero,
            audio.maxOfOrNull { it.end } ?: EditTime.zero,
            generated.maxOfOrNull { it.interval.end } ?: EditTime.zero,
        )
    val allIDs: Set<UUID> get() = (video.map { it.id } + audio.map { it.id }).toSet()

    /// One-time conversion of a development-format sequential edit. Capture IDs
    /// give deterministic counterpart IDs, so converting Undo snapshots agrees.
    constructor(clips: List<TimelineClip>, effects: List<AudioEffect> = emptyList(), pictureOnlySources: Set<UUID> = emptySet()) : this() {
        val layout = TimelineLayout(clips)
        val video = mutableListOf<VideoTimelineItem>()
        val audio = mutableListOf<AudioTimelineItem>()
        val links = mutableListOf<TimelineLink>()
        for (item in layout.placements) {
            val clip = item.clip.copy(openingTransition = null, transition = null)
            video += VideoTimelineItem(clip.id, EditTime.of(item.start), clip, item.index)
            if (clip.sourceID in pictureOnlySources) continue
            val audioID = counterpart(clip.id)
            var sound = AudioTimelineItem(id = audioID, occurrenceID = clip.id, sourceID = clip.sourceID, start = EditTime.of(item.start), mapping = SourcePlaybackMap(clip))
            if (effects.isNotEmpty()) sound = sound.copy(enhancements = listOf(AudioEnhancementRegion(audioID, EditInterval(EditTime.zero, sound.mapping.duration), effects)))
            audio += sound.copy(followsVideo = clip.id)
            links += TimelineLink(clip.id, setOf(clip.id, audioID))
        }
        this.video = video; this.audio = audio; this.links = links
        generated = layout.joins.mapNotNull { join ->
            val transition = join.transition ?: return@mapNotNull null
            if (join.range.endInclusive <= join.range.start) return@mapNotNull null
            TimelineGeneratedRegion(counterpart(transition.id), transition.id, EditInterval.of(join.range.start, join.range.endInclusive),
                if (transition.style == TransitionStyle.still && join.index > 0) clips[join.index - 1].id else null)
        }
        transitions = layout.joins.mapNotNull { join ->
            val settings = join.transition ?: return@mapNotNull null
            TimelineTransition(if (join.index > 0) clips[join.index - 1].id else null,
                if (join.index < clips.size) clips[join.index].id else null, settings, EditTime.of(join.origin), join.factor)
        }
    }

    fun expanded(selection: TimelineSelection): TimelineSelection {
        var pictures = selection.video
        var sounds = selection.audio
        val ids = selection.ids
        for (group in links) {
            if (group.members.isDisjoint(ids)) continue
            pictures = pictures + video.filter { it.id in group.members }.map { it.id }
            sounds = sounds + audio.filter { it.id in group.members }.map { it.id }
        }
        return selection.copy(video = pictures, audio = sounds)
    }

    fun selection(of: UUID, linked: Boolean = true, range: EditInterval? = null): TimelineSelection {
        val members = if (linked) links.firstOrNull { of in it.members }?.members ?: setOf(of) else setOf(of)
        return TimelineSelection(video.filter { it.id in members }.map { it.id }.toSet(), audio.filter { it.id in members }.map { it.id }.toSet(), range)
    }

    fun items(usingSources: Set<UUID>): TimelineSelection =
        TimelineSelection(video.filter { it.content.sourceID in usingSources }.map { it.id }.toSet(),
            audio.filter { it.sourceID in usingSources }.map { it.id }.toSet())

    fun entireSelection(range: EditInterval? = null) = TimelineSelection(video.map { it.id }.toSet(), audio.map { it.id }.toSet(), range)

    fun selection(ids: Set<UUID>): TimelineSelection =
        expanded(TimelineSelection(ids.intersect(video.map { it.id }.toSet()), ids.intersect(audio.map { it.id }.toSet())))

    fun toggling(id: UUID, selected: Set<UUID>): Set<UUID> {
        val group = selection(of = id).ids
        return if (group.isSubset(selected)) selected - group else selected + group
    }

    fun isSingleLink(selection: TimelineSelection): Boolean = links.any { it.members == selection.ids }

    fun interval(selection: TimelineSelection): EditInterval? {
        selection.range?.let { return it }
        val intervals = video.filter { it.id in selection.video }.map { it.interval } + audio.filter { it.id in selection.audio }.map { it.interval }
        val start = intervals.minOfOrNull { it.start } ?: return null
        val end = intervals.maxOf { it.end }
        return EditInterval(start, end)
    }

    fun unlink(selection: TimelineSelection) {
        val ids = selection.ids
        val members = links.filter { !it.members.isDisjoint(ids) }.flatMap { it.members }.toSet()
        audio = audio.map { if (it.id in members) it.copy(followsVideo = null) else it }
        links = links.filter { it.members.isDisjoint(ids) }
    }

    /// Establish horizontal continuity first, then compare the two resulting
    /// spans. Different source files/effects remain separate render items; the
    /// link glues their selection and movement without flattening any media.
    fun linkPlan(selection: TimelineSelection): TimelineLinkPlan {
        val selection = expanded(selection)
        if (!(selection.ids.size >= 2 && selection.range == null)) throw TimelineLinkError.tooFew
        fun chain(parts: List<Pair<UUID?, EditInterval>>): EditInterval? {
            val sorted = parts.sortedBy { it.second.start }
            val first = sorted.firstOrNull() ?: return null
            for ((a, b) in sorted.zipWithNext()) {
                if (a.second.end == b.second.start) continue
                // A real transition connects two sequential parts even though
                // its handles overlap. Arbitrary stacked videos are not a chain.
                val transitionJoin = a.second.end > b.second.start && a.first != null && b.first != null &&
                    transitions.any { it.outgoing == a.first && it.incoming == b.first }
                if (!transitionJoin) throw TimelineLinkError.nonContiguous
            }
            return EditInterval(first.second.start, sorted.maxOf { it.second.end })
        }
        val plan = TimelineLinkPlan(selection,
            chain(video.filter { it.id in selection.video }.map { it.id to it.interval }),
            chain(audio.filter { it.id in selection.audio }.map { it.followsVideo to it.interval }))
        if (plan.needsIntersection && plan.intersection == null) throw TimelineLinkError.noIntersection
        val shared = plan.intersection
        if (plan.needsIntersection && shared != null) {
            for (transition in transitions) {
                val a = video.firstOrNull { it.id == transition.outgoing && it.id in selection.video } ?: continue
                val b = video.firstOrNull { it.id == transition.incoming && it.id in selection.video } ?: continue
                if (listOf(shared.start, shared.end).any { b.start < it && it < a.end }) throw TimelineLinkError.transitionBoundary
            }
        }
        return plan
    }

    /// Pure draft: cancellation cannot split footage or create a history entry.
    fun joining(selection: TimelineSelection, intersectionOnly: Boolean = false): ScopedTimeline {
        val plan = linkPlan(selection)
        if (!(!plan.needsIntersection || intersectionOnly)) throw TimelineLinkError.confirmationRequired
        var target = plan.selection
        if (plan.needsIntersection) target = target.copy(range = plan.intersection)
        val result = scoped(target)
        val timeline = result.timeline
        val members = result.selection.ids
        timeline.links = timeline.links.mapNotNull { group ->
            val remaining = group.members - members
            if (remaining.size >= 2) group.copy(members = remaining) else null
        }
        timeline.links = timeline.links + TimelineLink(members = members)
        // Preserve existing sample-aligned followers. Re-linking a matching
        // original pair also restores its source-time relationship for Speed.
        timeline.audio = timeline.audio.map { sound ->
            if (sound.id !in result.selection.audio || sound.followsVideo != null) return@map sound
            val picture = timeline.video.firstOrNull {
                it.id in result.selection.video && it.occurrenceID == sound.occurrenceID && it.content.sourceID == sound.sourceID &&
                    it.start == sound.start && it.mapping == sound.mapping
            }
            if (picture != null) sound.copy(followsVideo = picture.id) else sound
        }
        timeline.pruneRelations()
        return result
    }

    /// Resolve companions on the scoped, undragged timeline once per Move session.
    /// An ordinary overlap is a separate layer; a transition explicitly joins its two pictures.
    fun moveChoices(selected: TimelineSelection): TimelineMoveChoices {
        val seed = expanded(selected)
        val pictures = video.map { it.id }.toSet()
        val sounds = audio.map { it.id }.toSet()
        val pictureLinks = links.filter { !it.members.isDisjoint(pictures) }
        val linkedToPicture = pictureLinks.flatMap { it.members }.toSet()
        val groupByMember = HashMap<UUID, TimelineLink>()
        for (group in links) for (member in group.members) groupByMember[member] = group

        // A transition owns the cut on the corresponding side of each participant.
        val previous = HashMap<UUID, UUID>()
        val next = HashMap<UUID, UUID>()
        fun join(a: UUID, b: UUID) { previous[b] = a; next[a] = b }
        for (relation in transitions) {
            val a = relation.outgoing ?: continue
            val b = relation.incoming ?: continue
            join(a, b)
        }
        val starts = LinkedHashMap<MoveBoundary, MutableList<CutNeighbor>>()
        val ends = LinkedHashMap<MoveBoundary, MutableList<CutNeighbor>>()
        for (picture in video.sortedWith(backToFrontOrder)) {
            val part = CutNeighbor(picture.id, picture.occurrenceID)
            if (previous[picture.id] == null) starts.getOrPut(MoveBoundary(false, picture.start)) { mutableListOf() } += part
            if (next[picture.id] == null) ends.getOrPut(MoveBoundary(false, picture.end)) { mutableListOf() } += part
        }
        // Independent sound has its own joins. A soundtrack can span many picture gaps.
        for (sound in audio) {
            if (sound.id in linkedToPicture) continue
            val part = CutNeighbor(sound.id, sound.occurrenceID)
            starts.getOrPut(MoveBoundary(true, sound.start)) { mutableListOf() } += part
            ends.getOrPut(MoveBoundary(true, sound.end)) { mutableListOf() } += part
        }
        for ((cut, outgoing) in ends) {
            for ((a, b) in neighbors(outgoing, starts[cut] ?: emptyList())) join(a, b)
        }
        fun following(neighbors: Map<UUID, UUID>): TimelineSelection {
            val members = LinkedHashSet(seed.ids)
            val pending = ArrayDeque(members)
            val visitedLinks = HashSet<UUID>()
            fun include(id: UUID) { if (members.add(id)) pending.addLast(id) }
            while (pending.isNotEmpty()) {
                val id = pending.removeLast()
                val group = groupByMember[id]
                if (group != null && visitedLinks.add(group.id)) group.members.forEach(::include)
                neighbors[id]?.let(::include)
            }
            return TimelineSelection(members.intersect(pictures), members.intersect(sounds))
        }
        val left = following(previous)
        val right = following(next)
        val both = TimelineSelection(left.video + right.video, left.audio + right.audio)
        return TimelineMoveChoices(seed, left, right, both)
    }

    /// Shift all selected members by one clamped offset; never clamp individually
    /// because doing so would destroy their existing synchronization/offset.
    fun move(selection: TimelineSelection, by: EditTime) {
        val selection = expanded(selection)
        val range = interval(TimelineSelection(selection.video, selection.audio)) ?: return
        val amount = maxOf(EditTime.zero - range.start, by)
        if (amount == EditTime.zero) return
        val previous = video
        video = video.map { if (it.id in selection.video) it.copy(start = it.start + amount) else it }
        promoteNewOverlaps(selection.video, previous)
        audio = audio.map { if (it.id in selection.audio) it.copy(start = it.start + amount) else it }
        fun moves(relation: TimelineTransition) = selection.video.containsAll(listOfNotNull(relation.outgoing, relation.incoming))
        transitions = transitions.map { if (moves(it)) it.copy(origin = it.origin + amount) else it }
        generated = generated.map { region ->
            val relation = transitions.firstOrNull { it.id == region.owner }
            if (relation != null && moves(relation)) region.copy(interval = EditInterval(region.interval.start + amount, region.interval.end + amount)) else region
        }
        retainPlacementAcrossNewOverlaps(selection.video)
        pruneRelations()
    }

    /// A newly dropped overlay comes forward. Adjusting an existing overlap
    /// must not silently swap the picture merely to fix its timing.
    private fun promoteNewOverlaps(selected: Set<UUID>, previous: List<VideoTimelineItem>) {
        val old = previous.associate { it.id to it.interval }
        val stationary = video.filter { it.id !in selected }
        val promoted = video.any { item ->
            item.id in selected && stationary.any { other ->
                item.interval.intersection(other.interval) != null && old[item.id]?.intersection(old[other.id] ?: other.interval) == null
            }
        }
        if (!promoted) return
        val front = (video.maxOfOrNull { it.layer } ?: 0) + 1
        val order = video.indices.filter { video[it].id in selected }.sortedWith { a, b -> backToFrontOrder.compare(video[a], video[b]) }
        val layers = order.withIndex().associate { (rank, index) -> index to front + rank }
        video = video.mapIndexed { index, item -> layers[index]?.let { item.copy(layer = it) } ?: item }
    }

    fun setVolume(db: Double, muted: Boolean, selection: TimelineSelection) {
        audio = audio.map { if (it.id in selection.audio) it.copy(volumeDB = min(12.0, max(-60.0, db)), muted = muted) else it }
    }

    /** Swift `AudioTimelineItem.setFade` on the selected items of the timeline. */
    fun setFade(edge: AudioFadeEdge, seconds: Double, itemID: UUID) {
        audio = audio.map { if (it.id == itemID) it.settingFade(edge, seconds) else it }
    }

    fun enhance(effects: List<AudioEffect>, selection: TimelineSelection, effectID: UUID = newUUID()) {
        audio = audio.map { item ->
            if (item.id !in selection.audio) return@map item
            val target: EditInterval = selection.range?.let { item.interval.intersection(it) ?: return@map item } ?: item.interval
            val local = EditInterval(target.start - item.start, target.end - item.start)
            val regions = mutableListOf<AudioEnhancementRegion>()
            for (region in item.enhancements) {
                if (region.interval.intersection(local) == null) { regions += region; continue }
                if (region.interval.start < local.start) regions += region.copy(interval = region.interval.copy(end = local.start))
                if (region.interval.end > local.end) regions += region.copy(interval = region.interval.copy(start = local.end))
            }
            if (effects.isNotEmpty()) regions += AudioEnhancementRegion(effectID, local, effects)
            item.copy(enhancements = regions.sortedBy { it.interval.start })
        }
    }

    /// Return the new position of the content under the playhead. Use the same
    /// resolved shifts as deletion (not the selected duration: overlaps and
    /// detached tracks may leave some or all of that time in place).
    fun remove(selection: TimelineSelection, preserving: EditTime? = null): EditTime? {
        val position = preserving
        if (selection.isEmpty) return position
        val oldVideo = video
        val oldAudio = audio
        val oldLinks = links
        val oldDuration = duration
        val selectedIDs = selection.ids
        // Only complete linked groups qualify. A range is split before this call.
        val complete = oldLinks.filter { !it.members.isDisjoint(selection.video) && !it.members.isDisjoint(selection.audio) && it.members.isSubset(selectedIDs) }
        val removedVideo = complete.flatMap { it.members }.toSet().intersect(selection.video)
        val closing = union(oldVideo.filter { it.id in removedVideo }.map { it.interval })
        val audioIDs = audio.map { it.id }.toSet()
        val videoIDs = video.map { it.id }.toSet()
        val downstreamGroups = oldLinks.filter { it.members.isDisjoint(selectedIDs) && !it.members.isDisjoint(audioIDs) && !it.members.isDisjoint(videoIDs) }
        val downstreamIDs = downstreamGroups.flatMap { it.members }.toSet()
        val remainingVideo = oldVideo.filter { it.id !in selection.video && it.id in downstreamIDs }
        // P and N are remaining linked picture edges. This closes only the
        // newly vacated space, including the handles of removed transitions.
        val closedGaps = closing.mapNotNull { range ->
            val before = remainingVideo.filter { it.start < range.start }.maxOfOrNull { it.end } ?: range.start
            val after = remainingVideo.filter { it.start >= range.start }.minOfOrNull { it.start } ?: range.end
            val start = maxOf(range.start, before)
            val end = minOf(range.end, after)
            if (end > start) EditInterval(start, end) else null
        }
        video = video.filter { it.id !in selection.video }
        audio = audio.filter { it.id !in selection.audio }
        for (group in downstreamGroups) {
            val positions = oldVideo.filter { it.id in group.members }.map { it.start }
            val anchor = positions.minOrNull() ?: audio.filter { it.id in group.members }.map { it.start }.minOrNull() ?: EditTime.zero
            val amount = closedGaps.filter { it.start <= anchor }.fold(EditTime.zero) { total, gap -> total + gap.duration }
            video = video.map { if (it.id in group.members) it.copy(start = it.start - amount) else it }
            audio = audio.map { if (it.id in group.members) it.copy(start = it.start - amount) else it }
        }
        transitions = transitions.map { relation ->
            val owner = relation.outgoing ?: relation.incoming ?: return@map relation
            val prior = oldVideo.firstOrNull { it.id == owner } ?: return@map relation
            val current = video.firstOrNull { it.id == owner } ?: return@map relation
            relation.copy(origin = relation.origin + current.start - prior.start)
        }
        pruneRelations()
        if (position == null) return null
        val removedTime = closedGaps.fold(EditTime.zero) { total, gap -> total + minOf(gap.duration, maxOf(EditTime.zero, position - gap.start)) }
        if (closedGaps.any { it.contains(position) }) return minOf(duration, maxOf(EditTime.zero, position - removedTime))
        fun contains(interval: EditInterval) = interval.contains(position) || (position == oldDuration && interval.end == position)
        // Preserve the visible occurrence, even if an unrelated layer rippled.
        // Audio is the anchor only when there was no picture here to begin with.
        var mapped: EditTime? = null
        val picture = oldVideo.filter { contains(it.interval) }.maxByOrNull { it.layer }
        if (picture != null) {
            video.firstOrNull { it.id == picture.id }?.let { kept -> mapped = position + kept.start - picture.start }
        } else {
            val sound = oldAudio.firstOrNull { contains(it.interval) }
            val kept = sound?.let { s -> audio.firstOrNull { it.id == s.id } }
            if (sound != null && kept != null) mapped = position + kept.start - sound.start
        }
        // Inside deleted footage, land at its resulting cut. With no ripple
        // (unlinked picture/sound), the existing gap and clock stay unchanged.
        return minOf(duration, maxOf(EditTime.zero, mapped ?: (position - removedTime)))
    }

    fun retainUniqueTransitionSlots() {
        val incoming = HashSet<UUID>()
        val outgoing = HashSet<UUID>()
        transitions = transitions.reversed().filter { relation ->
            if (relation.incoming?.let { it in incoming } == true || relation.outgoing?.let { it in outgoing } == true) return@filter false
            relation.incoming?.let { incoming += it }
            relation.outgoing?.let { outgoing += it }
            true
        }.reversed()
        val owners = transitions.map { it.id }.toSet()
        generated = generated.filter { it.owner in owners }
    }

    fun pruneRelations() {
        val ids = allIDs
        links = links.mapNotNull { link ->
            val members = link.members.intersect(ids)
            if (members.size >= 2) (if (members == link.members) link else link.copy(members = members)) else null
        }
        placements = placements.filterNot { region ->
            val members = video.filter { it.id in region.participants }
            if (!(members.size == region.participants.size && members.size > 1)) return@filterNot true
            val lower = members.maxOf { it.start }
            val upper = members.minOf { it.end }
            lower >= upper
        }
        transitions = transitions.mapNotNull { saved ->
            var relation = saved
            val a = relation.outgoing?.let { id -> video.firstOrNull { it.id == id } }
            val b = relation.incoming?.let { id -> video.firstOrNull { it.id == id } }
            if ((relation.outgoing != null && a == null) || (relation.incoming != null && b == null)) return@mapNotNull null
            val edge = if (a == null) -1 else if (b == null) 1 else 0
            if (a != null && b != null) {
                val requiredOverlap = (a.end - b.start).seconds
                var lo = 0.0
                var hi = 1.0
                val original = TransitionTiming(relation.settings, relation.factor)
                if (abs(original.overlap - requiredOverlap) > 2.0 / EditTime.scale) {
                    val low = TransitionTiming(relation.settings, 0.0).overlap
                    val high = TransitionTiming(relation.settings, 1.0).overlap
                    if (!(requiredOverlap >= min(low, high) - 0.00001 && requiredOverlap <= max(low, high) + 0.00001)) return@mapNotNull null
                    repeat(48) {
                        val mid = (lo + hi) / 2
                        val overlap = TransitionTiming(relation.settings, mid).overlap
                        if ((overlap < requiredOverlap) == (high >= low)) lo = mid else hi = mid
                    }
                    relation = relation.copy(factor = (lo + hi) / 2)
                }
                val timing = TransitionTiming(relation.settings, relation.factor)
                if (!(timing.leftCost <= a.mapping.duration.seconds + 0.00001 && timing.rightCost <= b.mapping.duration.seconds + 0.00001)) return@mapNotNull null
                relation = relation.copy(origin = a.end - EditTime.of(timing.leftEnd))
                if (abs((relation.origin + EditTime.of(timing.rightStart) - b.start).ticks) > 2) return@mapNotNull null
            } else {
                val timing = TransitionTiming(relation.settings, relation.factor, edge)
                if (a != null) relation = relation.copy(origin = a.end - EditTime.of(timing.leftEnd))
                if (b != null) relation = relation.copy(origin = b.start - EditTime.of(timing.rightStart))
            }
            relation
        }
        retainUniqueTransitionSlots()
        generated = transitions.map { relation ->
            val timing = TransitionTiming.of(relation)
            TimelineGeneratedRegion(counterpart(relation.id), relation.id,
                EditInterval(maxOf(EditTime.zero, relation.origin + EditTime.of(timing.lower)), maxOf(EditTime.zero, relation.origin + EditTime.of(timing.upper))),
                if (relation.settings.style == TransitionStyle.still) relation.outgoing else null)
        }
        audio = audio.map { item ->
            val id = item.followsVideo
            if (id != null && links.none { id in it.members && item.id in it.members }) item.copy(followsVideo = null) else item
        }
    }

    val resolvedBookmarks: List<ResolvedBookmark>
        get() = bookmarks.mapNotNull { bookmark ->
            val time: EditTime? = when (val anchor = bookmark.anchor) {
                is BookmarkAnchor.project -> anchor.time
                is BookmarkAnchor.source ->
                    video.filter { it.occurrenceID == anchor.occurrence && it.content.sourceID == anchor.source }
                        .firstNotNullOfOrNull { item -> item.mapping.local(anchor.time)?.let { item.start + it } }
                        ?: audio.filter { it.occurrenceID == anchor.occurrence && it.sourceID == anchor.source }
                            .firstNotNullOfOrNull { item -> item.mapping.local(anchor.time)?.let { item.start + it } }
                is BookmarkAnchor.local ->
                    video.firstOrNull { it.occurrenceID == anchor.occurrence && anchor.offset >= it.localOrigin && anchor.offset < it.localOrigin + it.mapping.duration }
                        ?.let { it.start + anchor.offset - it.localOrigin }
            }
            time?.let { ResolvedBookmark(bookmark, it) }
        }.sortedWith { a, b -> if (a.time == b.time) a.id.uuidString.compareTo(b.id.uuidString) else a.time.compareTo(b.time) }

    /// A newly overlapping third video must not reset the existing windows.
    /// Geometry follows shared items; new items retain their normal layer slot.
    fun placementSeed(participants: Set<UUID>, near: EditTime, moving: Set<UUID> = emptySet()): VideoPlacementRegion {
        placements.firstOrNull { it.participants == participants }?.let { return it }
        val position = near
        val members = video.filter { it.id in participants }.sortedWith(backToFrontOrder)
        val canonical = members.map { it.id }
        fun distance(region: VideoPlacementRegion): Double {
            val items = video.filter { it.id in region.participants }
            val lower = items.maxOfOrNull { it.start } ?: EditTime.zero
            val upper = items.minOfOrNull { it.end } ?: EditTime.zero
            return max(0.0, max(lower.seconds - position.seconds, position.seconds - upper.seconds))
        }
        val candidates = placements.filter { !it.participants.isDisjoint(participants) }.sortedWith { p, q ->
            val a = p.participants.intersect(participants).size
            val b = q.participants.intersect(participants).size
            if (a != b) return@sortedWith b.compareTo(a)
            val da = distance(p)
            val db = distance(q)
            if (da == db) p.id.uuidString.compareTo(q.id.uuidString) else da.compareTo(db)
        }
        val values = members.associate { item ->
            item.id to (candidates.firstOrNull { region ->
                region.videos[item.id] != null && (item.id in moving ||
                    region.participants.intersect(participants).size > 1 || distance(region) < 0.00001)
            }?.videos?.get(item.id) ?: VideoPlacement())
        }.toMutableMap()
        val order = canonical.toMutableList()
        for (region in candidates.reversed()) {
            val slots = order.indices.filter { order[it] in region.participants }
            val shared = VideoPlacement.ordered(slots.map { order[it] }, region.videos)
            for ((slot, id) in slots.zip(shared)) order[slot] = id
        }
        for ((rank, id) in order.withIndex()) values[id]?.let { values[id] = it.copy(order = rank) }
        return VideoPlacementRegion(participants = participants, videos = values)
    }

    private fun retainPlacementAcrossNewOverlaps(moving: Set<UUID>) {
        if (placements.isEmpty() || moving.isEmpty()) return
        val original = copy()
        val seen = placements.map { it.participants }.toMutableSet()
        for (interval in compile()) {
            if (!(interval.video.size > 1 && !interval.video.toSet().isDisjoint(moving))) continue
            val ids = interval.video.toSet()
            if (!seen.add(ids)) continue
            val seed = original.placementSeed(ids, interval.interval.start, moving)
            val defaults = interval.video.withIndex().associate { (rank, id) -> id to VideoPlacement(order = rank) }
            if (seed.videos != defaults) placements = placements + seed
        }
    }

    /// Audio edits can subdivide a visual overlap. Merge those subdivisions,
    /// but never include a time where the inspector's visible layers differ.
    fun placementPreviewInterval(participants: Set<UUID>, near: Double, preferred: ClosedFloatingPointRange<Double>): EditInterval? {
        val spans = union(compile().filter { it.video.toSet() == participants && it.video.size > 1 }.map { it.interval })
        return spans.firstOrNull { it.contains(EditTime.of(near)) }
            ?: spans.firstOrNull { it.intersection(EditInterval.of(preferred.start, preferred.endInclusive)) != null }
            ?: spans.firstOrNull()
    }

    fun compile(): List<CompiledTimelineInterval> {
        val duration = duration
        val edges = linkedSetOf(EditTime.zero, duration)
        for (item in video) { edges += item.start; edges += item.end }
        for (item in audio) {
            edges += item.start; edges += item.end
            for (region in item.enhancements) { edges += item.start + region.interval.start; edges += item.start + region.interval.end }
            for (region in item.censors) { edges += item.start + region.interval.start; edges += item.start + region.interval.end }
        }
        for (region in generated) { edges += region.interval.start; edges += region.interval.end }
        val timings = transitions.associate { it.id to TransitionTiming.of(it) }
        for (relation in transitions) {
            val timing = timings.getValue(relation.id)
            for (value in listOf(timing.lower, timing.upper, 0.0, timing.videoDuration, timing.audioIn.start, timing.audioIn.endInclusive,
                    timing.audioOut.start, timing.audioOut.endInclusive)) {
                edges += maxOf(EditTime.zero, relation.origin + EditTime.of(value))
            }
        }
        val marks = edges.filter { it >= EditTime.zero && it <= duration }.sorted()
        return marks.zipWithNext().mapNotNull { (a, b) ->
            if (b <= a) return@mapNotNull null
            val picture = video.filter { it.interval.contains(a) }.sortedWith(backToFrontOrder)
            val ids = picture.map { it.id }.toSet()
            CompiledTimelineInterval(EditInterval(a, b), picture.map { it.id }, audio.filter { it.interval.contains(a) }.map { it.id },
                placements.firstOrNull { it.participants == ids }?.id,
                transitions.filter { relation ->
                    val timing = timings.getValue(relation.id)
                    (relation.outgoing != null || relation.incoming != null) &&
                        a >= relation.origin + EditTime.of(timing.lower) && a < relation.origin + EditTime.of(timing.upper)
                }.map { it.id })
        }
    }

    /// Split identities once in the draft; dragging or changing inspector values
    /// must reuse that draft instead of regenerating fragments every frame.
    fun split(at: EditTime, selection: TimelineSelection): Map<UUID, UUID> {
        val time = at
        val selection = expanded(selection)
        val children = LinkedHashMap<UUID, UUID>()
        val pictures = mutableListOf<VideoTimelineItem>()
        for (item in video) {
            if (!(item.id in selection.video && time > item.start && time < item.end)) { pictures += item; continue }
            val local = time - item.start
            if (!(local.ticks >= 1 && (item.end - time).ticks >= 1)) { pictures += item; continue }
            var leftContent = item.content
            var rightContent = item.content.copy(id = newUUID())
            val frozen = item.content.frozenFrame
            if (frozen != null) {
                leftContent = leftContent.copy(frozenFrame = frozen.copy(duration = local.seconds))
                rightContent = rightContent.copy(frozenFrame = frozen.copy(duration = (item.end - time).seconds, offset = frozen.offset + local.seconds))
            } else {
                val source = item.mapping.source(local)
                if (source == null) { pictures += item; continue }
                leftContent = leftContent.copy(end = source)
                rightContent = rightContent.copy(start = source)
            }
            val left = item.copy(content = leftContent, mapping = item.mapping.sliced(EditInterval(EditTime.zero, local)))
            val right = item.copy(content = rightContent, start = time, localOrigin = item.localOrigin + local,
                mapping = item.mapping.sliced(EditInterval(local, item.mapping.duration)))
            children[item.id] = right.id
            pictures += left; pictures += right
        }
        video = pictures
        val sounds = mutableListOf<AudioTimelineItem>()
        for (item in audio) {
            if (!(item.id in selection.audio && time > item.start && time < item.end)) { sounds += item; continue }
            val local = time - item.start
            val newID = newUUID()
            sounds += item.sliced(EditInterval(EditTime.zero, local), item.id)
            sounds += item.sliced(EditInterval(local, item.mapping.duration), newID)
            children[item.id] = newID
        }
        audio = sounds.map { item ->
            val child = item.followsVideo?.let { children[it] }
            if (child != null && item.start >= time) item.copy(followsVideo = child) else item
        }
        val newLinks = mutableListOf<TimelineLink>()
        val selectedIDs = selection.ids
        for (group in links) {
            // Splitting a different lane must not dissolve a horizontal join.
            if (group.members.isDisjoint(selectedIDs)) { newLinks += group; continue }
            val left = LinkedHashSet<UUID>()
            val right = LinkedHashSet<UUID>()
            for (id in group.members) {
                val child = children[id]
                if (child != null) { left += id; right += child } else {
                    val start = video.firstOrNull { it.id == id }?.start ?: audio.firstOrNull { it.id == id }?.start ?: EditTime.zero
                    if (start >= time) right += id else left += id
                }
            }
            if (left.size >= 2) newLinks += TimelineLink(group.id, left)
            if (right.size >= 2) newLinks += TimelineLink(if (left.isEmpty()) group.id else newUUID(), right)
        }
        links = newLinks
        transitions = transitions.map { relation ->
            val right = relation.outgoing?.let { children[it] }
            if (right != null) relation.copy(outgoing = right) else relation
        }
        // Every child starts at the same cut. A placement can survive on
        // either side, but a mix of left and right children never overlaps.
        val rightPlacements = placements.mapNotNull { region ->
            val divided = region.participants.filter { children[it] != null }
            if (divided.isEmpty()) return@mapNotNull null
            var participants = region.participants
            var videos = region.videos
            for (id in divided) {
                val right = children[id] ?: continue
                participants = participants - id + right
                val value = videos[id]
                videos = videos - id
                if (value != null) videos = videos + (right to value)
            }
            region.copy(id = newUUID(), participants = participants, videos = videos)
        }
        placements = placements + rightPlacements
        pruneRelations()
        return children
    }

    fun scoped(selection: TimelineSelection): ScopedTimeline {
        val selection = expanded(selection)
        val range = selection.range ?: return ScopedTimeline(copy(), selection)
        val result = copy()
        var targets = selection
        val upper = result.split(range.end, targets)
        targets = targets.copy(video = targets.video + upper.filterKeys { it in targets.video }.values,
            audio = targets.audio + upper.filterKeys { it in targets.audio }.values)
        val lower = result.split(range.start, targets)
        targets = targets.copy(video = targets.video + lower.filterKeys { it in targets.video }.values,
            audio = targets.audio + lower.filterKeys { it in targets.audio }.values)
        targets = TimelineSelection(
            result.video.filter { it.id in targets.video && it.start >= range.start && it.end <= range.end }.map { it.id }.toSet(),
            result.audio.filter { it.id in targets.audio && it.start >= range.start && it.end <= range.end }.map { it.id }.toSet(),
            null)
        return ScopedTimeline(result, targets)
    }

    /// Keep a marked scope attached to the same source occurrences when a tool
    /// splits its items. A second use of the same source is not a descendant.
    fun remapping(selection: TimelineSelection, previous: TrackTimeline): TimelineSelection {
        fun scoped(map: SourcePlaybackMap, start: EditTime): SourcePlaybackMap {
            val range = selection.range ?: return map
            return map.sliced(EditInterval(range.start - start, range.end - start))
        }
        val ranges = mutableListOf<EditInterval>()
        fun scopeOrigin(origin: EditTime, start: EditTime, duration: EditTime): EditTime =
            origin + (selection.range?.let { minOf(duration, maxOf(EditTime.zero, it.start - start)) } ?: EditTime.zero)
        fun match(old: SourcePlaybackMap, new: SourcePlaybackMap, oldOrigin: EditTime, newOrigin: EditTime, start: EditTime): Boolean {
            var found = false
            var local = EditTime.zero
            for (run in new.runs) {
                var sourceLocal = oldOrigin
                for (source in old.runs) {
                    if (run.isHold || source.isHold) {
                        if (run.isHold && source.isHold && run.sourceStart == source.sourceStart) {
                            val part = EditInterval(sourceLocal, sourceLocal + source.duration).intersection(EditInterval(newOrigin + local, newOrigin + local + run.duration))
                            if (part != null) { ranges += EditInterval(start + part.start - newOrigin, start + part.end - newOrigin); found = true }
                        }
                    } else {
                        val a = max(source.sourceStart, run.sourceStart)
                        val b = min(source.sourceEnd, run.sourceEnd)
                        if (b > a + 0.0000001) {
                            val scale = run.duration.seconds / (run.sourceEnd - run.sourceStart)
                            ranges += EditInterval(start + local + EditTime.of((a - run.sourceStart) * scale), start + local + EditTime.of((b - run.sourceStart) * scale))
                            found = true
                        }
                    }
                    sourceLocal += source.duration
                }
                local += run.duration
            }
            return found
        }
        val pictures = previous.video.filter { it.id in selection.video }
        val sounds = previous.audio.filter { it.id in selection.audio }
        val pictureIDs = video.filter { item ->
            pictures.any {
                it.occurrenceID == item.occurrenceID && it.content.sourceID == item.content.sourceID &&
                    match(scoped(it.mapping, it.start), item.mapping, scopeOrigin(it.localOrigin, it.start, it.mapping.duration), item.localOrigin, item.start)
            }
        }.map { it.id }.toSet()
        val soundIDs = audio.filter { item ->
            sounds.any {
                it.occurrenceID == item.occurrenceID && it.sourceID == item.sourceID &&
                    match(scoped(it.mapping, it.start), item.mapping, scopeOrigin(it.localOrigin, it.start, it.mapping.duration), item.localOrigin, item.start)
            }
        }.map { it.id }.toSet()
        val range = selection.range?.let {
            val start = ranges.minOfOrNull { it.start }
            val end = ranges.maxOfOrNull { it.end }
            if (start != null && end != null) EditInterval(start, end) else null
        }
        return TimelineSelection(pictureIDs, soundIDs, range)
    }

    fun insertTime(duration: EditTime, at: EditTime, atPictureGap: Boolean = false) {
        val time = at
        if (duration <= EditTime.zero) return
        // A soundtrack running under a picture gap continues through the edit.
        // Linked sound follows the pictures being moved, including split edits.
        val splitScope = if (atPictureGap) TimelineSelection(video.filter { it.start >= time }.map { it.id }.toSet()) else entireSelection()
        split(time, splitScope)
        video = video.map { if (it.start >= time) it.copy(start = it.start + duration) else it }
        audio = audio.map { if (it.start >= time) it.copy(start = it.start + duration) else it }
        bookmarks = bookmarks.map { mark ->
            val anchor = mark.anchor
            if (anchor is BookmarkAnchor.project && anchor.time >= time) mark.copy(anchor = BookmarkAnchor.project(anchor.time + duration)) else mark
        }
        transitions = transitions.map { if (it.origin >= time) it.copy(origin = it.origin + duration) else it }
        generated = generated.map { if (it.interval.start >= time) it.copy(interval = EditInterval(it.interval.start + duration, it.interval.end + duration)) else it }
        pruneRelations()
    }

    fun addBookmark(at: EditTime, preferredVideo: UUID? = null, label: String = "") {
        val time = at
        val active = video.filter { it.interval.contains(time) }
        val audioSelected = audio.any { it.id == preferredVideo }
        val item = if (audioSelected) null else active.firstOrNull { it.id == preferredVideo } ?: active.maxByOrNull { it.layer }
        val itemSource = item?.mapping?.source(time - item.start)
        val anchor: BookmarkAnchor = if (item != null && itemSource != null) {
            if (item.content.frozenFrame == null) BookmarkAnchor.source(item.occurrenceID, item.content.sourceID, itemSource)
            else BookmarkAnchor.local(item.occurrenceID, item.localOrigin + time - item.start)
        } else {
            val sound = audio.firstOrNull { it.id == preferredVideo && it.interval.contains(time) } ?: audio.firstOrNull { it.interval.contains(time) }
            val source = sound?.mapping?.source(time - sound.start)
            if (sound != null && source != null) BookmarkAnchor.source(sound.occurrenceID, sound.sourceID, source) else BookmarkAnchor.project(time)
        }
        bookmarks = bookmarks + TimelineBookmark(label = label, anchor = anchor)
    }

    fun validate(sources: List<SourceMedia>) {
        val invalid = { ProjectError.invalidDocument }
        val ids = video.map { it.id } + audio.map { it.id }
        if (!(ids.toSet().size == ids.size && duration >= EditTime.zero && duration.seconds <= 604800)) throw invalid()
        val sourceDurations = HashMap<UUID, Double>()
        for (source in sources) sourceDurations.putIfAbsent(source.id, source.duration)
        fun valid(map: SourcePlaybackMap, sourceID: UUID): Boolean {
            val duration = sourceDurations[sourceID] ?: return false
            if (map.runs.isEmpty()) return false
            return map.runs.all {
                it.duration > EditTime.zero && it.sourceStart.isFinite() && it.sourceEnd.isFinite() && it.sourceStart >= 0 &&
                    it.sourceEnd >= it.sourceStart && it.sourceEnd <= duration + 0.001
            }
        }
        if (!(video.all { it.start >= EditTime.zero && valid(it.mapping, it.content.sourceID) } &&
                audio.all {
                    it.start >= EditTime.zero && valid(it.mapping, it.sourceID) && it.volumeDB.isFinite() && it.volumeDB in -60.0..12.0 &&
                        it.fadeIn?.valid != false && it.fadeOut?.valid != false
                })) throw invalid()
        val linked = HashSet<UUID>()
        val idSet = ids.toSet()
        for (link in links) {
            if (!(link.members.size >= 2 && link.members.isSubset(idSet) && linked.isDisjoint(link.members))) throw invalid()
            linked += link.members
        }
        if (subtitles?.valid == false) throw invalid()
        for (item in audio) {
            val words = item.subtitleWords ?: emptyList()
            if (!(words.size <= 200_000 && words.map { it.id }.toSet().size == words.size &&
                    words.all {
                        it.text.isNotEmpty() && it.text.codePointCount(0, it.text.length) <= 4000 && it.start.isFinite() && it.end.isFinite() &&
                            it.start >= 0 && it.end > it.start && it.end <= (sourceDurations[item.sourceID] ?: 0.0) + 0.001
                    })) throw invalid()
        }
        val pictures = video.map { it.id }.toSet()
        if (!(links.map { it.id }.toSet().size == links.size && bookmarks.map { it.id }.toSet().size == bookmarks.size &&
                placements.map { it.id }.toSet().size == placements.size && transitions.map { it.id }.toSet().size == transitions.size)) throw invalid()
        for (placement in placements) {
            if (!(placement.participants.size > 1 && placement.participants.isSubset(pictures) && placement.videos.keys == placement.participants)) throw invalid()
            // TODO(port): `EffectAnimation.valid` for an animated placement's endpoints.
            if (!placement.videos.values.all { it.validGeometry }) throw invalid()
        }
        val incoming = transitions.mapNotNull { it.incoming }
        val outgoing = transitions.mapNotNull { it.outgoing }
        if (!(incoming.toSet().size == incoming.size && outgoing.toSet().size == outgoing.size)) throw invalid()
        for (relation in transitions) {
            val participants = listOfNotNull(relation.outgoing, relation.incoming).toSet()
            if (!(participants.isNotEmpty() && participants.isSubset(pictures) && relation.settings.valid &&
                    relation.factor.isFinite() && relation.factor in 0.0..1.0)) throw invalid()
        }
        for (bookmark in bookmarks) {
            if (bookmark.label.codePointCount(0, bookmark.label.length) > 500) throw invalid()
            when (val anchor = bookmark.anchor) {
                is BookmarkAnchor.source -> {
                    val duration = sourceDurations[anchor.source]
                    if (!(duration != null && anchor.time.isFinite() && anchor.time >= 0 && anchor.time <= duration + 0.001)) throw invalid()
                }
                is BookmarkAnchor.local -> if (!(anchor.offset >= EditTime.zero && anchor.offset.seconds <= 604800)) throw invalid()
                is BookmarkAnchor.project -> if (!(anchor.time >= EditTime.zero && anchor.time.seconds <= 604800)) throw invalid()
            }
        }
        for (item in audio) {
            item.followsVideo?.let { videoID ->
                val picture = video.firstOrNull { it.id == videoID }
                if (!(picture != null && links.any { videoID in it.members && item.id in it.members } &&
                        picture.start == item.start && picture.mapping.duration == item.mapping.duration)) throw invalid()
            }
            val regions = item.enhancements.sortedBy { it.interval.start }
            var censorEnd = EditTime.zero
            for (region in item.censors.sortedBy { it.interval.start }) {
                val envelopeValid = region.envelopeInterval?.let {
                    !it.isEmpty && it.start <= region.interval.start && it.end >= region.interval.end &&
                        abs(it.start.seconds) <= 604800 && abs(it.end.seconds) <= 604800
                } ?: true
                if (!(region.interval.start >= censorEnd && region.interval.end <= item.mapping.duration && !region.interval.isEmpty &&
                        region.settings.valid && abs(region.phaseOrigin.seconds) <= 604800 && envelopeValid)) throw invalid()
                censorEnd = region.interval.end
            }
            var end = EditTime.zero
            for (region in regions) {
                if (!(region.interval.start >= end && region.interval.end <= item.mapping.duration && !region.interval.isEmpty &&
                        region.effects.map { it.id }.toSet().size == region.effects.size &&
                        region.effects.all {
                            it.implementationVersion > 0 && it.amount.isFinite() && it.frequency.isFinite() && it.frequency in 20.0..20000.0 &&
                                (!it.supported || it.amount in (if (it.kind.isNeural) 0.0..1.0 else -24.0..24.0))
                        })) throw invalid()
                end = region.interval.end
            }
        }
    }

    /// Stable time order for sequence-facing controls. Layer order remains
    /// independent: moving a picture must not change which overlap is in front.
    val chronologicalVideo: List<VideoTimelineItem>
        get() = video.withIndex().sortedWith { a, b ->
            if (a.value.start == b.value.start) a.index.compareTo(b.index) else a.value.start.compareTo(b.value.start)
        }.map { it.value }

    val projectedClips: List<TimelineClip>
        get() = chronologicalVideo.map { item ->
            val outgoing = transitions.firstOrNull { it.outgoing == item.id }
            item.content.copy(openingTransition = transitions.firstOrNull { it.incoming == item.id && it.outgoing == null }?.settings,
                transition = outgoing?.settings?.copy(nextClipID = outgoing.incoming))
        }

    fun restoreSourceMute() {
        video = video.map { it.copy(mapping = it.mapping.applyingMute(it.content)) }
        audio = audio.map { item ->
            val picture = item.followsVideo?.let { id -> video.firstOrNull { it.id == id } } ?: return@map item
            item.copy(mapping = item.mapping.applyingMute(picture.content))
        }
    }

    fun synchronizeFollowers() {
        restoreSourceMute()
        audio = audio.map { original ->
            val item = original.followsVideo?.let { id -> video.firstOrNull { it.id == id } } ?: return@map original
            val priorMap = original.mapping
            var sound = original.copy(start = item.start, mapping = item.mapping)
            if (priorMap == item.mapping) return@map sound
            sound = sound.copy(fadeIn = sound.fadeIn?.fitted(priorMap.duration, item.mapping.duration),
                fadeOut = sound.fadeOut?.fitted(priorMap.duration, item.mapping.duration))
            fun rebase(interval: EditInterval): List<EditInterval> {
                val sourceRuns = priorMap.sliced(interval).runs
                val result = mutableListOf<EditInterval>()
                var cursor = EditTime.zero
                for (run in item.mapping.runs) {
                    for (old in sourceRuns) {
                        if (old.isHold || run.isHold) continue
                        val a = max(old.sourceStart, run.sourceStart)
                        val b = min(old.sourceEnd, run.sourceEnd)
                        if (b <= a) continue
                        val duration = run.sourceEnd - run.sourceStart
                        result += EditInterval(cursor + EditTime.of(run.duration.seconds * (a - run.sourceStart) / duration),
                            cursor + EditTime.of(run.duration.seconds * (b - run.sourceStart) / duration))
                    }
                    cursor += run.duration
                }
                return result
            }
            val rebased = sound.enhancements.flatMap { region -> rebase(region.interval).map { region.copy(interval = it) } }.filter { !it.interval.isEmpty }
            val merged = mutableListOf<AudioEnhancementRegion>()
            for (region in rebased.sortedBy { it.interval.start }) {
                val last = merged.lastOrNull()
                if (last != null && last.id == region.id && last.effects == region.effects && last.interval.end == region.interval.start) {
                    merged[merged.size - 1] = last.copy(interval = last.interval.copy(end = region.interval.end))
                } else merged += region
            }
            val seen = HashSet<UUID>()
            for (j in merged.indices) {
                if (!seen.add(merged[j].id)) { merged[j] = merged[j].copy(id = newUUID()); seen += merged[j].id }
            }
            sound = sound.copy(enhancements = merged)
            val censors = sound.censors.flatMap { region ->
                rebase(region.interval).map { region.copy(interval = it, phaseOrigin = it.start, envelopeInterval = null) }
            }.filter { !it.interval.isEmpty }
            val mergedCensors = mutableListOf<AudioCensorRegion>()
            for (region in censors.sortedBy { it.interval.start }) {
                val last = mergedCensors.lastOrNull()
                if (last != null && last.id == region.id && last.settings == region.settings && last.interval.end == region.interval.start) {
                    mergedCensors[mergedCensors.size - 1] = last.copy(interval = last.interval.copy(end = region.interval.end))
                } else mergedCensors += region
            }
            sound.withCensors(mergedCensors)
        }
    }

    /// Compatibility at the *tool API*, not a second saved timeline. Existing
    /// color/crop/effect inspectors edit video payloads; positions remain explicit.
    fun replaceVideoContents(clips: List<TimelineClip>) {
        val previous = projectedClips
        if (clips == previous) return
        val previousItems = video
        val retimed = LinkedHashSet<UUID>()
        val oldIDs = video.map { it.id }.toSet()
        val newIDs = clips.map { it.id }.toSet()
        val removed = oldIDs - newIDs
        if (removed.isNotEmpty()) {
            var targets = TimelineSelection()
            for (id in removed) {
                val group = selection(of = id)
                targets = targets.copy(video = targets.video + group.video, audio = targets.audio + group.audio)
            }
            remove(targets)
        }
        for (content in clips) {
            val index = video.indexOfFirst { it.id == content.id }
            if (index < 0) continue
            val before = video[index]
            val clean = content.copy(openingTransition = null, transition = null)
            val derived = SourcePlaybackMap(clean)
            val timingChanged = derived != SourcePlaybackMap(before.content)
            val difference = if (timingChanged) derived.duration - before.mapping.duration else EditTime.zero
            video = video.updated(index) { it.copy(content = clean, mapping = if (timingChanged) derived else it.mapping) }
            if (difference != EditTime.zero) retimed += content.id
            val group = links.firstOrNull { content.id in it.members }
            if (difference != EditTime.zero && group != null) {
                val originalIncoming = transitions.firstOrNull { it.incoming == content.id }
                val originalOutgoing = transitions.firstOrNull { it.outgoing == content.id }
                var incoming = originalIncoming
                var outgoing = originalOutgoing
                fun timing(relation: TimelineTransition?, scale: Double = 1.0): TransitionTiming =
                    if (relation == null) TransitionTiming(null) else TransitionTiming(relation.settings, relation.factor * scale, relation.edge)
                fun cost(scale: Double) = timing(incoming, scale).rightCost + timing(outgoing, scale).leftCost
                if (cost(1.0) > derived.duration.seconds) {
                    if (clean.start != before.content.start || clean.end != before.content.end) {
                        // Trimming away the footage required by a transition
                        // removes that relation, as an ordinary trim promises.
                        val removedIDs = listOfNotNull(incoming?.id, outgoing?.id).toSet()
                        transitions = transitions.filter { it.id !in removedIDs }; incoming = null; outgoing = null
                    } else {
                        // Speed changes preserve the source footage. Fit both
                        // handles together before moving the next picture.
                        var lo = 0.0
                        var hi = 1.0
                        repeat(48) { val mid = (lo + hi) / 2; if (cost(mid) <= derived.duration.seconds) lo = mid else hi = mid }
                        incoming = incoming?.let { it.copy(factor = it.factor * lo) }
                        outgoing = outgoing?.let { it.copy(factor = it.factor * lo) }
                        for (updated in listOfNotNull(incoming, outgoing)) {
                            transitions = transitions.map { if (it.id == updated.id) updated else it }
                        }
                    }
                }
                if (originalIncoming != null) {
                    val t = timing(incoming)
                    val previous = video.firstOrNull { it.id == originalIncoming.outgoing }
                    val start = if (previous != null) previous.end - EditTime.of(t.leftEnd) + EditTime.of(t.rightStart)
                        else incoming?.let { maxOf(EditTime.zero, it.origin + EditTime.of(t.rightStart)) } ?: EditTime.zero
                    video = video.updated(index) { it.copy(start = start) }
                }
                var delta = video[index].end - before.end
                val nextItem = originalOutgoing?.let { relation -> video.firstOrNull { it.id == relation.incoming } }
                if (originalOutgoing != null && nextItem != null) {
                    val t = timing(outgoing)
                    delta = video[index].end - EditTime.of(t.leftEnd) + EditTime.of(t.rightStart) - nextItem.start
                }
                val soundIDs = audio.map { it.id }.toSet()
                val pictureIDs = video.map { it.id }.toSet()
                val avGroups = links.filter { !it.members.isDisjoint(soundIDs) && !it.members.isDisjoint(pictureIDs) }
                val downstream = if (group.members.isDisjoint(soundIDs)) group.members else avGroups.flatMap { it.members }.toSet()
                // A concurrent overlay is not downstream of the edited picture.
                // Explicit transition handles are the exception: their incoming
                // picture must follow the retimed outgoing end.
                val nextPicture = originalOutgoing?.incoming
                val isOverlay = previousItems.any {
                    it.id != content.id && it.id != originalIncoming?.outgoing && it.layer < before.layer && it.interval.contains(before.start)
                }
                val transitionChain = LinkedHashSet<UUID>()
                var following = nextPicture
                while (following != null && transitionChain.add(following)) {
                    val id: UUID = following
                    following = transitions.firstOrNull { it.outgoing == id }?.incoming
                }
                val moving = video.filter {
                    it.id != content.id && it.id in downstream && (if (isOverlay) it.id in transitionChain else (it.start >= before.end || it.id == nextPicture))
                }.map { it.id }.toSet()
                video = video.map { if (it.id in moving) it.copy(start = it.start + delta) else it }
                audio = audio.map { sound ->
                    val follows = sound.followsVideo
                    val shifts = sound.id in downstream && follows != content.id &&
                        (if (follows != null) follows in moving else (!isOverlay && sound.start >= before.end))
                    if (shifts) sound.copy(start = sound.start + delta) else sound
                }
                transitions = transitions.map {
                    if ((!isOverlay && it.origin >= before.end) || it.outgoing == content.id || it.incoming?.let { id -> id in moving } == true)
                        it.copy(origin = it.origin + delta) else it
                }
                generated = generated.map { region ->
                    val shifts = (!isOverlay && region.interval.start >= before.end) || transitions.any {
                        it.id == region.owner && (it.outgoing == content.id || it.incoming?.let { id -> id in moving } == true)
                    }
                    if (shifts) region.copy(interval = EditInterval(region.interval.start + delta, region.interval.end + delta)) else region
                }
            }
        }
        for ((index, clip) in clips.withIndex()) {
            if (clip.id in oldIDs) continue
            val position = if (index > 0) video.firstOrNull { it.id == clips[index - 1].id }?.end ?: duration else EditTime.zero
            insertTime(EditTime.of(clip.duration), position)
            val added = TrackTimeline(listOf(clip))
            val layer = (video.maxOfOrNull { it.layer } ?: -1) + 1
            video = video + added.video.map { it.copy(start = it.start + position, layer = layer) }
            audio = audio + added.audio.map { it.copy(start = it.start + position) }
            links = links + added.links
        }
        val ordering = HashMap<UUID, Int>()
        for ((offset, clip) in clips.withIndex()) ordering.putIfAbsent(clip.id, offset)
        video = video.sortedBy { ordering[it.id] ?: Int.MAX_VALUE }
        // Inspector removals are explicit. Merely rearranging an array is never
        // allowed to transfer a transition onto a different neighbor.
        for (clip in clips) {
            val old = previous.firstOrNull { it.id == clip.id } ?: continue
            if (old.openingTransition != null && clip.openingTransition == null) transitions = transitions.filterNot { it.incoming == clip.id && it.outgoing == null }
            if (old.transition != null && clip.transition == null) transitions = transitions.filterNot { it.outgoing == clip.id }
        }
        promoteNewOverlaps(retimed, previousItems)
        synchronizeFollowers(); retainPlacementAcrossNewOverlaps(retimed); pruneRelations()
    }

    fun includeCaptureBookmarks(source: SourceMedia) {
        val item = video.firstOrNull { it.content.sourceID == source.id && it.content.frozenFrame == null } ?: return
        for (event in source.events ?: emptyList()) {
            if (!(event.kind == "bookmark" && event.time >= 0 && event.time < source.duration)) continue
            if (bookmarks.any { it.id == event.id }) continue
            bookmarks = bookmarks + TimelineBookmark(event.id, anchor = BookmarkAnchor.source(item.occurrenceID, source.id, event.time))
        }
    }

    private fun snapPoints(excluding: Set<UUID>, extra: List<Double>): List<EditTime> {
        val stationaryMarks = resolvedBookmarks.filter { resolved ->
            val owner: UUID? = when (val anchor = resolved.bookmark.anchor) {
                is BookmarkAnchor.project -> null
                is BookmarkAnchor.source ->
                    video.firstOrNull { it.occurrenceID == anchor.occurrence && it.content.sourceID == anchor.source && it.mapping.local(anchor.time) != null }?.id
                        ?: audio.firstOrNull { it.occurrenceID == anchor.occurrence && it.sourceID == anchor.source && it.mapping.local(anchor.time) != null }?.id
                is BookmarkAnchor.local ->
                    video.firstOrNull { it.occurrenceID == anchor.occurrence && anchor.offset >= it.localOrigin && anchor.offset < it.localOrigin + it.mapping.duration }?.id
            }
            owner?.let { it !in excluding } ?: true
        }
        val edges = video.filter { it.id !in excluding }.flatMap { listOf(it.start.seconds, it.end.seconds) } +
            audio.filter { it.id !in excluding }.flatMap { listOf(it.start.seconds, it.end.seconds) } +
            stationaryMarks.map { it.time.seconds } + extra + listOf(0.0)
        return edges.map { EditTime.of(it) }.toSet().sorted()
    }

    fun snapTarget(time: Double, scale: Double, excluding: Set<UUID> = emptySet(), extra: List<Double> = emptyList()): Double? {
        val target = snapPoints(excluding, extra).minWithOrNull(compareBy { abs(it.seconds - time) }) ?: return null
        if (abs(target.seconds - time) * scale > 8) return null
        return target.seconds
    }

    fun snappedMove(selection: TimelineSelection, requested: EditTime, scale: Double, latch: TimelineMoveSnap? = null,
                    extra: List<Double> = emptyList()): TimelineMoveResult {
        val selected = expanded(selection)
        val intervals = video.filter { it.id in selected.video }.map { it.interval } + audio.filter { it.id in selected.audio }.map { it.interval }
        val start = intervals.minOfOrNull { it.start } ?: return TimelineMoveResult(EditTime.zero)
        val minimum = EditTime.zero - start
        val delta = maxOf(minimum, requested)
        val edges = intervals.flatMap { listOf(it.start, it.end) }.toSet().sorted()
        val targets = snapPoints(selected.ids, extra)
        val scale = max(0.000001, scale)
        var best: TimelineMoveSnap? = null
        var distance = Double.POSITIVE_INFINITY
        for (edge in edges) {
            val position = edge + delta
            var lower = 0
            var upper = targets.size
            while (lower < upper) {
                val mid = (lower + upper) / 2
                if (targets[mid] < position) lower = mid + 1 else upper = mid
            }
            for (index in listOf(lower - 1, lower)) {
                if (index !in targets.indices) continue
                val target = targets[index]
                if (target - edge < minimum) continue
                val gap = abs((edge + delta - target).seconds) * scale
                if (gap <= 10 && gap < distance) { best = TimelineMoveSnap(target, edge); distance = gap }
            }
        }
        if (latch != null && latch.edge in edges && latch.target in targets && latch.target - latch.edge >= minimum) {
            val heldDistance = abs((latch.edge + delta - latch.target).seconds) * scale
            // Hysteresis must not pull past another cut under the finger.
            if (heldDistance <= 18 && (best == null || heldDistance <= distance + 1)) {
                return TimelineMoveResult(latch.target - latch.edge, latch)
            }
        }
        return TimelineMoveResult(best?.let { it.target - it.edge } ?: delta, best)
    }

    fun snap(time: Double, scale: Double, excluding: Set<UUID> = emptySet(), extra: List<Double> = emptyList()): Double =
        snapTarget(time, scale, excluding, extra) ?: time

    fun copied(targets: TimelineSelection): TimelineClipboard {
        val scoped = scoped(targets)
        return scoped.timeline.clipboardFragment(scoped.selection, keepGaps = targets.range != null)
    }

    /** Pastes an independent copy of [clipboard]: every item, link, tool, word and mark gets a fresh identity. */
    fun paste(clipboard: TimelineClipboard, at: EditTime, target: TimelinePasteTarget? = null): TimelineSelection {
        val addition = clipboard.timeline.copy()
        if (clipboard.isEmpty) return TimelineSelection()
        val identifiers = HashMap<UUID, UUID>()
        fun fresh(old: UUID): UUID = identifiers.getOrPut(old) { newUUID() }
        val position = preparePaste(clipboard, at, target)
        val front = (video.maxOfOrNull { it.layer } ?: 0) + 1
        val layers = addition.video.map { it.layer }.toSet().sorted()
        val newLayers = layers.withIndex().associate { it.value to front + it.index }
        addition.video = addition.video.map { item ->
            val content = item.content.copy(id = fresh(item.id)).withFreshToolIDs(::fresh)
            item.copy(content = content, occurrenceID = fresh(item.occurrenceID), start = item.start + position, layer = newLayers.getValue(item.layer))
        }
        addition.audio = addition.audio.map { item ->
            item.copy(id = fresh(item.id), occurrenceID = fresh(item.occurrenceID), followsVideo = item.followsVideo?.let(::fresh),
                start = item.start + position,
                subtitleWords = item.subtitleWords?.map { it.copy(id = fresh(it.id), phraseID = fresh(it.phraseID)) },
                enhancements = item.enhancements.map { it.copy(id = fresh(it.id)) },
                censorRegions = item.censorRegions?.map { it.copy(id = fresh(it.id)) })
        }
        addition.links = addition.links.map { TimelineLink(members = it.members.map(::fresh).toSet()) }
        addition.transitions = addition.transitions.map {
            it.copy(settings = it.settings.copy(id = fresh(it.id)), outgoing = it.outgoing?.let(::fresh), incoming = it.incoming?.let(::fresh),
                origin = it.origin + position)
        }
        addition.placements = addition.placements.map { region ->
            VideoPlacementRegion(participants = region.participants.map(::fresh).toSet(), videos = region.videos.mapKeys { fresh(it.key) })
        }
        addition.bookmarks = addition.bookmarks.map { mark ->
            mark.copy(id = newUUID(), anchor = when (val anchor = mark.anchor) {
                is BookmarkAnchor.source -> BookmarkAnchor.source(fresh(anchor.occurrence), anchor.source, anchor.time)
                is BookmarkAnchor.local -> BookmarkAnchor.local(fresh(anchor.occurrence), anchor.offset)
                is BookmarkAnchor.project -> BookmarkAnchor.project(anchor.time + position)
            })
        }
        if (subtitles == null) subtitles = addition.subtitles?.copy(timingFingerprint = "")
        video = (video + addition.video).sortedBy { it.start }
        audio = audio + addition.audio; links = links + addition.links
        transitions = transitions + addition.transitions; placements = placements + addition.placements; bookmarks = bookmarks + addition.bookmarks
        pruneRelations()
        return addition.entireSelection()
    }

    fun gap(at: EditTime): EditInterval? {
        val time = at
        val occupied = union(video.map { it.interval } + audio.map { it.interval } + generated.map { it.interval })
        if (!(time >= EditTime.zero && time < duration && occupied.none { it.contains(time) })) return null
        val start = occupied.filter { it.end <= time }.maxOfOrNull { it.end } ?: EditTime.zero
        val end = occupied.filter { it.start > time }.minOfOrNull { it.start } ?: duration
        return if (end > start) EditInterval(start, end) else null
    }

    fun closeGap(at: EditTime) {
        val gap = gap(at) ?: return
        video = video.map { if (it.start >= gap.end) it.copy(start = it.start - gap.duration) else it }
        audio = audio.map { if (it.start >= gap.end) it.copy(start = it.start - gap.duration) else it }
        transitions = transitions.map { if (it.origin >= gap.end) it.copy(origin = it.origin - gap.duration) else it }
        bookmarks = bookmarks.map { mark ->
            val anchor = mark.anchor
            if (anchor is BookmarkAnchor.project && anchor.time >= gap.start) mark.copy(anchor = BookmarkAnchor.project(maxOf(gap.start, anchor.time - gap.duration))) else mark
        }
        pruneRelations()
    }

    fun syncOffset(audioID: UUID): EditTime? {
        val sound = audio.firstOrNull { it.id == audioID } ?: return null
        val sourceTime = sound.mapping.runs.firstOrNull()?.sourceStart ?: return null
        val picture = video.firstOrNull { it.occurrenceID == sound.occurrenceID && it.mapping.local(sourceTime) != null } ?: return null
        val local = picture.mapping.local(sourceTime) ?: return null
        return sound.start - picture.start - local
    }

    fun restoreSync(audioID: UUID) {
        val offset = syncOffset(audioID) ?: return
        val index = audio.indexOfFirst { it.id == audioID }
        if (index < 0) return
        audio = audio.updated(index) { it.copy(start = maxOf(EditTime.zero, it.start - offset)) }
        pruneRelations()
    }

    /// Reveal previously trimmed original material. Other items keep their
    /// positions: revealing into an occupied interval creates an editable overlap.
    fun extend(selection: TimelineSelection, left: Boolean, by: EditTime, sources: List<SourceMedia>) {
        val target = expanded(selection)
        var amount = by
        fun available(map: SourcePlaybackMap, start: EditTime, source: UUID): EditTime {
            val run = (if (left) map.runs.firstOrNull() else map.runs.lastOrNull()) ?: return EditTime.zero
            if (run.isHold) return EditTime.zero
            val duration = sources.firstOrNull { it.id == source }?.duration ?: return EditTime.zero
            val rate = (run.sourceEnd - run.sourceStart) / run.duration.seconds
            return if (left) minOf(start, EditTime.of(run.sourceStart / rate)) else EditTime.of(max(0.0, duration - run.sourceEnd) / rate)
        }
        fun revealMap(item: VideoTimelineItem): SourcePlaybackMap {
            if (item.content.frozenFrame != null) return SourcePlaybackMap(emptyList<PlaybackRun>())
            val duration = sources.firstOrNull { it.id == item.content.sourceID }?.duration ?: return SourcePlaybackMap(emptyList<PlaybackRun>())
            var clip = item.content
            clip = clip.copy(start = if (left) 0.0 else item.mapping.runs.lastOrNull()?.sourceEnd ?: clip.end)
            clip = clip.copy(end = if (left) item.mapping.runs.firstOrNull()?.sourceStart ?: clip.start else duration)
            return if (clip.end > clip.start) SourcePlaybackMap(clip) else SourcePlaybackMap(emptyList<PlaybackRun>())
        }
        val revealed = video.filter { it.id in target.video }.associate { it.id to revealMap(it) }
        for (item in video) {
            if (item.id !in target.video) continue
            val map = revealed.getValue(item.id)
            amount = minOf(amount, if (left) minOf(item.start, map.duration) else map.duration)
        }
        for (item in audio) {
            if (item.id in target.audio && !(item.followsVideo?.let { it in target.video } ?: false)) {
                amount = minOf(amount, available(item.mapping, item.start, item.sourceID))
            }
        }
        if (amount <= EditTime.zero) return
        val previous = video
        video = video.map { item ->
            if (item.id !in target.video) return@map item
            val map = revealed.getValue(item.id)
            val part = map.sliced(if (left) EditInterval(map.duration - amount, map.duration) else EditInterval(EditTime.zero, amount))
            val runs = if (left) part.runs + item.mapping.runs else item.mapping.runs + part.runs
            var next = item.copy(mapping = SourcePlaybackMap(runs))
            if (left) next = next.copy(start = next.start - amount, localOrigin = next.localOrigin - amount)
            next.copy(content = next.content.copy(start = runs.first().sourceStart, end = runs.last().sourceEnd))
        }
        audio = audio.map { item ->
            if (item.id !in target.audio) return@map item
            var next = item
            val fadeIn = item.fadeIn
            val fadeOut = item.fadeOut
            if (left && fadeIn != null) next = next.copy(fadeIn = fadeIn.copy(elapsed = max(0.0, fadeIn.elapsed - amount.seconds)))
            else if (!left && fadeOut != null) next = next.copy(fadeOut = fadeOut.copy(elapsed = max(0.0, fadeOut.elapsed - amount.seconds)))
            val follows = item.followsVideo?.let { it in target.video } ?: false
            if (!follows) {
                val runs = next.mapping.runs
                val run = if (left) runs.first() else runs.last()
                val change = amount.seconds * (run.sourceEnd - run.sourceStart) / run.duration.seconds
                val added = PlaybackRun(amount, if (left) max(0.0, run.sourceStart - change) else run.sourceEnd,
                    if (left) run.sourceStart else run.sourceEnd + change, silent = false)
                next = next.copy(mapping = SourcePlaybackMap(if (left) listOf(added) + runs else runs + added))
            }
            if (left) {
                next = next.copy(start = next.start - amount, localOrigin = next.localOrigin - amount)
                if (!follows) {
                    next = next.copy(enhancements = next.enhancements.map {
                        it.copy(interval = EditInterval(it.interval.start + amount, it.interval.end + amount))
                    }).withCensors(next.censors.map { region ->
                        region.copy(interval = EditInterval(region.interval.start + amount, region.interval.end + amount),
                            phaseOrigin = region.phaseOrigin + amount,
                            envelopeInterval = region.envelopeInterval?.let { EditInterval(it.start + amount, it.end + amount) })
                    })
                }
            }
            next
        }
        synchronizeFollowers()
        promoteNewOverlaps(target.video, previous)
        retainPlacementAcrossNewOverlaps(target.video)
        pruneRelations()
    }

    companion object {
        /** Swift `init(sequence:)`. */
        fun sequence(edit: EditState): TrackTimeline = edit.timeline.copy()

        fun counterpart(id: UUID): UUID = UUID(id.mostSignificantBits, id.leastSignificantBits xor 0x80L)

        /// A cut can coincide across several layers. Match both halves of the
        /// same occurrence before assigning the remaining neighbors in lane order.
        fun neighbors(outgoing: List<CutNeighbor>, incoming: List<CutNeighbor>): List<Pair<UUID, UUID>> {
            val available = incoming.toMutableList()
            val unmatched = mutableListOf<CutNeighbor>()
            val pairs = mutableListOf<Pair<UUID, UUID>>()
            for (part in outgoing) {
                val i = available.indexOfFirst { it.occurrence == part.occurrence }
                if (i >= 0) pairs += part.id to available.removeAt(i).id else unmatched += part
            }
            pairs += unmatched.zip(available).map { (a, b) -> a.id to b.id }
            return pairs
        }

        fun backToFront(a: VideoTimelineItem, b: VideoTimelineItem): Boolean = backToFrontOrder.compare(a, b) < 0
        val backToFrontComparator: Comparator<VideoTimelineItem> get() = backToFrontOrder

        fun union(ranges: List<EditInterval>): List<EditInterval> {
            val result = mutableListOf<EditInterval>()
            for (range in ranges.sortedBy { it.start }) {
                if (range.isEmpty) continue
                val last = result.lastOrNull()
                if (last != null && range.start <= last.end) result[result.size - 1] = last.copy(end = maxOf(last.end, range.end))
                else result += range
            }
            return result
        }
    }
}

class TimelineClipboard(val timeline: TrackTimeline, val insertsTime: Boolean) {
    val isEmpty: Boolean get() = timeline.video.isEmpty() && timeline.audio.isEmpty()
}

/// Audio overlays do not ripple the picture or any existing sound.
fun TrackTimeline.insertAudio(source: SourceMedia, at: EditTime): UUID {
    val item = AudioTimelineItem(occurrenceID = newUUID(), sourceID = source.id, start = maxOf(EditTime.zero, at),
        mapping = SourcePlaybackMap(listOf(PlaybackRun(EditTime.of(source.duration), 0.0, source.duration))))
    audio = audio + item
    return item.id
}

/// One viewport calculation for pinch, fit, presets and hit testing. At fit,
/// both ends are visible and the playhead moves independently of the viewport.
data class TimelineViewport(val width: Double, val duration: Double, val requestedScale: Double) {
    val minimumScale: Double get() = max(0.000001, (width - 2 * edgeInset) / max(1.0, duration))
    val scale: Double get() = max(minimumScale, min(720.0, requestedScale))
    val fits: Boolean get() = duration * scale <= width - 2 * edgeInset + 0.5
    val inset: Double get() = if (fits) edgeInset else width / 2
    val contentWidth: Double get() = max(width, duration * scale + 2 * inset)
    fun x(time: Double, offset: Double): Double = time * scale + inset - offset
    fun time(x: Double, offset: Double): Double = min(duration, max(0.0, (x + offset - inset) / scale))

    companion object {
        // At whole-project fit, each edge has room for its own 44-point add target.
        const val edgeInset = 48.0
    }
}

// Shared by drawing, hit testing, accessibility and edit-invariant tests.
data class TimelineLane(
    val id: UUID,
    /// Visible card only. Transition handles remain in contentInterval/mapping.
    val interval: EditInterval,
    val contentInterval: EditInterval,
    val source: UUID,
    val mapping: SourcePlaybackMap,
    val row: Int,
    val number: Int,
    val audio: Boolean,
) {
    val trimsLeft: Boolean get() = interval.start == contentInterval.start
    val trimsRight: Boolean get() = interval.end == contentInterval.end
}

private fun displayIntervals(timeline: TrackTimeline, layout: TimelineLayout?): Map<UUID, EditInterval> {
    val visible = LinkedHashMap<UUID, EditInterval>()
    for (item in timeline.video) visible[item.id] = item.interval
    for (item in timeline.audio) visible[item.id] = item.interval
    // A transition is presented as a join, not a second compositing layer.
    // Only fold its real overlap; generated black/freeze gaps keep their time.
    fun fold(a: UUID, b: UUID, time: EditTime) {
        val left = visible[a] ?: return
        val right = visible[b] ?: return
        val overlap = left.intersection(right) ?: return
        val cut = minOf(overlap.end, maxOf(overlap.start, time))
        if (!(cut > left.start && cut < right.end)) return
        visible[a] = left.copy(end = cut); visible[b] = right.copy(start = cut)
    }
    if (layout != null) {
        for (join in layout.joins) {
            if (join.transition == null || join.edge != 0) continue
            val a = timeline.video.firstOrNull { it.id == join.outgoingID } ?: continue
            val b = timeline.video.firstOrNull { it.id == join.incomingID } ?: continue
            fold(a.id, b.id, EditTime.of(join.time))
            // Detached or independently trimmed sound remains an honest layer.
            fun followers(picture: VideoTimelineItem) = timeline.audio.filter { sound ->
                sound.followsVideo == picture.id && sound.interval == picture.interval &&
                    timeline.links.any { picture.id in it.members && sound.id in it.members }
            }
            val first = followers(a)
            val second = followers(b)
            if (first.size == 1 && second.size == 1) fold(first[0].id, second[0].id, EditTime.of(join.time))
        }
    }
    return visible
}

fun timelineLanes(timeline: TrackTimeline, moving: TimelineSelection? = null): List<TimelineLane> {
    val result = mutableListOf<TimelineLane>()
    val layout = if (moving == null && timeline.transitions.isNotEmpty()) TimelineLayout(timeline) else null
    val visible = displayIntervals(timeline, layout)
    val videoNumbers = timeline.video.withIndex().associate { it.value.id to it.index + 1 }
    val audioNumbers = timeline.audio.withIndex().associate { it.value.id to it.index + 1 }
    val pictures = timeline.video.sortedWith { a, b -> TrackTimeline.backToFrontComparator.compare(b, a) }
    val videoLayers = timeline.video.associate { it.id to it.layer }
    val linkedLayers = HashMap<UUID, Int>()
    for (group in timeline.links) {
        val layer = group.members.mapNotNull { videoLayers[it] }.maxOrNull() ?: continue
        for (id in group.members) linkedLayers[id] = layer
    }
    val sounds = timeline.audio.sortedWith { a, b ->
        val aLayer = linkedLayers[a.id] ?: -1
        val bLayer = linkedLayers[b.id] ?: -1
        when {
            aLayer != bLayer -> bLayer.compareTo(aLayer)
            a.start == b.start -> a.id.uuidString.compareTo(b.id.uuidString)
            else -> a.start.compareTo(b.start)
        }
    }
    val transitionPairs = timeline.transitions.mapNotNull { relation ->
        val a = relation.outgoing ?: return@mapNotNull null
        val b = relation.incoming ?: return@mapNotNull null
        setOf(a, b)
    }.toSet()
    fun pack(items: List<TimelineLane>, base: Int): Int {
        val rows = mutableListOf<MutableList<EditInterval>>()
        val placed = mutableListOf<Triple<UUID, EditInterval, Int>>()
        for (item in items) {
            // Ordinary overlays retain their front/back order. Transition
            // partners blend, rather than cover, so reuse alternate rows for a
            // long chain of crossfades instead of growing one row per cut.
            val minimum = if (item.audio) 0 else (placed.filter {
                it.second.intersection(item.interval) != null && setOf(it.first, item.id) !in transitionPairs
            }.maxOfOrNull { it.third } ?: -1) + 1
            val row = rows.indices.firstOrNull { index -> index >= minimum && rows[index].none { it.intersection(item.interval) != null } } ?: rows.size
            if (row == rows.size) rows += mutableListOf<EditInterval>()
            rows[row] += item.interval
            placed += Triple(item.id, item.interval, row)
            result += item.copy(row = base + row)
        }
        return rows.size
    }
    val video = pictures.map {
        TimelineLane(it.id, visible.getValue(it.id), it.interval, it.content.sourceID, it.mapping, 0, videoNumbers.getValue(it.id), false)
    }
    val audio = sounds.map {
        TimelineLane(it.id, visible.getValue(it.id), it.interval, it.sourceID, it.mapping, 0, audioNumbers.getValue(it.id), true)
    }
    var base = 0
    for (items in listOf(video, audio)) {
        if (items.firstOrNull()?.audio == true) base = max(1, base)
        if (moving != null) {
            // Give the moving group dedicated rows until Done/Cancel. Both the
            // group and stationary cards keep their vertical positions as edges
            // cross. Empty space is intentional, like lifting a clip for placing.
            base += pack(items.filter { it.id in moving.ids }, base)
            base += pack(items.filter { it.id !in moving.ids }, base)
        } else base += pack(items, base)
    }
    return result
}
