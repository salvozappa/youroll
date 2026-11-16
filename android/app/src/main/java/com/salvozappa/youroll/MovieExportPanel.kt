// Port of YouRec/MovieExportPanel.swift (MovieExportPanel + CatalogueMovieExport),
// with the export state that iOS keeps in YouRec/EditorController.swift
// (`render`, `refreshExports`, `matchingExport`, `selectExport`,
// `exportActionsAppeared`, `saveToPhotos`, `cancel`, `prepareCatalogueExport`)
// and its private `ExportCompletionSound`.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Entry point for both Projects and the Editor:
//     MovieExportPanel(store, projectID, onDismiss)
// The panel owns a MovieExportController (iOS CatalogueMovieExport creates its
// own EditorController the same way). The Editor can instead pass its own
// controller to keep the export state across sheet presentations.
//
// Simplified (TODO.md): export settings are fixed to H.264 · 30 fps · project
// size (shown read-only, like iOS shows the fixed HDR codec); no paused or
// resumable export, no audio distortion warning (no peak measurement), and
// earlier exports are kept instead of being retired after a newer one.
package com.salvozappa.youroll

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Export state for one project folder (iOS: the export half of EditorController). */
class MovieExportController(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    var rendering by mutableStateOf(false); private set
    var exportProgress by mutableStateOf(ExportProgress(ExportProgress.Step.opening)); private set
    var exports by mutableStateOf<List<ExportReceipt>>(emptyList()); private set
    /** The movie Share and Save act on (Swift `exportURL`). */
    var exportURL by mutableStateOf<File?>(null); private set
    var canvas by mutableStateOf<CGSize?>(null); private set
    var error by mutableStateOf<String?>(null)
    private var photoSavesInFlight by mutableStateOf<Set<File>>(emptySet())
    private var savedPhotoExports by mutableStateOf<Set<File>>(emptySet())
    private var pendingExportSound: File? = null
    private var job: Job? = null
    private var latestProjectID: UUID? = null

    val savingToPhotos: Boolean get() = exportURL?.let { it in photoSavesInFlight } ?: false
    val savedToPhotos: Boolean get() = exportURL?.let { it in savedPhotoExports } ?: false
    val hasPhotoSaveInFlight: Boolean get() = photoSavesInFlight.isNotEmpty()

    /** The catalogue only needs source headers for the export settings summary. */
    suspend fun prepareCatalogueExport(project: Project, folder: File) {
        latestProjectID = project.id
        canvas = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaRenderer.canvas(project, folder) } }.getOrNull()
    }

    fun refreshExports(folder: File) {
        exports = ExportLibrary.receipts(folder)
        if (exportURL == null) exports.firstOrNull()?.let { exportURL = File(ExportLibrary.directory(folder), it.filename) }
    }

    fun matchingExport(project: Project, folder: File): ExportReceipt? = ExportLibrary.matching(project, exports, folder)

    fun selectExport(project: Project, folder: File) {
        refreshExports(folder)
        val receipt = matchingExport(project, folder) ?: exports.firstOrNull()
        exportURL = receipt?.let { File(ExportLibrary.directory(folder), it.filename) }
    }

    /** Play the completion chime once, when the finished movie's actions first appear. */
    fun exportActionsAppeared(movie: File): Boolean {
        if (rendering || exportURL != movie || pendingExportSound != movie) return false
        pendingExportSound = null
        ExportCompletionSound.play()
        return true
    }

    fun render(project: Project, folder: File) {
        if (rendering) return
        if (hasPhotoSaveInFlight) { error = "Wait for the gallery to finish saving the previous export, then export again."; return }
        pendingExportSound = null
        rendering = true
        exportProgress = ExportProgress(ExportProgress.Step.opening)
        latestProjectID = project.id
        YouRollApp.keepAwake(awakeReason, true)
        job = scope.launch {
            try {
                refreshExports(folder)
                // Every export is a new file with its own identity.
                val destination = File(ExportLibrary.directory(folder), newUUID().uuidString + ".mp4")
                val receipt = MediaRenderer.render(context, project, folder, destination) { value ->
                    if (value.step == ExportProgress.Step.complete) return@render
                    scope.launch {
                        val next = exportProgress.advanced(value)
                        if (next != exportProgress) exportProgress = next
                    }
                }
                exports = listOf(receipt) + exports.filter { it.id != receipt.id }
                if (latestProjectID == project.id) {
                    exportURL = destination
                    pendingExportSound = destination
                }
                exportProgress = ExportProgress(ExportProgress.Step.complete)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                rendering = false
                YouRollApp.keepAwake(awakeReason, false)
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** iOS `saveToPhotos`: copy the selected export into the gallery (`Movies/YouRoll`). */
    fun saveToPhotos(title: String) {
        val movie = exportURL ?: return
        if (rendering || movie in photoSavesInFlight || movie in savedPhotoExports) return
        photoSavesInFlight = photoSavesInFlight + movie
        scope.launch {
            try {
                MediaSharing.saveToGallery(context, movie, MediaSharing.displayName(title))
                // The selection can change while the gallery copies a large video.
                savedPhotoExports = savedPhotoExports + movie
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "The video could not be saved to the gallery."
            } finally {
                photoSavesInFlight = photoSavesInFlight - movie
            }
        }
    }

    private companion object { const val awakeReason = "export" }
}

/** Swift `ExportCompletionSound`: a short locally generated chime, plus a success haptic in the panel. */
private object ExportCompletionSound {
    private var track: AudioTrack? = null
    fun play() {
        val rate = 48000
        val count = 14400
        val samples = ShortArray(count) { index ->
            val time = index.toDouble() / rate
            val envelope = min(1.0, index / 240.0) * (1 - index.toDouble() / count).pow(2)
            val sample = (sin(time * 2 * PI * 880) + sin(time * 2 * PI * 1320) * 0.4) * envelope * 0.12
            (sample * 32767).toInt().toShort()
        }
        runCatching {
            track?.release()
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(count * 2)
                .build().apply { write(samples, 0, count); play() }
        }
    }
}

private object ExportColors {
    val accent = Color(1f, 0.25f, 0.29f)
    val save = Color(0.16f, 0.39f, 0.78f)
    val green = Color(48, 209, 88)
    val sheet = Color(28, 28, 30)
    val bar = Color(37, 37, 39)
    val card = Color.White.copy(alpha = 0.10f)
    val secondary = Color(235, 235, 245).copy(alpha = 0.6f)
    val teal = Color(64, 200, 224)
}

/** Swift `editorTime` (EditorTimeline.swift): m:ss.cc on 30 fps frames. */
private fun exportTime(seconds: Double): String {
    val frames = Math.round(maxOf(0.0, if (seconds.isFinite()) seconds else 0.0) * 30).toInt()
    return "%d:%02d.%02d".format(frames / 1800, frames / 30 % 60, Math.round((frames % 30) / 30.0 * 100).toInt())
}

/**
 * The export sheet for [projectID], usable from Projects and from the Editor.
 * [controller] defaults to a panel-owned one (iOS CatalogueMovieExport).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieExportPanel(store: ProjectStore, projectID: UUID, onDismiss: () -> Unit, controller: MovieExportController? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val editor = controller ?: remember { MovieExportController(context, scope) }
    val projects by store.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectID } ?: run { LaunchedEffect(Unit) { onDismiss() }; return }
    val folder = store.folder(project)
    // Projects in the store are immutable snapshots, so the edit (materialized from the
    // history) and the matching export (which stats its movie) only change with the project
    // or the export list, never with the progress ticks that recompose the bar.
    val edit = remember(project) { project.edit }
    val exports = editor.exports
    val matching = remember(project, exports) { editor.matchingExport(project, folder) }
    var readingFormat by remember { mutableStateOf(true) }
    var optionsOpen by remember { mutableStateOf(false) }
    // Medium and large detents: the content is always large-detent tall and opens half-way.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false, confirmValueChange = { it != SheetValue.Hidden || !editor.rendering })
    val view = LocalView.current

    LaunchedEffect(projectID) {
        editor.selectExport(project, folder)
        editor.prepareCatalogueExport(project, folder)
        readingFormat = false
    }
    LaunchedEffect(edit, project.exportSettings) { editor.selectExport(project, folder) }
    // Leaving the sheet never leaves a half-written movie behind; the controller stops with its scope.
    DisposableEffect(Unit) { onDispose { if (controller == null) editor.cancel() } }

    fun close() { if (!editor.rendering) onDismiss() }

    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = sheetState,
        containerColor = ExportColors.sheet,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !editor.rendering),
    ) {
        val large = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        // The sheet is its own window: expose its test tags to UiAutomator (acceptance suite).
        Box(Modifier.semantics { testTagsAsResourceId = true }) {
        PinnedBottomBar(large, content = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // The iOS grabber sits 5 pt from the top; Material's DragHandle adds 22 dp above and below.
                Box(Modifier.align(Alignment.CenterHorizontally)) { SheetGrabber() }
                Box(Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Export", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                        SheetPill(if (editor.rendering) "Cancel" else "Done", "export.done") {
                            if (editor.rendering) editor.cancel() else onDismiss()
                        }
                    }
                }
                // SF text styles set lines about 1.3 x the font size; Material's default is 24 sp for every size,
                // which made this sheet about 25% looser than iOS.
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.testTag("export.project"))
                        Text("${exportTime(edit.duration)} · ${edit.clips.size} clips", fontSize = 15.sp, color = ExportColors.secondary)
                    }
                    ExportOptions(project, edit, editor, readingFormat, optionsOpen) {
                        if (!optionsOpen) scope.launch { sheetState.expand() }
                        optionsOpen = !optionsOpen
                    }
                    Text("Your previous export stays available until the new movie is ready.", fontSize = 15.sp, color = Color.White)
                    if (!editor.rendering && editor.exportURL != null && matching == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Previous export", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("This movie uses the earlier edits and settings.", fontSize = 12.sp, color = ExportColors.secondary)
                            ExportFileActions(editor, project.title, previous = true)
                        }
                    }
                    // Room to scroll the last lines above the pinned bar.
                    Spacer(Modifier.height(200.dp))
                }
                }
            }
        }, bar = {
            Column {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f), thickness = 0.5.dp)
                Column(Modifier.fillMaxWidth().background(ExportColors.bar).padding(16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        editor.rendering -> ExportProgressView(editor.exportProgress)
                        matching != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.CheckCircle, null, tint = ExportColors.green, modifier = Modifier.size(18.dp))
                                Text("Exported", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ExportColors.green)
                            }
                            ExportFileActions(editor, project.title, previous = false)
                            val movie = editor.exportURL
                            LaunchedEffect(movie) {
                                if (movie != null && editor.exportActionsAppeared(movie)) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                }
                            }
                        }
                        else -> {
                            CapsuleButton("Export video", Icons.Outlined.IosShare, ExportColors.accent, "export.start",
                                enabled = !editor.hasPhotoSaveInFlight && !readingFormat, minHeight = 52) {
                                optionsOpen = false
                                editor.render(project, folder)
                            }
                            if (editor.hasPhotoSaveInFlight) {
                                Text("Waiting for the gallery to finish saving the previous export…", fontSize = 12.sp, color = ExportColors.secondary)
                            }
                        }
                    }
                }
            }
        })
        }
    }

    editor.error?.let { message ->
        AlertDialog(onDismissRequest = { editor.error = null }, title = { Text("Export") }, text = { Text(message) },
            confirmButton = { TextButton({ editor.error = null }) { Text("OK", color = ExportColors.accent) } },
            containerColor = ExportColors.sheet, titleContentColor = Color.White, textContentColor = Color.White)
    }
}

/**
 * Lays [content] out at the top of a [height]-tall sheet and keeps [bar] pinned
 * to the bottom of the part of the sheet that is on screen, in both detents
 * (iOS `.safeAreaInset(edge: .bottom)` in a medium/large sheet).
 */
@Composable
private fun PinnedBottomBar(height: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit, bar: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(Int.MAX_VALUE) }
    Layout(contents = listOf(content, bar), modifier = Modifier.onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates()
        visible = (root.size.height - coordinates.positionInRoot().y).toInt()
    }) { (contentMeasurables, barMeasurables), constraints ->
        val total = if (constraints.hasBoundedHeight) minOf(height.roundToPx(), constraints.maxHeight) else height.roundToPx()
        val loose = constraints.copy(minHeight = 0, minWidth = constraints.maxWidth)
        val bars = barMeasurables.map { it.measure(loose.copy(maxHeight = total)) }
        val barHeight = bars.maxOfOrNull { it.height } ?: 0
        val contents = contentMeasurables.map { it.measure(loose.copy(maxHeight = total)) }
        layout(constraints.maxWidth, total) {
            contents.forEach { it.place(0, 0) }
            val bottom = minOf(total, visible)
            bars.forEach { it.place(0, maxOf(0, bottom - barHeight)) }
        }
    }
}

