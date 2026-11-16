// Port of YouRec/CaptureAudio.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import android.media.AudioDeviceInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The saved list controls preview. A quick selection or an open take pins a
 * port until Stop, except when the system confirms that a replacement is in use.
 *
 * Pure policy, ported line by line from Swift; times are seconds of uptime.
 */
class RecordingMicrophoneRoute {
    data class Decision(
        var request: String? = null,
        var recheckAfter: Double? = null,
        var disconnected: String? = null,
        var failed: String? = null,
    )

    private var pinned: String? = null
    private var missingSince: Double? = null
    private var removalReported = false
    private val refusedFallbacks = mutableSetOf<String>()
    private var pending: String? = null
    private var pendingActual: String? = null
    private var attempts = 0
    private var nextAttempt = 0.0

    /** The input this route holds for the current take or quick selection. */
    val pinnedInput: String? get() = pinned

    fun select(id: String) {
        reset()
        pinned = id
    }

    fun reset() {
        pinned = null; missingSince = null; removalReported = false; refusedFallbacks.clear()
        pending = null; pendingActual = null; attempts = 0; nextAttempt = 0.0
    }

    fun deviceDisconnected(previousInputs: List<String>) {
        val pinned = pinned
        if (pinned != null && previousInputs.contains(pinned)) removalReported = true
        pending = null
        refusedFallbacks.clear()
    }

    fun resolve(ranked: List<String>, actual: String?, preferred: String?, inTake: Boolean, now: Double): Decision {
        val result = Decision()
        if (inTake && pinned == null) pinned = ranked.firstOrNull() ?: actual
        var target = pinned ?: ranked.firstOrNull()
        val pinned = pinned
        if (pinned != null && !ranked.contains(pinned)) {
            // During Bluetooth/USB negotiation the input list can be empty or
            // briefly show only the phone, even with a working accessory.
            val first = ranked.firstOrNull { !refusedFallbacks.contains(it) || it == actual }
            if (actual == pinned || first == null) {
                missingSince = null
                pending = null
                result.recheckAfter = 1.0
                return result
            }
            val since = missingSince ?: now
            missingSince = since
            val remaining = since + (if (removalReported) 0.5 else 3.0) - now
            if (remaining > 0) {
                result.recheckAfter = remaining
                return result
            }
            target = first
            if (actual == first) {
                if (inTake) result.disconnected = pinned
                this.pinned = if (inTake) first else null
                missingSince = null
                removalReported = false
                refusedFallbacks.clear()
            }
        } else {
            missingSince = null
            removalReported = false
            refusedFallbacks.clear()
        }

        if (target == null || (target == actual && target == preferred)) {
            pending = null
            return result
        }
        // preferred acknowledges a request; the active route confirms where
        // the audio is coming from. Retry mismatches without a notification loop.
        if (pending != target || pendingActual != actual) {
            pending = target
            pendingActual = actual
            attempts = 0
            nextAttempt = now
        }
        val delays = listOf(0.25, 0.5, 1.0, 1.5)
        if (now < nextAttempt) {
            result.recheckAfter = nextAttempt - now
        } else if (attempts < delays.size) {
            result.request = target
            result.recheckAfter = delays[attempts]
            nextAttempt = now + delays[attempts]
            attempts += 1
        } else if (attempts == delays.size && actual != target) {
            if (missingSince != null && ranked.any { it != target && !refusedFallbacks.contains(it) }) {
                // The pinned port disappeared and the system declined this fallback.
                // Try remaining inputs in preference order and confirm the winner.
                refusedFallbacks.add(target)
                pending = null
                result.recheckAfter = 0.05
            } else {
                result.failed = target
                attempts += 1
            }
        }
        return result
    }

    companion object {
        fun priority(saved: List<String>, discovered: List<String>, actual: String?): List<String> {
            val order = saved.toMutableList()
            // A fresh installation starts with the accessory the system is already using.
            if (order.isEmpty() && actual != null && discovered.contains(actual)) order.add(actual)
            for (id in discovered) if (!order.contains(id)) order.add(id)
            return order
        }
    }
}

/**
 * Only the dB range and rounding are ported. The Swift class also applies the gain
 * to the microphone PCM before encoding; per-microphone recording gain is listed
 * in TODO.md.
 */
object RecordingGain {
    val range = -40.0..12.0
    fun normalized(db: Double): Double =
        if (db.isFinite()) min(range.endInclusive, max(range.start, (db * 2).roundToLong() / 2.0)) else 0.0
}

