// Port of YouRec/ColorGradeControls.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The Color LUTs inspector: look name, favourite and import, Strength, local
// highlight recovery (Log footage only, so on Android only a saved setting is
// shown), Highlights, Exposure & white balance, the Built-in / Imported /
// Favorites / Grades library with thumbnails of the paused frame, the LUT's
// expected input and Save grade. Imports come from the system document picker
// (iOS: Files).
package com.salvozappa.youroll

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val libraryCategories = listOf("Built-in", "Imported", "Favorites", "Grades")

@Composable
private fun gradeSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f), disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray,
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

/** A stepped value (Swift `Slider(…, step:)`). */
private fun stepped(value: Float, step: Double, low: Double, high: Double): Double =
    (Math.round(value / step) * step).coerceIn(low, high).let { Math.round(it * 1000) / 1000.0 }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorGradeControls(model: ToolWorkspace) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(if (model.importedLUT != null) "Imported" else "Built-in") }
    var saving by remember { mutableStateOf(false) }
    var gradeName by remember { mutableStateOf("") }
    var balanceOpen by remember { mutableStateOf(false) }
    val selectedID = model.importedLUT?.id ?: model.look.rawValue
    // Swift `.fileImporter(allowedContentTypes: [.cube])`: any document; the CUBE reader rejects others.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (data, name) = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "LUT"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(1 shl 16)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                            if (buffer.size() > CubeLUT.maximumBytes) throw LUTError("Choose a .cube file smaller than 32 MB.")
                        }
                        buffer.toByteArray()
                    } ?: throw LUTError("The file could not be read.")
                    bytes to name
                }
                model.importLUT(data, name)
                category = "Imported"
            } catch (failure: Exception) { model.colorError = failure.message }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.importedLUT?.name ?: model.look.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            val favorite = selectedID in model.library.favorites
            Box(Modifier.size(44.dp).clickable { model.toggleFavorite(selectedID) }
                .semantics { contentDescription = "Favorite look"; stateDescription = if (favorite) "On" else "Off" }, contentAlignment = Alignment.Center) {
                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = EditorColors.accent)
            }
            Box(Modifier.size(44.dp).clickable { importer.launch(arrayOf("*/*")) }.semantics { contentDescription = "Import LUT" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.FileDownload, null, tint = EditorColors.accent)
            }
        }
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Strength", color = Color.White, fontSize = 12.sp)
            val enabled = !(model.look == ColorLook.natural && model.importedLUT == null)
            Slider(model.amount.toFloat(), { model.amount = stepped(it, 0.01, 0.0, 1.0) }, enabled = enabled, valueRange = 0f..1f, colors = gradeSliderColors(),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Look strength" })
            Text("${(model.amount * 100).toInt()}%", color = Color.White, fontSize = 12.sp, style = tabular, textAlign = TextAlign.End, modifier = Modifier.width(35.dp))
        }
        val logSources = model.logSourceCount ?: 0
        if (logSources > 0 || model.localRecovery > 0) {
            Column(Modifier.alpha(if (logSources == 0) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Local highlight recovery", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(enabled = logSources > 0) { model.localRecovery = 1.0 }
                        .semantics { contentDescription = "Use natural highlight recovery" }, contentAlignment = Alignment.Center) {
                        Text("Natural", color = EditorColors.accent, fontSize = 15.sp)
                    }
                    val percent = "${(model.localRecovery * 100).roundToInt()}%"
                    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(enabled = logSources > 0) { model.localRecovery = 0.0 }
                        .semantics { contentDescription = "Reset local highlight recovery"; stateDescription = percent }, contentAlignment = Alignment.Center) {
                        Text(percent, color = EditorColors.accent, fontSize = 15.sp, style = tabular)
                    }
                }
                Slider(model.localRecovery.toFloat(), { model.localRecovery = stepped(it, 0.01, 0.0, 1.0) }, enabled = logSources > 0, colors = gradeSliderColors(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Local highlight recovery" })
                Text(if (logSources > 0) "Compresses bright areas while retaining texture. Applies only to Log footage."
                    else "Available for Log footage. This grade’s recovery setting is saved but does not affect this footage.",
                    color = EditorColors.secondary, fontSize = 12.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Highlights", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                val value = "%+.0f".format(java.util.Locale.ROOT, model.highlights * 100)
                Box(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable { model.highlights = 0.0 }
                    .semantics { contentDescription = "Reset highlights"; stateDescription = value }, contentAlignment = Alignment.Center) {
                    Text(value, color = EditorColors.accent, fontSize = 15.sp, style = tabular)
                }
            }
            Slider(model.highlights.toFloat(), { model.highlights = stepped(it, 0.01, -1.0, 1.0) }, valueRange = -1f..1f, colors = gradeSliderColors(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Highlights" })
            Text("Adjusts bright tones before the look. Move left to lower them.", color = EditorColors.secondary, fontSize = 12.sp)
        }
        // Swift `DisclosureGroup("Exposure & white balance")`, collapsed initially.
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { balanceOpen = !balanceOpen }
                .semantics { contentDescription = "Exposure & white balance" }, verticalAlignment = Alignment.CenterVertically) {
                Text("Exposure & white balance", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(if (balanceOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = EditorColors.accent)
            }
            if (balanceOpen) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Adjustment("Exposure", model.exposure, -4.0..4.0, " EV") { model.exposure = it }
                Adjustment("Warmth", model.warmth, -1.0..1.0) { model.warmth = it }
                Adjustment("Tint", model.tint, -1.0..1.0) { model.tint = it }
                Box(Modifier.heightIn(min = 44.dp).clickable { model.exposure = 0.0; model.warmth = 0.0; model.tint = 0.0 }, contentAlignment = Alignment.CenterStart) {
                    Text("Reset balance", color = EditorColors.accent, fontSize = 17.sp)
                }
            }
        }
        // Swift: a segmented `Picker("Library")`.
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(9.dp)).padding(2.dp)
            .semantics { contentDescription = "Library" }) {
            for (name in libraryCategories) {
                val selected = category == name
                Box(Modifier.weight(1f).heightIn(min = 32.dp).background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(7.dp))
                    .clickable { category = name }.semantics { this.selected = selected; contentDescription = name }, contentAlignment = Alignment.Center) {
                    Text(name, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        if (category == "Grades") {
            if (model.library.grades.isEmpty()) Text("Save a look and its color balance to reuse on another clip.", color = EditorColors.secondary, fontSize = 12.sp)
            for (grade in model.library.grades) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { model.selectGrade(grade.settings.copy(id = model.colorSettings.id)) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(grade.name, color = EditorColors.accent, fontSize = 17.sp)
                }
            }
        } else {
            data class Tile(val title: String, val id: String, val action: () -> Unit)
            val tiles = mutableListOf<Tile>()
            if (category != "Imported") for (look in ColorLook.entries) {
                if (category == "Favorites" && look.rawValue !in model.library.favorites) continue
                tiles += Tile(look.title, look.rawValue) { model.importedLUT = null; model.look = look }
            }
            if (category != "Built-in") for (lut in model.library.imported) {
                if (category == "Favorites" && lut.id !in model.library.favorites) continue
                tiles += Tile(lut.name, lut.id) { model.selectGrade(model.colorSettings.copy(imported = lut)) }
            }
            // Swift `LazyVGrid(columns: [GridItem(.adaptive(minimum: 85), spacing: 8)], spacing: 10)`.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = maxOf(1, ((maxWidth.value + 8) / (85 + 8)).toInt())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in tiles.chunked(columns)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (tile in row) LookTile(model, tile.title, tile.id, selectedID == tile.id, Modifier.weight(1f), tile.action)
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (category == "Imported" && model.library.imported.isEmpty()) {
                Box(Modifier.heightIn(min = 44.dp).clickable { importer.launch(arrayOf("*/*")) }, contentAlignment = Alignment.CenterStart) {
                    Text("Import a .cube LUT from Files", color = EditorColors.accent, fontSize = 17.sp)
                }
            }
            if (category == "Favorites" && model.library.favorites.isEmpty()) {
                Text("Tap the star beside a look’s name to keep it here.", color = EditorColors.secondary, fontSize = 12.sp)
            }
        }
        model.importedLUT?.let { lut ->
            // Swift `Picker("LUT expects")` (menu style).
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = true }.semantics { contentDescription = "LUT expects, ${lut.input.title}" },
                verticalAlignment = Alignment.CenterVertically) {
                Text("LUT expects", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Box {
                    Text(lut.input.title, color = EditorColors.accent, fontSize = 17.sp)
                    DropdownMenu(open, { open = false }) {
                        for (input in LUTInput.entries) DropdownMenuItem({ Text(input.title) }, { open = false; model.setLUTInput(input) })
                    }
                }
            }
            Text("Choose the input specified by the LUT’s author. Output must be Rec.709. Log-to-Rec.709 LUTs include their own camera conversion.",
                color = EditorColors.secondary, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .clickable { gradeName = model.colorSettings.title; saving = true }.semantics { contentDescription = "Save grade" },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BookmarkBorder, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save grade", color = EditorColors.accent, fontSize = 17.sp)
        }
        model.colorError?.let { message ->
            Row(verticalAlignment = Alignment.Top) {
                Text(message, color = EditorColors.orange, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clickable { model.colorError = null }.semantics { contentDescription = "Dismiss LUT message" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, null, tint = Color.White)
                }
            }
        }
    }
    if (saving) {
        AlertDialog(onDismissRequest = { saving = false }, title = { Text("Save grade") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saves the look, strength, highlight adjustments, exposure and white balance for other clips and projects.")
                    OutlinedTextField(gradeName, { gradeName = it }, singleLine = true, placeholder = { Text("Name") })
                }
            },
            confirmButton = { TextButton({ saving = false; model.saveGrade(gradeName) }) { Text("Save") } },
            dismissButton = { TextButton({ saving = false }) { Text("Cancel") } })
    }
}

