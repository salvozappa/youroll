// YouRoll JVM tests for transitions. They port the assertions of
// ../Tests/TransitionTests.swift that do not need AVFoundation: timing,
// identity, history, short clips, safe edits (`transitionTests` up to the
// composite checks), the composite pixel checks against the shared shader
// math (`TransitionComposite`), and the timing parts of
// `expandedTransitionTests` (holds, still, J/L audio windows, the 90 seeded
// random footprints). Rendering itself is checked on the device.
// ../Tests/ZoomTransitionTests.swift tests the recorder's zoom ramps, not
// editor transitions; it is ported as ZoomTransitionTest.kt.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import org.junit.Test
import kotlin.math.abs

class TransitionTests {
    private fun project(): Project = Project("Transitions").also {
        it.append(SourceMedia(filename = "source.mp4", duration = 4.0))
        it.append(SourceMedia(filename = "portrait.mp4", duration = 4.0))
    }

    @Test fun timingIdentityHistoryAndSafeEdits() {
        val project = project()
        val original = project.edit
        val secondID = original.clips[1].id
        val dissolve = ClipTransition(nextClipID = secondID, duration = 1.0)
        val edit = original.settingTransition(dissolve, 1)
        require(edit.duration == 7.0 && edit.layout.placements[1].start == 3.0 && edit.layout.joins[1].time == 3.5, "Overlap shifts the second clip and join by the correct amounts")
        require(edit.clips.map { it.start } == original.clips.map { it.start } && edit.clips.map { it.end } == original.clips.map { it.end }, "Adding an overlap never changes source trims")
        require(TimelineEditing.snap(3.51, edit.clips, 60.0) == 3.5, "Snap lands at the transition midpoint")
        require(TimelineEditing.scope(edit, null, secondID) == 3.5..7.0, "Clip scope follows the overlap layout")
        require(TimelineEditing.split(edit, 3.25) == edit, "Splitting inside an overlap is refused without modifying footage")
        val split = TimelineEditing.split(edit, 1.0)
        require(split.clips.size == 3 && split.duration == 7.0 && split.clips[1].transition == dissolve && split.clips[0].transition == null, "Splits preserve the outgoing join and duration")
        val removed = edit.settingTransition(null, 1)
        require(removed.timeline.transitions.isEmpty() && removed.timeline.video.map { it.start } == edit.timeline.video.map { it.start }, "Removing a transition retains explicit clip positions")
        require(edit.restoringStraightCut(1).duration == original.duration, "Explicit straight-cut restoration recovers sequence duration")
        project.commit(edit); project.undo()
        val reopened = project.roundTripped(); reopened.redo(); reopened.validate()
        require(reopened.edit == edit, "Transition parameters and undo/redo survive reopening")
        require(reopened.schemaVersion == 6, "Older builds reject transition projects instead of silently changing their running time")
        val longEdit0 = original.copy()
        for (index in longEdit0.clips.indices) longEdit0.updateClip(index) { it.copy(speed = SpeedSettings(rate = 0.25)) }
        val longEdit = longEdit0.settingTransition(ClipTransition(duration = 12.0), 1)
        require(longEdit.layout.joins[1].maximum == 16.0 && longEdit.duration == 20.0, "Transitions extend beyond ten seconds up to available footage")
        val longProject = project.roundTripped(); longProject.commit(longEdit); longProject.undo()
        val longReopened = longProject.roundTripped(); longReopened.redo(); longReopened.validate()
        require(longReopened.edit == longEdit, "Long transition settings and Undo/Redo survive reopening")
        require(longEdit.restoringStraightCut(1).duration == 32.0, "Removing a long overlap restores all footage")
        val reordered = edit.copy(); reordered.clips = listOf(reordered.clips[1], reordered.clips[0])
        require(reordered.normalizedTools().timeline.transitions == edit.timeline.transitions, "Reordering payload storage never reassigns a transition or changes participant identity")
        val trimmed = edit.copy(); trimmed.updateClip(0) { it.copy(end = 0.5) }
        require(trimmed.timeline.transitions.isEmpty(), "A trim that removes required transition handles removes the invalid relation")
        trimmed.updateClip(0) { it.copy(end = 4.0) }
        require(trimmed.timeline.transitions.isEmpty(), "Extending does not resurrect removed transitions")
        val fade = dissolve.copy(style = TransitionStyle.fadeColor)
        val faded = original.settingTransition(fade, 1)
        require(faded.duration == 8.0 && faded.layout.joins[1].range == 3.5..4.5, "Fade through black retains timeline length")
        val ends = original.settingTransition(ClipTransition(duration = 1.0), 0).settingTransition(ClipTransition(duration = 1.0), 2)
        require(ends.duration == 8.0 && ends.layout.joins[0].range == 0.0..1.0 && ends.layout.joins[2].range == 7.0..8.0, "Black opening/closing retain the whole project")
        val chain0 = original.copy()
        chain0.clips = chain0.clips + chain0.clips[0].copy(id = newUUID())
        val chain = chain0.settingTransition(ClipTransition(duration = 3.0), 1).settingTransition(ClipTransition(duration = 3.0), 2)
        val layout = chain.layout
        require(layout.placements[1].incoming + layout.placements[1].outgoing <= 4.000001, "Adjacent transitions never require three simultaneous clips")
        require(layout.joins[1].range.endInclusive <= layout.joins[2].range.start + 0.000001, "Transition windows never cross")
        val badScope = TimelineEditing.scoped(edit, 3.2..5.0)
        require(badScope.second.isEmpty() && badScope.first == edit, "A selection inside overlap cannot silently modify excess footage")
        val offGrid = original.copy(); offGrid.updateClip(0) { it.copy(end = 0.517) }
        val pastedClip = original.clips[0].copy(end = 0.3)
        val pasted = TimelineEditing.inserting(listOf(pastedClip), offGrid, 1.0)
        val afterDelete = TimelineEditing.removing(1.0..1.3, pasted)
        require(abs(afterDelete.duration - offGrid.duration) < 0.00001, "Paste then Delete near off-grid clip boundaries restores exact duration")
        val nearCut = TimelineEditing.removing((0.517 + 0.001)..(offGrid.duration - 0.001), offGrid)
        require(nearCut.clips.size == 1 && abs(nearCut.duration - 0.517) < 0.00001, "Selection endpoints within a frame of a cut keep existing deletion behavior")
        val oldFade = SwiftJSON.decodeFromString(ClipTransition.serializer(), SwiftJSON.encodeToString(ClipTransition.serializer(), fade))
        require(oldFade.resolvedFadeColor == TransitionColor.black && oldFade.style.rawValue == "fadeBlack", "Existing black fades decode unchanged")
        val coloredFade = fade.copy(fadeColor = TransitionColor(0.1, 0.8, 0.2))
        val colorProject = project.roundTripped()
        val colorEdit = original.settingTransition(coloredFade, 1); colorProject.commit(colorEdit); colorProject.undo()
        val colorReopened = colorProject.roundTripped(); colorReopened.redo(); colorReopened.validate()
        require(colorReopened.edit == colorEdit && colorReopened.schemaVersion == 6, "Custom colors persist and older apps cannot silently discard them")
        val edgeHold = ClipTransition(style = TransitionStyle.fadeColor, duration = 1.0, hold = 10.0)
        val edgeLayout = original.settingTransition(edgeHold, 0).settingTransition(dissolve, 1).layout
        require(edgeLayout.joins[0].range.endInclusive <= edgeLayout.joins[1].range.start + 0.000001, "Opening color holds cannot collide with the next transition")
    }

