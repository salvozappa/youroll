// YouRoll JVM tests for subtitles: the model, timing and editing assertions of
// ../Tests/SubtitleTests.swift (`subtitleTests`). The layout, font and export
// checks need android.graphics and run on the device (SubtitleDeviceTest).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.math.abs

class SubtitleTests {
    private val speech = listOf(0.2..2.0, 4.0..7.0, 9.0..11.0)
    private val words = SubtitleTiming.estimate("Here is the first sentence. Then another sentence arrives. Finally we finish.", speech)

    @Test fun timingEstimatesAndCorrections() {
        require(words.isNotEmpty() && words.all { word -> speech.any { word.start >= it.start && word.end <= it.endInclusive } }, "Word estimates never bridge a silence")
        require(words.zipWithNext().all { (a, b) -> a.end <= b.start + 0.000001 }, "Word estimates are ordered")
        val corrected = SubtitleTiming.corrected(words.take(4), "Here is a replacement")
        require(corrected.take(2).map { it.start } == words.take(2).map { it.start }, "Unchanged words retain timing after correction")
        require(corrected.all { it.start >= words[0].start && it.end <= words[3].end }, "Corrected words stay within their phrase")
        val punctuated = SubtitleTiming.corrected(words.take(3), "Here, is the!")
        require(punctuated.map { it.start } == words.take(3).map { it.start } && punctuated.map { it.end } == words.take(3).map { it.end },
            "Punctuation corrections retain timings exactly")
        val inserted = SubtitleTiming.corrected(words.take(2), "Here really is")
        require(inserted.size == 3 && inserted.zipWithNext().all { (a, b) -> a.end <= b.start + 0.000001 }, "Inserted words borrow bounded timing without overlaps")
    }

    @Test fun tokensKeepPunctuationWithTheirWord() {
        require(SubtitleTiming.tokens("  Here, is the!  ") == listOf("Here,", "is", "the!"), "Punctuation stays with the preceding word")
        require(SubtitleTiming.tokens("città è bella.") == listOf("città", "è", "bella."), "Accented words are single tokens")
        require(SubtitleTiming.tokens("...") == listOf("..."), "Punctuation only is one token")
        require(SubtitleTiming.tokens("   ").isEmpty(), "Blank text has no tokens")
    }

    @Test fun speechPlan() {
        val silence = SubtitleSpeechPlan.chunks(List(2000) { 0f }, 40.0)
        require(silence.isEmpty(), "Silence does not invoke ASR")
        val continuous = SubtitleSpeechPlan.chunks(List(5000) { 0.1f }, 100.0)
        require(continuous.size >= 9 && continuous.all { it.range.endInclusive - it.range.start <= 12.001 }, "Continuous speech stays in bounded inference chunks")
    }

    private fun original(): Project {
        val project = Project("Subtitle model QA")
        project.append(SourceMedia(filename = "source.mp4", duration = 12.0))
        val state = project.edit.copy()
        state.timeline.installSubtitles(words, 0.0..12.0, "English")
        require(state.timeline.subtitleGroupingAvailable && state.timeline.subtitleCues.all { SubtitleTiming.tokens(it.text).size <= 6 }, "Initial captions obey word limit")
        state.timeline.regroupSubtitles(2)
        require(state.timeline.subtitleCues.all { SubtitleTiming.tokens(it.text).size <= 2 }, "Regrouping uses stored transcript")
        project.commit(state)
        return project
    }

