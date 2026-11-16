// Port of YouRec/SubtitleEditor.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The Subtitles tool: download the speech model once, transcribe the marked
// range (or the whole project) offline, regroup, restyle, place, correct and
// remove captions. Swift's view state lives in `SubtitleWorkspace` so the
// composable stays a layout. The preview is a second EditorController playing
// the project without captions; the draft captions are drawn over it by the
// same SubtitleRenderTrack the export uses (`previewCaptions`).
//
// Android differences: the language picker is a dropdown menu; the scene
// phase is the activity's ON_STOP; "Copy full transcript" uses the clipboard
// manager; the model is the 1.9 GB BF16 pack (SubtitleModelPack.kt).
package com.salvozappa.youroll

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** The state and actions of Swift `SubtitleEditor`. */
class SubtitleWorkspace(val context: Context, val project: Project, val folder: File, val request: ToolRequest) {
    companion object {
        const val awakeReason = "subtitle-editor"
        val languages = listOf("Auto", "Italian", "English", "French", "German", "Spanish", "Portuguese", "Chinese", "Japanese", "Korean", "Russian", "Arabic")
    }

    lateinit var preview: EditorController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var draft by mutableStateOf(project.edit.copy())
        private set
    var projectCues by mutableStateOf(project.edit.timeline.subtitleCues)
        private set
    var resolvedCues by mutableStateOf(projectCues.filter { it.start < request.range.endInclusive && it.end > request.range.start })
        private set
    var lastCopy by mutableStateOf(false)
    var captionLayout by mutableStateOf<SubtitleLayout?>(null)
        private set
    var inspecting by mutableStateOf(false)
    var language by mutableStateOf(project.edit.timeline.subtitles?.language ?: "Auto")
        private set
    var installed by mutableStateOf(SubtitleModelPack.installed(context))
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableDoubleStateOf(0.0)
        private set
    var status by mutableStateOf("")
        private set
    var failure by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var jobID: UUID? = null
    var stopping by mutableStateOf(false)
        private set
    var editing by mutableStateOf<SubtitleCue?>(null)
        private set
    var editedText by mutableStateOf("")
    var editingInAll by mutableStateOf(false)
        private set
    var selectedTranscriptID by mutableStateOf<String?>(null)
        private set
    var activeRange: ClosedFloatingPointRange<Double>? = null
        private set
    var pausedRange by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
        private set
    private var checkpoint: TranscriptionCheckpoint? = null
    var interruptionMessage by mutableStateOf<String?>(null)
        private set
    var pendingRange: ClosedFloatingPointRange<Double>? = null
    private var started = false
    private val track = SubtitleRenderTrack(emptyList(), SubtitleSettings(), folder)
    private var auditionEnd: Double? = null

    val cues: List<SubtitleCue> get() = resolvedCues
    val settings: SubtitleSettings get() = draft.timeline.subtitles ?: SubtitleSettings()
    val currentIndex: Int? get() = cues.indexOfLast { it.start <= preview.playhead && preview.playhead < it.end }.takeIf { it >= 0 }
    val focusIndex: Int get() = currentIndex ?: cues.indexOfFirst { it.start > preview.playhead }.takeIf { it >= 0 } ?: max(0, cues.size - 1)
    /**
     * A selection is half-open: seeking to its exact end would show the next
     * clip/caption. Keep the slider's final stop on its last output frame.
     */
    val seekRange: ClosedFloatingPointRange<Double>
        get() = request.range.start..max(request.range.start, request.range.endInclusive - min(1.0 / 30, (request.range.endInclusive - request.range.start) / 2))
    val ready: Boolean get() = !busy && !preview.loading && preview.audioMix != null
    val changed: Boolean get() = draft != project.edit

    private fun setDraft(transform: (TrackTimeline) -> Unit) {
        val next = draft.copy(); transform(next.timeline); draft = next
    }

    private fun setSettings(value: SubtitleSettings) = setDraft { it.subtitles = value }

