// Port of YouRec/EditorTools.swift (the Text tool: `ToolWorkspace` for
// `request.tool == .text`, and `ToolEditor`'s textCanvas, playback,
// textSettings and overlayTiming)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Swift shares one ToolWorkspace/ToolEditor between all its tools. This port
// keeps the Text tool in its own file (`TextToolWorkspace`, `TextToolEditor`)
// so the tools can be ported independently; the logic follows the Swift text
// branches. The preview is a second EditorController playing the project
// without the draft title, with the draft drawn as `previewTitle` (Swift
// `refreshTitlePreview`), bounded to the title's interval.
package com.salvozappa.youroll

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Swift `ToolWorkspace` for the Text tool. Pure apart from the preview player, so the edit logic is JVM-tested. */
class TextToolWorkspace(val project: Project, val folder: File, val request: ToolRequest) {
    val effectID: UUID = request.effectID ?: newUUID()
    var text by mutableStateOf(TextOverlay())
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
    private var existingMotion: TitleMotion? = null
    private var existingTitleRange: ClosedFloatingPointRange<Double>? = null
    /** Position within the title's interval. */
    var position by mutableDoubleStateOf(0.0)
        private set
    /** The player of the preview (Swift `player`), set by the screen. */
    var preview: EditorController? = null
    private var auditionEnd: Double? = null
    private var draftTiming: Triple<ClosedFloatingPointRange<Double>, TitleMotion?, PreviewTitle>? = null

    val start: Double get() = timing.range.start
    val end: Double get() = timing.range.endInclusive
    val duration: Double get() = timing.duration
    val canApply: Boolean get() = text.text.isNotBlank()
    val playing: Boolean get() = preview?.playing == true
    val canvas: CGSize? get() = preview?.canvas

    init {
        val ids = request.clipIDs ?: project.edit.layout.placements
            .filter { it.start < request.range.endInclusive && it.end > request.range.start }.map { it.clip.id }.toSet()
        request.effectID?.let { id ->
            project.edit.clips.filter { it.id in ids }.firstNotNullOfOrNull { clip -> clip.texts?.firstOrNull { it.id == id } }?.let { existing ->
                text = existing
                existingMotion = existing.motion
                existingTitleRange = TitleMotionRanges(project.edit).range(existing, request.range.start)
            }
        }
        if (request.effectID == null) text = text.copy(text = "")
        text = text.copy(id = effectID)
    }

    /** The clock of the draft's motion: the saved run while its timing is unchanged. */
    val titleMotionRange: ClosedFloatingPointRange<Double>
        get() {
            val saved = existingTitleRange
            if (start == request.range.start && end == request.range.endInclusive && text.motion == existingMotion && saved != null) return saved
            return start..max(start, end)
        }

    fun setTitleDesign(design: TitleDesign) { text = text.copy(appearance = design) }

    /**
     * Swift `state(removing:forPreview:)` for `.text`: the title on every clip it
     * covers, in that clip's source time. A style-only edit keeps each fragment's source timing.
     */
    fun state(removing: Boolean = false, forPreview: Boolean = false): EditState {
        val edit = project.edit
        val positions = edit.layout.placements
        val ids = request.clipIDs
        edit.clips = edit.clips.mapIndexed { index, original ->
            if (ids != null && original.id !in ids) return@mapIndexed original
            val cursor = positions[index].start
            val previous = original.texts.orEmpty().firstOrNull { it.id == effectID }
            var texts = original.texts.orEmpty().filter { it.id != effectID }
            if (!removing && !forPreview && request.effectID != null && start == request.range.start && end == request.range.endInclusive) {
                if (previous != null) texts = texts + text.copy(start = previous.start, end = previous.end)
                return@mapIndexed original.copy(texts = texts.ifEmpty { null })
            }
            val a = max(start, cursor)
            val b = min(end, cursor + original.duration)
            if (!removing && !forPreview && b > a + 0.000001) {
                texts = texts + text.copy(start = original.sourceTime(a - cursor), end = original.sourceTime(b - cursor))
            }
            original.copy(texts = texts.ifEmpty { null })
        }
        return edit
    }

    /** Swift `refreshTitlePreview`: the draft title on the clips it will cover, with its motion clock. */
    fun refreshTitlePreview() {
        val player = preview ?: return
        val cached = draftTiming
        val draft = if (cached != null && cached.first == timing.range && cached.second == text.motion) cached.third.copy(text = text) else {
            val edit = state()
            val clips = HashMap<UUID, ClosedFloatingPointRange<Double>>()
            for (clip in edit.clips) {
                if (request.clipIDs?.contains(clip.id) == false) continue
                clip.texts?.firstOrNull { it.id == effectID }?.let { clips[clip.id] = it.start..it.end }
            }
            PreviewTitle(text, clips, TitleMotionRanges(edit)).also { draftTiming = Triple(timing.range, text.motion, it) }
        }
        player.previewTitle = if (text.text.isEmpty()) null else draft
    }

