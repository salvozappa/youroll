// Port of YouRec/TimelineNavigation.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/// Transport stops, derived from the edit rather than persisted or inferred
/// from the visible chips. Half-open intervals make simultaneous ends/starts
/// one event. Comparing recipes (not UUIDs) skips a plain split on long press.
class TimelineNavigation(edit: EditState = EditState()) {
    val changes: List<Double>
    val cutsAndChanges: List<Double>

    init {
        val timeline = edit.timeline
        val duration = timeline.duration
        val events = HashMap<EditTime, HashMap<String, Int>>()
        val cuts = linkedSetOf(EditTime.zero, duration)
        // Land on the first output frame carrying the new treatment. One stop
        // per frame, including changes from several independently timed tracks.
        fun frame(time: EditTime): EditTime =
            minOf(duration, maxOf(EditTime.zero, EditTime.of(ceil(time.seconds * 30 - 0.00001) / 30)))
        fun add(token: String, range: EditInterval) {
            val a = frame(range.start)
            val b = frame(range.end)
            if (b <= a) return
            val start = events.getOrPut(a) { HashMap() }
            start[token] = (start[token] ?: 0) + 1
            val end = events.getOrPut(b) { HashMap() }
            end[token] = (end[token] ?: 0) - 1
        }
        val joins = edit.layout.joins.filter { it.transition != null }
        val compiled = timeline.compile()
        fun addVideo(token: String, item: VideoTimelineItem, range: EditInterval) {
            for (span in compiled) {
                val rank = span.video.indexOf(item.id)
                if (rank < 0) continue
                val part = range.intersection(span.interval) ?: continue
                add("video.$rank.$token", part)
            }
        }
        fun itemCut(time: EditTime, videoID: UUID?) {
            // Render handles inside a transition aren't extra user cuts. The
            // transition's visible join and its picture/sound edges are stops.
            val hidden = videoID?.let { id ->
                timeline.transitions.any { relation ->
                    if (!(relation.outgoing == id || relation.incoming == id)) return@any false
                    val join = joins.firstOrNull { it.transition?.id == relation.id } ?: return@any false
                    time.seconds > join.range.start + 0.00001 && time.seconds < join.range.endInclusive - 0.00001
                }
            } ?: false
            if (!hidden) cuts += frame(time)
        }
        fun sourceEffect(kind: String, value: JsonElement, start: Double, end: Double, item: VideoTimelineItem,
                         omit: Set<String> = setOf("start", "end")) {
            val token = recipe(kind, value, omit)
            var local = EditTime.zero
            for (run in item.mapping.runs) {
                if (run.isHold) {
                    if (start <= run.sourceStart && end > run.sourceStart) addVideo(token, item, EditInterval(item.start + local, item.start + local + run.duration))
                } else {
                    val a = max(start, run.sourceStart)
                    val b = minOf(end, run.sourceEnd)
                    if (b > a) {
                        val scale = run.duration.seconds / (run.sourceEnd - run.sourceStart)
                        addVideo(token, item, EditInterval(item.start + local + EditTime.of((a - run.sourceStart) * scale),
                            item.start + local + EditTime.of((b - run.sourceStart) * scale)))
                    }
                }
                local += run.duration
            }
        }
        fun internalCuts(mapping: SourcePlaybackMap, start: EditTime, videoID: UUID?) {
            var local = EditTime.zero
            for ((left, right) in mapping.runs.zipWithNext()) {
                local += left.duration
                if (kotlin.math.abs(left.sourceEnd - right.sourceStart) > 0.00001 || left.isHold != right.isHold) itemCut(start + local, videoID)
            }
        }
        for (item in timeline.video) {
            val clip = item.content
            itemCut(item.start, item.id); itemCut(item.end, item.id)
            internalCuts(item.mapping, item.start, item.id)
            fun whole(kind: String, value: JsonElement, omit: Set<String> = emptySet()) = addVideo(recipe(kind, value, omit), item, item.interval)
            // The tool payloads are passed-through JSON (their tools are not
            // ported); these are the same activity tests the Swift code applies.
            clip.crop?.let { whole("crop", SwiftJSON.encodeToJsonElement(CropSettings.serializer(), it)) }
            clip.mirror?.let { if (it.horizontal || it.vertical) whole("mirror", SwiftJSON.encodeToJsonElement(MirrorSettings.serializer(), it)) }
            clip.speed?.let { if (it.rate != 1.0) whole("speed", SwiftJSON.encodeToJsonElement(SpeedSettings.serializer(), it)) }
            clip.color?.let { if (it.look != ColorLook.natural && it.amount > 0) whole("color", SwiftJSON.encodeToJsonElement(ColorSettings.serializer(), it)) }
            clip.stabilization?.let { if (it.level != StabilizationLevel.off) whole("stabilize", SwiftJSON.encodeToJsonElement(StabilizationSettings.serializer(), it), setOf("referenceStart", "referenceEnd")) }
            clip.pauses?.let { whole("pauses", SwiftJSON.encodeToJsonElement(PauseSettings.serializer(), it), setOf("cuts", "candidates", "analyzed")) }
            if (clip.frozenFrame != null) whole("freeze", JsonPrimitive(true))
            for (text in clip.texts.orEmpty()) {
                if (text.text.isEmpty()) continue
                sourceEffect("text", SwiftJSON.encodeToJsonElement(TextOverlay.serializer(), text), text.start, text.end, item)
            }
            for (image in clip.images.orEmpty()) {
                if (image.opacity <= 0) continue
                sourceEffect("image", SwiftJSON.encodeToJsonElement(ImageOverlay.serializer(), image), image.start, image.end, item)
            }
            for (value in clip.videoEffects.orEmpty()) {
                if (!value.enabled) continue
                if (value.kind.descriptor.supportsIntensity && value.number("intensity") == 0.0 &&
                    value.parameters.firstOrNull { it.id == "intensity" }?.keyframes?.isEmpty() != false) continue
                sourceEffect("effect", VideoEffectSettings.json(value), value.start, value.end, item,
                    setOf("start", "end", "analysisFile", "analysisDataFile", "capturedEffectID"))
            }
        }
        for (item in timeline.audio) {
            itemCut(item.start, item.followsVideo); itemCut(item.end, item.followsVideo)
            internalCuts(item.mapping, item.start, item.followsVideo)
            if (item.muted) add("audio.muted", item.interval)
            else if (item.volumeDB != 0.0) add(recipe("audio.volume", JsonPrimitive(item.volumeDB)), item.interval)
            item.fadeIn?.let { fade ->
                add(recipe("audio.fadeIn", JsonPrimitive(fade.seconds)), EditInterval(item.start, item.start + EditTime.of(item.fadeSeconds(AudioFadeEdge.fadeIn))))
            }
            item.fadeOut?.let { fade ->
                add(recipe("audio.fadeOut", JsonPrimitive(fade.seconds)), EditInterval(item.end - EditTime.of(item.fadeSeconds(AudioFadeEdge.fadeOut)), item.end))
            }
            for (region in item.censors) {
                add(recipe("audio.censor", SwiftJSON.encodeToJsonElement(AudioCensorSettings.serializer(), region.settings)), EditInterval(item.start + region.interval.start, item.start + region.interval.end))
            }
            for (region in item.enhancements) {
                val chain = region.effects.filter { it.enabled && it.supported }
                if (chain.isEmpty()) continue
                val range = item.interval.intersection(EditInterval(item.start + region.interval.start, item.start + region.interval.end)) ?: continue
                add(recipe("audio.enhancement", SwiftJSON.encodeToJsonElement(ListSerializer(AudioEffect.serializer()), chain)), range)
            }
        }
        // Captions are one tool, not hundreds of effect changes. Navigate to
        // its first visible sentence; speech gaps and later sentences are
        // navigated inside the subtitle editor instead.
        val subtitleStart = timeline.subtitleCues.minOfOrNull { it.start }?.let { frame(EditTime.of(it)) }
        for (plan in compiled) {
            if (plan.video.size <= 1) continue
            if (plan.placementID == null && plan.video.size == 2 && timeline.transitions.any {
                    it.id in plan.transitionIDs && listOfNotNull(it.outgoing, it.incoming).toSet() == plan.video.toSet()
                }) continue
            val placement = timeline.placements.firstOrNull { it.id == plan.placementID }
            val values = plan.video.map { placement?.videos?.get(it) ?: VideoPlacement() }
            add(recipe("placement", SwiftJSON.encodeToJsonElement(ListSerializer(VideoPlacement.serializer()), values)), plan.interval)
        }
        for (join in joins) {
            val value = join.transition ?: continue
            val token = SwiftJSON.encodeToJsonElement(ClipTransition.serializer(), value)
            cuts += frame(EditTime.of(join.time))
            add(recipe("transition.picture", token), EditInterval.of(join.videoRange.start, join.videoRange.endInclusive))
            // J/L cuts have independent picture and sound boundaries. A hold
            // inside the crossfade is still the same transition, not a new stop.
            val a = if (join.edge == -1) join.audioIn.start else if (join.edge == 1) join.audioOut.start else min(join.audioIn.start, join.audioOut.start)
            val b = if (join.edge == -1) join.audioIn.endInclusive else if (join.edge == 1) join.audioOut.endInclusive
                else max(join.audioIn.endInclusive, join.audioOut.endInclusive)
            add(recipe("transition.sound", token), EditInterval.of(a, b))
        }
        val boundaries = linkedSetOf(EditTime.zero, duration)
        if (subtitleStart != null) boundaries += subtitleStart
        for ((time, delta) in events) if (delta.values.any { it != 0 }) boundaries += time
        changes = boundaries.sorted().map { it.seconds }
        cutsAndChanges = (cuts + boundaries).sorted().map { it.seconds }
    }

    fun destination(from: Double, forward: Boolean, effectsOnly: Boolean = false): Double? {
        val stops = if (effectsOnly) changes else cutsAndChanges
        // The player's current time can be just either side of the exact seek.
        // Less than half a frame is the same anchor, never a repeated jump.
        val tolerance = 1.0 / 120
        return if (forward) stops.firstOrNull { it > from + tolerance } else stops.lastOrNull { it < from - tolerance }
    }

    companion object {
        private fun recipe(kind: String, value: JsonElement, omit: Set<String> = emptySet()): String {
            // UUIDs identify UI/editor objects, not how they look or sound.
            // String parameter IDs remain significant. Analysis files are cache
            // artifacts; sampled/locked palettes and all actual values remain.
            fun clean(element: JsonElement, root: Boolean = false): JsonElement = when (element) {
                is JsonObject -> JsonObject(element.filter { (key, field) ->
                    !(root && key in omit) && !(key == "id" && field is JsonPrimitive && field.isString && uuidOrNull(field.content) != null)
                }.mapValues { clean(it.value) })
                is JsonArray -> JsonArray(element.map { clean(it) })
                else -> element
            }
            return kind + ":" + base64(clean(value, root = true).sortedKeys().encodedBytes())
        }
    }
}
