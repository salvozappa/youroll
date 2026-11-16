// Port of Tests/TimelineClipboardChecks.swift (JUnit).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class TimelineClipboardChecks {
    private val source = SourceMedia(filename = "clipboard.mov", duration = 10.0)
    private val clips = (0 until 5).map { TimelineClip(sourceID = source.id, start = it * 2.0, end = it * 2.0 + 2) }
    private fun base() = TrackTimeline(clips)

    private fun boundary(t: TrackTimeline, second: Double, kind: TimelinePasteTarget.Kind = TimelinePasteTarget.Kind.picture): TimelinePasteTarget? =
        t.pasteTargets(EditState(t).layout).firstOrNull { it.kind == kind && it.isAt(et(second)) }

    private fun order(timeline: TrackTimeline): List<Int> = timeline.chronologicalVideo.map { (it.content.start / 2).toInt() }

    @Test fun copyAndCutEverySubsetIntoEverySlot() {
        val base = base()
        val indices = clips.indices.toList()
        for (mask in 1 until (1 shl clips.size)) {
            val chosen = indices.filter { mask and (1 shl it) != 0 }
            val kept = indices.filter { it !in chosen }
            val scope = base.selection(ids = chosen.map { clips[it].id }.toSet())
            val copy = base.copied(scope)
            require(copy.timeline.duration == et(chosen.size * 2), "Disjoint scenes must pack in playback order")
            for (slot in 0..clips.size) {
                val result = base.copy()
                val time = slot * 2.0
                val added = result.paste(copy, et(time), boundary(result, time))
                require(order(result) == indices.take(slot) + chosen + indices.drop(slot) && added.ids.isDisjoint(base.allIDs),
                    "Copy into slot $slot of mask $mask must keep the originals")
                result.validate(listOf(source))
            }
            val lifted = base.copy()
            val cut = lifted.cutToClipboard(scope, EditTime.zero) ?: error("Cut unexpectedly empty")
            require(order(lifted) == kept, "Cut removes only the selected scenes")
            for (slot in 0..kept.size) {
                val result = lifted.copy()
                val time = slot * 2.0
                result.paste(cut.clipboard, et(time), boundary(result, time))
                require(order(result) == kept.take(slot) + chosen + kept.drop(slot) && result.duration == base.duration,
                    "Cut and paste must reconstruct a complete sequence, including an empty destination")
                result.validate(listOf(source))
            }
        }
    }

    /** The base with a mark, words, a fade, an enhancement and a title on the last scene. */
    private fun dressed(): TrackTimeline {
        val base = base()
        base.addBookmark(et(8.75))
        base.updateAudio(4) { it.copy(subtitleWords = listOf(SubtitleWord(text = "Last", start = 8.2, end = 8.8))) }
        base.setFade(AudioFadeEdge.fadeIn, 0.25, base.audio[4].id)
        base.enhance(listOf(AudioEffect(kind = EffectKind.gain, amount = -3.0)), base.selection(of = clips[4].id))
        base.updateVideo(4) { it.copy(content = it.content.copy(texts = listOf(textOverlay("Last scene", 8.0, 9.0)))) }
        return base
    }

    @Test fun eachPasteIsAnIndependentCopy() {
        val base = dressed()
        val title = base.video[4].content.texts!!.first().id
        val late = base.copied(base.selection(of = clips[4].id))
        val layered = base.copy()
        val firstPaste = layered.paste(late, EditTime.zero, boundary(layered, 0.0))
        val secondPaste = layered.paste(late, layered.duration, boundary(layered, layered.duration.seconds))
        require(firstPaste.ids.isDisjoint(secondPaste.ids) && layered.resolvedBookmarks.map { it.time } == listOf(et(0.75), et(10.75), et(12.75)),
            "Each paste has its own media, mark, and edit identities")
        val audio = layered.audio.first { it.id in firstPaste.audio }
        val video = layered.video.first { it.id in firstPaste.video }
        val text = video.content.texts?.firstOrNull()
        require(audio.subtitleWords?.firstOrNull()?.text == "Last" && audio.fadeIn == base.audio[4].fadeIn &&
            audio.enhancements.firstOrNull()?.effects == base.audio[4].enhancements.firstOrNull()?.effects &&
            text?.text == "Last scene" && text.id != title,
            "Pasted titles, captions, fades and enhancements remain editable independently")
        require(audio.subtitleWords!!.first().id != base.audio[4].subtitleWords!!.first().id &&
            audio.enhancements.first().id != base.audio[4].enhancements.first().id, "Words and enhancement regions get fresh identities")
    }

    @Test fun cutAndPasteSurviveHistoryAndReopening() {
        val base = dressed()
        val range = base.copy()
        val selection = base.entireSelection(range = iv(1, 3))
        val piece = range.cutToClipboard(selection, et(8))!!
        require(piece.position == et(6) && range.duration == et(8) && piece.clipboard.timeline.duration == et(2),
            "Cut and the clipboard must use the same exact range fragments")
        var project = Project("Clipboard history"); project.sources = listOf(source)
        project.commit(EditState(base)); project.commit(EditState(range))
        range.paste(piece.clipboard, et(0.5)); project.commit(EditState(range))
        project = project.roundTripped(); project.validate()
        project.undo(); require(project.edit.timeline.duration == et(8), "Undo Paste")
        project.undo(); require(project.edit.timeline == base, "Undo Cut restores the source and its edits after reopening")
        project.redo(); project.redo(); require(project.edit.timeline == range, "Redo Cut and Paste")
    }

    @Test fun soundPastesLayerOnPictureOrInsertIntoTheSoundSequence() {
        val base = dressed()
        val late = base.copied(base.selection(of = clips[4].id))
        val detached = base.copy(); detached.unlink(detached.entireSelection())
        val sound = detached.copied(detached.selection(of = detached.audio[4].id))
        val atPicture = detached.copy()
        val atSound = detached.copy()
        atPicture.paste(sound, et(2), boundary(detached, 2.0))
        atSound.paste(sound, et(2), boundary(detached, 2.0, TimelinePasteTarget.Kind.sound))
        require(atPicture.video == detached.video && atPicture.audio.take(5) == detached.audio &&
            atSound.video == detached.video && atSound.audio[1].start == et(4), "Choose layering on picture or inserting into the sound sequence")
        val mix = base.copy()
        val score = mix.insertAudio(source, EditTime.zero)
        mix.split(et(1), TimelineSelection(audio = setOf(score)))
        mix.paste(late, et(1), boundary(mix, 1.0, TimelinePasteTarget.Kind.sound))
        require(mix.video.any { it.occurrenceID == base.video[0].occurrenceID && it.start == et(3) } &&
            mix.audio.firstOrNull { it.id == score }?.start == EditTime.zero, "Paste picture at a sound cut preserves detached music and splits dialogue in sync")
    }

    @Test fun rangesKeepGapsAndScenesLeaveMusicContinuous() {
        val base = dressed()
        val late = base.copied(base.selection(of = clips[4].id))
        val spaced = base.copy()
        spaced.move(spaced.selection(ids = clips.takeLast(2).map { it.id }.toSet()), et(2))
        val marked = spaced.copied(spaced.entireSelection(range = iv(4, 10)))
        require(marked.timeline.duration == et(6) && marked.timeline.chronologicalVideo.map { it.start } == listOf(EditTime.zero, et(4)),
            "A marked range retains the intentional gap between included clips")
        val continuousBed = base.copy()
        val scoreID = continuousBed.insertAudio(source, EditTime.zero)
        val beforeMusic = continuousBed.audio.first { it.id == scoreID }
        continuousBed.paste(late, et(1.5))
        require(continuousBed.audio.first { it.id == scoreID } == beforeMusic, "A mid-shot scene paste must leave detached music continuous")
        continuousBed.validate(listOf(source))
    }

    @Test fun dissolvesRecoverHandlesAndTravelWithBothPartners() {
        val base = dressed()
        val late = base.copied(base.selection(of = clips[4].id))
        val dissolve = ClipTransition(duration = 0.5)
        val blend = EditState(base).settingTransition(dissolve, 2)
        val separated = blend.timeline.copy()
        val join = blend.layout.joins.first { it.transition?.id == dissolve.id }
        separated.paste(late, et(join.time), boundary(separated, join.time))
        require(separated.transitions.isEmpty() && separated.duration == et(12), "Pasting picture into a dissolve recovers both source handles")
        val both = blend.timeline.copied(blend.timeline.selection(ids = setOf(clips[1].id, clips[2].id)))
        val copiedBlend = base.copy()
        copiedBlend.paste(both, copiedBlend.duration)
        require(copiedBlend.transitions.size == 1 && copiedBlend.transitions[0].id != dissolve.id && both.timeline.duration == et(3.5),
            "An internal transition travels with both selected partners and keeps the real overlap")
        copiedBlend.validate(listOf(source))
        val inside = blend.timeline.copy()
        inside.paste(late, et(join.time - 0.1))
        require(inside.transitions.isEmpty() && inside.duration == et(12), "A mid-transition scene paste retains all source handles")
        inside.validate(listOf(source))
    }

    @Test fun overlappingPicksRetainOffsetAndCustomFraming() {
        val arrangement = base()
        val pip = VideoTimelineItem(newUUID(), et(8.5), TimelineClip(sourceID = source.id, start = 0.0, end = 1.0), layer = 5)
        val third = VideoTimelineItem(newUUID(), et(8.6), TimelineClip(sourceID = source.id, start = 0.0, end = 0.4), layer = 6)
        arrangement.video = arrangement.video + listOf(pip, third)
        val window = VideoPlacement(width = 0.4, order = 2)
        val plain = VideoPlacement()
        arrangement.placements = listOf(VideoPlacementRegion(participants = setOf(clips[4].id, pip.id, third.id),
            videos = mapOf(clips[4].id to plain, pip.id to window, third.id to plain)))
        val parts = arrangement.copied(arrangement.selection(ids = setOf(clips[4].id, pip.id)))
        require(parts.timeline.video.firstOrNull { it.id == pip.id }?.start == et(0.5) &&
            parts.timeline.placements.firstOrNull()?.videos?.get(pip.id) == window, "Overlapping picks retain offset and custom framing")
    }
}
