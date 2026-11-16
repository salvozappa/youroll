// Port of YouRec/RecorderView.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
//
// Footage is the entire recorder. Everything else is a lightweight overlay;
// secondary controls live in the drawer, without taking space from the preview.
// Portrait and landscape layouts (in landscape the capture rail sits at the
// phone's bottom end); live effects, two cameras, front light, horizon and the
// recording-gain slider are in TODO.md.
package com.salvozappa.youroll

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.vectorResource

/** What the recorder shows about its project until ProjectStore is wired (Project in Swift). */
data class RecorderProjectSummary(
    val title: String,
    /** Edited duration in seconds (`project.edit.duration`). */
    val duration: Double,
    /** Captured duration in seconds (`project.capturedDuration`). */
    val capturedDuration: Double,
    val hasSourceMedia: Boolean,
)

// SwiftUI dark-mode system colours.
internal object RecorderColors {
    val red = Color(1f, 0.25f, 0.29f)
    val cyan = Color(100, 210, 255)
    val yellow = Color(255, 214, 10)
    val orange = Color(255, 159, 10)
    val green = Color(48, 209, 88)
    val controlPlate = Color.Black.copy(alpha = 0.52f)
    val activePlate = Color(0.06f, 0.26f, 0.31f).copy(alpha = 0.85f)
    val material = Color(0.17f, 0.17f, 0.19f).copy(alpha = 0.78f) // .ultraThinMaterial over footage
    val sheet = Color(28, 28, 30)        // secondarySystemBackground
    val row = Color(28, 28, 30)          // grouped Form rows
    val surface = Color(0.09f, 0.10f, 0.13f)
    val secondary = Color(235, 235, 245).copy(alpha = 0.6f)
}

/** The @AppStorage values RecorderView.swift reads. */
class RecorderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    var fitRecordedFrame by mutableStateOf(prefs.getBoolean("fitRecordedFrame", true)); private set
    var zoomTransitionSeconds by mutableStateOf(prefs.getFloat("zoomTransitionSeconds", 0f).toDouble()); private set
    fun setFit(value: Boolean) { fitRecordedFrame = value; prefs.edit().putBoolean("fitRecordedFrame", value).apply() }
    fun setZoomSeconds(value: Double) {
        val next = ZoomTransition.boundedDuration(value)
        zoomTransitionSeconds = next; prefs.edit().putFloat("zoomTransitionSeconds", next.toFloat()).apply()
    }
}

fun recorderClock(seconds: Double): String = CaptureController.clock(seconds)

/** Keep a group in the safe letterbox if it fits, otherwise put it inside the picture. */
internal fun controlInset(gutter: Float, extent: Float, margin: Float, clearance: Float = 12f): Float {
    if (gutter >= margin + extent + clearance) return margin
    return max(margin, ceil(gutter + clearance))
}

/** Recorder geometry from RecorderView.swift's layout pass (JVM-tested). Values in dp. */
internal object RecorderGeometry {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * The recorded frame (16:9 in landscape, 9:16 in portrait) fitted in the
     * safe area and centred on the full display, in safe-area coordinates
     * (Swift `wideFrame`, offset into control coordinates as `picture`).
     */
    fun picture(fullWidth: Float, fullHeight: Float, left: Float, top: Float, right: Float, bottom: Float, landscape: Boolean): Box {
        val safeWidth = max(1f, fullWidth - left - right)
        val safeHeight = max(1f, fullHeight - top - bottom)
        val aspect = if (landscape) 16f / 9f else 9f / 16f
        val width = min(safeWidth, safeHeight * aspect)
        val height = width / aspect
        val x = (fullWidth - width) / 2 - left
        val y = (fullHeight - height) / 2 - top
        return Box(x, y, x + width, y + height)
    }

    /**
     * Swift `controlsOnLeft`: interface landscapeLeft puts the hardware top (the
     * cameras) on the right, and the capture controls go to the opposite, bottom
     * end. On Android that interface orientation is display rotation 270.
     */
    fun controlsOnLeft(displayRotation: Int): Boolean = displayRotation == Surface.ROTATION_270

    /** Swift `captureGutter`: the letterbox beside (landscape) or below (portrait) the picture. */
    fun captureGutter(picture: Box, safeWidth: Float, safeHeight: Float, landscape: Boolean, controlsOnLeft: Boolean): Float =
        if (landscape) (if (controlsOnLeft) picture.left else safeWidth - picture.right) else safeHeight - picture.bottom

    /** Swift `toolbarTop`/`abovePicture` and the landscape top bar's insets that keep it over the picture. */
    fun topBarInsets(picture: Box, rowLeft: Float, rowRight: Float, overPicture: Boolean, clearance: Float = 12f): Pair<Float, Float> =
        if (!overPicture) 0f to 0f
        else max(0f, picture.left + clearance - rowLeft) to max(0f, rowRight - picture.right + clearance)

