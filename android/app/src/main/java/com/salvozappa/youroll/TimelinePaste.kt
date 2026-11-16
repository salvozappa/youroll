// Port of YouRec/TimelinePaste.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Transitions: a copied fragment keeps the transitions between its selected
// partners (`paste` gives them fresh identities, TrackTimeline.kt), and a
// picture paste that separates a dissolve first restores its straight cut
// (`insertPictureTime`, `restoringStraightCut`) so both source handles are used.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/// A clipboard destination on the exact edit clock. At coincident cuts, the
/// user can choose the picture sequence or the independent sound sequence.
data class TimelinePasteTarget(
    val kind: Kind,
    val time: EditTime,
    val anchor: Anchor,
    val transition: UUID? = null,
) {
    enum class Kind { picture, sound, project }

    sealed class Anchor {
        data class between(val left: UUID, val right: UUID) : Anchor()
        data object start : Anchor()
        data object end : Anchor()
        data object gap : Anchor()
    }

    fun isAt(playhead: EditTime): Boolean {
        // Next change lands on the first presentation frame after a subframe
        // edit. Pin the original exact cut even when reached by transport.
        val frameTicks = EditTime.scale / 30
        val displayFrame = ((time.ticks + frameTicks - 1) / frameTicks) * frameTicks
        return abs(time.ticks - playhead.ticks) < 24 || playhead.ticks == displayFrame
    }

    fun label(timeline: TrackTimeline): String = when (anchor) {
        Anchor.start -> "Start"
        Anchor.end -> "End"
        Anchor.gap -> "Gap"
        is Anchor.between -> {
            val ids = if (kind == Kind.picture) timeline.video.map { it.id } else timeline.audio.map { it.id }
            val prefix = if (kind == Kind.picture) "V" else "A"
            fun number(id: UUID): String = ids.indexOf(id).takeIf { it >= 0 }?.let { prefix + (it + 1) } ?: prefix
            (if (kind == Kind.picture) "Video" else "Audio") + " · " + number(anchor.left) + " | " + number(anchor.right)
        }
    }
}

/** Swift returns `(clipboard: TimelineClipboard, position: EditTime)`. */
data class TimelineCut(val clipboard: TimelineClipboard, val position: EditTime)

fun TrackTimeline.pasteTargets(layout: TimelineLayout): List<TimelinePasteTarget> {
    val pictures = layout.joins.mapNotNull { join ->
        val left = join.outgoingID
        val right = join.incomingID
        if (!join.selectable || left == null || right == null) return@mapNotNull null
        TimelinePasteTarget(TimelinePasteTarget.Kind.picture, EditTime.of(join.time), TimelinePasteTarget.Anchor.between(left, right),
            join.transition?.id)
    }
    val pictureIDs = video.map { it.id }.toSet()
    val linked = links.filter { !it.members.isDisjoint(pictureIDs) }.flatMap { it.members }.toSet()
    val independent = audio.filter { it.id !in linked }
    val starts = independent.groupBy { it.start }
    val ends = independent.groupBy { it.end }
    val cuts = pictures.toMutableList()
    for (time in ends.keys.sorted()) {
        val left = ends[time] ?: continue
        val right = starts[time] ?: continue
        val pairs = TrackTimeline.neighbors(left.map { CutNeighbor(it.id, it.occurrenceID) }, right.map { CutNeighbor(it.id, it.occurrenceID) })
        cuts += pairs.map { (a, b) -> TimelinePasteTarget(TimelinePasteTarget.Kind.sound, time, TimelinePasteTarget.Anchor.between(a, b)) }
    }
    if (video.isEmpty() && audio.isEmpty()) return cuts
    val coverage = videoCoverage
    val first = coverage.firstOrNull()
    val last = coverage.lastOrNull()
    if (first != null && last != null) {
        cuts += TimelinePasteTarget(TimelinePasteTarget.Kind.picture, first.start, TimelinePasteTarget.Anchor.start)
        cuts += TimelinePasteTarget(TimelinePasteTarget.Kind.picture, last.end, TimelinePasteTarget.Anchor.end)
    }
    // A score can extend beyond the last picture, or precede the first.
    // Both the picture edge and the actual project edge are useful targets.
    val outerKind = if (video.isEmpty()) TimelinePasteTarget.Kind.sound else TimelinePasteTarget.Kind.project
    if (first?.start != EditTime.zero) cuts += TimelinePasteTarget(outerKind, EditTime.zero, TimelinePasteTarget.Anchor.start)
    if (last?.end != duration) cuts += TimelinePasteTarget(outerKind, duration, TimelinePasteTarget.Anchor.end)
    return cuts
}

