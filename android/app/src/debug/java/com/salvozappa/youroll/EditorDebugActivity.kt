// Debug-only launcher for the editor until ContentView is merged.
// Copyright (c) 2026 YouRoll contributors. MIT License.
//
//   adb shell am start -n com.salvozappa.youroll/.EditorDebugActivity [--es fixture editor-fixture.mp4] [--ez fresh true] [--ef hold 1.5]
//
// Opens (or creates by importing /sdcard/Movies/<fixture>) a project titled
// after the fixture and shows EditorView. `fresh` imports a new copy. `hold`
// inserts a 2 s freeze frame of the source picture at that time with the
// Freeze Frame effect (`TimelineEditing.freezing`).
package com.salvozappa.youroll

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorDebugActivity : ComponentActivity() {
    private var projectID by mutableStateOf<UUID?>(null)
    private var problem by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The editor is dark-only: light status bar icons on black.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        val store = ProjectStore(File(filesDir, "Projects"), DebugMediaProbe())
        val fixture = intent.getStringExtra("fixture") ?: "editor-fixture.mp4"
        val title = fixture.substringBeforeLast('.')
        lifecycleScope.launch {
            val existing = store.projects.value.firstOrNull { it.title == title }
            if (existing != null && !intent.getBooleanExtra("fresh", false)) {
                store.select(existing.id); projectID = existing.id; return@launch
            }
            val copy = File(cacheDir, fixture)
            val found = withContext(Dispatchers.IO) {
                listOf(File("/sdcard/Movies", fixture), File("/data/local/tmp", fixture)).firstOrNull { it.canRead() }?.also { it.copyTo(copy, overwrite = true) }
            }
            if (found == null) { problem = "Push $fixture to /sdcard/Movies or /data/local/tmp"; return@launch }
            store.importVideo(copy)
            copy.delete()
            val imported = store.projects.value.firstOrNull { it.title == title }
            if (imported == null) { problem = store.error.value ?: "Import failed"; return@launch }
            if (intent.hasExtra("hold")) {
                val time = intent.getFloatExtra("hold", 1f).toDouble()
                // The Freeze Frame effect's own hold (TimelineEditing.freezing), shortened to 2 s.
                val (frozen, id) = TimelineEditing.freezing(imported.edit, time)
                if (id != null) {
                    frozen.clips = frozen.clips.map { clip ->
                        if (clip.frozenFrame == null) clip else clip.copy(frozenFrame = clip.frozenFrame.copy(duration = 2.0),
                            videoEffects = clip.videoEffects?.map { if (it.id == id) it.setting("hold", EffectValue.number(2.0)) else it })
                    }
                    store.commit(imported.id, frozen)
                }
            }
            store.select(imported.id); projectID = imported.id
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = EditorColors.accent, surface = Color(0.11f, 0.11f, 0.12f))) {
                val id = projectID
                if (id != null) {
                    EditorView(store, id, back = { finish() },
                        recordHere = { time, site -> toast("Record here at ${editorTime(time.seconds)} (${site?.label ?: "playhead"})") },
                        export = { toast("Export opens MovieExportPanel in the app") })
                } else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text(problem ?: "Opening $fixture…", color = Color.White)
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
