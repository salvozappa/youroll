// Port of YouRec/TransitionEditor.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// TransitionWorkspace is the draft (settings of one join), TransitionEditor the
// full-screen tool: the picture (a still of the transition's midpoint, or the
// playing preview), then the controls of the chosen style, audio timing and
// Remove. As on iOS the workspace reviews a two-clip project at most 960 px
// wide; its still and its playback come from the same renderer as the export
// (TransitionRenderer.kt), so what is reviewed is what is exported.
// The timeline chip and bubble of a join (Swift `effectChips`, `EffectRemovalBubble`)
// are at the end of this file.
//
// Not ported (TODO.md): the system colour picker (a swatch row with RGB sliders
// stands in), the instant CoreImage still during split framing gestures (the
// still re-renders 200 ms after a change), `InspectPreview` pinch inspection,
// accessibility-size layouts of the style grid, and `TransitionDiagnostics`.
package com.salvozappa.youroll

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PausePresentation
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Material Symbols closest to the SF Symbols of `TransitionStyle.symbol`. */
val TransitionStyle.icon: ImageVector
    get() = when (this) {
        TransitionStyle.dissolve -> Icons.Outlined.FilterNone
        TransitionStyle.fadeColor -> Icons.Outlined.Contrast
        TransitionStyle.wipe -> Icons.Outlined.ViewSidebar
        TransitionStyle.split -> Icons.Outlined.VerticalSplit
        TransitionStyle.hitchcock -> Icons.Outlined.Tonality
        TransitionStyle.whip -> Icons.Outlined.Air
        TransitionStyle.flashCut -> Icons.Filled.Bolt
        TransitionStyle.still -> Icons.Outlined.PausePresentation
        TransitionStyle.defocus -> Icons.Outlined.Camera
    }

class TransitionWorkspace(val project: Project, val folder: File, val boundary: Int) {
    val existing: Boolean
    var settings by mutableStateOf(ClipTransition())
    var canvas by mutableStateOf(CGSize(1920.0, 1080.0)); private set
    var still by mutableStateOf<Bitmap?>(null); private set
    var busy by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var previewRange by mutableStateOf(0.0..1.0); private set
    /** The player of the transition preview, set by the screen. */
    var preview: EditorController? = null
    /** Content sizes (canvas pixels without bars) of the outgoing and incoming pictures, for split framing. */
    var firstSize by mutableStateOf(CGSize(1920.0, 1080.0)); private set
    var secondSize by mutableStateOf(CGSize(1920.0, 1080.0)); private set
    private val visitedStyles = mutableSetOf<TransitionStyle>()
    private var hasChosenAxis = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var cachedJoin: Pair<ClipTransition, Pair<TransitionJoin, Double>>? = null

    val originalJoin: TransitionJoin get() = project.edit.layout.joins.first { it.index == boundary }
    private val evaluated: Pair<TransitionJoin, Double>
        get() {
            cachedJoin?.let { if (it.first == settings) return it.second }
            val state = state()
            val join = state.layout.joins.firstOrNull { it.transition?.id == settings.id } ?: originalJoin
            return (join to state.duration).also { cachedJoin = settings to it }
        }
    val join: TransitionJoin get() = evaluated.first
    val maximum: Double get() = if (settings.style == TransitionStyle.still) maxOf(60.0, project.edit.duration, settings.duration) else join.maximum
    val audioMaximum: Double get() = min(60.0, max(2.0, project.edit.clips.maxOfOrNull { it.duration } ?: 2.0))
    val displayedAudioBlend: Double
        get() = settings.audioBlend ?: (if (settings.style == TransitionStyle.fadeColor && (isOpening || isClosing)) settings.duration + settings.holdDuration else settings.defaultAudioBlend)
    val canApply: Boolean get() = !busy && error == null && still != null && effectiveDuration >= TimelineEditing.frame - 0.000001
    val isOpening: Boolean get() = originalJoin.edge == -1
    val isClosing: Boolean get() = originalJoin.edge == 1
    val title: String get() = if (isOpening) "Start · fade in" else if (isClosing) "End · fade out" else "Clip $boundary → ${boundary + 1}"
    val effectiveDuration: Double get() = join.timing.videoDuration
    private val straightDuration: Double by lazy { project.edit.settingTransition(null, boundary).duration }
    val lengthNote: String
        get() {
            val change = evaluated.second - straightDuration
            val length = join.range.endInclusive - join.range.start
            val outcome = if (abs(change) < 0.00001) "Project length unchanged"
                else "Project ${"%.2f".format(Locale.US, abs(change))} s ${if (change < 0) "shorter" else "longer"}"
            return "${"%.2f".format(Locale.US, length)} s total · $outcome"
        }

    init {
        val join = originalJoin
        existing = join.transition != null
        settings = join.transition ?: ClipTransition(duration = min(0.5, join.maximum))
        visitedStyles += settings.style
    }

