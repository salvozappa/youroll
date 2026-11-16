// Port of YouRec/VideoEffects/VideoEffectsEditor.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The editor's Video effects screen: Cancel · "Video effects" over the scope ·
// Apply, the preview with the Effects/Original comparison and its playback
// bar, and the effect pages (applied list, library sections, inspector). The
// preview is an EditorController over the candidate project, bounded to the
// scope (`videoPreviewRange`) like the iOS AVPlayer; effect-look changes only
// redraw the paused frame (EditorController's fast path), time effects rebuild.
//
// Simplified or left out (TODO.md): the Kaleidoscope guide gestures and the
// RGB Offset pads (sliders instead: `KaleidoscopeGuide.swift`,
// `RGBOffsetPads.swift`), the colour pickers (eyedropper sampling; Color
// Isolation offers its hue slider), background media import and positioning,
// the slow-effect sample window and refinement (`EffectPreviewWindow`, Night
// fix), accessibility-size layouts, swipe-to-delete in the applied list (a
// trash button), and the effects that need segmentation, depth or offline
// analysis, which are listed but "Not yet available".
package com.salvozappa.youroll

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Swift `InspectorHelp`. */
data class InspectorHelp(val title: String, val guide: String, val history: String?) {
    constructor(effect: VideoEffectID) : this(effect.descriptor.displayName, effect.usageHelp, effect.historicalHelp)
}

/** Swift `VideoEffectPage`. */
sealed class VideoEffectPage {
    data object applied : VideoEffectPage()
    data object library : VideoEffectPage()
    data class section(val section: VideoEffectLibrarySection) : VideoEffectPage()
    data object inspector : VideoEffectPage()
}

val VideoEffectLibrarySection.tint: Color
    get() = when (this) {
        VideoEffectLibrarySection.colorStyle -> EditorColors.orange; VideoEffectLibrarySection.cameraFrame -> EditorColors.cyan
        VideoEffectLibrarySection.time -> EditorColors.purple; VideoEffectLibrarySection.background -> EditorColors.teal
    }
val VideoEffectLibrarySection.icon: ImageVector
    get() = when (this) {
        VideoEffectLibrarySection.colorStyle -> Icons.Outlined.Palette; VideoEffectLibrarySection.cameraFrame -> Icons.Outlined.CenterFocusStrong
        VideoEffectLibrarySection.time -> Icons.Outlined.Schedule; VideoEffectLibrarySection.background -> Icons.Outlined.AccountBox
    }

/** Swift `VideoEffectsWorkspace`. */
class VideoEffectsWorkspace(val project: Project, val folder: File, val request: ToolRequest, private val initialPosition: Double) {
    var state by mutableStateOf(EditState())
    var ids: Set<UUID> = emptySet()
    var selected by mutableStateOf(request.effectID)
    var animate by mutableStateOf(false)
    var bypass by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var correctionEndpoint by mutableStateOf<EffectEndpoint?>(null)
    private var correctionLast = EffectEndpoint.a
    var sampleMessage by mutableStateOf<String?>(null)
    var samplingPalette by mutableStateOf(false)
    var preview: EditorController? = null
    private val scopeLazy = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val scope by scopeLazy
    private var rebuildJob: Job? = null

    init {
        val (scoped, scopedIDs) = request.videoScope(project.edit)
        state = scoped; ids = scopedIDs
    }

    val duration: Double get() = state.duration
    val previewRange: ClosedFloatingPointRange<Double> get() = request.videoPreviewRange(state, ids)
    val position: Double get() = preview?.playhead ?: initialPosition
    val placement: TimelinePlacement? get() = state.layout.placement(min(position, max(0.0, duration - 0.001)))
    val sourceTime: Double get() = placement?.let { it.clip.sourceTime(position - it.start) } ?: 0.0
    val active: List<VideoEffectSettings>
        get() { val time = sourceTime; return VideoEffectSettings.ordered(placement?.clip?.videoEffects.orEmpty()).filter { time >= it.start && time < it.end } }
    val current: VideoEffectSettings?
        get() = active.firstOrNull { it.id == selected } ?: state.clips.flatMap { it.videoEffects.orEmpty() }.firstOrNull { it.id == selected }
    val correctionRange: ClosedFloatingPointRange<Double> get() = request.videoPreviewRange(state, ids)
    val correctionDuration: Double get() = correctionRange.endInclusive - correctionRange.start

    private fun updateClips(transform: (TimelineClip) -> TimelineClip) {
        val next = state.copy()
        next.clips = next.clips.map(transform)
        state = next
    }

    /** The edit the preview plays: the Original comparison disables every look but keeps the time effects' clock. */
    private fun previewState(): EditState {
        if (!bypass) return state
        val next = state.copy()
        next.clips = next.clips.map { clip -> clip.copy(videoEffects = clip.videoEffects?.map { if (it.kind.descriptor.family == EffectFamily.time) it else it.copy(enabled = false) }) }
        return next
    }

    fun rebuild(delay: Boolean = true) {
        val player = preview ?: return
        rebuildJob?.cancel()
        error = null
        rebuildJob = scope.launch {
            if (delay) delay(180)
            val candidate = project.copy()
            candidate.history = listOf(previewState()); candidate.historyCursor = 0
            player.previewRange = previewRange
            player.seekAfterEdit(min(max(previewRange.start, position), max(previewRange.start, previewRange.endInclusive - TimelineEditing.frame)), candidate.edit.duration)
            player.load(candidate, folder, includeTimelineDetails = false)
        }
    }

