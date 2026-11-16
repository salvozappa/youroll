// Port of YouRec/EditorTimeline.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The UIKit TimelineSurface (a UIScrollView with gesture recognisers and a
// custom-drawn TimelineDrawing view) becomes one Compose Canvas with a single
// pointer handler. Geometry is computed in dp, which plays the role of iOS points.
//
// Transition markers (Swift `transitionMarkers`) are drawn on the join and focus it on tap.
// Censored ranges draw their tone and an orange strip (Swift `TimelineDrawing.draw`).
// Enhancement regions draw a purple strip.
// Not ported (TODO.md): processed-audio overviews, tool-region strips, VoiceOver elements and
// adjustable actions, edge-repeat of waveform gain drags.
package com.salvozappa.youroll

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val moveDestinationHint = "Choose a time on the timeline. Your selection will start there."

/** Colours shared by the editor screens (iOS dark-mode system colours). */
internal object EditorColors {
    val accent = Color(1f, 0.25f, 0.29f)
    val surface = Color(0.08f, 0.08f, 0.08f)
    val timeline = Color(0.065f, 0.065f, 0.065f)
    val yellow = Color(0xFFFFD60A)
    val cyan = Color(0xFF64D2FF)
    val mint = Color(0xFF63E6E2)
    val waveform = Color(0xFF00C7BE) // systemMint as the iOS timeline draws it
    val teal = Color(0xFF40CBE0)
    val orange = Color(0xFFFF9F0A)
    val purple = Color(0xFFBF5AF2)
    val pink = Color(0xFFFF375F)
    val indigo = Color(0xFF5E5CE6)
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val secondary = Color(235, 235, 245).copy(alpha = 0.6f)
    val tertiary = Color(235, 235, 245).copy(alpha = 0.3f)
    val exportGreen = Color(36, 83, 55) // MovieExportPanel.buttonColor
}

/** Swift `struct EditorTimeline`: everything the timeline draws and reports. */
class EditorTimelineModel(
    val edit: EditState,
    val sources: List<SourceMedia>,
    val folder: File?,
    val waves: Map<UUID, SourceWaveform>,
    val thumbnails: Map<UUID, List<SourceThumbnail>>,
    val playhead: Double,
    val pointsPerSecond: Double,
    val selected: TimelineSelection,
    val selection: ClosedFloatingPointRange<Double>?,
    val seek: (Double) -> Unit,
    val scrubbing: (Boolean) -> Unit,
    val snapEnabled: Boolean = true,
    val moving: Boolean = false,
    val moveLayout: TrackTimeline? = null,
    val moveViewportEnd: EditTime? = null,
    val boundaryChoices: List<EditTime>? = null,
    val pickBoundary: (List<EditTime>) -> Unit = {},
    val laneAreaHeight: Double = 144.0,
    val snapTime: Double? = null,
    val bookmarks: () -> Unit = {},
    val addVideo: ((VideoInsertionSite) -> Unit)? = null,
    val move: (Double, Double) -> Unit = { _, _ -> },
    val moveEnded: () -> Unit = {},
    val moveCancelled: () -> Unit = {},
    val select: (UUID?) -> Unit,
    val toggleSelection: (UUID) -> Unit = {},
    val zoom: (Double) -> Unit,
    val trim: (UUID, Boolean, Double) -> Unit,
    val gainEnabled: Boolean = false,
    val previewGain: (Double?, TimelineSelection) -> Unit = { _, _ -> },
    val commitGain: (Double, TimelineSelection) -> Unit = { _, _ -> },
    /** Swift `focusTransition`: put the playhead on a transition's join (its chip then edits it). */
    val focusTransition: (Double) -> Unit = {},
) {
    val viewportDuration: Double get() = moveViewportEnd?.seconds ?: moveLayout?.duration?.seconds ?: edit.duration
    val lanes: List<TimelineLane> by lazy { timelineLanes(edit.timeline, if (moving) selected else null) }
    val geometry: LaneGeometry by lazy { LaneGeometry(lanes, laneAreaHeight) }
    val contentHeight: Double get() = 20 + geometry.total
}

class LaneGeometry(lanes: List<TimelineLane>, laneAreaHeight: Double) {
    val heights: List<Double>

    init {
        val count = (lanes.maxOfOrNull { it.row } ?: 1) + 1
        // The usual two-picture/two-sound overlap fits without hiding a lane.
        val pitch = max(36.0, min(46.0, laneAreaHeight / count))
        val result = MutableList(count) { pitch }
        // Use spare room for larger video thumbnails regardless of selection. A tap,
        // range or multi-selection must not resize rows or move other hit targets.
        val videoRows = lanes.filter { !it.audio }.map { it.row }.toSet()
        val spare = max(0.0, laneAreaHeight - count * pitch)
        val extra = min(82 - pitch, spare / max(1, videoRows.size))
        for (row in videoRows) result[row] += extra
        heights = result
    }

    val total: Double get() = heights.sum()
    fun top(row: Int): Double = heights.take(row).sum()
    fun height(row: Int): Double = heights.getOrNull(row) ?: 46.0
    fun row(y: Double): Int = heights.indices.firstOrNull { y < top(it) + heights[it] } ?: heights.size
}

// One finger-sized target for boundaries too close to distinguish at this
// zoom. A cluster opens the exact times instead of guessing which cut was tapped.
data class MoveBoundaryMarker(val firstX: Double, var lastX: Double, val times: MutableList<EditTime>) {
    val center: Double get() = (firstX + lastX) / 2

    companion object {
        fun visible(choices: List<EditTime>, viewport: TimelineViewport, offset: Double, width: Double): List<MoveBoundaryMarker> {
            val result = mutableListOf<MoveBoundaryMarker>()
            for (time in choices) {
                val x = viewport.x(time.seconds, offset)
                if (x < 0) continue
                if (x > width) break
                val last = result.lastOrNull()
                if (last != null && x - last.firstX < 22) { last.lastX = x; last.times += time }
                else result += MoveBoundaryMarker(x, x, mutableListOf(time))
            }
            return result
        }
    }
}

/** Swift `TimelineTransitionMarker`: a join badge, its touch target and the picture-transition lines (dp). */
class TimelineTransitionMarker(val join: TransitionJoin, val frame: Rect, val hitFrame: Rect, val durationLines: List<Rect>)

