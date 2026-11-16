// Port of YouRec/Transitions.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Transition settings (`ClipTransition`, Codable with the Swift field names and
// raw values), their timing (`TransitionTiming`), the sequence layout of joins
// (`TimelineLayout`), and `settingTransition` / `restoringStraightCut`.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// A join belongs to these exact neighbors. Moving or deleting a clip must never
// silently apply its transition to unrelated footage. Source trims stay intact.
@Serializable
enum class TransitionStyle(val rawValue: String) {
    @SerialName("dissolve") dissolve("dissolve"),
    @SerialName("fadeBlack") fadeColor("fadeBlack"), // Preserve existing project documents.
    @SerialName("wipe") wipe("wipe"),
    @SerialName("split") split("split"),
    @SerialName("hitchcock") hitchcock("hitchcock"),
    @SerialName("whip") whip("whip"),
    @SerialName("flashCut") flashCut("flashCut"),
    @SerialName("still") still("still"),
    @SerialName("defocus") defocus("defocus");

    val id: String get() = rawValue
    val title: String
        get() = when (this) {
            dissolve -> "Dissolve"; fadeColor -> "Fade through color"; wipe -> "Wipe"; split -> "Split screen"
            hitchcock -> "Hitchcock"; whip -> "Whip pan"; flashCut -> "Flash Cut"; still -> "still"; defocus -> "Defocus"
        }
    /** SF Symbol name; Android icons are chosen in TransitionEditor.kt. */
    val symbol: String
        get() = when (this) {
            dissolve -> "square.on.square"; fadeColor -> "circle.lefthalf.filled"; wipe -> "rectangle.lefthalf.inset.filled"
            split -> "rectangle.split.2x1"; hitchcock -> "circle.lefthalf.striped.horizontal"; whip -> "wind"
            flashCut -> "bolt.fill"; still -> "pause.rectangle"; defocus -> "camera.aperture"
        }
    val supportsHold: Boolean get() = this != flashCut && this != still

    companion object {
        val allCases: List<TransitionStyle> get() = entries
    }
}

@Serializable
enum class TransitionAxis {
    horizontal, vertical;

    val title: String get() = if (this == horizontal) "Left / right" else "Top / bottom"
}

@Serializable
enum class TransitionAudio {
    crossfade, first, second;

    val title: String
        get() = when (this) { crossfade -> "Crossfade"; first -> "First clip"; second -> "Second clip" }
}

@Serializable
data class TransitionFraming(
    val x: Double = 0.5,
    val y: Double = 0.5, // top-to-bottom coordinates, matching touch input
    val zoom: Double = 1.0,
) {
    fun crop(source: CGSize, viewport: CGSize): CGRect = CropSettings(x = x, y = y, zoom = zoom).rect(source, viewport)
}

@Serializable
data class TransitionColor(val red: Double, val green: Double, val blue: Double) {
    val valid: Boolean get() = listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }

    companion object {
        val black = TransitionColor(0.0, 0.0, 0.0)
    }
}