@Composable
private fun ExportOptions(project: Project, edit: EditState, editor: MovieExportController, readingFormat: Boolean, open: Boolean, toggle: () -> Unit) {
    val settings = ExportLibrary.effectiveSettings(project)
    val canvas = edit.outputSize ?: editor.canvas
    val dimensions = canvas?.let { settings.resolution.size(it) }?.let { "${it.width.toInt()} × ${it.height.toInt()}" } ?: settings.resolution.rawValue
    val summary = if (readingFormat) "Reading video format…" else "$dimensions · ${settings.frameRate.rawValue} fps · ${settings.outputCodec(false).rawValue}"
    val enabled = !readingFormat && !editor.rendering && !editor.hasPhotoSaveInFlight
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ExportColors.card).padding(12.dp).alpha(if (enabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = toggle).testTag("export.settings"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Export settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(summary, fontSize = 15.sp, color = ExportColors.secondary, fontFamily = FontFamily.Default)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (open) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f), thickness = 0.5.dp)
            // Fixed values, shown like iOS shows the fixed HDR codec. Other formats are a TODO.
            OptionRow("Resolution", settings.resolution.rawValue)
            OptionRow("Frame rate", "${settings.frameRate.rawValue} fps")
            OptionRow("Codec", "H.264 · Compatibility")
        }
    }
}