    @Test fun codableShapeUsesSwiftFieldNames() {
        val id = newUUID()
        val value = ClipTransition(id = id, style = TransitionStyle.fadeColor, duration = 1.0, hold = 2.0, fadeColor = TransitionColor(1.0, 1.0, 1.0))
        val json = SwiftJSON.encodeToString(ClipTransition.serializer(), value)
        require(json.contains("\"style\":\"fadeBlack\"") && json.contains("\"hold\":2.0") && json.contains("\"fadeColor\":{\"red\":1.0,\"green\":1.0,\"blue\":1.0}") &&
            json.contains("\"first\":{\"x\":0.5,\"y\":0.5,\"zoom\":1.0}") && !json.contains("audioOffset"), "ClipTransition encodes Swift names and raw values: $json")
        val relation = TimelineTransition(newUUID(), null, value, EditTime(240_000), 0.5)
        val text = SwiftJSON.encodeToString(TimelineTransition.serializer(), relation)
        require(text.startsWith("{\"outgoing\":") && text.contains("\"origin\":{\"ticks\":240000}") && text.contains("\"factor\":0.5") && !text.contains("incoming"),
            "TimelineTransition encodes outgoing/incoming/settings/origin/factor: $text")
        require(SwiftJSON.decodeFromString(TimelineTransition.serializer(), text) == relation, "and decodes back")
    }

