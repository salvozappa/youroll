// Port of YouRec/YouRecApp.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// `YouRecApp` (a SwiftUI App showing ContentView in dark mode) maps to
// MainActivity + ContentView. Its `RecordingAppDelegate` helpers that matter on
// Android live here:
// - `keepAwake(reason, active)`: a set of reasons; MainActivity keeps the
//   screen on (FLAG_KEEP_SCREEN_ON) while any reason is active.
// - `protectPendingSave`/`finishedPendingSave`: iOS asks for background time so
//   a take's last segment finishes saving. On Android the process keeps running
//   after onStop, so these only record the state (no foreground service yet; TODO.md).
// The retired YouTube uploader cleanup and the orientation lock are iOS-only.
package com.salvozappa.youroll

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class YouRollApp : Application() {
    companion object {
        private val awakeReasons = MutableStateFlow<Set<String>>(emptySet())
        /** Non-empty while the screen must stay on. */
        val keepingAwake: StateFlow<Set<String>> = awakeReasons.asStateFlow()

        fun keepAwake(reason: String, active: Boolean) {
            awakeReasons.value = if (active) awakeReasons.value + reason else awakeReasons.value - reason
        }

        @Volatile var pendingSave = false
            private set
        fun protectPendingSave() { pendingSave = true }
        fun finishedPendingSave() { pendingSave = false }
    }
}
