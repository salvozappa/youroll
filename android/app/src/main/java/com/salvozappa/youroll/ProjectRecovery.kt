// Port of YouRec/ProjectRecovery.swift (partial: atomic files, manifest backups,
// recovering open, take receipts (read side) and the pending-edit journal)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// TODO(port): TakeReceipt.begin / disk reserves (written by the recorder, M3)
// and RecoverySpace (free-space checks) are not ported yet.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/// All files passed here are app-owned metadata, on the same volume. Publish only
/// after the bytes reach the filesystem; synchronize the containing directory too.
object RecoveryFile {
    private val checkedSnapshots = ConcurrentHashMap<String, String>()

    private fun stamp(file: File): String {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return "${attributes.fileKey()}-${attributes.size()}-${attributes.lastModifiedTime().toMillis()}"
    }

    fun ensureSnapshot(data: ByteArray, file: File) {
        val key = file.absolutePath
        try {
            val stamp = stamp(file)
            if (checkedSnapshots[key] == stamp) return
            val existing = file.readBytes()
            if (existing.contentEquals(data)) {
                checkedSnapshots[key] = stamp
                return
            }
            val evidence = File(file.parentDirectory.parentDirectory, ".revisions/damaged-history/" + hash(existing) + ".json")
            write(existing, evidence)
        } catch (error: Exception) {
            if (!missing(error)) throw error
        }
        write(data, file)
        checkedSnapshots[key] = stamp(file)
    }

