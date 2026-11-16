// Port of YouRec/EditorTools.swift (partial: VideoTool.tint, ToolsPanel, PauseChoice, and
// ToolWorkspace/ToolEditor for the Speed, Mirror, Pauses, Color and Stabilize tools)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// The Tools sheet lists every iOS tool. "Video or photo", Crop (CropEditor.kt),
// Speed, Mirror, Pauses, Color and Stabilize (EditorTools.kt, ColorGradeControls.kt), Text (TextToolEditor.kt), Image
// (ImageToolEditor.kt), Add audio and Censor audio (AudioToolsEditor.kt) and the
// Snap setting work in this port; the other tools are shown disabled and
// labelled "Not yet available" (TODO.md, "Editor (M4)"). "In this project"
// lists the ported tools' effects that can be reopened (Censor audio included).
// ToolWorkspace and ToolEditor hold the Speed, Mirror, Pauses and Color drafts and
// controls; Text and Image keep their own workspaces. Like iOS, the Color tool
// previews the whole project (transitions, overlaps and titles included),
// bounded to the graded clips (`gradeRange`, `playbackOrigin`). Stabilize
// previews a 10 s sample around the playhead (`stabilizationWindow`); a saved
// level is reviewed on the editor's own frame until "Preview around playhead".
// Not ported (TODO.md): animated grades (`EffectAnimationControls`, A/B endpoints).
package com.salvozappa.youroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/** Swift `struct ToolRequest` (VideoTools.swift; the fields the ported tools use). */
data class ToolRequest(
    val tool: VideoTool,
    val range: ClosedFloatingPointRange<Double>,
    val scopeTitle: String,
    val targets: TimelineSelection? = null,
    val effectID: UUID? = null,
    val clipIDs: Set<UUID>? = null,
    /** The transition join (`TransitionJoin.index`) a Transition request edits. */
    val boundaryIndex: Int? = null,
) {
    // Keep absolute project time: rebasing previews changes animated effects,
    // transitions and overlapping tracks. Only playback/scrubbing are bounded.
    fun videoPreviewRange(state: EditState, ids: Set<UUID>): ClosedFloatingPointRange<Double> {
        var ranges = state.layout.placements.filter { it.clip.id in ids }.map { it.range }
        effectID?.let { effectID ->
            val regions = state.toolRegions.filter { it.tool == VideoTool.videoEffects && it.effectID == effectID && it.clipID in ids }
            if (regions.isNotEmpty()) ranges = regions.map { it.range }
        }
        val lower = max(0.0, min(state.duration, ranges.minOfOrNull { it.start } ?: range.start))
        return lower..max(lower, min(state.duration, ranges.maxOfOrNull { it.endInclusive } ?: range.endInclusive))
    }

    fun videoScope(state: EditState): Pair<EditState, Set<UUID>> {
        targets?.let { targets ->
            val scoped = state.timeline.scoped(targets)
            val requested = scoped.timeline.remapping(targets, state.timeline).video
            return EditState(scoped.timeline, state.outputSize) to (scoped.selection.video intersect requested)
        }
        clipIDs?.let { return state to it }
        return TimelineEditing.scoped(state, range)
    }
}

val VideoTool.tint: Color
    get() = when (this) {
        VideoTool.subtitles -> EditorColors.cyan
        VideoTool.text -> EditorColors.purple
        VideoTool.speed -> EditorColors.blue
        VideoTool.color -> EditorColors.pink
        VideoTool.stabilize -> EditorColors.mint
        VideoTool.pauses -> Color.White
        VideoTool.crop -> EditorColors.indigo
        VideoTool.mirror -> EditorColors.teal
        VideoTool.image, VideoTool.transition, VideoTool.placement -> EditorColors.orange
        VideoTool.videoEffects, VideoTool.addVideo -> EditorColors.cyan
        VideoTool.enhanceAudio, VideoTool.volume, VideoTool.addAudio, VideoTool.censorAudio -> EditorColors.mint
    }

/** The Material Symbol closest to each tool's SF Symbol. */
val VideoTool.icon: ImageVector
    get() = when (this) {
        VideoTool.subtitles -> Icons.Outlined.ClosedCaption
        VideoTool.stabilize -> Icons.Outlined.Straighten
        VideoTool.text -> Icons.Filled.TextFields
        VideoTool.color -> Icons.Outlined.Contrast
        VideoTool.pauses -> Icons.Filled.GraphicEq
        VideoTool.speed -> Icons.Outlined.Speed
        VideoTool.crop -> Icons.Outlined.Crop
        VideoTool.mirror -> Icons.Outlined.Flip
        VideoTool.image -> Icons.Outlined.Image
        VideoTool.addVideo -> Icons.Outlined.VideoCall
        VideoTool.addAudio -> Icons.Filled.GraphicEq
        VideoTool.censorAudio -> Icons.Outlined.MusicOff
        VideoTool.transition -> Icons.Outlined.ViewAgenda
        VideoTool.videoEffects -> Icons.Outlined.AutoAwesome
        VideoTool.enhanceAudio -> Icons.Outlined.Mic
        VideoTool.volume -> Icons.AutoMirrored.Outlined.VolumeUp
        VideoTool.placement -> Icons.Outlined.Layers
    }

/** Tools this port can open from the Tools sheet. */
private val portedTools = setOf(VideoTool.addVideo, VideoTool.videoEffects, VideoTool.crop, VideoTool.speed, VideoTool.text,
    VideoTool.image, VideoTool.mirror, VideoTool.pauses, VideoTool.color, VideoTool.stabilize, VideoTool.addAudio, VideoTool.censorAudio, VideoTool.enhanceAudio,
    VideoTool.subtitles)

