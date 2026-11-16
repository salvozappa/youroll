// Android glue: the 32-bit float PCM files of the enhancement pipeline. iOS
// uses AVAudioFile with Float32 CAF files (OfflineAudioPipeline.swift,
// SourceMediaCache.swift); YouRoll writes standard IEEE-float WAV files
// (format tag 3), which the preview mixer memory-maps and Media3 reads in the export.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/** Interleaved float WAV files with a fixed 44-byte header. */
object AudioFile {
    const val headerSize = 44
    const val sampleRate = 48_000

    fun header(channels: Int, frames: Long, rate: Int = sampleRate): ByteArray {
        val data = frames * channels * 4
        return ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(min32(36 + data)).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 4).putShort((channels * 4).toShort()).putShort(32)
            .put("data".toByteArray()).putInt(min32(data)).array()
    }

    private fun min32(value: Long): Int = if (value > 0xFFFF_FFFFL) -1 else value.toInt()

    class Info(val channels: Int, val sampleRate: Int, val frames: Long)

    /** Reads and validates a header written by [Writer]; null for anything else. */
    fun info(file: File): Info? {
        if (!file.isFile || file.length() < headerSize) return null
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        RandomAccessFile(file, "r").use { it.channel.read(header, 0) }
        header.flip()
        fun tag(text: String) = ByteArray(4).also { header.get(it) }.contentEquals(text.toByteArray())
        if (!tag("RIFF")) return null
        header.int
        if (!tag("WAVE") || !tag("fmt ") || header.int != 16 || header.short.toInt() != 3) return null
        val channels = header.short.toInt()
        val rate = header.int
        header.int; header.short
        if (header.short.toInt() != 32 || !tag("data") || channels !in 1..8 || rate <= 0) return null
        val frames = (file.length() - headerSize) / (4L * channels)
        return Info(channels, rate, frames)
    }

    /** Streams interleaved samples into a new file; [close] writes the final sizes. */
    class Writer(val file: File, val channels: Int, val rate: Int = sampleRate) : Closeable {
        private val handle = RandomAccessFile(file, "rw")
        private val buffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0L
            private set
        private var closed = false

        init {
            handle.setLength(0)
            handle.write(header(channels, 0, rate))
        }

        fun write(samples: FloatArray, frames: Int, offset: Int = 0) {
            for (index in offset until offset + frames * channels) {
                if (buffer.remaining() < 4) flush()
                buffer.putFloat(samples[index])
            }
            this.frames += frames
        }

        private fun flush() {
            buffer.flip()
            handle.channel.write(buffer)
            buffer.clear()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
                handle.seek(0)
                handle.write(header(channels, frames, rate))
                handle.fd.sync()
            } finally { handle.close() }
        }
    }

    /** Sequential reads of interleaved frames. */
    class Reader(val file: File) : Closeable {
        private val handle = RandomAccessFile(file, "r")
        val info: Info = info(file) ?: run { handle.close(); throw AudioProcessingError.invalidFormat }
        val channels get() = info.channels
        val length get() = info.frames
        var framePosition = 0L
            private set
        private val bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

        fun seek(frame: Long) {
            framePosition = frame.coerceIn(0, length)
        }

        /** Reads up to [frames] frames into [into]; returns the count (0 at the end). */
        fun read(into: FloatArray, frames: Int): Int {
            val count = minOf(frames.toLong(), length - framePosition).toInt()
            if (count <= 0) return 0
            var position = headerSize + framePosition * channels * 4
            var index = 0
            val total = count * channels
            while (index < total) {
                bytes.clear()
                bytes.limit(minOf(bytes.capacity(), (total - index) * 4))
                val read = handle.channel.read(bytes, position)
                if (read <= 0) throw IOException("Unexpected end of ${file.name}")
                position += read
                bytes.flip()
                while (bytes.remaining() >= 4) into[index++] = bytes.float
                if (bytes.hasRemaining()) { position -= bytes.remaining() }
            }
            framePosition += count
            return count
        }

        override fun close() = handle.close()
    }
}

/** A memory-mapped float WAV for the preview mixer ([PcmSamples]). */
class FloatPcm private constructor(override val sampleRate: Int, override val channels: Int, private val data: FloatBuffer,
                                   /** The source frame of this file's first frame (a preview window), else 0. */
                                   val origin: Long = 0) : PcmSamples {
    override val frames: Long = (data.capacity() / channels).toLong()
    override fun sample(frame: Long, channel: Int): Float {
        val local = frame - origin
        if (local < 0 || local >= frames) return 0f
        return data.get((local * channels).toInt() + channel)
    }

    companion object {
        fun open(file: File, origin: Long = 0): FloatPcm? {
            val info = AudioFile.info(file) ?: return null
            return RandomAccessFile(file, "r").use { handle ->
                // One mapping covers about 90 minutes of 48 kHz stereo float; anything later plays silent.
                val length = minOf(info.frames * info.channels * 4, (Int.MAX_VALUE.toLong() - 8) / (4 * info.channels) * (4 * info.channels))
                val mapped = handle.channel.map(FileChannel.MapMode.READ_ONLY, AudioFile.headerSize.toLong(), length).order(ByteOrder.LITTLE_ENDIAN)
                FloatPcm(info.sampleRate, info.channels, mapped.asFloatBuffer(), origin)
            }
        }
    }
}

/** Several [FloatPcm] windows of one source (Swift `AudioPreviewWindow`s); silent between them. */
class WindowedPcm(private val windows: List<FloatPcm>) : PcmSamples {
    override val sampleRate: Int = windows.firstOrNull()?.sampleRate ?: AudioFile.sampleRate
    override val channels: Int = windows.firstOrNull()?.channels ?: 2
    override val frames: Long = windows.maxOfOrNull { it.origin + it.frames } ?: 0
    override fun sample(frame: Long, channel: Int): Float {
        for (window in windows) if (frame >= window.origin && frame < window.origin + window.frames) return window.sample(frame, channel)
        return 0f
    }
}