/// Snapshot only the chosen media. Collapse empty space between unrelated
/// selections while retaining real overlaps and offsets inside linked groups.
fun TrackTimeline.clipboardFragment(selected: TimelineSelection, keepGaps: Boolean): TimelineClipboard {
    val fragment = copy()
    fragment.video = fragment.video.filter { it.id in selected.video }
    fragment.audio = fragment.audio.filter { it.id in selected.audio }
    fragment.placements = placementsKeeping(selected.video)
    fragment.pruneRelations()
    val visibleMarks = fragment.resolvedBookmarks.map { it.id }.toSet()
    fragment.bookmarks = fragment.bookmarks.filterNot { it.anchor is BookmarkAnchor.project || it.id !in visibleMarks }
    val spans = (fragment.video.map { it.id to it.interval } + fragment.audio.map { it.id to it.interval }).toMap()
    val occupied = spans.values.toMutableList()
    occupied += fragment.generated.map { it.interval }
    for (group in fragment.links) {
        val ranges = group.members.mapNotNull { spans[it] }
        val start = ranges.minOfOrNull { it.start }
        val end = ranges.maxOfOrNull { it.end }
        if (start != null && end != null) occupied += EditInterval(start, end)
    }
    var islands = TrackTimeline.union(occupied)
    if (keepGaps && islands.isNotEmpty()) islands = listOf(EditInterval(islands.first().start, islands.last().end))
    islands.firstOrNull()?.let { first ->
        var island = 0
        var offset = EditTime.zero - first.start
        val starts = HashMap<UUID, EditTime>()
        for ((id, range) in spans.entries.sortedBy { it.value.start }) {
            while (island + 1 < islands.size && range.start >= islands[island + 1].start) {
                offset -= islands[island + 1].start - islands[island].end
                island += 1
            }
            starts[id] = range.start + offset
        }
        fragment.video = fragment.video.map { it.copy(start = starts.getValue(it.id)) }
        fragment.audio = fragment.audio.map { it.copy(start = starts.getValue(it.id)) }
        fragment.pruneRelations()
    }
    if (fragment.audio.none { !it.subtitleWords.isNullOrEmpty() }) fragment.subtitles = null
    val pictureIDs = fragment.video.map { it.id }.toSet()
    val soundIDs = fragment.audio.map { it.id }.toSet()
    val inserts = fragment.links.any { !it.members.isDisjoint(pictureIDs) && !it.members.isDisjoint(soundIDs) }
    return TimelineClipboard(fragment, inserts)
}

private fun TrackTimeline.placementsKeeping(ids: Set<UUID>): List<VideoPlacementRegion> {
    val kept = placements.filter { ids.containsAll(it.participants) }.toMutableList()
    for (region in placements) {
        val shared = region.participants intersect ids
        if (shared.size < 2 || kept.any { it.participants == shared }) continue
        kept += VideoPlacementRegion(participants = shared, videos = region.videos.filterKeys { it in shared })
    }
    return kept
}

/** Swift `self = other` for this class-backed model. */
private fun TrackTimeline.replaceContents(other: TrackTimeline) {
    video = other.video; audio = other.audio; links = other.links; bookmarks = other.bookmarks
    placements = other.placements; transitions = other.transitions; generated = other.generated; subtitles = other.subtitles
    captureBase = other.captureBase
}

