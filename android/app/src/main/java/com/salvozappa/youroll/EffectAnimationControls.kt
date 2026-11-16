// Port of YouRec/EffectAnimationControls.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The A/B animation controls shared by the animated tools; YouRoll uses them for
// Color Correction in the video effects inspector. `EffectPreviewEndObserver`
// (an AVPlayer end notification) is not needed: the inspector's playback loop
// returns to the endpoint itself.
package com.salvozappa.youroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

class EffectAnimationControls(
    val motion: EffectAnimation?,
    val endpoint: EffectEndpoint?,
    val duration: Double,
    val select: (EffectEndpoint) -> Unit,
    val remove: () -> Unit,
    val done: () -> Unit,
    val update: (EffectTiming) -> Unit,
    val showsEndpointMenu: Boolean = true,
) {
    /** Swift `endpointMenu`: Animate / Edit A·B, with Remove animation and Done once animated. */
    @Composable
    fun EndpointMenu() {
        var open by remember { mutableStateOf(false) }
        Box {
            Row(Modifier.heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(50)).clickable { open = true }
                .padding(horizontal = 12.dp).semantics { contentDescription = endpoint?.let { "Edit " + it.rawValue } ?: "Animate" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (motion == null) Icons.Outlined.Diamond else Icons.Filled.Diamond, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(endpoint?.let { "Edit " + it.rawValue } ?: "Animate", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(open, { open = false }) {
                for (e in EffectEndpoint.entries) DropdownMenuItem(text = { Text(e.rawValue) }, onClick = { open = false; select(e) },
                    leadingIcon = { Icon(if (e == endpoint) Icons.Filled.Diamond else Icons.Outlined.Diamond, null) })
                if (motion != null) {
                    DropdownMenuItem(text = { Text("Remove animation", color = EditorColors.red) }, onClick = { open = false; remove() })
                    DropdownMenuItem(text = { Text("Done") }, onClick = { open = false; done() })
                }
            }
        }
    }

    @Composable
    fun Body() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val motion = motion
            if (motion != null) {
                TimingMenu("Animation curve", motion.timing.curve, EffectCurve.entries, { it.title }, { choice ->
                    var timing = motion.timing.copy(curve = choice)
                    if (choice == EffectCurve.riseHoldReturn && timing.rise + timing.release > duration) timing = timing.copy(rise = duration / 2, release = duration / 2)
                    update(timing)
                }) { if (showsEndpointMenu) EndpointMenu() else Text("Animation curve", color = Color.White, fontSize = 15.sp) }
            } else if (showsEndpointMenu) EndpointMenu()
            if (motion != null && (endpoint != null || motion.timing.curve.hasRise)) {
                Canvas(Modifier.fillMaxWidth().height(23.dp)) {
                    val line = Path()
                    for (i in 0..100) {
                        val x = i / 100.0
                        val f = motion.timing.fraction(x * duration, duration, motion.frame)
                        val p = Offset((x * size.width).toFloat(), (2 + (1 - f) * (size.height - 4)).toFloat())
                        if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                    }
                    drawPath(line, EditorColors.cyan, style = Stroke(width = 2.dp.toPx()))
                }
                if (motion.timing.curve.hasRise) {
                    TimingMenu("Ramp shape", motion.timing.ramp, RampShape.entries, { it.title }, { update(motion.timing.copy(ramp = it)) }) {
                        Text("Ramp shape", color = Color.White, fontSize = 15.sp)
                    }
                    Phase("Rise", true, motion)
                    if (motion.timing.curve == EffectCurve.riseHoldReturn) Phase("Return", false, motion)
                    Text("Hold B for " + seconds(motion.timing.phases(duration).hold), color = EditorColors.secondary, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun <T> TimingMenu(name: String, current: T, choices: List<T>, title: (T) -> String, choose: (T) -> Unit, heading: @Composable () -> Unit) {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { heading() }
            Box {
                Row(Modifier.heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(50)).clickable { open = true }
                    .padding(horizontal = 12.dp).semantics { contentDescription = "$name, ${title(current)}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text(title(current), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(open, { open = false }) {
                    for (choice in choices) DropdownMenuItem(text = { Text(title(choice)) }, onClick = { open = false; choose(choice) })
                }
            }
        }
    }

    private fun seconds(value: Double): String = rateText(value) + " s"

    @Composable
    private fun Phase(title: String, rise: Boolean, motion: EffectAnimation) {
        val times = motion.timing.phases(duration)
        val value = if (rise) times.rise else times.release
        val maximum = max(0.0, duration - if (rise) times.release else times.rise)
        val step = if (maximum < 0.25) TimelineEditing.frame else 0.1
        fun set(number: Double) {
            val bounded = if (number >= maximum) maximum else min(maximum, max(0.0, swiftRound(number / step) * step))
            update(if (rise) motion.timing.copy(rise = bounded) else motion.timing.copy(release = bounded))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, modifier = Modifier.widthIn(min = 45.dp))
            Slider(value.toFloat(), { set(it.toDouble()) }, Modifier.weight(1f).semantics { contentDescription = "$title time" },
                enabled = maximum > 0, valueRange = 0f..max(0.001, maximum).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent))
            Text(seconds(value), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}
