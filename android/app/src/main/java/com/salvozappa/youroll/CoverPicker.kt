// Port of YouRec/CoverPicker.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// "Select cover" in Tools: nine frames sampled across the scope (the marked
// range, the selected clips or the whole project; `CoverFramePool`), Update for
// other frames, and a frame's full-resolution JPEG to share. As on iOS the
// cover is a shared picture only: it does not change the project card or the
// exported movie.
//
// iOS reads the frames from the editor's composition with
// AVAssetImageGenerator. Media3 has no composition frame generator, so
// `CoverPickerSource.image` assembles the edited picture itself, layer by
// layer like the export (ExportPlan.videoLayers): the source frame
// (MediaMetadataRetriever, exact time), its grade (`ColorLUT.apply`, the CPU twin
// of the export's shader), its crop or fit and mirror, its image overlays and
// titles, then its placement; a transition window is the rendered transition
// still (TransitionRenderer). Simplified (TODO.md): HDR sources are converted by
// the platform retriever, not tone-mapped like the export.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.salvozappa.youroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Capture the editor's assembled picture once. The grid and JPEG then use
 * the same edit and clock even if the project changes later.
 */
class CoverPickerSource(project: Project, val folder: File, selection: TimelineSelection, canvas: CGSize) {
    val title: String = project.title
    val scopeLabel: String
    val rangeLabel: String
    val aspect: Double = if (canvas.width > 0 && canvas.height > 0) canvas.width / canvas.height else 16.0 / 9.0
    val pool: CoverFramePool
    private val frames = EditFrames(project.edit, project.sources, folder, canvas)

    init {
        val timeline = project.edit.timeline
        val scope: List<EditInterval>
        val range = selection.range
        if (range != null) {
            scope = listOf(range); scopeLabel = "Selected range"
        } else if (!selection.isEmpty) {
            scope = timeline.video.filter { it.id in selection.video }.map { it.interval } + timeline.audio.filter { it.id in selection.audio }.map { it.interval }
            scopeLabel = "Selected clips"
        } else {
            scope = listOf(EditInterval(EditTime.zero, timeline.duration)); scopeLabel = "Whole project"
        }
        rangeLabel = editorTime(scope.minOfOrNull { it.start.seconds } ?: 0.0) + " – " + editorTime(scope.maxOfOrNull { it.end.seconds } ?: 0.0)
        val picture = timeline.video.map { it.interval }.toMutableList()
        // A Still transition can extend the outgoing picture beyond its clip.
        for (join in timeline.transitions) {
            if (join.settings.style != TransitionStyle.still || join.outgoing == null) continue
            val timing = TransitionTiming(join.settings, join.factor, if (join.incoming == null) 1 else 0)
            picture += EditInterval(join.origin, minOf(timeline.duration, join.origin + EditTime.of(timing.videoDuration)))
        }
        pool = CoverFramePool(picture, scope)
    }

    /** Swift `generator(thumbnail:).image(at:)`: the picture at [time], at most 512 px for a thumbnail, else the canvas size. */
    suspend fun image(time: EditTime, thumbnail: Boolean): Bitmap = frames.image(time, if (thumbnail) 512 else null)
}

/**
 * The edited picture at one time, assembled like the export (see the file
 * header). Shared by the cover picker and the Color tool's look thumbnails.
 */
class EditFrames(private val edit: EditState, private val sources: List<SourceMedia>, private val folder: File, private val canvas: CGSize) {
    private val timeline = edit.timeline
    private val lanes by lazy { if (timeline.duration > EditTime.zero) ExportPlan.videoLayers(timeline, timeline.duration) else emptyList() }
    private val ranges by lazy { TitleMotionRanges(edit) }
    private val bitmaps = TitleBitmaps()

