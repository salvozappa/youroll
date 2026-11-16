// Port of YouRec/LiveMicrophone.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Peak meter shared by the microphone engine and, on the CameraX fallback path,
 * the recording statistics. Fast attack, approximately 300 ms release between
 * callback buffers (the same constants as CaptureController.processMicrophoneSample in Swift).
 */
class LevelMeter {
    private val lock = Any()
    var lastAudioAt = Double.NEGATIVE_INFINITY; private set
    var lastClipAt = Double.NEGATIVE_INFINITY; private set
    var measuredPeak = -160f; private set

    /** [peak] is the linear absolute peak of one buffer in 0…1. */
    fun process(peak: Float, now: Double) = synchronized(lock) {
        lastAudioAt = now
        measuredPeak = max(20 * log10(max(peak, 0.00000001f)), measuredPeak - 1.5f)
        if (peak >= 0.988f) lastClipAt = now
    }

    fun reset() = synchronized(lock) { measuredPeak = -160f; lastClipAt = Double.NEGATIVE_INFINITY }

    data class Reading(val level: Float, val arriving: Boolean, val clipping: Boolean, val audioAge: Double)

    fun read(now: Double): Reading = synchronized(lock) {
        val age = now - lastAudioAt
        val arriving = age < 0.8
        Reading(if (arriving) measuredPeak else -160f, arriving, now - lastClipAt < 0.35, age)
    }

    companion object {
        fun peak(samples: ShortArray, count: Int): Float {
            var peak = 0
            for (i in 0 until count) peak = max(peak, abs(samples[i].toInt()))
            return peak / 32768f
        }
    }
}

/**
 * A microphone engine independent of the video capture. Replacing a camera must
 * not tear down the audio stream. As on iOS, this engine meters the input and
 * feeds the segment writer ([onSample]); blocks carry CLOCK_MONOTONIC timestamps
 * from `AudioRecord.getTimestamp`, the clock the writer puts camera frames on.
 *
 * The input is chosen with `AudioRecord.setPreferredDevice`, and [routedDevice]
 * reports the input the system actually used. On the CameraX fallback path the
 * controller stops this engine during a segment, because CameraX opens its own input.
 */
class LiveMicrophone(private val meter: LevelMeter) {
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var failed = false
    private var inputID: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var routing: AudioRouting.OnRoutingChangedListener? = null

    /** Receives every PCM block on the reader thread. */
    @Volatile var onSample: ((CapturedAudio) -> Unit)? = null
    /** Main thread: the system moved the input (a device appeared or disappeared). */
    var onRouting: (() -> Unit)? = null
    /** Channels of the running input (1 or 2). */
    @Volatile var channels = 1; private set

    val isRunning: Boolean get() = !failed && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val routedDevice: AudioDeviceInfo? get() = record?.routedDevice

    /** Throws when the microphone cannot be opened (permission, busy input). */
    @SuppressLint("MissingPermission")
    fun start(preferred: AudioDeviceInfo?) {
        val wanted = preferred?.let { CaptureMicrophones.uid(it.type, it.address.orEmpty()) }
        val running = record
        if (running != null && isRunning) {
            if (inputID == wanted) return
            // Reopening leaves a short gap in a take. Keep a running input when no
            // device is requested (the preferred one vanished and the system already
            // rerouted), or when the system already routes the requested one.
            if (wanted == null) return
            if (CaptureMicrophones.port(running.routedDevice)?.uid == wanted &&
                running.channelCount == CaptureMicrophones.channels(preferred?.channelCounts)) {
                running.preferredDevice = preferred; inputID = wanted; return
            }
        }
        stop()
        val rate = CaptureAudioNormalizer.RATE
        val stereo = CaptureMicrophones.channels(preferred?.channelCounts) == 2
        val next = open(rate, stereo, preferred) ?: (if (stereo) open(rate, false, preferred) else null)
            ?: throw IllegalStateException("The microphone could not be opened.")
        try { next.startRecording() } catch (e: Exception) { next.release(); throw e }
        if (next.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            next.release(); throw IllegalStateException("The microphone is in use by another app.")
        }
        val listener = AudioRouting.OnRoutingChangedListener { if (record === next) onRouting?.invoke() }
        next.addOnRoutingChangedListener(listener, main)
        routing = listener
        failed = false
        record = next; inputID = wanted; channels = next.channelCount
        val reader = Thread({ read(next, rate) }, "youroll-microphone")
        reader.priority = Thread.MAX_PRIORITY
        thread = reader
        reader.start()
    }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int, stereo: Boolean, preferred: AudioDeviceInfo?): AudioRecord? {
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val next = try {
            // Half a second of buffer: a busy reader must not overrun the input.
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, mask, AudioFormat.ENCODING_PCM_16BIT, max(minimum * 2, rate * (if (stereo) 4 else 2) / 2))
        } catch (_: Exception) { return null }
        if (next.state != AudioRecord.STATE_INITIALIZED) { next.release(); return null }
        if (preferred != null) next.preferredDevice = preferred
        return next
    }

    private fun read(source: AudioRecord, rate: Int) {
        val channels = source.channelCount
        val buffer = ShortArray(1024 * channels)
        val stamp = AudioTimestamp()
        var framesRead = 0L
        var stampTime = 0L
        var stampPosition = -1L
        while (record === source) {
            val count = try { source.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
            if (count < 0) { if (record === source) failed = true; break }
            if (count == 0) continue
            val frames = count / channels
            val first = framesRead
            framesRead += frames
            val now = System.nanoTime()
            // On a busy device reads lag the input by hundreds of milliseconds, so the
            // read time is no clock for the samples; the input's own timestamp is. A
            // timestamp in the future or seconds old is stale (seen after route changes).
            if (source.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                stamp.nanoTime <= now && now - stamp.nanoTime < 5_000_000_000L) {
                stampTime = stamp.nanoTime; stampPosition = stamp.framePosition
            }
            val time = if (stampPosition >= 0) stampTime + Math.round((first - stampPosition) * 1e9 / rate)
                else now - Math.round(frames * 1e9 / rate)
            meter.process(LevelMeter.peak(buffer, count), SystemClock.uptimeMillis() / 1000.0)
            onSample?.invoke(CapturedAudio(buffer.copyOf(count), frames, channels, rate, time))
        }
    }

    fun stop() {
        val old = record ?: return
        record = null; inputID = null
        routing?.let { old.removeOnRoutingChangedListener(it) }; routing = null
        try { old.stop() } catch (_: Exception) { }
        thread?.join(300); thread = null
        old.release()
    }
}