    fun seek(value: Double) { preview?.seekPaused(min(max(previewRange.start, value), max(previewRange.start, previewRange.endInclusive - TimelineEditing.frame))) }

    fun togglePlay() {
        val player = preview ?: return
        if (player.playing) { player.pause(); returnToCorrection() }
        else {
            if (correctionEndpoint != null || position >= previewRange.endInclusive - 0.05 || position < previewRange.start) seek(previewRange.start)
            player.togglePlay()
        }
    }

    fun add(kind: VideoEffectID) {
        if (kind == VideoEffectID.freezeFrame) {
            val (frozen, effectID) = TimelineEditing.freezing(state, position)
            if (effectID == null) { error = "Move the playhead outside a transition to freeze one frame."; return }
            state = frozen; selected = effectID
            ids = ids + frozen.clips.filter { clip -> clip.videoEffects?.any { it.id == effectID } == true }.map { it.id }
            rebuild(); return
        }
        if (kind == VideoEffectID.negativeStrobe) preview?.pause()
        val starts = if (kind == VideoEffectID.negativeStrobe) state.layout.placements.associate { it.clip.id to it.start } else emptyMap()
        val rangeStart = starts.filterKeys { it in ids }.values.minOrNull() ?: 0.0
        val id = newUUID()
        updateClips { clip ->
            if (clip.id !in ids) return@updateClips clip
            var effect = VideoEffectSettings(kind, clip.start, clip.end).copy(id = id)
            // YouRoll has no face tracking: a new Crash Zoom targets its manual point.
            if (kind == VideoEffectID.crashZoom) effect = effect.setting("trackFace", EffectValue.toggle(false))
            if (kind == VideoEffectID.negativeStrobe) {
                effect = effect.copy(phaseOffset = max(0.0, (starts[clip.id] ?: rangeStart) - rangeStart))
                val spans = clip.effectPlaybackSpans
                val first = spans.firstOrNull()
                if (clip.frozenFrame == null && (spans.size > 1 || first?.rate != 1.0) && first != null) {
                    var elapsed = 0.0
                    val points = mutableListOf(StrobeTimePoint(first.start, 0.0))
                    for (span in spans) {
                        if (span.start > points.last().source) points += StrobeTimePoint(span.start, elapsed)
                        elapsed += span.duration
                        points += StrobeTimePoint(span.end, elapsed)
                    }
                    effect = effect.copy(strobeTimeMap = points)
                }
            }
            clip.copy(videoEffects = clip.videoEffects.orEmpty() + effect)
        }
        selected = id; rebuild()
    }

    fun mutate(action: (VideoEffectSettings) -> VideoEffectSettings) {
        val id = selected ?: return
        val time = sourceTime
        updateClips { clip ->
            if (clip.id !in ids || clip.videoEffects.orEmpty().none { it.id == id }) return@updateClips clip
            var next = clip.copy(videoEffects = clip.videoEffects!!.map { effect ->
                if (effect.id != id) effect else {
                    val changed = action(effect).sanitizingBackgroundMedia(time)
                    val limit = clip.videoEffects.filter { it.kind == VideoEffectID.crashZoom && it.start > changed.start }.minOfOrNull { it.start } ?: clip.end
                    changed.retimingCapturedCrash(effect, min(clip.end, limit))
                }
            })
            next.videoEffects!!.firstOrNull { it.id == id && it.kind == VideoEffectID.freezeFrame }?.let { freeze ->
                next.frozenFrame?.let { next = next.copy(frozenFrame = it.copy(duration = freeze.number("hold", next.start))) }
            }
            next
        }
        rebuild()
    }

    fun set(parameter: EffectParameter, value: EffectValue) {
        if (!parameter.kind.accepts(value)) return
        val current = current
        if (current?.kind == VideoEffectID.colorCorrection && current.motion != null && value is EffectValue.number) {
            val e = correctionEndpoint ?: correctionLast
            correctionEndpoint = e
            returnToCorrection()
            mutate { effect -> val motion = effect.motion ?: return@mutate effect
                effect.copy(motion = motion.setting(e, motion[e].copy(values = motion[e].values + (parameter.id to value.value)))) }
            return
        }
        if (parameter.id == "lockPalette" && value == EffectValue.toggle(true) && !animate) { samplePalette(lockOnly = true); return }
        if (parameter.id == "view") { mutate { it.updatingParameter("view") { p -> p.copy(value = value, keyframes = emptyList()) } }; return }
        val time = sourceTime
        val clipID = placement?.clip?.id
        val resampleLock = parameter.id == "colors" && current?.toggle("lockPalette", time) == true && !animate
        if (animate && parameter.animatable) {
            val id = selected ?: return
            updateClips { clip ->
                if (clip.id != clipID) return@updateClips clip
                clip.copy(videoEffects = clip.videoEffects?.map { effect ->
                    if (effect.id != id) effect else {
                        effect.updatingParameter(parameter.id) { p ->
                            var keyed = p
                            if (keyed.keyframes.isEmpty() && time > clip.start + 0.001) keyed = keyed.settingKeyframe(clip.start, p.value)
                            keyed.settingKeyframe(time, value)
                        }.sanitizingBackgroundMedia(time).retimingCapturedCrash(effect, clip.end)
                    }
                })
            }
            rebuild()
        } else {
            // Changing a control with existing animation edits the key at the playhead, never silently erases its animation.
            mutate { effect -> effect.updatingParameter(parameter.id) { p -> val set = p.copy(value = value); if (p.keyframes.isNotEmpty()) set.settingKeyframe(time, value) else set } }
        }
        if (resampleLock) samplePalette(lockOnly = true)
    }

