// YouRoll test helpers mirroring the Swift test idioms.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Swift `require(_:_:)`. */
fun require(condition: Boolean, message: String) = assertTrue(message, condition)

/** Swift `EditTime(seconds)`. */
fun et(seconds: Double) = EditTime.of(seconds)
fun et(seconds: Int) = EditTime.of(seconds.toDouble())
val zero = EditTime.zero

/** Swift `EditInterval(a...b)`. */
fun iv(a: Double, b: Double) = EditInterval.of(a, b)
fun iv(a: Int, b: Int) = EditInterval.of(a.toDouble(), b.toDouble())

inline fun <reified E : Throwable> assertThrowing(message: String, block: () -> Unit): E {
    try {
        block()
    } catch (error: Throwable) {
        if (error is E) return error
        throw AssertionError("$message: unexpected ${error::class.simpleName}: ${error.message}", error)
    }
    fail("$message: nothing was thrown")
    throw IllegalStateException()
}

fun assertInvalidDocument(message: String, block: () -> Unit) {
    val error = assertThrowing<ProjectError>(message, block)
    require(error.case == ProjectError.Case.invalidDocument, message)
}

fun speedJson(rate: Double): SpeedSettings = SpeedSettings(rate = rate)

// Payloads of tools that are not ported, in the shape Swift encodes them.

fun pausesJson(vararg cuts: Pair<Double, Double>): PauseSettings = PauseSettings(cuts = cuts.map { (a, b) -> SourceInterval(a, b) })

fun colorJson(look: String, amount: Double): ColorSettings = ColorSettings(look = ColorLook.valueOf(look), amount = amount)

fun cropJson(zoom: Double): CropSettings = CropSettings(zoom = zoom)

/** A title at source seconds [start]…[end] (Swift `TextOverlay(text:start:end:)`). */
fun textOverlay(text: String, start: Double, end: Double) = TextOverlay(text = text, start = start, end = end)

fun jsonArray(vararg values: JsonElement) = JsonArray(values.toList())

fun TrackTimeline.updateVideo(index: Int, transform: (VideoTimelineItem) -> VideoTimelineItem) { video = video.updated(index, transform) }
fun TrackTimeline.updateAudio(index: Int, transform: (AudioTimelineItem) -> AudioTimelineItem) { audio = audio.updated(index, transform) }
fun EditState.updateClip(index: Int, transform: (TimelineClip) -> TimelineClip) { clips = clips.updated(index, transform) }

/** Swift `JSONDecoder().decode(Project.self, from: JSONEncoder().encode(project))`. */
fun Project.roundTripped(): Project = Project.decode(Project.encode(this))
