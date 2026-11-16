// Port of YouRec/AudioToolsEditor.swift (AudioImportEditor, CensorAudioEditor)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Android: "Choose audio from Files" opens the Storage Access Framework picker
// (ACTION_OPEN_DOCUMENT, audio/*) instead of the iOS `fileImporter`; the
// picked document is copied by `ProjectStore.insertAudio` and never modified.
// Not ported (TODO.md): "Record now" (`VoiceRecordingEditor`, shown disabled),
// the accessibility-size layouts and `AudioToolsDiagnostics` (its checks are
// JVM tests in AudioCensorTests and a device export check).
package com.salvozappa.youroll

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Swift `AudioImportEditor`: add a sound file at the playhead as its own audio layer. */
@Composable
fun AudioImportEditor(store: ProjectStore, projectID: UUID, position: Double, close: () -> Unit, inserted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val progress by store.operationProgress.collectAsState()
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val original = MediaImportStaging.displayName(context, uri)?.takeIf { it.isNotBlank() } ?: "Audio"
        busy = true; name = original; failure = null
        job = scope.launch {
            try {
                val success = store.insertAudio(original, projectID, position) {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("The selected file could not be read.")
                }
                busy = false
                if (success) inserted()
                else { failure = store.error.value ?: "Audio could not be imported."; store.setError(null) }
            } catch (cancelled: CancellationException) {
                busy = false; close()
            }
        }
    }
    BackHandler(enabled = busy) { job?.cancel() }
    Column(Modifier.fillMaxWidth()) {
        SheetBar("Add audio", leading = "Cancel", leadingAction = { if (busy) job?.cancel() else close() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // iOS `Label("At …", systemImage: "playhead")`: that symbol does not exist, so iOS shows the title alone.
            Text("At ${editorTime(position)}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Adds a separate sound layer. Your existing video and audio stay in place.", color = EditorColors.secondary, fontSize = 17.sp)
            if (busy) {
                Text(name, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Adding audio…", color = Color.White, fontSize = 17.sp)
                    LinearProgressIndicator(progress = { progress.toFloat() }, color = EditorColors.mint,
                        modifier = Modifier.fillMaxWidth().testTag("audioImport.progress"))
                }
            } else {
                // Swift "Record now" (VoiceRecordingEditor) is not ported yet.
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(0.4f).background(EditorColors.mint, RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "Record now, not yet available" }.testTag("audioImport.record"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Mic, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Record now", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Not yet available", color = Color.Black, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(EditorColors.mint.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .clickable { failure = null; picker.launch(arrayOf("audio/*")) }.testTag("audioImport.files"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, tint = EditorColors.mint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose audio from Files", color = EditorColors.mint, fontSize = 17.sp)
                }
                Text("WAV, AIFF, M4A, MP3 and other supported audio files. Use Move and Volume to arrange the sound after importing.",
                    color = EditorColors.secondary, fontSize = 12.sp)
            }
            failure?.let { Text(it, color = EditorColors.orange, fontSize = 15.sp, modifier = Modifier.testTag("audioImport.error")) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Swift `CensorAudioEditor`: nudge the marked range by frames, choose the
 * tracks to replace and the tone, audition up to ten seconds around it, then
 * Apply. The preview is a second EditorController playing the candidate edit,
 * so it hears exactly what the export renders.
 */
@Composable
fun CensorAudioEditor(project: Project, folder: File, request: ToolRequest, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val preview = remember { EditorController(context) }
    val scope = rememberCoroutineScope()
    val effectID = remember { request.effectID ?: newUUID() }
    val duration = project.edit.duration
    val initial = remember {
        val target = (request.targets ?: project.edit.timeline.entireSelection(EditInterval.of(request.range.start, request.range.endInclusive)))
        val existing = project.edit.timeline.audio.filter { it.id in target.audio }.flatMap { it.censors }.firstOrNull { it.id == request.effectID }
        target.audio to (existing?.settings ?: AudioCensorSettings())
    }
    var settings by remember { mutableStateOf(initial.second) }
    var selectedAudio by remember { mutableStateOf(initial.first) }
    var start by remember { mutableDoubleStateOf(request.range.start) }
    var end by remember { mutableDoubleStateOf(request.range.endInclusive) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val selectionRange = EditInterval.of(start, end)
    val overlapping = project.edit.timeline.audio.filter { it.interval.intersection(selectionRange) != null }
    val target = TimelineSelection(audio = selectedAudio intersect overlapping.map { it.id }.toSet(), range = selectionRange)
    val range = max(0.0, start - 1).let { lower -> lower..max(lower, min(duration, min(end + 1, lower + 10))) }

    fun state(): EditState {
        var edit = project.edit.copy()
        if (request.effectID != null) edit = edit.removingTool(request)
        edit.timeline.censor(settings, target, effectID)
        return edit
    }
    fun invalidate() {
        job?.cancel(); preview.pause(); dirty = true
        if (busy) { preview.stop(); loaded = false }
        busy = false
    }
    fun finish(action: () -> Unit) { invalidate(); action() }
    fun play() {
        if (busy) { invalidate(); return }
        if (loaded && !dirty && preview.error == null) { preview.togglePlay(); return }
        busy = true
        val candidate = project.copy().also { it.history = listOf(state()); it.historyCursor = 0 }
        val window = range
        job = scope.launch {
            preview.previewRange = window
            preview.seekAfterEdit(window.start, duration)
            preview.load(candidate, folder, includeTimelineDetails = false)
            snapshotFlow { preview.loading }.first { !it }
            busy = false
            if (preview.error == null) { loaded = true; dirty = false; preview.seek(window.start); preview.togglePlay() }
        }
    }
    // Swift shows the editor's own player until the first Play; here the unchanged project, paused at the range.
    DisposableEffect(preview) {
        preview.previewRange = range
        preview.seekAfterEdit(request.range.start, duration)
        preview.load(project, folder, includeTimelineDetails = false)
        onDispose { job?.cancel(); preview.release() }
    }
    fun audioTitle(item: AudioTimelineItem): String {
        val number = project.edit.timeline.audio.indexOfFirst { it.id == item.id }.coerceAtLeast(0) + 1
        val name = project.sources.firstOrNull { it.id == item.sourceID }?.originalName ?: "Video sound"
        return "♪$number · " + name + if (item.muted) " · Muted" else ""
    }
    val mint = EditorColors.mint
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = mint, inactiveTrackColor = Color.White.copy(alpha = 0.2f),
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent, disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray)

    BackHandler { finish(cancel) }
    Box(Modifier.fillMaxSize().background(Color(0.06f, 0.06f, 0.06f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            SheetBar("Censor audio", leading = "Cancel", leadingAction = { finish(cancel) }, trailing = "Apply",
                trailingEnabled = target.audio.isNotEmpty(), trailingBold = true, trailingAction = { finish { apply(state()) } })
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth > maxHeight
                val boxWidth = maxWidth
                val boxHeight = maxHeight
                @Composable fun player(modifier: Modifier) {
                    Column(modifier) {
                        PlayerSurface(preview.player, Modifier.weight(1f).fillMaxWidth(), framing = preview.framing, titles = preview.titles,
                            layers = preview.layers, onPlaybackTap = { play() })
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val title = if (busy) "Cancel preview" else if (preview.playing) "Pause" else "Play preview"
                            Row(Modifier.heightIn(min = 44.dp).clickable { play() }.semantics { contentDescription = title }.testTag("censor.play"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (busy) Icons.Filled.Close else if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = mint)
                                Spacer(Modifier.width(6.dp))
                                Text(title, color = mint, fontSize = 17.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(editorTime(preview.playhead), color = Color.White, fontSize = 12.sp, style = tabular)
                        }
                        Slider(value = min(range.endInclusive, max(range.start, if (loaded) preview.playhead else range.start)).toFloat(),
                            onValueChange = { preview.seekPaused(it.toDouble()) }, valueRange = range.start.toFloat()..max(range.start + 0.01, range.endInclusive).toFloat(),
                            enabled = loaded && !busy && !dirty, colors = sliderColors,
                            modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Censor preview position" }.testTag("censor.seek"))
                    }
                }
                @Composable fun boundary(title: String, value: Double, earlier: Boolean, later: Boolean, change: (Double) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        for ((icon, delta) in listOf(Icons.Filled.Remove to -TimelineEditing.frame, Icons.Filled.Add to TimelineEditing.frame)) {
                            if (delta > 0) Text(editorTime(value), color = Color.White, fontSize = 15.sp, style = tabular,
                                modifier = Modifier.testTag("censor." + title.lowercase()))
                            val enabled = if (delta < 0) earlier else later
                            val label = title + if (delta < 0) " earlier" else " later"
                            Box(Modifier.size(44.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled) { change(delta) }
                                .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                                Icon(icon, null, tint = mint, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                @Composable fun heading(title: String, value: String) {
                    Row {
                        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(value, color = Color.White, fontSize = 17.sp, style = tabular)
                    }
                }
                @Composable fun controls(modifier: Modifier) {
                    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 24.dp)
                        .semantics { contentDescription = "Censor controls" }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Censor range", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Box(Modifier.size(44.dp).clickable { help = true }.semantics { contentDescription = "About audio censoring" }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = mint, modifier = Modifier.size(20.dp))
                                }
                            }
                            boundary("Start", start, start > 0, start + TimelineEditing.frame < end) { delta ->
                                start = max(0.0, min(end - TimelineEditing.frame, start + delta)); invalidate()
                            }
                            boundary("End", end, end - TimelineEditing.frame > start, end < duration) { delta ->
                                end = max(start + TimelineEditing.frame, min(duration, end + delta)); invalidate()
                            }
                            Text("Nudge by one frame to cover the entire word.", color = EditorColors.secondary, fontSize = 12.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sound to replace", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            for (item in overlapping) {
                                val on = item.id in selectedAudio
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("censor.track." + item.id.uuidString), verticalAlignment = Alignment.CenterVertically) {
                                    Text(audioTitle(item), color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Switch(on, { enabled -> selectedAudio = if (enabled) selectedAudio + item.id else selectedAudio - item.id; invalidate() },
                                        colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green),
                                        modifier = Modifier.semantics { contentDescription = audioTitle(item) })
                                }
                            }
                            if (target.audio.isEmpty()) Text("Select an audio part in this range.", color = EditorColors.orange, fontSize = 12.sp)
                            else Text("Unselected tracks keep their sound.", color = EditorColors.secondary, fontSize = 12.sp)
                        }
                        CensorSegmented(CensorWaveform.entries.map { it to it.name.replaceFirstChar { c -> c.uppercase() } }, settings.waveform, "censor.waveform") {
                            settings = settings.copy(waveform = it); invalidate()
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            heading("Frequency", "${settings.frequency.toInt()} Hz")
                            Slider(value = settings.frequency.toFloat(), onValueChange = { value ->
                                val next = (value / 25f).roundToInt() * 25.0
                                if (next != settings.frequency) { settings = settings.copy(frequency = next); invalidate() }
                            }, valueRange = 100f..4000f, colors = sliderColors,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Beep frequency"; stateDescription = "${settings.frequency.toInt()} hertz" }.testTag("censor.frequency"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (frequency in listOf(440.0, 1000.0, 2000.0)) {
                                    val chosen = settings.frequency == frequency
                                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                                        .background(if (chosen) mint.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .border(1.dp, if (chosen) mint else Color.Transparent, RoundedCornerShape(8.dp))
                                        .clickable { settings = settings.copy(frequency = frequency); invalidate() }
                                        .semantics { if (chosen) stateDescription = "Selected" }, contentAlignment = Alignment.Center) {
                                        Text("${frequency.toInt()} Hz", color = if (chosen) mint else Color.White, fontSize = 15.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Envelope", color = Color.White, fontSize = 17.sp)
                            CensorSegmented(listOf(CensorEnvelope.short to "Short fade", CensorEnvelope.soft to "Soft fade", CensorEnvelope.pulse to "Pulsed"),
                                settings.envelope, "censor.envelope") { settings = settings.copy(envelope = it); invalidate() }
                            EnvelopeDrawing(settings, end - start, Modifier.fillMaxWidth().height(34.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            heading("Beep level", "${settings.levelDB.toInt()} dBFS")
                            Slider(value = settings.levelDB.toFloat(), onValueChange = { value ->
                                val next = value.roundToInt().toDouble()
                                if (next != settings.levelDB) { settings = settings.copy(levelDB = next); invalidate() }
                            }, valueRange = -36f..-6f, colors = sliderColors,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Beep peak level"; stateDescription = "${settings.levelDB.toInt()} decibels full scale" }.testTag("censor.level"))
                            Text("Track Volume applies. Where selected tracks overlap, the loudest track carries the beep.", color = EditorColors.secondary, fontSize = 12.sp)
                        }
                        if (busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("censor.preparing")) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Preparing short audio preview…", color = Color.White, fontSize = 15.sp)
                        }
                        preview.error?.let { Text(it, color = EditorColors.orange, fontSize = 15.sp) }
                        Text("Preview includes nearby sound, up to 10 seconds. Speech stays removed during the beep's soft edges and pulse gaps.",
                            color = EditorColors.secondary, fontSize = 12.sp)
                        if (request.effectID != null) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { finish { apply(project.edit.removingTool(request)) } }
                                .semantics { contentDescription = "Remove censor" }.testTag("censor.remove"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Delete, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Remove censor", color = EditorColors.accent, fontSize = 17.sp)
                            }
                        }
                    }
                }
                if (wide) Row(Modifier.fillMaxSize()) {
                    player(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(minOf(400.dp, boxWidth * 0.5f)).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    player(Modifier.fillMaxWidth().height(maxOf(150.dp, boxHeight * 0.36f)))
                    controls(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    if (help) {
        AlertDialog(onDismissRequest = { help = false }, title = { Text("Censor audio") },
            text = { Text("Replace a word or sound with a generated tone. Nudge Start and End to cover it completely, and choose which audio parts to replace. Unselected music or other tracks keep playing.\n\nSine is smooth; triangle and square sound sharper. Frequency sets pitch, Beep level sets the peak before track Volume, and the envelope shapes the tone without revealing speech during fades or pulse gaps.\n\nPlay preview auditions up to ten seconds around the start of the range. Changes wait for another Play tap. Apply saves the edit; Remove or Undo restores the original.") },
            confirmButton = { TextButton({ help = false }) { Text("Done") } })
    }
}

/** iOS segmented `Picker(.segmented)`. */
@Composable
private fun <T> CensorSegmented(options: List<Pair<T, String>>, selected: T, tag: String, choose: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(2.dp).testTag(tag)) {
        for ((value, title) in options) {
            val chosen = value == selected
            Box(Modifier.weight(1f).heightIn(min = 32.dp).background(if (chosen) Color.White.copy(alpha = 0.28f) else Color.Transparent, RoundedCornerShape(7.dp))
                .clickable { choose(value) }.semantics { contentDescription = title; if (chosen) stateDescription = "Selected" },
                contentAlignment = Alignment.Center) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Swift `envelopeDrawing`: the envelope over the first second of the range. */
@Composable
private fun EnvelopeDrawing(settings: AudioCensorSettings, length: Double, modifier: Modifier) {
    Canvas(modifier) {
        val seconds = min(1.0, length)
        val path = Path()
        for (i in 0..160) {
            val x = i / 160.0
            val point = Offset((x * size.width).toFloat(), ((1 - settings.envelopeValue(x * seconds, seconds)) * (size.height - 4) + 2).toFloat())
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, EditorColors.mint.copy(alpha = 0.8f), style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
    }
}