// Drawing, touch and accessibility use the same compact marker geometry.
fun transitionMarkers(model: EditorTimelineModel, width: Double, offset: Offset, viewport: TimelineViewport,
                      addSites: List<InsertionMarker>): List<TimelineTransitionMarker> {
    if (model.moving || model.edit.timeline.transitions.isEmpty()) return emptyList()
    val lanes = model.lanes
    val layout = model.geometry
    return model.edit.layout.joins.mapNotNull { join ->
        if (join.transition == null) return@mapNotNull null
        val ids = listOfNotNull(join.outgoingID, join.incomingID)
        val rows = lanes.filter { !it.audio && it.id in ids }.map { it.row }.toSet().sorted()
        val row = rows.firstOrNull() ?: return@mapNotNull null
        val x = viewport.x(join.time, offset.x.toDouble())
        val y = layout.top(row) + 20 - offset.y
        val center = y + 2 + (layout.height(row) - 2) / 2
        var frame = Rect((x - 12).toFloat(), (center - 11).toFloat(), (x + 12).toFloat(), (center + 11).toFloat())
        var hit = Rect((x - 22).toFloat(), (center - 22).toFloat(), (x + 22).toFloat(), (center + 22).toFloat())
        // A tight gutter or gap can place Add beside a one-sided transition.
        // Keep the badge on the clip and the boundary itself tappable.
        if (join.edge != 0 && addSites.any { add ->
                val outside = if (join.edge < 0) add.frame.right <= x else add.frame.left >= x
                outside && add.frame.overlaps(Rect(hit.left - 4, hit.top, hit.right + 4, hit.bottom))
            }) {
            frame = frame.translate((if (join.edge < 0) x else x - frame.width) .toFloat() - frame.left, 0f)
            hit = hit.translate((if (join.edge < 0) x - 1 else x + 1 - hit.width).toFloat() - hit.left, 0f)
        }
        hit = hit.intersect(Rect(0f, (y + 2).toFloat(), width.toFloat(), (y + 2 + max(0.0, layout.height(row) - 2)).toFloat()))
        val a = viewport.x(join.videoRange.start, offset.x.toDouble())
        val b = viewport.x(join.videoRange.endInclusive, offset.x.toDouble())
        val lines = rows.map { r ->
            val top = layout.top(r) + 20 - offset.y + layout.height(r) - 3
            Rect(a.toFloat(), top.toFloat(), (a + max(1.0, b - a)).toFloat(), (top + 2).toFloat())
        }
        TimelineTransitionMarker(join, frame, hit, lines)
    }
}

/** A "+" target for inserting media, in timeline-surface coordinates (dp). */
data class InsertionMarker(val site: VideoInsertionSite, val frame: Rect)

/**
 * Swift `insertionMarkers(layout:)`: the "+" buttons at either end of the
 * picture and in picture gaps, kept clear of the span button and the lane overflow button.
 */
fun insertionMarkers(width: Double, height: Double, firstLaneHeight: Double, offset: Offset, viewport: TimelineViewport,
                     videoCoverage: List<EditInterval>, duration: Double, span: Rect, overflow: Rect?): List<InsertionMarker> {
    if (width < 44) return emptyList()
    val lane = Rect(0f, (20 - offset.y), width.toFloat(), (20 - offset.y + firstLaneHeight).toFloat())
        .intersect(Rect(0f, 20f, width.toFloat(), max(20.0, height).toFloat()))
    if (lane.width <= 0 || lane.height < 28) return emptyList()
    fun x(time: EditTime): Double = viewport.x(time.seconds, offset.x.toDouble())
    val bounds = Rect(0f, 0f, width.toFloat(), height.toFloat())
    val result = mutableListOf<InsertionMarker>()
    fun add(site: VideoInsertionSite, left: Double, right: Double, edge: Boolean = false) {
        val a = max(0.0, left); val b = min(width, right)
        if (b - a < 44 - 0.0001) return
        val h = if (edge) 44.0 else min(44.0, lane.height.toDouble())
        var frame = Rect(((a + b - 44) / 2).toFloat(), (lane.center.y - h / 2).toFloat(), ((a + b - 44) / 2 + 44).toFloat(), (lane.center.y + h / 2).toFloat())
        if (frame.overlaps(span)) {
            if (edge) frame = frame.translate(0f, span.bottom + 1 - frame.top)
            else if (span.left - a >= 44) frame = Rect(((a + span.left - 44) / 2).toFloat(), frame.top, ((a + span.left - 44) / 2 + 44).toFloat(), frame.bottom)
            else return
        }
        if (!(bounds.contains(frame.topLeft) && frame.right <= bounds.right + 0.01f && frame.bottom <= bounds.bottom + 0.01f)) return
        if (overflow != null && frame.overlaps(overflow)) return
        if (result.any { it.site == site }) return
        result += InsertionMarker(site, frame)
    }
    if (videoCoverage.isEmpty()) {
        add(VideoInsertionSite.start, 0.0, width)
        return result
    }
    val first = videoCoverage.first(); val last = videoCoverage.last()
    val start = x(EditTime.zero); val end = x(EditTime.of(duration))
    // Leave 4 pt between adjacent 44 pt targets whenever the gutter allows.
    // At whole-project fit, keep the entire button on screen.
    fun addOutside(site: VideoInsertionSite, boundary: Double, before: Boolean, edge: Boolean) {
        val room = if (before) boundary else width - boundary
        val padding = max(2.0, min(26.0, room - 44 - 2))
        val left = if (before) boundary - padding - 44 else boundary + padding
        add(site, left, left + 44, edge)
    }
    addOutside(VideoInsertionSite.start, start, before = true, edge = true)
    if (last.end < EditTime.of(duration)) addOutside(VideoInsertionSite.end, x(last.end), before = false, edge = false)
    addOutside(VideoInsertionSite.end, end, before = false, edge = true)
    // In a long gap the affordance stays in the visible slice. Its insertion
    // point still belongs to the actual neighbouring picture.
    add(VideoInsertionSite.start, start + 2, x(first.start) - 2)
    for ((left, right) in videoCoverage.zipWithNext()) add(VideoInsertionSite.gap(left.end), x(left.end) + 2, x(right.start) - 2)
    add(VideoInsertionSite.end, x(last.end) + 2, end - 2)
    return result.sortedBy { it.frame.center.x }
}

/**
 * Swift `TimelineDrawing.peak(source:wave:from:to:)`: the maximum peak over a
 * source span from a max-pyramid, so zoomed-out drawing stays O(log n) per column.
 */
class PeakPyramid {
    private val pyramids = HashMap<String, List<FloatArray>>()

    fun peak(source: String, wave: SourceWaveform, from: Double, to: Double): Float {
        if (pyramids[source]?.firstOrNull()?.size != wave.peaks.size || pyramids[source]?.first() !== wave.peaks) {
            val levels = mutableListOf(wave.peaks)
            while (levels.last().size > 1) {
                val last = levels.last()
                levels += FloatArray((last.size + 1) / 2) { max(last[it * 2], last[min(last.size - 1, it * 2 + 1)]) }
            }
            pyramids[source] = levels
        }
        val levels = pyramids[source] ?: return 0f
        if (wave.peaks.isEmpty() || wave.duration <= 0) return 0f
        val count = wave.peaks.size
        val a = max(0, min(count - 1, (from / wave.duration * count).toInt()))
        val b = max(a + 1, min(count, (to / wave.duration * count).toInt()))
        var cursor = a
        var result = 0f
        while (cursor < b) {
            var level = 0
            while (level + 1 < levels.size && cursor % (1 shl (level + 1)) == 0 && cursor + (1 shl (level + 1)) <= b) level += 1
            result = max(result, levels[level][cursor shr level]); cursor += 1 shl level
        }
        return result
    }
}

