// YouRoll tests for Crop and video shape presets. Ports the crop assertions of
// Tests/VideoToolsTests.swift (geometry, persistence, split) and the
// CropDiagnostics checks in YouRec/CropEditor.swift (workspace scope, neutral
// crops, shapes, reopening, Remove crop, mixed geometry, retry), plus the
// Android export and preview mappings.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.math.abs

class CropTests {
    private val landscape = CGSize(1920.0, 1080.0)
    private val portrait = CGSize(1080.0, 1920.0)

    // ---- Geometry (VideoToolsTests.swift) ----

    @Test fun cropAtAnEdgeStaysInsideTheSourceWithoutStretching() {
        for (source in listOf(landscape, portrait)) {
            for (aspect in listOf(16.0 / 9, 9.0 / 16, 1.0, 4.0 / 5)) {
                val canvas = OutputShape.canvas(source, aspect)
                for (zoom in listOf(1.0, 2.0, 8.0)) {
                    val area = CropSettings(x = 0.98, y = 0.02, zoom = zoom).rect(source, canvas)
                    require(area.minX >= -0.00001 && area.minY >= -0.00001 && area.maxX <= source.width + 0.00001 && area.maxY <= source.height + 0.00001,
                        "Crop at an edge always stays inside the source")
                    require(abs(area.width / area.height - canvas.width / canvas.height) < 0.00001, "Crop never stretches the selected area")
                }
            }
        }
    }

    @Test fun evenPixelRoundingIsNotACrop() {
        val oddSource = CGSize(1179.0, 2556.0)
        val evenCanvas = CGSize(1180.0, 2556.0)
        require(CropSettings().isFullFrame(oddSource, evenCanvas), "Even-pixel rounding of odd imported media does not create a crop")
        require(!CropSettings(zoom = 1.01).isFullFrame(oddSource, evenCanvas), "A deliberate zoom remains an edit on odd imported media")
        require(!CropSettings().isFullFrame(oddSource, portrait), "A different aspect still crops odd imported media")
    }

    @Test fun rotatedCropNeverExposesAnEmptyCorner() {
        for (source in listOf(landscape, portrait)) {
            for (aspect in listOf(16.0 / 9, 9.0 / 16, 1.0, 4.0 / 5)) {
                val canvas = OutputShape.canvas(source, aspect)
                for (angle in listOf(-180.0, -135.0, -90.0, -45.0, -17.0, 0.0, 17.0, 45.0, 90.0, 135.0, 180.0)) {
                    for (zoom in listOf(1.0, 2.0, 8.0)) {
                        val inverse = CropSettings(x = 0.98, y = 0.02, zoom = zoom, rotation = angle).transform(source, canvas).inverted()
                        for (corner in listOf(CGPoint.zero, CGPoint(canvas.width, 0.0), CGPoint(0.0, canvas.height), CGPoint(canvas.width, canvas.height))) {
                            val point = corner.applying(inverse)
                            require(point.x >= -0.00001 && point.y >= -0.00001 && point.x <= source.width + 0.00001 && point.y <= source.height + 0.00001,
                                "Rotation, aspect, pan and zoom never expose an empty corner ($angle°, ×$zoom)")
                        }
                    }
                }
            }
        }
        require(!CropSettings(rotation = 180.0).isFullFrame(landscape, landscape), "An upside-down full frame remains a real edit")
    }

    @Test fun presetsKeepTheShortSideResolution() {
        require(OutputShape.canvas(landscape, 9.0 / 16) == CGSize(1080.0, 1920.0), "9:16 from landscape keeps 1080")
        require(OutputShape.canvas(landscape, 1.0) == CGSize(1080.0, 1080.0), "1:1 from landscape")
        require(OutputShape.canvas(portrait, 16.0 / 9) == CGSize(1920.0, 1080.0), "16:9 from portrait keeps 1080")
        require(OutputShape.canvas(portrait, 4.0 / 5) == CGSize(1080.0, 1350.0), "4:5 from portrait")
        require(OutputShape.canvas(CGSize(1179.0, 2556.0), 1.0) == CGSize(1180.0, 1180.0), "Rounded to even pixels")
        require(OutputShape.canvas(landscape, null) == landscape, "Original keeps the source")
        require(OutputShape.entries.map { it.title } == listOf("Original", "16:9", "9:16", "1:1", "4:5"), "Menu order and titles")
        require(CropSettings.normalizedRotation(270.0) == -90.0 && CropSettings.normalizedRotation(180.0) == 180.0 &&
            CropSettings.normalizedRotation(-180.0) == -180.0 && CropSettings.normalizedRotation(-190.0) == 170.0, "Rotation normalizes to ±180")
    }