    @Test fun editingFollowsTheFootage() {
        val project = original()
        val navigation = TimelineNavigation(project.edit)
        val firstCue = project.edit.timeline.subtitleCues[0]
        require(navigation.changes.any { abs(it - firstCue.start) <= 1.0 / 30 }, "Effect navigation includes subtitle boundaries")
        val original = project.edit.copy()
        val firstAudio = original.timeline.audio[0].id
        val divided = original.copy()
        divided.timeline.split(et(5), divided.timeline.entireSelection())
        require(!divided.timeline.subtitleGroupingAvailable && divided.timeline.subtitleCues.isNotEmpty(), "Split preserves captions and marks regrouping stale")
        val beforeRight = divided.timeline.subtitleCues.filter { it.audioID != firstAudio }.map { it.text }
        divided.timeline.subtitleCues.firstOrNull { it.audioID == firstAudio }?.let { divided.timeline.correctSubtitle(it, "Edited independently") }
        require(beforeRight == divided.timeline.subtitleCues.filter { it.audioID != firstAudio }.map { it.text }, "Correcting a split caption cannot mutate its other half")
        val count = divided.timeline.subtitles!!.maxWords
        divided.timeline.regroupSubtitles(9)
        require(divided.timeline.subtitles!!.maxWords == count, "Stale transcript refuses regrouping")
        project.commit(divided); project.undo()
        require(project.edit == original && project.edit.timeline.subtitleGroupingAvailable, "Undo restores exact captions and regrouping")
        project.redo(); require(project.edit == divided, "Redo restores corrected split captions")

        val moved = original.copy()
        moved.timeline.unlink(moved.timeline.entireSelection())
        moved.timeline.move(TimelineSelection(audio = setOf(firstAudio)), et(3))
        require(abs(moved.timeline.subtitleCues[0].start - original.timeline.subtitleCues[0].start - 3) < 0.00001, "Captions follow independent speech moves")
        val faster = original.copy()
        faster.updateClip(0) { it.copy(speed = SpeedSettings(rate = 2.0)) }
        require(abs(faster.timeline.subtitleCues[0].start * 2 - original.timeline.subtitleCues[0].start) < 0.0001, "Captions follow pitch-preserved speed changes")
        val partial = original.timeline.copy()
        val preserved = partial.subtitleCues.filter { it.start > 4 }.map { it.id }
        partial.installSubtitles(SubtitleTiming.estimate("New opening", listOf(0.3..1.5)), 0.0..2.0, "English")
        require(partial.subtitleCues.filter { it.start > 4 }.map { it.id } == preserved, "Partial re-transcription preserves phrase identity outside the selection")
        val styled = original.copy(); styled.timeline.subtitles = styled.timeline.subtitles?.copy(style = TextStyle.bold)
        require(styled.timeline.subtitleGroupingAvailable, "Style changes do not invalidate timing")
        val gain = original.copy(); gain.timeline.updateAudio(0) { it.copy(volumeDB = -3.0) }
        require(gain.timeline.subtitleGroupingAvailable, "Gain changes do not invalidate timing")
        val pasted = original.timeline.copy()
        val clipboard = pasted.copied(pasted.entireSelection())
        val selection = pasted.paste(clipboard, et(12))
        val copy = pasted.subtitleCues.first { it.audioID in selection.audio }
        pasted.correctSubtitle(copy, "Independent copy")
        require(pasted.subtitleCues.filter { it.audioID !in selection.audio }.map { it.text } == original.timeline.subtitleCues.map { it.text }, "Pasted subtitles own independent words")
        val removed = original.copy(); removed.timeline.removeSubtitles(); project.commit(removed)
        require(project.edit.timeline.subtitleCues.isEmpty() && project.edit.timeline.audio.all { it.subtitleWords == null }, "Removal deletes transcript and timing together")
        project.undo(); require(project.edit == divided, "Undo restores full transcript after global removal")
        val reopened = project.roundTripped()
        require(reopened.edit == project.edit && reopened.canRedo, "Transcript corrections and Undo/Redo survive reopening")
        reopened.validate()
    }

    @Test fun toolRegionReopensAndRemovesTheProjectTranscript() {
        val project = original()
        val regions = project.edit.toolRegions.filter { it.tool == VideoTool.subtitles }
        require(regions.size == 1 && regions[0].label == "Subtitles" && regions[0].effectID == project.edit.timeline.subtitles!!.id, "One Subtitles region per project")
        val request = project.edit.request(regions[0])
        require(request.range == 0.0..project.edit.duration && request.scopeTitle == "Project subtitles", "The chip reopens the whole project")
        val removed = project.edit.removingTool(request)
        require(removed.timeline.subtitles == null && removed.timeline.audio.all { it.subtitleWords == null }, "Removing the chip removes every subtitle")
    }

    @Test fun settingsJsonAndValidity() {
        val placement = SubtitleSettings(font = SubtitleFont.classic, x = 0.3, size = 0.13, y = 0.2)
        val encoded = SwiftJSON.encodeToJsonElement(SubtitleSettings.serializer(), placement)
        require(SwiftJSON.decodeFromJsonElement(SubtitleSettings.serializer(), encoded) == placement, "Font and placement round trip")
        require(encoded.jsonObject.keys == setOf("id", "maxWords", "style", "color", "size", "y", "language", "timingFingerprint", "x", "font"),
            "Swift Codable field names, nil optionals omitted: ${encoded.jsonObject.keys}")
        val legacy = JsonObject(encoded.jsonObject - "x" - "font")
        val oldSettings = SwiftJSON.decodeFromJsonElement(SubtitleSettings.serializer(), legacy)
        require(oldSettings.centerX == 0.5 && oldSettings.resolvedFont == SubtitleFont.system, "Existing subtitles load with centered system font")
        require(!SubtitleSettings(maxWords = 17).valid && !SubtitleSettings(size = 0.5).valid && !SubtitleSettings(x = 1.2).valid && SubtitleSettings().valid, "Validity bounds")
        val project = original()
        project.commit(project.edit.copy().also { it.timeline.subtitles = it.timeline.subtitles!!.copy(size = 0.9) })
        assertInvalidDocument("Invalid subtitle settings are rejected") { project.validate() }
    }

