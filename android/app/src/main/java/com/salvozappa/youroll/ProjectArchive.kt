// Port of YouRec/ProjectArchive.swift (partial: local project duplication and
// the audio import staging, `ImportedAudioFile`)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// TODO(port): ZIP export/restore with the OpenTimelineIO edit, archive
// integrity, relinking and copy-on-write clones (`clonefile`).
package com.salvozappa.youroll

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import kotlin.coroutines.coroutineContext

/**
 * A private, validated copy. The caller moves it into the project only when
 * the new edit can be committed; the user's original (a document picked
 * through the Storage Access Framework) is only ever read.
 */
data class ImportedAudioFile(val file: File, val source: SourceMedia) {
    companion object {
        /**
         * Copies [open] into [directory] under a new name, checks that it has
         * decodable audio of a sane length, and fingerprints it. A failure removes
         * only the staged copy.
         */
        suspend fun stage(originalName: String, directory: File, probe: MediaProbe, io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
                          open: () -> java.io.InputStream, progress: (Double) -> Unit = {}): ImportedAudioFile {
            val suffix = originalName.substringAfterLast('.', "").lowercase()
            val ext = if (suffix.isNotEmpty() && suffix.length <= 10 && suffix.all { it in 'a'..'z' || it in '0'..'9' }) suffix else "audio"
            val staged = File(directory, newUUID().uuidString + "." + ext)
            try {
                kotlinx.coroutines.withContext(io) {
                    Files.createDirectories(directory.toPath())
                    open().use { input -> staged.outputStream().use { output -> input.copyTo(output, 1 shl 20) } }
                }
                progress(0.5)
                val info = try { probe.probe(staged) } catch (failure: kotlinx.coroutines.CancellationException) { throw failure } catch (_: Exception) { throw AudioImportError.unreadable }
                if (!(info.hasAudio && info.duration.isFinite() && info.duration > 0 && info.duration <= 604800)) throw AudioImportError.unreadable
                if (!probe.decodesAudio(staged)) throw AudioImportError.unreadable
                val hash = kotlinx.coroutines.withContext(io) { ProjectStore.fingerprint(staged) }
                coroutineContext.ensureActive()
                progress(1.0)
                return ImportedAudioFile(staged, SourceMedia(filename = staged.name, duration = info.duration, sha256 = hash,
                    audioOnly = true, originalName = originalName.take(255)))
            } catch (failure: Throwable) {
                staged.delete()
                throw failure
            }
        }
    }
}

sealed class AudioImportError(message: String) : Exception(message) {
    data object unreadable : AudioImportError("This file has no playable audio. Choose an unprotected audio file such as WAV, AIFF, M4A or MP3.")
}

object ProjectArchive {
    private val restoreSession = newUUID().uuidString

    /** Same staging folder name as the iOS app. */
    private fun restoreWorkspace(disk: ProjectDisk) = File(disk.root, ".yourec-archive-staging")

