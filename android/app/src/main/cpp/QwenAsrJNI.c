/*
 * Copyright (c) 2026 YouRoll contributors. MIT License.
 *
 * JNI wrapper for antirez's qwen-asr (qwen-asr/, MIT): load a local
 * Qwen3-ASR model directory, transcribe 16 kHz mono float PCM, free it.
 * Kotlin side: QwenASR in SubtitleTranscriber.kt. No network is used.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "qwen-asr/qwen_asr.h"
#include "qwen-asr/qwen_asr_kernels.h"
#include "qwen-blas/cblas.h"

#define TAG "QwenASR"

JNIEXPORT jlong JNICALL Java_com_salvozappa_youroll_QwenASR_nativeLoad(JNIEnv *env, jclass clazz, jstring directory, jint threads) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!path) return 0;
    qwen_verbose = 0;
    qwen_set_threads(threads);
    youroll_sgemm_set_threads(threads);
    qwen_ctx_t *ctx = qwen_load(path);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "The model could not be loaded");
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jboolean JNICALL Java_com_salvozappa_youroll_QwenASR_nativeSetLanguage(JNIEnv *env, jclass clazz, jlong handle, jstring language) {
    (void)clazz;
    qwen_ctx_t *ctx = (qwen_ctx_t *)(intptr_t)handle;
    if (!ctx) return JNI_FALSE;
    if (!language) return qwen_set_force_language(ctx, NULL) == 0;
    const char *name = (*env)->GetStringUTFChars(env, language, NULL);
    int result = qwen_set_force_language(ctx, name);
    (*env)->ReleaseStringUTFChars(env, language, name);
    return result == 0;
}

JNIEXPORT jbyteArray JNICALL Java_com_salvozappa_youroll_QwenASR_nativeTranscribe(JNIEnv *env, jclass clazz, jlong handle, jfloatArray samples) {
    (void)clazz;
    qwen_ctx_t *ctx = (qwen_ctx_t *)(intptr_t)handle;
    if (!ctx || !samples) return NULL;
    jsize count = (*env)->GetArrayLength(env, samples);
    float *values = (float *)malloc(sizeof(float) * (size_t)(count > 0 ? count : 1));
    if (!values) return NULL;
    (*env)->GetFloatArrayRegion(env, samples, 0, count, values);
    char *text = qwen_transcribe_audio(ctx, values, count);
    free(values);
    if (!text) return NULL;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%.1f s audio: encode %.0f ms, decode %.0f ms, %d tokens",
                        ctx->perf_audio_ms / 1000.0, ctx->perf_encode_ms, ctx->perf_decode_ms, ctx->perf_text_tokens);
    /* UTF-8 bytes: NewStringUTF expects modified UTF-8, which differs for 4-byte characters. */
    jsize length = (jsize)strlen(text);
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)text);
    free(text);
    return result;
}

JNIEXPORT void JNICALL Java_com_salvozappa_youroll_QwenASR_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    qwen_ctx_t *ctx = (qwen_ctx_t *)(intptr_t)handle;
    if (ctx) qwen_free(ctx);
    qwen_set_threads(1);
}
