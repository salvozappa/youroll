// YouRoll JVM tests for the Pauses tool. The detection checks port the
// assertions of ../Tests/PausesTests.swift (`pausesTests`, `pauseLevelTests`)
// and the pause parts of ../Tests/VideoToolsTests.swift (`videoToolsTests`)
// and of the EditorTools.swift diagnostics (`EditorToolsDiagnostics`,
// `PausesDiagnostics`: Keep survives padding changes and reopening, Remove
// restores the footage, Undo/Redo). The waveform-cache upgrade and the real
// recording export run on the device (emulator QA).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.math.abs

class PausesTests {
    private val clip = TimelineClip(sourceID = newUUID(), start = 0.0, end = 4.0)

    private fun wave(duration: Double, levels: FloatArray, rms: FloatArray? = null, speech: ByteArray? = null) =
        SourceWaveform(duration, levels, rms, speech?.let { base64(it) })

    @Test fun automaticLevelFollowsRoomToneAndProtectsWords() {
        val energy = FloatArray(400) { i ->
            if (i in 190 until 210) 0.08f // A short word between two pauses.
            else if (i in 100 until 300) 0.01f else 0.24f
        }
        val noisy = wave(4.0, FloatArray(400) { energy[it] * 3 }, energy)
        val defaults = PauseSettings()
        require(PauseDetector.levelDB(noisy, defaults) == -32.0, "Automatic level follows room tone with headroom below speech")
        val cuts = PauseDetector.raw(noisy, clip, defaults)
        require(cuts.size == 2 && cuts[0].end <= 1.9 + 0.0001 && cuts[1].start >= 2.1 - 0.0001,
            "Room tone is detected without cutting the short word between pauses")
        val fixed = PauseSettings(thresholdDB = -50.0, levelMode = PauseLevelMode.manual)
        require(PauseDetector.raw(noisy, clip, fixed).isEmpty(), "A stricter manual RMS threshold is honored")
        val stored = SwiftJSON.encodeToJsonElement(PauseSettings.serializer(), defaults).jsonObject
        require(stored["levelMode"].toString() == "\"automatic\"", "New detections save the automatic level mode")
        val legacy = SwiftJSON.decodeFromJsonElement(PauseSettings.serializer(), JsonObject(stored - "levelMode"))
        require(legacy.levelMode == null && PauseDetector.raw(noisy, clip, legacy).isEmpty(), "Previously saved manual thresholds still use peak levels")
        val peaksOnly = wave(4.0, energy)
        require(PauseDetector.raw(peaksOnly, clip, defaults).isEmpty(), "Automatic detection cannot accidentally treat a peak-only cache as RMS")
        val roomTone = FloatArray(1200) { 0.014f }
        roomTone[800] = 0.24f
        fun sparse(levels: FloatArray) = PauseDetector.raw(wave(12.0, levels, levels), TimelineClip(sourceID = newUUID(), start = 0.0, end = 12.0), defaults)
        require(sparse(roomTone).isEmpty(), "One click cannot calibrate an otherwise continuous signal as speech")
        for (i in 780 until 830) roomTone[i] = 0.24f
        require(sparse(roomTone).size == 2, "A mostly quiet take still protects a brief spoken passage")
    }

    @Test fun speechProbabilitiesProtectQuietWords() {
        // The noisy gap never drops below the energy threshold. A short quiet word
        // splits it in two, even though that word is quieter than the background.
        val noisyRoom = FloatArray(400) { 0.04f }
        val voice = ByteArray(400) { 8 }
        for (i in 0 until 75) { noisyRoom[i] = 0.3f; voice[i] = 235.toByte() }
        for (i in 190 until 211) { noisyRoom[i] = 0.003f; voice[i] = 210.toByte() }
        for (i in 330 until 400) { noisyRoom[i] = 0.24f; voice[i] = 240.toByte() }
        val defaults = PauseSettings()
        val dialogue = wave(4.0, noisyRoom, noisyRoom, voice)
        val speechCuts = PauseDetector.raw(dialogue, clip, defaults)
        require(speechCuts.size == 2 && abs(speechCuts[0].start - 0.75) < 1e-6 && abs(speechCuts[0].end - 1.9) < 1e-6 &&
            abs(speechCuts[1].start - 2.11) < 1e-6 && abs(speechCuts[1].end - 3.3) < 1e-6, "Speech-aware detection protects a quiet word between two noisy pauses")
        val manual = PauseSettings(thresholdDB = PauseDetector.levelDB(dialogue, defaults), levelMode = PauseLevelMode.manual)
        require(PauseDetector.raw(dialogue, clip, manual).isEmpty(), "Manual mode uses the selected dB threshold, even with cached speech probabilities")
        val missedOnset = noisyRoom.copyOf()
        for (i in 166 until 179) missedOnset[i] = 0.12f
        missedOnset[110] = 0.12f
        val kept = PauseDetector.raw(wave(4.0, missedOnset, missedOnset, voice), clip, defaults)
        require(kept.any { it.start < 1.10 && it.end > 1.12 } && kept.none { it.start < 1.77 && it.end > 1.68 },
            "A brief unclassified syllable is retained while a single noise spike stays inside the pause")
        val ambient = wave(4.0, noisyRoom, noisyRoom, ByteArray(400) { 4 })
        require(PauseDetector.raw(ambient, clip, defaults).isEmpty(), "Without evidence of dialogue, an ambient take is not classified wholesale as a speech pause")
    }

