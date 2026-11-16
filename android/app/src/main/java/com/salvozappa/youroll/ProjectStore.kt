// Port of YouRec/ProjectStore.swift (non-UI logic)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The Swift class is a @MainActor ObservableObject. This port exposes the same
// published state as StateFlows and expects to be used from one thread (the
// main thread in the app); suspend functions move file and media work to
// [ioDispatcher]. It takes the projects root directly (no android Context) so
// it runs in plain JVM tests: the app passes `File(filesDir, "Projects")`.
//
// Projects inside the flows are snapshots: never mutate one in place; change a
// project only through `update`/`commit`.
//
// Saving off the main thread (YouRoll). iOS saves on the main actor; Android's 5 s
// ANR limit makes that risky for long histories, so the app passes
// [SaveDispatchers] (JVM tests leave it null and save inline, as before):
// - A change is published in memory at once, then saved by one serial writer.
//   `update`, `commit` and `addRecording` read the newest state, change it and
//   publish it in one step on the owner thread, without suspending in between,
//   so a concurrent edit is never lost. `update` returns whether the change
//   was accepted; a save failure arrives later. `addRecording` waits for its
//   save, because the recorder keeps a take paused when it could not be added.
// - Every write of a listed project's manifest, history, backups or journal
//   holds [writeLock] (create, import and duplicate save a new project before
//   it is listed). Each accepted state has a generation. The writer skips a
//   state that a newer one superseded (a save writes the whole history, so the
//   newer save includes it) and never writes an older state after a newer one:
//   the head on disk only moves forward.
// - A failed save is journaled by the writer right after the failure, before
//   any later write of that project, unless a newer state is already queued (its
//   save comes next and journals itself if it fails). A later successful save
//   always moves the head past the journal, which `pendingDraft` then ignores,
//   so a relaunch restores the newest failed state and never an older one. The
//   result reaches the owner thread in write order and leaves the unsaved draft,
//   the journaled flag and the "Changes not saved" error exactly as an inline
//   failure does. While a draft exists it is the newest state.
// - Rare paths still write on the owner thread, after any write in progress:
//   Retry save, Move to Recently deleted (it first writes a state still queued),
//   the batch-import commit and `create`.
// - Opening a library summary decodes its history on [ioDispatcher]
//   (`prepareProject`, `selectPrepared`), while the UI shows that it is opening.
//   `openProject`/`select` stay synchronous for startup (already off the main
//   thread) and rare paths.
// - Limit: an edit accepted in the milliseconds before a process death, while
//   its save is still running, is lost, as it was when the process died during
//   the old synchronous save. The disk then holds the previous saved state (or
//   journal), never a partial or older one.
//
// Not ported yet (TODO.md): thumbnails and waveforms, Photos/Photo Picker batch
// insertion, audio file import, recovery of unindexed takes (RecordingSalvage),
// ZIP archives and relinking, recovered-project creation, effect application
// receipts, the clipboard, and keeping the device awake during transfers.
package com.salvozappa.youroll

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ---- App layer must provide ----
 * Media facts ProjectStore needs about a movie or audio file. Swift reads them
 * with AVURLAsset; on Android implement with MediaExtractor /
 * MediaMetadataRetriever. Not available in JVM tests unless faked.
 */
interface MediaProbe {
    data class Info(val duration: Double, val hasVideo: Boolean, val hasAudio: Boolean)
    /** Throw when the file is not a readable movie/audio file. */
    suspend fun probe(file: File): Info
    /**
     * Swift `ImportedAudioFile.stage`: a container declaring an audio track is
     * not sufficient evidence that its codec decodes or that its samples exist.
     */
    suspend fun decodesAudio(file: File): Boolean = probe(file).hasAudio
}

/** Swift `UserDefaults` for the selected project. The app can back this with DataStore/SharedPreferences. */
interface ProjectPreferences {
    fun selectedProject(key: String): String?
    fun setSelectedProject(key: String, value: String)
}

class InMemoryProjectPreferences : ProjectPreferences {
    private val values = HashMap<String, String>()
    override fun selectedProject(key: String) = values[key]
    override fun setSelectedProject(key: String, value: String) { values[key] = value }
}

/**
 * Background saving: project writes run on [writer] (the app passes a serial
 * view of Dispatchers.IO) and their results are applied on [owner], the thread
 * that uses the store (Dispatchers.Main in the app).
 */
data class SaveDispatchers(val writer: CoroutineDispatcher, val owner: CoroutineDispatcher)