    /** Swift `.task(id: revision)`: the project without captions; the draft is drawn over it. */
    fun loadPreview() {
        val candidate = project.copy()
        val base = draft.copy()
        base.timeline.removeSubtitles()
        // Keep a caption track on the preview, including before the first transcript.
        base.timeline.subtitles = SubtitleSettings()
        candidate.commit(base)
        preview.previewRange = request.range
        if (!started) { started = true; preview.seekAfterEdit(request.range.start, candidate.edit.duration) }
        preview.load(candidate, folder, includeTimelineDetails = false)
        refreshCaptionPreview()
    }

    /** Swift `rebuildCaptionLayout`. */
    fun rebuildCaptionLayout() {
        val canvas = preview.canvas
        if (canvas == null || draft.timeline.subtitles == null) { captionLayout = null; refreshCaptionPreview(); return }
        val layout = SubtitleLayout(projectCues, settings, canvas, folder)
        captionLayout = layout
        val constrained = layout.constrained(settings)
        if (constrained != settings) setSettings(constrained)
        refreshCaptionPreview()
    }

    /** Swift `refreshCaptionPreview`: the draft settings on the preview's caption track. */
    fun refreshCaptionPreview() {
        val canvas = preview.canvas ?: return
        track.updatePreview(captionLayout, settings, canvas)
        preview.previewCaptions = track
    }

    /** Swift `captionPreview`. */
    val captionPreview: CaptionPreview?
        get() {
            val layout = captionLayout ?: return null
            if (currentIndex == null) return null
            val settings = settings
            val face = settings.appearance?.let { TitleTypeface.label(it.face) } ?: settings.resolvedFont.title
            return CaptionPreview(layout.envelope(settings),
                "%.3f x · %.3f y · %.4f size · %s".format(java.util.Locale.ROOT, settings.centerX, settings.y, settings.size, face),
                editable = !busy && editing == null && !inspecting) { gesture ->
                preview.pause()
                var value = this.settings
                when (gesture) {
                    is VideoEffectPlacementGesture.pan -> value = value.copy(x = value.centerX + gesture.delta.x, y = value.y + gesture.delta.y)
                    is VideoEffectPlacementGesture.pinch -> {
                        val target = min(layout.maximumSize, max(layout.minimumSize, value.size * gesture.factor))
                        val ratio = target / value.size
                        value = value.copy(x = gesture.to.x + (value.centerX - gesture.from.x) * ratio, y = gesture.to.y + (value.y - gesture.from.y) * ratio, size = target)
                    }
                    VideoEffectPlacementGesture.ended -> {}
                }
                setSettings(layout.constrained(value))
                refreshCaptionPreview()
            }
        }

    /** Swift `place(_:)`. */
    fun place(body: (SubtitleSettings) -> SubtitleSettings) {
        preview.pause()
        val value = body(settings)
        setSettings(captionLayout?.constrained(value) ?: value)
        refreshCaptionPreview()
    }

    fun setDesign(value: TitleDesign) {
        val reflow = settings.appearance?.layoutKey != value.layoutKey
        setSettings(settings.copy(appearance = value)); preview.pause()
        if (reflow) rebuildCaptionLayout() else refreshCaptionPreview()
    }

    fun setMotion(value: TitleMotion) {
        setSettings(settings.copy(motion = value)); preview.pause(); rebuildCaptionLayout()
    }

    fun chooseLanguage(value: String) {
        language = value; pausedRange = null; checkpoint = null; interruptionMessage = null
    }

    fun regroup(count: Int) {
        setDraft { it.regroupSubtitles(count) }; changed()
    }

    fun removeAll() {
        setDraft { it.removeSubtitles() }; changed()
    }

