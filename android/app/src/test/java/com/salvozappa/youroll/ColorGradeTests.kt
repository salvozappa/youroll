// YouRoll JVM tests for colour grading and the cover picker: the assertions of
// ../Tests/VideoToolsTests.swift `colorGradeTests` and `coverSamplingTests`
// that apply without Log footage (ColorGrading.kt, LogColor.kt, `ColorLUT` in
// VideoToolCompositor.kt, CoverFramePool.kt), plus the Color tool's regions,
// removal, persistence and export plan. The GPU twin of `ColorLUT.apply` is
// compared with exported pixels on the device (emulator QA).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random

class ColorGradeTests {
    private val identity = "TITLE \"Identity\"\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"

    /** Swift's tests feed linear working-space values; `ColorLUT.apply` takes and returns encoded picture values. */
    private fun grade(settings: ColorSettings, r: Double, g: Double, b: Double, cube: CubeLUT? = null): DoubleArray =
        ColorLUT.apply(settings, doubleArrayOf(ColorLUT.encoded(r), ColorLUT.encoded(g), ColorLUT.encoded(b)), cube).map { ColorLUT.linear(it) }.toDoubleArray()

    @Test fun cubeParsingAndLookup() {
        val cube = CubeLUT(identity.toByteArray())
        require(cube.dimension == 2 && cube.rgba.size == 8 * 4, "CUBE lattice and channel count")
        require(cube.title == "Identity", "TITLE is read")
        val copied = cube.lookup(doubleArrayOf(0.18, 0.4, 0.7))
        require(abs(copied[0] - 0.18) < 0.002 && abs(copied[2] - 0.7) < 0.002, "Identity LUT preserves channel order")
        val domain = CubeLUT(("DOMAIN_MIN -1 -1 -1\nDOMAIN_MAX 1 1 1\n" + identity).toByteArray())
        require(abs(domain.lookup(doubleArrayOf(0.0, 0.0, 0.0))[0] - 0.5) < 0.002, "Non-default CUBE domains are respected")
        val curve = CubeLUT("LUT_1D_SIZE 3\n0 0 0\n0.25 0.5 0.75\n1 1 1\n".toByteArray())
        val curvePixel = curve.lookup(doubleArrayOf(0.5, 0.5, 0.5))
        require(abs(curvePixel[0] - 0.25) < 0.002 && abs(curvePixel[2] - 0.75) < 0.002, "Standalone 1D LUT preserves independent channels")
        for (invalid in listOf("LUT_3D_SIZE 999999\n", identity + "0 0 0\n", identity.replace("1 1 1", "nan 1 1"), "LUT_1D_SIZE 2\n" + identity,
                "DOMAIN_MIN 1 1 1\nDOMAIN_MAX 1 1 1\n" + identity, "LUT_3D_SIZE 2\n0 0 0\n")) {
            assertThrowing<LUTError>("Rejects malformed LUT: $invalid") { CubeLUT(invalid.toByteArray()) }
        }
        // Red is the fastest-changing axis: a red/blue swap reads back swapped.
        val swap = buildString {
            append("LUT_3D_SIZE 3\n")
            for (b in 0..2) for (g in 0..2) for (r in 0..2) append("${b / 2.0} ${g / 2.0} ${r / 2.0}\n")
        }
        val swapped = CubeLUT(swap.toByteArray()).lookup(doubleArrayOf(0.9, 0.3, 0.1))
        require(abs(swapped[0] - 0.1) < 1e-6 && abs(swapped[1] - 0.3) < 1e-6 && abs(swapped[2] - 0.9) < 1e-6, "Red-fastest ordering")
    }

