// Port of YouRec/CropEditor.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// CropWorkspace is the draft model, CropEditor the full-screen tool and
// CropCanvas the framing surface (Swift `CropSurface`): the frozen source frame
// under a fixed output-shaped window, dimmed outside, with a rule-of-thirds
// grid. Pinch and pan change the draft without rebuilding video; Preview plays
// the real result through a second EditorController.
//
// Not ported (TODO.md): animated crops (`EffectAnimationControls`, A/B
// endpoints, `displayedCrop`; an existing animation is kept unchanged), titles,
// images and mirror drawn over the framing canvas, the `InspectPreview`
// wrapper, keep-awake during frame loads, and `CropDiagnostics` (its checks
// are JVM tests in CropTests.kt).
package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Swift `CropFrame`: one frozen source frame and its oriented size. Titles, images and mirror are not ported. */
data class CropFrame(val clipID: UUID, val image: Bitmap?, val size: CGSize)

/** The media reads Crop needs (Swift: AVFoundation inline). JVM tests replace it. */
interface CropMedia {
    /** Swift `MediaRenderer.canvas`. */
    suspend fun canvas(project: Project, folder: File): CGSize
    /** The picture's upright size (natural size through the preferred transform). */
    suspend fun sourceSize(source: SourceMedia, folder: File): CGSize
    /** The upright picture at [sourceTime] seconds, at most 1600 pixels on its long side. */
    suspend fun frame(source: SourceMedia, folder: File, sourceTime: Double): Bitmap?

    object Android : CropMedia {
        override suspend fun canvas(project: Project, folder: File): CGSize = withContext(Dispatchers.IO) { MediaRenderer.canvas(project, folder) }

        override suspend fun sourceSize(source: SourceMedia, folder: File): CGSize = withContext(Dispatchers.IO) {
            val file = File(folder, source.filename)
            if (!file.exists()) throw ProjectError.missingMedia
            val info = VideoGeometry.read(file) ?: throw ProjectError.invalidMedia
            CGSize(info.orientedWidth.toDouble(), info.orientedHeight.toDouble())
        }

        override suspend fun frame(source: SourceMedia, folder: File, sourceTime: Double): Bitmap? = withContext(Dispatchers.IO) {
            val file = File(folder, source.filename)
            if (!file.exists()) throw ProjectError.missingMedia
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val us = (max(0.0, sourceTime) * 1_000_000).toLong()
                val image = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 1600, 1600)
                    ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: throw ProjectError.invalidMedia
                // The retriever returns upright frames; rotate if this one was not.
                val info = VideoGeometry.read(file)
                if (info != null && info.rotation % 180 != 0 && (image.width > image.height) != (info.orientedWidth > info.orientedHeight)) {
                    Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(info.rotation.toFloat()) }, true)
                } else image
            } catch (failure: IllegalArgumentException) {
                throw ProjectError.invalidMedia
            } finally { retriever.release() }
        }
    }
}