@Composable
fun ToolsPanel(
    snapToCuts: Boolean,
    setSnapToCuts: (Boolean) -> Unit,
    matchListeningLevels: Boolean,
    setMatchListeningLevels: (Boolean) -> Unit,
    scope: String,
    overlayScope: String,
    insertionPosition: Double,
    choose: (VideoTool) -> Unit,
    close: () -> Unit,
    regions: List<ToolRegion> = emptyList(),
    canAdjust: Boolean = true,
    hasVideo: Boolean = true,
    hasAudio: Boolean = true,
    canCensor: Boolean = false,
    /** Swift `canTranscribe`: the project has audio or already has subtitles. */
    canTranscribe: Boolean = false,
    /** Swift `selectCover`: opens the cover picker; null disables the row (no video, or the preview is still loading). */
    selectCover: (() -> Unit)? = null,
    edit: (ToolRegion) -> Unit = {},
) {
    // Swift `toolCard(_:).disabled(...)` for the ported tools.
    val addTools = setOf(VideoTool.addVideo, VideoTool.addAudio)
    val audioTools = setOf(VideoTool.enhanceAudio, VideoTool.censorAudio)
    fun available(tool: VideoTool) = tool in portedTools && !(!canAdjust && tool !in addTools) && !(tool in audioTools && !hasAudio) &&
        !(tool == VideoTool.subtitles && !canTranscribe) && !(tool !in addTools + audioTools + VideoTool.subtitles && !hasVideo)
    var settingHelp by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Swift `toolCard`: Censor audio needs a marked range first.
    val chooseTool: (VideoTool) -> Unit = { tool ->
        if (tool == VideoTool.censorAudio && !canCensor) settingHelp = "Mark the sound to censor" to
            "In the timeline, use Mark start and Mark end around the word or sound, then open Censor audio. Select an audio item first to affect only that track; otherwise all audio in the marked range is censored."
        else choose(tool)
    }
    Column(Modifier.fillMaxWidth()) {
        SheetBar(title = "Tools", trailing = "Done", trailingAction = close)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add media at ${editorTime(insertionPosition)}", color = EditorColors.secondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolCard(VideoTool.addVideo, Modifier.weight(1f), available(VideoTool.addVideo)) { choose(it) }
                ToolCard(VideoTool.addAudio, Modifier.weight(1f), available(VideoTool.addAudio)) { choose(it) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (selectCover != null) 1f else 0.45f)
                .background(EditorColors.teal.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
                .clickable(enabled = selectCover != null) { selectCover?.invoke() }
                .padding(horizontal = 12.dp).semantics { contentDescription = "Select cover, browse frames to share as a JPEG" }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Collections, null, tint = EditorColors.teal, modifier = Modifier.width(28.dp).size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Select cover", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, null, tint = EditorColors.secondary, modifier = Modifier.size(16.dp))
            }
            SectionTitle("Overlays", overlayScope)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolCard(VideoTool.text, Modifier.weight(1f), available(VideoTool.text)) { choose(it) }
                ToolCard(VideoTool.image, Modifier.weight(1f), available(VideoTool.image)) { choose(it) }
            }
            SectionTitle("Adjust", scope, dashedScope = true)
            val adjust = listOf(VideoTool.videoEffects, VideoTool.stabilize, VideoTool.crop, VideoTool.mirror, VideoTool.color,
                VideoTool.speed, VideoTool.pauses, VideoTool.enhanceAudio, VideoTool.censorAudio, VideoTool.subtitles)
            for (pair in adjust.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (tool in pair) ToolCard(tool, Modifier.weight(1f), available(tool)) { chooseTool(it) }
                }
            }
            if (regions.isNotEmpty()) {
                Text("In this project", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                for (region in regions) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { edit(region) }
                        .semantics { contentDescription = (if (region.tool == VideoTool.text) region.label else region.tool.title + " · " + region.label) + ", tap to edit or remove" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(region.tool.icon, null, tint = region.tool.tint, modifier = Modifier.width(26.dp).size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (region.tool == VideoTool.text) region.label else region.tool.title + " · " + region.label, color = Color.White,
                                fontSize = 17.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("Tap to edit or remove", color = EditorColors.secondary, fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = EditorColors.secondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(top = 6.dp))
            Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingCard("Snap to cuts", snapToCuts, Modifier.weight(1f), icon = {
                    Icon(Icons.Filled.Compress, null, modifier = Modifier.size(22.dp).rotate(90f), tint = it)
                }, toggle = { setSnapToCuts(!snapToCuts) }, explain = {
                    settingHelp = "Snap to cuts" to "When you stop scrubbing near a clip boundary, the playhead snaps exactly to it. Selection marks also snap to nearby boundaries. Turn it off for free placement.\n\nThis applies to clip boundaries, not the cuts inside a Pauses effect."
                })
                SettingCard("Match listening levels", matchListeningLevels, Modifier.weight(1f), icon = {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, modifier = Modifier.size(22.dp), tint = it)
                }, toggle = { setMatchListeningLevels(!matchListeningLevels) }, explain = {
                    settingHelp = "Match listening levels" to "Plays Bypass and Enhanced audio at similar volume in Enhance audio, so you can compare sound quality without the louder version seeming better.\n\nThis changes preview volume only. Exported audio keeps your filter gain. Matching is automatically suspended when an Output gain filter is enabled, so gain adjustments remain audible. Turn it off to hear actual levels for other filters too."
                })
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(0.35f).background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TextFields, null, tint = Color.White, modifier = Modifier.width(28.dp).size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fonts", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Not yet available", color = EditorColors.secondary, fontSize = 11.sp)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = EditorColors.secondary, modifier = Modifier.size(16.dp))
            }
            Text("Touch and hold a setting for an explanation.", color = EditorColors.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
        }
    }
    settingHelp?.let { (title, explanation) ->
        AlertDialog(onDismissRequest = { settingHelp = null }, title = { Text(title) }, text = { Text(explanation) },
            confirmButton = { TextButton({ settingHelp = null }) { Text("Got it") } })
    }
}

/** A centred sheet title with an optional leading and trailing pill button, as in the iOS navigation bar. */
@Composable
internal fun SheetBar(title: String, leading: String? = null, leadingAction: () -> Unit = {}, trailing: String? = null,
                      trailingAction: () -> Unit = {}, trailingEnabled: Boolean = true, trailingBold: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
        // Keep the centred title clear of the pill buttons, as a navigation bar does.
        val inset = maxOf(leading?.length ?: 0, trailing?.length ?: 0).let { if (it == 0) 0 else 24 + it * 9 }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = inset.dp))
        if (leading != null) PillButton(leading, Modifier.align(Alignment.CenterStart), action = leadingAction)
        if (trailing != null) PillButton(trailing, Modifier.align(Alignment.CenterEnd), trailingEnabled, trailingBold, trailingAction)
    }
}

