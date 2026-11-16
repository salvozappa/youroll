// Port of YouRec/QuickVolumeControl.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The button only opens the inspector. Direct gain manipulation belongs to the
// waveform, where its amplitude can respond under the user's finger.
@Composable
fun QuickVolumeControl(value: Double?, enabled: Boolean, tap: () -> Unit) {
    Column(Modifier.size(56.dp, 44.dp).alpha(if (enabled) 1f else 0.35f)
        .background(Color(0.12f, 0.12f, 0.12f), RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = tap)
        .semantics {
            contentDescription = "Volume"
            stateDescription = value?.let { "%+.1f dB".format(it) } ?: "Mixed"
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text("Volume", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
