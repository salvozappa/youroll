// Port of YouRec/ContentView.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The app shell: three destinations swapped in place (0 Recorder, 1 Projects,
// 2 Editor), the "Changes not saved" and recovery banners, the project list
// with its sheets, and the recorder ↔ ProjectStore wiring.
//
// Android specifics:
// - ContentModel (a ViewModel) owns ProjectStore and CaptureController, the
//   SwiftUI @StateObjects. The store loads off the main thread.
// - System Back maps to the iOS back actions: Editor → Projects, Projects/Edit
//   tab → Recorder. On the Recorder, Back first pauses an active take (as when
//   the app leaves the screen) and otherwise moves the app to the background
//   without finishing it, so a paused take is never lost.
// - Import uses the system Photo Picker (videos), copied into managed storage.
//
// Not ported yet (TODO.md): Zip project and ZIP import, Repair media/relink,
// Unfinished recordings sheet, privacy policy, recording insertion from the
// editor, export checkpoints at startup, DEBUG self-test launch arguments.
package com.salvozappa.youroll

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object CatalogueColors {
    val accent = Color(1f, 0.25f, 0.29f)
    val surface = Color(0.09f, 0.10f, 0.13f)
    val background = Color(0.035f, 0.04f, 0.055f)
    val edit = Color(0.06f, 0.35f, 0.72f)
    val export = Color(36, 83, 55)
    val secondary = Color(235, 235, 245).copy(alpha = 0.6f)
    val green = Color(48, 209, 88)
    val orange = Color(255, 159, 10)
    val blue = Color(10, 132, 255)
    val sheet = Color(28, 28, 30)
    val grouped = Color(44, 44, 46)
    val bar = Color(30, 30, 32).copy(alpha = 0.94f)
}

