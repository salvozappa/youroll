// Copyright (c) 2026 YouRoll contributors. MIT License.
//
// Android glue for the editor preview (no Swift counterpart). iOS's
// compositor is handed each frame's source time by AVFoundation. ExoPlayer's
// effect pipeline only sees a frame time on its own clock: the renderer time
// of the decoded buffer plus the sink's adjustment (`MediaCodecVideoRenderer.
// getBufferTimestampAdjustmentUs`), where the renderer time is the playlist
// item's period time plus that stream's renderer offset. The offsets change
// with every playlist item and every seek, so `ClockedVideoRenderer` records
// them as streams start, and `PreviewFrameClock.locate` maps a frame time back
// to its playlist item and period time. The preview's stabilization effect
// then corrects exactly the source frame being drawn, as the export does.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

class PreviewFrameClock {
    private data class Stream(val startUs: Long, val offsetUs: Long, val periodUid: Any)

    private val streams = ArrayList<Stream>()
    @Volatile private var adjustmentUs = 0L
    /** The player's playlist timeline (set on the main thread when it changes). */
    @Volatile var timeline: Timeline = Timeline.EMPTY

    internal fun streamChanged(startUs: Long, offsetUs: Long, periodUid: Any) = synchronized(streams) {
        // A seek restarts the renderer clock: streams at or after the new start belong to the old playback.
        streams.removeAll { it.startUs >= startUs }
        streams += Stream(startUs, offsetUs, periodUid)
        while (streams.size > 64) streams.removeAt(0)
    }

    internal fun adjustmentChanged(value: Long) { adjustmentUs = value }

    /** The playlist item and its period time (microseconds) of the frame an effect receives at [frameTimeUs]; null if unknown. */
    fun locate(frameTimeUs: Long): Pair<Int, Long>? {
        val rendererTime = frameTimeUs - adjustmentUs
        val stream = synchronized(streams) { streams.lastOrNull { it.startUs <= rendererTime } ?: streams.firstOrNull() } ?: return null
        val timeline = timeline
        val index = timeline.getIndexOfPeriod(stream.periodUid)
        if (index == C.INDEX_UNSET) return null
        return timeline.getPeriod(index, Timeline.Period()).windowIndex to (rendererTime - stream.offsetUs)
    }
}

/** A MediaCodecVideoRenderer that reports its streams' renderer offsets to [clock]. */
class ClockedVideoRenderer(builder: Builder, private val clock: PreviewFrameClock) : MediaCodecVideoRenderer(builder) {
    override fun onStreamChanged(formats: Array<out Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        clock.streamChanged(startPositionUs, offsetUs, mediaPeriodId.periodUid)
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onProcessedStreamChange() {
        super.onProcessedStreamChange()
        // The adjustment the video sink was just given (MediaCodecVideoRenderer sets it here).
        clock.adjustmentChanged(bufferTimestampAdjustmentUs)
    }
}

/** DefaultRenderersFactory whose video renderer is a [ClockedVideoRenderer] (same settings as Media3's). */
open class ClockedRenderersFactory(context: Context, private val clock: PreviewFrameClock) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                                     eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
        out += ClockedVideoRenderer(MediaCodecVideoRenderer.Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY), clock)
    }
}