fun editorTime(seconds: Double): String {
    val frames = swiftRound(max(0.0, if (seconds.isFinite()) seconds else 0.0) * 30).toInt()
    return "%d:%02d.%02d".format(frames / 1800, frames / 30 % 60, swiftRound((frames % 30).toDouble() / 30 * 100).toInt())
}

/// Editor labels normally round to a video frame. Keep subframe cuts distinct
/// when two of them would have the same label in the nearby-boundaries list.
fun moveBoundaryLabel(time: EditTime, among: List<EditTime>): String {
    val label = editorTime(time.seconds)
    val ticksPerFrame = EditTime.scale / 30
    val frame = (time.ticks + ticksPerFrame / 2) / ticksPerFrame
    if (among.none { it != time && (it.ticks + ticksPerFrame / 2) / ticksPerFrame == frame }) return label
    val minutes = time.ticks / (60 * EditTime.scale)
    val seconds = (time.ticks % (60 * EditTime.scale)).toDouble() / EditTime.scale
    return "%d:%09.6f".format(minutes, seconds)
}

/** The geometry of the latest composition, read by gestures at event time. */
private class TimelineFrame(val viewport: TimelineViewport, val baseOffset: Double, val offsetX: Double, val width: Double,
                            val maxOffsetY: Double, val addSites: List<InsertionMarker>, val boundaryMarkers: List<MoveBoundaryMarker>)

private class GainSession(val scope: TimelineSelection, val origin: Offset, var point: Offset, val adjustment: AudioGainDrag, var cancelling: Boolean = false)
private class TimelineDrag(val id: UUID, val left: Boolean?, val originOffset: Double, val originScale: Double)
private enum class Phase { undecided, scrub, fitScrub, lanes, trim, move, gain, pinch, done }

