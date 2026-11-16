// Port of YouRec/EditorialTimeline.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// An editable conform of the current edit. Media URLs are relative to the OTIO
/// document so the unzipped folder can move between machines.
object EditorialTimeline {
    private class Item(
        var start: EditTime,
        var map: SourcePlaybackMap,
        val source: SourceMedia,
        val muted: Boolean,
        var incomingDissolve: JsonObject? = null,
    ) {
        val end: EditTime get() = start + map.duration
    }

    private val pretty = Json { prettyPrint = true }

    fun filename(project: Project): String {
        val reserved = project.mediaFiles.map { it.lowercase() }.toSet()
        var name = "Timeline.otio"
        var index = 1
        while (name.lowercase() in reserved) { index += 1; name = "Timeline-$index.otio" }
        return name
    }

    private fun percentEncoded(name: String): String {
        val allowed = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')
        return buildString {
            for (byte in name.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xff).toChar()
                if (byte >= 0 && char in allowed) append(char) else append("%%%02X".format(byte.toInt() and 0xff))
            }
        }
    }

    fun data(project: Project): ByteArray {
        val edit = project.edit.timeline
        val rate = project.exportSettings.frameRate.rawValue.toDouble()
        val media = project.sources.associateBy { it.id }
        fun node(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (key, value) -> key to json(value) })
        fun time(frames: Double) = node("OTIO_SCHEMA" to "RationalTime.1", "rate" to rate, "value" to frames)
        fun range(start: Double, length: Double) = node("OTIO_SCHEMA" to "TimeRange.1", "start_time" to time(start), "duration" to time(length))
        fun obj(schema: String, name: String, fields: Map<String, Any?> = emptyMap()): JsonObject {
            val base = linkedMapOf<String, Any?>("OTIO_SCHEMA" to schema, "name" to name, "metadata" to JsonObject(emptyMap()), "effects" to JsonArray(emptyList()),
                "markers" to JsonArray(emptyList()), "enabled" to true, "source_range" to JsonNull)
            base.putAll(fields)
            return JsonObject(base.mapValues { json(it.value) })
        }
        fun edge(time: EditTime): Double = swiftRound(time.seconds * rate)
        fun gap(length: Double) = obj("Gap.1", "", mapOf("source_range" to range(0.0, length)))
        fun tracks(items: List<Item>, kind: String): List<JsonObject> {
            // Input picture order is back to front. Reuse a lane across cuts,
            // stacking only overlapping items above the material they obscure.
            val lanes = mutableListOf<MutableList<Item>>()
            for (item in items) {
                var index = lanes.size
                while (index > 0) {
                    val lane = lanes[index - 1]
                    var low = 0
                    var high = lane.size
                    while (low < high) {
                        val mid = (low + high) / 2
                        if (lane[mid].end <= item.start) low = mid + 1 else high = mid
                    }
                    if (low < lane.size && lane[low].start < item.end) break
                    index -= 1
                }
                if (index == lanes.size) lanes += mutableListOf<Item>()
                val last = lanes[index].lastOrNull()
                if (last != null && last.start > item.start) {
                    val slot = lanes[index].indexOfFirst { it.start > item.start }.let { if (it < 0) lanes[index].size else it }
                    lanes[index].add(slot, item)
                } else lanes[index].add(item)
            }
            return lanes.withIndex().map { (n, lane) ->
                val children = mutableListOf<JsonObject>()
                var cursor = 0.0
                for (item in lane) {
                    item.incomingDissolve?.let { children += it }
                    var pos = item.start
                    for (run in item.map.runs) {
                        val start = edge(pos)
                        pos += run.duration
                        val end = edge(pos)
                        val length = end - start
                        if (length <= 0) continue
                        if (start > cursor) children += gap(start - cursor)
                        cursor = end
                        if (kind == "Audio" && run.isHold) { children += gap(length); continue }
                        val ref = node("OTIO_SCHEMA" to "ExternalReference.1", "metadata" to JsonObject(emptyMap()),
                            "name" to (item.source.originalName ?: item.source.filename),
                            "available_range" to range(0.0, item.source.duration * rate),
                            "target_url" to percentEncoded(item.source.filename))
                        val effects = mutableListOf<JsonObject>()
                        val scalar = (run.sourceEnd - run.sourceStart) / run.duration.seconds
                        if (run.isHold || kotlin.math.abs(scalar - 1) > 0.0001) {
                            val name = if (run.isHold) "FreezeFrame" else "LinearTimeWarp"
                            effects += node("OTIO_SCHEMA" to "$name.1", "name" to "", "effect_name" to name, "metadata" to JsonObject(emptyMap()), "time_scalar" to scalar)
                        }
                        children += obj("Clip.2", item.source.originalName ?: item.source.filename, mapOf(
                            "source_range" to range(run.sourceStart * rate, length), "effects" to JsonArray(effects),
                            "enabled" to (kind != "Audio" || (!run.silent && !item.muted)),
                            "active_media_reference_key" to "DEFAULT_MEDIA", "media_references" to node("DEFAULT_MEDIA" to ref),
                        ))
                    }
                }
                if (edge(edit.duration) > cursor) children += gap(edge(edit.duration) - cursor)
                obj("Track.1", "$kind ${n + 1}", mapOf("kind" to kind, "children" to JsonArray(children)))
            }
        }
        val ordered = edit.video.sortedWith(TrackTimeline.backToFrontComparator)
        val picture = ordered.map { clip ->
            val source = media[clip.content.sourceID] ?: throw ProjectError.missingMedia
            Item(clip.start, clip.mapping, source, false)
        }
        // TODO(port): transitions. A simple two-shot dissolve is conformed to a
        // single-lane SMPTE_Dissolve; other transitions become PURPLE markers.
        val sound = edit.audio.sortedWith { a, b ->
            if (a.start == b.start) a.id.uuidString.compareTo(b.id.uuidString) else a.start.compareTo(b.start)
        }.map { clip ->
            val source = media[clip.sourceID] ?: throw ProjectError.missingMedia
            Item(clip.start, clip.mapping, source, clip.muted)
        }
        fun marker(name: String, start: Double, duration: Double, color: String) =
            node("OTIO_SCHEMA" to "Marker.2", "metadata" to JsonObject(emptyMap()), "name" to name, "color" to color,
                "marked_range" to range(start, duration), "comment" to "")
        val markers = edit.resolvedBookmarks.map { marker(it.bookmark.label.ifEmpty { "Bookmark" }, edge(it.time), 0.0, "YELLOW") }
        val stack = obj("Stack.1", "tracks", mapOf("children" to JsonArray(tracks(sound, "Audio") + tracks(picture, "Video")), "markers" to JsonArray(markers)))
        val timeline = node("OTIO_SCHEMA" to "Timeline.1", "name" to project.title, "global_start_time" to time(0.0), "tracks" to stack,
            "metadata" to JsonObject(emptyMap()))
        return pretty.encodeToString(JsonElement.serializer(), timeline.sortedKeys()).toByteArray(Charsets.UTF_8)
    }

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("Unsupported OTIO value $value")
    }
}
