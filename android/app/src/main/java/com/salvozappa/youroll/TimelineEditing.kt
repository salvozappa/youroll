// Port of YouRec/TimelineEditing.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Result of `VideoInsertionSite.placement`: Swift `(start, rippleAt, ripple)`. */
data class SitePlacement(val start: EditTime, val rippleAt: EditTime, val ripple: EditTime)

/** Swift enum `start, gap(after: EditTime), end`; Codable `{"gap":{"after":…}}`. */
@Serializable(with = VideoInsertionSiteSerializer::class)
sealed class VideoInsertionSite {
    data object start : VideoInsertionSite()
    data class gap(val after: EditTime) : VideoInsertionSite()
    data object end : VideoInsertionSite()

    val label: String
        get() = when (this) {
            start -> "Add video or photo at start"
            is gap -> "Add video or photo in gap"
            end -> "Add video or photo at end"
        }

    // Free picture time may contain independent audio. Use it before opening
    // more time, and ripple at the next picture only when the batch overflows.
    fun placement(timeline: TrackTimeline, length: EditTime): SitePlacement {
        val coverage = timeline.videoCoverage
        val first = coverage.firstOrNull() ?: return SitePlacement(EditTime.zero, EditTime.zero, EditTime.zero)
        val last = coverage.last()
        return when (this) {
            start -> SitePlacement(maxOf(EditTime.zero, first.start - length), first.start, maxOf(EditTime.zero, length - first.start))
            end -> SitePlacement(last.end, last.end, EditTime.zero)
            is gap -> {
                // Re-resolve against the edit being committed (Photos may take time).
                val left = coverage.lastOrNull { it.start < after } ?: return start.placement(timeline, length)
                val right = coverage.firstOrNull { it.start > left.end } ?: return SitePlacement(left.end, left.end, EditTime.zero)
                SitePlacement(left.end, right.start, maxOf(EditTime.zero, length - (right.start - left.end)))
            }
        }
    }
}

object VideoInsertionSiteSerializer : KSerializer<VideoInsertionSite> {
    @Serializable private class Gap(val after: EditTime)
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: VideoInsertionSite) {
        val json = encoder as JsonEncoder
        val element = when (value) {
            VideoInsertionSite.start -> JsonObject(mapOf("start" to JsonObject(emptyMap())))
            VideoInsertionSite.end -> JsonObject(mapOf("end" to JsonObject(emptyMap())))
            is VideoInsertionSite.gap -> JsonObject(mapOf("gap" to json.json.encodeToJsonElement(Gap.serializer(), Gap(value.after))))
        }
        json.encodeJsonElement(element)
    }
    override fun deserialize(decoder: Decoder): VideoInsertionSite {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("VideoInsertionSite")
        if (element.size != 1) throw SerializationException("VideoInsertionSite needs exactly one case")
        val (case, payload) = element.entries.first()
        return when (case) {
            "start" -> VideoInsertionSite.start
            "end" -> VideoInsertionSite.end
            "gap" -> VideoInsertionSite.gap(json.json.decodeFromJsonElement(Gap.serializer(), payload).after)
            else -> throw SerializationException("Unknown VideoInsertionSite case $case")
        }
    }
}

val TrackTimeline.videoCoverage: List<EditInterval>
    get() = TrackTimeline.union(video.map { it.interval } + generated.map { it.interval })

/** Give a passed-through tool payload a fresh `id` (Swift `value.id = fresh(value.id)`). */
private fun JsonElement?.refreshingID(fresh: (UUID) -> UUID): JsonElement? {
    val value = this as? JsonObject ?: return this
    val id = value.string("id")?.let(::uuidOrNull) ?: return value
    return value.with("id", JsonPrimitive(fresh(id).uuidString))
}

/** `VideoEffectSettings`: a captured analysis keeps pointing at its original effect identity. */
internal fun VideoEffectSettings.retainingCapturedEffectID(): VideoEffectSettings =
    if (analysisFile != null) copy(capturedEffectID = analysisID) else this

/** Fresh identities for every tool on a pasted or re-inserted copy of a clip. */
internal fun TimelineClip.withFreshToolIDs(fresh: (UUID) -> UUID): TimelineClip = copy(
    speed = speed?.let { it.copy(id = fresh(it.id)) },
    color = color?.let { it.copy(id = fresh(it.id)) },
    crop = crop?.let { it.copy(id = fresh(it.id)) },
    mirror = mirror?.let { it.copy(id = fresh(it.id)) },
    pauses = pauses?.let { it.copy(id = fresh(it.id)) },
    stabilization = stabilization?.let { value -> value.id?.let { value.copy(id = fresh(it)) } ?: value },
    texts = texts?.map { it.copy(id = fresh(it.id)) },
    videoEffects = videoEffects?.map { it.retainingCapturedEffectID().let { v -> v.copy(id = fresh(v.id)) } },
    images = images?.map { it.copy(id = fresh(it.id)) },
)

object TimelineEditing {
    const val frame = 1.0 / 30
    fun quantize(time: Double): Double = swiftRound(time * 30) / 30