    @Test fun cropAndOutputFormatSurviveReopeningUndoRedoAndSplit() {
        val project = Project("Crop persistence")
        project.append(SourceMedia(filename = "source.mp4", duration = 4.0))
        val cropEdit = project.edit
        cropEdit.outputSize = CGSize(360.0, 640.0)
        cropEdit.clips = cropEdit.clips.mapIndexed { i, clip -> if (i == 0) clip.copy(crop = CropSettings(x = 0.75, y = 0.3, zoom = 2.0, rotation = 17.0)) else clip }
        project.commit(cropEdit)
        val reopened = project.roundTripped()
        reopened.undo(); reopened.redo(); reopened.validate()
        require(reopened.edit == cropEdit, "Crop and output format survive reopening and undo/redo")
        val split = TimelineEditing.split(cropEdit, 1.0)
        require(split.outputSize == cropEdit.outputSize && split.clips.size == 2 && split.clips.all { it.crop == cropEdit.clips[0].crop },
            "Splitting preserves crop identity and output format")
        val json = SwiftJSON.encodeToJsonElement(CropSettings.serializer(), cropEdit.clips[0].crop!!) as kotlinx.serialization.json.JsonObject
        require(json.keys == setOf("id", "x", "y", "zoom", "rotation"), "Swift Codable field names, nil motion omitted")
        val older = SwiftJSON.decodeFromString(CropSettings.serializer(), """{"id":"0F8FAD5B-D9CB-469F-A165-70867728950E","x":0.25,"y":0.5,"zoom":2}""")
        require(older.rotation == 0.0 && older.zoom == 2.0, "Older crops without rotation decode")
        val invalid = project.copy()
        val bad = project.edit; bad.clips = bad.clips.map { it.copy(crop = CropSettings(zoom = 9.0)) }; invalid.commit(bad)
        assertInvalidDocument("Zoom beyond 8× is invalid") { invalid.validate() }
    }

    // ---- Workspace (CropDiagnostics in CropEditor.swift) ----

    /** Fixed geometry by filename; files named in [missing] fail like a deleted source. */
    private class FakeMedia(val sizes: Map<String, CGSize>, val missing: MutableSet<String> = mutableSetOf()) : CropMedia {
        var frames = 0
        override suspend fun canvas(project: Project, folder: File): CGSize {
            val edit = project.edit
            edit.outputSize?.let { return it }
            val source = project.sources.first { it.id == edit.canvasSourceID }
            return sourceSize(source, folder)
        }
        override suspend fun sourceSize(source: SourceMedia, folder: File): CGSize {
            if (source.filename in missing) throw ProjectError.missingMedia
            return sizes.getValue(source.filename)
        }
        override suspend fun frame(source: SourceMedia, folder: File, sourceTime: Double): Bitmap? {
            if (source.filename in missing) throw ProjectError.missingMedia
            delay(5); frames += 1; return null
        }
    }

    private val media = FakeMedia(mapOf("shake.mp4" to landscape, "portrait.mov" to portrait, "square.mp4" to CGSize(1080.0, 1080.0)))

    private suspend fun ready(model: CropWorkspace) {
        var waited = 0
        while (model.busy && waited < 10_000) { delay(5); waited += 5 }
        require(!model.busy && model.error == null, model.error ?: "Crop preparation timed out")
    }

    private fun workspace(project: Project, request: ToolRequest, position: Double, context: kotlin.coroutines.CoroutineContext) =
        CropWorkspace(project, File("unused"), request, position, media, context)

