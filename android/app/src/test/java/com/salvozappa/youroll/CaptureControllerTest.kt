package com.salvozappa.youroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Camera choices, per-camera memory and the take state machine of CaptureController. */
class CaptureControllerTest {
    // MARK: Camera choices

    private val pixelRear = CameraDescriptor("0", front = false, equivalentFocal = 25.0, supports4K = true, physical = listOf(
        CameraDescriptor("2", false, 14.0, supports4K = true),
        CameraDescriptor("3", false, 25.0, supports4K = true),
        CameraDescriptor("4", false, 113.0, supports4K = false),
    ))
    private val pixelFront = CameraDescriptor("1", front = true, equivalentFocal = 22.0, supports4K = true)

    @Test fun logicalCameraGivesAutoPlusEachPhysicalLens() {
        val choices = CameraCatalog.choices(listOf(pixelRear, pixelFront), CaptureQuality.hd1080)
        assertEquals(listOf("Rear auto", "Rear ultrawide", "Rear main", "Rear tele", "Front main"), choices.map { it.name })
        assertEquals(listOf("0", "0/2", "0/3", "0/4", "1"), choices.map { it.id })
        val tele = choices.first { it.name == "Rear tele" }
        assertEquals("0", tele.logicalID); assertEquals("4", tele.physicalID); assertEquals(CameraLens.tele, tele.lens)
        assertNull(choices.first { it.name == "Rear auto" }.physicalID)
        assertTrue(choices.all { it.available })
    }

    @Test fun lensesWithout4KAreMarkedUnavailableAt4K() {
        val choices = CameraCatalog.choices(listOf(pixelRear, pixelFront), CaptureQuality.ultraHD4K)
        assertFalse(choices.first { it.name == "Rear tele" }.available)
        assertTrue(choices.first { it.name == "Rear main" }.available)
    }

    @Test fun aPhysicalLensAlsoExposedOnItsOwnIsListedOnce() {
        val standaloneUltrawide = CameraDescriptor("2", false, 14.0)
        val choices = CameraCatalog.choices(listOf(pixelRear, standaloneUltrawide, pixelFront), CaptureQuality.hd1080)
        assertEquals(1, choices.count { it.lens == CameraLens.ultraWide })
        assertEquals("2", choices.first { it.lens == CameraLens.ultraWide }.id)
        assertFalse(choices.any { it.id == "0/2" })
    }

    @Test fun emulatorFrontLogicalCamera() {
        val rear = CameraDescriptor("10", false, 13.5)
        val front = CameraDescriptor("1", true, 26.9, physical = listOf(CameraDescriptor("5", true, 43.4), CameraDescriptor("4", true, 26.9)))
        val choices = CameraCatalog.choices(listOf(rear, front), CaptureQuality.hd1080)
        assertEquals(listOf("Rear main", "Front auto", "Front main", "Front tele"), choices.map { it.name })
        assertEquals(listOf("10", "1", "1/4", "1/5"), choices.map { it.id })
    }

    @Test fun equivalentFocalLength() {
        assertEquals(26.86, CameraCatalog.equivalentFocal(4.38f, 5.6448f, 4.2336f)!!, 0.01)
        assertNull(CameraCatalog.equivalentFocal(null, 1f, 1f))
    }

    @Test fun flipChoosesTheRememberedCameraOnTheOtherSide() {
        val choices = CameraCatalog.choices(listOf(pixelRear, pixelFront), CaptureQuality.hd1080)
        assertEquals("1", CameraCatalog.oppositeCamera(choices, "0/4", lastFront = null, lastRear = null)!!.id)
        assertEquals("0/4", CameraCatalog.oppositeCamera(choices, "1", lastFront = "1", lastRear = "0/4")!!.id)
        assertEquals("0", CameraCatalog.oppositeCamera(choices, "1", lastFront = "1", lastRear = null)!!.id)
        // An unavailable remembered lens is skipped.
        val at4K = CameraCatalog.choices(listOf(pixelRear, pixelFront), CaptureQuality.ultraHD4K)
        assertEquals("0", CameraCatalog.oppositeCamera(at4K, "1", lastFront = "1", lastRear = "0/4")!!.id)
        assertNull(CameraCatalog.oppositeCamera(choices, "missing", null, null))
    }

    @Test fun oneTimesMainSwitchesFromTheUltrawideOnly() {
        val choices = CameraCatalog.choices(listOf(pixelRear, pixelFront), CaptureQuality.hd1080)
        assertEquals("0/3", CameraCatalog.mainLensAtOneX(choices, "0/2")!!.id)
        assertNull(CameraCatalog.mainLensAtOneX(choices, "0"))
        assertNull(CameraCatalog.mainLensAtOneX(choices, "0/3"))
    }

