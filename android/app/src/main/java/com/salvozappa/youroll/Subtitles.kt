// Port of YouRec/Subtitles.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Times are estimates, anchored in the original sound. Each audio item owns
// its words by value: splits, moves, copies and Undo cannot share mutable text.
//
// Differences from Swift (TODO.md):
// - `SubtitleTiming.tokens` uses java.text.BreakIterator (ICU on Android)
//   instead of NLTokenizer to find word starts.
// - `subtitleTimingFingerprint` hashes a Swift-style JSON encoding
//   ([SwiftFingerprintJSON]): sorted keys, integral doubles without a
//   fraction, Swift's exponent format.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Times are estimates in the item's source seconds. */
@Serializable
data class SubtitleWord(
    val id: UUID = newUUID(),
    val phraseID: UUID = newUUID(),
    val text: String,
    val start: Double,
    val end: Double,
)

@Serializable
enum class SubtitleFont {
    system, modern, classic, mono, condensed;

    val title: String
        get() = when (this) {
            system -> "System"; modern -> "Modern"; classic -> "Classic"; mono -> "Mono"; condensed -> "Narrow"
        }

    /** The iOS font each case names (stored in a legacy design's `face`). */
    fun postScriptName(bold: Boolean): String? = when (this) {
        system -> null
        modern -> if (bold) "AvenirNext-DemiBold" else "AvenirNext-Regular"
        classic -> if (bold) "Georgia-Bold" else "Georgia"
        mono -> if (bold) "Menlo-Bold" else "Menlo-Regular"
        condensed -> "DINCondensed-Bold"
    }

    companion object {
        /** The case whose iOS font is [name], if any (Avenir Next, Georgia, Menlo and DIN are not on Android). */
        fun fromPostScriptName(name: String): Pair<SubtitleFont, Boolean>? {
            for (font in entries) for (bold in listOf(true, false)) if (font.postScriptName(bold) == name) return font to bold
            return null
        }
    }
}

@Serializable
data class SubtitleSettings(
    val id: UUID = newUUID(),
    val maxWords: Int = 6,
    val style: TextStyle = TextStyle.box,
    val color: TextColor = TextColor.white,
    val size: Double = 0.052,
    val y: Double = 0.86,
    val language: String = "Auto",
    val timingFingerprint: String = "",
    // Optional fields preserve projects written before direct caption placement.
    val x: Double? = null,
    val font: SubtitleFont? = null,
    val appearance: TitleDesign? = null,
    val motion: TitleMotion? = null,
) {
    val design: TitleDesign get() = appearance ?: TitleDesign.legacy(style, color, resolvedFont)
    val centerX: Double get() = x ?: 0.5
    val resolvedFont: SubtitleFont get() = font ?: SubtitleFont.system
    val valid: Boolean
        get() = appearance?.valid != false && motion?.valid != false && maxWords in 1..16 && size.isFinite() && size in 0.0001..0.30 &&
            y.isFinite() && y in 0.0..1.0 && centerX.isFinite() && centerX in 0.0..1.0
}

data class SubtitleCue(
    val id: String,
    val audioID: UUID,
    val wordIDs: List<UUID>,
    val text: String,
    val start: Double,
    val end: Double,
) {
    fun overlay(settings: SubtitleSettings): TextOverlay =
        TextOverlay(text = text, start = start, end = end, x = settings.centerX, y = settings.y, size = settings.size, style = settings.style,
            color = settings.color, appearance = settings.appearance, motion = settings.motion)
}

object SubtitleTiming {
    private const val sentenceEnds = ".!?。！？"