    @Test fun selectedRangeCropIsolatesItsBoundariesAndPinsTheShape() = runBlocking {
        val duration = 6.0
        val project = Project("Crop hardware diagnostic")
        project.append(SourceMedia(filename = "shake.mp4", duration = duration))
        val request = ToolRequest(VideoTool.crop, 1.0..3.0, "Selected range")
        val model = workspace(project, request, 2.0, coroutineContext)
        model.start(); ready(model)
        require(model.frame != null && abs(model.position - 1) < 0.001, "Range workspace opens at the current frame")
        require(model.state() == project.edit, "A neutral range crop left splits or output settings behind")
        require(model.shapeTitle == "Original" && model.canvas == landscape, "Automatic sizing shows Original at the take's size")
        model.setShape(OutputShape.portrait); model.adjust(CropSettings(x = 0.7, y = 0.4, zoom = 1.7, rotation = 17.0))
        val state = model.state()
        require(state.clips.size == 3, "Range crop isolates only its boundaries")
        require(state.clips[0].crop == null && state.clips[1].crop != null && state.clips[2].crop == null, "Crop touched footage outside the selection")
        require(state.outputSize == CGSize(1080.0, 1920.0), "Crop shape did not pin vertical output")
        project.commit(state); project.validate(); model.release()
        val reopened = project.roundTripped(); reopened.validate()
        require(reopened.edit == state, "Crop lost state on reopening")
        val cropID = state.clips[1].crop!!.id
        val existing = workspace(reopened, ToolRequest(VideoTool.crop, 1.0..3.0, "Existing crop", effectID = cropID, clipIDs = setOf(state.clips[1].id)), 2.0, coroutineContext)
        existing.start(); ready(existing)
        require(existing.shapeTitle == "9:16", "A pinned portrait shape reopens as 9:16")
        require(existing.state() == state, "Opening and applying an unchanged crop creates an edit")
        val removed = existing.state(removing = true)
        require(removed.clips.all { it.crop == null } && removed.outputSize == state.outputSize, "Remove crop changes the committed output shape")
        existing.release()
        // Tools → In this project: the region reopens the same crop and can remove it.
        val region = reopened.edit.toolRegions.single { it.tool == VideoTool.crop }
        require(region.effectID == cropID && region.label == "Crop · 17°", "The crop is listed with its rotation")
        val listed = workspace(reopened, reopened.edit.request(region), 2.0, coroutineContext)
        listed.start(); ready(listed)
        require(listed.crop.id == cropID && listed.state() == state && listed.state(removing = true).clips.all { it.crop == null },
            "A listed crop reopens by its identity and can be removed")
        listed.release()
    }

    @Test fun applyIsOneUndoStepAndCancelStoresNothing() = runBlocking {
        val project = Project("Crop history")
        project.append(SourceMedia(filename = "shake.mp4", duration = 4.0))
        val before = project.edit
        val cursor = project.historyCursor
        val cancelled = workspace(project, ToolRequest(VideoTool.crop, 0.0..4.0, "Whole project", targets = project.edit.timeline.entireSelection()), 1.0, coroutineContext)
        cancelled.start(); ready(cancelled)
        cancelled.setShape(OutputShape.square); cancelled.adjust(cancelled.crop.copy(zoom = 2.0)); cancelled.release()
        require(project.edit == before && project.historyCursor == cursor, "Cancel stores nothing")

        val model = workspace(project, ToolRequest(VideoTool.crop, 0.0..4.0, "Whole project", targets = project.edit.timeline.entireSelection()), 1.0, coroutineContext)
        model.start(); ready(model)
        model.setShape(OutputShape.square); model.adjust(model.crop.copy(zoom = 2.0, x = 0.25))
        val applied = model.state(); model.release()
        require(applied.outputSize == CGSize(1080.0, 1080.0) && applied.clips.single().crop?.zoom == 2.0, "1:1 crop of a landscape take")
        require(applied.timeline.audio == before.timeline.audio && applied.duration == before.duration, "Crop never changes timing or sound")
        project.commit(applied)
        require(project.historyCursor == cursor + 1, "Apply stores shape and crop as one step")
        project.undo()
        require(project.edit == before, "Undo restores the uncropped automatic shape")
        project.redo()
        require(project.edit == applied, "Redo restores crop and shape together")
    }

    @Test fun shapePresetsFixTheFormatAndOriginalReturnsToAutomatic() = runBlocking {
        val project = Project("Crop shapes")
        project.append(SourceMedia(filename = "shake.mp4", duration = 4.0))
        val whole = ToolRequest(VideoTool.crop, 0.0..4.0, "Whole project", targets = project.edit.timeline.entireSelection())
        // Choosing 16:9 on a 16:9 take still fixes the format, without a crop.
        val same = workspace(project, whole, 0.0, coroutineContext)
        same.start(); ready(same); same.setShape(OutputShape.landscape)
        val fixed = same.state(); same.release()
        require(fixed.outputSize == landscape && fixed.clips.single().crop == null, "A preset fixes the format even when the ratio already matches")
        project.commit(fixed)
        // A full-frame crop on an unchanged shape creates no history entry.
        val neutral = workspace(project, whole, 0.0, coroutineContext)
        neutral.start(); ready(neutral); neutral.adjust(neutral.crop.copy(zoom = 1.0))
        require(neutral.state() == project.edit, "An unchanged full-frame crop creates no history entry")
        neutral.release()
        // Original with no crop returns to automatic sizing.
        val original = workspace(project, whole, 0.0, coroutineContext)
        original.start(); ready(original)
        require(original.shapeTitle == "16:9", "The fixed format reopens as 16:9")
        original.setShape(OutputShape.original)
        require(original.state().outputSize == null, "Original with no crop returns to automatic sizing")
        original.release()
        // Original with a committed crop keeps a fixed canvas.
        val cropped = workspace(project, whole, 0.0, coroutineContext)
        cropped.start(); ready(cropped); cropped.setShape(OutputShape.original); cropped.adjust(cropped.crop.copy(zoom = 1.5))
        val kept = cropped.state(); cropped.release()
        require(kept.outputSize == landscape && kept.clips.single().crop?.zoom == 1.5, "A committed crop fixes the canvas")
    }