    fun copyTranscript() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Transcript", SubtitleTiming.joined(projectCues.map { it.text })))
        lastCopy = true
        scope.launch { delay(2000); lastCopy = false }
    }

    /** Swift `sentence(_:current:all:)`'s tap. */
    fun tapSentence(cue: SubtitleCue, all: Boolean) {
        val edit = !all || selectedTranscriptID == cue.id
        preview.seekPaused(max(request.range.start, cue.start))
        if (all) selectedTranscriptID = cue.id
        if (edit) { editingInAll = all; editing = cue; editedText = cue.text }
    }

    fun cancelEdit() { editing = null }

    fun saveText() {
        val cue = editing ?: return
        val text = editedText.let { if (it.codePointCount(0, it.length) > 4000) it.substring(0, it.offsetByCodePoints(0, 4000)) else it }
        setDraft { it.correctSubtitle(cue, text) }
        editing = null; changed()
    }

    /** Swift `changed()`. */
    private fun changed() {
        selectedTranscriptID = null; lastCopy = false
        projectCues = draft.timeline.subtitleCues
        resolvedCues = projectCues.filter { it.start < request.range.endInclusive && it.end > request.range.start }
        failure = null; preview.pause(); rebuildCaptionLayout()
    }

    /** Clears a selected transcript row once playback leaves it. */
    fun followPreview() {
        val time = preview.playhead
        selectedTranscriptID?.let { id -> if (cues.none { it.id == id && time >= it.start && time < it.end }) selectedTranscriptID = null }
        val stop = auditionEnd
        if (stop != null && (!preview.playing || time >= stop - 0.015)) { auditionEnd = null; if (preview.playing) preview.pause() }
    }

    fun togglePlay() { auditionEnd = null; preview.togglePlay() }

    /** Swift `replay`: the entrance or exit window of the caption in focus. */
    fun replay(exit: Boolean) {
        val cue = cues.getOrNull(focusIndex) ?: return
        val window = (settings.motion ?: TitleMotion()).previewWindow(exit, cue.end - cue.start)
        val lower = max(request.range.start, cue.start + window.start)
        val upper = min(request.range.endInclusive, cue.start + window.endInclusive)
        if (upper > lower) { preview.seekPaused(lower); auditionEnd = upper; preview.togglePlay() }
    }

    /** The source frame at the playhead, for the inspector's colour sampling (crop not applied). */
    suspend fun frame(): Bitmap? {
        val time = preview.playhead
        val placement = project.edit.layout.placement(time) ?: return null
        val source = project.sources.firstOrNull { it.id == placement.clip.sourceID } ?: return null
        return CropMedia.Android.frame(source, folder, placement.clip.sourceTime(time - placement.start))
    }

    fun finish() { cancelJob(); preview.stop() }

    fun cancelJob(keepCheckpoint: Boolean = false) {
        val worker = job
        if (worker != null && jobID != null) {
            jobID = null
            worker.cancel()
            stopping = true
            scope.launch {
                worker.join()
                if (jobID == null) { stopping = false; job = null }
            }
        }
        busy = false
        activeRange = null
        if (!keepCheckpoint) { pausedRange = null; checkpoint = null; interruptionMessage = null }
    }

    /** Swift `scenePhase == .background` while busy. */
    fun backgrounded() {
        if (!busy) return
        pausedRange = activeRange
        interruptionMessage = if (activeRange == null) "Download interrupted. Tap Download to continue."
            else "Transcription paused. Resume here to continue from the last completed section."
        cancelJob(keepCheckpoint = true)
    }

    /** True when the range already has captions (Swift shows the replace prompt). */
    fun requestTranscription(range: ClosedFloatingPointRange<Double>): Boolean {
        if (!ready) return false
        if (draft.timeline.subtitleCues.any { it.start < range.endInclusive && it.end > range.start }) { pendingRange = range; return true }
        transcribe(range)
        return false
    }

    fun download() {
        if (stopping || busy) return
        activeRange = null; interruptionMessage = null
        busy = true; progress = 0.0; failure = null; status = "Downloading speech model…"; preview.pause()
        val id = newUUID()
        jobID = id
        job = scope.launch {
            try {
                SubtitleModelPack.download(context) { value -> scope.launch { if (jobID == id) progress = max(progress, value) } }
                if (jobID == id) installed = SubtitleModelPack.installed(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (jobID == id) failure = error.message ?: "The model download failed. Please retry."
            }
            if (jobID == id) { busy = false; jobID = null; job = null }
        }
    }

    fun removeModel() {
        try { SubtitleModelPack.remove(context); installed = false; pausedRange = null; checkpoint = null; interruptionMessage = null }
        catch (error: Exception) { failure = error.message }
    }

    fun transcribe(range: ClosedFloatingPointRange<Double>, resuming: Boolean = false) {
        val mix = preview.audioMix
        if (stopping || busy || mix == null) return
        if (!resuming) checkpoint = null
        pausedRange = null; activeRange = range; interruptionMessage = null
        busy = true; progress = 0.0; status = "Reading selected audio…"; failure = null; preview.pause(); editing = null
        val id = newUUID()
        val saved = checkpoint
        jobID = id
        job = scope.launch {
            try {
                val words = SubtitleTranscriber.shared.transcribe(context, mix, range, language, saved,
                    saveCheckpoint = { value -> scope.launch { if (jobID == id) checkpoint = value } }) { value, message ->
                    scope.launch { if (jobID == id && value >= progress) { progress = value; status = message } }
                }
                if (jobID == id) {
                    setDraft { it.installSubtitles(words, range, language) }
                    checkpoint = null; changed()
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (jobID == id) failure = error.message ?: "Transcription failed."
            }
            if (jobID == id) { busy = false; jobID = null; job = null; activeRange = null }
        }
    }

    fun release() { scope.cancel() }
}

@Composable
private fun subtitleSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f), disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray)

