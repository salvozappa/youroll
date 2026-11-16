// Debug-only MediaProbe for EditorDebugActivity. The app's real probe belongs to ContentView's owner.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DebugMediaProbe : MediaProbe {
    override suspend fun probe(file: File): MediaProbe.Info = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
                ?: throw ProjectError.invalidMedia
            MediaProbe.Info(
                duration = duration,
                hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } catch (failure: RuntimeException) {
            throw ProjectError.invalidMedia
        } finally {
            retriever.release()
        }
    }
}