@Composable
private fun OptionRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, fontSize = 17.sp, color = ExportColors.secondary)
    }
}

@Composable
private fun ExportProgressView(progress: ExportProgress) {
    val percent = "${(progress.fraction * 100).toInt()}%"
    val tint = when (progress.step) {
        ExportProgress.Step.opening, ExportProgress.Step.audioEffects, ExportProgress.Step.timeline, ExportProgress.Step.audioPeak -> Color(10, 132, 255)
        ExportProgress.Step.audio, ExportProgress.Step.picture -> ExportColors.teal
        else -> Color(94, 92, 230)
    }
    Column(Modifier.fillMaxWidth().testTag("export.progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(progress.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(percent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ExportColors.secondary)
        }
        LinearProgressIndicator(progress = { progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = tint, trackColor = Color.White.copy(alpha = 0.15f), strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable
private fun ExportFileActions(editor: MovieExportController, title: String, previous: Boolean) {
    val context = LocalContext.current
    val movie = editor.exportURL ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsuleButton(if (previous) "Share previous movie" else "Share latest export", Icons.Outlined.IosShare, ExportColors.accent,
            "export.share", enabled = !editor.rendering) {
            runCatching { MediaSharing.share(context, movie, title = title) }.onFailure { editor.error = it.message }
        }
        if (editor.savedToPhotos) {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = ExportColors.green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (previous) "Previous movie saved to gallery" else "Saved to gallery", fontSize = 17.sp, color = ExportColors.green, modifier = Modifier.testTag("export.saved"))
            }
        } else {
            CapsuleButton(if (editor.savingToPhotos) "Saving…" else if (previous) "Save previous movie to gallery" else "Save to gallery",
                Icons.Outlined.PhotoLibrary, ExportColors.save, "export.saveToGallery", enabled = !editor.savingToPhotos && !editor.rendering) {
                editor.saveToPhotos(title)
            }
        }
    }
}

@Composable
private fun CapsuleButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, tag: String,
                          enabled: Boolean = true, minHeight: Int = 58, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(CircleShape).background(color.copy(alpha = if (enabled) 1f else 0.4f))
        .clickable(enabled = enabled, role = Role.Button, onClick = action).testTag(tag).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 17.sp, color = Color.White.copy(alpha = if (enabled) 1f else 0.6f))
    }
}

/** iOS 26 glass capsule in a sheet's navigation bar ("Done", "Cancel"), accent text. */
@Composable
internal fun SheetPill(title: String, tag: String? = null, color: Color = ExportColors.accent, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.height(36.dp).defaultMinSize(minWidth = 66.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
        .clickable(enabled = enabled, role = Role.Button, onClick = action).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color.copy(alpha = if (enabled) 1f else 0.4f))
    }
}
