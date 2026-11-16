// YouRoll tests for titles. Ports the pure assertions of Tests/TitleDesignTests.swift
// (overlay timing, looks and outline, motion poses and wipes, persistence and
// motion clocks across cuts) and the text parts of Tests/VideoToolsTests.swift
// (tool persistence, scoped overlay removal), plus the Text tool's draft edits,
// the effect regions and the export plan. Rendering (fonts, outline, gradient,
// exported pixels) is checked on a device: androidTest TitleExportDeviceTest.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.File
import kotlin.math.abs

class TitleDesignTests {
    private val folder = File("build/tmp/titles")

    // ---- TitleDesignTests.swift: overlayTimingTests ----

    @Test fun overlayTiming() {
        val frame = TimelineEditing.frame
        val timing = OverlayTiming(3.125..6.125, 1.25..70.38)
        for (seconds in EditorDurationPresets.wholeSeconds) {
            require(EditorDurationPresets.fits(seconds, timing.available), "Expected a usable duration preset")
            timing.set(OverlayTiming.Edge.end, timing.range.start + seconds)
            require(timing.range == 3.125..(3.125 + seconds), "Preset moved Start or rounded the duration")
        }
        timing.set(OverlayTiming.Edge.start, 62.0)
        require(!EditorDurationPresets.fits(9.0, timing.available) && EditorDurationPresets.fits(8.0, timing.available), "Overlong presets must not change Start")
        timing.set(OverlayTiming.Edge.end, timing.time(1.0, OverlayTiming.Edge.end))
        require(timing.range.endInclusive == timing.bounds.endInclusive, "A fractional final boundary must be reachable")
        timing.set(OverlayTiming.Edge.end, timing.time(0.0, OverlayTiming.Edge.end))
        require(abs(timing.duration - frame) < 0.000001, "Dragging End past Start must preserve a frame")
        for (length in listOf(0.0, frame / 2, frame, frame * 1.4)) {
            val bounds = 8.125..(8.125 + length)
            val short = OverlayTiming(bounds, bounds)
            for (edge in OverlayTiming.Edge.entries) {
                for (fraction in listOf(0.0, 0.1, 0.95, 1.0)) {
                    short.set(edge, short.time(fraction, edge))
                    require(short.range.start in bounds && short.range.endInclusive in bounds && short.duration + 0.000001 >= minOf(length, frame),
                        "Tiny trim range exceeded its bounds")
                }
            }
        }
    }

    // ---- Looks, motion ----

    @Test fun looksKeepTheIndependentOutline() {
        val design = TitleDesign(face = "archivoblack", shadow = false, outlined = true, border = TitleInk.rgb(0x00ff00), borderWidth = 0.10,
            gradient = TitleGradient.of(0xff0000, 0x0000ff, 0.0))
        val look = design.applyingLook(TitleDesign.looks[1].second)
        require(look.outlined && look.border == design.border && look.borderWidth == design.borderWidth, "Looks changed the independent outline")
        require(TitleDesign.looks.map { it.first } == listOf("Clean", "Film title", "Lower third", "Headline", "Field note", "Dateline", "Warm editorial", "Sunset mono"),
            "Look gallery order")
        require(TitleDesign.looks.all { it.second.valid } && TitleDesign().valid, "Built-in looks are valid")
        require(!TitleDesign(weight = 50).valid && !TitleDesign(tracking = 0.3).valid && !TitleDesign(face = "").valid &&
            !TitleDesign(gradient = TitleGradient.of(0, 0, 400.0)).valid, "Design bounds")
    }

    @Test fun inkPacksRGBA() {
        val ink = TitleInk.rgb(0x112233, alpha = 128)
        require(ink.rgba == 0x11223380L && ink.rgb == 0x112233L && abs(ink.alpha - 128.0 / 255) < 1e-9, "RRGGBBAA packing")
        require(ink.argb == 0x80112233.toInt(), "Android ARGB")
        require(TitleInk.of(TextColor.yellow) == TitleInk.rgb(0xffd929), "Legacy colours round to bytes")
        val legacy = TitleDesign.legacy(TextStyle.highlight, TextColor.coral)
        require(legacy.fill == TitleInk.black && legacy.plate == TitlePlate.block && !legacy.shadow && legacy.backing == TitleInk.of(TextColor.coral),
            "Highlight: black on the colour")
        require(TitleDesign.legacy(TextStyle.clean, TextColor.white).weight == 400 && TitleDesign.legacy(TextStyle.bold, TextColor.white).weight == 700, "Legacy weight")
    }