    @Test fun compositeMatchesTheSwiftFilters() {
        val canvas = CGSize(160.0, 90.0)
        val red = TransitionComposite.Rgb(1.0, 0.0, 0.0)
        val blue = TransitionComposite.Rgb(0.0, 0.0, 1.0)
        fun solid(color: TransitionComposite.Rgb) = { _: Double, _: Double -> color }
        // Swift samples pixels in Core Image coordinates (y up), like `TransitionComposite.pixel`.
        fun pixel(settings: ClipTransition, progress: Double, x: Int, yUp: Int, first: (Double, Double) -> TransitionComposite.Rgb = solid(red),
                  second: (Double, Double) -> TransitionComposite.Rgb = solid(blue)): TransitionComposite.Rgb =
            TransitionComposite.pixel(first, second, settings, progress, canvas, x + 0.5, yUp + 0.5)
        val dissolve = ClipTransition(duration = 1.0)
        var pair = dissolve.copy(style = TransitionStyle.split, divider = 0.35)
        require(pixel(pair, 0.5, 20, 40).r > 0.94 && pixel(pair, 0.5, 100, 40).b > 0.94, "Split screen puts outgoing left and incoming right")
        pair = pair.copy(axis = TransitionAxis.vertical)
        require(pixel(pair, 0.5, 50, 80).r > 0.94 && pixel(pair, 0.5, 50, 20).b > 0.94, "Vertical split uses outgoing top and incoming bottom")
        val fade = dissolve.copy(style = TransitionStyle.fadeColor)
        val black = pixel(fade, 0.5, 40, 40)
        require(black.r < 2 / 255.0 && black.g < 2 / 255.0 && black.b < 2 / 255.0, "Fade through black midpoint is black")
        val colored = pixel(fade.copy(fadeColor = TransitionColor(0.1, 0.8, 0.2)), 0.5, 40, 40)
        require(colored.g > 180 / 255.0 && colored.r < 50 / 255.0 && colored.b < 80 / 255.0, "Color fade midpoint uses the selected sRGB color")
        val wipe = ClipTransition(style = TransitionStyle.wipe, duration = 1.0, hardEdge = true, wipeAngle = 45.0)
        require(pixel(wipe, 0.5, 20, 80).b > 0.94 && pixel(wipe, 0.5, 140, 10).r > 0.94, "Hard wipe follows its configured diagonal")
        val hitchcock = ClipTransition(style = TransitionStyle.hitchcock, duration = 1.0)
        // Left half dark (0.1), right half an HDR highlight (3.0).
        val brightness = { x: Double, _: Double -> if (x < 80) TransitionComposite.Rgb(0.1, 0.1, 0.1) else TransitionComposite.Rgb(3.0, 3.0, 3.0) }
        require(pixel(hitchcock, 0.5, 20, 40, brightness).b > 0.94 && pixel(hitchcock, 0.5, 120, 40, brightness).r > 0.94, "Hitchcock reveals shadows before highlights")
        require(pixel(hitchcock, 0.99, 120, 40, brightness).r < 4 / 255.0, "HDR highlights reveal before the final frame, without a last-frame pop")
        val highlights = hitchcock.copy(highlightsFirst = true)
        require(pixel(highlights, 0.5, 120, 40, brightness).r < 4 / 255.0 && pixel(highlights, 0.5, 20, 40, brightness).b < 100 / 255.0, "Hitchcock reverses to highlights first")
        val flashes = ClipTransition(style = TransitionStyle.flashCut, duration = 1.0)
        for (frame in 0 until 30) {
            val isBlue = pixel(flashes, frame / 30.0, 40, 40).b > 0.94
            require(isBlue == (frame in flashes.flashFrames(1.0)), "Flash Cut uses exact single frames of the next scene")
        }
    }

