// Port of YouRec/AudioChainControls.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The UIKit `FilledFaderControl` becomes a Compose canvas with the same
// geometry: a tap selects, a vertical drag adjusts relative to where it
// started (snapping to the default and the ends), and the value is committed
// when the finger lifts. iOS reorders by dragging a strip's grip onto another
// strip; here a long-press drag on the grip moves the strip by the strips it
// crosses. LavaSR (Detail) and VoiceFixer (Studio) are Core ML models with no
// Android runtime yet: their strips keep the saved settings and say
// "Not yet available" (TODO.md).
package com.salvozappa.youroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

val EffectKind.stripName: String
    get() = when (this) {
        EffectKind.voiceIsolation -> "Denoise"
        EffectKind.studioVoice -> "Detail"
        EffectKind.studioRestoration -> "Studio"
        EffectKind.highPass -> "Low-cut"
        EffectKind.equalizer -> "EQ"
        EffectKind.gain -> "Gain"
        else -> "Unknown"
    }

val EffectKind.stripColor: Color
    get() = when (this) {
        EffectKind.voiceIsolation -> Color(0.18f, 0.83f, 0.69f)
        EffectKind.studioVoice -> Color(0.98f, 0.70f, 0.29f)
        EffectKind.studioRestoration -> Color(0.45f, 0.71f, 0.98f)
        EffectKind.highPass -> Color(0.73f, 0.53f, 0.97f)
        EffectKind.equalizer -> Color(0.34f, 0.66f, 1f)
        EffectKind.gain -> Color(1f, 0.44f, 0.50f)
        else -> Color.Gray
    }

private val EffectKind.primaryLabel: String get() = if (isNeural) "Amount" else if (this == EffectKind.highPass) "Cutoff" else "Gain"
private val EffectKind.faderLabel: String get() = if (this == EffectKind.gain) title else if (isNeural) "$title amount" else "$title ${primaryLabel.lowercase()}"

val AudioEffect.normalizedPrimary: Double
    get() = when {
        kind.isNeural -> min(1.0, max(0.0, amount))
        kind == EffectKind.highPass -> min(1.0, max(0.0, ln(max(20.0, frequency) / 20) / ln(25.0)))
        else -> min(1.0, max(0.0, (amount + 24) / 48))
    }

fun AudioEffect.settingPrimary(normalized: Double): AudioEffect {
    val n = min(1.0, max(0.0, normalized))
    return when {
        kind.isNeural -> copy(amount = swiftRound(n * 100) / 100)
        kind == EffectKind.highPass -> copy(frequency = swiftRound(20 * 25.0.pow(n)))
        else -> copy(amount = swiftRound((n * 48 - 24) * 10) / 10)
    }
}

val AudioEffect.frequencyText: String
    get() = if (frequency >= 1000) "%.1f kHz".format(java.util.Locale.ROOT, frequency / 1000) else "${swiftRound(frequency).toLong()} Hz"

val AudioEffect.primaryText: String
    get() = when {
        kind.isNeural -> "${swiftRound(amount * 100).toLong()}%"
        kind == EffectKind.highPass -> frequencyText
        else -> "%+.1f dB".format(java.util.Locale.ROOT, amount)
    }

/** True when this strip can be edited and processed here (supported and, for Core ML models, ported). */
private val AudioEffect.adjustable: Boolean get() = supported && !awaitsAndroidModel

/**
 * Selection is local UI state; each finished adjustment or move commits
 * exactly one change to the existing project history. Effects keep their stable IDs.
 */