    @Test fun gradientAxisCoversTheBox() {
        val (a, b) = TitleGradient.of(0, 0, 0.0).axis(CGSize(100.0, 40.0))
        require(a == CGPoint(0.0, 20.0) && b == CGPoint(100.0, 20.0), "0° runs left to right")
        val (c, d) = TitleGradient.of(0, 0, 90.0).axis(CGSize(100.0, 40.0))
        require(abs(c.x - 50) < 1e-9 && abs(c.y) < 1e-9 && abs(d.y - 40) < 1e-9, "90° runs top to bottom")
    }

    @Test fun motionPoses() {
        val motion = TitleMotion(entrance = TitleMove.fade, exit = TitleMove.wipe, inSeconds = 0.75, outSeconds = 0.75)
        for (t in listOf(0.13, 0.15, 0.17)) {
            val pose = motion.pose(t, 0.30)
            require(pose.opacity == 1.0 && pose.left == 0.0, "Short title lost its readable hold")
        }
        for (mode in TitleMove.entries.filter { it != TitleMove.none }) {
            val m = TitleMotion(entrance = mode, exit = mode)
            val t = 0.15
            val early = m.pose(t, 2.0)
            val late = m.pose(2 - t, 2.0)
            val mid = m.pose(1.0, 2.0)
            require(mid.opacity == 1.0 && mid.scale == 1.0 && mid.left == 0.0 && mid.right == 1.0, "Unstable title hold")
            if (mode == TitleMove.wipe) require(early.right < 1 && late.left > 0, "Wipe does not reveal and erase")
            else require(early.opacity < 1 && late.opacity < 1, "Animation missing on one end")
        }
        for (d in TitleDirection.entries) {
            val before = TitleMotion(entrance = TitleMove.wipe, inDirection = d).pose(0.1, 2.0)
            when (d) {
                TitleDirection.up -> require(before.top > 0 && before.bottom == 1.0, "Up wipe")
                TitleDirection.down -> require(before.top == 0.0 && before.bottom < 1, "Down wipe")
                TitleDirection.left -> require(before.left > 0 && before.right == 1.0, "Left wipe")
                TitleDirection.right -> require(before.left == 0.0 && before.right < 1, "Right wipe")
            }
        }
        val drift = TitleMotion(entrance = TitleMove.drift).pose(0.0, 2.0)
        require(drift.rise > 0 && drift.opacity == 0.0, "Drift up starts below and transparent (top-left coordinates)")
        require(TitleMotion().pose(0.0, 2.0) == TitleMotion.Pose() && !TitleMotion().active, "No motion is the identity")
        val window = TitleMotion(entrance = TitleMove.fade, exit = TitleMove.fade).previewWindow(exit = true, duration = 3.0)
        require(abs(window.start - 2.3) < 1e-9 && window.endInclusive == 3.0, "Exit preview window")
        require(TitleMotion(entrance = TitleMove.fade, inSeconds = 0.5).pose(0.25, 3.0).crop(100, 40)!!.contentEquals(intArrayOf(0, 0, 100, 40)), "No crop without wipe")
        val wiped = TitleMotion(entrance = TitleMove.wipe, inSeconds = 1.0).pose(0.5, 3.0)
        val visible = wiped.crop(100, 40)!!
        require(visible[0] == 0 && visible[2] in 49..51 && visible[3] == 40, "A right wipe shows the left part")
        val rect = wiped.rect(CGRect(0.0, 0.0, 100.0, 40.0))
        require(abs(rect.width - visible[2]) < 1.01 && rect.x == 0.0, "The pose's rect matches its crop")
    }

    // ---- Persistence and motion clocks (TitleDesignTests.swift) ----

    private fun animatedTitle() = TextOverlay(text = "HORIZON", start = 0.0, end = 1.5, size = 0.18,
        appearance = TitleDesign(face = "archivoblack", shadow = false, outlined = true, border = TitleInk.rgb(0x00ff00), borderWidth = 0.10,
            gradient = TitleGradient.of(0xff0000, 0x0000ff, 0.0)),
        motion = TitleMotion(entrance = TitleMove.fade, exit = TitleMove.wipe, inSeconds = 0.75, outSeconds = 0.75))