    fun keyframe(parameter: EffectParameter) {
        val id = selected ?: return
        val clipID = placement?.clip?.id ?: return
        val time = sourceTime
        updateClips { clip ->
            if (clip.id != clipID) return@updateClips clip
            clip.copy(videoEffects = clip.videoEffects?.map { effect ->
                if (effect.id != id) effect else effect.updatingParameter(parameter.id) { p ->
                    val index = parameter.keyframes.indexOfFirst { abs(it.time - time) < 1.0 / 60 }
                    if (index >= 0) p.copy(keyframes = p.keyframes.filterIndexed { i, _ -> i != index }) else p.settingKeyframe(time, parameter.value(time))
                }.retimingCapturedCrash(effect, clip.end)
            })
        }
        rebuild()
    }

    fun remove() {
        val id = selected
        val next = state.copy()
        next.clips = next.clips.filterNot { clip -> clip.id in ids && clip.frozenFrame != null && clip.videoEffects?.any { it.id == id && it.kind == VideoEffectID.freezeFrame } == true }
            .map { clip -> if (clip.id !in ids) clip else clip.copy(videoEffects = clip.videoEffects?.filter { it.id != id }?.ifEmpty { null }) }
        state = next
        selected = active.firstOrNull()?.id
        rebuild()
    }

    // ---- Color Correction A/B animation

    fun selectCorrection(e: EffectEndpoint) {
        val id = selected ?: return
        if (current?.kind != VideoEffectID.colorCorrection) return
        preview?.pause(); animate = false
        val range = correctionRange
        val length = correctionDuration
        val next = state.copy()
        val placements = next.layout.placements.filter { it.clip.id in ids }
        next.clips = next.clips.map { clip ->
            val placed = placements.firstOrNull { it.clip.id == clip.id } ?: return@map clip
            clip.copy(videoEffects = clip.videoEffects?.map { effect ->
                if (effect.id != id || effect.motion != null) effect else {
                    val motion = EffectAnimation.of(effect.correctionPose(effect.start), length).copy(b = effect.correctionPose(min(effect.end, clip.end)))
                    effect.copy(motion = motion.anchored(clip, placed.start - range.start), parameters = effect.parameters.map { it.copy(keyframes = emptyList()) })
                }
            })
        }
        state = next
        correctionEndpoint = e; correctionLast = e; returnToCorrection(); rebuild(delay = false)
    }

    fun returnToCorrection() {
        val e = correctionEndpoint ?: return
        val motion = current?.motion ?: return
        seek(correctionRange.start + motion.timing.endpoint(e, correctionDuration, motion.frame))
    }

    fun removeCorrectionAnimation() {
        val endpoint = correctionEndpoint ?: correctionLast
        correctionEndpoint = null
        mutate { effect ->
            val motion = effect.motion ?: return@mutate effect
            motion[endpoint].values.entries.fold(effect.copy(motion = null)) { e, (key, n) -> e.setting(key, EffectValue.number(n)) }
        }
    }

    fun updateCorrectionTiming(timing: EffectTiming) {
        val id = selected ?: return
        val motion = current?.motion ?: return
        if (motion.timing == timing) return
        preview?.pause()
        val start = correctionRange.start
        val length = correctionRange.endInclusive - start
        val next = state.copy()
        for (i in next.timeline.video.indices) {
            val item = next.timeline.video[i]
            if (item.id !in ids) continue
            val effects = item.content.videoEffects ?: continue
            val index = effects.indexOfFirst { it.id == id }
            val old = effects.getOrNull(index)?.motion ?: continue
            val updated = old.copy(timing = timing, duration = length).anchored(item.content, item.start.seconds - start)
            next.timeline.video = next.timeline.video.updated(i) { it.copy(content = it.content.copy(videoEffects = effects.updated(index) { e -> e.copy(motion = updated) })) }
        }
        state = next
        rebuild()
        returnToCorrection()
    }

    fun resetCorrection() {
        val pose = VideoEffectSettings(VideoEffectID.colorCorrection).correctionPose(0.0)
        val e = correctionEndpoint ?: correctionLast
        mutate { effect -> effect.motion?.let { effect.copy(motion = it.setting(e, pose)) } ?: effect.copy(parameters = VideoEffectSettings(VideoEffectID.colorCorrection).parameters) }
    }

    // ---- Quantization palette sampling (Swift `samplePalette`): the palette the renderer analysed for the paused frame.

    fun samplePalette(lockOnly: Boolean = false) {
        val player = preview ?: return
        val effect = current ?: return
        if (samplingPalette) return
        samplingPalette = true
        val main = Handler(Looper.getMainLooper())
        val time = sourceTime
        val count = effect.number("colors", time).toInt()
        player.renderedEffects = { renderer, _ ->
            val palette = renderer.analyzedPalettes[effect.id]?.takeIf { it.colors.size == count }
            if (palette != null) {
                player.renderedEffects = null
                main.post {
                    samplingPalette = false
                    mutate { e ->
                        if (lockOnly) e.copy(lockedPalette = palette).setting("lockPalette", EffectValue.toggle(true)).updatingParameter("lockPalette") { p ->
                            if (p.keyframes.isNotEmpty()) p.settingKeyframe(time, EffectValue.toggle(true)) else p }
                        else e.copy(palette = palette.colors).setting("palette", EffectValue.choice("Saved")).updatingParameter("palette") { p ->
                            if (p.keyframes.isNotEmpty()) p.settingKeyframe(time, EffectValue.choice("Saved")) else p }
                    }
                    sampleMessage = if (lockOnly) "Palette locked to this frame · $count colours" else "Palette saved in this project"
                }
            }
        }
        player.pause(); player.redraw()
        scope.launch { delay(4000); if (samplingPalette) { samplingPalette = false; player.renderedEffects = null; error = "The palette could not be sampled. Pause on the frame and try again." } }
    }

