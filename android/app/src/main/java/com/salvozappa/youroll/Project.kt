// Port of YouRec/Project.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// Value semantics: EditState and Project are mutable classes standing in for
// Swift structs. Use `copy()` wherever Swift would copy by assignment.
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

object ProjectCacheLocation {
    // Older builds used both "Cache" and "cache". A sandbox on a
    // case-insensitive filesystem may reject creating the alternate spelling
    // even though stat() finds it. Keep the exact spelling already on disk.
    // On a case-sensitive device with both legacy folders, preserve each one.
    fun root(folder: File, preferred: String = "Cache"): File {
        val entries = folder.listFiles()?.toList() ?: emptyList()
        val matches = entries.filter { it.name.equals("Cache", ignoreCase = true) && it.isDirectory }
        return (matches.firstOrNull { it.name == preferred } ?: matches.firstOrNull() ?: File(folder, "Cache")).absoluteFile.normalize()
    }
}

@Serializable
data class CaptureEvent(
    val id: UUID = newUUID(),
    val time: Double,
    val kind: String,
    val detail: String,
) {
    companion object {
        fun recovered(events: List<CaptureEvent>, duration: Double): List<CaptureEvent> =
            events.filter { it.time.isFinite() && it.time >= 0 && it.time < duration }
    }
}

@Serializable
data class SourceMedia(
    val id: UUID = newUUID(),
    val filename: String,
    val duration: Double,
    val recordedAt: SwiftDate = SwiftDate.now(),
    val events: List<CaptureEvent>? = null,
    val sha256: String? = null,
    @Serializable(with = SupportedVideoEffects::class)
    val videoEffects: List<VideoEffectSettings>? = null,
    val audioOnly: Boolean? = null,
    val isStillPhoto: Boolean? = null,
    val originalName: String? = null,
)

/**
 * Every tool payload is typed: Stabilization (`StabilizationSettings`, StabilizationPath.kt),
 * Color (`ColorSettings`, ColorGrading.kt),
 * Crop (`CropSettings`, CropEditor.kt), Speed, Mirror and Pauses (`SpeedSettings`, `MirrorSettings`, `PauseSettings`, EditorTools.kt),
 * Image (`ImageOverlay`, ImageToolEditor.kt), Text (`TextOverlay`, TitleDesign.kt), transitions (`ClipTransition`, Transitions.kt)
 * and video effects (`VideoEffectSettings`, VideoEffectModel.kt) are ported.
 */
@Serializable
data class TimelineClip(
    val id: UUID = newUUID(),
    val sourceID: UUID,
    val start: Double,
    val end: Double,
    // Optional so existing projects/history decode with stabilization off.
    val stabilization: StabilizationSettings? = null,
    val speed: SpeedSettings? = null,
    val color: ColorSettings? = null,
    val texts: List<TextOverlay>? = null,
    val pauses: PauseSettings? = null,
    val crop: CropSettings? = null,
    val mirror: MirrorSettings? = null,
    val images: List<ImageOverlay>? = null,
    val openingTransition: ClipTransition? = null,
    val transition: ClipTransition? = null,
    @Serializable(with = SupportedVideoEffects::class)
    val videoEffects: List<VideoEffectSettings>? = null,
    val frozenFrame: FrozenFrameSegment? = null,
) {
    val duration: Double get() = frozenFrame?.duration ?: effectPlaybackSpans.fold(0.0) { total, span -> total + span.duration }
}

/** Swift enum with an `unavailable(String)` case, encoded as its raw string. */
@Serializable(with = EffectKindSerializer::class)
data class EffectKind(val rawValue: String) {
    val isSupported: Boolean get() = this in allCases
    val isNeural: Boolean get() = this == voiceIsolation || this == studioVoice || this == studioRestoration
    val id: String get() = rawValue
    val title: String
        get() = when (this) {
            voiceIsolation -> "Noise reduction"
            studioVoice -> "Speech detail restoration"
            studioRestoration -> "Studio restoration"
            highPass -> "Low-cut filter"
            equalizer -> "Voice EQ"
            gain -> "Output gain"
            else -> "Unavailable filter"
        }

    companion object {
        val voiceIsolation = EffectKind("voiceIsolation")
        val studioVoice = EffectKind("studioVoice")
        val studioRestoration = EffectKind("studioRestoration")
        val highPass = EffectKind("highPass")
        val equalizer = EffectKind("equalizer")
        val gain = EffectKind("gain")
        val allCases = listOf(voiceIsolation, studioVoice, studioRestoration, highPass, equalizer, gain)
        fun unavailable(name: String) = EffectKind(name)
    }
}

object EffectKindSerializer : KSerializer<EffectKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EffectKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: EffectKind) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder) = EffectKind(decoder.decodeString())
}

@Serializable
data class AudioModel(val id: String, val weightsSHA256: String, val runtime: String) {
    companion object {
        val rnnoise = AudioModel("rnnoise-v0.1-cdf196b1",
            "f0cdb52b30501aab489f90fedbc7a023c719d91b337db2da7f26fc3036556b95", "yourec-rnnoise-c-v1")
        val lava = AudioModel("lavasr-v2-33ac0408",
            "1ef7557e72c9b2ee78ccb35cf3ab3400c0c73be1838ab16ff7dde52934f4d2cf", "yourec-coreml-fp32-lava-dsp-v1")
        val voiceFixer = AudioModel("voicefixer-aae2253c",
            "67971970d18c06c1220dc63df11aa0186f787dc961c829dcd1624dad3d612360", "yourec-voicefixer-coreml-44100-context256-overlap-v2")
        val deepFilter = AudioModel("deepfilternet3-dfc12319",
            "1114ff972e25679e7f9707da85eee62659e1bdf50199035ecacba12e4ec0425d", "yourec-dfn3-coreml-cpu-v1-dsp3dd66dea-milc2141d48")
    }
}

// Optional model metadata keeps pre-AI project documents readable. An enabled
// unknown model is an explicit render error; it is never silently substituted.
// (Settings only: the audio processing chain itself is not ported, TODO.md.)
@Serializable
data class AudioEffect(
    val id: UUID = newUUID(),
    val kind: EffectKind,
    val enabled: Boolean = true,
    val amount: Double = 0.0,
    val frequency: Double = 1800.0,
    val implementationVersion: Int = 1,
    val model: AudioModel? = null,
) {
    val supported: Boolean
        get() {
            if (!(kind.isSupported && implementationVersion == 1)) return false
            if (kind == EffectKind.voiceIsolation) return amount == 0.0 || model == AudioModel.rnnoise || model == AudioModel.deepFilter
            if (kind == EffectKind.studioVoice) return amount == 0.0 || model == AudioModel.lava
            if (kind == EffectKind.studioRestoration) return amount == 0.0 || model == AudioModel.voiceFixer
            return true
        }

    companion object {
        fun make(kind: EffectKind) = AudioEffect(
            kind = kind,
            amount = if (kind == EffectKind.studioVoice) 0.35 else if (kind.isNeural) 1.0 else 0.0,
            frequency = if (kind == EffectKind.highPass) 80.0 else 1800.0,
            model = when (kind) {
                EffectKind.voiceIsolation -> AudioModel.rnnoise
                EffectKind.studioVoice -> AudioModel.lava
                EffectKind.studioRestoration -> AudioModel.voiceFixer
                else -> null
            },
        )
    }
}