@Serializable
data class ClipTransition(
    val id: UUID = newUUID(),
    val nextClipID: UUID? = null,
    val style: TransitionStyle = TransitionStyle.dissolve,
    val duration: Double = 0.5,
    val axis: TransitionAxis = TransitionAxis.horizontal,
    val divider: Double = 0.5,
    val reverse: Boolean = false,
    val audio: TransitionAudio = TransitionAudio.crossfade,
    val first: TransitionFraming = TransitionFraming(),
    val second: TransitionFraming = TransitionFraming(),
    val fadeColor: TransitionColor? = null,
    val hold: Double? = null,
    val audioOffset: Double? = null,
    val audioBlend: Double? = null,
    val wipeAngle: Double? = null, // Degrees clockwise in screen coordinates.
    val hardEdge: Boolean? = null,
    val highlightsFirst: Boolean? = null,
    val flashCount: Int? = null,
    val flashAcceleration: Double? = null,
    val stillDissolve: Double? = null, // nil/0 = cut; duration is the freeze length.
    val blurAmount: Double? = null,
) {
    val resolvedFadeColor: TransitionColor get() = fadeColor ?: TransitionColor.black
    val holdDuration: Double get() = if (style.supportsHold) hold ?: 0.0 else 0.0
    val customAudio: Boolean get() = audioOffset != null || audioBlend != null
    val defaultAudioBlend: Double
        get() = if (style == TransitionStyle.still) 0.0 else duration + (if (style == TransitionStyle.fadeColor) 0.0 else holdDuration)
    val angle: Double get() = (wipeAngle ?: (if (axis == TransitionAxis.horizontal) 0.0 else 90.0)) + (if (reverse) 180.0 else 0.0)
    val newFeatures: Boolean
        get() = style !in listOf(TransitionStyle.dissolve, TransitionStyle.fadeColor, TransitionStyle.wipe, TransitionStyle.split) ||
            resolvedFadeColor != TransitionColor.black || hold != null || audioOffset != null || audioBlend != null || wipeAngle != null || hardEdge == true

    fun flashFrames(duration: Double, framesPerSecond: Double = 30.0): List<Int> {
        val frames = max(1, swiftRound(duration * framesPerSecond).toInt())
        val count = min(flashCount ?: 3, max(0, (frames - 1) / 2))
        if (count <= 0) return emptyList()
        val result = mutableListOf<Int>()
        for (index in 1..count) {
            val u = index.toDouble() / (count + 1)
            val desired = swiftRound((1 - (1 - u).pow(flashAcceleration ?: 2.0)) * (frames - 1)).toInt()
            result += min(frames - 2 - 2 * (count - index), max((result.lastOrNull() ?: -1) + 2, desired))
        }
        return result
    }

    val valid: Boolean
        get() = duration.isFinite() && duration > 0 && duration <= maximumDuration && divider.isFinite() && divider in 0.15..0.85 && resolvedFadeColor.valid &&
            listOf(first, second).all { it.x.isFinite() && it.y.isFinite() && it.zoom.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 && it.zoom in 1.0..4.0 } &&
            listOfNotNull(hold, audioBlend, stillDissolve).all { it.isFinite() && it in 0.0..maximumDuration } &&
            (audioOffset?.let { it.isFinite() && abs(it) <= maximumDuration } ?: true) &&
            (wipeAngle?.let { it.isFinite() && it in -360.0..360.0 } ?: true) &&
            (flashCount?.let { it in 1..20 } ?: true) &&
            (flashAcceleration?.let { it.isFinite() && it in 0.25..4.0 } ?: true) &&
            (blurAmount?.let { it.isFinite() && it in 0.0..1.0 } ?: true)

    companion object {
        // Match the document's maximum running time; usable duration is limited
        // by the actual neighboring footage, not an arbitrary short preset cap.
        const val maximumDuration = 604800.0
    }
}

// Relative times use the beginning of the picture effect as zero. Both tracks
// keep the same media clock; the union of picture and sound requirements decides
// how far the next clip overlaps (or follows) the previous one.
class TransitionTiming(settings: ClipTransition?, factor: Double = 1.0, edge: Int = 0) {
    var style: TransitionStyle? = null; private set
    var motion = 0.0; private set
    var hold = 0.0; private set
    var videoDuration = 0.0; private set
    var audioIn: ClosedFloatingPointRange<Double> = 0.0..0.0; private set
    var audioOut: ClosedFloatingPointRange<Double> = 0.0..0.0; private set
    var leftEnd = 0.0; private set
    var rightStart = 0.0; private set
    var lower = 0.0; private set
    var upper = 0.0; private set
    var cut = 0.0; private set
    var leftCost = 0.0; private set
    var rightCost = 0.0; private set
    val overlap: Double get() = leftEnd - rightStart

