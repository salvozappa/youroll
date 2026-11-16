// Port of YouRec/TrackEditors.swift (partial: BookmarkHelpView, FullEditorPreview,
// AudioFadeRow, AudioToolEditor for the Volume and Enhance audio tools, and
// PlacementEditor with its still surface and edge-crop handles)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Not ported (TODO.md): headroom/clipping analysis, the exact-time
// entry alert of a fade; in PlacementEditor the animation controls
// (EffectAnimationControls), titles in the drag stills, VoiceOver adjustable crop edges.
package com.salvozappa.youroll

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import java.io.File
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BookmarkHelpView(dismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = dismiss, containerColor = Color(0.06f, 0.06f, 0.06f), dragHandle = { SheetGrabber() }) {
        SheetBar("Marks", trailing = "Got it", trailingBold = true, trailingAction = dismiss)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.BookmarkBorder, null, tint = EditorColors.orange, modifier = Modifier.size(40.dp))
            Text("No marks yet", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("While recording, tap Mark to remember a moment in your video—for example, a mistake to cut or a take to keep.",
                color = Color.White, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Use the previous and next bookmark buttons in the editor to jump back to those moments.",
                color = EditorColors.secondary, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun FullEditorPreview(editor: EditorController, duration: Double, bookmarks: List<ResolvedBookmark>, close: () -> Unit, bookmarkHelp: () -> Unit,
                      addBookmark: (() -> Unit)? = null) {
    var controls by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var poke by remember { mutableStateOf(0) }
    var speedMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    fun scheduleControls() { controls = true; poke += 1 }
    LaunchedEffect(poke, editor.playing, scrubbing) {
        if (!editor.playing || scrubbing) return@LaunchedEffect
        delay(2500)
        if (editor.playing && !scrubbing) controls = false
    }
    fun jump(forward: Boolean) {
        if (bookmarks.isEmpty()) { editor.pause(); bookmarkHelp(); return }
        val mark = if (forward) bookmarks.firstOrNull { it.time.seconds > editor.playhead + 0.02 } else bookmarks.lastOrNull { it.time.seconds < editor.playhead - 0.02 }
        mark?.let { editor.seekPaused(it.time.seconds) }; scheduleControls()
    }
    BackHandler(onBack = close)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(editor.player, Modifier.fillMaxSize(), framing = editor.framing, titles = editor.titles, layers = editor.layers, captions = editor.subtitles, onPlaybackTap = { editor.togglePlay(); scheduleControls() })
        if (!editor.showsPicture) Box(Modifier.fillMaxSize().background(Color.Black))
        if (controls) Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Box(Modifier.size(52.dp, 44.dp).clickable { speedMenu = true }, contentAlignment = Alignment.Center) {
                        Text(speedLabel(editor.playbackSpeed), color = Color.White, fontSize = 17.sp)
                    }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        for (speed in listOf(1.0, 1.5, 2.0, 2.5, 3.0)) DropdownMenuItem(text = { Text(speedLabel(speed)) }, onClick = { editor.setPlaybackSpeed(speed); speedMenu = false })
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.heightIn(min = 44.dp).widthIn(min = 60.dp).clickable(onClick = close), contentAlignment = Alignment.Center) {
                    Text("Done", color = EditorColors.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                Slider(value = editor.playhead.toFloat().coerceIn(0f, max(0.001, duration).toFloat()), valueRange = 0f..max(0.001, duration).toFloat(),
                    onValueChange = { if (!scrubbing) { scrubbing = true; editor.setScrubbing(true) }; editor.seek(it.toDouble()); scheduleControls() },
                    onValueChangeFinished = { scrubbing = false; editor.setScrubbing(false); scheduleControls() },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent),
                    modifier = Modifier.semantics { contentDescription = "Full preview position" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(editorTime(editor.playhead), color = Color.White, fontSize = 12.sp, style = tabular)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(44.dp).clickable { jump(false) }, contentAlignment = Alignment.Center) { Text("‹", color = Color.White); Icon(Icons.Outlined.BookmarkBorder, "Previous bookmark", tint = Color.White) }
                    ChangeNavigationButton(false, editor.navigation.destination(editor.stationaryPlayhead, false) != null) { editor.jumpChange(false, it); scheduleControls() }
                    Box(Modifier.size(52.dp, 44.dp).clickable { editor.togglePlay(); scheduleControls() }, contentAlignment = Alignment.Center) {
                        Icon(if (editor.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (editor.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    ChangeNavigationButton(true, editor.navigation.destination(editor.stationaryPlayhead, true) != null) { editor.jumpChange(true, it); scheduleControls() }
                    Box(Modifier.size(44.dp).clickable { jump(true) }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.BookmarkBorder, "Next bookmark", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        Box(Modifier.size(44.dp).clickable { bookmarkMenu = true }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.BookmarkBorder, "Bookmarks", tint = Color.White) }
                        DropdownMenu(bookmarkMenu, { bookmarkMenu = false }) {
                            if (addBookmark != null) DropdownMenuItem(text = { Text("Add bookmark here") }, onClick = { bookmarkMenu = false; addBookmark() })
                            for (mark in bookmarks) DropdownMenuItem(text = { Text(editorTime(mark.time.seconds) + if (mark.bookmark.label.isEmpty()) "" else " · " + mark.bookmark.label) },
                                onClick = { bookmarkMenu = false; editor.seekPaused(mark.time.seconds) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioFadeRow(edge: AudioFadeEdge, target: String, seconds: Double, limit: Double, playing: Boolean, change: (Double) -> Unit, listen: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(edge.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(target, color = EditorColors.secondary, fontSize = 12.sp)
            }
            Text(if (seconds == 0.0) "Off" else formatSeconds(seconds) + " s", color = EditorColors.accent, fontSize = 17.sp, style = tabular,
                modifier = Modifier.heightIn(min = 44.dp).padding(top = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).clickable(onClick = listen).semantics { contentDescription = (if (playing) "Pause " else "Preview ") + edge.title.lowercase() },
                contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = EditorColors.accent)
            }
            // Give short fades useful travel even when a take is many minutes long.
            Slider(value = (ln1p(seconds) / ln1p(max(0.001, limit))).toFloat().coerceIn(0f, 1f), onValueChange = { value ->
                val raw = expm1(value * ln1p(limit))
                change(if (value >= 1f) limit else min(limit, swiftRound(raw * 10) / 10))
            }, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.mint), modifier = Modifier.weight(1f).semantics { contentDescription = edge.title })
        }
    }
}

private fun formatSeconds(value: Double): String {
    val rounded = swiftRound(value * 1000) / 1000
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString().trimEnd('0')
}

/** Swift `AudioToolEditor` in its Volume mode: level, mute, reset and edge fades of the selected sound. */
@Composable
fun AudioToolEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit) {
    if (request.tool == VideoTool.enhanceAudio) { EnhanceAudioEditor(project, folder, request, position, cancel, apply); return }
    val context = LocalContext.current
    val preview = remember { EditorController(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    val setup = remember(request) {
        val targets = (request.targets ?: project.edit.timeline.entireSelection(EditInterval.of(request.range.start, request.range.endInclusive))).copy(video = emptySet())
        val scoped = project.edit.timeline.scoped(targets)
        val base = project.edit.copy().also { it.timeline = scoped.timeline }
        val target = scoped.selection
        val original = project.edit.timeline.audio
        val endpoints = HashMap<AudioFadeEdge, Pair<Int, String>>()
        for (edge in AudioFadeEdge.entries) {
            val i = edge.index(base.timeline.audio, target.audio) ?: continue
            val part = base.timeline.audio[i]
            val sourceIndex = original.indexOfFirst { it.id == part.id }.takeIf { it >= 0 }
                ?: original.indexOfFirst { it.occurrenceID == part.occurrenceID && it.interval.intersection(part.interval) != null }.takeIf { it >= 0 }
            val number = sourceIndex?.let { "Audio ${it + 1} · " } ?: ""
            endpoints[edge] = i to number + editorTime(if (edge == AudioFadeEdge.fadeIn) part.start.seconds else part.end.seconds)
        }
        Triple(base, target, endpoints)
    }
    val (base, target, fadeTargets) = setup
    val items = base.timeline.audio.filter { it.id in target.audio }
    val mixedVolume = AudioGainGrid.common(items) == null
    val volumeBounds = (items.minOfOrNull { it.volumeDB } ?: 0.0)..(items.maxOfOrNull { it.volumeDB } ?: 0.0)
    val mixedMute = items.any { it.muted } && items.any { !it.muted }
    var volume by remember { mutableStateOf(AudioGainGrid.common(items) ?: items.maxOfOrNull { it.volumeDB } ?: 0.0) }
    var volumeChosen by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(items.isNotEmpty() && items.all { it.muted }) }
    var muteChosen by remember { mutableStateOf(false) }
    val fadeEdits = remember { mutableStateMapOf<AudioFadeEdge, Double>() }
    var listeningEdge by remember { mutableStateOf<AudioFadeEdge?>(null) }
    var revision by remember { mutableStateOf(0) }

    fun state(): EditState {
        val state = base.copy()
        state.timeline.audio = state.timeline.audio.map { item ->
            if (item.id !in target.audio) item
            else item.copy(volumeDB = if (volumeChosen) volume else item.volumeDB, muted = if (muteChosen) muted else item.muted)
        }
        for ((edge, seconds) in fadeEdits) {
            val index = fadeTargets[edge]?.first ?: continue
            state.timeline.audio = state.timeline.audio.mapIndexed { i, item -> if (i == index) item.settingFade(edge, seconds) else item }
        }
        return state
    }
    fun listeningRange(): ClosedFloatingPointRange<Double> {
        val edge = listeningEdge
        val endpoint = edge?.let { fadeTargets[it] }
        if (edge != null && endpoint != null) {
            val item = base.timeline.audio[endpoint.first]
            val length = (fadeEdits[edge] ?: item.fadeSeconds(edge)) + 1
            return if (edge == AudioFadeEdge.fadeIn) item.start.seconds..min(item.end.seconds, item.start.seconds + length)
                else max(item.start.seconds, item.end.seconds - length)..item.end.seconds
        }
        return request.range
    }
    // The preview plays the edited candidate. Rebuild it when the settings settle.
    LaunchedEffect(revision, volumeChosen, muted, muteChosen, fadeEdits.toMap()) {
        delay(if (revision == 0) 0 else 200)
        val wasPlaying = preview.playing
        preview.previewRange = listeningRange()
        val candidate = project.copy().also { it.history = listOf(state()); it.historyCursor = 0 }
        if (revision == 0) preview.seekAfterEdit(min(request.range.endInclusive, max(request.range.start, position)), base.duration)
        preview.load(candidate, folder, includeTimelineDetails = false)
        preview.auditionGain(null, target)
        if (wasPlaying && !preview.playing) preview.togglePlay()
    }
    fun playPreview() {
        if (!preview.playing && listeningEdge != null) { listeningEdge = null; preview.previewRange = request.range }
        preview.togglePlay()
    }
    fun listen(edge: AudioFadeEdge) {
        if (listeningEdge == edge && preview.playing) { preview.pause(); return }
        preview.pause(); listeningEdge = edge
        val range = listeningRange()
        preview.previewRange = range
        preview.seekPaused(range.start); preview.togglePlay()
    }
    BackHandler(onBack = cancel)
    Box(Modifier.fillMaxSize().background(Color(0.06f, 0.06f, 0.06f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            SheetBar("Volume", leading = "Cancel", leadingAction = cancel, trailing = "Apply", trailingEnabled = target.audio.isNotEmpty(), trailingBold = true,
                trailingAction = { val edited = state(); apply(if (edited == base) project.edit else edited) })
            BoxWithConstraints(Modifier.weight(1f)) {
                val compact = maxWidth > maxHeight; val boxWidth = maxWidth; val boxHeight = maxHeight
                val range = listeningRange()
                @Composable fun player(modifier: Modifier) {
                    Column(modifier) {
                        PlayerSurface(preview.player, Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), framing = preview.framing, titles = preview.titles, layers = preview.layers, captions = preview.subtitles, onPlaybackTap = { playPreview() })
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(editorTime(max(0.0, preview.playhead - range.start)) + " / " + editorTime(range.endInclusive - range.start),
                                color = Color.White, fontSize = 12.sp, style = tabular)
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.heightIn(min = 44.dp).clickable { playPreview() }, contentAlignment = Alignment.Center) {
                                Icon(if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (preview.playing) "Pause" else "Play preview", tint = EditorColors.accent)
                            }
                        }
                    }
                }
                @Composable fun settings(modifier: Modifier) {
                    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(request.scopeTitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (mixedVolume && !volumeChosen) "Mixed levels" else if (muted) "Muted" else "%+.1f dB".format(volume),
                                color = Color.White, fontSize = 22.sp, style = tabular, modifier = Modifier.weight(1f))
                            // Swift Stepper: ±0.5 dB.
                            Row(Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))) {
                                for ((icon, delta) in listOf(Icons.Filled.Remove to -0.5, Icons.Filled.Add to 0.5)) {
                                    Box(Modifier.size(46.dp, 32.dp).clickable {
                                        val next = if (mixedVolume && !volumeChosen) (if (delta > 0) volumeBounds.endInclusive else volumeBounds.start) else volume + delta
                                        volume = AudioGainGrid.snap(next); volumeChosen = true; revision += 1
                                    }.semantics { contentDescription = if (delta > 0) "Increase by 0.5 dB" else "Decrease by 0.5 dB" }, contentAlignment = Alignment.Center) {
                                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Slider(value = volume.toFloat(), valueRange = -40f..12f, onValueChange = {
                            volume = AudioGainGrid.snap(it.toDouble()); volumeChosen = true
                            preview.auditionGain(volume, target)
                        }, onValueChangeFinished = { revision += 1 },
                            colors = SliderDefaults.colors(thumbColor = if (mixedVolume && !volumeChosen) Color.Gray else Color.White,
                                activeTrackColor = if (mixedVolume && !volumeChosen) Color.Gray else EditorColors.cyan),
                            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Volume in decibels" })
                        if (mixedVolume && !volumeChosen) Text("Choose a level to set all selected parts to the same gain.", color = EditorColors.secondary, fontSize = 12.sp)
                        if (mixedMute && !muteChosen) Text("Some selected parts are muted. Gain preserves their mute settings.", color = EditorColors.secondary, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Mute", color = Color.White, fontSize = 17.sp)
                            Spacer(Modifier.width(12.dp))
                            Switch(muted, { muted = it; muteChosen = true; revision += 1 }, colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green))
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.heightIn(min = 44.dp).clickable { volume = 0.0; muted = false; volumeChosen = true; muteChosen = true; revision += 1 },
                                contentAlignment = Alignment.Center) { Text("Reset to 0 dB", color = EditorColors.accent, fontSize = 17.sp) }
                        }
                        for (edge in AudioFadeEdge.entries) {
                            val endpoint = fadeTargets[edge] ?: continue
                            val item = base.timeline.audio[endpoint.first]
                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                            AudioFadeRow(edge, endpoint.second, fadeEdits[edge] ?: item.fadeSeconds(edge), item.mapping.duration.seconds,
                                playing = listeningEdge == edge && preview.playing,
                                change = { seconds ->
                                    val value = EditTime.of(seconds).seconds
                                    if (value == item.fadeSeconds(edge)) fadeEdits.remove(edge) else fadeEdits[edge] = value
                                }, listen = { listen(edge) })
                        }
                    }
                }
                if (compact) Row(Modifier.fillMaxSize()) { player(Modifier.weight(1f).fillMaxHeight()); settings(Modifier.fillMaxHeight().width(boxWidth * 0.55f)) }
                else Column(Modifier.fillMaxSize()) { player(Modifier.weight(1f).fillMaxWidth()); settings(Modifier.fillMaxWidth().height(boxHeight * 0.55f)) }
            }
        }
    }
}

// ---- Enhance audio (Swift `AudioToolEditor` with `request.tool == .enhanceAudio`) ----

/**
 * The filter chain of the selected sound, auditioned on up to five seconds
 * either side of the playhead within the selection: Play prepares the latest
 * chain (only the audible source windows are processed), Bypass compares with
 * the original, and Match listening levels plays both at similar loudness.
 * Apply replaces the chain in the whole selection; Remove deletes it.
 */
@Composable
private fun EnhanceAudioEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val preview = remember { EditorController(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val setup = remember(request) {
        val targets = (request.targets ?: project.edit.timeline.entireSelection(EditInterval.of(request.range.start, request.range.endInclusive))).copy(video = emptySet())
        val items = project.edit.timeline.audio.filter { it.id in targets.audio }
        val chains = items.flatMap { item ->
            item.enhancements.filter { region ->
                targets.range?.let { it.intersection(EditInterval(item.start + region.interval.start, item.start + region.interval.end)) != null } ?: true
            }.map { it.effects }
        }
        val mixed = chains.drop(1).any { it != chains.first() } || (items.any { it.enhancements.isEmpty() } && chains.isNotEmpty())
        Triple(targets, chains.firstOrNull() ?: emptyList(), mixed)
    }
    val (target, initialEffects, mixed) = setup
    val base = project.edit
    val enhancementID = remember { newUUID() }
    var effects by remember { mutableStateOf(initialEffects) }
    var bypass by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var loadedPreview by remember { mutableStateOf(false) }
    var matchingHelp by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var matchListeningLevels by remember { mutableStateOf(preferences(context).getBoolean("matchListeningLevels", true)) }
    var comparisonGains by remember { mutableStateOf(1f to 1f) }
    var originalEnergies by remember { mutableStateOf<FloatArray?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val listeningRange = remember(request, position) { AudioPreviewWindow.listeningRange(position, request.range) }
    val allowsMatching = AudioPreviewLevels.allowsMatching(effects)

    fun state(bypassed: Boolean = false): EditState {
        val state = base.copy()
        state.timeline.enhance(if (bypassed) emptyList() else effects, target, request.effectID ?: enhancementID)
        return state
    }
    fun updateListeningVolume() {
        preview.player.volume = if (matchListeningLevels && allowsMatching) (if (bypass) comparisonGains.first else comparisonGains.second) else 1f
    }
    fun invalidatePreview() {
        preview.pause()
        job?.cancel(); job = null
        if (preparing) { preview.stop(); loadedPreview = false }
        preparing = false; dirty = true
    }
    fun changeEffects(value: List<AudioEffect>) { invalidatePreview(); effects = value; bypass = false }
    /** Swift `preparePreview` for Enhance audio, then plays. */
    fun preparePreview() {
        preparing = true; preview.error = null
        val range = listeningRange
        val candidate = project.copy().also { it.history = listOf(state(bypass)); it.historyCursor = 0 }
        val chain = effects
        job = scope.launch {
            try {
                preview.pause(); preview.previewRange = range; preview.audioPreviewRange = range
                preview.seekAfterEdit(range.start, base.duration)
                preview.load(candidate, folder, includeTimelineDetails = false)
                snapshotFlow { preview.loading }.first { !it }
                if (preview.error != null) return@launch
                if (!bypass && chain.any { it.enabled } && AudioPreviewLevels.allowsMatching(chain)) {
                    val processedMix = preview.mix
                    if (processedMix != null) {
                        val original = originalEnergies ?: kotlinx.coroutines.withContext(Dispatchers.Default) {
                            AudioPreviewLevels.energies(preview.mixFor(state(bypassed = true).timeline), range)
                        }.also { originalEnergies = it }
                        val processed = kotlinx.coroutines.withContext(Dispatchers.Default) { AudioPreviewLevels.energies(processedMix, range) }
                        comparisonGains = AudioPreviewLevels.gains(original, processed)
                    }
                }
                updateListeningVolume()
                android.util.Log.d("YouRollEnhance", "preview %s gains original=%.3f processed=%.3f volume=%.3f".format(java.util.Locale.ROOT,
                    if (bypass) "bypass" else "processed", comparisonGains.first, comparisonGains.second, preview.player.volume))
                dirty = false; loadedPreview = true; preparing = false
                preview.seek(range.start); preview.togglePlay()
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                preview.error = failure.message
            } finally {
                if (job == coroutineContext[kotlinx.coroutines.Job]) preparing = false
            }
        }
    }
    fun playPreview() {
        if (preparing) { invalidatePreview(); return }
        if (!loadedPreview || dirty || preview.error != null) preparePreview() else preview.togglePlay()
    }
    // Swift shows the editor's own player until the first Play; here the unchanged project, paused at the listening range.
    DisposableEffect(preview) {
        preview.previewRange = listeningRange; preview.audioPreviewRange = listeningRange
        preview.seekAfterEdit(listeningRange.start, base.duration)
        preview.load(project, folder, includeTimelineDetails = false)
        onDispose { job?.cancel(); preview.release() }
    }
    BackHandler(onBack = cancel)
    Box(Modifier.fillMaxSize().background(Color(0.06f, 0.06f, 0.06f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            SheetBar(request.tool.title, leading = "Cancel", leadingAction = cancel, trailing = "Apply", trailingEnabled = target.audio.isNotEmpty(), trailingBold = true,
                trailingAction = { invalidatePreview(); apply(state()) })
            BoxWithConstraints(Modifier.weight(1f)) {
                val compact = maxWidth > maxHeight; val boxWidth = maxWidth; val boxHeight = maxHeight
                @Composable fun player(modifier: Modifier) {
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PlayerSurface(preview.player, Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), framing = preview.framing, titles = preview.titles, layers = preview.layers, onPlaybackTap = { playPreview() })
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val displayed = if (loadedPreview) preview.playhead else listeningRange.start
                            Text(editorTime(max(0.0, displayed - listeningRange.start)) + " / " + editorTime(listeningRange.endInclusive - listeningRange.start),
                                color = Color.White, fontSize = 12.sp, style = tabular, modifier = Modifier.testTag("audioTool.position"))
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(44.dp).clickable { invalidatePreview(); bypass = !bypass }
                                .semantics { contentDescription = if (bypass) "Bypass on" else "Bypass" }.testTag("audioTool.bypass"), contentAlignment = Alignment.Center) {
                                Icon(if (bypass) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, null,
                                    tint = if (bypass) EditorColors.orange else EditorColors.accent)
                            }
                            val title = if (preparing) "Cancel preview" else if (preview.playing) "Pause" else "Play preview"
                            Row(Modifier.heightIn(min = 44.dp).clickable { playPreview() }.semantics { contentDescription = title }.testTag("audioTool.play"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (preparing) Icons.Filled.Close else if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = EditorColors.accent)
                                Text(" $title", color = EditorColors.accent, fontSize = 17.sp)
                            }
                        }
                    }
                }
                @Composable fun settings(modifier: Modifier) {
                    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 48.dp).testTag("audioTool.controls"),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(request.scopeTitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("audioTool.scope"))
                        if (mixed) Text("This selection contains different chains. Apply replaces them only in this selection.", color = EditorColors.secondary, fontSize = 12.sp)
                        Box {
                            Row(Modifier.heightIn(min = 44.dp).clickable { addOpen = true }.semantics { contentDescription = "Add filter" }.testTag("audio.addFilter"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, null, tint = EditorColors.accent)
                                Text(" Add filter", color = EditorColors.accent, fontSize = 17.sp)
                            }
                            androidx.compose.material3.DropdownMenu(addOpen, { addOpen = false }) {
                                for (kind in EffectKind.allCases) {
                                    // LavaSR and VoiceFixer: Core ML models with no Android runtime yet (TODO.md).
                                    val waiting = kind == EffectKind.studioVoice || kind == EffectKind.studioRestoration
                                    androidx.compose.material3.DropdownMenuItem(text = {
                                        Column {
                                            Text(kind.title)
                                            if (waiting) Text("Not yet available", color = EditorColors.secondary, fontSize = 12.sp)
                                        }
                                    }, enabled = !waiting, onClick = { addOpen = false; changeEffects(effects + AudioEffect.make(kind)) })
                                }
                            }
                        }
                        AudioChainControls(effects, busy = false, compact = compact, change = ::changeEffects)
                        Text("Play previews up to 5 seconds before and after your playhead, within this selection. Apply uses the full selection.",
                            color = EditorColors.secondary, fontSize = 12.sp)
                        Row(Modifier.alpha(if (allowsMatching) 1f else 0.45f).pointerInput(Unit) { detectTapGestures(onLongPress = { matchingHelp = true }) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Match listening levels", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Switch(matchListeningLevels && allowsMatching, { value ->
                                matchListeningLevels = value; preferences(context).edit().putBoolean("matchListeningLevels", value).apply()
                                invalidatePreview(); comparisonGains = 1f to 1f; updateListeningVolume()
                            }, enabled = allowsMatching, colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green),
                                modifier = Modifier.semantics { contentDescription = "Match listening levels. Only affects comparison playback, never the exported file." })
                        }
                        if (!allowsMatching) Text("Output gain uses actual volume; level matching is suspended.", color = EditorColors.secondary, fontSize = 12.sp)
                        else if (dirty) Text("Play prepares your latest changes.", color = EditorColors.secondary, fontSize = 12.sp)
                        if (preparing) {
                            val progress = preview.videoProgress
                            if (progress != null) androidx.compose.material3.LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = EditorColors.accent)
                            else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EditorColors.accent)
                            Text("Preparing " + EffectPreviewWindow.label(listeningRange), color = Color.White, fontSize = 12.sp, modifier = Modifier.testTag("audioTool.preparing"))
                        }
                        preview.error?.let { Text(it, color = EditorColors.orange, fontSize = 15.sp, modifier = Modifier.testTag("audioTool.error")) }
                        run {
                            // Swift: the bottom-bar "Remove" (destructive).
                            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                                invalidatePreview()
                                val edited = base.copy(); edited.timeline.enhance(emptyList(), target); apply(edited)
                            }.semantics { contentDescription = "Remove" }.testTag("audioTool.remove"), contentAlignment = Alignment.Center) {
                                Text("Remove", color = EditorColors.red, fontSize = 17.sp)
                            }
                        }
                    }
                }
                if (compact) Row(Modifier.fillMaxSize()) { player(Modifier.weight(1f).fillMaxHeight()); settings(Modifier.fillMaxHeight().width(boxWidth * 0.55f)) }
                else Column(Modifier.fillMaxSize()) { player(Modifier.weight(1f).fillMaxWidth()); settings(Modifier.fillMaxWidth().height(boxHeight * 0.55f)) }
            }
        }
        if (matchingHelp) androidx.compose.material3.AlertDialog(onDismissRequest = { matchingHelp = false },
            confirmButton = { androidx.compose.material3.TextButton({ matchingHelp = false }) { Text("OK") } },
            title = { Text("Match listening levels") },
            text = { Text("Balances the original and processed voice for a fair comparison. It does not affect export, and is suspended when Output gain is enabled so you can hear your volume changes.") })
    }
}