@Serializable(with = EditStateSerializer::class)
class EditState(
    timeline: TrackTimeline,
    // Only Crop sets a fixed output size. In automatic mode an edit may keep
    // an earlier take as its reference even after a different clip moves ahead of it.
    var outputSize: CGSize? = null,
) {
    /** Assigning a timeline stores a copy, like assigning a Swift struct. Mutate it in place through this property. */
    var timeline: TrackTimeline = timeline.copy()
        set(value) { field = value.copy() }

    var canvasReference: UUID? = null
        internal set

    internal val firstVideoSourceID: UUID? get() = timeline.video.minByOrNull { it.start }?.content?.sourceID
    val canvasSourceID: UUID?
        get() {
            val reference = canvasReference
            if (reference != null && timeline.video.any { it.content.sourceID == reference }) return reference
            return firstVideoSourceID
        }

    constructor(clips: List<TimelineClip> = emptyList(), effects: List<AudioEffect> = emptyList(), outputSize: CGSize? = null) :
        this(TrackTimeline(clips, effects), outputSize)

    fun copy(): EditState = EditState(timeline.copy(), outputSize).also { it.canvasReference = canvasReference }

    fun retainCanvasReference(previous: EditState) {
        canvasReference = null
        val reference = previous.canvasSourceID ?: return
        if (!(outputSize == null && reference != firstVideoSourceID && timeline.video.any { it.content.sourceID == reference })) return
        canvasReference = reference
    }

    var clips: List<TimelineClip>
        get() = timeline.projectedClips
        set(value) { timeline.replaceVideoContents(value) }

    // Kept as an inspector convenience while the audio UI is moved into Tools.
    // The persisted data is always per audio item/interval.
    var effects: List<AudioEffect>
        get() = timeline.audio.firstOrNull()?.enhancements?.firstOrNull()?.effects ?: emptyList()
        set(value) { timeline.enhance(value, timeline.entireSelection()) }

    val duration: Double get() = timeline.duration.seconds

    override fun equals(other: Any?): Boolean =
        other is EditState && timeline == other.timeline && outputSize == other.outputSize && canvasReference == other.canvasReference
    override fun hashCode(): Int = listOf(timeline, outputSize, canvasReference).hashCode()
    override fun toString(): String = "EditState(timeline=$timeline, outputSize=$outputSize, canvasReference=$canvasReference)"
}

object EditStateSerializer : KSerializer<EditState> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: EditState) {
        val json = encoder as JsonEncoder
        val fields = LinkedHashMap<String, JsonElement>()
        fields["timeline"] = json.json.encodeToJsonElement(TrackTimeline.serializer(), value.timeline)
        value.outputSize?.let { fields["outputSize"] = json.json.encodeToJsonElement(CGSize.serializer(), it) }
        value.canvasReference?.let { fields["canvasReference"] = JsonPrimitive(it.uuidString) }
        json.encodeJsonElement(JsonObject(fields))
    }
    override fun deserialize(decoder: Decoder): EditState {
        val json = decoder as JsonDecoder
        val values = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("EditState")
        val outputSize = values.field("outputSize")?.let { json.json.decodeFromJsonElement(CGSize.serializer(), it) }
        val canvasReference = values.field("canvasReference")?.let { json.json.decodeFromJsonElement(UUIDSerializer, it) }
        val saved = values.field("timeline")
        val timeline = if (saved != null) {
            json.json.decodeFromJsonElement(TrackTimeline.serializer(), saved).also { it.restoreSourceMute(); it.retainUniqueTransitionSlots() }
        } else {
            // Explicit conversion of existing development projects. There is no
            // alternate legacy playback path or modification of original media.
            val clips = json.json.decodeFromJsonElement(ListSerializer(TimelineClip.serializer()),
                values.field("clips") ?: throw SerializationException("EditState needs timeline or clips"))
            val effects = values.field("effects")?.let { json.json.decodeFromJsonElement(ListSerializer(AudioEffect.serializer()), it) } ?: emptyList()
            TrackTimeline(clips, effects)
        }
        return EditState(timeline, outputSize).also { it.canvasReference = canvasReference }
    }
}

/// Immutable edit payload. Capture is journaled independently, so a new take
/// never rewrites a historical snapshot or becomes an accidental Undo step.
class EditSnapshot(state: EditState, val captureCursor: Int) {
    private val stored: EditState = state.copy()
    private var encoded: ByteArray? = null

    /** A copy: the snapshot itself never changes. */
    val state: EditState get() = stored.copy()

    fun toJson(): JsonObject = JsonObject(mapOf(
        "state" to SwiftJSON.encodeToJsonElement(EditStateSerializer, stored),
        "captureCursor" to JsonPrimitive(captureCursor),
    ))

    @Synchronized fun data(): ByteArray {
        encoded?.let { return it }
        val data = toJson().sortedKeys().encodedBytes()
        encoded = data
        return data
    }

    companion object {
        fun fromJson(element: JsonElement): EditSnapshot {
            val values = element as? JsonObject ?: throw SerializationException("EditSnapshot")
            val state = SwiftJSON.decodeFromJsonElement(EditStateSerializer, values.field("state") ?: throw SerializationException("state"))
            val cursor = values.field("captureCursor")?.jsonPrimitive?.int ?: throw SerializationException("captureCursor")
            return EditSnapshot(state, cursor)
        }

        fun decode(bytes: ByteArray): EditSnapshot = fromJson(parseJson(bytes)).also { it.encoded = bytes }
    }
}

@Serializable
private data class CaptureLedgerEntry(val clip: TimelineClip, val insertion: Boolean)

@Serializable
private data class RecordingInsertionSite(val site: VideoInsertionSite, val firstCapture: Int)

data class DeferredProjectHistory(val manifest: File, val count: Int, val cursor: Int)

