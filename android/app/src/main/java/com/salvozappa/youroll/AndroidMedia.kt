// YouRoll-specific: Android implementations of the media and preference hooks
// that ProjectStore leaves to the app (Swift reads them with AVURLAsset and
// UserDefaults), plus copying a content Uri into managed storage.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** `MediaProbe` over `MediaExtractor` (tracks) and `MediaMetadataRetriever` (duration). */
class AndroidMediaProbe : MediaProbe {
    override suspend fun probe(file: File): MediaProbe.Info = withContext(Dispatchers.IO) {
        if (!file.isFile) throw ProjectError.invalidMedia
        val extractor = MediaExtractor()
        var hasVideo = false
        var hasAudio = false
        var trackDuration = 0L
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
                if (format.containsKey(MediaFormat.KEY_DURATION)) trackDuration = maxOf(trackDuration, format.getLong(MediaFormat.KEY_DURATION))
            }
        } catch (failure: Exception) {
            throw ProjectError.invalidMedia
        } finally {
            extractor.release()
        }
        val retriever = MediaMetadataRetriever()
        val milliseconds = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) { null } finally { retriever.release() }
        // Prefer the container duration; fall back to the longest track.
        val duration = milliseconds?.let { it / 1000.0 } ?: (trackDuration / 1_000_000.0)
        MediaProbe.Info(duration, hasVideo, hasAudio)
    }

    /** Swift `AVAssetReader` check of an audio import: decode until the first non-empty PCM buffer. */
    override suspend fun decodesAudio(file: File): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext false
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            codec = android.media.MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            repeat(500) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    val samples = info.size > 0
                    val end = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                    if (samples) return@withContext true
                    if (end) return@withContext false
                }
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            extractor.release()
        }
    }
}

/** Swift `UserDefaults.standard` for the selected project. */
class SharedProjectPreferences(private val prefs: SharedPreferences) : ProjectPreferences {
    override fun selectedProject(key: String): String? = prefs.getString(key, null)
    override fun setSelectedProject(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

/** Oriented picture size of a movie, as AVFoundation reports `naturalSize` with `preferredTransform`. */
object VideoGeometry {
    data class Info(val width: Int, val height: Int, val rotation: Int) {
        val orientedWidth: Int get() = if (rotation % 180 != 0) height else width
        val orientedHeight: Int get() = if (rotation % 180 != 0) width else height
    }

    /** Average picture rate: the track's declared rate, else frames over duration. */
    fun frameRate(file: File): Float? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val rate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
                        ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                    if (rate != null && rate > 0) return rate
                }
            }
        } catch (_: Exception) { } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
            val milliseconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull()
            if (frames != null && milliseconds != null && milliseconds > 0) frames * 1000f / milliseconds else null
        } catch (_: Exception) { null } finally { retriever.release() }
    }

    fun read(file: File): Info? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            Info(width, height, rotation)
        } catch (_: Exception) { null } finally { retriever.release() }
    }
}

/**
 * Photo Picker results arrive as content Uris. `ProjectStore.importVideo` copies
 * from a readable file, so the picked video is first staged in the app cache
 * (deleted afterwards); the picked original is only ever read.
 */
object MediaImportStaging {
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    suspend fun stage(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)?.takeIf { it.isNotBlank() } ?: "Video.mp4"
        // The Photo Picker names items by media id ("20.mp4"); title those like a new project.
        val stem = name.substringBeforeLast('.')
        val titled = if (stem.all { it.isDigit() }) importTitle() + "." + name.substringAfterLast('.', "mp4") else name
        val safe = titled.replace(Regex("[/\\\\\\u0000]"), "_")
        val folder = File(context.cacheDir, "ImportStaging/" + newUUID().uuidString).apply { mkdirs() }
        val staged = File(folder, if (safe.contains('.')) safe else "$safe.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        } ?: throw IOException("The selected video could not be read.")
        staged
    }

    /** The title `ProjectStore.create` gives a new project. */
    private fun importTitle(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String =
        java.time.format.DateTimeFormatter.ofPattern("MMM d").format(now) + " · " +
            java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(now)

    fun discard(staged: File) { staged.parentFile?.deleteRecursively() }
}

/** iOS ShareLink and Save to Photos: the system share sheet through FileProvider, and MediaStore `Movies/YouRoll`. */
object MediaSharing {
    const val galleryFolder = "Movies/YouRoll"

    fun share(context: Context, file: File, mime: String = "video/mp4", title: String? = null) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            title?.let { putExtra(android.content.Intent.EXTRA_TITLE, it) }
            clipData = android.content.ClipData.newRawUri(title ?: file.name, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, title).apply {
            if (context !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** A gallery file name from a title: no path separators, at most 80 characters. */
    fun displayName(title: String, extension: String = "mp4"): String {
        val base = title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ").trim().take(80).ifEmpty { "YouRoll" }
        return "$base.$extension"
    }

    /** Copy [file] into the shared gallery (no permission needed on API 29+). Never modifies [file]. */
    suspend fun saveToGallery(context: Context, file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, if (file.extension.lowercase() == "mov") "video/quicktime" else "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, galleryFolder)
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("The gallery did not accept the video.")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output, 1 shl 20) } }
                ?: throw IOException("The gallery did not accept the video.")
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        uri
    }
}