// ---- Placement (Swift `PlacementEditor`, `PlacementStillSurface`, `PlacementCropOverlay`) ----

/** One prepared still of a participant: its source frame, upright size and crop (Swift renders the item's composed frame). */
private data class PlacementStill(val image: android.graphics.Bitmap, val size: CGSize, val crop: CropSettings?)

/**
 * Swift `PlacementEditor`'s state. Geometry for gestures is in the inset
 * canvas's points (dp), as on iOS, so the 44-point limits keep their meaning.
 */
private class PlacementEditorModel(source: Project, val folder: File, val request: ToolRequest, position: Double) {
    val originalEdit: EditState = source.edit
    val project: Project = source.copy()
    val members: List<VideoTimelineItem>
    val initialPlacement: VideoPlacementRegion
    val inheritedSeed: Boolean
    val previewRange: ClosedFloatingPointRange<Double>

    var placement by mutableStateOf(VideoPlacementRegion(participants = emptySet(), videos = emptyMap()))
    var selected by mutableStateOf<UUID?>(null)
    var explicitPick by mutableStateOf(false)
    var reframe by mutableStateOf(false)
    var revision by mutableStateOf(0)
    var frameTime by mutableStateOf(0.0)
    var stills by mutableStateOf<Map<UUID, PlacementStill>>(emptyMap())
    var stillsTime by mutableStateOf<Double?>(null)
    var showingStills by mutableStateOf(false)
    var stillError by mutableStateOf<String?>(null)
    var reviewing by mutableStateOf(false)
    var loadedRevision = -1
    var dragStart: VideoPlacement? = null
    var pinchStart: VideoPlacement? = null
    var manipulationID: UUID? = null
    var cropStart by mutableStateOf<Pair<UUID, VideoPlacement>?>(null)
    var activeEdge by mutableStateOf<PlacementCropEdge?>(null)
    var canvas: CGSize = CGSize(1920.0, 1080.0)

