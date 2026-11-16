// Port of YouRec/WaveformDetail.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class WaveformTile(val start: Double, val low: FloatArray, val high: FloatArray) {
    fun envelope(from: Double, to: Double): Pair<Float, Float>? {
        if (!(from >= start && from < start + seconds)) return null
        val a = max(0, ((from - start) * binsPerSecond).toInt())
        val b = min(low.size, max(a + 1, ceil((to - start) * binsPerSecond).toInt()))
        if (a >= b) return null
        var lo = low[a]; var hi = high[a]
        for (i in a + 1 until b) { if (low[i] < lo) lo = low[i]; if (high[i] > hi) hi = high[i] }
        return lo to hi
    }

    companion object {
        const val seconds = 4.0
        const val binsPerSecond = 1000.0

        /** The per-frame binning of `WaveformDetailCache.decode`, separated for tests. */
        fun build(index: Int, chunks: Sequence<PcmDecoder.Chunk>): WaveformTile {
            val start = index * seconds
            val count = (seconds * binsPerSecond).toInt()
            val low = FloatArray(count); val high = FloatArray(count)
            for (chunk in chunks) accumulate(chunk, start, low, high)
            return WaveformTile(start, low, high)
        }

        internal fun accumulate(chunk: PcmDecoder.Chunk, start: Double, low: FloatArray, high: FloatArray) {
            val origin = chunk.origin - start
            for (frame in 0 until chunk.frames) {
                val bin = floor((origin + frame.toDouble() / chunk.sampleRate) * binsPerSecond + 1e-7).toInt()
                if (bin < 0 || bin >= low.size) continue
                for (channel in 0 until chunk.channels) {
                    val value = chunk.samples[frame * chunk.channels + channel]
                    if (value < low[bin]) low[bin] = value
                    if (value > high[bin]) high[bin] = value
                }
            }
        }
    }
}

// Only visible four-second tiles are decoded. No full-project millisecond array,
// scratch PCM file, or per-pixel decoding. 64 tiles cost about two megabytes.
object WaveformDetailCache {
    private val lock = Mutex()
    private val tiles = HashMap<String, WaveformTile>()
    private val order = ArrayList<String>()

    suspend fun tile(file: File, index: Int): WaveformTile {
        val key = "${file.path}|${file.length()}|${file.lastModified()}|$index"
        lock.withLock {
            tiles[key]?.let { order.remove(key); order.add(key); return it }
        }
        val result = withContext(Dispatchers.IO) { decode(file, index) }
        currentCoroutineContext().ensureActive()
        lock.withLock {
            tiles[key] = result; order.add(key)
            while (order.size > 64) tiles.remove(order.removeAt(0))
        }
        return result
    }

    private suspend fun decode(file: File, index: Int): WaveformTile {
        val start = index * WaveformTile.seconds
        val count = (WaveformTile.seconds * WaveformTile.binsPerSecond).toInt()
        val low = FloatArray(count); val high = FloatArray(count)
        val context = currentCoroutineContext()
        PcmDecoder.decode(file, start, start + WaveformTile.seconds, active = { context.isActive }) { chunk ->
            WaveformTile.accumulate(chunk, start, low, high)
        }
        return WaveformTile(start, low, high)
    }
}