class Project(var title: String) {
    var exportSettings = ExportSettings()
    var schemaVersion = 6
    var id: UUID = newUUID()
    var createdAt: SwiftDate = SwiftDate.now()
    var updatedAt: SwiftDate = SwiftDate.now()
    var sources: List<SourceMedia> = emptyList()
    internal var snapshots: List<EditSnapshot> = listOf(EditSnapshot(EditState(), 0))
    private var captureLedger: List<CaptureLedgerEntry> = emptyList()
    var deferredHistory: DeferredProjectHistory? = null
    // Runtime provenance only. Pin the damaged index before the first edit
    // publishes over it, even after older rotating backups expire.
    var recoveryOrigin: String? = null
    var historyCursor = 0
    // Missing in older documents, where adding a captured segment was undoable.
    var recordingHistoryVersion: Int? = 1
    var recordingInsertion: Double? = null
        set(value) { field = value; if (value == null) recordingSite = null }
    var recordingInsertionStep: Int? = null
    private var recordingSite: RecordingInsertionSite? = null

    /** The Swift struct copy. */
    fun copy(): Project {
        val result = Project(title)
        result.exportSettings = exportSettings; result.schemaVersion = schemaVersion; result.id = id
        result.createdAt = createdAt; result.updatedAt = updatedAt; result.sources = sources
        result.snapshots = snapshots; result.captureLedger = captureLedger; result.deferredHistory = deferredHistory
        result.recoveryOrigin = recoveryOrigin; result.historyCursor = historyCursor
        result.recordingHistoryVersion = recordingHistoryVersion; result.recordingInsertion = recordingInsertion
        result.recordingInsertionStep = recordingInsertionStep; result.recordingSite = recordingSite
        return result
    }

    // Explicit fixture/import callers can still replace a complete history.
    // Normal editing uses snapshots directly, and materializes only its cursor.
    var history: List<EditState>
        get() = snapshots.map { materialized(it) }
        set(value) { snapshots = value.map { EditSnapshot(it, 0) }; captureLedger = emptyList() }

    private fun materialized(snapshot: EditSnapshot): EditState = materialized(snapshot, captureLedger.size)

    private fun materialized(snapshot: EditSnapshot, captureCount: Int): EditState {
        val state = snapshot.state
        for (event in captureLedger.take(captureCount).drop(snapshot.captureCursor)) {
            if (event.insertion) continue
            val addition = TrackTimeline(listOf(event.clip))
            val position = state.timeline.duration
            val layer = (state.timeline.video.maxOfOrNull { it.layer } ?: -1) + 1
            state.timeline.video = state.timeline.video + addition.video.map { it.copy(start = it.start + position, layer = layer) }
            state.timeline.audio = state.timeline.audio + addition.audio.map { it.copy(start = it.start + position) }
            state.timeline.links = state.timeline.links + addition.links
            sources.firstOrNull { it.id == event.clip.sourceID }?.let { state.timeline.includeCaptureBookmarks(it) }
        }
        state.timeline.captureBase = captureCount
        return state
    }

    fun beginRecordingInsertion(position: Double, site: VideoInsertionSite?) {
        recordingInsertion = position
        recordingInsertionStep = null
        recordingSite = site?.let { RecordingInsertionSite(it, captureLedger.size) }
    }

    fun recordingPlacement(): TakeReceipt.Placement {
        val insertion = recordingInsertion
        if (insertion == null || deferredHistory != null) throw ProjectError.invalidDocument
        return TakeReceipt.Placement(insertion, recordingSite?.site, RecoveryFile.hash(snapshots[historyCursor].data()) + ".json", captureLedger.size)
    }

    val edit: EditState get() = materialized(snapshots[historyCursor])

    val imageFiles: List<String>
        get() = history.flatMap { state -> state.clips.flatMap { clip -> clip.images.orEmpty().map { it.filename } } }.toSet().sorted()

    val effectFiles: List<String>
        get() {
            val effects = sources.flatMap { it.videoEffects.orEmpty() } + history.flatMap { state -> state.clips.flatMap { it.videoEffects.orEmpty() } }
            val luts = history.flatMap { state -> state.clips.flatMap { clip -> clip.color?.lutAssets.orEmpty().map { it.filename } } }
            return (effects.flatMap { it.mediaFiles } + luts).toSet().sorted()
        }

    // TODO(port): imported title fonts (`ImportedFontFace.Reference`, ImportedFonts.swift).
    val fontFiles: List<String> get() = emptyList()

    val mediaFiles: List<String> get() = sources.map { it.filename } + (imageFiles + effectFiles + fontFiles).toSet().sorted()
    val hasSourceMedia: Boolean get() = sources.isNotEmpty()
    val capturedDuration: Double get() = sources.fold(0.0) { total, source -> total + source.duration }
    val canUndo: Boolean get() = (deferredHistory?.cursor ?: historyCursor) > 0
    val canRedo: Boolean get() = (deferredHistory?.cursor ?: historyCursor) + 1 < (deferredHistory?.count ?: snapshots.size)

    fun canCommit(state: EditState): Boolean {
        val base = state.timeline.captureBase ?: return true
        return captureLedger.drop(max(0, base)).none { it.insertion }
    }

    fun commit(state: EditState) {
        if (!canCommit(state)) return
        val previous = edit
        val state = state.normalizedTools()
        state.retainCanvasReference(previous)
        if (state == previous) return
        // Older readers must not silently discard independent placement crops.
        schemaVersion = max(schemaVersion, if (state.timeline.placements.any { region -> region.videos.values.any { it.edgeCrop != null } }) 7 else 6)
        val motion = state.timeline.video.any { item ->
            val clip = item.content
            clip.crop?.motion != null || clip.color?.isAnimated == true || clip.images.orEmpty().any { it.motion != null && it.motion !is kotlinx.serialization.json.JsonNull } ||
                clip.videoEffects.orEmpty().any { it.motion != null }
        } || state.timeline.placements.any { region -> region.videos.values.any { it.motion != null } }
        if (motion) schemaVersion = max(schemaVersion, 12)
        if (state.timeline.subtitles != null) schemaVersion = max(schemaVersion, 8)
        // An older reader would pick the first timeline picture and silently reframe the movie.
        if (state.canvasReference != null) schemaVersion = max(schemaVersion, 10)
        if (state.timeline.audio.any { it.fadeIn != null || it.fadeOut != null }) schemaVersion = max(schemaVersion, 11)
        // Older builds must not silently discard a censor and reveal speech.
        if (state.timeline.audio.any { it.censors.isNotEmpty() } || sources.any { it.audioOnly == true }) schemaVersion = max(schemaVersion, 9)
        snapshots = snapshots.take(historyCursor + 1) +
            EditSnapshot(state, min(captureLedger.size, max(0, state.timeline.captureBase ?: captureLedger.size)))
        historyCursor += 1
        updatedAt = SwiftDate.now()
    }

    fun append(source: SourceMedia) {
        protectRecordingHistory()
        if (source.videoEffects.orEmpty().isNotEmpty()) schemaVersion = max(schemaVersion, 5)
        sources = sources + source
        includeRecording(source)
    }

