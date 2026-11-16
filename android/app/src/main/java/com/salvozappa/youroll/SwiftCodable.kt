// YouRoll support code (not a port of a single Swift file).
// Copyright (c) 2026 YouRoll contributors. MIT License (see LICENSE at the repository root).
//
// Kotlin equivalents of the Swift Foundation encodings used by YouRec's
// `Codable` documents, so project files written by either app stay readable:
// - UUID: uppercase string (Foundation `uuidString`)
// - Date: Double seconds since 2001-01-01 00:00:00 UTC (default JSONEncoder)
// - CGSize: `[width, height]` (CoreGraphics Codable conformance); CGPoint,
//   CGRect and CGAffineTransform stand-ins for the crop geometry
// - Dictionary with UUID keys: flat array `[k1, v1, k2, v2, …]`
// - Set: array
// - Data: base64 string (default JSONEncoder)
// - nil optionals omitted, unknown keys ignored
package com.salvozappa.youroll

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs

typealias UUID = java.util.UUID

private val uuidPattern = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

/** Foundation `UUID.uuidString`: uppercase. Swift orders UUIDs by this string. */
val UUID.uuidString: String get() = toString().uppercase()

/** Foundation `UUID(uuidString:)`: strict 8-4-4-4-12 hex, case-insensitive. */
fun uuidOrNull(value: String): UUID? = if (uuidPattern.matches(value)) UUID.fromString(value) else null

fun newUUID(): UUID = UUID.randomUUID()

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SwiftUUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.uuidString)
    override fun deserialize(decoder: Decoder): UUID {
        val text = decoder.decodeString()
        return uuidOrNull(text) ?: throw SerializationException("Invalid UUID string: $text")
    }
}

/** Foundation `Date`, stored exactly as the default JSONEncoder writes it. */
@kotlinx.serialization.Serializable(with = SwiftDateSerializer::class)
data class SwiftDate(val timeIntervalSinceReferenceDate: Double) : Comparable<SwiftDate> {
    val timeIntervalSince1970: Double get() = timeIntervalSinceReferenceDate + REFERENCE_EPOCH
    fun addingTimeInterval(seconds: Double) = SwiftDate(timeIntervalSinceReferenceDate + seconds)
    override fun compareTo(other: SwiftDate) = timeIntervalSinceReferenceDate.compareTo(other.timeIntervalSinceReferenceDate)
    fun toInstant(): java.time.Instant {
        val millis = Math.round(timeIntervalSince1970 * 1000.0)
        return java.time.Instant.ofEpochMilli(millis)
    }

    companion object {
        /** 2001-01-01T00:00:00Z in Unix seconds. */
        const val REFERENCE_EPOCH = 978_307_200.0
        fun now() = SwiftDate(System.currentTimeMillis() / 1000.0 - REFERENCE_EPOCH)
        fun fromUnix(seconds: Double) = SwiftDate(seconds - REFERENCE_EPOCH)
        fun of(instant: java.time.Instant) = fromUnix(instant.toEpochMilli() / 1000.0)
        /** Foundation `Date.distantPast` (0001-01-01). */
        val distantPast = SwiftDate(-63_114_076_800.0)
    }
}

object SwiftDateSerializer : KSerializer<SwiftDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SwiftDate", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: SwiftDate) = encoder.encodeDouble(value.timeIntervalSinceReferenceDate)
    override fun deserialize(decoder: Decoder) = SwiftDate(decoder.decodeDouble())
}

/** CoreGraphics `CGSize`; its Codable form is the unkeyed array `[width, height]`. */
@kotlinx.serialization.Serializable(with = CGSizeSerializer::class)
data class CGSize(val width: Double, val height: Double)

object CGSizeSerializer : KSerializer<CGSize> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: CGSize) = delegate.serialize(encoder, listOf(value.width, value.height))
    override fun deserialize(decoder: Decoder): CGSize {
        val values = delegate.deserialize(decoder)
        if (values.size != 2) throw SerializationException("CGSize needs two values")
        return CGSize(values[0], values[1])
    }
}

/** CoreGraphics `CGPoint` (geometry only; not persisted). */
data class CGPoint(val x: Double, val y: Double) {
    fun applying(t: CGAffineTransform) = CGPoint(t.a * x + t.c * y + t.tx, t.b * x + t.d * y + t.ty)

    companion object { val zero = CGPoint(0.0, 0.0) }
}

/** CoreGraphics `CGRect` with a top-left origin, as UIKit draws it (geometry only). */
data class CGRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
    val size: CGSize get() = CGSize(width, height)

    companion object { val zero = CGRect(0.0, 0.0, 0.0, 0.0) }
}

/**
 * CoreGraphics `CGAffineTransform`, in its row-vector convention:
 * x' = a·x + c·y + tx, y' = b·x + d·y + ty. `t1.concatenating(t2)` applies t1, then t2.
 */