/** Swift `tile(_:id:action:)`: the look applied to the paused frame, its name, a cyan frame when selected; long press toggles the favourite. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LookTile(model: ToolWorkspace, title: String, id: String, selected: Boolean, modifier: Modifier, action: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cyan = EditorColors.cyan
    Box(modifier) {
        Column(Modifier.fillMaxWidth().background(if (selected) cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) cyan else Color.White.copy(alpha = 0.15f), RoundedCornerShape(7.dp))
            .combinedClickable(onClick = action, onLongClick = { menu = true })
            .padding(3.dp).semantics { contentDescription = title; this.selected = selected },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).background(Color.White.copy(alpha = 0.06f)).clipToBounds(), contentAlignment = Alignment.Center) {
                val image = model.lookThumbnails[id]
                if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.Photo, null, tint = EditorColors.secondary)
            }
            Text(title, color = Color.White, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (id in model.library.favorites) "Remove favorite" else "Add favorite") }, { menu = false; model.toggleFavorite(id) })
        }
    }
}

/** Swift `adjustment(_:value:range:unit:)`: title and signed value over a 0.05-step slider. */
@Composable
private fun Adjustment(title: String, value: Double, range: ClosedFloatingPointRange<Double>, unit: String = "", change: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text("%+.2f".format(java.util.Locale.ROOT, value) + unit, color = Color.White, fontSize = 17.sp, style = tabular)
        }
        Slider(value.toFloat(), { change(stepped(it, 0.05, range.start, range.endInclusive)) }, valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            colors = gradeSliderColors(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
    }
}