    fun addRecording(source: SourceMedia) {
        schemaVersion = max(schemaVersion, 2)
        val position = recordingInsertion
        if (position == null) { append(source); return }
        if (source.videoEffects.orEmpty().isNotEmpty()) schemaVersion = max(schemaVersion, 5)
        sources = sources + source
        val clip = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration, videoEffects = source.videoEffects)
        captureLedger = captureLedger + CaptureLedgerEntry(clip, true)
        val continuing = recordingInsertionStep == historyCursor && historyCursor > 0 && historyCursor == snapshots.size - 1
        val previousSite = recordingSite
        if (!continuing && recordingInsertionStep != null && previousSite != null) {
            recordingSite = RecordingInsertionSite(previousSite.site, captureLedger.size - 1)
        }
        val site = recordingSite
        val next: EditState
        if (site != null && continuing) {
            // Refit the complete recording (including paused segments) just
            // like a Photos batch. A longer prepend may use more of the lead-in.
            val clips = captureLedger.drop(site.firstCapture).filter { it.insertion }.map { it.clip }
            val base = materialized(snapshots[historyCursor - 1])
            next = TimelineEditing.inserting(clips, base, position, site.site)
            val ids = clips.map { it.sourceID }.toSet()
            for (take in sources) if (take.id in ids) next.timeline.includeCaptureBookmarks(take)
        } else {
            next = TimelineEditing.inserting(listOf(clip), edit, position, site?.site)
            next.timeline.includeCaptureBookmarks(source)
        }
        if (continuing) {
            next.retainCanvasReference(edit)
            snapshots = snapshots.updated(historyCursor) { EditSnapshot(next.normalizedTools(), captureLedger.size) }
            updatedAt = SwiftDate.now()
        } else {
            commit(next); recordingInsertionStep = historyCursor
        }
        recordingInsertion = next.timeline.video.firstOrNull { it.content.sourceID == source.id }?.end?.seconds ?: min(edit.duration, position + source.duration)
    }

    /// Return false when the footage was rescued into Originals but the original
    /// edit location can no longer be proven. Never resurrect intentional cuts.
    fun indexTake(source: SourceMedia, receipt: TakeReceipt?, uncertain: Boolean): Boolean {
        if (sources.any { it.id == source.id || it.filename == source.filename }) return false
        if (source.audioOnly == true) {
            schemaVersion = max(schemaVersion, 9)
            sources = sources + source; updatedAt = SwiftDate.now(); return false
        }
        if (uncertain || receipt?.indexed == true) { sources = sources + source; updatedAt = SwiftDate.now(); return false }
        if (receipt != null) {
            if (!(receipt.projectID == id && receipt.segmentID == source.id)) throw RecoveryError.identity
            if (receipt.insertion == null) { append(source); return true }
            val revision = RecoveryFile.hash(snapshots[historyCursor].data()) + ".json"
            if (!(recordingInsertion == receipt.insertion && recordingSite?.site == receipt.insertionSite &&
                    revision == receipt.revision && captureLedger.size == receipt.captureCount)) {
                sources = sources + source; updatedAt = SwiftDate.now(); return false
            }
        }
        addRecording(source)
        return true
    }

    private fun includeRecording(source: SourceMedia) {
        val clip = TimelineClip(sourceID = source.id, start = 0.0, end = source.duration, videoEffects = source.videoEffects)
        captureLedger = captureLedger + CaptureLedgerEntry(clip, false)
        updatedAt = SwiftDate.now()
    }

    fun undo() { if (canUndo) { historyCursor -= 1; updatedAt = SwiftDate.now() } }
    fun redo() { if (canRedo) { historyCursor += 1; updatedAt = SwiftDate.now() } }

    // Explicit recovery only. An unused source might have been deliberately
    // removed, so loading a document must never automatically add it back.
    fun restoreUnusedRecording(id: UUID) {
        protectRecordingHistory()
        val source = sources.firstOrNull { it.id == id }
        if (source == null || source.audioOnly == true || history.any { state -> state.clips.any { it.sourceID == id } }) throw ProjectError.invalidDocument
        includeRecording(source)
    }

    fun protectRecordingHistory() {
        if (!(recordingHistoryVersion == null && history.isNotEmpty() && historyCursor in history.indices)) return
        val previous = history
        val seen = previous[0].clips.map { it.sourceID }.toMutableSet()
        val captureSteps = HashSet<Int>()
        for (index in previous.indices.drop(1)) {
            val before = previous[index - 1]
            val after = previous[index]
            try {
                val added = after.clips.lastOrNull() ?: continue
                if (after.clips.size != before.clips.size + 1 || added.sourceID in seen) continue
                val source = sources.firstOrNull { it.id == added.sourceID } ?: continue
                if (added != TimelineClip(id = added.id, sourceID = source.id, start = 0.0, end = source.duration)) continue
                val expected = before.copy()
                expected.clips = expected.clips + added
                if (expected != after) continue
                captureSteps += index
                // Only an exact first full-source append qualifies. Later cuts,
                // deliberate deletions and reinsertions remain ordinary edits.
                for (earlier in 0 until index) {
                    val states = history.toMutableList()
                    val state = states[earlier].copy()
                    state.clips = state.clips + added
                    states[earlier] = state
                    history = states
                }
            } finally {
                seen += after.clips.map { it.sourceID }
            }
        }
        val compacted = mutableListOf<EditState>()
        var cursor = 0
        for ((index, state) in history.withIndex()) {
            if (index !in captureSteps) compacted += state
            if (index == historyCursor) cursor = compacted.size - 1
        }
        history = compacted; historyCursor = cursor; recordingHistoryVersion = 1
    }

    fun validate() {
        val invalid = { ProjectError.invalidDocument }
        val history = history
        val reserved = setOf("project.json", "integrity.json", "cache", "processing", "previews", "exports")
        if (!(snapshots.all { it.captureCursor in 0..captureLedger.size } &&
                captureLedger.all { event -> sources.any { it.id == event.clip.sourceID } } &&
                schemaVersion in 1..latestReadableSchema && (recordingHistoryVersion == null || recordingHistoryVersion == 1) &&
                history.isNotEmpty() && historyCursor in history.indices &&
                sources.map { it.id }.toSet().size == sources.size &&
                sources.map { it.filename.lowercase() }.toSet().size == sources.size &&
                sources.all {
                    it.duration.isFinite() && it.duration > 0 && it.duration <= 604800 && (it.originalName?.let { name -> name.codePointCount(0, name.length) } ?: 0) <= 255 &&
                        it.filename == lastPathComponent(it.filename) && !it.filename.startsWith(".") && !it.filename.contains("\\") &&
                        it.filename.lowercase() !in reserved
                })) throw invalid()
        recordingInsertion?.let { if (!(it.isFinite() && it >= 0 && it <= 604800)) throw invalid() }
        recordingInsertionStep?.let { if (it !in history.indices) throw invalid() }
        recordingSite?.let { if (!(recordingInsertion != null && it.firstCapture in 0..captureLedger.size)) throw invalid() }
        val mediaFiles = mediaFiles
        if (mediaFiles.map { it.lowercase() }.toSet().size != mediaFiles.size) throw invalid()
        for (filename in imageFiles) {
            if (!(filename.startsWith("image-") && filename == lastPathComponent(filename) && !filename.contains("\\") &&
                    pathExtension(filename).lowercase() in listOf("png", "jpg", "jpeg", "heic"))) throw invalid()
        }
        for (filename in effectFiles) {
            if (!(filename.startsWith("effect-") && filename == lastPathComponent(filename) && !filename.contains("\\") &&
                    pathExtension(filename).lowercase() in listOf("json", "png", "jpg", "jpeg", "heic", "mov", "mp4", "bin", "cube"))) throw invalid()
        }
        for (source in sources) {
            val recipe = source.videoEffects.orEmpty()
            if (!(recipe.all { it.valid } && recipe.map { it.id }.toSet().size == recipe.size)) throw invalid()
            source.sha256?.let { hash -> if (!(hash.length == 64 && hash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })) throw invalid() }
            if (!(source.events ?: emptyList()).all { it.time.isFinite() && it.time >= 0 && it.time <= source.duration + 1 }) throw invalid()
        }
        for (state in history) {
            state.timeline.validate(sources)
            state.outputSize?.let { size ->
                if (!(size.width.isFinite() && size.height.isFinite() && size.width in 2.0..16384.0 && size.height in 2.0..16384.0 &&
                        size.width % 2 == 0.0 && size.height % 2 == 0.0)) throw invalid()
            }
            if (!(state.duration.isFinite() && state.duration <= 604800)) throw invalid()
            val clips = state.clips
            val effects = state.effects
            if (!(clips.map { it.id }.toSet().size == clips.size && effects.map { it.id }.toSet().size == effects.size)) throw invalid()
            for (clip in clips) {
                val source = sources.firstOrNull { it.id == clip.sourceID } ?: throw invalid()
                if (!(clip.start.isFinite() && clip.end.isFinite() && clip.start >= 0 && clip.end > clip.start && clip.end <= source.duration + 0.001)) throw invalid()
                val speed = clip.speed?.rate
                if (!(clip.rate.isFinite() && clip.rate in 0.125..6.0 && clip.duration > 0 && (speed == null || (speed.isFinite() && speed in 0.25..4.0)))) throw invalid()
                clip.frozenFrame?.let { frozen ->
                    // A hold belongs to the Freeze Frame video effect that created it.
                    if (!(frozen.valid && frozen.sourceTime >= clip.start && frozen.sourceTime < clip.end &&
                            clip.videoEffects?.any { it.kind == VideoEffectID.freezeFrame } == true)) throw invalid()
                }
                clip.crop?.let { crop ->
                    // TODO(port): `EffectAnimation.valid` for an animated crop's endpoints.
                    if (!(crop.x.isFinite() && crop.y.isFinite() && crop.zoom.isFinite() && crop.rotation.isFinite() && crop.rotation in -180.0..180.0 &&
                            crop.x in 0.0..1.0 && crop.y in 0.0..1.0 && crop.zoom in 1.0..8.0)) throw invalid()
                }
                if (!listOfNotNull(clip.openingTransition, clip.transition).all { it.valid }) throw invalid()
                clip.pauses?.let { pauses ->
                    if (!(pauses.thresholdDB.isFinite() && pauses.thresholdDB in -80.0..0.0 && pauses.minimum.isFinite() && pauses.minimum in 0.1..10.0 &&
                            pauses.before.isFinite() && pauses.before in 0.0..2.0 && pauses.after.isFinite() && pauses.after in 0.0..2.0 &&
                            pauses.cuts.all { it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start && it.end <= source.duration + 0.001 })) throw invalid()
                    if (!pauses.candidates.orEmpty().all { it.raw.start.isFinite() && it.raw.end.isFinite() && it.raw.start >= 0 && it.raw.end > it.raw.start &&
                            it.raw.end <= source.duration + 0.001 }) throw invalid()
                    pauses.analyzed?.let { if (!(it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start && it.end <= source.duration + 0.001)) throw invalid() }
                }
                // TODO(port): `EffectAnimation.valid` for an animated grade's endpoints.
                clip.color?.let { if (!it.valid) throw invalid() }
                clip.stabilization?.let { stabilization ->
                    if (stabilization.version != 1) throw invalid()
                    val start = stabilization.referenceStart
                    val end = stabilization.referenceEnd
                    if (start != null && end != null) {
                        if (!(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= source.duration + 0.001)) throw invalid()
                    } else if (start != null || end != null) throw invalid()
                }
                val texts = clip.texts.orEmpty()
                if (texts.map { it.id }.toSet().size != texts.size) throw invalid()
                for (text in texts) {
                    if (!(text.start.isFinite() && text.end.isFinite() && text.start >= 0 && text.end > text.start && text.end <= source.duration + 0.001 &&
                            text.appearance?.valid != false && text.motion?.valid != false &&
                            text.text.codePointCount(0, text.text.length) <= 500 && text.x.isFinite() && text.y.isFinite() &&
                            text.x in 0.0..1.0 && text.y in 0.0..1.0 && text.size.isFinite() && text.size in 0.02..0.3)) throw invalid()
                }
                val images = clip.images.orEmpty()
                if (images.map { it.id }.toSet().size != images.size) throw invalid()
                for (image in images) {
                    // TODO(port): `EffectAnimation.valid` for an animated image's endpoints.
                    if (!(image.start.isFinite() && image.end.isFinite() && image.start >= 0 && image.end > image.start && image.end <= source.duration + 0.001 &&
                            image.x.isFinite() && image.y.isFinite() && image.x in 0.0..1.0 && image.y in 0.0..1.0 &&
                            image.width.isFinite() && image.width in 0.03..3.0 && image.opacity.isFinite() && image.opacity in 0.0..1.0)) throw invalid()
                }
                val videoEffects = clip.videoEffects.orEmpty()
                if (!(videoEffects.all { it.valid } && videoEffects.map { it.id }.toSet().size == videoEffects.size)) throw invalid()
            }
            if (!effects.all {
                    it.implementationVersion > 0 && it.amount.isFinite() &&
                        (!it.supported || it.amount in (if (it.kind.isNeural) 0.0..1.0 else -24.0..24.0)) &&
                        it.frequency.isFinite() && it.frequency in 20.0..20000.0
                }) throw invalid()
        }
    }

    // ---- Persistence (Swift `extension Project`) ----

    /** Swift `HistoryReader`: loads snapshot payloads named by the manifest. */
    class HistoryReader(val summaryFile: File?, val load: (String) -> ByteArray)

    fun manifest(write: (String, ByteArray) -> Unit): ByteArray {
        if (deferredHistory != null) throw ProjectError.invalidDocument
        val names = mutableListOf<String>()
        for (snapshot in snapshots) {
            val data = snapshot.data()
            val name = sha256Hex(data) + ".json"
            write(name, data)
            names += name
        }
        val metadata = copy()
        metadata.snapshots = emptyList()
        val header = LinkedHashMap(metadata.toJson())
        header.remove("snapshots")
        header["snapshotFiles"] = JsonArray(names.map { JsonPrimitive(it) })
        return JsonObject(header).sortedKeys().encodedBytes()
    }

    fun toJson(): JsonObject {
        val values = LinkedHashMap<String, JsonElement>()
        values["exportSettings"] = SwiftJSON.encodeToJsonElement(ExportSettings.serializer(), exportSettings)
        values["schemaVersion"] = JsonPrimitive(schemaVersion)
        values["id"] = JsonPrimitive(id.uuidString)
        values["title"] = JsonPrimitive(title)
        values["createdAt"] = JsonPrimitive(createdAt.timeIntervalSinceReferenceDate)
        values["updatedAt"] = JsonPrimitive(updatedAt.timeIntervalSinceReferenceDate)
        values["sources"] = SwiftJSON.encodeToJsonElement(ListSerializer(SourceMedia.serializer()), sources)
        values["historyCursor"] = JsonPrimitive(historyCursor)
        values["snapshots"] = JsonArray(snapshots.map { it.toJson() })
        values["captureLedger"] = SwiftJSON.encodeToJsonElement(ListSerializer(CaptureLedgerEntry.serializer()), captureLedger)
        values["automaticCanvasVersion"] = JsonPrimitive(1)
        recordingInsertionStep?.let { values["recordingInsertionStep"] = JsonPrimitive(it) }
        recordingInsertion?.let { values["recordingInsertion"] = JsonPrimitive(it) }
        recordingSite?.let { values["recordingSite"] = SwiftJSON.encodeToJsonElement(RecordingInsertionSite.serializer(), it) }
        recordingHistoryVersion?.let { values["recordingHistoryVersion"] = JsonPrimitive(it) }
        return JsonObject(values)
    }

    /// Old versions wrote a fixed size during timeline edits as well as Crop.
    /// Only a change of picture sources proves that the size came from a timeline
    /// edit. Preserve unattested formats and later Crop decisions, including Redo.
    private fun repairImplicitCanvasSizes() {
        var inheritedSize: CGSize? = null
        for (index in snapshots.indices.drop(1)) {
            val entry = snapshots[index]
            val state = entry.state
            val size = state.outputSize
            if (size == null || !state.timeline.video.all { it.content.crop == null }) { inheritedSize = null; continue }
            val before = materialized(snapshots[index - 1], entry.captureCursor)
            val previousSources = before.timeline.video.map { it.content.sourceID }.toSet()
            val addedVideo = state.timeline.video.any { it.content.sourceID !in previousSources }
            val startsImplicitSize = before.outputSize == null && previousSources.isNotEmpty() &&
                before.timeline.video.all { it.content.crop == null } &&
                (before.firstVideoSourceID != state.firstVideoSourceID || addedVideo)
            if (!(size == inheritedSize || (inheritedSize == null && startsImplicitSize))) { inheritedSize = null; continue }
            inheritedSize = size
            state.outputSize = null
            state.retainCanvasReference(before)
            if (state.canvasReference != null) schemaVersion = max(schemaVersion, 10)
            snapshots = snapshots.updated(index) { EditSnapshot(state, entry.captureCursor) }
        }
    }

    companion object {
        const val latestReadableSchema = 12

        fun isSnapshotName(name: String): Boolean =
            name.toByteArray(Charsets.UTF_8).size == 69 && name.endsWith(".json") && name.take(64).all { it in "0123456789abcdef" }

        fun readManifest(data: ByteArray, summaryFile: File? = null, load: (String) -> ByteArray): Project =
            fromJson(parseJson(data), HistoryReader(summaryFile, load))

        /** Swift `JSONEncoder().encode(project)` (snapshots inline). */
        fun encode(project: Project): ByteArray = project.toJson().encodedBytes()
        /** Swift `JSONDecoder().decode(Project.self, from:)` without a history reader. */
        fun decode(data: ByteArray): Project = fromJson(parseJson(data), null)

        // Normalize before a caller can read edit and build its next operation.
        // Migrating inside commit would be too late: that operation could have
        // captured a legacy state which was still missing an undone recording.
        fun fromJson(element: JsonElement, reader: HistoryReader?): Project {
            val values = element as? JsonObject ?: throw SerializationException("Project")
            fun required(key: String): JsonElement = values.field(key) ?: throw SerializationException("Missing key $key")
            val json = SwiftJSON
            val project = Project(json.decodeFromJsonElement(String.serializer(), required("title")))
            project.exportSettings = values.field("exportSettings")?.let { json.decodeFromJsonElement(ExportSettings.serializer(), it) } ?: ExportSettings()
            project.schemaVersion = required("schemaVersion").jsonPrimitive.intOrNull ?: throw SerializationException("schemaVersion")
            project.id = json.decodeFromJsonElement(UUIDSerializer, required("id"))
            project.createdAt = json.decodeFromJsonElement(SwiftDate.serializer(), required("createdAt"))
            project.updatedAt = json.decodeFromJsonElement(SwiftDate.serializer(), required("updatedAt"))
            project.sources = json.decodeFromJsonElement(ListSerializer(SourceMedia.serializer()), required("sources"))
            project.historyCursor = required("historyCursor").jsonPrimitive.intOrNull ?: throw SerializationException("historyCursor")
            project.captureLedger = values.field("captureLedger")?.let { json.decodeFromJsonElement(ListSerializer(CaptureLedgerEntry.serializer()), it) } ?: emptyList()
            val names = values.field("snapshotFiles")?.let { json.decodeFromJsonElement(ListSerializer(String.serializer()), it) }
            if (names != null) {
                if (reader == null || project.historyCursor !in names.indices || !names.all(::isSnapshotName)) throw ProjectError.invalidDocument
                val requested: List<String>
                val summary = reader.summaryFile
                if (summary != null) {
                    project.deferredHistory = DeferredProjectHistory(summary, names.size, project.historyCursor)
                    requested = listOf(names[project.historyCursor])
                    project.historyCursor = 0
                } else requested = names
                project.snapshots = requested.map { name ->
                    val payload = reader.load(name)
                    if (sha256Hex(payload) + ".json" != name) throw ProjectError.invalidDocument
                    EditSnapshot.decode(payload)
                }
            } else {
                val records = values.field("snapshots")
                if (records != null) project.snapshots = records.array().map { EditSnapshot.fromJson(it) }
                else project.history = json.decodeFromJsonElement(ListSerializer(EditStateSerializer), required("history"))
            }
            project.recordingInsertionStep = if (project.deferredHistory == null) values.field("recordingInsertionStep")?.jsonPrimitive?.int else null
            project.recordingInsertion = values.field("recordingInsertion")?.let { json.decodeFromJsonElement(Double.serializer(), it) }
            project.recordingSite = values.field("recordingSite")?.let { json.decodeFromJsonElement(RecordingInsertionSite.serializer(), it) }
            project.recordingHistoryVersion = values.field("recordingHistoryVersion")?.jsonPrimitive?.int
            project.validate()
            project.protectRecordingHistory()
            if (project.deferredHistory == null && !values.containsKey("automaticCanvasVersion")) project.repairImplicitCanvasSizes()
            project.validate()
            return project
        }

        private fun lastPathComponent(name: String): String = name.trimEnd('/').substringAfterLast('/').ifEmpty { name }
        private fun pathExtension(name: String): String = lastPathComponent(name).let { if ('.' in it.drop(1)) it.substringAfterLast('.') else "" }
    }
}

