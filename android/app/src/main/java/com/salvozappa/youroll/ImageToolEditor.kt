// Port of YouRec/EditorTools.swift (the Image tool: `ToolWorkspace` for
// `request.tool == .image` — `loadImage`, `finishImages`, the image branch of
// `state(removing:forPreview:)`, `trim` — and `ToolEditor`'s imageCanvas,
// playback, imageSettings and overlayTiming)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Like the Text tool (TextToolEditor.kt), the Image tool keeps its own
// workspace so the tools can be ported independently; the logic follows the
// Swift image branches. The preview is a second EditorController playing the
// project without the draft image, bounded to the image's interval; the
// draft is drawn over it with the export's placement and blend
// (`VideoOverlayRenderer.frame`). Photos come from the Android Photo Picker
// and are stored in the project folder as `image-<UUID>.png` (Swift
// `loadImage`: at most 4096 px, orientation applied, transparency kept).
//
// Not ported (TODO.md): animated images (`EffectAnimationControls`, A/B
// endpoints), the still-frame-while-paused preview and `InspectPreview`.
package com.salvozappa.youroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Swift `ToolWorkspace` for the Image tool. Pure apart from the preview player and file import, so the edit logic is JVM-tested. */
class ImageToolWorkspace(val project: Project, val folder: File, val request: ToolRequest) {
    val effectID: UUID = request.effectID ?: newUUID()
    var image by mutableStateOf(ImageOverlay(filename = ""))
    /** The chosen picture (Swift `imageBitmap`); null until one is chosen or the saved file is read. */
    var imageBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var loadingImage by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    private val importedImages = mutableListOf<File>()
    var timing by mutableStateOf(run {
        val bounds = request.clipIDs?.let { ids ->
            var lower = project.edit.duration
            var upper = 0.0
            for (placement in project.edit.layout.placements) {
                if (placement.clip.id !in ids) continue
                lower = min(lower, placement.lower); upper = max(upper, placement.upper)
            }
            min(lower, upper)..upper
        } ?: 0.0..project.edit.duration
        OverlayTiming(request.range, bounds)
    })
        private set
    /** Position within the image's interval. */
    var position by mutableDoubleStateOf(0.0)
        private set
    /** The player of the preview (Swift `player`), set by the screen. */
    var preview: EditorController? = null

    val start: Double get() = timing.range.start
    val end: Double get() = timing.range.endInclusive
    val duration: Double get() = timing.duration
    val canApply: Boolean get() = !loadingImage && imageBitmap != null && error == null
    val playing: Boolean get() = preview?.playing == true
    val canvas: CGSize? get() = preview?.canvas

    init {
        request.effectID?.let { id ->
            project.edit.clips.flatMap { it.images.orEmpty() }.firstOrNull { it.id == id }?.let { image = it }
        }
        image = image.copy(id = effectID)
    }

    /**
     * Swift `state(removing:forPreview:)` for `.image`: the image on every clip
     * it covers, in that clip's source time. A look-only edit keeps each fragment's source timing.
     */
    fun state(removing: Boolean = false, forPreview: Boolean = false): EditState {
        val edit = project.edit
        val positions = edit.layout.placements
        val ids = request.clipIDs
        edit.clips = edit.clips.mapIndexed { index, clip ->
            if (ids != null && clip.id !in ids) return@mapIndexed clip
            val cursor = positions[index].start
            val previous = clip.images.orEmpty().firstOrNull { it.id == effectID }
            var images = clip.images.orEmpty().filter { it.id != effectID }
            if (!removing && !forPreview && request.effectID != null && start == request.range.start && end == request.range.endInclusive) {
                if (previous != null) images = images + image.copy(start = previous.start, end = previous.end)
            } else {
                val a = max(start, cursor)
                val b = min(end, cursor + clip.duration)
                if (!removing && !forPreview && b > a + 0.000001 && image.filename.isNotEmpty()) {
                    images = images + image.copy(start = clip.sourceTime(a - cursor), end = clip.sourceTime(b - cursor))
                }
            }
            clip.copy(images = images.ifEmpty { null })
        }
        return edit
    }