    /** The edit to apply (Swift: every effect's recording result). */
    fun result(): EditState {
        val next = state.copy()
        next.clips = next.clips.map { clip -> clip.copy(videoEffects = clip.videoEffects?.map { it.recordingResult }) }
        return next
    }

    fun stop() { rebuildJob?.cancel(); preview?.pause() }
    fun release() { if (scopeLazy.isInitialized()) scope.cancel() }
}

/** Swift `VideoEffectsEditor`. */
@Composable
fun VideoEffectsEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { VideoEffectInputs.install(context); VideoEffectsWorkspace(project, folder, request, position) }
    val preview = remember { EditorController(context) }
    var page by remember { mutableStateOf<VideoEffectPage>(VideoEffectPage.library) }
    var confirmRemove by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<InspectorHelp?>(null) }
    DisposableEffect(model) {
        model.preview = preview
        preview.seekAfterEdit(position, model.duration)
        page = if (model.active.isEmpty()) VideoEffectPage.library else if (request.effectID != null) VideoEffectPage.inspector else VideoEffectPage.applied
        model.rebuild(delay = false)
        onDispose { model.stop(); model.release(); preview.release() }
    }
    // Swift's periodic observer: stop at the end of the scope and return to an edited endpoint.
    LaunchedEffect(model) {
        while (true) {
            if (preview.playing && preview.playhead >= model.previewRange.endInclusive - 0.02) { preview.pause(); model.returnToCorrection() }
            delay(66)
        }
    }
    BackHandler(onBack = { model.stop(); cancel() })
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0.035f, 0.035f, 0.035f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth > maxHeight * 1.25f
        val height = maxHeight
        val width = maxWidth
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", Modifier.widthIn(min = 60.dp), action = { model.stop(); cancel() })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Video effects", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(request.scopeTitle, color = EditorColors.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Apply", Modifier.widthIn(min = 60.dp), enabled = model.error == null, bold = true) { model.stop(); apply(model.result()) }
            }
            @Composable fun previewPane(modifier: Modifier) {
                Column(modifier) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles, layers = preview.layers, captions = preview.subtitles,
                            inspectionZoom = false, onPlaybackTap = { model.togglePlay() })
                        Row(Modifier.align(Alignment.TopStart).padding(8.dp).heightIn(min = 44.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                            .clickable { model.bypass = !model.bypass; model.rebuild(delay = false) }.padding(horizontal = 12.dp)
                            .semantics { contentDescription = if (model.bypass) "Original" else "Effects" }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (model.bypass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (model.bypass) "Original" else "Effects", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (preview.loading) {
                            Column(Modifier.align(Alignment.Center).background(Color(0.2f, 0.2f, 0.22f, 0.85f), RoundedCornerShape(14.dp)).padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                                Text(preview.loadingLabel + "…", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    PlaybackBar(model, preview)
                }
            }
            @Composable fun controls(modifier: Modifier) {
                Column(modifier.background(Color(0.07f, 0.07f, 0.07f))) {
                    NavigationBar(page, model.active.size, back = {
                        page = when (page) { is VideoEffectPage.section -> VideoEffectPage.library; else -> VideoEffectPage.applied }
                    }, add = { page = VideoEffectPage.library })
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        (model.error ?: preview.error)?.let { message ->
                            Text(message, color = EditorColors.orange, fontSize = 15.sp)
                            Text("Retry", color = EditorColors.accent, fontSize = 17.sp, modifier = Modifier.heightIn(min = 44.dp)
                                .clickable { model.error = null; preview.error = null; model.rebuild(delay = false) })
                        }
                        when (val current = page) {
                            VideoEffectPage.applied -> AppliedEffects(model, edit = { model.selected = it.id; model.animate = false; page = VideoEffectPage.inspector },
                                add = { page = VideoEffectPage.library },
                                remove = { model.selected = it.id; model.remove(); page = if (model.active.isEmpty()) VideoEffectPage.library else VideoEffectPage.applied })
                            VideoEffectPage.library -> VideoEffectLibrary(null, choose = { page = VideoEffectPage.section(it) }) { kind -> model.add(kind); page = VideoEffectPage.inspector }
                            is VideoEffectPage.section -> VideoEffectLibrary(current.section, choose = {}) { kind -> model.add(kind); page = VideoEffectPage.inspector }
                            VideoEffectPage.inspector -> model.current?.let { Settings(model, it, help = { topic -> help = topic }, remove = { confirmRemove = true }) }
                                ?: AppliedEffects(model, edit = { model.selected = it.id; page = VideoEffectPage.inspector }, add = { page = VideoEffectPage.library },
                                    remove = { model.selected = it.id; model.remove() })
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                previewPane(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(minOf(340.dp, width * 0.36f)).fillMaxHeight())
            } else {
                previewPane(Modifier.fillMaxWidth().height(maxOf(180.dp, height * 0.40f)))
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove this effect?") },
        confirmButton = { TextButton({ confirmRemove = false; model.remove(); page = if (model.active.isEmpty()) VideoEffectPage.library else VideoEffectPage.applied }) {
            Text("Remove effect", color = EditorColors.red) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } })
    help?.let { topic ->
        AlertDialog(onDismissRequest = { help = null }, title = { Text(topic.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(topic.guide)
                topic.history?.let { Text("Film technique", fontWeight = FontWeight.SemiBold); Text(it, color = EditorColors.secondary) }
            }
        }, confirmButton = { TextButton({ help = null }) { Text("Done") } })
    }
}

@Composable
private fun PlaybackBar(model: VideoEffectsWorkspace, preview: EditorController) {
    val range = model.previewRange
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).clickable { model.togglePlay() }.semantics { contentDescription = if (preview.playing) "Pause preview" else "Play preview" },
            contentAlignment = Alignment.Center) {
            Icon(if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
        }
        val upper = max(range.start + 0.001, range.endInclusive - 1.0 / 30)
        Slider(min(upper, max(range.start, preview.playhead)).toFloat(), { model.seek(it.toDouble()) }, Modifier.weight(1f).semantics { contentDescription = "Preview position" },
            valueRange = range.start.toFloat()..upper.toFloat(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent))
        Text(editorTime(preview.playhead - range.start) + " / " + editorTime(range.endInclusive - range.start), color = Color.White, fontSize = 12.sp)
    }
}

/** Swift `VideoEffectNavigationBar`. */
@Composable
private fun NavigationBar(page: VideoEffectPage, count: Int, back: () -> Unit, add: () -> Unit) {
    val title = when (page) {
        VideoEffectPage.applied -> "Applied effects · $count"; VideoEffectPage.library -> "Add effects"
        is VideoEffectPage.section -> page.section.title; VideoEffectPage.inspector -> "Applied · $count"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page == VideoEffectPage.inspector) {
            Row(Modifier.heightIn(min = 44.dp).clickable(onClick = back).semantics { contentDescription = "Back to applied effects" }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = EditorColors.accent)
                Text(title, color = EditorColors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            if (page != VideoEffectPage.applied && (page != VideoEffectPage.library || count > 0)) {
                Box(Modifier.size(44.dp).clickable(onClick = back).semantics { contentDescription = if (page == VideoEffectPage.library) "Back to applied effects" else "Back to effect sections" },
                    contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = EditorColors.accent) }
            }
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        if (page == VideoEffectPage.applied || page == VideoEffectPage.inspector) {
            Row(Modifier.heightIn(min = 44.dp).clickable(onClick = add).padding(horizontal = 8.dp).semantics { contentDescription = "Add effect" }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = EditorColors.accent, modifier = Modifier.size(18.dp))
                Text("Add", color = EditorColors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Swift `VideoEffectLibrary` (editor): the four sections, then a section's effects. Effects YouRoll cannot render yet are listed but disabled. */
@Composable
private fun VideoEffectLibrary(section: VideoEffectLibrarySection?, choose: (VideoEffectLibrarySection) -> Unit, add: (VideoEffectID) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (section != null) {
            Text(section.summary, color = EditorColors.secondary, fontSize = 15.sp)
            for (effect in section.effects(live = false)) {
                val available = VideoEffectSupport.available(effect.id)
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).alpha(if (available) 1f else 0.45f).clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.045f)).clickable(enabled = available) { add(effect.id) }.padding(12.dp)
                    .semantics { contentDescription = effect.displayName + if (available) "" else ", not yet available" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(section.icon, null, tint = section.tint, modifier = Modifier.width(28.dp).size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(effect.displayName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (available) effect.id.summary else "Not yet available", color = EditorColors.secondary, fontSize = 12.sp)
                    }
                    if (available) Icon(Icons.Outlined.AddCircleOutline, null, tint = section.tint)
                }
            }
        } else {
            for (pair in VideoEffectLibrarySection.entries.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (group in pair) {
                        Column(Modifier.weight(1f).heightIn(min = 92.dp).clip(RoundedCornerShape(12.dp)).background(group.tint.copy(alpha = 0.08f))
                            .clickable { choose(group) }.padding(12.dp).semantics { contentDescription = group.title }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row { Icon(group.icon, null, tint = group.tint); Spacer(Modifier.weight(1f)); Icon(Icons.Filled.ChevronRight, null, tint = EditorColors.secondary, modifier = Modifier.size(16.dp)) }
                            Text(group.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${group.effects(live = false).size} effects", color = EditorColors.secondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Swift `appliedEffects` + `VideoEffectAppliedList`. */
@Composable
private fun AppliedEffects(model: VideoEffectsWorkspace, edit: (VideoEffectSettings) -> Unit, add: () -> Unit, remove: (VideoEffectSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val active = model.active
        if (active.isEmpty()) {
            Text("No effects at the playhead.", color = EditorColors.secondary, fontSize = 15.sp)
            Row(Modifier.heightIn(min = 44.dp).clickable(onClick = add), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = EditorColors.accent); Text("Add effect", color = EditorColors.accent, fontSize = 17.sp)
            }
        } else {
            Text("At the playhead · tap an effect to adjust it.", color = EditorColors.secondary, fontSize = 12.sp)
            for (effect in active) {
                val section = effect.kind.librarySection
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.045f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable { edit(effect) }.semantics { contentDescription = effect.kind.descriptor.displayName },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(section.icon, null, tint = section.tint, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(effect.kind.descriptor.displayName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (effect.kind == VideoEffectID.freezeFrame) "Hold frame · tap to edit" else if (effect.enabled) "On · tap to edit" else "Off · tap to edit",
                                color = EditorColors.secondary, fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = EditorColors.secondary, modifier = Modifier.size(16.dp))
                    }
                    if (effect.kind != VideoEffectID.freezeFrame) {
                        Switch(effect.enabled, { model.selected = effect.id; model.mutate { it.copy(enabled = !it.enabled) } },
                            Modifier.semantics { contentDescription = "Enable " + effect.kind.descriptor.displayName },
                            colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green))
                    }
                    Box(Modifier.size(44.dp).clickable { remove(effect) }.semantics { contentDescription = "Delete " + effect.kind.descriptor.displayName },
                        contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Delete, null, tint = EditorColors.red, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

/** Swift `settings(_:)`: the inspector of one effect. */
@Composable
private fun Settings(model: VideoEffectsWorkspace, effect: VideoEffectSettings, help: (InspectorHelp) -> Unit, remove: () -> Unit) {
    val tint = effect.kind.librarySection.tint
    val time = model.sourceTime
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(effect.kind.descriptor.displayName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(44.dp).clickable { help(InspectorHelp(effect.kind)) }.semantics { contentDescription = "About " + effect.kind.descriptor.displayName },
                contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = EditorColors.secondary) }
            Spacer(Modifier.weight(1f))
            if (effect.kind == VideoEffectID.colorCorrection) correctionControls(model, effect).EndpointMenu()
            else {
                Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (model.animate) tint.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.07f))
                    .clickable { model.animate = !model.animate }.padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Animate, " + if (model.animate) "On" else "Off" }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (model.animate) Icons.Filled.Diamond else Icons.Outlined.Diamond, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Animate", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (effect.kind != VideoEffectID.freezeFrame) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (effect.enabled) tint else Color.White.copy(alpha = 0.07f))
                    .clickable { model.mutate { it.copy(enabled = !it.enabled) } }.semantics { contentDescription = if (effect.enabled) "Disable effect" else "Enable effect" },
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.PowerSettingsNew, null, tint = if (effect.enabled) Color.Black else EditorColors.secondary) }
            }
        }
        if (model.animate) Text("Animating · changes add keyframes at ${editorTime(model.position)}", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.12f)).padding(10.dp))
        VideoEffectSupport.unsupported(effect)?.let { Text(it, color = EditorColors.orange, fontSize = 12.sp) }
        if (effect.kind == VideoEffectID.colorCorrection) {
            if (effect.motion != null) correctionControls(model, effect).Body()
            ColorCorrectionPanel(model, effect, includesBackground = model.active.any { it.enabled && it.kind.descriptor.family == EffectFamily.key })
        } else {
            for (section in VideoEffectParameterSection.sections(effect, time)) {
                if (section != VideoEffectParameterSection.general) Text(section.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                for (parameter in effect.parameters.filter { VideoEffectParameterVisibility.visible(it, effect, time, hasDepth = true) && VideoEffectParameterSection.section(it, effect) == section }) {
                    ParameterControl(model, effect, parameter)
                }
            }
        }
        if (effect.kind == VideoEffectID.freezeFrame) Text("Silence during hold pauses dialogue until the video resumes. Continue plays it during the hold too, so some dialogue may repeat. Fade out lowers it through the hold.",
            color = EditorColors.secondary, fontSize = 12.sp)
        if (effect.kind == VideoEffectID.crashZoom) Text("Follow face is not yet available: the zoom pushes toward Target X and Y.", color = EditorColors.secondary, fontSize = 12.sp)
        if (effect.kind == VideoEffectID.quantization) {
            if (effect.toggle("lockPalette", time)) ActionRow(Icons.Filled.Refresh, "Re-sample this frame", tint, enabled = !model.samplingPalette) { model.samplePalette(lockOnly = true) }
            if (!effect.toggle("monochrome", time)) {
                ActionRow(Icons.Outlined.Palette, if (model.samplingPalette) "Sampling frame…" else "Save palette from this frame", tint, enabled = !model.samplingPalette) { model.samplePalette() }
                effect.palette?.let { colors ->
                    Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp))) {
                        for (color in colors) Box(Modifier.weight(1f).fillMaxHeight().background(Color(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())))
                    }
                }
            }
            model.sampleMessage?.let { Text(it, color = EditorColors.secondary, fontSize = 12.sp) }
        }
        if (effect.kind == VideoEffectID.widescreen) Text("No usable depth in these clips. Oval bokeh requires an original Cinematic video with depth.", color = EditorColors.secondary, fontSize = 12.sp)
        ActionRow(Icons.Outlined.Delete, if (effect.kind == VideoEffectID.freezeFrame) "Remove hold" else "Remove effect", EditorColors.red, action = remove)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, tint: Color, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = action).semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)); Text(title, color = tint, fontSize = 17.sp)
    }
}