@Serializable
data class ExportSettings(
    val codec: Codec = Codec.h264,
    val resolution: Resolution = Resolution.project,
    val frameRate: FrameRate = FrameRate.standard,
) {
    @Serializable
    enum class Codec(val rawValue: String) {
        @SerialName("H.264") h264("H.264"),
        @SerialName("H.265") hevc("H.265"),
    }

    @Serializable
    enum class Resolution(val rawValue: String) {
        @SerialName("Project size") project("Project size"),
        @SerialName("720p") hd("720p"),
        @SerialName("1080p") fullHD("1080p"),
        @SerialName("4K") ultraHD("4K");

        fun size(canvas: CGSize): CGSize {
            val limit = when (this) {
                project -> return canvas
                hd -> 1280.0 to 720.0
                fullHD -> 1920.0 to 1080.0
                ultraHD -> 3840.0 to 2160.0
            }
            val scale = min(limit.first / max(canvas.width, canvas.height), limit.second / min(canvas.width, canvas.height))
            fun even(dimension: Double) = max(2.0, swiftRound(dimension * scale / 2) * 2)
            return CGSize(even(canvas.width), even(canvas.height))
        }
    }

    @Serializable(with = FrameRateSerializer::class)
    enum class FrameRate(val rawValue: Int) { film(24), pal(25), standard(30), palHigh(50), high(60) }

    fun outputCodec(hdr: Boolean): Codec = if (hdr) Codec.hevc else codec
}