    /** A timeline written by iOS (Swift `Codable` shapes) loads typed, with its words, and renders cues. */
    @Test fun iosTimelineJsonLoads() {
        val audio = "6F9619FF-8B86-D011-B42D-00CF4FC964FF"
        val json = """{"video":[],"links":[],"bookmarks":[],"placements":[],"transitions":[],"generated":[],
            "audio":[{"id":"$audio","occurrenceID":"$audio","sourceID":"$audio","start":{"ticks":0},
              "mapping":{"runs":[{"duration":{"ticks":2400000},"sourceStart":0,"sourceEnd":10,"silent":false}]},"localOrigin":{"ticks":0},
              "volumeDB":0,"muted":false,"enhancements":[],
              "subtitleWords":[{"id":"11111111-1111-1111-1111-111111111111","phraseID":"22222222-2222-2222-2222-222222222222","text":"Ciao","start":1,"end":1.5},
                               {"id":"33333333-3333-3333-3333-333333333333","phraseID":"22222222-2222-2222-2222-222222222222","text":"mondo.","start":1.5,"end":2.25}]}],
            "subtitles":{"id":"44444444-4444-4444-4444-444444444444","maxWords":6,"style":"highlight","color":"yellow","size":0.052,"y":0.86,
              "language":"Italian","timingFingerprint":"","x":0.4,"font":"condensed",
              "motion":{"entrance":"fade","exit":"none","inSeconds":0.3,"outSeconds":0.3}}}"""
        val timeline = SwiftJSON.decodeFromString(TrackTimeline.serializer(), json)
        val settings = timeline.subtitles!!
        require(settings.style == TextStyle.highlight && settings.color == TextColor.yellow && settings.font == SubtitleFont.condensed &&
            settings.centerX == 0.4 && settings.motion?.entrance == TitleMove.fade && settings.valid, "Settings decode typed: $settings")
        val cues = timeline.subtitleCues
        require(cues.size == 1 && cues[0].text == "Ciao mondo." && cues[0].start == 1.0 && cues[0].end == 2.25, "Words become one cue: $cues")
        require(settings.design.face == "DINCondensed-Bold" && settings.design.plate == TitlePlate.block, "Legacy design keeps the iOS font name")
    }

    /** iOS projects keep a fresh fingerprint here: the encoding matches Swift's `JSONEncoder` with `.sortedKeys`. */
    @Test fun fingerprintEncodingMatchesSwift() {
        require(SwiftFingerprintJSON.number(1.0) == "1" && SwiftFingerprintJSON.number(0.5) == "0.5" && SwiftFingerprintJSON.number(12.345) == "12.345" &&
            SwiftFingerprintJSON.number(0.0001) == "0.0001" && SwiftFingerprintJSON.number(0.00001) == "1e-05" && SwiftFingerprintJSON.number(-2.5e-7) == "-2.5e-07" &&
            SwiftFingerprintJSON.number(1.5e16) == "1.5e+16" && SwiftFingerprintJSON.number(1e16) == "1e+16" && SwiftFingerprintJSON.number(123456789.125) == "123456789.125" &&
            SwiftFingerprintJSON.number(1.0 / 3.0) == "0.3333333333333333", "Swift number formatting")
        val id = java.util.UUID.fromString("6F9619FF-8B86-D011-B42D-00CF4FC964FF")
        val timeline = TrackTimeline(audio = listOf(AudioTimelineItem(id = id, occurrenceID = id, sourceID = id, start = et(1.5),
            mapping = SourcePlaybackMap(listOf(PlaybackRun(et(2), 0.25, 2.25), PlaybackRun(et(1), 3.0, 3.0, silent = true), PlaybackRun(EditTime(1), 0.1, 0.00001234))))))
        // From Swift (Xcode 27.2): JSONEncoder with .sortedKeys of the same `Item` array, and its SHA-256.
        require(timeline.subtitleTimingFingerprint == "abc89b91ebd1754470ad00253e64e2ab91369a0e4b8182604fdbadcee2613a4c", "Fingerprint equals the Swift one")
        require(SwiftFingerprintJSON.encode(kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("a/b")))) == "[\"a\\/b\"]", "Slashes are escaped")
        require(timeline.subtitleTimingFingerprint.length == 64, "SHA-256 hex")
    }
}
