// Port of YouRec/SubtitleSpeech.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

data class SubtitleSpeechChunk(
    val range: ClosedFloatingPointRange<Double>,
    val speech: List<ClosedFloatingPointRange<Double>>,
)

object SubtitleSpeechPlan {
    /**
     * An energy gate identifies timing anchors, not speakers. Qwen identifies
     * speech. The adaptive floor is capped so a continuously spoken passage
     * does not classify its quieter syllables as background.
     */
    fun chunks(levels: List<Float>, duration: Double, step: Double = 0.02): List<SubtitleSpeechChunk> {
        if (levels.isEmpty() || duration <= 0) return emptyList()
        val db = levels.map { 20 * log10(max(1e-9, it.toDouble())) }
        val sorted = db.sorted()
        val floor = sorted[min(sorted.size - 1, sorted.size / 5)]
        val threshold = max(-52.0, min(-35.0, floor + 9))
        val islands = mutableListOf<ClosedFloatingPointRange<Double>>()
        var begin: Int? = null
        var last = 0
        for (i in db.indices) {
            if (db[i] > threshold) { if (begin == null) begin = i; last = i }
            val start = begin
            if (start != null && (i - last > 8 || i == db.size - 1)) {
                if (last - start >= 2) {
                    val a = max(0.0, start * step - 0.08)
                    val b = min(duration, (last + 1) * step + 0.1)
                    val prior = islands.lastOrNull()
                    if (prior != null && a <= prior.endInclusive) islands[islands.size - 1] = prior.start..b
                    else if (b > a) islands += a..b
                }
                begin = null
            }
        }
        // Split long unbroken speech near the quietest 100 ms in a bounded
        // window. This keeps decoder context and activation memory bounded.
        val bounded = mutableListOf<ClosedFloatingPointRange<Double>>()
        for (island in islands) {
            var a = island.start
            while (island.endInclusive - a > 12) {
                val lower = min(db.size - 1, ((a + 8) / step).toInt())
                val upper = min(db.size - 1, ((a + 12) / step).toInt())
                var best = lower
                var energy = Double.POSITIVE_INFINITY
                for (i in lower..upper) {
                    var value = 0.0
                    for (k in max(0, i - 2)..min(db.size - 1, i + 2)) value += db[k]
                    if (value < energy) { energy = value; best = i }
                }
                val b = max(a + 8, best * step)
                bounded += a..b; a = b
            }
            if (island.endInclusive > a) bounded += a..island.endInclusive
        }
        val result = mutableListOf<SubtitleSpeechChunk>()
        for (island in bounded) {
            val previous = result.lastOrNull()
            if (previous != null && island.endInclusive - previous.range.start <= 12 &&
                (island.start - previous.range.endInclusive < 0.7 ||
                    (island.start - previous.range.endInclusive < 1.3 && previous.range.endInclusive - previous.range.start < 3))) {
                result[result.size - 1] = SubtitleSpeechChunk(previous.range.start..island.endInclusive, previous.speech + listOf(island))
            } else result += SubtitleSpeechChunk(island, listOf(island))
        }
        return result
    }
}
