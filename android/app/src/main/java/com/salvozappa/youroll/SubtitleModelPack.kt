// Port of YouRec/SubtitleModelPack.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Only weights/data are downloaded. Runtime code ships with the app.
//
// iOS downloads a 4-bit MLX conversion of Qwen3-ASR-0.6B (713 MB) for MLX.
// Android runs antirez's qwen-asr C engine (cpp/qwen-asr), which reads the
// official BF16 safetensors of the same model: `Qwen/Qwen3-ASR-0.6B` at a
// pinned revision (Apache-2.0), 1.88 GB. As on iOS: the download starts only
// when the user asks, shows progress, can be cancelled and resumed (completed
// files are kept, a partial file continues with an HTTP Range request), every
// file's SHA-256 is verified before the pack is published atomically, and the
// pack lives in no-backup storage. Transcription is then fully offline.
package com.salvozappa.youroll

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object SubtitleModelPack {
    data class FileEntry(val name: String, val bytes: Long, val sha256: String)

    const val revision = "5eb144179a02acc5e5ba31e748d22b0cf3e303b0"
    const val repository = "Qwen/Qwen3-ASR-0.6B"
    val files: List<FileEntry> = listOf(
        FileEntry("vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        FileEntry("merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        FileEntry("model.safetensors", 1_876_091_704, "79d6cbd4c98c7bbffe9db2edac07f56cd6637d0d5944b27f6c2b8353840323ea"),
    )
    val byteCount: Long get() = files.sumOf { it.bytes }
    /** The size shown in the editor ("1.9 GB"; iOS shows "713 MB" for its 4-bit pack). */
    const val sizeLabel = "1.9 GB"

    fun root(context: Context) = File(context.noBackupFilesDir, "SubtitleModels")
    fun directory(context: Context) = File(root(context), "qwen3-asr-0.6b-bf16-v1")
    fun staging(context: Context) = File(root(context), "qwen3-asr-0.6b-bf16-v1.download")

    fun installed(context: Context): Boolean {
        val directory = directory(context)
        val ready = runCatching { File(directory, "ready").readText() }.getOrNull()
        return ready == revision && files.all { File(directory, it.name).length() == it.bytes }
    }

    suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Download the pack once (Swift `download(progress:)`). Cancelling the
     * coroutine stops it; completed and partial files are kept for the next try.
     */
    suspend fun download(context: Context, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (installed(context)) { progress(1.0); return@withContext }
        val root = root(context)
        val staging = staging(context)
        if (!staging.isDirectory && !staging.mkdirs()) throw SubtitleError("The speech model could not be stored.")
        val free = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
        val missing = files.sumOf { entry ->
            val target = File(staging, entry.name)
            if (target.length() == entry.bytes) 0L else entry.bytes - File(staging, entry.name + ".part").length().coerceAtMost(entry.bytes)
        }
        if (free <= missing + 150_000_000) throw SubtitleError("Free at least 2.1 GB to download the speech model.")
        var completed = 0L
        for (entry in files) {
            ensureActive()
            val target = File(staging, entry.name)
            if (target.length() == entry.bytes && hash(target) == entry.sha256) {
                completed += entry.bytes; progress(completed.toDouble() / byteCount); continue
            }
            target.delete()
            val partial = File(staging, entry.name + ".part")
            val base = completed
            fetch(URL("https://huggingface.co/$repository/resolve/$revision/${entry.name}"), partial, entry.bytes) { written ->
                progress(minOf(1.0, (base + minOf(entry.bytes, written)).toDouble() / byteCount))
            }
            if (hash(partial) != entry.sha256) {
                partial.delete()
                throw SubtitleError("The downloaded model did not pass verification. Please retry.")
            }
            if (!partial.renameTo(target)) throw SubtitleError("The speech model could not be stored.")
            completed += entry.bytes; progress(completed.toDouble() / byteCount)
        }
        ensureActive()
        File(staging, "ready").writeText(revision)
        val directory = directory(context)
        if (directory.exists()) directory.deleteRecursively()
        if (!staging.renameTo(directory)) throw SubtitleError("The speech model could not be stored.")
    }

    /** One file, continued from [partial]'s length with a Range request when the server allows it. */
    private suspend fun fetch(url: URL, partial: File, bytes: Long, written: (Long) -> Unit) {
        var offset = partial.length()
        if (offset > bytes) { partial.delete(); offset = 0 }
        if (offset == bytes) return
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            val append = status == HttpURLConnection.HTTP_PARTIAL
            if (!(status in 200..299)) throw SubtitleError("The model download was refused. Please retry.")
            if (!append) offset = 0
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = offset
                    var reported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (total - reported > 1 shl 20) { reported = total; written(total) }
                        if (total > bytes) throw SubtitleError("The downloaded model did not pass verification. Please retry.")
                    }
                    written(total)
                }
            }
        } catch (error: IOException) {
            throw SubtitleError("The model download stopped (${error.message ?: "network error"}). Tap Download to continue.")
        } finally {
            connection.disconnect()
        }
    }

    fun remove(context: Context) {
        for (file in listOf(directory(context), staging(context))) if (file.exists() && !file.deleteRecursively()) throw SubtitleError("The speech model could not be removed.")
    }
}

/** Swift `SubtitleError.message`. */
class SubtitleError(message: String) : Exception(message)