    init {
        var participantIDs = request.clipIDs ?: emptySet()
        val targets = request.targets
        if (targets?.range != null) {
            val scoped = project.edit.timeline.scoped(targets)
            participantIDs = scoped.selection.video
            project.history = listOf(EditState(scoped.timeline, project.edit.outputSize)); project.historyCursor = 0
        }
        val timeline = project.edit.timeline
        members = timeline.video.filter { it.id in participantIDs }.sortedWith(TrackTimeline.backToFrontComparator)
        val ids = members.map { it.id }.toSet()
        initialPlacement = timeline.placementSeed(ids, EditTime.of(position))
        val defaults = members.withIndex().associate { (rank, item) -> item.id to VideoPlacement(order = rank) }
        inheritedSeed = timeline.placements.none { it.participants == ids } && initialPlacement.videos != defaults
        placement = initialPlacement
        selected = VideoPlacement.ordered(members.map { it.id }, initialPlacement.videos).lastOrNull()
        val interval = timeline.placementPreviewInterval(ids, position, request.range)
        val lower = max(interval?.start?.seconds ?: request.range.start, request.range.start)
        val upper = max(lower, min(interval?.end?.seconds ?: request.range.endInclusive, request.range.endInclusive) - TimelineEditing.frame)
        previewRange = lower..upper
        frameTime = min(upper, max(lower, position))
    }