    /**
     * The `CaptureController` target rotation for an `OrientationEventListener`
     * reading. The recorded picture follows gravity, like iOS's
     * `videoRotationAngleForHorizonLevelCapture`, whatever the interface does.
     */
    fun captureRotation(orientationDegrees: Int): Int = when (((orientationDegrees % 360) + 360) % 360) {
        in 45..134 -> Surface.ROTATION_270
        in 135..224 -> Surface.ROTATION_180
        in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}

/**
 * Swift `RecorderOrientationReader`: report the display rotation actually chosen
 * by the system, including same-size half turns (90 to 270), which change no
 * configuration and so would not recompose the layout.
 */
@Composable
private fun RecorderOrientationReader(changed: (Int) -> Unit) {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val current by rememberUpdatedState(changed)
    LaunchedEffect(configuration) { view.display?.let { current(it.rotation) } }
    DisposableEffect(view) {
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val display = view.display ?: return
                if (display.displayId == displayId) current(display.rotation)
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
}

/** SwiftUI `ViewThatFits(in: .horizontal)`: the first content whose natural width fits, else the second. */
@Composable
private fun ViewThatFits(first: @Composable () -> Unit, second: @Composable () -> Unit, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val wide = subcompose(0, first).firstOrNull()
        val chosen = if (wide != null && wide.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth) wide
            else subcompose(1, second).firstOrNull() ?: wide
        val placeable = chosen?.measure(constraints)
        layout(placeable?.width ?: constraints.minWidth, placeable?.height ?: constraints.minHeight) { placeable?.place(0, 0) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@ExperimentalCamera2Interop
@Composable
fun RecorderView(
    camera: CaptureController,
    project: RecorderProjectSummary?,
    start: () -> Unit,
    startNewProject: () -> Unit,
    openProjects: () -> Unit,
    openEditor: () -> Unit,
    insertionPosition: Double? = null,
) {
    val context = LocalContext.current
    val preferences = remember { RecorderPreferences(context) }
    var settingsOpen by remember { mutableStateOf(false) }
    var zoomOpen by remember { mutableStateOf(false) }
    var recordingAudioOpen by remember { mutableStateOf(false) }
    // Centre of the microphone pill, where the popover's arrow points (iOS `.popover` arrow).
    var micCenter by remember { mutableStateOf(38.dp) }
    val recording = camera.state == CaptureState.recording
    val keepFraming = camera.hasActiveTake || camera.state == CaptureState.paused
    val shutterSize = 88.dp

    // RecordingAppDelegate.keepAwake("recording", active: camera.hasActiveTake)
    val view = LocalView.current
    DisposableEffect(camera.hasActiveTake) {
        view.keepScreenOn = camera.hasActiveTake
        onDispose { view.keepScreenOn = false }
    }
    // The recorded rotation follows the phone until a take freezes it (iOS horizon-level
    // capture angle); ContentView locks the interface orientation during a take.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                camera.setOrientation(RecorderGeometry.captureRotation(degrees))
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    var controlsOrientation by remember { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    RecorderOrientationReader { controlsOrientation = it }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).semantics { testTagsAsResourceId = true }) {
        val landscape = maxWidth > maxHeight
        val top = insets.calculateTopPadding()
        val bottom = insets.calculateBottomPadding()
        val left = insets.calculateLeftPadding(layoutDirection)
        val right = insets.calculateRightPadding(layoutDirection)
        val safeWidth = maxWidth - left - right
        val safeHeight = maxHeight - top - bottom
        // Use the reported rotation only when it agrees with the layout's axes (Swift `controlsOrientation`).
        val rotation = if ((controlsOrientation % 2 == 1) == landscape) controlsOrientation else view.display?.rotation ?: Surface.ROTATION_0
        val controlsOnLeft = landscape && RecorderGeometry.controlsOnLeft(rotation)
        val fitsFrame = preferences.fitRecordedFrame
        // The picture is centred on the full display; controls are laid out in the safe area.
        val picture = RecorderGeometry.picture(maxWidth.value, maxHeight.value, left.value, top.value, right.value, bottom.value, landscape)
        val toolbarTop = controlInset(if (fitsFrame) picture.top else 0f, 44f, 12f)
        val captureGutter = RecorderGeometry.captureGutter(picture, safeWidth.value, safeHeight.value, landscape, controlsOnLeft)
        val captureInset = controlInset(if (fitsFrame) captureGutter else 0f, shutterSize.value, if (landscape) 18f else 14f)

        CameraPreview(camera, fill = !fitsFrame, pinchSeconds = preferences.zoomTransitionSeconds,
            modifier = if (fitsFrame) Modifier.align(Alignment.Center).size(picture.width.dp, picture.height.dp) else Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.32f), 0.16f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.36f))))

        if (landscape) LandscapeControls(
            camera, project, preferences, recording, keepFraming, shutterSize, controlsOnLeft, fitsFrame, picture,
            safeWidth = safeWidth.value, toolbarTop = toolbarTop, captureInset = captureInset,
            safePadding = PaddingValues(start = left, top = top, end = right, bottom = bottom),
            zoomOpen = zoomOpen, toggleZoom = { zoomOpen = !zoomOpen },
            recordingAudioOpen = recordingAudioOpen, setRecordingAudioOpen = { recordingAudioOpen = it },
            micCenter = micCenter, setMicCenter = { micCenter = it },
            openSettings = { settingsOpen = true },
            start = start, startNewProject = startNewProject, openProjects = openProjects, openEditor = openEditor,
            insertionPosition = insertionPosition)
        if (!landscape) Column(Modifier.fillMaxSize().padding(top = top + toolbarTop.dp, bottom = bottom + captureInset.dp, start = 20.dp, end = 20.dp)) {
            TopBar(camera, project, compact = true, recording = recording, onMicrophone = { recordingAudioOpen = true },
                onSettings = { settingsOpen = true }, onMicrophoneCenter = { micCenter = it })
            if (recordingAudioOpen) {
                Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(with(LocalDensity.current) { -PopoverInset.roundToPx() }, with(LocalDensity.current) { 46.dp.roundToPx() }),
                    onDismissRequest = { recordingAudioOpen = false }, properties = PopupProperties(focusable = true)) {
                    RecordingAudioControls(camera, arrowX = micCenter + PopoverInset)
                }
            }
            camera.warning?.let { WarningBanner(it) { camera.warning = null }; }
            Spacer(Modifier.weight(1f).defaultMinSize(minHeight = 12.dp))
            ProjectChip(camera, project, startNewProject, insertionPosition, openEditor)
            if (zoomOpen) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp)) { RecorderZoomPresets(camera, preferences) }
            }
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 22.dp)) { CameraStrip(camera, zoomOpen) { zoomOpen = !zoomOpen } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (keepFraming) BookmarkButton(camera, recording) else LibraryButton(camera, openProjects)
                }
                RecordButton(camera, recording, shutterSize, start)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SecondaryButton(camera, recording, openEditor) }
            }
        }

        if (recording || camera.state == CaptureState.paused) {
            val color = if (recording) RecorderColors.red.copy(alpha = 0.85f) else RecorderColors.yellow.copy(alpha = 0.75f)
            Box(Modifier.fillMaxSize().padding(3.dp).border(2.dp, color, RoundedCornerShape(40.dp)))
        }
        if (settingsOpen) CameraSettings(camera, project, preferences, landscape) { settingsOpen = false }
    }

    val error = camera.error
    if (error != null) {
        AlertDialog(onDismissRequest = { camera.error = null }, title = { Text("YouRoll") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = { camera.error = null }) { Text("OK", color = RecorderColors.cyan) } },
            containerColor = RecorderColors.sheet, titleContentColor = Color.White, textContentColor = Color.White)
    }
}

/**
 * The landscape branch of RecorderView.swift's body: the capture rail at the
 * phone's bottom end, beside a column with the top bar, banners, project chip,
 * zoom presets and camera strip.
 */