    fun discardInterruptedRestores(disk: ProjectDisk) {
        val work = restoreWorkspace(disk)
        if (!Files.isDirectory(work.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        for (session in work.listFiles()?.toList() ?: emptyList()) {
            if (session.name != restoreSession && uuidOrNull(session.name) != null) session.deleteRecursively()
        }
    }

    private fun isRecoveryEntry(path: String): Boolean {
        val parts = path.split("/")
        if (!(parts.size == 3 && parts[0] == "Recovery" &&
                (uuidOrNull(parts[1]) != null || (parts[1].length == 64 && parts[1].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })))) return false
        val name = parts[2]
        if (!(name.isNotEmpty() && name.toByteArray().size <= 255 && !name.startsWith(".") && !name.contains("\\") && !name.contains("\u0000"))) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("mov", "mp4", "m4v", "m4a", "caf", "wav", "aac", "flac", "mp3", "aif", "aiff") ||
            name.endsWith(".events.json") || name.endsWith(".effects.json")
    }

    private fun recoveryEntries(projectFolder: File): List<String> {
        val root = File(projectFolder, "Recovery")
        if (!root.exists()) return emptyList()
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ProjectError.invalidDocument
        val paths = mutableListOf<String>()
        for (group in root.listFiles()?.toList() ?: emptyList()) {
            if (!Files.isDirectory(group.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            for (file in group.listFiles()?.toList() ?: emptyList()) {
                val path = "Recovery/" + group.name + "/" + file.name
                if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && isRecoveryEntry(path)) paths += path
            }
        }
        return paths.sorted()
    }

    private fun preservedOriginals(project: Project, folder: File): Map<String, File> {
        val preserved = LinkedHashMap<String, File>()
        for (entry in recoveryEntries(folder)) preserved[entry] = File(folder, entry)
        // A backup can be requested before startup has finished indexing a take.
        // Restore these files under Originals, without guessing their timeline position.
        if (uuidOrNull(folder.name) == project.id) {
            val disk = ProjectDisk(folder.parentDirectory)
            for (movie in disk.unindexedRecordings(project)) {
                val prefix = "Recovery/" + RecoveryFile.hash(movie.name.toByteArray(Charsets.UTF_8)) + "/"
                preserved[prefix + movie.name] = movie
                for (suffix in listOf("events.json", "effects.json")) {
                    val sidecar = File(movie.parentFile, movie.nameWithoutExtension + "." + suffix)
                    if (sidecar.exists()) preserved[prefix + sidecar.name] = sidecar
                }
            }
        }
        return preserved
    }

    /// Copy editable media locally. Each project owns its files and can edit,
    /// repair, or delete them. TODO(port): share blocks like `clonefile` does on APFS.
    suspend fun duplicate(original: Project, disk: ProjectDisk, title: String, progress: (Double) -> Unit): Project {
        coroutineContext.ensureActive()
        if (original.deferredHistory != null) throw ProjectError.invalidDocument
        original.validate()
        val copy = original.copy()
        copy.id = newUUID(); copy.title = title
        copy.createdAt = SwiftDate.now(); copy.updatedAt = copy.createdAt
        copy.recordingInsertion = null; copy.recordingInsertionStep = null; copy.recoveryOrigin = null

        val staged = stagingDisk(disk)
        val work = staged.folder(copy.id)
        val sourceFolder = disk.folder(original.id)
        Files.createDirectories(work.toPath())
        try {
            val preserved = preservedOriginals(original, sourceFolder)
            val names = original.mediaFiles + preserved.keys.sorted()
            val feedback = MediaProgress(progress)
            for ((index, name) in names.withIndex()) {
                coroutineContext.ensureActive()
                val source = preserved[name] ?: File(sourceFolder, name)
                val target = File(work, name)
                if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS) && preserved[name] == null) {
                    // Keep an already missing asset relinkable in both timelines.
                    continue
                }
                if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ProjectError.invalidDocument
                Files.createDirectories(target.parentDirectory.toPath())
                try {
                    Files.copy(source.toPath(), target.toPath())
                } catch (error: NoSuchFileException) {
                    throw FileNotFoundException(error.message)
                }
                feedback.report((index + 1).toDouble() / names.size)
            }
            return publish(copy, staged, disk)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun stagingDisk(disk: ProjectDisk): ProjectDisk {
        discardInterruptedRestores(disk)
        return ProjectDisk(File(restoreWorkspace(disk), restoreSession))
    }

    private suspend fun publish(project: Project, staged: ProjectDisk, disk: ProjectDisk): Project {
        coroutineContext.ensureActive()
        staged.save(project)
        coroutineContext.ensureActive()
        val destination = disk.folder(project.id)
        Files.move(staged.folder(project.id).toPath(), destination.toPath())
        try {
            RecoveryFile.syncDirectory(disk.root)
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
        return project
    }
}
