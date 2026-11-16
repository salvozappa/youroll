// Copyright (c) 2026 YouRoll contributors. MIT License.
//
// The editor preview retimes a sped-up, slowed or held picture with the very
// MediaSource that Transformer's ExoPlayerAssetLoader (the export) and Media3's
// own CompositionPlayer use for `EditedMediaItem.setSpeed`, so preview and
// export map source frames to the timeline identically. That class is
// package-private in Media3 1.11; this file only exposes its constructor.
// Recheck on every Media3 upgrade (TODO.md).
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package androidx.media3.transformer

import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.exoplayer.source.MediaSource

object PreviewSpeedSource {
    /**
     * [source] (already clipped from [clipStartUs]) with its period clock
     * divided by [speed] from the clip start, as in the export.
     */
    fun create(source: MediaSource, speed: SpeedProvider, clipStartUs: Long): MediaSource =
        SpeedChangingMediaSource(source, speed, MediaItem.ClippingConfiguration.Builder().setStartPositionUs(clipStartUs).build())
}