@ExperimentalCamera2Interop
@Composable
private fun LandscapeControls(
    camera: CaptureController, project: RecorderProjectSummary?, preferences: RecorderPreferences,
    recording: Boolean, keepFraming: Boolean, shutterSize: Dp, controlsOnLeft: Boolean, fitsFrame: Boolean,
    picture: RecorderGeometry.Box, safeWidth: Float, toolbarTop: Float, captureInset: Float, safePadding: PaddingValues,
    zoomOpen: Boolean, toggleZoom: () -> Unit, recordingAudioOpen: Boolean, setRecordingAudioOpen: (Boolean) -> Unit,
    micCenter: Dp, setMicCenter: (Dp) -> Unit,
    openSettings: () -> Unit, start: () -> Unit, startNewProject: () -> Unit, openProjects: () -> Unit, openEditor: () -> Unit,
    insertionPosition: Double?,
) {
    val toolbarHeight = 44f
    val railSpacing = 16f
    val captureRail: @Composable () -> Unit = {
        Column(Modifier.width(shutterSize), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (keepFraming) BookmarkButton(camera, recording) else LibraryButton(camera, openProjects)
            RecordButton(camera, recording, shutterSize, start)
            SecondaryButton(camera, recording, openEditor)
        }
    }
    Row(Modifier.fillMaxSize().padding(safePadding).padding(horizontal = captureInset.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(railSpacing.dp)) {
        if (controlsOnLeft) captureRail()
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = toolbarTop.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Over the picture, the bar stays inside the picture's edges.
            val rail = shutterSize.value + railSpacing
            val rowLeft = captureInset + if (controlsOnLeft) rail else 0f
            val rowRight = safeWidth - captureInset - if (controlsOnLeft) 0f else rail
            val abovePicture = fitsFrame && toolbarTop + toolbarHeight <= picture.top - 12f
            val (leading, trailing) = RecorderGeometry.topBarInsets(picture, rowLeft, rowRight, overPicture = fitsFrame && !abovePicture)
            Box(Modifier.fillMaxWidth().height(toolbarHeight.dp).padding(start = leading.dp, end = trailing.dp)) {
                ViewThatFits(
                    { TopBar(camera, project, compact = false, recording = recording, onMicrophone = { setRecordingAudioOpen(true) }, onSettings = openSettings,
                        onMicrophoneCenter = setMicCenter) },
                    { TopBar(camera, project, compact = true, recording = recording, onMicrophone = { setRecordingAudioOpen(true) }, onSettings = openSettings,
                        onMicrophoneCenter = setMicCenter) })
                if (recordingAudioOpen) {
                    Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(with(LocalDensity.current) { -PopoverInset.roundToPx() }, with(LocalDensity.current) { 46.dp.roundToPx() }),
                        onDismissRequest = { setRecordingAudioOpen(false) }, properties = PopupProperties(focusable = true)) {
                        RecordingAudioControls(camera, arrowX = micCenter + PopoverInset)
                    }
                }
            }
            camera.warning?.let { WarningBanner(it, topPadding = 8.dp) { camera.warning = null } }
            Spacer(Modifier.weight(1f))
            ProjectChip(camera, project, startNewProject, insertionPosition, openEditor, bottomPadding = 0.dp)
            if (zoomOpen) Box(Modifier.padding(bottom = 8.dp)) { RecorderZoomPresets(camera, preferences) }
            CameraStrip(camera, zoomOpen, toggleZoom)
        }
        if (!controlsOnLeft) captureRail()
    }
}

// MARK: - Small building blocks