/** The SwiftUI @StateObjects of ContentView (`store`, `camera`) and its `screen`. */
@ExperimentalCamera2Interop
class ContentModel(application: Application) : AndroidViewModel(application) {
    val camera = CaptureController(application)
    var store by mutableStateOf<ProjectStore?>(null); private set
    var screen by mutableIntStateOf(0)
    // iOS `insertingRecording` / `insertionHasStarted`: "Record here" from the editor.
    var insertingRecording by mutableStateOf(false)
    var insertionHasStarted by mutableStateOf(false)
    // Held by the ViewModel so an activity recreation never cancels a running export.
    var exportingProject by mutableStateOf<UUID?>(null)
    val exportController = MovieExportController(application, viewModelScope)
    var startupRecoveryComplete by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // Saves run on one background writer; their results are applied on the main thread.
                ProjectStore(File(application.filesDir, "Projects"), AndroidMediaProbe(),
                    SharedProjectPreferences(application.getSharedPreferences("projects", Context.MODE_PRIVATE)),
                    saveDispatchers = SaveDispatchers(Dispatchers.IO.limitedParallelism(1), Dispatchers.Main))
            }
            store = loaded
            startup(loaded)
        }
    }

    /** iOS ContentView `.task`. */
    private suspend fun startup(store: ProjectStore) {
        if (store.projects.value.isEmpty()) store.create()
        camera.sink = ProjectCaptureSink(store, viewModelScope)
        startupRecoveryComplete = true
        store.recoverUnindexedRecordings()
    }

    override fun onCleared() { camera.release() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun sourceSummary(project: Project): String {
    val sounds = project.sources.count { it.audioOnly == true }
    val takes = project.sources.size - sounds
    val parts = mutableListOf<String>()
    if (takes > 0 || sounds == 0) parts += if (takes == 1) "1 take" else "$takes takes"
    if (sounds > 0) parts += if (sounds == 1) "1 audio file" else "$sounds audio files"
    return parts.joinToString(" · ")
}

private fun timeLabel(seconds: Double): String {
    val value = maxOf(0, if (seconds.isFinite()) seconds.toInt() else 0)
    return "%02d:%02d".format(value / 60, value % 60)
}

@OptIn(ExperimentalComposeUiApi::class)
@ExperimentalCamera2Interop
@Composable
fun ContentView(model: ContentModel) {
    val store = model.store
    if (store == null) { Box(Modifier.fillMaxSize().background(Color.Black)); return }
    val camera = model.camera
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val projects by store.projects.collectAsState()
    val selectedID by store.selectedID.collectAsState()
    val unsavedDrafts by store.unsavedDrafts.collectAsState()
    val journaledDrafts by store.journaledDrafts.collectAsState()
    val recoveryMessages by store.recoveryMessages.collectAsState()
    val storeError by store.error.collectAsState()
    val importing by store.importing.collectAsState()
    val selected = projects.firstOrNull { it.id == selectedID }
    val screen = model.screen
    var originals by remember { mutableStateOf<UUID?>(null) }
    var exportingProject by model::exportingProject

    fun finishInsertion() {
        if (camera.hasActiveTake) return
        store.selectedID.value?.let { id -> store.update(id) { it.recordingInsertion = null; it.recordingInsertionStep = null } }
        model.insertingRecording = false; model.insertionHasStarted = false
        camera.finishPausedTake(); model.screen = 2
    }
    fun startRecording() {
        val project = store.selected ?: store.create() ?: return
        if (project.id in store.recoveringIDs.value) {
            store.setError("Recovering the last take in this project. You can start a new project while it finishes."); return
        }
        if (model.insertingRecording) model.insertionHasStarted = true
        if (store.unsavedDrafts.value[project.id] != null) {
            store.retrySaves()
            if (store.unsavedDrafts.value[project.id] != null) return
        }
        camera.start(store.folder(project).also { it.mkdirs() })
    }
    fun editFromRecorder() {
        if (camera.hasActiveTake) return
        if (model.insertingRecording) { finishInsertion(); return }
        // The library can be empty after deleting its last project.
        if (store.selected == null && store.create() == null) return
        model.screen = 2
    }
    fun startNewProject() {
        if (camera.hasActiveTake || store.importing.value || store.selected?.hasSourceMedia != true || store.create() == null) return
        activity?.window?.decorView?.announceForAccessibility("New project ready to record")
    }

    // iOS `.onChange(of: screen)`: the camera runs only on the recorder.
    LaunchedEffect(screen, model.startupRecoveryComplete) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        if (screen == 0) { if (model.startupRecoveryComplete && owner != null && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) camera.prepare(owner) }
        else camera.suspend(forPlayback = true)
        YouRollApp.keepAwake("camera-screen", screen == 0)
        // The recorder hides the status bar (`.statusBarHidden()`); the catalogue shows it.
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (screen == 0) controller.hide(WindowInsetsCompat.Type.statusBars()) else controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    // iOS `.onChange(of: store.selectedID) { camera.finishPausedTake() }`
    LaunchedEffect(selectedID) { camera.finishPausedTake() }
    // iOS `.onChange(of: camera.state)`: a finished insertion take returns to the editor.
    var previousState by remember { mutableStateOf(camera.state) }
    LaunchedEffect(camera.state) {
        val old = previousState; previousState = camera.state
        if (model.insertingRecording && model.insertionHasStarted && camera.state == CaptureState.idle && old != CaptureState.idle) finishInsertion()
    }
    // Every screen rotates as on iOS (`allowedOrientations = .allButUpsideDown`, Android's
    // USER). The recorder pins the current orientation while a take is active or paused
    // (iOS `setOrientationLock(orientation)` when `keepFraming`), and releases it after Stop.
    val keepFraming = screen == 0 && (camera.hasActiveTake || camera.state == CaptureState.paused)
    LaunchedEffect(screen, keepFraming) {
        activity?.requestedOrientation = if (keepFraming) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
    }
    LaunchedEffect(camera.hasActiveTake) {
        YouRollApp.keepAwake("recording", camera.hasActiveTake)
        if (!camera.hasActiveTake) YouRollApp.finishedPendingSave()
    }

    BackHandler {
        when {
            screen == 2 && selected != null -> model.screen = 1
            screen != 0 -> model.screen = 0
            camera.hasActiveTake -> camera.stop(pause = true)
            else -> activity?.moveTaskToBack(true)
        }
    }

    Column(Modifier.fillMaxSize().background(CatalogueColors.background).semantics { testTagsAsResourceId = true }) {
        // iOS `.safeAreaInset(edge: .top)`
        val bannerInsets = if (screen == 0) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier.statusBarsPadding()
        if (unsavedDrafts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(CatalogueColors.bar).then(bannerInsets).padding(10.dp).testTag("recovery.unsaved"),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = CatalogueColors.orange, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Changes not saved", color = CatalogueColors.orange, fontSize = 17.sp)
                    }
                    if (!journaledDrafts.containsAll(unsavedDrafts.keys)) {
                        Text("Keep YouRoll open. New edits are only in memory.", color = CatalogueColors.orange, fontSize = 13.sp)
                    }
                }
                TextButton({ store.retrySaves() }) { Text("Retry save", color = CatalogueColors.orange, fontSize = 17.sp) }
            }
        } else if (!camera.hasActiveTake) {
            val id = recoveryMessages.keys.minByOrNull { it.uuidString }
            val message = id?.let { recoveryMessages[it] }
            if (id != null && message != null) {
                Row(Modifier.fillMaxWidth().background(CatalogueColors.bar).then(bannerInsets).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton({ originals = id }) { Text("Review", color = CatalogueColors.accent, fontSize = 17.sp) }
                    Box(Modifier.size(44.dp).clickable(role = Role.Button) { store.setRecoveryMessage(id, null) }
                        .semantics { contentDescription = "Dismiss recovery notice" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, null, tint = CatalogueColors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                screen == 0 -> RecorderView(
                    camera = camera,
                    project = selected?.let { RecorderProjectSummary(it.title, it.edit.duration, it.capturedDuration, it.hasSourceMedia) },
                    start = ::startRecording,
                    startNewProject = ::startNewProject,
                    openProjects = { if (model.insertingRecording) finishInsertion() else model.screen = 1 },
                    openEditor = ::editFromRecorder,
                    insertionPosition = if (model.insertingRecording) selected?.recordingInsertion else null,
                )
                screen == 2 && selected != null -> EditorView(store, selected.id, back = { model.screen = 1 },
                    recordHere = { position, site ->
                        camera.finishPausedTake()
                        if (store.update(selected.id) { it.beginRecordingInsertion(position.seconds, site) }) {
                            model.insertionHasStarted = false; model.insertingRecording = true; model.screen = 0
                        }
                    },
                    export = { if (store.openProject(selected.id)) exportingProject = selected.id })
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.White)) { append("you") }
                            withStyle(SpanStyle(color = CatalogueColors.accent)) { append("roll") }
                        // iOS: SF Rounded heavy 29 pt. No rounded face ships with Android, so the
                        // system font at its heaviest weight stands in (TODO.md).
                        }, fontSize = 29.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Text(if (screen == 1) "PROJECTS" else "EDITOR", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold, color = CatalogueColors.secondary)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (screen == 1) ProjectList(store, model, openOriginals = { originals = it }, export = { id ->
                            if (store.openProject(id)) exportingProject = id
                        }) else {
                            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_film_stack), null, tint = CatalogueColors.secondary, modifier = Modifier.size(44.dp))
                                Text("Choose a project", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Record a take or import a video to begin editing.", fontSize = 15.sp, color = CatalogueColors.secondary)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().background(CatalogueColors.surface).padding(top = 8.dp, bottom = 4.dp).navigationBarsPadding()) {
                        val enabled = !camera.hasActiveTake && !importing
                        Destination("Record", Icons.Outlined.RadioButtonChecked, 0, screen, enabled) { model.screen = 0 }
                        Destination("Projects", ImageVector.vectorResource(R.drawable.ic_square_stack), 1, screen, enabled) { model.screen = 1 }
                        Destination("Edit", Icons.Filled.Tune, 2, screen, enabled) { model.screen = 2 }
                    }
                }
            }
        }
    }

    exportingProject?.let { id -> MovieExportPanel(store, id, onDismiss = { exportingProject = null }, controller = model.exportController) }
    originals?.let { id -> OriginalRecordingsView(store, id) { originals = null } }

    val alert = storeError ?: if (screen != 0) camera.error else null
    if (alert != null) {
        AlertDialog(onDismissRequest = { store.setError(null); camera.error = null },
            title = { Text("YouRoll") }, text = { Text(alert) },
            confirmButton = { TextButton({ store.setError(null); camera.error = null }) { Text("OK", color = CatalogueColors.accent) } },
            dismissButton = if (unsavedDrafts.isNotEmpty()) ({ TextButton({ store.retrySaves() }) { Text("Retry save", color = CatalogueColors.accent) } }) else null,
            containerColor = CatalogueColors.sheet, titleContentColor = Color.White, textContentColor = Color.White)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Destination(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                                                                   index: Int, screen: Int, enabled: Boolean, action: () -> Unit) {
    val tint = if (screen == index) CatalogueColors.accent else CatalogueColors.secondary
    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Tab, onClick = action)
        .testTag("catalogue.tab.$index").alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

// MARK: - Project list

@ExperimentalCamera2Interop
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectList(store: ProjectStore, model: ContentModel, openOriginals: (UUID) -> Unit, export: (UUID) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by store.projects.collectAsState()
    val deleted by store.deletedProjects.collectAsState()
    val selectedID by store.selectedID.collectAsState()
    val importing by store.importing.collectAsState()
    val progress by store.operationProgress.collectAsState()
    val label by store.operationLabel.collectAsState()
    val unavailable by store.unavailableProjects.collectAsState()
    var renaming by remember { mutableStateOf<Project?>(null) }
    var showingDeleted by remember { mutableStateOf(false) }
    var duplicatedProjectID by remember { mutableStateOf<UUID?>(null) }
    var staging by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var expiredDeletions by remember { mutableStateOf<List<ProjectDeletion>>(emptyList()) }
    // The project whose history is being decoded before it opens (a library card holds a summary).
    var opening by remember { mutableStateOf<UUID?>(null) }
    val listState = rememberLazyListState()

    fun open(id: UUID, then: () -> Unit) {
        if (opening != null) return
        opening = id
        scope.launch {
            try {
                if (store.prepareProject(id)) then()
            } finally {
                opening = null
            }
        }
    }

    LaunchedEffect(Unit) {
        // iOS `.onAppear`: offer to clean up projects deleted more than 30 days ago.
        if (!store.importing.value && store.error.value == null) expiredDeletions = store.expiredDeletions()
    }
    LaunchedEffect(duplicatedProjectID) {
        val id = duplicatedProjectID ?: return@LaunchedEffect
        val index = store.projects.value.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(2 + index)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        operation = scope.launch {
            staging = true
            var staged: File? = null
            try {
                staged = MediaImportStaging.stage(context, uri)
                staging = false
                store.importVideo(staged)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                store.setError("Import failed: " + (failure.message ?: failure.toString()))
            } finally {
                staging = false
                staged?.let { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { MediaImportStaging.discard(it) } }
                operation = null
            }
        }
    }
    val busy = importing || staging

    Column(Modifier.fillMaxSize()) {
        if (busy) {
            Row(Modifier.fillMaxWidth().background(CatalogueColors.bar).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (staging) "Copying original video…" else label, fontSize = 15.sp, color = Color.White)
                    LinearProgressIndicator(progress = { if (staging) 0f else progress.toFloat() }, modifier = Modifier.fillMaxWidth(),
                        color = CatalogueColors.accent, trackColor = Color.White.copy(alpha = 0.15f), gapSize = 0.dp, drawStopIndicator = {})
                }
                TextButton({ operation?.cancel() }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Cancel", color = CatalogueColors.accent, fontSize = 17.sp) }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Pick up where you left off.", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BorderedButton("New project", Icons.Filled.Add, enabled = !busy) { if (store.create() != null) model.screen = 0 }
                    Spacer(Modifier.weight(1f))
                    BorderedButton("Import", ImageVector.vectorResource(R.drawable.ic_tray_arrow_down), enabled = !busy) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    }
                }
            }
            items(projects, key = { it.id.uuidString }) { project ->
                ProjectCard(store, project, selected = project.id == selectedID, busy = busy, opening = project.id == opening,
                    rename = { renaming = it },
                    duplicate = { scope.launch { store.duplicate(it)?.let { copy -> duplicatedProjectID = copy.id } } },
                    openOriginals = openOriginals,
                    record = { open(project.id) { if (store.select(project.id)) model.screen = 0 } },
                    edit = { open(project.id) { if (store.select(project.id)) model.screen = 2 } },
                    export = { id -> open(id) { export(id) } })
            }
            items(unavailable.keys.sortedBy { it.uuidString }, key = { "unavailable-" + it.uuidString }) { id ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CatalogueColors.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Project needs recovery", fontSize = 17.sp, color = Color.White)
                    }
                    Text(unavailable[id] ?: "", fontSize = 13.sp, color = CatalogueColors.secondary)
                    BorderedButton("Open original recordings", null) { openOriginals(id) }
                }
            }
            if (deleted.isNotEmpty()) item {
                Row(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { showingDeleted = true }.testTag("catalogue.recentlyDeleted"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Delete, null, tint = CatalogueColors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recently deleted (${deleted.size})", fontSize = 17.sp, color = CatalogueColors.accent)
                }
            }
        }
    }

    renaming?.let { project -> RenameSheet(project, dismiss = { renaming = null }) { store.rename(project, it); renaming = null } }
    if (showingDeleted) RecentlyDeletedSheet(store) { showingDeleted = false }
    if (expiredDeletions.isNotEmpty()) {
        val count = expiredDeletions.size
        AlertDialog(onDismissRequest = { expiredDeletions = emptyList() },
            title = { Text("Delete older projects?") },
            text = { Text("${if (count == 1) "1 project has" else "$count projects have"} been in Recently deleted for more than 30 days. Permanently delete ${if (count == 1) "it" else "them"}, including original recordings, edits and exports? This cannot be undone.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({ store.eraseExpiredDeletions(expiredDeletions); expiredDeletions = emptyList() }) {
                        Text(if (count == 1) "Delete 1 project" else "Delete $count projects", color = CatalogueColors.accent)
                    }
                    TextButton({ expiredDeletions = emptyList(); showingDeleted = true }) { Text("Review", color = Color.White) }
                    TextButton({ expiredDeletions = emptyList() }) { Text("Not now", color = Color.White) }
                }
            },
            containerColor = CatalogueColors.sheet, titleContentColor = Color.White, textContentColor = Color.White)
    }
}