object FrameRateSerializer : KSerializer<ExportSettings.FrameRate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FrameRate", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: ExportSettings.FrameRate) = encoder.encodeInt(value.rawValue)
    override fun deserialize(decoder: Decoder): ExportSettings.FrameRate {
        val raw = decoder.decodeInt()
        return ExportSettings.FrameRate.entries.firstOrNull { it.rawValue == raw } ?: throw SerializationException("Unknown frame rate $raw")
    }
}

@Serializable
data class ExportReceipt(
    val schemaVersion: Int = 1,
    val id: UUID = newUUID(),
    val createdAt: SwiftDate = SwiftDate.now(),
    val filename: String,
    val projectID: UUID,
    val projectTitle: String,
    val historyRevision: Int,
    val edit: EditState,
    val sources: List<SourceMedia>,
    val sourceChecksums: Map<String, String>,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int = 30,
    val settings: ExportSettings? = null,
    val codec: ExportSettings.Codec? = null,
    // Measured from this export's final mix, not whole cached source recordings.
    // Older exports without a measurement make no claim about audio headroom.
    val audioPeak: Float? = null,
) {
    val audioWarning: String?
        get() {
            val peak = audioPeak ?: return null
            if (!(peak.isFinite() && peak > 1.0001f)) return null
            val excess = max(0.1, ceil(20 * log10(peak.toDouble()) * 10) / 10)
            return "Peaks are %.1f dB above full scale. Lower Volume in the editor and export again.".format(java.util.Locale.ROOT, excess)
        }
}