@Composable
private fun PlateIcon(
    icon: ImageVector, description: String, enabled: Boolean = true, size: Dp = 52.dp, iconSize: Dp = 22.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape, plate: Color = RecorderColors.controlPlate,
    tint: Color = Color.White, onClick: () -> Unit,
) {
    // Dim the plate as well as the glyph, like a disabled iOS button whose label draws its background.
    Box(Modifier.size(size).alpha(if (enabled) 1f else 0.4f).clip(shape).background(plate, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** iOS 26 glass capsule button in a sheet's navigation bar. */
@Composable
private fun GlassButton(text: String, id: String? = null, onClick: () -> Unit) {
    Box(Modifier.height(36.dp).defaultMinSize(minWidth = 66.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
        .clickable(role = Role.Button, onClick = onClick).then(if (id != null) Modifier.testTag(id) else Modifier)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RecorderColors.cyan)
    }
}

@Composable
private fun Divider16(height: Dp = 16.dp) = Box(Modifier.width(1.dp).height(height).background(Color.White.copy(alpha = 0.2f)))

@ExperimentalCamera2Interop
@Composable
private fun TopBar(camera: CaptureController, project: RecorderProjectSummary?, compact: Boolean, recording: Boolean,
                   onMicrophone: () -> Unit, onSettings: () -> Unit, onMicrophoneCenter: (Dp) -> Unit = {}) {
    val density = LocalDensity.current
    val hasFootage = project?.hasSourceMedia == true
    val clockPlate = when {
        recording -> RecorderColors.red.copy(alpha = 0.8f)
        hasFootage && !camera.busy -> Color(0.59f, 0.11f, 0.16f)
        camera.state == CaptureState.paused -> Color.Black.copy(alpha = 0.65f)
        else -> RecorderColors.controlPlate
    }
    val noMic = camera.microphoneID.isEmpty()
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        val micName = if (noMic) "No microphone" else camera.microphoneName.replace("Microphone", "Mic")
        Column(Modifier.height(44.dp).onGloballyPositioned { onMicrophoneCenter(with(density) { (it.size.width / 2).toDp() }) }.clip(CircleShape)
            .background(if (noMic) RecorderColors.orange.copy(alpha = 0.22f) else RecorderColors.controlPlate, CircleShape)
            .clickable(enabled = !camera.busy, role = Role.Button, onClick = onMicrophone)
            .semantics { contentDescription = "Microphone: ${camera.microphoneName}"; stateDescription = camera.microphoneName }
            .padding(horizontal = if (compact) 6.dp else 10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (noMic) Icons.Filled.MicOff else Icons.Filled.Mic, null, tint = if (noMic) RecorderColors.orange else Color.White, modifier = Modifier.size(14.dp))
                // The wide (landscape) bar names the microphone beside its meter.
                if (!compact) Text(micName, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (noMic) RecorderColors.orange else Color.White, modifier = Modifier.widthIn(max = 96.dp))
                RecorderMicrophoneReadings(camera.readings, hasMicrophone = !noMic, ready = camera.ready, meterAvailable = camera.meterAvailable)
            }
            if (compact) Text(micName, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (noMic) RecorderColors.orange else Color.White, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
        }
        Spacer(Modifier.weight(1f))
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(camera.startedAt) { while (camera.startedAt != null) { now = SystemClock.elapsedRealtime(); delay(250) } }
        val elapsed = camera.startedAt?.let { max(0L, now - it) / 1000.0 } ?: 0.0
        val duration = recorderClock((project?.duration ?: 0.0) + elapsed)
        Row(Modifier.defaultMinSize(minWidth = if (compact) 52.dp else 80.dp, minHeight = 38.dp)
            .background(clockPlate, CircleShape).padding(horizontal = if (compact) 6.dp else 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (hasFootage && camera.state == CaptureState.idle) "Ready to continue recording" else camera.state.title
                stateDescription = if (camera.busy) "" else duration
            }.testTag("recorder.projectDuration"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
            if (camera.busy) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            else if (recording && !compact) Text("REC", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            else if (camera.state == CaptureState.paused) {
                if (compact) Icon(Icons.Filled.Pause, null, tint = RecorderColors.yellow, modifier = Modifier.size(12.dp))
                else Text("PAUSED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RecorderColors.yellow)
            }
            Text(if (camera.busy) camera.state.title else duration, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        // Live effects are not ported yet (TODO.md); the control keeps its place, disabled.
        PlateIcon(Icons.Filled.AutoAwesome, "Live video effects", enabled = false, size = 44.dp, iconSize = 20.dp) {}
        Box {
            PlateIcon(Icons.Filled.Tune, "Camera settings", size = 44.dp, iconSize = 20.dp, onClick = onSettings)
            if (camera.stabilizationEnabled && camera.singleStabilizationSupported) {
                Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(6.dp).background(RecorderColors.cyan, CircleShape))
            }
        }
    }
}

@Composable
private fun RecorderMicrophoneReadings(readings: CaptureReadings, hasMicrophone: Boolean, ready: Boolean, meterAvailable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hasMicrophone) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (index in 0 until 7) {
                    val lit = readings.level > -54 + index * 8
                    Box(Modifier.width(3.dp).height((5 + index * 2).dp).background(
                        if (lit) (if (index > 5) Color.Red else Color.White) else Color.White.copy(alpha = 0.22f), CircleShape))
                }
            }
        }
        if (readings.audioClipping) Icon(Icons.Filled.Error, "Clipping", tint = Color.Red, modifier = Modifier.size(14.dp))
        else if (!readings.audioArriving && ready && meterAvailable) Icon(Icons.Filled.MicOff, "No audio", tint = RecorderColors.yellow, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun WarningBanner(warning: String, topPadding: Dp = 12.dp, dismiss: () -> Unit) {
    // Android addition: the banner dismisses itself after a few seconds;
    // longer messages stay a little longer. The message is also in the log.
    val currentDismiss by androidx.compose.runtime.rememberUpdatedState(dismiss)
    androidx.compose.runtime.LaunchedEffect(warning) {
        kotlinx.coroutines.delay((4000L + warning.length * 40L).coerceAtMost(9000L))
        currentDismiss()
    }
    Row(Modifier.padding(top = topPadding).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, tint = RecorderColors.yellow, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(warning, fontSize = 12.sp, color = RecorderColors.yellow, modifier = Modifier.weight(1f).testTag("recorder.warning"))
        Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = dismiss).semantics { contentDescription = "Dismiss warning" },
            contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, null, tint = RecorderColors.yellow, modifier = Modifier.size(16.dp)) }
    }
}

@ExperimentalCamera2Interop
@Composable
private fun ColumnScope.ProjectChip(camera: CaptureController, project: RecorderProjectSummary?, startNewProject: () -> Unit,
                                     insertionPosition: Double?, openEditor: () -> Unit, bottomPadding: Dp = 6.dp) {
    // iOS `projectChip`: while recording into an editor position, offer the way back instead.
    if (insertionPosition != null) {
        val enabled = !camera.hasActiveTake
        Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = bottomPadding).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(32.dp).background(Color.Black.copy(alpha = 0.28f), CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = openEditor).padding(horizontal = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Finish insertion and return to editor" }
                .testTag("recorder.finishInsertion"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
                Icon(Icons.AutoMirrored.Filled.Undo, null, tint = tint, modifier = Modifier.size(12.dp))
                Text("Insert at " + editorTime(insertionPosition) + " · Back to edit", fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
            }
        }
    } else if (project != null && project.hasSourceMedia && !camera.hasActiveTake) {
        Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = bottomPadding).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(32.dp).background(Color.Black.copy(alpha = 0.28f), CircleShape)
                .clickable(role = Role.Button, onClick = startNewProject).padding(horizontal = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Start new project" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text("Start new project", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
            }
        }
    }
}

@ExperimentalCamera2Interop
@Composable
private fun CameraStrip(camera: CaptureController, zoomOpen: Boolean, toggleZoom: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.background(RecorderColors.material, CircleShape).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val opposite = camera.oppositeCamera
        val flipEnabled = camera.ready && !camera.busy && !camera.switchingCamera && opposite != null
        Box(Modifier.size(44.dp).clickable(enabled = flipEnabled, role = Role.Button) { camera.flipCamera() }
            .semantics { contentDescription = "Switch to ${if (opposite?.front == true) "front" else "back"} camera"; stateDescription = opposite?.name ?: "Unavailable" }
            .testTag("recorder.flip").alpha(if (flipEnabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
            // iOS: camera.rotate at 18 pt medium.
            Icon(Icons.Outlined.Cameraswitch, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        val menuEnabled = camera.ready && !camera.busy && !camera.switchingCamera
        val name = camera.currentCamera?.name?.replace("Rear ", "")?.split(" ")
            ?.joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } } ?: "Camera"
        Box {
            Row(Modifier.sizeIn(minWidth = 64.dp, maxWidth = 118.dp, minHeight = 44.dp)
                .clickable(enabled = menuEnabled, role = Role.Button) { menuOpen = true }
                .semantics(mergeDescendants = true) { contentDescription = "Camera"; stateDescription = camera.currentCamera?.name ?: "Unavailable" }
                .testTag("recorder.camera").padding(end = 8.dp).alpha(if (menuEnabled) 1f else 0.4f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }, containerColor = RecorderColors.sheet) {
                for (choice in camera.cameras) {
                    val selected = choice.id == camera.cameraID
                    DropdownMenuItem(
                        text = { Text(if (choice.available) choice.name else choice.name + " · 1080p only", color = Color.White) },
                        leadingIcon = { Icon(if (selected) Icons.Filled.Check else Icons.Outlined.PhotoCamera, null, tint = Color.White) },
                        enabled = choice.available,
                        onClick = { menuOpen = false; camera.switchCamera(choice.id) })
                }
            }
        }
        Divider16()
        RecorderZoomButton(camera, zoomOpen, toggleZoom)
        Divider16()
        Box {
            RecorderFocusButton(camera)
            camera.focusHint?.let { hint ->
                Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, with(LocalDensity.current) { (-40).dp.roundToPx() })) {
                    Text(hint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.82f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("recorder.focusFeedback"))
                }
            }
        }
    }
}