private enum class SubtitlePrompt { replace, remove, discard, applyPaused, removeModel }

@Composable
fun SubtitleEditor(project: Project, folder: File, request: ToolRequest, @Suppress("UNUSED_PARAMETER") position: Double,
                   cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { SubtitleWorkspace(context.applicationContext, project, folder, request) }
    val preview = remember { EditorController(context).also { model.preview = it } }
    val focus = LocalFocusManager.current
    var prompt by remember { mutableStateOf<SubtitlePrompt?>(null) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(revision) { delay(180); model.loadPreview() }
    DisposableEffect(model) {
        YouRollApp.keepAwake(SubtitleWorkspace.awakeReason, true)
        onDispose { model.cancelJob(); preview.stop(); preview.release(); model.release(); YouRollApp.keepAwake(SubtitleWorkspace.awakeReason, false) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.backgrounded() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(preview.canvas) { model.rebuildCaptionLayout() }
    LaunchedEffect(model) { while (true) { model.followPreview(); delay(33) } }
    fun finish(action: () -> Unit) { model.finish(); action() }
    val cancelAction = {
        if (model.editing != null) { model.cancelEdit(); focus.clearFocus() }
        else if (model.busy || model.pausedRange != null || model.changed) prompt = SubtitlePrompt.discard
        else finish(cancel)
    }
    BackHandler(onBack = cancelAction)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        val wide = maxWidth > maxHeight
        val boxHeight = maxHeight
        val boxWidth = maxWidth
        var textFocused by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (model.editing == null) "Cancel" else "Cancel edit", Modifier.widthIn(min = 60.dp), action = cancelAction)
                Text("Subtitles", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                PillButton(if (model.editing == null) "Apply" else "Save text", Modifier.widthIn(min = 60.dp), enabled = !model.busy, bold = true) {
                    if (model.editing != null) { model.saveText(); focus.clearFocus() }
                    else if (model.pausedRange != null) prompt = SubtitlePrompt.applyPaused
                    else finish { apply(model.draft) }
                }
            }
            @Composable fun pictureAndPlayback(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles, layers = preview.layers,
                            captions = preview.subtitles, captionPreview = model.captionPreview, inspectionZoom = model.inspecting,
                            onPlaybackTap = { if (model.ready && model.editing == null) model.togglePlay() })
                        if (preview.loading) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
                        Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(44.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape)
                            .alpha(if (model.ready) 1f else 0.4f).clickable(enabled = model.ready) { model.inspecting = !model.inspecting }
                            .semantics { contentDescription = if (model.inspecting) "Edit subtitle placement" else "Inspect subtitles preview" },
                            contentAlignment = Alignment.Center) {
                            Icon(if (model.inspecting) Icons.Filled.PanTool else Icons.Filled.ZoomIn, null,
                                tint = if (model.inspecting) Color.Cyan else Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val enabled = model.ready && model.editing == null
                        Box(Modifier.size(44.dp).alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled) { model.togglePlay() }
                            .semantics { contentDescription = if (preview.playing) "Pause subtitles preview" else "Play subtitles preview" }, contentAlignment = Alignment.Center) {
                            Icon(if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                        }
                        val range = model.seekRange
                        Slider(max(range.start, min(range.endInclusive, preview.playhead)).toFloat(), { preview.seekPaused(it.toDouble()) },
                            valueRange = range.start.toFloat()..max(range.start.toFloat() + 0.001f, range.endInclusive.toFloat()), enabled = enabled,
                            colors = subtitleSliderColors(), modifier = Modifier.weight(1f).semantics { contentDescription = "Subtitles preview position" })
                        Text(editorTime(preview.playhead), color = Color.White, fontSize = 12.sp, style = tabular, modifier = Modifier.widthIn(min = 45.dp))
                    }
                }
            }
            @Composable fun controls(modifier: Modifier) {
                Column(modifier.background(Color(0.075f, 0.075f, 0.075f)).verticalScroll(rememberScrollState()).padding(16.dp)
                    .semantics { contentDescription = "Subtitle controls" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(request.scopeTitle, color = EditorColors.secondary, fontSize = 12.sp)
                    Generation(model) { range -> if (model.requestTranscription(range)) prompt = SubtitlePrompt.replace }
                    if (model.projectCues.isNotEmpty()) {
                        Row(Modifier.heightIn(min = 44.dp).alpha(if (model.busy || model.editing != null) 0.4f else 1f)
                            .clickable(enabled = !model.busy && model.editing == null) { model.copyTranscript() }
                            .semantics { contentDescription = if (model.lastCopy) "Transcript copied" else "Copy full transcript" },
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (model.lastCopy) Icons.Filled.Check else Icons.Filled.ContentCopy, null, tint = EditorColors.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (model.lastCopy) "Transcript copied" else "Copy full transcript", color = EditorColors.accent, fontSize = 17.sp)
                        }
                    }
                    model.failure?.let { message ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = EditorColors.orange, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(message, color = EditorColors.orange, fontSize = 15.sp)
                        }
                    }
                    preview.error?.let { error ->
                        Text(error, color = EditorColors.orange, fontSize = 15.sp)
                        Text("Reload preview", color = EditorColors.accent, fontSize = 17.sp, modifier = Modifier.heightIn(min = 44.dp).clickable { preview.error = null; revision += 1 })
                    }
                    if (model.draft.timeline.subtitles != null) {
                        Grouping(model) { range -> if (model.requestTranscription(range)) prompt = SubtitlePrompt.replace }
                        Text("Drag subtitles to move · pinch to resize", color = EditorColors.secondary, fontSize = 12.sp)
                        Disclosure("Appearance", initiallyOpen = false) { Appearance(model) }
                        if (model.cues.isNotEmpty()) FocusedSentences(model) { focus.clearFocus() }
                        else Text("No captions in this range.", color = EditorColors.secondary, fontSize = 15.sp)
                        Row(Modifier.heightIn(min = 44.dp).alpha(if (model.busy || model.editing != null) 0.4f else 1f)
                            .clickable(enabled = !model.busy && model.editing == null) { prompt = SubtitlePrompt.remove }
                            .semantics { contentDescription = "Remove all subtitles" }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Delete, null, tint = EditorColors.red, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remove all subtitles", color = EditorColors.red, fontSize = 17.sp)
                        }
                        if (model.cues.isNotEmpty()) Disclosure("All subtitles · ${model.cues.size}", initiallyOpen = true) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (cue in model.cues) Sentence(model, cue, model.currentIndex?.let { model.cues[it].id == cue.id } ?: false, all = true,
                                    focused = { textFocused = it })
                            }
                        }
                    }
                    Text("Speech stays on your phone. Word timing is estimated from pauses.", color = EditorColors.secondary, fontSize = 12.sp)
                    if (model.installed) Text("Remove downloaded model (${SubtitleModelPack.sizeLabel})", color = EditorColors.red, fontSize = 12.sp,
                        modifier = Modifier.heightIn(min = 44.dp).alpha(if (model.busy || model.stopping) 0.4f else 1f)
                            .clickable(enabled = !model.busy && !model.stopping) { prompt = SubtitlePrompt.removeModel }.padding(top = 14.dp))
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    pictureAndPlayback(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(minOf(390.dp, boxWidth * 0.46f)).fillMaxHeight())
                }
            } else {
                pictureAndPlayback(Modifier.fillMaxWidth().height(max(130f, boxHeight.value * if (textFocused) 0.25f else 0.37f).dp))
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    when (prompt) {
        SubtitlePrompt.applyPaused -> ConfirmPrompt("Apply edits and discard paused transcription?", null, "Apply edits and close", destructive = true,
            dismiss = { prompt = null }) { finish { apply(model.draft) } }
        SubtitlePrompt.discard -> ConfirmPrompt("Discard subtitle work?", null, "Discard and close", destructive = true, dismiss = { prompt = null }) { finish(cancel) }
        SubtitlePrompt.removeModel -> ConfirmPrompt("Remove the downloaded speech model?",
            "Existing subtitles are kept. Generating more will require a ${SubtitleModelPack.sizeLabel} download.", "Remove model", destructive = true,
            dismiss = { prompt = null }) { model.removeModel() }
        SubtitlePrompt.replace -> ConfirmPrompt("Replace subtitles in this range? Corrected text in the range will be replaced. You can Undo after applying.", null,
            "Re-transcribe", destructive = false, dismiss = { prompt = null }) { model.pendingRange?.let { model.transcribe(it) } }
        SubtitlePrompt.remove -> ConfirmPrompt("Remove every subtitle from this project? This includes corrected text and timing. Undo will restore them.", null,
            "Remove all subtitles", destructive = true, dismiss = { prompt = null }) { model.removeAll() }
        null -> {}
    }
}