@Composable
private fun BorderedButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.heightIn(min = 36.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(CatalogueColors.accent.copy(alpha = 0.15f))
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, null, tint = CatalogueColors.accent, modifier = Modifier.size(20.dp)) }
        Text(title, fontSize = 17.sp, color = CatalogueColors.accent)
    }
}

@Composable
private fun ProjectCard(store: ProjectStore, project: Project, selected: Boolean, busy: Boolean, opening: Boolean,
                        rename: (Project) -> Unit, duplicate: (Project) -> Unit, openOriginals: (UUID) -> Unit,
                        record: () -> Unit, edit: () -> Unit, export: (UUID) -> Unit) {
    val duration = project.edit.duration
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CatalogueColors.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectThumbnail(project.sources.firstOrNull()?.let { store.mediaFile(project, it) },
                Modifier.size(64.dp, 56.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).heightIn(min = 44.dp).clickable(enabled = !busy, role = Role.Button) { rename(project) }
                .semantics(mergeDescendants = true) { contentDescription = "Rename " + project.title },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(project.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Outlined.Edit, null, tint = CatalogueColors.secondary, modifier = Modifier.size(13.dp))
            }
            if (opening) CircularProgressIndicator(color = CatalogueColors.accent, strokeWidth = 2.dp,
                modifier = Modifier.padding(horizontal = 4.dp).size(20.dp).semantics { contentDescription = "Opening " + project.title })
            else if (selected) Icon(Icons.Filled.CheckCircle, "Selected", tint = CatalogueColors.green, modifier = Modifier.padding(horizontal = 4.dp).size(20.dp))
            Box {
                Box(Modifier.size(44.dp).clickable(enabled = !busy, role = Role.Button) { menu = true }
                    .semantics { contentDescription = "Actions for " + project.title }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MoreHoriz, null, tint = CatalogueColors.accent.copy(alpha = if (busy) 0.4f else 1f), modifier = Modifier.size(24.dp))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Color(48, 48, 52), shape = RoundedCornerShape(22.dp)) {
                    MenuItem("Rename", Icons.Outlined.Edit) { menu = false; rename(project) }
                    MenuItem("Duplicate project", Icons.Outlined.LibraryAdd) { menu = false; duplicate(project) }
                    MenuItem("Original recordings", ImageVector.vectorResource(R.drawable.ic_film_stack)) { menu = false; openOriginals(project.id) }
                    MenuItem("Move to Recently deleted", Icons.Outlined.Delete, destructive = true) { menu = false; store.moveToDeleted(project) }
                }
            }
        }
        val missing = store.missingSources(project)
        if (missing.isNotEmpty()) {
            val affectsTimeline = !project.edit.timeline.items(missing.map { it.id }.toSet()).isEmpty
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val color = if (affectsTimeline) CatalogueColors.orange else CatalogueColors.secondary
                Icon(if (affectsTimeline) Icons.Filled.Warning else Icons.Outlined.Info, null, tint = color, modifier = Modifier.size(18.dp))
                Text(if (affectsTimeline) "Repair media · ${missing.size} missing" else "${missing.size} missing from history", fontSize = 17.sp, color = color)
            }
        }
        Text(sourceSummary(project) + " · " + timeLabel(duration) + " edited", fontSize = 15.sp, color = CatalogueColors.secondary)
        ProjectActions(project, canExport = duration > 0, busy = busy, record = record, edit = edit, export = { export(project.id) })
    }
}