    /** Swift `startPreview`/`rebuild` for text: the project without the draft, bounded to the interval. */
    fun startPreview() {
        val player = preview ?: return
        val candidate = project.copy()
        candidate.history = listOf(state(forPreview = true)); candidate.historyCursor = 0
        player.previewRange = start..end
        player.seekAfterEdit(start + position, candidate.edit.duration)
        player.load(candidate, folder, includeTimelineDetails = false)
        refreshTitlePreview()
    }

    fun seek(value: Double, playThrough: Double? = null) {
        auditionEnd = null
        val player = preview
        player?.pause()
        position = min(max(0.0, value), max(0.0, duration - TimelineEditing.frame))
        player?.seek(start + position)
        if (playThrough != null && player != null) { auditionEnd = playThrough; player.togglePlay() }
    }

    fun togglePlay() {
        val player = preview ?: return
        auditionEnd = null
        if (player.playing) { player.pause(); return }
        if (position >= duration - 0.05) seek(0.0)
        player.togglePlay()
    }

    /** Swift `replayTitle(exit:)`: play the entrance or exit window once. */
    fun replayTitle(exit: Boolean) {
        val motion = text.motion ?: return
        if (text.text.isEmpty()) return
        val clock = titleMotionRange
        val window = motion.previewWindow(exit, clock.endInclusive - clock.start)
        val from = max(0.0, min(duration, clock.start + window.start - start))
        val to = max(0.0, min(duration, clock.start + window.endInclusive - start))
        if (to > from) seek(from, playThrough = to)
    }

    /** Swift `trim(_:to:previewEdge:)`: text previews only change the playback window and the draft's clock. */
    fun trim(edge: OverlayTiming.Edge, value: Double, previewEdge: Boolean = true) {
        val absolutePlayhead = start + position
        val updated = timing.copy()
        updated.set(edge, value)
        if (updated.range == timing.range) return
        timing = updated
        val lastFrame = max(0.0, duration - TimelineEditing.frame)
        val target = if (previewEdge) (if (edge == OverlayTiming.Edge.start) 0.0 else lastFrame) else min(lastFrame, max(0.0, absolutePlayhead - start))
        preview?.let { it.pause(); it.previewRange = start..end }
        refreshTitlePreview()
        seek(target)
    }

    /** The preview's periodic observer: follow its playhead and end auditions. */
    fun followPreview() {
        val player = preview ?: return
        if (!player.playing) return
        position = min(duration, max(0.0, player.playhead - start))
        val stop = auditionEnd
        if (stop != null && position >= stop - 0.015) seek(stop)
    }

    /** The composed picture at the playhead for colour sampling: the source frame of the clip there (crop not applied). */
    suspend fun frame(): Bitmap? {
        val time = start + position
        val placement = project.edit.layout.placement(time) ?: return null
        val source = project.sources.firstOrNull { it.id == placement.clip.sourceID } ?: return null
        return CropMedia.Android.frame(source, folder, placement.clip.sourceTime(time - placement.start))
    }
}

@Composable
private fun toolSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f), disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray)