    val orderedIDs: List<UUID> get() = VideoPlacement.ordered(members.map { it.id }, placement.videos)
    val canManipulate: Boolean get() = !reviewing && stillsTime == frameTime && stills.size == members.size

    /** The edge-crop handles' picture (Swift `PlacementCropOverlay.value`). */
    val handleValue: VideoPlacement? get() = if (canManipulate) selected?.let { placement.videos[it] } else null

    val overlapTitle: String
        get() {
            val labels = members.map { item -> "V" + (project.edit.timeline.video.indexOfFirst { it.id == item.id }.coerceAtLeast(0) + 1) }
            val range = editorTime(previewRange.start) + "–" + editorTime(previewRange.endInclusive + TimelineEditing.frame)
            return labels.joinToString(" + ") + " · " + range
        }

    fun videoLabel(item: VideoTimelineItem): String {
        val ids = orderedIDs
        val rank = ids.indexOf(item.id).coerceAtLeast(0)
        val depth = if (rank == ids.size - 1) "front" else if (rank == 0) "back" else "middle ${rank + 1} of ${ids.size}"
        return "V${project.edit.timeline.video.indexOfFirst { it.id == item.id }.coerceAtLeast(0) + 1} · " + depth
    }

    /** Transitions joining two of these pictures, which an arrangement replaces. */
    val replacesTransition: Boolean
        get() = project.edit.timeline.transitions.any { relation ->
            val ids = setOfNotNull(relation.outgoing, relation.incoming)
            ids.size > 1 && placement.participants.containsAll(ids)
        }

