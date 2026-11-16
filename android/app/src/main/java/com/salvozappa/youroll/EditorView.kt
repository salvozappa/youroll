// Port of YouRec/EditorView.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Transitions: the join chip/bubble (`effectChips`, TransitionEditor.kt), timeline markers and TransitionEditor.
// Not ported (TODO.md): effect bubbles and tool regions of the other tools,
// Preview cut and the
// double-tap quick review, Original/Processed comparison, export progress in
// the editor (the export panel belongs to ContentView), effect application
// receipts, accessibility-size layouts and VoiceOver actions.
package com.salvozappa.youroll

import android.content.Context
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.TransformOrigin
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class MoveOrigin(val revision: SwiftDate, val playhead: Double, val scale: Double)

/** Edit operations of EditorView.swift that do not need the view, kept apart so they run in JVM tests. */
object EditorOperations {
    class Result(val state: EditState, val position: Double?, val feedback: String)

    /**
     * Swift `trimItem(_:left:delta:project:)`. Trim is not a timeline operation:
     * shrinking scopes the trimmed range of this item (and its linked parts),
     * then removes it; revealing earlier or later material is `extend`.
     */
    fun trim(edit: EditState, sources: List<SourceMedia>, id: UUID, left: Boolean, delta: Double, preserving: Double): Result? {
        val chosen = edit.timeline.selection(of = id)
        val interval = edit.timeline.interval(chosen) ?: return null
        if (left && delta < 0 || !left && delta > 0) {
            val next = edit.copy()
            next.timeline.extend(chosen, left, EditTime.of(abs(delta)), sources)
            return if (next != edit) Result(next, null, "Trim extended · Undo available") else null
        }
        // A trim removes material from this occurrence, never from another lane.
        val point = if (left) min(interval.end.seconds - TimelineEditing.frame, max(interval.start.seconds, interval.start.seconds + delta))
            else max(interval.start.seconds + TimelineEditing.frame, min(interval.end.seconds, interval.end.seconds + delta))
        val removed = if (left) interval.start.seconds..point else point..interval.end.seconds
        if (removed.endInclusive - removed.start <= 0.00001) return null
        val scope = edit.timeline.scoped(chosen.copy(range = EditInterval.of(removed.start, removed.endInclusive)))
        val position = scope.timeline.remove(scope.selection, EditTime.of(preserving))
        return Result(EditState(scope.timeline, edit.outputSize), position?.seconds, "Trimmed · Undo available")
    }

    /** Swift `removeSelection`: a complete linked picture+sound group closes its gap; a lone part leaves one. */
    fun remove(edit: EditState, targets: TimelineSelection, preserving: Double): Result? {
        val scope = edit.timeline.scoped(targets)
        val position = scope.timeline.remove(scope.selection, EditTime.of(preserving))
        val next = EditState(scope.timeline, edit.outputSize)
        if (scope.selection.isEmpty || next == edit) return null
        return Result(next, position?.seconds, "Deleted · Undo available")
    }

    fun selectionTitle(project: Project, edit: EditState, scope: TimelineSelection): String {
        val timeline = edit.timeline
        val video = timeline.video.withIndex().filter { it.value.id in scope.video }.map { "V${it.index + 1}" }
        val audio = timeline.audio.withIndex().filter { it.value.id in scope.audio }.map { "♪${it.index + 1}" }
        val names = video + audio
        if (names.size == 1) {
            val item = timeline.audio.firstOrNull { it.id in scope.audio }
            val name = item?.let { sound -> project.sources.firstOrNull { it.id == sound.sourceID }?.originalName }
            if (name != null) return names[0] + " · " + name
        }
        return if (names.size > 4) scope.title + " · ${names.size} items" else names.joinToString(" + ")
    }
}

