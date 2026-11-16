// YouRoll JVM tests for the video effect model, catalogue, math and timing.
// Ports the assertions of ../Tests/VideoEffectModelTests.swift (except the live
// recorder's dolly follower, live budget and capture zoom history, which are not
// ported), the timing part of ../Tests/EffectAnimationChecks.swift, and the
// model/timing assertions of ../Tests/VideoEffectIntegrationTests.swift (freeze,
// split hold, partial and animated speed, scoped mute, media validation).
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

class VideoEffectModelTests {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    private fun reopen(project: Project): Project {
        val disk = ProjectDisk(File(temporary.root, "projects"))
        disk.save(project)
        return disk.read(File(disk.folder(project.id), "project.json"))
    }
    private fun roundTrip(effect: VideoEffectSettings): VideoEffectSettings =
        SwiftJSON.decodeFromString(VideoEffectSettings.serializer(), SwiftJSON.encodeToString(VideoEffectSettings.serializer(), effect))

    private fun parameterIndex(effect: VideoEffectSettings, id: String) = effect.parameters.indexOfFirst { it.id == id }
    private fun VideoEffectSettings.keyed(id: String, time: Double, value: EffectValue) = updatingParameter(id) { it.settingKeyframe(time, value) }
    private fun n(value: Double) = EffectValue.number(value)

    @Test fun catalogueAndRoundTrips() {
        require(VideoEffectCatalog.all.size == VideoEffectID.entries.size, "Every effect has a catalog entry")
        for (id in VideoEffectID.entries) {
            val effect = VideoEffectSettings(id)
            require(effect.valid, "Valid defaults: $id")
            val copy = roundTrip(effect)
            require(copy == effect && copy.valid, "Typed parameter round trip: $id")
        }
        require(VideoEffectID.entries.map { it.shaderIndex }.toSet() == (0..24).toSet() - 10, "Unique shader effect IDs without the removed rotation shader")
        require(ColorCorrectionControls.display("tint", -0.004) == "0" && ColorCorrectionControls.display("hue", -0.2) == "0°",
            "Interpolated near-neutral values never display negative zero")
    }

    @Test fun swiftJsonShapes() {
        val effect = VideoEffectSettings(VideoEffectID.silentTint, 1.0, 2.0)
        val json = VideoEffectSettings.json(effect) as JsonObject
        require(json.keys.containsAll(listOf("id", "kind", "enabled", "version", "start", "end", "parameters", "algorithm")), "Swift synthesized keys")
        require("motion" !in json && "palette" !in json, "nil optionals are omitted")
        val intensity = (json["parameters"] as JsonArray)[0] as JsonObject
        assertEquals("""{"number":{"_0":1.0}}""", intensity["value"].toString())
        assertEquals("""{"slider":{"min":0.0,"max":1.0,"defaultValue":1.0,"step":0.01}}""", intensity["kind"].toString())
        require(EffectValue.of(SwiftJSON.parseToJsonElement("""{"media":{}}""")) == EffectValue.media(null), "A nil media value has no _0")
        require(EffectValue.media(null).json().toString() == """{"media":{}}""", "and is written without one")
        require(EffectValue.of(SwiftJSON.parseToJsonElement("""{"choice":{"_0":"Sepia"}}""")) == EffectValue.choice("Sepia"), "Choices")
        require(EffectValue.of(SwiftJSON.parseToJsonElement("""{"color":{"_0":{"red":1,"green":0.5,"blue":0}}}""")) == EffectValue.color(EffectRGB(1.0, 0.5, 0.0)), "Colors")
    }

    @Test fun strobePhase() {
        var strobe = VideoEffectSettings(VideoEffectID.negativeStrobe, 3.0, 9.0).setting("frequency", n(15.0))
        for (frame in listOf(0, 1, 2, 25, 15, 4, 3)) {
            require(strobe.strobeShowsNegative(3 + frame / 30.0, 30.0) == (frame % 2 != 0), "Alternating frames stay in phase after backward seeks")
        }
        strobe = strobe.setting("timedBurst", EffectValue.toggle(true)).setting("burstLength", n(0.1))
        require(!strobe.strobeShowsNegative(3.1, 30.0), "Timed burst ends exactly at its boundary")
        strobe = strobe.copy(phaseOffset = 0.1)
        require(!strobe.strobeShowsNegative(3.04, 30.0), "Later clips do not restart a timed burst")
        strobe = strobe.copy(phaseOffset = null).setting("timedBurst", EffectValue.toggle(false))
        strobe = strobe.copy(strobeTimeMap = listOf(StrobeTimePoint(3.0, 0.0), StrobeTimePoint(5.0, 1.0)))
        val fast = roundTrip(strobe)
        require(fast.valid && fast.strobeShowsNegative(3 + 2.0 / 30, 30.0), "Two-times footage uses playback seconds after saving")
        strobe = strobe.copy(strobeTimeMap = null).keyed("frequency", 3.0, n(4.0)).keyed("frequency", 3.2, n(8.0))
        require(strobe.strobeShowsNegative(3.21, 30.0) && !strobe.strobeShowsNegative(3.24, 30.0), "Rate changes preserve accumulated phase")
    }