    /**
     * Preserve punctuation with its preceding word. Swift uses NLTokenizer;
     * BreakIterator (ICU on Android) also handles languages that do not
     * separate words with spaces.
     */
    fun tokens(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty()) return emptyList()
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(value)
        val starts = mutableListOf<Int>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            // A word token contains a letter or a number; spaces and punctuation are not tokens.
            var index = start
            var word = false
            while (index < end) {
                val c = value.codePointAt(index)
                if (Character.isLetterOrDigit(c)) { word = true; break }
                index += Character.charCount(c)
            }
            if (word) starts += start
            start = end
            end = iterator.next()
        }
        if (starts.isEmpty()) return listOf(value)
        starts[0] = 0
        return starts.indices.map { i ->
            value.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] else value.length).trim()
        }.filter { it.isNotEmpty() }
    }

    fun joined(words: List<String>): String = words.joinToString(" ")

    private fun weight(text: String): Double = max(1, text.codePoints().filter { Character.isLetter(it) }.count().toInt()).toDouble().pow(0.65)
    private fun normalized(text: String): String {
        val result = StringBuilder()
        text.lowercase().codePoints().forEach { if (Character.isLetter(it) || Character.isDigit(it)) result.appendCodePoint(it) }
        return result.toString()
    }

    /**
     * Divide voiced time, never the intervening silence. Allocate each word to
     * one speech island before interpolating within that island.
     */
    fun estimate(text: String, speech: List<ClosedFloatingPointRange<Double>>): List<SubtitleWord> {
        val words = tokens(text)
        val spans = speech.filter { it.endInclusive > it.start }
        if (words.isEmpty() || spans.isEmpty()) return emptyList()
        val totalTime = spans.sumOf { it.endInclusive - it.start }
        val weights = words.map(::weight)
        val totalWeight = weights.sum()
        val grouped = List(spans.size) { mutableListOf<Int>() }
        var consumed = 0.0
        for (i in words.indices) {
            val midpoint = (consumed + weights[i] / 2) / totalWeight * totalTime
            var cursor = 0.0
            var island = spans.size - 1
            for (j in spans.indices) {
                cursor += spans[j].endInclusive - spans[j].start
                if (midpoint < cursor) { island = j; break }
            }
            grouped[island] += i; consumed += weights[i]
        }
        return spans.indices.flatMap { j ->
            val sum = grouped[j].sumOf { weights[it] }
            var cursor = spans[j].start
            grouped[j].map { i ->
                val end = min(spans[j].endInclusive, cursor + (spans[j].endInclusive - spans[j].start) * weights[i] / max(sum, 0.001))
                SubtitleWord(text = words[i], start = cursor, end = end).also { cursor = end }
            }
        }
    }

    fun group(input: List<SubtitleWord>, maxWords: Int): List<SubtitleWord> {
        val words = input.sortedBy { it.start }.toMutableList()
        var group = newUUID()
        var count = 0
        for (i in words.indices) {
            if (i > 0 && (count >= maxWords || words[i].start - words[i - 1].end > 0.32 || words[i - 1].text.lastOrNull()?.let { it in sentenceEnds } == true)) {
                group = newUUID(); count = 0
            }
            words[i] = words[i].copy(phraseID = group); count += 1
        }
        return words
    }

    /**
     * LCS keeps unchanged words anchored. Replacements share only the freed
     * interval between neighboring surviving words. Full rewrites retain the
     * old phrase interval. Punctuation-only edits leave all timings intact.
     */
    fun corrected(oldWords: List<SubtitleWord>, text: String): List<SubtitleWord> {
        val old = oldWords.sortedBy { it.start }
        val next = tokens(text)
        val first = old.firstOrNull() ?: return emptyList()
        val last = old.last()
        if (next.isEmpty()) return emptyList()
        // Bound work for a pasted paragraph, without quadratic allocation.
        if (!(old.size <= 128 && next.size <= 128)) {
            return estimate(joined(next.take(128)), listOf(first.start..last.end)).map { it.copy(phraseID = first.phraseID) }
        }
        val a = old.map { normalized(it.text) }
        val b = next.map(::normalized)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else max(dp[i + 1][j], dp[i][j + 1])
        }
        val anchors = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { anchors += i to j; i += 1; j += 1 }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i += 1 else j += 1
        }
        val result = mutableListOf<SubtitleWord>()
        var priorOld = -1
        var priorNew = -1
        for ((oi, ni) in anchors + (old.size to next.size)) {
            if (ni > priorNew + 1) {
                var start = if (priorOld < 0) first.start else old[priorOld].end
                var end = if (oi == old.size) last.end else old[oi].start
                // Insertion between adjacent words has no empty interval. Borrow
                // a small part of the following/preceding word within the phrase.
                if (end - start < 0.04) {
                    if (oi < old.size) end = min(old[oi].end, start + (old[oi].end - old[oi].start) * 0.45)
                    else result.lastOrNull()?.let { previous ->
                        start = max(previous.start, previous.end - (previous.end - previous.start) * 0.45)
                        result[result.size - 1] = previous.copy(end = start)
                    }
                }
                val replacement = estimate(joined(next.subList(priorNew + 1, ni)), listOf(start..max(start, end)))
                result += replacement.map { it.copy(phraseID = first.phraseID) }
            }
            if (oi < old.size) {
                var w = old[oi].copy(text = next[ni])
                w = w.copy(start = max(w.start, result.lastOrNull()?.end ?: w.start))
                if (w.end > w.start) result += w
            }
            priorOld = oi; priorNew = ni
        }
        return result
    }
}