    fun state(removing: Boolean = false): EditState {
        // TODO(port): animated placements. A saved `motion` stays on its value unchanged.
        val placement = placement
        if (!removing && !inheritedSeed && placement == initialPlacement) return originalEdit
        val state = project.edit
        state.timeline.placements = state.timeline.placements.filter { it.participants != placement.participants }
        if (!removing) {
            state.timeline.placements = state.timeline.placements + placement
            state.timeline.transitions = state.timeline.transitions.filterNot { relation ->
                val ids = setOfNotNull(relation.outgoing, relation.incoming)
                ids.size > 1 && placement.participants.containsAll(ids)
            }
            state.timeline.pruneRelations()
        }
        return state
    }

    fun change(id: UUID? = null, body: (VideoPlacement) -> VideoPlacement) {
        val target = id ?: selected ?: return
        val value = placement.videos[target] ?: return
        placement = placement.copy(videos = placement.videos + (target to body(value)))
        showingStills = true
    }

    fun beginManipulation(x: Double, y: Double, pinchMargin: Double = 0.0) {
        if (manipulationID != null) return
        // Retain a deliberately selected occluded window; otherwise choose
        // what the finger touches. Both simultaneous gestures lock this ID.
        val point = CGPoint(x * canvas.width, y * canvas.height)
        val window = selected?.let { placement.videos[it]?.visibleWindow(canvas) }
        val hit = hitTest(x, y)
        val hitWindow = hit?.let { placement.videos[it]?.visibleWindow(canvas) }
        val marginIsClear = hit == null || hit == selected || (hitWindow?.let { it.width * it.height >= canvas.width * canvas.height * 0.5 } ?: false)
        fun contains(rect: CGRect, inset: Double = 0.0) =
            point.x >= rect.minX - inset && point.x <= rect.maxX + inset && point.y >= rect.minY - inset && point.y <= rect.maxY + inset
        manipulationID = if (explicitPick && window != null && contains(window)) selected
        else if (pinchMargin > 0 && marginIsClear && window != null && window.width * window.height < canvas.width * canvas.height * 0.5 &&
            contains(window, pinchMargin * canvas.width)) selected
        else { explicitPick = false; hit }
        manipulationID?.let { selected = it }
    }

    fun hitTest(x: Double, y: Double): UUID? {
        val px = x * canvas.width
        val py = y * canvas.height
        return orderedIDs.reversed().firstOrNull { id ->
            placement.videos[id]?.visibleWindow(canvas)?.let { px >= it.minX && px <= it.maxX && py >= it.minY && py <= it.maxY } == true
        }
    }

    /** Swift `constrain`: keep at least 44 points on the canvas and snap to centre and edges. */
    fun constrain(value: VideoPlacement, canvas: CGSize): VideoPlacement {
        val visible = value.visibleWindow(canvas)
        fun coordinate(raw: Double, leading: Double, trailing: Double, points: Double): Double {
            val keep = min(44 / points, trailing - leading)
            val bounded = min(1 - keep - leading, max(keep - trailing, raw))
            val anchors = listOf(0.5 - (leading + trailing) / 2, -leading, 1 - trailing)
            return anchors.firstOrNull { abs(it - bounded) < 0.012 } ?: bounded
        }
        return value.copy(
            x = coordinate(value.x, visible.minX / canvas.width - value.x, visible.maxX / canvas.width - value.x, canvas.width),
            y = coordinate(value.y, visible.minY / canvas.height - value.y, visible.maxY / canvas.height - value.y, canvas.height))
    }

