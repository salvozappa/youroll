// YouRoll entry point: hosts ContentView (YouRec/YouRecApp.swift `WindowGroup { ContentView() }`).
// The activity maps the SwiftUI scene phase onto the camera and the store:
// leaving the foreground pauses an active take and retries unsaved edits;
// returning restores the preview only on the recorder and never resumes a take.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {
    private val model: ContentModel by viewModels()
    private val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { if (model.screen == 0) model.camera.prepare(this) }
        else model.camera.error = "Allow Camera and Microphone access in Settings to record."
    }

    private fun granted() = required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enhance audio: the DeepFilterNet3 assets and the thermal state for long offline processing.
        DeepFilterVoiceProcessor.resources = OnnxDeepFilterNetModel.Assets(applicationContext)
        val power = getSystemService(android.os.PowerManager::class.java)
        AudioThermalGate.thermalStatus = { power?.currentThermalStatus ?: 0 }
        // Dark-only app: light status and navigation bar icons on every screen.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // RecordingAppDelegate.keepAwake: the screen stays on while any reason is active.
        lifecycleScope.launch {
            YouRollApp.keepingAwake.collect { reasons ->
                if (reasons.isEmpty()) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // The system shows the bars again after the app returns; the recorder keeps the status bar hidden.
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    if (model.screen == 0) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
                }
                model.store?.let { if (it.unsavedDrafts.value.isNotEmpty()) it.retrySaves() }
                if (granted() && model.screen == 0 && model.startupRecoveryComplete) model.camera.prepare(this@MainActivity)
            }
            override fun onStop(owner: LifecycleOwner) {
                // A recreation for a configuration change the manifest doesn't absorb must not pause a take.
                if (isChangingConfigurations) return
                model.store?.let { if (it.unsavedDrafts.value.isNotEmpty()) it.retrySaves() }
                if (model.camera.hasActiveTake) YouRollApp.protectPendingSave()
                // Pauses an active take; the camera is released while YouRoll is not visible.
                model.camera.suspend(forPlayback = !model.camera.hasActiveTake)
            }
        })
        if (!granted()) permissions.launch(required)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = RecorderColors.cyan, surface = RecorderColors.surface)) {
                ContentView(model)
            }
        }
    }
}
