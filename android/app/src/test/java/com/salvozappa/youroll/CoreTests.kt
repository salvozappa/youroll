// Port of Tests/CoreTests.swift (persistence, history, timeline, retention and duplication parts)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CoreTests {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = temporary.root

    @Test fun persistenceAndPersistentUndoRedo() {
        val disk = ProjectDisk(File(root, "projects"))
        val project = Project("Persistence and signal test")
        val source = SourceMedia(filename = "source.mp4", duration = 4.0)
        project.append(source)
        val edited = project.edit
        edited.updateClip(0) { it.copy(start = 0.5) }; edited.updateClip(0) { it.copy(end = 3.5) }
        edited.effects = listOf(AudioEffect.make(EffectKind.highPass), AudioEffect.make(EffectKind.equalizer), AudioEffect(kind = EffectKind.gain, amount = -6.0))
        project.commit(edited)
        project.undo()
        disk.save(project)
        val reopened = disk.read(File(disk.folder(project.id), "project.json"))
        require(reopened.canRedo && reopened.edit.clips[0].start == 0.0, "Undo cursor survives disk round trip")
        reopened.redo()
        require(reopened.edit == edited, "Redo restores exact clips and ordered effects")
        reopened.undo()
        val branch = reopened.edit; branch.updateClip(0) { it.copy(end = 2.0) }
        reopened.commit(branch)
        require(!reopened.canRedo && reopened.sources == listOf(source), "Divergent edit drops redo, preserves original")
        val malformed = reopened.copy(); malformed.historyCursor = -1
        assertInvalidDocument("A negative cursor is rejected") { malformed.validate() }
        val badRange = reopened.copy()
        badRange.history = badRange.history.updated(badRange.historyCursor) { state -> state.copy().also { s -> s.updateClip(0) { it.copy(end = 5.0) } } }
        assertInvalidDocument("A clip beyond its source is rejected") { badRange.validate() }
        val pathEscape = reopened.copy(); pathEscape.sources = pathEscape.sources.updated(0) { it.copy(filename = "../source.mp4") }
        assertInvalidDocument("A path traversal is rejected") { pathEscape.validate() }
        require(reopened.sources[0].duration == 4.0, "Source duration is immutable across trim")
        val future = reopened.copy()
        val futureEdit = future.edit
        val unknown = AudioEffect(kind = EffectKind.unavailable("futureDereverbV9"), amount = 42.0, implementationVersion = 9)
        futureEdit.effects = futureEdit.effects + unknown; future.commit(futureEdit)
        disk.save(future)
        val preserved = disk.read(File(disk.folder(future.id), "project.json"))
        require(preserved.edit.effects.last() == unknown && preserved.canUndo, "Unknown effect kind and version preserve project, settings, and history")
        disk.save(project)
        // Skipped: AudioChainProcessor DSP checks (audio processing is not ported).
    }

    @Test fun frameAlignedSplitAndRangeDeletion() {
        val source = SourceMedia(filename = "source.mp4", duration = 4.0)
        val whole = EditState(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0)))
        val split = TimelineEditing.split(whole, 2.0)
        require(split.clips.size == 2 && split.duration == 4.0 && split.clips[0].end == split.clips[1].start, "Split keeps all frames and source coordinates")
        require(TimelineEditing.split(whole, 0.0) == whole, "Boundary split does not create an empty clip")
        val removed = TimelineEditing.removing(1.0..3.0, split)
        require(removed.clips.size == 2 && removed.duration == 2.0 && removed.clips[0].start == 0.0 && removed.clips[0].end == 1.0 &&
            removed.clips[1].start == 3.0 && removed.clips[1].end == 4.0, "Ripple range deletion crosses clip boundaries without shifting source coordinates")
        require(TimelineEditing.removing(0.0..4.0, split).clips.isEmpty(), "Whole selection can be removed and undone")
        val tiny = TimelineEditing.removing(0.001..0.002, whole)
        require(tiny == whole, "Subframe accidental selection is a no-op")
    }

    @Test fun projectRetention() {
        val disk = ProjectDisk(File(root, "retention-" + newUUID().uuidString))
        val utc = ZoneOffset.UTC
        val now = SwiftDate.fromUnix(1_800_000_000.0)
        val cutoff = SwiftDate.of(ZonedDateTime.ofInstant(now.toInstant(), utc).minusDays(30).toInstant())
        fun record(date: SwiftDate) = ProjectDeletion(newUUID(), newUUID(), date, true)
        require(!record(cutoff).isOlderThan30Days(now, utc), "Exactly 30 days is not older than 30 days")
        require(record(cutoff.addingTimeInterval(-1.0)).isOlderThan30Days(now, utc), "More than 30 days qualifies")
        require(!record(now.addingTimeInterval(60.0)).isOlderThan30Days(now, utc), "Future timestamps do not qualify")
        fun make(title: String): Project {
            val project = Project(title); project.updatedAt = SwiftDate.distantPast
            disk.save(project)
            File(disk.folder(project.id), "original.mov").writeText("original footage")
            return project
        }
        val old = make("Old"); val recent = make("Recent"); val legacy = make("Untracked"); val active = make("Active")
        val originalManifest = File(disk.folder(old.id), "project.json").readBytes()
        disk.moveToDeleted(old, cutoff.addingTimeInterval(-86400.0))
        disk.moveToDeleted(recent, now)
        disk.moveToDeleted(legacy, cutoff.addingTimeInterval(-86400.0))
        val legacyFile = File(File(disk.deletedRoot, legacy.id.uuidString), ".yourec-deletion.json")
        Files.delete(legacyFile.toPath())
        val oldReceipt = disk.deletionRecord(old.id, now)
        val recentReceipt = disk.deletionRecord(recent.id, now)
        val legacyReceipt = disk.deletionRecord(legacy.id, now)
        require(legacyReceipt.date == now && !legacyReceipt.dateIsKnown, "Unknown deletion dates start at first observation")
        val legacyReopened = disk.deletionRecord(legacy.id, now.addingTimeInterval(86400.0))
        require(legacyReopened == legacyReceipt, "First-observed date survives reopening")
        require(!recentReceipt.isOlderThan30Days(now, utc), "Old last-edit time does not make a newly deleted project expire")
        val trashedManifest = File(File(disk.deletedRoot, old.id.uuidString), "project.json").readBytes()
        require(trashedManifest.contentEquals(originalManifest), "Moving to trash leaves the manifest unchanged")
        val recentRemoved = disk.eraseExpiredDeletion(recentReceipt, now, utc)
        val legacyRemoved = disk.eraseExpiredDeletion(legacyReceipt, now, utc)
        require(!recentRemoved && !legacyRemoved, "Recent and untracked deletions cannot be purged")
        disk.restoreDeleted(old)
        require(File(disk.folder(old.id), "original.mov").readText() == "original footage", "Restore preserves originals")
        disk.moveToDeleted(old, now)
        val staleRemoved = disk.eraseExpiredDeletion(oldReceipt, now.addingTimeInterval(60 * 86400.0), utc)
        require(!staleRemoved && File(disk.deletedRoot, old.id.uuidString).exists(), "A stale confirmation cannot delete a restored and re-deleted project")
        val newReceipt = disk.deletionRecord(old.id, now)
        val removed = disk.eraseExpiredDeletion(newReceipt, now.addingTimeInterval(60 * 86400.0), utc)
        require(removed && !File(disk.deletedRoot, old.id.uuidString).exists(), "Confirmed expired project and its files are removed")
        require(File(disk.folder(active.id), "original.mov").exists(), "Active project files survive cleanup")
        legacyFile.writeText("broken receipt")
        var rejected = false
        try { disk.eraseExpiredDeletion(legacyReceipt, now.addingTimeInterval(60 * 86400.0), utc) } catch (error: Exception) { rejected = true }
        require(rejected && legacyFile.exists(), "Unreadable dates preserve the deleted project")
        val userTrash = ProjectDisk(File(root, "Projects")).deletedRoot
        for (name in listOf("EditorUITestProjects", "CinematicUITestProjects", "RetentionUITestProjects")) {
            val fixtureTrash = ProjectDisk(File(root, name)).deletedRoot
            require(fixtureTrash != userTrash && fixtureTrash.name == "$name-RecentlyDeleted", "Each fixture store has its own trash")
        }
        // Simulate a crash midway through a previously confirmed removal: the
        // manifest may already be gone, while original media is still present.
        val pending = File(disk.deletedRoot, ".purging-" + newUUID().uuidString)
        Files.move(File(disk.deletedRoot, legacy.id.uuidString).toPath(), pending.toPath())
        Files.delete(File(pending, "project.json").toPath())
        val unrelated = File(disk.deletedRoot, ".purging-not-a-queue-id")
        Files.createDirectories(unrelated.toPath())
        disk.finishPendingDeletions()
        require(!pending.exists() && unrelated.exists(), "Interrupted confirmed deletion resumes without touching unrelated folders")
        require(File(disk.deletedRoot, recent.id.uuidString).exists(), "Queued cleanup leaves ordinary Recently Deleted projects untouched")
    }

    @Test fun duplicateProject() = runBlocking {
        val disk = ProjectDisk(File(root, "Duplicate-" + newUUID().uuidString))
        val original = Project("Interview")
        val movie = SourceMedia(filename = "source.mp4", duration = 4.0)
        val voice = SourceMedia(filename = "voice.caf", duration = 2.0, audioOnly = true)
        original.append(movie); original.sources = original.sources + voice
        val edit = original.edit
        edit.timeline.insertAudio(voice, et(0.5)); original.commit(edit)
        edit.timeline.updateVideo(0) { it.copy(content = it.content.copy(images = listOf(ImageOverlay(filename = "image-title.png")))) }
        original.commit(edit); original.undo()
        original.exportSettings = original.exportSettings.copy(codec = ExportSettings.Codec.hevc)
        original.beginRecordingInsertion(1.0, null)
        disk.save(original)
        val source = disk.folder(original.id)
        val media = listOf("source.mp4", "voice.caf", "image-title.png", "unfinished.mov", "unfinished.events.json", "Recovery/" + newUUID().uuidString + "/salvaged.mov")
        val originalMovie = ByteArray(4096) { (it * 31 % 251).toByte() }
        for (name in media + listOf("Cache/preview.mov", "Exports/master.mp4")) {
            val file = File(source, name)
            file.parentFile.mkdirs()
            file.writeBytes(if (name == movie.filename) originalMovie else name.toByteArray())
        }
        val copy = ProjectArchive.duplicate(original, disk, "Interview copy") {}
        val folder = disk.folder(copy.id)
        val manifest = File(folder, "project.json")
        val reopened = disk.read(manifest)
        require(copy.id != original.id && reopened.title == "Interview copy" && reopened.history == original.history &&
            reopened.historyCursor == original.historyCursor && reopened.canUndo && reopened.canRedo, "Independent project with the full saved history and cursor")
        require(reopened.exportSettings == original.exportSettings && reopened.recordingInsertion == null, "Keep export settings and start future recordings independently")
        reopened.redo(); disk.save(reopened)
        val unchanged = disk.read(File(source, "project.json"))
        require(unchanged.edit == original.edit && unchanged.canRedo, "Redo in the copy cannot change the original")
        val lostTake = "Recovery/" + RecoveryFile.hash("unfinished.mov".toByteArray()) + "/"
        for (name in media) {
            val path = if (name.startsWith("unfinished.")) lostTake + name else name
            val bytes = File(folder, path).readBytes()
            require(bytes.contentEquals(if (name == movie.filename) originalMovie else name.toByteArray()), "Copied original or recovery asset: $name")
        }
        require(!File(folder, "Cache").exists() && !File(folder, "Exports").exists(), "Generated artifacts stay with their original project")
        val damaged = originalMovie.copyOf(); damaged[0] = (damaged[0].toInt() xor 0xff).toByte()
        File(source, movie.filename).writeBytes(damaged)
        disk.moveToDeleted(original); disk.eraseDeleted(original.id)
        require(File(folder, movie.filename).readBytes().contentEquals(originalMovie), "The copy keeps its own media after editing and deleting the original")
        Files.delete(File(folder, voice.filename).toPath())
        val repairable = ProjectArchive.duplicate(reopened, disk, "Repairable") {}
        require(repairable.sources.any { it.id == voice.id } && !File(disk.folder(repairable.id), voice.filename).exists(), "An existing offline reference stays relinkable")
        val empty = Project("Empty"); disk.save(empty)
        val emptyCopy = ProjectArchive.duplicate(empty, disk, "Empty copy") {}
        require(emptyCopy.edit.duration == 0.0 && emptyCopy.id != empty.id, "Empty projects duplicate")
        val before = disk.root.list()!!.toSet()
        lateinit var cancelled: kotlinx.coroutines.Deferred<Project>
        cancelled = async { ProjectArchive.duplicate(reopened, disk, "Cancelled") { cancelled.cancel() } }
        assertThrowing<CancellationException>("Expected cancellation") { cancelled.await() }
        Files.createSymbolicLink(File(folder, voice.filename).toPath(), File(folder, movie.filename).toPath())
        assertInvalidDocument("Expected copy failure") { runBlocking { ProjectArchive.duplicate(reopened, disk, "Incomplete") {} } }
        val after = disk.root.list()!!.toSet()
        require(before == after, "Canceled and failed copies leave no published project")
    }
}
