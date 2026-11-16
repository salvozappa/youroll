// Port of YouRec/ColorGrading.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// LUT inputs, imported LUT references, the strict CUBE reader, the immutable
// hash-named project payloads and the app-wide grade library. `CubeLUT.apply`
// (Core Image) becomes `CubeLUT.lookup`, the CPU reference of the GLSL lookup
// in `ColorLUT` (VideoToolCompositor.kt). The library lives in the app's files
// directory (iOS: Application Support/ColorLibrary).
@file:UseSerializers(UUIDSerializer::class)

package com.salvozappa.youroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class LUTInput(val title: String) {
    rec709("Rec.709"), appleLog("Apple Log"), appleLog2("Apple Log 2");

    val id: String get() = name
    val rawValue: String get() = name
}

@Serializable
data class ImportedLUT(
    val filename: String,
    val name: String,
    val input: LUTInput = LUTInput.rec709,
) {
    val id: String get() = filename
    val valid: Boolean
        get() = filename.startsWith("effect-lut-") && filename.endsWith(".cube") && filename.length < 160 &&
            filename == File(filename).name && !filename.contains("\\") && filename != "." && filename != ".." &&
            name.isNotEmpty() && name.length <= 120
}

class LUTError(reason: String) : Exception(reason)

/**
 * Strict, bounded CUBE reader. Red is the fastest-changing axis. Both standalone
 * 1D curves and 3D cubes are supported; combined shaper+cube files are rejected
 * explicitly rather than silently discarding their shaper. Values may exceed 1.
 */
class CubeLUT(data: ByteArray) {
    val dimension: Int
    /** 3D cube: RGBA floats, red fastest (empty for a 1D curve). */
    val rgba: FloatArray
    /** 1D curve: RGB floats per sample (null for a cube). */
    val curves: FloatArray?
    val domainMin: DoubleArray
    val domainMax: DoubleArray
    val title: String?

    init {
        if (data.size > maximumBytes) throw LUTError("Choose a UTF-8 .cube file smaller than 32 MB.")
        val text = try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw LUTError("Choose a UTF-8 .cube file smaller than 32 MB.") }
        var size: Int? = null
        var oneD = false
        var name: String? = null
        val low = doubleArrayOf(0.0, 0.0, 0.0)
        val high = doubleArrayOf(1.0, 1.0, 1.0)
        val rows = ArrayList<Float>()
        var rowCount = 0
        val seen = HashSet<String>()
        // Swift `split(whereSeparator: \.isNewline)` drops empty lines.
        for (raw in text.split('\n', '\r', '\u000B', '\u000C', '\u0085', ' ', ' ')) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val first = parts.firstOrNull() ?: continue
            if (first == "TITLE") {
                if ("TITLE" in seen) throw LUTError("The LUT contains duplicate TITLE entries.")
                name = line.drop(5).trim('"', ' ').take(120)
                seen += "TITLE"; continue
            }
            if (first == "LUT_3D_SIZE" || first == "LUT_1D_SIZE") {
                val value = parts.getOrNull(1)?.toIntOrNull()
                if (size != null || rowCount > 0 || parts.size != 2 || value == null ||
                    !(if (first == "LUT_3D_SIZE") value in 2..65 else value in 2..16384)) {
                    throw LUTError("Use a standalone 3D LUT (2–65 points) or 1D LUT (2–16,384 points). Combined shaper LUTs are not supported.")
                }
                size = value; oneD = first == "LUT_1D_SIZE"; continue
            }
            if (first == "DOMAIN_MIN" || first == "DOMAIN_MAX") {
                if (rowCount > 0 || first in seen || parts.size != 4) throw LUTError("Invalid LUT input domain.")
                val values = parts.drop(1).mapNotNull { swiftDouble(it) }
                if (values.size != 3 || !values.all { it.isFinite() && abs(it) <= 65536 }) throw LUTError("Invalid LUT input domain.")
                val target = if (first == "DOMAIN_MIN") low else high
                for (i in 0..2) target[i] = values[i]
                seen += first; continue
            }
            val expected = size?.let { if (oneD) it else it * it * it }
            if (expected == null || parts.size != 3 || rowCount >= expected) throw LUTError("Unsupported LUT header or incorrect number of color entries.")
            val values = parts.mapNotNull { swiftDouble(it)?.toFloat() }
            if (values.size != 3 || !values.all { it.isFinite() && abs(it) <= 65536 }) throw LUTError("The LUT contains an invalid color value.")
            rows += values; rowCount += 1
        }
        val n = size
        if (n == null || rowCount != (if (oneD) n else n * n * n) || !(0..2).all { high[it] > low[it] }) {
            throw LUTError("The LUT is incomplete or has an invalid input domain.")
        }
        dimension = n; domainMin = low; domainMax = high; title = name
        if (oneD) {
            curves = rows.toFloatArray(); rgba = FloatArray(0)
        } else {
            rgba = FloatArray(rowCount * 4).also { out ->
                for (i in 0 until rowCount) { out[i * 4] = rows[i * 3]; out[i * 4 + 1] = rows[i * 3 + 1]; out[i * 4 + 2] = rows[i * 3 + 2]; out[i * 4 + 3] = 1f }
            }
            curves = null
        }
    }

    /**
     * Swift `apply(to:)` on one RGB value: the domain normalisation, then
     * CIColorCurves (per channel, clamped to the curve) or CIColorCube
     * (trilinear, clamped: `inputExtrapolate: false`). The GLSL in `ColorLUT` does the same.
     */
    fun lookup(rgb: DoubleArray): DoubleArray {
        val x = DoubleArray(3) { (rgb[it] - domainMin[it]) / (domainMax[it] - domainMin[it]) }
        curves?.let { values ->
            return DoubleArray(3) { channel ->
                val f = min(1.0, max(0.0, x[channel])) * (dimension - 1)
                val i = min(dimension - 2, floor(f).toInt())
                val t = f - i
                values[i * 3 + channel] * (1 - t) + values[(i + 1) * 3 + channel] * t
            }
        }
        return ColorLUT.trilinear(rgba, dimension, x, extrapolate = false)
    }

    companion object {
        const val maximumBytes = 32 * 1024 * 1024

        /** Swift `Double(_:)`/`Float(_:)` parsing: plain decimal or exponent notation, plus nan/inf (rejected later as not finite). */
        private fun swiftDouble(text: String): Double? {
            if (!Regex("[+-]?(([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?|nan|NaN|inf|infinity|Inf)").matches(text)) return null
            return when (text.lowercase().trimStart('+', '-')) {
                "nan" -> Double.NaN
                "inf", "infinity" -> if (text.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                else -> text.toDoubleOrNull()
            }
        }
    }
}