    fun split(state: EditState, at: Double, selectedItems: Set<UUID> = emptySet()): EditState {
        val time = at
        val result = state.copy()
        val point = EditTime.of(quantize(time))
        val join = state.layout.transition(time)
        if (join != null && time > join.range.start + 0.00001 && time < join.range.endInclusive - 0.00001) return state
        if (selectedItems.isNotEmpty()) {
            // An explicit lane selection can split detached audio or picture on
            // its own. A selection elsewhere must not swallow the playhead cut.
            val selected = state.timeline.selection(ids = selectedItems)
            val cutsVideo = state.timeline.video.any { it.id in selected.video && it.start < point && point < it.end }
            val cutsAudio = state.timeline.audio.any { it.id in selected.audio && it.start < point && point < it.end }
            if (cutsVideo || cutsAudio) {
                result.timeline.split(point, selected)
                return result
            }
        }
        result.timeline.split(point, result.timeline.entireSelection())
        return result
    }

    // Use exactly the same clip-relative boundaries for Cut, Copy and Delete.
    // Absolute quantization corrupts selections at non-frame-aligned take joins.
    fun cut(range: ClosedFloatingPointRange<Double>, state: EditState): Pair<EditState, List<TimelineClip>> {
        if (range.endInclusive - range.start < frame - 0.00001) return state to emptyList()
        val boundaries = (state.timeline.video.flatMap { listOf(it.start.seconds, it.end.seconds) } +
            state.timeline.audio.flatMap { listOf(it.start.seconds, it.end.seconds) }).toSet()
        fun aligned(time: Double): Double {
            val nearest = boundaries.minWithOrNull(compareBy { abs(it - time) }) ?: return time
            return if (abs(nearest - time) < frame) nearest else time
        }
        val a = aligned(range.start)
        val b = aligned(range.endInclusive)
        if (b <= a) return state to emptyList()
        val scoped = state.timeline.scoped(state.timeline.entireSelection(EditInterval.of(a, b)))
        val copied = scoped.timeline.video.filter { it.id in scoped.selection.video }.map { it.content }
        scoped.timeline.remove(scoped.selection)
        return EditState(scoped.timeline, state.outputSize) to copied
    }

    fun removing(range: ClosedFloatingPointRange<Double>, state: EditState): EditState = cut(range, state).first

    // TODO(port): `copying` (clipboard, VideoTools.swift `scoped(_:range:)`).

    fun inserting(clips: List<TimelineClip>, state: EditState, at: Double, site: VideoInsertionSite? = null,
                  pictureOnlySources: Set<UUID> = emptySet()): EditState {
        if (clips.isEmpty()) return state
        val time = min(state.duration, max(0.0, at))
        val result = state.copy()
        // Pasted copies need independent identities for every tool, including
        // overlays shared across adjacent pieces. Re-editing one copy must not
        // silently edit the original or a later paste.
        val ids = HashMap<UUID, UUID>()
        fun fresh(old: UUID): UUID = ids.getOrPut(old) { newUUID() }
        val inserted = clips.map { original ->
            // The clipboard contains clips, not the neighboring join identity.
            original.copy(id = newUUID(), openingTransition = null, transition = null).withFreshToolIDs(::fresh)
        }
        val addition = TrackTimeline(inserted, pictureOnlySources = pictureOnlySources)
        val point = EditTime.of(time)
        val placement = site?.placement(state.timeline, addition.duration) ?: SitePlacement(point, point, addition.duration)
        result.timeline.insertTime(placement.ripple, placement.rippleAt, atPictureGap = site != null)
        val insertionLayer = (result.timeline.video.maxOfOrNull { it.layer } ?: 0) + 1
        val shiftedVideo = addition.video.map { it.copy(start = it.start + placement.start, layer = it.layer + insertionLayer) }
        val shiftedAudio = addition.audio.map { it.copy(start = it.start + placement.start) }
        result.timeline.video = (result.timeline.video + shiftedVideo).sortedBy { it.start }
        result.timeline.audio = result.timeline.audio + shiftedAudio
        result.timeline.links = result.timeline.links + addition.links
        return result
    }

    fun snap(time: Double, clips: List<TimelineClip>, pointsPerSecond: Double): Double {
        val boundaries = TimelineLayout(clips).joins.map { it.time }
        val nearest = boundaries.minWithOrNull(compareBy { abs(it - time) }) ?: return time
        return if (abs(nearest - time) * pointsPerSecond <= 8) nearest else time
    }

    fun trimmed(clip: TimelineClip, left: Boolean, by: Double, sourceDuration: Double): TimelineClip {
        val delta = by
        if (abs(delta) <= 0.000001) return clip
        clip.frozenFrame?.let { frozen ->
            val length = min(10.0, max(frame, frozen.duration + (if (left) -delta else delta)))
            var hold = frozen
            if (left) hold = hold.copy(offset = max(0.0, frozen.offset + frozen.duration - length))
            return clip.copy(frozenFrame = hold.copy(duration = length))
        }
        val available = clip.copy(start = 0.0, end = sourceDuration)
        val a = available.timelineTime(clip.start)
        val b = available.timelineTime(clip.end)
        val result = if (left) {
            val target = min(b - frame, max(0.0, quantize(a + delta)))
            clip.copy(start = available.sourceTime(target))
        } else {
            val target = max(a + frame, min(available.duration, quantize(b + delta)))
            clip.copy(end = available.sourceTime(target))
        }
        return if (result.duration >= frame - 0.000001) result else clip
    }

    fun offset(id: UUID, state: EditState): Double =
        state.layout.placements.firstOrNull { it.clip.id == id }?.start ?: state.duration
}