@ExperimentalCamera2Interop
@Composable
private fun RecorderZoomButton(camera: CaptureController, open: Boolean, toggle: () -> Unit) {
    val value = camera.readings.zoom * camera.displayZoomMultiplier
    val enabled = camera.ready && !camera.busy
    Box(Modifier.size(58.dp, 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = toggle)
        .semantics { contentDescription = "Zoom, ${ZoomTransition.formatted(Math.round(value * 10) / 10.0)} times"; stateDescription = String.format(Locale.US, "%.1f", value) }
        .testTag("recorder.zoom").alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        Text(String.format(Locale.US, "%.1f×", value), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = if (open) RecorderColors.yellow else Color.White)
    }
}

@ExperimentalCamera2Interop
@Composable
private fun RecorderFocusButton(camera: CaptureController) {
    val readings = camera.readings
    val density = LocalDensity.current
    var adjustedLens by remember { mutableStateOf<Float?>(null) }
    val canToggleMode = camera.focusLockSupported && camera.autofocusSupported
    val enabled = camera.ready && !camera.busy && !camera.switchingCamera
    fun toggleMode() {
        if (canToggleMode) camera.setFocus(locked = !readings.focusLocked)
        else if (camera.fixedFocus) camera.showFocusHint("This lens has fixed focus")
    }
    val label = if (camera.fixedFocus) "Fixed" else if (readings.focusLocked) (if (camera.focusAdjustable) "MF" else "Locked") else "AF"
    val mode = if (camera.fixedFocus) "Fixed focus" else if (readings.focusLocked) "Locked · ${Math.round(readings.lensPosition * 100)}%" else "Auto"
    Box(Modifier.size(52.dp, 44.dp)
        .pointerInput(enabled, canToggleMode, camera.fixedFocus) { if (enabled) detectTapGestures { toggleMode() } }
        .pointerInput(enabled, readings.focusLocked, camera.focusAdjustable) {
            if (!enabled || !readings.focusLocked || !camera.focusAdjustable) return@pointerInput
            var start = 0f; var travel = 0f
            detectVerticalDragGestures(
                onDragStart = { start = readings.lensPosition; travel = 0f },
                onDragEnd = { adjustedLens?.let { camera.setFocus(true, it) }; adjustedLens = null },
                onDragCancel = { adjustedLens = null },
            ) { change, dy ->
                change.consume()
                travel += dy
                // A short stroke gives fine control. Longer strokes traverse the lens faster.
                val d = kotlin.math.abs(travel) / density.density
                val amount = min(d, 40f) / 320f + max(0f, d - 40f) / 160f
                val lens = (start + if (travel < 0) amount else -amount).coerceIn(0f, 1f)
                if (lens != adjustedLens) { adjustedLens = lens; camera.setFocus(true, lens, persist = false) }
            }
        }
        .semantics {
            contentDescription = if (camera.fixedFocus) "Fixed focus" else if (readings.focusLocked) (if (camera.focusAdjustable) "Manual focus" else "Focus locked") else "Autofocus"
            stateDescription = mode
        }.testTag("recorder.focus").alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        val color = if (readings.focusLocked) RecorderColors.yellow else Color.White
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (readings.focusLocked && camera.focusAdjustable) Icon(Icons.Filled.KeyboardArrowUp, null, tint = color, modifier = Modifier.size(10.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            if (readings.focusLocked && camera.focusAdjustable) Icon(Icons.Filled.KeyboardArrowDown, null, tint = color, modifier = Modifier.size(10.dp))
        }
        adjustedLens?.let { lens ->
            Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, with(density) { (-40).dp.roundToPx() })) {
                Text("Focus ${Math.round(lens * 100)}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(RecorderColors.sheet, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@ExperimentalCamera2Interop
@Composable
private fun RecorderZoomPresets(camera: CaptureController, preferences: RecorderPreferences) {
    var hint by remember { mutableStateOf(0L) }
    val enabled = camera.ready && !camera.busy && !camera.switchingCamera
    val presets = CameraCatalog.zoomPresets(camera.minZoom, camera.maxZoom, camera.displayZoomMultiplier)
    val main = camera.mainLensAtOneX
    fun select(zoom: Double, seconds: Double) {
        hint = 0
        if (zoom == 1.0 && main != null) camera.switchCamera(main.id, nativeZoom = true)
        else camera.setZoom(zoom / camera.displayZoomMultiplier, seconds)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hint != 0L) {
            LaunchedEffect(hint) { delay(2500); hint = 0 }
            Text("Swipe up/down to change zoom time", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.8f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        Row(Modifier.height(48.dp).background(RecorderColors.material, CircleShape).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RecorderZoomDuration(preferences) { hint = SystemClock.uptimeMillis() }
            Divider16(22.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()).alpha(if (enabled) 1f else 0.4f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (zoom in presets) {
                    val selectsMainLens = zoom == 1.0 && main != null
                    val selected = !selectsMainLens && kotlin.math.abs(camera.readings.zoom * camera.displayZoomMultiplier - zoom) < 0.04
                    RecorderZoomPreset(zoom, selectsMainLens, selected, enabled,
                        normal = { select(zoom, preferences.zoomTransitionSeconds) }, quick = { select(zoom, 1.0) })
                }
            }
        }
    }
}

@Composable
private fun RecorderZoomPreset(zoom: Double, selectsMainLens: Boolean, selected: Boolean, enabled: Boolean, normal: () -> Unit, quick: () -> Unit) {
    val color = if (selected) RecorderColors.yellow else Color.White
    Column(Modifier.size(44.dp, 48.dp).clip(CircleShape)
        // Wait to distinguish taps; in particular Instant must not fire before the second tap.
        .pointerInput(enabled) { if (enabled) detectTapGestures(onDoubleTap = { quick() }, onTap = { normal() }) }
        .semantics {
            contentDescription = if (selectsMainLens) "1 times zoom, Main camera" else "${ZoomTransition.formatted(zoom)} times zoom"
            if (selected) stateDescription = "Selected"
        }.testTag("recorder.zoom.preset.${(zoom * 10).toInt()}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("${ZoomTransition.formatted(zoom)}×", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
        if (selectsMainLens) Text("Main", fontSize = 9.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** Separate from the live readings so an adjustment survives sensor updates. */
@Composable
private fun RecorderZoomDuration(preferences: RecorderPreferences, showHint: () -> Unit) {
    val seconds = preferences.zoomTransitionSeconds
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val color = if (seconds == 0.0) Color.White else RecorderColors.yellow
    Box(Modifier.size(56.dp, 48.dp)
        .pointerInput(Unit) { detectTapGestures { showHint() } }
        .pointerInput(Unit) {
            var start = 0.0; var travel = 0f
            detectVerticalDragGestures(onDragStart = { start = preferences.zoomTransitionSeconds; travel = 0f; dragging = true },
                onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, dy ->
                change.consume(); travel += dy
                val steps = (-travel / density.density / 14f).toInt()
                preferences.setZoomSeconds(start + steps * 0.5)
            }
        }
        .semantics { contentDescription = "Zoom transition duration"; stateDescription = ZoomTransition.durationLabel(seconds) }
        .testTag("recorder.zoom.duration"), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Timer, null, tint = color, modifier = Modifier.size(11.dp))
                Text(if (seconds == 0.0) "Instant" else "${ZoomTransition.formatted(seconds)}s", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = color, maxLines = 1)
            }
            Icon(Icons.Filled.UnfoldMore, null, tint = color, modifier = Modifier.size(10.dp))
        }
        if (dragging) {
            Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(with(density) { 36.dp.roundToPx() }, with(density) { (-42).dp.roundToPx() })) {
                Text(ZoomTransition.durationLabel(seconds), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.background(RecorderColors.sheet, CircleShape).padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
    }
}

@ExperimentalCamera2Interop
@Composable
private fun RecordButton(camera: CaptureController, recording: Boolean, size: Dp, start: () -> Unit) {
    val enabled = recording || (camera.ready && !camera.busy && !camera.switchingCamera)
    val paused = camera.state == CaptureState.paused
    val inner by animateDpAsState(if (recording) 30.dp else 66.dp, label = "shutter")
    val corner by animateDpAsState(if (recording) 7.dp else 33.dp, label = "corner")
    Box(Modifier.size(size)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
            if (recording) camera.stop() else start()
        }
        .semantics { contentDescription = if (recording) "Stop recording" else if (paused) "Resume recording" else "Record into this project" }
        .testTag("recorder.record"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(80.dp).shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.16f))
            .border(BorderStroke(3.dp, Color.White), CircleShape))
        when {
            camera.busy -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
            paused -> {
                Box(Modifier.size(66.dp).background(RecorderColors.red, CircleShape))
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp).offset(x = 2.dp))
            }
            else -> Box(Modifier.size(inner).background(RecorderColors.red, RoundedCornerShape(corner)))
        }
    }
}

@ExperimentalCamera2Interop
@Composable
private fun LibraryButton(camera: CaptureController, openProjects: () -> Unit) =
    PlateIcon(ImageVector.vectorResource(R.drawable.ic_square_stack), "Projects", enabled = !camera.hasActiveTake, shape = RoundedCornerShape(15.dp), onClick = openProjects)

@ExperimentalCamera2Interop
@Composable
private fun BookmarkButton(camera: CaptureController, recording: Boolean) {
    var acknowledged by remember { mutableStateOf(false) }
    LaunchedEffect(acknowledged) { if (acknowledged) { delay(700); acknowledged = false } }
    Column(Modifier.size(52.dp).alpha(if (recording) 1f else 0.4f).clip(RoundedCornerShape(15.dp)).background(RecorderColors.controlPlate)
        .clickable(enabled = recording, role = Role.Button) { camera.addBookmark(); acknowledged = true }
        .semantics { contentDescription = "Add recording bookmark"; stateDescription = if (acknowledged) "Bookmark added" else "Mark this moment" }
        .testTag("recorder.bookmark"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(if (acknowledged) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text("Mark", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@ExperimentalCamera2Interop
@Composable
private fun SecondaryButton(camera: CaptureController, recording: Boolean, openEditor: () -> Unit) {
    when {
        recording -> PlateIcon(Icons.Filled.Pause, "Pause recording", iconSize = 24.dp) { camera.stop(pause = true) }
        camera.state == CaptureState.paused -> PlateIcon(Icons.Filled.Stop, "Finish paused recording", iconSize = 24.dp) { camera.finishPausedTake() }
        else -> PlateIcon(Icons.Filled.ContentCut, "Edit this project", enabled = !camera.busy, onClick = openEditor)
    }
}

// MARK: - Microphone popover (RecordingAudioControls)

@ExperimentalCamera2Interop
@Composable
private fun RecordingAudioControls(camera: CaptureController, arrowX: Dp) {
    var explanation by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val readings = camera.readings
    val plate = Color(42, 42, 44)
    Column(Modifier.width(310.dp)) {
    // The iOS popover's arrow, pointing up at the microphone pill.
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val tip = arrowX.toPx().coerceIn(28.dp.toPx(), size.width - 28.dp.toPx())
        val half = 13.dp.toPx()
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(tip - half, size.height + 1f); lineTo(tip, 0f); lineTo(tip + half, size.height + 1f); close()
        }, plate)
    }
    Column(Modifier.fillMaxWidth().background(plate, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            // iOS keeps the title "Recording volume" (the gain slider itself is in TODO.md).
            Text("Recording volume", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable(role = Role.Button) { explanation = !explanation }
                .semantics { contentDescription = "About recording volume" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, null, tint = Color.White)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { ((readings.level + 60) / 60f).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp),
                color = if (readings.audioClipping) Color.Red else RecorderColors.green, trackColor = Color.White.copy(alpha = 0.15f), drawStopIndicator = {})
            Spacer(Modifier.width(8.dp))
            Text(if (readings.audioClipping) "Clipping" else if (readings.audioArriving) String.format(Locale.US, "%.0f dBFS", readings.level) else "No signal",
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (readings.audioClipping) Color.Red else RecorderColors.secondary)
        }
        Box {
            Row(Modifier.defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button) { menu = true }
                .semantics(mergeDescendants = true) { contentDescription = "Input: ${camera.microphoneName}" }.testTag("recorder.gainMicrophone"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White)
                Text(camera.microphoneName, fontSize = 15.sp, color = Color.White)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = RecorderColors.sheet) {
                for (mic in camera.microphones) {
                    DropdownMenuItem(text = { Text(mic.name, color = Color.White) },
                        leadingIcon = { Icon(if (mic.id == camera.microphoneID) Icons.Filled.Check else Icons.Filled.Mic, null, tint = Color.White) },
                        onClick = { menu = false; camera.selectMicrophone(mic.id) })
                }
            }
        }
        if (explanation) {
            Text("Shows the input the recording uses and its level before any editor processing. Red means the signal may distort. " +
                "Recording volume adjustment is not available on Android yet.", fontSize = 12.sp, lineHeight = CaptionLineHeight, color = RecorderColors.secondary)
        }
    }
    }
}

// MARK: - Camera settings (quick panel) and More settings

@ExperimentalCamera2Interop
@Composable
private fun CameraSettings(camera: CaptureController, project: RecorderProjectSummary?, preferences: RecorderPreferences, landscape: Boolean, close: () -> Unit) {
    var moreSettings by remember { mutableStateOf(false) }
    val canChange = camera.ready && !camera.hasActiveTake && !camera.switchingCamera
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (moreSettings) 1f else 0.25f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close))
        if (moreSettings) {
            RecorderSettings(camera, project, preferences, back = { moreSettings = false }, close = close)
            return@BoxWithConstraints
        }
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        // In landscape the quick panel is a full-height side panel at the trailing edge.
        val placement = if (landscape) {
            val direction = LocalLayoutDirection.current
            val safeWidth = maxWidth - insets.calculateLeftPadding(direction) - insets.calculateRightPadding(direction)
            Modifier.align(Alignment.CenterEnd).padding(insets).padding(6.dp)
                .width(min(496f, safeWidth.value * 0.70f).dp - 12.dp).fillMaxHeight()
        } else Modifier.align(Alignment.BottomCenter).padding(6.dp).padding(bottom = insets.calculateBottomPadding())
            .fillMaxWidth().height(min(maxHeight.value, 388f).dp - 12.dp)
        Column(placement
            .clip(RoundedCornerShape(20.dp)).background(RecorderColors.sheet)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            Box(Modifier.fillMaxWidth().height(60.dp)) {
                Text("Camera settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { GlassButton("Done", "recorder.settingsDone", close) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stabilizeOn = camera.stabilizationEnabled
                    Assist("Stabilize", Icons.Outlined.CropFree, on = stabilizeOn,
                        enabled = canChange && (camera.singleStabilizationSupported || stabilizeOn), id = "recorder.dualStabilization") {
                        camera.setStabilization(!stabilizeOn)
                    }
                    // Not ported yet (TODO.md): shown in place, disabled.
                    Assist("Level horizon", ImageVector.vectorResource(R.drawable.ic_level), on = false, enabled = false, id = "recorder.levelHorizon") {}
                    Assist("Follow face", ImageVector.vectorResource(R.drawable.ic_face_dashed), on = false, enabled = false, id = "recorder.dualFaceFollow") {}
                    Assist("Front light", Icons.Outlined.Lightbulb, on = false, enabled = false, id = "recorder.frontLight") {}
                }
                // Swift `cameras(sideBySide:)`: the toggle and its note form one VStack(spacing: 6).
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use two cameras", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                        Switch(checked = false, onCheckedChange = null, enabled = false, modifier = Modifier.semantics { contentDescription = "Use two cameras" })
                    }
                    Text("Two-camera capture is not available in YouRoll yet.", fontSize = 12.sp, lineHeight = CaptionLineHeight, color = RecorderColors.secondary)
                }
                RecordingColorControls(camera)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button) { moreSettings = true }.padding(horizontal = 20.dp)
                .semantics(mergeDescendants = true) {}.testTag("recorder.moreSettings"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Settings, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("More settings", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, null, tint = RecorderColors.secondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Assist(title: String, icon: ImageVector, on: Boolean, enabled: Boolean, id: String, action: () -> Unit) {
    val tint = if (on) RecorderColors.cyan else Color.White
    // iOS dims the whole tile, fill included, so alpha comes before the background.
    Box(Modifier.weight(1f).height(62.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(10.dp))
        .background(if (on) RecorderColors.cyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f))
        .clickable(enabled = enabled, role = Role.Switch, onClick = action)
        .semantics { contentDescription = title; stateDescription = if (on) "On" else "Off" }.testTag(id), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
        }
        if (on) Icon(Icons.Filled.CheckCircle, null, tint = RecorderColors.cyan, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(11.dp))
    }
}

@ExperimentalCamera2Interop
@Composable
private fun RecordingColorControls(camera: CaptureController) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Recording color", fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(11.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (mode in listOf("SDR", "HDR", "Log")) {
                val selected = mode == "SDR"
                Box(Modifier.weight(1f).height(44.dp).background(if (selected) RecorderColors.cyan else Color.Transparent, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = mode; if (selected) stateDescription = "Selected" }
                    .alpha(if (selected) 1f else 0.4f), contentAlignment = Alignment.Center) {
                    Text(mode, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.Black else RecorderColors.secondary)
                }
            }
        }
        // Swift `RecordingColorControls.note`. HDR and Log are not ported (TODO.md), so every
        // camera takes the "SDR only" case; the note stays one line, as on iOS.
        val note = when {
            camera.hasActiveTake -> "Pause to change recording color, stabilization or the two-camera setup."
            !camera.ready -> "Recording color is available when the camera is ready."
            else -> "This camera setup supports SDR only."
        }
        Text(note,
            fontSize = 12.sp, lineHeight = CaptionLineHeight, color = RecorderColors.secondary)
    }
}