    /** Reads the saved picture of an image being edited. */
    suspend fun loadSavedImage() {
        if (image.filename.isEmpty() || imageBitmap != null) return
        imageBitmap = withContext(Dispatchers.IO) { ImageBitmaps.image(folder, image.filename) }
        if (imageBitmap == null) error = ProjectError.missingImage.message
    }

    /**
     * Swift `loadImage(_:)`: the picked photo, upright and at most 4096 px, saved
     * as a PNG in the project folder (so transparency survives). Files imported
     * by this draft but not kept are deleted by [finishImages].
     */
    suspend fun loadImage(context: Context, uri: Uri) {
        loadingImage = true
        try {
            val (file, bitmap) = withContext(Dispatchers.IO) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > 4096) {
                        val scale = 4096.0 / longest
                        decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                    }
                }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
                val file = File(folder, "image-" + newUUID().uuidString + ".png")
                try {
                    file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw ProjectError.invalidMedia }
                } catch (failure: Exception) { file.delete(); throw failure }
                file to bitmap
            }
            importedImages += file
            image = image.copy(filename = file.name)
            imageBitmap = bitmap
            error = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: ProjectError.invalidMedia.message
        } finally {
            loadingImage = false
        }
    }

    /** Swift `finishImages(keeping:)`: delete the pictures this draft imported, except the one kept. */
    fun finishImages(keeping: String? = null) {
        val files = importedImages.filter { it.name != keeping }
        importedImages.clear()
        if (files.isNotEmpty()) Thread { files.forEach { it.delete() } }.start()
    }

    /** Swift `startPreview`/`rebuild` for an overlay: the project without the draft, bounded to the interval. */
    fun startPreview() {
        val player = preview ?: return
        val candidate = project.copy()
        candidate.history = listOf(state(forPreview = true)); candidate.historyCursor = 0
        player.previewRange = start..end
        player.seekAfterEdit(start + position, candidate.edit.duration)
        player.load(candidate, folder, includeTimelineDetails = false)
    }

    fun seek(value: Double) {
        position = min(max(0.0, value), max(0.0, duration - TimelineEditing.frame))
        preview?.seekPaused(start + position)
    }

    fun togglePlay() {
        val player = preview ?: return
        if (player.playing) { player.pause(); return }
        if (position >= duration - 0.05) seek(0.0)
        player.togglePlay()
    }

    /** Swift `trim(_:to:previewEdge:)`: the playback window follows the image's interval. */
    fun trim(edge: OverlayTiming.Edge, value: Double, previewEdge: Boolean = true) {
        val absolutePlayhead = start + position
        val updated = timing.copy()
        updated.set(edge, value)
        if (updated.range == timing.range) return
        timing = updated
        val lastFrame = max(0.0, duration - TimelineEditing.frame)
        val target = if (previewEdge) (if (edge == OverlayTiming.Edge.start) 0.0 else lastFrame) else min(lastFrame, max(0.0, absolutePlayhead - start))
        preview?.let { it.pause(); it.previewRange = start..end }
        seek(target)
    }

    /** The preview's periodic observer: follow its playhead. */
    fun followPreview() {
        val player = preview ?: return
        if (player.playing) position = min(duration, max(0.0, player.playhead - start))
    }

    /** The draft drawn over the preview, where and how the export draws it. */
    fun draftFrame(canvas: CGSize): SubtitleLayout.Frame? {
        val bitmap = imageBitmap ?: return null
        return VideoOverlayRenderer.frame(image, CGSize(bitmap.width.toDouble(), bitmap.height.toDouble()), canvas, bitmap)
    }
}

@Composable
private fun imageSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f), disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray)