class CropWorkspace(
    val project: Project,
    val folder: File,
    val request: ToolRequest,
    position: Double,
    private val media: CropMedia = CropMedia.Android,
    context: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val jobs = CoroutineScope(context + SupervisorJob())
    val clips: List<TimelineClip>
    private val scoped: EditState
    private val ids: Set<UUID>
    private val effectID: UUID
    private val origins: Map<UUID, Double>
    private val scope: ClosedFloatingPointRange<Double>
    var crop by mutableStateOf(CropSettings())
        private set
    var aspect by mutableStateOf<Double?>(null)
        private set
    var originalCanvas by mutableStateOf<CGSize?>(null)
        private set
    var frame by mutableStateOf<CropFrame?>(null)
        private set
    private var sourceSizes: Map<UUID, CGSize> = emptyMap()
    var position by mutableDoubleStateOf(0.0)
        private set
    var busy by mutableStateOf(true)
        private set
    var reviewing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var selectedShape by mutableStateOf<OutputShape?>(null)
    private var framingChanged = false
    private var frameJob: Job? = null
    private var revision = Any()
    /** The player of the Preview mode (Swift `player`), set by the screen. */
    var preview: EditorController? = null

    val duration: Double get() = scope.endInclusive - scope.start
    val canvas: CGSize
        get() = OutputShape.canvas(if (aspect == null) originalCanvas ?: CGSize(0.0, 0.0) else project.edit.outputSize ?: originalCanvas ?: CGSize(0.0, 0.0), aspect)
    val shapeTitle: String get() = selectedShape?.title ?: OutputShape.entries.firstOrNull { it.aspect == aspect }?.title ?: "Custom"
    val sourceSize: CGSize get() = frame?.size ?: CGSize(0.0, 0.0)
    val picture: Bitmap? get() = frame?.image
    val canApply: Boolean get() = !busy && error == null && frame != null && originalCanvas != null
    val playing: Boolean get() = reviewing && preview?.playing == true

    data class Active(val clip: TimelineClip, val offset: Double, val index: Int)

    val active: Active?
        get() {
            val t = scope.start + position
            fun distance(clip: TimelineClip): Double {
                val start = origins[clip.id] ?: scope.start
                return max(0.0, max(start - t, t - start - clip.duration + 0.000001))
            }
            val index = clips.indices.minByOrNull { distance(clips[it]) } ?: return null
            val clip = clips[index]
            val offset = min(max(0.0, clip.duration - TimelineEditing.frame), max(0.0, t - (origins[clip.id] ?: t)))
            return Active(clip, offset, index)
        }

    init {
        val selection = request.videoScope(project.edit)
        scoped = selection.first; ids = selection.second
        clips = scoped.clips.filter { it.id in ids }.map { it.copy(transition = null, openingTransition = null) }
        effectID = request.effectID ?: newUUID()
        aspect = project.edit.outputSize?.let { it.width / it.height }
        origins = scoped.layout.placements.filter { it.clip.id in ids }.associate { it.clip.id to it.start }
        scope = request.videoPreviewRange(scoped, ids)
        this.position = min(max(0.0, scope.endInclusive - scope.start - TimelineEditing.frame), max(0.0, position - scope.start))
        // An existing animation is kept as it is: its editor is not ported.
        crop = (active?.clip?.crop ?: CropSettings()).copy(id = effectID)
    }

    private fun describe(failure: Exception): String = failure.message ?: "The video could not be read."

    /** Swift `start()`: the original (automatic) canvas and every clip's oriented size, then the frame. */
    fun start() {
        frameJob?.cancel()
        val token = Any(); revision = token; busy = true; error = null
        frameJob = jobs.launch {
            try {
                val original = project.copy()
                val edit = project.edit.copy(); edit.outputSize = null
                original.history = listOf(edit); original.historyCursor = 0
                val canvas = media.canvas(original, folder)
                val sizes = HashMap<UUID, CGSize>()
                for (sourceID in clips.map { it.sourceID }.toSet()) {
                    val source = project.sources.firstOrNull { it.id == sourceID } ?: throw ProjectError.missingMedia
                    sizes[sourceID] = media.sourceSize(source, folder)
                }
                if (revision !== token) return@launch
                sourceSizes = sizes; originalCanvas = canvas
                showFrame(token)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (revision === token) error = describe(failure)
            } finally {
                if (revision === token) busy = false
            }
        }
    }

    fun retry() { if (originalCanvas == null) start() else showReview(reviewing) }

    fun stop() {
        revision = Any(); frameJob?.cancel(); preview?.pause()
    }

    fun release() { stop(); jobs.cancel() }

    fun adjust(value: CropSettings) {
        if (!canApply) return
        crop = value.copy(id = effectID, x = min(1.0, max(0.0, value.x)), y = min(1.0, max(0.0, value.y)),
            zoom = min(8.0, max(1.0, value.zoom)), rotation = CropSettings.normalizedRotation(value.rotation))
        framingChanged = true
    }

    fun move(x: Double? = null, y: Double? = null) {
        var value = crop
        if (x != null) value = value.copy(x = x)
        if (y != null) value = value.copy(y = y)
        val visible = value.clamped(sourceSize, canvas)
        if (x != null) value = value.copy(x = visible.x)
        if (y != null) value = value.copy(y = visible.y)
        adjust(value)
    }

    fun positionRange(horizontal: Boolean): ClosedFloatingPointRange<Double> {
        val area = crop.footprint(sourceSize, canvas)
        val half = if (horizontal) area.width / max(1.0, sourceSize.width) / 2 else area.height / max(1.0, sourceSize.height) / 2
        return if (half < 0.5) half..(1 - half) else 0.0..1.0
    }

    fun setShape(value: OutputShape) {
        if (!canApply) return
        selectedShape = value
        aspect = value.aspect
        if (reviewing) showReview(false)
    }

    fun rotate(degrees: Double) = adjust(crop.copy(rotation = if (abs(degrees) < 0.5) 0.0 else degrees))

    fun resetFraming() = adjust(CropSettings(id = effectID))

    /**
     * The edit Apply stores. A crop that only fills the frame is no crop, and a
     * neutral crop leaves no split boundaries behind. Shape and crop are one step.
     */
    fun state(removing: Boolean = false, forPreview: Boolean = false): EditState {
        var edit = scoped.copy()
        var changedCrop = false
        edit.clips = edit.clips.map { clip ->
            if (clip.id !in ids) return@map clip
            val previous = clip.crop
            var proposed: CropSettings? = if (framingChanged) crop else previous ?: CropSettings(id = effectID)
            if (removing) proposed = null
            else {
                val size = sourceSizes[clip.sourceID]
                if (size != null && proposed != null && proposed.motion == null && proposed.isFullFrame(size, canvas)) proposed = null
            }
            changedCrop = changedCrop || proposed != previous
            clip.copy(crop = proposed)
        }
        if (!forPreview && !changedCrop) {
            edit = project.edit.copy() // A neutral crop must not leave split boundaries behind.
        }
        if (removing) return edit
        if (selectedShape == OutputShape.original && edit.timeline.video.all { it.content.crop == null }) {
            edit.outputSize = null
        } else if (changedCrop || selectedShape != null || forPreview) {
            edit.outputSize = canvas
        }
        return edit
    }

    fun seek(value: Double) {
        preview?.pause()
        position = min(max(0.0, value), max(0.0, duration - TimelineEditing.frame))
        if (reviewing) preview?.seek(scope.start + position) else loadFrame()
    }

    fun stepClip(delta: Int) {
        val active = active ?: return
        val index = min(clips.size - 1, max(0, active.index + delta))
        seek((origins[clips[index].id] ?: scope.start) - scope.start)
    }

    fun loadFrame() {
        if (originalCanvas == null) { start(); return }
        frameJob?.cancel()
        val token = Any(); revision = token; busy = true; error = null
        frameJob = jobs.launch {
            try {
                delay(80)
                showFrame(token)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (revision === token) error = describe(failure)
            } finally {
                if (revision === token) busy = false
            }
        }
    }

    /** The frame and its geometry change together, so a frame is never paired with another clip's size. */
    private suspend fun showFrame(token: Any) {
        val active = active ?: throw ProjectError.emptyTimeline
        val source = project.sources.firstOrNull { it.id == active.clip.sourceID } ?: throw ProjectError.missingMedia
        val image = media.frame(source, folder, active.clip.sourceTime(active.offset))
        if (revision !== token) return
        val size = sourceSizes[active.clip.sourceID] ?: throw ProjectError.invalidMedia
        if (!framingChanged) crop = (active.clip.crop ?: CropSettings()).copy(id = effectID)
        frame = CropFrame(active.clip.id, image, size)
    }

    /** Swift `showReview`: Preview plays the candidate edit (with the chosen shape) in the scope. */
    fun showReview(value: Boolean, autoplay: Boolean = false) {
        if (originalCanvas == null) { start(); return }
        val player = preview
        player?.pause(); reviewing = value
        if (!value) { loadFrame(); return }
        frameJob?.cancel(); revision = Any(); busy = false; error = null
        if (player == null) return
        val candidate = project.copy()
        candidate.history = listOf(state(forPreview = true)); candidate.historyCursor = 0
        player.previewRange = scope
        player.seekAfterEdit(scope.start + position, candidate.edit.duration)
        player.load(candidate, folder, includeTimelineDetails = false)
        if (autoplay) player.togglePlay()
    }

    /** Swift's periodic time observer: follow the preview's playhead. */
    fun followPreview() {
        val player = preview ?: return
        if (reviewing && player.playing) position = min(duration, max(0.0, player.playhead - scope.start))
    }

    fun togglePlay() {
        val player = preview ?: return
        when {
            playing -> player.pause()
            !reviewing -> showReview(true, autoplay = true)
            else -> {
                if (position >= duration - 0.05) { position = 0.0; player.seek(scope.start) }
                player.togglePlay()
            }
        }
    }
}