private fun correctionControls(model: VideoEffectsWorkspace, effect: VideoEffectSettings) = EffectAnimationControls(
    effect.motion, model.correctionEndpoint, model.correctionDuration, select = { model.selectCorrection(it) }, remove = { model.removeCorrectionAnimation() },
    done = { model.correctionEndpoint = null }, update = { model.updateCorrectionTiming(it) }, showsEndpointMenu = false)

private fun numberLabel(effect: VideoEffectSettings, number: Double, parameter: EffectParameter): String {
    if (effect.kind == VideoEffectID.kaleidoscope && parameter.id != "divisions")
        return if (parameter.id == "splitAngle" || parameter.id == "sourceAngle") "${swiftRound(number).toInt()}°" else "${swiftRound(number * 100).toInt()}%"
    if (effect.kind == VideoEffectID.nightFix) return if (parameter.id == "brightness") String.format(java.util.Locale.ROOT, "+%.1f stops", number) else "${swiftRound(number * 100).toInt()}%"
    if (parameter.id == "intensity") return "${(number * 100).toInt()}%"
    val suffix = if (parameter.id == "range" || parameter.id == "angle") "°" else ""
    return rateText(number) + suffix
}

private fun choiceTitle(effect: VideoEffectSettings, value: String, parameter: EffectParameter): String =
    if (effect.kind == VideoEffectID.freezeFrame && parameter.id == "audio" && value == "Cut") "Silence during hold" else value

