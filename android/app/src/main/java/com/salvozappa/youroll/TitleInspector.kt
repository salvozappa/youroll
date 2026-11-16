// Port of YouRec/TitleInspector.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The Text tool's look inspector: Looks, Font, Fill, Outline, Shadow,
// Background and Motion tabs, saved channel styles, favourite fonts and the
// colour control (hex, opacity, palette, recent colours, frame sampling).
//
// Android differences (TODO.md): the iOS system ColorPicker has no Android
// counterpart, so "Pick color" is hue/saturation/brightness sliders; Import
// (FontLibraryView.swift) and "More iPhone fonts…" (UIFontPickerViewController)
// are not ported; Reduce Motion is not consulted before a motion replay; frame
// sampling reads the clip's source frame (crop not applied).
package com.salvozappa.youroll

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.max
import kotlin.math.min

private enum class InspectorTab(val title: String, val icon: ImageVector) {
    looks("Looks", Icons.Outlined.GridView),
    font("Font", Icons.Filled.TextFields),
    fill("Fill", Icons.Outlined.Palette),
    border("Outline", Icons.Outlined.RadioButtonUnchecked),
    shadow("Shadow", Icons.Outlined.Contrast),
    plate("Background", Icons.Filled.Rectangle),
    motion("Motion", Icons.AutoMirrored.Filled.KeyboardTab),
}

@Serializable
private data class SavedLook(@Serializable(with = UUIDSerializer::class) val id: UUID = newUUID(), val name: String, val design: TitleDesign)

/** Swift `@AppStorage("title.…")`: per-device title settings shared across projects. */
private object TitleStorage {
    private fun prefs(context: Context) = context.getSharedPreferences("youroll.titles", Context.MODE_PRIVATE)
    private val looks = ListSerializer(SavedLook.serializer())
    private val strings = ListSerializer(String.serializer())
    private val inks = ListSerializer(TitleInk.serializer())

    fun library(context: Context): List<SavedLook> =
        runCatching { SwiftJSON.decodeFromString(looks, prefs(context).getString("title.savedLooks.v1", null)!!) }.getOrDefault(emptyList())
    fun setLibrary(context: Context, value: List<SavedLook>) =
        prefs(context).edit().putString("title.savedLooks.v1", SwiftJSON.encodeToString(looks, value)).apply()
    fun favorites(context: Context): List<String> =
        runCatching { SwiftJSON.decodeFromString(strings, prefs(context).getString("title.favoriteFonts.v1", null)!!) }.getOrDefault(emptyList())
    fun setFavorites(context: Context, value: List<String>) =
        prefs(context).edit().putString("title.favoriteFonts.v1", SwiftJSON.encodeToString(strings, value)).apply()
    fun recents(context: Context): List<TitleInk> =
        runCatching { SwiftJSON.decodeFromString(inks, prefs(context).getString("title.recentColors.v1", null)!!) }.getOrDefault(emptyList())
    fun setRecents(context: Context, value: List<TitleInk>) =
        prefs(context).edit().putString("title.recentColors.v1", SwiftJSON.encodeToString(inks, value)).apply()
}

private val inspectorCyan = Color(0xFF64D2FF)

@Composable
private fun inspectorSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f))

