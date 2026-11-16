# qwen-asr (C inference for Qwen3-ASR)

Source: https://github.com/antirez/qwen-asr at `924694251d9e0f18e5d86bbd06aa3ab5f870002d`
(30 Sep 2026), MIT, Copyright (c) 2026 Salvatore Sanfilippo (LICENSE included).

Only the CPU inference files are vendored, unchanged: `qwen_asr*.c/.h` without
`main.c`, the CUDA/ROCm kernels, Python, tests and samples (`qwen_asr_cuda.h` and
`qwen_asr_rocm.h` are kept because `qwen_asr.c` includes them).

YouRoll builds it with `-DUSE_BLAS` against its own `cblas_sgemm` (`../qwen-blas`,
NEON-friendly, OpenMP threads) instead of Accelerate/OpenBLAS, behind a JNI wrapper
(`../QwenAsrJNI.c`), with `qwen_verbose = 0` (its `stderr` goes nowhere on Android; timings go to logcat).

Weights are NOT bundled. The Apache-2.0 `Qwen/Qwen3-ASR-0.6B` BF16 safetensors (same
model as iOS, which runs a 4-bit MLX conversion) are downloaded only on request,
verified by SHA-256 (`SubtitleModelPack.kt`). No audio or transcript leaves the device.