    init {
        if (settings != null) {
            style = settings.style
            val still = settings.style == TransitionStyle.still
            motion = (if (still) settings.stillDissolve ?: 0.0 else settings.duration) * factor
            // Solid gaps and silent freezes need no source handles. Custom audio
            // can make the neighboring source run underneath them, so those holds
            // must then participate in fitting, just like moving-picture holds.
            hold = if (still) settings.duration * (if (settings.customAudio && edge != 1) factor else 1.0)
                else settings.holdDuration * (if (settings.style == TransitionStyle.fadeColor && edge == 0 && !settings.customAudio) 1.0 else factor)
            if (!still && motion + hold < TimelineEditing.frame - 0.000001) { motion = 0.0; hold = 0.0 }
            videoDuration = motion + hold
            var aNeed = videoDuration
            var bNeed = 0.0
            if (settings.style == TransitionStyle.fadeColor && edge == 0) { aNeed = motion / 2; bNeed = motion / 2 + hold }
            if (still) { aNeed = 0.0; bNeed = hold }
            var offset = (settings.audioOffset ?: 0.0) * factor
            var blend = (settings.audioBlend ?: (if (edge != 0 && settings.style == TransitionStyle.fadeColor) settings.duration + settings.holdDuration
                else settings.defaultAudioBlend)) * factor
            if (settings.style == TransitionStyle.split && settings.audioOffset == null && settings.audioBlend == null) {
                if (settings.audio == TransitionAudio.first) { offset = max(0.0, videoDuration - 0.008); blend = min(0.008, videoDuration) }
                if (settings.audio == TransitionAudio.second) blend = min(0.008, videoDuration)
            }
            if (edge == -1) {
                val begin = offset + (if (still && !settings.customAudio) hold else 0.0)
                audioIn = begin..(begin + blend)
                rightStart = min(bNeed, audioIn.start)
                lower = min(0.0, rightStart); upper = max(videoDuration, audioIn.endInclusive)
                rightCost = upper - rightStart
            } else if (edge == 1) {
                audioOut = offset..(offset + blend)
                leftEnd = max(aNeed, audioOut.endInclusive)
                lower = min(0.0, audioOut.start); upper = max(videoDuration, leftEnd)
                leftCost = leftEnd - lower
            } else {
                if ((settings.style == TransitionStyle.fadeColor || still) && !settings.customAudio) {
                    audioOut = offset..(offset + blend / 2)
                    audioIn = (offset + blend / 2 + hold)..(offset + blend + hold)
                } else { audioIn = offset..(offset + blend); audioOut = audioIn }
                leftEnd = max(aNeed, audioOut.endInclusive)
                rightStart = min(bNeed, audioIn.start)
                lower = minOf(0.0, rightStart, audioOut.start)
                upper = maxOf(videoDuration, leftEnd, audioIn.endInclusive)
                leftCost = leftEnd - lower; rightCost = upper - rightStart
            }
            cut = if (still) hold else videoDuration / 2
        }
    }

    fun progress(elapsed: Double): Double {
        if (style == TransitionStyle.still) return if (motion > 0) min(1.0, max(0.0, (elapsed - hold) / motion)) else if (elapsed < hold) 0.0 else 1.0
        if (motion <= 0) return if (elapsed < videoDuration / 2) 0.0 else 1.0
        if (elapsed < motion / 2) return min(1.0, max(0.0, elapsed / motion))
        if (elapsed <= motion / 2 + hold) return 0.5
        return min(1.0, max(0.0, (elapsed - hold) / motion))
    }

    companion object {
        /** The timing of a stored relation: its edge follows the participants that exist. */
        fun of(relation: TimelineTransition): TransitionTiming = TransitionTiming(relation.settings, relation.factor, relation.edge)
    }
}


data class TimelinePlacement(
    val clip: TimelineClip,
    val index: Int,
    val start: Double,
    val incoming: Double,
    val outgoing: Double,
    val lower: Double,
    val upper: Double,
) {
    val end: Double get() = start + clip.duration
    val range: ClosedFloatingPointRange<Double> get() = lower..max(lower, upper)
}

