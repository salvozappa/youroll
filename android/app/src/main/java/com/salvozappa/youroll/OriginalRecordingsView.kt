// Port of YouRec/OriginalRecordingsView.swift, with `OriginalRecording` and
// `ProjectDisk.originals(in:project:)` from YouRec/RecordingSalvage.swift.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Lists a project's original media files. Each can be shared (iOS ShareLink)
// or saved to the gallery (`Movies/YouRoll`, MediaStore) without touching the
// original. Not ported yet (TODO.md): the per-original review sheet
// (`OriginalReview`: player, re-inserting a range, repair copy), "Create
// recovered project", "Retry interrupted recovery" and "Save all originals".
package com.salvozappa.youroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class OriginalRecording(val file: File, val source: SourceMedia? = null) {
    val id: File get() = file
    val title: String get() = source?.originalName ?: file.name
}

private val originalMediaExtensions = setOf("mov", "mp4", "m4v", "m4a", "aac", "mp3", "wav", "aif", "aiff", "flac", "caf")

/** Limit sharing and repair to real media in owned directories. Caches, backups and working exports are separate namespaces. */
fun ProjectDisk.originals(id: UUID, project: Project?): List<OriginalRecording> {
    val base = folder(id)
    fun real(file: File, directory: Boolean): Boolean {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) return false
        return if (directory) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    }
    if (!real(base, directory = true)) return emptyList()
    val sources = (project?.sources ?: emptyList()).associateBy { it.filename }
    fun list(directory: File) = (directory.listFiles()?.toList() ?: emptyList()).filter { !it.name.startsWith(".") }
    val result = list(base).mapNotNull { file ->
        val source = sources[file.name]
        if (!real(file, directory = false)) return@mapNotNull null
        if (source == null && !(file.extension.lowercase() in originalMediaExtensions && !file.name.lowercase().startsWith("effect-"))) return@mapNotNull null
        OriginalRecording(file, source)
    }.toMutableList()
    val recovery = File(base, "Recovery")
    if (real(recovery, directory = true)) {
        for (directory in list(recovery)) if (real(directory, directory = true)) {
            result += list(directory).filter { it.extension.lowercase() in originalMediaExtensions && real(it, directory = false) }.map { OriginalRecording(it) }
        }
    }
    return result.sortedBy { it.file.name }
}

private val originalsAccent = Color(1f, 0.25f, 0.29f)
private val originalsSecondary = Color(235, 235, 245).copy(alpha = 0.6f)
private val originalsRow = Color(44, 44, 46)

private fun originalTime(seconds: Double): String {
    val frames = Math.round(maxOf(0.0, if (seconds.isFinite()) seconds else 0.0) * 30).toInt()
    return "%d:%02d.%02d".format(frames / 1800, frames / 30 % 60, Math.round((frames % 30) / 30.0 * 100).toInt())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalRecordingsView(store: ProjectStore, projectID: UUID, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by store.projects.collectAsState()
    val recoveryMessages by store.recoveryMessages.collectAsState()
    val unavailable by store.unavailableProjects.collectAsState()
    val project = projects.firstOrNull { it.id == projectID }
    var items by remember { mutableStateOf<List<OriginalRecording>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<Set<File>>(emptySet()) }
    var saved by remember { mutableStateOf<Set<File>>(emptySet()) }
    LaunchedEffect(projectID, project?.sources) { items = withContext(Dispatchers.IO) { store.disk.originals(projectID, project) } }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(28, 28, 30), dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Text("Original recordings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { SheetPill("Done", "originals.done", action = dismiss) }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                recoveryMessages[projectID]?.let { notice -> item { Text(notice, fontSize = 15.sp, color = Color.White) } }
                unavailable[projectID]?.let { issue ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(issue, fontSize = 15.sp, color = originalsSecondary)
                            Text("Retry opening project", fontSize = 17.sp, color = originalsAccent,
                                modifier = Modifier.clickable(role = Role.Button) { store.retryUnavailableProjects() }.padding(vertical = 8.dp))
                        }
                    }
                }
                message?.let { text -> item { Text(text, fontSize = 15.sp, color = Color.White) } }
                item { Text("ORIGINAL RECORDINGS", fontSize = 13.sp, color = originalsSecondary, modifier = Modifier.padding(top = 8.dp)) }
                if (items.isEmpty()) item { Text("No original media files found.", fontSize = 17.sp, color = originalsSecondary) }
                items(items, key = { it.file.path }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(originalsRow).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.title, fontSize = 17.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            item.source?.let { Text(originalTime(it.duration) + " recorded", fontSize = 12.sp, color = originalsSecondary) }
                        }
                        val mime = if (item.file.extension.lowercase() in setOf("mov", "mp4", "m4v")) "video/*" else "audio/*"
                        when {
                            item.file in saved -> Icon(Icons.Filled.CheckCircle, "Saved to gallery", tint = Color(48, 209, 88), modifier = Modifier.padding(12.dp).size(20.dp))
                            item.file in saving -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) }
                            mime == "video/*" -> Box(Modifier.size(44.dp).clickable(role = Role.Button) {
                                saving = saving + item.file
                                scope.launch {
                                    try {
                                        MediaSharing.saveToGallery(context, item.file, MediaSharing.displayName(item.file.nameWithoutExtension, item.file.extension.lowercase()))
                                        saved = saved + item.file
                                    } catch (failure: Exception) {
                                        message = failure.message ?: "The original could not be saved to the gallery."
                                    } finally { saving = saving - item.file }
                                }
                            }.semantics { contentDescription = "Save original to gallery " + item.title }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.PhotoLibrary, null, tint = originalsAccent, modifier = Modifier.size(22.dp))
                            }
                        }
                        Box(Modifier.size(44.dp).clickable(role = Role.Button) {
                            runCatching { MediaSharing.share(context, item.file, mime = mime, title = item.title) }.onFailure { message = it.message }
                        }.semantics { contentDescription = "Share original " + item.title }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.IosShare, null, tint = originalsAccent, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                item { HorizontalDivider(color = Color.Transparent); Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