@Composable
fun AudioChainControls(effects: List<AudioEffect>, busy: Boolean, compact: Boolean = false, change: (List<AudioEffect>) -> Unit) {
    var selectedID by remember { mutableStateOf(effects.firstOrNull()?.id) }
    var previousIDs by remember { mutableStateOf(effects.map { it.id }) }
    // Swift `.onChange(of: effects.map(\.id))`: select an added strip, or the first when the selection is gone.
    LaunchedEffect(effects.map { it.id }) {
        val ids = effects.map { it.id }
        val added = ids.firstOrNull { it !in previousIDs }
        if (added != null) selectedID = added else if (selectedID !in ids) selectedID = ids.firstOrNull()
        previousIDs = ids
    }
    val selected = effects.firstOrNull { it.id == selectedID } ?: effects.firstOrNull()
    fun replace(effect: AudioEffect) {
        if (busy) return
        val index = effects.indexOfFirst { it.id == effect.id }.takeIf { it >= 0 } ?: return
        change(effects.toMutableList().also { it[index] = effect })
    }
    fun move(source: UUID, by: Int) {
        if (busy) return
        val from = effects.indexOfFirst { it.id == source }.takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, effects.lastIndex)
        if (from == to) return
        val next = effects.toMutableList(); val effect = next.removeAt(from); next.add(to, effect)
        selectedID = source; change(next)
    }
    if (effects.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Tune, null, tint = EditorColors.secondary, modifier = Modifier.size(34.dp))
            Text("Build your sound", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Add a filter above. Combine noise reduction, speech detail and EQ in any order.", color = EditorColors.secondary,
                fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        return
    }
    @Composable fun strips(modifier: Modifier) {
        Row(modifier.horizontalScroll(rememberScrollState()).padding(3.dp).testTag("audio.strips"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((index, effect) in effects.withIndex()) {
                EffectStrip(effect, index, selected = selected?.id == effect.id, busy = busy, compact = compact,
                    select = { selectedID = effect.id }, change = ::replace, reorder = { move(effect.id, it) })
            }
        }
    }
    @Composable fun settings() {
        val effect = selected ?: return
        val index = effects.indexOfFirst { it.id == effect.id }
        androidx.compose.runtime.key(effect.id) {
            SelectedEffectSettings(effect, index, effects.size, compact, change = ::replace, move = { offset -> move(effect.id, offset) }, remove = {
                val next = effects.toMutableList(); next.removeAt(index)
                selectedID = next.getOrNull(min(index, next.size - 1))?.id
                change(next)
            }, busy = busy)
        }
    }
    if (compact) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            strips(Modifier.weight(1f))
            // Reserve only the width the settings need; the chain gets the remaining space.
            Box(Modifier.width(220.dp).height(196.dp).verticalScroll(rememberScrollState()).testTag("audio.details")) { settings() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${effects.size} ${if (effects.size == 1) "FILTER" else "FILTERS"}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                Spacer(Modifier.weight(1f))
                Text("Hold grips to reorder", color = EditorColors.secondary, fontSize = 12.sp)
            }
            strips(Modifier)
            settings()
        }
    }
}