internal fun preferences(context: Context) = context.getSharedPreferences("youroll.editor", Context.MODE_PRIVATE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorView(
    store: ProjectStore,
    projectID: UUID,
    back: () -> Unit,
    recordHere: (EditTime, VideoInsertionSite?) -> Unit,
    export: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val projects by store.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectID }
    val editor = remember { EditorController(context) }
    DisposableEffect(editor) { onDispose { editor.release() } }
    // The library holds summaries; open the full history (decoded off the main thread) before editing.
    LaunchedEffect(projectID) { if (project?.deferredHistory != null) store.prepareProject(projectID) }
    if (project == null) { Box(Modifier.fillMaxSize().background(Color.Black)); return }
    val edit = remember(project.updatedAt, project.historyCursor, project.deferredHistory) { project.edit }
    val folder = store.folder(project)
    fun current(): Project? = store.projects.value.firstOrNull { it.id == projectID }

    var snapToCuts by remember { mutableStateOf(preferences(context).getBoolean("snapToCuts", true)) }
    var matchListeningLevels by remember { mutableStateOf(preferences(context).getBoolean("matchListeningLevels", true)) }
    var rangeTargets by remember { mutableStateOf<TimelineSelection?>(null) }
    var selectedItems by remember { mutableStateOf(emptySet<UUID>()) }
    var markStart by remember { mutableStateOf<Double?>(null) }
    var selection by remember { mutableStateOf<ClosedFloatingPointRange<Double>?>(null) }
    var pointsPerSecond by remember { mutableStateOf(60.0) }
    var playbackSpeedsOpen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var fullPreview by remember { mutableStateOf(false) }
    var toolsSheet by remember { mutableStateOf(false) }
    var showingAddVideo by remember { mutableStateOf(false) }
    /** Swift `coverPicker`: the cover picker shown inside the Tools sheet. */
    var coverPicker by remember { mutableStateOf<CoverPickerSource?>(null) }
    var addAudioSheet by remember { mutableStateOf(false) }
    var timelineAddSite by remember { mutableStateOf<VideoInsertionSite?>(null) }
    var insertionTime by remember { mutableStateOf(0.0) }
    var toolRequest by remember { mutableStateOf<ToolRequest?>(null) }
    // Swift `removalRegionID`: the effect bubble showing its removal controls.
    var removalRegionID by remember { mutableStateOf<String?>(null) }
    /** Swift `removalRegionID` for the join's transition bubble: armed for removal. */
    var transitionRemovalArmed by remember { mutableStateOf(false) }
    val cutLayout = remember(edit) { edit.layout }
    var bookmarkHelp by remember { mutableStateOf(false) }
    var bookmarksSheet by remember { mutableStateOf(false) }
    var renamingBookmark by remember { mutableStateOf<UUID?>(null) }
    var bookmarkName by remember { mutableStateOf("") }
    var pendingLink by remember { mutableStateOf<TimelineLinkPlan?>(null) }
    var pendingLinkBase by remember { mutableStateOf<EditState?>(null) }
    val clipboards by store.trackClipboards.collectAsState()
    val clipboard = clipboards[projectID]
    val pasteTargets = remember(edit) { edit.timeline.pasteTargets(edit.layout) }
    var selectionMenu by remember { mutableStateOf(false) }
    val viewPositions = remember { mutableStateMapOf<Int, Double>() }
    // Move mode.
    var moveBase by remember { mutableStateOf<EditState?>(null) }
    var moveDraft by remember { mutableStateOf<EditState?>(null) }
    var moveChoices by remember { mutableStateOf(TimelineMoveChoices()) }
    var moveScope by remember { mutableStateOf(TimelineMoveScope.selected) }
    var moveOrigin by remember { mutableStateOf<MoveOrigin?>(null) }
    var moveBoundaries by remember { mutableStateOf(TimelineMoveBoundaries()) }
    var moveViewportEnd by remember { mutableStateOf<EditTime?>(null) }
    var pickingMoveBoundary by remember { mutableStateOf(false) }
    var moveBrowseTime by remember { mutableStateOf<Double?>(null) }
    var nearbyMoveBoundaries by remember { mutableStateOf(emptyList<EditTime>()) }
    var moveOffset by remember { mutableStateOf(0.0) }
    var requestedMoveOffset by remember { mutableStateOf(0.0) }
    var moveSnap by remember { mutableStateOf<TimelineMoveSnap?>(null) }
    var moveDragOrigin by remember { mutableStateOf(0.0) }
    var movePreview by remember { mutableStateOf<Job?>(null) }
    val moveTargets = moveChoices[moveScope]

    LaunchedEffect(feedback) { if (feedback != null) { delay(3000); feedback = null } }
    LaunchedEffect(project.updatedAt, project.historyCursor) {
        val origin = moveOrigin
        if (origin != null && origin.revision != project.updatedAt) {
            // Another change arrived: the move draft no longer applies.
            moveOrigin = null; moveDraft = null; moveBase = null; pickingMoveBoundary = false
            feedback = "Timeline changed. Select the clips again."
        }
        selectedItems = selectedItems intersect edit.timeline.allIDs
        editor.load(project, folder)
    }

    fun targets(): TimelineSelection {
        val value = rangeTargets ?: if (selectedItems.isEmpty()) edit.timeline.entireSelection() else edit.timeline.selection(ids = selectedItems)
        return value.copy(range = selection?.let { EditInterval.of(it.start, it.endInclusive) })
    }
    fun selectedTargets(): TimelineSelection =
        if (selection != null || markStart != null || selectedItems.isNotEmpty()) targets() else TimelineSelection()
    fun clearSelection() { selection = null; markStart = null; selectedItems = emptySet(); rangeTargets = null }

    fun finishEdit(state: EditState, from: Project, position: Double?) {
        rangeTargets?.let { scope0 ->
            val mapped = state.timeline.remapping(scope0.copy(range = selection?.let { EditInterval.of(it.start, it.endInclusive) }), from.edit.timeline)
            rangeTargets = mapped
            if (selection != null) selection = mapped.range?.seconds
        }
        if (selectedItems.isNotEmpty()) {
            selectedItems = state.timeline.remapping(from.edit.timeline.selection(ids = selectedItems), from.edit.timeline).ids
        }
        val newPosition = position ?: min(editor.playhead, state.duration)
        current()?.let { viewPositions[it.historyCursor] = newPosition }
        editor.seekAfterEdit(newPosition, state.duration)
    }

    fun commit(state: EditState, position: Double? = null): Boolean {
        val base = current() ?: return false
        editor.pause()
        viewPositions[base.historyCursor] = editor.playhead
        if (!store.commit(projectID, state)) {
            editor.error = store.error.value ?: "The edit could not be saved."
            return false
        }
        finishEdit(state, base, position)
        return true
    }

    fun history(redo: Boolean) {
        val base = current() ?: return
        editor.pause(); viewPositions[base.historyCursor] = editor.playhead
        if (!store.update(projectID) { if (redo) it.redo() else it.undo() }) { editor.error = store.error.value; return }
        clearSelection()
        current()?.let { editor.seekAfterEdit(viewPositions[it.historyCursor] ?: editor.playhead, it.edit.duration) }
        feedback = if (redo) "Redone" else "Undone"
    }

    fun split() {
        val base = current() ?: return
        val time = editor.playhead
        val items = if (selection == null && markStart == null) selectedItems else emptySet()
        if (cutLayout.transition(time) != null) { feedback = "Remove the transition before splitting inside its overlap"; return }
        val next = TimelineEditing.split(base.edit, time, items)
        if (next == base.edit) {
            feedback = if (cutLayout.placement(time)?.let { val joins = cutLayout.joins(it); joins.first.transition != null || joins.second.transition != null } == true)
                "Shorten or remove the nearby transition before splitting here" else "Move at least one frame inside a clip"
            return
        }
        if (!commit(next, TimelineEditing.quantize(time))) return
        clearSelection()
        feedback = "Split · Undo available"
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun mark() {
        val raw = editor.playhead
        val boundary = if (snapToCuts) TimelineEditing.snap(raw, edit.clips, pointsPerSecond) else raw
        val onBoundary = edit.layout.boundary(boundary) != null
        var time = if (snapToCuts && onBoundary) boundary else TimelineEditing.quantize(editor.snapped(raw, edit.clips))
        edit.layout.transition(time)?.let { time = it.time; feedback = "Selection aligned to the transition join" }
        editor.pause(); editor.seek(time)
        val range = selection
        val start = markStart
        if (range != null) {
            if (time - range.start < TimelineEditing.frame - 0.00001) { feedback = "Place the end after the start"; return }
            selection = range.start..time
        } else if (start != null) {
            if (abs(start - time) < TimelineEditing.frame - 0.00001) { feedback = "Choose an end at least one frame away"; return }
            selection = min(start, time)..max(start, time); markStart = null
        } else { selection = null; markStart = time; rangeTargets = targets() }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun removeSelection() {
        if (selectedItems.isEmpty() && selection == null) return
        val base = current() ?: return
        val result = EditorOperations.remove(base.edit, targets(), editor.stationaryPlayhead)
        if (result == null) { clearSelection(); feedback = "Nothing selected to delete"; return }
        if (!commit(result.state, result.position)) return
        clearSelection(); feedback = result.feedback
    }

    fun trimItem(id: UUID, left: Boolean, delta: Double) {
        val base = current() ?: return
        val result = EditorOperations.trim(base.edit, base.sources, id, left, delta, editor.stationaryPlayhead) ?: return
        if (commit(result.state, result.position)) feedback = result.feedback
    }

    fun applyLink(target: TimelineSelection, intersectionOnly: Boolean = false) {
        val base = current() ?: return
        try {
            val joined = base.edit.timeline.joining(target, intersectionOnly)
            val next = base.edit.copy(); next.timeline = joined.timeline
            if (commit(next)) { selectedItems = joined.selection.ids; feedback = "Linked · Undo available" }
        } catch (failure: TimelineLinkError) { editor.error = failure.message }
    }

    fun toggleLink() {
        if (selection != null || markStart != null) {
            editor.error = "Clear the time range, then tap a part and long-press other parts to select them for Link."; return
        }
        val base = current() ?: return
        val target = selectedTargets()
        if (base.edit.timeline.isSingleLink(target)) {
            val next = base.edit.copy(); next.timeline.unlink(target)
            if (commit(next)) feedback = "Unlinked ${target.countTitle} · Undo restores links"
            return
        }
        try {
            val plan = base.edit.timeline.linkPlan(target)
            if (plan.needsIntersection) { pendingLinkBase = base.edit; pendingLink = plan } else applyLink(target)
        } catch (failure: TimelineLinkError) { editor.error = failure.message }
    }

    fun previewMove() {
        val draft = moveDraft ?: return
        val base = current() ?: return
        movePreview?.cancel()
        movePreview = scope.launch {
            delay(120)
            val candidate = base.copy().also { it.history = listOf(draft); it.historyCursor = 0 }
            editor.load(candidate, folder)
        }
    }

    fun updateMove(offset: Double, snapping: Boolean = true, scale: Double? = null, changingScope: Boolean = false) {
        val base = moveBase?.copy() ?: return
        val targetsNow = moveChoices[moveScope]
        val interval = base.timeline.interval(targetsNow) ?: return
        var delta = max(-interval.start.seconds, offset)
        val previousSnap = moveSnap
        if (snapToCuts && snapping) {
            val result = base.timeline.snappedMove(targetsNow, EditTime.of(delta), scale ?: pointsPerSecond, moveSnap, listOf(editor.playhead))
            delta = result.delta.seconds; moveSnap = result.snap
        } else moveSnap = null
        if (moveSnap != null && moveSnap != previousSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        moveOffset = max(-interval.start.seconds, delta)
        // Trying a wider group can hit time zero. Switching back restores the original drag.
        if (!changingScope) requestedMoveOffset = moveOffset
        base.timeline.move(targetsNow, EditTime.of(moveOffset)); moveDraft = base
        if (changingScope && moveViewportEnd != null) moveViewportEnd = maxOf(EditTime.of(edit.duration), base.timeline.duration)
    }

    fun beginMove() {
        editor.pause()
        val base = current() ?: return
        val scoped = base.edit.timeline.scoped(targets())
        if (scoped.selection.isEmpty) return
        val state = base.edit.copy(); state.timeline = scoped.timeline
        moveChoices = state.timeline.moveChoices(scoped.selection); moveScope = TimelineMoveScope.selected
        moveOrigin = MoveOrigin(base.updatedAt, editor.playhead, pointsPerSecond)
        moveBoundaries = TimelineMoveBoundaries(state.timeline, scoped.selection, scoped.selection)
        pickingMoveBoundary = false; moveViewportEnd = null
        moveBase = state; moveDraft = state; moveOffset = 0.0; requestedMoveOffset = 0.0; moveDragOrigin = 0.0; moveSnap = null
    }

    fun changeMoveScope(value: TimelineMoveScope) {
        if (value == moveScope || !moveChoices.available(value)) return
        val base = moveBase ?: return
        moveScope = value
        moveBoundaries = TimelineMoveBoundaries(base.timeline, moveChoices.selected, moveChoices[value])
        nearbyMoveBoundaries = emptyList()
        updateMove(requestedMoveOffset, snapping = false, changingScope = true)
        moveDragOrigin = moveOffset
        if (requestedMoveOffset != 0.0) previewMove()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun placeMove(time: EditTime) {
        val base = current() ?: return
        if (moveOrigin?.revision != base.updatedAt || time !in moveBoundaries.times) return
        editor.pause()
        pickingMoveBoundary = false
        updateMove((time - moveBoundaries.anchor).seconds, snapping = false)
        moveDragOrigin = moveOffset
        moveSnap = TimelineMoveSnap(time, moveBoundaries.anchor)
        moveDraft?.let { draft ->
            moveViewportEnd = maxOf(EditTime.of(base.edit.duration), draft.timeline.duration)
            editor.seekAfterEdit(time.seconds, draft.duration)
        }
        previewMove()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun finishMove(apply: Boolean) {
        movePreview?.cancel(); movePreview = null
        val origin = moveOrigin ?: return
        val base = current() ?: return
        val draft = moveDraft
        var applied = false
        if (apply && origin.revision == base.updatedAt && draft != null && EditTime.of(moveOffset) != EditTime.zero) {
            if (!commit(draft)) return
            viewPositions[base.historyCursor] = origin.playhead
            clearSelection(); applied = true
        } else {
            pointsPerSecond = origin.scale
            editor.pause(); editor.seekAfterEdit(origin.playhead, base.edit.duration)
            editor.load(base, folder)
        }
        pickingMoveBoundary = false; nearbyMoveBoundaries = emptyList(); moveOrigin = null; moveViewportEnd = null
        moveDraft = null; moveBase = null; moveDragOrigin = 0.0; moveSnap = null
        feedback = if (origin.revision != base.updatedAt) "Timeline changed. Select the clips again."
            else if (apply) (if (applied) "Moved · Undo available" else null) else "Move cancelled"
    }

    fun makeVolumeRequest(): ToolRequest {
        val target = targets().copy(video = emptySet())
        val range = edit.timeline.interval(target)?.seconds ?: 0.0..edit.duration
        val title = if (selectedTargets().isEmpty) "Whole project · All audio" else EditorOperations.selectionTitle(project, edit, target)
        return ToolRequest(VideoTool.volume, range, title + " · " + editorTime(range.start) + "–" + editorTime(range.endInclusive), target)
    }

    /** Swift `makeToolRequest` for the video tools: marked range, else selection, else the whole project. */
    fun makeToolRequest(kind: VideoTool): ToolRequest {
        var scope = targets()
        if (kind in setOf(VideoTool.enhanceAudio, VideoTool.volume, VideoTool.censorAudio)) scope = scope.copy(video = emptySet())
        var range = edit.timeline.interval(scope)?.seconds ?: 0.0..edit.duration
        // New Text (and an Image without a selected clip) defaults to three seconds at the playhead unless a range is marked.
        if (selection == null && (kind == VideoTool.text || (kind == VideoTool.image && selectedItems.isEmpty()))) {
            range = min(editor.playhead, max(0.0, edit.duration - TimelineEditing.frame))..min(edit.duration, editor.playhead + 3)
        }
        val title = EditorOperations.selectionTitle(project, edit, scope)
        val scopeTitle = if (kind == VideoTool.text || kind == VideoTool.image) title else title + " · " + editorTime(range.start) + "–" + editorTime(range.endInclusive)
        return ToolRequest(kind, range, scopeTitle, scope, clipIDs = scope.video)
    }

    /**
     * Swift `uniqueEffects`: one row per effect identity, those at the playhead
     * first, limited to the selection. Only the tools that can be reopened here
     * (Subtitles, Speed, Text, Crop, Image, Mirror, Pauses, Color, Stabilize and Censor audio) are listed; see `toolRegions`.
     */
    fun uniqueEffects(): List<ToolRegion> {
        val selected = selectedTargets()
        val here = EditTime.of(editor.playhead)
        return edit.toolRegions.filter { region ->
            region.tool in setOf(VideoTool.subtitles, VideoTool.speed, VideoTool.text, VideoTool.crop, VideoTool.image, VideoTool.mirror, VideoTool.pauses, VideoTool.color, VideoTool.stabilize, VideoTool.censorAudio, VideoTool.enhanceAudio) && (selected.isEmpty || (region.clipID in selected.ids &&
                (selected.range == null || selected.range.intersection(EditInterval.of(region.range.start, region.range.endInclusive)) != null)))
        }.sortedWith(compareBy({ !EditInterval.of(it.range.start, it.range.endInclusive).contains(here) }, { it.range.start }))
            .distinctBy { it.tool.rawValue + it.effectID }
    }

    /** Swift `makeToolRequest(.transition)` / `request(for:)` of a transition: the join it edits. */
    fun transitionRequest(join: TransitionJoin): ToolRequest =
        ToolRequest(VideoTool.transition, join.range, join.title, effectID = join.transition?.id, boundaryIndex = join.index)

    /** Swift `request(for:project:)` in EditorView: a Placement opens on its overlap, a transition on its join; the other tools by effect identity. */
    fun request(region: ToolRegion): ToolRequest {
        if (region.tool == VideoTool.placement) {
            val regionInterval = EditInterval.of(region.range.start, region.range.endInclusive)
            val plan = edit.timeline.compile().firstOrNull { it.video.size > 1 && it.interval.intersection(regionInterval) != null }
            val ids = plan?.video.orEmpty().toSet()
            val selectedRange = selection?.let { EditInterval.of(it.start, it.endInclusive).intersection(regionInterval) }
            return ToolRequest(VideoTool.placement, selectedRange?.seconds ?: region.range, "Overlapping videos",
                targets = selectedRange?.let { TimelineSelection(video = ids, range = it) }, effectID = plan?.placementID, clipIDs = ids)
        }
        if (region.tool == VideoTool.transition) {
            edit.layout.joins.firstOrNull { it.transition?.id == region.effectID }?.let { return transitionRequest(it) }
        }
        return edit.request(region, selectedTargets())
    }

    fun applyTool(state: EditState, request: ToolRequest) {
        val base = current() ?: run { toolRequest = null; return }
        if (state == base.edit) { toolRequest = null; return }
        // After a transition edit the playhead stays on the (moved) join.
        val transitionPosition = request.boundaryIndex?.takeIf { request.tool == VideoTool.transition }?.let { index ->
            val original = base.edit.layout.joins.firstOrNull { it.index == index } ?: return@let null
            state.layout.joins.firstOrNull { it.outgoingID == original.outgoingID && it.incomingID == original.incomingID }?.time
        }
        if (!commit(state, transitionPosition)) return
        if (request.tool == VideoTool.volume && request.targets?.range != null) clearSelection()
        toolRequest = null; feedback = "${request.tool.title} updated · Undo available"
    }

    fun commitGain(db: Double, target: TimelineSelection) {
        val base = current() ?: return
        val wasPlaying = editor.playing
        val scoped = base.edit.timeline.scoped(target.copy(video = emptySet()))
        val ids = scoped.timeline.audio.filter { it.id in scoped.selection.audio }
        if (ids.none { it.volumeDB != db }) return
        scoped.timeline.audio = scoped.timeline.audio.map { if (it.id in scoped.selection.audio) it.copy(volumeDB = db) else it }
        if (commit(EditState(scoped.timeline, base.edit.outputSize))) {
            clearSelection(); selectedItems = scoped.selection.ids
            if (wasPlaying) editor.togglePlay()
            feedback = "%+.1f dB · Undo available".format(db)
        }
    }

    fun toolScopeTitle(): String {
        selection?.let { return "Selected range · ${editorTime(it.start)}–${editorTime(it.endInclusive)}" }
        if (selectedItems.isNotEmpty()) return "Selected · " + targets().countTitle
        return "Whole project · ${editorTime(edit.duration)}"
    }

    fun jumpBookmark(forward: Boolean) {
        val marks = edit.timeline.resolvedBookmarks
        if (marks.isEmpty()) { editor.pause(); bookmarkHelp = true; return }
        val mark = if (forward) marks.firstOrNull { it.time.seconds > editor.playhead + 0.02 } else marks.lastOrNull { it.time.seconds < editor.playhead - 0.02 }
        mark?.let { editor.seekPaused(it.time.seconds) }
    }

    fun copyOrCut(cut: Boolean) {
        if (markStart != null || (selectedItems.isEmpty() && selection == null)) return
        val scope = targets()
        val base = current() ?: return
        if (cut) {
            val next = base.edit.copy()
            val result = next.timeline.cutToClipboard(scope, EditTime.of(editor.stationaryPlayhead)) ?: return
            if (!commit(next, result.position.seconds)) return
            store.setTrackClipboard(projectID, result.clipboard)
        } else {
            val copied = base.edit.timeline.copied(scope)
            if (copied.isEmpty) return
            editor.pause()
            store.setTrackClipboard(projectID, copied)
        }
        clearSelection()
        feedback = if (cut) "Cut to clipboard" else "Copied to clipboard"
    }

    fun paste(target: TimelinePasteTarget? = null) {
        val contents = clipboard ?: return
        if (contents.isEmpty) return
        val base = current() ?: return
        val state = base.edit.copy()
        val added = state.timeline.paste(contents, EditTime.of(editor.stationaryPlayhead), target)
        if (added.isEmpty || !commit(state, state.timeline.interval(added)?.start?.seconds)) return
        clearSelection(); selectedItems = added.ids
        feedback = "Pasted · Undo available"
    }

    fun addBookmark() {
        val base = current() ?: return
        val next = base.edit.copy()
        next.timeline.addBookmark(EditTime.of(editor.playhead), if (selectedItems.size == 1) selectedItems.first() else null)
        if (store.commit(projectID, next)) feedback = "Bookmark added"
    }

    fun bookmarkThumbnail(mark: ResolvedBookmark): android.graphics.Bitmap? {
        val item = edit.timeline.video.filter { it.interval.contains(mark.time) }.maxByOrNull { it.layer } ?: return null
        val source = item.mapping.source(mark.time - item.start) ?: return null
        val frames = editor.thumbnails[item.content.sourceID] ?: return null
        return (frames.lastOrNull { it.time <= source } ?: frames.firstOrNull())?.image
    }

    fun deleteBookmark(id: UUID) {
        val state = (current() ?: return).edit.copy()
        state.timeline.bookmarks = state.timeline.bookmarks.filterNot { it.id == id }
        commit(state)
    }

    // Swift: scrubbing, a new selection or the Tools sheet dismisses armed removal controls.
    LaunchedEffect(selectedItems, toolsSheet, editor.scrubbing) { removalRegionID = null }

    BackHandler {
        when {
            moveDraft != null -> finishMove(apply = false)
            else -> back()
        }
    }

    // ---------------------------------------------------------------- Views

    @Composable
    fun header(compact: Boolean) {
        // iOS disables the header in Move mode but keeps it white (`.foregroundStyle(.white)`).
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(enabled = moveDraft == null) { back() }.semantics { contentDescription = "Back to projects" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            if (!compact) Text(project.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            if (compact) FullPreviewButton(enabled = edit.duration > 0 && !editor.loading && moveDraft == null, modifier = Modifier.padding(end = 8.dp)) { fullPreview = true }
        }
    }

    @Composable
    fun preview(horizontal: Boolean, modifier: Modifier) {
        Box(modifier.background(Color.Black).clipToBounds()) {
            // The full preview and the Stabilize tool (reviewing a saved level) show this player themselves; one surface owns it at a time.
            if (!fullPreview && toolRequest?.tool != VideoTool.stabilize) PlayerSurface(editor.player, Modifier.fillMaxSize(), framing = editor.framing, titles = editor.titles, layers = editor.layers, captions = editor.subtitles, onPlaybackTap = {
                if (!editor.loading && edit.duration > 0) editor.togglePlay()
            })
            if (!editor.showsPicture) Box(Modifier.fillMaxSize().background(Color.Black))
            if (edit.duration <= 0) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_film), null, tint = EditorColors.secondary, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your timeline is empty", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(if (project.canUndo) "Undo to bring your clips back." else "Record or import a video to get started.",
                        color = EditorColors.secondary, fontSize = 20.sp, textAlign = TextAlign.Center)
                }
            }
            // Review speed capsule (bottom-left).
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                // Disabled without dimming: iOS draws the capsule's label white either way.
                .animateContentSize(),
                verticalAlignment = Alignment.CenterVertically) {
                val enabled = edit.duration > 0 && !editor.loading
                Box(Modifier.widthIn(min = 48.dp).height(44.dp).clickable(enabled = enabled) { playbackSpeedsOpen = !playbackSpeedsOpen }
                    .semantics { contentDescription = "Review speed" }, contentAlignment = Alignment.Center) {
                    Text(speedLabel(editor.playbackSpeed), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = tabular)
                }
                if (playbackSpeedsOpen) for (speed in listOf(1.0, 1.5, 2.0, 2.5, 3.0)) {
                    Box(Modifier.width(44.dp).height(44.dp).clickable { editor.setPlaybackSpeed(speed); playbackSpeedsOpen = false }
                        .semantics { contentDescription = "Review at ${speedLabel(speed)} speed" }, contentAlignment = Alignment.Center) {
                        Text(speedLabel(speed), color = if (editor.playbackSpeed == speed) EditorColors.yellow else Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = tabular)
                    }
                }
            }
            if (editor.loading) {
                Column(Modifier.align(Alignment.Center).background(Color(0.17f, 0.17f, 0.19f, 0.85f), RoundedCornerShape(16.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    // Swift: a determinate bar and "label · N%" while `videoProgress` is known (Applying stabilization).
                    val progress = editor.videoProgress
                    if (progress != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { progress.toFloat() }, color = EditorColors.accent,
                            trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.width(160.dp))
                        Text("${editor.loadingLabel} · ${(progress * 100).toInt()}%", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    } else {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(editor.loadingLabel, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (!horizontal) FullPreviewButton(enabled = edit.duration > 0 && !editor.loading && moveDraft == null,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { fullPreview = true }
        }
    }

    @Composable
    fun transport() {
        val duration = moveDraft?.duration ?: edit.duration
        val position = if (pickingMoveBoundary) moveBrowseTime ?: editor.playhead else editor.playhead
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).alpha(if (editor.loading) 0.35f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Text(editorTime(position), color = Color.White, fontSize = 12.sp, style = tabular, modifier = Modifier.weight(1f))
            BookmarkButton(forward = false) { jumpBookmark(false) }
            ChangeNavigationButton(forward = false, enabled = edit.duration > 0 && editor.navigation.destination(editor.stationaryPlayhead, false) != null) {
                editor.jumpChange(false, it)
            }
            Box(Modifier.size(48.dp).clickable(enabled = edit.duration > 0) { editor.togglePlay() }
                .semantics { contentDescription = if (editor.playing) "Pause playback" else "Play" }, contentAlignment = Alignment.Center) {
                // iOS: disabled on an empty timeline but still white; only change navigation greys out.
                Icon(if (editor.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                    tint = Color.White, modifier = Modifier.size(30.dp))
            }
            ChangeNavigationButton(forward = true, enabled = edit.duration > 0 && editor.navigation.destination(editor.stationaryPlayhead, true) != null) {
                editor.jumpChange(true, it)
            }
            BookmarkButton(forward = true) { jumpBookmark(true) }
            Text(editorTime(duration), color = EditorColors.secondary, fontSize = 12.sp, style = tabular,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }

    @Composable
    fun timeline(height: Double) {
        val shown = moveDraft ?: edit
        val model = EditorTimelineModel(
            edit = shown, sources = project.sources, folder = folder, waves = editor.waveforms, thumbnails = editor.thumbnails,
            playhead = if (pickingMoveBoundary) moveBrowseTime ?: editor.playhead else editor.playhead,
            pointsPerSecond = pointsPerSecond, selected = if (moveDraft == null) selectedTargets() else moveTargets,
            selection = if (moveDraft == null) selection ?: markStart?.let { min(it, editor.playhead)..max(it, editor.playhead) } else null,
            seek = { time ->
                if (pickingMoveBoundary) moveBrowseTime = if (time > (moveDraft?.duration ?: edit.duration)) time else null
                if (editor.scrubbing) { playbackSpeedsOpen = false; editor.seek(time) } else editor.seekPaused(time)
            },
            scrubbing = { editor.setScrubbing(it) }, snapEnabled = snapToCuts, moving = moveDraft != null, moveLayout = moveBase?.timeline,
            moveViewportEnd = moveViewportEnd, boundaryChoices = if (pickingMoveBoundary) moveBoundaries.times else null,
            pickBoundary = { choices -> if (choices.size == 1) placeMove(choices[0]) else if (choices.isNotEmpty()) nearbyMoveBoundaries = choices },
            laneAreaHeight = height - 20, snapTime = moveSnap?.target?.seconds, bookmarks = { bookmarksSheet = true },
            addVideo = if (editor.loading || moveDraft != null) null else { site ->
                editor.pause()
                insertionTime = site.placement(edit.timeline, EditTime.zero).start.seconds
                timelineAddSite = site; toolsSheet = true
            },
            move = { delta, scale -> updateMove(moveDragOrigin + delta, scale = scale) },
            moveEnded = { moveDragOrigin = moveOffset; previewMove() },
            moveCancelled = { updateMove(moveDragOrigin, snapping = false); previewMove() },
            select = { id ->
                selectedItems = id?.let { edit.timeline.selection(of = it).ids } ?: emptySet()
                selection = null; markStart = null; rangeTargets = null
            },
            toggleSelection = { id ->
                editor.pause()
                selectedItems = edit.timeline.toggling(id, if (selection == null && markStart == null) selectedItems else emptySet())
                selection = null; markStart = null; rangeTargets = null
            },
            zoom = { pointsPerSecond = it }, trim = { id, left, delta -> trimItem(id, left, delta) },
            gainEnabled = !editor.loading && moveDraft == null,
            previewGain = { db, target -> editor.auditionGain(db, target) },
            commitGain = { db, target -> commitGain(db, target) },
            focusTransition = { time -> editor.pause(); transitionRemovalArmed = false; editor.seek(time) },
        )
        EditorTimeline(model, Modifier.fillMaxWidth().height(min(height, model.contentHeight).dp))
    }

    @Composable
    fun RowScope.moveActions() {
        val at = moveBoundaries.anchor + EditTime.of(moveOffset)
        val previous = moveBoundaries.previous(at); val next = moveBoundaries.next(at)
        if (pickingMoveBoundary) {
            Icon(Icons.Filled.GpsFixed, null, tint = EditorColors.cyan, modifier = Modifier.padding(end = 4.dp).size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Tap a dashed cyan line on the timeline", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Your selection will start there.", color = EditorColors.secondary, fontSize = 15.sp)
            }
            Box(Modifier.heightIn(min = 44.dp).widthIn(min = 48.dp).clickable { pickingMoveBoundary = false }, contentAlignment = Alignment.Center) {
                Text("Back", color = EditorColors.accent, fontSize = 15.sp)
            }
        } else {
            Column {
                Text(moveBoundaryLabel(at, moveBoundaries.times), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = tabular)
                Text("Selection start", color = EditorColors.secondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            MoveBoundaryIcon(Icons.AutoMirrored.Filled.KeyboardTab, EditorColors.yellow, previous != null, "Move to previous cut", mirrored = true) { previous?.let { placeMove(it) } }
            Spacer(Modifier.width(6.dp))
            MoveBoundaryIcon(Icons.AutoMirrored.Filled.KeyboardTab, EditorColors.yellow, next != null, "Move to next cut") { next?.let { placeMove(it) } }
            Spacer(Modifier.width(6.dp))
            MoveBoundaryIcon(Icons.Filled.GpsFixed, EditorColors.cyan, previous != null || next != null, "Move to a boundary") {
                editor.pause(); moveBrowseTime = null; pickingMoveBoundary = true
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable { finishMove(apply = false) }, contentAlignment = Alignment.Center) {
                Text("Cancel", color = EditorColors.accent, fontSize = 17.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 44.dp).clickable { finishMove(apply = true) }, contentAlignment = Alignment.Center) {
                Text("Done", color = EditorColors.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    fun pasteButton(cuts: List<TimelinePasteTarget>) {
        var choosing by remember { mutableStateOf(false) }
        val position = EditTime.of(editor.playhead)
        val insideTransition = edit.timeline.generated.any { it.interval.start < position && position < it.interval.end }
        val insertsPicture = clipboard?.timeline?.video?.isNotEmpty() == true &&
            (if (cuts.isEmpty()) clipboard.insertsTime else cuts.any { it.anchor is TimelinePasteTarget.Anchor.between })
        val replacesTransition = insertsPicture && (insideTransition || cuts.any { it.transition != null })
        val enabled = !editor.loading && !editor.playing
        Box {
            Row(Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.35f).background(EditorColors.cyan.copy(alpha = 0.12f), RoundedCornerShape(50))
                .clickable(enabled = enabled) { if (cuts.size > 1) choosing = true else paste(cuts.firstOrNull()) }
                .padding(horizontal = 12.dp).semantics { contentDescription = if (replacesTransition) "Paste, replaces transition" else "Paste" },
                verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ContentPaste, null, tint = EditorColors.cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste", color = EditorColors.cyan, fontSize = 15.sp)
                    }
                    if (replacesTransition) Text("Replaces transition", color = EditorColors.cyan, fontSize = 11.sp)
                }
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                for (cut in cuts) DropdownMenuItem(text = { Text(cut.label(edit.timeline)) }, onClick = { choosing = false; paste(cut) })
            }
        }
    }

    /** Swift `effectBubble`: tap edits the effect, touch and hold shows Remove. */
    @Composable
    fun effectBubble(region: ToolRegion, compact: Boolean = false) {
        EffectRemovalBubble(region.label, region.tool.icon, region.tool.tint, armed = removalRegionID == region.id, compact = compact,
            edit = { removalRegionID = null; editor.pause(); toolRequest = request(region) },
            arm = { editor.pause(); removalRegionID = region.id; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            cancel = { removalRegionID = null },
            remove = {
                val base = current()
                removalRegionID = null
                if (base != null) {
                    val state = base.edit.removingTool(request(region))
                    if (state != base.edit && commit(state)) feedback = "Removed " + region.label + " · Undo available"
                }
            })
    }

    /** Swift: the join under the playhead (`cutLayout.boundary`, else the picture cut there), when a transition fits. */
    fun joinAtPlayhead(cuts: List<TimelinePasteTarget>): TransitionJoin? {
        if (moveDraft != null) return null
        val join = cutLayout.boundary(editor.playhead) ?: cuts.firstOrNull { it.kind == TimelinePasteTarget.Kind.picture }?.let { cut ->
            val anchor = cut.anchor as? TimelinePasteTarget.Anchor.between ?: return@let null
            cutLayout.joins.firstOrNull { it.incomingID == anchor.right && it.outgoingID == anchor.left }
        }
        return join?.takeIf { it.maximum >= TimelineEditing.frame }
    }

    @Composable
    fun selectionStatus() {
        val cuts = if (moveDraft == null && !editor.playing) pasteTargets.filter { it.isAt(EditTime.of(editor.playhead)) } else emptyList()
        // Swift: the regions at the playhead (a Placement by its exact overlap interval).
        val timelineRegions = edit.toolRegions
        val active = timelineRegions.filter { region ->
            if (region.tool == VideoTool.placement) EditInterval.of(region.range.start, region.range.endInclusive).contains(EditTime.of(editor.playhead))
            else editor.playhead in region.range
        }
        val placementRegion = active.firstOrNull { it.tool == VideoTool.placement }
        val armed = removalRegionID?.let { id -> timelineRegions.firstOrNull { it.id == id } }
        // Swift `.frame(minHeight: 48).padding(.vertical, 6)`: the row is always at least 60 points, so the
        // join chip or the paste button appearing at the playhead never moves the timeline.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (moveDraft != null) {
                moveActions()
            } else if (armed != null) {
                effectBubble(armed)
            } else if (selectedItems.isNotEmpty() || selection != null || markStart != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val scopeNow = targets()
                    Box {
                        Column(Modifier.widthIn(min = 44.dp, max = 120.dp).heightIn(min = 48.dp).clickable { selectionMenu = true }
                            .semantics { contentDescription = "Selection actions: " + scopeNow.title }, verticalArrangement = Arrangement.Center) {
                            Text(EditorOperations.selectionTitle(project, edit, scopeNow), color = EditorColors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (markStart != null) "Choose end" else selection?.let { editorTime(it.endInclusive - it.start) } ?: scopeNow.countTitle,
                                color = EditorColors.accent.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Swift Menu: Copy, Cut, Clear clipboard, Restore audio sync (Preview cut is not ported).
                        DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, enabled = markStart == null,
                                onClick = { selectionMenu = false; copyOrCut(false) })
                            DropdownMenuItem(text = { Text("Cut") }, leadingIcon = { Icon(Icons.Outlined.ContentCut, null) }, enabled = markStart == null,
                                onClick = { selectionMenu = false; copyOrCut(true) })
                            if (clipboard != null) DropdownMenuItem(text = { Text("Clear clipboard") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
                                onClick = { selectionMenu = false; store.setTrackClipboard(projectID, null) })
                            val sound = scopeNow.audio.singleOrNull()
                            val offset = sound?.let { edit.timeline.syncOffset(it) }
                            if (sound != null && offset != null && offset != EditTime.zero) {
                                DropdownMenuItem(text = { Text("Restore audio sync (%+.2f s)".format(offset.seconds)) }, leadingIcon = { Icon(Icons.Filled.Sync, null) },
                                    onClick = {
                                        selectionMenu = false
                                        val next = (current() ?: return@DropdownMenuItem).edit.copy()
                                        next.timeline.restoreSync(sound); commit(next)
                                    })
                            }
                        }
                    }
                    ClipboardButton(cut = false, enabled = markStart == null) { copyOrCut(false) }
                    ClipboardButton(cut = true, enabled = markStart == null) { copyOrCut(true) }
                    Spacer(Modifier.width(4.dp))
                    IconAction(Icons.Outlined.Delete, if (scopeNow.video.isEmpty() || scopeNow.audio.isEmpty()) "Delete, leave gap" else "Delete selection", EditorColors.accent) { removeSelection() }
                    IconAction(Icons.Filled.Close, "Clear selection", Color.White.copy(alpha = 0.8f)) { clearSelection(); feedback = "Selection cleared" }
                    if (clipboard != null) pasteButton(cuts)
                    // Swift `compactPlacement`.
                    if (placementRegion != null) effectBubble(placementRegion, compact = true)
                }
            } else if (clipboard != null || joinAtPlayhead(cuts) != null || active.isNotEmpty()) {
                // Swift `effectChips`: the clipboard, the join's transition, the Placement, then the other effects here.
                val join = joinAtPlayhead(cuts)
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (clipboard != null) {
                        pasteButton(cuts)
                        IconAction(Icons.Filled.Close, "Clear clipboard", Color.White) { store.setTrackClipboard(projectID, null) }
                    }
                    if (join != null) TransitionJoinChip(join, transitionRemovalArmed && join.transition != null,
                        edit = { transitionRemovalArmed = false; editor.pause(); editor.seek(join.time); toolRequest = transitionRequest(join) },
                        arm = { editor.pause(); transitionRemovalArmed = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        cancelRemoval = { transitionRemovalArmed = false },
                        remove = {
                            transitionRemovalArmed = false
                            val base = current() ?: return@TransitionJoinChip
                            val next = base.edit.restoringStraightCut(join.index)
                            if (next != base.edit && commit(next, join.time)) feedback = "${join.transition?.style?.title ?: "Transition"} removed · Undo available"
                        })
                    if (placementRegion != null) effectBubble(placementRegion)
                    for (region in active.filter { it.tool != VideoTool.placement && (join == null || it.effectID != join.transition?.id) }) effectBubble(region)
                }
            } else if (edit.timeline.gap(EditTime.of(editor.playhead)) != null) {
                Row(Modifier.heightIn(min = 48.dp).clickable {
                    val base = current() ?: return@clickable
                    val next = base.edit.copy()
                    val cut = next.timeline.gap(EditTime.of(editor.stationaryPlayhead))?.start?.seconds
                    next.timeline.closeGap(EditTime.of(editor.stationaryPlayhead))
                    commit(next, cut)
                }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Compress, null, tint = EditorColors.accent, modifier = Modifier.size(18.dp).rotate(90f))
                    Spacer(Modifier.width(6.dp))
                    Text("Close gap", color = EditorColors.accent, fontSize = 15.sp)
                }
            } else {
                Text(feedback ?: "Scroll to scrub · pinch to zoom", color = EditorColors.secondary, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
        }
    }

    @Composable
    fun editTools(compact: Boolean) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).alpha(if (editor.loading && moveDraft == null) 0.35f else 1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (moveDraft != null) {
                for (value in TimelineMoveScope.entries) {
                    MoveScopeButton(value, chosen = value == moveScope, compact = compact, enabled = moveChoices.available(value), modifier = Modifier.weight(1f)) { changeMoveScope(value) }
                }
            } else {
                val markTitle = if (selection != null) "Move end" else if (markStart == null) "Mark start" else "Mark end"
                ToolTile(markTitle, compact, enabled = edit.duration > 0, modifier = Modifier.weight(1f),
                    icon = { if (markStart == null) DashedRectangleGlyph() else Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }) { mark() }
                ToolTile("Split", compact, enabled = edit.duration > 0, modifier = Modifier.weight(1f), icon = { SplitGlyph() }) { split() }
                ToolTile("Tools", compact, modifier = Modifier.weight(1f), icon = { Icon(Icons.Outlined.GridView, null, tint = Color.White, modifier = Modifier.size(18.dp)) }) {
                    editor.pause(); toolsSheet = true
                }
                ToolTile("Export", compact, fill = EditorColors.exportGreen, enabled = edit.duration > 0, modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Outlined.IosShare, null, tint = Color.White, modifier = Modifier.size(18.dp)) }) { editor.pause(); export() }
            }
        }
    }

    @Composable
    fun comparison() {
        val target = selectedTargets()
        val volumeScope = targets()
        val linked = edit.timeline.isSingleLink(target)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp).alpha(if (editor.loading && moveDraft == null) 0.35f else 1f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconAction(Icons.AutoMirrored.Filled.Undo, "Undo", Color.White, enabled = project.canUndo && moveDraft == null, size = 44.dp) { history(false) }
            IconAction(Icons.AutoMirrored.Filled.Redo, "Redo", Color.White, enabled = project.canRedo && moveDraft == null, size = 44.dp) { history(true) }
            SnapButton(snapToCuts, explain = {
                editor.error = "Snap aligns the playhead and moved parts with nearby clip edges and bookmarks. Turn the magnet off for free positioning. The one-frame Move arrows always remain precise."
            }) {
                snapToCuts = !snapToCuts; moveSnap = null
                preferences(context).edit().putBoolean("snapToCuts", snapToCuts).apply()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            Spacer(Modifier.width(4.dp))
            ToolTile(if (linked) "Unlink" else "Link", compact = true, enabled = moveDraft == null, modifier = Modifier.weight(1f),
                icon = { Icon(if (linked) Icons.Filled.Link else Icons.Filled.AddLink, null, tint = Color.White, modifier = Modifier.size(18.dp)) }) { toggleLink() }
            ToolTile("Move", compact = true, enabled = !target.isEmpty && moveDraft == null, modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Filled.Height, null, tint = Color.White, modifier = Modifier.size(18.dp).rotate(90f)) }) { beginMove() }
            QuickVolumeControl(
                value = AudioGainGrid.common(edit.timeline.audio.filter { it.id in volumeScope.audio }),
                enabled = volumeScope.audio.isNotEmpty() && moveDraft == null && !editor.loading,
                tap = { editor.pause(); toolRequest = makeVolumeRequest() },
            )
        }
    }

    @Composable
    fun controls(compact: Boolean, timelineHeight: Double?) {
        Column(Modifier.background(EditorColors.surface)) {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { transport() }
            if (timelineHeight != null) { timeline(timelineHeight); selectionStatus() }
            editTools(compact)
            comparison()
            editor.notice?.let { Text(it, color = EditorColors.orange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            Spacer(Modifier.height(8.dp))
        }
    }

    fun portraitTimelineHeight(): Double {
        val lanes = timelineLanes((moveDraft ?: edit).timeline, if (moveDraft == null) null else moveTargets)
        val rows = (lanes.maxOfOrNull { it.row } ?: 1) + 1
        // Keep six full-size usable lanes visible on the taller portrait canvas.
        return max(164.0, 20 + min(6, rows) * 36.0)
    }

    // iPhone landscape: no status bar and a symmetric safe area on both sides.
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    LifecycleResumeEffect(landscape) {
        val window = (view.context as? android.app.Activity)?.window
            ?: ((view.context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) controller?.hide(WindowInsetsCompat.Type.statusBars())
        onPauseOrDispose { if (landscape) controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val side = max(safe.calculateLeftPadding(direction).value, safe.calculateRightPadding(direction).value).dp
    val insets = if (landscape) PaddingValues(start = side, end = side, top = safe.calculateTopPadding(), bottom = safe.calculateBottomPadding()) else safe

    // One root, so the full-screen overlays below stack over the editor in any parent layout.
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).padding(insets)) {
        val horizontal = maxWidth > maxHeight; val screenWidth = maxWidth
        Column(Modifier.fillMaxSize()) {
            if (!horizontal) header(compact = false)
            if (horizontal) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        preview(horizontal = true, Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.TopStart).fillMaxWidth()) { header(compact = true) }
                    }
                    Box(Modifier.width(screenWidth * 0.52f)) { controls(compact = true, timelineHeight = null) }
                }
                timeline(164.0)
                Box(Modifier.background(Color.Black)) { selectionStatus() }
            } else {
                preview(horizontal = false, Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp))
                controls(compact = false, timelineHeight = portraitTimelineHeight())
            }
        }
    }

    // ------------------------------------------------------------- Overlays

    if (fullPreview) FullEditorPreview(editor, edit.duration, edit.timeline.resolvedBookmarks, close = { fullPreview = false },
        bookmarkHelp = { bookmarkHelp = true }, addBookmark = { addBookmark() })
    toolRequest?.let { request ->
        if (request.tool == VideoTool.crop) {
            CropEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.videoEffects) {
            VideoEffectsEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.transition) {
            TransitionEditor(project = project, folder = folder, request = request, cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.text) {
            TextToolEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.image) {
            ImageToolEditor(project = project, folder = folder, request = request,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.placement) {
            PlacementEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.subtitles) {
            SubtitleEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.censorAudio) {
            CensorAudioEditor(project = project, folder = folder, request = request,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        } else if (request.tool == VideoTool.speed || request.tool == VideoTool.mirror || request.tool == VideoTool.pauses || request.tool == VideoTool.color ||
            request.tool == VideoTool.stabilize) {
            ToolEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) }, editor = editor)
        } else {
            AudioToolEditor(project = project, folder = folder, request = request, position = editor.playhead,
                cancel = { toolRequest = null }, apply = { applyTool(it, request) })
        }
    }
    if (toolsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val close = { toolsSheet = false; showingAddVideo = false; timelineAddSite = null; coverPicker = null }
        // iOS `.presentationDetents([.large])`: the sheet stops below the status bar.
        ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = Color(0.06f, 0.06f, 0.06f),
            modifier = Modifier.statusBarsPadding(), dragHandle = { SheetGrabber() }) {
            val cover = coverPicker
            if (cover != null) Box(Modifier.fillMaxHeight()) {
                CoverPicker(cover, back = { coverPicker = null })
            } else if (timelineAddSite != null || showingAddVideo) Box(Modifier.fillMaxHeight()) {
                val pasteTarget = when (val site = timelineAddSite) {
                    VideoInsertionSite.start -> pasteTargets.firstOrNull { it.kind != TimelinePasteTarget.Kind.project && it.anchor == TimelinePasteTarget.Anchor.start }
                    VideoInsertionSite.end -> pasteTargets.firstOrNull { it.kind != TimelinePasteTarget.Kind.project && it.anchor == TimelinePasteTarget.Anchor.end }
                    is VideoInsertionSite.gap -> TimelinePasteTarget(TimelinePasteTarget.Kind.picture, site.after, TimelinePasteTarget.Anchor.gap)
                    null -> null
                }
                // A cut can leave an empty project. Its start + still pastes at zero.
                AddVideoPanel(store = store, project = project, position = insertionTime, site = timelineAddSite,
                    pasteHere = if (timelineAddSite != null && clipboard != null) { { close(); paste(pasteTarget) } } else null,
                    close = if (timelineAddSite == null) { { showingAddVideo = false } } else close,
                    backToTools = timelineAddSite == null,
                    record = {
                        val site = timelineAddSite; val time = insertionTime
                        close(); recordHere(EditTime.of(time), site)
                    },
                    inserted = { count, start ->
                        close()
                        current()?.let { updated -> finishEdit(updated.edit, project, start) }
                        feedback = if (count == 1) "Clip inserted · Undo available" else "$count clips inserted · Undo available"
                    })
            } else {
                ToolsPanel(
                    snapToCuts = snapToCuts, setSnapToCuts = { snapToCuts = it; preferences(context).edit().putBoolean("snapToCuts", it).apply() },
                    matchListeningLevels = matchListeningLevels,
                    setMatchListeningLevels = { matchListeningLevels = it; preferences(context).edit().putBoolean("matchListeningLevels", it).apply() },
                    scope = toolScopeTitle(),
                    overlayScope = if (selection == null) "At ${editorTime(editor.playhead)} · 3 s long" else toolScopeTitle(),
                    insertionPosition = editor.playhead,
                    choose = { kind ->
                        when (kind) {
                            VideoTool.addVideo -> { insertionTime = editor.playhead; showingAddVideo = true }
                            VideoTool.videoEffects, VideoTool.crop, VideoTool.speed, VideoTool.text, VideoTool.image, VideoTool.mirror, VideoTool.pauses, VideoTool.color,
                            VideoTool.stabilize, VideoTool.censorAudio, VideoTool.enhanceAudio, VideoTool.subtitles -> {
                                editor.pause(); close(); toolRequest = makeToolRequest(kind)
                            }
                            VideoTool.addAudio -> { editor.pause(); insertionTime = editor.playhead; close(); addAudioSheet = true }
                            else -> {}
                        }
                    },
                    close = close,
                    regions = uniqueEffects(),
                    canAdjust = edit.duration > 0,
                    hasVideo = targets().video.isNotEmpty(),
                    hasAudio = targets().audio.isNotEmpty(),
                    canCensor = selection != null,
                    canTranscribe = edit.timeline.audio.isNotEmpty() || edit.timeline.subtitles != null,
                    // Swift `coverAction`: the selection's scope, or the whole project.
                    selectCover = editor.canvas?.takeIf { edit.timeline.video.isNotEmpty() && !editor.loading }?.let { canvas ->
                        {
                            val scope = if (selection == null && selectedItems.isEmpty()) TimelineSelection() else targets()
                            coverPicker = CoverPickerSource(project.copy(), folder, scope, canvas)
                        }
                    },
                    edit = { region -> editor.pause(); close(); toolRequest = edit.request(region, selectedTargets()) },
                )
            }
        }
    }
    if (addAudioSheet) {
        // Swift `.sheet(isPresented: $addAudioSheet) { AudioImportEditor(…) }`.
        val close = { addAudioSheet = false }
        ModalBottomSheet(onDismissRequest = close, containerColor = Color(0.06f, 0.06f, 0.06f), dragHandle = { SheetGrabber() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AudioImportEditor(store = store, projectID = projectID, position = insertionTime, close = close, inserted = {
                addAudioSheet = false
                current()?.let { updated -> finishEdit(updated.edit, project, insertionTime) }
                feedback = "Audio added · Undo available"
            })
        }
    }
    if (bookmarkHelp) BookmarkHelpView { bookmarkHelp = false }
    if (bookmarksSheet) {
        // Swift `bookmarkPanel`: opened from a cluster of marks on the ruler.
        val close = { bookmarksSheet = false }
        ModalBottomSheet(onDismissRequest = close, containerColor = Color(0.06f, 0.06f, 0.06f), dragHandle = { SheetGrabber() }) {
            SheetBar("Bookmarks", trailing = "Done", trailingBold = true, trailingAction = close)
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { addBookmark() }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BookmarkAdd, null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Add at ${editorTime(editor.playhead)}", color = EditorColors.accent, fontSize = 17.sp)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f), modifier = Modifier.padding(start = 20.dp))
                }
                items(edit.timeline.resolvedBookmarks, key = { it.id.uuidString }) { mark ->
                    BookmarkRow(mark, bookmarkThumbnail(mark),
                        open = { editor.seek(mark.time.seconds); close() },
                        rename = { bookmarkName = mark.bookmark.label; renamingBookmark = mark.id },
                        delete = { deleteBookmark(mark.id) })
                }
            }
        }
    }
    renamingBookmark?.let { id ->
        AlertDialog(onDismissRequest = { renamingBookmark = null }, title = { Text("Bookmark label") },
            text = { OutlinedTextField(bookmarkName, { bookmarkName = it }, label = { Text("Label") }, singleLine = true) },
            dismissButton = { TextButton({ renamingBookmark = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton({
                    val state = current()?.edit?.copy()
                    if (state != null && state.timeline.bookmarks.any { it.id == id }) {
                        state.timeline.bookmarks = state.timeline.bookmarks.map { if (it.id == id) it.copy(label = bookmarkName.take(120)) else it }
                        store.commit(projectID, state)
                    }
                    renamingBookmark = null
                }) { Text("Save") }
            })
    }
    if (nearbyMoveBoundaries.isNotEmpty()) {
        AlertDialog(onDismissRequest = { nearbyMoveBoundaries = emptyList() }, title = { Text("Choose a start time") },
            text = {
                Column {
                    for (time in nearbyMoveBoundaries) {
                        Text(moveBoundaryLabel(time, nearbyMoveBoundaries), style = tabular, color = Color.White,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { nearbyMoveBoundaries = emptyList(); placeMove(time) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton({ nearbyMoveBoundaries = emptyList() }) { Text("Back") } })
    }
    pendingLink?.let { plan ->
        AlertDialog(onDismissRequest = { pendingLink = null; pendingLinkBase = null }, title = { Text("Link shared time only?") },
            text = {
                plan.intersection?.let {
                    Text("Video and audio cover different times. Split at ${editorTime(it.start.seconds)} and ${editorTime(it.end.seconds)}, then link the shared interval? The outside parts stay separate. Nothing is deleted or moved.")
                }
            },
            dismissButton = { TextButton({ pendingLink = null; pendingLinkBase = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton({
                    if (pendingLinkBase == current()?.edit) applyLink(plan.selection, intersectionOnly = true)
                    else editor.error = "The timeline changed. Tap Link again to review the current selection."
                    pendingLink = null; pendingLinkBase = null
                }) { Text("Link intersection") }
            })
    }
    editor.error?.let { message ->
        AlertDialog(onDismissRequest = { editor.error = null }, title = { Text("Editor") }, text = { Text(message) },
            confirmButton = { TextButton({ editor.error = null }) { Text("OK") } })
    }
    }
}

internal fun speedLabel(speed: Double): String = (if (speed == speed.toLong().toDouble()) speed.toLong().toString() else speed.toString()) + "×"

// ------------------------------------------------------------------ Controls

/** One row of the bookmarks sheet: tap to go there, long-press to rename, swipe left to delete (Swift context menu and swipe action). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(mark: ResolvedBookmark, thumbnail: android.graphics.Bitmap?, open: () -> Unit, rename: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(swipe, enableDismissFromStartToEnd = false, onDismiss = { delete() }, backgroundContent = {
        Box(Modifier.fillMaxSize().background(EditorColors.accent).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
            Text("Delete", color = Color.White, fontSize = 17.sp)
        }
    }) {
        Box {
            Row(Modifier.fillMaxWidth().background(Color(0.06f, 0.06f, 0.06f)).heightIn(min = 60.dp)
                .combinedClickable(onClick = open, onLongClick = { menu = true }, onLongClickLabel = "Rename")
                .padding(horizontal = 20.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                if (thumbnail != null) {
                    androidx.compose.foundation.Image(thumbnail.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(5.dp)))
                } else {
                    Icon(Icons.Filled.Bookmark, null, tint = EditorColors.orange, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(mark.bookmark.label.ifEmpty { "Bookmark" }, color = Color.White, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(editorTime(mark.time.seconds), color = EditorColors.secondary, fontSize = 12.sp, style = tabular)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; rename() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; delete() })
            }
        }
    }
}

@Composable
internal fun SheetGrabber() {
    Box(Modifier.padding(top = 6.dp, bottom = 4.dp).size(36.dp, 5.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(50)))
}

@Composable
private fun FullPreviewButton(enabled: Boolean, modifier: Modifier = Modifier, open: () -> Unit) {
    // iOS's plain button does dim this one when disabled, to about 55% (see the 09 reference).
    Box(modifier.size(44.dp).alpha(if (enabled) 1f else 0.55f).clickable(enabled = enabled, onClick = open)
        .semantics { contentDescription = "Full" }, contentAlignment = Alignment.Center) {
        // A crisp black outline around the white glyph, without a button plate.
        for (index in 0 until 8) {
            val angle = index * Math.PI / 4
            Icon(Icons.Filled.OpenInFull, null, tint = Color.Black, modifier = Modifier.size(26.dp)
                .offset(x = (Math.cos(angle) * 1.25).dp, y = (Math.sin(angle) * 1.25).dp))
        }
        Icon(Icons.Filled.OpenInFull, null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun BookmarkButton(forward: Boolean, action: () -> Unit) {
    Box(Modifier.size(44.dp, 48.dp).clickable(onClick = action).semantics { contentDescription = if (forward) "Next bookmark" else "Previous bookmark" },
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.BookmarkBorder, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Icon(if (forward) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft, null, tint = Color.White,
            modifier = Modifier.size(10.dp).offset(x = if (forward) 13.dp else (-13).dp, y = 2.dp))
    }
}

/// A hold fires only effects-only navigation, never an additional cut jump when the finger lifts.
@Composable
internal fun ChangeNavigationButton(forward: Boolean, enabled: Boolean, action: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.size(44.dp, 48.dp).semantics { contentDescription = if (forward) "Next change" else "Previous change" }
        .pointerInput(enabled) {
            if (enabled) detectTapGestures(onTap = { action(false) }, onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); action(true) })
        }, contentAlignment = Alignment.Center) {
        Icon(if (forward) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious, null,
            tint = if (enabled) Color.White else EditorColors.tertiary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, tint: Color, enabled: Boolean = true, size: Dp = 44.dp, action: () -> Unit) {
    Box(Modifier.size(size, if (size == 44.dp) 44.dp else 48.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = action)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) tint else EditorColors.secondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ClipboardButton(cut: Boolean, enabled: Boolean, action: () -> Unit) {
    Column(Modifier.alpha(if (enabled) 1f else 0.35f).heightIn(min = 44.dp).widthIn(min = 46.dp).background(EditorColors.cyan.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = action)
        .padding(horizontal = 6.dp, vertical = 3.dp).semantics { contentDescription = if (cut) "Cut selection" else "Copy selection" },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (cut) Icons.Outlined.ContentCut else Icons.Outlined.ContentCopy, null, tint = EditorColors.cyan, modifier = Modifier.size(22.dp))
        Text(if (cut) "Cut" else "Copy", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MoveBoundaryIcon(icon: ImageVector, tint: Color, enabled: Boolean, label: String, mirrored: Boolean = false, action: () -> Unit) {
    Box(Modifier.size(44.dp).alpha(if (enabled) 1f else 0.35f).background(tint.copy(alpha = 0.14f), CircleShape).clickable(enabled = enabled, onClick = action)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { if (mirrored) scaleX = -1f })
    }
}

@Composable
internal fun ToolTile(title: String, compact: Boolean, modifier: Modifier = Modifier, fill: Color = Color.White.copy(alpha = 0.07f),
                      enabled: Boolean = true, icon: @Composable () -> Unit, action: () -> Unit) {
    val label = if (compact) when (title) { "Mark start" -> "Start"; "Mark end", "Move end" -> "End"; "Preview cut" -> "Preview"; else -> title } else title
    Column(modifier.alpha(if (enabled) 1f else 0.35f).background(fill, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = action)
        .heightIn(min = if (compact) 44.dp else 56.dp).padding(vertical = if (compact) 2.dp else 4.dp).semantics { contentDescription = title },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        icon()
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, textAlign = TextAlign.Center)
    }
}

/** SF Symbol `rectangle.dashed`. */
@Composable
internal fun DashedRectangleGlyph(color: Color = Color.White, modifier: Modifier = Modifier.size(18.dp, 14.dp)) {
    Canvas(modifier) {
        drawRoundRect(color, Offset(1.dp.toPx(), 2.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(2.dp.toPx()),
            style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 1.6.dp.toPx()))))
    }
}

/** SF Symbol `square.split.2x1`. */
@Composable
private fun SplitGlyph() {
    Canvas(Modifier.size(16.dp, 16.dp)) {
        drawRoundRect(Color.White, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(3.dp.toPx()), style = Stroke(1.4.dp.toPx()))
        drawLine(Color.White, Offset(size.width / 2, 1.dp.toPx()), Offset(size.width / 2, size.height - 1.dp.toPx()), 1.4.dp.toPx())
    }
}

/** Swift `SnapMagnet`. */
@Composable
private fun SnapButton(enabled: Boolean, explain: () -> Unit, toggle: () -> Unit) {
    val tint = if (enabled) EditorColors.yellow else EditorColors.secondary
    Box(Modifier.size(44.dp).background(if (enabled) EditorColors.yellow.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(12.dp))
        .pointerInput(enabled) { detectTapGestures(onTap = { toggle() }, onLongPress = { explain() }) }
        .semantics { contentDescription = "Snap to cuts, " + if (enabled) "On" else "Off" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24
            rotate(-25f) {
                val path = Path().apply {
                    moveTo(5 * u, 8 * u); lineTo(5 * u, 14 * u)
                    cubicTo(5 * u, 24 * u, 19 * u, 24 * u, 19 * u, 14 * u)
                    lineTo(19 * u, 8 * u)
                }
                drawPath(path, tint, style = Stroke(4 * u, cap = StrokeCap.Butt))
                drawRect(Color.White.copy(alpha = if (enabled) 0.95f else 0.5f), Offset(3 * u, 3 * u), Size(4 * u, 3 * u))
                drawRect(Color.White.copy(alpha = if (enabled) 0.95f else 0.5f), Offset(17 * u, 3 * u), Size(4 * u, 3 * u))
                if (!enabled) drawLine(tint, Offset(1 * u, 23 * u), Offset(23 * u, 1 * u), 1.5f * u, StrokeCap.Round)
            }
        }
    }
}

/** Swift `MoveScopeButton`: a three-block glyph above the scope name. */
@Composable
private fun MoveScopeButton(value: TimelineMoveScope, chosen: Boolean, compact: Boolean, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    val title = when (value) {
        TimelineMoveScope.selected -> "Selected only"; TimelineMoveScope.left -> "With left"
        TimelineMoveScope.right -> "With right"; TimelineMoveScope.both -> "Both sides"
    }
    val color = if (chosen) EditorColors.yellow else Color.White
    Column(modifier.alpha(if (enabled) 1f else 0.35f)
        .background(if (chosen) EditorColors.yellow.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
        .then(if (chosen) Modifier.border(1.5.dp, EditorColors.yellow, RoundedCornerShape(12.dp)) else Modifier)
        .clickable(enabled = enabled, onClick = action).heightIn(min = if (compact) 44.dp else 56.dp).padding(vertical = if (compact) 0.dp else 4.dp)
        .semantics { contentDescription = title },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Canvas(Modifier.size(38.dp, 19.dp)) {
            val u = size.width / 38
            for (index in 0..2) {
                val center = index == 1
                val tile = androidx.compose.ui.geometry.Rect(Offset((index * 13 + 1) * u, (if (center) 1 else 3) * u), Size(10 * u, (if (center) 14 else 10) * u))
                if (center || (if (index == 0) value.includesLeft else value.includesRight)) {
                    drawRoundRect(if (center) EditorColors.yellow else Color.White, tile.topLeft, tile.size, CornerRadius(2 * u))
                } else drawRoundRect(Color.White.copy(alpha = 0.55f), tile.topLeft, tile.size, CornerRadius(2 * u), style = Stroke(u))
                if (center) drawRoundRect(Color.Black.copy(alpha = 0.75f), Offset(tile.center.x - u, 5 * u), Size(2 * u, 6 * u), CornerRadius(u))
            }
            val firstX = if (value.includesLeft) 1f else 14f
            val lastX = if (value.includesRight) 37f else 24f
            drawRoundRect(Color.White, Offset(firstX * u, 17 * u), Size((lastX - firstX) * u, 2 * u), CornerRadius(u))
        }
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/**
 * Swift `PlayerSurface`/`PlayerLayerView`: the player's picture, aspect-fit on
 * black. Tap plays or pauses; pinch (and drag while zoomed) inspects the
 * picture without changing the export. With a [framing], the picture is drawn
 * inside the project canvas exactly as the export places it: the source-sized
 * video view is moved, scaled and rotated by the framing transform and clipped
 * to the canvas window. While placed videos overlap, [layers] are the pictures
 * behind the front one, and every picture is moved into its placement window
 * and clipped to its visible part (Swift `TrackRenderer.placed`).
 */
@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier, framing: PreviewFraming? = null, inspectionZoom: Boolean = true,
                  onPlaybackTap: (() -> Unit)? = null, titles: List<SubtitleLayout.Frame> = emptyList(),
                  layers: List<EditorController.PreviewLayer> = emptyList(), captions: List<SubtitleLayout.Frame> = emptyList(),
                  captionPreview: CaptionPreview? = null) {
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds()) {
        val bounds = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val window = framing?.let { aspectFitRect(it.canvas, CGRect(0.0, 0.0, bounds.width.toDouble(), bounds.height.toDouble())) }
        fun clamp(value: Offset, scale: Float): Offset {
            val fit = if (window != null) Size(window.width.toFloat(), window.height.toFloat()) else {
                val video = player.videoSize
                val aspect = if (video.width > 0 && video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else bounds.width / max(1f, bounds.height)
                if (bounds.width / max(1f, bounds.height) > aspect) Size(bounds.height * aspect, bounds.height) else Size(bounds.width, bounds.width / aspect)
            }
            val limitX = max(0f, (fit.width * scale - bounds.width) / 2); val limitY = max(0f, (fit.height * scale - bounds.height) / 2)
            return Offset(value.x.coerceIn(-limitX, limitX), value.y.coerceIn(-limitY, limitY))
        }
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }) {
            // The canvas window: everything outside it is black, like the exported bars.
            val windowModifier = if (window == null) Modifier.fillMaxSize() else Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(max(1, Math.round(window.width).toInt()), max(1, Math.round(window.height).toInt())))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(Math.round(window.minX).toInt(), Math.round(window.minY).toInt()) }
            }
            Box(windowModifier) {
                // Swift `renderTracks`: the pictures behind the front one first, deepest first.
                for (layer in layers.asReversed()) key(layer.player) { PlacedPicture(layer.player, layer.framing, window, layer.titles) }
                PlacedPicture(player, framing, window, titles)
                // Swift `VideoToolCompositor`: subtitles over the finished picture, in canvas coordinates.
                if (framing != null && window != null && captions.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val k = (window.width / framing.canvas.width).toFloat()
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.scale(k, k)
                        VideoOverlayRenderer.draw(canvas.nativeCanvas, captions, framing.canvas)
                        canvas.nativeCanvas.restore()
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()
            .pointerInput(onPlaybackTap) { detectTapGestures { onPlaybackTap?.invoke() } }
            .then(if (inspectionZoom) Modifier.pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val next = (zoom * gestureZoom).coerceIn(1f, 8f)
                    val ratio = next / zoom
                    val center = Offset(bounds.width / 2, bounds.height / 2)
                    // Keep the point under the fingers fixed while scaling.
                    val anchored = (centroid - center) - (centroid - center - offset) * ratio
                    zoom = next
                    offset = clamp(anchored + pan, next)
                }
            } else Modifier)
            .semantics { contentDescription = "Video preview" })
        // Above the inspection layer, so a gesture that starts on the caption moves it (Swift `positionCaption`).
        if (framing != null && window != null && captionPreview != null) {
            // The window as drawn: graphicsLayer scales about the centre, then translates.
            val cx = bounds.width / 2.0; val cy = bounds.height / 2.0
            val shown = CGRect(cx + (window.minX - cx) * zoom + offset.x, cy + (window.minY - cy) * zoom + offset.y, window.width * zoom, window.height * zoom)
            CaptionHandle(captionPreview, shown)
        }
        if (zoom > 1.001f) {
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(48.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .clickable { zoom = 1f; offset = Offset.Zero }.semantics { contentDescription = "Reset preview zoom" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CloseFullscreen, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Swift `CaptionPreview`: the subtitle envelope (normalized, top-left) and
 * the placement gesture it forwards.
 */
data class CaptionPreview(
    val envelope: CGRect,
    val value: String,
    val editable: Boolean = true,
    val change: (VideoEffectPlacementGesture) -> Unit,
)

/**
 * Swift `PlayerLayerView.captionHandle`/`captionGuide`: a hit area over the
 * caption envelope (at least 44 points); a drag moves the whole series, a
 * pinch that starts on it resizes it about the fingers. The cyan outline is
 * shown while manipulating.
 */
@Composable
private fun CaptionHandle(captions: CaptionPreview, window: CGRect) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(captions)
    var manipulating by remember { mutableStateOf(false) }
    val box = CGRect(captions.envelope.x * window.width, captions.envelope.y * window.height, captions.envelope.width * window.width, captions.envelope.height * window.height)
    val minimum = with(density) { 44.dp.toPx() }.toDouble()
    val slop = with(density) { 10.dp.toPx() }.toDouble()
    val padX = max(0.0, (minimum - box.width) / 2) + slop
    val padY = max(0.0, (minimum - box.height) / 2) + slop
    if (manipulating) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Cyan, Offset(box.x.toFloat(), box.y.toFloat()), Size(box.width.toFloat(), box.height.toFloat()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
    if (!captions.editable) return
    Box(Modifier.offset { IntOffset(Math.round(box.x - padX).toInt(), Math.round(box.y - padY).toInt()) }
        .size(with(density) { (box.width + 2 * padX).toFloat().toDp() }, with(density) { (box.height + 2 * padY).toFloat().toDp() })
        .pointerInput(window) {
            detectTransformGestures(onGesture = { centroid, pan, zoom, _ ->
                manipulating = true
                val origin = Offset((box.x - padX).toFloat(), (box.y - padY).toFloat())
                val to = CGPoint((origin.x + centroid.x) / window.width, (origin.y + centroid.y) / window.height)
                if (zoom != 1f) {
                    val from = CGPoint(to.x - pan.x / window.width, to.y - pan.y / window.height)
                    current.change(VideoEffectPlacementGesture.pinch(zoom.toDouble(), from, to))
                } else if (pan != Offset.Zero) current.change(VideoEffectPlacementGesture.pan(CGPoint(pan.x / window.width, pan.y / window.height)))
            })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                if (manipulating) { manipulating = false; current.change(VideoEffectPlacementGesture.ended) }
            }
        }
        .semantics {
            contentDescription = "Subtitle placement"
            stateDescription = captions.value
            customActions = listOf(
                CustomAccessibilityAction("Move subtitles left") { current.change(VideoEffectPlacementGesture.pan(CGPoint(-0.05, 0.0))); true },
                CustomAccessibilityAction("Move subtitles right") { current.change(VideoEffectPlacementGesture.pan(CGPoint(0.05, 0.0))); true },
                CustomAccessibilityAction("Move subtitles up") { current.change(VideoEffectPlacementGesture.pan(CGPoint(0.0, -0.05))); true },
                CustomAccessibilityAction("Move subtitles down") { current.change(VideoEffectPlacementGesture.pan(CGPoint(0.0, 0.05))); true },
                CustomAccessibilityAction("Enlarge subtitles") {
                    val c = CGPoint(current.envelope.midX, current.envelope.midY); current.change(VideoEffectPlacementGesture.pinch(1.1, c, c)); true
                },
                CustomAccessibilityAction("Shrink subtitles") {
                    val c = CGPoint(current.envelope.midX, current.envelope.midY); current.change(VideoEffectPlacementGesture.pinch(1 / 1.1, c, c)); true
                },
            )
        })
}

/**
 * One picture of [PlayerSurface] inside the canvas [window] (view pixels):
 * the framed picture, moved by its placement and clipped to its visible part,
 * with its titles, which belong to the picture and move with it.
 */
@Composable
private fun PlacedPicture(player: Player, framing: PreviewFraming?, window: CGRect?, titles: List<SubtitleLayout.Frame>) {
    val placed = framing?.placed
    if (placed != null && !placed.isVisible) return
    val k = if (framing != null && window != null) window.width / framing.canvas.width else 1.0
    val clip = placed?.clip
    val clipModifier = if (clip == null || window == null) Modifier.fillMaxSize() else Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(max(1, Math.round(clip.width * k).toInt()), max(1, Math.round(clip.height * k).toInt())))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(Math.round(clip.minX * k).toInt(), Math.round(clip.minY * k).toInt()) }
    }
    // Canvas pixels → view pixels inside the clip box.
    val toView = (placed?.transform ?: CGAffineTransform.identity).concatenating(CGAffineTransform.scale(k, k))
        .concatenating(CGAffineTransform.translation(-Math.round((clip?.minX ?: 0.0) * k).toDouble(), -Math.round((clip?.minY ?: 0.0) * k).toDouble()))
    Box(clipModifier.clipToBounds()) {
        val pictureModifier = if (framing == null || window == null) Modifier.fillMaxSize() else Modifier.layout { measurable, constraints ->
            // Lay the view out at the oriented source size and map it with the framing transform.
            val sourceWidth = max(1, Math.round(framing.source.width).toInt())
            val sourceHeight = max(1, Math.round(framing.source.height).toInt())
            val placeable = measurable.measure(Constraints.fixed(sourceWidth, sourceHeight))
            val m = CGAffineTransform.scale(framing.source.width / sourceWidth, framing.source.height / sourceHeight)
                .concatenating(framing.transform).concatenating(toView)
            val scale = kotlin.math.hypot(m.a, m.b).toFloat()
            // A Mirror reflects the picture (negative determinant): a negative
            // x scale, then the rotation that completes the matrix.
            val reflected = m.a * m.d - m.b * m.c < 0
            val degrees = Math.toDegrees(if (reflected) kotlin.math.atan2(-m.b, -m.a) else kotlin.math.atan2(m.b, m.a)).toFloat()
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeWithLayer(0, 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = if (reflected) -scale else scale; scaleY = scale; rotationZ = degrees
                    translationX = m.tx.toFloat(); translationY = m.ty.toFloat()
                }
            }
        }
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.editor_player_view, null) as PlayerView).also {
                    it.player = player; it.useController = false
                    it.setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                // A framed view already has the source's shape; fill it exactly.
                val mode = if (framing != null) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (view.resizeMode != mode) view.resizeMode = mode
            },
            onRelease = { view -> view.player = null },
            modifier = pictureModifier,
        )
        // Titles in output coordinates, drawn with the export's bitmaps (VideoToolCompositor.kt).
        if (framing != null && window != null && titles.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.concat(android.graphics.Matrix().apply {
                    setValues(floatArrayOf(toView.a.toFloat(), toView.c.toFloat(), toView.tx.toFloat(), toView.b.toFloat(), toView.d.toFloat(), toView.ty.toFloat(), 0f, 0f, 1f))
                })
                VideoOverlayRenderer.draw(canvas.nativeCanvas, titles, framing.canvas)
                canvas.nativeCanvas.restore()
            }
        }
    }
}

