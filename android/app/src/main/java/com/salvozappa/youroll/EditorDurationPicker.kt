// Port of YouRec/EditorDurationPicker.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Seconds as Swift `formatted(.number.precision(.fractionLength(0...2)))`. */
internal fun secondsText(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded == Math.rint(rounded)) "%.0f".format(java.util.Locale.ROOT, rounded)
    else java.math.BigDecimal(rounded).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** Editor time presets share the same touch targets and wrap in reading order. */
@Composable
fun EditorDurationPicker(
    title: String,
    seconds: Double,
    maximum: Double,
    width: Dp,
    choices: List<Double>,
    identifier: String,
    presetPrefix: String,
    /** The tool's tint (iOS `.tint`; Transition uses orange). */
    tint: Color = EditorColors.accent,
    onToggle: () -> Unit = {},
    select: (Double) -> Unit,
    details: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
            .clickable { onToggle(); expanded = !expanded }.padding(horizontal = 12.dp).testTag(identifier)
            .semantics { contentDescription = title; stateDescription = secondsText(seconds) + " s" },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(secondsText(seconds) + " s", color = Color.White, fontSize = 17.sp, style = tabular)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        if (expanded) {
            val columns = minOf(5, maxOf(1, ((width.value + 6) / (52 + 6)).toInt()))
            for (row in choices.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (value in row) Preset(value, seconds, maximum, presetPrefix, tint, Modifier.weight(1f), select)
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            details()
        }
    }
}

@Composable
private fun Preset(value: Double, seconds: Double, maximum: Double, prefix: String, tint: Color, modifier: Modifier, select: (Double) -> Unit) {
    val selected = EditTime.of(seconds) == EditTime.of(value)
    val enabled = EditorDurationPresets.fits(value, maximum)
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.35f)
        .background(if (selected) tint.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.07f), shape)
        .then(if (selected) Modifier.border(1.dp, tint, shape) else Modifier)
        .clickable(enabled = enabled) { select(value) }.testTag("$prefix." + (if (value == Math.rint(value)) value.toInt().toString() else value.toString()))
        .semantics { contentDescription = secondsText(value) + if (value == 1.0) " second" else " seconds"; if (selected) stateDescription = "Selected" },
        contentAlignment = Alignment.Center) {
        Text(secondsText(value) + " s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = tabular)
    }
}