    @Test fun expandedTimingHoldsStillAndAudioWindows() {
        val base = project()
        val original = base.edit
        val opening0 = original.copy()
        opening0.timeline.unlink(opening0.timeline.selection(of = opening0.timeline.video[0].id))
        var opening = opening0
        val starts = opening.timeline.video.map { it.start }
        val soundStarts = opening.timeline.audio.map { it.start }
        val originalDuration = opening.duration
        for (offset in listOf(-0.5, -0.2, 0.0, -0.5)) {
            opening = opening.settingTransition(ClipTransition(duration = 1.0, audioOffset = offset, audioBlend = 0.0), 0)
            require(opening.timeline.video.map { it.start } == starts && opening.timeline.audio.map { it.start } == soundStarts && opening.duration == originalDuration,
                "Re-editing an opening J-cut stays anchored without gaps or detached audio drift")
        }
        fun candidate(settings: ClipTransition, boundary: Int = 1) = original.settingTransition(settings, boundary)
        val hold = ClipTransition(duration = 1.0, hold = 2.0)
        val held = candidate(hold)
        val join = held.layout.joins[1]
        require(abs(held.duration - 5) < 0.00001 && join.timing.progress(1.0) == 0.5 && join.timing.progress(2.0) == 0.5, "Midpoint hold adds to the moving overlap and retains the 50/50 mix")
        val color = hold.copy(style = TransitionStyle.fadeColor, fadeColor = TransitionColor(0.2, 0.7, 0.4))
        require(candidate(color).duration == 10.0, "Solid color hold adds time without consuming footage")
        val freeze = ClipTransition(style = TransitionStyle.still, duration = 2.0, stillDissolve = 0.5)
        val still = candidate(freeze)
        require(still.duration == 10.0 && still.layout.joins[1].videoRange == 4.0..6.5, "Still holds the final frame before dissolving into the next scene")
        require(still.timeline.generated.single().freezeVideo == still.timeline.video[0].id, "The still's generated region freezes the outgoing picture")
        require(candidate(ClipTransition(style = TransitionStyle.still, duration = 1.0), 0).duration == 9.0, "Opening still holds black before source footage")
        require(candidate(freeze, 2).duration == 10.5, "Closing still includes the final fade to black")
        for (style in TransitionStyle.allCases) {
            for ((offset, blend) in listOf(-1.0 to 0.5, 1.5 to 0.5)) {
                val p = candidate(ClipTransition(style = style, duration = 1.0, audioOffset = offset, audioBlend = blend))
                val j = p.layout.joins[1]
                require(j.audioIn == j.audioOut, "Every style permits a continuous custom audio crossfade ($style)")
                if (offset < 0) require(j.audioIn.endInclusive < j.videoRange.start, "J-cut finishes its audio blend before the picture transition ($style)")
                else require(j.audioOut.start > j.videoRange.endInclusive, "L-cut retains outgoing sound after the picture change ($style)")
            }
        }
        // Deterministic varied footprints exercise both neighbors, off-grid cuts,
        // custom audio and added-time joins without exposing any private media.
        var seed = 71uL
        fun random(): Double { seed = seed * 6364136223846793005uL + 1442695040888963407uL; return (seed shr 11).toDouble() / 9007199254740992.0 }
        val clips = original.clips
        for (run in 0 until 90) {
            val edit0 = original.copy()
            edit0.clips = edit0.clips + clips[0].copy(id = newUUID())
            for (i in edit0.clips.indices) { val end = 0.2 + random() * 3.8; edit0.updateClip(i) { it.copy(end = end) } }
            var edit = edit0
            for (boundary in 0..edit.clips.size) {
                val style = TransitionStyle.allCases[(run + boundary) % TransitionStyle.allCases.size]
                val duration = 0.1 + random() * 3
                var settings = ClipTransition(style = style, duration = duration)
                settings = settings.copy(hold = random() * 10)
                settings = settings.copy(audioOffset = random() * 4 - 2)
                settings = settings.copy(audioBlend = random() * 2)
                if (style == TransitionStyle.still) settings = settings.copy(stillDissolve = random())
                edit = edit.settingTransition(settings, boundary)
            }
            val p = base.roundTripped(); p.commit(edit)
            p.validate()
            for (placement in p.edit.layout.placements) {
                val (incoming, outgoing) = p.edit.layout.joins(placement)
                require(incoming.timing.rightCost + outgoing.timing.leftCost <= EditTime.of(placement.clip.duration).seconds + 1.0 / EditTime.scale,
                    "Long holds and custom sound fit available footage: run $run, clip ${placement.index}")
            }
            p.undo()
            val reopened = p.roundTripped(); reopened.redo()
            require(reopened.edit == edit && reopened.schemaVersion == 6, "All new transition settings survive saved Undo/Redo (run $run)")
        }
    }

    @Test fun flashFramesAndStyleMetadata() {
        val flashes = ClipTransition(style = TransitionStyle.flashCut, duration = 1.0)
        val frames = flashes.flashFrames(1.0)
        require(frames.size == 3 && frames.zipWithNext().all { (a, b) -> b - a >= 2 } && frames.last() <= 28, "Three single-frame flashes accelerate toward the cut: $frames")
        require(ClipTransition(style = TransitionStyle.flashCut, duration = 2.0 / 30, flashCount = 5).flashFrames(2.0 / 30).isEmpty(), "Extremely short durations limit distinct flashes")
        require(TransitionStyle.allCases.map { it.rawValue } == listOf("dissolve", "fadeBlack", "wipe", "split", "hitchcock", "whip", "flashCut", "still", "defocus"),
            "Every iOS style, in the iOS picker order")
        require(!TransitionStyle.flashCut.supportsHold && !TransitionStyle.still.supportsHold && TransitionStyle.dissolve.supportsHold, "Holds apply to moving transitions")
        require(!ClipTransition(duration = 0.0).valid && !ClipTransition(divider = 0.9).valid && !ClipTransition(flashCount = 21).valid && ClipTransition().valid, "Validity bounds")
    }

