// Port of YouRec/AddVideoPanel.swift, with the Photos batch insertion of
// YouRec/ProjectStore.swift (`insertVideos(_:into:at:site:completion:)`).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// iOS uses PhotosPicker in ordered multi-selection; Android uses the system
// Photo Picker, which returns items in the order they were tapped. Each item
// is copied through its content Uri into a private staged file first.
// The "Unused takes" picker (Swift `UnusedTakePicker`) is a page of the same
// sheet: tap takes in playback order, drag a numbered row's handle to reorder.
package com.salvozappa.youroll

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AddVideoPanel(
    store: ProjectStore,
    project: Project,
    position: Double,
    site: VideoInsertionSite?,
    pasteHere: (() -> Unit)? = null,
    close: () -> Unit,
    backToTools: Boolean,
    record: () -> Unit,
    inserted: (Int, Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var retryItems by remember { mutableStateOf(emptyList<Uri>()) }
    var progress by remember { mutableStateOf(0.0) }
    var label by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var choosingTakes by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val unused = unusedTakes(project)
    val destinationTitle = site?.label ?: "Insert at ${editorTime(position)}"
    val placement = when (site) {
        VideoInsertionSite.start -> if (project.edit.timeline.video.isEmpty()) "The first starts at 0:00." else "The last one meets your first clip."
        is VideoInsertionSite.gap -> "They fill the gap from ${editorTime(position)}, moving later footage forward if needed."
        VideoInsertionSite.end -> "The first starts where your last clip ends."
        null -> "The first starts at the playhead."
    }
    val instructions = "Choose photos and videos in playback order. $placement Each photo lasts ${PhotoClip.seconds} seconds."

    fun importPhotos(items: List<Uri>) {
        if (loading || items.isEmpty()) return
        loading = true; cancelling = false; problem = null; retryItems = items
        job = scope.launch {
            try {
                val start = insertMedia(context, store, project.id, items, position, site) { value, text -> progress = value; label = text }
                retryItems = emptyList(); loading = false
                inserted(items.size, start)
            } catch (failure: CancellationException) {
                problem = "Import cancelled."; loading = false
            } catch (failure: Exception) {
                problem = failure.message ?: failure.toString(); loading = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { importPhotos(it) }

    if (choosingTakes && unused.isNotEmpty()) {
        UnusedTakePicker(unused, store.folder(project), destinationTitle, back = { choosingTakes = false }) { ordered ->
            if (store.update(project.id) { it.insertVideos(ordered, position, site) }) {
                val start = store.projects.value.firstOrNull { it.id == project.id }?.edit?.timeline?.video
                    ?.firstOrNull { it.content.sourceID == ordered.firstOrNull()?.id }?.start?.seconds
                inserted(ordered.size, start ?: position)
            } else {
                choosingTakes = false
                problem = store.error.value ?: "Couldn’t insert the selected takes."
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SheetBar("Add video or photo", leading = if (loading) "Cancel import" else if (backToTools) "Back" else "Cancel",
            leadingAction = { if (loading) { cancelling = true; job?.cancel() } else close() })
        Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // iOS `Label(…, systemImage: "playhead")`: that symbol does not exist, so iOS shows the title alone.
            Text(destinationTitle, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (loading) {
                Text(if (cancelling) "Cancelling import…" else label, color = Color.White, fontSize = 15.sp)
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = EditorColors.accent)
            } else {
                Text(instructions, color = EditorColors.secondary, fontSize = 15.sp, lineHeight = 20.sp)
                problem?.let {
                    Text(it, color = EditorColors.orange, fontSize = 15.sp)
                    if (retryItems.isNotEmpty()) Text("Retry selection", color = EditorColors.accent, fontSize = 17.sp,
                        modifier = Modifier.heightIn(min = 44.dp).clickable { importPhotos(retryItems) }.padding(vertical = 10.dp))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).background(EditorColors.accent, RoundedCornerShape(50))
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("From Photos", color = Color.White, fontSize = 17.sp)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).background(EditorColors.accent.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .clickable(onClick = record), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.RadioButtonChecked, null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Record here", color = EditorColors.accent, fontSize = 17.sp)
                }
                if (pasteHere != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).background(EditorColors.accent.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .clickable(onClick = pasteHere), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ContentPaste, null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Paste here", color = EditorColors.accent, fontSize = 17.sp)
                    }
                }
                if (unused.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { choosingTakes = true }
                        .semantics(mergeDescendants = true) {}.testTag("addVideo.unused"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_film_stack), null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Unused takes (${unused.size})", color = EditorColors.accent, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = EditorColors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(240.dp))
        }
    }
}

/** Swift `AddVideoPanel.unused`: video takes of the project that no clip of the edit uses, in recording order. */
fun unusedTakes(project: Project): List<SourceMedia> {
    val used = project.edit.clips.map { it.sourceID }.toSet()
    return project.sources.filter { it.audioOnly != true && it.id !in used }
}

/**
 * Swift `UnusedTakePicker`: takes are tapped in playback order and numbered;
 * a numbered row is removed with a tap and reordered with its drag handle
 * (iOS edit-mode reorder control).
 */
@Composable
private fun UnusedTakePicker(takes: List<SourceMedia>, folder: File, destinationTitle: String, back: () -> Unit, insert: (List<SourceMedia>) -> Unit) {
    var selected by remember { mutableStateOf(emptyList<UUID>()) }
    val byID = takes.associateBy { it.id }
    val ordered = selected.mapNotNull { byID[it] }
    val available = takes.filter { it.id !in selected }
    val density = LocalDensity.current
    val rowHeight = 64.dp
    val rowPixels = with(density) { rowHeight.toPx() }
    var dragging by remember { mutableStateOf<UUID?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val current by rememberUpdatedState(selected)
    val time = remember { java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withZone(java.time.ZoneId.systemDefault()) }

    @Composable
    fun RowScope.label(take: SourceMedia) {
        ProjectThumbnail(File(folder, take.filename), Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(take.originalName ?: time.format(take.recordedAt.toInstant()), color = Color.White, fontSize = 17.sp, maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis)
            Text(editorTime(take.duration), color = EditorColors.secondary, fontSize = 12.sp, style = tabular)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SheetBar("Unused takes", leading = "Back", leadingAction = back, trailing = if (ordered.isEmpty()) "Insert" else "Insert ${ordered.size}",
            trailingEnabled = ordered.isNotEmpty(), trailingBold = true, trailingAction = { insert(ordered) })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ordered.isNotEmpty()) {
                Text("Playback order", color = EditorColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                Column(Modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp))) {
                    for ((index, take) in ordered.withIndex()) key(take.id) {
                        val lifted = dragging == take.id
                        Row(Modifier.fillMaxWidth().height(rowHeight).zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                            .background(if (lifted) Color(0.2f, 0.2f, 0.2f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { selected = selected - take.id }
                            .semantics { stateDescription = "Remove from this selection" }.testTag("addVideo.ordered.${index + 1}")
                            .padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", color = EditorColors.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = tabular,
                                modifier = Modifier.width(24.dp))
                            Spacer(Modifier.width(8.dp))
                            label(take)
                            Icon(Icons.Outlined.Cancel, null, tint = EditorColors.secondary, modifier = Modifier.size(22.dp))
                            Box(Modifier.size(48.dp, rowHeight).semantics { contentDescription = "Reorder" }
                                .pointerInput(take.id) {
                                    detectDragGestures(
                                        onDragStart = { dragging = take.id; dragOffset = 0f },
                                        onDragEnd = { dragging = null; dragOffset = 0f },
                                        onDragCancel = { dragging = null; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val list = current
                                        val from = list.indexOf(take.id)
                                        val to = when {
                                            dragOffset > rowPixels / 2 && from < list.size - 1 -> from + 1
                                            dragOffset < -rowPixels / 2 && from > 0 -> from - 1
                                            else -> from
                                        }
                                        if (from >= 0 && to != from) {
                                            selected = list.toMutableList().apply { add(to, removeAt(from)) }
                                            dragOffset -= (to - from) * rowPixels
                                        }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.DragHandle, null, tint = EditorColors.secondary, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            if (available.isNotEmpty()) {
                Text("Available takes", color = EditorColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                Column(Modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp))) {
                    for (take in available) key(take.id) {
                        Row(Modifier.fillMaxWidth().height(rowHeight).clickable { selected = selected + take.id }
                            .testTag("addVideo.take.${take.id.uuidString}").padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AddCircleOutline, null, tint = EditorColors.accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            label(take)
                        }
                    }
                }
                Text("Tap takes in playback order. Drag the selected rows to rearrange them.", color = EditorColors.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/**
 * Swift `ProjectStore.insertVideos(_:into:at:site:)`: copy each picked item,
 * turn photos into silent clips, then publish the whole batch as one edit.
 * Returns the start of the first inserted clip.
 */
suspend fun insertMedia(context: Context, store: ProjectStore, projectID: UUID, items: List<Uri>, position: Double,
                        site: VideoInsertionSite?, progress: (Double, String) -> Unit): Double {
    val project = store.projects.value.firstOrNull { it.id == projectID } ?: throw ProjectError.invalidDocument
    val probe = store.mediaProbe ?: throw IllegalStateException("The project store has no media probe.")
    val batch = withContext(Dispatchers.IO) { VideoBatchImport(store.folder(project)) }
    var preparing: Int? = null
    try {
        progress(0.0, "Loading 1 of ${items.size} from Photos…")
        for ((i, uri) in items.withIndex()) {
            preparing = i
            currentCoroutineContext().ensureActive()
            val count = "${i + 1} of ${items.size}"
            progress(i.toDouble() / (items.size + 1), "Loading $count from Photos…")
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)
            val isPhoto = mime?.startsWith("image/") == true
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: if (isPhoto) "jpg" else "mp4"
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
                }
            }.getOrNull()
            val copy = File(batch.directory, newUUID().uuidString + "." + ext)
            withContext(Dispatchers.IO) {
                if (batch.directory.usableSpace < (name?.second ?: 0L) + 256_000_000L) throw IllegalStateException("There is not enough free space.")
                (resolver.openInputStream(uri) ?: throw ProjectError.invalidMedia).use { input ->
                    copy.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
            }
            try {
                progress((i + 0.5) / (items.size + 1), if (isPhoto) "Creating photo clip $count…" else "Checking video $count…")
                batch.stage(copy, name?.first, isPhoto, probe, context)
            } finally { copy.delete() }
            progress((i + 1).toDouble() / (items.size + 1), "Loading $count from Photos…")
        }
        preparing = null
        progress(items.size.toDouble() / (items.size + 1), "Inserting clips…")
        val saved = commitBatch(batch, store, projectID, position, site)
        progress(1.0, "Inserting clips…")
        return saved.edit.timeline.video.firstOrNull { it.content.sourceID == batch.sources.firstOrNull()?.id }?.start?.seconds ?: position
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        val message = preparing?.let { "Couldn’t add item ${it + 1} of ${items.size}." } ?: "Couldn’t insert the clips."
        throw IllegalStateException(message + " " + (failure.message ?: failure.toString()), failure)
    } finally {
        // withContext rethrows a pending cancellation on return; it must not turn a saved batch into a cancel.
        try {
            withContext(NonCancellable + Dispatchers.IO) { batch.discard() }
        } catch (_: CancellationException) {
        }
    }
}

/**
 * Runs [VideoBatchImport.commit] on [dispatcher]. Cancellation only counts
 * before the commit starts: once it has saved, the clips are in the project
 * and on the undo stack, so the saved project is returned even when the
 * caller was cancelled meanwhile (Swift checks cancellation only before
 * `disk.save`).
 */
internal suspend fun commitBatch(batch: VideoBatchImport, store: ProjectStore, projectID: UUID, position: Double,
                                 site: VideoInsertionSite?, dispatcher: CoroutineDispatcher = Dispatchers.Main): Project {
    currentCoroutineContext().ensureActive()
    var saved: Project? = null
    try {
        withContext(dispatcher + NonCancellable) { saved = batch.commit(store, projectID, position, site) }
    } catch (failure: CancellationException) {
        saved?.let { return it }
        throw failure
    }
    return saved ?: throw ProjectError.invalidDocument
}
