// Copyright (c) 2026 YouRoll contributors. MIT License.
// JNI wrapper of ../ThirdParty/RNNoise/YouRecDenoiser.c (the iOS adapter,
// unchanged) for Denoiser.kt. Samples are copied in and out per 480-frame block.
#include <jni.h>
#include <stdint.h>
#include "YouRecDenoiser.h"

JNIEXPORT jlong JNICALL Java_com_salvozappa_youroll_Denoiser_nativeCreate(JNIEnv *env, jclass type) {
    return (jlong)(intptr_t)yr_denoiser_create();
}

JNIEXPORT void JNICALL Java_com_salvozappa_youroll_Denoiser_nativeDestroy(JNIEnv *env, jclass type, jlong state) {
    yr_denoiser_destroy((void *)(intptr_t)state);
}

// One 480-sample mono frame of [input] from [offset] into [output] from [outputOffset].
JNIEXPORT void JNICALL Java_com_salvozappa_youroll_Denoiser_nativeProcess(JNIEnv *env, jclass type, jlong state,
        jfloatArray input, jint offset, jfloatArray output, jint outputOffset) {
    float in[480], out[480];
    (*env)->GetFloatArrayRegion(env, input, offset, 480, in);
    yr_denoiser_process((void *)(intptr_t)state, in, out);
    (*env)->SetFloatArrayRegion(env, output, outputOffset, 480, out);
}

// Speech probability of one channel of a 480-frame interleaved block: [offset]
// is that channel's first sample, [stride] the channel count (at most 8).
JNIEXPORT jfloat JNICALL Java_com_salvozappa_youroll_Denoiser_nativeSpeechProbability(JNIEnv *env, jclass type, jlong state,
        jfloatArray input, jint offset, jint stride) {
    float frame[480 * 8];
    if (stride < 1 || stride > 8) return 0;
    (*env)->GetFloatArrayRegion(env, input, offset, 479 * stride + 1, frame);
    return yr_speech_probability((void *)(intptr_t)state, frame, stride);
}