data class TransitionJoin(
    val index: Int,
    val time: Double,
    val range: ClosedFloatingPointRange<Double>,
    val maximum: Double,
    val transition: ClipTransition?,
    val origin: Double = 0.0,
    val timing: TransitionTiming = TransitionTiming(null),
    val factor: Double = 1.0,
    val selectable: Boolean = true,
    val edge: Int = 0, // -1 opening, +1 closing; independent of rounded timestamps.
    val outgoingID: UUID? = null,
    val incomingID: UUID? = null,
) {
    val videoRange: ClosedFloatingPointRange<Double> get() = origin..(origin + timing.videoDuration)
    val audioIn: ClosedFloatingPointRange<Double> get() = (origin + timing.audioIn.start)..(origin + timing.audioIn.endInclusive)
    val audioOut: ClosedFloatingPointRange<Double> get() = (origin + timing.audioOut.start)..(origin + timing.audioOut.endInclusive)
    val id: Int get() = index
    val title: String get() = if (index == 0) "Start" else "Join $index"
}

class TimelineLayout private constructor(
    val placements: List<TimelinePlacement>,
    val joins: List<TransitionJoin>,
    val duration: Double,
) {
    fun boundary(time: Double): TransitionJoin? {
        val matches = joins.filter { it.selectable && abs(it.time - time) < 0.0001 }
        return matches.firstOrNull { it.edge == 0 } ?: matches.firstOrNull()
    }

    fun placement(time: Double): TimelinePlacement? =
        placements.firstOrNull { time >= it.lower && time < it.upper }
            ?: if (abs(time - duration) < 0.00001 && placements.lastOrNull()?.upper == duration) placements.last() else null

    fun transition(time: Double): TransitionJoin? =
        joins.firstOrNull { it.transition != null && time >= it.range.start && time < it.range.endInclusive }

    fun joins(placement: TimelinePlacement): Pair<TransitionJoin, TransitionJoin> {
        fun join(incoming: Boolean): TransitionJoin {
            val candidates = joins.filter { (if (incoming) it.incomingID else it.outgoingID) == placement.clip.id }
            candidates.firstOrNull { it.transition != null }?.let { return it }
            candidates.firstOrNull()?.let { return it }
            val time = if (incoming) placement.start else placement.end
            return TransitionJoin(placement.index + (if (incoming) 0 else 1), time, time..time, 0.0, null)
        }
        return join(true) to join(false)
    }

    companion object {
        /** Swift `TimelineLayout(clips:)`: clips played in sequence with their clip transitions. */
        operator fun invoke(clips: List<TimelineClip>): TimelineLayout {
            if (clips.isEmpty()) return TimelineLayout(emptyList(), emptyList(), 0.0)
            val count = clips.size
            val settings = arrayOfNulls<ClipTransition>(count + 1)
            val factors = DoubleArray(count + 1) { 1.0 }
            fun edge(index: Int) = if (index == 0) -1 else if (index == count) 1 else 0
            for (index in 0..count) {
                val right = if (index < count) clips[index] else null
                val value = if (index == 0) right?.openingTransition else clips[index - 1].transition
                if (value != null && value.valid && (index == 0 || value.nextClipID == right?.id)) settings[index] = value
            }
            fun timing(index: Int, scale: Double = 1.0) = TransitionTiming(settings[index], factors[index] * scale, edge(index))
            // Adjacent footprints cannot share source time. This also prevents two
            // nonadjacent clips (or their freeze extensions) colliding on one track.
            for (index in clips.indices) {
                if (timing(index).rightCost + timing(index + 1).leftCost > clips[index].duration + 0.00000001) {
                    var lo = 0.0
                    var hi = 1.0
                    repeat(48) {
                        val mid = (lo + hi) / 2
                        if (timing(index, mid).rightCost + timing(index + 1, mid).leftCost <= clips[index].duration) lo = mid else hi = mid
                    }
                    factors[index] *= lo; factors[index + 1] *= lo
                }
            }
            val times = (0..count).map { timing(it) }
            val starts = DoubleArray(count)
            val origins = DoubleArray(count + 1)
            origins[0] = -min(0.0, times[0].rightStart)
            starts[0] = origins[0] + times[0].rightStart
            for (index in 1 until count) {
                origins[index] = starts[index - 1] + clips[index - 1].duration - times[index].leftEnd
                starts[index] = origins[index] + times[index].rightStart
            }
            val lastEnd = starts[count - 1] + clips[count - 1].duration
            origins[count] = lastEnd - times[count].leftEnd
            val duration = max(lastEnd, origins[count] + times[count].upper)
            val builtJoins = (0..count).map { index ->
                val t = times[index]
                val origin = origins[index]
                val leftSpace = if (index > 0) max(0.0, clips[index - 1].duration - times[index - 1].rightCost) else Double.POSITIVE_INFINITY
                val rightSpace = if (index < count) max(0.0, clips[index].duration - times[index + 1].leftCost) else Double.POSITIVE_INFINITY
                // The primary duration slider remains useful even when custom audio
                // requires proportional fitting; show actual timing in the summary.
                val cost = if (settings[index]?.style == TransitionStyle.fadeColor && edge(index) == 0) 0.5 else 1.0
                val maximum = if (settings[index]?.style == TransitionStyle.still) ClipTransition.maximumDuration
                    else max(0.0, minOf(ClipTransition.maximumDuration, leftSpace / cost, rightSpace / cost))
                val point = if (index == 0) 0.0 else if (index == count) duration else origin + t.cut
                val range = max(0.0, origin + t.lower)..max(0.0, origin + t.upper)
                TransitionJoin(index, point, range, maximum, settings[index], origin, t, factors[index], edge = edge(index),
                    outgoingID = if (index > 0) clips[index - 1].id else null, incomingID = if (index < count) clips[index].id else null)
            }
            val placements = clips.indices.map { index ->
                TimelinePlacement(clips[index], index, starts[index], max(0.0, times[index].overlap), max(0.0, times[index + 1].overlap),
                    builtJoins[index].time, builtJoins[index + 1].time)
            }
            return TimelineLayout(placements, builtJoins, duration)
        }

        /** Swift `TimelineLayout(timeline:)`: joins derived from actual picture time order. */
        operator fun invoke(timeline: TrackTimeline): TimelineLayout {
            val pictures = timeline.chronologicalVideo
            val clips = timeline.projectedClips
            val duration = timeline.duration.seconds
            if (pictures.isEmpty()) return TimelineLayout(emptyList(), emptyList(), duration)
            val built = mutableListOf<TransitionJoin>()
            val last = pictures.maxWith(compareBy { it.end })
            for (index in 0..pictures.size) {
                val right = if (index < pictures.size) pictures[index] else null
                val relation = timeline.transitions.firstOrNull { it.incoming == right?.id && (right != null || it.outgoing == last.id) }
                val left: VideoTimelineItem? = if (relation != null) pictures.firstOrNull { it.id == relation.outgoing }
                else if (right != null && index > 0) {
                    val ending = pictures.filter { it.id != right.id && it.end == right.start }
                    ending.filter { it.layer <= right.layer }.maxWithOrNull(TrackTimeline.backToFrontComparator)
                        ?: ending.minWithOrNull(TrackTimeline.backToFrontComparator) ?: pictures[index - 1]
                } else if (right == null) last else null
                val edge = if (left == null) -1 else if (right == null) 1 else 0
                val timing = TransitionTiming(relation?.settings, relation?.factor ?: 1.0, edge)
                val origin = relation?.origin?.seconds ?: left?.end?.seconds ?: right?.start?.seconds ?: 0.0
                val point = if (relation == null) origin else if (edge == -1) right!!.start.seconds else if (edge == 1) left!!.end.seconds else origin + timing.cut
                built += TransitionJoin(index, point,
                    max(0.0, origin + timing.lower)..max(0.0, origin + timing.upper),
                    min(left?.mapping?.duration?.seconds ?: 604800.0, right?.mapping?.duration?.seconds ?: 604800.0),
                    relation?.settings, origin, timing, relation?.factor ?: 1.0,
                    selectable = relation != null || (left == null && right?.start == EditTime.zero) || right == null || (left != null && left.end == right.start),
                    edge = edge, outgoingID = left?.id, incomingID = right?.id)
            }
            // Moving a clip can leave its fade/transition away from an ordinary
            // sequence boundary. Keep every explicit relation editable by identity.
            for (relation in timeline.transitions) {
                if (built.any { it.transition?.id == relation.id }) continue
                val left = pictures.firstOrNull { it.id == relation.outgoing }
                val right = pictures.firstOrNull { it.id == relation.incoming }
                val edge = if (left == null) -1 else if (right == null) 1 else 0
                val timing = TransitionTiming(relation.settings, relation.factor, edge)
                val origin = relation.origin.seconds
                val end = max(0.0, origin + timing.upper)
                built += TransitionJoin(built.size, if (edge == -1) max(0.0, origin) else if (edge == 1) end else origin + timing.cut,
                    max(0.0, origin + timing.lower)..end, min(left?.mapping?.duration?.seconds ?: 604800.0, right?.mapping?.duration?.seconds ?: 604800.0),
                    relation.settings, origin, timing, relation.factor, edge = edge, outgoingID = left?.id, incomingID = right?.id)
            }
            // An opening fade belongs to its picture; it must not hide a real
            // adjacent cut when that picture was moved later in the project.
            for (right in pictures) {
                if (timeline.transitions.none { it.incoming == right.id && it.outgoing == null }) continue
                val ending = pictures.filter { it.id != right.id && it.end == right.start }
                val left = ending.filter { it.layer <= right.layer }.maxWithOrNull(TrackTimeline.backToFrontComparator)
                    ?: ending.minWithOrNull(TrackTimeline.backToFrontComparator) ?: continue
                if (built.any { it.outgoingID == left.id && it.incomingID == right.id }) continue
                val time = right.start.seconds
                built += TransitionJoin(built.size, time, time..time,
                    min(left.mapping.duration.seconds, right.mapping.duration.seconds), null, outgoingID = left.id, incomingID = right.id)
            }
            val placements = pictures.withIndex().map { (index, item) ->
                val incoming = built.firstOrNull { it.incomingID == item.id && it.transition != null }
                val outgoing = built.firstOrNull { it.outgoingID == item.id && it.transition != null }
                TimelinePlacement(clips[index], index, item.start.seconds,
                    max(0.0, incoming?.timing?.overlap ?: 0.0), max(0.0, outgoing?.timing?.overlap ?: 0.0),
                    incoming?.time ?: item.start.seconds, outgoing?.time ?: item.end.seconds)
            }
            return TimelineLayout(placements, built, duration)
        }
    }
}