    @Test fun zoomPresetsFollowTheActiveRange() {
        assertEquals(listOf(0.7, 1.0, 2.0, 3.0, 5.0, 6.0), CameraCatalog.zoomPresets(0.67, 10.0))
        assertEquals(listOf(0.5, 1.0, 2.0, 3.0, 5.0, 6.0), CameraCatalog.zoomPresets(0.5, 10.0))
        assertEquals(listOf(1.0, 2.0, 3.0), CameraCatalog.zoomPresets(1.0, 4.0))
        assertEquals(listOf(1.0), CameraCatalog.zoomPresets(1.0, 1.0))
    }

    // MARK: Per-camera memory

    @Test fun everyCameraKeepsItsOwnZoomAndFocus() {
        val store = MemoryStore()
        val memory = CameraSettingsMemory(store)
        memory.saveZoom("0/2", 0.8); memory.saveZoom("0/3", 2.0); memory.saveZoom("1", 1.4)
        memory.saveFocus("0/3", locked = true, lens = 0.25)
        memory.saveZoom("0/3", 3.0) // changing B never touches A
        assertEquals(0.8, memory.load("0/2").zoom!!, 0.0)
        assertEquals(3.0, memory.load("0/3").zoom!!, 0.0)
        assertEquals(1.4, memory.load("1").zoom!!, 0.0)
        assertTrue(memory.load("0/3").locked); assertEquals(0.25, memory.load("0/3").lens, 0.0)
        assertFalse(memory.load("0/2").locked)
        // Auto and the physical main lens are separate profiles.
        assertNull(memory.load("0").zoom)
        // Survives a relaunch (a new memory over the same store), with Swift's field names.
        val relaunched = CameraSettingsMemory(store)
        assertEquals(3.0, relaunched.load("0/3").zoom!!, 0.0)
        assertTrue(store.values["camera.0/3"]!!.contains("\"zoom\":3.0"))
        assertTrue(store.values["camera.0/3"]!!.contains("\"locked\":true"))
    }

    @Test fun aTemporaryFormatLimitClampsWithoutForgettingThePreference() {
        val memory = CameraSettingsMemory(MemoryStore())
        memory.saveZoom("0", 8.0)
        assertEquals(5.0, memory.restoredZoom("0", 1.0, 5.0, nativeZoom = false), 0.0)
        assertEquals(8.0, memory.load("0").zoom!!, 0.0)
        assertEquals(1.0, memory.restoredZoom("new", 0.67, 10.0, nativeZoom = false), 0.0)
        // 1× Main opens the main lens at its native framing and remembers it.
        memory.saveZoom("0/3", 4.0)
        assertEquals(1.0, memory.restoredZoom("0/3", 1.0, 10.0, nativeZoom = true), 0.0)
        assertEquals(1.0, memory.load("0/3").zoom!!, 0.0)
    }

    @Test fun focusLensPositionIsClamped() {
        val memory = CameraSettingsMemory(MemoryStore())
        memory.saveFocus("1", true, 1.7)
        assertEquals(1.0, memory.load("1").lens, 0.0)
        memory.saveFocus("1", false, null)
        assertFalse(memory.load("1").locked); assertEquals(1.0, memory.load("1").lens, 0.0)
    }

    // MARK: Take state machine

    private val dir = File("/tmp/project")
    private fun recordSegment(take: CaptureTake, pause: Boolean, saved: Boolean = true): Int {
        assertTrue(take.begin(dir))
        val sequence = take.segmentStarting()
        assertEquals(CaptureState.starting, take.state)
        assertFalse(take.recordingStarted(wantsPreview = true))
        assertEquals(CaptureState.recording, take.state)
        assertTrue(take.requestStop(pause))
        assertEquals(CaptureState.saving, take.state)
        take.finished(success = true)
        take.saved(saved)
        return sequence
    }

