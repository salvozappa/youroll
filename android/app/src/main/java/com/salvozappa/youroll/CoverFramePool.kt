// Port of YouRec/CoverFramePool.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlin.random.Random

/**
 * Sample the editor's 30 fps clock in chronological bands, skipping empty
 * timeline gaps. The pool stays small even for a multi-hour project.
 */
class CoverFramePool(picture: List<EditInterval>, scope: List<EditInterval>, private val random: Random = Random.Default) {
    private data class Span(val first: Long, val count: Int)

    private val spans: List<Span>
    val count: Int

    init {
        val pictures = merged(picture)
        val scopes = merged(scope)
        val result = mutableListOf<Span>()
        var i = 0
        var j = 0
        while (i < pictures.size && j < scopes.size) {
            val interval = pictures[i].intersection(scopes[j])
            if (interval != null && !interval.isEmpty) {
                val gridStart = (interval.start.ticks + step - 1) / step * step
                val first = if (gridStart < interval.end.ticks) gridStart else interval.start.ticks
                result += Span(first, 1 + ((interval.end.ticks - 1 - first) / step).toInt())
            }
            if (pictures[i].end < scopes[j].end) i += 1 else j += 1
        }
        spans = result
        count = result.sumOf { it.count }
    }

    fun picks(previous: List<Int>): List<Int> {
        val bands = minOf(count, 9)
        return (0 until bands).map { band ->
            val start = band * count / bands
            val end = (band + 1) * count / bands
            if (end - start <= 1 || band !in previous.indices) return@map random.nextInt(start, end)
            val pick = random.nextInt(start, end - 1)
            if (pick < previous[band]) pick else pick + 1
        }
    }

    fun time(index: Int): EditTime {
        var offset = index
        for (span in spans) {
            if (offset < span.count) return EditTime(span.first + offset.toLong() * step)
            offset -= span.count
        }
        throw IllegalArgumentException("Cover index outside the sampled range")
    }

    companion object {
        private const val step = EditTime.scale / 30

        private fun merged(ranges: List<EditInterval>): List<EditInterval> {
            val result = mutableListOf<EditInterval>()
            for (range in ranges.sortedBy { it.start }) {
                if (range.isEmpty) continue
                val last = result.lastOrNull()
                if (last != null && range.start <= last.end) result[result.size - 1] = EditInterval(last.start, maxOf(last.end, range.end))
                else result += range
            }
            return result
        }
    }
}