@Composable
internal fun PillButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, bold: Boolean = false, action: () -> Unit) {
    Box(modifier.heightIn(min = 36.dp).alpha(if (enabled) 1f else 0.4f).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = action).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(title, color = EditorColors.accent, fontSize = 17.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun SectionTitle(title: String, caption: String, dashedScope: Boolean = false) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        // Swift: `Label(scope, systemImage: "rectangle.dashed")` for Adjust, plain text for Overlays.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dashedScope) DashedRectangleGlyph(EditorColors.secondary, Modifier.size(15.dp, 12.dp))
            Text(caption, color = EditorColors.secondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ToolCard(tool: VideoTool, modifier: Modifier, enabled: Boolean, choose: (VideoTool) -> Unit) {
    Row(modifier.heightIn(min = 54.dp).alpha(if (enabled) 1f else 0.45f).background(tool.tint.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
        .clickable(enabled = enabled) { choose(tool) }.padding(horizontal = 12.dp, vertical = 6.dp)
        .semantics { contentDescription = tool.title + if (tool in portedTools) "" else ", not yet available" },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(tool.icon, null, tint = tool.tint, modifier = Modifier.width(28.dp).size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(tool.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (tool !in portedTools) Text("Not yet available", color = EditorColors.secondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingCard(title: String, on: Boolean, modifier: Modifier, icon: @Composable (Color) -> Unit, toggle: () -> Unit, explain: () -> Unit) {
    val tint = if (on) Color.White else EditorColors.secondary
    Column(modifier.heightIn(min = 98.dp).background(Color.White.copy(alpha = if (on) 0.1f else 0.04f), RoundedCornerShape(14.dp))
        .border(1.dp, Color.White.copy(alpha = if (on) 0.55f else 0.12f), RoundedCornerShape(14.dp))
        .pointerInput(on) { detectTapGestures(onTap = { toggle() }, onLongPress = { explain() }) }
        .padding(12.dp).semantics { contentDescription = "$title, " + if (on) "On" else "Off" },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon(tint)
            Spacer(Modifier.weight(1f))
            Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (on) "On" else "Off", color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
    }
}

/** Swift `PauseChoice`: one detected pause of the open Pauses draft. */
data class PauseChoice(
    val clipID: UUID,
    val raw: SourceInterval,
    val interval: SourceInterval,
    val enabled: Boolean = true,
    val id: UUID = newUUID(),
)

/**
 * Swift `ToolWorkspace`, for the Speed, Mirror and Pauses tools: the draft,
 * the scoped edit and the tool's own preview (a second EditorController
 * playing the scoped clips, Before or After). Text and Image have their own
 * workspaces (TextToolEditor.kt, ImageToolEditor.kt). TODO(port): playback
 * failure alerts.
 */
class ToolWorkspace(val project: Project, val folder: File, val request: ToolRequest, position: Double) {
    var rate by mutableDoubleStateOf(1.0)
    var mirror by mutableStateOf(MirrorSettings())
    /** Swift `original`: Before shows the scope without this tool's change. */
    var original by mutableStateOf(false)
    /** The player of the tool preview, set by the screen. */
    var preview: EditorController? = null
    var error by mutableStateOf<String?>(null)
    private val effectID: UUID = request.effectID ?: newUUID()
    // Keep split UUIDs stable throughout a draft, including pause-choice IDs.
    private val scopedState: EditState
    private val scopedIDs: Set<UUID>
    private var initialPosition: Double? = null
    private var playbackClips by mutableStateOf<List<TimelineClip>>(emptyList())
    private val jobsLazy = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val jobs by jobsLazy
    private var rebuildJob: Job? = null

    // ---- Pauses (Swift `pauseSettings` … `auditioningPause`)
    var pauseSettings by mutableStateOf(PauseSettings())
        private set
    var choices by mutableStateOf<List<PauseChoice>>(emptyList())
        private set
    var selectedPause by mutableStateOf(0)
        private set
    var detected by mutableStateOf(false)
        private set
    var analyzingPauses by mutableStateOf(false)
        private set
    var pauseScanProgress by mutableDoubleStateOf(0.0)
        private set
    var pauseAudioAvailable by mutableStateOf(true)
        private set
    var auditioningPause by mutableStateOf(false)
        private set
    private var detectedLevels by mutableStateOf<Map<UUID, Double>>(emptyMap())
    private var pauseJob: Job? = null
    // Retain manual Keep decisions even when a temporary detection setting
    // hides their candidates. Sliding back should not silently cut them again.
    private val keptPauses = HashMap<UUID, MutableList<SourceInterval>>()
    /** Swift `pauseAudition`: the position to return to and where the audition stops. */
    private var pauseAudition: Pair<Double, Double>? = null
    private var pauseDraft: Pair<Triple<PauseSettings, List<PauseChoice>, Map<UUID, Double>>, EditState>? = null
    private val uncutPauseState by lazy { makePauseState(removing = true) }

    // ---- Color (Swift `look` … `colorError`)
    var look by mutableStateOf(ColorLook.natural)
    var amount by mutableDoubleStateOf(1.0)
    var importedLUT by mutableStateOf<ImportedLUT?>(null)
    var exposure by mutableDoubleStateOf(0.0)
    var highlights by mutableDoubleStateOf(0.0)
    var localRecovery by mutableDoubleStateOf(0.0)
    /** Swift `logSourceCount`: Log sources in the scope. Always 0 on Android (LogColor.kt). */
    var logSourceCount by mutableStateOf<Int?>(0)
        private set
    var warmth by mutableDoubleStateOf(0.0)
    var tint by mutableDoubleStateOf(0.0)
    var library by mutableStateOf(GradeLibrary.read())
    var lookThumbnails by mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap())
        private set
    var colorError by mutableStateOf<String?>(null)
    private var thumbnailJob: Job? = null
    private var thumbnailFrames: EditFrames? = null
    /** Swift `colorSettings`: the draft grade. */
    val colorSettings: ColorSettings
        get() = ColorSettings(effectID, look, amount, importedLUT, exposure, highlights, localRecovery, warmth, tint)
    /** Swift `gradeRange`: the graded clips' span of the project, which the Color preview plays. */
    private val gradeRange: ClosedFloatingPointRange<Double>
    // ---- Stabilize (Swift `level`, `cropZoom`, `trackingWarning`, `analysisLabel`, `stabilizationWindow` …)
    var level by mutableStateOf(StabilizationLevel.standard)
    /** Swift `busy` while the stabilization preview measures its sample and builds its plans. */
    var preparingStabilization by mutableStateOf(false)
        private set
    /** Swift `progress` of that preparation. */
    var progress by mutableDoubleStateOf(0.0)
        private set
    var cropZoom by mutableDoubleStateOf(1.0)
        private set
    var trackingWarning by mutableStateOf(false)
        private set
    var analysisLabel by mutableStateOf("Preparing stabilization…")
        private set
    /** Swift `savedStabilizationLevel`: the one level saved on every scoped clip (null: new, mixed or off). */
    val savedStabilizationLevel: StabilizationLevel?
    private var stabilizationAnchor = 0.0
    /** The previewed sample of the scope, in scope time (Swift `stabilizationWindow`). */
    var stabilizationWindow by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
        private set
    var stabilizationSampleStopped by mutableStateOf(false)
        private set
    private val awakeReason = "tool-" + newUUID().uuidString
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Swift `stopStabilizationSample()`: Stop on the preparation overlay. */
    fun stopStabilizationSample() {
        rebuildJob?.cancel(); rebuildJob = null
        preview?.pause(); preparingStabilization = false; stabilizationSampleStopped = true
        YouRollApp.keepAwake(awakeReason, false)
    }

    fun resumeStabilizationSample() { stabilizationSampleStopped = false; rebuild(delay = false) }

    /** Swift `playbackOrigin`: project time of the tool preview's position 0. */
    val playbackOrigin: Double get() = if (request.tool == VideoTool.color) gradeRange.start else 0.0

    /** The length of the previewed scope (Swift `duration`). */
    val duration: Double get() = if (request.tool == VideoTool.color) gradeRange.endInclusive - gradeRange.start else playbackClips.sumOf { it.duration }
    val busy: Boolean get() = preview?.loading == true || preparingStabilization
    /** The tool preview's position (Swift `position`). */
    val position: Double get() = (preview?.playhead ?: 0.0) - playbackOrigin

    init {
        val scoped = request.videoScope(project.edit)
        scopedState = scoped.first; scopedIDs = scoped.second
        val clip = scopedState.clips.firstOrNull { it.id in scopedIDs }
        rate = clip?.speed?.rate ?: 1.0
        clip?.pauses?.let { pauseSettings = it }
        mirror = (clip?.mirror ?: MirrorSettings()).copy(id = effectID)
        clip?.color?.let { color ->
            look = color.look; amount = color.amount; importedLUT = color.imported; exposure = color.exposure ?: 0.0
            warmth = color.warmth ?: 0.0; tint = color.tint ?: 0.0; highlights = color.highlights ?: 0.0; localRecovery = color.localRecovery ?: 0.0
        }
        gradeRange = if (request.tool == VideoTool.color) request.videoPreviewRange(scopedState, scopedIDs) else request.range
        initialPosition = 0.0
        var local = 0.0
        for (placement in scopedState.layout.placements) {
            val item = placement.clip
            if (item.id !in scopedIDs) continue
            if (position >= placement.range.start && position <= placement.range.endInclusive) {
                initialPosition = local + min(max(0.0, position - placement.start), max(0.0, item.duration - TimelineEditing.frame)); break
            }
            local += item.duration
        }
        if (request.tool == VideoTool.color) initialPosition = min(max(0.0, duration - TimelineEditing.frame), max(0.0, position - playbackOrigin))
        savedStabilizationLevel = if (request.tool == VideoTool.stabilize) {
            val levels = scopedState.timeline.video.filter { it.id in scopedIDs }.map { it.content.stabilization?.level ?: StabilizationLevel.off }.toSet()
            if (levels.size == 1 && StabilizationLevel.off !in levels) levels.first() else null
        } else null
        level = clip?.stabilization?.level ?: StabilizationLevel.standard
        if (request.tool == VideoTool.stabilize) {
            // A selected clip can be away from the playhead. Sample its nearest
            // edge, including at the end of the project or between selected clips.
            var offset = 0.0
            var nearest = Double.POSITIVE_INFINITY
            for (placement in scopedState.layout.placements) {
                if (placement.clip.id !in scopedIDs) continue
                val local = min(placement.clip.duration, max(0.0, position - placement.start))
                val distance = abs(position - (placement.start + local))
                if (distance < nearest) { nearest = distance; stabilizationAnchor = offset + local }
                offset += placement.clip.duration
            }
            initialPosition = stabilizationAnchor
        }
    }

    val canApply: Boolean
        get() = !analyzingPauses && error == null && (request.tool != VideoTool.pauses || (detected && (choices.isNotEmpty() || request.effectID != null)))

    /** Swift `state(removing:forPreview:)` for Speed, Mirror and Pauses: one identity on every scoped clip. */
    fun state(removing: Boolean = false): EditState {
        if (request.tool == VideoTool.pauses) {
            if (removing) return uncutPauseState
            val key = Triple(pauseSettings, choices, detectedLevels)
            pauseDraft?.let { if (it.first == key) return it.second }
            return makePauseState(removing = false).also { pauseDraft = key to it }
        }
        if (request.tool == VideoTool.mirror && request.effectID == null && !mirror.horizontal && !mirror.vertical) return project.edit
        return scopedEdit(removing)
    }

    /** The scoped clips with this draft (the preview shows them even before a new Mirror flips anything). */
    private fun scopedEdit(removing: Boolean): EditState {
        val edit = scopedState.copy()
        edit.clips = edit.clips.map { clip ->
            if (clip.id !in scopedIDs) return@map clip
            when (request.tool) {
                VideoTool.speed -> clip.copy(speed = if (removing || rate == 1.0) null else SpeedSettings(effectID, rate))
                VideoTool.mirror -> clip.copy(mirror = if (removing || (!mirror.horizontal && !mirror.vertical)) null else mirror)
                // An animated grade keeps its endpoints (animation is not editable here, TODO.md).
                VideoTool.color -> clip.copy(color = if (removing) null else colorSettings.copy(motion = clip.color?.motion).takeUnless { it.isIdentity })
                VideoTool.stabilize -> clip.copy(stabilization = if (removing || level == StabilizationLevel.off) null else clip.stabilizing(level).copy(id = effectID))
                else -> clip
            }
        }
        return edit
    }

    private fun makePauseState(removing: Boolean): EditState {
        val edit = scopedState.copy()
        val groups = choices.groupBy { it.clipID }
        // Replace the clip collection once (Swift: retiming once, not once per clip).
        edit.clips = edit.clips.map { clip ->
            if (clip.id !in scopedIDs) return@map clip
            val chosen = groups[clip.id]
            if (removing || chosen.isNullOrEmpty()) return@map clip.copy(pauses = null)
            var settings = pauseSettings.copy(id = effectID)
            if (settings.levelMode == PauseLevelMode.automatic) settings = settings.copy(thresholdDB = detectedLevels[clip.id] ?: settings.thresholdDB)
            val candidates = chosen.map { DetectedPause(it.raw, it.enabled) }
            settings = settings.copy(candidates = candidates, analyzed = SourceInterval(clip.start, clip.end))
            clip.copy(pauses = settings.copy(cuts = PauseDetector.effectiveCuts(candidates, clip, settings)))
        }
        return edit
    }

    /** Swift `previewProject`: the complete scoped clips, without their joins (Color: the whole project). */
    private fun previewProject(): Project {
        val candidate = project.copy()
        val edit = if (request.tool == VideoTool.pauses) state(removing = original) else scopedEdit(removing = original)
        if (request.tool == VideoTool.color) {
            // Preserve transitions, gaps and layer placement; the player's bounds restrict playback to the graded clips.
            candidate.history = listOf(edit); candidate.historyCursor = 0
            return candidate
        }
        val clips = edit.clips.filter { it.id in scopedIDs }.map { it.copy(transition = null, openingTransition = null) }
        if (request.tool == VideoTool.stabilize) {
            val window = EffectPreviewWindow.range(stabilizationAnchor, 0.0..clips.sumOf { it.duration })
            stabilizationWindow = window
            val samples = EffectPreviewWindow(window, TrackTimeline(clips))
            // Keep the saved take clock. A ready full path is authoritative;
            // otherwise the inspector measures just its visible sample.
            val sampled = clips.mapNotNull { clip ->
                samples.analysisClip(clip)?.let { sample ->
                    sample.copy(stabilization = sample.stabilization?.copy(referenceStart = clip.stabilizationStart, referenceEnd = clip.stabilizationEnd))
                }
            }
            candidate.history = listOf(EditState(sampled, outputSize = edit.outputSize)); candidate.historyCursor = 0
            return candidate
        }
        candidate.history = listOf(EditState(clips, outputSize = edit.outputSize)); candidate.historyCursor = 0
        return candidate
    }

    /** Swift `rebuild(delay:focusPause:)`: reload the preview, keeping the source moment under the playhead. */
    fun rebuild(delay: Boolean = true, focusPause: Boolean = false) {
        if (stabilizationSampleStopped) return
        val player = preview ?: return
        rebuildJob?.cancel()
        auditioningPause = false
        val resume = player.playing && pauseAudition == null
        var position = player.playhead
        pauseAudition?.let { position = it.first; pauseAudition = null }
        var cursor = 0.0
        var anchor: Triple<UUID, UUID, Double>? = null
        for (clip in playbackClips) {
            if (position < cursor + clip.duration || clip.id == playbackClips.last().id) {
                anchor = Triple(clip.id, clip.sourceID, clip.sourceTime(position - cursor)); break
            }
            cursor += clip.duration
        }
        player.pause()
        error = null
        if (request.tool == VideoTool.color) { rebuildColor(player, delay, resume); return }
        rebuildJob = jobs.launch {
            if (delay) delay(180)
            val candidate = previewProject()
            val clips = candidate.edit.clips
            // Swift `rebuild`: the stabilize preview's plans, measured on its sample if the take's analysis is not ready.
            var plans: Map<UUID, StabilizationPlan>? = null
            if (request.tool == VideoTool.stabilize) {
                plans = emptyMap()
                if (!original && level != StabilizationLevel.off) {
                    preparingStabilization = true; progress = 0.0
                    YouRollApp.keepAwake(awakeReason, true)
                    try {
                        val window = EffectPreviewWindow(0.0..candidate.edit.duration, candidate.edit.timeline)
                        val result = HashMap<UUID, StabilizationPlan>()
                        var crop = 1.0
                        var weakTracking = false
                        for ((index, clip) in clips.withIndex()) {
                            val source = candidate.sources.firstOrNull { it.id == clip.sourceID } ?: throw ProjectError.missingMedia
                            analysisLabel = "Preparing " + (stabilizationWindow?.let { EffectPreviewWindow.label(it) } ?: "stabilization preview")
                            val plan = VideoStabilizer.plan(source, clip, folder, window) { value ->
                                main.post { if (preparingStabilization) progress = (index + value) / max(1, clips.size) }
                            }
                            if (plan != null) { result[clip.id] = plan; crop = max(crop, plan.zoom); weakTracking = weakTracking || plan.trackingFraction < 0.65 }
                        }
                        cropZoom = crop; trackingWarning = weakTracking; plans = result
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        android.util.Log.w("YouRoll", "Stabilization preview failed", failure)
                        error = failure.message ?: "The motion could not be analyzed."
                        return@launch
                    } finally {
                        preparingStabilization = false
                        YouRollApp.keepAwake(awakeReason, false)
                    }
                }
            }
            var target = initialPosition?.let { it - (stabilizationWindow?.start ?: 0.0) } ?: position
            if (initialPosition == null && anchor != null) {
                val matching = clips.firstOrNull { it.id == anchor.first && anchor.third >= it.start && anchor.third <= it.end }
                    ?: clips.firstOrNull { it.sourceID == anchor.second && anchor.third >= it.start && anchor.third <= it.end }
                if (matching != null) {
                    val offset = clips.takeWhile { it.id != matching.id }.sumOf { it.duration }
                    target = offset + matching.timelineTime(anchor.third)
                }
            }
            initialPosition = null
            playbackClips = clips
            if (focusPause && selectedPause in choices.indices) target = pausePreviewRange(choices[selectedPause]).start
            val length = candidate.edit.duration
            player.seekAfterEdit(min(max(0.0, target), max(0.0, length - TimelineEditing.frame)), length)
            player.load(candidate, folder, includeTimelineDetails = false, stabilizationPlans = plans)
            if (resume) player.togglePlay()
        }
    }

    /** Swift `rebuild` for `.color`: the whole graded project, bounded to `gradeRange`. */
    private fun rebuildColor(player: EditorController, delay: Boolean, resume: Boolean) {
        val current = position
        rebuildJob = jobs.launch {
            if (delay) delay(180)
            val candidate = previewProject()
            val target = initialPosition ?: current
            initialPosition = null
            player.previewRange = gradeRange
            player.seekAfterEdit(playbackOrigin + min(max(0.0, target), max(0.0, duration - TimelineEditing.frame)), candidate.edit.duration)
            player.load(candidate, folder, includeTimelineDetails = false)
            if (resume) player.togglePlay()
            refreshLookThumbnails()
        }
    }

    fun seek(value: Double) {
        val player = preview ?: return
        pauseAudition = null; auditioningPause = false
        player.seekPaused(playbackOrigin + min(max(0.0, value), max(0.0, duration - TimelineEditing.frame)))
        if (request.tool == VideoTool.color) refreshLookThumbnails()
    }

    fun togglePlay() {
        val player = preview ?: return
        if (player.playing) {
            player.pause()
            pauseAudition?.let { seek(it.first) }
            if (request.tool == VideoTool.color) refreshLookThumbnails()
            return
        }
        if (request.tool != VideoTool.color && player.playhead >= duration - 0.05) player.seek(0.0)
        player.togglePlay()
    }

    // ---- Color (Swift `selectGrade` … `refreshLookThumbnails`)

    /** Swift `selectGrade(_:)`: a saved grade or an imported LUT tile; its LUT payload is copied into the project. */
    fun selectGrade(settings: ColorSettings) {
        try {
            settings.imported?.let { lut -> GradeLibrary.folder?.let { GradeLibrary.copy(lut, it, folder) } }
            look = settings.look; amount = settings.amount; importedLUT = settings.imported
            exposure = settings.exposure ?: 0.0; warmth = settings.warmth ?: 0.0; tint = settings.tint ?: 0.0
            highlights = settings.highlights ?: 0.0
            localRecovery = settings.localRecovery ?: 0.0
            colorError = null
        } catch (failure: Exception) { colorError = failure.message }
    }

    /** Swift `importLUT(_:)`: [data] is the picked .cube file, [name] its file name. */
    fun importLUT(data: ByteArray, name: String) {
        jobs.launch {
            try {
                val libraryFolder = GradeLibrary.folder ?: throw LUTError("The color library is unavailable.")
                val lut = withContext(Dispatchers.IO) {
                    LUTAssets.ingest(data, name.substringBeforeLast('.'), libraryFolder).also { GradeLibrary.copy(it, libraryFolder, folder) }
                }
                if (library.imported.none { it.id == lut.id }) library = library.copy(imported = library.imported + lut)
                withContext(Dispatchers.IO) { library.save() }
                importedLUT = lut; look = ColorLook.natural; amount = 1.0; colorError = null
                refreshLookThumbnails()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) { colorError = failure.message }
        }
    }

    fun saveGrade(name: String) {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return
        try {
            importedLUT?.let { lut -> GradeLibrary.folder?.let { GradeLibrary.copy(lut, folder, it) } }
            library = library.copy(grades = library.grades + SavedGrade(name = clean, settings = colorSettings))
            library.save(); colorError = null
        } catch (failure: Exception) { colorError = failure.message }
    }

    fun toggleFavorite(id: String) {
        library = library.copy(favorites = if (id in library.favorites) library.favorites - id else library.favorites + id)
        try { library.save() } catch (failure: Exception) { colorError = failure.message }
    }

    /** Swift `Picker("LUT expects")`: the selected imported LUT's input, remembered in the library. */
    fun setLUTInput(input: LUTInput) {
        val lut = importedLUT?.copy(input = input) ?: return
        importedLUT = lut
        if (library.imported.any { it.id == lut.id }) {
            library = library.copy(imported = library.imported.map { if (it.id == lut.id) lut else it })
            try { library.save() } catch (failure: Exception) { colorError = failure.message }
        }
    }

    /**
     * Swift `refreshLookThumbnails`: the paused preview frame without any grade,
     * then every built-in look and imported LUT applied to it (240 px).
     */
    fun refreshLookThumbnails() {
        val player = preview ?: return
        if (request.tool != VideoTool.color || player.playing) return
        thumbnailJob?.cancel()
        thumbnailJob = jobs.launch {
            delay(220)
            try {
                val canvas = player.canvas ?: return@launch
                val time = playbackOrigin + min(max(0.0, position), max(0.0, duration - TimelineEditing.frame))
                val frames = thumbnailFrames ?: run {
                    val edit = scopedEdit(removing = true).copy()
                    edit.clips = edit.clips.map { it.copy(color = null) }
                    EditFrames(edit, project.sources, folder, canvas).also { thumbnailFrames = it }
                }
                val frame = frames.image(EditTime.of(time), 240)
                val luts = library.imported
                val pictures = withContext(Dispatchers.Default) {
                    val result = HashMap<String, android.graphics.Bitmap>()
                    for (look in ColorLook.entries) {
                        val copy = frame.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        ColorLUT.apply(ColorSettings(look = look), copy)
                        result[look.rawValue] = copy
                    }
                    for (lut in luts) {
                        val source = if (File(folder, lut.filename).exists()) folder else GradeLibrary.folder ?: continue
                        val cube = runCatching { LUTAssets.load(lut, source) }.getOrNull() ?: continue
                        val copy = frame.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        ColorLUT.apply(ColorSettings(imported = lut), copy, cube)
                        result[lut.id] = copy
                    }
                    result
                }
                lookThumbnails = pictures
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Playback remains usable if an individual frame is unavailable.
            }
        }
    }

    /** The preview's periodic observer: a pause audition returns to its anchor at its end. */
    fun followPreview() {
        val player = preview ?: return
        val audition = pauseAudition ?: return
        if (!player.playing || player.playhead >= min(audition.second, duration - 0.02)) seek(audition.first)
    }

    // ---- Pauses

    val displayedPauseLevel: Double
        get() {
            val clipID = choices.getOrNull(selectedPause)?.clipID ?: scopedState.clips.firstOrNull { it.id in scopedIDs }?.id
            return clipID?.let { detectedLevels[it] } ?: pauseSettings.thresholdDB
        }

    val automaticLevelDescription: String?
        get() {
            if (!pauseAudioAvailable) return null
            val low = detectedLevels.values.minOrNull() ?: return null
            val high = detectedLevels.values.maxOrNull() ?: return null
            if (low == high) return "Reference level ${rateText(low)} dBFS RMS."
            return "Reference levels range from ${rateText(low)} to ${rateText(high)} dBFS RMS."
        }

    fun setAutomaticPauseLevel(automatic: Boolean) {
        val old = pauseSettings
        var settings = pauseSettings
        if (!automatic) settings = settings.copy(thresholdDB = displayedPauseLevel)
        pauseSettings = settings.copy(levelMode = if (automatic) PauseLevelMode.automatic else PauseLevelMode.manual)
        pauseSettingsChanged(old)
    }

    /** A Detection settings control changed [pauseSettings] (Swift `.onChange(of: model.pauseSettings)`). */
    fun updatePauseSettings(settings: PauseSettings) {
        if (settings == pauseSettings) return
        val old = pauseSettings
        pauseSettings = settings
        pauseSettingsChanged(old)
    }

    val cutSeconds: Double
        get() = state().clips.filter { it.id in scopedIDs }.sumOf { clip -> clip.copy(pauses = null).duration - clip.duration }
    val cutCount: Int get() = state().clips.filter { it.id in scopedIDs }.sumOf { it.pauses?.cuts?.size ?: 0 }

    fun analyzePauses(delay: Boolean = false) {
        pauseJob?.cancel()
        analyzingPauses = true; pauseScanProgress = 0.0; detected = false; error = null
        val settings = pauseSettings
        val anchor = choices.getOrNull(selectedPause)
        pauseJob = jobs.launch {
            try {
                if (delay) delay(250)
                val proposed = mutableListOf<PauseChoice>()
                var hasAudio = false
                val sourceLevels = HashMap<UUID, Double>()
                val clipLevels = HashMap<UUID, Double>()
                val clips = scopedState.clips.filter { it.id in scopedIDs }
                for ((index, clip) in clips.withIndex()) {
                    val source = project.sources.firstOrNull { it.id == clip.sourceID } ?: throw ProjectError.missingMedia
                    // Swift: automatic detection asks for RNNoise speech probabilities (SpeechMeter).
                    val wave = SourceMediaCache.waveform(source, folder, needsRMS = settings.levelMode != null,
                        needsSpeech = settings.levelMode == PauseLevelMode.automatic) { fraction ->
                        main.post { pauseScanProgress = (index + min(1.0, max(0.0, fraction))) / max(1, clips.size) }
                    }
                    pauseScanProgress = (index + 1.0) / max(1, clips.size)
                    hasAudio = hasAudio || wave.peaks.isNotEmpty()
                    val level = sourceLevels[source.id] ?: PauseDetector.levelDB(wave, settings)
                    sourceLevels[source.id] = level
                    if (wave.peaks.isNotEmpty()) clipLevels[clip.id] = level
                    val intervals = withContext(Dispatchers.Default) { PauseDetector.raw(wave, clip, settings, level) }
                    val previous = keptPauses[clip.id].orEmpty()
                    for (raw in intervals) {
                        val keep = previous.any { min(it.end, raw.end) - max(it.start, raw.start) > min(raw.duration, it.duration) * 0.5 }
                        proposed += PauseChoice(clip.id, raw, PauseDetector.padded(raw, clip, settings), enabled = !keep)
                    }
                }
                choices = proposed; pauseAudioAvailable = hasAudio; detectedLevels = clipLevels
                val closest = anchor?.let { a -> choices.indices.filter { choices[it].clipID == a.clipID }.minByOrNull { abs(choices[it].raw.start - a.raw.start) } }
                selectedPause = closest ?: min(selectedPause, max(0, choices.size - 1))
                detected = true; analyzingPauses = false
                rebuild(delay = false, focusPause = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                analyzingPauses = false
                error = failure.message ?: "The pauses could not be found."
            }
        }
    }

    private fun pauseSettingsChanged(old: PauseSettings) {
        pauseDraft = null
        if (old.levelMode != pauseSettings.levelMode || old.thresholdDB != pauseSettings.thresholdDB || old.minimum != pauseSettings.minimum || analyzingPauses) {
            analyzePauses(delay = true); return
        }
        choices = choices.map { choice ->
            val clip = scopedState.clips.firstOrNull { it.id == choice.clipID } ?: return@map choice
            choice.copy(interval = PauseDetector.padded(choice.raw, clip, pauseSettings))
        }
        if (detected) rebuild(focusPause = true)
    }

    fun cutDuration(choice: PauseChoice): Double {
        val clip = scopedState.clips.firstOrNull { it.id == choice.clipID } ?: return 0.0
        val full = clip.copy(pauses = null)
        return PauseDetector.effectiveCuts(listOf(DetectedPause(choice.raw)), clip, pauseSettings)
            .sumOf { full.timelineTime(it.end) - full.timelineTime(it.start) }
    }

    fun pausePreviewRange(choice: PauseChoice): ClosedFloatingPointRange<Double> {
        var cursor = 0.0
        for (clip in state(removing = original).clips) {
            if (clip.id !in scopedIDs) continue
            if (clip.id == choice.clipID) {
                val interval = if (original || !choice.enabled || cutDuration(choice) < TimelineEditing.frame) choice.raw else choice.interval
                val start = cursor + clip.timelineTime(interval.start)
                val end = cursor + clip.timelineTime(interval.end)
                return start..max(start, end)
            }
            cursor += clip.duration
        }
        return 0.0..0.0
    }

    fun selectPause(index: Int) {
        if (busy || analyzingPauses || choices.isEmpty()) return
        selectedPause = min(max(0, index), choices.size - 1)
        seek(pausePreviewRange(choices[selectedPause]).start)
    }

    fun setPauseEnabled(enabled: Boolean) {
        val choice = choices.getOrNull(selectedPause) ?: return
        val kept = keptPauses.getOrPut(choice.clipID) { mutableListOf() }
        kept.removeAll { min(it.end, choice.raw.end) - max(it.start, choice.raw.start) > min(choice.raw.duration, it.duration) * 0.5 }
        if (!enabled) kept += choice.raw
        choices = choices.mapIndexed { index, value -> if (index == selectedPause) value.copy(enabled = enabled) else value }
        rebuild(focusPause = true)
    }

    /** Swift `auditionPause`: play ¾ s either side of the pause (or of the cut), then return to the starting position. */
    fun auditionPause() {
        val player = preview ?: return
        pauseAudition?.let { seek(it.first); return }
        if (busy || analyzingPauses || selectedPause !in choices.indices) return
        val range = pausePreviewRange(choices[selectedPause])
        val anchor = player.playhead
        val start = max(0.0, range.start - 0.75)
        val end = min(duration, range.endInclusive + 0.75)
        player.pause()
        auditioningPause = true
        pauseAudition = anchor to end
        player.seek(start)
        player.togglePlay()
    }

    /** Swift `startPreview` for Speed, Mirror, Pauses and Color. */
    fun startPreview() {
        if (request.tool != VideoTool.pauses) { rebuild(delay = false); return }
        val restored = mutableListOf<PauseChoice>()
        val levels = HashMap<UUID, Double>()
        for (clip in scopedState.clips) {
            val settings = clip.pauses
            if (clip.id !in scopedIDs || settings == null) continue
            levels[clip.id] = settings.thresholdDB
            val candidates = settings.candidates ?: settings.cuts.map {
                DetectedPause(SourceInterval(max(clip.start, it.start - settings.before * clip.rate), min(clip.end, it.end + settings.after * clip.rate)))
            }
            restored += candidates.filter { it.raw.start < clip.end && it.raw.end > clip.start }.map {
                PauseChoice(clip.id, it.raw, PauseDetector.padded(it.raw, clip, settings), it.enabled)
            }
        }
        detectedLevels = levels; choices = restored
        for (choice in restored) if (!choice.enabled) keptPauses.getOrPut(choice.clipID) { mutableListOf() } += choice.raw
        if (request.effectID != null) { detected = true; rebuild(delay = false, focusPause = true) }
        else { rebuild(delay = false); analyzePauses() }
    }

    fun stop() {
        rebuildJob?.cancel(); pauseJob?.cancel(); thumbnailJob?.cancel(); pauseAudition = null; auditioningPause = false; preview?.pause()
        preparingStabilization = false; YouRollApp.keepAwake(awakeReason, false)
    }
    fun release() { if (jobsLazy.isInitialized()) jobs.cancel() }
}

/**
 * Swift `ToolEditor` (the Speed, Mirror, Pauses, Color and Stabilize screens): Cancel · title over scope · Apply,
 * preview, playback and settings. [editor] is the main editor, whose frame Stabilize shows while reviewing a saved level.
 */
@Composable
fun ToolEditor(project: Project, folder: File, request: ToolRequest, position: Double, cancel: () -> Unit, apply: (EditState) -> Unit,
               editor: EditorController? = null) {
    val context = LocalContext.current
    val model = remember { GradeLibrary.install(context); ToolWorkspace(project, folder, request, position) }
    val preview = remember { EditorController(context) }
    val kind = request.tool
    // Swift `samplingStabilization`, `reviewingSavedLevel`, `showingEditorFrame`.
    var samplingStabilization by remember { mutableStateOf(false) }
    val reviewingSavedLevel = model.savedStabilizationLevel != null && model.savedStabilizationLevel == model.level
    val showingEditorFrame = reviewingSavedLevel && !samplingStabilization && editor != null
    DisposableEffect(model) {
        model.preview = preview
        if (!showingEditorFrame) model.startPreview()
        onDispose { model.stop(); model.release(); preview.release() }
    }
    // Swift `.onChange(of: model.level) { samplingStabilization = true; model.resumeStabilizationSample() }`.
    var shownLevel by remember { mutableStateOf(model.level) }
    LaunchedEffect(model.level) { if (model.level != shownLevel) { shownLevel = model.level; samplingStabilization = true; model.resumeStabilizationSample() } }
    LaunchedEffect(model) {
        while (true) { model.followPreview(); delay(33) }
    }
    // Swift `.onChange(of: model.rate/mirror) { rebuild() }`, `.onChange(of: model.original) { rebuild(delay: false) }`.
    var shownRate by remember { mutableDoubleStateOf(model.rate) }
    LaunchedEffect(model.rate) { if (model.rate != shownRate) { shownRate = model.rate; model.rebuild() } }
    var shownMirror by remember { mutableStateOf(model.mirror) }
    LaunchedEffect(model.mirror) { if (model.mirror != shownMirror) { shownMirror = model.mirror; model.rebuild() } }
    // Swift `.onChange(of: model.colorSettings) { rebuild() }`.
    var shownGrade by remember { mutableStateOf(model.colorSettings) }
    LaunchedEffect(model.colorSettings) { if (model.colorSettings != shownGrade) { shownGrade = model.colorSettings; model.rebuild() } }
    var shownOriginal by remember { mutableStateOf(model.original) }
    LaunchedEffect(model.original) { if (model.original != shownOriginal) { shownOriginal = model.original; model.rebuild(delay = false) } }
    BackHandler(onBack = { model.stop(); cancel() })
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        val boxWidth = maxWidth
        val boxHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color(0.06f, 0.06f, 0.06f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (reviewingSavedLevel) Spacer(Modifier.width(60.dp))
                else PillButton("Cancel", Modifier.widthIn(min = 60.dp), action = { model.stop(); cancel() })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(kind.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    // Swift `scopeDescription`: the stabilize sample's length follows the scope.
                    val scope = request.scopeTitle + (model.stabilizationWindow?.let { " · " + EffectPreviewWindow.label(it) } ?: "")
                    Text(scope, color = EditorColors.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (reviewingSavedLevel) PillButton("Done", Modifier.widthIn(min = 60.dp), bold = true) { model.stop(); cancel() }
                else PillButton(if (model.savedStabilizationLevel == null) "Apply" else "Apply changes", Modifier.widthIn(min = 60.dp),
                    enabled = model.canApply, bold = true) { model.stop(); apply(model.state()) }
            }
            @Composable fun pictureAndPlayback(modifier: Modifier) {
                Column(modifier) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                        if (showingEditorFrame && editor != null) {
                            PlayerSurface(editor.player, Modifier.fillMaxSize(), framing = editor.framing, titles = editor.titles, layers = editor.layers, captions = editor.subtitles, inspectionZoom = false)
                        } else {
                            PlayerSurface(preview.player, Modifier.fillMaxSize(), framing = preview.framing, titles = preview.titles, layers = preview.layers, captions = preview.subtitles, inspectionZoom = false, onPlaybackTap = { model.togglePlay() })
                        }
                        if (model.stabilizationSampleStopped) {
                            Box(Modifier.heightIn(min = 44.dp).background(EditorColors.accent, RoundedCornerShape(50)).clickable { model.resumeStabilizationSample() }
                                .padding(horizontal = 18.dp, vertical = 10.dp).semantics { contentDescription = "Preview stabilization" }, contentAlignment = Alignment.Center) {
                                Text("Preview stabilization", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else if (!showingEditorFrame && (model.busy || model.analyzingPauses)) {
                            Column(Modifier.background(Color(0.2f, 0.2f, 0.22f, 0.85f), RoundedCornerShape(14.dp)).padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (kind == VideoTool.stabilize && model.preparingStabilization && model.progress > 0) {
                                    androidx.compose.material3.LinearProgressIndicator(progress = { model.progress.toFloat() }, color = EditorColors.accent,
                                        trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.width(140.dp).semantics { contentDescription = "Stabilization progress" })
                                } else CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                                Text(if (model.analyzingPauses) "Finding pauses…" else if (kind == VideoTool.stabilize && model.preparingStabilization) model.analysisLabel
                                    else "Preparing preview…", color = Color.White, fontSize = 12.sp)
                                if (kind == VideoTool.stabilize) {
                                    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 64.dp).clickable { model.stopStabilizationSample() }
                                        .semantics { contentDescription = "Stop" }, contentAlignment = Alignment.Center) {
                                        Text("Stop", color = EditorColors.accent, fontSize = 17.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (showingEditorFrame) {
                        // Swift `Label("Preview around playhead", systemImage: "play.fill")`.
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { samplingStabilization = true; model.startPreview() }
                            .semantics { contentDescription = "Preview around playhead" },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Preview around playhead", color = EditorColors.accent, fontSize = 17.sp)
                        }
                    } else ToolPlayback(model, preview, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                }
            }
            @Composable fun inspector(modifier: Modifier) {
                Column(modifier.background(Color(0.08f, 0.08f, 0.08f)).verticalScroll(rememberScrollState()).padding(16.dp)
                    .semantics { contentDescription = "Tool settings" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    (model.error ?: preview.error)?.let { message ->
                        Text(message, color = EditorColors.orange, fontSize = 15.sp)
                        Box(Modifier.heightIn(min = 44.dp).clickable { preview.error = null; if (kind == VideoTool.pauses && !model.detected) model.analyzePauses() else model.rebuild(delay = false) },
                            contentAlignment = Alignment.CenterStart) { Text("Retry", color = EditorColors.accent, fontSize = 17.sp) }
                    }
                    when (kind) {
                        VideoTool.mirror -> MirrorSettingsPanel(model)
                        VideoTool.pauses -> PauseControls(model)
                        VideoTool.color -> ColorGradeControls(model)
                        VideoTool.stabilize -> StabilizeSettingsPanel(model, showingEditorFrame)
                        else -> SpeedSettingsPanel(model)
                    }
                    if (request.effectID != null) {
                        val removeTitle = "Remove " + if (kind == VideoTool.color) "look" else kind.title.lowercase()
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { model.stop(); apply(model.state(removing = true)) }
                            .semantics { contentDescription = removeTitle },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Delete, null, tint = EditorColors.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(removeTitle, color = EditorColors.accent, fontSize = 17.sp)
                        }
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pictureAndPlayback(Modifier.weight(1f).fillMaxHeight())
                    inspector(Modifier.width(minOf(320.dp, boxWidth * 0.4f)).fillMaxHeight())
                }
            } else {
                pictureAndPlayback(Modifier.weight(1f).fillMaxWidth())
                inspector(Modifier.fillMaxWidth().heightIn(max = minOf(if (kind == VideoTool.pauses || kind == VideoTool.color) 360.dp else 230.dp, boxHeight * 0.48f)))
            }
        }
    }
}

/** Swift `ToolEditor.playback`: play/pause, Before/After, then the position slider between the times. */
@Composable
private fun ToolPlayback(model: ToolWorkspace, preview: EditorController, modifier: Modifier) {
    val duration = model.duration
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).alpha(if (preview.loading) 0.4f else 1f).clickable(enabled = !preview.loading) { model.togglePlay() }
                .semantics { contentDescription = if (preview.playing) "Pause tool preview" else "Play tool preview" }, contentAlignment = Alignment.Center) {
                Icon(if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = EditorColors.accent)
            }
            Spacer(Modifier.weight(1f))
            for (original in listOf(true, false)) {
                val selected = model.original == original
                Box(Modifier.heightIn(min = 44.dp).clickable { model.original = original }
                    .semantics { stateDescription = if (selected) "Selected" else "" }, contentAlignment = Alignment.Center) {
                    Text(if (original) "Before" else "After", color = EditorColors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(editorTime(model.position), color = Color.White, fontSize = 12.sp, style = tabular, maxLines = 1)
            Slider(value = min(max(0.0, model.position), duration).toFloat(), onValueChange = { model.seek(it.toDouble()) },
                valueRange = 0f..max(TimelineEditing.frame, duration - TimelineEditing.frame).toFloat(), enabled = !preview.loading,
                colors = toolSliderColors(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Tool preview position" })
            Text(editorTime(duration), color = EditorColors.secondary, fontSize = 12.sp, style = tabular, maxLines = 1)
        }
    }
}

@Composable
private fun toolSliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f), disabledThumbColor = Color.Gray, disabledActiveTrackColor = Color.Gray)

/** Swift `ToolEditor.settings` `.speed`: the rate, a 0.25×–4× slider in 0.05 steps, presets and the explanation. */
@Composable
private fun SpeedSettingsPanel(model: ToolWorkspace) {
    val tint = VideoTool.speed.tint
    Text(rateText(model.rate) + "×", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, style = tabular)
    Slider(value = model.rate.toFloat(), onValueChange = { model.rate = Math.round(it * 20) / 20.0 }, valueRange = 0.25f..4f, steps = 74,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = tint, inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback speed"; stateDescription = rateText(model.rate) + "×" })
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rate in listOf(0.5, 1.0, 1.5, 2.0)) {
            val selected = abs(model.rate - rate) < 0.001
            Box(Modifier.weight(1f).heightIn(min = 44.dp)
                .background(if (selected) tint.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                .border(1.dp, if (selected) tint else Color.Transparent, RoundedCornerShape(11.dp))
                .clickable { model.rate = rate }.semantics { stateDescription = if (selected) "Selected" else "" }, contentAlignment = Alignment.Center) {
                Text(rateText(rate) + "×", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Text("Voice pitch is preserved. Text follows the footage, so its duration changes too. Slow motion repeats source frames.",
        color = EditorColors.secondary, fontSize = 12.sp)
}

/** Swift `ToolEditor.settings` `.stabilize`: the saved status, the four levels, tracking and crop feedback. */
@Composable
private fun StabilizeSettingsPanel(model: ToolWorkspace, showingEditorFrame: Boolean) {
    model.savedStabilizationLevel?.let { saved ->
        // Swift `editor.stabilizationStatus ?? "Applied"` (the resumable application status is not ported).
        Text("Applied · " + saved.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in StabilizationLevel.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (level in pair) {
                    ToolChoice(level.title, model.level == level, VideoTool.stabilize.tint, Modifier.weight(1f)) { model.level = level }
                }
            }
        }
    }
    if (model.trackingWarning) Text("Some frames in this preview could not be tracked reliably.", color = EditorColors.orange, fontSize = 12.sp)
    Text(model.level.detail, color = EditorColors.secondary, fontSize = 16.sp)
    if (!showingEditorFrame) {
        Text(if (model.level == StabilizationLevel.off) "Original framing" else "Crop zoom ${"%.2f".format(java.util.Locale.ROOT, model.cropZoom)}× · Audio unchanged",
            color = EditorColors.secondary, fontSize = 12.sp, modifier = Modifier.semantics { contentDescription = "Stabilization crop" })
    }
    if (model.request.range.start > 0 || model.request.range.endInclusive < model.project.edit.duration) {
        Text("Framing can change at the edges of this selection.", color = EditorColors.secondary, fontSize = 12.sp)
    }
}

/** Swift `ToolEditor.choice(_:selected:action:)`: a rounded option tinted by the tool when selected. */
@Composable
private fun ToolChoice(title: String, selected: Boolean, tint: Color, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp)
        .background(if (selected) tint.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
        .border(1.dp, if (selected) tint else Color.Transparent, RoundedCornerShape(11.dp))
        .clickable(onClick = action).semantics { contentDescription = title; stateDescription = if (selected) "Selected" else "" },
        contentAlignment = Alignment.Center) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Swift `ToolEditor.settings` `.mirror`: two flips and the explanation. */
@Composable
private fun MirrorSettingsPanel(model: ToolWorkspace) {
    Column {
        ToolToggle("Flip horizontally", model.mirror.horizontal) { model.mirror = model.mirror.copy(horizontal = it) }
        ToolToggle("Flip vertically", model.mirror.vertical) { model.mirror = model.mirror.copy(vertical = it) }
        Text("Flips the footage. Text and image overlays keep their orientation.", color = EditorColors.secondary, fontSize = 12.sp)
    }
}

/** A SwiftUI `Toggle` row in the tool inspector. */
@Composable
private fun ToolToggle(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.green, checkedThumbColor = Color.White),
            modifier = Modifier.semantics { contentDescription = title })
    }
}

/** Swift `ToolEditor.pauseControls`: the summary, the pause navigator (Cut/Keep, Listen) and Detection settings. */
@Composable
private fun PauseControls(model: ToolWorkspace) {
    var settingsOpen by remember { mutableStateOf(false) }
    val tint = EditorColors.accent
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (model.analyzingPauses) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.GraphicEq, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(if (model.pauseSettings.levelMode == PauseLevelMode.automatic) "Listening for speech pauses…" else "Measuring silence…",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { model.pauseScanProgress.toFloat() }, color = tint,
                trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pause scan progress" })
        } else if (model.detected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = model.cutCount
                Text("$count ${if (count == 1) "cut" else "cuts"} · ${"%.2f".format(java.util.Locale.ROOT, model.cutSeconds)} s shorter",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { contentDescription = "Pause summary" })
                Row(Modifier.heightIn(min = 44.dp).clickable { model.analyzePauses() }.semantics { contentDescription = "Scan again" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan again", color = tint, fontSize = 15.sp)
                }
            }
            Text("${editorTime(model.state(removing = true).duration)} → ${editorTime(model.state().duration)} in project",
                color = EditorColors.secondary, fontSize = 12.sp, style = tabular)
        }
        val choice = model.choices.getOrNull(model.selectedPause)
        if (choice != null) {
            val removable = model.cutDuration(choice)
            Column(Modifier.fillMaxWidth().alpha(if (model.analyzingPauses) 0.4f else 1f).background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val enabled = !model.analyzingPauses
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val previous = enabled && !model.busy && model.selectedPause > 0
                    Box(Modifier.size(44.dp).alpha(if (previous) 1f else 0.4f).clickable(enabled = previous) { model.selectPause(model.selectedPause - 1) }
                        .semantics { contentDescription = "Previous pause" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ChevronLeft, null, tint = tint)
                    }
                    Text("Pause ${model.selectedPause + 1} of ${model.choices.size}", color = Color.White, fontSize = 15.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    val next = enabled && !model.busy && model.selectedPause < model.choices.size - 1
                    Box(Modifier.size(44.dp).alpha(if (next) 1f else 0.4f).clickable(enabled = next) { model.selectPause(model.selectedPause + 1) }
                        .semantics { contentDescription = "Next pause" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ChevronRight, null, tint = tint)
                    }
                }
                Text("At ${editorTime(model.pausePreviewRange(choice).start)} · ${if (model.original) "Before" else "After"}",
                    color = Color.White, fontSize = 12.sp, style = tabular)
                if (removable < TimelineEditing.frame) {
                    Text("Kept: padding leaves no room to cut.", color = EditorColors.secondary, fontSize = 12.sp)
                } else {
                    // Swift: a segmented `Picker("This pause")` with Cut and Keep.
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(9.dp)).padding(2.dp)) {
                        for ((title, value) in listOf("Cut" to true, "Keep" to false)) {
                            val selected = choice.enabled == value
                            Box(Modifier.weight(1f).heightIn(min = 40.dp)
                                .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(7.dp))
                                .clickable(enabled = enabled && !selected) { model.setPauseEnabled(value) }
                                .semantics { contentDescription = title; stateDescription = if (selected) "Selected" else "" }, contentAlignment = Alignment.Center) {
                                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                    Text(if (choice.enabled) "Remove ${"%.2f".format(java.util.Locale.ROOT, removable)} s" else "Keep this pause in the video",
                        color = EditorColors.secondary, fontSize = 12.sp)
                }
                val listenTitle = if (model.auditioningPause) "Stop preview"
                    else if (model.original || !choice.enabled || removable < TimelineEditing.frame) "Listen to pause" else "Listen to cut"
                val listen = enabled && !model.busy
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(if (listen) 1f else 0.4f).background(tint, RoundedCornerShape(10.dp))
                    .clickable(enabled = listen) { model.auditionPause() }.semantics { contentDescription = listenTitle },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (model.auditioningPause) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(listenTitle, color = Color.White, fontSize = 17.sp)
                }
            }
        } else if (model.detected) {
            Text(if (!model.pauseAudioAvailable) "This section has no audio to analyze."
                else if (model.pauseSettings.levelMode == PauseLevelMode.automatic)
                    "No pauses found. Try a shorter minimum pause or choose a manual silence level in Detection settings."
                else "No pauses found. Raise the silence level or shorten the minimum pause in Detection settings.",
                color = EditorColors.secondary, fontSize = 15.sp)
        }
        // Swift `DisclosureGroup("Detection settings")`, collapsed initially.
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { settingsOpen = !settingsOpen }.semantics { contentDescription = "Detection settings" },
                verticalAlignment = Alignment.CenterVertically) {
                Text("Detection settings", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(if (settingsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = tint)
            }
            if (settingsOpen) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val settings = model.pauseSettings
                val automatic = settings.levelMode == PauseLevelMode.automatic
                ToolToggle("Automatic silence level", automatic, enabled = !(model.analyzingPauses && automatic)) { model.setAutomaticPauseLevel(it) }
                if (automatic) {
                    Text("Looks for breaks in speech, even when background noise continues.", color = EditorColors.secondary, fontSize = 12.sp)
                    if (model.detected) model.automaticLevelDescription?.let { Text(it, color = EditorColors.secondary, fontSize = 12.sp) }
                } else {
                    PauseParameter("Silence below", settings.thresholdDB, -65.0..-15.0, 1.0, if (settings.levelMode == null) "dBFS peak" else "dBFS RMS") {
                        model.updatePauseSettings(model.pauseSettings.copy(thresholdDB = it))
                    }
                    Text("This level applies to the selection. Raising it can include quiet speech.", color = EditorColors.secondary, fontSize = 12.sp)
                }
                PauseParameter("Minimum pause", settings.minimum, 0.1..3.0, 0.1, "s") { model.updatePauseSettings(model.pauseSettings.copy(minimum = it)) }
                PauseParameter("Keep after speech", settings.before, 0.0..1.0, 0.02, "s") { model.updatePauseSettings(model.pauseSettings.copy(before = it)) }
                PauseParameter("Keep before speech", settings.after, 0.0..1.0, 0.02, "s") { model.updatePauseSettings(model.pauseSettings.copy(after = it)) }
                Text("Uses original audio. Changes update automatically. After extending a clip, tap Scan again.", color = EditorColors.secondary, fontSize = 12.sp)
            }
        }
        Text("Choices are saved. Removing this effect restores the pauses.", color = EditorColors.secondary, fontSize = 12.sp)
    }
}

/** Swift `ToolEditor.parameter`: a caption with the value, over a stepped slider. */
@Composable
private fun PauseParameter(title: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, suffix: String, change: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(title, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${rateText(value)} $suffix", color = Color.White, fontSize = 12.sp, style = tabular)
        }
        val steps = Math.round((range.endInclusive - range.start) / step).toInt() - 1
        Slider(value.toFloat(), { raw ->
            val snapped = range.start + Math.round((raw - range.start) / step) * step
            change(Math.round(snapped * 1000) / 1000.0)
        }, valueRange = range.start.toFloat()..range.endInclusive.toFloat(), steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = EditorColors.accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title; stateDescription = "${rateText(value)} $suffix" })
    }
}
