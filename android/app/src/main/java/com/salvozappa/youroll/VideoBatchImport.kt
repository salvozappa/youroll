// Port of YouRec/VideoBatchImport.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Photo Picker copies are staged on the project volume and published in tap
// order. Swift `commit` saves the project with `disk.save`; this port saves
// through `ProjectStore.saveDirectly`, which publishes to the store's flows but,
// like `disk.save`, keeps no unsaved draft when the save fails: the rollback
// below removes the files such a draft would reference.
package com.salvozappa.youroll

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files

class VideoBatchImport(folder: File) {
    val directory: File = File(folder, "Processing/AddVideos-" + newUUID().uuidString).also { Files.createDirectories(it.toPath()) }
    val sources = mutableListOf<SourceMedia>()

    suspend fun stage(privateCopy: File, originalName: String?, isPhoto: Boolean, probe: MediaProbe, context: Context) {
        currentCoroutineContext().ensureActive()
        val ext = privateCopy.extension.lowercase()
        val filename = "photos-import-" + newUUID().uuidString + "." + (if (!isPhoto && ext in listOf("mov", "mp4", "m4v")) ext else "mp4")
        val staged = File(directory, filename)
        if (isPhoto) PhotoClip.write(context, privateCopy, staged)
        else withContext(Dispatchers.IO) { Files.move(privateCopy.toPath(), staged.toPath()) }
        val info = probe.probe(staged)
        if (!(info.duration.isFinite() && info.duration > 0 && info.duration <= 604800 && info.hasVideo)) throw ProjectError.invalidMedia
        val hash = withContext(Dispatchers.IO) { ProjectStore.fingerprint(staged) }
        currentCoroutineContext().ensureActive()
        sources += SourceMedia(filename = filename, duration = info.duration, sha256 = hash, isStillPhoto = if (isPhoto) true else null,
            originalName = originalName?.take(255))
    }

    /**
     * Publishes the staged files into the project and inserts them at [position] (or [site]). Returns the saved project.
     * [remove] deletes a promoted file during rollback (replaceable so tests can make it fail).
     */
    fun commit(store: ProjectStore, projectID: UUID, position: Double, site: VideoInsertionSite? = null,
               remove: (File) -> Boolean = { it.delete() }): Project {
        if (sources.isEmpty()) throw ProjectError.invalidMedia
        val folder = store.disk.folder(projectID)
        val journal = File(directory, "promoting.json")
        val names = sources.map { it.filename }
        if (names.any { File(folder, it).exists() }) throw IllegalStateException("A file with the same name already exists.")
        // A process termination between the first move and the atomic manifest
        // save must never turn part of this group into recovered camera footage.
        journal.writeText(SwiftJSON.encodeToString(ListSerializer(String.serializer()), names))
        val moved = mutableListOf<File>()
        val saved: Project
        try {
            for (name in names) {
                val destination = File(folder, name)
                Files.move(File(directory, name).toPath(), destination.toPath())
                moved += destination
            }
            val staged = sources.toList()
            saved = store.saveDirectly(projectID) { project ->
                project.sources = project.sources + staged
                project.insertVideos(staged, position, site)
            }
        } catch (failure: Exception) {
            // Swift's `try fm.removeItem` stops at the first failed removal and keeps
            // the journal, so startup recovery (`recover`) still removes the promoted
            // files and `discard` leaves the batch folder in place.
            val rolledBack = moved.all { remove(it) || !it.exists() }
            if (rolledBack) journal.delete()
            throw failure
        }
        directory.deleteRecursively()
        return saved
    }

    fun discard() {
        // Leave the journal available to startup recovery if rollback itself failed.
        if (!File(directory, "promoting.json").exists()) directory.deleteRecursively()
    }

    companion object {
        private const val prefix = "photos-import-"

        fun ownsFilename(name: String): Boolean {
            val last = name.substringAfterLast('/')
            val ext = last.substringAfterLast('.', "")
            val stem = if (ext.isEmpty()) last else last.dropLast(ext.length + 1)
            return name == last && name.startsWith(prefix) && ext.lowercase() in listOf("mov", "mp4", "m4v") &&
                uuidOrNull(stem.drop(prefix.length)) != null
        }

        fun recover(project: Project, folder: File) {
            val work = File(folder, "Processing")
            if (!work.exists()) return
            val known = project.sources.map { it.filename.lowercase() }.toSet()
            for (batch in work.listFiles()?.toList() ?: emptyList()) {
                if (!batch.name.startsWith("AddVideos-")) continue
                val journal = File(batch, "promoting.json")
                if (!journal.exists()) continue
                val names = SwiftJSON.decodeFromString(ListSerializer(String.serializer()), journal.readText())
                if (!names.all(::ownsFilename)) throw ProjectError.invalidDocument
                for (name in names) {
                    if (name.lowercase() in known) continue
                    val file = File(folder, name)
                    if (file.exists()) file.delete()
                }
            }
        }
    }
}

fun Project.insertVideos(ordered: List<SourceMedia>, position: Double, site: VideoInsertionSite? = null) {
    if (ordered.isEmpty()) return
    val clips = ordered.map { TimelineClip(sourceID = it.id, start = 0.0, end = it.duration) }
    val photos = ordered.filter { it.isStillPhoto == true }.map { it.id }.toSet()
    commit(TimelineEditing.inserting(clips, edit, position, site, photos))
}