/** Swift `ToolEditor` for `.image`. */
@Composable
fun ImageToolEditor(project: Project, folder: File, request: ToolRequest, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { ImageToolWorkspace(project, folder, request) }
    val preview = remember { EditorController(context) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { model.loadImage(context, uri) }
    }
    DisposableEffect(model) {
        model.preview = preview
        model.startPreview()
        onDispose { preview.release() }
    }
    LaunchedEffect(model) {
        model.loadSavedImage()
        while (true) { model.followPreview(); delay(33) }
    }
    val close = { model.finishImages(); cancel() }
    BackHandler(onBack = close)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        val boxWidth = maxWidth; val boxHeight = maxHeight
        val inspectorWidth = if (landscape) minOf(320.dp, boxWidth * 0.4f) else boxWidth
        Column(Modifier.fillMaxSize()) {
            // Swift header: Cancel · "Image" over "scope · start–end" · Apply.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color(0.06f, 0.06f, 0.06f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", Modifier.widthIn(min = 60.dp), action = close)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(VideoTool.image.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(request.scopeTitle + " · " + editorTime(model.start) + "–" + editorTime(model.end), color = EditorColors.secondary,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Apply", Modifier.widthIn(min = 60.dp), enabled = model.canApply, bold = true) {
                    model.finishImages(keeping = model.image.filename); preview.pause(); apply(model.state())
                }
            }
            @Composable fun pictureAndPlayback(modifier: Modifier) {
                Column(modifier) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                        ImageCanvas(model, preview)
                        if (preview.loading || model.loadingImage) {
                            Box(Modifier.background(Color(0.2f, 0.2f, 0.22f, 0.85f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    ImagePlayback(model, preview, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                }
            }
            @Composable fun settings(modifier: Modifier) {
                Column(modifier.background(Color(0.08f, 0.08f, 0.08f)).verticalScroll(rememberScrollState()).padding(16.dp)
                    .semantics { contentDescription = "Image settings" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    (model.error ?: preview.error)?.let { Text(it, color = EditorColors.orange, fontSize = 16.sp) }
                    ImageSettings(model, inspectorWidth - 32.dp) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    if (request.effectID != null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { model.finishImages(); preview.pause(); apply(model.state(removing = true)) }
                            .semantics { contentDescription = "Remove image" }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Delete, null, tint = EditorColors.accent)
                            Spacer(Modifier.width(6.dp))
                            Text("Remove image", color = EditorColors.accent, fontSize = 17.sp)
                        }
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pictureAndPlayback(Modifier.weight(1f).fillMaxHeight())
                    settings(Modifier.width(inspectorWidth).fillMaxHeight())
                }
            } else {
                pictureAndPlayback(Modifier.weight(1f).fillMaxWidth())
                settings(Modifier.fillMaxWidth().heightIn(max = minOf(310.dp, boxHeight * 0.48f)))
            }
        }
    }
}

/**
 * Swift `imageCanvas`: the project picture in the canvas shape with the draft
 * image drawn as it will export; dragging the image moves it (snapping to the
 * centre lines) and a pinch anywhere resizes it.
 */
@Composable
private fun ImageCanvas(model: ImageToolWorkspace, preview: EditorController) {
    val canvas = model.canvas ?: preview.canvas
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bounds = CGRect(0.0, 0.0, constraints.maxWidth.toDouble(), constraints.maxHeight.toDouble())
        val window = canvas?.let { aspectFitRect(it, bounds) }
        val currentModel by rememberUpdatedState(model)
        val draft = canvas?.let { model.draftFrame(it) }
        PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles + listOfNotNull(draft), layers = preview.layers, captions = preview.subtitles,
            inspectionZoom = false, onPlaybackTap = null)
        if (canvas != null && window != null && window.width > 0) {
            var dragging by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().pointerInput(canvas) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        val m = currentModel
                        m.image = m.image.copy(width = min(3.0, max(0.03, m.image.width * zoom)))
                    }
                }
            })
            if (draft == null) {
                if (!model.loadingImage) Text("Choose an image below", color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp))
            } else {
                val scale = window.width / canvas.width
                val w = max(with(density) { 44.dp.toPx() }.toDouble(), draft.rect.width * canvas.width * scale)
                val h = max(with(density) { 44.dp.toPx() }.toDouble(), draft.rect.height * canvas.height * scale)
                val cx = window.minX + window.width * model.image.x
                val cy = window.minY + window.height * model.image.y
                Box(Modifier.align(Alignment.TopStart).offset { IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()) }
                    .size(with(density) { w.toFloat().toDp() }, with(density) { h.toFloat().toDp() })
                    .then(if (dragging) Modifier.border(1.dp, EditorColors.orange) else Modifier)
                    .pointerInput(canvas, window) {
                        var origin0: Pair<Double, Double>? = null
                        var dx = 0.0; var dy = 0.0
                        detectDragGestures(onDragStart = { origin0 = currentModel.image.x to currentModel.image.y; dx = 0.0; dy = 0.0; dragging = true },
                            onDragEnd = { origin0 = null; dragging = false }, onDragCancel = { origin0 = null; dragging = false }) { change, drag ->
                            change.consume()
                            val start = origin0 ?: return@detectDragGestures
                            dx += drag.x; dy += drag.y
                            val x = min(1.0, max(0.0, start.first + dx / max(1.0, window.width)))
                            val y = min(1.0, max(0.0, start.second + dy / max(1.0, window.height)))
                            val m = currentModel
                            m.image = m.image.copy(x = if (abs(x - 0.5) < 0.015) 0.5 else x, y = if (abs(y - 0.5) < 0.015) 0.5 else y)
                        }
                    }
                    .semantics { contentDescription = "Image position" })
            }
        }
    }
}

