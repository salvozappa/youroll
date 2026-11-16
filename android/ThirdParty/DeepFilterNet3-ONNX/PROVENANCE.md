# DeepFilterNet3 (ONNX) for YouRoll's Enhance audio

iOS runs DeepFilterNet3 as a stateful Core ML conversion
(`../../../ThirdParty/DeepFilterNet3`), which Android cannot load. YouRoll uses the
official ONNX export of the same model published by the DeepFilterNet project.

## Source

- Project: https://github.com/Rikorose/DeepFilterNet (Hendrik Schröter), dual
  licensed MIT / Apache-2.0.
- File: `models/DeepFilterNet3_onnx.tar.gz` in the tree of the release tag
  **v0.5.6** (2023-08-31; the archive itself is dated 2023-05-23), downloaded on
  2026-11-16 from
  `https://github.com/Rikorose/DeepFilterNet/raw/v0.5.6/models/DeepFilterNet3_onnx.tar.gz`.
  The GitHub release has no model asset; the tagged tree is where the project
  publishes it (the Rust `deep_filter` crate embeds the same archive as its default model).
- Archive SHA-256: `c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616`.
- Licences: `LICENSE-MIT` and `LICENSE-APACHE` here, downloaded from the same tag.
  `LICENSE-MIT` is byte-identical to the iOS `MODEL-LICENSE-MIT`
  (SHA-256 `24e6bb09c928af8d8e56268082f87413247ce36b39dd5d33add2f9893968065e`).

## Files (extracted unmodified from `tmp/export/` in the archive)

| File | SHA-256 |
| --- | --- |
| `enc.onnx` | `7c5399d3da8a50ebef1c1a0ae421b33376aa5e45d0e92df16da7e83c9c131916` |
| `erb_dec.onnx` | `ab669a1d10afe20911728b33053a452071042317a90581092b325da7b2f9d895` |
| `df_dec.onnx` | `23114ce3b0f6464b763ee62f7bb8aab6b2a129a21eabd5bcfe59413db05f278a` |
| `config.ini` | `415eb925d44990d938fb739f514aa3662c1ec0ea836cff044fa1291b82cb4290` |

`config.ini` (not shipped) records the model parameters, which match the iOS DSP
JSON: 48 kHz, FFT 960, hop 480, 32 ERB bands, 96 deep-filter bins, order 5,
deep-filter lookahead 2, conv lookahead 2. The app checks the three `.onnx`
hashes (`OnnxDeepFilterNetModel.onnxSHA256`) and the iOS DSP JSON hash before loading.

## Integration

- The build copies the three graphs and `../../../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json`
  into `assets/DeepFilterNet3` (task `deepFilterAssets`), about 8.6 MB.
- ONNX Runtime for Android 1.31.1 (MIT) runs them on the CPU, two threads. Its
  arm64 library adds about 34 MB to the APK; the x86_64 one is excluded.
- `DeepFilterNetEngine.kt` keeps the iOS DSP (STFT, ERB features and
  normalisations, mask, deep filter, overlap-add, delay and drain). The batch
  graphs take the features shifted by the two conv-lookahead frames and run on
  blocks of 1000 frames (10 s) with 300 frames of warm-up context, because the
  export keeps its GRU state internal.
- Checked on the Mac (2026-11-16) against the iOS Core ML model run hop by hop
  through the same DSP, on a 4 s harmonic voice with noise: identical alignment
  (lag 0), 1.3 % relative difference in voiced parts, noise in pauses −70.7 dB
  (ONNX) vs −65.2 dB (Core ML) from −34 dB. Core ML computes in float16, ONNX in float32.