    @Test fun builtInLooksAndBalance() {
        val folder = Files.createTempDirectory("color-grade").toFile()
        val external = File(folder, "external.cube").apply { writeText(identity) }
        val lut = LUTAssets.ingest(external.readBytes(), "external", folder)
        external.delete()
        require(lut.filename.startsWith("effect-lut-") && lut.name == "Identity", "Imports are hash-named and keep the LUT title")
        val cube = LUTAssets.load(lut, folder)
        val source = doubleArrayOf(0.18, 0.4, 0.7)
        val identityLook = grade(ColorSettings(imported = lut), source[0], source[1], source[2], cube)
        require(identityLook.indices.all { abs(identityLook[it] - source[it]) < 0.003 }, "Rec709 import round-trips through explicit LUT color space")
        val warm = grade(ColorSettings(look = ColorLook.goldenHour), source[0], source[1], source[2])
        require(abs(warm[0] - 0.18) > 0.005, "Creative looks visibly change the image")
        val balance = grade(ColorSettings(look = ColorLook.warm, amount = 0.0, exposure = 1.0), source[0], source[1], source[2])
        require(abs(balance[0] - 0.36) < 0.003, "Look zero retains exposure adjustment")
        require(!ColorSettings(amount = 0.0, imported = lut).isIdentity, "Zero-strength LUT remains available to re-edit")
        require(ColorSettings().isIdentity && ColorSettings(look = ColorLook.natural, amount = 0.3).isIdentity, "An empty grade is removed")
        // Natural at full strength is the identity, and every look stays within range.
        val natural = ColorLUT.apply(ColorSettings(look = ColorLook.natural), doubleArrayOf(0.2, 0.5, 0.9))
        require(abs(natural[0] - 0.2) < 1e-9 && abs(natural[2] - 0.9) < 1e-9, "Natural leaves the picture untouched")
        for (look in ColorLook.entries) {
            val out = ColorLUT.apply(ColorSettings(look = look), doubleArrayOf(0.3, 0.6, 0.2))
            require(out.all { it.isFinite() && it in 0.0..1.0 }, "${look.title} stays in range")
        }
        val mono = ColorLUT.apply(ColorSettings(look = ColorLook.mono), doubleArrayOf(0.9, 0.1, 0.4))
        require(abs(mono[0] - mono[1]) < 1e-6 && abs(mono[1] - mono[2]) < 1e-6, "Mono is neutral")
        // Strength mixes identity and look in linear light.
        val half = grade(ColorSettings(look = ColorLook.mono, amount = 0.5), 0.6, 0.1, 0.1)
        val full = grade(ColorSettings(look = ColorLook.mono), 0.6, 0.1, 0.1)
        require(abs(half[0] - (0.6 + full[0]) / 2) < 0.002, "Strength blends the original and graded picture")
        // CITemperatureAndTint semantics: warmth warms, tint adds magenta; neutral stays neutral at zero.
        val warmer = grade(ColorSettings(warmth = 1.0), 0.5, 0.5, 0.5)
        require(warmer[0] > 0.52 && warmer[2] < 0.48, "Positive warmth warms a neutral grey")
        val magenta = grade(ColorSettings(tint = 1.0), 0.5, 0.5, 0.5)
        require(magenta[1] < magenta[0] && magenta[1] < magenta[2], "Positive tint adds magenta")
        val none = ColorLUT.balanceMatrix(0.0, 0.0)
        require((0..2).all { r -> (0..2).all { c -> abs(none[r][c] - if (r == c) 1.0 else 0.0) < 1e-3 } }, "Zero balance is (nearly) the identity")
        folder.deleteRecursively()
    }

    @Test fun logCurvesAndHighlights() {
        for (value in listOf(-0.05, 0.0, 0.005, 0.01, 0.18, 1.0, 8.0)) {
            require(abs(LogColor.decodeValue(LogColor.encodeValue(value)) - value) < 0.00001, "Apple Log curve reference round-trip")
        }
        // Distinct, recoverable source highlights must survive the viewing shoulder.
        val bright = LogColor.display(doubleArrayOf(4.0, 4.0, 4.0))[0]
        val brighter = LogColor.display(doubleArrayOf(8.0, 8.0, 8.0))[0]
        require(brighter - bright > 0.01, "Log viewing preserves bright highlight separation")
        require(abs(LogColor.display(doubleArrayOf(0.18, 0.18, 0.18))[0] - 0.18) < 0.0001, "Log viewing preserves middle gray")
        for (amount in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
            var previous = -1.0
            for (index in 0..256) {
                val value = index / 16.0
                val adjusted = LogColor.highlights(doubleArrayOf(value, value, value), amount)[0]
                require(adjusted.isFinite() && adjusted > previous, "Highlights stay monotonic and finite over extended scene range")
                previous = adjusted
            }
            for (value in listOf(0.0, 0.02, 0.18, 0.3, 0.35)) {
                require(abs(LogColor.highlights(doubleArrayOf(value, value, value), amount)[0] - value) < 0.0001, "Highlights preserve shadows and midtones at every strength")
            }
        }
        val unchanged = grade(ColorSettings(highlights = -1.0), 0.18, 0.18, 0.18)[0]
        require(abs(unchanged - 0.18) < 0.001, "Recovery preserves source middle gray through the complete grade")
        val lowered = grade(ColorSettings(highlights = -1.0), 0.9, 0.9, 0.9)[0]
        require(lowered < 0.85, "Negative highlights lower bright tones")
        val zeroStrength = grade(ColorSettings(look = ColorLook.warm, amount = 0.0, highlights = -1.0), 0.9, 0.9, 0.9)[0]
        require(abs(zeroStrength - lowered) < 0.0001, "Highlight recovery remains active at zero creative strength")
        require(!ColorSettings(highlights = -0.5).isIdentity && ColorSettings(highlights = -0.5).valid, "Highlights-only grade remains editable")
        require(!ColorSettings(highlights = Double.NaN).valid && !ColorSettings(highlights = 2.0).valid, "Invalid highlight settings rejected")
        require(!ColorSettings(localRecovery = 0.5).isIdentity && ColorSettings(localRecovery = 0.5).valid, "Recovery alone is a persistent grade")
        require(!ColorSettings(localRecovery = 1.01).valid && !ColorSettings(localRecovery = Double.NaN).valid, "Out-of-range recovery is rejected")
        // Recovery is a Log-only adjustment: on Android sources it is kept but has no effect.
        val kept = grade(ColorSettings(localRecovery = 1.0), 0.9, 0.5, 0.2)
        require(abs(kept[0] - 0.9) < 0.002 && abs(kept[2] - 0.2) < 0.002, "Local recovery does not touch display-referred footage")
        for (profile in listOf(LUTInput.appleLog, LUTInput.appleLog2)) {
            val scene = doubleArrayOf(0.18, 0.5, 2.0)
            val decoded = LogColor.decode(LogColor.encode(scene, profile), profile)
            require(decoded.indices.all { abs(decoded[it] - scene[it]) < 0.006 }, "Log GPU curve and gamut round-trip")
        }
    }