    @Test fun kaleidoscopeAnimationAndUpgrades() {
        var split = VideoEffectSettings(VideoEffectID.kaleidoscope).keyed("splitAngle", 0.0, n(170.0)).keyed("splitAngle", 1.0, n(-170.0))
        require(split.number("splitAngle", 0.5) == -180.0, "Animating across the angle boundary takes the short arc")
        split = split.keyed("divisions", 0.0, n(2.0)).keyed("divisions", 1.0, n(9.0))
        require(split.number("divisions", 0.99) == 2.0 && split.number("divisions", 1.0) == 9.0, "Animated divisions change in whole wedges")
        val previousSplit = split.copy(parameters = split.parameters.filterNot { it.id in setOf("sourcePanX", "sourcePanY", "sourceAngle") })
        val upgraded = roundTrip(previousSplit)
        require(upgraded == split && upgraded.valid, "Existing Kaleidoscope edits reopen with neutral source pan and rotation")
        split = split.keyed("sourcePanX", 0.0, n(-0.4)).keyed("sourcePanX", 1.0, n(0.8))
        val oldPanned = split.copy(parameters = split.parameters.filterNot { it.id == "sourceAngle" })
        require(roundTrip(oldPanned) == split, "An existing panned take keeps its framing with zero source rotation")
        split = split.keyed("sourceAngle", 0.0, n(-160.0)).keyed("sourceAngle", 1.0, n(140.0))
        val saved = roundTrip(split)
        require(saved.number("sourceAngle", 0.5) == 170.0 && saved.number("splitAngle", 0.5) == -180.0,
            "Independent source and split rotations animate along their own short arcs")
        require(abs(saved.number("sourcePanX", 0.5) - 0.2) < 1e-9 && saved.number("centerX", 0.5) == 0.5, "Source pan animates independently of the split after reopening")
        val size = CGSize(320.0, 180.0); val pivot = CGPoint(0.42, 0.63)
        for (count in 2..9) {
            val span = 2 * PI / count; val axis = -53.0 * PI / 180
            for (sector in 0 until count) {
                val theta = axis + (sector + 0.27) * span
                val start = CGPoint(pivot.x + 40 * cos(theta) / size.width, pivot.y + 40 * sin(theta) / size.height)
                val t = kaleidoscopeDragTransform(start, pivot, size, 37.0, count, 73.0)
                val mirrored = count % 2 == 0 && sector % 2 != 0
                val sourceAngle = axis + (if (mirrored) 0.73 else 0.27) * span - 73 * PI / 180
                fun apply(x: Double, y: Double) = CGPoint(t.a * x + t.c * y, t.b * x + t.d * y)
                val radial = apply(cos(theta), sin(theta)); val tangent = apply(-sin(theta), cos(theta))
                val sign = if (mirrored) -1.0 else 1.0
                require(hypot(radial.x - cos(sourceAngle), radial.y - sin(sourceAngle)) < 1e-9 &&
                    hypot(tangent.x + sign * sin(sourceAngle), tangent.y - sign * cos(sourceAngle)) < 1e-9,
                    "Dragging the touched copy follows the finger in sector $sector of $count")
            }
        }
    }

    @Test fun correctionOrderAndUnknownEffects() {
        val correction = VideoEffectSettings(VideoEffectID.colorCorrection)
        require(correction.parameters.all { it.animatable }, "Every correction control supports keyframes")
        val order = VideoEffectSettings.ordered(listOf(VideoEffectSettings(VideoEffectID.silentTint), correction, VideoEffectSettings(VideoEffectID.noir), VideoEffectSettings(VideoEffectID.chromaKey)))
        require(order.map { it.kind } == listOf(VideoEffectID.chromaKey, VideoEffectID.colorCorrection, VideoEffectID.silentTint, VideoEffectID.noir), "Correction precedes looks without changing their relative order")
        require((ColorCorrectionControls.light + ColorCorrectionControls.advanced + ColorCorrectionControls.color).toSet() == correction.kind.descriptor.parameters.map { it.id }.toSet(),
            "Every correction knob appears exactly once in its inspector")
        val animated = correction.keyed("exposure", 0.0, n(-1.0)).keyed("exposure", 2.0, n(1.0))
        val copy = roundTrip(animated)
        require(copy.number("exposure", 1.0) == 0.0 && copy == animated, "Correction animation survives save/reopen with source-time interpolation")
        val known = VideoEffectSettings(VideoEffectID.silentTint)
        val mixed = JsonArray(listOf(JsonObject(mapOf("kind" to JsonPrimitive("dutchAngle"))), VideoEffectSettings.json(known),
            JsonObject(mapOf("kind" to JsonPrimitive("futureEffect"), "parameters" to JsonPrimitive("unrecognized layout")))))
        require(SupportedVideoEffects.values(mixed) == listOf(known), "Unknown effects are skipped without discarding recognized neighbors")
        val clip = SwiftJSON.decodeFromString(TimelineClip.serializer(),
            """{"id":"${newUUID().uuidString}","sourceID":"${newUUID().uuidString}","start":0,"end":1,"videoEffects":$mixed}""")
        require(clip.videoEffects == listOf(known), "Synthesized project-field decoding skips unknown effects")
        val empty = SwiftJSON.decodeFromString(TimelineClip.serializer(),
            """{"id":"${newUUID().uuidString}","sourceID":"${newUUID().uuidString}","start":0,"end":1,"videoEffects":[{"kind":"futureEffect"}]}""")
        require(empty.videoEffects?.isEmpty() == true, "An entirely unknown stack becomes empty")
        for (kind in VideoEffectID.entries) {
            val candidate = VideoEffectSettings(kind).copy(algorithm = 2)
            val supported = kind == VideoEffectID.colorIsolation || kind == VideoEffectID.chromaKey
            require(candidate.valid == supported && candidate.usesTemporalMatte == supported, "Temporal matte version is accepted only by the techniques that implement it")
            if (supported) {
                val c = roundTrip(candidate)
                require(c.algorithm == 2 && c.valid && c.usesModernPersonMask && !c.usesAutoMaskStream, "Temporal recipes preserve their version and raw-person-mask routing")
            }
        }
    }