/// Capture the very fragments that are lifted (a range split creates new IDs).
/// The caller publishes the clipboard only after the project save succeeds.
fun TrackTimeline.cutToClipboard(targets: TimelineSelection, preserving: EditTime): TimelineCut? {
    val point = preserving
    val scoped = scoped(targets)
    val clip = scoped.timeline.clipboardFragment(scoped.selection, keepGaps = targets.range != null)
    if (clip.isEmpty) return null
    scoped.timeline.placements = scoped.timeline.placementsKeeping(scoped.timeline.video.map { it.id }.toSet() - scoped.selection.video)
    val position = scoped.timeline.remove(scoped.selection, preserving = point) ?: point
    val copiedMarks = clip.timeline.bookmarks.map { it.id }.toSet()
    val remainingMarks = scoped.timeline.resolvedBookmarks.map { it.id }.toSet()
    scoped.timeline.bookmarks = scoped.timeline.bookmarks.filterNot { it.id in copiedMarks && it.id !in remainingMarks }
    replaceContents(scoped.timeline)
    return TimelineCut(clip, position)
}

fun TrackTimeline.preparePaste(clipboard: TimelineClipboard, at: EditTime, target: TimelinePasteTarget?): EditTime {
    val point = at
    val length = clipboard.timeline.duration
    if (target == null) {
        val position = maxOf(EditTime.zero, minOf(duration, point))
        if (clipboard.insertsTime) return insertPictureTime(length, position)
        return position
    }
    val hasPicture = clipboard.timeline.video.isNotEmpty()
    var destination = target.time
    when (val anchor = target.anchor) {
        TimelinePasteTarget.Anchor.start, TimelinePasteTarget.Anchor.gap -> {
            if (hasPicture) {
                val site = if (anchor == TimelinePasteTarget.Anchor.gap) VideoInsertionSite.gap(target.time) else VideoInsertionSite.start
                val placement = site.placement(this, length)
                destination = if (target.kind == TimelinePasteTarget.Kind.project) EditTime.zero else placement.start
                if (placement.ripple > EditTime.zero) {
                    if (anchor == TimelinePasteTarget.Anchor.gap) insertTime(placement.ripple, placement.rippleAt, atPictureGap = true)
                    else move(TimelineSelection(video = video.map { it.id }.toSet()), placement.ripple)
                }
            } else if (target.kind == TimelinePasteTarget.Kind.sound) {
                val leadingSilence = audio.minOfOrNull { it.start } ?: EditTime.zero
                move(entireSelection(), maxOf(EditTime.zero, length - leadingSilence))
            }
        }
        TimelinePasteTarget.Anchor.end -> {}
        is TimelinePasteTarget.Anchor.between -> {
            if (hasPicture) {
                destination = insertPictureTime(length, destination,
                    pictureOnRight = if (target.kind == TimelinePasteTarget.Kind.picture) anchor.right else null, transition = target.transition)
            } else if (target.kind == TimelinePasteTarget.Kind.sound) {
                val chain = moveChoices(selection(of = anchor.right)).right.audio
                val tail = audio.filter { it.id in chain && it.start >= destination }.map { it.id }.toSet()
                split(destination, TimelineSelection(audio = tail))
                audio = audio.map { if (it.id in tail) it.copy(start = it.start + length) else it }
            }
        }
    }
    return destination
}

/// Open the picture sequence and its production sound, leaving independent
/// music continuous. If the insertion separates a dissolve, use its full handles.
private fun TrackTimeline.insertPictureTime(length: EditTime, at: EditTime, pictureOnRight: UUID? = null, transition: UUID? = null): EditTime {
    val crossing = generated.filter { it.interval.start < at && at < it.interval.end }.map { it.owner }
    val broken = (crossing + listOfNotNull(transition)).toSet()
    for (id in transitions.map { it.id }) {
        if (id !in broken) continue
        val edit = EditState(this)
        val join = edit.layout.joins.firstOrNull { it.transition?.id == id } ?: continue
        replaceContents(edit.restoringStraightCut(join.index).timeline)
    }
    val position = pictureOnRight?.let { id -> video.firstOrNull { it.id == id }?.start } ?: at
    split(position, TimelineSelection(video = video.map { it.id }.toSet()))
    val tail = expanded(TimelineSelection(video = video.filter { it.start >= position }.map { it.id }.toSet()))
    video = video.map { if (it.id in tail.video) it.copy(start = it.start + length) else it }
    audio = audio.map { if (it.id in tail.audio) it.copy(start = it.start + length) else it }
    bookmarks = bookmarks.map { mark ->
        val anchor = mark.anchor
        if (anchor is BookmarkAnchor.project && anchor.time >= position) mark.copy(anchor = BookmarkAnchor.project(anchor.time + length)) else mark
    }
    return position
}


