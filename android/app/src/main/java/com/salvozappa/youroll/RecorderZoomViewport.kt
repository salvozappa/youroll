// Port of YouRec/RecorderZoomViewport.swift
// YouRec copyright (c) 2026 Salvatore Sanfilippo, MIT licence.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
//
// Gesture coordinates and the recorder HUD stay fixed while composing a camera move.
// Ported: two-finger pinch with the same neutral zone and easing, live zoom for
// Instant, a speculative scaled preview committed as a timed ramp on release, the
// magnification badge, tap to focus with the focus box (from CameraPreview.Preview)
// and the frozen frame shown while a camera change completes.
// Not ported (TODO.md): panning/manual framing centres, the wider sensor picture
// shown around a zoomed-out speculative move, face-follow hand-off.
package com.salvozappa.youroll

import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/** RecorderZoomViewport.Move, reduced to the centred zoom this port supports. */
private class Move(val lens: String, val seconds: Double, val startZoom: Double, val initialSpan: Float) {
    var zoom = startZoom
    var appliedLiveChange = false
}

/** Pure pinch maths shared with the tests: the neutral zone eases into the zoom step. */
object RecorderPinch {
    fun zoom(startZoom: Double, initialSpan: Float, span: Float, minZoom: Double, maxZoom: Double): Double {
        val stops = ln(span.toDouble() / initialSpan)
        val t = ((abs(stops) - 0.022) / 0.033).coerceIn(0.0, 1.0)
        return (startZoom * exp(stops * t * t * (3 - 2 * t))).coerceIn(minZoom, maxOf(minZoom, maxZoom))
    }

    fun badge(zoom: Double, multiplier: Double, seconds: Double): String {
        val magnification = String.format(Locale.US, "%.1f×", zoom * multiplier)
        return if (seconds == 0.0) magnification else "$magnification  ·  ${ZoomTransition.formatted(seconds)}s on release"
    }
}

@ExperimentalCamera2Interop
@Composable
fun RecorderZoomViewport(camera: CaptureController, seconds: Double, fill: Boolean, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var move by remember { mutableStateOf<Move?>(null) }
    var ratio by remember { mutableStateOf(1f) }
    var badge by remember { mutableStateOf<String?>(null) }
    val enabled = camera.ready && !camera.busy && !camera.switchingCamera && camera.maxZoom > camera.minZoom
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSeconds by rememberUpdatedState(ZoomTransition.boundedDuration(seconds))

    BoxWithConstraints(
        modifier
            .background(if (move != null && currentSeconds > 0) Color(0.10f, 0.10f, 0.10f) else Color.Black)
            .clipToBounds()
            .pointerInput(camera) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var active: Move? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val a = pressed[0].position; val b = pressed[1].position
                            val span = maxOf(1f, hypot(a.x - b.x, a.y - b.y))
                            if (active == null && currentEnabled) {
                                active = Move(camera.cameraID, currentSeconds, camera.readings.zoom, span)
                                move = active
                            }
                            active?.let { m ->
                                if (camera.cameraID != m.lens || camera.switchingCamera) { active = null; move = null; ratio = 1f; badge = null; return@let }
                                val zoom = RecorderPinch.zoom(m.startZoom, m.initialSpan, span, camera.minZoom, camera.maxZoom)
                                if (m.seconds == 0.0) {
                                    if (abs(zoom - m.zoom) > 0.001) {
                                        m.appliedLiveChange = true
                                        camera.setZoom(zoom, 0.0, lens = m.lens, persist = false)
                                    }
                                } else ratio = (zoom / m.startZoom).toFloat()
                                m.zoom = zoom
                                badge = RecorderPinch.badge(zoom, camera.displayZoomMultiplier, m.seconds)
                                event.changes.forEach { it.consume() }
                            }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    active?.let { m ->
                        // Ending the gesture commits exactly once.
                        val changed = abs(m.zoom / m.startZoom - 1) > 0.005
                        if (changed || m.appliedLiveChange) camera.setZoom(m.zoom, m.seconds, lens = m.lens)
                    }
                    move = null; ratio = 1f; badge = null
                }
            }
            .pointerInput(camera) {
                detectTapGestures { offset ->
                    val view = previewView ?: return@detectTapGestures
                    if (camera.frozenFrame != null) return@detectTapGestures
                    camera.autofocus(view.meteringPointFactory.createPoint(offset.x, offset.y), offset)
                }
            }
    ) {
        if (move != null && currentSeconds > 0) {
            // The grid shows where the speculative framing leaves the live picture.
            Canvas(Modifier.fillMaxSize()) {
                val step = 18.dp.toPx(); val line = Color(0.27f, 0.27f, 0.27f)
                var x = 0f; while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 0.75.dp.toPx()); x += step }
                var y = 0f; while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 0.75.dp.toPx()); y += step }
            }
        }
        val unmirror = camera.currentCamera?.front == true && !camera.mirrorPreview
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    contentDescription = "Camera preview"
                    camera.attachPreviewView(this, owner)
                    previewView = this
                }
            },
            update = { view ->
                val type = if (fill) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
                if (view.scaleType != type) view.scaleType = type
            },
            modifier = Modifier.fillMaxSize().testTag("recorder.preview").graphicsLayer {
                scaleX = ratio * (if (unmirror) -1f else 1f); scaleY = ratio
            },
        )
        camera.frozenFrame?.let { frame ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(frame.asImageBitmap(), contentDescription = null, contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(if (camera.previewPaused) 0.4f else 1f))
                if (camera.previewPaused) Text("Preview paused", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center))
            }
        }
        val marker = camera.focusMarker
        val focusAlpha by animateFloatAsState(if (marker != null) 1f else 0f, tween(if (marker != null) 0 else 220), label = "focus")
        var lastMarker by remember { mutableStateOf<Offset?>(null) }
        if (marker != null) lastMarker = marker.point
        lastMarker?.let { point ->
            val half = with(density) { 24.dp.toPx() }
            Box(Modifier
                .offset { IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt()) }
                .size(48.dp).alpha(focusAlpha)
                .border(1.5.dp, Color(1f, 0.8f, 0f), RoundedCornerShape(3.dp))
                .semantics { contentDescription = "Focus point" })
        }
        badge?.let { text ->
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.27f - 14.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("recorder.pinchZoomTarget"))
        }
    }
}