    fun selectStyle(style: TransitionStyle) {
        var value = settings
        if (style !in visitedStyles) {
            if (style == TransitionStyle.whip) value = value.copy(duration = 8.0 / 30)
            if (style == TransitionStyle.still) value = value.copy(duration = 1.0)
            visitedStyles += style
        }
        settings = value.copy(style = style)
    }

    fun chooseAxis(axis: TransitionAxis) { hasChosenAxis = true; settings = settings.copy(axis = axis) }
    fun resetFraming() { settings = settings.copy(first = TransitionFraming(), second = TransitionFraming(), divider = 0.5) }

    fun state(removing: Boolean = false): EditState =
        if (removing) project.edit.restoringStraightCut(boundary) else project.edit.settingTransition(settings, boundary)

    /** Swift `previewProject`: the two neighbours only, fitted exactly as in the full project. */
    private fun previewProject(): Pair<Project, Int> {
        val original = originalJoin
        val ids = listOfNotNull(original.outgoingID, original.incomingID)
        val clips = ids.mapNotNull { id -> project.edit.clips.firstOrNull { it.id == id }?.copy(openingTransition = null, transition = null) }
        val localBoundary = if (isOpening) 0 else 1
        val factor = join.factor
        var value = settings
        if (settings.style != TransitionStyle.still || (settings.customAudio && !isClosing)) value = value.copy(duration = value.duration * factor)
        if (settings.style != TransitionStyle.fadeColor || isOpening || isClosing || settings.customAudio) value = value.copy(hold = settings.hold?.let { it * factor })
        value = value.copy(audioOffset = settings.audioOffset?.let { it * factor }, audioBlend = settings.audioBlend?.let { it * factor },
            stillDissolve = settings.stillDissolve?.let { it * factor })
        val candidate = project.copy()
        candidate.history = listOf(EditState(clips, outputSize = canvas).settingTransition(value, localBoundary)); candidate.historyCursor = 0
        return candidate to localBoundary
    }

    fun start() {
        job?.cancel(); busy = true
        job = scope.launch {
            try {
                val (original, sizes) = withContext(Dispatchers.IO) {
                    val canvas = MediaRenderer.canvas(project, folder)
                    // The pictures' sizes without bars, for split-screen framing.
                    val scale = min(1.0, 960 / max(canvas.width, canvas.height))
                    val reviewed = CGSize(max(2.0, round(canvas.width * scale / 2) * 2), max(2.0, round(canvas.height * scale / 2) * 2))
                    val layers = listOf(originalJoin.outgoingID, originalJoin.incomingID).map { id ->
                        val item = project.edit.timeline.video.firstOrNull { it.id == id } ?: return@map reviewed
                        val source = project.sources.firstOrNull { it.id == item.content.sourceID } ?: return@map reviewed
                        val info = VideoGeometry.read(File(folder, source.filename)) ?: return@map reviewed
                        val oriented = CGSize(info.orientedWidth.toDouble(), info.orientedHeight.toDouble())
                        TransitionComposite.contentBounds(PreviewFraming(reviewed, oriented, item.content.crop).transform, oriented, reviewed).size
                    }
                    reviewed to layers
                }
                // Review at screen resolution; saved framing uses normalized coordinates.
                canvas = original
                firstSize = if (isOpening) canvas else sizes[0]
                secondSize = if (isClosing) canvas else sizes[1]
                if (!existing && !hasChosenAxis) settings = settings.copy(axis = if (canvas.height > canvas.width) TransitionAxis.vertical else TransitionAxis.horizontal)
                rebuild(delay = false)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "This transition could not be previewed."; busy = false
            }
        }
    }

    /** Swift `rebuild(delay:)`: the midpoint still, then the preview playlist. */
    fun rebuild(delay: Boolean = true) {
        job?.cancel(); pause(); busy = true; error = null
        job = scope.launch {
            if (delay) delay(200)
            try {
                val (candidate, joinIndex) = previewProject()
                val join = candidate.edit.layout.joins.first { it.index == joinIndex }
                val duration = candidate.edit.duration
                previewRange = max(0.0, join.range.start - 0.65)..min(duration, join.range.endInclusive + 0.65)
                val moment = min(duration - TimelineEditing.frame, max(0.0, join.origin + join.timing.videoDuration / 2))
                val window = candidate.edit.timeline.transitionWindows().firstOrNull { it.id == settings.id }
                val render = window?.let { TransitionRenderJob.of(it, project.sources, folder, canvas.width.toInt(), canvas.height.toInt()) }
                still = render?.let { TransitionRenderer.still(it, EditTime.of(moment), folder) }
                val player = preview
                if (player != null) {
                    player.previewRange = previewRange
                    player.seekAfterEdit(moment, duration)
                    player.load(candidate, folder, includeTimelineDetails = false)
                }
                busy = false
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                android.util.Log.w("YouRoll", "Transition preview failed", failure)
                error = "This transition could not be previewed."; busy = false
            }
        }
    }

    fun changed() { pause(); rebuild() }
    fun pause() { preview?.pause() }

    fun play() {
        val player = preview ?: return
        if (!canApply) return
        if (player.playing) { player.pause(); return }
        player.seekPaused(previewRange.start)
        player.togglePlay()
    }