/**
 * Android counterpart of an AVAudioSession input port: a stable identifier
 * (type + address), the type and a display name. Built-in microphones are one
 * port, as on iOS, even when the phone exposes several capsules.
 */
data class MicrophonePort(val uid: String, val type: Int, val portName: String)

object CaptureMicrophones {
    private val recordable = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_DOCK,
    )

    fun uid(type: Int, address: String): String =
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "builtin" else "$type:$address"

    fun name(type: Int, productName: String?): String {
        val product = productName?.trim().orEmpty()
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset Microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                product.ifEmpty { "USB Microphone" }
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> product.ifEmpty { "Bluetooth Microphone" }
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> product.ifEmpty { "Line In" }
            else -> product.ifEmpty { "Microphone" }
        }
    }

    /** Recordable inputs in discovery order, one entry per port. */
    fun ports(devices: List<Triple<Int, String, String?>>): List<MicrophonePort> {
        val seen = LinkedHashMap<String, MicrophonePort>()
        for ((type, address, product) in devices) {
            if (type !in recordable) continue
            val id = uid(type, address)
            if (id !in seen) seen[id] = MicrophonePort(id, type, name(type, product))
        }
        return seen.values.toList()
    }

    fun ports(devices: Array<AudioDeviceInfo>): List<MicrophonePort> =
        ports(devices.filter { it.isSource }.map { Triple(it.type, it.address.orEmpty(), it.productName?.toString()) })

    fun port(device: AudioDeviceInfo?): MicrophonePort? =
        device?.let { ports(arrayOf(it)).firstOrNull() }

    /**
     * Recording channels for an input: stereo when the device reports a stereo
     * capture (a phone's built-in array with the camcorder source, USB stereo
     * receivers), else mono. An empty list ("dynamic", as on the emulator) is mono.
     */
    fun channels(channelCounts: IntArray?): Int = if (channelCounts != null && channelCounts.any { it >= 2 }) 2 else 1
}

/**
 * One block of microphone PCM: 16-bit interleaved samples and the CLOCK_MONOTONIC
 * time of its first frame (`System.nanoTime`), the clock the writer uses for
 * camera frames too. Swift's `CapturedAudio` wraps a CMSampleBuffer on the host clock.
 */
class CapturedAudio(val samples: ShortArray, val frames: Int, val channels: Int, val sampleRate: Int, val timeNanos: Long)

/** Normalised PCM ready for the AAC encoder: [frames] frames at [frame] on the segment's 48 kHz clock. */
class NormalizedAudio(val samples: ShortArray, val frame: Long, val frames: Int)

/** PCM helpers (Swift `CapturePCM` converts sample buffers; here only channel maps and clock nudges are needed). */
object CapturePCM {
    /** Mono is duplicated to both channels; stereo to mono averages the pair (Swift's channelMap is [0, 0] / [0, 1]). */
    fun remix(samples: ShortArray, frames: Int, from: Int, to: Int): ShortArray {
        if (from == to) return samples.copyOf(frames * to)
        val out = ShortArray(frames * to)
        for (frame in 0 until frames) {
            if (to == 2) {
                val left = samples[frame * from]
                out[frame * 2] = left
                out[frame * 2 + 1] = if (from >= 2) samples[frame * from + 1] else left
            } else out[frame] = ((samples[frame * from].toInt() + samples[frame * from + 1].toInt()) / 2).toShort()
        }
        return out
    }

    /**
     * Add ([amount] > 0) or remove frames at the quietest frame of the block, so a
     * microphone clock that drifts against the camera clock is corrected gradually,
     * as the Swift normaliser does, without shifting audio or inserting silence.
     */
    fun adjust(samples: ShortArray, frames: Int, channels: Int, amount: Int): ShortArray {
        if (amount == 0 || frames == 0) return samples
        val removing = if (amount < 0) min(-amount, frames - 1) else 0
        var quietest = 0
        var lowest = Long.MAX_VALUE
        for (frame in 0 until frames - removing) {
            var energy = 0L
            for (c in 0 until channels) { val v = samples[frame * channels + c].toLong(); energy += v * v }
            if (energy < lowest) { lowest = energy; quietest = frame }
        }
        return if (amount > 0) {
            val out = ShortArray((frames + amount) * channels)
            System.arraycopy(samples, 0, out, 0, (quietest + 1) * channels)
            for (i in 1..amount) System.arraycopy(samples, quietest * channels, out, (quietest + i) * channels, channels)
            System.arraycopy(samples, (quietest + 1) * channels, out, (quietest + 1 + amount) * channels, (frames - quietest - 1) * channels)
            out
        } else {
            val out = ShortArray((frames - removing) * channels)
            System.arraycopy(samples, 0, out, 0, quietest * channels)
            System.arraycopy(samples, (quietest + removing) * channels, out, quietest * channels, (frames - quietest - removing) * channels)
            out
        }
    }
}