@Composable
fun CropEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { CropWorkspace(project, folder, request, position) }
    val preview = remember { EditorController(context) }
    DisposableEffect(model) {
        model.preview = preview
        model.start()
        onDispose { model.release(); preview.release() }
    }
    LaunchedEffect(model.reviewing) {
        while (model.reviewing) { model.followPreview(); delay(33) }
    }
    BackHandler(onBack = cancel)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        val boxWidth = maxWidth; val boxHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            // Swift: Cancel · "Crop" over the scope · [trash] · Apply.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", Modifier.widthIn(min = 60.dp), action = cancel)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Crop", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(request.scopeTitle, color = EditorColors.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (request.effectID != null) {
                    Box(Modifier.size(44.dp).clickable { model.stop(); apply(model.state(removing = true)) }
                        .semantics { contentDescription = "Remove crop" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Delete, null, tint = Color.White.copy(alpha = 0.7f))
                    }
                }
                PillButton("Apply", Modifier.widthIn(min = 60.dp), enabled = model.canApply, bold = true) { model.stop(); apply(model.state()) }
            }
            @Composable fun pictureAndPlayback(modifier: Modifier) {
                Column(modifier) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                        val frame = model.frame
                        if (model.reviewing) PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles, layers = preview.layers, captions = preview.subtitles, inspectionZoom = false,
                            onPlaybackTap = { model.togglePlay() })
                        else if (frame != null) CropCanvas(frame, model.canvas, model.crop, enabled = !model.busy, changed = model::adjust)
                        if (model.busy || (model.reviewing && preview.loading)) {
                            Box(Modifier.background(Color(0.2f, 0.2f, 0.22f, 0.85f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    CropPlayback(model, Modifier.padding(horizontal = 14.dp))
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pictureAndPlayback(Modifier.weight(1f).fillMaxHeight())
                    CropSettingsPanel(model, Modifier.width(minOf(320.dp, boxWidth * 0.38f)).fillMaxHeight())
                }
            } else {
                pictureAndPlayback(Modifier.weight(1f).fillMaxWidth())
                CropSettingsPanel(model, Modifier.fillMaxWidth().heightIn(max = minOf(280.dp, boxHeight * 0.43f)))
            }
        }
    }
}