    fun seek(time: Double) { preview?.seekPaused(time) }
    fun stop() { job?.cancel(); pause() }
    fun release() { scope.cancel() }
}

private val orange = EditorColors.orange

@Composable
private fun orangeSlider() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = orange, inactiveTrackColor = Color.White.copy(alpha = 0.2f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionEditor(project: Project, folder: File, request: ToolRequest, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { TransitionWorkspace(project, folder, request.boundaryIndex ?: 0) }
    val preview = remember { EditorController(context) }
    var framing by remember { mutableStateOf(true) }
    var choosingStyle by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        model.preview = preview
        model.start()
        onDispose { model.stop(); model.release(); preview.release() }
    }
    // Swift `.onChange(of: model.settings) { framing = true; model.changed() }`.
    var shown by remember { mutableStateOf(model.settings) }
    LaunchedEffect(model.settings) {
        if (model.settings != shown) { shown = model.settings; framing = true; model.changed() }
    }
    BackHandler { model.stop(); cancel() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val horizontal = maxWidth > maxHeight
        val inspectorWidth = if (horizontal) minOf(340.dp, maxWidth * 0.4f) else maxWidth
        val boxHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            SheetBar("Transition", leading = "Cancel", leadingAction = { model.stop(); cancel() }, trailing = "Apply",
                trailingAction = { if (model.canApply) { model.stop(); apply(model.state()) } }, trailingEnabled = model.canApply, trailingBold = true)
            @Composable fun picture(modifier: Modifier) {
                Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(model.title, color = EditorColors.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                        val scale = min(maxWidth.value / model.canvas.width.toFloat(), maxHeight.value / model.canvas.height.toFloat())
                        Box(Modifier.size((model.canvas.width * scale).dp, (model.canvas.height * scale).dp), contentAlignment = Alignment.Center) {
                            val image = model.still
                            if (framing && image != null) {
                                Image(image.asImageBitmap(), "Transition at its midpoint", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                if (model.settings.style == TransitionStyle.split) {
                                    TransitionFramingSurface(model.settings, model.canvas, model.firstSize, model.secondSize,
                                        firstEnabled = !model.isOpening, secondEnabled = !model.isClosing) { model.settings = it }
                                }
                            } else {
                                PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, inspectionZoom = false)
                            }
                            if ((model.busy && model.still == null) || (!framing && preview.loading)) {
                                Row(Modifier.background(Color(0.2f, 0.2f, 0.22f, 0.85f), RoundedCornerShape(12.dp)).padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text("Preparing preview…", color = Color.White, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                    model.error?.let { message ->
                        Text(message, color = orange, fontSize = 12.sp)
                        PillButton("Retry") { model.rebuild(delay = false) }
                    }
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val enabled = model.canApply && !preview.loading
                        Box(Modifier.size(44.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled) { framing = false; model.play() }
                            .testTag("transition.play").semantics { contentDescription = if (preview.playing) "Pause transition" else "Play transition" },
                            contentAlignment = Alignment.Center) {
                            Icon(if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = orange)
                        }
                        val range = model.previewRange
                        Slider(value = preview.playhead.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                            onValueChange = { framing = false; model.seek(it.toDouble()) },
                            valueRange = range.start.toFloat()..max(range.start + 0.001, range.endInclusive).toFloat(),
                            enabled = !model.busy, colors = orangeSlider(),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Transition preview position" })
                        if (model.settings.style == TransitionStyle.split) {
                            Box(Modifier.size(44.dp).clickable { model.pause(); framing = true }.semantics { contentDescription = "Frame split screen" },
                                contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CenterFocusWeak, null, tint = orange) }
                        }
                    }
                }
            }
            @Composable fun inspector(modifier: Modifier) {
                Column(modifier.background(Color(0.07f, 0.07f, 0.07f)).verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 24.dp)
                    .testTag("transition.controls")) {
                    TransitionControls(model, (inspectorWidth - 32.dp), choose = { choosingStyle = true },
                        remove = { model.stop(); apply(model.state(removing = true)) })
                }
            }
            if (horizontal) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    picture(Modifier.weight(1f).fillMaxHeight())
                    inspector(Modifier.width(inspectorWidth).fillMaxHeight())
                }
            } else {
                picture(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                inspector(Modifier.fillMaxWidth().height(boxHeight * 0.48f))
            }
        }
    }
    if (choosingStyle) {
        ModalBottomSheet(onDismissRequest = { choosingStyle = false }, containerColor = Color(0.06f, 0.06f, 0.06f), dragHandle = { SheetGrabber() }) {
            SheetBar("Transition style", trailing = "Done", trailingBold = true, trailingAction = { choosingStyle = false })
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(TransitionStyle.allCases) { style ->
                    val selected = model.settings.style == style
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
                        .background(if (selected) orange.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .then(if (selected) Modifier.border(1.dp, orange, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { model.selectStyle(style); choosingStyle = false }.padding(horizontal = 6.dp)
                        .testTag("transition.style." + style.rawValue).semantics { if (selected) stateDescription = "Selected" },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(style.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(style.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** iOS segmented `Picker(.segmented)`. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, tag: String, choose: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(2.dp).testTag(tag)) {
        for ((value, title) in options) {
            val chosen = value == selected
            Box(Modifier.weight(1f).heightIn(min = 32.dp).background(if (chosen) Color.White.copy(alpha = 0.28f) else Color.Transparent, RoundedCornerShape(7.dp))
                .clickable { choose(value) }.semantics { contentDescription = title; if (chosen) stateDescription = "Selected" },
                contentAlignment = Alignment.Center) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Swift `seconds(_:value:range:id:)`: a titled slider in frame steps. */
@Composable
private fun SecondsSlider(title: String, value: Double, range: ClosedFloatingPointRange<Double>, id: String, change: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text("%.2f s".format(Locale.US, value), color = Color.White, fontSize = 17.sp, style = tabular, modifier = Modifier.testTag("$id.value"))
        }
        Box(contentAlignment = Alignment.Center) {
            Slider(value = value.coerceIn(range.start, range.endInclusive).toFloat(),
                onValueChange = { change(min(range.endInclusive, max(range.start, round(it / TimelineEditing.frame) * TimelineEditing.frame))) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(), colors = orangeSlider(),
                modifier = Modifier.fillMaxWidth().testTag(id).semantics { contentDescription = title })
            if (range.start < 0 && range.endInclusive == -range.start) {
                Box(Modifier.size(1.dp, 14.dp).background(Color.White.copy(alpha = 0.65f)))
            }
        }
    }
}

@Composable
private fun TransitionControls(model: TransitionWorkspace, width: androidx.compose.ui.unit.Dp, choose: () -> Unit, remove: () -> Unit) {
    val settings = model.settings
    fun update(transform: (ClipTransition) -> ClipTransition) { model.settings = transform(model.settings) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = choose).testTag("transition.stylePicker")
            .semantics { contentDescription = "Transition style, " + settings.style.title }, verticalAlignment = Alignment.CenterVertically) {
            Icon(settings.style.icon, null, tint = orange, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(settings.style.title, color = orange, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.UnfoldMore, null, tint = orange, modifier = Modifier.size(16.dp))
        }
        if (settings.style == TransitionStyle.fadeColor) FadeColorPicker(settings.resolvedFadeColor) { color ->
            update { it.copy(fadeColor = if (color == TransitionColor.black) null else color) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val maximum = model.maximum
            EditorDurationPicker(if (settings.style == TransitionStyle.still) "Freeze duration" else "Blend duration", settings.duration, maximum, width,
                EditorDurationPresets.transitionSeconds, "transition.timing", "transition.duration", tint = orange,
                select = { value -> update { it.copy(duration = value) } }) {
                Text("Up to ${secondsText(maximum)} s", color = EditorColors.secondary, fontSize = 13.sp)
            }
            // A one-frame neighbour has only one valid duration, so adjustment is disabled.
            val upper = max(2 * TimelineEditing.frame, maximum)
            Slider(value = min(max(TimelineEditing.frame, maximum), max(TimelineEditing.frame, settings.duration)).toFloat(),
                onValueChange = { v -> val value = max(TimelineEditing.frame, round(v / TimelineEditing.frame) * TimelineEditing.frame); update { it.copy(duration = min(upper, value)) } },
                valueRange = TimelineEditing.frame.toFloat()..upper.toFloat(), enabled = maximum >= 2 * TimelineEditing.frame, colors = orangeSlider(),
                modifier = Modifier.fillMaxWidth().testTag("transition.duration").semantics { contentDescription = "Transition duration" })
            Text(model.lengthNote, color = EditorColors.secondary, fontSize = 12.sp)
            if (model.join.factor < 0.99999) {
                Text("Picture and audio timing reduced to ${"%.0f".format(Locale.US, model.join.factor * 100)}% to fit the neighboring clips. Your requested settings are kept.",
                    color = orange, fontSize = 12.sp)
            }
        }
        if (settings.style == TransitionStyle.split || settings.style == TransitionStyle.whip) {
            Segmented(TransitionAxis.entries.map { it to it.title }, settings.axis, "transition.axis") { model.chooseAxis(it) }
        }
        if (settings.style == TransitionStyle.whip) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reverse direction", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Switch(settings.reverse, { value -> update { it.copy(reverse = value) } }, colors = SwitchDefaults.colors(checkedTrackColor = orange))
            }
        }
        if (settings.style == TransitionStyle.wipe) {
            Segmented(listOf(false to "Soft edge", true to "Hard edge"), settings.hardEdge ?: false, "transition.wipeEdge") { value -> update { it.copy(hardEdge = value) } }
            Row {
                Text("Angle", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("${Math.round(settings.angle)}°", color = Color.White, fontSize = 17.sp, style = tabular)
            }
            val shownAngle = settings.angle.let { if (it > 180) it - 360 else it }
            Slider(value = shownAngle.toFloat(), onValueChange = { v -> update { it.copy(wipeAngle = round(v.toDouble()), reverse = false) } },
                valueRange = -180f..180f, colors = orangeSlider(), modifier = Modifier.fillMaxWidth().testTag("transition.wipeAngle").semantics { contentDescription = "Wipe angle" })
        }
        if (settings.style == TransitionStyle.hitchcock) {
            Segmented(listOf(false to "Shadows first", true to "Highlights first"), settings.highlightsFirst ?: false, "transition.luminanceOrder") { value ->
                update { it.copy(highlightsFirst = value) }
            }
        }
        if (settings.style == TransitionStyle.flashCut) {
            val count = settings.flashCount ?: 3
            Row(Modifier.heightIn(min = 44.dp).testTag("transition.flashes"), verticalAlignment = Alignment.CenterVertically) {
                Text("Flashes: $count", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Row(Modifier.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))) {
                    Box(Modifier.size(46.dp, 32.dp).alpha(if (count > 1) 1f else 0.35f).clickable(enabled = count > 1) { update { it.copy(flashCount = count - 1) } }
                        .semantics { contentDescription = "Fewer flashes" }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Remove, null, tint = Color.White) }
                    Box(Modifier.size(46.dp, 32.dp).alpha(if (count < 20) 1f else 0.35f).clickable(enabled = count < 20) { update { it.copy(flashCount = count + 1) } }
                        .semantics { contentDescription = "More flashes" }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, tint = Color.White) }
                }
            }
            Text("Brief glimpses of the next scene, closer together toward the cut.", color = EditorColors.secondary, fontSize = 12.sp)
            Row {
                Text("Acceleration", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("%.1f×".format(Locale.US, settings.flashAcceleration ?: 2.0), color = Color.White, fontSize = 17.sp)
            }
            Slider(value = (settings.flashAcceleration ?: 2.0).toFloat(), onValueChange = { v -> update { it.copy(flashAcceleration = round(v / 0.05) * 0.05) } },
                valueRange = 0.25f..4f, colors = orangeSlider(), modifier = Modifier.fillMaxWidth().testTag("transition.flashAcceleration"))
            val fitting = settings.flashFrames(model.effectiveDuration).size
            if (fitting < count) Text("$fitting flashes fit at this duration.", color = orange, fontSize = 12.sp)
        }
        if (settings.style == TransitionStyle.still) {
            val dissolves = (settings.stillDissolve ?: 0.0) > 0
            Segmented(listOf(false to "Cut", true to "Dissolve"), dissolves, "transition.stillExit") { value -> update { it.copy(stillDissolve = if (value) 0.3 else null) } }
            if (dissolves) SecondsSlider("Exit dissolve", settings.stillDissolve ?: 0.3, TimelineEditing.frame..5.0, "transition.stillDissolve") { value ->
                update { it.copy(stillDissolve = value) }
            }
            Text("Holds the outgoing picture, then cuts or dissolves into the next scene.", color = EditorColors.secondary, fontSize = 12.sp)
        }
        if (settings.style == TransitionStyle.defocus) {
            Row {
                Text("Optical blur", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("${((settings.blurAmount ?: 0.75) * 100).toInt()}%", color = Color.White, fontSize = 17.sp)
            }
            Slider(value = (settings.blurAmount ?: 0.75).toFloat(), onValueChange = { v -> update { it.copy(blurAmount = v.toDouble()) } },
                valueRange = 0.1f..1f, colors = orangeSlider(), modifier = Modifier.fillMaxWidth().testTag("transition.blur"))
        }
        if (settings.style.supportsHold) {
            SecondsSlider(if (settings.style == TransitionStyle.fadeColor && !model.isOpening && !model.isClosing) "Hold on color" else "Hold at halfway",
                settings.hold ?: 0.0, 0.0..max(10.0, min(60.0, model.maximum)), "transition.hold") { value ->
                update { it.copy(hold = if (value == 0.0) null else value) }
            }
        }
        if (settings.style == TransitionStyle.split) {
            Text("Drag each picture to pan · pinch to zoom. Drag the handle to move the divider.", color = EditorColors.secondary, fontSize = 12.sp)
            Row {
                Text("Divider", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("${Math.round(settings.divider * 100)}%", color = Color.White, fontSize = 17.sp, style = tabular)
            }
            Slider(value = settings.divider.toFloat(), onValueChange = { v -> update { it.copy(divider = v.toDouble()) } }, valueRange = 0.15f..0.85f,
                colors = orangeSlider(), modifier = Modifier.fillMaxWidth().testTag("transition.divider").semantics { contentDescription = "Divider position" })
            Segmented(TransitionAudio.entries.map { it to it.title }, settings.audio, "transition.audio") { value ->
                update { it.copy(audio = value, audioOffset = null, audioBlend = null) }
            }
            Box(Modifier.heightIn(min = 44.dp).clickable { model.resetFraming() }.testTag("transition.reset"), contentAlignment = Alignment.CenterStart) {
                Text("Reset framing", color = orange, fontSize = 17.sp)
            }
        }
        AudioTimingControls(model)
        if (model.existing) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .clickable(onClick = remove).testTag("transition.remove").semantics { contentDescription = "Remove transition" },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Delete, null, tint = EditorColors.red, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Remove transition", color = EditorColors.red, fontSize = 17.sp)
            }
            if (!model.isOpening && !model.isClosing) {
                Text("Restores a straight cut and keeps all source footage.", color = EditorColors.secondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AudioTimingControls(model: TransitionWorkspace) {
    val settings = model.settings
    fun update(transform: (ClipTransition) -> ClipTransition) { model.settings = transform(model.settings) }
    val maximum = model.audioMaximum
    @Composable fun StepButton(label: String, description: String, tag: String, icon: ImageVector? = null, action: () -> Unit) {
        Row(Modifier.heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).clickable(onClick = action)
            .padding(horizontal = 12.dp).testTag(tag).semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = orange, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
            Text(label, color = orange, fontSize = 15.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Audio timing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.heightIn(min = 44.dp).clickable { update { it.copy(audioOffset = null, audioBlend = null, audio = TransitionAudio.crossfade) } },
                contentAlignment = Alignment.Center) { Text("Reset", color = orange, fontSize = 15.sp) }
        }
        SecondsSlider("Start offset", settings.audioOffset ?: 0.0, -maximum..maximum, "transition.audioOffset") { value ->
            update { it.copy(audioOffset = value, audio = TransitionAudio.crossfade) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("0.1 s", "Start audio 0.1 seconds earlier", "transition.audioEarlier", Icons.Filled.Remove) {
                update { it.copy(audioOffset = max(-maximum, (it.audioOffset ?: 0.0) - 0.1), audio = TransitionAudio.crossfade) }
            }
            Spacer(Modifier.weight(1f))
            StepButton("0 s", "Start audio crossfade at picture transition start", "transition.audioAlign") {
                update { it.copy(audioOffset = 0.0, audio = TransitionAudio.crossfade) }
            }
            Spacer(Modifier.weight(1f))
            StepButton("0.1 s", "Start audio 0.1 seconds later", "transition.audioLater", Icons.Filled.Add) {
                update { it.copy(audioOffset = min(maximum, (it.audioOffset ?: 0.0) + 0.1), audio = TransitionAudio.crossfade) }
            }
        }
        val offset = settings.audioOffset ?: 0.0
        Text(if (offset < 0) "Sound starts before the picture transition." else if (offset > 0) "Sound starts after the picture transition." else "Sound follows the picture transition.",
            color = EditorColors.secondary, fontSize = 12.sp)
        SecondsSlider("Audio blend", model.displayedAudioBlend, 0.0..max(maximum, model.displayedAudioBlend), "transition.audioBlend") { value ->
            update { it.copy(audioBlend = value, audio = TransitionAudio.crossfade) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("0.1 s", "Shorten audio blend by 0.1 seconds", "transition.audioShorter", Icons.Filled.Remove) {
                update { it.copy(audioBlend = max(0.0, model.displayedAudioBlend - 0.1), audio = TransitionAudio.crossfade) }
            }
            Spacer(Modifier.weight(1f))
            StepButton("Cut", "Cut audio without crossfade", "transition.audioCut") { update { it.copy(audioBlend = 0.0, audio = TransitionAudio.crossfade) } }
            Spacer(Modifier.weight(1f))
            StepButton("0.1 s", "Lengthen audio blend by 0.1 seconds", "transition.audioLonger", Icons.Filled.Add) {
                update { it.copy(audioBlend = min(ClipTransition.maximumDuration, model.displayedAudioBlend + 0.1), audio = TransitionAudio.crossfade) }
            }
        }
        if (!settings.customAudio && (settings.style == TransitionStyle.fadeColor || settings.style == TransitionStyle.still)) {
            Text("By default, sound follows the fade or freeze. Adjust either audio control to crossfade independently of the picture.", color = EditorColors.secondary, fontSize = 12.sp)
        }
        if (settings.style == TransitionStyle.split && !settings.customAudio && settings.audio != TransitionAudio.crossfade) {
            Text("${settings.audio.title} is selected above. Adjust either audio control to use a timed crossfade.", color = EditorColors.secondary, fontSize = 12.sp)
        }
        AudioTimingDiagram(model)
        Text(model.lengthNote, color = EditorColors.secondary, fontSize = 12.sp)
    }
}

/** Swift `audioTimingDiagram`: the picture and sound intervals after fitting, with the picture start marked. */
@Composable
private fun AudioTimingDiagram(model: TransitionWorkspace) {
    val join = model.join
    val lower = join.range.start
    val span = max(TimelineEditing.frame, join.range.endInclusive - lower)
    val audio = if (model.isOpening) join.audioIn else if (model.isClosing) join.audioOut
        else min(join.audioIn.start, join.audioOut.start)..max(join.audioIn.endInclusive, join.audioOut.endInclusive)
    fun signed(value: Double) = (if (value >= 0) "+" else "-") + "%.2f".format(Locale.US, abs(value))
    Column(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {
        contentDescription = "Picture transition and audio timing"; stateDescription = model.lengthNote
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (index in 0..1) {
                    val range = if (index == 0) join.videoRange else audio
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (index == 0) Icons.Outlined.Movie else Icons.Outlined.GraphicEq, null, tint = EditorColors.secondary, modifier = Modifier.width(24.dp).size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Canvas(Modifier.weight(1f).height(7.dp)) {
                            val h = size.height
                            drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                            val x = (size.width * (range.start - lower) / span).toFloat()
                            val w = max(2f, (size.width * (range.endInclusive - range.start) / span).toFloat())
                            drawRoundRect(if (index == 0) orange else EditorColors.cyan, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, h),
                                androidx.compose.ui.geometry.CornerRadius(h / 2))
                        }
                    }
                }
            }
            Canvas(Modifier.matchParentSize()) {
                val left = 32.dp.toPx()
                val x = left + max(0f, size.width - left) * ((join.origin - lower) / span).toFloat()
                drawLine(Color.White.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, size.height), 1f)
            }
        }
        Text("Sound ${signed(audio.start - join.origin)} to ${signed(audio.endInclusive - join.origin)} s · picture starts at 0", color = EditorColors.secondary, fontSize = 11.sp)
    }
}


/** iOS `ColorPicker("Fade color")`: Android has no system picker, so swatches plus RGB sliders stand in. */
@Composable
private fun FadeColorPicker(color: TransitionColor, change: (TransitionColor) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val swatches = listOf(TransitionColor.black, TransitionColor(1.0, 1.0, 1.0), TransitionColor(1.0, 0.25, 0.29), TransitionColor(1.0, 0.62, 0.04),
        TransitionColor(1.0, 0.84, 0.04), TransitionColor(0.2, 0.78, 0.35), TransitionColor(0.04, 0.52, 1.0))
    Column(Modifier.testTag("transition.fadeColor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fade color", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).background(Color(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat()), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape).clickable { custom = !custom }
                .semantics { contentDescription = "Fade color, custom" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (swatch in swatches) {
                val chosen = swatch == color
                Box(Modifier.size(32.dp).background(Color(swatch.red.toFloat(), swatch.green.toFloat(), swatch.blue.toFloat()), CircleShape)
                    .border(if (chosen) 2.dp else 1.dp, if (chosen) orange else Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { change(swatch) }
                    .semantics { contentDescription = if (swatch == TransitionColor.black) "Black" else if (swatch == TransitionColor(1.0, 1.0, 1.0)) "White" else "Color" })
            }
        }
        if (custom) {
            for ((name, value) in listOf("Red" to color.red, "Green" to color.green, "Blue" to color.blue)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = EditorColors.secondary, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                    Slider(value = value.toFloat(), onValueChange = { v ->
                        val c = v.toDouble().coerceIn(0.0, 1.0)
                        change(when (name) { "Red" -> color.copy(red = c); "Green" -> color.copy(green = c); else -> color.copy(blue = c) })
                    }, colors = orangeSlider(), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Swift `TransitionFramingSurface`: the gesture is assigned at touch-down. A
 * divider grab cannot pan a picture, and a picture drag cannot pick up the
 * divider halfway through. Double tap resets one picture.
 */
@Composable
private fun TransitionFramingSurface(settings: ClipTransition, canvas: CGSize, firstSize: CGSize, secondSize: CGSize,
                                     firstEnabled: Boolean, secondEnabled: Boolean, change: (ClipTransition) -> Unit) {
    val current by rememberUpdatedState(settings)
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize().testTag("transition.framing")
        .semantics { contentDescription = "Split screen framing"; stateDescription = String.format(Locale.US, "divider %.3f", settings.divider) }) {
        val bounds = CGSize(constraints.maxWidth.toDouble(), constraints.maxHeight.toDouble())
        fun side(point: Offset, value: ClipTransition): Int =
            if (value.axis == TransitionAxis.horizontal) (if (point.x < bounds.width * value.divider) 0 else 1) else (if (point.y < bounds.height * value.divider) 0 else 1)
        fun enabled(side: Int) = if (side == 0) firstEnabled else secondEnabled
        val gestures = Modifier.pointerInput(canvas, firstSize, secondSize, firstEnabled, secondEnabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val anchor = current
                val horizontal = anchor.axis == TransitionAxis.horizontal
                val distance = if (horizontal) abs(down.position.x - bounds.width * anchor.divider) else abs(down.position.y - bounds.height * anchor.divider)
                var target = if (distance <= 22 * density) -1 else side(down.position, anchor)
                var pinchStart: Double? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        val a = pressed[0].position; val b = pressed[1].position
                        val spread = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
                        if (pinchStart == null) { pinchStart = spread; if (target == -1) target = side((a + b) / 2f, anchor) }
                        if (target >= 0 && enabled(target)) {
                            val framing = if (target == 0) anchor.first else anchor.second
                            val scaled = framing.copy(zoom = min(4.0, max(1.0, framing.zoom * spread / max(1.0, pinchStart))))
                            change(if (target == 0) anchor.copy(first = scaled) else anchor.copy(second = scaled))
                        }
                    } else if (pinchStart == null) {
                        val delta = pressed[0].position - down.position
                        if (target == -1) {
                            change(anchor.copy(divider = min(0.85, max(0.15, anchor.divider + if (horizontal) delta.x / max(1.0, bounds.width) else delta.y / max(1.0, bounds.height)))))
                        } else if (enabled(target)) {
                            var framing = if (target == 0) anchor.first else anchor.second
                            val (first, second) = TransitionComposite.splitRects(anchor, canvas)
                            val viewport = if (target == 0) first else second
                            val sourceSize = if (target == 0) firstSize else secondSize
                            val crop = framing.crop(sourceSize, viewport.size)
                            val screenScale = bounds.width / max(1.0, canvas.width)
                            val scale = viewport.width / max(1.0, crop.width) * screenScale
                            framing = framing.copy(x = min(1.0, max(0.0, framing.x - delta.x / max(1.0, scale * sourceSize.width))),
                                y = min(1.0, max(0.0, framing.y - delta.y / max(1.0, scale * sourceSize.height))))
                            val clamped = framing.crop(sourceSize, viewport.size)
                            framing = framing.copy(x = clamped.midX / sourceSize.width, y = clamped.midY / sourceSize.height)
                            change(if (target == 0) anchor.copy(first = framing) else anchor.copy(second = framing))
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        }.pointerInput(firstEnabled, secondEnabled) {
            detectTapGestures(onDoubleTap = { point ->
                val value = current
                val target = side(point, value)
                if (enabled(target)) change(if (target == 0) value.copy(first = TransitionFraming()) else value.copy(second = TransitionFraming()))
            })
        }
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val horizontal = settings.axis == TransitionAxis.horizontal
            val center = Offset(if (horizontal) size.width * settings.divider.toFloat() else size.width / 2, if (horizontal) size.height / 2 else size.height * settings.divider.toFloat())
            drawCircle(Color.Black.copy(alpha = 0.7f), 18 * density, center)
            // Two arrowheads, like `arrow.left.and.right` / `arrow.up.and.down`.
            for (sign in listOf(-1f, 1f)) {
                val tip = if (horizontal) Offset(center.x + sign * 10 * density, center.y) else Offset(center.x, center.y + sign * 10 * density)
                val base1 = if (horizontal) Offset(center.x + sign * 4 * density, center.y - 5 * density) else Offset(center.x - 5 * density, center.y + sign * 4 * density)
                val base2 = if (horizontal) Offset(center.x + sign * 4 * density, center.y + 5 * density) else Offset(center.x + 5 * density, center.y + sign * 4 * density)
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(tip.x, tip.y); lineTo(base1.x, base1.y); lineTo(base2.x, base2.y); close() }, Color.White)
            }
            if (horizontal) drawLine(Color.White, Offset(center.x - 5 * density, center.y), Offset(center.x + 5 * density, center.y), 2 * density)
            else drawLine(Color.White, Offset(center.x, center.y - 5 * density), Offset(center.x, center.y + 5 * density), 2 * density)
        }
    }
}

// MARK: - Join chip and bubble (Swift `effectChips` / `EffectRemovalBubble` in EditorView.swift)

/**
 * The control shown when the playhead is on a join: "+ Transition" to add
 * one, or the existing transition as a bubble (tap to edit, long press for
 * the trash and cancel buttons).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransitionJoinChip(join: TransitionJoin, armed: Boolean, edit: () -> Unit, arm: () -> Unit, cancelRemoval: () -> Unit, remove: () -> Unit) {
    val transition = join.transition
    if (transition == null) {
        Row(Modifier.heightIn(min = 48.dp).background(orange.copy(alpha = 0.12f), RoundedCornerShape(50)).clickable(onClick = edit)
            .padding(horizontal = 12.dp).testTag("editor.transition").semantics { contentDescription = "Add transition" },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, null, tint = orange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Transition", color = orange, fontSize = 15.sp)
        }
        return
    }
    val tint = orange
    Row(Modifier.heightIn(min = 48.dp).background(tint.copy(alpha = if (armed) 0.22f else 0.12f), RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.heightIn(min = 48.dp).widthIn(max = 180.dp).combinedClickable(onClick = { if (!armed) edit() }, onLongClick = { if (!armed) arm() })
            .padding(horizontal = 12.dp).testTag("editor.transition")
            .semantics { contentDescription = "Edit " + transition.style.title }, verticalAlignment = Alignment.CenterVertically) {
            Icon(transition.style.icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(transition.style.title, color = tint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (armed) {
            Box(Modifier.size(44.dp, 48.dp).clickable(onClick = remove).testTag("effect.confirmRemove").semantics { contentDescription = "Remove " + transition.style.title },
                contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Delete, null, tint = EditorColors.red) }
            Box(Modifier.size(44.dp, 48.dp).clickable(onClick = cancelRemoval).testTag("effect.cancelRemove").semantics { contentDescription = "Cancel removal" },
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, null, tint = Color.White) }
        }
    }
}