    @Test fun quietAndSpeechExtremes() {
        val quiet = wave(4.0, FloatArray(400) { 0.001f })
        val raw = PauseDetector.raw(quiet, clip, PauseSettings(levelMode = null))
        require(raw.size == 1, "A fully quiet clip must appear as a reviewable pause, not falsely report no pauses")
        val zeroPadding = PauseSettings(before = 0.0, after = 0.0)
        val quietCuts = PauseDetector.effectiveCuts(raw.map { DetectedPause(it) }, clip, zeroPadding)
        require(abs(quietCuts.sumOf { it.duration } - (4 - TimelineEditing.frame)) < 0.000001, "Fully quiet footage keeps exactly one selectable frame with zero padding")
        val protected = PauseDetector.effectiveCuts(raw.map { DetectedPause(it) }, clip, PauseSettings())
        require(abs(protected.sumOf { it.duration } - 3.7) < 0.000001, "Fully quiet footage honors both padding values")
        val short = listOf(DetectedPause(SourceInterval(1.0, 1.2)))
        require(PauseDetector.effectiveCuts(short, clip, PauseSettings()).isEmpty(), "Padding-swallowed pauses do not count as removals")
        val overlapping = listOf(DetectedPause(SourceInterval(1.0, 3.0)), DetectedPause(SourceInterval(2.0, 3.5)))
        require(PauseDetector.effectiveCuts(overlapping, clip, zeroPadding) == listOf(SourceInterval(1.0, 3.5)), "Overlapping candidates never double-count removed time")
        val speech = wave(4.0, FloatArray(400) { 0.2f })
        require(PauseDetector.raw(speech, clip, PauseSettings(levelMode = null)).isEmpty(), "Continuous speech produces no pause")
        val split = wave(4.0, FloatArray(400) { if (it in 100 until 250) 0.001f else 0.2f })
        require(PauseDetector.raw(split, clip, PauseSettings(levelMode = null)) == listOf(SourceInterval(1.0, 2.5)), "Raw boundaries align with quiet windows")
    }

    @Test fun detectorKeepsIndependentPadding() {
        val wave = wave(4.0, FloatArray(400) { if (it in 100 until 250) 0.001f else 0.2f })
        val settings = PauseSettings(thresholdDB = -40.0, minimum = 0.6, before = 0.1, after = 0.2, levelMode = null)
        val cuts = PauseDetector.detect(wave, clip, settings)
        require(cuts.size == 1 && abs(cuts[0].start - 1.1) < 0.001 && abs(cuts[0].end - 2.3) < 0.001, "Pause detector retains independent pre/post speech padding")
        val fast = clip.copy(speed = SpeedSettings(rate = 2.0))
        val padded = PauseDetector.padded(SourceInterval(1.0, 2.5), fast, settings)
        require(abs(padded.start - 1.2) < 1e-9 && abs(padded.end - 2.1) < 1e-9, "Padding is measured on the playback clock")
    }

    @Test fun pauseHistoryStaysBoundedAcrossSplits() {
        val project = Project("Pause history size")
        project.append(SourceMedia(filename = "long.mp4", duration = 720.0))
        val edit = project.edit
        val candidates = (0 until 140).map { DetectedPause(SourceInterval(it * 5 + 1.0, it * 5 + 1.7), enabled = it % 7 != 0) }
        edit.updateClip(0) { it.copy(pauses = PauseSettings(cuts = candidates.filter { c -> c.enabled }.map { c -> SourceInterval(c.raw.start + 0.1, c.raw.end - 0.2) },
            candidates = candidates, analyzed = SourceInterval(0.0, 720.0))) }
        project.commit(edit)
        for (index in 1..40) project.commit(TimelineEditing.split(project.edit, index * 14.0))
        project.validate()
        require(project.edit.clips.all { clip -> clip.pauses?.candidates.orEmpty().all { it.raw.start < clip.end && it.raw.end > clip.start } },
            "Split pieces keep only their own pause metadata")
        require(project.edit.clips.all { clip -> clip.pauses?.analyzed?.let { it.start >= clip.start && it.end <= clip.end } ?: true }, "Analysis is pruned to each piece")
        val reopened = project.roundTripped()
        reopened.validate()
        require(reopened.edit == project.edit, "Pause choices survive reopening exactly")
    }