    @Test fun animatedTitleAcrossACut() {
        val title = animatedTitle()
        val project = Project("Animated title across a cut")
        project.append(SourceMedia(filename = "title-black.mp4", duration = 3.0))
        val source = project.sources[0].id
        project.commit(EditState(listOf(TimelineClip(sourceID = source, start = 0.0, end = 1.5, texts = listOf(title)),
            TimelineClip(sourceID = source, start = 0.0, end = 1.5, texts = listOf(title)))))
        project.validate()
        val decoded = project.roundTripped()
        require(decoded.edit.clips[0].texts?.first() == title, "Saved title design or motion failed to round-trip")
        require(TitleMotionRanges(decoded.edit).range(title, 1.5) == 0.0..3.0, "Title does not span its two source fragments")
        val edited = decoded.edit
        val different = title.copy(motion = title.motion!!.copy(exit = TitleMove.drift))
        edited.clips = edited.clips.mapIndexed { i, clip -> if (i == 1) clip.copy(texts = listOf(different)) else clip }
        val independent = TitleMotionRanges(edited)
        require(independent.range(title, 1.0) == 0.0..1.5 && independent.range(different, 2.0) == 1.5..3.0,
            "Editing motion on one side of a cut changed the other")
        val separated = decoded.edit
        separated.timeline.updateVideo(1) { it.copy(start = et(2)) }
        val parted = TitleMotionRanges(separated)
        require(parted.range(title, 1.6) == null && parted.range(title, 2.5) == 2.0..3.5, "Title motion ran through an empty gap")
    }

    @Test fun swiftCodableShape() {
        val title = animatedTitle()
        val json = SwiftJSON.encodeToJsonElement(TextOverlay.serializer(), title).jsonObject
        require(json.keys == setOf("id", "text", "start", "end", "x", "y", "size", "style", "color", "appearance", "motion"), "TextOverlay keys: ${json.keys}")
        val appearance = json.getValue("appearance").jsonObject
        require(appearance.keys == setOf("face", "weight", "italic", "alignment", "tracking", "lineSpacing", "fill", "gradient", "outlined", "border",
            "borderWidth", "shadow", "shade", "shadowBlur", "shadowOffset", "plate", "backing", "padding", "radius"), "TitleDesign keys: ${appearance.keys}")
        require(appearance.getValue("border").jsonObject.getValue("rgba").jsonPrimitive.long == 0x00ff00ffL, "TitleInk is {rgba: UInt32}")
        require(appearance.getValue("shade").jsonObject.getValue("rgba").jsonPrimitive.long == 0xd2L, "Shade alpha 210")
        require(json.getValue("motion").jsonObject.keys == setOf("entrance", "exit", "inSeconds", "outSeconds"), "Nil directions are omitted")
        // A title as Swift writes it: legacy fields only, no appearance or motion.
        val swift = """{"id":"0F8FAD5B-D9CB-469F-A165-70867728950E","text":"Hi","start":1,"end":3,"x":0.5,"y":0.8,"size":0.065,"style":"box","color":"yellow"}"""
        val legacy = SwiftJSON.decodeFromString(TextOverlay.serializer(), swift)
        require(legacy.appearance == null && legacy.style == TextStyle.box && legacy.design.plate == TitlePlate.block, "Legacy titles decode")
        val white = SwiftJSON.decodeFromString(TitleInk.serializer(), """{"rgba":4294967295}""")
        require(white == TitleInk.white, "Full 32-bit inks decode")
    }

    @Test fun validationBounds() {
        fun project(text: TextOverlay): Project {
            val project = Project("Title bounds")
            project.append(SourceMedia(filename = "source.mp4", duration = 4.0))
            val edit = project.edit
            edit.clips = edit.clips.map { it.copy(texts = listOf(text)) }
            project.commit(edit)
            return project
        }
        project(TextOverlay(text = "Hello", start = 1.0, end = 3.0)).validate()
        for (bad in listOf(TextOverlay(start = 3.0, end = 1.0), TextOverlay(end = 9.0), TextOverlay(x = 1.5), TextOverlay(size = 0.5),
            TextOverlay(text = "x".repeat(501)), TextOverlay(appearance = TitleDesign(weight = 2000)),
            TextOverlay(motion = TitleMotion(inSeconds = 9.0)))) {
            assertInvalidDocument("Invalid title $bad") { project(bad).validate() }
        }
        val duplicate = TextOverlay()
        val twice = Project("Duplicate title")
        twice.append(SourceMedia(filename = "source.mp4", duration = 4.0))
        val edit = twice.edit
        edit.clips = edit.clips.map { it.copy(texts = listOf(duplicate, duplicate)) }
        twice.commit(edit)
        assertInvalidDocument("Duplicate title IDs") { twice.validate() }
    }

    // ---- VideoToolsTests.swift: tool persistence and timing, quick removal ----

