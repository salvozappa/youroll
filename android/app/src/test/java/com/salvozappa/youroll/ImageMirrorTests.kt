// YouRoll JVM tests for the Image and Mirror tools: the geometry of
// ../YouRec/VideoTools.swift (`MirrorSettings.transform`, `ImageOverlay.rect`),
// tool regions and removal, validation and image files across history (the
// image part of ../Tests/CoreTests.swift `duplicateProjectTests`), the Image
// workspace's edit logic (EditorTools.swift `state(removing:forPreview:)`) and
// the export plan. Pixels are compared on the device (emulator QA).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ImageMirrorTests {
    private fun project(duration: Double = 6.0) = Project("Tools").also { it.append(SourceMedia(filename = "source.mp4", duration = duration)) }

    @Test fun mirrorFlipsAboutTheCanvasCentre() {
        val canvas = CGSize(1920.0, 1080.0)
        val h = MirrorSettings(horizontal = true).transform(canvas)
        require(CGPoint(100.0, 50.0).applying(h) == CGPoint(1820.0, 50.0), "Horizontal flip")
        val v = MirrorSettings(vertical = true).transform(canvas)
        require(CGPoint(100.0, 50.0).applying(v) == CGPoint(100.0, 1030.0), "Vertical flip")
        require(MirrorSettings().transform(canvas) == CGAffineTransform.identity, "No flip is the identity")
        require(MirrorTransformation.ndcMatrix(MirrorSettings(horizontal = true, vertical = true)).toList() ==
            listOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f), "The export flips normalized device coordinates")
        // Preview framing: aspect fit, then the mirror (Swift `transform.concatenating(mirror.transform)`).
        val framing = PreviewFraming(canvas, CGSize(1280.0, 720.0), null, mirror = MirrorSettings(horizontal = true))
        require(CGPoint(0.0, 0.0).applying(framing.transform) == CGPoint(1920.0, 0.0), "The source's left edge lands on the right")
        val cropped = PreviewFraming(canvas, CGSize(1920.0, 1080.0), CropSettings(zoom = 2.0), mirror = MirrorSettings(vertical = true))
        val plain = PreviewFraming(canvas, CGSize(1920.0, 1080.0), CropSettings(zoom = 2.0))
        val p = CGPoint(700.0, 400.0)
        require(abs(p.applying(cropped.transform).y - (1080 - p.applying(plain.transform).y)) < 1e-9, "Mirror follows the crop")
    }

    @Test fun imageRectKeepsAspectOnTheShorterEdge() {
        val canvas = CGSize(1920.0, 1080.0)
        val overlay = ImageOverlay(filename = "image-a.png", x = 0.25, y = 0.2, width = 0.5)
        val rect = overlay.rect(CGSize(400.0, 200.0), canvas)
        require(rect == CGRect(480.0 - 270.0, 1080 * 0.8 - 135.0, 540.0, 270.0), "Width is a fraction of the shorter edge; y is measured upward")
        val bitmapRect = CGRect(rect.minX / canvas.width, (canvas.height - rect.maxY) / canvas.height, rect.width / canvas.width, rect.height / canvas.height)
        require(abs(bitmapRect.midX - 0.25) < 1e-9 && abs(bitmapRect.midY - 0.2) < 1e-9, "The drawn frame is centred at x, y from the top left, as the editor drags it")
        val json = SwiftJSON.encodeToJsonElement(ImageOverlay.serializer(), overlay).jsonObject
        require(json.keys == setOf("id", "filename", "start", "end", "x", "y", "width", "opacity"), "Swift Codable keys")
        val mirror = SwiftJSON.encodeToJsonElement(MirrorSettings.serializer(), MirrorSettings(horizontal = true)).jsonObject
        require(mirror.keys == setOf("id", "horizontal", "vertical") && mirror["horizontal"] == JsonPrimitive(true), "Mirror keeps the Swift keys")
    }

    @Test fun regionsAndRemoval() {
        val edit = project().edit
        val image = ImageOverlay(filename = "image-a.png", start = 1.0, end = 3.0)
        val mirror = MirrorSettings(horizontal = true, vertical = true)
        edit.updateClip(0) { it.copy(speed = SpeedSettings(rate = 2.0), images = listOf(image), mirror = mirror) }
        val regions = edit.toolRegions
        val imageRegion = regions.single { it.tool == VideoTool.image }
        require(imageRegion.range == 0.5..1.5 && imageRegion.label == "Image", "An image region follows the footage through speed")
        require(regions.single { it.tool == VideoTool.mirror }.label == "Flip H + V", "Mirror label")
        edit.updateClip(0) { it.copy(mirror = MirrorSettings(vertical = true)) }
        require(edit.toolRegions.single { it.tool == VideoTool.mirror }.label == "Flip vertical", "Vertical label")
        val split = TimelineEditing.split(edit, 1.0)
        require(split.clips.all { it.images == listOf(image) }, "A split keeps the source-timed image on both pieces")
        val first = split.toolRegions.first { it.tool == VideoTool.image }
        val removed = split.removingTool(split.request(first))
        require(removed.clips[0].images == null && removed.clips[1].images == listOf(image), "Removing one image fragment keeps the other")
        val unmirrored = split.removingTool(split.request(split.toolRegions.first { it.tool == VideoTool.mirror }))
        require(unmirrored.clips[0].mirror == null && unmirrored.clips[1].mirror != null && unmirrored.clips.all { it.images == listOf(image) }, "Mirror removal keeps overlays")
        val pasted = TimelineEditing.inserting(listOf(split.clips[0]), split, 0.0)
        require(pasted.clips[0].images!![0].id != pasted.clips[1].images!![0].id && pasted.clips[0].mirror!!.id != pasted.clips[1].mirror!!.id,
            "Pasted image and mirror edit independently from their originals")
    }

    @Test fun validationAndImageFiles() {
        val project = project()
        val edit = project.edit
        edit.updateClip(0) { it.copy(images = listOf(ImageOverlay(filename = "image-title.png"))) }
        project.commit(edit); project.undo()
        require(project.imageFiles == listOf("image-title.png") && "image-title.png" in project.mediaFiles, "Image assets are kept across the whole history")
        project.validate()
        for (bad in listOf(ImageOverlay(filename = "image-a.png", width = 4.0), ImageOverlay(filename = "image-a.png", opacity = 1.5),
                ImageOverlay(filename = "image-a.png", start = 2.0, end = 1.0), ImageOverlay(filename = "photo.png"))) {
            val broken = project()
            val state = broken.edit
            state.updateClip(0) { it.copy(images = listOf(bad)) }
            broken.commit(state)
            assertInvalidDocument("Invalid image $bad is rejected") { broken.validate() }
        }
        val duplicate = project()
        val image = ImageOverlay(filename = "image-a.png")
        duplicate.edit.let { state -> state.updateClip(0) { it.copy(images = listOf(image, image)) }; duplicate.commit(state) }
        assertInvalidDocument("Duplicate image identities are rejected") { duplicate.validate() }
        val badPauses = project()
        badPauses.edit.let { state -> state.updateClip(0) { it.copy(pauses = PauseSettings(minimum = 20.0)) }; badPauses.commit(state) }
        assertInvalidDocument("Out-of-range pause settings are rejected") { badPauses.validate() }
    }

    @Test fun imageWorkspacePlacesTheImageOnEveryCoveredClip() {
        val project = project(8.0)
        project.commit(TimelineEditing.split(project.edit, 4.0))
        val ids = project.edit.clips.map { it.id }.toSet()
        val model = ImageToolWorkspace(project, File("."), ToolRequest(VideoTool.image, 3.0..6.0, "Whole project", clipIDs = ids))
        require(!model.canApply, "No image chosen yet")
        model.image = model.image.copy(filename = "image-new.png", x = 0.3, opacity = 0.5)
        val state = model.state()
        require(state.clips[0].images!!.single().let { it.start == 3.0 && it.end == 4.0 && it.x == 0.3 } &&
            state.clips[1].images!!.single().let { it.start == 4.0 && it.end == 6.0 }, "The image covers each clip in its source time")
        require(model.state(forPreview = true).clips.all { it.images == null }, "The preview draws the draft itself")
        project.commit(state)
        val region = project.edit.toolRegions.first { it.tool == VideoTool.image }
        val reopened = ImageToolWorkspace(project, File("."), project.edit.request(region))
        require(reopened.image.filename == "image-new.png" && reopened.image.opacity == 0.5, "Reopening reads the saved image")
        reopened.image = reopened.image.copy(opacity = 1.0)
        val restyled = reopened.state()
        require(restyled.clips[0].images!!.single().let { it.opacity == 1.0 && it.start == 3.0 && it.end == 4.0 } &&
            restyled.clips[1].images!!.single().opacity == 0.5, "A look-only edit keeps the fragment's timing and leaves the other fragment")
        require(reopened.state(removing = true).clips[0].images == null, "Remove image")
    }

    @Test fun mirrorWorkspace() {
        val project = project()
        val model = ToolWorkspace(project, File("."), ToolRequest(VideoTool.mirror, 1.0..2.0, "Range", targets = project.edit.timeline.entireSelection(EditInterval.of(1.0, 2.0))), 0.0)
        require(model.state() == project.edit, "A new mirror without flips leaves the project unsplit")
        model.mirror = model.mirror.copy(horizontal = true)
        val state = model.state()
        require(state.clips.size == 3 && state.clips[1].mirror?.horizontal == true && state.clips[0].mirror == null && state.clips[2].mirror == null,
            "A marked range mirrors only its section")
        require(model.state(removing = true).clips.all { it.mirror == null }, "Remove mirror")
    }

    @Test fun exportPlanCarriesMirrorAndImages() {
        val edit = project().edit
        val image = ImageOverlay(filename = "image-a.png", start = 1.0, end = 2.0)
        edit.updateClip(0) { it.copy(mirror = MirrorSettings(horizontal = true), images = listOf(image)) }
        val plan = ExportPlan.make(edit)
        val piece = plan.video.single() as ExportPlan.Piece.Clip
        require(piece.mirror?.horizontal == true && piece.images == listOf(image), "The export draws the clip's mirror and images")
        val unflipped = project().edit
        unflipped.updateClip(0) { it.copy(mirror = MirrorSettings()) }
        require((ExportPlan.make(unflipped).video.single() as ExportPlan.Piece.Clip).mirror == null, "An empty mirror is no transform")
        val animated = project().edit
        animated.updateClip(0) { it.copy(images = listOf(image.copy(motion = buildJsonObject { put("duration", JsonPrimitive(2.0)) }))) }
        assertThrowing<ExportPlan.Unsupported>("An animated image cannot be exported yet") { ExportPlan.make(animated) }
        val paused = project().edit
        paused.updateClip(0) { it.copy(pauses = PauseSettings(cuts = listOf(SourceInterval(2.0, 3.0)))) }
        val pieces = ExportPlan.make(paused).video.filterIsInstance<ExportPlan.Piece.Clip>()
        require(pieces.size == 2 && pieces[0].sourceEnd == 2.0 && pieces[1].sourceStart == 3.0 && ExportPlan.make(paused).duration.seconds == 5.0,
            "Pause cuts are skipped in the export and its duration is the timeline's")
    }
}
