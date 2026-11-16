// Port of Tests/RecordingHistoryTests.swift (all parts that need no media decoding)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class RecordingHistoryTests {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = temporary.root

    private val a = SourceMedia(filename = "first.mov", duration = 764.485)
    private val b = SourceMedia(filename = "last.mov", duration = 204.31554166666666)
    private val first = TimelineClip(sourceID = a.id, start = 0.0, end = a.duration)
    private val last = TimelineClip(sourceID = b.id, start = 0.0, end = b.duration)
    private val c = SourceMedia(filename = "continued.mov", duration = 7.0)

    @Test fun capturedFootageSurvivesUndoRedo() {
        val project = Project("Recording undo regression")
        project.append(a); project.append(b)
        require(!project.canUndo && project.history.size == 1, "Recording additions are a baseline, not editing undo steps")
        val edit = project.edit; edit.updateClip(0) { it.copy(start = 2.0) }; project.commit(edit)
        project.undo(); project.undo()
        require(abs(project.edit.duration - a.duration - b.duration) < 0.000001, "Undo cannot remove the final recording")
        val branched = project.edit; branched.effects = listOf(AudioEffect.make(EffectKind.voiceIsolation)); project.commit(branched)
        require(project.edit.clips.size == 2 && !project.canRedo, "A new edit after Undo retains all captured segments")
        project.undo()
        project.append(c)
        require(project.canRedo && project.edit.clips.size == 3, "Continuing recording retains the existing editing redo branch")
        project.redo(); require(project.edit.effects.size == 1 && project.edit.clips.size == 3, "Redo restores effects while preserving continued footage")
        project.undo(); require(project.edit.effects.isEmpty() && project.edit.clips.size == 3, "Older edits remain undoable after recording continuation")
        val deletion = project.edit; deletion.clips = deletion.clips.filterIndexed { i, _ -> i != 1 }; project.commit(deletion)
        require(project.edit.clips.none { it.sourceID == b.id }, "Explicit clip deletion still works")
        project.undo(); require(project.edit.clips.size == 3, "Undoing an explicit deletion restores the deleted clip")
        project.redo(); project.append(SourceMedia(filename = "later.mov", duration = 3.0))
        require(project.edit.clips.none { it.sourceID == b.id }, "New recording does not reverse an intentional deletion")
        project.undo(); require(project.edit.clips.size == 4, "Deletion can still be undone without losing later capture")
        project.validate()
    }

    @Test fun legacyMigration() {
        val legacy = Project("Legacy append history")
        legacy.sources = listOf(a, b); legacy.recordingHistoryVersion = null
        val trimmed = EditState(listOf(first)); trimmed.updateClip(0) { it.copy(start = 2.0) }
        val continued = trimmed.copy(); continued.clips = continued.clips + last
        val filtered = continued.copy(); filtered.effects = listOf(AudioEffect.make(EffectKind.voiceIsolation))
        val removed = filtered.copy(); removed.clips = removed.clips.dropLast(1)
        legacy.history = listOf(EditState(), EditState(listOf(first)), trimmed, continued, filtered, removed)
        for (cursor in legacy.history.indices) {
            val migrated = legacy.copy(); migrated.historyCursor = cursor; migrated.protectRecordingHistory()
            migrated.validate()
            require(migrated.history.size == 4, "Migration removes only the two capture transitions")
            require(migrated.historyCursor == listOf(0, 0, 1, 1, 2, 3)[cursor], "Migration maps the current undo cursor correctly")
            val expectedCount = if (cursor == 5) 1 else 2
            require(migrated.edit.clips.size == expectedCount, "Migration preserves deliberate deletion, including the current state")
            val saved = migrated.history; migrated.protectRecordingHistory()
            require(migrated.history == saved, "Migration is idempotent")
            while (migrated.canUndo) migrated.undo()
            require(abs(migrated.edit.duration - a.duration - b.duration) < 0.000001, "All historical edits undo to the complete recording")
        }
        // A full source manually reinserted after deletion is an editing step, not
        // its first appearance. Do not rebase that later insertion.
        legacy.history = legacy.history + filtered; legacy.historyCursor = 6
        legacy.protectRecordingHistory(); legacy.undo()
        require(legacy.edit.clips.size == 1, "A later reinsertion remains undoable")

        for (variant in 0 until 3) {
            val ambiguous = Project("Non-capture transition")
            ambiguous.sources = listOf(a); ambiguous.recordingHistoryVersion = null
            val inserted = EditState(listOf(first))
            if (variant == 0) inserted.updateClip(0) { it.copy(crop = cropJson(2.0)) }
            if (variant == 1) inserted.effects = listOf(AudioEffect.make(EffectKind.voiceIsolation))
            if (variant == 2) inserted.outputSize = CGSize(1080.0, 1920.0)
            ambiguous.history = listOf(EditState(), inserted); ambiguous.historyCursor = 1
            ambiguous.protectRecordingHistory()
            require(ambiguous.history.size == 2 && ambiguous.historyCursor == 1, "Migration leaves ambiguous tool/effect/format changes alone")
        }
        val repeated = Project("Unrelated historical no-op")
        repeated.sources = listOf(a); repeated.recordingHistoryVersion = null
        repeated.history = listOf(EditState(), EditState(), EditState(listOf(first))); repeated.historyCursor = 2
        repeated.protectRecordingHistory()
        require(repeated.history.size == 2 && repeated.historyCursor == 1, "Migration removes only capture steps, not unrelated duplicate snapshots")

        val undoneCapture = Project("Direct decode before first edit")
        undoneCapture.sources = listOf(a, b); undoneCapture.recordingHistoryVersion = null
        undoneCapture.history = listOf(EditState(), EditState(listOf(first)), EditState(listOf(first, last)))
        undoneCapture.historyCursor = 1
        val decoded = undoneCapture.roundTripped()
        require(decoded.edit.clips.size == 2 && !decoded.canUndo, "Every decode normalizes capture before exposing edit or undo availability")
        val nextEdit = decoded.edit; nextEdit.effects = listOf(AudioEffect.make(EffectKind.voiceIsolation)); decoded.commit(nextEdit)
        require(decoded.edit.clips.size == 2 && decoded.canUndo, "The first edit after direct decoding cannot reintroduce lost footage")
    }

    @Test fun explicitMissingTakeRecoveryAndJournal() {
        val trimmed = EditState(listOf(first)); trimmed.updateClip(0) { it.copy(start = 2.0) }
        // Reproduce the user's saved branch: both sources survive, but the second
        // recording disappeared from every retained snapshot after Undo + a trim.
        var affected = Project("Missing final section")
        affected.recordingHistoryVersion = null; affected.sources = listOf(a, b)
        val tools = trimmed.copy(); tools.updateClip(0) { it.copy(crop = cropJson(2.0)) }; tools.effects = listOf(AudioEffect.make(EffectKind.voiceIsolation))
        affected.history = listOf(EditState(), EditState(listOf(first)), trimmed, tools); affected.historyCursor = 3
        val disk = ProjectDisk(File(root, "recording-history"))
        disk.save(affected)
        val raw = parseJson(File(disk.folder(affected.id), "project.json").readBytes()) as JsonObject
        require(raw["recordingHistoryVersion"] == null, "Legacy fixture genuinely omits the new optional field")
        affected = disk.read(File(disk.folder(affected.id), "project.json"))
        require(affected.edit.clips.size == 1, "Loading never guesses that an unused source should return")
        val before = affected.history
        affected.restoreUnusedRecording(b.id)
        require(affected.historyCursor == 2 && affected.history.size == before.size, "Recovery retains the current edit and all editing history")
        for ((old, new) in before.zip(affected.history)) {
            val prefix = new.copy(); prefix.clips = prefix.clips.dropLast(1)
            require(prefix == old && new.clips.lastOrNull()?.sourceID == b.id, "Recovery preserves every edit and appends only the intact missing take")
        }
        disk.save(affected); affected = disk.read(File(disk.folder(affected.id), "project.json"))
        while (affected.canUndo) affected.undo()
        require(abs(affected.edit.duration - a.duration - b.duration) < 0.000001, "Recovered footage survives Undo all and reopening")
        while (affected.canRedo) affected.redo()
        require(affected.edit.clips[0].crop?.zoom == 2.0 && affected.edit.effects.size == 1, "Recovered project retains its video and audio effects")
        assertInvalidDocument("A recovered take cannot be restored twice") { affected.restoreUnusedRecording(b.id) }
        // Real captured boundaries are not generally on an absolute 30-fps grid.
        val edgeA = TimelineClip(sourceID = newUUID(), start = 0.0, end = 10.017)
        val edgeB = TimelineClip(sourceID = newUUID(), start = 0.0, end = 5.013)
        val edgeC = TimelineClip(sourceID = newUUID(), start = 0.0, end = 7.0)
        val boundaryState = EditState(listOf(edgeA, edgeB, edgeC))
        val cut = TimelineEditing.cut(edgeA.duration..(edgeA.duration + edgeB.duration), boundaryState)
        require(cut.first.clips == listOf(edgeA, edgeC) && cut.second == listOf(edgeB), "Boundary-exact Cut leaves no slivers and changes no adjacent source coordinates")
        val pasted = TimelineEditing.inserting(cut.second, cut.first, edgeA.duration)
        require(pasted.clips.map { it.sourceID } == boundaryState.clips.map { it.sourceID } && pasted.duration == boundaryState.duration,
            "Pasting at an exact take join restores the order and exact duration")
        val decorated = edgeB.copy(texts = listOf(textOverlay("Copy", 0.0, 3.0)),
            mirror = MirrorSettings(horizontal = true))
        val twice = TimelineEditing.inserting(listOf(decorated), EditState(listOf(decorated)), 0.0)
        require(twice.clips[0].texts!!.first().id != twice.clips[1].texts!!.first().id &&
            twice.clips[0].mirror!!.id != twice.clips[1].mirror!!.id, "Pasted effects edit independently from their originals")
        require(TimelineEditing.snap(10.02, boundaryState.clips, 60.0) == edgeA.duration, "Snapping retains the exact recorded boundary")
        var insertSession = Project("Record here")
        insertSession.append(a); insertSession.recordingInsertion = 10.0
        insertSession.addRecording(b); insertSession.addRecording(c)
        insertSession.validate()
        require(insertSession.history.size == 2 && insertSession.edit.clips.map { it.sourceID } == listOf(a.id, b.id, c.id, a.id),
            "All recorded insertion segments form one ordered undo step")
        insertSession = insertSession.roundTripped()
        insertSession.undo(); require(insertSession.edit.clips.size == 1 && insertSession.sources.size == 3, "Undoing Record here preserves every original recording")
        insertSession.redo(); require(insertSession.edit.outputSize == null && insertSession.edit.canvasSourceID == a.id,
            "Record insertion retains the automatic canvas reference through reopen/redo")
        val late = Project("Late recording while an inspector is open")
        late.append(a); val openDraft = late.edit
        openDraft.timeline.updateAudio(0) { it.copy(volumeDB = -4.0) }
        late.append(b); late.commit(openDraft)
        require(late.edit.clips.map { it.sourceID } == listOf(a.id, b.id) && late.edit.timeline.audio[0].volumeDB == -4.0,
            "A stale tool draft cannot hide capture indexed after the tool opened")
        late.undo(); require(late.edit.clips.size == 2, "Undo after a stale-draft commit still preserves late capture")
        val recovery = Project("Late record-here recovery")
        recovery.append(a); val staleInsertionDraft = recovery.edit
        recovery.recordingInsertion = 2.0; recovery.addRecording(b)
        val recoveredState = recovery.edit
        require(!recovery.canCommit(staleInsertionDraft), "An inspector opened before Record here recovery reports a conflict")
        recovery.commit(staleInsertionDraft)
        require(recovery.edit == recoveredState, "A stale draft never overwrites a recovered inserted recording")
        val recoveredMarks = CaptureEvent.recovered(listOf(CaptureEvent(time = 1.0, kind = "bookmark", detail = ""), CaptureEvent(time = 5.8, kind = "bookmark", detail = "")), 4.0)
        require(recoveredMarks.size == 1 && recoveredMarks[0].time == 1.0, "A mark beyond the last written fragment does not prevent recording recovery")
        // Published payloads are immutable, and later ordinary recording is a
        // small journal update even with both Undo and Redo branches present.
        var journal = Project("Immutable capture ledger")
        journal.append(a); val modified = journal.edit; modified.timeline.updateAudio(0) { it.copy(volumeDB = -3.0) }; journal.commit(modified)
        disk.save(journal)
        val manifestURL = File(disk.folder(journal.id), "project.json")
        val historyURL = File(disk.folder(journal.id), "History")
        val filesBefore = historyURL.listFiles()!!.toList()
        val bytesBefore = filesBefore.associate { it.name to it.readBytes() }
        journal.undo(); journal.append(b); disk.save(journal)
        val filesAfter = historyURL.listFiles()!!.toList()
        require(filesAfter.size == filesBefore.size, "Recording publishes no rewritten history payloads")
        for (file in filesAfter) require(file.readBytes().contentEquals(bytesBefore[file.name]), "Every old snapshot stays byte-identical")
        val summary = disk.read(manifestURL, summary = true)
        require(summary.deferredHistory != null && summary.canRedo && summary.edit.clips.size == 2, "Library reads only the active snapshot and materializes later capture")
        assertInvalidDocument("A library summary cannot be saved over the full history") { disk.save(summary) }
        journal = disk.read(manifestURL); journal.redo()
        require(journal.edit.timeline.audio[0].volumeDB == -3.0 && journal.edit.clips.size == 2, "Journal capture survives reopened Redo")
        val stableIDs = journal.edit.timeline.allIDs
        require(stableIDs == journal.edit.timeline.allIDs, "Capture materialization uses stable item and link identities")
        val lastCapturedID = journal.edit.timeline.video.last().id
        val deleted = journal.edit; deleted.timeline.remove(deleted.timeline.selection(of = lastCapturedID)); journal.commit(deleted)
        journal.append(c); disk.save(journal); journal = disk.read(manifestURL)
        require(journal.edit.clips.map { it.sourceID } == listOf(a.id, c.id), "Snapshot capture cursor never resurrects a deliberate deletion")
        journal.undo(); require(journal.edit.clips.map { it.sourceID } == listOf(a.id, b.id, c.id), "Undo restores a deleted recording while retaining subsequent capture")
        // Corrupt history must be rejected rather than silently losing edits.
        val latestManifest = parseJson(manifestURL.readBytes()) as JsonObject
        val referenced = latestManifest.field("snapshotFiles").array().map { it.primitiveContent()!! }
        val payload = File(historyURL, referenced[0])
        val original = payload.readBytes()
        payload.writeText("{}")
        assertInvalidDocument("Corrupt history must be rejected") { disk.read(manifestURL) }
        payload.writeBytes(original)
    }

    @Test fun recordingRecoveryAssets() {
        val disk = ProjectDisk(File(root, "recovery-assets"))
        val project = Project("Recovery with background video")
        project.append(SourceMedia(filename = "original.mov", duration = 4.0))
        // VideoEffectSettings(.chromaKey) with a background video.
        val background = VideoEffectSettings(VideoEffectID.chromaKey, 0.0, 4.0).setting("background", EffectValue.choice("Video"))
            .setting("media", EffectValue.media("effect-background.mov"))
        val edit = project.edit; edit.updateClip(0) { it.copy(videoEffects = listOf(background)) }; project.commit(edit)
        disk.save(project)
        val folder = disk.folder(project.id)
        val manifest = File(folder, "project.json")
        val names = listOf("original.mov", "effect-background.mov", "effect-unused.mp4", "EFFECT-unused.MOV", ".partial.mov", "1790000000000-new-take.mov")
        for (name in names) File(folder, name).writeText("fixture")
        File(folder, "directory.mov").mkdir()
        fun check(p: Project) {
            val candidates = disk.unindexedRecordings(p).map { it.name }
            require(candidates == listOf("1790000000000-new-take.mov"), "Recovery finds actual unindexed takes and excludes background assets, hidden files and directories")
        }
        // The old startup scan attempted this and falsely reported project corruption.
        val mistaken = project.copy(); mistaken.append(SourceMedia(filename = "effect-background.mov", duration = 4.0))
        assertInvalidDocument("A background asset is not a recording") { mistaken.validate() }
        check(project); check(disk.read(manifest, summary = true))
        project.undo(); disk.save(project)
        val summary = disk.read(manifest, summary = true)
        require(summary.effectFiles.isEmpty(), "Undone background is absent from the library summary")
        check(summary)
        project.redo(); val removed = project.edit; removed.updateClip(0) { it.copy(videoEffects = null) }; project.commit(removed); disk.save(project)
        val before = manifest.readBytes()
        check(disk.read(manifest, summary = true)); check(disk.read(manifest))
        val after = manifest.readBytes()
        require(after.contentEquals(before), "Recovery discovery never rewrites projects")
        val orphan = File(folder, "1790000000000-new-take.mov")
        val sidecar = File(folder, "1790000000000-new-take.events.json")
        sidecar.writeText("sidecar")
        disk.quarantineRecording(orphan, project)
        val preserved = disk.recoveryFiles(project)
        val remaining = disk.unindexedRecordings(project)
        require(preserved.size == 1 && remaining.isEmpty(), "Quarantine preserves an unfinished take without retrying it on startup")
        require(preserved[0].readText() == "fixture" && File(preserved[0].parentFile, "1790000000000-new-take.events.json").exists(),
            "Recovery keeps the movie and its sidecars together")
        disk.quarantineRecording(File(folder, "original.mov"), project)
        require(File(folder, "original.mov").exists(), "Quarantine refuses a referenced source")
        require(manifest.readBytes().contentEquals(before), "Quarantine does not modify the project or Undo history")
    }

    @Test fun automaticCanvasHistory() {
        val portrait = SourceMedia(filename = "portrait.mov", duration = 3.0)
        val landscape = SourceMedia(filename = "landscape.mov", duration = 4.0)
        val portraitSize = CGSize(1080.0, 1920.0)
        var project = Project("Canvas history")
        project.append(landscape)
        project.recordingInsertion = 0.0
        project.addRecording(portrait)
        require(project.schemaVersion == 10 && project.edit.clips.firstOrNull()?.sourceID == portrait.id && project.edit.canvasSourceID == landscape.id,
            "Recording at the front preserves the existing picture reference")
        val disk = ProjectDisk(File(root, newUUID().uuidString))
        disk.save(project)
        val url = File(disk.folder(project.id), "project.json")
        project = disk.read(url)
        require(project.edit.canvasSourceID == landscape.id && project.edit.outputSize == null, "A non-leading automatic reference survives disk reopen")
        val pictures = project.edit
        pictures.timeline.video = pictures.timeline.video.filter { it.content.sourceID != landscape.id }
        pictures.timeline.links = emptyList()
        pictures.timeline.audio = pictures.timeline.audio.map { it.copy(followsVideo = null) }
        project.commit(pictures)
        project.validate()
        require(project.edit.canvasSourceID == portrait.id && project.edit.timeline.audio.any { it.sourceID == landscape.id },
            "Detached audio alone cannot hold the old picture orientation")
        project.undo(); require(project.edit.canvasSourceID == landscape.id, "Undo restores the former reference")
        project.redo(); require(project.edit.canvasSourceID == portrait.id, "Redo restores the remaining picture's orientation")

        // Recreate the on-disk representation written by the old UI. A later capture
        // must neither rewrite the deletion nor be mistaken for a Crop operation.
        val old = Project("Implicit portrait padding")
        old.append(portrait); old.append(landscape)
        val deleted = old.edit; deleted.clips = deleted.clips.drop(1); deleted.outputSize = portraitSize; old.commit(deleted)
        val gain = old.edit; gain.timeline.updateAudio(0) { it.copy(volumeDB = -3.0) }; old.commit(gain)
        val crop = old.edit; crop.updateClip(0) { it.copy(crop = cropJson(1.2)) }; old.commit(crop)
        old.append(SourceMedia(filename = "subsequent.mov", duration = 2.0))
        old.undo()
        fun reopenLegacy(project: Project): Project = Project.fromJson(JsonObject(project.toJson() - "automaticCanvasVersion"), null)
        disk.save(old)
        val legacyURL = File(disk.folder(old.id), "project.json")
        val header = parseJson(legacyURL.readBytes()) as JsonObject
        legacyURL.writeBytes(JsonObject(header - "automaticCanvasVersion").encodedBytes())
        val summary = disk.read(legacyURL, summary = true)
        require(summary.deferredHistory != null, "Project browser can still load only the current snapshot")
        var recovered = disk.read(legacyURL)
        disk.save(recovered)
        recovered = disk.read(legacyURL)
        require(recovered.edit.outputSize == null && recovered.edit.canvasSourceID == landscape.id && recovered.edit.timeline.audio[0].volumeDB == -3.0,
            "Opening a padded project repairs its automatic format without losing later edits")
        recovered.undo(); recovered.undo()
        require(recovered.edit.canvasSourceID == portrait.id && recovered.edit.clips.size == 3, "Undo recovers portrait and the later appended take")
        recovered.redo(); recovered.redo(); recovered.redo()
        require(recovered.edit.outputSize == portraitSize && recovered.edit.clips[0].crop?.zoom == 1.2, "Redoing a deliberate crop retains its fixed format")

        val explicit = Project("Intentional portrait output")
        explicit.append(landscape)
        val fixed = explicit.edit; fixed.outputSize = portraitSize; explicit.commit(fixed)
        val preserved = reopenLegacy(explicit)
        require(preserved.edit.outputSize == portraitSize, "A deliberate shape-only edit stays fixed even without a crop rectangle")
        explicit.history = listOf(fixed); explicit.historyCursor = 0
        val ambiguous = reopenLegacy(explicit)
        require(ambiguous.edit.outputSize == portraitSize, "A format without evidence of how it was chosen stays fixed")
    }

    @Test fun timelineVideoInsertion() {
        val left = SourceMedia(filename = "left.mov", duration = 4.0)
        val right = SourceMedia(filename = "right.mov", duration = 2.0)
        val short = SourceMedia(filename = "short.mov", duration = 2.0)
        val long = SourceMedia(filename = "long.mov", duration = 8.0)
        val bed = SourceMedia(filename = "bed.m4a", duration = 16.0, audioOnly = true)
        val project = Project("Video insertion sites")
        project.append(left); project.append(right)
        project.sources = project.sources + listOf(short, long, bed)
        val gapped = project.edit
        gapped.timeline.insertTime(et(6), et(4))
        gapped.timeline.insertAudio(bed, zero)
        project.commit(gapped)
        val original = project.edit
        val gap = VideoInsertionSite.gap(et(4))
        val layered = original.timeline.copy()
        val overlay = TimelineClip(sourceID = long.id, start = 0.0, end = 5.0)
        layered.video = layered.video + VideoTimelineItem(newUUID(), et(3), overlay, 1)
        layered.generated = layered.generated + TimelineGeneratedRegion(newUUID(), newUUID(), iv(8, 10))
        require(layered.videoCoverage == listOf(iv(0, 12)), "Overlapping layers and generated transition frames do not expose false gaps")
        fun start(edit: EditState, source: SourceMedia): Double? = edit.timeline.video.firstOrNull { it.content.sourceID == source.id }?.start?.seconds
        val filled = project.copy()
        filled.insertVideos(listOf(short, short), 8.0, gap)
        require(filled.edit.timeline.video.map { it.start.seconds } == listOf(0.0, 4.0, 6.0, 10.0), "Batch fills from left edge, preserving the remaining gap and the right clip")
        require(filled.edit.timeline.audio.firstOrNull { it.sourceID == bed.id } == original.timeline.audio.firstOrNull { it.sourceID == bed.id },
            "Audio crossing a picture gap stays intact when inserts fit")
        filled.undo(); require(filled.edit == original, "One Undo restores the exact gap")
        filled.redo(); require(start(filled.edit, right) == 10.0, "Redo preserves the right clip timing")
        val overflow = project.copy()
        overflow.insertVideos(listOf(long), 8.0, gap)
        require(start(overflow.edit, long) == 4.0 && start(overflow.edit, right) == 12.0, "Only overflow ripples at the right edge")
        require(overflow.edit.timeline.audio.filter { it.sourceID == bed.id } == original.timeline.audio.filter { it.sourceID == bed.id },
            "Overflow keeps an independent soundtrack continuous")
        val appended = project.copy()
        appended.insertVideos(listOf(short), 16.0, VideoInsertionSite.end)
        require(start(appended.edit, short) == 12.0 && appended.edit.duration == 16.0, "Append meets last picture before an audio-only tail")
        val leading = original.copy()
        leading.timeline.insertTime(et(5), zero)
        val prepend = project.copy(); prepend.commit(leading)
        prepend.insertVideos(listOf(short), 0.0, VideoInsertionSite.start)
        require(start(prepend.edit, short) == 3.0 && start(prepend.edit, left) == 5.0, "Prepend backfills the lead-in flush against the first picture")
        prepend.undo(); prepend.insertVideos(listOf(long), 0.0, VideoInsertionSite.start)
        require(start(prepend.edit, long) == 0.0 && start(prepend.edit, left) == 8.0, "A long prepend shifts only its excess")
        val audioOnly = Project("Soundtrack")
        audioOnly.sources = listOf(bed, short)
        val music = audioOnly.edit; music.timeline.insertAudio(bed, zero); audioOnly.commit(music)
        audioOnly.insertVideos(listOf(short), 10.0, VideoInsertionSite.start)
        require(start(audioOnly.edit, short) == 0.0 && audioOnly.edit.timeline.audio.firstOrNull { it.sourceID == bed.id }?.start == zero,
            "First picture starts with the soundtrack")

        val takeA = SourceMedia(filename = "take-a.mov", duration = 2.0)
        val takeB = SourceMedia(filename = "take-b.mov", duration = 4.0)
        for (site in listOf(VideoInsertionSite.start, gap, VideoInsertionSite.end)) {
            var recorded = project.copy()
            if (site == VideoInsertionSite.start) recorded.commit(leading)
            val base = recorded.edit
            recorded.beginRecordingInsertion(0.0, site)
            recorded.addRecording(takeA)
            recorded = recorded.roundTripped()
            recorded.addRecording(takeB); recorded.recordingInsertion = null; recorded.recordingInsertionStep = null
            val span = site.placement(base.timeline, et(6))
            require(start(recorded.edit, takeA) == span.start.seconds && start(recorded.edit, takeB) == span.start.seconds + 2,
                "Recorded segments match an ordered import after reload")
            recorded.validate()
            recorded.undo()
            require(recorded.edit.timeline.video == base.timeline.video && recorded.edit.timeline.audio == base.timeline.audio, "Record here remains a single Undo")
        }
    }
}