class ProjectError private constructor(val case: Case) : Exception(case.description) {
    enum class Case(val description: String) {
        invalidDocument("This project has unsupported or invalid editing data. Its files have been preserved."),
        missingMedia("A source file is missing. The project has been preserved."),
        missingImage("An overlay image is missing. Open Tools and select its Image entry to replace or remove it. Your recordings and editing history are preserved."),
        emptyTimeline("Add a recording before previewing or exporting."),
        invalidMedia("The recording could not be opened as a valid video. Its file has been preserved."),
    }

    companion object {
        val invalidDocument get() = ProjectError(Case.invalidDocument)
        val missingMedia get() = ProjectError(Case.missingMedia)
        val missingImage get() = ProjectError(Case.missingImage)
        val emptyTimeline get() = ProjectError(Case.emptyTimeline)
        val invalidMedia get() = ProjectError(Case.invalidMedia)
    }
}

@Serializable
data class ProjectDeletion(
    val id: UUID,
    val token: UUID,
    val date: SwiftDate,
    val dateIsKnown: Boolean,
) {
    fun isOlderThan30Days(now: SwiftDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!date.timeIntervalSinceReferenceDate.isFinite()) return false
        val current = ZonedDateTime.ofInstant(now.toInstant(), zone)
        val shift = Duration.between(current.minusDays(30), current)
        val cutoff = now.addingTimeInterval(-(shift.seconds + shift.nano / 1e9))
        return date < cutoff
    }
}

data class ProjectDisk(val root: File) {
    fun folder(id: UUID): File = File(root, id.uuidString)