/**
 * Swift `EffectRemovalBubble`: an effect at the playhead. Tap edits it; touch
 * and hold arms removal (trash) with a cancel button. [compact] shows the icon
 * only (Swift `compactPlacement`).
 */
@Composable
private fun EffectRemovalBubble(title: String, icon: ImageVector, tint: Color, armed: Boolean, compact: Boolean = false,
                                edit: () -> Unit, arm: () -> Unit, cancel: () -> Unit, remove: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.heightIn(min = 48.dp).background(tint.copy(alpha = if (armed) 0.22f else 0.12f), shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically) {
        val label = @Composable { modifier: Modifier ->
            Row(modifier.heightIn(min = 48.dp).padding(horizontal = if (compact) 11.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                if (!compact) {
                    Spacer(Modifier.width(6.dp))
                    Text(title, color = tint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                }
            }
        }
        if (armed) {
            label(Modifier)
            IconAction(Icons.Outlined.Delete, "Remove $title", Color(1f, 0.27f, 0.23f)) { remove() }
            IconAction(Icons.Filled.Close, "Cancel removal", tint) { cancel() }
        } else {
            label(Modifier.pointerInput(title) { detectTapGestures(onLongPress = { arm() }, onTap = { edit() }) }
                .semantics { contentDescription = "Edit $title" })
        }
    }
}