    @Test fun recordPauseResumeStop() {
        val states = mutableListOf<CaptureState>()
        val take = CaptureTake { states += it }
        assertEquals(0, recordSegment(take, pause = true))
        assertEquals(CaptureState.paused, take.state)
        assertTrue(take.takeHasMedia); assertTrue(take.retainMicrophone)
        val takeID = take.takeID
        assertEquals(1, recordSegment(take, pause = false))
        assertEquals(takeID, take.takeID) // Resume continues the same take
        assertEquals(CaptureState.idle, take.state)
        assertFalse(take.takeHasMedia); assertFalse(take.retainMicrophone)
        assertEquals(0, recordSegment(take, pause = false))
        assertNotEquals(takeID, take.takeID) // a new Record starts a new take
        assertEquals(listOf(CaptureState.starting, CaptureState.recording, CaptureState.saving, CaptureState.saving, CaptureState.paused),
            states.take(5))
    }

    @Test fun stopWinsOverAnAutomaticPause() {
        val take = CaptureTake()
        take.begin(dir); take.segmentStarting(); take.recordingStarted(true)
        assertTrue(take.requestStop(pause = false))
        assertFalse(take.requestStop(pause = true))
        assertTrue(take.interrupt())
        assertEquals(CaptureTake.Next.stop, take.next)
        take.finished(success = false) // even a failed writer keeps the explicit Stop
        assertFalse(take.saved(true))
        assertEquals(CaptureState.idle, take.state)
    }

    @Test fun interruptionsPauseAndNeverResume() {
        val take = CaptureTake()
        take.begin(dir); take.segmentStarting(); take.recordingStarted(true)
        assertTrue(take.interrupt())
        assertEquals(CaptureState.saving, take.state)
        take.finished(success = true)
        assertTrue(take.saved(true))
        assertEquals(CaptureState.paused, take.state)
        assertFalse(take.captureActive)
    }

    @Test fun aWriterFailureLeavesTheTakePaused() {
        val take = CaptureTake()
        take.begin(dir); take.segmentStarting(); take.recordingStarted(true)
        take.finished(success = false)
        assertEquals(CaptureTake.Next.pause, take.next)
        assertTrue(take.saved(true))
        assertEquals(CaptureState.paused, take.state)
    }

    @Test fun aRefusedSegmentKeepsTheTakePausedSoTheFileIsNotLost() {
        val take = CaptureTake()
        recordSegment(take, pause = false, saved = false)
        assertEquals(CaptureState.paused, take.state)
    }

    @Test fun stopBeforeTheFirstFrameStopsTheWriter() {
        val take = CaptureTake()
        take.begin(dir); take.segmentStarting()
        assertTrue(take.requestStop(pause = false))
        assertTrue(take.recordingStarted(wantsPreview = true))
        val leaving = CaptureTake()
        leaving.begin(dir); leaving.segmentStarting()
        assertTrue(leaving.recordingStarted(wantsPreview = false))
    }

    @Test fun emptySegments() {
        val first = CaptureTake()
        first.begin(dir); first.segmentStarting(); first.requestStop(pause = true)
        assertFalse(first.empty())
        assertEquals(CaptureState.idle, first.state) // nothing recorded yet
        val later = CaptureTake()
        recordSegment(later, pause = true)
        later.begin(dir); later.segmentStarting(); later.requestStop(pause = true)
        assertTrue(later.empty())
        assertEquals(CaptureState.paused, later.state)
    }

    @Test fun failedStarts() {
        val fresh = CaptureTake()
        fresh.begin(dir); fresh.segmentStarting()
        assertFalse(fresh.failedStart())
        assertEquals(CaptureState.idle, fresh.state)
        val paused = CaptureTake()
        recordSegment(paused, pause = true)
        paused.begin(dir); paused.segmentStarting()
        assertTrue(paused.failedStart())
        assertEquals(CaptureState.paused, paused.state)
    }

    @Test fun guards() {
        val take = CaptureTake()
        take.begin(dir); take.segmentStarting()
        assertFalse(take.begin(dir)) // a segment is active
        assertFalse(take.finishPaused())
        assertFalse(CaptureTake().requestStop(pause = false))
        assertFalse(CaptureTake().interrupt())
        assertFalse(CaptureTake().begin(dir, blocked = true))
        val paused = CaptureTake()
        recordSegment(paused, pause = true)
        assertTrue(paused.finishPaused())
        assertEquals(CaptureState.idle, paused.state)
        assertFalse(paused.takeHasMedia)
    }

    @Test fun clockFormatting() {
        assertEquals("00:00", CaptureController.clock(-3.0))
        assertEquals("01:05", CaptureController.clock(65.9))
        assertEquals("00:00", CaptureController.clock(Double.NaN))
        assertEquals(12f, controlInset(gutter = 80f, extent = 44f, margin = 12f))
        assertEquals(40f, controlInset(gutter = 28f, extent = 44f, margin = 12f))
    }
}
