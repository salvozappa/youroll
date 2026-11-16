// Port of YouRec/VideoEffects/VideoEffectSampling.swift (held source frames)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS reads a freeze's source frame with `VideoEffectSourceReader` and renders
// every output frame of the hold in its compositor, so the Freeze Frame push-in
// and Print grain animate over the held picture. Media3 has no frame source for
// that: a one-frame item slowed down to the hold reaches the effects only once.
// YouRoll therefore turns each held picture into a "hold clip" in the project
// cache: the source frame (decoded like the transition clips, upright, at the
// oriented source size, no framing or effects) repeated at 30 fps for 10 s,
// the longest hold. Both the preview playlist and the export play the hold's
// span of that clip as an ordinary piece, so the framing, the effect chain
// (with the hold clock, `holdTime`) and the overlays apply to it exactly as to
// other footage, and preview == export.
//
// Not ported (TODO.md): `VideoEffectFrameIndex`/`VideoEffectFrameAnalysis`
// (source frame indices for Step Printing and temporal mattes),
// `VideoEffectSamplePlan`'s Step Printing blocks, `VideoEffectRecordedMatteHistory`
// and `VideoEffectSourceMatteProvider`. The cadence holds of `VideoEffectSamplePlan`
// are in MetalVideoEffects.kt (`VideoEffectsShaderProgram`).
package com.salvozappa.youroll

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** A held source picture as a clip (see the file comment). */
data class VideoEffectHoldClip(val sourceID: UUID, val file: File, val sourceTime: Double) {
    val key: String
        get() {
            val text = "hold-v1|${file.name}:${file.length()}:${file.lastModified()}|$sourceTime|$frames@$framesPerSecond"
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }

    fun output(folder: File): File = File(ProjectCacheLocation.root(folder), "hold-$key.mp4")

    companion object {
        const val framesPerSecond = 30
        /** Ten seconds (the longest hold, including a split hold's offset) and one frame. */
        const val frames = 10 * framesPerSecond + 1
        val duration: Double get() = frames.toDouble() / framesPerSecond

        /** The held pictures of [timeline]: one clip per source picture. */
        fun all(timeline: TrackTimeline, sources: List<SourceMedia>, folder: File): List<VideoEffectHoldClip> =
            timeline.video.mapNotNull { item ->
                val hold = item.content.frozenFrame ?: return@mapNotNull null
                val source = sources.firstOrNull { it.id == item.content.sourceID } ?: return@mapNotNull null
                VideoEffectHoldClip(source.id, File(folder, source.filename), hold.sourceTime)
            }.distinct()
    }
}

object VideoEffectHoldRenderer {
    private val lock = Mutex()

    /** Renders [clip] into its cache file unless it already exists, and returns the file. */
    suspend fun render(clip: VideoEffectHoldClip, folder: File): File = lock.withLock {
        val destination = clip.output(folder)
        if (destination.exists() && destination.length() > 0) return@withLock destination
        val context = coroutineContext
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + ".partial")
            try {
                encode(clip, partial) { if (context[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException("Hold render cancelled") }
                if (!partial.renameTo(destination)) throw IOException("The freeze frame could not be prepared.")
            } finally { partial.delete() }
        }
        destination
    }

    private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTexture;
uniform mat3 uMap;
uniform mat4 uSurface;
uniform vec2 uCanvas;
void main() {
    vec2 c = vec2(gl_FragCoord.x, uCanvas.y - gl_FragCoord.y);
    vec3 q = uMap * vec3(c, 1.0);
    vec4 t = uSurface * vec4(q.x, 1.0 - q.y, 0.0, 1.0);
    gl_FragColor = vec4(texture2D(uTexture, t.xy).rgb, 1.0);
}
"""

    private fun encode(clip: VideoEffectHoldClip, output: File, checkCancelled: () -> Unit) {
        val egl = TransitionEGL()
        val oes = IntArray(1)
        GLES20.glGenTextures(1, oes, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val reader = TransitionFrameReader(clip.file, oes[0])
        val width = if (reader.rotation % 180 != 0) reader.codedHeight else reader.codedWidth
        val height = if (reader.rotation % 180 != 0) reader.codedWidth else reader.codedHeight
        val time = HoldFrames.read(clip.file, clip.sourceTime)?.startUs ?: Math.round(clip.sourceTime * 1_000_000)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, max(2_000_000, width * height * 12))
            setInteger(MediaFormat.KEY_FRAME_RATE, VideoEffectHoldClip.framesPerSecond)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = encoder.createInputSurface()
        encoder.start()
        val surface = egl.windowSurface(input)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end) return; if (System.nanoTime() > deadline) throw IOException("The freeze frame encoder stalled.") }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { data.position(info.offset); data.limit(info.offset + info.size); muxer.writeSampleData(track, data, info) }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val program = GLES20.glCreateProgram()
        try {
            egl.makeCurrent(surface)
            fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
                val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) throw IOException("Hold shader: " + GLES20.glGetShaderInfoLog(shader))
            }
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, TransitionRenderer.VERTEX))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glBindAttribLocation(program, 0, "aPosition")
            GLES20.glLinkProgram(program)
            if (!reader.frame(time)) throw IOException("The held picture could not be read.")
            // Oriented (upright) pixels → normalized coded picture coordinates.
            val unrotate = when (((reader.rotation % 360) + 360) % 360) {
                90 -> CGAffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, reader.codedHeight.toDouble())
                180 -> CGAffineTransform(-1.0, 0.0, 0.0, -1.0, reader.codedWidth.toDouble(), reader.codedHeight.toDouble())
                270 -> CGAffineTransform(0.0, 1.0, -1.0, 0.0, reader.codedWidth.toDouble(), 0.0)
                else -> CGAffineTransform.identity
            }
            val m = unrotate.concatenating(CGAffineTransform.scale(1.0 / reader.codedWidth, 1.0 / reader.codedHeight))
            val map = floatArrayOf(m.a.toFloat(), m.b.toFloat(), 0f, m.c.toFloat(), m.d.toFloat(), 0f, m.tx.toFloat(), m.ty.toFloat(), 1f)
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            for (k in 0 until VideoEffectHoldClip.frames) {
                checkCancelled()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes[0])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
                GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uMap"), 1, false, map, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSurface"), 1, false, reader.surfaceTransform, 0)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uCanvas"), width.toFloat(), height.toFloat())
                GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glEnableVertexAttribArray(0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                egl.present(surface, k * 1_000_000_000L / VideoEffectHoldClip.framesPerSecond)
                drain(false)
            }
            encoder.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { GLES20.glDeleteProgram(program) }
            runCatching { egl.destroy(surface) }
            runCatching { encoder.stop() }; encoder.release(); input.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
            runCatching { reader.close() }
            runCatching { GLES20.glDeleteTextures(1, oes, 0) }
            egl.close()
        }
    }
}