    /** The picture at [time], scaled to fit [maximumSize] pixels (null: the canvas size). */
    suspend fun image(time: EditTime, maximumSize: Int?): Bitmap = withContext(Dispatchers.IO) {
        val scale = maximumSize?.let { min(1.0, it / max(canvas.width, canvas.height)) } ?: 1.0
        val width = max(1, Math.round(canvas.width * scale).toInt())
        val height = max(1, Math.round(canvas.height * scale).toInt())
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val target = Canvas(output)
        target.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // Back to front, like `renderTracks`.
        for (lane in lanes.indices.reversed()) {
            val piece = lanes[lane].lastOrNull { it.start <= time && time < it.end } ?: lanes[lane].lastOrNull()?.takeIf { time >= it.end && it.end == timeline.duration }
            val clip = piece as? ExportPlan.Piece.Clip ?: continue
            val layer = layerImage(clip, time, scale) ?: continue
            val placed = clip.placement?.let { TrackRenderer.placed(it, canvas) }
            if (placed != null && !placed.isVisible) { layer.recycle(); continue }
            target.save()
            target.scale(scale.toFloat(), scale.toFloat())
            if (placed != null) {
                target.clipRect(placed.clip.minX.toFloat(), placed.clip.minY.toFloat(), placed.clip.maxX.toFloat(), placed.clip.maxY.toFloat())
                target.concat(matrix(placed.transform))
            }
            target.drawBitmap(layer, Matrix().apply { setScale((1 / scale).toFloat(), (1 / scale).toFloat()) }, paint)
            target.restore()
            layer.recycle()
        }
        output
    }

    /** One layer: the framed, graded picture with its overlays, at the output scale (canvas × [scale]). */
    private suspend fun layerImage(piece: ExportPlan.Piece.Clip, time: EditTime, scale: Double): Bitmap? {
        val width = max(1, Math.round(canvas.width * scale).toInt())
        val height = max(1, Math.round(canvas.height * scale).toInt())
        piece.transition?.let { id ->
            val window = timeline.transitionWindows().firstOrNull { it.id == id } ?: return null
            val job = TransitionRenderJob.of(window, sources, folder, canvas.width.toInt(), canvas.height.toInt()) ?: return null
            val still = TransitionRenderer.still(job, time, folder)
            if (scale >= 1.0) return still
            return Bitmap.createScaledBitmap(still, width, height, true).also { if (it !== still) still.recycle() }
        }
        val source = sources.firstOrNull { it.id == piece.sourceID } ?: return null
        val file = File(folder, source.filename)
        val sourceTime = if (piece.isHold) piece.sourceStart else piece.sourceStart + (time - piece.start).seconds * piece.rate
        val frame = retrieve(file, sourceTime, scale) ?: return null
        // Swift `preparedImage`: grade the source, then frame it over black.
        piece.color?.let { settings -> ColorGrade.load(settings, folder)?.let { ColorLUT.apply(it.settings, frame, it.cube) } }
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawing = Canvas(layer)
        drawing.drawColor(android.graphics.Color.BLACK)
        drawing.scale(scale.toFloat(), scale.toFloat())
        var framing = PreviewFraming(canvas, CGSize(frame.width.toDouble(), frame.height.toDouble()), piece.crop, mirror = piece.mirror).transform
        // Swift `preparedImage`: the stabilization correction of this source frame, before the framing.
        piece.stabilization?.let { reference -> VideoStabilizer.correction(source, reference, folder) }?.let { correction ->
            // The retrieved picture can be scaled (or square-pixel corrected) from the analysed one: convert both axes.
            val sx = frame.width / correction.orientedSize.width
            val sy = frame.height / correction.orientedSize.height
            framing = CGAffineTransform.scale(1 / sx, 1 / sy).concatenating(correction.upright(sourceTime))
                .concatenating(CGAffineTransform.scale(sx, sy)).concatenating(framing)
        }
        drawing.save()
        drawing.clipRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        drawing.drawBitmap(frame, matrix(framing), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        drawing.restore()
        frame.recycle()
        // Swift `VideoOverlayRenderer.apply`: images, then titles, over the framed picture.
        val overlays = VideoOverlayRenderer.overlays(piece.images, piece.titles, sourceTime, time.seconds, ranges, canvas, HashSet(),
            { ImageBitmaps.image(folder, it.filename) }) { bitmaps.image(it, canvas) }
        if (overlays.isNotEmpty()) VideoOverlayRenderer.draw(drawing, overlays, canvas)
        return layer
    }

    /** The source picture shown at [seconds] (oriented, mutable), at about [scale] of its size for small outputs. */
    private fun retrieve(file: File, seconds: Double, scale: Double): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val us = Math.round(seconds * 1_000_000)
            val frame = if (scale < 1.0) {
                val info = VideoGeometry.read(file)
                val edge = info?.let { max(it.orientedWidth, it.orientedHeight) } ?: 0
                val size = max(64, Math.round(edge * scale * 1.5).toInt())
                retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, size, size)
            } else retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            val picture = frame ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return if (picture.config == Bitmap.Config.ARGB_8888 && picture.isMutable) picture
                else picture.copy(Bitmap.Config.ARGB_8888, true).also { picture.recycle() }
        } catch (_: Exception) {
            return null
        } finally { runCatching { retriever.release() } }
    }

    private fun matrix(t: CGAffineTransform) = Matrix().apply {
        setValues(floatArrayOf(t.a.toFloat(), t.c.toFloat(), t.tx.toFloat(), t.b.toFloat(), t.d.toFloat(), t.ty.toFloat(), 0f, 0f, 1f))
    }
}