/** Swift `parameterControl(_:)` for the parameter kinds. */
@Composable
private fun ParameterControl(model: VideoEffectsWorkspace, effect: VideoEffectSettings, parameter: EffectParameter) {
    val time = model.sourceTime
    val value = parameter.value(time)
    val disabled = effect.kind == VideoEffectID.crashZoom && parameter.id == "trackFace"
    Column(Modifier.alpha(if (disabled) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(parameter.title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (value is EffectValue.number && parameter.kind is EffectParameterKind.slider) Text(numberLabel(effect, value.value, parameter), color = EditorColors.secondary, fontSize = 15.sp)
            if (value is EffectValue.color) {
                val shown = if (effect.kind == VideoEffectID.colorIsolation && parameter.id == "color") IsolationHue.color(IsolationHue.degrees(value.value)) else value.value
                Box(Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).background(Color(shown.red.toFloat(), shown.green.toFloat(), shown.blue.toFloat())))
            }
            if (parameter.kind is EffectParameterKind.toggle && value is EffectValue.toggle) {
                Switch(value.value, { model.set(parameter, EffectValue.toggle(it)) }, enabled = !disabled && parameter.id != "ovalBokeh",
                    modifier = Modifier.semantics { contentDescription = parameter.title }, colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green))
            }
            if (parameter.id != "view" && parameter.animatable && (model.animate || parameter.keyframes.isNotEmpty())) {
                val keyed = parameter.keyframes.any { abs(it.time - time) < 1.0 / 60 }
                Box(Modifier.size(44.dp).clickable { model.keyframe(parameter) }.semantics { contentDescription = "Keyframe for " + parameter.title }, contentAlignment = Alignment.Center) {
                    Icon(if (keyed) Icons.Filled.Diamond else Icons.Outlined.Diamond, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        when (val kind = parameter.kind) {
            is EffectParameterKind.slider -> if (value is EffectValue.number) {
                val steps = ((kind.max - kind.min) / kind.step).toInt() - 1
                Slider(value.value.toFloat(), { v -> model.set(parameter, EffectValue.number(min(kind.max, max(kind.min, swiftRound((v - kind.min) / kind.step) * kind.step + kind.min)))) },
                    Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics { contentDescription = parameter.title },
                    valueRange = kind.min.toFloat()..kind.max.toFloat(), steps = if (steps in 1..400) steps else 0,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = effect.kind.librarySection.tint, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            }
            is EffectParameterKind.picker -> if (value is EffectValue.choice) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)).clickable { open = true }
                        .padding(horizontal = 12.dp).semantics { contentDescription = parameter.title + ", " + choiceTitle(effect, value.value, parameter) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(choiceTitle(effect, value.value, parameter), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
                    }
                    DropdownMenu(open, { open = false }) {
                        for (choice in VideoEffectParameterVisibility.choices(parameter, effect)) {
                            val unavailable = effect.kind == VideoEffectID.colorIsolation && parameter.id == "mode" && choice == "The subject"
                            DropdownMenuItem(text = { Text(choiceTitle(effect, choice, parameter) + if (unavailable) " · Not yet available" else "") }, enabled = !unavailable,
                                onClick = { open = false; model.set(parameter, EffectValue.choice(choice)) },
                                leadingIcon = { if (choice == value.value) Icon(Icons.Filled.Check, null) })
                        }
                    }
                }
            }
            is EffectParameterKind.stepper -> if (value is EffectValue.number) {
                // Swift `VideoEffectNumberChoices`: an adaptive grid of the allowed values.
                for (row in kind.values.chunked(5)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in row) {
                        Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = if (choice == value.value) 0.2f else 0.05f)).clickable { model.set(parameter, EffectValue.number(choice)) }
                            .semantics { contentDescription = parameter.title + " " + rateText(choice) }, contentAlignment = Alignment.Center) {
                            Text(rateText(choice), color = Color.White, fontSize = 15.sp)
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            is EffectParameterKind.color -> if (value is EffectValue.color && effect.kind == VideoEffectID.colorIsolation && parameter.id == "color") {
                Slider(IsolationHue.degrees(value.value).toFloat(), { model.set(parameter, EffectValue.color(IsolationHue.color(swiftRound(it.toDouble())))) },
                    Modifier.fillMaxWidth().semantics { contentDescription = "Hue" }, valueRange = 0f..359f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = effect.kind.librarySection.tint))
                if (!IsolationHue.accepts(value.value)) Text(IsolationHue.invalidSample, color = EditorColors.orange, fontSize = 12.sp)
            }
            is EffectParameterKind.media -> Text("Choose from Photos · Not yet available", color = EditorColors.secondary, fontSize = 15.sp)
            is EffectParameterKind.toggle -> {}
        }
        if (parameter.keyframes.isNotEmpty()) KeyframeNavigation(model, parameter)
    }
}