/** How far the microphone popover starts left of the pill, as the iOS popover does. */
private val PopoverInset = 9.dp

/** SF `.caption` (12 pt) and `.footnote` (13 pt) line heights; the Material default (24 sp) is far taller. */
private val CaptionLineHeight = 16.sp
private val FootnoteLineHeight = 18.sp

@Composable
private fun FormSection(header: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp)) {
        header?.let { Text(it, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RecorderColors.secondary, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(RecorderColors.row).padding(horizontal = 24.dp, vertical = 6.dp), content = content)
        footer?.let { Text(it, fontSize = 13.sp, lineHeight = FootnoteLineHeight, color = RecorderColors.secondary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) }
    }
}

@Composable
private fun FormToggle(title: String, checked: Boolean, enabled: Boolean = true, id: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).alpha(if (enabled) 1f else 0.4f)
        .then(if (id != null) Modifier.testTag(id) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = RecorderColors.cyan, checkedThumbColor = Color.White),
            modifier = Modifier.semantics { contentDescription = title })
    }
}

/**
 * The trailing value of an iOS menu-style `Picker` row. Its menu is anchored here, at the
 * trailing edge, 250 dp wide like the iOS menu.
 */
@Composable
private fun PickerValue(value: String, open: Boolean, dismiss: () -> Unit, items: @Composable ColumnScope.() -> Unit) {
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 17.sp, color = RecorderColors.cyan)
            Icon(Icons.Filled.UnfoldMore, null, tint = RecorderColors.cyan, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = dismiss, containerColor = RecorderColors.sheet, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.width(250.dp), content = items)
    }
}