    @Test fun persistenceAndLibrary() {
        val folder = Files.createTempDirectory("color-persist").toFile()
        val lut = LUTAssets.ingest(identity.toByteArray(), "external", folder)
        val project = Project("LUT persistence").also { it.append(SourceMedia(filename = "source.mp4", duration = 4.0)) }
        val edit = project.edit
        edit.clips = edit.clips.map { it.copy(color = ColorSettings(imported = lut, exposure = 0.5, highlights = -0.6, localRecovery = 0.65)) }
        project.commit(edit); project.undo(); project.validate()
        require(lut.filename in project.effectFiles, "Undo retains immutable LUT payload for redo/archive")
        val reopened = Project.decode(Project.encode(project))
        require(lut.filename in reopened.effectFiles, "LUT ownership survives project reopening")
        reopened.redo()
        val color = reopened.edit.clips[0].color
        require(color?.exposure == 0.5 && color.highlights == -0.6 && color.localRecovery == 0.65 && color.imported == lut, "Grade survives reopen and redo")
        val grade = SavedGrade(name = "Recovered highlights", settings = color!!)
        val decoded = SwiftJSON.decodeFromString(SavedGrade.serializer(), SwiftJSON.encodeToString(SavedGrade.serializer(), grade))
        require(decoded.settings.highlights == -0.6 && decoded.settings.localRecovery == 0.65, "Named grades retain highlights and local recovery")
        require(!lut.copy(filename = "../outside.cube").valid, "LUT references cannot escape project folder")
        // Swift Codable keys: look raw values, the LUT reference and its input.
        val json = SwiftJSON.encodeToString(ColorSettings.serializer(), ColorSettings(look = ColorLook.goldenHour, amount = 0.5, imported = lut))
        require(json.contains("\"look\":\"goldenHour\"") && json.contains("\"amount\":0.5") && json.contains("\"input\":\"rec709\"") &&
            !json.contains("lutAssets"), "ColorSettings encodes the Swift keys")
        // `lutAssets` includes the endpoints of an animated grade (passed through).
        val motion = SwiftJSON.parseToJsonElement("""{"a":{"values":{},"lut":{"filename":"effect-lut-a.cube","name":"A","input":"appleLog"}},"b":{"values":{}},"duration":1}""")
        require(ColorSettings(motion = motion).lutAssets.map { it.filename } == listOf("effect-lut-a.cube"), "Animated grades own their endpoint LUTs")
        // An invalid grade is rejected by validation.
        val bad = project.edit
        bad.clips = bad.clips.map { it.copy(color = ColorSettings(exposure = 9.0)) }
        project.commit(bad)
        assertInvalidDocument("Out-of-range exposure is rejected") { project.validate() }
        folder.deleteRecursively()
    }