/** Swift `keyframeNavigation(_:)`. */
@Composable
private fun KeyframeNavigation(model: VideoEffectsWorkspace, parameter: EffectParameter) {
    val time = model.sourceTime
    val previous = parameter.keyframes.lastOrNull { it.time < time - 1.0 / 60000 }
    val next = parameter.keyframes.firstOrNull { it.time > time + 1.0 / 60000 }
    fun go(key: EffectKeyframe) { model.placement?.let { model.seek(it.start + it.clip.timelineTime(key.time)) } }
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).alpha(if (previous == null) 0.4f else 1f).clickable(enabled = previous != null) { previous?.let(::go) }
            .semantics { contentDescription = "Previous keyframe for " + parameter.title }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.SkipPrevious, null, tint = Color.White) }
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = true }.semantics { contentDescription = "Keyframes for " + parameter.title },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Diamond, null, tint = Color.White, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(6.dp))
                Text("${parameter.keyframes.size} " + if (parameter.keyframes.size == 1) "keyframe" else "keyframes", color = Color.White, fontSize = 12.sp)
            }
            DropdownMenu(open, { open = false }) { for (key in parameter.keyframes) DropdownMenuItem(text = { Text(editorTime(key.time)) }, onClick = { open = false; go(key) }) }
        }
        Box(Modifier.size(44.dp).alpha(if (next == null) 0.4f else 1f).clickable(enabled = next != null) { next?.let(::go) }
            .semantics { contentDescription = "Next keyframe for " + parameter.title }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.SkipNext, null, tint = Color.White) }
    }
}