    fun preset(kind: Int) {
        showingStills = true
        val ids = orderedIDs
        placement = placement.copy(videos = placement.videos + ids.withIndex().associate { (index, id) -> id to VideoPlacement.layout(kind, index, members.size) })
        revision += 1
    }

    fun drag(translation: CGSize, size: CGSize) {
        val start = dragStart ?: return
        change(manipulationID) { value ->
            if (reframe) {
                val framed = start.copy(framing = start.framing.copy(zoom = value.framing.zoom)).reframe(translation, size)
                value.copy(framing = value.framing.copy(x = framed.framing.x, y = framed.framing.y))
            } else {
                val original = start.visibleWindow(size)
                val current = value.visibleWindow(size)
                constrain(value.copy(x = value.x + (original.midX + translation.width - current.midX) / size.width,
                    y = value.y + (original.midY + translation.height - current.midY) / size.height), size)
            }
        }
    }

    fun pinch(magnification: Double, size: CGSize) {
        val start = pinchStart ?: return
        change(manipulationID) { value ->
            if (reframe) value.copy(framing = value.framing.copy(zoom = min(8.0, max(1.0, start.framing.zoom * magnification))))
            else {
                val original = start.visibleWindow(size)
                val before = value.visibleWindow(size)
                val minimum = min(1.0, max(44 / original.width, 44 / original.height))
                val scale = min(3 / max(start.width, start.height), max(minimum, magnification))
                val resized = value.copy(width = start.width * scale, height = start.height * scale)
                val after = resized.visibleWindow(size)
                constrain(resized.copy(x = resized.x + (before.midX - after.midX) / size.width, y = resized.y + (before.midY - after.midY) / size.height), size)
            }
        }
    }

    fun endManipulation() {
        val manipulated = dragStart != null || pinchStart != null
        dragStart = null; pinchStart = null; manipulationID = null
        if (manipulated) revision += 1
    }

    /** Swift `PlacementCropHandleView.points`, in the overlay's coordinates ([frame] is the inset canvas there). */
    fun handlePoints(frame: CGRect): Map<PlacementCropEdge, CGPoint> {
        val value = handleValue ?: return emptyMap()
        if (!(frame.width > 0 && frame.height > 0)) return emptyMap()
        val visible = value.visibleWindow(frame.size).let { CGRect(it.x + frame.minX, it.y + frame.minY, it.width, it.height) }
        fun x(v: Double) = min(frame.maxX, max(frame.minX, v))
        fun y(v: Double) = min(frame.maxY, max(frame.minY, v))
        val intersection = TrackRenderer.intersection(visible, frame)
        val middle = if (intersection.width <= 0 || intersection.height <= 0) CGPoint(x(visible.midX), y(visible.midY)) else CGPoint(intersection.midX, intersection.midY)
        return mapOf(PlacementCropEdge.left to CGPoint(x(visible.minX), middle.y), PlacementCropEdge.right to CGPoint(x(visible.maxX), middle.y),
            PlacementCropEdge.top to CGPoint(middle.x, y(visible.minY)), PlacementCropEdge.bottom to CGPoint(middle.x, y(visible.maxY)))
    }

    /** Swift `nearest(_:)`: the edge handle under a touch, leaving a useful interior for moving a small window. */
    fun nearestEdge(point: CGPoint, frame: CGRect): Pair<PlacementCropEdge, CGPoint>? {
        if (dragStart != null || pinchStart != null) return null
        val value = handleValue ?: return null
        val visible = value.visibleWindow(frame.size)
        return handlePoints(frame).mapNotNull { (edge, position) ->
            val span = if (edge.horizontal) visible.width else visible.height
            val inward = if (span < 88) 7.0 else 14.0
            val outward = 44 - inward
            val dx = point.x - position.x
            val dy = point.y - position.y
            val normal = when (edge) { PlacementCropEdge.left -> dx; PlacementCropEdge.right -> -dx; PlacementCropEdge.top -> dy; PlacementCropEdge.bottom -> -dy }
            val tangent = if (edge.horizontal) abs(dy) else abs(dx)
            if (normal >= -outward && normal <= inward && tangent <= 22) edge to position else null
        }.minByOrNull { (_, position) -> kotlin.math.hypot(position.x - point.x, position.y - point.y) }
    }

    fun beginCrop(edge: PlacementCropEdge) {
        val id = selected ?: return
        val value = placement.videos[id] ?: return
        cropStart = id to value; activeEdge = edge; showingStills = true
    }

    fun cropTo(edge: PlacementCropEdge, handle: CGPoint, translation: CGSize, frame: CGRect) {
        val (id, start) = cropStart ?: return
        val position = if (edge.horizontal) (handle.x + translation.width - frame.minX) / max(1.0, frame.width)
            else (handle.y + translation.height - frame.minY) / max(1.0, frame.height)
        val base = start.pictureWindow(frame.size)
        val original = when (edge) {
            PlacementCropEdge.left -> base.minX / frame.width
            PlacementCropEdge.right -> base.maxX / frame.width
            PlacementCropEdge.top -> base.minY / frame.height
            PlacementCropEdge.bottom -> base.maxY / frame.height
        }
        val threshold = 5 / (if (edge.horizontal) frame.width else frame.height)
        val snapped = listOf(original, 0.0, 0.5, 1.0).firstOrNull { abs(it - position) < threshold } ?: position
        placement = placement.copy(videos = placement.videos + (id to start.crop(edge, snapped, frame.size, minimumPoints = 44.0)))
    }

    fun endCrop(cancelled: Boolean) {
        val (id, start) = cropStart ?: return
        val changed = placement.videos[id] != start
        if (cancelled) placement = placement.copy(videos = placement.videos + (id to start))
        cropStart = null; activeEdge = null
        if (changed && !cancelled) revision += 1 else if (loadedRevision == revision) showingStills = false
    }

    suspend fun prepareStills(position: Double, canvas: CGSize?) {
        val images = HashMap<UUID, PlacementStill>()
        stillError = null
        for (item in members) {
            try {
                val source = project.sources.firstOrNull { it.id == item.content.sourceID } ?: throw ProjectError.missingMedia
                val local = min(item.mapping.duration.seconds - 1.0 / 30, max(0.0, position - item.start.seconds))
                val sourceTime = item.mapping.source(EditTime.of(max(0.0, local))) ?: item.content.start
                val image = CropMedia.Android.frame(source, folder, sourceTime) ?: throw ProjectError.invalidMedia
                images[item.id] = PlacementStill(image, CropMedia.Android.sourceSize(source, folder), item.content.crop)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                stillError = "This frame could not be prepared. Choose another position or retry."
            }
        }
        if (frameTime != position) return
        if (canvas != null) this.canvas = canvas
        stills = images; stillsTime = position
    }