/** Swift `confirmationDialog` with one action and Cancel/Keep editing. */
@Composable
private fun ConfirmPrompt(title: String, message: String?, action: String, destructive: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, fontSize = 17.sp) }, text = message?.let { { Text(it) } },
        confirmButton = { TextButton({ dismiss(); confirm() }) { Text(action, color = if (destructive) EditorColors.red else EditorColors.accent) } },
        dismissButton = { TextButton(dismiss) { Text(if (action.endsWith("close")) "Keep editing" else "Cancel") } })
}

@Composable
private fun Disclosure(title: String, initiallyOpen: Boolean, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = !open }.semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = EditorColors.accent)
        }
        if (open) Box(Modifier.padding(top = 8.dp)) { content() }
    }
}

/** Swift `generation`. */
@Composable
private fun Generation(model: SubtitleWorkspace, request: (ClosedFloatingPointRange<Double>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (model.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.status, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                PillButton("Cancel", Modifier.semantics { contentDescription = "Cancel subtitle job" }) { model.cancelJob() }
            }
            LinearProgressIndicator(progress = { model.progress.toFloat() }, color = EditorColors.accent, trackColor = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Subtitle progress" })
        } else {
            if (model.stopping) Text("Stopping…", color = Color.White, fontSize = 15.sp)
            model.interruptionMessage?.let { Text(it, color = Color.White, fontSize = 16.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.heightIn(min = 44.dp).clickable { open = true }.semantics { contentDescription = "Language, ${model.language}" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Language", color = Color.White, fontSize = 17.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(model.language, color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp)
                        Icon(Icons.Filled.ExpandMore, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(open, { open = false }) {
                        for (language in SubtitleWorkspace.languages) DropdownMenuItem(text = { Text(language) }, onClick = { open = false; model.chooseLanguage(language) },
                            leadingIcon = if (language == model.language) { { Icon(Icons.Filled.Check, null) } } else null)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (model.installed) {
                    val enabled = model.ready && !model.stopping && model.editing == null
                    val title = if (model.pausedRange != null) "Resume transcription" else if (model.cues.isEmpty()) "Transcribe" else "Re-transcribe"
                    Row(Modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.4f).background(EditorColors.accent, RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) {
                            val paused = model.pausedRange
                            if (paused != null) model.transcribe(paused, resuming = true) else request(model.request.range)
                        }.padding(horizontal = 12.dp).semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ClosedCaption, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (!model.installed) {
                Text("Download Qwen speech recognition once, then use it offline. About ${SubtitleModelPack.sizeLabel}.", color = EditorColors.secondary, fontSize = 15.sp)
                Box(Modifier.heightIn(min = 44.dp).alpha(if (model.stopping) 0.4f else 1f).background(EditorColors.accent, RoundedCornerShape(10.dp))
                    .clickable(enabled = !model.stopping) { model.download() }.padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Download speech model" }, contentAlignment = Alignment.Center) {
                    Text("Download speech model · ${SubtitleModelPack.sizeLabel}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Swift `grouping`: the Max words stepper and the stale-footage notice. */
@Composable
private fun Grouping(model: SubtitleWorkspace, request: (ClosedFloatingPointRange<Double>) -> Unit) {
    val available = model.draft.timeline.subtitleGroupingAvailable
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val enabled = !model.busy && model.editing == null && available
        val count = model.settings.maxWords
        Row(Modifier.alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
            Text("Max words per subtitle: $count", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Row(Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))) {
                Box(Modifier.size(width = 46.dp, height = 36.dp).clickable(enabled = enabled && count > 1) { model.regroup(count - 1) }
                    .semantics { contentDescription = "Fewer words per subtitle" }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Remove, null, tint = Color.White) }
                Box(Modifier.size(width = 46.dp, height = 36.dp).clickable(enabled = enabled && count < 16) { model.regroup(count + 1) }
                    .semantics { contentDescription = "More words per subtitle" }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, tint = Color.White) }
            }
        }
        if (!available) {
            Text("Footage changed. Re-transcribe to change subtitle grouping.", color = EditorColors.orange, fontSize = 12.sp)
            val ready = model.ready && model.editing == null
            Text("Re-transcribe whole project", color = EditorColors.accent, fontSize = 17.sp,
                modifier = Modifier.heightIn(min = 44.dp).alpha(if (ready) 1f else 0.4f).clickable(enabled = ready) { request(0.0..model.draft.duration) })
        }
    }
}

/** Swift `appearance`. */
@Composable
private fun Appearance(model: SubtitleWorkspace) {
    val enabled = !model.busy && model.editing == null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Drag or pinch the caption to position all subtitles. Pinch elsewhere to inspect the video. The outline fits the largest caption in the whole project.",
            color = EditorColors.secondary, fontSize = 12.sp)
        Box(Modifier.alpha(if (enabled) 1f else 0.4f)) {
            val settings = model.settings
            TitleInspector(design = settings.design, setDesign = { if (enabled) model.setDesign(it) },
                motion = settings.motion ?: TitleMotion(), setMotion = { if (enabled) model.setMotion(it) },
                sample = model.currentIndex?.let { model.cues[it].text } ?: model.cues.firstOrNull()?.text ?: "Subtitles", prefix = "subtitles",
                frame = { model.frame() }, replay = { exit -> model.replay(exit) }, pauseForFonts = { model.preview.pause() })
        }
        val layout = model.captionLayout
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(55.dp))
            val lower = (layout?.minimumSize ?: 0.012).toFloat()
            val upper = max(lower + 0.0001f, (layout?.maximumSize ?: 0.10).toFloat())
            Slider(model.settings.size.toFloat().coerceIn(lower, upper), { size -> model.place { it.copy(size = size.toDouble()) } }, valueRange = lower..upper,
                enabled = !model.busy, colors = subtitleSliderColors(), modifier = Modifier.weight(1f).semantics { contentDescription = "Subtitle size" })
        }
        Disclosure("Position", initiallyOpen = false) {
            Column {
                Slider(model.settings.centerX.toFloat(), { x -> model.place { it.copy(x = x.toDouble()) } }, valueRange = 0f..1f, enabled = !model.busy,
                    colors = subtitleSliderColors(), modifier = Modifier.semantics { contentDescription = "Subtitle horizontal position" })
                Slider(model.settings.y.toFloat(), { y -> model.place { it.copy(y = y.toDouble()) } }, valueRange = 0f..1f, enabled = !model.busy,
                    colors = subtitleSliderColors(), modifier = Modifier.semantics { contentDescription = "Subtitle vertical position" })
                Text("Reset position and size", color = EditorColors.accent, fontSize = 17.sp, modifier = Modifier.heightIn(min = 44.dp)
                    .clickable(enabled = !model.busy) { model.place { it.copy(x = 0.5, y = 0.86, size = 0.052) } }.padding(top = 10.dp))
            }
        }
    }
}

/** Swift `focusedSentences`: two captions either side of the one playing. */
@Composable
private fun FocusedSentences(model: SubtitleWorkspace, @Suppress("UNUSED_PARAMETER") unfocus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Following playback", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (model.currentIndex == null) Text("Pause in speech", color = EditorColors.secondary, fontSize = 12.sp)
        }
        for (offset in -2..2) {
            val index = model.focusIndex + offset
            model.cues.getOrNull(index)?.let { cue -> Sentence(model, cue, model.currentIndex == index, all = false) }
        }
    }
}

/** Swift `sentence(_:current:all:)`: a row that seeks (and edits on a second tap in the full list). */
@Composable
private fun Sentence(model: SubtitleWorkspace, cue: SubtitleCue, current: Boolean, all: Boolean, focused: (Boolean) -> Unit = {}) {
    if (model.editing?.id == cue.id && model.editingInAll == all) {
        val requester = remember { FocusRequester() }
        OutlinedTextField(model.editedText, { model.editedText = it }, placeholder = { Text("Subtitle text") }, minLines = 2, maxLines = 6,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = EditorColors.accent,
                focusedBorderColor = EditorColors.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(requester).semantics { contentDescription = "Subtitle text" })
        LaunchedEffect(cue.id) { requester.requestFocus(); focused(true) }
        DisposableEffect(cue.id) { onDispose { focused(false) } }
        return
    }
    val enabled = !model.busy && model.editing == null
    val selected = all && model.selectedTranscriptID == cue.id
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .background(if (current) Color.Cyan.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.035f), RoundedCornerShape(9.dp))
        .alpha(if (enabled) 1f else 0.6f)
        .clickable(enabled = enabled) { model.tapSentence(cue, all) }
        .padding(10.dp)
        .semantics {
            contentDescription = (if (all) "Transcript " else "Sentence ") + editorTime(cue.start) + ", " + cue.text
            this.selected = selected
        }, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(editorTime(cue.start), color = EditorColors.secondary, fontSize = 12.sp, style = tabular, modifier = Modifier.width(45.dp))
        Text(cue.text, color = if (current) Color.White else EditorColors.secondary, fontSize = 15.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (current) Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.Cyan, modifier = Modifier.size(14.dp))
    }
}
