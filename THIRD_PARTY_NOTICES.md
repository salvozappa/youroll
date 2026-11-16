# YouRoll third-party notices

YouRoll (this Android port) is based on YouRec by Salvatore Sanfilippo, under the
MIT licence: see `LICENSE` and `THIRD_PARTY_NOTICES.md` for the original
app and its components. This file lists what the Android app itself ships.

Salvatore Sanfilippo reserves the YouRec name and icon; YouRoll uses its own name and
icon. The third-party works below keep their own licences and notices, included next to
each work in `ThirdParty/` (or `android/ThirdParty/`).

## Title fonts

The Text tool bundles seven font families, the original unmodified TrueType
files from the Google Fonts distribution in `ThirdParty/TitleFonts` (exact
file identities in `ThirdParty/TitleFonts/SHA256SUMS`). All are licensed
under the **SIL Open Font License 1.1**. The build copies each family's folder,
with its `OFL.txt` (copyright notice and complete licence), into the APK at
`assets/TitleFonts/<family>/`, so every font travels with its licence.

| Family | Files | Licence |
| --- | --- | --- |
| Atkinson Hyperlegible Next | `atkinsonhyperlegiblenext/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/atkinsonhyperlegiblenext/OFL.txt) |
| Barlow Condensed | `barlowcondensed/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/barlowcondensed/OFL.txt) |
| Archivo Black | `archivoblack/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/archivoblack/OFL.txt) |
| Source Serif 4 | `sourceserif4/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/sourceserif4/OFL.txt) |
| Fraunces | `fraunces/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/fraunces/OFL.txt) |
| IBM Plex Mono | `ibmplexmono/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/ibmplexmono/OFL.txt) |
| Caveat | `caveat/*.ttf` | [OFL 1.1](ThirdParty/TitleFonts/caveat/OFL.txt) |

The app has no Credits screen yet (TODO.md, `CreditsView.swift`); until it has
one, the licences are only in the APK assets and in this repository.

## Enhance audio

- **RNNoise** v0.1 (Xiph.Org / Mozilla, Jean-Marc Valin), commit
  `cdf196b1e9de2f8ff1003328ebf9a4316477429d`, with its embedded weights and the
  iOS adapter `YouRecDenoiser.c`: `ThirdParty/RNNoise`, **BSD 3-clause**
  ([COPYING](ThirdParty/RNNoise/COPYING); provenance in
  [PROVENANCE.md](ThirdParty/RNNoise/PROVENANCE.md)). Built unchanged with the
  NDK into `libyouroll_audio.so`.
- **DeepFilterNet3** model, Hendrik Schröter, official ONNX export of release
  v0.5.6 (`enc.onnx`, `erb_dec.onnx`, `df_dec.onnx`): `android/ThirdParty/DeepFilterNet3-ONNX`,
  **MIT** ([LICENSE-MIT](android/ThirdParty/DeepFilterNet3-ONNX/LICENSE-MIT), identical to
  `ThirdParty/DeepFilterNet3/MODEL-LICENSE-MIT`) or Apache-2.0
  ([LICENSE-APACHE](android/ThirdParty/DeepFilterNet3-ONNX/LICENSE-APACHE)). Source and
  checksums in [PROVENANCE.md](android/ThirdParty/DeepFilterNet3-ONNX/PROVENANCE.md). The
  DSP parameters are `ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json`.
- **DeepFilterNet3 streaming runtime** (the DSP of `DeepFilterNetEngine.kt`,
  ported from `ThirdParty/DeepFilterNet3/DeepFilterNetEngine.swift`), Kyle
  Howells, DeepFilterNet-mlx, **MIT or Apache-2.0**
  (`ThirdParty/DeepFilterNet3/LICENSE-MIT`, `LICENSE-APACHE`, `NOTICE`).
- **ONNX Runtime** for Android (`com.microsoft.onnxruntime:onnxruntime-android`
  1.31.1), Microsoft, **MIT**.

## Subtitles

- **qwen-asr** (C inference for Qwen3-ASR), Salvatore Sanfilippo (antirez), commit
  `924694251d9e0f18e5d86bbd06aa3ab5f870002d` of https://github.com/antirez/qwen-asr,
  **MIT** ([LICENSE](android/app/src/main/cpp/qwen-asr/LICENSE); provenance in
  [PROVENANCE.md](android/app/src/main/cpp/qwen-asr/PROVENANCE.md)). The CPU sources are
  built unchanged with the NDK into `libyouroll_qwen_asr.so`, with YouRoll's own
  `cblas_sgemm` (`android/app/src/main/cpp/qwen-blas`, MIT) and LLVM's OpenMP runtime
  linked statically (Apache-2.0 with LLVM exceptions, part of the NDK).
- **Qwen3-ASR-0.6B** weights and tokenizer (`Qwen/Qwen3-ASR-0.6B`, revision
  `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`), Alibaba Qwen team, **Apache-2.0**.
  Not in the APK: downloaded only when the user asks (`SubtitleModelPack.kt`).

## Libraries

Android Jetpack (Compose, CameraX, Media3, AndroidX), kotlinx.serialization and
kotlinx.coroutines are used under the Apache License 2.0.