/** Swift `CoverJPEG`: the shared file and its suggested name. */
object CoverJPEG {
    /** `<title> - <time>.jpg`, the title without path or control characters, at most 40 characters. */
    fun filename(title: String, time: EditTime): String {
        val name = title.split(Regex("[/:\\\\\\p{Cntrl}]")).filter { it.isNotEmpty() }.joinToString("-").trim()
        val timestamp = editorTime(time.seconds).replace(":", "-")
        return (name.ifEmpty { "YouRoll" }).take(40) + " - " + timestamp + ".jpg"
    }

    /** Encodes [image] at quality 95 into the share cache. */
    suspend fun encode(context: android.content.Context, image: Bitmap, title: String, time: EditTime): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "Covers")
        folder.deleteRecursively(); folder.mkdirs()
        val file = File(folder, filename(title, time))
        file.outputStream().use { if (!image.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw java.io.IOException("The cover could not be saved.") }
        file
    }
}

/** Swift `CoverPicker`: the scope, Update, and the grid of frames. Shown inside the Tools sheet with a back button. */
@Composable
fun CoverPicker(source: CoverPickerSource, back: () -> Unit) {
    var picks by remember { mutableStateOf<List<Int>>(emptyList()) }
    val thumbnails = remember { mutableStateMapOf<Int, Bitmap>() }
    val failures = remember { mutableStateMapOf<Int, Boolean>() }
    var selected by remember { mutableStateOf<Int?>(null) }
    fun update() {
        val next = source.pool.picks(picks)
        thumbnails.keys.retainAll(next.toSet())
        failures.clear()
        picks = next
    }
    LaunchedEffect(Unit) { if (picks.isEmpty()) update() }
    LaunchedEffect(picks) {
        val batch = picks
        for (pick in batch) {
            if (thumbnails.containsKey(pick)) continue
            try {
                val image = source.image(source.pool.time(pick), thumbnail = true)
                if (picks != batch) return@LaunchedEffect
                thumbnails[pick] = image
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (picks != batch) return@LaunchedEffect
                failures[pick] = true
            }
        }
    }
    selected?.let { pick ->
        CoverDetail(source, source.pool.time(pick), thumbnails[pick]) { selected = null }
        return
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        CoverBar("Select cover", back)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source.scopeLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(source.rangeLabel, color = EditorColors.secondary, fontSize = 12.sp, style = tabular)
            }
            val canUpdate = source.pool.count > 9
            Row(Modifier.heightIn(min = 44.dp).alpha(if (canUpdate) 1f else 0.4f).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                .clickable(enabled = canUpdate) { update() }.padding(horizontal = 14.dp)
                .semantics { contentDescription = "Update, show other frames from the same range" },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Update", color = Color.White, fontSize = 15.sp)
            }
        }
        if (source.pool.count == 0) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Movie, null, tint = EditorColors.secondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.size(12.dp))
                Text("No video in this range", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Select a range that contains video.", color = EditorColors.secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }
        val minimum = if (source.aspect < 1) 100.dp else 148.dp
        LazyVerticalGrid(GridCells.Adaptive(minimum), Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            items(picks, key = { it }) { pick ->
                val stamp = editorTime(source.pool.time(pick).seconds)
                Column(Modifier.clickable { selected = pick }.semantics { contentDescription = "Frame at $stamp" },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(source.aspect.toFloat()).clip(RoundedCornerShape(6.dp)).background(Color(0.09f, 0.09f, 0.09f)),
                        contentAlignment = Alignment.Center) {
                        val image = thumbnails[pick]
                        when {
                            image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            failures[pick] == true -> Icon(Icons.Outlined.BrokenImage, null, tint = EditorColors.secondary)
                            else -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        }
                    }
                    Text(stamp, color = EditorColors.secondary, fontSize = 12.sp, style = tabular)
                }
            }
        }
    }
}