    @Test fun temporalWindows() {
        for (kind in VideoEffectMatteKind.entries) {
            for (fps in listOf(24.0, 30.0, 60.0, 240.0)) {
                val times = (0 until 100).map { it / fps }
                val window = VideoEffectTemporalWindow.make(times, times[60], 0.0, kind)!!
                require(window.indices.size == kind.capacity && abs(window.weights.sum() - 1) < 1e-12 && window.tailBound < 0.001, "Temporal matte windows bound their EMA tail at every source rate")
                val raw = window.indices.map { ((it * 17) % 29) / 28.0 }
                val recursive = raw.drop(1).fold(raw[0]) { acc, v -> acc * kind.prior + v * (1 - kind.prior) }
                require(abs(raw.zip(window.weights).sumOf { it.first * it.second } - recursive) < 1e-12, "Finite-window weights equal an EMA initialized at the oldest source frame")
                val beginning = VideoEffectTemporalWindow.make(times, times[61], times[60] + 0.001, kind)!!
                require(beginning.indices == listOf(60, 61) && beginning.tailBound == 0.0, "A mid-frame effect starts on the frame already displayed")
                val cut = VideoEffectTemporalWindow.make(times, times[61], 0.0, kind, setOf(60))!!
                require(cut.indices == listOf(60, 61) && cut.tailBound == 0.0, "An internal scene cut closes the temporal window")
            }
            val gap = VideoEffectTemporalWindow.make(listOf(0.0, 0.03, 0.08, 0.6, 0.64), 0.64, 0.0, kind)!!
            require(gap.indices == listOf(3, 4) && gap.tailBound == 0.0, "A capture discontinuity resets the matte history")
        }
    }

