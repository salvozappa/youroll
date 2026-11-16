// YouRoll tests for Swift Codable compatible JSON (no iOS fixture exists yet).
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class JsonCompatibilityTests {
    private fun richProject(): Project {
        val project = Project("Round trip · «YouRoll»")
        val source = SourceMedia(filename = "take.mov", duration = 12.0, events = listOf(CaptureEvent(time = 1.5, kind = "bookmark", detail = "Mark")),
            sha256 = "a".repeat(64), originalName = "Take 1")
        val music = SourceMedia(filename = "score.m4a", duration = 30.0, audioOnly = true)
        project.append(source); project.sources = project.sources + music
        val edit = project.edit
        edit.timeline.split(et(4), edit.timeline.entireSelection())
        edit.timeline.unlink(edit.timeline.selection(of = edit.timeline.video[1].id))
        edit.timeline.move(TimelineSelection(audio = setOf(edit.timeline.audio[1].id)), et(0.5))
        edit.timeline.insertAudio(music, et(1))
        edit.timeline.setFade(AudioFadeEdge.fadeIn, 0.75, edit.timeline.audio[0].id)
        edit.timeline.enhance(listOf(AudioEffect.make(EffectKind.voiceIsolation), AudioEffect(kind = EffectKind.unavailable("futureFilter"), amount = 3.0)),
            TimelineSelection(audio = setOf(edit.timeline.audio[0].id)))
        edit.timeline.addBookmark(et(2), label = "Source mark")
        edit.timeline.bookmarks = edit.timeline.bookmarks + TimelineBookmark(label = "Project mark", anchor = BookmarkAnchor.project(et(11)))
        edit.timeline.bookmarks = edit.timeline.bookmarks + TimelineBookmark(anchor = BookmarkAnchor.local(edit.timeline.video[0].occurrenceID, et(0.25)))
        // Out-of-scope payloads must survive untouched. Crop is typed (Swift Codable drops unknown keys).
        val crop = SwiftJSON.decodeFromJsonElement(CropSettings.serializer(),
            buildJsonObject { put("id", newUUID().uuidString); put("zoom", 1.5); put("x", 0.25); put("y", 0.75); put("rotation", 3.0); put("futureField", "dropped") })
        edit.timeline.updateVideo(0) { it.copy(content = it.content.copy(crop = crop, texts = listOf(textOverlay("Hello", 0.0, 2.0)))) }
        val placement = VideoPlacement(x = 0.3, width = 0.5, edgeCrop = PlacementEdgeCrop(left = 0.1))
        val cover = VideoTimelineItem(newUUID(), et(1), TimelineClip(sourceID = source.id, start = 0.0, end = 2.0), 5)
        edit.timeline.video = edit.timeline.video + cover
        val participants = setOf(edit.timeline.video[0].id, cover.id)
        edit.timeline.placements = listOf(VideoPlacementRegion(participants = participants, videos = participants.associateWith { placement }))
        edit.timeline.subtitles = SubtitleSettings(language = "Italian")
        edit.timeline.transitions = listOf(TimelineTransition(outgoing = edit.timeline.video[0].id,
            settings = ClipTransition(style = TransitionStyle.fadeColor, duration = 0.5, fadeColor = TransitionColor(1.0, 1.0, 1.0)), origin = et(1)))
        edit.outputSize = CGSize(1080.0, 1920.0)
        project.commit(edit)
        project.beginRecordingInsertion(2.0, VideoInsertionSite.gap(et(4)))
        project.exportSettings = ExportSettings(ExportSettings.Codec.hevc, ExportSettings.Resolution.fullHD, ExportSettings.FrameRate.pal)
        return project
    }

    @Test fun swiftFoundationEncodings() {
        val id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        require(SwiftJSON.encodeToString(UUIDSerializer, id) == "\"0F8FAD5B-D9CB-469F-A165-70867728950E\"", "UUIDs encode uppercase")
        require(SwiftJSON.decodeFromString(UUIDSerializer, "\"0f8fad5b-d9cb-469f-a165-70867728950e\"") == id, "UUIDs decode case-insensitively")
        require(runCatching { SwiftJSON.decodeFromString(UUIDSerializer, "\"1-2-3-4-5\"") }.isFailure, "Malformed UUID strings are rejected like Foundation")
        val date = SwiftDate.fromUnix(1_000_000_000.0)
        require(SwiftJSON.encodeToJsonElement(SwiftDate.serializer(), date) == JsonPrimitive(21_692_800.0), "Dates are seconds since 2001-01-01")
        require(SwiftJSON.decodeFromString(SwiftDate.serializer(), "21692800") == date, "Integral dates written by Swift decode")
        require(SwiftJSON.encodeToString(CGSize.serializer(), CGSize(1080.0, 1920.0)) == "[1080.0,1920.0]", "CGSize is an unkeyed pair")
        require(SwiftJSON.encodeToString(EditTime.serializer(), EditTime(240_000)) == "{\"ticks\":240000}", "EditTime keeps integer ticks")
        val anchors = listOf(BookmarkAnchor.source(id, id, 1.5), BookmarkAnchor.local(id, EditTime(5)), BookmarkAnchor.project(EditTime(7)))
        val encoded = SwiftJSON.encodeToJsonElement(ListSerializer(BookmarkAnchor.serializer()), anchors) as JsonArray
        require(encoded[0] == buildJsonObject {
            put("source", buildJsonObject { put("occurrence", id.uuidString); put("source", id.uuidString); put("time", 1.5) })
        }, "Labeled associated values use their labels")
        require(encoded[2] == buildJsonObject { put("project", buildJsonObject { put("_0", buildJsonObject { put("ticks", 7) }) }) },
            "Unlabeled associated values use _0")
        require(SwiftJSON.decodeFromJsonElement(ListSerializer(BookmarkAnchor.serializer()), encoded) == anchors, "Anchors round-trip")
        val sites = listOf(VideoInsertionSite.start, VideoInsertionSite.gap(EditTime(3)), VideoInsertionSite.end)
        val siteJson = SwiftJSON.encodeToString(ListSerializer(VideoInsertionSite.serializer()), sites)
        require(siteJson == "[{\"start\":{}},{\"gap\":{\"after\":{\"ticks\":3}}},{\"end\":{}}]", "Cases without values encode as empty objects")
        require(SwiftJSON.decodeFromString(ListSerializer(VideoInsertionSite.serializer()), siteJson) == sites, "Sites round-trip")
        val region = VideoPlacementRegion(id, setOf(id), mapOf(id to VideoPlacement(order = 1)))
        val regionJson = SwiftJSON.encodeToJsonElement(VideoPlacementRegion.serializer(), region) as JsonObject
        require(regionJson["videos"] == JsonArray(listOf(JsonPrimitive(id.uuidString), SwiftJSON.encodeToJsonElement(VideoPlacement.serializer(), VideoPlacement(order = 1)))),
            "UUID-keyed dictionaries are flat arrays")
        require(SwiftJSON.decodeFromJsonElement(VideoPlacementRegion.serializer(), regionJson) == region, "Placement regions round-trip")
        val placementJson = SwiftJSON.encodeToJsonElement(VideoPlacement.serializer(), VideoPlacement()) as JsonObject
        require(placementJson.keys == setOf("x", "y", "width", "height", "framing", "fill", "order") &&
            (placementJson["framing"] as JsonObject).keys == setOf("x", "y", "zoom"), "VideoPlacement keeps the Swift keys and omits nil motion and edgeCrop")
        val swiftPlacement = SwiftJSON.decodeFromString(VideoPlacement.serializer(),
            "{\"x\":0.25,\"y\":0.75,\"width\":0.32,\"height\":0.32,\"framing\":{\"x\":0.4,\"y\":0.5,\"zoom\":2},\"fill\":false,\"order\":1,\"edgeCrop\":{\"left\":0.1,\"right\":0,\"top\":0,\"bottom\":0.2}}")
        require(swiftPlacement == VideoPlacement(0.25, 0.75, 0.32, 0.32, TransitionFraming(0.4, 0.5, 2.0), false, 1, null, PlacementEdgeCrop(left = 0.1, bottom = 0.2)),
            "A Swift-encoded VideoPlacement decodes field for field")
        require(regionJson["participants"] == JsonArray(listOf(JsonPrimitive(id.uuidString))), "Sets are arrays")
        val audio = AudioTimelineItem(occurrenceID = id, sourceID = id, start = zero, mapping = SourcePlaybackMap(emptyList<PlaybackRun>()))
        val audioJson = SwiftJSON.encodeToJsonElement(AudioTimelineItem.serializer(), audio) as JsonObject
        require("followsVideo" !in audioJson && "fadeIn" !in audioJson && "censorRegions" !in audioJson && "volumeDB" in audioJson,
            "nil optionals are omitted, defaults are written")
        val settings = SwiftJSON.encodeToString(ExportSettings.serializer(), ExportSettings())
        require(settings == "{\"codec\":\"H.264\",\"resolution\":\"Project size\",\"frameRate\":30}", "Export settings use Swift raw values")
        require(SwiftJSON.encodeToString(EffectKind.serializer(), EffectKind.unavailable("x9")) == "\"x9\"", "Unknown effect kinds keep their raw name")
    }

    @Test fun projectRoundTripKeepsEverything() {
        val project = richProject()
        project.validate()
        val decoded = project.roundTripped()
        require(decoded.edit == project.edit && decoded.history == project.history && decoded.historyCursor == project.historyCursor,
            "Edits and history round-trip")
        require(decoded.sources == project.sources && decoded.title == project.title && decoded.id == project.id &&
            decoded.createdAt == project.createdAt && decoded.updatedAt == project.updatedAt && decoded.exportSettings == project.exportSettings,
            "Metadata round-trips")
        require(decoded.recordingInsertion == project.recordingInsertion && decoded.schemaVersion == project.schemaVersion &&
            decoded.recordingHistoryVersion == 1, "Recording state round-trips")
        require(Project.encode(decoded).contentEquals(Project.encode(project)), "Re-encoding is byte-identical")
        val timeline = decoded.edit.timeline
        require(timeline.video[0].content.crop?.zoom == 1.5 && timeline.video[0].content.crop?.rotation == 3.0 && timeline.subtitles?.language == "Italian" &&
            timeline.transitions.size == 1 && timeline.placements[0].videos.values.all { it.edgeCrop != null },
            "Unported payloads pass through untouched")
        require(project.schemaVersion == 11, "Fades, placement crops and subtitles raise the schema like Swift")
        require(timeline.audio[0].enhancements[0].effects[1].kind == EffectKind.unavailable("futureFilter"), "Unknown effects survive")
        val legacyClips = buildJsonObject {
            put("clips", JsonArray(listOf(SwiftJSON.encodeToJsonElement(TimelineClip.serializer(), TimelineClip(sourceID = project.sources[0].id, start = 1.0, end = 3.0)))))
        }
        val legacy = SwiftJSON.decodeFromJsonElement(EditStateSerializer, legacyClips)
        require(legacy.timeline.video.size == 1 && legacy.timeline.audio.size == 1 && legacy.duration == 2.0, "Development-format sequential edits convert")
    }

    @Test fun manifestAndSnapshotsOnDisk() {
        val root = kotlin.io.path.createTempDirectory("youroll-json").toFile()
        try {
            val disk = ProjectDisk(root)
            val project = richProject()
            disk.save(project)
            val manifest = parseJson(java.io.File(disk.folder(project.id), "project.json").readBytes()) as JsonObject
            require(manifest.keys.toList() == manifest.keys.sorted(), "Manifest keys are sorted")
            require("snapshots" !in manifest && manifest.field("snapshotFiles").array().size == project.history.size, "History lives in content-addressed snapshot files")
            for (name in manifest.field("snapshotFiles").array().map { it.primitiveContent()!! }) {
                val bytes = java.io.File(disk.folder(project.id), "History/$name").readBytes()
                require(sha256Hex(bytes) + ".json" == name && Project.isSnapshotName(name), "Snapshot names are their SHA-256")
            }
            val reopened = disk.read(java.io.File(disk.folder(project.id), "project.json"))
            require(reopened.edit == project.edit && reopened.history == project.history, "Disk round trip keeps the history")
            disk.save(reopened)
            require(java.io.File(disk.folder(project.id), "History").list()!!.size == project.history.size, "Saving again writes no new snapshots")
        } finally {
            root.deleteRecursively()
        }
    }
}