/**
 * One fixed PCM format and sample clock across microphone route changes.
 * Missing input becomes explicit silence with a measured count; audio is never
 * shifted earlier to hide a hardware discontinuity. Port of the Swift class: the
 * input is already 48 kHz (AudioRecord resamples), so only channels are converted.
 * Times are CLOCK_MONOTONIC nanoseconds; [start] is the segment's first video frame.
 */
class CaptureAudioNormalizer(private val start: Long, val channels: Int) {
    var nextFrame = 0L; private set
    var insertedSilence = 0L; private set
    var clockCorrections = 0L; private set
    var maximumClockOffset = 0L; private set
    var resyncs = 0; private set
    private var expectedInputEnd: Long? = null
    private var sourceChannels = 0

    fun frameAt(nanos: Long): Long = Math.round((nanos - start).toDouble() * RATE / 1e9)

    fun process(block: CapturedAudio, end: Long? = null): List<NormalizedAudio> {
        if (block.sampleRate != RATE || block.channels !in 1..2) throw IllegalArgumentException("Unsupported microphone format.")
        val expected = expectedInputEnd
        var contiguous = sourceChannels == block.channels && expected != null &&
            abs(block.timeNanos - expected) < max(2_000_000L, 1_500_000_000L / RATE)
        sourceChannels = block.channels
        var converted = CapturePCM.remix(block.samples, block.frames, block.channels, channels)
        var count = block.frames
        val timestampFrame = frameAt(block.timeNanos)
        // A contiguous input follows its own sample clock, not a separately rounded
        // timestamp on every block. Only real input discontinuities create gaps.
        if (contiguous && timestampFrame >= 0) {
            val offset = timestampFrame - nextFrame
            maximumClockOffset = max(maximumClockOffset, abs(offset))
            if (abs(offset) > RESYNC) {
                // Android addition: an input whose sample count runs away from its
                // timestamps faster than the gradual correction (seen on the emulator)
                // is placed by its timestamp again, so picture and sound stay within a frame.
                contiguous = false
                resyncs += 1
            } else if (abs(offset) > 72 && count > 2) {
                val amount = (min(abs(offset) - 48, max(1L, count / 1000L)) * (if (offset > 0) 1 else -1)).toInt()
                converted = CapturePCM.adjust(converted, count, channels, amount)
                count += amount; clockCorrections += amount
            }
        }
        val target = if (contiguous && timestampFrame >= 0) nextFrame else timestampFrame
        expectedInputEnd = block.timeNanos + Math.round(block.frames * 1e9 / RATE)
        if (target - nextFrame >= RATE * 10L) throw IllegalStateException("The microphone clock jumped.")
        val outputs = mutableListOf<NormalizedAudio>()
        val finalFrame = end?.let { max(0L, frameAt(it)) } ?: Long.MAX_VALUE
        silence(min(target, finalFrame), outputs)
        val skip = min(count.toLong(), max(0L, nextFrame - target)).toInt()
        val kept = min((count - skip).toLong(), max(0L, finalFrame - nextFrame)).toInt()
        if (kept > 0) {
            outputs.add(NormalizedAudio(converted.copyOfRange(skip * channels, (skip + kept) * channels), nextFrame, kept))
            nextFrame += kept
        }
        return outputs
    }

    /** Silence up to [end] when the microphone's last block ends before Stop. */
    fun pad(end: Long): List<NormalizedAudio> {
        val finalFrame = max(0L, frameAt(end))
        if (finalFrame - nextFrame > RATE * 10L) throw IllegalStateException("The microphone ended long before Stop.")
        return mutableListOf<NormalizedAudio>().also { silence(finalFrame, it) }
    }

    private fun silence(until: Long, outputs: MutableList<NormalizedAudio>) {
        while (until > nextFrame) {
            val count = min(4096L, until - nextFrame).toInt()
            outputs.add(NormalizedAudio(ShortArray(count * channels), nextFrame, count))
            nextFrame += count; insertedSilence += count
        }
    }

    companion object {
        const val RATE = 48000
        /** Frames (30 ms, under one 30 fps frame) of clock offset that re-anchor the input to its timestamps. */
        const val RESYNC = 1440L
    }
}