@Composable
fun TextToolEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { TextToolWorkspace(project, folder, request) }
    val preview = remember { EditorController(context) }
    val focus = LocalFocusManager.current
    DisposableEffect(model) {
        model.preview = preview
        model.startPreview()
        onDispose { preview.release() }
    }
    LaunchedEffect(model) {
        while (true) { model.followPreview(); delay(33) }
    }
    LaunchedEffect(model.text, model.timing) { model.refreshTitlePreview() }
    BackHandler(onBack = cancel)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        val boxWidth = maxWidth; val boxHeight = maxHeight
        val inspectorWidth = if (landscape) minOf(320.dp, boxWidth * 0.4f) else boxWidth
        Column(Modifier.fillMaxSize()) {
            // Swift header: Cancel · "Text" over "scope · start–end" · Apply.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color(0.06f, 0.06f, 0.06f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", Modifier.widthIn(min = 60.dp), action = cancel)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(VideoTool.text.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(request.scopeTitle + " · " + editorTime(model.start) + "–" + editorTime(model.end), color = EditorColors.secondary,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Apply", Modifier.widthIn(min = 60.dp), enabled = model.canApply, bold = true) {
                    focus.clearFocus(); preview.pause(); apply(model.state())
                }
            }
            @Composable fun pictureAndPlayback(modifier: Modifier) {
                Column(modifier) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                        TextCanvas(model, preview) { focus.clearFocus() }
                        if (preview.loading) {
                            Box(Modifier.background(Color(0.2f, 0.2f, 0.22f, 0.85f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    TextPlayback(model, preview, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                }
            }
            @Composable fun settings(modifier: Modifier) {
                Column(modifier.background(Color(0.08f, 0.08f, 0.08f)).verticalScroll(rememberScrollState()).imePadding().padding(16.dp)
                    .semantics { contentDescription = "Text settings" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    preview.error?.let { Text(it, color = EditorColors.orange, fontSize = 16.sp) }
                    TextSettings(model, inspectorWidth - 32.dp) { focus.clearFocus() }
                    if (request.effectID != null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { preview.pause(); apply(model.state(removing = true)) }
                            .semantics { contentDescription = "Remove text" }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Delete, null, tint = EditorColors.accent)
                            Spacer(Modifier.width(6.dp))
                            Text("Remove text", color = EditorColors.accent, fontSize = 17.sp)
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
 * Swift `textCanvas`: the project picture in the canvas shape with the draft
 * title drawn as it will export; a hit area over the title drags it (snapping
 * to the centre lines) and a pinch anywhere resizes it.
 */
@Composable
private fun TextCanvas(model: TextToolWorkspace, preview: EditorController, tapped: () -> Unit) {
    val canvas = model.canvas ?: preview.canvas
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bounds = CGRect(0.0, 0.0, constraints.maxWidth.toDouble(), constraints.maxHeight.toDouble())
        val window = canvas?.let { aspectFitRect(it, bounds) }
        val currentModel by rememberUpdatedState(model)
        PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles, layers = preview.layers, captions = preview.subtitles, inspectionZoom = false,
            onPlaybackTap = null)
        if (canvas != null && window != null && window.width > 0) {
            val scale = window.width / canvas.width
            var pinchSize by remember { mutableStateOf<Double?>(null) }
            // Pinch to resize anywhere over the picture.
            Box(Modifier.fillMaxSize().pointerInput(canvas) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        val m = currentModel
                        m.text = m.text.copy(size = min(0.3, max(0.02, m.text.size * zoom)))
                    }
                }
            })
            val bitmap = if (model.text.text.isEmpty()) null else remember(model.text.copy(x = 0.5, y = 0.5), canvas) { TitleBitmaps(1).image(model.text, canvas) }
            if (bitmap != null) {
                val origin = TextRasterizer.origin(model.text, CGSize(bitmap.width.toDouble(), bitmap.height.toDouble()), canvas)
                val w = max(with(density) { 44.dp.toPx() }.toDouble(), bitmap.width * scale + with(density) { 16.dp.toPx() })
                val h = max(with(density) { 44.dp.toPx() }.toDouble(), bitmap.height * scale + with(density) { 16.dp.toPx() })
                val cx = window.minX + (origin.x + bitmap.width / 2.0) * scale
                val cy = window.minY + window.height - (origin.y + bitmap.height / 2.0) * scale
                Box(Modifier.align(Alignment.TopStart).offset { IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()) }
                    .size(with(density) { w.toFloat().toDp() }, with(density) { h.toFloat().toDp() })
                    .pointerInput(canvas, window) {
                        var origin0: Pair<Double, Double>? = null
                        var dx = 0.0; var dy = 0.0
                        detectDragGestures(onDragStart = { origin0 = currentModel.text.x to currentModel.text.y; dx = 0.0; dy = 0.0 },
                            onDragEnd = { origin0 = null }, onDragCancel = { origin0 = null }) { change, drag ->
                            change.consume()
                            val start = origin0 ?: return@detectDragGestures
                            dx += drag.x; dy += drag.y
                            val x = min(1.0, max(0.0, start.first + dx / max(1.0, window.width)))
                            val y = min(1.0, max(0.0, start.second + dy / max(1.0, window.height)))
                            val m = currentModel
                            m.text = m.text.copy(x = if (abs(x - 0.5) < 0.015) 0.5 else x, y = if (abs(y - 0.5) < 0.015) 0.5 else y)
                        }
                    }
                    .clickable { tapped() }
                    .semantics { contentDescription = "Text position" })
            }
        }
    }
}

@Composable
private fun TextPlayback(model: TextToolWorkspace, preview: EditorController, modifier: Modifier) {
    Row(modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).alpha(if (preview.loading) 0.4f else 1f).clickable(enabled = !preview.loading) { model.togglePlay() }
            .semantics { contentDescription = if (model.playing) "Pause text preview" else "Play text preview" }, contentAlignment = Alignment.Center) {
            Icon(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
        }
        Text(editorTime(model.position), color = Color.White, fontSize = 12.sp, style = tabular, maxLines = 1)
        Slider(model.position.toFloat(), { model.seek(it.toDouble()) },
            valueRange = 0f..max(TimelineEditing.frame, model.duration - TimelineEditing.frame).toFloat(), enabled = !preview.loading,
            colors = toolSliderColors(), modifier = Modifier.weight(1f).semantics { contentDescription = "Tool preview position" })
        Text(editorTime(model.duration), color = EditorColors.secondary, fontSize = 12.sp, style = tabular, maxLines = 1)
    }
}