    @Test fun pauseRegionsAndRemoval() {
        val project = Project("Pauses")
        project.append(SourceMedia(filename = "source.mp4", duration = 6.0))
        val edit = project.edit
        val settings = PauseSettings(cuts = listOf(SourceInterval(2.0, 3.0)))
        edit.updateClip(0) { it.copy(pauses = settings, texts = listOf(textOverlay("Hello", 0.0, 5.0))) }
        val split = TimelineEditing.split(edit, 4.0)
        val regions = split.toolRegions.filter { it.tool == VideoTool.pauses }
        require(regions.size == 2 && regions.all { it.effectID == settings.id && it.label == "Pauses" }, "Pauses has one shared identity on each piece")
        val request = split.request(regions[0])
        val removed = split.removingTool(request)
        require(removed.clips[0].pauses == null && removed.clips[1].pauses?.id == settings.id, "Badge removal stays on its segment")
        require(removed.clips[0].texts == split.clips[0].texts, "Removing Pauses keeps the other effects")
        val all = split.removingTool(ToolRequest(VideoTool.pauses, 0.0..split.duration, "All", effectID = settings.id, clipIDs = split.clips.map { it.id }.toSet()))
        require(abs(all.duration - 6.0) < 1e-9 && abs(split.duration - 5.0) < 1e-9, "Remove restores the removed pause time")
    }

    /** Swift `EditorToolsDiagnostics`/`PausesDiagnostics`: Keep survives padding changes and reopening; Remove, Undo and Redo restore exactly. */
    @Test fun keptPausesSurvivePaddingReopenRemoveAndHistory() {
        val project = Project("Tools diagnostic")
        project.append(SourceMedia(filename = "shake.mp4", duration = 8.0))
        val edit = TimelineEditing.split(project.edit, 4.0)
        val pauseID = newUUID()
        edit.clips = edit.clips.mapIndexed { index, clip ->
            val raw = SourceInterval(clip.start + 1, min(clip.end, clip.start + 2))
            var pauses = PauseSettings(id = pauseID, before = 0.1, after = 0.2)
            pauses = pauses.copy(candidates = listOf(DetectedPause(raw, enabled = index == 0)), analyzed = SourceInterval(clip.start, clip.end),
                cuts = if (index == 0) listOf(PauseDetector.padded(raw, 1.0, pauses)) else emptyList())
            clip.copy(pauses = pauses)
        }
        val original = project.edit
        project.commit(edit); project.validate()
        val ids = edit.clips.map { it.id }.toSet()
        val model = ToolWorkspace(project, java.io.File("."), ToolRequest(VideoTool.pauses, 0.0..edit.duration, "Both clips", effectID = pauseID, clipIDs = ids), 0.0)
        model.startPreview()
        require(model.detected && model.choices.size == 2 && model.cutCount == 1, "Reopening restores the saved choices")
        model.updatePauseSettings(model.pauseSettings.copy(before = 0.2))
        require(model.choices.count { !it.enabled } == 1 && !model.analyzingPauses, "Kept pause was lost on padding change (padding never rescans)")
        val revised = Project.decode(Project.encode(project)); revised.commit(model.state()); revised.validate()
        val reopened = revised.roundTripped(); reopened.validate()
        require(reopened.edit.clips.flatMap { it.pauses?.candidates.orEmpty() }.count { !it.enabled } == 1, "Kept pause was lost on reopening")
        require(reopened.edit.clips.all { it.pauses?.before == 0.2 }, "The padding change is saved on every piece")
        require(model.state(removing = true).clips.all { it.pauses == null } &&
            model.state(removing = true).clips.map { it.copy(pauses = null) } == edit.clips.map { it.copy(pauses = null) }, "Remove failed to restore footage")
        val history = Project.decode(Project.encode(revised))
        history.undo(); require(history.edit == project.edit, "Undo restores the previous pause choices")
        history.redo(); require(history.edit == revised.edit, "Redo restores the cuts")
        history.undo(); history.undo(); require(history.edit == original, "Undo restores all original footage after Pauses")
        model.setPauseEnabled(true)
        require(model.state().clips.sumOf { it.pauses?.cuts?.size ?: 0 } == 1 && model.choices.count { it.enabled } == 1, "Only the selected pause changes")
    }

    @Test fun newFootageNeedsDetectAgain() {
        val project = Project("Extend")
        project.append(SourceMedia(filename = "source.mp4", duration = 10.0))
        val edit = project.edit
        val trimmed = edit.clips[0].copy(end = 5.0, pauses = PauseSettings(cuts = listOf(SourceInterval(1.0, 2.0)),
            candidates = listOf(DetectedPause(SourceInterval(0.9, 2.2))), analyzed = SourceInterval(0.0, 5.0)))
        edit.clips = listOf(trimmed)
        val extended = EditState(listOf(trimmed.copy(end = 10.0))).normalizedTools()
        val settings = extended.clips[0].pauses!!
        require(settings.analyzed == SourceInterval(0.0, 5.0) && settings.cuts == listOf(SourceInterval(1.0, 2.0)),
            "Extending a trim never scans new footage automatically; its analyzed range stays as it was")
    }

    private fun min(a: Double, b: Double) = kotlin.math.min(a, b)
}