/** Swift `TrackTimeline.subtitleCues`. */
val TrackTimeline.subtitleCues: List<SubtitleCue>
    get() {
        if (subtitles == null) return emptyList()
        val cues = mutableListOf<SubtitleCue>()
        for (item in audio) {
            if (item.muted) continue
            var offset = 0.0
            for ((runIndex, run) in item.mapping.runs.withIndex()) {
                val runOffset = offset
                offset += run.duration.seconds
                if (run.silent || run.isHold) continue
                val scale = run.duration.seconds / (run.sourceEnd - run.sourceStart)
                val words = (item.subtitleWords ?: emptyList()).filter { it.end > run.sourceStart && it.start < run.sourceEnd }.sortedBy { it.start }
                val group = mutableListOf<SubtitleWord>()
                fun emit() {
                    val first = group.firstOrNull() ?: return
                    val last = group.last()
                    val start = item.start.seconds + runOffset + (max(run.sourceStart, first.start) - run.sourceStart) * scale
                    val end = item.start.seconds + runOffset + (min(run.sourceEnd, last.end) - run.sourceStart) * scale
                    if (end > start) cues += SubtitleCue("${item.id.uuidString}-$runIndex-${first.id.uuidString}", item.id, group.map { it.id },
                        SubtitleTiming.joined(group.map { it.text }), start, end)
                }
                for (word in words) {
                    val last = group.lastOrNull()
                    if (last != null && (last.phraseID != word.phraseID || word.start - last.end > 0.4)) { emit(); group.clear() }
                    group += word
                }
                emit()
            }
        }
        return cues.sortedWith { a, b -> if (a.start == b.start) a.id.compareTo(b.id) else a.start.compareTo(b.start) }
    }

/**
 * Excludes color, framing, gain and caption edits. Includes structural sound
 * and picture edits. Undo to the transcribed structure restores regrouping.
 */
val TrackTimeline.subtitleTimingFingerprint: String
    get() {
        fun item(id: UUID, source: UUID, start: EditTime, mapping: SourcePlaybackMap, muted: Boolean): JsonElement = JsonObject(mapOf(
            "id" to JsonPrimitive(id.uuidString),
            "source" to JsonPrimitive(source.uuidString),
            "start" to SwiftJSON.encodeToJsonElement(EditTime.serializer(), start),
            "mapping" to SwiftJSON.encodeToJsonElement(SourcePlaybackMap.serializer(), mapping),
            "muted" to JsonPrimitive(muted),
        ))
        val sound = audio.map { item(it.id, it.sourceID, it.start, it.mapping, it.muted) }
        val picture = video.map { item(it.id, it.content.sourceID, it.start, it.mapping, false) }
        return sha256Hex(SwiftFingerprintJSON.encode(JsonArray(sound + picture)).toByteArray(Charsets.UTF_8))
    }

val TrackTimeline.subtitleGroupingAvailable: Boolean get() = subtitles?.timingFingerprint == subtitleTimingFingerprint

fun TrackTimeline.removeSubtitles() {
    subtitles = null
    audio = audio.map { it.copy(subtitleWords = null) }
}

fun TrackTimeline.regroupSubtitles(count: Int) {
    if (!(subtitleGroupingAvailable && count in 1..16)) return
    subtitles = subtitles?.copy(maxWords = count)
    audio = audio.map { it.copy(subtitleWords = SubtitleTiming.group(it.subtitleWords ?: emptyList(), count)) }
}

fun TrackTimeline.correctSubtitle(cue: SubtitleCue, text: String) {
    val i = audio.indexOfFirst { it.id == cue.audioID }
    if (i < 0) return
    val ids = cue.wordIDs.toSet()
    val words = audio[i].subtitleWords ?: emptyList()
    val updated = (words.filter { it.id !in ids } + SubtitleTiming.corrected(words.filter { it.id in ids }, text)).sortedBy { it.start }
    audio = audio.updated(i) { it.copy(subtitleWords = updated) }
}

/**
 * Attach estimated project times to the strongest eligible speech track.
 * Transitions/overlapping speakers are best effort; never duplicate a word
 * onto both tracks. Each source time remains independent of project moves.
 */
