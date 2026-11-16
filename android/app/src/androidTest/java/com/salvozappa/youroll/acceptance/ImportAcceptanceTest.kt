// Import a fixture video through the system Photo Picker (Projects → Import),
// like a user. The test adds the fixture to the gallery itself (MediaStore, as the
// newest video, with a length that changes from run to run, 5 to 16 s, so its picker tile
// can be told apart, even from a stale tile of an earlier run that the picker still shows)
// and removes it afterwards. The imported project must hold a byte-identical copy
// in managed storage: a wrong pick fails on the hash.
//
// `--import-via store` seeds the same project through MediaImportStaging and
// ProjectStore.importVideo (the code the picker callback runs) for devices where
// the picker cannot be driven.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import com.salvozappa.youroll.MediaImportStaging
import com.salvozappa.youroll.ProjectStore
import com.salvozappa.youroll.uuidString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.regex.Pattern

class ImportAcceptanceTest : AcceptanceTest() {
    /** Whole seconds of this run's fixture, and its "duration 00:SS" label in the picker. */
    private val fixtureWholeSeconds = 5 + ((System.currentTimeMillis() / 60_000) % 12).toInt()
    private val fixtureLabel = "00:%02d".format(fixtureWholeSeconds)
    private val pickerPackage = Pattern.compile(".*(photopicker|providers\\.media|mediaprovider).*")
    private val picker = By.pkg(pickerPackage)
    private val fixtureTile = By.desc(Pattern.compile(".*\\b$fixtureLabel\\b.*", Pattern.DOTALL))

    /** The first [fixtureWholeSeconds] + 0.45 s of the test asset, remuxed without re-encoding. */
    private fun makeFixture(): File {
        val source = File(context.cacheDir, "acceptance-source.mp4")
        instrumentation.context.assets.open("editor-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val fixture = File(context.cacheDir, "acceptance-import.mp4").apply { delete() }
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(fixture.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = (0 until extractor.trackCount).associateWith { muxer.addTrack(extractor.getTrackFormat(it)) }
            tracks.keys.forEach { extractor.selectTrack(it) }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 shl 20)
            val info = MediaCodec.BufferInfo()
            val cutoff = fixtureWholeSeconds * 1_000_000L + 450_000L   // N.45 s reads "00:N" whether the picker floors or rounds
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (extractor.sampleTime < cutoff) {
                    info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
        } finally {
            extractor.release(); muxer.release(); source.delete()
        }
        return fixture
    }

    private fun insertFixture(fixture: File): Uri {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "youroll-acceptance-import-$now.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/YouRollAcceptance")
            put(MediaStore.Video.Media.DATE_TAKEN, now)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw AssertionError("Could not add the fixture to the gallery")
        resolver.openOutputStream(uri)!!.use { out -> fixture.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Projects → Import, then the newest 0:07 tile. The picker syncs new media in the background: reopen it once if needed. */
    private fun pickFixture() {
        var attempt = 0
        while (true) {
            tap(text("Import"), "the Import button")
            find(picker, "the system Photo Picker", 20_000)
            val shown = device.wait(androidx.test.uiautomator.Until.hasObject(fixtureTile), 15_000) == true
            if (shown) break
            if (++attempt >= 2) throw AssertionError("The Photo Picker does not show the $fixtureLabel fixture")
            Suite.note("The Photo Picker has not listed the new fixture yet; reopening it")
            device.pressBack()
            waitFor("the Photo Picker to close", 10_000) { device.findObject(picker) == null }
        }
        val newest = device.findObjects(fixtureTile).minWithOrNull(compareBy<UiObject2>({ it.visibleBounds.top }, { it.visibleBounds.left }))
            ?: throw AssertionError("The fixture tile disappeared")
        newest.click()
        // Single selection either returns at once or asks for confirmation (Done).
        val done = By.pkg(pickerPackage).text(Pattern.compile("(?i)^(done|add|select|ok|fine|fatto|aggiungi|seleziona)$"))
        waitFor("the picker to return or offer Done", 10_000) { device.findObject(picker) == null || device.findObject(done) != null }
        device.findObject(done)?.click()
        waitFor("the Photo Picker to close", 20_000) { device.findObject(picker) == null }
    }

    @Test fun importFixture() {
        launchApp()
        val fixture = makeFixture()
        val expectedHash = ProjectStore.fingerprint(fixture)
        val expectedSeconds = MediaCheck.containerSeconds(fixture)
        val uri = insertFixture(fixture)
        try {
            goToProjects()
            val before = onMain { store.projects.value.map { it.id }.toSet() }
            if (Suite.importVia == "store") {
                Suite.note("Import seeded through ProjectStore (--import-via store), not the Photo Picker")
                val staged = runBlocking { MediaImportStaging.stage(context, uri) }
                runBlocking { withContext(Dispatchers.Main) { store.importVideo(staged) } }
                MediaImportStaging.discard(staged)
            } else pickFixture()
            waitForApp("the imported project", 60_000) { !store.importing.value && store.projects.value.any { it.id !in before } }
            val project = onMain { store.projects.value.first { it.id !in before } }
            onMain { store.rename(project, Suite.PROJECT_PREFIX + "Import") }
            val saved = savedProject(project.id)
            assertEquals("One imported source", 1, saved.sources.size)
            val source = saved.sources.single()
            val copy = File(projectFolder(project.id), source.filename)
            assertTrue("The video is copied into managed storage", copy.isFile)
            assertEquals("The copy is byte-identical to the gallery video", expectedHash, ProjectStore.fingerprint(copy))
            assertEquals("The project records the fixture's hash", expectedHash, source.sha256)
            assertEquals("The fixture's duration", expectedSeconds, source.duration, Suite.FRAME)
            assertEquals("The timeline holds the whole video", expectedSeconds, saved.edit.duration, Suite.FRAME)
            find(text(Suite.PROJECT_PREFIX + "Import"), "the imported project's card")
            Evidence.json(evidenceName, "report.json", json("project" to project.id.uuidString, "via" to Suite.importVia,
                "sha256" to source.sha256, "duration" to source.duration))
            finishWithProject(project.id)
        } finally {
            context.contentResolver.delete(uri, null, null)
            fixture.delete()
        }
    }
}