val EditState.layout: TimelineLayout get() = TimelineLayout(timeline)

/** Swift `settingTransition(_:at:)`. Pure: returns a new state. */
fun EditState.settingTransition(value: ClipTransition?, index: Int): EditState {
    val boundary = layout.joins.firstOrNull { it.index == index } ?: return this
    if (!boundary.selectable) return this
    val edit = copy()
    val left = timeline.video.firstOrNull { it.id == boundary.outgoingID }
    val right = timeline.video.firstOrNull { it.id == boundary.incomingID }
    edit.timeline.transitions = edit.timeline.transitions.filterNot { it.outgoing == left?.id && it.incoming == right?.id }
    edit.timeline.pruneRelations()
    if (value == null) return edit
    // Each picture has one incoming and one outgoing transition slot.
    // A new cut transition replaces edge fades on those same sides.
    edit.timeline.transitions = edit.timeline.transitions.filterNot { relation ->
        (left?.let { relation.outgoing == it.id } ?: false) || (right?.let { relation.incoming == it.id } ?: false)
    }
    val settings = value.copy(nextClipID = right?.id)
    // Reserve neighbouring transition handles first. Fitting a newly
    // edited relation must never create intersecting transition windows.
    val incoming = left?.let { item -> edit.timeline.transitions.firstOrNull { it.incoming == item.id } }
    val outgoing = right?.let { item -> edit.timeline.transitions.firstOrNull { it.outgoing == item.id } }
    val leftFree = max(0.0, (left?.mapping?.duration?.seconds ?: 604800.0) - (incoming?.let { TransitionTiming.of(it).rightCost } ?: 0.0))
    val rightFree = max(0.0, (right?.mapping?.duration?.seconds ?: 604800.0) - (outgoing?.let { TransitionTiming.of(it).leftCost } ?: 0.0))
    val edge = if (left == null) -1 else if (right == null) 1 else 0
    fun fits(factor: Double): Boolean {
        val t = TransitionTiming(settings, factor, edge)
        return t.leftCost <= leftFree + 0.00000001 && t.rightCost <= rightFree + 0.00000001
    }
    var factor = 1.0
    if (!fits(1.0)) {
        var lo = 0.0
        var hi = 1.0
        repeat(48) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }
        factor = lo
    }
    val t = TransitionTiming(settings, factor, edge)
    if (t.videoDuration < TimelineEditing.frame - 0.000001) return edit
    // Opening J-cuts may begin sound before the picture fade. Reserve
    // that lead-in at time zero instead of shifting the first take negative.
    val origin = left?.let { it.end - EditTime.of(t.leftEnd) }
        ?: maxOf(EditTime.zero, (right?.start ?: EditTime.zero) - EditTime.of(t.rightStart))
    if (right != null) {
        val nextStart = origin + EditTime.of(t.rightStart)
        val delta = nextStart - right.start
        val moving = edit.timeline.transitionShiftTargets(right, left)
        edit.timeline.shiftTransitionTail(moving, delta)
    }
    edit.timeline.transitions = edit.timeline.transitions + TimelineTransition(left?.id, right?.id, settings, origin, factor)
    val pair = listOfNotNull(left?.id, right?.id).toSet()
    edit.timeline.placements = edit.timeline.placements.filterNot { it.participants == pair }
    edit.timeline.pruneRelations()
    edit.timeline.synchronizeFollowers()
    return edit
}