    @Test fun mixedGeometryKeepsTheSharedCentreAndVisiblePositions() = runBlocking {
        val duration = 4.0
        val mixed = Project("Crop geometry QA")
        mixed.append(SourceMedia(filename = "shake.mp4", duration = duration)); mixed.append(SourceMedia(filename = "portrait.mov", duration = duration))
        val model = workspace(mixed, ToolRequest(VideoTool.crop, 0.0..mixed.edit.duration, "Both orientations"), duration + 1, coroutineContext)
        model.start(); ready(model)
        require(model.frame?.size == portrait && model.active?.index == 1, "Opens on the portrait clip at the playhead")
        model.adjust(CropSettings(y = 0.25)); model.stepClip(-1)
        while (model.busy) {
            model.frame?.let { frame -> require(frame.size == (if (frame.clipID == model.clips[0].id) landscape else portrait), "A frame was paired with another clip's geometry") }
            delay(2)
        }
        require(model.error == null && model.frame?.clipID == model.clips[0].id && model.frame?.size == landscape, "Mixed orientation load failed")
        model.adjust(model.crop.copy(zoom = 1.2))
        require(model.state().clips.all { abs((it.crop?.y ?: 0.0) - 0.25) < 0.00001 }, "Zooming on landscape moved the portrait framing centre")
        model.move(x = -1.0)
        require(abs(model.crop.x - 1 / 2.4) < 0.00001 && model.crop.y == 0.25, "Moving across an edge stored an invisible position or changed the other axis")
        model.setShape(OutputShape.portrait)
        require(abs(model.crop.clamped(model.sourceSize, model.canvas).x - 1 / 2.4) < 0.00001, "A shape change revealed an unseen position")
        require(model.state().outputSize == CGSize(1080.0, 1920.0), "Portrait shape from a landscape reference keeps 1080")
        model.release()
    }

    @Test fun missingGeometryDisablesApplyUntilRetry() = runBlocking {
        val missing = FakeMedia(media.sizes + ("retry-source.mp4" to landscape), mutableSetOf("retry-source.mp4"))
        val project = Project("Retry")
        project.append(SourceMedia(filename = "retry-source.mp4", duration = 4.0))
        val model = CropWorkspace(project, File("unused"), ToolRequest(VideoTool.crop, 0.0..4.0, "Second clip"), 1.0, missing, coroutineContext)
        model.start()
        while (model.busy) delay(5)
        require(model.error != null && model.originalCanvas == null && !model.canApply, "Missing geometry enabled Apply with a placeholder")
        model.setShape(OutputShape.square)
        require(model.shapeTitle == "Original", "Shapes cannot change without geometry")
        missing.missing.clear()
        model.retry(); ready(model)
        require(model.originalCanvas == landscape && model.canApply, "Retry did not reload the original canvas")
        model.release()
    }

    @Test fun selectedClipRequestCropsOnlyThatClip() = runBlocking {
        val project = Project("Selected clip")
        project.append(SourceMedia(filename = "shake.mp4", duration = 3.0)); project.append(SourceMedia(filename = "square.mp4", duration = 3.0))
        val second = project.edit.timeline.video[1]
        val targets = project.edit.timeline.selection(ids = setOf(second.id))
        val model = workspace(project, ToolRequest(VideoTool.crop, 3.0..6.0, "V2", targets = targets, clipIDs = targets.video), 4.0, coroutineContext)
        model.start(); ready(model)
        require(model.clips.size == 1 && model.frame?.size == CGSize(1080.0, 1080.0), "Only the selected picture is in scope")
        model.adjust(model.crop.copy(zoom = 2.0))
        val state = model.state(); model.release()
        require(state.clips.size == 2 && state.clips[0].crop == null && state.clips[1].crop?.zoom == 2.0, "The other clip stays uncropped")
        require(state.outputSize == landscape, "A committed crop fixes the automatic canvas")
    }

    // ---- Export and preview mapping ----