    @Test fun regionsRemovalAndExportPlan() {
        val project = Project("Color tool").also { it.append(SourceMedia(filename = "source.mp4", duration = 6.0)) }
        val (scoped, ids) = TimelineEditing.scoped(project.edit, 1.0..3.0)
        val grade = ColorSettings(look = ColorLook.cinema, amount = 0.7)
        scoped.clips = scoped.clips.map { if (it.id in ids) it.copy(color = grade) else it }
        val regions = scoped.toolRegions.filter { it.tool == VideoTool.color }
        require(regions.size == 1 && regions[0].label == "Cinema" && abs(regions[0].range.start - 1.0) < 1e-6 && abs(regions[0].range.endInclusive - 3.0) < 1e-6,
            "A graded range is one Color region labelled with its look")
        require(ColorSettings(exposure = 0.5).title == "Color balance" && ColorSettings(look = ColorLook.ink).title == "Ink", "Grade titles follow Swift")
        val removed = scoped.removingTool(scoped.request(regions[0]))
        require(removed.clips.none { it.color != null }, "Remove clears the grade")
        val plan = ExportPlan.make(scoped)
        val graded = plan.video.filterIsInstance<ExportPlan.Piece.Clip>().filter { it.color != null }
        require(graded.size == 1 && graded[0].color == grade && abs(graded[0].start.seconds - 1.0) < 1e-6, "The export grades exactly the range")
        val animated = scoped.copy()
        animated.clips = animated.clips.map { if (it.id in ids) it.copy(color = grade.copy(motion = SwiftJSON.parseToJsonElement("{\"duration\":1}"))) else it }
        assertThrowing<ExportPlan.Unsupported>("Animated grades are refused") { ExportPlan.make(animated) }
        // Navigation: a grade is a change at its clip.
        require(TimelineNavigation(scoped).changes.any { abs(it - 1.0) < 1e-6 }, "A graded clip is a change point")
    }

    @Test fun lutTextureLayout() {
        val texture = ColorLUT.texture(ColorSettings(look = ColorLook.mono), null)!!
        require(texture.size == 33 && texture.columns == 6 && texture.width == 6 * 33 && texture.height == 6 * 33, "Cube slices in a 6×6 grid")
        // The last slice (blue = 1) starts at column 32 % 6, row 32 / 6.
        val x = (32 % 6) * 33 + 32
        val y = (32 / 6) * 33 + 32
        val white = texture.pixels[(y * texture.width + x) * 4].toInt() and 255
        require(white == 255, "White maps to white in Mono")
        val folder = Files.createTempDirectory("color-texture").toFile()
        val curve = LUTAssets.ingest("LUT_1D_SIZE 3\n0 0 0\n0.25 0.5 0.75\n1 1 1\n".toByteArray(), "curve", folder)
        val curveTexture = ColorLUT.texture(ColorSettings(imported = curve), LUTAssets.load(curve, folder))!!
        require(curveTexture.curve && curveTexture.size == 3 && (curveTexture.pixels[4 + 1].toInt() and 255) == 128, "1D curves are rows of samples")
        val parameters = ColorLUT.parameters(ColorSettings(imported = curve), LUTAssets.load(curve, folder))
        require(parameters.lutKind == 2 && !parameters.extrapolate, "Imported curves are clamped, not extrapolated")
        require(ColorLUT.parameters(ColorSettings(look = ColorLook.natural, exposure = 1.0), null).lutKind == 0, "Natural needs no LUT")
        folder.deleteRecursively()
    }

    @Test fun coverSampling() {
        val footage = listOf(iv(0.0, 1.0), iv(0.5, 2.0), iv(20.0, 21.0))
        val pool = CoverFramePool(footage, listOf(iv(1.0, 2.0), iv(12.0, 20.5)), Random(7))
        require(pool.count == 45, "Overlapping video layers count once and gaps are skipped")
        var previous = emptyList<Int>()
        repeat(100) {
            val picks = pool.picks(previous)
            require(picks.size == 9 && picks.zipWithNext().all { (a, b) -> a < b }, "Nine ordered cover choices")
            require(previous.zip(picks).all { (a, b) -> a != b }, "Update replaces every choice with an alternative")
            for ((band, pick) in picks.withIndex()) {
                val time = pool.time(pick).seconds
                require(pick in band * 5 until (band + 1) * 5, "Sample the whole available span")
                require((time >= 1 && time < 2) || (time >= 20 && time < 20.5), "Only sample selected visible video, before the end boundary")
            }
            previous = picks
        }
        val short = CoverFramePool(footage, listOf(iv(0.0, 0.2)))
        require(short.picks(emptyList()) == (0 until 6).toList(), "Six-frame range shows six distinct frames")
        val subframe = CoverFramePool(footage, listOf(iv(0.040, 0.045)))
        require(subframe.count == 1 && subframe.time(0) == EditTime.of(0.040), "Subframe selection stays inside its range")
        val empty = CoverFramePool(footage, listOf(iv(5.0, 7.0)))
        require(empty.picks(emptyList()).isEmpty(), "A gap has no cover candidates")
        require(CoverJPEG.filename("My/clip: take\u0001", EditTime.of(65.5)) == "My-clip- take - " + editorTime(65.5).replace(":", "-") + ".jpg",
            "JPEG names drop path and control characters")
        require(CoverJPEG.filename("", EditTime.zero).startsWith("YouRoll - "), "An untitled project uses the app name")
    }
}