@Composable
private fun MenuItem(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) {
    // iOS menu items are 17 pt regular; the Material default is medium weight.
    DropdownMenuItem(text = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Normal, color = if (destructive) CatalogueColors.accent else Color.White) },
        leadingIcon = { Icon(icon, null, tint = CatalogueColors.accent, modifier = Modifier.size(22.dp)) }, onClick = action,
        modifier = Modifier.width(250.dp))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProjectActions(project: Project, canExport: Boolean, busy: Boolean, record: () -> Unit, edit: () -> Unit, export: () -> Unit) {
    // iOS ViewThatFits: one row when it fits, otherwise Continue above Edit … Export.
    FlowRow(Modifier.fillMaxWidth().alpha(if (busy) 0.4f else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.heightIn(min = 58.dp).clip(CircleShape).background(CatalogueColors.accent).clickable(enabled = !busy, role = Role.Button, onClick = record)
            .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text("Continue recording", fontSize = 17.sp, color = Color.White, maxLines = 1)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 58.dp).clip(CircleShape).background(CatalogueColors.edit).clickable(enabled = !busy, role = Role.Button, onClick = edit)
                .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Tune, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Edit", fontSize = 17.sp, color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            // iOS fades the whole tile, fill included (`.opacity` after `.background`), so alpha comes first.
            Box(Modifier.size(46.dp).alpha(if (canExport) 1f else 0.35f).clip(RoundedCornerShape(12.dp)).background(CatalogueColors.export)
                .clickable(enabled = canExport && !busy, role = Role.Button, onClick = export)
                .semantics { contentDescription = "Export video for " + project.title }
                .testTag("project.export." + project.id.uuidString), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.IosShare, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** First frame of the project's first source, or a placeholder (iOS `ProjectThumbnail`). */
@Composable
fun ProjectThumbnail(file: File?, modifier: Modifier = Modifier) {
    var thumbnail by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        val movie = file ?: return@LaunchedEffect
        thumbnail = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(movie.absolutePath)
                val frame: Bitmap? = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 168)
                frame?.asImageBitmap()
            } catch (_: Exception) { null } finally { retriever.release() }
        }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val image = thumbnail
        if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(ImageVector.vectorResource(R.drawable.ic_film_stack), null, tint = CatalogueColors.secondary, modifier = Modifier.size(22.dp))
    }
}