    @Test fun exportPlanCarriesStaticCropsAndRefusesAnimatedOnes() {
        val a = SourceMedia(filename = "a.mp4", duration = 4.0)
        val crop = CropSettings(x = 0.75, y = 0.25, zoom = 2.0)
        val edit = EditState(listOf(TimelineClip(sourceID = a.id, start = 0.0, end = 2.0, crop = crop), TimelineClip(sourceID = a.id, start = 2.0, end = 4.0)))
        val plan = ExportPlan.make(edit)
        val pieces = plan.video.map { it as ExportPlan.Piece.Clip }
        require(pieces.size == 2 && pieces[0].crop == crop && pieces[1].crop == null, "A continuous take is not merged across a crop change")
        val split = TimelineEditing.split(EditState(listOf(TimelineClip(sourceID = a.id, start = 0.0, end = 4.0, crop = crop))), 2.0)
        require(ExportPlan.make(split).video.size == 1, "Split pieces of one crop stay one media item")
        val animated = EditState(listOf(TimelineClip(sourceID = a.id, start = 0.0, end = 4.0,
            crop = crop.copy(motion = kotlinx.serialization.json.buildJsonObject { put("duration", kotlinx.serialization.json.JsonPrimitive(4.0)) }))))
        assertThrowing<ExportPlan.Unsupported>("An animated crop cannot be exported yet") { ExportPlan.make(animated) }
    }

    @Test fun cropMatrixMapsTheCropRectOntoTheCanvas() {
        val crop = CropSettings(x = 0.75, y = 0.25, zoom = 2.0)
        val canvas = CGSize(1080.0, 1080.0)
        val m = CropTransformation.ndcMatrix(crop, landscape, canvas)
        fun ndc(px: Double, py: Double): Pair<Double, Double> {
            val x = 2 * px / landscape.width - 1
            val y = 1 - 2 * py / landscape.height
            return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
        }
        val area = crop.rect(landscape, canvas)
        val topLeft = ndc(area.minX, area.minY)
        val bottomRight = ndc(area.maxX, area.maxY)
        val centre = ndc(area.midX, area.midY)
        require(abs(topLeft.first + 1) < 1e-4 && abs(topLeft.second - 1) < 1e-4, "The crop's top-left is the output's top-left: $topLeft")
        require(abs(bottomRight.first - 1) < 1e-4 && abs(bottomRight.second + 1) < 1e-4, "The crop's bottom-right is the output's bottom-right: $bottomRight")
        require(abs(centre.first) < 1e-4 && abs(centre.second) < 1e-4, "The crop centre is the output centre")
        require(area.minX > landscape.width / 2 && area.maxY < landscape.height / 2 + 1, "x 0.75, y 0.25 selects the upper right")
        // Clockwise rotation in pixels (y down) is clockwise on screen: the output's top edge comes from the source's left side.
        val turned = CropSettings(rotation = 90.0)
        val square = CGSize(1080.0, 1080.0)
        val r = CropTransformation.ndcMatrix(turned, square, square)
        val left = (r[0] * -1 + r[1] * 0 + r[2]) to (r[3] * -1 + r[4] * 0 + r[5])
        require(abs(left.first) < 1e-4 && abs(left.second - 1) < 1e-4, "90° clockwise moves the left edge to the top: $left")
    }

    @Test fun previewFramingUsesTheExportGeometry() {
        val source = newUUID()
        val edit = EditState(listOf(TimelineClip(sourceID = source, start = 0.0, end = 2.0)))
        require(PreviewFraming.canvas(edit, mapOf(source to CGSize(1079.0, 1921.0))) == CGSize(1080.0, 1922.0), "Automatic canvas rounds the reference to even pixels")
        edit.outputSize = CGSize(1080.0, 1080.0)
        require(PreviewFraming.canvas(edit, emptyMap()) == CGSize(1080.0, 1080.0), "A fixed output size wins")
        val fit = PreviewFraming(CGSize(1920.0, 1080.0), portrait, null).transform
        val topLeft = CGPoint.zero.applying(fit)
        val bottomRight = CGPoint(portrait.width, portrait.height).applying(fit)
        require(abs(topLeft.y) < 1e-9 && abs(bottomRight.y - 1080) < 1e-9 && abs(topLeft.x - (1920 - 607.5) / 2) < 1e-9,
            "An uncropped portrait clip is pillarboxed in a landscape canvas")
        val crop = CropSettings(zoom = 2.0)
        require(PreviewFraming(landscape, landscape, crop).transform == crop.transform(landscape, landscape), "Cropped clips use the crop transform")
    }
}