/** Swift `restoringStraightCut(at:)`. */
fun EditState.restoringStraightCut(index: Int): EditState {
    val join = layout.joins.firstOrNull { it.index == index }
    val left = join?.let { j -> timeline.video.firstOrNull { it.id == j.outgoingID } }
    val right = join?.let { j -> timeline.video.firstOrNull { it.id == j.incomingID } }
    if (left == null || right == null) return settingTransition(null, index)
    val result = settingTransition(null, index)
    val moving = result.timeline.transitionShiftTargets(right, left)
    result.timeline.shiftTransitionTail(moving, left.end - right.start)
    result.timeline.pruneRelations()
    return result
}

private fun TrackTimeline.transitionShiftTargets(right: VideoTimelineItem, left: VideoTimelineItem?): Set<UUID> {
    val linked = links.flatMap { it.members }.toSet()
    val pictures = video.filter { it.id != left?.id && (it.id == right.id || (it.id in linked && it.start >= right.start)) }.map { it.id }.toSet()
    val sounds = audio.filter { sound ->
        if (!(sound.followsVideo != left?.id || left == null)) return@filter false
        val follower = sound.followsVideo
        if (follower != null) follower in pictures else sound.id in linked && sound.start >= right.start
    }.map { it.id }
    return pictures + sounds
}

private fun TrackTimeline.shiftTransitionTail(moving: Set<UUID>, delta: EditTime) {
    video = video.map { if (it.id in moving) it.copy(start = it.start + delta) else it }
    audio = audio.map { if (it.id in moving) it.copy(start = it.start + delta) else it }
    transitions = transitions.map { relation ->
        val members = listOfNotNull(relation.outgoing, relation.incoming).toSet()
        if (moving.containsAll(members)) relation.copy(origin = relation.origin + delta) else relation
    }
}
