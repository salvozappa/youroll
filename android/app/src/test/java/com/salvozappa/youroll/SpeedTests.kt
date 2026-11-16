// YouRoll JVM tests for Speed ranges and freeze frames. The speed/pause source
// mapping, quick removal and shared-effect checks port the assertions of
// ../Tests/VideoToolsTests.swift (`videoToolsTests`, `effectQuickRemovalTests`)
// and the Speed part of the EditorTools.swift self-test; the rest covers the
// Android tool workspace, regions and validation.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SpeedTests {
    private fun project(duration: Double): Project = Project("Speed").also { it.append(SourceMedia(filename = "source.mp4", duration = duration)) }

    @Test fun speedAwareEditingUsesSourceCoordinates() {
        val project = project(4.0)
        val edit = project.edit.copy()
        edit.updateClip(0) { it.copy(speed = SpeedSettings(rate = 2.0)) }
        project.commit(edit)
        project.validate()
        val split = TimelineEditing.split(edit, 0.8)
        require(split.clips.size == 2 && abs(split.clips[0].end - 1.6) < 0.00001 && abs(split.duration - 2) < 0.00001,
            "Speed-aware split uses source coordinates and preserves duration")
        require(split.clips.all { it.speed == edit.clips[0].speed }, "Split pieces share one speed identity")
        val removed = TimelineEditing.removing(0.5..1.5, edit)
        require(removed.clips.size == 2 && removed.clips[0].end == 1.0 && removed.clips[1].start == 3.0 && removed.duration == 1.0, "Ripple removal maps through speed")
        val (scoped, ids) = ToolRequest(VideoTool.speed, 0.5..1.5, "Range").videoScope(edit)
        require(scoped.clips.size == 3 && ids.size == 1 && scoped.clips[1].start == 1.0 && scoped.clips[1].end == 3.0, "Tool scope isolates exactly the selected source range")
        val pauseClip = edit.clips[0].copy(pauses = pausesJson(1.0 to 2.0))
        require(pauseClip.duration == 1.5 && pauseClip.sourceTime(0.5) == 2.0 && pauseClip.timelineTime(3.0) == 1.0, "Pauses and speed compose without drifting effect timing")
        val pauseSplit = TimelineEditing.split(EditState(listOf(pauseClip)), 0.5)
        require(abs(pauseSplit.duration - pauseClip.duration) < 0.000001, "Splitting at a removed pause preserves output duration")
        val trimmed = TimelineEditing.trimmed(pauseClip, left = true, by = 0.5, sourceDuration = 4.0)
        require(trimmed.start == 2.0 && trimmed.duration == 1.0, "Trim handles follow timeline distance through speed and skipped pauses")
        val minimal = TimelineEditing.trimmed(pauseClip, left = false, by = -100.0, sourceDuration = 4.0)
        require(minimal.duration >= TimelineEditing.frame - 0.000001, "Trim never produces a fully skipped, invalid clip")
        project.undo()
        val reopened = project.roundTripped(); reopened.redo(); reopened.validate()
        require(reopened.edit == edit, "Speed survives undo/redo and document reopening")
    }

    @Test fun speedSettingsUseTheSwiftCodableShape() {
        val id = newUUID()
        val clip = TimelineClip(sourceID = newUUID(), start = 0.0, end = 4.0, speed = SpeedSettings(id, 1.5))
        val json = SwiftJSON.encodeToJsonElement(TimelineClip.serializer(), clip).toString()
        require(json.contains("\"speed\":{\"id\":\"${id.uuidString}\",\"rate\":1.5}"), "SpeedSettings encodes {id, rate}: $json")
        val decoded = SwiftJSON.decodeFromString(TimelineClip.serializer(), json)
        require(decoded == clip && decoded.rate == 1.5, "and decodes back")
    }

    @Test fun validationBoundsSpeedAndRequiresTheFreezeEffect() {
        fun validate(clip: TimelineClip) {
            val project = project(8.0)
            project.commit(EditState(listOf(clip.copy(sourceID = project.sources[0].id))))
            project.validate()
        }
        val source = newUUID()
        validate(TimelineClip(sourceID = source, start = 0.0, end = 4.0, speed = SpeedSettings(rate = 0.25)))
        validate(TimelineClip(sourceID = source, start = 0.0, end = 4.0, speed = SpeedSettings(rate = 4.0)))
        assertInvalidDocument("Speed below 0.25×") { validate(TimelineClip(sourceID = source, start = 0.0, end = 4.0, speed = SpeedSettings(rate = 0.2))) }
        assertInvalidDocument("Speed above 4×") { validate(TimelineClip(sourceID = source, start = 0.0, end = 4.0, speed = SpeedSettings(rate = 4.5))) }
        val hold = TimelineClip(sourceID = source, start = 1.0, end = 1.0 + TimelineEditing.frame, frozenFrame = FrozenFrameSegment(1.0, 3.0))
        assertInvalidDocument("A hold without its Freeze Frame effect") { validate(hold) }
        val effect = VideoEffectSettings(VideoEffectID.freezeFrame, 1.0, 1.0 + TimelineEditing.frame)
        validate(hold.copy(videoEffects = listOf(effect)))
        assertInvalidDocument("A hold outside its clip") { validate(hold.copy(frozenFrame = FrozenFrameSegment(2.0, 3.0), videoEffects = listOf(effect))) }
        assertInvalidDocument("A hold longer than 10 s") { validate(hold.copy(frozenFrame = FrozenFrameSegment(1.0, 11.0), videoEffects = listOf(effect))) }
    }

    // Swift effectQuickRemovalTests: removing speed restores timing and keeps other tools.
    @Test fun removingSpeedRestoresTimingAndKeepsOtherTools() {
        val project = project(8.0)
        val edit = TimelineEditing.split(project.edit, 4.0)
        val speed = SpeedSettings(rate = 2.0)
        val crop = CropSettings(zoom = 1.5)
        edit.clips = edit.clips.map { it.copy(speed = speed, crop = crop) }
        require(edit.duration == 4.0, "2× halves the project")
        val all = edit.clips.map { it.id }.toSet()
        val regular = edit.removingTool(ToolRequest(VideoTool.speed, 0.0..4.0, "Speed", effectID = speed.id, clipIDs = all))
        require(regular.duration == 8.0 && regular.clips.all { it.speed == null && it.crop == crop }, "Removing speed restores timing and keeps other tools")
        require(regular.clips.map { it.start } == edit.clips.map { it.start } && regular.clips.map { it.end } == edit.clips.map { it.end }, "Removing an effect never trims source footage")
        project.commit(edit); project.commit(regular); project.undo()
        require(project.edit == edit, "One Undo restores the deleted effect and its complete settings")
        project.redo(); project.validate()
        require(project.edit == regular && project.sources.size == 1, "Redo removes the effect without deleting source media")
    }

    @Test fun toolRegionsReopenOneSharedSpeedEffect() {
        val project = project(8.0)
        val edit = TimelineEditing.split(project.edit, 4.0)
        val speed = SpeedSettings(rate = 0.5)
        edit.clips = edit.clips.map { it.copy(speed = speed) }
        val regions = edit.toolRegions.filter { it.tool == VideoTool.speed }
        require(regions.size == 2 && regions.all { it.effectID == speed.id && it.label == "0.5×" }, "One speed identity spans both pieces")
        assertEquals(0.0, regions[0].range.start, 1e-9); assertEquals(8.0, regions[0].range.endInclusive, 1e-9)
        assertEquals(16.0, regions[1].range.endInclusive, 1e-9)
        // A badge targets its own segment unless the user selects more.
        val one = edit.request(regions[0])
        require(one.clipIDs == setOf(regions[0].clipID) && one.effectID == speed.id && one.scopeTitle == "This clip", "A tapped region edits its clip")
        val both = edit.request(regions[1], TimelineSelection(video = edit.clips.map { it.id }.toSet()))
        require(both.clipIDs == edit.clips.map { it.id }.toSet() && both.scopeTitle == "Selected · 2 clips", "A selection reaches every piece")
        assertEquals(listOf("2", "1.5", "0.25", "1", "0.05"), listOf(2.0, 1.5, 0.25, 1.0, 0.05).map(::rateText))
    }

    // Swift EditorTools self-test: "Re-editing a shared speed effect", "Removing a shared effect".
    @Test fun reopeningASharedSpeedEditsEveryPiece() {
        val project = project(8.0)
        val edit = TimelineEditing.split(project.edit, 4.0)
        val speed = SpeedSettings(rate = 1.5)
        edit.clips = edit.clips.map { it.copy(speed = speed) }
        project.commit(edit)
        val ids = edit.clips.map { it.id }.toSet()
        val workspace = ToolWorkspace(project, File("."), ToolRequest(VideoTool.speed, 0.0..edit.duration, "Both clips", effectID = speed.id, clipIDs = ids), 0.0)
        assertEquals("Opens at the effect's rate", 1.5, workspace.rate, 1e-9)
        workspace.rate = 2.0
        require(workspace.state().clips.all { it.rate == 2.0 && it.speed?.id == speed.id }, "Re-editing a shared speed effect edits every piece, keeping its identity")
        require(workspace.state(removing = true).clips.all { it.speed == null }, "Removing a shared effect reaches every piece")
        workspace.rate = 1.0
        require(workspace.state().clips.all { it.speed == null }, "1× is no effect")
        workspace.release()
    }

    @Test fun newRangeEffectGetsItsOwnIdentityAndChangesOnlyItsRange() {
        val project = project(8.0)
        val edit = project.edit
        val range = TimelineSelection(video = edit.clips.map { it.id }.toSet(), range = iv(2, 4))
        val workspace = ToolWorkspace(project, File("."), ToolRequest(VideoTool.speed, 2.0..4.0, "Range", targets = range, clipIDs = range.video), 3.0)
        workspace.rate = 2.0
        val state = workspace.state()
        require(state.clips.size == 3 && state.clips.map { it.rate } == listOf(1.0, 2.0, 1.0), "Only the marked range is retimed: ${state.clips.map { it.rate }}")
        require(abs(state.duration - 7.0) < 1e-9, "2 + 1 + 4 s")
        require(state.timeline.duration == et(7), "The timeline is exactly 7 s")
        val audio = state.timeline.audio
        require(audio.size == 3 && audio.sumOf { it.mapping.duration.seconds } == 7.0, "Linked sound follows the picture")
        workspace.release()
    }

    @Test fun navigationStopsAtSpeedAndHolds() {
        val project = project(8.0)
        val edit = project.edit.copy()
        edit.updateClip(0) { it.copy(speed = SpeedSettings(rate = 2.0)) }
        val navigation = TimelineNavigation(edit)
        require(navigation.destination(0.0, forward = true, effectsOnly = false) != null, "A sped clip still has its end as a change")
    }
}