/** An iOS menu item: 17 pt regular, with the checkmark column kept for every row. */
@Composable
private fun PickerMenuItem(title: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Normal, color = Color.White) },
        leadingIcon = { Box(Modifier.size(20.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) } },
        onClick = onClick)
}

@Composable
private fun RowSeparator() = HorizontalDivider(color = Color.White.copy(alpha = 0.12f), thickness = 0.5.dp)

@Composable
private fun Caption(text: String) = Text(text, fontSize = 12.sp, lineHeight = CaptionLineHeight, color = RecorderColors.secondary, modifier = Modifier.padding(vertical = 8.dp))

@ExperimentalCamera2Interop
@Composable
private fun RecorderSettings(camera: CaptureController, project: RecorderProjectSummary?, preferences: RecorderPreferences, back: () -> Unit, close: () -> Unit) {
    val keepFraming = camera.hasActiveTake || camera.state == CaptureState.paused
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = insets.calculateTopPadding())
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Box(Modifier.fillMaxWidth().height(60.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(44.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)).clickable(role = Role.Button, onClick = back)
                .semantics { contentDescription = "Back to camera settings" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = Color.White, modifier = Modifier.size(18.dp).offset(x = 3.dp))
            }
            Text("More settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { GlassButton("Done", "recorder.moreSettingsDone", close) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = insets.calculateBottomPadding() + 24.dp)) {
            FormSection("This project") {
                Text(project?.title ?: "New project", fontSize = 17.sp, color = Color.White, modifier = Modifier.padding(vertical = 10.dp))
                RowSeparator()
                Text("${recorderClock(project?.capturedDuration ?: 0.0)} captured", fontSize = 12.sp, color = RecorderColors.secondary, modifier = Modifier.padding(bottom = 10.dp))
            }
            FormSection("Picture") {
                FormToggle("Mirror selfie preview", camera.mirrorPreview, id = "recorder.mirrorPreview") { camera.setMirrorPreview(it) }
                RowSeparator()
                FormToggle("Save mirrored selfies", camera.mirrorRecording, enabled = !keepFraming, id = "recorder.mirrorRecording") { camera.setMirrorRecording(it) }
                RowSeparator()
                Caption("The front-camera preview behaves like a mirror by default. Saved video keeps writing readable unless Save mirrored selfies is on.")
            }
            FormSection("Focus", footer = if (camera.fixedFocus) "This lens has fixed focus." else if (camera.focusAdjustable)
                "In the recorder, swipe up on MF for farther focus or down for nearer focus." else "This lens does not offer manual focus.") {
                FormToggle("Lock focus", camera.readings.focusLocked, enabled = camera.focusLockSupported && !camera.fixedFocus) { camera.setFocus(locked = it) }
                if (camera.focusAdjustable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Near", fontSize = 12.sp, color = Color.White)
                        Slider(value = camera.readings.lensPosition, onValueChange = { camera.setFocus(true, it) }, modifier = Modifier.weight(1f)
                            .padding(horizontal = 8.dp).semantics { contentDescription = "Manual lens position" },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = RecorderColors.cyan))
                        Text("Far", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
            FormSection("Zoom") {
                var open by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button) { open = true }
                    .semantics(mergeDescendants = true) { contentDescription = "Zoom transition duration"; stateDescription = ZoomTransition.durationLabel(preferences.zoomTransitionSeconds) }
                    .testTag("recorder.transition"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Transition", fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
                    PickerValue(ZoomTransition.durationLabel(preferences.zoomTransitionSeconds), open, dismiss = { open = false }) {
                        for (seconds in (ZoomTransition.durationPresets + preferences.zoomTransitionSeconds).toSortedSet()) {
                            PickerMenuItem(ZoomTransition.durationLabel(seconds), seconds == preferences.zoomTransitionSeconds) {
                                open = false; preferences.setZoomSeconds(seconds)
                            }
                        }
                    }
                }
                Caption("Swipe the time beside the zoom presets up or down to adjust by half a second. Double-tap a zoom preset to reach it in one second. Each camera remembers its own zoom.")
            }
            FormSection("Microphone") {
                for (mic in camera.microphones) {
                    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button) { camera.selectMicrophone(mic.id) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(mic.name, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
                        if (mic.id == camera.microphoneID) Icon(Icons.Filled.Check, "Selected", tint = RecorderColors.cyan)
                    }
                }
                Caption("A quick selection lasts until Stop. Priority below is remembered, including disconnected microphones.")
            }
            FormSection("Microphone priority · highest first") {
                camera.microphonePriority.forEachIndexed { index, mic ->
                    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mic.name, fontSize = 17.sp, color = Color.White)
                            if (camera.microphones.none { it.id == mic.id }) Text("Disconnected", fontSize = 12.sp, color = RecorderColors.secondary)
                        }
                        PlateIcon(Icons.Filled.ArrowUpward, "Move ${mic.name} earlier", enabled = index > 0, size = 44.dp, iconSize = 18.dp,
                            plate = Color.Transparent, tint = RecorderColors.cyan) { camera.moveMicrophonePreference(mic.id, -1) }
                        PlateIcon(Icons.Filled.ArrowDownward, "Move ${mic.name} later", enabled = index + 1 < camera.microphonePriority.size, size = 44.dp,
                            iconSize = 18.dp, plate = Color.Transparent, tint = RecorderColors.cyan) { camera.moveMicrophonePreference(mic.id, 1) }
                    }
                }
            }
            FormSection("Recording quality") {
                var open by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).alpha(if (keepFraming) 0.4f else 1f)
                    .clickable(enabled = !keepFraming, role = Role.Button) { open = true }
                    .semantics(mergeDescendants = true) { contentDescription = "Quality"; stateDescription = camera.quality.title }
                    .testTag("recorder.quality"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quality", fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
                    PickerValue(camera.quality.title, open, dismiss = { open = false }) {
                        for (quality in CaptureQuality.entries) {
                            PickerMenuItem(quality.title, quality == camera.quality) {
                                open = false; if (quality != camera.quality) camera.setQuality(quality)
                            }
                        }
                    }
                }
                Caption("4K uses more storage and power. Quality stays fixed until Stop.")
            }
            FormSection("Framing") {
                FormToggle("Fit entire recorded frame", preferences.fitRecordedFrame) { preferences.setFit(it) }
                Caption("The full-screen view fills the display. Fit shows the complete recorded frame.")
            }
        }
    }
}