    @Test fun libraryAndVisibility() {
        val sections = VideoEffectLibrarySection.entries
        require(sections == listOf(VideoEffectLibrarySection.colorStyle, VideoEffectLibrarySection.cameraFrame, VideoEffectLibrarySection.time, VideoEffectLibrarySection.background),
            "The library opens on the four effect categories")
        for (live in listOf(false, true)) {
            val available = sections.flatMap { s -> s.effects(live).map { it.id } }
            val expected = VideoEffectID.entries.filter { !live || it.descriptor.mode != EffectMode.edit }
            require(available.size == expected.size && available.toSet() == expected.toSet(), "Every available individual effect appears exactly once")
        }
        val tint = VideoEffectSettings(VideoEffectID.silentTint).setting("tint", EffectValue.choice("Night Blue"))
        val stack = listOf(VideoEffectSettings(VideoEffectID.expressionism), tint, VideoEffectSettings(VideoEffectID.sixteenMM), VideoEffectSettings(VideoEffectID.undercranking))
        require(SupportedVideoEffects.values(JsonArray(stack.map { VideoEffectSettings.json(it) })) == stack, "Saved component effects retain their order, identities and settings")
        for (live in listOf(false, true)) {
            fun visible(id: String, e: VideoEffectSettings) = VideoEffectParameterVisibility.visible(e.parameters.first { it.id == id }, e, live = live)
            val key = VideoEffectSettings(VideoEffectID.chromaKey)
            require(key.parameters.filter { VideoEffectParameterVisibility.visible(it, key, live = live) }.none { it.id in setOf("fit", "backgroundX", "backgroundY", "backgroundZoom", "media", "matchStrength") },
                "Solid backgrounds hide irrelevant framing and color-match controls in both inspectors")
            val isolation = VideoEffectSettings(VideoEffectID.colorIsolation).setting("mode", EffectValue.choice("The subject"))
            require(isolation.parameters.filter { VideoEffectParameterVisibility.visible(it, isolation, live = live) }.none { it.id in setOf("color", "range") },
                "Subject isolation hides color selection in both inspectors")
            var k = key
            require(!k.toggle("personHint") && !visible("screenColor", k), "New keys do not imply automatic person cutout or show an ignored manual color")
            k = k.setting("autoScreen", EffectValue.toggle(false))
            require(visible("screenColor", k) && !visible("screenType", k), "Manual key infers screen type from a supported green/blue sample")
            k = k.setting("cleanPlate", EffectValue.media("plate.png"))
            require(!visible("screenColor", k) && !visible("autoScreen", k), "A clean plate replaces the color reference")
            var bg = VideoEffectSettings(VideoEffectID.autoBackground)
            require(!visible("backgroundBlur", bg) && visible("portraitBlur", bg), "Original blur has one meaningful radius control")
            bg = bg.setting("background", EffectValue.choice("Image"))
            require(visible("media", bg) && !visible("fit", bg) && !visible("backgroundBrightness", bg), "Media controls wait for a real image")
            bg = bg.setting("media", EffectValue.media("picture.png"))
            require(visible("fit", bg) && visible("backgroundBrightness", bg), "Imported backgrounds expose their controls")
            var crash = VideoEffectSettings(VideoEffectID.crashZoom)
            require(!visible("hold", crash) && !visible("targetX", crash), "A non-returning tracked zoom hides ineffective hold and manual target controls")
            crash = crash.setting("return", EffectValue.toggle(true)).setting("trackFace", EffectValue.toggle(false))
            require(visible("hold", crash) && (visible("targetX", crash) == !live), "Returning/manual zoom exposes its active controls")
            crash = crash.copy(releaseTime = 2.0)
            require(!visible("return", crash) && !visible("hold", crash), "Recorded manual return owns its timing; ignored automatic controls stay hidden")
            var quant = VideoEffectSettings(VideoEffectID.quantization)
            val palette = quant.parameters.first { it.id == "palette" }
            require("Saved" !in VideoEffectParameterVisibility.choices(palette, quant), "Saved cannot be selected before a palette exists")
            quant = quant.copy(palette = listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0)))
            require("Saved" in VideoEffectParameterVisibility.choices(palette, quant), "Saved palettes remain selectable")
        }
        require(ChromaScreen.accepts(EffectRGB(0.0, 1.0, 0.0)) && ChromaScreen.accepts(EffectRGB(0.0, 0.0, 1.0)), "Green and blue screen samples are supported")
        require(!ChromaScreen.accepts(EffectRGB(0.8, 0.75, 0.65)) && !ChromaScreen.accepts(EffectRGB(1.0, 0.0, 0.0)) && !ChromaScreen.accepts(EffectRGB(0.5, 0.5, 0.5)),
            "Beige, red and neutral screen samples cannot silently select an unsupported key")
        for (hue in listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0, 359.0)) {
            val color = IsolationHue.color(hue)
            require(IsolationHue.accepts(color) && abs(IsolationHue.degrees(color) - hue) < 0.0001, "Hue controls offer only meaningful chromatic targets")
        }
        require(!IsolationHue.accepts(EffectRGB(0.0, 0.0, 0.0)) && !IsolationHue.accepts(EffectRGB(1.0, 1.0, 1.0)) && !IsolationHue.accepts(EffectRGB(0.5, 0.5, 0.5)),
            "Neutral samples must not silently isolate red")
    }

    @Test fun keyframesAndBounds() {
        var parameter = EffectParameter("value", "Value", EffectParameterKind.slider(0.0, 1.0, 0.0, 0.01))
        parameter = parameter.settingKeyframe(1.0, n(0.0)).settingKeyframe(3.0, n(1.0))
        require(parameter.value(2.0) == n(0.5) && parameter.valid, "Continuous interpolation")
        parameter = parameter.settingKeyframe(2.0, n(99.0)); require(parameter.keyframes.size == 2, "Reject out-of-range keyframes")
        val categorical = EffectParameter("mode", "Mode", EffectParameterKind.picker(listOf("A", "B"), "A"))
            .settingKeyframe(0.0, EffectValue.choice("A")).settingKeyframe(2.0, EffectValue.choice("B"))
        require(categorical.value(1.99) == EffectValue.choice("A") && categorical.value(2.0) == EffectValue.choice("B"), "Categorical keyframes hold until their time")
        val chain = VideoEffectSettings.ordered(listOf(VideoEffectSettings(VideoEffectID.widescreen), VideoEffectSettings(VideoEffectID.sixteenMM), VideoEffectSettings(VideoEffectID.noir), VideoEffectSettings(VideoEffectID.chromaKey)))
        require(chain.map { it.kind } == listOf(VideoEffectID.chromaKey, VideoEffectID.noir, VideoEffectID.sixteenMM, VideoEffectID.widescreen), "Fixed pipeline order")
        val noir = VideoEffectSettings(VideoEffectID.noir)
        val invalid = noir.copy(parameters = noir.parameters.updated(0) { it.copy(kind = EffectParameterKind.slider(-100.0, 100.0, 0.0, 1.0)) })
        require(!invalid.valid, "Documents cannot redefine parameter bounds")
        val canonical = roundTrip(invalid)
        require(canonical.valid && canonical.parameters[0].kind == noir.parameters[0].kind, "Decoded parameter metadata comes from the installed catalogue")
        val old = VideoEffectSettings(VideoEffectID.undercranking).updatingParameter("speed") { it.copy(animatable = false) }
        val migrated = roundTrip(old)
        require(migrated.valid && migrated.parameters.first { it.id == "speed" }.animatable, "Catalogue animation upgrades preserve old projects")
    }

    @Test fun paletteMath() {
        for (rgb in listOf(Vec3(0.1, 0.4, 0.8), Vec3(1.0, 0.0, 0.0), Vec3(0.25, 0.25, 0.25))) {
            require(rgb.distance(EffectColorMath.linearFromLab(EffectColorMath.lab(rgb))) < 0.000001, "Oklab round trip")
        }
        val samples = (0 until 2304).map { i -> Vec3((i % 64) / 63.0, (i / 64) / 35.0, 0.1) }
        val a = EffectPaletteAnalysis.analyze(samples, 4, seed = 23uL); val b = EffectPaletteAnalysis.analyze(samples, 4, seed = 23uL)
        require(a == b && a.colors.size == 4, "Deterministic palette sampling")
        val flat = EffectPaletteAnalysis.analyze(List(60) { Vec3.repeating(0.25) }, 3)
        val lights = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        val colored = EffectPaletteAnalysis.analyze(lights, 3, flat)
        for (light in lights) {
            val expected = EffectColorMath.prepared(light)
            require(colored.colors.any { EffectColorMath.linear(it).distance(expected) < 0.005 }, "A neutral opening frame must not trap color clusters")
        }
        val dark = (0 until 100).map { Vec3.repeating(it / 1000.0) }
        require(EffectPaletteAnalysis.analyze(dark, 2).thresholds[0] < 0.1, "B&W threshold follows scene median, not 0.5")
        VideoEffectInputs.install(File("src/main/assets/VideoEffects/PopPalettes.json").readText())
        require(VideoEffectInputs.pop.map { it.name } == listOf("Pop 1", "Pop 2", "Pop 3"), "The bundled Pop palettes")
        val quant = VideoEffectSettings(VideoEffectID.quantization).setting("palette", EffectValue.choice("Pop 2"))
        val shown = VideoEffectInputs.displayPalette(quant, 0.0, a)!!
        require(shown.colors == VideoEffectInputs.pop[1].sampled(4) && shown.thresholds == a.thresholds, "Pop palettes keep the scene's brightness bands")
        val saved = quant.copy(palette = listOf(EffectRGB(0.0, 0.0, 0.0), EffectRGB(1.0, 0.0, 0.0), EffectRGB(1.0, 1.0, 1.0))).setting("palette", EffectValue.choice("Saved"))
        val bands = VideoEffectInputs.displayPalette(saved.setting("colors", n(3.0)), 0.0, null)!!
        require(bands.colors.size == 3 && bands.thresholds.size == 2, "Saved colors are available before any analysis")
    }

    @Test fun cadence() {
        for (fps in listOf(12.0, 16.0, 18.0, 24.0)) {
            val indices = (0 until 30).map { VideoEffectTime.heldTime(it / 30.0, fps) }.toSet()
            require(indices.size == fps.toInt(), "Exact $fps fps cadence at 30fps output")
        }
        var super8 = VideoEffectSettings(VideoEffectID.superEight, 0.0, 4.0)
        val under = VideoEffectSettings(VideoEffectID.undercranking, 0.0, 4.0).setting("speed", n(1.0))
        for (fps in listOf(30.0, 60.0)) {
            val cadence = VideoEffectCadenceClock(listOf(super8))
            var previous = -1; var held = 0.0
            for (frame in 0 until (fps * 4).toInt()) {
                val time = frame / fps; val hold = cadence.hold(time)!!
                if (hold.index != previous) { held = time; previous = hold.index }
                val selected = VideoEffectTime.firstFrame(hold.boundary, fps)
                require(abs(selected - held) < 0.00001, "Super 8 export chooses the same first source frame as live capture at $fps fps")
            }
        }
        for (fps in listOf(12.0, 16.0, 18.0)) {
            val effect = under.copy(end = 12.0).setting("fps", n(fps))
            val clock = VideoEffectCadenceClock(listOf(effect))
            for (frame in 0 until 300) {
                val nominal = frame / 30.0
                val jitter = if (frame == 0) 0.0 else (if (frame % 2 == 0) 0.00004 else -0.00004) - frame * 0.00000000033
                val live = VideoEffectTime.captureTime(nominal + jitter); val file = VideoEffectTime.captureTime(nominal)
                require(clock.hold(live)!!.index == clock.hold(file)!!.index && VideoEffectTime.frameIndex(live, 30.0) == VideoEffectTime.frameIndex(file, 30.0),
                    "Camera clock jitter cannot change held frames or grain seeds")
            }
        }
        super8 = super8.keyed("fps", 0.0, n(24.0)).keyed("fps", 1.37, n(18.0))
        val rates = (0 until 40).map { index -> val t = index.toDouble(); Triple(t / 10, (t + 1) / 10, 1 + t / 80) }
        for (effects in listOf(listOf(super8), listOf(super8, under), listOf(under, super8))) {
            val clock = VideoEffectCadenceClock(effects, rates)
            var lastIndex = -1; var lastTime = -1.0
            for (frame in 0 until 240) {
                val time = frame / 60.0; val hold = clock.hold(time)!!
                require(hold.index >= lastIndex && hold.boundary >= lastTime - 0.00001 && hold.boundary <= time + 0.00001, "Animated cadence and speed never rewind the chosen frame")
                lastIndex = hold.index; lastTime = hold.boundary
            }
        }
        val combined = VideoEffectCadenceClock(listOf(super8, under)); val single = VideoEffectCadenceClock(listOf(under))
        for (frame in 0 until 120) {
            val t = frame / 30.0
            require(abs(combined.hold(t)!!.boundary - single.hold(t)!!.boundary) < 0.00001, "The slower cadence governs a combined look independently of enabling order")
        }
        val first = VideoEffectTime.dollyTarget(1.0, 0.4, 0.2, 1.0..4.0)
        require(first == 2.0 && VideoEffectTime.dollyTarget(first, 0.4, 0.4, 1.0..4.0) == 2.0, "Dolly controller does not undo its zoom when subject height settles")
    }

    @Test fun capturedCrashRecipes() {
        var late = VideoEffectSettings(VideoEffectID.crashZoom, 3.0, 8.0).copy(releaseTime = 6.0)
        val trimmed = VideoEffectSettings.capturedRecipe(listOf(late), 4.0)
        require(trimmed.size == 1 && trimmed[0].valid && trimmed[0].end == 4.0 && trimmed[0].releaseTime == null, "Stopping mid-move retains a valid recipe")
        require(VideoEffectSettings.capturedRecipe(listOf(late), 2.0).isEmpty(), "A trigger beyond the saved take never invalidates the recording")
        late = late.copy(start = 0.0, phaseOffset = 2.0, releaseTime = -0.1)
        require(late.valid, "A carried manual return can begin before the take")
        var editable = VideoEffectSettings(VideoEffectID.crashZoom, 1.0, 2.0).copy(phaseOffset = 0.0)
            .setting("return", EffectValue.toggle(true)).setting("speed", n(0.5)).setting("hold", n(3.0))
        editable = editable.refreshingCapturedCrashEnd(8.0)
        require(abs(editable.end - 5.08) < 0.000001 && editable.valid, "Extending captured crash timing does not clip its return")
        editable = editable.setting("return", EffectValue.toggle(false)).refreshingCapturedCrashEnd(7.0)
        require(editable.end == 7.0, "Turning off automatic return holds until the next trigger or clip end")
        editable = editable.copy(releaseTime = 3.0).refreshingCapturedCrashEnd(7.0)
        require(editable.end == 3.5, "A manual return follows the edited push duration")
        val captured = editable
        editable = editable.retimingCapturedCrash(captured, 2.0)
        require(editable == captured, "Opening a split captured move preserves its saved timing and downstream analysis")
        editable = editable.setting("zoom", n(3.0)).retimingCapturedCrash(captured, 2.0)
        require(editable.end == captured.end, "Changing framing does not implicitly shorten a captured move")
        val before = editable
        editable = editable.setting("speed", n(0.3)).retimingCapturedCrash(before, 7.0)
        require(editable.end == 3.3, "An explicit timing edit refreshes a captured return")
    }

    @Test fun backgroundPlacement() {
        var placement = VideoEffectBackgroundPlacement(canvas = CGSize(1080.0, 1080.0), media = CGSize(1920.0, 1080.0), fill = true)
        placement = placement.applying(VideoEffectPlacementGesture.pan(CGPoint(0.1, 0.2)))
        require(abs(placement.x - (0.5 - 0.1 / (16.0 / 9 - 1))) < 1e-9 && placement.y == 0.5, "Fill dragging follows the finger and locks the axis with no overflow")
        placement = placement.applying(VideoEffectPlacementGesture.pan(CGPoint(100.0, -100.0)))
        require(placement.x == 0.0 && placement.y == 0.5, "Background drag clamps at image edges")
        val fit = VideoEffectBackgroundPlacement(canvas = CGSize(1080.0, 1080.0), media = CGSize(1920.0, 1080.0), fill = false).applying(VideoEffectPlacementGesture.pan(CGPoint(0.1, 0.1)))
        require(fit.x == 0.5 && abs(fit.y - (0.5 + 0.1 / (1 - 9.0 / 16))) < 1e-9, "Fit positioning moves inside the letterbox without exposing outside the canvas")
        var anchored = VideoEffectBackgroundPlacement(zoom = 2.0, canvas = CGSize(1000.0, 1000.0), media = CGSize(1000.0, 1000.0), fill = true)
        anchored = anchored.applying(VideoEffectPlacementGesture.pinch(1.5, CGPoint(0.3, 0.7), CGPoint(0.35, 0.65)))
        val imageX = (0.3 + 0.5) / 2; val imageY = (0.7 + 0.5) / 2
        require(abs(-2 * anchored.x + imageX * 3 - 0.35) < 1e-9 && abs(-2 * anchored.y + imageY * 3 - 0.65) < 1e-9, "Off-center pinch keeps the same image point under the moving fingers")
        anchored = anchored.applying(VideoEffectPlacementGesture.pinch(0.01, CGPoint(0.5, 0.5), CGPoint(0.5, 0.5)))
        require(anchored.zoom == 1.0 && anchored.movementLocked, "Background cannot shrink below Fit/Fill minimum")
        val locked = VideoEffectBackgroundPlacement(0.2, 0.3, canvas = CGSize(100.0, 100.0), media = CGSize(100.0, 100.0), fill = true).applying(VideoEffectPlacementGesture.pan(CGPoint(0.1, 0.2)))
        require(locked.x == 0.2 && locked.y == 0.3, "Locked axes preserve latent positioning values for later zoom keyframes")
    }

    @Test fun algorithmVersions() {
        for (kind in listOf(VideoEffectID.colorIsolation, VideoEffectID.chromaKey)) {
            val current = VideoEffectSettings(kind)
            require(current.algorithm == 2 && current.usesTemporalMatte, "New key and isolation recipes enable validated temporal processing")
            for (version in listOf<Int?>(null, 1)) {
                val json = (VideoEffectSettings.json(current) as JsonObject).toMutableMap()
                if (version != null) json["algorithm"] = JsonPrimitive(version) else json.remove("algorithm")
                val restored = VideoEffectSettings.of(JsonObject(json))
                require(restored.algorithm == version && !restored.usesTemporalMatte && restored.valid, "Existing recipes keep their original spatial processing after reopening")
            }
        }
        val modern = VideoEffectSettings(VideoEffectID.autoBackground)
        val legacy = VideoEffectSettings.of(JsonObject((VideoEffectSettings.json(modern) as JsonObject) - "algorithm"))
        require(modern.algorithm == 1 && legacy.algorithm == null && legacy.valid, "Absent algorithm version preserves legacy recipes instead of silently enabling new processing")
    }

    // ---- EffectAnimationChecks.swift (timing; the rendered comparisons are device tests) ----

    @Test fun effectTimingCurves() {
        var timing = EffectTiming(EffectCurve.riseHoldReturn, 1.0, 1.0)
        val frame = 1.0 / 30
        fun f(t: Double) = timing.fraction(t, 5.0, frame)
        require(f(0.0) == 0.0 && f(0.5) == 0.5 && f(1.0) == 1.0 && f(4.0) == 1.0 && f(4.5) < 0.5 && f(5 - frame) == 0.0, "One second to B, three at B, one back to A")
        timing = timing.copy(curve = EffectCurve.riseAndHold)
        require(f(4.99) == 1.0 && timing.endpoint(EffectEndpoint.b, 5.0, frame) == 1.0, "Reach B early and hold to the end")
        for (rise in listOf(0.5, 1.0, 2.0)) {
            timing = timing.copy(rise = rise)
            require(timing.previewPosition(1.0, null, 5.0, frame) == 1.0, "Dragging the peak across an inspected frame must keep the playhead there")
            require(timing.previewPosition(1.0, EffectEndpoint.b, 5.0, frame) == rise, "Explicitly editing B follows the peak to its new frame")
        }
        for (curve in EffectCurve.entries) {
            timing = timing.copy(curve = curve)
            for (length in listOf(frame, 0.1, 1.0, 5.0, 500.0)) for (t in listOf(-0.5, 0.0, length / 8, length / 2, length - frame, length + 1)) {
                require(timing.fraction(t, length, frame) in 0.0..1.0, "Every curve stays bounded on short selections")
            }
        }
    }

    @Test fun animatedCorrectionPersistsAndSplits() {
        val project = Project("Animated grade").also { it.append(SourceMedia(filename = "source.mp4", duration = 4.0)) }
        val clip = project.edit.clips[0]
        val correction = VideoEffectSettings(VideoEffectID.colorCorrection, 0.0, 4.0)
        val end = correction.setting("exposure", n(-0.8))
        val motion = EffectAnimation.of(correction.correctionPose(0.0), clip.duration).copy(b = end.correctionPose(0.0),
            timing = EffectTiming(EffectCurve.riseHoldReturn, 1.0, 1.0)).anchored(clip, 0.0)
        val animated = correction.copy(motion = motion)
        require(animated.valid, "An animated correction is valid")
        val state = project.edit; state.updateClip(0) { it.copy(videoEffects = listOf(animated)) }
        project.commit(state); project.validate()
        val decoded = reopen(project)
        decoded.validate()
        val split = TimelineEditing.split(decoded.edit, 1.5)
        require(split.clips.size == 2 && split.clips[1].videoEffects?.first()?.motion == motion, "Splitting retains source-anchored animation")
        require(abs(animated.number("exposure", 0.0)) < 1e-12 && abs(animated.number("exposure", 2.0) + 0.8) < 1e-12, "A at the start, B on the hold")
        require(abs(animated.number("exposure", 0.5) + 0.4) < 1e-9, "Halfway up the rise")
        require(!animated.copy(kind = VideoEffectID.noir).valid, "Only Color Correction animates by endpoints")
    }

    // ---- VideoEffectIntegrationTests.swift: model and timing ----

    @Test fun freezeInsertsHold() {
        val project = Project("Freeze").also { it.append(SourceMedia(filename = "source.mp4", duration = 4.0)) }
        val (frozen, id) = TimelineEditing.freezing(project.edit, 2.0)
        require(id != null && frozen.clips.size == 3 && abs(frozen.duration - 7) < 0.00001, "Freeze inserts a three-second hold without removing source footage")
        val held = frozen.clips[1]
        require(held.frozenFrame?.sourceTime == 2.0 && frozen.clips[2].start == 2.0, "Freeze and following footage share source position; no missing final section")
        require(held.videoEffects?.single()?.kind == VideoEffectID.freezeFrame && held.videoEffects!!.single().id == id, "The hold carries its Freeze Frame effect")
        val splitHold = TimelineEditing.split(frozen, 3.0)
        require(splitHold.clips.size == 4 && splitHold.duration == 7.0 && splitHold.clips[2].frozenFrame?.offset == 1.0, "Splitting a hold preserves its full duration and animation phase")
        project.commit(frozen); project.validate()
        require(held.mutesSourceAudio, "A hold with Audio Cut is silent")
        val plan = ExportPlan.make(frozen)
        require(plan.duration == EditTime.of(7.0), "The export includes the hold and the full remaining footage")
        val removed = frozen.removingTool(frozen.request(frozen.toolRegions.first { it.effectID == id }))
        require(removed.clips.size == 2 && abs(removed.duration - 4) < 1e-9, "Removing the Freeze Frame removes its hold")
        require(TimelineEditing.freezing(EditState(), 0.0).second == null, "Nothing to freeze in an empty edit")
    }

    @Test fun timeEffectsRetimeTheClip() {
        val source = newUUID()
        var step = VideoEffectSettings(VideoEffectID.stepPrinting, 0.0, 4.0).setting("speed", n(0.5))
        require(TimelineClip(sourceID = source, start = 0.0, end = 4.0, videoEffects = listOf(step)).duration == 8.0, "Slow step printing retimes the timeline at half speed")
        var crank = VideoEffectSettings(VideoEffectID.undercranking, 1.0, 3.0).setting("speed", n(1.5)).setting("mute", EffectValue.toggle(true))
        val clock = TimelineClip(sourceID = source, start = 0.0, end = 4.0, videoEffects = listOf(crank))
        require(abs(clock.duration - (2 + 2 / 1.5)) < 0.000001, "A partial time effect retimes only its covered source interval")
        require(clock.effectPlaybackSpans.size == 3 && abs(clock.sourceTime(2.0) - 2.5) < 0.000001 && abs(clock.timelineTime(2.5) - 2) < 0.000001,
            "Seeking uses the same partial-effect clock as composition")
        var s = 0.0
        while (s <= 4.0) { require(abs(clock.sourceTime(clock.timelineTime(s)) - s) < 0.000001, "Source/timeline clock is invertible"); s += 0.05 }
        val edit = EditState(listOf(clock))
        val runs = edit.timeline.video[0].mapping.runs
        require(runs.size == 3 && !runs[0].silent && runs[1].silent && !runs[2].silent, "Undercranking mute leaves source audio outside the effect untouched")
        val plan = ExportPlan.make(edit)
        require(abs(plan.duration.seconds - clock.duration) < 1.0 / 30000, "Partial speed renders the expected complete duration")
        val sound = plan.audio.single().filterIsInstance<ExportPlan.Piece.Clip>()
        require(sound.size == 2 && abs(sound[0].duration.seconds - 1) < 0.0001 && abs(sound[1].start.seconds - (1 + 2 / 1.5)) < 0.0001, "The muted stretch is a gap in the sound")
        crank = crank.keyed("speed", 1.0, n(1.0)).keyed("speed", 3.0, n(1.5))
        val animated = TimelineClip(sourceID = source, start = 0.0, end = 4.0, videoEffects = listOf(crank))
        val exact = 2 + ln(1.5) / 0.25
        require(abs(animated.duration - exact) < 0.0001 && animated.effectPlaybackSpans.size < 130, "Animated speed integrates smoothly with bounded composition segments")
        val partial = EditState(listOf(animated))
        val divided = TimelineEditing.split(partial, 1.7)
        require(divided.timeline.video[0].mapping.duration == EditTime.of(1.7) && divided.timeline.duration == partial.timeline.duration &&
            abs(divided.clips[0].duration - 1.7) <= 1.0 / EditTime.scale, "Animated speed keeps exact split ticks and source-grid timing")
        val cut = animated.copy(start = animated.sourceTime(0.7), end = animated.sourceTime(2.3))
        require(abs(cut.duration - 1.6) < 0.000001, "Trimming an animated rate preserves the same source clock")
        step = step.copy(enabled = false)
        require(TimelineClip(sourceID = source, start = 0.0, end = 4.0, videoEffects = listOf(step)).duration == 4.0, "A disabled time effect leaves the clock alone")
    }

    @Test fun mediaValidationAndRecipes() {
        val imageOnly = EffectParameterKind.media(image = true, video = false)
        require(!imageOnly.accepts(EffectValue.media("clip.mov")) && imageOnly.accepts(EffectValue.media("plate.png")), "Clean plate rejects movie media")
        var key = VideoEffectSettings(VideoEffectID.chromaKey, 0.0, 4.0)
        key = key.setting("background", EffectValue.choice("Video")).setting("media", EffectValue.media("source.mp4")).setting("background", EffectValue.choice("Image")).sanitizingBackgroundMedia(0.0)
        require(key.value("media") == EffectValue.media(null), "Switching background type clears incompatible media")
        key = key.setting("view", EffectValue.choice("Matte"))
        require(key.recordingResult.choice("view") == "Result" && key.choice("view") == "Matte", "Diagnostic views never become recorded effect recipes")
    }

    @Test fun persistenceSplitAndPaste() {
        val project = Project("Film effect persistence")
        val recorded = VideoEffectSettings(VideoEffectID.silentTint, 0.0, 4.0).let { it.copy(parameters = it.parameters.updated(0) { p -> p.settingKeyframe(0.0, n(0.3)).settingKeyframe(4.0, n(1.0)) }) }
        project.append(SourceMedia(filename = "source.mp4", duration = 4.0, videoEffects = listOf(recorded)))
        project.validate()
        require(project.edit.clips[0].videoEffects == listOf(recorded), "Recorded recipes attach to the timeline")
        val edit = TimelineEditing.split(project.edit, 2.0)
        val film = VideoEffectSettings(VideoEffectID.sixteenMM, 2.0, 4.0).setting("grain", n(0.2))
        edit.updateClip(1) { it.copy(videoEffects = it.videoEffects.orEmpty() + film) }
        val before = project.edit.duration
        project.commit(edit)
        val pasted = TimelineEditing.inserting(edit.clips, edit, 4.0)
        require(pasted.clips[2].videoEffects!![0].id != recorded.id && pasted.clips[2].videoEffects!![0].id == pasted.clips[3].videoEffects!![0].id,
            "Paste gives independent effect identity shared across copied split pieces")
        val reopened = reopen(project)
        reopened.undo(); require(reopened.edit.duration == 4.0 && reopened.edit.clips.size == 1, "Undoing effects preserves complete recording")
        reopened.redo(); require(reopened.edit == edit && reopened.sources == project.sources, "Effect values and keyframes survive reopening and redo")
        require(before == edit.duration, "Image effects do not change time")
        val regions = edit.toolRegions.filter { it.tool == VideoTool.videoEffects }
        require(regions.count { it.effectID == recorded.id } == 2 && regions.single { it.effectID == film.id }.range == 2.0..4.0, "Each effect piece is a tool region")
        val removed = edit.removingTool(edit.request(regions.single { it.effectID == film.id }))
        require(removed.clips[1].videoEffects == listOf(recorded) && removed.clips[0].videoEffects == listOf(recorded), "Removing one effect keeps the others")
        val ranged = ToolRequest(VideoTool.videoEffects, 0.0..4.0, "Project", effectID = film.id)
        require(ranged.videoPreviewRange(edit, edit.clips.map { it.id }.toSet()) == 2.0..4.0, "An effect's preview range is its region")
    }

    @Test fun exportSupport() {
        require(VideoEffectSupport.unsupported(VideoEffectSettings(VideoEffectID.noir)) == null, "Image-processing effects render")
        require(VideoEffectSupport.unsupported(VideoEffectSettings(VideoEffectID.autoBackground)) != null, "Segmentation effects are refused")
        require(VideoEffectSupport.unsupported(VideoEffectSettings(VideoEffectID.autoBackground).copy(enabled = false)) == null, "A disabled effect is not drawn")
        val crash = VideoEffectSettings(VideoEffectID.crashZoom)
        require(VideoEffectSupport.unsupported(crash) != null && VideoEffectSupport.unsupported(crash.setting("trackFace", EffectValue.toggle(false))) == null,
            "Crash Zoom renders without face tracking")
        val isolation = VideoEffectSettings(VideoEffectID.colorIsolation)
        require(VideoEffectSupport.unsupported(isolation) == null && VideoEffectSupport.unsupported(isolation.setting("mode", EffectValue.choice("The subject"))) != null,
            "Color isolation renders a colour, not the subject")
        val uniforms = MetalEffectRenderer.uniforms(VideoEffectSettings(VideoEffectID.silentTint).setting("tint", EffectValue.choice("Sepia")), 0.0)
        require(uniforms[0] == 4f && uniforms[4] == 0f && abs(uniforms[8] - 1.15f) < 1e-6, "Uniforms: choice index, then the catalogue order without intensity")
        val m = videoEffectInputMatrix(null, 1920, 1080, 1920, 1080)
        require(abs(m[0] * 960 + m[6] - 0.5f) < 1e-6 && abs(m[4] * 540 + m[7] - 0.5f) < 1e-6, "The identity framing maps pixels to uv")
        require(max(0, 1) == 1)
    }
}
