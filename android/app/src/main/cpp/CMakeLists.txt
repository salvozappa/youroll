# Copyright (c) 2026 YouRoll contributors. MIT License.
# Native code of the Android port. Each library builds iOS or third-party
# sources unchanged, behind a small JNI wrapper.
cmake_minimum_required(VERSION 3.22.1)
project(youroll_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(IOS_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ThirdParty/YouRec)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ThirdParty)

# Editor stabilization: the iOS motion estimator, built unchanged from
# ../ThirdParty/YouRec/StabilizationMotion.cpp.
add_library(youroll_stabilization SHARED
    ${IOS_SOURCES}/StabilizationMotion.cpp
    StabilizationMotionJNI.cpp)
target_include_directories(youroll_stabilization PRIVATE ${IOS_SOURCES})
target_compile_options(youroll_stabilization PRIVATE -O2 -Wall)

# Enhance audio: RNNoise v0.1 (BSD, ../ThirdParty/RNNoise) and its iOS adapter
# YouRecDenoiser.c, for noise reduction and the Pauses speech probabilities.
add_library(youroll_audio SHARED
    ${THIRD_PARTY}/RNNoise/src/denoise.c
    ${THIRD_PARTY}/RNNoise/src/rnn.c
    ${THIRD_PARTY}/RNNoise/src/rnn_data.c
    ${THIRD_PARTY}/RNNoise/src/pitch.c
    ${THIRD_PARTY}/RNNoise/src/kiss_fft.c
    ${THIRD_PARTY}/RNNoise/src/celt_lpc.c
    ${THIRD_PARTY}/RNNoise/YouRecDenoiser.c
    DenoiserJNI.c)
target_include_directories(youroll_audio PRIVATE ${THIRD_PARTY}/RNNoise/include ${THIRD_PARTY}/RNNoise)
# Xcode builds the adapter with -O3. Upstream's own warnings are left unpatched.
target_compile_options(youroll_audio PRIVATE -O3 -w)
target_link_libraries(youroll_audio m)

# Subtitles: antirez's qwen-asr (MIT, qwen-asr/PROVENANCE.md), the C inference
# of the Qwen3-ASR model iOS runs on MLX, with YouRoll's cblas_sgemm and a JNI wrapper.
set(QWEN_ASR ${CMAKE_CURRENT_SOURCE_DIR}/qwen-asr)
add_library(youroll_qwen_asr SHARED
    ${QWEN_ASR}/qwen_asr.c
    ${QWEN_ASR}/qwen_asr_kernels.c
    ${QWEN_ASR}/qwen_asr_kernels_generic.c
    ${QWEN_ASR}/qwen_asr_kernels_neon.c
    ${QWEN_ASR}/qwen_asr_kernels_avx.c
    ${QWEN_ASR}/qwen_asr_audio.c
    ${QWEN_ASR}/qwen_asr_encoder.c
    ${QWEN_ASR}/qwen_asr_decoder.c
    ${QWEN_ASR}/qwen_asr_tokenizer.c
    ${QWEN_ASR}/qwen_asr_safetensors.c
    qwen-blas/youroll_sgemm.c
    QwenAsrJNI.c)
target_include_directories(youroll_qwen_asr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/qwen-blas ${QWEN_ASR} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(youroll_qwen_asr PRIVATE USE_BLAS)
# OpenMP threads for the GEMM, linked statically (no libomp.so in the APK).
target_compile_options(youroll_qwen_asr PRIVATE -O3 -ffast-math -w -fopenmp)
target_link_options(youroll_qwen_asr PRIVATE -fopenmp -static-openmp)
target_link_libraries(youroll_qwen_asr PRIVATE log m)
