// YouRoll JVM tests for editor stabilization: a port of the model part of
// ../Tests/StabilizationTests.swift (smoothing, pan and zoom preservation, crop
// inclusion, scene resets, levels, persistence, legacy clips), plus the Kotlin
// parts of the port: the Swift JSON shape, the upright/NDC correction shared by
// preview and export, tool regions and removal, the stabilize draft and the
// export plan. The motion estimator's own tests are
// ../Tests/StabilizationMotionTests.cpp, built on the host by scripts/check.sh.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class StabilizationTests {
    private val frames = 240
    private val fps = 30.0
    private val size = CGSize(640.0, 360.0)
    private val pan = (0 until frames).map { StabilizationPose(x = it * 1.1, y = it * -0.2) }

    private fun analysis(poses: List<StabilizationPose>, duration: Double = 8.0, tracked: Int = frames - 1) =
        StabilizationAnalysis(640, 360, duration, poses.mapIndexed { index, pose -> StabilizationSample(index / fps, pose, 0) }, tracked)

    private fun noisyPoses(): List<StabilizationPose> = pan.mapIndexed { i, pose ->
        val t = i / fps
        pose.copy(x = pose.x + 6 * sin(2 * PI * 4.1 * t), y = pose.y + 4 * sin(2 * PI * 3.3 * t), angle = 0.008 * sin(2 * PI * 2.7 * t))
    }

    @Test fun constantSpeedPansRemainUnchanged() {
        val plan = StabilizationPath.make(analysis(pan), StabilizationLevel.strong)
        require(plan.samples.all { abs(it.pose.x) < 0.00001 && abs(it.pose.y) < 0.00001 }, "Constant-speed pans remain unchanged, including clip endpoints")
    }

    @Test fun strongerLevelsSmoothMoreWithinTheirCrop() {
        val poses = noisyPoses()
        val noisy = analysis(poses)
        var previousResidual = Double.POSITIVE_INFINITY
        for (level in StabilizationLevel.entries.filter { it != StabilizationLevel.off }) {
            val plan = StabilizationPath.make(noisy, level)
            val residual = plan.samples.withIndex().drop(30).toList().dropLast(30).sumOf { (index, sample) ->
                val stable = sample.pose.applying(poses[index])
                (stable.x - pan[index].x).pow(2) + (stable.y - pan[index].y).pow(2)
            } / (frames - 60)
            require(residual < previousResidual + 0.05, "Increasing stabilization levels does not increase residual shake")
            require(plan.zoom <= level.maximumZoom + 0.000001, "Crop respects the selected level's bound")
            for (frame in 0 until frames * 3) {
                val inverse = plan.transform(frame / 90.0).inverted()
                for (corner in listOf(CGPoint.zero, CGPoint(size.width, 0.0), CGPoint(0.0, size.height), CGPoint(size.width, size.height))) {
                    val source = corner.applying(inverse)
                    require(source.x >= -0.001 && source.y >= -0.001 && source.x <= size.width + 0.001 && source.y <= size.height + 0.001,
                        "Every output corner stays inside the source, also between analyzed frames")
                }
            }
            println("STABILIZATION: ${level.rawValue}, residual MSE $residual, crop ${plan.cropPercent}%")
            previousResidual = residual
        }
        require(previousResidual < 2, "Strong stabilization removes most high-frequency translation")
        val off = StabilizationPath.make(noisy, StabilizationLevel.off)
        require(off.zoom == 1.0 && off.transform(1.0) == CGAffineTransform.identity, "Off is exact identity")
    }

    @Test fun recordedZoomIsNeverSmoothed() {
        val zoomAnalysis = analysis((0 until frames).map { StabilizationPose(logScale = it / fps * ln(2.0) / 2) })
        val zoomPlan = StabilizationPath.make(zoomAnalysis, StabilizationLevel.strong)
        require(zoomPlan.zoom == 1.0 && zoomPlan.samples.all { abs(it.pose.logScale) < 1e-10 }, "Intentional recorded zoom is never smoothed or retimed")
        val panZoom = StabilizationAnalysis(640, 360, 15.0, (0 until 450).map { i ->
            val t = i / fps
            val cameraX = min(t, 4.0) * 200
            val zoom = 1 + min(1.0, max(0.0, (t - 7) / 2))
            StabilizationSample(t, StabilizationPose(x = -zoom * cameraX, logScale = ln(zoom)), 0)
        }, 449)
        for (level in listOf(StabilizationLevel.standard, StabilizationLevel.strong)) {
            val result = StabilizationPath.make(panZoom, level)
            require(result.samples.filter { it.time >= 6.5 }.all { abs(it.pose.x) < 0.0001 && abs(it.pose.y) < 0.0001 },
                "Zoom after a completed pan introduces no false translation")
        }
    }

    @Test fun smoothingNeverCrossesACut() {
        val noisy = analysis(noisyPoses())
        val cutSamples = noisy.samples.mapIndexed { i, sample -> if (i >= 120) sample.copy(segment = 1, pose = StabilizationPose.identity) else sample }
        val cutPlan = StabilizationPath.make(noisy.copy(samples = cutSamples, trackedFrames = 238), StabilizationLevel.strong)
        require(cutPlan.samples.drop(120).all { abs(it.pose.x) < 0.000001 && abs(it.pose.y) < 0.000001 }, "Smoothing never crosses a cut or tracking reset")
        require(StabilizationPath.make(noisy.copy(samples = cutSamples), StabilizationLevel.light).samples.size == frames, "One correction per analysed frame")
        var cancelled = false
        try { StabilizationPath.make(noisy, StabilizationLevel.strong) { throw kotlinx.coroutines.CancellationException("stop") } }
        catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        require(cancelled, "Building a plan can be cancelled (Swift Task.checkCancellation)")
    }

    @Test fun persistenceUndoSplitAndLegacyClips() {
        val p = Project("Stabilization persistence")
        p.append(SourceMedia(filename = "source.mp4", duration = 4.0))
        val edit = p.edit.copy()
        edit.updateClip(0) { it.copy(stabilization = StabilizationSettings(StabilizationLevel.strong)) }
        p.commit(edit)
        p.undo()
        val reopened = p.roundTripped()
        reopened.validate(); reopened.redo()
        require(reopened.edit.clips[0].stabilization?.level == StabilizationLevel.strong, "Stabilization survives persistent undo and redo")
        val split = TimelineEditing.split(reopened.edit, 2.0)
        require(split.clips.all { it.stabilization?.level == StabilizationLevel.strong }, "Split inherits per-clip stabilization")
        val legacy = SwiftJSON.decodeFromString(TimelineClip.serializer(),
            "{\"id\":\"${newUUID().uuidString}\",\"sourceID\":\"${p.sources[0].id.uuidString}\",\"start\":0,\"end\":4}")
        require(legacy.stabilization == null, "Old clips decode with stabilization off")
    }

    @Test fun swiftCodableShapeAndValidation() {
        val id = newUUID()
        val settings = StabilizationSettings(StabilizationLevel.standard, id = id, referenceStart = 0.5, referenceEnd = 3.5)
        val json = SwiftJSON.encodeToJsonElement(StabilizationSettings.serializer(), settings).jsonObject
        require(json.keys == setOf("level", "version", "id", "referenceStart", "referenceEnd") && json["level"] == JsonPrimitive("standard") &&
            json["version"] == JsonPrimitive(1), "Swift `StabilizationSettings` keys and raw values")
        val bare = SwiftJSON.encodeToJsonElement(StabilizationSettings.serializer(), StabilizationSettings(StabilizationLevel.off)).jsonObject
        require(bare.keys == setOf("level", "version"), "Swift omits nil optionals; `off` keeps its raw value")
        val sample = SwiftJSON.encodeToJsonElement(StabilizationAnalysis.serializer(),
            StabilizationAnalysis(640, 360, 1.0, listOf(StabilizationSample(0.0, StabilizationPose(1.0, 2.0, 0.1, 0.01), 0)), 0)).jsonObject
        require(sample.keys == setOf("width", "height", "duration", "samples", "trackedFrames"), "Analysis cache keeps the Swift keys")
        require(sample["samples"].toString().contains("\"logScale\"") && sample["samples"].toString().contains("\"segment\""), "Pose and sample keys")
        val p = Project("Validation")
        p.append(SourceMedia(filename = "source.mp4", duration = 4.0))
        for ((bad, message) in listOf(
            StabilizationSettings(StabilizationLevel.light, version = 2) to "An unknown version is refused",
            StabilizationSettings(StabilizationLevel.light, referenceStart = 1.0) to "A half reference range is refused",
            StabilizationSettings(StabilizationLevel.light, referenceStart = 1.0, referenceEnd = 9.0) to "A reference beyond the source is refused",
        )) {
            val edit = p.edit.copy()
            edit.updateClip(0) { it.copy(stabilization = bad) }
            val candidate = p.copy(); candidate.commit(edit)
            assertInvalidDocument(message) { candidate.validate() }
        }
    }

    @Test fun referenceRangeSurvivesTrims() {
        val clip = TimelineClip(sourceID = newUUID(), start = 1.0, end = 5.0)
        val settings = clip.stabilizing(StabilizationLevel.light)
        require(settings.referenceStart == 1.0 && settings.referenceEnd == 5.0, "Applying records the analysed take range")
        val trimmed = clip.copy(start = 2.0, end = 3.0, stabilization = settings)
        require(trimmed.stabilizationStart == 1.0 && trimmed.stabilizationEnd == 5.0, "A trim within the range keeps the analysed reference")
        val extended = trimmed.copy(start = 0.5)
        require(extended.stabilizationStart == 0.5, "Extending beyond the range needs a fresh analysis of the expanded range")
        val reference = trimmed.copy(stabilization = settings.copy(id = newUUID())).stabilizationReference
        require(reference == StabilizationSettings(StabilizationLevel.light, 1, null, 1.0, 5.0), "Rendering keys ignore the effect identity")
        require(clip.copy(stabilization = StabilizationSettings(StabilizationLevel.off)).stabilizationReference == null, "Off renders nothing")
    }

    @Test fun planHoldsTheShownSourceFrame() {
        val samples = (0 until 10).map { StabilizationSample(it / 25.0, StabilizationPose(x = it.toDouble()), 0) }
        val plan = StabilizationPlan(size, samples, 1.0, false, 1.0)
        require(plan.transform(0.0).tx == 0.0 && plan.transform(-1.0).tx == 0.0, "Before the first frame: the first frame")
        require(plan.transform(1 / 25.0 - 0.0000005).tx == 1.0, "A microsecond early still finds the frame (Swift's tolerance)")
        require(plan.transform(1.5 / 25.0).tx == 1.0, "Between frames: the frame on screen, never an interpolated one")
        val correction = StabilizationCorrection(plan, 2.0, 0)
        require(correction.coded(2.0 + 3 / 25.0 - 0.00004).tx == 3.0, "Microsecond-rounded frame times map to their own frame")
    }

    @Test fun uprightCorrectionMatchesTheCodedOne() {
        // A coded 640×360 frame, shown rotated 90° (portrait): correcting coded pixels and then rotating
        // equals rotating and then applying the upright correction.
        val plan = StabilizationPlan(size, listOf(StabilizationSample(0.0, StabilizationPose(12.0, -7.0, 0.02), 0)), 1.05, false, 1.0)
        for (rotation in listOf(0, 90, 180, 270)) {
            val correction = StabilizationCorrection(plan, 0.0, rotation)
            val toOriented = StabilizationCorrection.orientedToCoded(rotation, size).inverted()
            for (point in listOf(CGPoint(10.0, 20.0), CGPoint(600.0, 300.0), CGPoint(320.0, 180.0))) {
                val a = point.applying(correction.coded(0.0)).applying(toOriented)
                val b = point.applying(toOriented).applying(correction.upright(0.0))
                require(abs(a.x - b.x) < 1e-6 && abs(a.y - b.y) < 1e-6, "Rotation $rotation: the upright correction is the coded one, conjugated")
            }
        }
        // NDC: a pure translation by (dx, dy) pixels moves NDC by (2dx/w, -2dy/h).
        val ndc = StabilizationCorrection.ndcMatrix(CGAffineTransform.translation(64.0, 36.0), size)
        require(abs(ndc[2] - 0.2f) < 1e-6 && abs(ndc[5] + 0.2f) < 1e-6 && ndc[0] == 1f && ndc[4] == 1f, "Pixel translation in NDC (y up)")
        val zoomed = StabilizationCorrection.ndcMatrix(StabilizationPose.identity.transform(size, 1.1), size)
        require(abs(zoomed[0] - 1.1f) < 1e-6 && abs(zoomed[2]) < 1e-6 && abs(zoomed[5]) < 1e-6, "The crop zoom magnifies about the centre")
    }

    @Test fun regionsRemovalAndTheDraft() {
        val p = Project("Tools")
        p.append(SourceMedia(filename = "source.mp4", duration = 6.0))
        val id = newUUID()
        val edit = p.edit.copy()
        edit.updateClip(0) { it.copy(stabilization = it.stabilizing(StabilizationLevel.standard).copy(id = id)) }
        val region = edit.toolRegions.single { it.tool == VideoTool.stabilize }
        require(region.effectID == id && region.label == "Stabilize" && region.range == 0.0..6.0, "Swift `toolRegions`: one Stabilize region per clip")
        val split = TimelineEditing.split(edit, 3.0)
        require(split.toolRegions.filter { it.tool == VideoTool.stabilize }.map { it.effectID }.toSet() == setOf(id), "Split pieces share one identity")
        val request = split.request(region)
        require(request.tool == VideoTool.stabilize && request.clipIDs?.size == 1, "A badge targets its own segment")
        val removed = split.removingTool(split.request(split.toolRegions.first { it.tool == VideoTool.stabilize }))
        require(removed.clips.count { it.stabilization != null } == 1, "Badge removal stays on its segment")
        val legacy = EditState(listOf(edit.clips[0].copy(stabilization = StabilizationSettings(StabilizationLevel.light))))
        val legacyRegion = legacy.toolRegions.single { it.tool == VideoTool.stabilize }
        require(legacyRegion.effectID == legacy.clips[0].id, "A settings without an id is identified by its clip")
        require(legacy.removingTool(legacy.request(legacyRegion)).clips[0].stabilization == null, "and can still be removed")
        require(EditState(listOf(edit.clips[0].copy(stabilization = StabilizationSettings(StabilizationLevel.off)))).toolRegions.none { it.tool == VideoTool.stabilize },
            "Off shows no region")
        // The draft (Swift `state(removing:)`): one identity, the reference range, Off and Remove clear it.
        p.commit(edit)
        val workspace = ToolWorkspace(p, java.io.File("."), ToolRequest(VideoTool.stabilize, 0.0..6.0, "Whole project", effectID = id), 2.0)
        require(workspace.savedStabilizationLevel == StabilizationLevel.standard && workspace.level == StabilizationLevel.standard, "Reopening reviews the saved level")
        workspace.level = StabilizationLevel.strong
        val draft = workspace.state().clips[0].stabilization
        require(draft?.level == StabilizationLevel.strong && draft.id == id && draft.referenceStart == 0.0 && draft.referenceEnd == 6.0, "The draft keeps the identity and reference")
        workspace.level = StabilizationLevel.off
        require(workspace.state().clips[0].stabilization == null, "Off removes the effect")
        require(workspace.state(removing = true).clips[0].stabilization == null, "Remove clears it")
        val fresh = ToolWorkspace(Project("New").also { it.append(SourceMedia(filename = "a.mp4", duration = 4.0)) }, java.io.File("."),
            ToolRequest(VideoTool.stabilize, 0.0..4.0, "Whole project"), 0.0)
        require(fresh.savedStabilizationLevel == null && fresh.level == StabilizationLevel.standard, "A new effect starts at Standard")
    }

    @Test fun previewWindowSamplesTenSecondsAroundTheAnchor() {
        require(EffectPreviewWindow.range(2.0, 0.0..30.0) == 0.0..7.0, "Clamped at the start")
        require(EffectPreviewWindow.range(20.0, 0.0..30.0) == 15.0..25.0, "Five seconds each side")
        require(EffectPreviewWindow.label(10.0..20.0) == "10 s preview" && EffectPreviewWindow.label(0.0..0.4) == "0.4 s preview", "Swift labels")
        val source = newUUID()
        val clips = listOf(TimelineClip(sourceID = source, start = 10.0, end = 30.0, stabilization = StabilizationSettings(StabilizationLevel.light)))
        val window = EffectPreviewWindow(EffectPreviewWindow.range(12.0, 0.0..20.0), TrackTimeline(clips))
        val sample = window.analysisClip(clips[0])!!
        require(sample.start == 17.0 && sample.end == 27.0, "The sample is the clip's source span inside the window")
    }

    @Test fun exportPiecesCarryTheReference() {
        val source = newUUID()
        val clip = TimelineClip(sourceID = source, start = 0.0, end = 6.0, stabilization = StabilizationSettings(StabilizationLevel.strong, id = newUUID()))
        val edit = TimelineEditing.split(EditState(listOf(clip.copy(stabilization = clip.stabilizing(StabilizationLevel.strong).copy(id = clip.stabilization!!.id)))), 3.0)
        val plan = ExportPlan.make(edit)
        val pieces = plan.video.filterIsInstance<ExportPlan.Piece.Clip>()
        require(pieces.size == 1 && pieces[0].stabilization == StabilizationSettings(StabilizationLevel.strong, 1, null, 0.0, 6.0),
            "A split stabilized take stays one export piece with one reference (same crop on both sides)")
        val windows = EditState(listOf(clip)).timeline.transitionWindows()
        require(windows.isEmpty(), "No transition, no window")
    }
}