/** Swift `ColorCorrectionPanel` with `ColorCorrectionSlider`s. */
@Composable
private fun ColorCorrectionPanel(model: VideoEffectsWorkspace, effect: VideoEffectSettings, includesBackground: Boolean) {
    var tab by remember { mutableStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val time = model.sourceTime
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.08f))) {
            for ((index, title) in listOf("Light", "Color").withIndex()) {
                Box(Modifier.weight(1f).heightIn(min = 36.dp).padding(2.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (tab == index) Color.White.copy(alpha = 0.22f) else Color.Transparent).clickable { tab = index }
                    .semantics { contentDescription = title }, contentAlignment = Alignment.Center) { Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Text("Before color looks · tap a value to reset", color = EditorColors.secondary, fontSize = 12.sp)
        if (includesBackground) Text("Also grades the replacement background.", color = EditorColors.secondary, fontSize = 12.sp)
        for (id in if (tab == 0) ColorCorrectionControls.light else ColorCorrectionControls.color) effect.parameters.firstOrNull { it.id == id }?.let { CorrectionSlider(model, effect, it) }
        if (tab == 0) {
            Row(Modifier.heightIn(min = 44.dp).clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced light", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Filled.ArrowDropDown else Icons.Filled.ChevronRight, null, tint = EditorColors.secondary)
            }
            if (advanced) {
                for (id in ColorCorrectionControls.advanced) effect.parameters.firstOrNull { it.id == id }?.let { p ->
                    Box(Modifier.alpha(if (p.id == "pivot" && effect.number("contrast", time) == 1.0) 0.45f else 1f)) { CorrectionSlider(model, effect, p) }
                }
                if (effect.number("contrast", time) == 1.0) Text("Adjust contrast to use the pivot.", color = EditorColors.secondary, fontSize = 12.sp)
            }
        }
        effect.parameters.firstOrNull { it.id == "intensity" }?.let { CorrectionSlider(model, effect, it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hold a label for help", color = EditorColors.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Reset correction", color = EditorColors.accent, fontSize = 15.sp, modifier = Modifier.heightIn(min = 44.dp).clickable { confirmReset = true }.padding(top = 12.dp))
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset this correction?") },
        text = { Text("Returns all controls to neutral and removes their keyframes. Other effects are kept.") },
        confirmButton = { TextButton({ confirmReset = false; model.resetCorrection() }) { Text("Reset all adjustments", color = EditorColors.red) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

@Composable
private fun CorrectionSlider(model: VideoEffectsWorkspace, effect: VideoEffectSettings, raw: EffectParameter) {
    val kind = raw.kind as? EffectParameterKind.slider ?: return
    val time = model.sourceTime
    // Swift `correctionParameter`: an animated correction shows its endpoint while editing it.
    val motion = effect.motion
    val shown = when {
        motion != null && model.correctionEndpoint != null && model.preview?.playing != true -> motion[model.correctionEndpoint!!][raw.id]
        motion != null -> effect.number(raw.id, time)
        else -> (raw.value(time) as? EffectValue.number)?.value ?: kind.defaultValue
    }
    var help by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(raw.title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(top = 10.dp)
                .combinedClickable(onClick = {}, onLongClick = { help = true }))
            Text(ColorCorrectionControls.display(raw.id, shown), color = Color.White, fontSize = 15.sp,
                modifier = Modifier.widthIn(min = 68.dp).heightIn(min = 44.dp).clickable { model.set(raw, EffectValue.number(kind.defaultValue)) }.padding(top = 12.dp)
                    .semantics { contentDescription = "Reset " + raw.title })
            if (motion == null && (model.animate || raw.keyframes.isNotEmpty())) {
                Box(Modifier.size(44.dp).clickable { model.keyframe(raw) }.semantics { contentDescription = "Keyframe for " + raw.title }, contentAlignment = Alignment.Center) {
                    Icon(if (raw.keyframes.any { abs(it.time - time) < 1.0 / 60 }) Icons.Filled.Diamond else Icons.Outlined.Diamond, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Slider(shown.toFloat(), { v -> model.set(raw, EffectValue.number(min(kind.max, max(kind.min, swiftRound((v - kind.min) / kind.step) * kind.step + kind.min)))) },
            Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics { contentDescription = raw.title }, valueRange = kind.min.toFloat()..kind.max.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.orange))
        if (motion == null && raw.keyframes.isNotEmpty()) KeyframeNavigation(model, raw)
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(raw.title) }, text = { Text(ColorCorrectionControls.help(raw.id)) },
        confirmButton = { TextButton({ help = false }) { Text("Got it") } })
}