/** Swift `textSettings(width:)`. */
@Composable
private fun TextSettings(model: TextToolWorkspace, width: androidx.compose.ui.unit.Dp, unfocus: () -> Unit) {
    var positionOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(model.text.text, { value ->
            model.text = model.text.copy(text = if (value.codePointCount(0, value.length) > 500) value.substring(0, value.offsetByCodePoints(0, 500)) else value)
        }, placeholder = { Text("Write something") }, minLines = 1, maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = EditorColors.accent,
                focusedBorderColor = EditorColors.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Text" })
        OverlayTimingControl(model.timing, model.start, model.end, VideoTool.text, width, unfocus) { edge, value, previewEdge -> model.trim(edge, value, previewEdge) }
        TitleInspector(design = model.text.design, setDesign = model::setTitleDesign,
            motion = model.text.motion ?: TitleMotion(), setMotion = { model.text = model.text.copy(motion = it) },
            sample = model.text.text, prefix = "title", frame = { model.frame() }, replay = model::replayTitle,
            changeSection = unfocus, pauseForFonts = { if (model.playing) model.togglePlay() })
        Text("Drag to move · pinch to resize", color = EditorColors.secondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", color = Color.White, fontSize = 15.sp)
            Slider(model.text.size.toFloat(), { model.text = model.text.copy(size = it.toDouble()) }, valueRange = 0.02f..0.3f, colors = toolSliderColors(),
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { contentDescription = "Text size" })
        }
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { positionOpen = !positionOpen }, verticalAlignment = Alignment.CenterVertically) {
                Text("Position", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(if (positionOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = EditorColors.accent)
            }
            if (positionOpen) {
                for (horizontal in listOf(true, false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (horizontal) "Across" else "Down", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(60.dp))
                        Slider((if (horizontal) model.text.x else model.text.y).toFloat(), {
                            model.text = if (horizontal) model.text.copy(x = it.toDouble()) else model.text.copy(y = it.toDouble())
                        }, valueRange = 0f..1f, colors = toolSliderColors(),
                            modifier = Modifier.weight(1f).semantics { contentDescription = if (horizontal) "Text horizontal position" else "Text vertical position" })
                    }
                }
                Box(Modifier.heightIn(min = 44.dp).clickable { model.text = model.text.copy(x = 0.5, y = 0.5) }, contentAlignment = Alignment.CenterStart) {
                    Text("Centre text", color = EditorColors.accent, fontSize = 17.sp)
                }
            }
        }
    }
}

/** Swift `overlayTiming(width:)`: duration presets, the time available, To end, and Start/End sliders. */
@Composable
internal fun OverlayTimingControl(timing: OverlayTiming, start: Double, end: Double, tool: VideoTool, width: androidx.compose.ui.unit.Dp,
                                  unfocus: () -> Unit, trim: (OverlayTiming.Edge, Double, Boolean) -> Unit) {
    EditorDurationPicker(title = "Duration", seconds = timing.duration, maximum = timing.available, width = width,
        choices = EditorDurationPresets.wholeSeconds, identifier = "overlay.timing", presetPrefix = "overlay.duration", onToggle = unfocus,
        select = { trim(OverlayTiming.Edge.end, start + it, false) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(secondsText(timing.available) + " s available", color = EditorColors.secondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.heightIn(min = 44.dp).clickable { trim(OverlayTiming.Edge.end, timing.bounds.endInclusive, false) }
                .semantics { contentDescription = "Extend to end" }, contentAlignment = Alignment.Center) {
                Text("To end", color = EditorColors.accent, fontSize = 17.sp)
            }
        }
        for (edge in OverlayTiming.Edge.entries) {
            val value = if (edge == OverlayTiming.Edge.start) start else end
            Row {
                Text(if (edge == OverlayTiming.Edge.start) "Start" else "End", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(editorTime(value), color = Color.White, fontSize = 12.sp, style = tabular)
            }
            val limits = timing.limits(edge)
            val span = limits.endInclusive - limits.start
            // A fixed 0…1 domain, as Swift: the other handle changes the available time.
            val fraction = if (span > 0) min(1.0, max(0.0, (value - limits.start) / span)) else if (edge == OverlayTiming.Edge.start) 0.0 else 1.0
            Slider(fraction.toFloat(), { trim(edge, timing.time(it.toDouble(), edge), true) }, enabled = EditTime.of(span) != EditTime.zero,
                colors = toolSliderColors(), modifier = Modifier.semantics {
                    contentDescription = tool.title + if (edge == OverlayTiming.Edge.start) " start" else " end"
                    stateDescription = editorTime(value)
                })
        }
    }
}