@Composable
private fun EffectStrip(effect: AudioEffect, index: Int, selected: Boolean, busy: Boolean, compact: Boolean,
                        select: () -> Unit, change: (AudioEffect) -> Unit, reorder: (Int) -> Unit) {
    val stripWidth = if (compact) 96.dp else 82.dp
    val stripPixels = with(LocalDensity.current) { (stripWidth + 10.dp).toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val currentSelect by rememberUpdatedState(select)
    val currentReorder by rememberUpdatedState(reorder)
    Column(Modifier.width(stripWidth).offset { IntOffset(dragX.roundToInt(), 0) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val grip = @Composable { modifier: Modifier ->
            Row(modifier.height(44.dp)
                .pointerInput(busy, index) {
                    if (busy) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { currentSelect(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragEnd = { val by = (dragX / stripPixels).roundToInt(); dragX = 0f; if (by != 0) currentReorder(by) },
                        onDragCancel = { dragX = 0f },
                        onDrag = { change, amount -> change.consume(); dragX += amount.x })
                }
                .pointerInput(Unit) { detectTapGestures { currentSelect() } }
                .padding(horizontal = if (compact) 4.dp else 10.dp)
                .semantics { contentDescription = "Reorder ${effect.kind.title}"; stateDescription = "Position ${index + 1}" },
                verticalAlignment = Alignment.CenterVertically) {
                if (!compact) Text("${index + 1}", color = EditorColors.secondary, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.DragHandle, null, tint = EditorColors.secondary, modifier = Modifier.size(16.dp))
            }
        }
        val power = @Composable { modifier: Modifier ->
            val color = effect.kind.stripColor
            Row(modifier.heightIn(min = 44.dp).clip(CircleShape).background(color.copy(alpha = if (effect.enabled) 0.15f else 0.04f))
                .clickable(enabled = !busy) { select(); change(effect.copy(enabled = !effect.enabled)) }
                .semantics { contentDescription = "${effect.kind.title} enabled"; stateDescription = if (effect.enabled) "On" else "Off" }
                .testTag("audio.enabled.${effect.id}"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PowerSettingsNew, null, tint = if (effect.enabled) color else EditorColors.secondary, modifier = Modifier.size(16.dp))
                if (!compact) Text(if (effect.enabled) " On" else " Off", color = if (effect.enabled) color else EditorColors.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (compact) Row(verticalAlignment = Alignment.CenterVertically) { grip(Modifier.width(44.dp)); Spacer(Modifier.weight(1f)); power(Modifier.width(44.dp)) }
        else { grip(Modifier.fillMaxWidth()); power(Modifier.fillMaxWidth()) }
        FilledEffectFader(effect, selected, busy, select, change, Modifier.fillMaxWidth().height(if (compact) 140.dp else 180.dp))
    }
}

/**
 * Swift `FilledFaderControl`: a direction-aware vertical drag adjusts the
 * value relative to where it started; a tap selects without changing it.
 */
@Composable
private fun FilledEffectFader(effect: AudioEffect, selected: Boolean, busy: Boolean, select: () -> Unit, change: (AudioEffect) -> Unit, modifier: Modifier) {
    val enabled = !busy && effect.adjustable
    // Gesture handlers outlive recompositions: read the latest effect and callbacks.
    val current by rememberUpdatedState(effect)
    val currentChange by rememberUpdatedState(change)
    val currentSelect by rememberUpdatedState(select)
    var dragging by remember { mutableStateOf(false) }
    var normalized by remember { mutableDoubleStateOf(effect.normalizedPrimary) }
    if (!dragging) normalized = effect.normalizedPrimary
    var origin by remember { mutableDoubleStateOf(0.0) }
    var travel by remember { mutableFloatStateOf(0f) }
    var snapPoint by remember { mutableStateOf<Double?>(null) }
    val haptics = LocalHapticFeedback.current
    val color = effect.kind.stripColor
    val edited = effect.settingPrimary(normalized)
    val bipolar = effect.kind == EffectKind.equalizer || effect.kind == EffectKind.gain
    val parameter = when (effect.kind) {
        EffectKind.voiceIsolation -> if (effect.model == AudioModel.deepFilter) "DFN3" else "RNNoise"
        EffectKind.studioVoice -> "LavaSR"
        EffectKind.studioRestoration -> "VoiceFixer"
        EffectKind.equalizer -> effect.frequencyText
        EffectKind.gain -> null
        else -> effect.kind.primaryLabel
    }
    fun dragValue(height: Float): Double {
        val value = min(1.0, max(0.0, origin - travel / max(1f, height)))
        val center = AudioEffect.make(current.kind).normalizedPrimary
        val tolerance = if (bipolar) 0.4 / 48 else 0.01
        val snap = if (value == 0.0 || value == 1.0) value else if (abs(value - center) <= tolerance) center else null
        if (snap != snapPoint && snap != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        snapPoint = snap
        return snap ?: value
    }
    Box(modifier.clip(RoundedCornerShape(20.dp))
        .background(color.copy(alpha = if (effect.enabled) 0.1f else 0.035f))
        .border(if (selected) 2.5.dp else 1.5.dp, if (selected) Color.White else color.copy(alpha = 0.48f), RoundedCornerShape(20.dp))
        .alpha(if (effect.adjustable) 1f else 0.6f)
        .pointerInput(Unit) { detectTapGestures { currentSelect() } }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectVerticalDragGestures(
                onDragStart = { dragging = true; origin = normalized; travel = 0f; snapPoint = null; currentSelect() },
                onDragEnd = { dragging = false; currentChange(current.settingPrimary(normalized)) },
                onDragCancel = { dragging = false; normalized = current.normalizedPrimary },
                onVerticalDrag = { pointer, amount -> pointer.consume(); travel += amount; normalized = dragValue(size.height.toFloat()) })
        }
        .semantics { contentDescription = effect.kind.faderLabel; stateDescription = edited.primaryText + if (effect.enabled) "" else ", bypassed" }
        .testTag("audio.fader.${effect.id}")) {
        Canvas(Modifier.fillMaxSize()) {
            val y = (1 - normalized).toFloat() * size.height
            val mid = size.height / 2
            val top = if (bipolar) min(y, mid) else y
            val height = if (bipolar) abs(mid - y) else size.height - y
            drawRect(color.copy(alpha = if (effect.enabled) 0.48f else 0.10f), Offset(0f, top), Size(size.width, height))
            if (bipolar) drawRect(Color.White.copy(alpha = 0.24f), Offset(8.dp.toPx(), mid), Size(size.width - 16.dp.toPx(), 1.dp.toPx()))
            val line = max(0f, min(size.height - 2.dp.toPx(), y))
            drawRect(color.copy(alpha = if (effect.enabled) 1f else 0.4f), Offset(0f, line), Size(size.width, 2.dp.toPx()))
        }
        val compact = false
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            Text(effect.kind.stripName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (parameter != null) Text(parameter, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
            if (effect.awaitsAndroidModel) Text("Not yet available", color = EditorColors.orange, fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Text(edited.primaryText, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = tabular, maxLines = 1)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectedEffectSettings(effect: AudioEffect, index: Int, count: Int, compact: Boolean, change: (AudioEffect) -> Unit,
                                   move: (Int) -> Unit, remove: () -> Unit, busy: Boolean) {
    var frequency by remember(effect.frequency) { mutableDoubleStateOf(effect.frequency) }
    val color = effect.kind.stripColor
    val caption = @Composable { text: String, tint: Color -> Text(text, color = tint, fontSize = 12.sp) }
    val actions = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(enabled = index > 0 && !busy) { move(-1) }.semantics { contentDescription = "Move effect earlier" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = if (index > 0) EditorColors.accent else EditorColors.secondary.copy(alpha = 0.4f))
            }
            Box(Modifier.size(44.dp).clickable(enabled = index + 1 < count && !busy) { move(1) }.semantics { contentDescription = "Move effect later" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = if (index + 1 < count) EditorColors.accent else EditorColors.secondary.copy(alpha = 0.4f))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.heightIn(min = 44.dp).clickable(enabled = effect.supported && !busy) {
                change(AudioEffect.make(effect.kind).copy(id = effect.id, enabled = effect.enabled, model = effect.model))
            }.padding(horizontal = 8.dp).semantics { contentDescription = "Reset selected effect" }, contentAlignment = Alignment.Center) {
                Text("Reset", color = Color.White.copy(alpha = if (effect.supported) 0.85f else 0.3f), fontSize = 17.sp)
            }
            Box(Modifier.size(44.dp).clickable(enabled = !busy) { remove() }.semantics { contentDescription = "Remove effect" }.testTag("audio.remove"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Delete, null, tint = EditorColors.red)
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.07f))
        .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(18.dp)).padding(if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("audio.selectedEffect")) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(effect.kind.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (!effect.enabled) Text("Bypassed", color = EditorColors.secondary, fontSize = 12.sp)
        }
        if (compact) actions()
        when {
            !effect.supported ->
                caption("This filter is unavailable in this app version. Bypass or remove it to process audio; its saved settings are retained.", EditorColors.orange)
            effect.awaitsAndroidModel -> {
                caption("Not yet available in YouRoll: ${effect.modelName} runs a Core ML model that has no Android version yet. Bypass or remove it to process audio; its saved settings are kept.", EditorColors.orange)
            }
            effect.kind == EffectKind.voiceIsolation -> {
                var open by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Model", color = EditorColors.secondary, fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    Box {
                        val name = if (effect.model == AudioModel.deepFilter) "DeepFilterNet3" else "RNNoise"
                        Row(Modifier.heightIn(min = 44.dp).clickable(enabled = !busy) { open = true }
                            .semantics { contentDescription = "Noise reduction model"; stateDescription = name }.testTag("audio.model"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = EditorColors.accent, fontSize = 17.sp)
                            Icon(Icons.Filled.ExpandMore, null, tint = EditorColors.accent, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem({ Text("RNNoise") }, { open = false; change(effect.copy(model = AudioModel.rnnoise)) })
                            DropdownMenuItem({ Text("DeepFilterNet3") }, { open = false; change(effect.copy(model = AudioModel.deepFilter)) })
                        }
                    }
                }
                if (!compact) caption("Reduces background noise. Adjust Amount in the column above.", EditorColors.secondary)
            }
            effect.kind == EffectKind.equalizer -> {
                Row {
                    Text("Frequency", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Text(effect.copy(frequency = frequency).frequencyText, color = Color.White, fontSize = 15.sp, style = tabular)
                }
                Slider(value = (ln(max(80.0, frequency) / 80) / ln(150.0)).toFloat().coerceIn(0f, 1f),
                    onValueChange = { frequency = swiftRound(80 * 150.0.pow(it.toDouble())) },
                    onValueChangeFinished = { change(effect.copy(frequency = frequency)) }, enabled = !busy,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = color),
                    modifier = Modifier.semantics { contentDescription = "EQ frequency" })
                if (!compact) caption("Boost or cut this frequency with the EQ column. Add another EQ stage for another band.", EditorColors.secondary)
            }
            !compact -> caption(if (effect.kind == EffectKind.highPass) "Removes low rumble below the cutoff. Raise the column to raise the cutoff frequency."
                else "Adjusts the output level in decibels. The center line is 0 dB.", EditorColors.secondary)
        }
        if (!compact) actions()
    }
}
