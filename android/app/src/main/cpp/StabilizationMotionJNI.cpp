// Copyright (c) 2026 YouRoll contributors. MIT License.
//
// JNI binding of YouRec/StabilizationMotion.h (`yr_estimate_motion`, unchanged)
// and the luma downsampling that VideoStabilizer.swift does with
// vImageScale_Planar8. Kotlin side: StabilizationMotion.kt.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include "StabilizationMotion.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_salvozappa_youroll_StabilizationMotion_nativeEstimate(JNIEnv *env, jclass, jobject previous, jobject current,
                                                  jint width, jint height, jdoubleArray result) {
    auto *a = static_cast<const uint8_t *>(env->GetDirectBufferAddress(previous));
    auto *b = static_cast<const uint8_t *>(env->GetDirectBufferAddress(current));
    if (!a || !b || env->GetArrayLength(result) < 6) return JNI_FALSE;
    YRMotion motion = yr_estimate_motion(a, b, width, height);
    jdouble values[6] = {motion.x, motion.y, motion.angle, motion.logScale, motion.inlierRatio, double(motion.inliers)};
    env->SetDoubleArrayRegion(result, 0, 6, values);
    return motion.valid ? JNI_TRUE : JNI_FALSE;
}

// Area-average resampling of one 8-bit plane (a [cropWidth]x[cropHeight]
// window at [left],[top] with [rowStride] and [pixelStride]) to a dense
// [outWidth]x[outHeight] buffer. Separable box filter with fractional edges.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salvozappa_youroll_StabilizationMotion_nativeDownsample(JNIEnv *env, jclass, jobject plane, jint rowStride, jint pixelStride,
                                                    jint left, jint top, jint cropWidth, jint cropHeight,
                                                    jobject output, jint outWidth, jint outHeight) {
    auto *source = static_cast<const uint8_t *>(env->GetDirectBufferAddress(plane));
    auto *destination = static_cast<uint8_t *>(env->GetDirectBufferAddress(output));
    jlong capacity = env->GetDirectBufferCapacity(plane);
    if (!source || !destination || cropWidth <= 0 || cropHeight <= 0 || outWidth <= 0 || outHeight <= 0) return JNI_FALSE;
    if (env->GetDirectBufferCapacity(output) < jlong(outWidth) * outHeight) return JNI_FALSE;
    if (capacity < jlong(top + cropHeight - 1) * rowStride + jlong(left + cropWidth - 1) * pixelStride + 1) return JNI_FALSE;
    struct Tap { int index; float weight; };
    auto taps = [](int input, int output) {
        std::vector<std::vector<Tap>> result(output);
        double ratio = double(input) / output;
        for (int o = 0; o < output; ++o) {
            double a = o * ratio, b = (o + 1) * ratio;
            for (int i = int(std::floor(a)); i < std::min(input, int(std::ceil(b))); ++i) {
                double weight = std::min(b, double(i + 1)) - std::max(a, double(i));
                if (weight > 0) result[o].push_back({i, float(weight / ratio)});
            }
        }
        return result;
    };
    auto horizontal = taps(cropWidth, outWidth), vertical = taps(cropHeight, outHeight);
    std::vector<float> rows(size_t(cropHeight) * outWidth);
    for (int y = 0; y < cropHeight; ++y) {
        const uint8_t *row = source + size_t(top + y) * rowStride + size_t(left) * pixelStride;
        for (int x = 0; x < outWidth; ++x) {
            float sum = 0;
            for (const Tap &tap : horizontal[x]) sum += row[size_t(tap.index) * pixelStride] * tap.weight;
            rows[size_t(y) * outWidth + x] = sum;
        }
    }
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            float sum = 0;
            for (const Tap &tap : vertical[y]) sum += rows[size_t(tap.index) * outWidth + x] * tap.weight;
            destination[size_t(y) * outWidth + x] = uint8_t(std::min(255.0f, std::max(0.0f, std::round(sum))));
        }
    }
    return JNI_TRUE;
}