@Composable
fun EditorTimeline(model: EditorTimelineModel, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(model)
    val density = LocalDensity.current.density.toDouble()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val linkPainter = rememberVectorPainter(Icons.Filled.Link)
    var offsetY by remember { mutableStateOf(0.0) }
    var drag by remember { mutableStateOf<TimelineDrag?>(null) }
    var dragDelta by remember { mutableStateOf(0.0) }
    var dragScroll by remember { mutableStateOf(0.0) }
    var gain by remember { mutableStateOf<GainSession?>(null) }
    var gainValue by remember { mutableStateOf<Double?>(null) }
    var spanMenu by remember { mutableStateOf(false) }
    val details = remember { mutableStateMapOf<String, WaveformTile>() }
    val pyramid = remember { PeakPyramid() }
    var fling by remember { mutableStateOf<Job?>(null) }
    val videoCoverage = remember(model.edit.timeline.video, model.edit.timeline.generated) { model.edit.timeline.videoCoverage }

    BoxWithConstraints(modifier.background(EditorColors.timeline).semantics { contentDescription = "Video and audio timeline" }.testTag("editor.timeline")) {
        val width = maxWidth.value.toDouble()
        val height = maxHeight.value.toDouble()
        val viewport = TimelineViewport(width, model.viewportDuration, model.pointsPerSecond)
        val baseOffset = if (viewport.fits) 0.0 else model.playhead * viewport.scale
        val offsetX = baseOffset + (if (drag != null) dragScroll else 0.0)
        val maxOffsetY = max(0.0, model.contentHeight - height)
        if (offsetY > maxOffsetY) offsetY = maxOffsetY
        val offset = Offset(offsetX.toFloat(), offsetY.toFloat())
        val span = Rect((width - 48).toFloat(), 22f, (width - 4).toFloat(), 66f)
        val overflowVisible = model.contentHeight > height + 1
        val overflow = Rect((width - 108).toFloat(), (height - 30).toFloat(), (width - 4).toFloat(), height.toFloat())
        val addSites = if (model.addVideo == null || model.moving || gain != null || drag != null) emptyList() else
            insertionMarkers(width, height, model.geometry.height(0), offset, viewport, videoCoverage, model.edit.duration, span, if (overflowVisible) overflow else null)
        val boundaryMarkers = MoveBoundaryMarker.visible(model.boundaryChoices ?: emptyList(), viewport, offsetX, width)
        // The pointer handler outlives this composition: it reads geometry through `live`.
        val live by rememberUpdatedState(TimelineFrame(viewport, baseOffset, offsetX, width, maxOffsetY, addSites, boundaryMarkers))

        // Detail tiles for zoomed-in waveforms (Swift `requestDetail`).
        val detailRequests = remember(model.edit, model.folder, viewport.scale >= 100, (offsetX / 200).toInt(), width) {
            if (model.folder == null || viewport.scale < 100) emptyList() else {
                val visibleStart = viewport.time(0.0, offsetX); val visibleEnd = viewport.time(width, offsetX)
                val requests = mutableListOf<Triple<String, File, Int>>()
                for (lane in model.lanes.filter { it.audio }) {
                    val source = model.sources.firstOrNull { it.id == lane.source } ?: continue
                    if (!(lane.interval.end.seconds > visibleStart && lane.interval.start.seconds < visibleEnd)) continue
                    var cursor = lane.contentInterval.start.seconds
                    for (run in lane.mapping.runs) {
                        val a = max(cursor, visibleStart); val b = min(cursor + run.duration.seconds, visibleEnd)
                        if (b > a && !run.silent) {
                            val rate = (run.sourceEnd - run.sourceStart) / run.duration.seconds
                            val s = run.sourceStart + (a - cursor) * rate; val e = run.sourceStart + (b - cursor) * rate
                            for (tile in max(0, (s / WaveformTile.seconds).toInt())..max(0, (e / WaveformTile.seconds).toInt())) {
                                val key = "${source.id}:${source.filename}:$tile"
                                if (requests.none { it.first == key } && requests.size < 64) requests += Triple(key, File(model.folder, source.filename), tile)
                            }
                        }
                        cursor += run.duration.seconds
                    }
                }
                requests
            }
        }
        LaunchedEffect(detailRequests) {
            val keys = detailRequests.map { it.first }.toSet()
            details.keys.retainAll(keys)
            if (detailRequests.isEmpty()) return@LaunchedEffect
            delay(100)
            for ((key, file, index) in detailRequests) {
                if (key in details) continue
                try { details[key] = WaveformDetailCache.tile(file, index) } catch (_: Exception) { /* The overview stays available. */ }
            }
        }

        fun item(point: Offset): TimelineLane? {
            val m = current
            if (point.y < 20) return null
            val time = EditTime.of(live.viewport.time(point.x.toDouble(), live.offsetX))
            val row = m.geometry.row(point.y + offsetY - 20)
            return m.lanes.firstOrNull { it.row == row && it.interval.contains(time) }
        }

        fun hit(point: Offset): Pair<UUID, Boolean?>? {
            val m = current
            if (live.addSites.any { it.frame.contains(point) }) return null
            if (m.boundaryChoices != null) return null
            if (m.moving) {
                val lane = item(point)
                return if (lane != null && lane.id in m.selected.ids) lane.id to null else null
            }
            if (m.selection != null || m.selected.video.size > 1 || m.selected.audio.size > 1 || point.y < 20) return null
            val laneY = point.y + offsetY - 20
            val row = m.geometry.row(laneY)
            if (row !in m.geometry.heights.indices || abs(laneY - m.geometry.top(row) - m.geometry.height(row) / 2) > 22) return null
            for (lane in m.lanes) {
                if (lane.row != row || lane.id !in m.selected.ids) continue
                val left = live.viewport.x(lane.interval.start.seconds, live.offsetX); val right = live.viewport.x(lane.interval.end.seconds, live.offsetX)
                if (right - left < 44) continue
                val leftDistance = if (lane.trimsLeft && left >= 0 && left < live.width) abs(point.x - left) else Double.POSITIVE_INFINITY
                val rightDistance = if (lane.trimsRight && right > 0 && right <= live.width) abs(point.x - right) else Double.POSITIVE_INFINITY
                if (min(leftDistance, rightDistance) <= 22) return lane.id to (leftDistance <= rightDistance)
            }
            return null
        }

        fun gainScope(point: Offset): TimelineSelection? {
            val m = current
            if (!m.gainEnabled || m.moving) return null
            val lane = item(point) ?: return null
            if (!lane.audio) return null
            val time = EditTime.of(live.viewport.time(point.x.toDouble(), live.offsetX))
            if (lane.id in m.selected.audio && (m.selected.range?.contains(time) ?: true)) return m.selected.copy(video = emptySet())
            return TimelineSelection(audio = setOf(lane.id))
        }

        fun finishScrubbing(snap: Boolean = true) {
            val m = current
            if (!live.viewport.fits && snap) m.seek(if (m.snapEnabled) m.edit.timeline.snap(m.playhead, live.viewport.scale) else m.playhead)
            m.scrubbing(false)
        }

        fun transition(point: Offset): TimelineTransitionMarker? {
            if (point.y < 20) return null
            return transitionMarkers(current, live.width, Offset(live.offsetX.toFloat(), offsetY.toFloat()), live.viewport, live.addSites)
                .filter { it.hitFrame.contains(point) }.minByOrNull { abs(it.frame.center.x - point.x) }
        }

        fun tapped(point: Offset) {
            val m = current
            if (m.moving && m.boundaryChoices == null) return
            if (m.boundaryChoices != null) {
                val closest = live.boundaryMarkers.minByOrNull { abs(it.center - point.x) }
                if (closest != null && point.x >= closest.firstX - 22 && point.x <= closest.lastX + 22) m.pickBoundary(closest.times.toList())
                return
            }
            val time = live.viewport.time(point.x.toDouble(), live.offsetX)
            if (point.y < 20) {
                val marks = m.edit.timeline.resolvedBookmarks.filter { abs(it.time.seconds - time) * live.viewport.scale < 10 }
                if (marks.size > 1) { m.bookmarks(); return }
                val mark = marks.minByOrNull { abs(it.time.seconds - time) }
                if (mark != null) { m.seek(mark.time.seconds); return }
                m.seek(if (m.snapEnabled) m.edit.timeline.snap(time, live.viewport.scale) else time)
                return
            }
            val marker = live.addSites.firstOrNull { it.frame.contains(point) }
            if (marker != null) { m.addVideo?.invoke(marker.site); return }
            transition(point)?.let { m.select(null); m.focusTransition(it.join.time); return }
            val lane = item(point)
            if (lane != null) {
                // Range membership is an edit scope, not a whole-clip selection.
                m.select(if (m.selection == null && m.edit.timeline.selection(of = lane.id).ids == m.selected.ids) null else lane.id)
            } else { m.select(null); m.seek(time) }
        }

        fun held(point: Offset) {
            val m = current
            if (m.moving) return
            transition(point)?.let { m.select(null); m.focusTransition(it.join.time); return }
            val part = item(point) ?: return
            m.toggleSelection(part.id)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        val gestures = Modifier.pointerInput(Unit) {
            val d = density
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val wasFlinging = fling?.isActive == true
                fling?.cancel(); fling = null
                // A touch stops a fling where it is (Swift: tap while decelerating).
                if (wasFlinging) finishScrubbing()
                val start = Offset((down.position.x / d).toFloat(), (down.position.y / d).toFloat())
                val startTime = down.uptimeMillis
                val m = current
                val trimTarget = if (!m.moving) hit(start)?.takeIf { it.second != null } else null
                val moveTarget = if (m.moving) hit(start) else null
                val slop = viewConfiguration.touchSlop / d
                var phase = Phase.undecided
                var held = false
                var startPlayhead = m.playhead
                val startOffsetY = offsetY
                val velocity = VelocityTracker()
                var point = start
                var pinchStart = 0.0
                var zoomStart = live.viewport.scale
                var edgeJob: Job? = null

                fun updateDrag() {
                    val active = drag ?: return
                    dragDelta = ((point.x - start.x) + (live.baseOffset + dragScroll) - active.originOffset) / active.originScale
                    if (active.left == null) current.move(dragDelta, active.originScale)
                }
                fun beginDrag(target: Pair<UUID, Boolean?>) {
                    dragScroll = 0.0
                    drag = TimelineDrag(target.first, target.second, live.baseOffset, live.viewport.scale); dragDelta = 0.0
                    current.scrubbing(true)
                    edgeJob = scope.launch {
                        while (isActive) {
                            delay(33)
                            val active = drag ?: break
                            if (!(active.left == null || abs(point.x - start.x) > 8)) continue
                            if (point.x < 26 || point.x > live.width - 26) {
                                val contentWidth = max(live.viewport.contentWidth, current.edit.duration * live.viewport.scale + 2 * live.viewport.inset)
                                val next = min(max(0.0, live.baseOffset + dragScroll + (if (point.x < 26) -6 else 6)), max(0.0, contentWidth - live.width))
                                dragScroll = next - live.baseOffset
                                updateDrag()
                            }
                        }
                    }
                }
                fun endDrag(apply: Boolean) {
                    edgeJob?.cancel()
                    val active = drag
                    if (active != null) {
                        if (apply && active.left != null) current.trim(active.id, active.left, dragDelta)
                        if (active.left == null) { if (apply) current.moveEnded() else current.moveCancelled() }
                    }
                    drag = null; dragDelta = 0.0; dragScroll = 0.0
                    current.scrubbing(false)
                }

                while (phase != Phase.done) {
                    // Hold 0.3 s on a trim handle to trim; hold 0.45 s elsewhere to add to the selection.
                    val limit = if (phase != Phase.undecided || held) null else if (trimTarget != null) 300L else 450L
                    val event = if (limit != null) withTimeoutOrNull(max(1L, limit - (currentEventTime() - startTime))) { awaitPointerEvent() }
                        else awaitPointerEvent()
                    if (event == null) {
                        if (trimTarget != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            beginDrag(trimTarget); phase = Phase.trim; updateDrag()
                        } else { held = true; held(start) }
                        continue
                    }
                    val pressed = event.changes.filter { it.pressed }
                    pressed.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    if (pressed.size >= 2 && phase in listOf(Phase.undecided, Phase.scrub, Phase.fitScrub, Phase.lanes)) {
                        val m2 = current
                        if (!m2.moving || m2.boundaryChoices != null) {
                            if (phase == Phase.scrub || phase == Phase.fitScrub) finishScrubbing(snap = false)
                            phase = Phase.pinch
                            pinchStart = (pressed[0].position - pressed[1].position).getDistance().toDouble()
                            zoomStart = live.viewport.scale
                        }
                    }
                    if (pressed.isEmpty()) {
                        when (phase) {
                            Phase.undecided -> if (!held && !wasFlinging) tapped(start)
                            Phase.scrub -> {
                                val v = velocity.calculateVelocity().x / d
                                if (abs(v) > 50 && !live.viewport.fits) {
                                    val origin = min(current.edit.duration, max(0.0, startPlayhead - (point.x - start.x) / live.viewport.scale))
                                    fling = scope.launch {
                                        AnimationState(0f, (-v / live.viewport.scale).toFloat()).animateDecay(exponentialDecay(frictionMultiplier = 2.5f)) {
                                            current.seek(min(current.edit.duration, max(0.0, origin + value)))
                                        }
                                        finishScrubbing()
                                    }
                                } else finishScrubbing()
                            }
                            Phase.fitScrub -> {
                                val m3 = current
                                val time = live.viewport.time(point.x.toDouble(), 0.0)
                                m3.seek(if (m3.snapEnabled) m3.edit.timeline.snap(time, live.viewport.scale) else time)
                                m3.scrubbing(false)
                            }
                            Phase.trim, Phase.move -> endDrag(apply = true)
                            Phase.gain -> {
                                val session = gain
                                gain = null; gainValue = null
                                if (session != null) {
                                    current.previewGain(null, session.scope)
                                    val db = session.adjustment.value
                                    if (!session.cancelling && db != null && db != session.adjustment.initial) current.commitGain(db, session.scope)
                                }
                            }
                            else -> Unit
                        }
                        phase = Phase.done
                        break
                    }
                    val change = pressed[0]
                    point = Offset((change.position.x / d).toFloat(), (change.position.y / d).toFloat())
                    val dx = point.x - start.x; val dy = point.y - start.y
                    when (phase) {
                        Phase.undecided -> if (hypot(dx, dy) > slop && !held) {
                            val m3 = current
                            when {
                                moveTarget != null && abs(dx) >= abs(dy) -> { beginDrag(moveTarget); phase = Phase.move; updateDrag() }
                                abs(dy) > abs(dx) && gainScope(start) != null -> {
                                    val target = gainScope(start)!!
                                    val items = m3.edit.timeline.audio.filter { it.id in target.audio }
                                    val levels = (items.minOfOrNull { it.volumeDB } ?: 0.0)..(items.maxOfOrNull { it.volumeDB } ?: 0.0)
                                    gain = GainSession(target, start, point, AudioGainDrag(AudioGainGrid.common(items), levels))
                                    // Dragging an unselected waveform targets that item. An existing
                                    // range/multi-selection applies only when the touch starts in it.
                                    if (!target.audio.all { it in m3.selected.audio } || target.range != m3.selected.range) m3.select(target.audio.firstOrNull())
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    phase = Phase.gain
                                }
                                abs(dx) >= abs(dy) && live.viewport.fits -> { m3.scrubbing(true); phase = Phase.fitScrub }
                                abs(dx) >= abs(dy) -> { m3.scrubbing(true); startPlayhead = m3.playhead; phase = Phase.scrub }
                                else -> phase = Phase.lanes
                            }
                        }
                        Phase.scrub -> {
                            val m3 = current
                            m3.seek(min(m3.edit.duration, max(0.0, startPlayhead - dx / live.viewport.scale)))
                        }
                        Phase.fitScrub -> current.seek(live.viewport.time(point.x.toDouble(), 0.0))
                        Phase.lanes -> offsetY = min(live.maxOffsetY, max(0.0, startOffsetY - dy))
                        Phase.trim, Phase.move -> updateDrag()
                        Phase.gain -> gain?.let { session ->
                            session.point = point
                            session.cancelling = abs(point.x - session.origin.x) > 80
                            val before = session.adjustment.value
                            session.adjustment.update((session.origin.y - point.y).toDouble())
                            if (session.adjustment.value != before) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val db = if (session.cancelling) null else session.adjustment.value
                            gainValue = db
                            current.previewGain(db, session.scope)
                        }
                        Phase.pinch -> if (pressed.size >= 2 && pinchStart > 0) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance().toDouble()
                            current.zoom(max(live.viewport.minimumScale, min(720.0, zoomStart * distance / pinchStart)))
                        }
                        else -> Unit
                    }
                    if (phase != Phase.undecided) event.changes.forEach { it.consume() }
                }
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            drawTimeline(model, viewport, offset, width, height, addSites, boundaryMarkers, drag, dragDelta, details, pyramid, measurer, gain, gainValue)
            // Link glyphs on the lane badges.
            for (lane in model.lanes) {
                val linked = model.edit.timeline.links.any { lane.id in it.members }
                if (!linked) continue
                val badge = badgeFrame(model, lane, viewport, offset, measurer, density.toFloat()) ?: continue
                val s = density.toFloat()
                // SF `link` is a diagonal chain; Material's Link is horizontal, so turn it 45 degrees.
                translate((badge.right - 13) * s, (badge.center.y - 5) * s) {
                    rotate(-45f, pivot = Offset(5 * s, 5 * s)) {
                        with(linkPainter) { draw(Size(10 * s, 10 * s), alpha = 0.7f, colorFilter = ColorFilter.tint(Color.White)) }
                    }
                }
            }
        }

        // Swift TimelineSpanButton: one rectangular target over the filmstrip.
        Box(Modifier.offset(x = span.left.dp, y = span.top.dp).size(44.dp)) {
            Box(Modifier.size(44.dp, 28.dp).background(Color.Black.copy(alpha = 0.78f)).border(0.5.dp, Color.White.copy(alpha = 0.2f))
                .clickable { spanMenu = true }.semantics { contentDescription = "Timeline view span" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Height, null, tint = Color.White, modifier = Modifier.size(20.dp).rotate(90f))
            }
            DropdownMenu(spanMenu, onDismissRequest = { spanMenu = false }) {
                val choices = listOf("Whole project" to viewport.minimumScale, "5 minutes" to width / 300, "1 minute" to width / 60,
                    "10 seconds" to width / 10, "1 second" to width)
                for ((title, scale) in choices) {
                    DropdownMenuItem(text = { Text(title) }, onClick = { spanMenu = false; model.zoom(scale) },
                        trailingIcon = { if (abs(model.pointsPerSecond - scale) < 0.0000001) Icon(Icons.Filled.Check, null) })
                }
            }
        }
        if (overflowVisible) {
            val hidden = model.geometry.heights.indices.count { model.geometry.top(it) + model.geometry.height(it) + 20 > height + offsetY }
            Box(Modifier.offset(x = overflow.left.dp, y = overflow.top.dp).size(104.dp, 30.dp)
                .background(Color(0.08f, 0.08f, 0.08f, 0.95f), RoundedCornerShape(10.dp))
                .clickable {
                    val bottom = maxOffsetY
                    offsetY = if (offsetY >= bottom - 1) 0.0 else min(bottom, offsetY + 92)
                }.semantics { contentDescription = "Show more timeline lanes" }, contentAlignment = Alignment.Center) {
                Text(if (hidden > 0) "⌄ +$hidden lanes" else "⌃ Top lanes", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // Swift WaveformGainReadout: a single unobtrusive readout near the finger.
        gain?.let { session ->
            val x = min(width - 108, max(8.0, session.point.x - 110.0))
            val y = max(0.0, session.point.y - 48.0)
            Box(Modifier.offset(x = x.dp, y = y.dp).size(100.dp, 32.dp).background(Color(0.08f, 0.08f, 0.08f, 0.95f), RoundedCornerShape(12.dp))
                .border(1.dp, EditorColors.cyan, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(if (session.cancelling) "Cancel" else gainValue?.let { "%+.1f dB".format(it) } ?: "Mixed",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = tabular)
            }
        }
    }
}

private fun currentEventTime(): Long = android.os.SystemClock.uptimeMillis()

// iOS: `monospacedDigitSystemFont(ofSize: 13, weight: .semibold)`.
private val badgeStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default,
    fontFeatureSettings = "tnum")

/**
 * The badge label. Android's fallback ♪ is much smaller than SF's and has no side bearing,
 * so it is drawn larger and followed by a thin space, to read like the iOS badge.
 */
private fun badgeText(label: String): AnnotatedString = buildAnnotatedString {
    if (label.startsWith("♪")) {
        withStyle(SpanStyle(fontSize = 19.sp)) { append("♪") }
        append(" "); append(label.substring(1))
    } else append(label)
}

private fun laneLabel(model: EditorTimelineModel, lane: TimelineLane): String {
    val timeline = model.edit.timeline
    val linked = timeline.links.any { lane.id in it.members }
    val audio = timeline.audio.firstOrNull { it.id == lane.id }
    val picture = audio?.let { sound -> timeline.video.firstOrNull { it.occurrenceID == sound.occurrenceID && it.mapping.source(EditTime.zero) == sound.mapping.source(EditTime.zero) } }
    val displacement = if (audio != null && picture != null) (audio.start - picture.start).seconds else 0.0
    val offsetLabel = if (lane.audio && !linked && abs(displacement) >= 0.01) " %+.2fs".format(displacement) else ""
    return "${if (lane.audio) "♪" else "V"}${lane.number}$offsetLabel"
}

private fun cardFrame(model: EditorTimelineModel, lane: TimelineLane, viewport: TimelineViewport, offset: Offset, width: Double): Rect {
    val rowHeight = model.geometry.height(lane.row)
    val y = model.geometry.top(lane.row) + 20 - offset.y
    val left = viewport.x(lane.interval.start.seconds, offset.x.toDouble()); val right = viewport.x(lane.interval.end.seconds, offset.x.toDouble())
    val x0 = max(-5.0, left + 1.5)
    return Rect(x0.toFloat(), (y + 2).toFloat(), (x0 + max(1.0, min(width + 5, right - 1.5) - x0)).toFloat(), (y + rowHeight).toFloat())
}

private fun badgeFrame(model: EditorTimelineModel, lane: TimelineLane, viewport: TimelineViewport, offset: Offset, measurer: TextMeasurer, density: Float): Rect? {
    val card = cardFrame(model, lane, viewport, offset, viewport.width)
    if (card.width <= 28 || card.bottom < 20) return null
    val linked = model.edit.timeline.links.any { lane.id in it.members }
    // TextMeasurer reports pixels; the badge is laid out in dp like iOS points.
    val size = measurer.measure(badgeText(laneLabel(model, lane)), badgeStyle).size
    val w = size.width / density + 6 + (if (linked) 13 else 0)
    val x = max(4f, card.left + 3)
    val y = if (lane.audio) card.bottom - 20 else card.top + 2
    return Rect(x, y, x + w, y + 18)
}

private fun DrawScope.drawTimeline(
    model: EditorTimelineModel, view: TimelineViewport, offset: Offset, width: Double, height: Double,
    addSites: List<InsertionMarker>, boundaryMarkers: List<MoveBoundaryMarker>, drag: TimelineDrag?, dragDelta: Double,
    details: Map<String, WaveformTile>, pyramid: PeakPyramid, measurer: TextMeasurer, gain: GainSession?, gainValue: Double?,
) {
    val s = density
    fun r(rect: Rect) = Rect(rect.left * s, rect.top * s, rect.right * s, rect.bottom * s)
    fun p(x: Double, y: Double) = Offset((x * s).toFloat(), (y * s).toFloat())
    fun x(time: Double): Double = view.x(time, offset.x.toDouble())
    val start = view.time(0.0, offset.x.toDouble()); val end = view.time(width, offset.x.toDouble())
    val selected = model.selected.ids
    val layout = model.geometry
    val trackBottom = max(20.0, min(height, 20 + layout.total - offset.y))
    val timeline = model.edit.timeline
    val textScale = s

    clipRect(0f, 20 * s, (width * s).toFloat(), (height * s).toFloat()) {
        for (lane in model.lanes) {
            if (lane.interval.end.seconds < start || lane.interval.start.seconds > end) continue
            val rowHeight = layout.height(lane.row)
            val y = layout.top(lane.row) + 20 - offset.y
            if (y + rowHeight < 20 || y > height) continue
            val left = x(lane.interval.start.seconds); val right = x(lane.interval.end.seconds)
            val contentLeft = x(lane.contentInterval.start.seconds)
            val card = cardFrame(model, lane, view, offset, width)
            val cardPath = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r(card), CornerRadius(5 * s))) }
            clipPath(cardPath) {
                drawRect(Color(if (lane.audio) 0.12f else 0.18f, if (lane.audio) 0.12f else 0.18f, if (lane.audio) 0.12f else 0.18f), r(card).topLeft, r(card).size)
                val wave = model.waves[lane.source]
                if (lane.audio && wave != null) {
                    val audio = timeline.audio.firstOrNull { it.id == lane.id }
                    val censors = audio?.let { timeline.censorSpans(it) } ?: emptyList()
                    // The clip badge overlays the wave; it does not reserve a label row.
                    val half = max(1.0, (card.height - 6) / 2.0); val center = card.center.y.toDouble()
                    val points = ArrayList<Offset>()
                    var px = left + max(0.0, ceil(-left))
                    val source = model.sources.firstOrNull { it.id == lane.source }
                    val waveKey = "${lane.source}:${source?.filename ?: ""}"
                    while (px < min(width, right)) {
                        val local = max(0.0, (px - contentLeft) / view.scale)
                        val a = lane.mapping.source(EditTime.of(local)) ?: 0.0
                        val b = lane.mapping.source(EditTime.of(min(lane.mapping.duration.seconds, (px - contentLeft + 1) / view.scale))) ?: a
                        var runStart = 0.0
                        val silent = lane.mapping.runs.firstOrNull { run -> val inside = local < runStart + run.duration.seconds; runStart += run.duration.seconds; inside }?.silent ?: true
                        val peak = if (silent) 0f else pyramid.peak("${lane.source}:original", wave, a, max(a, b))
                        var envelope = -peak to peak
                        if (view.scale >= 100 && !silent) {
                            val tileIndex = (a / WaveformTile.seconds).toInt()
                            details["$waveKey:$tileIndex"]?.envelope(a, max(a, b))?.let { detail ->
                                envelope = detail
                                if (b >= (tileIndex + 1) * WaveformTile.seconds) {
                                    details["$waveKey:${tileIndex + 1}"]?.envelope((tileIndex + 1) * WaveformTile.seconds, b)?.let { next ->
                                        envelope = min(detail.first, next.first) to max(detail.second, next.second)
                                    }
                                }
                            }
                        }
                        val contentTime = lane.contentInterval.start.seconds + local
                        // A censored range draws its tone's envelope instead of the speech it replaces.
                        if (!silent) censors.firstOrNull { it.start <= contentTime && contentTime < it.end }?.let { censor ->
                            val t = contentTime - censor.envelopeStart
                            val tone = if (censor.emitsTone) (censor.settings.amplitude * censor.settings.envelopeValue(t, censor.envelopeEnd - censor.envelopeStart)).toFloat() else 0f
                            envelope = -tone to tone
                        }
                        val level = if (gain != null && gainValue != null && audio != null && audio.id in gain.scope.audio && (gain.scope.range?.contains(EditTime.of(contentTime)) ?: true))
                            (if (audio.muted) 0f else Math.pow(10.0, gainValue / 20).toFloat()) else (audio?.gain ?: 1.0).toFloat()
                        val fade = min(audio?.fadeIn?.gain(local) ?: 1.0, audio?.fadeOut?.gain(lane.contentInterval.duration.seconds - local) ?: 1.0)
                        val g = level * fade.toFloat()
                        // Square-root amplitude keeps quiet consonants visible.
                        val top = max(0.35, sqrt(min(1f, max(0f, envelope.second * g))).toDouble() * half)
                        val bottom = max(0.35, sqrt(min(1f, max(0f, -envelope.first * g))).toDouble() * half)
                        points += p(px + 0.5, center - top); points += p(px + 0.5, center + bottom)
                        px += 1
                    }
                    drawPoints(points, PointMode.Lines, EditorColors.waveform.copy(alpha = if (audio?.muted == true) 0.25f else 0.85f), strokeWidth = s)
                    for (region in audio?.censors.orEmpty()) {
                        val stripLeft = x(lane.contentInterval.start.seconds + region.interval.start.seconds)
                        drawRect(EditorColors.orange, p(stripLeft, card.bottom - 6.0), androidx.compose.ui.geometry.Size((region.interval.duration.seconds * view.scale * s).toFloat(), 3 * s))
                    }
                    // Swift: an enhancement region draws a purple strip along the card's bottom edge.
                    for (region in audio?.enhancements.orEmpty()) {
                        val stripLeft = x(lane.contentInterval.start.seconds + region.interval.start.seconds)
                        drawRect(EditorColors.purple, p(stripLeft, card.bottom - 3.0), androidx.compose.ui.geometry.Size((region.interval.duration.seconds * view.scale * s).toFloat(), 3 * s))
                    }
                } else if (!lane.audio) {
                    val images = model.thumbnails[lane.source]
                    if (!images.isNullOrEmpty()) {
                        val first = images[0].image
                        // Taller rows need wider landscape thumbnails, not a tighter crop of the same strip.
                        val tileWidth = max(64.0, card.height * first.width.toDouble() / max(1, first.height))
                        var tile = max(left, floor((max(0.0, left) - left) / tileWidth) * tileWidth + left)
                        while (tile < min(right, width)) {
                            val sourceTime = lane.mapping.source(EditTime.of((tile - contentLeft) / view.scale)) ?: 0.0
                            val image = (images.lastOrNull { it.time <= sourceTime } ?: images[0]).image
                            val ratio = max(tileWidth / image.width, card.height.toDouble() / image.height)
                            val w = image.width * ratio; val h = image.height * ratio
                            val dst = Rect((tile + (tileWidth - w) / 2).toFloat(), (card.center.y - h / 2).toFloat(), (tile + (tileWidth + w) / 2).toFloat(), (card.center.y + h / 2).toFloat())
                            val clip = r(Rect(tile.toFloat(), card.top, (tile + tileWidth).toFloat(), card.bottom))
                            clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                                val d = r(dst)
                                drawImage(image.asImageBitmap(), IntOffset.Zero, IntSize(image.width, image.height),
                                    IntOffset(d.left.toInt(), d.top.toInt()), IntSize(d.width.toInt().coerceAtLeast(1), d.height.toInt().coerceAtLeast(1)))
                            }
                            tile += tileWidth
                        }
                    }
                }
                // Badge "V1" / "♪1" (the link glyph is drawn afterwards with a vector painter).
                val badge = badgeFrame(model, lane, view, offset, measurer, s)
                if (badge != null) {
                    drawRoundRect(Color.Black.copy(alpha = if (lane.audio) 0.6f else 0.72f), r(badge).topLeft, r(badge).size, CornerRadius(4 * s))
                    val label = laneLabel(model, lane)
                    val text = measurer.measure(badgeText(label), badgeStyle)
                    // Centre on the plain 13 sp line, so the larger ♪ does not push the digits down.
                    val plain = measurer.measure(label, badgeStyle)
                    drawText(text, topLeft = Offset((badge.left + 3) * s,
                        (badge.top + 9) * s - plain.size.height / 2f + plain.firstBaseline - text.firstBaseline))
                }
            }
            if (lane.id in selected) {
                val range = model.selection
                if (range != null) {
                    // Highlight only the source/time intersection that an edit will affect.
                    val from = max(lane.interval.start.seconds, range.start); val to = min(lane.interval.end.seconds, range.endInclusive)
                    if (to > from) {
                        val part = card.intersect(Rect(x(from).toFloat(), card.top, (x(from) + (to - from) * view.scale).toFloat(), card.bottom))
                        if (part.width > 0) clipPath(cardPath) {
                            drawRect(EditorColors.yellow.copy(alpha = 0.16f), r(part).topLeft, r(part).size)
                            val inset = part.deflate(min(1f, part.width / 2)).let { Rect(it.left, part.top + 1, it.right, part.bottom - 1) }
                            drawRect(EditorColors.yellow, r(inset).topLeft, r(inset).size, style = Stroke(2 * s))
                        }
                    }
                } else {
                    val outline = card.deflate(1f)
                    drawRoundRect(EditorColors.yellow, r(outline).topLeft, r(outline).size, CornerRadius(4 * s), style = Stroke(2 * s))
                    if (!model.moving && model.selected.video.size <= 1 && model.selected.audio.size <= 1 && right - left >= 44) {
                        for (edge in listOfNotNull(if (lane.trimsLeft) left + 3 else null, if (lane.trimsRight) right - 6 else null)) {
                            drawRect(EditorColors.yellow, p(edge, card.center.y - 10.0), Size(5 * s, 24 * s))
                        }
                    }
                }
            }
            if (drag != null && drag.id == lane.id && drag.left != null) {
                val isLeft = drag.left
                val removed = min(max(0.0, lane.interval.duration.seconds - TimelineEditing.frame), if (isLeft) dragDelta else -dragDelta)
                val edge = if (isLeft) lane.interval.start.seconds + removed else lane.interval.end.seconds - removed
                drawLine(EditorColors.yellow, p(x(edge), card.top.toDouble()), p(x(edge), card.bottom.toDouble()), 3 * s)
                val title = if (removed > 0) "Trim " + editorTime(removed) else if (isLeft) "Trim start" else "Trim end"
                val text = measurer.measure(title, TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                val w = text.size.width / textScale; val h = text.size.height / textScale
                val bx = min(max(2.0, x(edge) - w / 2 - 6), width - w - 14)
                val badge = Rect(bx.toFloat(), card.top + 2, (bx + w + 12).toFloat(), card.top + 2 + h + 4)
                drawRoundRect(EditorColors.yellow, r(badge).topLeft, r(badge).size, CornerRadius(5 * s))
                drawText(text, topLeft = Offset((badge.left + 6) * s, (badge.top + 2) * s))
            }
        }
        model.moveLayout?.let { base ->
            val currentRows = model.lanes.associate { it.id to it.row }
            for (lane in timelineLanes(base)) {
                if (lane.id !in selected) continue
                // Show the original position on the item's current lane.
                val row = currentRows[lane.id] ?: lane.row
                val rect = Rect(x(lane.interval.start.seconds).toFloat(), (layout.top(row) + 22 - offset.y).toFloat(),
                    (x(lane.interval.start.seconds) + lane.interval.duration.seconds * view.scale).toFloat(), (layout.top(row) + 22 - offset.y + layout.height(row) - 4).toFloat())
                drawRect(Color.White.copy(alpha = 0.45f), r(rect).topLeft, r(rect).size, style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * s, 3 * s))))
            }
        }
        model.selection?.let { range ->
            // Quiet guides locate the range through gaps without shading lanes outside its scope.
            for (time in setOf(range.start, range.endInclusive)) {
                drawLine(EditorColors.yellow.copy(alpha = 0.5f), p(x(time), 20.0), p(x(time), trackBottom), s,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * s, 3 * s)))
            }
        }
    }
    model.snapTime?.let { time ->
        drawLine(EditorColors.cyan, p(x(time), 0.0), p(x(time), trackBottom), 1.5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * s, 3 * s)))
        drawCircle(EditorColors.cyan, 3 * s, p(x(time), 4.0))
    }
    // Ruler.
    val rulerStep = listOf(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0, 600.0, 1800.0, 3600.0).firstOrNull { it * view.scale >= 44 } ?: 7200.0
    var time = floor(start / rulerStep) * rulerStep
    val rulerStyle = TextStyle(color = EditorColors.secondary, fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Default)
    while (time <= end + 1e-9) {
        val label = if (time < 60) formatG(time) else "%d:%02d".format(time.toInt() / 60, time.toInt() % 60)
        drawText(measurer.measure(label, rulerStyle), topLeft = p(x(time) + 3, 2.0))
        time += rulerStep
    }
    // Bookmarks.
    val clusters = mutableListOf<Pair<Double, Int>>()
    for (mark in timeline.resolvedBookmarks) {
        val px = x(mark.time.seconds)
        if (px < 0 || px > width) continue
        val last = clusters.lastOrNull()
        if (last != null && px - last.first < 12) clusters[clusters.size - 1] = last.first to last.second + 1 else clusters += px to 1
    }
    for ((px, count) in clusters) {
        drawPath(Path().apply { moveTo((px - 4).toFloat() * s, 12 * s); lineTo((px + 4).toFloat() * s, 12 * s); lineTo(px.toFloat() * s, 19 * s); close() }, EditorColors.orange)
        if (count > 1) drawText(measurer.measure("$count", TextStyle(color = EditorColors.orange, fontSize = 9.sp, fontWeight = FontWeight.Bold)), topLeft = p(px + 4, 8.0))
    }
    // Transition markers: the picture transition as an orange line, the join as a badge with two arrowheads.
    for (marker in transitionMarkers(model, width, offset, view, addSites)) {
        val active = abs(model.playhead - marker.join.time) < 0.0001
        for (line in marker.durationLines) drawRect(EditorColors.orange.copy(alpha = 0.85f), r(line).topLeft, r(line).size)
        val frame = r(marker.frame)
        drawRoundRect(if (active) EditorColors.orange else Color(0.1f, 0.1f, 0.1f), frame.topLeft, frame.size, CornerRadius(5 * s))
        drawRoundRect(EditorColors.orange, frame.topLeft, frame.size, CornerRadius(5 * s), style = Stroke(1.5f * s))
        val c = marker.frame.center
        for (side in listOf(-1f, 1f)) {
            drawPath(Path().apply {
                moveTo((c.x + side * 7) * s, (c.y - 5) * s); lineTo((c.x + side) * s, c.y * s); lineTo((c.x + side * 7) * s, (c.y + 5) * s); close()
            }, if (active) Color.Black else EditorColors.orange)
        }
    }
    // Playhead.
    drawLine(Color.White, p(x(model.playhead), 0.0), p(x(model.playhead), trackBottom), 1.5f * s)
    for (marker in boundaryMarkers) {
        for (t in marker.times) {
            val edge = x(t.seconds)
            drawLine(EditorColors.cyan.copy(alpha = 0.85f), p(edge, 18.0), p(edge, trackBottom), 1.5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * s, 3 * s)))
        }
        val c = marker.center
        drawPath(Path().apply { moveTo(((c - 5) * s).toFloat(), 13 * s); lineTo(((c + 5) * s).toFloat(), 13 * s); lineTo((c * s).toFloat(), 20 * s); close() }, EditorColors.cyan)
        if (marker.times.size > 1) {
            val text = measurer.measure("${marker.times.size}", TextStyle(color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            val w = text.size.width / textScale
            drawRect(EditorColors.cyan, p(c - w / 2 - 3, 0.0), Size(((w + 6) * s).toFloat(), 14 * s))
            drawText(text, topLeft = p(c - w / 2, 0.0))
        }
    }
    for (marker in addSites) {
        val c = marker.frame.center
        drawCircle(Color(0.17f, 0.17f, 0.17f), 12 * s, Offset(c.x * s, c.y * s))
        drawCircle(Color(0.55f, 0.55f, 0.55f), 12 * s, Offset(c.x * s, c.y * s), style = Stroke(s))
        drawLine(Color.White, Offset((c.x - 5) * s, c.y * s), Offset((c.x + 5) * s, c.y * s), 1.6f * s, StrokeCap.Round)
        drawLine(Color.White, Offset(c.x * s, (c.y - 5) * s), Offset(c.x * s, (c.y + 5) * s), 1.6f * s, StrokeCap.Round)
    }
}

/** Swift `String(format: "%g", time)` for ruler labels. */
internal fun formatG(value: Double): String {
    val rounded = swiftRound(value * 10) / 10
    return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
}

/** SwiftUI `.monospacedDigit()`: tabular figures in the system font. */
internal val tabular = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