/** Swift `CoverDetail`: the full-resolution frame and its Share JPEG button. */
@Composable
private fun CoverDetail(source: CoverPickerSource, time: EditTime, thumbnail: Bitmap?, back: () -> Unit) {
    val context = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var jpeg by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        if (jpeg != null) return@LaunchedEffect
        try {
            val image = source.image(time, thumbnail = false)
            frame = image
            jpeg = CoverJPEG.encode(context, image, source.title, time)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("YouRoll", "Cover frame failed", failure)
            failed = true
        }
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        CoverBar(editorTime(time.seconds), back)
        Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(source.aspect.toFloat()).background(Color(0.07f, 0.07f, 0.07f))) {
                (frame ?: thumbnail)?.let { picture ->
                    Image(picture.asImageBitmap(), "Cover image", Modifier.fillMaxSize()
                        .semantics { contentDescription = "Cover image, ${picture.width} × ${picture.height} pixels" }, contentScale = ContentScale.Fit)
                }
                val file = jpeg
                if (file != null && frame != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(46.dp).background(Color.Black.copy(alpha = 0.78f), CircleShape)
                        .clickable { MediaSharing.share(context, file, "image/jpeg", file.name) }.semantics { contentDescription = "Share JPEG" },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.IosShare, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                } else if (!failed) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(46.dp).background(Color.Black.copy(alpha = 0.78f), CircleShape)
                        .semantics { contentDescription = "Preparing full-resolution JPEG" }, contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (failed) {
                Column(Modifier.background(Color(0.2f, 0.2f, 0.22f), RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn’t load this frame", color = Color.White, fontSize = 17.sp)
                    Box(Modifier.heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .clickable { failed = false; attempt += 1 }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("Try again", color = EditorColors.accent, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

/** An inline navigation bar: back chevron and a centred title (SwiftUI `.navigationTitle`, `.inline`). */
@Composable
private fun CoverBar(title: String, back: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
        Row(Modifier.align(Alignment.CenterStart).heightIn(min = 44.dp).clickable(onClick = back).padding(horizontal = 6.dp)
            .semantics { contentDescription = "Back" }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChevronLeft, null, tint = EditorColors.accent, modifier = Modifier.size(28.dp))
            Text("Back", color = EditorColors.accent, fontSize = 17.sp)
        }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
    }
}
