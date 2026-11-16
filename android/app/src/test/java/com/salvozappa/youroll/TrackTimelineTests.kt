// Port of Tests/TrackTimelineTests.swift (in-scope parts; see the skips noted inline)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrackTimelineTests {
    private fun lcg(seed: ULong): () -> Double {
        var state = seed
        return {
            state = state * 6364136223846793005uL + 1uL
            (state shr 11).toDouble() / (1L shl 53).toDouble()
        }
    }

    @Test fun independentTimeline() {
        val source = SourceMedia(filename = "source.mp4", duration = 4.0)
        val a = TimelineClip(sourceID = source.id, start = 0.0, end = 2.0)
        val b = TimelineClip(sourceID = source.id, start = 2.0, end = 4.0)
        val sequential = EditState(listOf(a, b))
        val baseline = TrackTimeline.sequence(sequential)
        baseline.validate(listOf(source))
        require(baseline == TrackTimeline.sequence(sequential), "Conversion gives stable AV/link identities")
        require(baseline.duration == et(4), "Independent timeline preserves complete source duration")
        val first = baseline.selection(of = a.id)
        val splitElsewhere = TimelineEditing.split(sequential, 3.0, first.ids).timeline
        require(splitElsewhere.video.map { it.start } == listOf(zero, et(2), et(3)) &&
            splitElsewhere.audio.map { it.start } == listOf(zero, et(2), et(3)) &&
            splitElsewhere.video.first() == baseline.video.first() && splitElsewhere.duration == baseline.duration,
            "A selection in the first clip still allows one synchronized cut in the second")
        require(TimelineEditing.split(sequential, 4.0, first.ids) == sequential, "An old selection cannot create an empty clip at the project end")
        for ((before, after) in listOf(0.5 to 0.0, 2.0 to 0.0, 3.25 to 1.25, 4.0 to 2.0)) {
            val cut = baseline.copy()
            require(cut.remove(first, et(before)) == et(after), "Ripple deletion follows surviving content, or lands at the cut when current content is removed")
        }
        val endCut = baseline.copy()
        require(endCut.remove(baseline.selection(of = b.id), et(0.75)) == et(0.75), "A later deletion leaves the playhead untouched")
        val overlaidCut = baseline.copy()
        val cover = baseline.video[0].let { it.copy(content = it.content.copy(id = newUUID()), start = et(0.25), layer = 99) }
        overlaidCut.video = overlaidCut.video + cover
        require(overlaidCut.remove(first, et(1)) == zero, "Deleted linked footage lands at its cut even under a stationary detached overlay")
        require(first.video == setOf(a.id) && first.audio.size == 1, "Linked selection includes exactly its picture and sound")
        val ripple = baseline.copy()
        ripple.remove(first)
        require(ripple.duration == et(2) && ripple.video[0].start == zero && ripple.audio[0].start == zero, "Linked deletion closes gap in both components")
        val separate = baseline.copy()
        separate.unlink(first)
        val splitSound = TimelineEditing.split(EditState(separate), 1.0, setOf(separate.audio[0].id)).timeline
        require(splitSound.video == separate.video && splitSound.audio.map { it.start } == listOf(zero, et(1), et(2)),
            "An explicitly selected detached audio part at the playhead still splits independently")
        require(separate.video == baseline.video && separate.audio.map { it.mapping } == baseline.audio.map { it.mapping } &&
            separate.audio.map { it.start } == baseline.audio.map { it.start }, "Unlink changes no timing, samples or settings")
        val audioOnly = separate.copy()
        val soundPosition = audioOnly.remove(separate.selection(of = baseline.audio[0].id), et(3))
        require(soundPosition == et(3), "Detached audio deletion keeps the absolute playhead")
        require(audioOnly.video == baseline.video && audioOnly.audio == listOf(baseline.audio[1]) && audioOnly.duration == et(4),
            "Deleting unlinked audio leaves silence without moving any video")
        val videoOnly = separate.copy()
        val picturePosition = videoOnly.remove(separate.selection(of = a.id), et(1))
        require(picturePosition == et(1), "Detached video deletion leaves the playhead in the resulting gap")
        require(videoOnly.audio == separate.audio && videoOnly.video == listOf(baseline.video[1]) && videoOnly.duration == et(4),
            "Deleting unlinked video leaves picture gap without moving sound")
        val moving = separate.copy()
        moving.move(moving.selection(of = a.id), et(2.5))
        require(moving.audio == separate.audio && moving.video[0].start == et(2.5), "Moving detached video leaves audio immutable")
        require(moving.compile().any { it.video.size == 2 && it.video.last() == a.id }, "Overlap compiler orders newly moved video in foreground")
        moving.move(moving.selection(of = a.id), et(-30))
        require(moving.video[0].start == zero, "Move clamps whole group at project start")
        val linkedMove = baseline.copy()
        linkedMove.move(first, et(1))
        require(linkedMove.video[0].start == linkedMove.audio[0].start, "Moving linked AV preserves sync")
        val bed = baseline.copy()
        bed.unlink(bed.selection(of = b.id))
        val detached = bed.audio[1]
        bed.remove(first)
        require(detached in bed.audio, "Deleting a linked clip never cuts or shifts detached audio")
        val marks = baseline.copy()
        marks.addBookmark(et(1))
        require(marks.snapTarget(1.02, 60.0) == 1.0, "Stationary source marks are snap targets")
        require(marks.snapTarget(1.02, 60.0, excluding = first.ids) == null, "Moving footage never snaps to its own attached bookmark")
        val selected = marks.selection(of = a.id)
        marks.split(et(1), selected)
        require(marks.duration == baseline.duration && marks.video.size == 3 && marks.audio.size == 3 && marks.links.size == 3,
            "Split preserves all source and creates independent linked pairs")
        require(marks.resolvedBookmarks.size == 1 && marks.resolvedBookmarks[0].time == et(1), "Bookmark at a split is not duplicated")
        val right = marks.video.first { it.start == et(1) }
        marks.move(marks.selection(of = right.id), et(4))
        require(marks.resolvedBookmarks[0].time == et(5), "Source bookmark follows its occurrence after split and move")
        marks.validate(listOf(source))
        val range = baseline.entireSelection(iv(0.5, 3.5))
        val scoped = baseline.scoped(range)
        val rangePosition = scoped.timeline.remove(scoped.selection, et(3.75))
        require(rangePosition == et(0.75), "Range deletion preserves the source frame after scoping splits")
        require(abs(scoped.timeline.duration.seconds - 1) < 0.00001, "Range deletion splits linked AV and closes the exact interval")
        require(scoped.timeline.video.map { it.content.start } == listOf(0.0, 3.5) && scoped.timeline.video.map { it.content.end } == listOf(0.5, 4.0),
            "Range deletion preserves exact outer source pieces")
        val enhancements = baseline.copy()
        val audioID = baseline.audio[0].id
        val gain = AudioEffect(kind = EffectKind.gain, amount = -6.0)
        enhancements.enhance(listOf(gain), TimelineSelection(audio = setOf(audioID), range = iv(0.5, 1.5)))
        enhancements.enhance(listOf(AudioEffect(kind = EffectKind.gain, amount = -3.0)), TimelineSelection(audio = setOf(audioID), range = iv(1, 2)))
        require(enhancements.audio[0].enhancements.size == 2 && enhancements.audio[0].enhancements[0].interval == iv(0.5, 1.0),
            "Partial enhancement replaces intersection without double processing")
        val unchanged = enhancements.copy()
        enhancements.enhance(listOf(gain), TimelineSelection(audio = setOf(audioID), range = iv(3, 4)))
        require(enhancements == unchanged, "Nonintersecting enhancement does not broaden to whole clip")
        enhancements.validate(listOf(source))
        val insertion = baseline.copy()
        insertion.insertTime(et(1), et(1))
        require(insertion.duration == et(5) && insertion.video.size == 3, "Insert time preserves crossing pieces and shifts right side")
        insertion.validate(listOf(source))
        // Skipped: copy/paste (TimelinePaste.swift is not ported).
        val viewport = TimelineViewport(402.0, 960.0, 0.01)
        require(viewport.fits && viewport.x(0.0, 0.0) >= 0 && viewport.x(960.0, 0.0) <= 402 && viewport.minimumScale < 0.4,
            "Whole-project fit shows both ends of a sixteen-minute project and escapes 201-second limit")
        val capture = source.copy(events = listOf(CaptureEvent(time = 1.2, kind = "bookmark", detail = "Bookmark"),
            CaptureEvent(time = 4.1, kind = "bookmark", detail = "After recovery end")))
        val captureMarks = baseline.copy()
        captureMarks.includeCaptureBookmarks(capture); captureMarks.includeCaptureBookmarks(capture)
        require(captureMarks.resolvedBookmarks.size == 1 && captureMarks.resolvedBookmarks[0].time == et(1.2), "Capture bookmarks import once and exclude unrecovered footage")
        val encoded = SwiftJSON.encodeToString(TrackTimeline.serializer(), marks)
        val restored = SwiftJSON.decodeFromString(TrackTimeline.serializer(), encoded)
        require(restored == marks, "Independent positions, links and bookmarks round-trip exactly")
        for (rate in listOf(24.0, 25.0, 30.0, 50.0, 60.0, 24000.0 / 1001, 30000.0 / 1001, 60000.0 / 1001)) {
            val frame = et(1 / rate)
            require(abs(frame.seconds - 1 / rate) < 1e-10, "Canonical clock exactly represents standard frame durations")
        }
        val speed = a.copy(speed = speedJson(1.5))
        val map = SourcePlaybackMap(speed)
        val retimedCut = TrackTimeline.sequence(EditState(listOf(speed, b)))
        val priorPosition = retimedCut.video[1].start + et(0.5)
        val retimedPosition = retimedCut.remove(retimedCut.selection(of = a.id), priorPosition)
        require(retimedPosition == et(0.5), "Deletion rebases by playback duration rather than source seconds")
        require(abs(map.source(et(0.5))!! - 0.75) < 0.00001, "Source mapping preserves speed independently of placement")
        var three = TrackTimeline(listOf(a, b, TimelineClip(sourceID = source.id, start = 0.0, end = 2.0)))
        var threeEdit = EditState(three)
        threeEdit = threeEdit.settingTransition(ClipTransition(duration = 0.5), 1)
        threeEdit = threeEdit.settingTransition(ClipTransition(duration = 0.5), 2)
        three = threeEdit.timeline.copy()
        val middleID = three.video[1].id
        val lastID = three.video[2].id
        val priorLast = three.video[2].start
        val afterOverlap = three.remove(three.selection(of = middleID), priorLast + et(1))
        require(afterOverlap == three.video.first { it.id == lastID }.start + et(1), "Transition handles do not cause playhead drift after deletion")
        require(three.video[1].start == three.video[0].end && three.duration == et(4), "Deleting a linked clip between two transitions closes only the newly vacated gap")
        val random = lcg(21uL)
        repeat(2000) {
            val clip = TimelineClip(sourceID = source.id, start = 0.0, end = 4.0,
                speed = speedJson(0.25 + random() * 3.75), pauses = pausesJson(0.5 + random() * 0.3 to 1.2 + random() * 0.3))
            val graph = TrackTimeline(listOf(clip))
            val end = graph.duration
            val at = EditTime(maxOf(1L, (end.ticks.toDouble() * (0.1 + random() * 0.8)).toLong()))
            graph.split(at, graph.entireSelection())
            require(graph.video[0].end == at && graph.video[1].start == at && graph.video[1].end == end, "Video split conserves exact ticks through speed and pauses")
            require(graph.audio[0].end == at && graph.audio[1].end == end, "Audio and picture split stay sample-aligned")
            val picked = graph.scoped(graph.entireSelection(EditInterval(at, end)))
            require(picked.selection.video.size == 1 && picked.selection.audio.size == 1, "Scope never selects only one linked component because of rounding")
        }
        // Swift compares against LegacyTimelineLayout (Tests/LegacyTimelineOracle.swift), a frozen
        // copy of the clip-sequence layout; `TimelineLayout(clips)` is the same algorithm.
        for (style in TransitionStyle.allCases) {
            for (hold in listOf(0.0, 1.5)) {
                for (offset in listOf(-0.7, 0.0, 0.7)) {
                    val withTransitions = listOf(
                        a.copy(openingTransition = ClipTransition(style = style, duration = 0.7, hold = hold, audioOffset = offset, audioBlend = 1.0),
                            transition = ClipTransition(nextClipID = b.id, style = style, duration = 1.2, hold = hold, audioOffset = offset, audioBlend = 1.0)),
                        b.copy(transition = ClipTransition(style = style, duration = 0.7, hold = hold, audioOffset = offset, audioBlend = 1.0)))
                    val oracle = TimelineLayout(withTransitions)
                    val converted = TrackTimeline(withTransitions)
                    require(abs(converted.duration.seconds - oracle.duration) <= 4.0 / 240000, "Transition conversion preserves full duration: $style")
                    for ((actual, expected) in converted.video.zip(oracle.placements)) {
                        require(abs(actual.start.seconds - expected.start) <= 4.0 / 240000, "Transition conversion preserves source placement")
                    }
                    val compiled = converted.compile()
                    require(compiled.first().interval.start == zero && compiled.last().interval.end == converted.duration, "Compiler covers transition holds and tails")
                    for ((x, y) in compiled.zipWithNext()) require(x.interval.end == y.interval.start, "Compiler has no instruction gaps")
                }
            }
        }
        val reveal = TrackTimeline(listOf(TimelineClip(sourceID = source.id, start = 1.0, end = 3.0)))
        val revealID = reveal.video[0].id
        reveal.move(reveal.entireSelection(), et(2))
        reveal.extend(reveal.selection(of = revealID), left = true, by = et(1), sources = listOf(source))
        require(reveal.video[0].start == et(1) && reveal.video[0].content.start == 0.0 && reveal.audio[0].mapping == reveal.video[0].mapping,
            "Outward trim restores source samples and linked sound")
        reveal.unlink(reveal.selection(of = revealID)); val detachedID = reveal.audio[0].id
        reveal.move(TimelineSelection(audio = setOf(detachedID)), et(0.5))
        reveal.restoreSync(detachedID)
        require(reveal.audio[0].start == reveal.video[0].start && reveal.links.isEmpty(), "Restore sync is explicit and does not silently relink")
        reveal.closeGap(et(0.5))
        require(reveal.video[0].start == zero && reveal.audio[0].start == zero, "Close gap removes only time empty on every lane")
    }

    @Test fun moveCompanions() {
        val source = SourceMedia(filename = "companions.mov", duration = 18.0)
        val clips = (0 until 5).map { i -> TimelineClip(sourceID = source.id, start = (3 * i).toDouble(), end = (3 * i + 3).toDouble()) }
        val timeline = TrackTimeline(clips)
        val seed = timeline.selection(of = clips[1].id)
        val later = timeline.selection(ids = clips.takeLast(2).map { it.id }.toSet())
        timeline.move(later, EditTime(1))
        val overlay = TimelineClip(sourceID = source.id, start = 0.0, end = 10.0)
        timeline.video = timeline.video + VideoTimelineItem(newUUID(), et(2), overlay, 12)
        val music = SourceMedia(filename = "score.m4a", duration = 16.0, audioOnly = true)
        timeline.insertAudio(music, zero)

        val choices = timeline.moveChoices(seed)
        fun pictures(vararg indices: Int) = timeline.selection(ids = indices.map { clips[it].id }.toSet())
        require(choices.selected == seed && choices.left == pictures(0, 1) && choices.right == pictures(1, 2) && choices.both == pictures(0, 1, 2),
            "Move includes the chosen sides with linked sound and stops at a one-tick gap, even under an overlay and score")
        val multiple = timeline.moveChoices(pictures(0, 4))
        require(multiple.left == pictures(0, 3, 4) && multiple.right == pictures(0, 1, 2, 4), "Each selected island has its own left and right neighbors")
        val moved = timeline.copy()
        moved.move(choices.right, et(0.75))
        for (picture in timeline.video) {
            val delta = if (picture.id in choices.right.video) et(0.75) else zero
            require(moved.video.firstOrNull { it.id == picture.id }?.start == picture.start + delta, "Move preserves relative picture offsets")
        }
        for (sound in timeline.audio) {
            val delta = if (sound.id in choices.right.audio) et(0.75) else zero
            require(moved.audio.firstOrNull { it.id == sound.id }?.start == sound.start + delta, "Linked audio moves with its picture, independent music stays")
        }
        val detached = TrackTimeline(clips)
        detached.unlink(detached.entireSelection())
        val audio = detached.moveChoices(detached.selection(of = detached.audio[2].id))
        require(audio.both.video.isEmpty() && audio.both.audio == detached.audio.map { it.id }.toSet(), "Independent sound can move with adjacent sound without selecting pictures")
        val picture = detached.moveChoices(detached.selection(of = clips[2].id))
        require(picture.both.audio.isEmpty() && picture.both.video.size == 5, "Unlinked pictures do not recruit sound at coincident cuts")
        val stacked = detached.copy()
        stacked.video = stacked.video + VideoTimelineItem(newUUID(), et(1), overlay, 12)
        stacked.split(et(6), stacked.entireSelection())
        val overlayIDs = stacked.video.filter { it.layer == 12 }.map { it.id }.toSet()
        val overlayChoices = stacked.moveChoices(stacked.selection(of = overlay.id))
        require(overlayChoices.right.video == overlayIDs && overlayIDs.size == 2, "An overlay cut exactly at a main edit follows its own continuation only")
        require(stacked.moveChoices(stacked.selection(of = clips[2].id)).both.video == picture.both.video, "The main edit stays on its own layer at a stacked cut")
        val end = detached.moveChoices(detached.selection(of = clips.last().id))
        require(end.available(TimelineMoveScope.left) && !end.available(TimelineMoveScope.right) && !end.available(TimelineMoveScope.both),
            "A direction with no additional neighbors is unavailable")
        val joinedClips = clips.take(3).toMutableList()
        joinedClips[0] = joinedClips[0].copy(transition = ClipTransition(nextClipID = clips[1].id, style = TransitionStyle.dissolve, duration = 1.0))
        val joined = TrackTimeline(joinedClips)
        val relation = joined.transitions[0]
        val fromLast = joined.moveChoices(joined.selection(of = clips[2].id)).left
        require(fromLast == joined.entireSelection(), "Walk through cuts and an overlapping transition transitively")
        joined.move(fromLast, et(2))
        require(joined.transitions.firstOrNull()?.origin == relation.origin + et(2), "A transition follows its moving pair")
        val scoped = detached.scoped(TimelineSelection(video = setOf(clips[1].id), range = iv(4, 5)))
        val range = scoped.timeline.moveChoices(scoped.selection)
        require(range.selected.video.size == 1 && range.left.video.size == 3 && range.right.video.size == 5, "Marked ranges recruit outward from their scoped boundaries")
    }

    @Test fun multiSelection() {
        val source = SourceMedia(filename = "multi.mp4", duration = 12.0)
        val clips = (0 until 3).map { TimelineClip(sourceID = source.id, start = (it * 4).toDouble(), end = ((it + 1) * 4).toDouble()) }
        val original = TrackTimeline(clips)
        val all = original.entireSelection()
        var chosen = original.selection(of = clips[0].id).ids
        chosen = original.toggling(clips[1].id, chosen)
        require(chosen.size == 4, "Long press adds another linked pair")
        chosen = original.toggling(original.audio[0].id, chosen)
        require(chosen == original.selection(of = clips[1].id).ids, "Long press removes the whole selected link, keeping other selections")
        val together = original.joining(all)
        require(together.timeline.links.size == 1 && together.timeline.links[0].members == all.ids, "Join horizontal chains before vertical linking")
        require(together.timeline.video == original.video && together.timeline.audio == original.audio, "Join preserves every sample map, source identity, setting and follower")
        together.timeline.validate(listOf(source))
        val encoded = SwiftJSON.encodeToString(TrackTimeline.serializer(), together.timeline)
        val decoded = SwiftJSON.decodeFromString(TrackTimeline.serializer(), encoded)
        require(decoded == together.timeline, "Multi-part link persists exactly")
        val project = Project("Link history")
        project.sources = listOf(source); project.history = listOf(EditState(original)); project.historyCursor = 0
        project.commit(EditState(together.timeline)); project.undo()
        require(project.edit.timeline == original, "One undo restores all original groups")
        project.redo(); require(project.edit.timeline == together.timeline, "Redo restores the compound link")
        val detached = original.copy(); detached.unlink(all)
        val videos = TimelineSelection(video = all.video)
        val audios = TimelineSelection(audio = all.audio)
        val v = detached.joining(videos)
        val a = detached.joining(audios)
        require(v.timeline.selection(of = clips[1].id).ids == all.video, "Video-only horizontal glue selects every joined section")
        require(a.timeline.selection(of = detached.audio[1].id).ids == all.audio, "Audio-only horizontal glue selects every joined section")
        val removeV = v.timeline.copy(); removeV.remove(v.selection)
        require(removeV.video.isEmpty() && removeV.audio == detached.audio && removeV.duration == et(12), "Removing a horizontal video group leaves detached sound and time intact")
        val removeA = a.timeline.copy(); removeA.remove(a.selection)
        require(removeA.audio.isEmpty() && removeA.video == detached.video, "Removing a horizontal audio group leaves picture intact")
        val bulk = original.copy()
        val firstLast = original.selection(ids = setOf(clips[0].id, clips[2].id))
        bulk.remove(firstLast)
        require(bulk.video.size == 1 && bulk.audio.size == 1 && bulk.duration == et(4) && bulk.video[0].start == zero, "Bulk linked deletion closes both vacated ranges exactly once")
        // Re-link only the first original pair; downstream audio remains a separate group.
        val pair = TimelineSelection(video = setOf(clips[0].id), audio = setOf(original.audio[0].id))
        var horizontalBed = original.copy()
        horizontalBed.unlink(horizontalBed.selection(of = clips[1].id)); horizontalBed.unlink(horizontalBed.selection(of = clips[2].id))
        horizontalBed = horizontalBed.joining(TimelineSelection(audio = horizontalBed.audio.drop(1).map { it.id }.toSet())).timeline
        val bedAudio = horizontalBed.audio.drop(1)
        horizontalBed.remove(pair)
        require(horizontalBed.audio == bedAudio, "AV ripple never shifts a detached horizontal audio group")
        val horizontal = detached.joining(videos).timeline
        val unrelatedSplit = horizontal.copy()
        unrelatedSplit.split(et(6), TimelineSelection(audio = setOf(unrelatedSplit.audio[1].id)))
        require(unrelatedSplit.links == horizontal.links, "Splitting unrelated audio preserves a horizontal video group")
        val splitGroup = together.timeline.copy()
        splitGroup.split(et(6), together.selection)
        require(splitGroup.links.size == 2 && splitGroup.links.all { it.members.size == 4 }, "Splitting a joined sequence separates the two sides, with AV sync preserved")
        splitGroup.validate(listOf(source))
        fun rejected(timeline: TrackTimeline, scope: TimelineSelection, expected: TimelineLinkError.Case) {
            val error = assertThrowing<TimelineLinkError>("Invalid Link unexpectedly accepted") { timeline.linkPlan(scope) }
            require(error.case == expected, "Link reports the correct selection problem")
        }
        rejected(detached, TimelineSelection(), TimelineLinkError.Case.tooFew)
        rejected(detached, TimelineSelection(video = setOf(clips[0].id)), TimelineLinkError.Case.tooFew)
        rejected(detached, TimelineSelection(video = setOf(clips[0].id, clips[2].id)), TimelineLinkError.Case.nonContiguous)
        val overlap = detached.copy()
        overlap.move(TimelineSelection(video = setOf(clips[1].id)), et(-1))
        rejected(overlap, videos, TimelineLinkError.Case.nonContiguous)
        rejected(detached, TimelineSelection(video = setOf(clips[0].id), audio = setOf(detached.audio[2].id)), TimelineLinkError.Case.noIntersection)
        // Different internal split points, identical coverage: no unnecessary prompt.
        val partitioned = TrackTimeline(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 12.0)))
        partitioned.unlink(partitioned.entireSelection())
        partitioned.split(et(4), TimelineSelection(video = setOf(partitioned.video[0].id)))
        partitioned.split(et(8), TimelineSelection(video = partitioned.video.map { it.id }.toSet()))
        val plan = partitioned.linkPlan(partitioned.entireSelection())
        require(!plan.needsIntersection, "Horizontal aggregation makes 3 video sections match one equal-length audio section")
        val joinedPartition = partitioned.joining(plan.selection)
        require(joinedPartition.timeline.links[0].members.size == 4, "One link can glue differently partitioned AV")
        // Same length is not enough: a shifted audio range requires confirmation.
        val mismatch = TrackTimeline(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 8.0)))
        mismatch.unlink(mismatch.entireSelection())
        mismatch.move(TimelineSelection(audio = setOf(mismatch.audio[0].id)), et(2))
        val before = mismatch.copy()
        val mismatchPlan = mismatch.linkPlan(mismatch.entireSelection())
        require(mismatchPlan.needsIntersection && mismatchPlan.intersection == iv(2, 8), "Shifted AV proposes only their shared time")
        rejected(mismatch, TimelineSelection(video = emptySet()), TimelineLinkError.Case.tooFew)
        val confirmation = assertThrowing<TimelineLinkError>("Intersection linked without confirmation") { mismatch.joining(mismatch.entireSelection()) }
        require(confirmation.case == TimelineLinkError.Case.confirmationRequired, "Intersection is explicitly opt-in")
        require(mismatch == before, "Planning/cancelling leaves the original edit byte-for-byte unchanged")
        val clipped = mismatch.joining(mismatch.entireSelection(), intersectionOnly = true)
        clipped.timeline.validate(listOf(source))
        require(clipped.timeline.duration == before.duration && clipped.timeline.video.size == 2 && clipped.timeline.audio.size == 2,
            "Intersection keeps both outside tails and the full timeline")
        require(clipped.timeline.interval(clipped.selection) == iv(2, 8) && clipped.selection.ids.size == 2, "Only the shared pieces are linked and selected")
        require(clipped.timeline.video.sumOf { it.mapping.duration.ticks } == before.video[0].mapping.duration.ticks &&
            clipped.timeline.audio.sumOf { it.mapping.duration.ticks } == before.audio[0].mapping.duration.ticks, "Intersection conserves every video/audio tick")
        val moveShared = clipped.timeline.copy()
        moveShared.move(clipped.selection, et(1))
        require(moveShared.video[0] == clipped.timeline.video[0] && moveShared.audio[1] == clipped.timeline.audio[1], "Moving the new link leaves outside tails stationary")
        // Multi-part mismatch: horizontal validation happens before vertical clipping.
        val many = detached.copy()
        many.move(audios, et(2))
        val manyJoin = many.joining(many.entireSelection(), intersectionOnly = true)
        manyJoin.timeline.validate(listOf(source))
        require(manyJoin.timeline.interval(manyJoin.selection) == iv(2, 12), "N-part video/audio chains link the complete shared span")
        val retimedVideo = horizontal.copy()
        retimedVideo.replaceVideoContents(retimedVideo.projectedClips.updated(0) { it.copy(speed = speedJson(0.5)) })
        require(retimedVideo.video[1].start == et(8) && retimedVideo.video[2].start == et(12) && retimedVideo.audio == horizontal.audio,
            "Retiming a horizontal video group keeps its joins contiguous without moving detached sound")
        // Snap on exact edit ticks, at both scales, with hysteresis and interior edges.
        val moving = TimelineSelection(video = setOf(clips[0].id))
        val snap = detached.snappedMove(moving, et(3.9), 60.0)
        require(snap.delta == et(4) && snap.snap != null, "Move snaps a leading edge exactly to a neighboring boundary")
        val held = detached.snappedMove(moving, et(4.25), 60.0, latch = snap.snap)
        require(held.delta == et(4), "Snap holds through small finger movement")
        val released = detached.snappedMove(moving, et(4.4), 60.0, latch = held.snap)
        require(released.snap == null && released.delta == et(4.4), "Snap releases beyond its sticky threshold")
        val trailing = detached.snappedMove(TimelineSelection(video = setOf(clips[2].id)), et(-3.9), 60.0)
        require(trailing.delta == et(-4), "Move also snaps a trailing edge when approaching from the other side")
        val zoomed = detached.snappedMove(moving, et(3.9), 720.0)
        require(zoomed.snap == null, "Snap tolerance is measured in displayed points, not a fixed time window")
        val clamp = detached.snappedMove(moving, et(-100), 60.0)
        require(clamp.delta == zero, "Snapping cannot move any selected part before the start")
        val selfOnly = original.snappedMove(all, et(0.5), 60.0)
        require(selfOnly.snap == null && selfOnly.delta == et(0.5), "Moving all linked parts excludes their own edges")
        val withTransition = EditState(clips.take(2)).settingTransition(ClipTransition(duration = 1.0), 1)
        val transitioned = withTransition.timeline.joining(withTransition.timeline.entireSelection())
        require(transitioned.timeline.transitions == withTransition.timeline.transitions && transitioned.timeline.video == withTransition.timeline.video,
            "Sequential parts joined by an explicit transition can be glued without losing its handles")
        transitioned.timeline.validate(listOf(source))
        val transitionIntersection = withTransition.timeline.copy()
        transitionIntersection.unlink(transitionIntersection.entireSelection())
        // A detached sound ending inside the crossfade must not make Link split
        // its two overlapping participants and silently discard the transition.
        transitionIntersection.audio = listOf(detached.audio[0].copy(mapping = detached.audio[0].mapping.sliced(iv(0.0, 3.5))))
        val unchangedTransition = transitionIntersection.copy()
        rejected(transitionIntersection, transitionIntersection.entireSelection(), TimelineLinkError.Case.transitionBoundary)
        require(transitionIntersection == unchangedTransition, "Refused intersection preserves the complete transition and sources")
        val layers = detached.copy()
        layers.move(TimelineSelection(video = setOf(clips[0].id)), et(5))
        val topBefore = layers.video.sortedBy { it.layer }.map { it.id }
        layers.move(layers.entireSelection(), et(1))
        require(layers.video.sortedBy { it.layer }.map { it.id } == topBefore, "Moving a group preserves its internal foreground/background order")
        val dense = detached.copy()
        dense.addBookmark(et(4.2))
        val targetA = dense.snappedMove(moving, et(3.99), 60.0)
        val targetB = dense.snappedMove(moving, et(4.2), 60.0, latch = targetA.snap)
        require(targetB.delta == et(4.2), "A sticky snap yields to a nearer cut or bookmark under the finger")
    }

    /// Exercise arrangements, not just individual methods: Move is never an
    /// overwrite edit, and every intersecting item must remain visible/hit-testable.
    @Test fun overlapInteraction() {
        val source = SourceMedia(filename = "source.mp4", duration = 12.0)
        val clips = (0 until 3).map { TimelineClip(sourceID = source.id, start = (it * 4).toDouble(), end = ((it + 1) * 4).toDouble()) }
        val original = TrackTimeline(clips)
        fun lanesAreDisjoint(timeline: TrackTimeline) {
            val lanes = timelineLanes(timeline)
            require(lanes.map { it.id }.toSet() == (timeline.video.map { it.id } + timeline.audio.map { it.id }).toSet(), "Every item has a visible lane")
            for ((index, lane) in lanes.withIndex()) {
                for (other in lanes.drop(index + 1)) {
                    if (other.row != lane.row) continue
                    require(lane.interval.intersection(other.interval) == null, "No two overlapping items may paint into the same lane")
                }
            }
            val lastVideo = lanes.filter { !it.audio }.maxOfOrNull { it.row } ?: -1
            require(lanes.filter { it.audio }.all { it.row > lastVideo }, "Audio lanes never paint over video")
            for (part in timeline.compile()) {
                val actual = timeline.video.filter { it.interval.contains(part.interval.start) }.map { it.id }.toSet()
                require(part.video.toSet() == actual, "The compositor retains every overlapping source")
                val row = lanes.filter { !it.audio }.associate { it.id to it.row }
                require(part.video.sortedByDescending { row.getValue(it) } == part.video, "Visible upper/lower lane order agrees with the compositor in every overlap interval")
            }
            timeline.validate(listOf(source))
        }
        fun retainedFootage(timeline: TrackTimeline) {
            for (id in clips.map { it.id }) {
                val pictures = timeline.video.filter { it.occurrenceID == id }.flatMap { it.mapping.runs }
                val sounds = timeline.audio.filter { it.occurrenceID == id }.flatMap { it.mapping.runs }
                for (runs in listOf(pictures, sounds)) {
                    val ordered = runs.sortedBy { it.sourceStart }
                    val index = clips.indexOfFirst { it.id == id }
                    require(ordered.firstOrNull()?.sourceStart == (index * 4).toDouble() && ordered.lastOrNull()?.sourceEnd == ((index + 1) * 4).toDouble(),
                        "The first and last source frame survive compound edits")
                    require(abs(ordered.sumOf { it.sourceEnd - it.sourceStart } - 4) < 0.00001, "Move/split/link preserves every source sample exactly once")
                    for ((left, right) in ordered.zipWithNext()) {
                        require(abs(left.sourceEnd - right.sourceStart) < 0.00001, "Source coverage has no missing or duplicated interior interval")
                    }
                }
            }
        }
        var count = 0
        for (detached in listOf(false, true)) {
            val base = original.copy()
            if (detached) base.unlink(base.entireSelection())
            val selections = listOf(base.selection(of = clips[0].id), base.selection(of = clips[1].id), base.selection(of = clips[2].id),
                TimelineSelection(audio = setOf(base.audio[1].id)),
                base.selection(ids = setOf(clips[0].id, clips[2].id)), base.entireSelection(),
                base.selection(of = clips[1].id, range = iv(5, 7)))
            for (selection in selections) {
                val scoped = base.scoped(selection)
                val moveRows = timelineLanes(scoped.timeline, moving = scoped.selection).associate { it.id to it.row }
                for (offset in listOf(-30.0, -8.0, -4.0, -2.0, -0.1, 0.0, 0.1, 2.0, 4.0, 6.0, 8.0, 12.0)) {
                    val moved = scoped.timeline.copy()
                    val priorVideo = moved.video.associateBy { it.id }
                    val priorAudio = moved.audio.associateBy { it.id }
                    moved.move(scoped.selection, et(offset))
                    require(moved.video.map { it.id }.toSet() == priorVideo.keys && moved.audio.map { it.id }.toSet() == priorAudio.keys, "Move cannot consume or replace another item")
                    for (item in moved.video) require(item.mapping == priorVideo[item.id]?.mapping && item.content == priorVideo[item.id]?.content, "Move cannot trim or rewrite video")
                    for (item in moved.audio) require(item.mapping == priorAudio[item.id]?.mapping, "Move cannot trim audio")
                    lanesAreDisjoint(moved); retainedFootage(moved)
                    val draftLanes = timelineLanes(moved, moving = scoped.selection)
                    for (lane in draftLanes) {
                        require(lane.row == moveRows[lane.id], "Move keeps every card's row fixed as edges cross")
                        require(draftLanes.all { it.id == lane.id || it.row != lane.row || it.interval.intersection(lane.interval) == null }, "Dedicated Move rows never hide another item")
                    }
                    val project = Project("Overlap regression")
                    project.sources = listOf(source); project.history = listOf(EditState(base)); project.historyCursor = 0
                    project.commit(EditState(moved))
                    val reopened = project.roundTripped()
                    require(reopened.edit.timeline == moved, "Overlap and item identities survive project reopening")
                    if (reopened.canUndo) {
                        reopened.undo(); require(reopened.edit.timeline == base, "Undo restores the exact prior arrangement")
                        reopened.redo(); require(reopened.edit.timeline == moved, "Redo restores all overlapping tracks")
                    }
                    // Removing the moved item must reveal the covered item intact.
                    val deleted = moved.copy()
                    deleted.unlink(deleted.entireSelection())
                    deleted.remove(scoped.selection)
                    for (item in deleted.video) {
                        require(item.content == priorVideo[item.id]?.content && item.mapping == priorVideo[item.id]?.mapping, "Deleting a moved overlay does not trim the video beneath it")
                    }
                    lanesAreDisjoint(deleted)
                    count += 1
                }
            }
        }
        require(count == 168, "All overlap arrangements ran")
        // Deterministic mixed sequence with full history: overlaps, scoped splits,
        // unlinks, linked joins, and moves in both directions.
        var sequence = original.copy()
        sequence.move(sequence.selection(of = clips[1].id), et(-2))
        lanesAreDisjoint(sequence)
        sequence.split(et(3), sequence.selection(of = clips[1].id))
        val secondPieces = sequence.video.filter { it.occurrenceID == clips[1].id }.map { it.id }.toSet()
        val joined = sequence.joining(sequence.selection(ids = secondPieces))
        sequence = joined.timeline
        sequence.move(joined.selection, et(1))
        lanesAreDisjoint(sequence); retainedFootage(sequence)
        sequence.unlink(sequence.entireSelection())
        for (index in 0 until 100) {
            val item = sequence.video[index % sequence.video.size]
            sequence.move(TimelineSelection(video = setOf(item.id)), et(if (index % 2 == 0) 0.37 else -0.23))
            lanesAreDisjoint(sequence); retainedFootage(sequence)
        }
        // A small timing adjustment of the lower picture must not change which
        // source covers it. Extending into a NEW overlap should come forward.
        val stacked = original.copy()
        stacked.move(stacked.selection(of = clips[1].id), et(-2))
        val lower = clips[0].id
        val top = clips[1].id
        val priorOrder = stacked.video.sortedWith(TrackTimeline.backToFrontComparator).map { it.id }
        stacked.move(stacked.selection(of = lower), et(1.0 / 30))
        require(stacked.video.sortedWith(TrackTimeline.backToFrontComparator).map { it.id } == priorOrder, "Nudging a covered picture preserves foreground order")
        require(stacked.compile().firstOrNull { lower in it.video && top in it.video }?.video?.last() == top, "A sync nudge cannot replace the visible picture")
        val slowed = original.copy()
        slowed.unlink(slowed.entireSelection())
        slowed.replaceVideoContents(slowed.projectedClips.updated(0) { it.copy(speed = speedJson(0.5)) })
        require(slowed.compile().firstOrNull { clips[0].id in it.video && clips[1].id in it.video }?.video?.last() == clips[0].id,
            "Slowing detached picture reveals its extended footage instead of hiding it")
        lanesAreDisjoint(slowed)
        val reveal = original.copy()
        reveal.split(et(2), reveal.selection(of = lower))
        val child = reveal.video.first { it.start == et(2) }
        reveal.extend(reveal.selection(of = lower), left = false, by = et(1), sources = listOf(source))
        val overlap = reveal.compile().firstOrNull { lower in it.video && child.id in it.video }
        require(overlap?.video?.last() == lower, "Extending into a new overlap reveals the extended footage in front")
        val lanes = timelineLanes(reveal)
        require(lanes.first { it.id == lower }.row < lanes.first { it.id == child.id }.row, "Trim overlap lane order agrees with the compositor")
        var transitions = EditState((0 until 20).map { TimelineClip(sourceID = source.id, start = it * 0.6, end = (it + 1) * 0.6) })
        for (boundary in 1 until 20) transitions = transitions.settingTransition(ClipTransition(duration = 0.2), boundary)
        val transitionLanes = timelineLanes(transitions.timeline)
        require(transitionLanes.filter { !it.audio }.map { it.row }.toSet().size == 1 && transitionLanes.filter { it.audio }.map { it.row }.toSet().size == 1,
            "Twenty sequential crossfades remain one picture row and one linked sound row")
        for ((index, lane) in transitionLanes.withIndex()) {
            require(transitionLanes.drop(index + 1).all { it.row != lane.row || it.interval.intersection(lane.interval) == null },
                "Transition partners have adjacent, independently selectable cards")
        }
    }

    // Swift `transitionPresentationTests`.
    @Test fun transitionPresentation() {
        val source = SourceMedia(filename = "source.mp4", duration = 12.0)
        val clips = listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0), TimelineClip(sourceID = source.id, start = 4.0, end = 8.0))
        for (style in TransitionStyle.allCases) {
            for (offset in listOf(null, -1.0, 0.0, 1.0)) {
                val edit = EditState(clips).settingTransition(ClipTransition(style = style, duration = 1.0, hold = 0.4, audioOffset = offset,
                    audioBlend = if (offset == null) null else 1.5), 1)
                val original = edit.timeline.copy()
                val lanes = timelineLanes(original)
                require(lanes.filter { !it.audio }.map { it.row }.toSet().size == 1 && lanes.filter { it.audio }.map { it.row }.toSet().size == 1,
                    "Sequential $style with audio offset $offset remains compact")
                for (lane in lanes) {
                    val content = if (lane.audio) original.audio.first { it.id == lane.id }.interval else original.video.first { it.id == lane.id }.interval
                    require(lane.contentInterval == content && !lane.interval.isEmpty && lane.interval.start >= content.start && lane.interval.end <= content.end,
                        "Presentation keeps all source handles and a nonempty selectable card")
                }
                require(edit.timeline == original, "Compact presentation cannot change saved edits, timing, or media")
                val transition = edit.layout.joins[1].transition!!
                val removed = edit.removingTool(ToolRequest(VideoTool.transition, edit.layout.joins[1].range, "Join", effectID = transition.id, boundaryIndex = 99))
                require(removed.timeline.transitions.isEmpty() && removed.duration == 8.0 && removed.timeline.video[0].end == removed.timeline.video[1].start,
                    "Removing $style by identity restores a straight cut, including custom audio timing")
                require(removed.timeline.video.map { it.mapping } == original.video.map { it.mapping } && removed.timeline.audio.map { it.mapping } == original.audio.map { it.mapping },
                    "Transition removal never trims or loses source samples")
            }
        }
        val edit = EditState(clips).settingTransition(ClipTransition(duration = 2.0), 1)
        // Skipped: the two Placement tool-region assertions ("A plain transition does not advertise a default
        // Placement effect", "An actual overlay retains its Placement control"): Placement regions are not ported.
        val lanes = timelineLanes(edit.timeline)
        val outgoing = lanes.first { it.id == clips[0].id }
        val incoming = lanes.first { it.id == clips[1].id }
        require(outgoing.interval.end == et(3) && incoming.interval.start == et(3) && edit.duration == 6.0,
            "Two four-second clips with a two-second dissolve meet at 3 s in a six-second timeline")
        require(incoming.mapping.source(et(3.25) - incoming.contentInterval.start) == 5.25,
            "The incoming thumbnail/waveform starts at its correct source position, not the hidden transition handle")
        require(outgoing.trimsLeft && !outgoing.trimsRight && !incoming.trimsLeft && incoming.trimsRight,
            "Internal transition handles cannot masquerade as ordinary trim edges")
        val detached = edit.timeline.copy(); detached.unlink(detached.selection(of = clips[0].id))
        require(timelineLanes(detached).filter { it.audio }.map { it.row }.toSet().size == 2, "Independently editable overlapping sound keeps separate lanes")
        val overlay = edit.timeline.copy()
        val cover = TimelineClip(sourceID = source.id, start = 8.0, end = 10.0)
        overlay.video = overlay.video + VideoTimelineItem(cover.id, et(2), cover, 99)
        val layers = timelineLanes(overlay)
        require(layers.first { it.id == cover.id }.interval == iv(2, 4) && layers.filter { !it.audio }.map { it.row }.toSet().size == 2,
            "A deliberate overlay remains a complete separate card above the compact transition")
        val moving = timelineLanes(edit.timeline, edit.timeline.selection(of = clips[0].id))
        require(moving.all { it.interval == it.contentInterval }, "Move exposes full source extents for accurate placement")
        val removed = edit.timeline.copy(); removed.transitions = emptyList(); removed.pruneRelations()
        require(timelineLanes(removed).filter { !it.audio }.map { it.row }.toSet().size == 2,
            "Removing the transition reveals the actual overlap instead of concealing a layer")
    }

    @Test fun timelineNavigation() {
        val source = newUUID()
        val original = TimelineClip(sourceID = source, start = 0.0, end = 18.0)
        val edit = EditState(listOf(original))
        val text = textOverlay("Title", 8.0, 10.0)
        edit.timeline.updateVideo(0) { it.copy(content = it.content.copy(texts = listOf(text))) }
        val voice = AudioEffect.make(EffectKind.equalizer).copy(amount = 2.0)
        edit.timeline.enhance(listOf(voice), edit.timeline.entireSelection())
        edit.timeline.split(et(6), edit.timeline.entireSelection())
        edit.timeline.split(et(12), edit.timeline.entireSelection())
        val nav = TimelineNavigation(edit)
        require(nav.cutsAndChanges == listOf(0.0, 6.0, 8.0, 10.0, 12.0, 18.0), "Tap includes cuts and title edges")
        require(nav.changes == listOf(0.0, 8.0, 10.0, 18.0), "Long press skips splits of the same sound/image recipe")
        require(nav.destination(0.0, true) == 6.0 && nav.destination(0.0, true, effectsOnly = true) == 8.0, "Separate tap/hold navigation")
        require(nav.destination(9.0, false) == 8.0 && nav.destination(8.0, false) == 6.0, "Previous reaches current section start, then earlier section")
        require(nav.destination(7.999, true) == 10.0, "Floating point seek noise does not repeat the same anchor")
        require(nav.destination(18.0, true) == null && nav.destination(0.0, false) == null, "Endpoints never wrap")
        // Skipped: subtitles (not ported).
        var changed = edit.copy()
        changed.timeline.updateAudio(1) { item ->
            item.copy(enhancements = item.enhancements.updated(0) { region -> region.copy(effects = region.effects.updated(0) { it.copy(amount = 4.0) }) })
        }
        require(TimelineNavigation(changed).changes == listOf(0.0, 6.0, 8.0, 10.0, 12.0, 18.0), "Same effect with different settings counts")
        changed = edit.copy()
        changed.timeline.updateAudio(1) { item ->
            item.copy(enhancements = item.enhancements.updated(0) { region -> region.copy(effects = region.effects.updated(0) { it.copy(id = newUUID()) }) })
        }
        require(TimelineNavigation(changed).changes == nav.changes, "New recipe identity alone isn't an effect change")
        // Skipped: disabled / zero-intensity video effects (VideoEffectSettings is not ported).
        changed.timeline.updateAudio(0) { item ->
            item.copy(enhancements = item.enhancements.updated(0) { region -> region.copy(effects = region.effects.updated(0) { it.copy(enabled = false) }) })
        }
        require(6.0 in TimelineNavigation(changed).changes, "Disabled sound changes the actual active set")
        changed = edit.copy()
        changed.timeline.unlink(changed.timeline.entireSelection())
        changed.timeline.updateAudio(1) { it.copy(volumeDB = -3.0) }
        changed.timeline.move(TimelineSelection(audio = setOf(changed.timeline.audio[1].id)), et(1))
        require(7.0 in TimelineNavigation(changed).changes && 13.0 in TimelineNavigation(changed).changes, "Detached moved audio has project-clock stops")
        val rate = original.copy(speed = speedJson(2.0), texts = listOf(text))
        val retimed = TimelineNavigation(EditState(listOf(rate)))
        require(retimed.changes == listOf(0.0, 4.0, 5.0, 9.0), "Source-anchored title follows speed mapping")
        val removed = original.copy(texts = listOf(text), pauses = pausesJson(4.0 to 6.0))
        val pauseNav = TimelineNavigation(EditState(listOf(removed)))
        require(pauseNav.changes == listOf(0.0, 6.0, 8.0, 16.0) && 4.0 in pauseNav.cutsAndChanges, "Removed silence is a cut, not a recipe change")
        val overlay = edit.copy()
        overlay.timeline.unlink(overlay.timeline.entireSelection())
        overlay.timeline.move(TimelineSelection(video = setOf(overlay.timeline.video[1].id)), et(-2))
        val overlapNav = TimelineNavigation(overlay)
        require(4.0 in overlapNav.changes && 6.0 in overlapNav.changes, "Overlap Placement begins and ends at its actual picture edges")
        val edge = text.copy(start = 8.001, end = 10.001)
        val frameAligned = original.copy(texts = listOf(edge))
        val rounded = TimelineNavigation(EditState(listOf(frameAligned)))
        require(abs(rounded.changes[1] - 241.0 / 30) < 0.00001, "Sub-frame changes land on the first affected output frame")
        val transitionClips = listOf(TimelineClip(sourceID = source, start = 0.0, end = 8.0), TimelineClip(sourceID = source, start = 8.0, end = 18.0))
        val transition = EditState(transitionClips).settingTransition(ClipTransition(duration = 2.0, audioOffset = -1.0, audioBlend = 3.0), 1)
        val transitionNav = TimelineNavigation(transition)
        val join = transition.layout.joins.first { it.transition != null }
        fun has(time: Double, stops: List<Double>) = stops.any { abs(it - time) <= 1.0 / 30 }
        require(has(join.videoRange.start, transitionNav.changes) && has(join.videoRange.endInclusive, transitionNav.changes), "Transition video edges are navigable")
        require(has(join.audioIn.start, transitionNav.changes) && has(join.audioIn.endInclusive, transitionNav.changes), "J/L audio edges are navigable independently")
        require(has(join.time, transitionNav.cutsAndChanges), "Tap also reaches the visible transition join")
        val opening = EditState(listOf(original)).settingTransition(ClipTransition(duration = 2.0, audioOffset = 3.0, audioBlend = 2.0), 0)
        val openingNav = TimelineNavigation(opening)
        val openingJoin = opening.layout.joins.first { it.transition != null }
        require(has(openingJoin.audioIn.start, openingNav.changes), "Opening fade ignores the absent outgoing track when sound starts later")
        require(TimelineNavigation(edit).changes == nav.changes, "Deriving navigation never mutates edit state; Undo's previous snapshot has its original stops")
    }

    @Test fun reviewRegressions() {
        val source = SourceMedia(filename = "source.mp4", duration = 20.0)
        val clips = (0 until 3).map { TimelineClip(sourceID = source.id, start = (it * 4).toDouble(), end = ((it + 1) * 4).toDouble()) }
        val overlapped = TrackTimeline(clips)
        overlapped.move(overlapped.selection(of = clips[1].id), et(-3))
        val fast = EditState(overlapped)
        fast.updateClip(0) { it.copy(speed = speedJson(4.0)) }
        fast.timeline.validate(listOf(source))
        require(fast.timeline.video.first { it.id == clips[1].id }.start == et(1), "Speed preserves an independent concurrent layer's position")
        require(fast.timeline.video.first { it.id == clips[2].id }.start == et(5), "Speed still ripples sequential linked content")

        val original = TrackTimeline(clips)
        val pair = TimelineSelection(video = setOf(clips[0].id, clips[1].id), audio = setOf(TrackTimeline.counterpart(clips[0].id), TrackTimeline.counterpart(clips[1].id)))
        val linked = original.joining(pair).timeline
        val dissolve = EditState(linked).settingTransition(ClipTransition(duration = 1.0), 1)
        dissolve.timeline.validate(listOf(source))
        require(dissolve.timeline.video[0].start == zero && dissolve.timeline.video[1].start == et(3) && dissolve.duration == 11.0,
            "A transition inside a horizontal link keeps its outgoing picture fixed")
        val straight = dissolve.restoringStraightCut(1)
        straight.timeline.validate(listOf(source))
        require(straight.timeline.video.map { it.start } == original.video.map { it.start } && straight.duration == 12.0, "Removing a linked transition restores the straight cut")
        val compressedTransition = EditState(original).settingTransition(ClipTransition(duration = 4.0), 1)
        compressedTransition.updateClip(0) { it.copy(speed = speedJson(4.0)) }
        compressedTransition.timeline.validate(listOf(source))
        require(compressedTransition.timeline.transitions.size == 1 && compressedTransition.timeline.video[1].start == zero,
            "Speed refits a full-length transition without negative time or dropping it")
        require(compressedTransition.duration == 8.0, "Shrinking footage already inside an overlap does not move the next cut")
        val reordered = original.copy()
        reordered.move(reordered.selection(of = clips[0].id), et(12))
        reordered.move(reordered.selection(of = clips[2].id), et(-8))
        reordered.move(reordered.selection(of = clips[0].id), et(-4))
        val layout = EditState(reordered).layout
        require(layout.joins.filter { it.selectable }.map { it.time } == listOf(0.0, 4.0, 8.0, 12.0), "Move derives joins from actual time order")
        require(layout.boundary(4.0)?.outgoingID == clips[2].id && layout.boundary(4.0)?.incomingID == clips[1].id,
            "Moved transition boundaries name the actual neighboring pictures")
        val movedDissolve = EditState(reordered).settingTransition(ClipTransition(duration = 1.0), layout.boundary(4.0)!!.index)
        movedDissolve.timeline.validate(listOf(source))
        require(movedDissolve.timeline.transitions.firstOrNull()?.outgoing == clips[2].id, "Transition after Move edits the correct cut")
        val withOverlay = dissolve.timeline.copy()
        val extra = TimelineClip(sourceID = source.id, start = 0.0, end = 1.0)
        val inserted = TrackTimeline(listOf(extra)); inserted.move(inserted.entireSelection(), et(1))
        withOverlay.video = withOverlay.video + inserted.video; withOverlay.audio = withOverlay.audio + inserted.audio; withOverlay.links = withOverlay.links + inserted.links
        require(EditState(withOverlay).layout.joins.any { it.transition?.id == dissolve.timeline.transitions[0].id && it.outgoingID == clips[0].id },
            "A third overlay between participant starts cannot hide a transition")

        val long = TrackTimeline(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 20.0)))
        val selected = long.entireSelection().copy(range = iv(2, 8))
        val scope = long.scoped(selected)
        scope.timeline.video = scope.timeline.video.map {
            if (it.id in scope.selection.video) it.copy(content = it.content.copy(color = colorJson("warm", 0.5))) else it
        }
        val retimedRange = EditState(scope.timeline)
        retimedRange.clips = retimedRange.clips.map { if (it.id in scope.selection.video) it.copy(speed = speedJson(2.0)) else it }
        val spedSelection = retimedRange.timeline.remapping(selected, long)
        require(spedSelection.range == iv(2, 5), "Marked ranges follow their source content through a speed change")
        val speedDelete = retimedRange.timeline.scoped(spedSelection); speedDelete.timeline.remove(speedDelete.selection)
        require(speedDelete.timeline.duration == et(14), "Delete after Speed removes the intended source range only")
        val retained = scope.timeline.remapping(selected, long)
        var deleting = scope.timeline.scoped(retained)
        deleting.timeline.remove(deleting.selection)
        require(deleting.timeline.duration == et(14), "Delete after a range tool removes the entire retained range")
        val split = long.copy(); split.split(et(5), selected)
        deleting = split.scoped(split.remapping(selected, long)); deleting.timeline.remove(deleting.selection)
        require(deleting.timeline.duration == et(14), "Delete after Split follows both children")

        val colored = EditState(clips.take(2))
        colored.clips = colored.clips.map { it.copy(color = colorJson("warm", 0.5)) }
        require(TimelineNavigation(colored).changes == listOf(0.0, 8.0) && TimelineNavigation(colored).cutsAndChanges == listOf(0.0, 4.0, 8.0),
            "Identical effects on separate takes skip a plain cut only on long press")
        // Skipped: undercranking mute keyframes and Extend through effect boundaries (video effects are not ported).

        val overlay = TimelineClip(sourceID = source.id, start = 12.0, end = 14.0)
        val composite = original.copy()
        val overlayTracks = TrackTimeline(listOf(overlay))
        overlayTracks.move(overlayTracks.entireSelection(), et(1)); overlayTracks.updateVideo(0) { it.copy(layer = 9) }
        composite.video = composite.video + overlayTracks.video; composite.audio = composite.audio + overlayTracks.audio
        composite.links = composite.links + overlayTracks.links
        for (rate in listOf(0.5, 2.0)) {
            val edit = EditState(composite)
            val index = edit.clips.indexOfFirst { it.id == overlay.id }
            edit.updateClip(index) { it.copy(speed = speedJson(rate)) }
            edit.timeline.validate(listOf(source))
            require(clips.all { clip -> edit.timeline.video.first { it.id == clip.id }.start == original.video.first { it.id == clip.id }.start },
                "Retiming an overlay never ripples the main sequence")
        }
        composite.updateVideo(3) { it.copy(start = et(2)) }; composite.synchronizeFollowers()
        val overlayLayout = EditState(composite).layout
        require(overlayLayout.boundary(4.0)?.outgoingID == clips[0].id, "An overlay ending on a cut cannot steal its outgoing participant")
        val overlapLayout = EditState(withOverlay).layout
        val firstPlacement = overlapLayout.placements.first { it.clip.id == clips[0].id }
        require(overlapLayout.joins(firstPlacement).second.transition != null, "Background sound envelopes follow participant IDs")
        val movedFade = EditState(original).settingTransition(ClipTransition(duration = 1.0), 0)
        movedFade.timeline.move(movedFade.timeline.selection(of = clips[0].id), et(12))
        val fadeJoin = movedFade.layout.joins.first { it.transition != null }
        require(fadeJoin.time == 12.0 && movedFade.layout.boundary(0.0) == null, "Moved fades are anchored to their own picture")
        require(movedFade.layout.boundary(12.0)?.outgoingID == clips[2].id && movedFade.layout.boundary(12.0)?.incomingID == clips[0].id,
            "A moved opening fade leaves the adjacent cut available")
        val held = clips[0].copy(frozenFrame = FrozenFrameSegment(sourceTime = 1.0, duration = 3.0))
        val holdTimeline = TrackTimeline(listOf(held))
        val holdRange = holdTimeline.entireSelection().copy(range = iv(1, 2))
        var holdScope = holdTimeline.scoped(holdRange)
        val holdMapped = holdScope.timeline.remapping(holdRange, holdTimeline)
        require(holdMapped.range == iv(1, 2) && holdMapped.video.size == 1, "A marked fraction of a freeze stays on that fraction after a tool splits it")
        holdScope = holdScope.timeline.scoped(holdMapped); holdScope.timeline.remove(holdScope.selection)
        require(holdScope.timeline.duration == et(2), "Delete after a scoped freeze edit removes only the marked second")
        var edgeFades = EditState(original).settingTransition(ClipTransition(style = TransitionStyle.fadeColor, duration = 1.0), 0)
        edgeFades = edgeFades.settingTransition(ClipTransition(style = TransitionStyle.fadeColor, duration = 1.0), 3)
        edgeFades.timeline.move(edgeFades.timeline.selection(of = clips[0].id), et(12))
        val replacedFades = edgeFades.settingTransition(ClipTransition(duration = 1.0), edgeFades.layout.boundary(12.0)!!.index)
        replacedFades.timeline.validate(listOf(source))
        require(replacedFades.timeline.transitions.size == 1 && replacedFades.timeline.transitions[0].outgoing == clips[2].id &&
            replacedFades.timeline.transitions[0].incoming == clips[0].id, "A cut transition replaces the old outgoing and incoming edge fades")
        val replacementID = replacedFades.timeline.transitions[0].id
        require(replacedFades.timeline.compile().filter { it.transitionIDs.isNotEmpty() }.all { it.transitionIDs == listOf(replacementID) },
            "Rendering sees only the replacement transition")
        val duplicate = replacedFades.timeline.copy(); duplicate.transitions = duplicate.transitions + edgeFades.timeline.transitions
        assertInvalidDocument("Validation rejects competing transition slots") { duplicate.validate(listOf(source)) }
        duplicate.transitions = edgeFades.timeline.transitions + replacedFades.timeline.transitions; duplicate.pruneRelations()
        require(duplicate.transitions.map { it.id } == listOf(replacementID), "Pruning deterministically retains the latest transition in each slot")
        // Skipped: Extend across consecutive rate runs (needs the undercranking video effect, not ported).
    }

    @Test fun moveBoundaryChecksRun() = moveBoundaryChecks()

    @Test fun copyOfTimelineIsIndependent() {
        val source = SourceMedia(filename = "a.mov", duration = 4.0)
        val base = TrackTimeline(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0)))
        val copy = base.copy()
        copy.split(et(2), copy.entireSelection())
        require(base.video.size == 1 && copy.video.size == 2, "Kotlin copies keep Swift value semantics")
        val edit = EditState(base)
        edit.timeline.split(et(1), edit.timeline.entireSelection())
        require(base.video.size == 1, "EditState stores its own timeline copy")
        require(JsonArray(emptyList()).isEmpty(), "sanity")
    }

    /** Port of `placementCropTests()` (Tests/TrackTimelineTests.swift). */
    @Test fun placementCropTests() {
        val original = VideoPlacement(x = 0.6, y = 0.4, width = 0.8, height = 0.6, framing = TransitionFraming(0.2, 0.7, 2.0), fill = true, order = 3)
        val canvas = CGSize(1920.0, 1080.0)
        val full = original.window(CGSize(1.0, 1.0))
        val value = original.crop(PlacementCropEdge.left, full.minX + full.width * 0.2)
            .crop(PlacementCropEdge.right, full.maxX - full.width * 0.1)
            .crop(PlacementCropEdge.top, full.minY + full.height * 0.15)
            .crop(PlacementCropEdge.bottom, full.maxY - full.height * 0.25)
        val visible = value.visibleWindow(canvas)
        require(abs(visible.width - original.width * canvas.width * 0.7) < 0.00001 && abs(visible.height - original.height * canvas.height * 0.6) < 0.00001,
            "Each Placement edge crops only its own margin")
        require(value.copy(edgeCrop = null) == original, "Edge cropping never changes picture scale, position, Fit/Fill, framing or order")
        var fit = original.copy(fill = false)
        val fitted = fit.pictureWindow(canvas)
        require(abs(fitted.width / fitted.height - canvas.width / canvas.height) < 0.00001, "Fit crop handles follow the actual picture aspect")
        fit = fit.crop(PlacementCropEdge.left, (fitted.minX + fitted.width * 0.2) / canvas.width, canvas)
        require(abs(fit.visibleWindow(canvas).minX - (fitted.minX + fitted.width * 0.2)) < 0.00001, "Fit crop starts at the picture, without dead margins")
        val touchCanvas = CGSize(300.0, 168.75)
        val small = original.crop(PlacementCropEdge.left, 10.0, touchCanvas, minimumPoints = 44.0)
        require(abs(small.visibleWindow(touchCanvas).width - 44) < 0.00001, "Touch crop leaves a movable visible window")
        val narrowBefore = original.copy(fill = false, edgeCrop = PlacementEdgeCrop(top = 0.4, bottom = 0.35))
        val top = narrowBefore.visibleWindow(touchCanvas).minY / touchCanvas.height
        val narrow = narrowBefore.crop(PlacementCropEdge.top, top + 0.01, touchCanvas, minimumPoints = 44.0)
        require(narrow == narrowBefore, "Touching an already-small crop after Fit/rotation neither grows it nor shrinks it further")
        for (fill in listOf(false, true)) for (zoom in listOf(1.0, 2.0, 5.0)) {
            var framed = original.copy(fill = fill, framing = TransitionFraming(0.5, 0.5, zoom))
            val window = framed.window(touchCanvas)
            val before = framed.framing.crop(touchCanvas, if (fill) window.size else touchCanvas)
            val scale = if (fill) max(window.width / before.width, window.height / before.height) else min(window.width / before.width, window.height / before.height)
            framed = framed.reframe(CGSize(4.0, -3.0), touchCanvas)
            val after = framed.framing.crop(touchCanvas, if (fill) window.size else touchCanvas)
            val expectedX = min(4.0, before.minX * scale)
            val expectedY = min(3.0, (touchCanvas.height - before.maxY) * scale)
            require(abs((before.midX - after.midX) * scale - expectedX) < 0.00001 && abs((after.midY - before.midY) * scale - expectedY) < 0.00001,
                "Reframe follows the exact finger distance in both Fit and Fill at every zoom")
            framed = framed.reframe(CGSize(10000.0, 10000.0), touchCanvas).reframe(CGSize(-2.0, -2.0), touchCanvas)
            val reversed = framed.framing.crop(touchCanvas, if (fill) window.size else touchCanvas)
            require(abs(reversed.minX * scale - min(2.0, (touchCanvas.width - reversed.width) * scale)) < 0.00001,
                "Reversing at an image edge responds immediately without a hidden pan dead zone")
        }
        for (count in 2..24) for (index in 1 until count) {
            val pip = VideoPlacement.layout(3, index, count).visibleWindow(canvas)
            require(pip.minX >= 0 && pip.minY >= 0 && pip.maxX <= canvas.width && pip.maxY <= canvas.height,
                "Picture-in-picture keeps every preset window on canvas, including larger stacks")
        }
        val serialized = SwiftJSON.encodeToString(VideoPlacement.serializer(), value)
        require(SwiftJSON.decodeFromString(VideoPlacement.serializer(), serialized) == value, "Placement crop margins round-trip exactly")
        val old = SwiftJSON.encodeToString(VideoPlacement.serializer(), original)
        require(SwiftJSON.decodeFromString(VideoPlacement.serializer(), old).edgeCrop == null, "Existing placements open uncropped")
        var clamped = value
        for (edge in PlacementCropEdge.entries) {
            clamped = clamped.crop(edge, if (edge == PlacementCropEdge.left || edge == PlacementCropEdge.top) 10.0 else -10.0)
            require(clamped.edgeCrop!!.valid, "Opposite edge handles cannot cross or produce an empty crop")
        }
        for (edge in PlacementCropEdge.entries) clamped = clamped.crop(edge, if (edge == PlacementCropEdge.left || edge == PlacementCropEdge.top) -10.0 else 10.0)
        require(clamped == original, "Dragging every edge out restores the complete original window")
        val moved = value.copy(x = value.x + 0.1, y = value.y - 0.1, width = value.width * 1.5, height = value.height * 1.5)
        require(moved.edgeCrop == value.edgeCrop && moved.framing == value.framing, "Pan and resize retain the crop margins and content framing")

        val project = Project("Placement crop Undo")
        val source = SourceMedia(filename = "source.mp4", duration = 8.0)
        project.append(source)
        val state = project.edit
        val first = state.timeline.video[0]
        val other = first.copy(content = first.content.copy(id = newUUID()), occurrenceID = newUUID())
        state.timeline.video = state.timeline.video + other
        val ids = state.timeline.video.map { it.id }.toSet()
        state.timeline.placements = listOf(VideoPlacementRegion(participants = ids, videos = mapOf(first.id to original, other.id to original)))
        val canonical = state.timeline.video.sortedWith(TrackTimeline.backToFrontComparator).map { it.id }
        require(VideoPlacement.ordered(canonical, state.timeline.placements[0].videos) == canonical,
            "Equal placement depths retain the timeline's stable picture order")
        val divided = state.timeline.copy()
        divided.unlink(divided.entireSelection())
        divided.split(et(3), TimelineSelection(audio = divided.audio.map { it.id }.toSet()))
        require(divided.placementPreviewInterval(ids, 9.0, 1.0..2.0) == EditInterval(zero, et(8)),
            "Arrange preview merges audio subdivisions and finds the overlap even from an outside playhead")
        val movedPlacement = state.timeline.copy()
        val baseID = movedPlacement.video[0].id
        val overlayID = movedPlacement.video[1].id
        movedPlacement.unlink(movedPlacement.entireSelection())
        movedPlacement.video = movedPlacement.video.mapIndexed { index, item ->
            if (index != 1) item else item.copy(mapping = SourcePlaybackMap(TimelineClip(sourceID = source.id, start = 0.0, end = 2.0)),
                content = item.content.copy(end = 2.0), start = et(1))
        }
        movedPlacement.split(et(4), TimelineSelection(video = setOf(baseID)))
        val beforePlacedMove = movedPlacement.copy()
        movedPlacement.move(TimelineSelection(video = setOf(overlayID)), et(4))
        val movedPlan = movedPlacement.compile().first { it.interval.contains(et(5.5)) }
        val movedRegion = movedPlacement.placements.firstOrNull { it.id == movedPlan.placementID }
        val expectedPlacement = original.copy(order = movedRegion?.videos?.get(overlayID)?.order ?: -1)
        require(movedRegion?.videos?.get(overlayID) == expectedPlacement,
            "Moving a placed overlay across a split preserves its window instead of turning it into full-frame Cover")
        val extendedPlacement = beforePlacedMove.copy()
        extendedPlacement.extend(TimelineSelection(video = setOf(overlayID)), left = false, by = et(3), sources = listOf(source))
        val extendedPlan = extendedPlacement.compile().first { it.interval.contains(et(5)) }
        require(extendedPlacement.placements.firstOrNull { it.id == extendedPlan.placementID }?.videos?.get(overlayID)?.width == original.width,
            "Trim extension into another base clip preserves the overlay's geometry")
        val slowPlacement = beforePlacedMove.copy()
        val slowClips = slowPlacement.projectedClips.map { if (it.id == overlayID) it.copy(speed = speedJson(0.5)) else it }
        slowPlacement.replaceVideoContents(slowClips)
        val slowPlan = slowPlacement.compile().first { it.interval.contains(et(4.5)) }
        require(slowPlacement.placements.firstOrNull { it.id == slowPlan.placementID }?.videos?.get(overlayID)?.width == original.width,
            "Slowing an overlay into another base clip preserves its geometry")
        val thirdLayer = state.timeline.copy()
        val third = thirdLayer.video[0].let { it.copy(content = it.content.copy(id = newUUID()), occurrenceID = newUUID(), start = et(8)) }
        thirdLayer.video = thirdLayer.video + third
        thirdLayer.move(TimelineSelection(video = setOf(third.id)), et(-6))
        val three = thirdLayer.compile().first { it.video.size == 3 }
        val inherited = thirdLayer.placements.first { it.id == three.placementID }
        require(inherited.videos[baseID]?.width == original.width && inherited.videos[overlayID]?.framing == original.framing,
            "Adding a third overlapping picture keeps the existing arrangement")
        require(VideoPlacement.ordered(three.video, inherited.videos).last() == third.id,
            "New overlay remains in front while inherited windows retain their relative order")
        val remote = TrackTimeline(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 8.0)))
        val short = remote.video[0].let { item ->
            val content = item.content.copy(id = newUUID(), end = 1.0)
            item.copy(content = content, occurrenceID = newUUID(), mapping = SourcePlaybackMap(content), start = et(1))
        }
        val remoteBase = remote.video[0].let { it.copy(content = it.content.copy(id = newUUID()), occurrenceID = newUUID(), start = et(10)) }
        val pip = short.copy(content = short.content.copy(id = newUUID()), occurrenceID = newUUID(), start = et(11), layer = 2)
        remote.video = remote.video + listOf(short, remoteBase, pip)
        val aID = remote.video[0].id
        remote.placements = listOf(
            VideoPlacementRegion(participants = setOf(aID, short.id), videos = mapOf(aID to VideoPlacement.layout(1, 0, 2), short.id to VideoPlacement.layout(1, 1, 2))),
            VideoPlacementRegion(participants = setOf(remoteBase.id, pip.id), videos = mapOf(remoteBase.id to VideoPlacement(), pip.id to VideoPlacement.layout(3, 1, 2))))
        remote.move(TimelineSelection(video = setOf(pip.id)), et(-6))
        val remotePlan = remote.compile().first { it.interval.contains(et(5.5)) }
        val remoteValues = remote.placements.first { it.id == remotePlan.placementID }.videos
        require(remoteValues[aID]?.width == 1.0 && remoteValues[pip.id]?.width == 0.32,
            "A moved PiP retains its size without borrowing unrelated distant geometry for the stationary background")
        project.commit(state)
        state.timeline.placements = listOf(state.timeline.placements[0].let { it.copy(videos = it.videos + (other.id to value)) })
        project.commit(state)
        project.validate()
        require(project.schemaVersion == 7, "Older builds cannot silently drop placement crops")
        project.undo()
        require(project.edit.timeline.placements[0].videos[other.id]?.edgeCrop == null, "Undo restores crop without changing the overlapping pictures")
        val reopened = Project.decode(Project.encode(project))
        reopened.redo()
        require(reopened.edit == state, "Placement crop survives saved Redo and reopening")
        state.timeline.placements = listOf(state.timeline.placements[0].let {
            it.copy(videos = it.videos + (other.id to value.copy(edgeCrop = PlacementEdgeCrop(left = 0.8, right = 0.7))))
        })
        assertInvalidDocument("An empty edge crop is rejected") { state.timeline.validate(listOf(source)) }
    }

    /** The Placement parts of the `toolRegions` assertions in Tests/TrackTimelineTests.swift (the transition half waits for Transitions). */
    @Test fun placementToolRegions() {
        val source = SourceMedia(filename = "source.mp4", duration = 10.0)
        val clips = listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0), TimelineClip(sourceID = source.id, start = 4.0, end = 8.0))
        val edit = EditState(clips)
        require(edit.toolRegions.none { it.tool == VideoTool.placement }, "A straight cut has no Placement control")
        val overlay = edit.timeline.copy()
        val cover = TimelineClip(sourceID = source.id, start = 8.0, end = 10.0)
        overlay.video = overlay.video + VideoTimelineItem(cover.id, et(2), cover, 99)
        val layeredEdit = EditState(overlay, edit.outputSize)
        val regions = layeredEdit.toolRegions.filter { it.tool == VideoTool.placement }
        require(regions.isNotEmpty() && regions.all { it.label == "Placement · default" }, "An actual overlay retains its Placement control")
        require(regions.map { it.range } == listOf(2.0..4.0) && regions[0].effectID == regions[0].clipID,
            "A default Placement spans its overlap and is identified by its back picture")
        val ids = overlay.compile().first { it.video.size > 1 }.video
        val arranged = layeredEdit.copy()
        arranged.timeline.placements = listOf(VideoPlacementRegion(participants = ids.toSet(),
            videos = ids.withIndex().associate { (index, id) -> id to VideoPlacement.layout(3, index, ids.size) }))
        val placed = arranged.toolRegions.single { it.tool == VideoTool.placement }
        require(placed.label == "Placement" && placed.effectID == arranged.timeline.placements[0].id, "A configured Placement is identified by its region")
        val request = ToolRequest(VideoTool.placement, placed.range, "Overlapping videos", effectID = placed.effectID, clipIDs = ids.toSet())
        require(arranged.removingTool(request).timeline.placements.isEmpty() && arranged.removingTool(request).timeline.video == arranged.timeline.video,
            "Removing a Placement restores the default stack without touching the pictures")
    }
}