class ProjectStore(
    root: File,
    val mediaProbe: MediaProbe? = null,
    private val preferences: ProjectPreferences = InMemoryProjectPreferences(),
    private val selectionKey: String = "selectedProject",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Null saves inline on the calling thread (JVM tests); see the file comment. */
    saveDispatchers: SaveDispatchers? = null,
) {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    private val _deletedProjects = MutableStateFlow<List<Project>>(emptyList())
    val deletedProjects: StateFlow<List<Project>> = _deletedProjects.asStateFlow()
    private val _deletionRecords = MutableStateFlow<Map<UUID, ProjectDeletion>>(emptyMap())
    val deletionRecords: StateFlow<Map<UUID, ProjectDeletion>> = _deletionRecords.asStateFlow()
    private val _selectedID = MutableStateFlow<UUID?>(null)
    val selectedID: StateFlow<UUID?> = _selectedID.asStateFlow()
    private val _unsavedDrafts = MutableStateFlow<Map<UUID, Project>>(emptyMap())
    val unsavedDrafts: StateFlow<Map<UUID, Project>> = _unsavedDrafts.asStateFlow()
    private val _journaledDrafts = MutableStateFlow<Set<UUID>>(emptySet())
    val journaledDrafts: StateFlow<Set<UUID>> = _journaledDrafts.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _unavailableProjects = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val unavailableProjects: StateFlow<Map<UUID, String>> = _unavailableProjects.asStateFlow()
    private val _recoveryMessages = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val recoveryMessages: StateFlow<Map<UUID, String>> = _recoveryMessages.asStateFlow()
    /** Swift `trackClipboards`: one timeline clipboard per project, kept for the app session and never saved. */
    private val _trackClipboards = MutableStateFlow<Map<UUID, TimelineClipboard>>(emptyMap())
    val trackClipboards: StateFlow<Map<UUID, TimelineClipboard>> = _trackClipboards.asStateFlow()
    fun setTrackClipboard(projectID: UUID, clipboard: TimelineClipboard?) {
        _trackClipboards.value = if (clipboard == null) _trackClipboards.value - projectID else _trackClipboards.value + (projectID to clipboard)
    }
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private val _operationProgress = MutableStateFlow(0.0)
    val operationProgress: StateFlow<Double> = _operationProgress.asStateFlow()
    private val _operationLabel = MutableStateFlow("")
    val operationLabel: StateFlow<String> = _operationLabel.asStateFlow()

    val disk = ProjectDisk(root)
    val selected: Project? get() = _projects.value.firstOrNull { it.id == _selectedID.value }

    // ---- The writer (see the file comment) ----

    /** A finished write of [project], the state with [generation]; [sequence] orders writes. */
    private class SaveReport(val project: Project, val generation: Long, val sequence: Long, val failure: Exception?, val journaled: Boolean)
    /** Per project: the newest generation saved, and the newest whose save failed while it was the newest state. */
    private data class SaveState(val saved: Long = 0, val failed: Long = 0)

    private val writerScope = saveDispatchers?.let { CoroutineScope(SupervisorJob() + it.writer) }
    private val ownerScope = saveDispatchers?.let { CoroutineScope(SupervisorJob() + it.owner) }
    /** Held for every write of a listed project's manifest, history, backups or journal. */
    private val writeLock = ReentrantLock()
    /** Guarded by [writeLock]: numbers writes. */
    private var writeSequence = 0L
    /** Guarded by [writeLock]: the newest generation whose write was attempted (or, on owner-thread paths, succeeded). */
    private val written = HashMap<UUID, Long>()
    /** Written on the owner thread, read by the writer: the newest accepted generation. Absent is 0, the state on disk at load. */
    private val generations = ConcurrentHashMap<UUID, Long>()
    /** Owner thread: the sequence of the last write result applied. */
    private val appliedWrites = HashMap<UUID, Long>()
    private val saveStates = MutableStateFlow<Map<UUID, SaveState>>(emptyMap())

    fun setError(message: String?) { _error.value = message }
    fun setRecoveryMessage(id: UUID, message: String?) { _recoveryMessages.value = _recoveryMessages.value.with(id, message) }

    init {
        try {
            Files.createDirectories(disk.root.toPath())
            ProjectArchive.discardInterruptedRestores(disk)
            val projects = mutableListOf<Project>()
            for (folder in disk.root.listFiles()?.toList() ?: emptyList()) {
                val manifest = File(folder, "project.json")
                val id = uuidOrNull(folder.name) ?: continue
                if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                // TODO(port): TakeReceipt.reclaimAbandonedReserves and
                // RecordingSalvage.clearUnpublishedCopies (recorder, M3).
                // TODO(port): `disk.originals` (RecordingSalvage.swift); approximated by any movie in the folder.
                if (!manifest.exists() && File(folder, ".yourec-importing").exists() && !hasMovies(folder)) continue
                try {
                    val opened = disk.openRecovering(manifest, summary = true)
                    val project = opened.project
                    opened.notice?.let { setRecoveryMessage(id, it) }
                    val pending: Project? = try {
                        disk.pendingDraft(id)
                    } catch (failure: Exception) {
                        setRecoveryMessage(id, "The saved project opened. Its pending edit journal could not be read.")
                        null
                    }
                    if (pending != null) {
                        projects += pending
                        _unsavedDrafts.value = _unsavedDrafts.value + (id to pending)
                        _journaledDrafts.value = _journaledDrafts.value + id
                        setRecoveryMessage(id, "Recovered your pending edits. Retry save to commit them.")
                    } else projects += project
                    if (opened.notice != null || pending != null) continue
                    VideoBatchImport.recover(project, folder)
                    // These are exclusively generated working files; originals,
                    // manifests, and completed exports are never cleaned here.
                    for (name in listOf("Processing", "Previews")) {
                        val cache = File(folder, name)
                        if (cache.exists()) runCatching { cache.deleteRecursively() }
                    }
                } catch (failure: Exception) {
                    if (projects.none { it.id == id }) _unavailableProjects.value = _unavailableProjects.value + (id to message(failure))
                    else setRecoveryMessage(id, message(failure))
                }
            }
            _projects.value = projects
            disk.finishPendingDeletions()
            _deletedProjects.value = (disk.deletedRoot.listFiles()?.toList() ?: emptyList()).mapNotNull { folder ->
                val id = uuidOrNull(folder.name) ?: return@mapNotNull null
                val project = runCatching { disk.read(File(folder, "project.json"), summary = true) }.getOrNull() ?: return@mapNotNull null
                if (project.id == id) project else null
            }
            refreshDeletionRecords()
            sort()
            val saved = preferences.selectedProject(selectionKey)?.let(::uuidOrNull)
            if (saved != null && _projects.value.any { it.id == saved }) {
                if (!select(saved)) _error.value = (_error.value ?: "") + " Choose a project in Projects or record a new project."
            } else _projects.value.firstOrNull()?.id?.let { select(it) }
        } catch (failure: Exception) {
            _error.value = message(failure)
        }
    }

    private fun hasMovies(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.extension.lowercase() in listOf("mov", "mp4", "m4v") } == true

    fun create(now: ZonedDateTime = ZonedDateTime.now()): Project? {
        val title = DateTimeFormatter.ofPattern("MMM d").format(now) + " · " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(now)
        val project = Project(title)
        return try {
            disk.save(project)
            _projects.value = listOf(project) + _projects.value
            select(project.id)
            project
        } catch (failure: Exception) {
            _error.value = message(failure); null
        }
    }

    fun retryUnavailableProjects() {
        for (id in _unavailableProjects.value.keys.toList()) {
            try {
                val opened = disk.openRecovering(File(disk.folder(id), "project.json"))
                if (_projects.value.none { it.id == id }) _projects.value = _projects.value + opened.project
                _unavailableProjects.value = _unavailableProjects.value - id
                setRecoveryMessage(id, opened.notice ?: "The project is available again.")
            } catch (failure: Exception) {
                _unavailableProjects.value = _unavailableProjects.value + (id to message(failure))
            }
        }
        sort()
    }

    private fun hydrated(project: Project): Project {
        val deferred = project.deferredHistory ?: return project
        val opened = disk.openRecovering(deferred.manifest)
        opened.notice?.let { setRecoveryMessage(project.id, it) }
        return opened.project
    }

    fun openProject(id: UUID): Boolean {
        val index = _projects.value.indexOfFirst { it.id == id }
        if (index < 0) { _error.value = "This project is no longer available."; return false }
        val current = _projects.value[index]
        try {
            val opened = hydrated(_unsavedDrafts.value[id] ?: current)
            _projects.value = _projects.value.updated(index) { opened }
        } catch (failure: Exception) {
            _error.value = "Could not open “${current.title}”. " + message(failure)
            return false
        }
        return true
    }

    fun select(id: UUID): Boolean {
        if (!openProject(id)) return false
        _selectedID.value = id
        preferences.setSelectedProject(selectionKey, id.uuidString)
        return true
    }

    /**
     * [openProject] with the history decoded on [ioDispatcher]: a library
     * summary holds only the current edit, and a long history takes a while to
     * decode. Callers show that the project is opening meanwhile.
     */
    suspend fun prepareProject(id: UUID): Boolean {
        val current = _projects.value.firstOrNull { it.id == id }
        if (current == null) { _error.value = "This project is no longer available."; return false }
        try {
            latestHydrated(id)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            _error.value = "Could not open “${current.title}”. " + message(failure)
            return false
        }
        return true
    }

    /** [select] after [prepareProject], so selecting never decodes a history on the calling thread. */
    suspend fun selectPrepared(id: UUID): Boolean = prepareProject(id) && select(id)

    /**
     * The newest state of [id] with its full history. A summary is decoded on
     * [ioDispatcher] and published only over the same summary (anything else is
     * already newer). Returns on the calling thread with nothing suspended after
     * the final read, so a change built from the result cannot lose a concurrent edit.
     */
    private suspend fun latestHydrated(id: UUID): Project {
        while (true) {
            val current = _unsavedDrafts.value[id] ?: _projects.value.firstOrNull { it.id == id } ?: throw ProjectError.invalidDocument
            val deferred = current.deferredHistory ?: return current
            val opened = withContext(ioDispatcher) { disk.openRecovering(deferred.manifest) }
            val index = _projects.value.indexOfFirst { it.id == id }
            if (index >= 0 && _projects.value[index] === current) {
                opened.notice?.let { setRecoveryMessage(id, it) }
                _projects.value = _projects.value.updated(index) { opened.project }
            }
        }
    }

    /**
     * One transaction. [change] edits a private copy, which is published at once
     * when it is valid and then saved (see the file comment). Returns false when
     * the change is rejected, or when its save already failed (inline saves); a
     * failed save leaves the unsaved draft, journal and error either way.
     */
    fun update(id: UUID, change: (Project) -> Unit): Boolean {
        val index = _projects.value.indexOfFirst { it.id == id }
        if (index < 0) return false
        try {
            val candidate = hydrated(_unsavedDrafts.value[id] ?: _projects.value[index]).copy()
            change(candidate)
            candidate.validate()
            val generation = accept(candidate)
            return (saveStates.value[id]?.failed ?: 0) < generation
        } catch (failure: Exception) {
            _error.value = message(failure); return false
        }
    }

    /**
     * Publishes [candidate] as the newest state of its project (and as its
     * draft while one exists) and saves it: inline, or queued on the writer.
     * Returns its generation. Never suspends.
     */
    private fun accept(candidate: Project): Long {
        val id = candidate.id
        val generation = (generations[id] ?: 0) + 1
        generations[id] = generation
        if (_unsavedDrafts.value.containsKey(id)) _unsavedDrafts.value = _unsavedDrafts.value + (id to candidate)
        val index = _projects.value.indexOfFirst { it.id == id }
        if (index >= 0) _projects.value = _projects.value.updated(index) { candidate }
        sort()
        val scope = writerScope
        if (scope == null) {
            val report = writeLock.withLock {
                written[id] = generation
                write(candidate, generation, journal = true)
            }
            applied(report, retainDraft = true)
        } else scope.launch { saveInBackground(candidate, generation) }
        return generation
    }

    /** Runs on the writer. */
    private fun saveInBackground(project: Project, generation: Long) {
        val id = project.id
        writeLock.withLock {
            // A newer state is queued (its save includes this one), or was already written by an owner-thread path.
            if (generation < (generations[id] ?: 0) || generation <= (written[id] ?: 0)) return
            written[id] = generation
            val report = write(project, generation, journal = true)
            // Posted while holding the lock, so results reach the owner in write order.
            ownerScope!!.launch { applied(report, retainDraft = true) }
        }
    }

    /**
     * One write, with [writeLock] held. A failed save is journaled at once
     * (before any later write of the project) when [journal] is set and no newer
     * state is queued: that state's save follows and journals itself if it fails.
     */
    private fun write(project: Project, generation: Long, journal: Boolean): SaveReport {
        check(writeLock.isHeldByCurrentThread)
        writeSequence += 1
        return try {
            disk.save(project)
            SaveReport(project, generation, writeSequence, null, false)
        } catch (failure: Exception) {
            val superseded = generation < (generations[project.id] ?: 0)
            val journaled = journal && !superseded && try { disk.journalDraft(project); true } catch (_: Exception) { false }
            SaveReport(project, generation, writeSequence, failure, journaled)
        }
    }

    /**
     * Applies a finished write on the owner thread. A result older than one
     * already applied is dropped (an owner-thread Retry save wrote after it). A
     * failure matters only for the newest state: an older state's failure is
     * settled by the newer state's save, which follows on the same writer.
     */
    private fun applied(report: SaveReport, retainDraft: Boolean) {
        val id = report.project.id
        if (report.sequence <= (appliedWrites[id] ?: 0)) return
        appliedWrites[id] = report.sequence
        val newest = report.generation == (generations[id] ?: 0)
        val state = saveStates.value[id] ?: SaveState()
        val failure = report.failure
        if (failure == null) {
            // TODO(port): EffectApplicationReceipt bookkeeping (video effects).
            if (newest) _unsavedDrafts.value = _unsavedDrafts.value - id
            saveStates.value = saveStates.value + (id to state.copy(saved = maxOf(state.saved, report.generation)))
        } else if (newest) {
            if (retainDraft && _projects.value.any { it.id == id }) retainDraft(report.project, failure, report.journaled)
            saveStates.value = saveStates.value + (id to state.copy(failed = maxOf(state.failed, report.generation)))
        }
    }

    /** True once [generation] of [id], or a newer state (which includes it), is saved; false when its save failed. */
    private suspend fun awaitSave(id: UUID, generation: Long): Boolean {
        val state = combine(saveStates, _projects) { states, projects -> states[id] to projects.any { it.id == id } }
            .first { (state, present) -> !present || (state?.saved ?: 0) >= generation || (state?.failed ?: 0) >= generation }.first
        return (state?.saved ?: 0) >= generation
    }

    /**
     * Suspends until the newest state of [id] is saved (true) or its save
     * failed (false, with the unsaved draft in place). Immediate with inline saves.
     */
    suspend fun awaitSaved(id: UUID): Boolean = awaitSave(id, generations[id] ?: 0) && _unsavedDrafts.value[id] == null

    /**
     * One saved transaction that leaves nothing behind when the save fails: no
     * unsaved draft and no `.pending-edit` journal. For callers that Swift
     * saves with `disk.save` directly (VideoBatchImport.commit), whose rollback
     * removes files the candidate references. Throws the failure.
     */
    fun saveDirectly(id: UUID, change: (Project) -> Unit): Project {
        val index = _projects.value.indexOfFirst { it.id == id }
        if (index < 0) throw IllegalStateException("This project is no longer available.")
        val candidate = hydrated(_unsavedDrafts.value[id] ?: _projects.value[index]).copy()
        change(candidate)
        candidate.validate()
        // Written here, after any write in progress; queued older states are then skipped.
        val generation = (generations[id] ?: 0) + 1
        val report = writeLock.withLock {
            write(candidate, generation, journal = false).also { if (it.failure == null) written[id] = generation }
        }
        report.failure?.let { throw it }
        generations[id] = generation
        _unsavedDrafts.value = _unsavedDrafts.value - id
        _projects.value = _projects.value.updated(index) { candidate }
        sort()
        applied(report, retainDraft = false)
        return candidate
    }

    /** Saves every unsaved draft on the calling thread, after any write in progress (a failure path, never per edit). */
    fun retrySaves() {
        val failures = mutableListOf<String>()
        for ((id, draft) in _unsavedDrafts.value) {
            if (_projects.value.none { it.id == id }) { _unsavedDrafts.value = _unsavedDrafts.value - id; continue }
            // A draft is always the newest state of its project.
            val generation = generations[id] ?: 0
            val report = writeLock.withLock {
                write(draft, generation, journal = false).also { if (it.failure == null) written[id] = maxOf(written[id] ?: 0, generation) }
            }
            applied(report, retainDraft = false)
            val failure = report.failure
            if (failure == null) {
                val index = _projects.value.indexOfFirst { it.id == id }
                if (index >= 0) _projects.value = _projects.value.updated(index) { draft }
                _unsavedDrafts.value = _unsavedDrafts.value - id
            } else failures += draft.title + ": " + message(failure)
        }
        _journaledDrafts.value = _journaledDrafts.value.intersect(_unsavedDrafts.value.keys)
        _error.value = if (failures.isEmpty()) null else failures.joinToString("\n")
        sort()
    }

    /** The state half of a failed save; the writer already tried to journal [draft]. */
    private fun retainDraft(draft: Project, failure: Exception, journaled: Boolean) {
        _unsavedDrafts.value = _unsavedDrafts.value + (draft.id to draft)
        val index = _projects.value.indexOfFirst { it.id == draft.id }
        if (index >= 0) _projects.value = _projects.value.updated(index) { draft }
        if (journaled) {
            _journaledDrafts.value = _journaledDrafts.value + draft.id
            _error.value = "Changes not saved. A recovery copy of the edits was written. " + message(failure)
        } else {
            _journaledDrafts.value = _journaledDrafts.value - draft.id
            _error.value = "Changes are only in memory. Keep YouRoll open and retry the save. " + message(failure)
        }
    }

    fun rename(project: Project, title: String) {
        val value = title.trim().let { it.take(it.offsetByCodePoints(0, minOf(120, it.codePointCount(0, it.length)))) }
        if (value.isEmpty()) return
        update(project.id) { it.title = value; it.updatedAt = SwiftDate.now() }
    }

    fun moveToDeleted(project: Project) {
        if (_importing.value) return
        try {
            val id = project.id
            val generation = generations[id] ?: 0
            // The folder moves only after its newest state is on disk: a failed save
            // (the draft) or one still queued on the writer, which then skips it.
            val flushed = writeLock.withLock {
                val pending = _unsavedDrafts.value[id] ?: _projects.value.firstOrNull { it.id == id }?.takeIf { (written[id] ?: 0) < generation }
                val report = pending?.let { write(it, generation, journal = false) }
                report?.failure?.let { failure ->
                    _error.value = "Save the pending edits before moving “${pending.title}” to Recently Deleted. " + message(failure)
                    return
                }
                disk.moveToDeleted(project)
                written[id] = maxOf(written[id] ?: 0, generation)
                report
            }
            flushed?.let { applied(it, retainDraft = false) }
            _unsavedDrafts.value = _unsavedDrafts.value - project.id
            _projects.value = _projects.value.filter { it.id != project.id }
            _deletedProjects.value = listOf(disk.read(File(File(disk.deletedRoot, project.id.uuidString), "project.json"), summary = true)) + _deletedProjects.value
            _deletionRecords.value = _deletionRecords.value + (project.id to disk.deletionRecord(project.id))
            if (_selectedID.value == project.id) {
                _selectedID.value = null
                _projects.value.firstOrNull()?.id?.let { select(it) }
            }
        } catch (failure: Exception) {
            _error.value = message(failure)
        }
    }

    fun restoreDeleted(project: Project) {
        try {
            disk.restoreDeleted(project)
            _deletionRecords.value = _deletionRecords.value - project.id
            _deletedProjects.value = _deletedProjects.value.filter { it.id != project.id }
            _projects.value = _projects.value + disk.read(File(disk.folder(project.id), "project.json"), summary = true)
            sort(); select(project.id)
        } catch (failure: Exception) {
            _error.value = message(failure)
        }
    }

    fun eraseDeleted(project: Project) {
        if (_importing.value) return
        try {
            disk.eraseDeleted(project.id)
            _deletedProjects.value = _deletedProjects.value.filter { it.id != project.id }
            _deletionRecords.value = _deletionRecords.value - project.id
        } catch (failure: Exception) {
            _error.value = message(failure)
        }
    }

    private fun refreshDeletionRecords(now: SwiftDate = SwiftDate.now()) {
        val records = HashMap<UUID, ProjectDeletion>()
        for (project in _deletedProjects.value) {
            // Keep a project with an unreadable record out of bulk cleanup; manual Restore/Delete remain available.
            runCatching { disk.deletionRecord(project.id, now) }.getOrNull()?.let { records[project.id] = it }
        }
        _deletionRecords.value = records
        _deletedProjects.value = _deletedProjects.value.sortedWith { first, second ->
            val a = records[first.id]
            val b = records[second.id]
            val aKnown = a?.dateIsKnown ?: false
            val bKnown = b?.dateIsKnown ?: false
            if (aKnown != bKnown) return@sortedWith if (aKnown) -1 else 1
            val left = a?.date ?: SwiftDate.distantPast
            val right = b?.date ?: SwiftDate.distantPast
            if (left == right) first.id.uuidString.compareTo(second.id.uuidString) else right.compareTo(left)
        }
    }

    fun expiredDeletions(now: SwiftDate = SwiftDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<ProjectDeletion> {
        refreshDeletionRecords(now)
        return _deletedProjects.value.mapNotNull { _deletionRecords.value[it.id] }.filter { it.isOlderThan30Days(now, zone) }
    }

    fun eraseExpiredDeletions(confirmed: List<ProjectDeletion>, now: SwiftDate = SwiftDate.now(), zone: ZoneId = ZoneId.systemDefault()) {
        if (_importing.value) return
        val failures = mutableListOf<String>()
        for (record in confirmed) {
            if (_deletedProjects.value.none { it.id == record.id }) continue
            try {
                if (disk.eraseExpiredDeletion(record, now, zone)) {
                    _deletedProjects.value = _deletedProjects.value.filter { it.id != record.id }
                    _deletionRecords.value = _deletionRecords.value - record.id
                }
            } catch (failure: Exception) {
                failures += message(failure)
            }
        }
        if (failures.isNotEmpty()) _error.value = "Some deleted projects could not be removed. They remain in Recently deleted. " + failures[0]
    }

    fun missingSources(project: Project): List<SourceMedia> = project.sources.filter { !mediaFile(project, it).exists() }

    fun commit(id: UUID, edit: EditState): Boolean {
        val current = _unsavedDrafts.value[id] ?: _projects.value.firstOrNull { it.id == id } ?: return false
        if (!current.canCommit(edit)) {
            _error.value = "A new recording was inserted while this tool was open. Reopen the tool to edit the updated timeline."
            return false
        }
        return update(id) { it.commit(edit) }
    }

    fun folder(project: Project): File = disk.folder(project.id)
    /** Swift `mediaURL(_:_:)`. */
    fun mediaFile(project: Project, source: SourceMedia): File = File(folder(project), source.filename)
    private fun sort() { _projects.value = _projects.value.sortedByDescending { it.updatedAt } }

    /**
     * Index a finished recording segment (or a recovered take) into a project.
     * Requires [mediaProbe]. TODO(port): read the captured `effects.json`
     * recipe (video effects) and memoize fingerprints like SourceMediaCache.
     */
    suspend fun addRecording(file: File, id: UUID, recovering: Boolean = false, preservePlacement: Boolean = true,
                             original: File? = null, audioOnly: Boolean = false): Boolean {
        val capture = original ?: file
        try {
            val probe = mediaProbe ?: error("ProjectStore needs a MediaProbe to index recordings")
            val info = probe.probe(file)
            val duration = info.duration
            if (!(duration.isFinite() && duration > 0 && (audioOnly || info.hasVideo))) throw ProjectError.invalidMedia
            val index = _projects.value.indexOfFirst { it.id == id }
            if (index < 0) throw ProjectError.invalidDocument
            if (_projects.value[index].sources.any { it.filename == file.name }) {
                if (_unsavedDrafts.value[id] != null) retrySaves()
                // Already indexed: report whether it is saved, once a save still queued has finished.
                return awaitSaved(id)
            }
            var uncertain = !preservePlacement
            val receipt: TakeReceipt? = try {
                withContext(ioDispatcher) { TakeReceipt.read(capture, id) }
            } catch (failure: Exception) {
                if (!(failure is SerializationException || failure is IllegalArgumentException || failure is RecoveryError)) throw failure
                uncertain = true; null
            }
            val notices = mutableListOf<String>()
            val eventFile = File(capture.parentDirectory, capture.nameWithoutExtension + ".events.json")
            val events: List<CaptureEvent>? = withContext(ioDispatcher) {
                if (!eventFile.exists()) return@withContext null
                try {
                    CaptureEvent.recovered(SwiftJSON.decodeFromString(ListSerializer(CaptureEvent.serializer()), eventFile.readText()), duration)
                } catch (failure: Exception) {
                    if (!RecoveryFile.repairable(failure)) throw failure
                    notices += "Some recording marks could not be read. The original marks file was kept."
                    null
                }
            }
            val hash = withContext(ioDispatcher) {
                RecoveryFile.syncFile(file)
                fingerprint(file)
            }
            // Re-read after hashing: the user can edit while this was suspended.
            // Nothing suspends from this read until `accept` publishes the take.
            val candidate = latestHydrated(id).copy()
            if (candidate.sources.any { it.filename == file.name }) return _unsavedDrafts.value[id] == null
            val source = SourceMedia(id = receipt?.segmentID ?: newUUID(), filename = file.name, duration = duration,
                recordedAt = receipt?.started ?: SwiftDate.now(), events = events, sha256 = hash, audioOnly = if (audioOnly) true else null)
            val placed = candidate.indexTake(source, receipt, uncertain)
            if (!placed) notices += "The take is in Original recordings. Choose where to insert it."
            candidate.validate()
            // A failed save leaves the draft and journal (`applied`); the recorder then keeps the take paused.
            if (!awaitSave(id, accept(candidate))) return false
            if (receipt != null) withContext(ioDispatcher) { runCatching { receipt.markIndexed(capture) } }
            if (recovering) notices.add(0, "Recovered " + "%.1f".format(duration) + " s. The length of any missing ending is unknown.")
            if (notices.isNotEmpty()) setRecoveryMessage(id, notices.joinToString(" "))
            return true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            _error.value = message(failure); return false
        }
    }

    /**
     * Copy a video into a new project. TODO(port): Android imports arrive as a
     * content Uri from the Photo Picker / document picker (M2); this takes a
     * readable file. Requires [mediaProbe].
     */
    suspend fun importVideo(file: File, ext: String = file.extension) {
        _importing.value = true; _operationProgress.value = 0.0; _operationLabel.value = "Copying original video…"
        val project = Project(file.nameWithoutExtension.take(120))
        val directory = folder(project)
        val destination = File(directory, newUUID().uuidString + "." + ext)
        try {
            val probe = mediaProbe ?: error("ProjectStore needs a MediaProbe to import video")
            withContext(ioDispatcher) {
                Files.createDirectories(directory.toPath())
                File(directory, ".yourec-importing").writeBytes(ByteArray(0))
                Files.copy(file.toPath(), destination.toPath())
            }
            _operationProgress.value = 0.9
            val info = probe.probe(destination)
            if (!(info.duration.isFinite() && info.duration > 0 && info.hasVideo)) throw ProjectError.invalidMedia
            val hash = withContext(ioDispatcher) { fingerprint(destination) }
            project.append(SourceMedia(filename = destination.name, duration = info.duration, sha256 = hash))
            withContext(ioDispatcher) { disk.save(project) }
            runCatching { File(directory, ".yourec-importing").delete() }
            _projects.value = listOf(project) + _projects.value
            select(project.id); sort()
        } catch (failure: Exception) {
            // Only this import's new directory is removed; its original is untouched.
            directory.deleteRecursively()
            if (failure is CancellationException) throw failure
            _error.value = "Import failed: " + message(failure)
        } finally {
            _importing.value = false
        }
    }

    /**
     * Swift `insertAudio(_:into:at:)`: stage a private, validated copy of the
     * picked file ([open] reads the original, which is never modified), then
     * add it as an independent audio item at [position] in one saved edit.
     * The file joins the project only when that edit is saved.
     */
    suspend fun insertAudio(originalName: String, id: UUID, position: Double, open: () -> java.io.InputStream): Boolean {
        if (_importing.value) return false
        val stored = _projects.value.firstOrNull { it.id == id } ?: return false
        _importing.value = true; _operationLabel.value = "Adding audio…"; _operationProgress.value = 0.0
        try {
            val probe = mediaProbe ?: error("ProjectStore needs a MediaProbe to import audio")
            val imported = ImportedAudioFile.stage(originalName, File(folder(stored), "Processing"), probe, ioDispatcher, open) {
                _operationProgress.value = it * 0.9
            }
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val destination = File(folder(stored), imported.source.filename)
                withContext(ioDispatcher) { Files.move(imported.file.toPath(), destination.toPath()) }
                try {
                    saveDirectly(id) { candidate ->
                        candidate.sources = candidate.sources + imported.source
                        val edit = candidate.edit
                        edit.timeline.insertAudio(imported.source, EditTime.of(minOf(edit.duration, maxOf(0.0, position))))
                        candidate.commit(edit)
                    }
                } catch (failure: Exception) {
                    withContext(ioDispatcher + kotlinx.coroutines.NonCancellable) { destination.delete() }
                    throw failure
                }
            } finally {
                withContext(ioDispatcher + kotlinx.coroutines.NonCancellable) { imported.file.delete() }
            }
            _operationProgress.value = 1.0
            return true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            _error.value = "Audio import failed: " + message(failure); return false
        } finally {
            _importing.value = false
        }
    }

    suspend fun duplicate(project: Project): Project? {
        if (_importing.value) return null
        val current = _projects.value.firstOrNull { it.id == project.id } ?: return null
        _importing.value = true; _operationProgress.value = 0.0; _operationLabel.value = "Duplicating project…"
        try {
            val original = latestHydrated(current.id)
            val title = copyTitle(original.title)
            val copy = withContext(ioDispatcher) { ProjectArchive.duplicate(original, disk, title) { _operationProgress.value = it } }
            _projects.value = listOf(copy) + _projects.value
            select(copy.id); sort()
            return copy
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            _error.value = "Couldn’t duplicate the project. " + message(failure); return null
        } finally {
            _importing.value = false
        }
    }

    internal fun copyTitle(title: String): String {
        val base = title.replace(Regex(" copy(?: [0-9]+)?$", RegexOption.IGNORE_CASE), "")
        val existing = _projects.value.map { it.title.lowercase() }.toSet()
        var number = 1
        while (true) {
            val suffix = if (number == 1) " copy" else " copy $number"
            val candidate = base.take(120 - suffix.length) + suffix
            if (candidate.lowercase() !in existing) return candidate
            number += 1
        }
    }

    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering.asStateFlow()
    private val _recoveringIDs = MutableStateFlow<Set<UUID>>(emptySet())
    val recoveringIDs: StateFlow<Set<UUID>> = _recoveringIDs.asStateFlow()

    /**
     * Swift `recoverUnindexedRecordings()`, without RecordingSalvage: a movie
     * left in a project folder but missing from its sources (the app stopped
     * between finalising a segment and indexing it) is indexed again when it is
     * playable; an unplayable one is moved to Recovery/ with its sidecars and
     * reported. TODO(port): repairing unfinished MP4s into `recovered-*.mov`
     * copies, disk reserves and the automatic repair budget.
     */
    suspend fun recoverUnindexedRecordings() {
        if (_recovering.value) return
        val probe = mediaProbe ?: return
        _recovering.value = true
        try {
            // Snapshot candidates before yielding: a new capture must never become a candidate.
            val candidates = _projects.value.map { it to (runCatching { disk.unindexedRecordings(it) }.getOrNull() ?: emptyList()) }
            _recoveringIDs.value = candidates.filter { it.second.isNotEmpty() }.map { it.first.id }.toSet()
            for ((project, files) in candidates) {
                val folder = disk.folder(project.id)
                var unresolved = false
                for (file in files) {
                    val marker = File(folder, ".recovery-attempts/" + RecoveryFile.hash(file.name.toByteArray(Charsets.UTF_8)))
                    try {
                        // Swift: an unreadable marker counts as one attempt; a read failure leaves the take unresolved.
                        val attempts = try {
                            RecoveryFile.readIfPresent(marker)?.let { it.toString(Charsets.UTF_8).toIntOrNull() ?: 1 } ?: 0
                        } catch (failure: CancellationException) { throw failure } catch (_: Exception) { unresolved = true; continue }
                        if (attempts >= 2) {
                            setRecoveryMessage(project.id, "Automatic repair was interrupted. You can review and save the original recordings."); continue
                        }
                        RecoveryFile.write((attempts + 1).toString().toByteArray(Charsets.UTF_8), marker)
                        try {
                            val info = try { probe.probe(file) } catch (failure: CancellationException) { throw failure } catch (_: Exception) { null }
                            if (info == null || !(info.duration.isFinite() && info.duration > 0 && (info.hasVideo || info.hasAudio))) {
                                withContext(ioDispatcher) { disk.quarantineRecording(file, _projects.value.firstOrNull { it.id == project.id } ?: project) }
                                setRecoveryMessage(project.id, "An unfinished recording could not be opened. Its original files are available to save.")
                                continue
                            }
                            val saved = addRecording(file, project.id, recovering = true, preservePlacement = project.recoveryOrigin == null,
                                audioOnly = !info.hasVideo)
                            if (!saved) unresolved = true
                        } finally {
                            runCatching { marker.delete() }
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        unresolved = true
                        setRecoveryMessage(project.id, "Recovery is pending. " + message(failure))
                    }
                }
                // Swift: a "Record here" position never outlives the session that set it. Android also
                // writes no take receipts, so a stale position would misplace every later normal take.
                if (!unresolved && project.recordingInsertion != null &&
                    _projects.value.firstOrNull { it.id == project.id }?.recordingInsertion != null) {
                    update(project.id) { it.recordingInsertion = null; it.recordingInsertionStep = null }
                }
                _recoveringIDs.value = _recoveringIDs.value - project.id
            }
        } finally {
            _recovering.value = false
            _recoveringIDs.value = emptySet()
        }
    }

    /** Swift `reinsertOriginal(_:in:start:end:)`, keyed by the source instead of an OriginalRecording. */
    fun reinsertOriginal(sourceID: UUID, id: UUID, start: Double, end: Double) {
        val current = _projects.value.firstOrNull { it.id == id } ?: throw ProjectError.invalidMedia
        val source = current.sources.firstOrNull { it.id == sourceID } ?: throw ProjectError.invalidMedia
        if (!(start >= 0 && end > start && end <= source.duration + 0.001)) throw ProjectError.invalidMedia
        val updated = update(id) { project ->
            val clip = TimelineClip(sourceID = source.id, start = start, end = minOf(end, source.duration), videoEffects = source.videoEffects)
            project.commit(TimelineEditing.inserting(listOf(clip), project.edit, project.edit.duration))
        }
        if (!updated) throw ProjectError.invalidDocument
    }

    private fun message(failure: Throwable): String = failure.message ?: failure.toString()

    companion object {
        /** SHA-256 of the whole file (SourceMediaCache.fingerprint without its memo cache). */
        fun fingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun <K, V> Map<K, V>.with(key: K, value: V?): Map<K, V> = if (value == null) this - key else this + (key to value)