/**
 * Immutable hash-named project payloads: editing a preset/library cannot change
 * a previous edit, redo entry or exported project archive.
 */
object LUTAssets {
    private val cache = object : LinkedHashMap<String, CubeLUT>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CubeLUT>?) = size > 8
    }

    fun load(lut: ImportedLUT, folder: File): CubeLUT {
        if (!lut.valid) throw LUTError("Invalid LUT reference.")
        val file = File(folder, lut.filename)
        synchronized(cache) { cache[file.path]?.let { return it } }
        if (!file.exists()) throw LUTError("The LUT file is missing.")
        if (file.length() > CubeLUT.maximumBytes) throw LUTError("The LUT is too large.")
        val cube = CubeLUT(file.readBytes())
        synchronized(cache) { cache[file.path] = cube }
        return cube
    }

    /** Swift `ingest(_:folder:)`: [data] read from the picked document, [fallbackName] its file name without extension. */
    fun ingest(data: ByteArray, fallbackName: String, folder: File): ImportedLUT {
        if (data.size > CubeLUT.maximumBytes) throw LUTError("Choose a .cube file smaller than 32 MB.")
        val cube = CubeLUT(data)
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val filename = "effect-lut-$hash.cube"
        folder.mkdirs()
        val target = File(folder, filename)
        if (!target.exists()) {
            val partial = File(folder, ".$filename.partial")
            partial.writeBytes(data)
            if (!partial.renameTo(target)) { partial.delete(); throw IOException("The LUT could not be saved.") }
        }
        val name = cube.title?.takeIf { it.isNotEmpty() } ?: fallbackName.take(120)
        return ImportedLUT(filename, name)
    }
}

@Serializable
data class SavedGrade(
    val id: UUID = newUUID(),
    val name: String,
    val settings: ColorSettings,
)

@Serializable
data class GradeLibrary(
    val imported: List<ImportedLUT> = emptyList(),
    val grades: List<SavedGrade> = emptyList(),
    /** Swift `Set<String>`, encoded as an array. */
    val favorites: Set<String> = emptySet(),
) {
    fun save() {
        val folder = folder ?: throw IOException("The color library is unavailable.")
        folder.mkdirs()
        val file = File(folder, "library.json")
        val partial = File(folder, "library.json.partial")
        partial.writeText(SwiftJSON.encodeToString(serializer(), this))
        if (!partial.renameTo(file)) throw IOException("The color library could not be saved.")
    }

    companion object {
        /** `ColorLibrary` in the app's files directory, set at startup ([install]). */
        @Volatile var folder: File? = null
            private set

        fun install(context: android.content.Context) { if (folder == null) folder = File(context.filesDir, "ColorLibrary") }

        fun read(): GradeLibrary = folder?.let { File(it, "library.json") }?.takeIf { it.exists() }
            ?.let { runCatching { SwiftJSON.decodeFromString(serializer(), it.readText()) }.getOrNull() } ?: GradeLibrary()

        fun copy(lut: ImportedLUT, source: File, target: File) {
            LUTAssets.load(lut, source)
            target.mkdirs()
            val destination = File(target, lut.filename)
            if (!destination.exists()) File(source, lut.filename).copyTo(destination)
        }
    }
}
