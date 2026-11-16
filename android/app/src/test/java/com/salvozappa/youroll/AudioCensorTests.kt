// YouRoll JVM tests for Add audio and Censor audio. The model checks port the
// assertions of `audioImportCensorTests` in ../Tests/TrackMediaTests.swift
// (import, overlay, undo; censor persistence, split/move/copy, overlap
// ownership, shared badges, linked speed, joining). The PCM checks run the
// preview mixer and the export's AudioEnvelopeProcessor on the same timeline
// (iOS renders the composition and the encoded export instead).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AudioCensorTests {
    private val voice = SourceMedia(filename = "voice.wav", duration = 4.0, audioOnly = true, originalName = "Original voice.wav")
    private val rate = 48_000

    /** The Swift fixture: 4 s of a 200 Hz sine at 0.25, stereo. */
    private val tone: PcmSamples = run {
        val data = ShortArray(rate * 4 * 2) { index -> (0.25 * sin((index / 2) * 2 * PI * 200 / rate) * 32768).toInt().toShort() }
        MemoryPcm(rate, 2, data)
    }

    private fun baseline(): Pair<EditState, UUID> {
        val edit = EditState()
        val id = edit.timeline.insertAudio(voice, EditTime.zero)
        return edit to id
    }

    @Test fun importedAudioIsAnIndependentLayerWithUndo() {
        val project = Project("Audio censor QA")
        project.sources = listOf(voice)
        val (baseline, _) = baseline()
        project.commit(baseline)
        project.undo(); require(project.edit.timeline.audio.isEmpty(), "Undo audio import removes the item")
        project.redo(); require(project.edit.timeline.audio.size == 1, "Redo audio import restores the item")
        val overlapping = baseline.timeline.copy()
        overlapping.insertAudio(voice, et(1))
        require(overlapping.audio[0] == baseline.timeline.audio[0] && overlapping.duration == et(5), "Audio imports overlay without shifting existing items")
        // An added layer under existing video leaves the picture in place.
        val clip = SourceMedia(filename = "clip.mp4", duration = 6.0)
        val video = EditState(listOf(TimelineClip(sourceID = clip.id, start = 0.0, end = 6.0)))
        val before = video.timeline.video
        val music = video.timeline.insertAudio(voice, et(2))
        require(video.timeline.video == before && video.timeline.audio.first { it.id == music }.start == et(2) &&
            video.timeline.links.none { music in it.members }, "Added audio is its own unlinked item at the playhead")
        video.timeline.validate(listOf(clip, voice))
    }

    @Test fun censorModelFollowsEditsAndPersists() {
        val project = Project("Audio censor QA")
        project.sources = listOf(voice)
        val (baseline, id) = baseline()
        project.commit(baseline)
        val scope = TimelineSelection(audio = setOf(id), range = iv(1, 2))
        val settings = AudioCensorSettings()
        val edit = baseline.copy()
        val effectID = newUUID()
        edit.timeline.censor(settings, scope, effectID)
        project.commit(edit); project.validate()
        assertEquals("Older builds must reject rather than silently drop censor edits", 9, project.schemaVersion)
        val encoded = Project.encode(project).toString(Charsets.UTF_8)
        require("\"censorRegions\"" in encoded && "\"levelDB\":-18" in encoded && "\"waveform\":\"sine\"" in encoded, "Swift field names and raw values")
        val decoded = Project.decode(encoded.toByteArray(Charsets.UTF_8))
        require(decoded.edit.timeline.audio[0].censors == edit.timeline.audio[0].censors, "Censor survives project persistence")
        val split = edit.timeline.copy()
        split.split(et(1.5), TimelineSelection(audio = setOf(id)))
        require(split.audio.size == 2 && split.audio.all { it.censors.size == 1 }, "Splitting preserves censor on both halves")
        val right = split.audio[1].id
        split.censor(null, TimelineSelection(audio = setOf(right)), removingOnly = effectID)
        require(split.audio[0].censors.size == 1 && split.audio[1].censors.isEmpty(), "Removing a split censor affects only its own audio section")
        split.move(TimelineSelection(audio = setOf(id)), et(2))
        require(split.censorSpans(split.audio[0])[0].start == 3.0, "Censor follows moved audio")
        split.validate(project.sources)
        val clipboard = edit.timeline.copied(scope)
        val pasted = baseline.timeline.copy(); pasted.paste(clipboard, et(2))
        pasted.validate(project.sources)
        require(pasted.audio.last().censors.firstOrNull()?.id != effectID, "Pasted censor has independent effect identity")
        val invalid = edit.timeline.copy()
        invalid.audio = invalid.audio.map { it.withCensors(it.censors.map { region -> region.copy(settings = region.settings.copy(frequency = 50.0)) }) }
        assertThrowing<Throwable>("Out-of-range censor settings are rejected") { invalid.validate(project.sources) }
    }

    @Test fun overlappingTracksShareOneBeepAndOneBadge() {
        val (baseline, _) = baseline()
        val settings = AudioCensorSettings()
        val effectID = newUUID()
        val layered = baseline.timeline.copy()
        layered.insertAudio(voice, EditTime.zero)
        layered.censor(settings, TimelineSelection(audio = layered.audio.map { it.id }.toSet(), range = iv(1, 2)), effectID)
        require(layered.audio.flatMap { layered.censorSpans(it) }.count { it.emitsTone } == 1, "One censor across overlapping selected tracks emits one beep")
        layered.audio = layered.audio.mapIndexed { index, item -> if (index == 0) item.copy(volumeDB = -18.0) else item }
        require(!layered.censorSpans(layered.audio[0])[0].emitsTone && layered.censorSpans(layered.audio[1])[0].emitsTone,
            "The loudest selected track carries a shared beep, independent of UUID ordering")
        val layeredEdit = EditState(); layeredEdit.timeline = layered
        val event = layeredEdit.request(layeredEdit.toolRegions.first { it.tool == VideoTool.censorAudio })
        require(event.targets?.audio?.size == 2, "One shared censor badge targets the entire overlapping event")
        val retuned = settings.copy(frequency = 440.0)
        val retunedEdit = layeredEdit.removingTool(event)
        retunedEdit.timeline.censor(retuned, event.targets!!, effectID)
        require(retunedEdit.timeline.audio.flatMap { retunedEdit.timeline.censorSpans(it) }.count { it.emitsTone } == 1, "Editing a shared badge never leaves two different beeps behind")
        require(retunedEdit.removingTool(event).timeline.audio.all { it.censors.isEmpty() }, "Removing a shared badge restores every affected track")
        val bridged = layeredEdit.copy()
        bridged.timeline.split(et(1.5), TimelineSelection(audio = setOf(bridged.timeline.audio[0].id)))
        for (region in bridged.toolRegions.filter { it.tool == VideoTool.censorAudio }) {
            val bridgedRequest = bridged.request(region)
            require(bridgedRequest.targets?.audio?.size == 3, "An uncut music bed joins both split voice halves into the same censor event")
            val changed = bridged.removingTool(bridgedRequest)
            changed.timeline.censor(retuned, bridgedRequest.targets!!, effectID)
            val spans = changed.timeline.audio.flatMap { changed.timeline.censorSpans(it) }
            for (t in listOf(1.25, 1.75)) require(spans.count { it.emitsTone && it.start <= t && t < it.end } == 1, "Retuning either split half never stacks old and new tones")
            require(changed.removingTool(bridgedRequest).timeline.audio.all { it.censors.isEmpty() }, "Removing the bridged event leaves no hidden old region")
        }
        val staggered = baseline.timeline.copy()
        staggered.insertAudio(voice, et(1))
        staggered.censor(settings, TimelineSelection(audio = staggered.audio.map { it.id }.toSet(), range = iv(0.5, 3.5)))
        for (span in staggered.audio.flatMap { staggered.censorSpans(it) }) {
            require(abs(span.phaseOrigin - 0.5) < 0.00001 && abs(span.envelopeStart - 0.5) < 0.00001 && abs(span.envelopeEnd - 3.5) < 0.00001,
                "Clips beginning inside one censor share its phase and envelope clock")
        }
        val changedTone = settings.copy(frequency = 2000.0)
        layered.censor(changedTone, TimelineSelection(audio = setOf(layered.audio[1].id), range = iv(1, 2)), effectID)
        require(layered.audio.flatMap { layered.censorSpans(it) }.count { it.emitsTone } == 2, "Editing one overlapping track's censor must remain audible independently")
    }

    @Test fun censorFollowsLinkedSpeedAndJoining() {
        val settings = AudioCensorSettings()
        val linked = TrackTimeline(listOf(TimelineClip(sourceID = voice.id, start = 0.0, end = 4.0)))
        linked.censor(settings, TimelineSelection(audio = setOf(linked.audio[0].id), range = iv(1, 2)))
        val faster = linked.projectedClips.map { it.copy(speed = SpeedSettings(rate = 2.0)) }
        linked.replaceVideoContents(faster)
        assertEquals("Censor follows speech through a linked speed change", iv(0.5, 1.0), linked.audio[0].censors[0].interval)
        val (baseline, id) = baseline()
        val edit = baseline.copy()
        edit.timeline.censor(settings, TimelineSelection(audio = setOf(id), range = iv(1, 2)))
        val reunited = edit.timeline.copy()
        reunited.split(et(1.5), reunited.entireSelection())
        val beforeJoin = reunited.audio
        require(reunited.joining(reunited.entireSelection()).timeline.audio == beforeJoin, "Horizontal linking keeps both split censor regions intact")
    }

    @Test fun waveformsAreBoundedAndEnvelopesShapeTheTone() {
        val settings = AudioCensorSettings()
        for (waveform in CensorWaveform.entries) {
            val value = settings.copy(waveform = waveform)
            val peak = (0 until 4800).maxOf { abs(value.oscillator(it / 48000.0, 48000.0)) }
            require(peak <= 1.000001 && peak > 0.6, "All waveforms have bounded useful peak amplitude ($waveform: $peak)")
        }
        close(settings.amplitude, 0.12589, "−18 dBFS", 1e-5)
        require(settings.envelopeValue(-0.001, 1.0) == 0.0 && settings.envelopeValue(1.0, 1.0) == 0.0, "Silent outside the range")
        close(settings.envelopeValue(0.5, 1.0), 1.0, "Full level inside a short fade", 1e-9)
        require(settings.copy(envelope = CensorEnvelope.soft).envelopeValue(0.03, 1.0) < 0.6, "Soft fades take 60 ms")
        val pulse = settings.copy(envelope = CensorEnvelope.pulse)
        require(pulse.envelopeValue(0.21, 1.0) == 0.0 && pulse.envelopeValue(0.1, 1.0) == 1.0, "Pulses gap from 0.18 to 0.25 s")
    }

    /** The mixed left channel of [edit] over the first [seconds], as the editor preview plays it. */
    private fun previewSamples(edit: EditState, seconds: Double = 4.0): FloatArray {
        val mix = PreviewAudioMix(ExportPlan.previewAudio(edit.timeline), mapOf(voice.id to tone))
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * 2)
        var first = 0
        while (first < frames) { val count = minOf(4096, frames - first); mix.render(first.toLong(), count, out, first * 2); first += count }
        return FloatArray(frames) { out[it * 2] }
    }

    /** The left channel the export's audio lanes produce: each piece's source span through AudioEnvelopeProcessor, summed. */
    private fun exportSamples(edit: EditState, encoding: Int = C.ENCODING_PCM_FLOAT, sources: Map<UUID, PcmSamples> = mapOf(voice.id to tone)): FloatArray {
        val plan = ExportPlan.make(edit)
        val frames = (plan.duration.seconds * rate).toInt()
        val out = FloatArray(frames)
        for (piece in plan.audio.flatten().filterIsInstance<ExportPlan.Piece.Clip>()) {
            val processor = AudioEnvelopeProcessor(piece.envelope!!)
            processor.configure(AudioProcessor.AudioFormat(rate, 2, encoding))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val first = Math.round(piece.start.seconds * rate).toInt()
            val count = Math.round(piece.duration.seconds * rate).toInt()
            val source = Math.round(piece.sourceStart * rate)
            val width = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            var done = 0
            while (done < count) {
                val chunk = minOf(1024, count - done)
                val input = ByteBuffer.allocateDirect(chunk * 2 * width).order(ByteOrder.nativeOrder())
                for (i in 0 until chunk) for (channel in 0 until 2) {
                    val value = sources.getValue(piece.sourceID).sample(source + done + i, channel)
                    if (width == 4) input.putFloat(value) else input.putShort((value * 32768).toInt().coerceIn(-32768, 32767).toShort())
                }
                input.flip()
                processor.queueInput(input)
                val output = processor.output.order(ByteOrder.nativeOrder())
                for (i in 0 until chunk) {
                    val left = if (width == 4) output.getFloat() else output.getShort() / 32768f
                    if (width == 4) output.getFloat() else output.getShort()
                    if (first + done + i < frames) out[first + done + i] += left
                }
                done += chunk
            }
        }
        return out
    }

    private fun component(values: FloatArray, hz: Double): Double {
        val range = 57600 until 86400
        var real = 0.0; var imaginary = 0.0
        for (i in range) { val phase = i * 2 * PI * hz / 48000; real += values[i] * cos(phase); imaginary += values[i] * sin(phase) }
        return 2 * hypot(real, imaginary) / range.count()
    }

    @Test fun censorPcmIsTheSameInPreviewAndExport() {
        val (baseline, id) = baseline()
        val settings = AudioCensorSettings()
        val scope = TimelineSelection(audio = setOf(id), range = iv(1, 2))
        val edit = baseline.copy(); edit.timeline.censor(settings, scope)
        val clean = previewSamples(baseline)
        val censored = previewSamples(edit)
        val again = previewSamples(edit)
        require(censored.contentEquals(again), "Censor PCM is independent of buffer order")
        for (frame in 4800 until 43200) require(abs(clean[frame] - censored[frame]) < 0.00001, "Audio outside censor remains unchanged at $frame")
        for (frame in 57600 until 86400) {
            val expected = settings.amplitude * sin((frame - 48000) * 2 * PI * 1000 / 48000)
            require(abs(censored[frame] - expected) < 0.00002, "Censor replaces source with exact 1 kHz -18 dBFS waveform at $frame")
        }
        val splitEdit = edit.copy()
        splitEdit.timeline.split(et(1.5), TimelineSelection(audio = setOf(id)))
        val splitSound = previewSamples(splitEdit)
        require(censored.indices.all { abs(censored[it] - splitSound[it]) < 0.00002 }, "A pure split inside a censor does not restart its oscillator or envelope")
        val halfRequest = splitEdit.request(splitEdit.toolRegions.first { it.tool == VideoTool.censorAudio })
        require(halfRequest.targets?.audio?.size == 1, "Censor badge grouping does not reach across adjacent split sections")
        val pulsed = baseline.copy(); pulsed.timeline.censor(settings.copy(envelope = CensorEnvelope.pulse), scope)
        val pulseSamples = previewSamples(pulsed)
        require((58320 until 58800).all { abs(pulseSamples[it]) < 0.00001 }, "Pulse gaps remain silent, never reveal original words")
        // preview == export: the export's per-piece processor produces the preview's samples.
        for (encoding in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)) {
            val exported = exportSamples(edit, encoding)
            val tolerance = if (encoding == C.ENCODING_PCM_FLOAT) 0.00002 else 0.0002
            require(exported.indices.all { abs(exported[it] - censored[it]) < tolerance }, "Export matches the preview sample by sample ($encoding)")
            require(abs(component(exported, 1000.0) - settings.amplitude) < 0.005, "Export retains censor frequency and level")
            require(component(exported, 200.0) < 0.0002, "Export does not retain censored source tone")
        }
    }

    @Test fun censorAndAddedAudioThroughPauseCuts() {
        // Censor linked speech, then cut a pause before it: the censor follows the speech (rebased, as in Swift
        // `replaceVideoContents`); an added music item is independent of the cut. Preview and export agree.
        val clip = SourceMedia(filename = "talk.mp4", duration = 4.0)
        val edit = EditState(listOf(TimelineClip(sourceID = clip.id, start = 0.0, end = 4.0)))
        val speech = edit.timeline.audio.single().id
        edit.timeline.censor(AudioCensorSettings(), TimelineSelection(audio = setOf(speech), range = iv(1, 2)))
        edit.updateClip(0) { it.copy(pauses = PauseSettings(cuts = listOf(SourceInterval(0.25, 0.75)))) }
        close(edit.duration, 3.5, "The pause cut shortens the clip", 1e-9)
        val region = edit.timeline.audio.single { it.id == speech }.censors.single()
        assertEquals("The censor follows the speech through the cut", iv(0.5, 1.5), region.interval)
        val music = edit.timeline.insertAudio(voice, et(0.1))
        edit.timeline.validate(listOf(clip, voice))
        require(edit.timeline.audio.first { it.id == music }.start == et(0.1), "Added audio keeps its place over the cut clip")
        val sources = mapOf(clip.id to tone, voice.id to tone)
        val mix = PreviewAudioMix(ExportPlan.previewAudio(edit.timeline), sources)
        val frames = (3.5 * rate).toInt()
        val out = FloatArray(frames * 2)
        mix.render(0, frames, out)
        val preview = FloatArray(frames) { out[it * 2] }
        val exported = exportSamples(edit, sources = sources)
        require(preview.indices.all { abs(exported[it] - preview[it]) < 0.00002 }, "Export matches the preview sample by sample through the pause cut")
        // Inside the censor only the music (200 Hz) and the beep remain: the speech is replaced exactly once.
        val settings = AudioCensorSettings()
        for (frame in (0.7 * rate).toInt() until (1.3 * rate).toInt()) {
            val time = frame / rate.toDouble()
            val expected = settings.amplitude * sin((time - 0.5) * 2 * PI * 1000) + 0.25 * sin((frame - rate / 10) * 2 * PI * 200 / rate)
            require(abs(preview[frame] - expected) < 0.0002, "Beep plus music at $time")
        }
    }

    @Test fun volumeAndFadesScaleTheTone() {
        val (baseline, id) = baseline()
        val edit = baseline.copy()
        edit.timeline.audio = edit.timeline.audio.map { it.copy(volumeDB = -6.0) }
        edit.timeline.censor(AudioCensorSettings(), TimelineSelection(audio = setOf(id), range = iv(1, 2)))
        val quiet = previewSamples(edit)
        close(component(quiet, 1000.0), AudioCensorSettings().amplitude * Math.pow(10.0, -6.0 / 20), "Track Volume applies to the beep", 0.002)
        val muted = edit.copy(); muted.timeline.audio = muted.timeline.audio.map { it.copy(muted = true) }
        require(ExportPlan.previewAudio(muted.timeline).flatten().none { it is ExportPlan.Piece.Clip }, "A muted track is not heard at all")
    }

    @Test fun stagingAudioCopiesValidatesAndCleansUp() = runBlocking {
        val root = Files.createTempDirectory("audio-import").toFile()
        try {
            val original = File(root, "Original voice.wav").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
            val bytes = original.readBytes()
            val work = File(root, "Processing")
            class Probe(val audio: Boolean, val decodes: Boolean = true) : MediaProbe {
                override suspend fun probe(file: File) = MediaProbe.Info(4.0, hasVideo = false, hasAudio = audio)
                override suspend fun decodesAudio(file: File) = decodes
            }
            val imported = ImportedAudioFile.stage(original.name, work, Probe(true), open = { original.inputStream() })
            require(imported.source.audioOnly == true && imported.source.originalName == "Original voice.wav", "Import records audio type and original name")
            require(abs(imported.source.duration - 4) < 0.0001 && imported.source.sha256 != null, "Import validates duration and fingerprints original bytes")
            require(imported.file.readBytes().contentEquals(bytes) && imported.file.extension == "wav", "Audio import does not transcode or change the original")
            require(original.readBytes().contentEquals(bytes), "Audio import leaves the original untouched")
            imported.file.delete()
            for (probe in listOf(Probe(false), Probe(true, decodes = false))) {
                assertThrowing<AudioImportError>("A file without decodable audio is refused") {
                    runBlocking { ImportedAudioFile.stage("broken.wav", work, probe, open = { original.inputStream() }) }
                }
            }
            require(work.listFiles().orEmpty().isEmpty(), "Failed import cleans only its staging file")
            val odd = ImportedAudioFile.stage("voice.what?ever", work, Probe(true), open = { original.inputStream() })
            require(odd.file.extension == "audio", "An unusual extension becomes .audio")
        } finally { root.deleteRecursively() }
    }

    @Test fun storeInsertsAudioAtThePlayheadInOneSavedEdit() = runBlocking {
        val root = Files.createTempDirectory("audio-insert").toFile()
        try {
            val probe = object : MediaProbe {
                override suspend fun probe(file: File) = MediaProbe.Info(if (file.extension == "mp4") 6.0 else 4.0, hasVideo = file.extension == "mp4", hasAudio = true)
            }
            val store = ProjectStore(File(root, "Projects"), probe)
            val project = store.create()!!
            val clip = SourceMedia(filename = "clip.mp4", duration = 6.0)
            File(store.folder(project), clip.filename).writeBytes(ByteArray(16))
            require(store.update(project.id) { it.append(clip) }, "Video added")
            val original = File(root, "song.m4a").apply { writeBytes(ByteArray(2048) { 7 }) }
            require(store.insertAudio("song.m4a", project.id, 2.5) { original.inputStream() }, "Audio inserted: ${store.error.value}")
            val saved = store.projects.value.first { it.id == project.id }
            val source = saved.sources.single { it.audioOnly == true }
            val item = saved.edit.timeline.audio.single { it.sourceID == source.id }
            require(item.start == et(2.5) && item.mapping.duration == et(4), "Placed at the playhead with the whole file")
            require(File(store.folder(saved), source.filename).exists() && File(store.folder(saved), "Processing").listFiles().orEmpty().isEmpty(),
                "The copy joins the project folder; nothing is left in Processing")
            require(saved.canUndo && !store.importing.value, "One undoable edit")
            val reopened = ProjectStore(File(root, "Projects"), probe).projects.value.first { it.id == project.id }
            require(reopened.sources.any { it.id == source.id }, "The import is saved")
        } finally { root.deleteRecursively() }
    }

    private fun close(actual: Double, expected: Double, message: String, tolerance: Double) =
        require(abs(actual - expected) <= tolerance, "$message: $actual vs $expected")
}
