// Port of YouRec/VideoEffects/VideoEffectAssets.swift (palettes only)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Ported: the palette track types, the Pop palette presets (PopPalettes.json,
// copied into assets/VideoEffects), `VideoEffectInputs.displayPalette`/`sample`
// and `VideoEffectAnalysis.samples`' 64×36 sampling size. The scene palette
// itself is analysed while rendering (MetalVideoEffects.kt) from the frames the
// renderer sees, every 0.2 s of source time with the same temporal smoothing
// as the iOS palette track.
//
// Not ported (TODO.md): the persisted palette tracks and the analysis cache
// (`VideoEffectAnalysis.palette`), person/depth inputs (`VideoEffectInputs`
// masks, `VideoEffectPersonProvider`), background media (`EffectBackgroundMovie`,
// `VideoEffectBackgroundMedia`), `LiveEffectPaletteSampler`.
package com.salvozappa.youroll

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.max

@kotlinx.serialization.Serializable
data class EffectPaletteFrame(val time: Double, val palette: EffectPalette)

@kotlinx.serialization.Serializable
data class EffectPaletteTrack(val frames: List<EffectPaletteFrame>) {
    val valid: Boolean
        get() = frames.isNotEmpty() && frames.size <= 3_100_000 && frames.all { it.time.isFinite() && it.time in 0.0..604800.0 && it.palette.valid } &&
            frames.zipWithNext().all { (a, b) -> a.time < b.time }

    fun palette(time: Double): EffectPalette? {
        if (frames.isEmpty()) return null
        var a = 0; var b = frames.size
        while (a < b) { val middle = (a + b) / 2; if (frames[middle].time <= time) a = middle + 1 else b = middle }
        return frames[max(0, a - 1)].palette
    }
}

object VideoEffectInputs {
    /** Swift `VideoEffectInputs.pop`, read from assets/VideoEffects/PopPalettes.json by [install]. */
    @Volatile var pop: List<EffectPalettePreset> = emptyList()
        private set

    fun install(context: Context) {
        if (pop.isNotEmpty()) return
        pop = runCatching {
            context.assets.open("VideoEffects/PopPalettes.json").use { stream ->
                SwiftJSON.decodeFromString(ListSerializer(EffectPalettePreset.serializer()), stream.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrElse { emptyList() }
    }

    /** For tests: the presets parsed from [json]. */
    fun install(json: String) { pop = SwiftJSON.decodeFromString(ListSerializer(EffectPalettePreset.serializer()), json) }

    fun displayPalette(effect: VideoEffectSettings, time: Double, analyzed: EffectPalette?): EffectPalette? {
        val count = effect.number("colors", time).toInt()
        var palette = if (analyzed?.colors?.size == count) analyzed else null
        if (effect.toggle("lockPalette", time)) effect.lockedPalette?.let { if (it.colors.size == count) palette = it }
        if (effect.toggle("monochrome", time)) return palette
        val choice = effect.choice("palette", time)
        val saved = effect.palette
        if (choice == "Saved" && saved != null && saved.size >= 2) {
            val colors = sample(saved, count)
            // Nearest-color matching needs no scene analysis. Supply valid bands
            // for the recorded sidecar even before the first live histogram arrives.
            val luminances = colors.map { EffectColorMath.luminance(EffectColorMath.linear(it)) }.sorted()
            val thresholds = palette?.thresholds ?: luminances.zipWithNext().map { (a, b) -> (a + b) / 2 }
            return EffectPalette(colors, thresholds)
        }
        pop.firstOrNull { it.name == choice }?.let { preset -> palette = palette?.copy(colors = preset.sampled(count)) }
        return palette
    }

    fun sample(colors: List<EffectRGB>, count: Int): List<EffectRGB> {
        if (colors.size <= 1 || count <= 1 || count == colors.size) return colors
        val entries = colors.map { it to EffectColorMath.lab(EffectColorMath.linear(it)) }.sortedBy { it.second.x }
        if (entries.size < count) return (0 until count).map { entries[swiftRound(it.toDouble() * (entries.size - 1) / (count - 1)).toInt()].first }
        // Preserve distinct hues when reducing a saved palette. Brightness ranks
        // alone can discard its only saturated entry between several neutrals.
        val chosen = mutableListOf(0)
        val distance = entries.map { it.second.distanceSquared(entries[0].second) }.toMutableList()
        distance[0] = -1.0
        while (chosen.size < count) {
            val next = distance.indices.maxByOrNull { distance[it] } ?: break
            chosen += next
            distance[next] = -1.0
            for (i in entries.indices) distance[i] = minOf(distance[i], entries[i].second.distanceSquared(entries[next].second))
        }
        return chosen.sorted().map { entries[it].first }
    }
}

object VideoEffectAnalysis {
    /** Swift `samples(_:context:)`: the analysed image is reduced to 64×36 (36×64 for portrait). */
    fun sampleSize(width: Int, height: Int): Pair<Int, Int> = if (width >= height) 64 to 36 else 36 to 64

    /** Swift `prefix(_:before:)`: the effects ordered before [id] (its palette and colour inputs). */
    fun prefix(effects: List<VideoEffectSettings>, before: UUID): List<VideoEffectSettings> =
        VideoEffectSettings.ordered(effects).takeWhile { it.id != before }
            .filter { it.kind != VideoEffectID.quantization && it.kind != VideoEffectID.stepPrinting }
            .map { effect ->
                val value = effect.recordingResult
                if (value.kind == VideoEffectID.undercranking) value.updatingParameter("speed") { it.copy(value = EffectValue.number(1.0), keyframes = emptyList()) } else value
            }
}