    /** Write a temporary file, flush it to storage, then rename it into place. */
    fun write(data: ByteArray, destination: File) {
        val directory = destination.parentDirectory
        Files.createDirectories(directory.toPath())
        val temp = File(directory, ".saving-" + newUUID().uuidString)
        try {
            FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally {
            runCatching { Files.deleteIfExists(temp.toPath()) }
        }
    }

    fun syncDirectory(directory: File) {
        // Not every platform lets the JVM open a directory; like the Swift code
        // tolerating EINVAL/ENOTSUP, an unsupported directory sync is skipped.
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    fun syncFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    fun hash(data: ByteArray): String = sha256Hex(data)

    fun missing(error: Throwable): Boolean = error is FileNotFoundException || error is NoSuchFileException

    /** Swift: DecodingError, ProjectError, a missing file or a corrupt JSON payload. */
    fun repairable(error: Throwable): Boolean =
        error is SerializationException || error is IllegalArgumentException || error is ProjectError || missing(error)

    fun readIfPresent(file: File): ByteArray? = try {
        file.readBytes()
    } catch (error: Exception) {
        if (missing(error)) null else throw error
    }
}

class RecoveryError private constructor(val case: Case) : Exception(case.description) {
    enum class Case(val description: String) {
        newerProject("This project was saved by a newer version of the app. You can save its original recordings."),
        identity("The project index belongs to a different folder."),
        noMedia("No playable media was found. The original files are available to save."),
    }

    companion object {
        val newerProject get() = RecoveryError(Case.newerProject)
        val identity get() = RecoveryError(Case.identity)
        val noMedia get() = RecoveryError(Case.noMedia)
    }
}

data class OpenedProject(val project: Project, val notice: String? = null)

private val revisionName = Regex("^revision-(\\d{19})-([0-9A-Fa-f]{64})\\.json$")

private fun revisions(directory: File): List<File> {
    val entries = directory.listFiles() ?: return emptyList()
    return entries.filter { file ->
        val match = revisionName.matchEntire(file.name) ?: return@filter false
        val generation = match.groupValues[1].toLongOrNull()
        generation != null && generation >= 0 && generation < Long.MAX_VALUE
    }.sortedByDescending { it.name }
}

private fun header(data: ByteArray): JsonObject? = runCatching { parseJson(data) as? JsonObject }.getOrNull()

/// Called under the project write lock. Only rotate our own backup files,
/// keeping immutable history dependencies and pre-migration copies.
fun ProjectDisk.keepManifest(data: ByteArray, directory: File, nextSchema: Int) {
    val backups = File(directory, ".revisions")
    val hash = RecoveryFile.hash(data)
    val header = header(data)
    val version = (header?.get("schemaVersion") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (version != null) {
        if (version > Project.latestReadableSchema) throw RecoveryError.newerProject
        if (header.string("id")?.let(::uuidOrNull) != uuidOrNull(directory.name)) throw RecoveryError.identity
        if (version < nextSchema || header["recordingHistoryVersion"] == null || header["automaticCanvasVersion"] == null || header["snapshotFiles"] == null) {
            val migration = File(backups, "schema-$version-$hash.json")
            if (!migration.exists()) RecoveryFile.write(data, migration)
        }
    } else {
        val damaged = File(backups, "damaged-$hash.json")
        if (!damaged.exists()) RecoveryFile.write(data, damaged)
        return
    }
    val files = revisions(backups)
    if (files.firstOrNull()?.name?.endsWith("-$hash.json") == true) return
    val last = files.firstOrNull()?.name?.split("-")?.getOrNull(1)?.toLongOrNull() ?: 0
    val name = "revision-%019d-".format(last + 1) + hash + ".json"
    RecoveryFile.write(data, File(backups, name))
    for (stale in files.drop(4)) runCatching { Files.deleteIfExists(stale.toPath()) }
}

fun ProjectDisk.openRecovering(file: File, summary: Boolean = false): OpenedProject {
    val directory = file.parentDirectory
    val expected = uuidOrNull(directory.name) ?: throw RecoveryError.identity
    fun decode(data: ByteArray, summarized: Boolean): Project {
        val project = Project.readManifest(data, if (summarized) file else null) { name -> File(directory, "History/$name").readBytes() }
        if (project.id != expected) throw RecoveryError.identity
        return project
    }
    fun open(data: ByteArray, summarized: Boolean): OpenedProject {
        try {
            return OpenedProject(decode(data, summarized))
        } catch (error: Exception) {
            if (!RecoveryFile.repairable(error)) throw error
            val header = header(data)?.toMutableMap() ?: throw error
            val schema = (header["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0
            if (schema > Project.latestReadableSchema) throw RecoveryError.newerProject
            val names = (header["snapshotFiles"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw error }
            val cursor = (header["historyCursor"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (names == null || cursor == null || cursor !in names.indices || !names.all(Project::isSnapshotName)) throw error
            fun valid(index: Int): Boolean = try {
                val blob = File(directory, "History/" + names[index]).readBytes()
                RecoveryFile.hash(blob) + ".json" == names[index] && runCatching { EditSnapshot.decode(blob) }.isSuccess
            } catch (failure: Exception) {
                if (RecoveryFile.repairable(failure)) false else throw failure
            }
            if (!valid(cursor)) throw error
            var lo = cursor
            var hi = cursor
            while (lo > 0) { if (!valid(lo - 1)) break; lo -= 1 }
            while (hi + 1 < names.size) { if (!valid(hi + 1)) break; hi += 1 }
            for (retained in listOf(lo..hi, cursor..cursor)) {
                if (retained.count() >= names.size) continue
                header["snapshotFiles"] = JsonArray(names.slice(retained).map { JsonPrimitive(it) })
                header["historyCursor"] = JsonPrimitive(cursor - retained.first)
                header.remove("recordingInsertionStep")
                try {
                    val project = decode(JsonObject(header).encodedBytes(), false)
                    return OpenedProject(project, "Opened the saved timeline. Some Undo or Redo steps could not be read.")
                } catch (failure: Exception) {
                    if (!RecoveryFile.repairable(failure)) throw failure
                }
            }
            throw error
        }
    }
    var primaryDigest: String? = null
    try {
        val data = file.readBytes()
        try {
            val result = open(data, summary)
            if (result.notice != null) result.project.recoveryOrigin = RecoveryFile.hash(data)
            return result
        } catch (error: Exception) {
            primaryDigest = RecoveryFile.hash(data)
            throw error
        }
    } catch (error: Exception) {
        if (!RecoveryFile.repairable(error)) throw error
        val backups = File(directory, ".revisions")
        val pinned = (backups.listFiles()?.toList() ?: emptyList()).filter { it.name.startsWith("schema-") }
            .sortedWith { a, b -> naturalCompare(b.name, a.name) }
        for (backup in revisions(backups) + pinned) {
            try {
                val data = backup.readBytes()
                if (!backup.name.endsWith("-" + RecoveryFile.hash(data) + ".json")) continue
                val opened = open(data, false)
                opened.project.recoveryOrigin = primaryDigest ?: "missing"
                val time = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
                    .format(java.time.ZonedDateTime.ofInstant(opened.project.updatedAt.toInstant(), java.time.ZoneId.systemDefault()))
                return opened.copy(notice = listOfNotNull("Opened the project revision saved $time.", opened.notice).joinToString(" "))
            } catch (failure: Exception) {
                if (!RecoveryFile.repairable(failure)) throw failure
            }
        }
        throw error
    }
}

/** Finder-style comparison (`localizedStandardCompare`): digit runs compare numerically. */
private fun naturalCompare(a: String, b: String): Int {
    val parts = Regex("\\d+|\\D+")
    val x = parts.findAll(a).map { it.value }.toList()
    val y = parts.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val order = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q, ignoreCase = true)
        if (order != 0) return order
    }
    return x.size.compareTo(y.size)
}

/// One receipt per writer segment. The movie name and project folder are
/// authoritative even if the phone is killed before the editor indexes the take.
@Serializable
data class TakeReceipt(
    val version: Int,
    val projectID: UUID,
    val takeID: UUID,
    val segmentID: UUID,
    val sequence: Int,
    val filename: String,
    val started: SwiftDate,
    val insertion: Double? = null,
    val insertionSite: VideoInsertionSite? = null,
    val revision: String? = null,
    val captureCount: Int,
    val indexed: Boolean = false,
) {
    data class Placement(val time: Double, val site: VideoInsertionSite?, val revision: String, val captureCount: Int)

    fun markIndexed(movie: File) {
        if (indexed) return
        RecoveryFile.write(SwiftJSON.encodeToString(serializer(), copy(indexed = true)).toByteArray(), location(movie))
    }

    companion object {
        fun location(movie: File): File = File(File(movie.parentDirectory, ".capture-receipts"), movie.nameWithoutExtension + ".json")

        fun read(movie: File, project: UUID): TakeReceipt? {
            val bytes = RecoveryFile.readIfPresent(location(movie)) ?: return null
            val receipt = SwiftJSON.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
            if (!(receipt.version == 1 && receipt.projectID == project && receipt.filename == movie.name && receipt.sequence >= 0 &&
                    receipt.filename.endsWith("-" + receipt.segmentID.uuidString + ".mov"))) throw RecoveryError.identity
            return receipt
        }
    }
}

/** `Data` fields use JSONEncoder's default base64 string encoding. */
@Serializable
private data class PendingEdit(val previous: String? = null, val manifest: String)

private fun pendingDirectory(folder: File) = File(folder, ".pending-edit")

fun ProjectDisk.clearMatchingDraft(committed: ByteArray, folder: File) {
    val directory = pendingDirectory(folder)
    val bytes = runCatching { File(directory, "draft.json").readBytes() }.getOrNull() ?: return
    val draft = runCatching { SwiftJSON.decodeFromString(PendingEdit.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
    if (!fromBase64(draft.manifest).contentEquals(committed)) return
    directory.deleteRecursively()
}

/// This is an emergency journal for a failed normal save. Only write
/// snapshots that cannot be read from the main history directory.
fun ProjectDisk.journalDraft(project: Project) {
    val base = folder(project.id)
    val directory = pendingDirectory(base)
    val previous = RecoveryFile.readIfPresent(File(base, "project.json"))?.let(RecoveryFile::hash)
    val data = project.manifest { name, bytes ->
        val existing = runCatching { File(base, "History/$name").readBytes() }.getOrNull()
        if (existing == null || !existing.contentEquals(bytes)) RecoveryFile.ensureSnapshot(bytes, File(directory, "History/$name"))
    }
    val envelope = PendingEdit(previous, base64(data))
    RecoveryFile.write(SwiftJSON.encodeToString(PendingEdit.serializer(), envelope).toByteArray(), File(directory, "draft.json"))
}

fun ProjectDisk.pendingDraft(id: UUID): Project? {
    val base = folder(id)
    val directory = pendingDirectory(base)
    val data = RecoveryFile.readIfPresent(File(directory, "draft.json")) ?: return null
    val draft = SwiftJSON.decodeFromString(PendingEdit.serializer(), data.toString(Charsets.UTF_8))
    val manifest = fromBase64(draft.manifest)
    val current = RecoveryFile.readIfPresent(File(base, "project.json"))
    if (current != null && current.contentEquals(manifest)) return null
    if (current?.let(RecoveryFile::hash) != draft.previous) return null
    val project = Project.readManifest(manifest) { name ->
        RecoveryFile.readIfPresent(File(directory, "History/$name")) ?: File(base, "History/$name").readBytes()
    }
    if (project.id != id) throw RecoveryError.identity
    return project
}