@Composable
private fun ImagePlayback(model: ImageToolWorkspace, preview: EditorController, modifier: Modifier) {
    Row(modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).alpha(if (preview.loading) 0.4f else 1f).clickable(enabled = !preview.loading) { model.togglePlay() }
            .semantics { contentDescription = if (model.playing) "Pause image preview" else "Play image preview" }, contentAlignment = Alignment.Center) {
            Icon(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
        }
        Text(editorTime(model.position), color = Color.White, fontSize = 12.sp, style = tabular, maxLines = 1)
        Slider(model.position.toFloat(), { model.seek(it.toDouble()) },
            valueRange = 0f..max(TimelineEditing.frame, model.duration - TimelineEditing.frame).toFloat(), enabled = !preview.loading,
            colors = imageSliderColors(), modifier = Modifier.weight(1f).semantics { contentDescription = "Tool preview position" })
        Text(editorTime(model.duration), color = EditorColors.secondary, fontSize = 12.sp, style = tabular, maxLines = 1)
    }
}

/** Swift `imageSettings(width:)`. */
@Composable
private fun ImageSettings(model: ImageToolWorkspace, width: androidx.compose.ui.unit.Dp, choose: () -> Unit) {
    var positionOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val label = if (model.loadingImage) "Loading image…" else if (model.imageBitmap == null) "Choose image" else "Replace image"
        Row(Modifier.heightIn(min = 44.dp).alpha(if (model.loadingImage) 0.4f else 1f).clickable(enabled = !model.loadingImage) { choose() }
            .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AddPhotoAlternate, null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = EditorColors.accent, fontSize = 17.sp)
        }
        OverlayTimingControl(model.timing, model.start, model.end, VideoTool.image, width, {}) { edge, value, previewEdge -> model.trim(edge, value, previewEdge) }
        Text("Drag to move · pinch to resize", color = EditorColors.secondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            // Swift: a logarithmic slider from 3 % to 300 % of the shorter canvas edge.
            Slider(ln(model.image.width).toFloat(), { model.image = model.image.copy(width = min(3.0, max(0.03, exp(it.toDouble())))) },
                valueRange = ln(0.03).toFloat()..ln(3.0).toFloat(), colors = imageSliderColors(),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Image size"; stateDescription = "${Math.round(model.image.width * 100)} percent"
                })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            Slider(model.image.opacity.toFloat(), { model.image = model.image.copy(opacity = it.toDouble()) }, valueRange = 0f..1f, colors = imageSliderColors(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Image opacity" })
        }
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { positionOpen = !positionOpen }, verticalAlignment = Alignment.CenterVertically) {
                Text("Position", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(if (positionOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = EditorColors.accent)
            }
            if (positionOpen) {
                for (horizontal in listOf(true, false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (horizontal) "Across" else "Down", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(64.dp))
                        Slider((if (horizontal) model.image.x else model.image.y).toFloat(), {
                            model.image = if (horizontal) model.image.copy(x = it.toDouble()) else model.image.copy(y = it.toDouble())
                        }, valueRange = 0f..1f, colors = imageSliderColors(),
                            modifier = Modifier.weight(1f).semantics { contentDescription = if (horizontal) "Image horizontal position" else "Image vertical position" })
                    }
                }
                Box(Modifier.heightIn(min = 44.dp).clickable { model.image = model.image.copy(x = 0.5, y = 0.5) }, contentAlignment = Alignment.CenterStart) {
                    Text("Centre image", color = EditorColors.accent, fontSize = 17.sp)
                }
            }
        }
    }
}