    fun recoveryFiles(project: Project): List<File> {
        val directory = File(folder(project.id), "Recovery")
        val entries = directory.listFiles()?.toList() ?: emptyList()
        return entries.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.extension.lowercase() in listOf("mov", "mp4") }.sortedBy { it.path }
    }

    fun quarantineRecording(requested: File, project: Project) {
        // Compare canonical paths, then move only the verified, non-symlink
        // orphan from our enumeration, never an arbitrary caller-supplied alias.
        val file = unindexedRecordings(project).firstOrNull { it.canonicalFile == requested.canonicalFile } ?: return
        val destination = File(File(folder(project.id), "Recovery"), RecoveryFile.hash(file.name.toByteArray(Charsets.UTF_8)))
        Files.createDirectories(destination.toPath())
        val stem = file.nameWithoutExtension + "."
        val companions = folder(project.id).listFiles()?.toList() ?: throw IOException("Cannot list ${folder(project.id)}")
        val mediaFiles = project.mediaFiles
        val sidecars = companions.filter { it != file && it.name != file.name && it.name.startsWith(stem) && it.name !in mediaFiles }
        for (companion in sidecars) {
            val target = File(destination, companion.name)
            if (!target.exists()) Files.copy(companion.toPath(), target.toPath())
        }
        Files.move(file.toPath(), File(destination, file.name).toPath())
        for (companion in sidecars) runCatching { Files.delete(companion.toPath()) }
    }

    fun unindexedRecordings(project: Project): List<File> {
        val known = project.mediaFiles.map { it.lowercase() }.toSet()
        val files = folder(project.id).listFiles()?.toList() ?: throw java.io.FileNotFoundException(folder(project.id).path)
        return files.filter { file ->
            val name = file.name.lowercase()
            // Effect assets share the project directory with captures. Also
            // exclude unused/undone assets: a library summary omits older edits.
            file.extension.lowercase() in listOf("mov", "mp4") && !name.startsWith("recovered-") && !name.startsWith(".") &&
                !name.startsWith("effect-") && !VideoBatchImport.ownsFilename(name) && name !in known &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.sortedBy { it.name }
    }

    // Fixture stores must never share the user's Recently Deleted directory.
    val deletedRoot: File
        get() = File(root.parentDirectory, if (root.name == "Projects") "RecentlyDeleted" else root.name + "-RecentlyDeleted")

    /** Same name as the iOS app, so a deleted project folder means the same on both. */
    private fun deletionFile(directory: File) = File(directory, ".yourec-deletion.json")

    fun moveToDeleted(project: Project, now: SwiftDate = SwiftDate.now()) {
        Files.createDirectories(deletedRoot.toPath())
        val receipt = ProjectDeletion(project.id, newUUID(), now, true)
        // A failed receipt write leaves the active project in place. The manifest,
        // originals and immutable edit history are not rewritten for deletion.
        RecoveryFile.write(SwiftJSON.encodeToString(ProjectDeletion.serializer(), receipt).toByteArray(), deletionFile(folder(project.id)))
        Files.move(folder(project.id).toPath(), File(deletedRoot, project.id.uuidString).toPath())
    }

    fun restoreDeleted(project: Project) {
        Files.move(File(deletedRoot, project.id.uuidString).toPath(), folder(project.id).toPath())
        runCatching { Files.deleteIfExists(deletionFile(folder(project.id)).toPath()) }
    }

    fun deletionRecord(id: UUID, firstSeenAt: SwiftDate = SwiftDate.now()): ProjectDeletion {
        val directory = File(deletedRoot, id.uuidString)
        if (!File(directory, "project.json").exists()) throw ProjectError.invalidDocument
        val file = deletionFile(directory)
        if (file.exists()) {
            val record = SwiftJSON.decodeFromString(ProjectDeletion.serializer(), file.readText())
            if (!(record.id == id && record.date.timeIntervalSinceReferenceDate.isFinite())) throw ProjectError.invalidDocument
            return record
        }
        // Previous builds did not record deletion time. Start a conservative
        // 30-day clock now; never substitute last edit or original creation time.
        val record = ProjectDeletion(id, newUUID(), firstSeenAt, false)
        RecoveryFile.write(SwiftJSON.encodeToString(ProjectDeletion.serializer(), record).toByteArray(), file)
        return record
    }

    fun eraseExpiredDeletion(confirmed: ProjectDeletion, now: SwiftDate = SwiftDate.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val directory = File(deletedRoot, confirmed.id.uuidString)
        if (!directory.exists()) return false
        val current = deletionRecord(confirmed.id, now)
        // Revalidate the exact deletion event: restoring and deleting the same
        // project again invalidates an already-visible confirmation.
        if (!(current == confirmed && current.isOlderThan30Days(now, zone))) return false
        eraseDeleted(confirmed.id)
        return true
    }

    fun eraseDeleted(id: UUID) {
        val original = File(deletedRoot, id.uuidString)
        val pending = File(deletedRoot, ".purging-" + newUUID().uuidString)
        // This rename is the permanent-deletion commit. Only confirmed removals
        // enter this queue; a crash after any child unlink can safely resume.
        Files.move(original.toPath(), pending.toPath())
        pending.deleteRecursively()
    }

    fun finishPendingDeletions() {
        for (folder in deletedRoot.listFiles()?.toList() ?: emptyList()) {
            val name = folder.name
            if (!(name.startsWith(".purging-") && uuidOrNull(name.removePrefix(".purging-")) != null &&
                    Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS))) continue
            folder.deleteRecursively()
        }
    }

    fun save(project: Project) {
        if (project.deferredHistory != null) throw ProjectError.invalidDocument
        project.validate()
        synchronized(writeLock) {
            val directory = folder(project.id)
            val history = File(directory, "History")
            Files.createDirectories(history.toPath())
            // TODO(port): ImportedFonts.preserve (imported title fonts).
            val head = File(directory, "project.json")
            val previous = RecoveryFile.readIfPresent(head)
            val damaged = project.recoveryOrigin
            if (previous != null && damaged != null && RecoveryFile.hash(previous) == damaged) {
                val preserved = File(directory, ".revisions/damaged-head-$damaged.json")
                if (!preserved.exists()) RecoveryFile.write(previous, preserved)
            }
            if (previous != null) keepManifest(previous, directory, project.schemaVersion)
            val data = project.manifest { name, bytes -> RecoveryFile.ensureSnapshot(bytes, File(history, name)) }
            if (previous == null || !data.contentEquals(previous)) RecoveryFile.write(data, head)
            // Secondary copy. A failure here cannot revoke the successful primary commit.
            runCatching { keepManifest(data, directory, project.schemaVersion) }
            clearMatchingDraft(data, directory)
        }
    }

    fun read(file: File, summary: Boolean = false): Project {
        val data = file.readBytes()
        val history = File(file.parentDirectory, "History")
        return Project.readManifest(data, if (summary) file else null) { name -> File(history, name).readBytes() }
    }

    companion object {
        private val writeLock = Any()
    }
}

/// Expensive media jobs can report thousands of blocks per second. Keep UI
/// updates bounded while always delivering the start and completion values.
class MediaProgress(private val callback: (Double) -> Unit) {
    private var lastTime = Double.NEGATIVE_INFINITY
    private var lastValue = -1.0
    fun report(value: Double) {
        val bounded = min(1.0, max(0.0, value))
        val now = System.nanoTime() / 1e9
        val publish: Boolean
        synchronized(this) {
            publish = bounded != lastValue && (now - lastTime >= 0.1 || bounded == 0.0 || bounded == 1.0)
            if (publish) { lastTime = now; lastValue = bounded }
        }
        if (publish) callback(bounded)
    }
}