    /** Swift `.task(id: revision)`: load the arrangement into the preview at the frame time, prepare stills, then show the live picture. */
    suspend fun refresh(preview: EditorController) {
        val version = revision
        val time = frameTime
        val candidate = project.copy()
        candidate.history = listOf(state()); candidate.historyCursor = 0
        preview.previewRange = previewRange.start..(previewRange.endInclusive + 1.0 / 30)
        preview.seekAfterEdit(time, candidate.edit.duration)
        preview.load(candidate, folder, includeTimelineDetails = false)
        if (stillsTime != time || stills.size != members.size) {
            // The canvas is known once the preview has read the source sizes.
            var waited = 0
            while (preview.canvas == null && waited < 100) { kotlinx.coroutines.delay(30); waited += 1 }
            prepareStills(time, preview.canvas)
        }
        var waited = 0
        while ((preview.loading || preview.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) && waited < 200) {
            kotlinx.coroutines.delay(30); waited += 1
        }
        // Let the seeked frame reach the surfaces before the stills are hidden.
        kotlinx.coroutines.delay(250)
        if (revision == version) {
            loadedRevision = version
            if (dragStart == null && pinchStart == null && cropStart == null) showingStills = false
        }
    }

    fun togglePreview(preview: EditorController) {
        if (reviewing) finishPreview(preview)
        else {
            showingStills = false; reviewing = true
            if (preview.playhead >= previewRange.endInclusive - 0.02) preview.seek(previewRange.start)
            preview.togglePlay()
        }
    }

    fun finishPreview(preview: EditorController) {
        reviewing = false; preview.pause()
        frameTime = min(previewRange.endInclusive, max(previewRange.start, preview.playhead))
        showingStills = false; revision += 1
    }
}

@Composable
fun PlacementEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit) {
    val context = LocalContext.current
    val model = remember { PlacementEditorModel(project, folder, request, position) }
    val preview = remember { EditorController(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(model.revision) { model.refresh(preview) }
    LaunchedEffect(preview.playing) { if (!preview.playing && model.reviewing) model.finishPreview(preview) }
    BackHandler(onBack = cancel)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxWidth > maxHeight
        val boxWidth = maxWidth
        val boxHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            SheetBar("Placement", leading = "Cancel", leadingAction = cancel, trailing = "Apply", trailingAction = { apply(model.state()) },
                trailingEnabled = model.members.size >= 2, trailingBold = true)
            if (compact) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlacementCanvas(model, preview, Modifier.weight(1f).fillMaxHeight())
                PlacementControls(model, preview, apply, Modifier.width(boxWidth * 0.4f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlacementCanvas(model, preview, Modifier.weight(1f).fillMaxWidth())
                PlacementControls(model, preview, apply, Modifier.fillMaxWidth().height(boxHeight * 0.45f))
            }
        }
    }
}

@Composable
private fun PlacementCanvas(model: PlacementEditorModel, preview: EditorController, modifier: Modifier) {
    val canvas = preview.canvas ?: model.canvas
    BoxWithConstraints(modifier.clipToBounds()) {
        val aspect = canvas.width / canvas.height
        val areaWidth = maxWidth.value.toDouble()
        val areaHeight = maxHeight.value.toDouble()
        val width = max(1.0, min(areaWidth - 48, (areaHeight - 48) * aspect))
        val height = width / aspect
        val frame = CGRect((areaWidth - width) / 2, (areaHeight - height) / 2, width, height)
        val size = CGSize(width, height)
        Box(Modifier.size(width.dp, height.dp).align(Alignment.Center).clipToBounds()) {
            PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles, layers = preview.layers, captions = preview.subtitles, inspectionZoom = false)
            if (model.showingStills && model.stillsTime == model.frameTime && model.stills.size == model.members.size) {
                PlacementStillSurface(model.stills, model.placement.videos, model.orderedIDs, canvas)
            }
        }
        val density = androidx.compose.ui.platform.LocalDensity.current.density.toDouble()
        val slop = 2 * density
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()
            .semantics { contentDescription = "Placement canvas" }
            .pointerInput(model, frame) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun point(offset: androidx.compose.ui.geometry.Offset) = CGPoint(offset.x / density, offset.y / density)
                    val touch = point(down.position)
                    if (!model.canManipulate) return@awaitEachGesture
                    val edge = model.nearestEdge(touch, frame)
                    if (edge != null) {
                        // Swift `PlacementCropOverlay`: one finger drags the nearest edge from its touch-down point.
                        preview.pause(); model.beginCrop(edge.first)
                        var ended = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val at = point(change.position)
                            model.cropTo(edge.first, edge.second, CGSize(at.x - touch.x, at.y - touch.y), frame)
                            change.consume()
                            if (!change.pressed) { ended = true; break }
                        }
                        model.endCrop(cancelled = !ended)
                        return@awaitEachGesture
                    }
                    var pan = androidx.compose.ui.geometry.Offset.Zero
                    var zoom = 1.0
                    var dragging = false
                    var pinching = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val pressed = event.changes.count { it.pressed }
                        zoom *= event.calculateZoom().toDouble()
                        pan += event.calculatePan()
                        if (!dragging && pan.getDistance() > slop) {
                            dragging = true
                            model.beginManipulation((touch.x - frame.minX) / frame.width, (touch.y - frame.minY) / frame.height)
                            preview.pause(); model.showingStills = true
                            model.dragStart = model.manipulationID?.let { model.placement.videos[it] }
                        }
                        if (!pinching && pressed >= 2 && abs(zoom - 1) > 0.01) {
                            pinching = true
                            val centroid = point(event.calculateCentroid())
                            model.beginManipulation((centroid.x - frame.minX) / frame.width, (centroid.y - frame.minY) / frame.height, pinchMargin = 44 / width)
                            preview.pause(); model.showingStills = true
                            model.pinchStart = model.manipulationID?.let { model.placement.videos[it] }
                        }
                        if (pinching) model.pinch(zoom, size)
                        if (dragging) model.drag(CGSize(pan.x / density, pan.y / density), size)
                        if (dragging || pinching) event.changes.forEach { it.consume() }
                    }
                    if (dragging || pinching) model.endManipulation()
                    else if (model.cropStart == null) {
                        // Swift `SpatialTapGesture`: select what is under the finger.
                        model.selected = model.hitTest((touch.x - frame.minX) / frame.width, (touch.y - frame.minY) / frame.height) ?: model.selected
                        model.explicitPick = false
                    }
                }
            }) {
            // Swift `PlacementCropHandleView.draw`: the selected window and its four edge circles.
            val value = model.handleValue ?: return@Canvas
            val k = density.toFloat()
            val visible = value.visibleWindow(size).let { CGRect(it.x + frame.minX, it.y + frame.minY, it.width, it.height) }
            clipRect((frame.minX * k - k).toFloat(), (frame.minY * k - k).toFloat(), (frame.maxX * k + k).toFloat(), (frame.maxY * k + k).toFloat()) {
                drawRect(EditorColors.yellow, androidx.compose.ui.geometry.Offset((visible.minX * k).toFloat(), (visible.minY * k).toFloat()),
                    androidx.compose.ui.geometry.Size((visible.width * k).toFloat(), (visible.height * k).toFloat()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * k,
                        pathEffect = if (model.reframe) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6 * k, 4 * k)) else null))
            }
            for ((edge, at) in model.handlePoints(frame)) {
                val center = androidx.compose.ui.geometry.Offset((at.x * k).toFloat(), (at.y * k).toFloat())
                drawCircle(if (model.activeEdge == edge) EditorColors.yellow else Color.White, 7 * k, center)
                drawCircle(Color.Black.copy(alpha = 0.8f), 7 * k, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2 * k))
            }
        }
    }
}