// MARK: - Sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSheet(project: Project, dismiss: () -> Unit, save: (String) -> Unit) {
    var title by remember(project.id) { mutableStateOf(project.title) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CatalogueColors.sheet, dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }) {
        Column(Modifier.fillMaxWidth().heightIn(min = 320.dp).semantics { testTagsAsResourceId = true }) {
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { SheetPill("Cancel", "rename.cancel", action = dismiss) }
                Text("Rename project", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) {
                    SheetPill("Save", "rename.save", enabled = title.isNotBlank()) { save(title) }
                }
            }
            TextField(title, { title = it }, singleLine = true, placeholder = { Text("Project name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(focusedContainerColor = CatalogueColors.grouped, unfocusedContainerColor = CatalogueColors.grouped,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = CatalogueColors.accent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(20.dp).focusRequester(focus).testTag("rename.field"))
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentlyDeletedSheet(store: ProjectStore, dismiss: () -> Unit) {
    val deleted by store.deletedProjects.collectAsState()
    val records by store.deletionRecords.collectAsState()
    var erasing by remember { mutableStateOf<Project?>(null) }
    val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    val now = remember { SwiftDate.now() }
    // The iOS sheet has a single detent and so no grabber: only the nav bar with Done.
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CatalogueColors.sheet, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(top = 8.dp).semantics { testTagsAsResourceId = true }) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Text("Recently deleted", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { SheetPill("Done", "deleted.done", color = Color.White, action = dismiss) }
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Text("Originals and editing history stay here until you permanently delete them.", fontSize = 15.sp, lineHeight = 20.sp,
                        color = CatalogueColors.secondary, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                            .background(CatalogueColors.grouped).padding(horizontal = 20.dp, vertical = 14.dp))
                }
                items(deleted, key = { it.id.uuidString }) { project ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CatalogueColors.grouped).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(project.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        val record = records[project.id]
                        if (record != null) {
                            Text((if (record.dateIsKnown) "Deleted " else "Age tracked since ") + format.format(record.date.toInstant()),
                                fontSize = 12.sp, color = CatalogueColors.secondary)
                            if (record.isOlderThan30Days(now)) Text("Over 30 days", fontSize = 12.sp, color = CatalogueColors.orange)
                        } else {
                            Text("Deletion date unavailable · excluded from bulk cleanup", fontSize = 12.sp, color = CatalogueColors.secondary)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            GreyCapsule("Restore", CatalogueColors.blue, "Restore " + project.title) { store.restoreDeleted(project) }
                            Spacer(Modifier.weight(1f))
                            GreyCapsule("Delete permanently", CatalogueColors.accent) { erasing = project }
                        }
                    }
                }
            }
        }
    }
    erasing?.let { project ->
        AlertDialog(onDismissRequest = { erasing = null },
            text = { Text("Permanently delete this project and its original takes?") },
            confirmButton = { TextButton({ store.eraseDeleted(project); erasing = null }) { Text("Delete permanently", color = CatalogueColors.accent) } },
            dismissButton = { TextButton({ erasing = null }) { Text("Cancel", color = Color.White) } },
            containerColor = CatalogueColors.sheet, textContentColor = Color.White)
    }
}

@Composable
private fun GreyCapsule(title: String, color: Color, description: String? = null, action: () -> Unit) {
    Box(Modifier.heightIn(min = 36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(role = Role.Button, onClick = action)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(title, fontSize = 17.sp, color = color)
    }
}