    @Test fun exportPlanPlaysTransitionClipsAndRampsTheSound() {
        val project = project()
        val edit = project.edit.settingTransition(ClipTransition(duration = 1.0), 1)
        val plan = ExportPlan.make(edit)
        val pieces = plan.video.map { it as ExportPlan.Piece.Clip }
        require(pieces.map { it.start.seconds to it.end.seconds } == listOf(0.0 to 3.0, 3.0 to 4.0, 4.0 to 7.0), "Clip, transition window, clip: ${pieces.map { it.start.seconds to it.end.seconds }}")
        require(pieces[1].transition == edit.timeline.transitions.single().id && pieces[0].sourceStart == 0.0 && pieces[2].sourceStart == 1.0,
            "The window plays its rendered clip; the incoming picture resumes after the window")
        require(plan.sourceIDs == project.sources.map { it.id }.toSet(), "A rendered window is not a source file")
        val envelopes = plan.audio.flatten().mapNotNull { (it as? ExportPlan.Piece.Clip)?.envelope }
        val outgoing = envelopes.single { it.outRamp != null }
        val incoming = envelopes.single { it.inRamp != null }
        require(outgoing.outRamp == 3.0..4.0 && incoming.inRamp == 3.0..4.0, "Both sounds ramp across the overlap")
        require(abs(outgoing.gain(3.5) - 0.5) < 0.01 && abs(incoming.gain(3.5) - 0.5) < 0.01 && incoming.gain(4.2) > 0.99, "Linear crossfade, as CutAudioEnvelope")
        // Dip to black with a hold: the hold is inside the window, the sound fades out and in around it.
        val dip = project.edit.settingTransition(ClipTransition(style = TransitionStyle.fadeColor, duration = 1.0, hold = 1.0), 1)
        val dipPlan = ExportPlan.make(dip)
        require(dipPlan.duration == et(9) && dipPlan.video.any { (it as? ExportPlan.Piece.Clip)?.let { c -> c.transition != null && c.start == et(3.5) && c.end == et(5.5) } == true },
            "The colour window covers fade out, hold and fade in")
        val previewSegments = PreviewPlan.segments(dip.timeline)
        require(previewSegments.map { it.start to it.end } == dipPlan.video.map { it.start.seconds to it.end.seconds } &&
            previewSegments[1].video?.transition?.id == dip.timeline.transitions.single().id, "The preview plays the export's partition")
    }

    @Test fun transitionClipKeysIgnoreAbsolutePosition() {
        val edit = project().edit.settingTransition(ClipTransition(duration = 1.0), 1)
        fun key(state: EditState) = TransitionRenderJob(state.timeline.transitionWindows().single(), 1280, 720, 30, emptyMap()).key
        val shifted = edit.copy(); shifted.timeline.insertTime(et(2), zero)
        require(shifted.timeline.transitionWindows().single().interval.start == et(5), "The window moved")
        require(key(shifted) == key(edit), "A moved transition reuses its rendered clip")
        require(key(edit.settingTransition(ClipTransition(duration = 1.5), 1)) != key(edit), "Changed settings render a new clip")
    }

    @Test fun transitionRegionsRemovalAndNavigation() {
        val original = project().edit
        val edit = original.settingTransition(ClipTransition(duration = 1.0), 1)
        val region = edit.toolRegions.single { it.tool == VideoTool.transition }
        require(region.range == 3.0..4.0 && region.label == "Dissolve", "A transition region spans its join")
        val removed = edit.removingTool(ToolRequest(VideoTool.transition, region.range, "Join 1", effectID = region.effectID, boundaryIndex = 1))
        require(removed.timeline.transitions.isEmpty() && removed.duration == original.duration, "Removing a transition restores a straight cut")
        val stops = TimelineNavigation(edit).cutsAndChanges
        require(3.5 in stops && 3.0 in stops && 4.0 in stops, "The join and its picture edges are navigation stops: $stops")
        val lanes = timelineLanes(edit.timeline)
        val pictures = lanes.filter { !it.audio }.sortedBy { it.interval.start }
        require(pictures[0].interval.end == et(3.5) && pictures[1].interval.start == et(3.5) && pictures[1].contentInterval.start == et(3),
            "The timeline folds a transition overlap at its join")
    }
}
