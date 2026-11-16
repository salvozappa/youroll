// Run by scripts/acceptance.sh before and after the flows (`housekeeping=true`).
// With --keep-data: `begin` backs up YouRoll's settings and takes an inventory of the
// user's projects; `end` deletes leftover acceptance projects (titles starting with
// Suite.PROJECT_PREFIX, never anything in the inventory), restores the settings and
// checks that every project and media file of the inventory is still there.
// Copyright (c) 2026 YouRoll contributors. MIT License.
package com.salvozappa.youroll.acceptance

import com.salvozappa.youroll.uuidString
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SuiteHousekeeping : AcceptanceTest() {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsFolder get() = File(target.applicationInfo.dataDir, "shared_prefs")
    private val backup get() = File(Marker.suiteFolder(), "prefs-backup")
    private val inventoryFile get() = File(Marker.suiteFolder(), "inventory.json")
    private val projectsRoot get() = File(target.filesDir, "Projects")

    /** Every project folder's media and manifest: relative path → size. */
    private fun inventory(): JSONObject {
        val result = JSONObject()
        projectsRoot.listFiles()?.filter { it.isDirectory && !isAcceptanceProject(it) }?.forEach { folder ->
            folder.walkTopDown().filter { it.isFile && (it.name == "project.json" || it.extension.lowercase() in setOf("mp4", "mov", "m4a", "jpg", "jpeg", "png", "heic")) }
                .forEach { result.put(it.relativeTo(projectsRoot).path, if (it.name == "project.json") -1 else it.length()) }
        }
        return result
    }

    /** Leftovers of an interrupted earlier run are not user projects. */
    private fun isAcceptanceProject(folder: File): Boolean = runCatching {
        JSONObject(File(folder, "project.json").readText()).optString("title").startsWith(Suite.PROJECT_PREFIX)
    }.getOrDefault(false)

    @Test fun begin() {
        assumeTrue("Run by scripts/acceptance.sh only", Suite.housekeeping)
        Evidence.root().deleteRecursively()
        Marker.suiteFolder().deleteRecursively()
        Marker.suiteFolder().mkdirs()
        if (!Suite.keepData) return
        backup.mkdirs()
        prefsFolder.listFiles()?.filter { it.extension == "xml" }?.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
        inventoryFile.writeText(inventory().toString(2))
        Suite.note("Backed up ${backup.list()?.size ?: 0} settings files; inventory of ${projectsRoot.list()?.size ?: 0} project folders")
    }

    @Test fun end() {
        assumeTrue("Run by scripts/acceptance.sh only", Suite.housekeeping)
        if (!Suite.keepData) { Marker.suiteFolder().deleteRecursively(); return }
        val before = if (inventoryFile.exists()) JSONObject(inventoryFile.readText()) else JSONObject()
        val userProjects = before.keys().asSequence().map { it.substringBefore('/') }.toSet()

        launchApp()
        // Projects titled "YouRoll acceptance · …" (the inventory never holds those).
        val leftovers = onMain { store.projects.value.filter { it.title.startsWith(Suite.PROJECT_PREFIX) && it.id.uuidString !in userProjects } }
        for (project in leftovers) deleteTestProject(project.id)
        val deleted = onMain { store.deletedProjects.value.filter { it.title.startsWith(Suite.PROJECT_PREFIX) && it.id.uuidString !in userProjects } }
        for (project in deleted) onMain { store.eraseDeleted(project) }
        // Close the app before putting the settings files back, so nothing writes over them.
        tearDownAcceptance()
        Thread.sleep(1500)
        val saved = backup.list()?.toSet() ?: emptySet()
        backup.listFiles()?.forEach { it.copyTo(File(prefsFolder, it.name), overwrite = true) }
        // Settings files the suite created (for example the editor's snap preference) go away.
        prefsFolder.listFiles()?.filter { it.extension == "xml" && it.name !in saved }?.forEach { it.delete() }

        val after = inventory()
        val missing = before.keys().asSequence().filter { key ->
            !after.has(key) || (before.getLong(key) >= 0 && before.getLong(key) != after.getLong(key))
        }.toList()
        Marker.suiteFolder().deleteRecursively()
        assertTrue("User project files changed or missing after the suite: $missing", missing.isEmpty())
        Suite.note("Removed ${leftovers.size + deleted.size} leftover acceptance projects; settings restored; ${before.length()} user files intact")
    }
}