    @Test fun titlesFollowSourceFootage() {
        val project = Project("Tool persistence and timing")
        project.append(SourceMedia(filename = "source.mp4", duration = 4.0))
        val edit = project.edit
        edit.clips = edit.clips.map { it.copy(speed = speedJson(2.0), texts = listOf(TextOverlay(text = "Hello", start = 1.0, end = 3.0))) }
        project.commit(edit)
        project.validate()
        val split = TimelineEditing.split(edit, 0.8)
        require(split.clips.size == 2 && split.clips.all { it.texts == edit.clips[0].texts }, "A split keeps the title on both pieces")
        val ranges = split.toolRegions.filter { it.tool == VideoTool.text }.map { it.range }
        require(TitleMotionRanges(split).isEmpty && ranges.size == 2 && listOf(0.5, 0.8, 0.8, 1.5).zip(ranges.flatMap { listOf(it.start, it.endInclusive) }).all { abs(it.first - it.second) < 1e-9 },
            "The title follows the speed-mapped footage on each side of the split")
        val removed = TimelineEditing.removing(0.5..1.5, edit)
        require(removed.toolRegions.none { it.tool == VideoTool.text }, "Ripple removal of the titled footage removes its visible title")
        val pauseClip = edit.clips[0].copy(pauses = pausesJson(1.0 to 2.0))
        val restored = pauseClip.copy(pauses = null)
        require(restored.texts == pauseClip.texts, "Removing Pauses restores media and retains other effects")
        project.undo()
        val reopened = project.roundTripped(); reopened.redo(); reopened.validate()
        require(reopened.edit == edit, "All tool settings survive undo/redo and document reopening")
    }

    // ---- The Text tool's draft (EditorTools.swift ToolWorkspace, .text) ----

    private fun twoClips(): Pair<Project, EditState> {
        val project = Project("Text tool")
        project.append(SourceMedia(filename = "source.mp4", duration = 8.0))
        project.commit(TimelineEditing.split(project.edit, 4.0))
        return project to project.edit
    }

    @Test fun aNewTitleCoversEveryClipInItsRangeInSourceTime() {
        val (project, edit) = twoClips()
        val ids = edit.clips.map { it.id }.toSet()
        val model = TextToolWorkspace(project, folder, ToolRequest(VideoTool.text, 3.0..6.0, "V1", clipIDs = ids))
        require(model.text.text.isEmpty() && !model.canApply, "New text starts empty and cannot be applied")
        model.text = model.text.copy(text = "Across the cut")
        val applied = model.state()
        require(applied.clips[0].texts!!.single().let { it.start == 3.0 && it.end == 4.0 && it.id == model.effectID } &&
            applied.clips[1].texts!!.single().let { it.start == 4.0 && it.end == 6.0 && it.id == model.effectID },
            "One identity, a fragment per clip in its source time")
        require(model.state(forPreview = true).clips.all { it.texts == null }, "The preview edit leaves the draft out (it is drawn as previewTitle)")
        project.commit(applied)
        val regions = project.edit.toolRegions.filter { it.tool == VideoTool.text }
        require(regions.map { it.range } == listOf(3.0..4.0, 4.0..6.0) && regions.all { it.label == "Across the cut" }, "Regions on the project clock")
        // A fragment reopened from its region edits only that fragment; a style-only edit keeps its source timing.
        val request = project.edit.request(regions[1])
        require(request.clipIDs == setOf(project.edit.clips[1].id) && request.range == 4.0..6.0 && request.scopeTitle == "This clip", "Region request")
        val reopened = TextToolWorkspace(project, folder, request)
        require(reopened.text.text == "Across the cut" && reopened.timing.bounds == 4.0..8.0, "Duration controls stay within its associated clips")
        reopened.setTitleDesign(TitleDesign(face = "caveat"))
        val styled = reopened.state()
        require(styled.clips[1].texts!!.single().let { it.start == 4.0 && it.end == 6.0 && it.design.face == "caveat" } &&
            styled.clips[0].texts!!.single().design.face == "system", "Separated fragments are edited individually")
        val removed = reopened.state(removing = true)
        require(removed.clips[0].texts!!.size == 1 && removed.clips[1].texts == null, "Removing a separated overlay respects the same scope as editing it")
        // Trimming the draft re-times it from the new range.
        reopened.trim(OverlayTiming.Edge.end, 5.0, previewEdge = false)
        require(reopened.state().clips[1].texts!!.single().let { it.start == 4.0 && abs(it.end - 5.0) < 1e-9 }, "Trim changes the fragment's source end")
    }

