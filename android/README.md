# YouRoll (Android)

YouRoll is an Android port of [YouRec](https://github.com/antirez/YouRecApp) by
Salvatore Sanfilippo (antirez), a camera and multitrack video editor for creators. The
iOS app is MIT-licensed. Its author reserves the **YouRec name and icon**, so this port
has its own name, YouRoll, and its own placeholder icon. See `../LICENSE` and
`../THIRD_PARTY_NOTICES.md`.

The [YouRec Swift sources](https://github.com/antirez/YouRecApp/tree/main/YouRec) are the
reference for behaviour, logic structure and UI. Every Kotlin file mirrors the Swift file of
the same name.

## Status

The recorder, the editor and most of the YouRec editor tools: crop, speed, titles,
transitions, placement, image, mirror, pauses, censor, added audio, cover, colour, video
effects, stabilization, audio enhancement and subtitles. What is left out is listed in
[TODO.md](TODO.md).

- **Recorder:**
  - full-screen camera preview with the iOS control layout;
  - Record / Pause / Resume / Stop into one project, including continuing after an app relaunch;
  - camera switching mid-take, holding the last frame;
  - per-camera zoom memory, presets and timed zoom ramps;
  - focus lock and manual focus;
  - recording from the selected microphone, AAC 48 kHz, with fallback on disconnect;
  - level meter, bookmarks (Mark), keep-awake, no auto-resume after interruptions.
- **Projects:**
  - cards with thumbnails, rename, duplicate;
  - Recently deleted, with restore and erase;
  - Original recordings;
  - import from the Photo Picker into managed storage.
- **Editor:**
  - ExoPlayer preview of the compiled timeline, mixing every overlapping sound, with review speed 1×–3×;
  - scrolling thumbnail + waveform timeline, zoom and snapping;
  - split, Start/End range delete, select, trim, Move with scopes;
  - Link/Unlink, Volume with fades;
  - Add audio: a music or voice file from Files as its own audio layer at the playhead;
  - Censor audio: replace a marked word or sound with a beep (sine, triangle or square), in the preview and the export;
  - Subtitles: transcribe speech offline with Qwen3-ASR-0.6B (antirez's qwen-asr C engine via the NDK; the 1.9 GB model is downloaded only on request), then regroup, correct, restyle with the title inspector, drag and pinch the caption series, in the preview and the export;
  - Enhance audio: a reorderable filter chain of noise reduction (RNNoise or DeepFilterNet3), low-cut, voice EQ and output gain, with Bypass and level-matched comparison, processed offline into a cache that the preview and the export both play (LavaSR and VoiceFixer are not available yet);
  - Crop and video shape presets (16:9, 9:16, 1:1, 4:5, Original), in the preview and the export;
  - Speed (0.25×–4×, voice pitch kept) on a marked range, the selection or the whole project, and iOS freeze frames, in the preview and the export;
  - Text titles with looks, the bundled fonts, fill, outline, shadow, background and motion, in the preview and the export;
  - Video effects: 19 film looks and techniques (colour correction, tints, Noir, Quantization, Kaleidoscope, Widescreen, Halation, 16mm, Super 8, Undercranking, Freeze Frame and more) with keyframes, drawn by one GLSL renderer in the preview, the export and transition windows;
  - Transitions at joins and at the project start and end, in all nine iOS styles with holds, colours and J/L sound timing, in the preview and the export;
  - Image overlays from the Photo Picker (position, size, opacity, duration), Mirror (horizontal and vertical flips) and Pauses (detect silent gaps, review Cut/Keep, non-destructive cuts that Remove restores), in the preview and the export;
  - Color LUTs: 14 built-in looks, imported .cube LUTs, strength, Highlights, exposure and white balance, favourites and saved grades, in the preview, the export, transition windows and placed pictures;
  - Stabilize: offline camera stabilization (Off, Light, Standard, Strong) with the iOS motion estimator (C++ via the NDK), a sampled Before/After preview and the actual crop zoom, in the preview, the export and transition windows;
  - Select cover: nine frames of the range or project, Update, and a full-resolution JPEG to share;
  - Copy/Cut/Paste, bookmarks;
  - insert videos, photos and unused takes, and "Record here";
  - persistent undo/redo that survives relaunch.
- **Export:** Media3 Transformer to H.264/AAC MP4 at 30 fps on the project canvas, with
  progress and Cancel, Share, Save to gallery and export receipts. It never overwrites
  sources or earlier exports.

Project files use the Swift `Codable` field names and the integer time clock (240,000
ticks per second), so the formats can converge with iOS later. Some assumptions are
unverified; see TODO.md.

## Build and run

Requirements:
- Android Studio 2026.2 (its bundled JBR is used as the JDK);
- Android SDK platform 37 and build-tools 37;
- Android NDK 30 and CMake (from the SDK manager) for the native code;
- an arm64 API 37 emulator named `YouRoll_API37` (and `_B`, `_C` for parallel runs), or a phone.

Commands, all from this folder:

```sh
scripts/emu.sh              # boot the API 37 emulator headless
scripts/check.sh            # build + JVM unit tests
scripts/run.sh [--clear]    # build, install, grant permissions, launch
scripts/shot.sh <name>      # screenshot to build/shots/
scripts/ui.sh dump|tap …    # drive the UI through uiautomator
scripts/fixtures.sh --push  # test videos into /sdcard/Movies
```

- **Several emulators:** set `YOUROLL_EMU_PORT=5556` or `5558` to use a second or third emulator.
- **Your own device:** connect it with USB debugging and set `YOUROLL_DEVICE=<serial>`
  (from `adb devices`); `run.sh`, `shot.sh` and `ui.sh` then target it instead of the emulator.
- **Android Studio:** open this folder as a normal Gradle project.

## Layout

- `app/src/main/java/com/salvozappa/youroll/`: one Kotlin file per ported Swift file (`Project.kt`
  ← `Project.swift`, …). Each starts with a `// Port of YouRec/<File>.swift` header.
  Files without a Swift counterpart are Android glue:
  - `AndroidMedia.kt`: probe, preferences, sharing, MediaStore;
  - `CaptureGL.kt`: GL frame path for the recorder;
  - `PreviewAudio.kt`: preview mixer;
  - `SwiftCodable.kt`: Swift-compatible JSON;
  - `CaptureSink.kt`: recorder → project wiring.
- `app/src/test/`: JVM tests. Many port the upstream Swift test assertions
  (`TrackTimelineTests`, `MoveBoundaryChecks`, `RecordingHistoryTests`, `CoreTests`,
  `TimelineClipboardChecks`, `CropTests`, `SpeedTests`, `TitleDesignTests`, `TransitionTests`, `PausesTests`, `ImageMirrorTests`,
  `AudioCensorTests`, `AudioEnhancementTests`, `SubtitleTests`, `ColorGradeTests`, `VideoEffectModelTests`, `VideoEffectsEditorTests`, `StabilizationTests`).
  `scripts/check.sh` also builds and runs `../ThirdParty/YouRec/StabilizationMotionTests.cpp` on the host.
- `app/src/androidTest/`: device tests for export (`ColorExportDeviceTest`: GPU grade against its CPU twin, black bars, graded placed pictures and transition windows; `MediaRendererDeviceTest`, including placed picture-in-picture
  geometry against reference exports, a placed overlay over a dissolve, and a censor with an added audio layer; `SubtitleDeviceTest`: caption layout limits and exported captions equal to the preview frames; `TitleExportDeviceTest`: title pixels,
  fonts and motion in exported movies; `TransitionRendererDeviceTest`: every
  transition style rendered, dissolve and colour-hold exports; `VideoEffectsDeviceTest`: every effect drawn, an exported effect frame against the shader's CPU twin, Freeze Frame push-in, Undercranking duration, effects inside a dissolve), and the acceptance suite (`acceptance/`).
- Subtitles: antirez's `qwen-asr` (MIT) is vendored unchanged in `app/src/main/cpp/qwen-asr` (provenance there) and built into `libyouroll_qwen_asr.so` with a local `cblas_sgemm` (`cpp/qwen-blas`). The Qwen3-ASR weights are never in the APK or the repository: the app downloads them on request into no-backup storage (`SubtitleModelPack.kt`). For testing without the network, push the three files (`vocab.json`, `merges.txt`, `model.safetensors` of `Qwen/Qwen3-ASR-0.6B` at the pinned revision) into `no_backup/SubtitleModels/qwen3-asr-0.6b-bf16-v1.download/` with `run-as` and tap Download: they are verified and published without downloading.
- Enhance audio models: RNNoise is built from `../ThirdParty/RNNoise` by the NDK (`src/main/cpp`); DeepFilterNet3's ONNX graphs (`ThirdParty/DeepFilterNet3-ONNX`, provenance there) and the iOS DSP JSON are copied into `assets/DeepFilterNet3` (task `deepFilterAssets`) and run by ONNX Runtime.
- Title fonts: copied at build time from `../ThirdParty/TitleFonts` into `assets/TitleFonts` (task
  `titleFontAssets`); licences in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
- `app/src/debug/`: `EditorDebugActivity`, to open the editor on a fixture directly.

## Acceptance suite

`scripts/acceptance.sh` runs the SPEC acceptance flows end to end on a device. The tests in
`app/src/androidTest/java/com/salvozappa/youroll/acceptance/` drive the real app through UiAutomator and
check the project files, segments and exports in-process. One class per flow:

- `RecordingLifecycleAcceptanceTest`: record A, pause, record B, stop, kill the app, relaunch, record C.
- `CameraSwitchAcceptanceTest`: one take through every camera in the camera menu.
- `ZoomAcceptanceTest`: preset with a timed ramp; per-camera zoom memory.
- `EditHistoryAcceptanceTest`: undo/redo across a relaunch, undo-all, recording during redo.
- `ExportAcceptanceTest`: two exports, H.264/AAC, durations, unchanged hashes.
- `ImportAcceptanceTest`: import a fixture through the Photo Picker.
- `MicrophoneAcceptanceTest`: the chip names the routed input; USB/BT unplug fallback.

```sh
scripts/acceptance.sh                                  # emulator-$YOUROLL_EMU_PORT (default 5554)
scripts/acceptance.sh --device <serial> --keep-data    # a phone
scripts/acceptance.sh --only ZoomAcceptanceTest,ExportAcceptanceTest
```

- **Data:** by default the script runs `pm clear com.salvozappa.youroll`, which deletes every YouRoll project
  on the device (it asks first on a physical device). `--keep-data` keeps them: each flow
  works in its own "YouRoll acceptance · …" project and deletes only that, and YouRoll's
  settings are backed up and restored.
- **Process deaths:** each test method runs in its own process and the app is force-stopped
  between methods, so `step2_…` sees a real relaunch. Run the tests through the script; a
  plain `connectedDebugAndroidTest` skips them.
- **Results:** `build/acceptance/<timestamp>/` holds `junit.xml`, `results.txt`, the raw
  instrumentation output, logcat per test, and `media/` (segments, exports, reports).
- **Skips:** a flow the device can't exercise is skipped with a reason, for example the
  external-mic fallback without a USB/Bluetooth mic. When a mic is connected, the script
  output asks the operator to unplug it mid-take.
- **Picker fallback:** `--import-via store` seeds the import through `ProjectStore` if the
  Photo Picker can't be driven.
- **Manual checks:** the script ends with the checks it can't automate (clap test, front
  mirroring, outdoor readability). They are in TODO.md under "Needs a real device".

## Testing on a phone

The emulator can't validate real cameras, microphones or encoder performance. The
"Needs a real device" section of TODO.md is the checklist for testing on a
phone. It covers physical lenses, 1080p/4K at 30 fps, USB-C mic fallback, lip sync and
front-camera mirroring.