data class CGAffineTransform(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    fun concatenating(t: CGAffineTransform) = CGAffineTransform(
        a * t.a + b * t.c, a * t.b + b * t.d,
        c * t.a + d * t.c, c * t.b + d * t.d,
        tx * t.a + ty * t.c + t.tx, tx * t.b + ty * t.d + t.ty)

    fun inverted(): CGAffineTransform {
        val det = a * d - b * c
        if (det == 0.0) return this
        return CGAffineTransform(d / det, -b / det, -c / det, a / det, (c * ty - d * tx) / det, (b * tx - a * ty) / det)
    }

    companion object {
        val identity = CGAffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translation(x: Double, y: Double) = CGAffineTransform(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double) = CGAffineTransform(x, 0.0, 0.0, y, 0.0, 0.0)
        /** Positive angles turn clockwise on screen (y down), as in UIKit. */
        fun rotation(radians: Double) = CGAffineTransform(kotlin.math.cos(radians), kotlin.math.sin(radians), -kotlin.math.sin(radians), kotlin.math.cos(radians), 0.0, 0.0)
    }
}

/** AVFoundation `AVMakeRect(aspectRatio:insideRect:)`: the largest rect of that aspect, centred in [bounds]. */
fun aspectFitRect(aspect: CGSize, bounds: CGRect): CGRect {
    if (aspect.width <= 0 || aspect.height <= 0 || bounds.width <= 0 || bounds.height <= 0) return CGRect(bounds.midX, bounds.midY, 0.0, 0.0)
    val scale = kotlin.math.min(bounds.width / aspect.width, bounds.height / aspect.height)
    val width = aspect.width * scale
    val height = aspect.height * scale
    return CGRect(bounds.midX - width / 2, bounds.midY - height / 2, width, height)
}

/**
 * Swift encodes a `Dictionary` whose key is not String/Int as a flat unkeyed
 * array of alternating keys and values. Values here are passed through as JSON.
 */
object UUIDKeyedJsonMapSerializer : KSerializer<Map<UUID, JsonElement>> {
    private val delegate = ListSerializer(JsonElement.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Map<UUID, JsonElement>) {
        val flat = value.flatMap { (key, element) -> listOf(JsonPrimitive(key.uuidString), element) }
        delegate.serialize(encoder, flat)
    }
    override fun deserialize(decoder: Decoder): Map<UUID, JsonElement> {
        val flat = delegate.deserialize(decoder)
        if (flat.size % 2 != 0) throw SerializationException("Odd keyed container")
        val result = LinkedHashMap<UUID, JsonElement>()
        for (index in flat.indices step 2) {
            val key = (flat[index] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::uuidOrNull)
                ?: throw SerializationException("Invalid UUID key")
            if (result.containsKey(key)) throw SerializationException("Duplicate key")
            result[key] = flat[index + 1]
        }
        return result
    }
}

/** The one JSON configuration used for project documents. */
val SwiftJSON: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** `JSONEncoder.outputFormatting = [.sortedKeys]`: object keys sorted recursively. */
fun JsonElement.sortedKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(keys.sorted().associateWith { getValue(it).sortedKeys() })
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    else -> this
}

fun JsonElement.encodedBytes(): ByteArray = SwiftJSON.encodeToString(JsonElement.serializer(), this).toByteArray(Charsets.UTF_8)

fun parseJson(bytes: ByteArray): JsonElement = SwiftJSON.parseToJsonElement(bytes.toString(Charsets.UTF_8))

/** Swift `Double.rounded()`: to nearest, ties away from zero. */
fun swiftRound(value: Double): Double {
    if (!value.isFinite()) return value
    val magnitude = Math.round(abs(value)).toDouble()
    return if (value < 0) -magnitude else magnitude
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text)

// Small helpers for passing out-of-scope payloads through untouched while
// still reading the few values the in-scope logic depends on.
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)?.takeUnless { it is JsonNull }
internal fun JsonElement?.double(name: String): Double? = (field(name) as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement?.bool(name: String): Boolean? = (field(name) as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement?.string(name: String): String? = (field(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
internal fun JsonObject.with(name: String, value: JsonElement?): JsonObject =
    JsonObject(if (value == null) this - name else this + (name to value))
internal fun JsonElement.jsonArrayOrEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonElement.primitiveContent(): String? = runCatching { jsonPrimitive.content }.getOrNull()
internal fun JsonElement.arrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

// Swift Set algebra used throughout the timeline code.
internal fun <T> Set<T>.isDisjoint(other: Collection<T>): Boolean = none { it in other }
internal fun <T> Set<T>.isSubset(of: Collection<T>): Boolean = of.containsAll(this)

/** Replace element [index] with [transform] applied to it. */
internal inline fun <T> List<T>.updated(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, value -> if (i == index) transform(value) else value }

/** Swift `deletingLastPathComponent()`; the filesystem root is its own parent. */
internal val java.io.File.parentDirectory: java.io.File get() = absoluteFile.parentFile ?: absoluteFile