    @Test fun motionReplayUsesTheSavedClock() {
        val (project, edit) = twoClips()
        val title = TextOverlay(text = "Run", start = 3.0, end = 6.0, motion = TitleMotion(entrance = TitleMove.fade, exit = TitleMove.fade))
        edit.clips = edit.clips.map { it.copy(texts = listOf(title)) }
        project.commit(edit)
        val region = project.edit.toolRegions.first { it.tool == VideoTool.text && it.range.start == 4.0 }
        val model = TextToolWorkspace(project, folder, project.edit.request(region))
        require(model.titleMotionRange == 3.0..6.0, "Reopening one fragment keeps the motion clock of the whole run")
        model.text = model.text.copy(motion = TitleMotion(entrance = TitleMove.scale))
        require(model.titleMotionRange == 4.0..6.0, "Changed motion runs on the fragment's own range")
    }

    // ---- Export plan ----

    @Test fun exportPlanCarriesTitles() {
        val (project, edit) = twoClips()
        val title = TextOverlay(text = "On", start = 1.0, end = 2.0)
        edit.clips = edit.clips.mapIndexed { i, clip -> if (i == 0) clip.copy(texts = listOf(title)) else clip }
        project.commit(edit)
        val plan = ExportPlan.make(project.edit)
        val pieces = plan.video.filterIsInstance<ExportPlan.Piece.Clip>()
        require(pieces.size == 2 && pieces[0].titles == listOf(title) && pieces[1].titles.isEmpty(), "Titled and untitled footage stay separate pieces")
        require(plan.titleRanges.isEmpty, "No motion, no shared clock")
        val legacy = TextOverlay(text = "Old", start = 0.0, end = 1.0, style = TextStyle.highlight)
        val oldEdit = project.edit
        oldEdit.clips = oldEdit.clips.map { it.copy(texts = listOf(legacy)) }
        ExportPlan.make(oldEdit) // titles are no longer refused
    }

    @Test fun aTitleOverASpeedRangeFollowsItsFootage() {
        val project = Project("Title over speed")
        project.append(SourceMedia(filename = "source.mp4", duration = 8.0))
        var edit = TimelineEditing.split(TimelineEditing.split(project.edit, 2.0), 4.0)
        val title = TextOverlay(text = "Fast", start = 2.5, end = 3.5)
        edit.clips = edit.clips.mapIndexed { i, clip -> if (i == 1) clip.copy(speed = SpeedSettings(rate = 2.0), texts = listOf(title), crop = CropSettings(zoom = 2.0)) else clip }
        project.commit(edit); project.validate()
        edit = project.edit
        require(abs(edit.duration - 7.0) < 1e-9, "2× halves the middle second")
        val regions = edit.toolRegions
        require(regions.map { it.tool }.toSet() == setOf(VideoTool.crop, VideoTool.speed, VideoTool.text), "In this project lists Crop, Speed and Text")
        val text = regions.single { it.tool == VideoTool.text }
        require(abs(text.range.start - 2.25) < 1e-9 && abs(text.range.endInclusive - 2.75) < 1e-9, "The title's span is retimed with its footage")
        val piece = ExportPlan.make(edit).video.filterIsInstance<ExportPlan.Piece.Clip>().single { it.titles.isNotEmpty() }
        require(abs(piece.rate - 2.0) < 1e-9 && piece.titles == listOf(title) && piece.start == et(2) && piece.duration == et(1),
            "The export piece carries the title on its 2× source clock")
        val removed = edit.removingTool(edit.request(text))
        require(removed.clips[1].texts == null && removed.clips[1].speed?.rate == 2.0, "Removing the title keeps the speed")
    }

    @Test fun rasterOriginIsClampedToTheFrame() {
        val canvas = CGSize(1920.0, 1080.0)
        val image = CGSize(400.0, 100.0)
        require(TextRasterizer.origin(TextOverlay(x = 0.5, y = 0.5), image, canvas) == CGPoint(760.0, 490.0), "Centred")
        require(TextRasterizer.origin(TextOverlay(x = 0.0, y = 0.0), image, canvas) == CGPoint(0.0, 980.0), "Top-left corner (y-up origin)")
        require(TextRasterizer.origin(TextOverlay(x = 1.0, y = 1.0), image, canvas) == CGPoint(1520.0, 0.0), "Bottom-right corner")
    }

    @Test fun regionsAndJsonOfOtherPayloadsStayIntact() {
        val (project, edit) = twoClips()
        edit.clips = edit.clips.map { it.copy(crop = CropSettings(zoom = 2.0)) }
        project.commit(edit)
        val crops = project.edit.toolRegions.filter { it.tool == VideoTool.crop }
        require(crops.size == 2 && crops.all { it.label == "2.0×" }, "Crop regions are listed with their zoom")
        val json = SwiftJSON.encodeToJsonElement(TimelineClip.serializer(), project.edit.clips[0]) as JsonObject
        require("texts" !in json, "No texts key without titles (Swift omits nil)")
    }
}