/** Swift `PlacementStillSurface`: lightweight drag feedback using the same placement transform as the export. */
@Composable
private fun PlacementStillSurface(images: Map<UUID, PlacementStill>, placements: Map<UUID, VideoPlacement>, order: List<UUID>, canvas: CGSize) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().background(Color.Black)) {
        val k = size.width / canvas.width
        for (id in order) {
            val settings = placements[id] ?: continue
            val still = images[id] ?: continue
            val framing = PreviewFraming(canvas, still.size, still.crop, settings)
            val placed = framing.placed
            if (!placed.isVisible) continue
            val m = CGAffineTransform.scale(still.size.width / still.image.width, still.size.height / still.image.height)
                .concatenating(framing.transform).concatenating(placed.transform).concatenating(CGAffineTransform.scale(k, k))
            drawIntoCanvas { target ->
                val native = target.nativeCanvas
                native.save()
                native.clipRect((placed.clip.minX * k).toFloat(), (placed.clip.minY * k).toFloat(), (placed.clip.maxX * k).toFloat(), (placed.clip.maxY * k).toFloat())
                native.concat(android.graphics.Matrix().apply {
                    setValues(floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.tx.toFloat(), m.b.toFloat(), m.d.toFloat(), m.ty.toFloat(), 0f, 0f, 1f))
                })
                native.drawBitmap(still.image, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                native.restore()
            }
        }
    }
}

@Composable
private fun PlacementControls(model: PlacementEditorModel, preview: EditorController, apply: (EditState) -> Unit, modifier: Modifier) {
    var videoMenu by remember { mutableStateOf(false) }
    var layoutMenu by remember { mutableStateOf(false) }
    var sliding by remember { mutableStateOf(false) }
    val enabled = !model.reviewing
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(model.overlapTitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(editorTime(if (model.reviewing) preview.playhead else model.frameTime), color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { model.togglePreview(preview) }, enabled = !preview.loading, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(if (preview.playing) "Pause" else "Play preview", color = if (preview.loading) EditorColors.secondary else EditorColors.accent, fontSize = 17.sp)
            }
        }
        Slider(value = model.frameTime.toFloat(), valueRange = model.previewRange.start.toFloat()..max(model.previewRange.start + 0.00001, model.previewRange.endInclusive).toFloat(),
            onValueChange = { value ->
                if (!sliding) { sliding = true; model.reviewing = false; preview.pause(); model.stillsTime = null }
                model.frameTime = value.toDouble(); model.showingStills = false; preview.seek(value.toDouble())
            },
            onValueChangeFinished = { sliding = false; model.revision += 1 },
            enabled = !preview.loading && !model.reviewing,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
            modifier = Modifier.semantics { contentDescription = "Overlap preview position" })
        Column(Modifier.alpha(if (enabled) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Drag to ${if (model.reframe) "reframe" else "move"} · pinch to ${if (model.reframe) "zoom" else "resize"}\nDrag edge circles to crop",
                color = EditorColors.secondary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    val current = model.members.firstOrNull { it.id == model.selected }
                    Row(Modifier.heightIn(min = 44.dp).clickable(enabled = enabled) { videoMenu = true }
                        .semantics { contentDescription = "Video: " + (current?.let(model::videoLabel) ?: "") }, verticalAlignment = Alignment.CenterVertically) {
                        Text(current?.let(model::videoLabel) ?: "Video", color = EditorColors.accent, fontSize = 17.sp)
                        Icon(Icons.Filled.UnfoldMore, null, tint = EditorColors.accent, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = videoMenu, onDismissRequest = { videoMenu = false }) {
                        for (item in model.members) DropdownMenuItem(text = { Text(model.videoLabel(item)) },
                            onClick = { videoMenu = false; model.selected = item.id; model.explicitPick = true })
                    }
                }
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                Box {
                    Row(Modifier.heightIn(min = 44.dp).clickable(enabled = enabled) { layoutMenu = true }.semantics { contentDescription = "Layout" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VerticalSplit, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Layout", color = EditorColors.accent, fontSize = 17.sp)
                    }
                    DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                        Text("Layouts reset crop, framing and Fit", color = EditorColors.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        for ((kind, title) in listOf("Cover", "Side by side", "Top and bottom", "Picture in picture").withIndex()) {
                            DropdownMenuItem(text = { Text(title) }, onClick = { layoutMenu = false; preview.pause(); model.preset(kind) })
                        }
                    }
                }
            }
            PlacementToggle("Reframe inside window", model.reframe, enabled) { model.reframe = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlacementBorderedButton("Bring to front", enabled) {
                    val order = (model.placement.videos.values.maxOfOrNull { it.order } ?: 0) + 1
                    model.change { it.copy(order = order) }; model.revision += 1
                }
                PlacementBorderedButton("Send to back", enabled) {
                    val order = (model.placement.videos.values.minOfOrNull { it.order } ?: 0) - 1
                    model.change { it.copy(order = order) }; model.revision += 1
                }
            }
            val value = model.selected?.let { model.placement.videos[it] }
            if (value != null) {
                PlacementToggle("Fill window", value.fill, enabled) { fill -> model.change { it.copy(fill = fill) }; model.revision += 1 }
                Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { model.change { it.copy(framing = TransitionFraming()) }; model.revision += 1 }, enabled = enabled) {
                        Text("Center picture", color = EditorColors.accent, fontSize = 17.sp)
                    }
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    TextButton(onClick = { model.change { it.copy(edgeCrop = null) }; model.revision += 1 }, enabled = enabled && value.edgeCrop != null) {
                        Text("Reset crop", color = if (enabled && value.edgeCrop != null) EditorColors.accent else EditorColors.secondary, fontSize = 17.sp)
                    }
                }
            }
        }
        if (preview.loading || (!model.reviewing && model.stillsTime != model.frameTime && model.stillError == null)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Preparing frame…", color = EditorColors.secondary, fontSize = 15.sp)
            }
        }
        model.stillError?.let { error ->
            Text(error, color = EditorColors.orange, fontSize = 12.sp)
            TextButton(onClick = { model.revision += 1 }) { Text("Retry frame", color = EditorColors.accent, fontSize = 17.sp) }
        }
        preview.error?.let { Text(it, color = EditorColors.orange, fontSize = 12.sp) }
        if (model.replacesTransition) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sync, null, tint = EditorColors.orange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Changing this arrangement replaces the transition between these videos", color = EditorColors.orange, fontSize = 12.sp)
        }
        Text("Audio from overlapping clips mixes. Select an audio item in the timeline to change its volume.", color = EditorColors.secondary, fontSize = 12.sp)
        TextButton(onClick = { apply(model.state(removing = true)) }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("Reset placement", color = Color(1f, 0.27f, 0.23f), fontSize = 17.sp)
        }
    }
}

@Composable
private fun PlacementToggle(title: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(value, change, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green))
    }
}

@Composable
private fun PlacementBorderedButton(title: String, enabled: Boolean, action: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, onClick = action).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(title, color = EditorColors.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