@Composable
fun TitleInspector(
    design: TitleDesign,
    setDesign: (TitleDesign) -> Unit,
    motion: TitleMotion,
    setMotion: (TitleMotion) -> Unit,
    sample: String,
    prefix: String,
    frame: suspend () -> Bitmap?,
    replay: ((Boolean) -> Unit)?,
    changeSection: () -> Unit = {},
    pauseForFonts: () -> Unit = {},
) {
    val context = LocalContext.current
    remember { TitleTypeface.install(context) }
    var tab by remember { mutableStateOf(InspectorTab.looks) }
    var exitSelected by remember { mutableStateOf(false) }
    var savePrompt by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var lastGradient by remember { mutableStateOf(TitleGradient.gallery[0].second) }
    var deleting by remember { mutableStateOf<SavedLook?>(null) }
    var library by remember { mutableStateOf(TitleStorage.library(context)) }
    var favorites by remember { mutableStateOf(TitleStorage.favorites(context)) }
    val demo = if (sample.isEmpty()) "Your title" else sample.take(48)

    @Composable fun slider(title: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double = 0.01, set: (Double) -> Unit) {
        Column {
            Row {
                Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${Math.round(value * 100)}%", color = EditorColors.secondary, fontSize = 15.sp, style = tabular)
            }
            // Stepped like Swift's Slider(step:), without Material's tick marks.
            Slider(value.toFloat(), { set(min(range.endInclusive, max(range.start, Math.round(it / step) * step))) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(), colors = inspectorSliderColors(),
                modifier = Modifier.semantics { contentDescription = title })
        }
    }

    @Composable fun color(title: String, ink: TitleInk, set: (TitleInk) -> Unit) = TitleColorControl(title, ink, set, frame)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = max(1, ((maxWidth.value + 6) / (82 + 6)).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in InspectorTab.entries.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (value in row) {
                            val selected = tab == value
                            Column(Modifier.weight(1f).heightIn(min = 46.dp)
                                .background(if (selected) inspectorCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                                .clickable { changeSection(); tab = value }
                                .semantics { contentDescription = value.title; if (selected) stateDescription = "Selected" }
                                .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(value.icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text(value.title, color = Color.White, fontSize = 12.sp)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
        when (tab) {
            InspectorTab.looks -> {
                @Composable fun look(label: String, value: TitleDesign, modifier: Modifier) {
                    val applied = design.applyingLook(value)
                    Column(modifier.heightIn(min = 58.dp).background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                        .clickable { setDesign(applied) }.padding(8.dp).semantics { contentDescription = label },
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TitleLookSpecimen(demo, applied)
                        Text(label, color = EditorColors.secondary, fontSize = 12.sp)
                    }
                }
                if (library.isNotEmpty()) {
                    Text("My styles", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    for (entry in library) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            look(entry.name, entry.design, Modifier.weight(1f))
                            Box(Modifier.size(44.dp).clickable { deleting = entry }.semantics { contentDescription = "Delete " + entry.name },
                                contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Delete, null, tint = EditorColors.accent) }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = max(1, ((maxWidth.value + 8) / (115 + 8)).toInt())
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in TitleDesign.looks.chunked(columns)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((label, value) in row) look(label, value, Modifier.weight(1f))
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Row(Modifier.heightIn(min = 44.dp).clickable { name = ""; savePrompt = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BookmarkBorder, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save as channel style", color = EditorColors.accent, fontSize = 17.sp)
                }
            }
            InspectorTab.font -> {
                Row(Modifier.heightIn(min = 44.dp).alpha(0.4f).semantics { contentDescription = "Import fonts, not yet available" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Download, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Import", color = EditorColors.accent, fontSize = 17.sp)
                        Text("Not yet available", color = EditorColors.secondary, fontSize = 11.sp)
                    }
                }
                if (TitleTypeface.missingOnDevice(design.face)) {
                    Text("Using a fallback for " + TitleTypeface.label(design.face) + ". Import the original font to restore it.", color = Color.White, fontSize = 13.sp)
                }
                val listed = TitleTypeface.families.map { it.id }
                val extra = if (design.face in listed) emptyList() else listOf(design.face)
                val ordered = (favorites.filter { !it.startsWith("import:") || it == design.face } + extra + listed).distinct()
                fun chooseFace(id: String) {
                    val family = TitleTypeface.families.firstOrNull { it.id == id }
                    var next = design
                    if (family != null && next.weight !in family.weights) next = next.copy(weight = family.weights[0])
                    next = next.copy(face = id)
                    if (family?.slanted == null) next = next.copy(italic = false)
                    setDesign(next)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (id in ordered) {
                        val label = TitleTypeface.label(id)
                        val family = TitleTypeface.families.firstOrNull { it.id == id }
                        var specimen = design.copy(face = id)
                        if (family != null && specimen.weight !in family.weights) specimen = specimen.copy(weight = family.weights[0])
                        val favorite = id in favorites
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).heightIn(min = 44.dp).clickable { chooseFace(id) }
                                .semantics { contentDescription = label; if (design.face == id) stateDescription = "Selected" },
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    FontSpecimen(demo, specimen)
                                    Text(label, color = EditorColors.secondary, fontSize = 12.sp)
                                }
                                if (design.face == id) Icon(Icons.Filled.Check, null, tint = inspectorCyan)
                            }
                            Box(Modifier.size(44.dp).clickable {
                                favorites = if (favorite) favorites.filter { it != id } else favorites + id
                                TitleStorage.setFavorites(context, favorites)
                            }.semantics { contentDescription = (if (favorite) "Unfavorite " else "Favorite ") + label }, contentAlignment = Alignment.Center) {
                                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = EditorColors.accent)
                            }
                        }
                    }
                }
                val choice = TitleTypeface.families.firstOrNull { it.id == design.face }
                val weights = choice?.weights ?: emptyList()
                if (weights.size > 1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = true }.semantics { contentDescription = "Weight, " + weightName(design.weight) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Weight", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Text(weightName(design.weight), color = EditorColors.accent, fontSize = 17.sp)
                        }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            for (w in weights) DropdownMenuItem(text = { Text(weightName(w)) }, onClick = { open = false; setDesign(design.copy(weight = w)) })
                        }
                    }
                }
                if (choice?.slanted != null) ToggleRow("Italic", design.italic) { setDesign(design.copy(italic = it)) }
                Segmented(TitleAlignment.entries.map { it.name.replaceFirstChar(Char::uppercase) to it }, design.alignment, "Alignment") { setDesign(design.copy(alignment = it)) }
                slider("Letter spacing", design.tracking, -0.05..0.25) { setDesign(design.copy(tracking = it)) }
                slider("Line spacing", design.lineSpacing, 0.0..0.8) { setDesign(design.copy(lineSpacing = it)) }
            }
            InspectorTab.fill -> {
                Segmented(listOf("Solid" to false, "Gradient" to true), design.gradient != null, "Fill") { enabled ->
                    if (enabled) setDesign(design.copy(gradient = lastGradient))
                    else { design.gradient?.let { lastGradient = it }; setDesign(design.copy(gradient = null)) }
                }
                val g = design.gradient
                if (g != null) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = max(1, ((maxWidth.value + 8) / (78 + 8)).toInt())
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (row in TitleGradient.gallery.chunked(columns)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((label, value) in row) {
                                        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { setDesign(design.copy(gradient = value)); lastGradient = value }
                                            .semantics { contentDescription = label; if (g == value) stateDescription = "Selected" },
                                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Box(Modifier.fillMaxWidth().height(29.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                                Canvas(Modifier.fillMaxSize()) {
                                                    val (a, b) = value.axis(CGSize(size.width.toDouble(), size.height.toDouble()))
                                                    drawRect(Brush.linearGradient(listOf(Color(value.first.argb), Color(value.last.argb)),
                                                        Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat())))
                                                }
                                                if (g == value) Icon(Icons.Filled.CheckCircle, null, tint = Color.White,
                                                    modifier = Modifier.size(18.dp).background(Color.Black, CircleShape))
                                            }
                                            Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
                                        }
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    color("First color", g.first) { setDesign(design.copy(gradient = (design.gradient ?: g).copy(first = it))) }
                    color("Second color", g.last) { setDesign(design.copy(gradient = (design.gradient ?: g).copy(last = it))) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Angle", color = Color.White, fontSize = 15.sp)
                        Slider(g.angle.toFloat(), { setDesign(design.copy(gradient = (design.gradient ?: g).copy(angle = Math.round(it).toDouble()))) },
                            valueRange = 0f..360f, colors = inspectorSliderColors(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Gradient angle" })
                        Text("${g.angle.toInt()}°", color = Color.White, fontSize = 15.sp, style = tabular)
                    }
                } else {
                    color("Text color", design.fill) { setDesign(design.copy(fill = it)) }
                }
            }
            InspectorTab.border -> {
                ToggleRow("Letter outline", design.outlined) { setDesign(design.copy(outlined = it)) }
                if (design.outlined) {
                    color("Outline color", design.border) { setDesign(design.copy(border = it)) }
                    slider("Width", design.borderWidth, 0.01..0.20, step = 0.005) { setDesign(design.copy(borderWidth = it)) }
                }
            }
            InspectorTab.shadow -> {
                ToggleRow("Drop shadow", design.shadow) { setDesign(design.copy(shadow = it)) }
                if (design.shadow) {
                    color("Shadow color", design.shade) { setDesign(design.copy(shade = it)) }
                    slider("Blur", design.shadowBlur, 0.0..0.6) { setDesign(design.copy(shadowBlur = it)) }
                    slider("Offset", design.shadowOffset, -0.5..0.5) { setDesign(design.copy(shadowOffset = it)) }
                }
            }
            InspectorTab.plate -> {
                Segmented(listOf("None" to TitlePlate.off, "One box" to TitlePlate.block, "Each line" to TitlePlate.lines), design.plate, "Background") {
                    setDesign(design.copy(plate = it))
                }
                if (design.plate != TitlePlate.off) {
                    color("Box color", design.backing) { setDesign(design.copy(backing = it)) }
                    slider("Padding", design.padding, 0.0..0.8) { setDesign(design.copy(padding = it)) }
                    slider("Corner radius", design.radius, 0.0..0.8) { setDesign(design.copy(radius = it)) }
                }
            }
            InspectorTab.motion -> {
                Segmented(listOf("Entrance" to false, "Exit" to true), exitSelected, "Animation end") { exitSelected = it }
                val move = if (exitSelected) motion.exit else motion.entrance
                fun setMove(value: TitleMove) = setMotion(if (exitSelected) motion.copy(exit = value) else motion.copy(entrance = value))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = max(1, ((maxWidth.value + 8) / (76 + 8)).toInt())
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in TitleMove.entries.chunked(columns)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (mode in row) {
                                    val selected = move == mode
                                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                                        .background(if (selected) inspectorCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                                        .clickable { setMove(mode); if (mode != TitleMove.none) replay?.invoke(exitSelected) }
                                        .semantics { contentDescription = mode.name.replaceFirstChar(Char::uppercase); if (selected) stateDescription = "Selected" },
                                        contentAlignment = Alignment.Center) {
                                        Text(mode.name.replaceFirstChar(Char::uppercase), color = Color.White, fontSize = 15.sp)
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                if (move == TitleMove.drift || move == TitleMove.wipe) {
                    val direction = (if (exitSelected) motion.outDirection else motion.inDirection) ?: if (move == TitleMove.drift) TitleDirection.up else TitleDirection.right
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = true }
                            .semantics { contentDescription = "Direction, " + direction.name }, verticalAlignment = Alignment.CenterVertically) {
                            Text("Direction", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Text(direction.name.replaceFirstChar(Char::uppercase), color = EditorColors.accent, fontSize = 17.sp)
                        }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            for (d in TitleDirection.entries) DropdownMenuItem(text = { Text(d.name.replaceFirstChar(Char::uppercase)) }, onClick = {
                                open = false
                                setMotion(if (exitSelected) motion.copy(outDirection = d) else motion.copy(inDirection = d))
                            })
                        }
                    }
                }
                if (move != TitleMove.none) {
                    val seconds = if (exitSelected) motion.outSeconds else motion.inSeconds
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Duration", color = Color.White, fontSize = 15.sp)
                        Slider(seconds.toFloat(), { value ->
                            val stepped = Math.round(value / 0.05) * 0.05
                            setMotion(if (exitSelected) motion.copy(outSeconds = stepped) else motion.copy(inSeconds = stepped))
                        }, valueRange = 0.05f..5f, colors = inspectorSliderColors(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Animation duration" })
                        Text("%.2f s".format(java.util.Locale.ROOT, seconds), color = Color.White, fontSize = 15.sp, style = tabular)
                    }
                    if (replay != null) {
                        Row(Modifier.heightIn(min = 44.dp).clickable { replay(exitSelected) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = EditorColors.accent)
                            Spacer(Modifier.width(6.dp))
                            Text(if (exitSelected) "Preview exit" else "Preview entrance", color = EditorColors.accent, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
    }
    if (savePrompt) {
        val trimmed = name.trim()
        val valid = trimmed.isNotEmpty() && name.length <= 60 && library.none { it.name.equals(trimmed, ignoreCase = true) }
        AlertDialog(onDismissRequest = { savePrompt = false }, title = { Text("Save channel style") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saves the font and appearance across projects. Outline and motion are set on each overlay.")
                    OutlinedTextField(name, { name = it }, placeholder = { Text("Style name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({
                library = listOf(SavedLook(name = trimmed, design = design)) + library
                TitleStorage.setLibrary(context, library); savePrompt = false
            }, enabled = valid) { Text("Save") } },
            dismissButton = { TextButton({ savePrompt = false }) { Text("Cancel") } })
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this saved style?") },
            confirmButton = { TextButton({
                library = library.filter { it.id != entry.id }; TitleStorage.setLibrary(context, library); deleting = null
            }) { Text("Delete style", color = EditorColors.accent) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
}

private fun weightName(weight: Int): String =
    mapOf(400 to "Regular", 500 to "Medium", 600 to "Semibold", 700 to "Bold", 800 to "Extra bold")[weight] ?: weight.toString()

@Composable
private fun ToggleRow(title: String, checked: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { set(!checked) }
        .semantics { contentDescription = title; stateDescription = if (checked) "On" else "Off" }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked, set, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158)))
    }
}

/** Swift `Picker(...).pickerStyle(.segmented)`. */
@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, label: String, set: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(9.dp)).padding(2.dp)
        .semantics { contentDescription = label }) {
        for ((title, value) in options) {
            val on = value == selected
            Box(Modifier.weight(1f).heightIn(min = 32.dp).background(if (on) Color.White.copy(alpha = 0.28f) else Color.Transparent, RoundedCornerShape(7.dp))
                .clickable { set(value) }.semantics { contentDescription = title; if (on) stateDescription = "Selected" }, contentAlignment = Alignment.Center) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Swift `TitleLookSpecimen`: the look rendered at 36 points, fitted in a short row. */
@Composable
private fun TitleLookSpecimen(words: String, design: TitleDesign) {
    val image by produceState<Bitmap?>(null, words, design) {
        value = withContext(Dispatchers.Default) { StyledTextLayout(words, design, 36.0, 360.0).image(1.0) }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 32.dp), contentAlignment = Alignment.CenterStart) {
        image?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth().height(32.dp)) }
    }
}

/** A font row's sample: the demo words in that face at 22 points (Swift `Font(TitleTypeface.font(specimen, size: 22))`). */
@Composable
private fun FontSpecimen(words: String, design: TitleDesign) {
    val density = LocalDensity.current.density.toDouble()
    val plain = TitleDesign(face = design.face, weight = design.weight, italic = design.italic, shadow = false)
    val image by produceState<Bitmap?>(null, words, plain, density) {
        value = withContext(Dispatchers.Default) { StyledTextLayout(words.replace('\n', ' '), plain, 22 * density, 100_000.0).image(1.0) }
    }
    Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.CenterStart) {
        image?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) }
    }
}

/** Swift `TitleColorControl`: a disclosure row with a swatch; inside, colour choice, hex, opacity, palette and frame sampling. */
@Composable
private fun TitleColorControl(title: String, ink: TitleInk, set: (TitleInk) -> Unit, frame: suspend () -> Bitmap?) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(TitleStorage.recents(context)) }
    var hex by remember(ink) { mutableStateOf("%06X".format(ink.rgb)) }
    var sampling by remember { mutableStateOf(false) }
    fun remember(c: TitleInk) { recents = (listOf(c) + recents.filter { it != c }).take(8); TitleStorage.setRecents(context, recents) }
    fun select(c: TitleInk) { set(c); remember(c) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { if (expanded) remember(ink); expanded = !expanded }
            .semantics { contentDescription = title + ", color " + "%06X".format(ink.rgb) }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(22.dp).background(Color(ink.argb), CircleShape).border(1.dp, Color.Gray, CircleShape))
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = EditorColors.accent, modifier = Modifier.size(18.dp))
        }
        if (expanded) Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // "Pick color": the iOS system colour picker, here hue/saturation/brightness.
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV((ink.rgb or 0xff000000).toInt(), it) }
            fun fromHSV(h: Float, s: Float, v: Float) {
                val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xffffff
                set(TitleInk.rgb(rgb, (ink.rgba and 255).toInt()))
            }
            for ((label, index) in listOf("Hue" to 0, "Saturation" to 1, "Brightness" to 2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.width(92.dp))
                    Slider(hsv[index], { v -> val next = hsv.copyOf(); next[index] = v; fromHSV(next[0], next[1], next[2]) },
                        valueRange = if (index == 0) 0f..360f else 0f..1f, colors = inspectorSliderColors(),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "$title $label".lowercase().replaceFirstChar(Char::uppercase) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hex", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(hex, { hex = it.uppercase() }, singleLine = true, placeholder = { Text("RRGGBB") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "$title hex" })
                val clean = hex.trim('#', ' ')
                val valid = clean.length == 6 && clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                TextButton({ select(TitleInk.rgb(clean.toLong(16), (ink.rgba and 255).toInt())) }, enabled = valid) { Text("Set", color = if (valid) EditorColors.accent else Color.Gray) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", color = Color.White, fontSize = 15.sp)
                Slider(ink.alpha.toFloat(), { set(TitleInk.rgb(ink.rgb, Math.round(it * 255))) }, valueRange = 0f..1f, colors = inspectorSliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "$title opacity" })
                Text("${Math.round(ink.alpha * 100)}%", color = Color.White, fontSize = 15.sp, style = tabular)
            }
            val palette = TextColor.entries.map { TitleInk.of(it) }
            val swatches = palette + recents.filter { it !in palette }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = max(1, ((maxWidth.value + 2) / (38 + 2)).toInt())
                Column {
                    for (row in swatches.chunked(columns)) {
                        Row {
                            for (c in row) {
                                Box(Modifier.weight(1f).heightIn(min = 44.dp).clickable { select(c) }.semantics { contentDescription = "Color %06X".format(c.rgb) },
                                    contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(24.dp).background(Color(c.argb), CircleShape).border(1.dp, Color.Gray, CircleShape))
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Row(Modifier.heightIn(min = 44.dp).clickable { sampling = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Colorize, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sample video frame", color = EditorColors.accent, fontSize = 17.sp)
            }
        }
    }
    if (sampling) FrameInkPicker(frame, cancel = { sampling = false }) { select(it); sampling = false }
}

/** Swift `FrameInkPicker`: tap a colour in the video frame. */
@Composable
private fun FrameInkPicker(load: suspend () -> Bitmap?, cancel: () -> Unit, choose: (TitleInk) -> Unit) {
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { image = load() ?: throw ProjectError.invalidMedia } catch (e: Exception) { failure = e.message ?: "The frame could not be read." }
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp)) {
                PillButton("Cancel", Modifier.align(Alignment.CenterStart), action = cancel)
                Text("Tap a color in the frame", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bitmap = image
                when {
                    bitmap != null -> Image(bitmap.asImageBitmap(), "Video frame", contentScale = ContentScale.Fit,
                        modifier = Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height).pointerInput(bitmap) {
                            detectTapGestures { p ->
                                val x = min(bitmap.width - 1, max(0, (p.x / size.width * bitmap.width).toInt()))
                                val y = min(bitmap.height - 1, max(0, (p.y / size.height * bitmap.height).toInt()))
                                choose(TitleInk.rgb(bitmap.getPixel(x, y).toLong() and 0xffffff))
                            }
                        })
                    failure != null -> Text(failure!!, color = Color.White, modifier = Modifier.padding(16.dp))
                    else -> Text("Loading frame…", color = Color.White)
                }
            }
        }
    }
}
