package com.salvozappa.youroll

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder's portrait/landscape layout maths and the recorded rotation (RecorderView.kt). */
class RecorderGeometryTest {
    private val e = 0.01f

    @Test fun thePortraitPictureFitsTheSafeAreaAndIsCentredOnTheDisplay() {
        // 400 × 900 dp display, 40 dp top and 20 dp bottom insets.
        val p = RecorderGeometry.picture(400f, 900f, 0f, 40f, 0f, 20f, landscape = false)
        assertEquals(400f, p.width, e)
        assertEquals(400f * 16 / 9, p.height, e)
        assertEquals((900f - p.height) / 2 - 40f, p.top, e)
        assertEquals(0f, p.left, e)
    }

    @Test fun theLandscapePictureIs16By9InsideTheSafeAreaWithItsEdgesInControlCoordinates() {
        // Landscape phone: a 41 dp cutout on the left, a 24 dp gesture bar at the bottom.
        val p = RecorderGeometry.picture(891f, 411f, 41f, 0f, 0f, 24f, landscape = true)
        assertEquals(387f, p.height, e)
        assertEquals(387f * 16 / 9, p.width, e)
        // Centred on the full display, expressed from the safe area's leading edge.
        assertEquals((891f - p.width) / 2 - 41f, p.left, e)
        assertEquals((411f - 387f) / 2, p.top, e)
    }

    @Test fun theCaptureRailSitsAtThePhonesBottomEnd() {
        // Rotation 270 (iOS interface landscapeLeft): the cameras are on the right, the rail on the left.
        assertTrue(RecorderGeometry.controlsOnLeft(Surface.ROTATION_270))
        assertFalse(RecorderGeometry.controlsOnLeft(Surface.ROTATION_90))
        assertFalse(RecorderGeometry.controlsOnLeft(Surface.ROTATION_0))
    }

    @Test fun theCaptureGutterIsTheLetterboxOnTheRailsSide() {
        val p = RecorderGeometry.picture(891f, 411f, 41f, 0f, 0f, 24f, landscape = true)
        val safeWidth = 891f - 41f
        assertEquals(p.left, RecorderGeometry.captureGutter(p, safeWidth, 387f, landscape = true, controlsOnLeft = true), e)
        assertEquals(safeWidth - p.right, RecorderGeometry.captureGutter(p, safeWidth, 387f, landscape = true, controlsOnLeft = false), e)
        val portrait = RecorderGeometry.picture(400f, 900f, 0f, 40f, 0f, 20f, landscape = false)
        assertEquals(840f - portrait.bottom, RecorderGeometry.captureGutter(portrait, 400f, 840f, landscape = false, controlsOnLeft = false), e)
        // A 60 dp gutter cannot hold the 88 dp shutter with its clearance: the rail moves over the picture.
        assertEquals(73f, controlInset(gutter = 60.5f, extent = 88f, margin = 18f))
        assertEquals(18f, controlInset(gutter = 130f, extent = 88f, margin = 18f))
    }

    @Test fun theLandscapeTopBarStaysInsideThePicture() {
        val p = RecorderGeometry.Box(100f, 0f, 700f, 387f)
        // Column from 20 to 830 dp: the bar is inset to 112 … 688 dp.
        val (leading, trailing) = RecorderGeometry.topBarInsets(p, rowLeft = 20f, rowRight = 830f, overPicture = true)
        assertEquals(92f, leading, e)
        assertEquals(142f, trailing, e)
        // A column already inside the picture, or a bar above the picture, needs no inset.
        assertEquals(0f to 0f, RecorderGeometry.topBarInsets(p, rowLeft = 200f, rowRight = 600f, overPicture = true))
        assertEquals(0f to 0f, RecorderGeometry.topBarInsets(p, rowLeft = 20f, rowRight = 830f, overPicture = false))
    }

    @Test fun theRecordedRotationFollowsGravity() {
        // OrientationEventListener degrees: 90 = left side up (phone turned clockwise).
        assertEquals(Surface.ROTATION_0, RecorderGeometry.captureRotation(0))
        assertEquals(Surface.ROTATION_0, RecorderGeometry.captureRotation(44))
        assertEquals(Surface.ROTATION_270, RecorderGeometry.captureRotation(45))
        assertEquals(Surface.ROTATION_270, RecorderGeometry.captureRotation(90))
        assertEquals(Surface.ROTATION_180, RecorderGeometry.captureRotation(180))
        assertEquals(Surface.ROTATION_90, RecorderGeometry.captureRotation(270))
        assertEquals(Surface.ROTATION_0, RecorderGeometry.captureRotation(315))
        assertEquals(Surface.ROTATION_0, RecorderGeometry.captureRotation(359))
        assertEquals(Surface.ROTATION_90, RecorderGeometry.captureRotation(-90))
    }
}