@Composable
private fun CropPlayback(model: CropWorkspace, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (result in listOf(false, true)) {
                val selected = model.reviewing == result
                Box(Modifier.heightIn(min = 44.dp).clickable { model.showReview(result) }
                    .semantics { stateDescription = if (selected) "Selected" else "" }, contentAlignment = Alignment.Center) {
                    Text(if (result) "Preview" else "Frame", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (model.reviewing) {
                val previewLoading = model.preview?.loading == true
                Box(Modifier.size(48.dp, 44.dp).alpha(if (previewLoading) 0.4f else 1f).clickable(enabled = !previewLoading) { model.togglePlay() }
                    .semantics { contentDescription = if (model.playing) "Pause crop preview" else "Play crop preview" }, contentAlignment = Alignment.Center) {
                    Icon(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                }
            }
            if (model.clips.size > 1) {
                val index = model.active?.index ?: 0
                Box(Modifier.size(44.dp).alpha(if (index == 0) 0.35f else 1f).clickable(enabled = index > 0) { model.stepClip(-1) }
                    .semantics { contentDescription = "Previous crop clip" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ChevronLeft, null, tint = Color.White)
                }
                Text("${index + 1}/${model.clips.size}", color = Color.White, fontSize = 12.sp, style = tabular)
                Box(Modifier.size(44.dp).alpha(if (index == model.clips.size - 1) 0.35f else 1f).clickable(enabled = index < model.clips.size - 1) { model.stepClip(1) }
                    .semantics { contentDescription = "Next crop clip" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ChevronRight, null, tint = Color.White)
                }
            }
        }
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(editorTime(model.position), color = Color.White, fontSize = 12.sp, style = tabular, maxLines = 1)
            Slider(value = model.position.toFloat(), onValueChange = { model.seek(it.toDouble()) },
                valueRange = 0f..max(TimelineEditing.frame, model.duration - TimelineEditing.frame).toFloat(),
                colors = cropSliderColors(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Crop preview position" })
            Text(editorTime(model.duration), color = EditorColors.secondary, fontSize = 12.sp, style = tabular, maxLines = 1)
        }
    }
}

@Composable
private fun cropSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f), disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray)


@Composable
private fun CropSettingsPanel(model: CropWorkspace, modifier: Modifier) {
    var shapeMenu by remember { mutableStateOf(false) }
    var positionOpen by remember { mutableStateOf(false) }
    val enabled = model.canApply
    Column(modifier.background(Color(0.08f, 0.08f, 0.08f)).verticalScroll(rememberScrollState()).padding(16.dp)
        .semantics { contentDescription = "Crop settings" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.error?.let { message ->
            Text(message, color = EditorColors.orange, fontSize = 16.sp)
            Box(Modifier.heightIn(min = 44.dp).clickable { model.retry() }, contentAlignment = Alignment.CenterStart) {
                Text("Retry", color = EditorColors.accent, fontSize = 17.sp)
            }
        }
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled) { shapeMenu = true }
                .semantics { contentDescription = "Video shape, ${model.shapeTitle}" }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AspectRatio, null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Video shape", color = EditorColors.accent, fontSize = 17.sp)
                    Text("Whole project", color = EditorColors.secondary, fontSize = 12.sp)
                }
                Text(model.shapeTitle, color = EditorColors.accent, fontSize = 17.sp, maxLines = 1)
                Icon(Icons.Filled.UnfoldMore, null, tint = EditorColors.accent, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(shapeMenu, onDismissRequest = { shapeMenu = false }) {
                for (shape in OutputShape.entries) DropdownMenuItem(text = { Text(shape.title) }, onClick = { shapeMenu = false; model.setShape(shape) })
            }
        }
        Text(if (model.reviewing) "Preview includes text and other video tools." else "Pinch to crop · drag to frame", color = Color.White, fontSize = 15.sp)
        if (!model.reviewing) {
            Column(Modifier.alpha(if (enabled) 1f else 0.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rotation", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    val angle = model.crop.rotation
                    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 52.dp).clickable(enabled = enabled) { model.rotate(0.0) }
                        .semantics { contentDescription = "Reset rotation"; stateDescription = "$angle degrees" }, contentAlignment = Alignment.Center) {
                        val text = if (angle == Math.rint(angle)) "%.0f°".format(angle) else "%.1f°".format(angle)
                        Text(text, color = EditorColors.accent, fontSize = 15.sp, style = tabular)
                    }
                    Box(Modifier.size(44.dp).clickable(enabled = enabled) { model.rotate(model.crop.rotation + 90) }
                        .semantics { contentDescription = "Rotate 90 degrees clockwise" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null, tint = EditorColors.accent)
                    }
                }
                Slider(value = model.crop.rotation.toFloat(), onValueChange = { model.rotate(Math.round(it * 10) / 10.0) }, valueRange = -180f..180f,
                    enabled = enabled, colors = cropSliderColors(), modifier = Modifier.semantics { contentDescription = "Crop rotation" })
                Text("Edges stay filled. Tap the angle to reset.", color = EditorColors.secondary, fontSize = 12.sp)
            }
            Row(Modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", color = Color.White, fontSize = 15.sp)
                Slider(value = model.crop.zoom.toFloat(), onValueChange = { model.adjust(model.crop.copy(zoom = it.toDouble())) }, valueRange = 1f..8f,
                    enabled = enabled, colors = cropSliderColors(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Crop zoom" })
                Text("%.1f×".format(model.crop.zoom), color = Color.White, fontSize = 15.sp, style = tabular, maxLines = 1)
            }
            Row(Modifier.alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.heightIn(min = 44.dp).clickable(enabled = enabled) { model.resetFraming() }, contentAlignment = Alignment.CenterStart) {
                    Text("Reset framing", color = EditorColors.accent, fontSize = 17.sp)
                }
                Spacer(Modifier.weight(1f))
                val rect = model.crop.rect(model.sourceSize, model.canvas)
                Text("${Math.round(rect.width)} × ${Math.round(rect.height)} px", color = EditorColors.secondary, fontSize = 12.sp, style = tabular, maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "Source area ${rect.width.toInt()} by ${rect.height.toInt()} pixels" })
            }
            // Swift DisclosureGroup("Position").
            Column(Modifier.alpha(if (enabled) 1f else 0.4f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled) { positionOpen = !positionOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Position", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Icon(if (positionOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = EditorColors.accent)
                }
                if (positionOpen) {
                    val visible = model.crop.clamped(model.sourceSize, model.canvas)
                    val footprint = model.crop.footprint(model.sourceSize, model.canvas)
                    for (horizontal in listOf(true, false)) {
                        val range = model.positionRange(horizontal)
                        val fixed = if (horizontal) footprint.width >= model.sourceSize.width - 0.001 else footprint.height >= model.sourceSize.height - 0.001
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (horizontal) "Across" else "Down", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(60.dp))
                            Slider(value = (if (horizontal) visible.x else visible.y).toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                                onValueChange = { if (horizontal) model.move(x = it.toDouble()) else model.move(y = it.toDouble()) },
                                valueRange = range.start.toFloat()..range.endInclusive.toFloat(), enabled = enabled && !fixed, colors = cropSliderColors(),
                                modifier = Modifier.weight(1f).semantics { contentDescription = if (horizontal) "Crop horizontal position" else "Crop vertical position" })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Text("Video shape applies to the whole project. Other clips keep their full image unless cropped.", color = EditorColors.secondary, fontSize = 12.sp)
        if (model.request.range.start > 0 || model.request.range.endInclusive < model.project.edit.duration) {
            Text("Framing changes instantly at the edges of this selection.", color = EditorColors.secondary, fontSize = 12.sp)
        }
        if (model.clips.size > 1) Text("Framing changes apply to all ${model.clips.size} selected clips. Check each with the arrows.", color = EditorColors.secondary, fontSize = 12.sp)
    }
}

/**
 * Swift `CropCanvas`/`CropSurface`: the frame drawn through the crop transform
 * into a fixed output-shaped window (20 points in from the edges), the outside
 * dimmed to 65 % black, a white border and a rule-of-thirds grid. Pinch zooms
 * around the fingers' source point; one-finger drag pans. Both keep the window
 * filled (positions are clamped to the visible range).
 */
@Composable
private fun CropCanvas(frame: CropFrame, canvas: CGSize, crop: CropSettings, enabled: Boolean, changed: (CropSettings) -> Unit) {
    val density = LocalDensity.current
    val inset = with(density) { 20.dp.toPx() }.toDouble()
    // The draft as the gesture last left it: events can arrive faster than recomposition.
    val live = remember { arrayOf(crop) }
    SideEffect { live[0] = crop }
    val source = frame.size
    val currentChanged by rememberUpdatedState(changed)
    val currentEnabled by rememberUpdatedState(enabled)
    var bounds by remember { mutableStateOf(CGRect.zero) }
    fun window() = aspectFitRect(canvas, CGRect(inset, inset, bounds.width - 2 * inset, bounds.height - 2 * inset))
    fun imageTransform(value: CropSettings): CGAffineTransform {
        val window = window()
        val scale = window.width / max(1.0, canvas.width)
        return value.transform(source, canvas)
            .concatenating(CGAffineTransform.scale(scale, scale))
            .concatenating(CGAffineTransform.translation(window.minX, window.minY))
    }
    fun sourceDelta(x: Double, y: Double, value: CropSettings): CGPoint {
        val linear = imageTransform(value).copy(tx = 0.0, ty = 0.0)
        return CGPoint(x, y).applying(linear.inverted())
    }
    val visible = crop.clamped(source, canvas)
    Canvas(Modifier.fillMaxSize()
        .semantics {
            contentDescription = "Crop framing"
            stateDescription = "Rotation %.1f degrees, Zoom %.1f times, centre %.0f percent from left, %.0f percent from top"
                .format(crop.rotation, crop.zoom, visible.x * 100, visible.y * 100)
        }
        .pointerInput(source, canvas) {
            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                if (!currentEnabled || frame.image == null) return@detectTransformGestures
                val current = live[0]
                val base = current.clamped(source, canvas)
                if (gestureZoom != 1f) {
                    // Keep the source point under the fingers in place while zooming.
                    val anchor = CGPoint((centroid.x - pan.x).toDouble(), (centroid.y - pan.y).toDouble()).applying(imageTransform(base).inverted())
                    var value = base.copy(zoom = min(8.0, max(1.0, base.zoom * gestureZoom)))
                    val window = window()
                    val delta = sourceDelta(centroid.x - window.midX, centroid.y - window.midY, value)
                    value = value.copy(x = (anchor.x - delta.x) / source.width, y = (anchor.y - delta.y) / source.height)
                    val clamped = value.clamped(source, canvas)
                    live[0] = value.copy(x = clamped.x, y = clamped.y)
                    currentChanged(live[0])
                } else if (pan != Offset.Zero) {
                    val delta = sourceDelta(pan.x.toDouble(), pan.y.toDouble(), current)
                    var value = current
                    val movedX = abs(delta.x) > 0.000001
                    val movedY = abs(delta.y) > 0.000001
                    if (movedX) value = value.copy(x = base.x - delta.x / source.width)
                    if (movedY) value = value.copy(y = base.y - delta.y / source.height)
                    val clamped = value.clamped(source, canvas)
                    if (movedX) value = value.copy(x = clamped.x)
                    if (movedY) value = value.copy(y = clamped.y)
                    live[0] = value
                    currentChanged(value)
                }
            }
        }) {
        bounds = CGRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
        if (size.width <= 40 || size.height <= 40 || canvas.width <= 0 || canvas.height <= 0) return@Canvas
        val window = window()
        val image = frame.image
        if (image != null) {
            // The bitmap may be smaller than the source: scale it to source pixels first.
            val m = CGAffineTransform.scale(source.width / image.width, source.height / image.height).concatenating(imageTransform(crop))
            val matrix = Matrix().apply { setValues(floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.tx.toFloat(), m.b.toFloat(), m.d.toFloat(), m.ty.toFloat(), 0f, 0f, 1f)) }
            drawIntoCanvas { it.nativeCanvas.drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
        val rect = Rect(window.minX.toFloat(), window.minY.toFloat(), window.maxX.toFloat(), window.maxY.toFloat())
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height)); addRect(rect)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.65f))
        val hairline = with(density) { 0.5.dp.toPx() }
        for (fraction in listOf(1f / 3, 2f / 3)) {
            drawLine(Color.White.copy(alpha = 0.3f), Offset(rect.left + rect.width * fraction, rect.top), Offset(rect.left + rect.width * fraction, rect.bottom), hairline)
            drawLine(Color.White.copy(alpha = 0.3f), Offset(rect.left, rect.top + rect.height * fraction), Offset(rect.right, rect.top + rect.height * fraction), hairline)
        }
        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(with(density) { 1.5.dp.toPx() }))
    }
}