fun TrackTimeline.installSubtitles(words: List<SubtitleWord>, range: ClosedFloatingPointRange<Double>, language: String) {
    val previous = subtitleCues
    var settings = (subtitles ?: SubtitleSettings()).copy(language = language)
    val coversExisting = previous.all { it.start >= range.start - 0.001 && it.end <= range.endInclusive + 0.001 }
    if (subtitles == null || subtitleGroupingAvailable || coversExisting) settings = settings.copy(timingFingerprint = subtitleTimingFingerprint)
    subtitles = settings
    audio = audio.map { item ->
        item.copy(subtitleWords = (item.subtitleWords ?: emptyList()).filter { word ->
            val local = item.mapping.local((word.start + word.end) / 2) ?: return@filter false
            (item.start.seconds + local.seconds) !in range
        })
    }
    val newIDs = words.map { it.id }.toSet()
    for (word in words) {
        val midpoint = EditTime.of((word.start + word.end) / 2)
        val candidates = audio.indices.filter { !audio[it].muted && audio[it].interval.contains(midpoint) }.sortedWith { a, b ->
            if (audio[a].volumeDB == audio[b].volumeDB) audio[a].id.uuidString.compareTo(audio[b].id.uuidString)
            else audio[b].volumeDB.compareTo(audio[a].volumeDB)
        }
        for (i in candidates) {
            val item = audio[i]
            var cursor = item.start.seconds
            var attached = false
            for (run in item.mapping.runs) {
                val runStart = cursor
                cursor += run.duration.seconds
                if (run.silent || run.isHold || !(midpoint.seconds >= runStart && midpoint.seconds < runStart + run.duration.seconds)) continue
                val a = max(runStart, word.start)
                val b = min(runStart + run.duration.seconds, word.end)
                val value = word.copy(start = run.source(EditTime.of(a - runStart)), end = run.source(EditTime.of(b - runStart)))
                if (value.end > value.start) {
                    audio = audio.updated(i) { it.copy(subtitleWords = (it.subtitleWords ?: emptyList()) + value) }
                    attached = true
                }
                break
            }
            if (attached) break
        }
    }
    // Re-transcribing a selection must not regroup corrected captions outside it.
    audio = audio.map { item ->
        val all = item.subtitleWords ?: emptyList()
        val preserved = all.filter { it.id !in newIDs }
        val added = SubtitleTiming.group(all.filter { it.id in newIDs }, settings.maxWords)
        item.copy(subtitleWords = (preserved + added).sortedBy { it.start })
    }
}

/**
 * The bytes Swift's `JSONEncoder` with `.sortedKeys` writes for a value, so
 * the subtitle timing fingerprint of an iOS project stays valid here: keys
 * sorted, no spaces, `/` escaped, and Swift's `Double.description` without a
 * trailing ".0" (exponent form below 1e-4 and from 1e16).
 */
internal object SwiftFingerprintJSON {
    fun encode(value: JsonElement): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonObject -> {
                out.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    string(key, out); out.append(':'); write(value.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, element -> if (index > 0) out.append(','); write(element, out) }
                out.append(']')
            }
            is JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                value.isString -> string(value.content, out)
                value.content == "true" || value.content == "false" -> out.append(value.content)
                value.longOrNull != null && !value.content.contains('.') && !value.content.contains('e', ignoreCase = true) -> out.append(value.content)
                else -> out.append(number(value.doubleOrNull ?: 0.0))
            }
        }
    }

    private fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) when (c) {
            '"' -> out.append("\\\""); '\\' -> out.append("\\\\"); '/' -> out.append("\\/")
            '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t")
            '\b' -> out.append("\\b"); '\u000C' -> out.append("\\f")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
    }

    /** Swift `Double.description` (an integral value has no fraction in JSON). */
    fun number(value: Double): String {
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e16) return value.toLong().toString()
        val decimal = BigDecimal(value.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().abs().toString()
        val exponent = digits.length - 1 - decimal.scale() // value = d.ddd × 10^exponent
        val sign = if (value < 0) "-" else ""
        if (exponent < -4 || exponent >= 16) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val e = kotlin.math.abs(exponent).toString().padStart(2, '0')
            return sign + mantissa + "e" + (if (exponent < 0) "-" else "+") + e
        }
        return sign + decimal.abs().toPlainString().let { if (it.contains('.')) it else "$it.0" }
    }
}
