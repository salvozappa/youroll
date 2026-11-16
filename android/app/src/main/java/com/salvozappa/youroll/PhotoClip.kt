// Port of YouRec/PhotoClip.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// A self-contained, silent timeline source. Swift decodes the still once and
// appends its pixels at 30 fps with AVAssetWriter; here Media3 Transformer
// encodes the image for three seconds at 30 fps into an H.264 MP4.
// Differences (TODO.md): the long side is capped at 1920 px instead of 3840 so
// every device encoder accepts it, and the container is MP4 instead of MOV.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

object PhotoClip {
    const val seconds = 3
    private const val maximumSide = 1920

    suspend fun write(context: Context, source: File, destination: File, mimeType: String? = null) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ProjectError.invalidMedia
        val scale = minOf(1.0, maximumSide.toDouble() / max(bounds.outWidth, bounds.outHeight))
        fun even(value: Int) = max(2, (value + 1) / 2 * 2)
        // The upright picture's size: EXIF can rotate the stored pixels by a quarter turn.
        val quarterTurn = runCatching {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) in
                listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        }.getOrDefault(false)
        val width = even(((if (quarterTurn) bounds.outHeight else bounds.outWidth) * scale).roundToInt())
        val height = even(((if (quarterTurn) bounds.outWidth else bounds.outHeight) * scale).roundToInt())
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val item = MediaItem.Builder().setUri(Uri.fromFile(source)).setImageDurationMs(seconds * 1000L)
                        .apply { if (mimeType != null) setMimeType(mimeType) }.build()
                    // Presentation sizes the frame; EXIF rotation is applied by the bitmap loader.
                    val edited = EditedMediaItem.Builder(item).setFrameRate(30)
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
                        .build()
                    val transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        }).build()
                    transformer.start(edited, destination.path)
                    continuation.invokeOnCancellation { transformer.cancel() }
                }
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }
}
