// SPEC "Microphone selection and monitoring" and the UI acceptance ("identify
// the active mic and its fallback"):
// - the recorder's microphone chip names the input Android actually routes the
//   recording to (AudioManager's active recording configuration);
// - with a USB or Bluetooth microphone connected: select it, record, and the
//   operator unplugs it mid-take (prompted in the script output); the take
//   continues on the built-in microphone with a `microphoneFallback` event and
//   audio that stays aligned with the video. Skipped when no such mic is present.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.salvozappa.youroll.CaptureMicrophones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MicrophoneAcceptanceTest : AcceptanceTest() {
    override val timeoutSeconds: Long = 600

    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val externalTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET)

    private fun externalMicrophone(): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in externalTypes }

    /** The input Android routes YouRoll's own recording (the meter or the take) to. */
    private fun routedInput(): String? = audio.activeRecordingConfigurations.mapNotNull { it.audioDevice }
        .firstNotNullOfOrNull { CaptureMicrophones.port(it)?.portName }

    private fun chipName(): String = find(descStarting("Microphone: "), "the microphone chip").contentDescription.removePrefix("Microphone: ")

    @Test fun activeInputIsReported() {
        launchApp()
        waitRecorderIdle()
        waitForApp("the microphone meter to start", 15_000) { model.camera.microphoneID.isNotEmpty() && model.camera.meterAvailable }
        var routed: String? = null
        waitFor("Android to report YouRoll's recording input", 15_000) { routed = routedInput(); routed != null }
        val name = chipName()
        assertNotEquals("A microphone is reported", "Microphone unavailable", name)
        assertEquals("The chip names the routed input", routed, name)
        assertEquals("The chip matches the recorder state", onMain { model.camera.microphoneName }, name)
        val shortName = name.replace("Microphone", "Mic")
        assertTrue("The chip shows “$shortName”", device.hasObject(text(shortName)))
        // The popover names the input too.
        tap(descStarting("Microphone: "), "the microphone chip")
        find(desc("Input: $name"), "the microphone popover's input")
        dismissPopup(desc("Input: $name"), "the microphone popover")
        Evidence.json(evidenceName, "report.json", json("chip" to name, "routed" to routed,
            "inputs" to jsonArray(audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { "${it.type}:${it.productName}" })))
    }

    @Test fun externalMicrophoneFallsBackWhenUnplugged() {
        val external = externalMicrophone()
        assumeTrue("No USB, Bluetooth or wired microphone is connected: fallback check skipped (connect one and rerun; TODO.md, Needs a real device)",
            external != null)
        external!!
        launchApp()
        val id = createTestProject("Microphone fallback")
        waitForApp("the microphone list", 15_000) { model.camera.microphones.isNotEmpty() }
        val externalName = CaptureMicrophones.port(external)?.portName ?: throw AssertionError("YouRoll does not list ${external.productName}")
        tap(descStarting("Microphone: "), "the microphone chip")
        tap(res("recorder.gainMicrophone"), "the input menu")
        tap(text(externalName), "$externalName in the input menu")
        waitForApp("$externalName to become the input", 15_000) { model.camera.microphoneName == externalName }
        dismissPopup(desc("Input: $externalName"), "the microphone popover")
        assertEquals("The chip reports the external microphone", externalName, chipName())

        startRecording()
        holdRecording(2.0)
        Suite.operator("UNPLUG (or switch off) “$externalName” now. Waiting up to 120 s; the take keeps recording.")
        waitFor("$externalName to disappear", 120_000) { externalMicrophone() == null }
        waitForApp("the recorder to fall back", 20_000) {
            model.camera.microphoneName != externalName && model.camera.warning?.contains("disconnected") == true
        }
        val fallback = chipName()
        holdRecording(3.0)
        stopRecording(id, 1)
        Suite.operator("You can reconnect “$externalName”.")

        val project = savedProject(id)
        val events = project.sources.single().events ?: emptyList()
        assertTrue("A microphoneFallback event marks the change", events.any { it.kind == "microphoneFallback" })
        val gaps = events.filter { it.kind == "audioGap" }
        assertTrue("At most one short audio gap: ${gaps.map { it.detail }}", gaps.size <= 1)
        val file = sourceFiles(project).single()
        Evidence.copy(evidenceName, file)
        val aligned = MediaCheck.assertAudioVideoAligned(file)
        Evidence.json(evidenceName, "fallback.json", json("external" to externalName, "fallback" to fallback,
            "events" to jsonArray(events.map { json("time" to it.time, "kind" to it.kind, "detail" to it.detail) }), "media" to aligned))
        finishWithProject(id)
    }
}
