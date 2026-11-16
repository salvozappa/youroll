/*
 * Copyright (c) 2026 YouRoll contributors. MIT License.
 *
 * Single-precision GEMM for qwen-asr on Android (row-major only, which is all
 * qwen-asr uses): C = alpha·op(A)·op(B) + beta·C.
 *
 * - A·Bᵀ (every linear layer: activations × weight rows) is computed in 4×4
 *   register tiles of dot products over contiguous rows, blocked over K so the
 *   eight rows stay in cache, with NEON fused multiply-adds on arm64.
 * - A·B (the convolution stem's im2col product) is row-wise AXPY.
 * - Tiles are spread over OpenMP threads.
 */
#include "cblas.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifdef _OPENMP
#include <omp.h>
#endif
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

static int threads = 0;

void youroll_sgemm_set_threads(int n) { threads = n; }

static int thread_count(void) {
    if (threads > 0) return threads;
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n > 0 ? (int)n : 1;
}

#define KB 256
#define TILE 64

/* C[i][j] += alpha * dot(A row i, B row j) over k0..k1 for a 4×4 tile. */
static void nt_micro4x4(const float *A, int lda, const float *B, int ldb, float *C, int ldc, int k0, int k1, float alpha) {
#ifdef __ARM_NEON
    float32x4_t acc[4][4];
    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) acc[i][j] = vdupq_n_f32(0);
    int k = k0;
    for (; k + 4 <= k1; k += 4) {
        float32x4_t a[4], b[4];
        for (int i = 0; i < 4; i++) a[i] = vld1q_f32(A + (size_t)i * lda + k);
        for (int j = 0; j < 4; j++) b[j] = vld1q_f32(B + (size_t)j * ldb + k);
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) acc[i][j] = vfmaq_f32(acc[i][j], a[i], b[j]);
    }
    float sum[4][4];
    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) sum[i][j] = vaddvq_f32(acc[i][j]);
#else
    float sum[4][4] = {{0}};
    int k = k0;
    for (; k + 4 <= k1; k += 4)
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++)
            for (int t = 0; t < 4; t++) sum[i][j] += A[(size_t)i * lda + k + t] * B[(size_t)j * ldb + k + t];
#endif
    for (; k < k1; k++)
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) sum[i][j] += A[(size_t)i * lda + k] * B[(size_t)j * ldb + k];
    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) C[(size_t)i * ldc + j] += alpha * sum[i][j];
}

static float dot(const float *a, const float *b, int n) {
    int k = 0;
#ifdef __ARM_NEON
    float32x4_t acc = vdupq_n_f32(0);
    for (; k + 4 <= n; k += 4) acc = vfmaq_f32(acc, vld1q_f32(a + k), vld1q_f32(b + k));
    float sum = vaddvq_f32(acc);
#else
    float sum = 0;
#endif
    for (; k < n; k++) sum += a[k] * b[k];
    return sum;
}

/* One TILE×TILE block of C = alpha·A·Bᵀ (+= into C). */
static void nt_tile(int M, int N, int K, float alpha, const float *A, int lda, const float *B, int ldb, float *C, int ldc, int m0, int n0) {
    int m1 = m0 + TILE < M ? m0 + TILE : M;
    int n1 = n0 + TILE < N ? n0 + TILE : N;
    for (int k0 = 0; k0 < K; k0 += KB) {
        int k1 = k0 + KB < K ? k0 + KB : K;
        int m = m0;
        for (; m + 4 <= m1; m += 4) {
            int n = n0;
            for (; n + 4 <= n1; n += 4)
                nt_micro4x4(A + (size_t)m * lda, lda, B + (size_t)n * ldb, ldb, C + (size_t)m * ldc + n, ldc, k0, k1, alpha);
            for (; n < n1; n++)
                for (int i = 0; i < 4; i++) C[(size_t)(m + i) * ldc + n] += alpha * dot(A + (size_t)(m + i) * lda + k0, B + (size_t)n * ldb + k0, k1 - k0);
        }
        for (; m < m1; m++)
            for (int n = n0; n < n1; n++) C[(size_t)m * ldc + n] += alpha * dot(A + (size_t)m * lda + k0, B + (size_t)n * ldb + k0, k1 - k0);
    }
}

void cblas_sgemm(enum CBLAS_ORDER order, enum CBLAS_TRANSPOSE transA, enum CBLAS_TRANSPOSE transB,
                 int M, int N, int K, float alpha, const float *A, int lda,
                 const float *B, int ldb, float beta, float *C, int ldc) {
    if (order != CblasRowMajor) abort();
    if (M <= 0 || N <= 0) return;
    /* C = beta·C first; every path below accumulates. */
    for (int m = 0; m < M; m++) {
        float *row = C + (size_t)m * ldc;
        if (beta == 0) memset(row, 0, sizeof(float) * (size_t)N);
        else if (beta != 1) for (int n = 0; n < N; n++) row[n] *= beta;
    }
    if (K <= 0 || alpha == 0) return;
    int nt = thread_count();
    if (transA == CblasNoTrans && transB != CblasNoTrans) {
        int mt = (M + TILE - 1) / TILE, ntiles = (N + TILE - 1) / TILE;
        long tiles = (long)mt * ntiles;
        #pragma omp parallel for schedule(dynamic, 1) num_threads(nt) if (tiles > 1 && (long)M * N * K > 200000)
        for (long t = 0; t < tiles; t++) {
            int m0 = (int)(t / ntiles) * TILE, n0 = (int)(t % ntiles) * TILE;
            nt_tile(M, N, K, alpha, A, lda, B, ldb, C, ldc, m0, n0);
        }
    } else if (transA == CblasNoTrans && transB == CblasNoTrans) {
        #pragma omp parallel for schedule(static) num_threads(nt) if ((long)M * N * K > 200000)
        for (int m = 0; m < M; m++) {
            float *c = C + (size_t)m * ldc;
            const float *a = A + (size_t)m * lda;
            for (int n0 = 0; n0 < N; n0 += 1024) {
                int n1 = n0 + 1024 < N ? n0 + 1024 : N;
                for (int k = 0; k < K; k++) {
                    float s = alpha * a[k];
                    if (s == 0) continue;
                    const float *b = B + (size_t)k * ldb;
                    for (int n = n0; n < n1; n++) c[n] += s * b[n];
                }
            }
        }
    } else {
        /* Not used by qwen-asr: plain loops. */
        #pragma omp parallel for schedule(static) num_threads(nt)
        for (int m = 0; m < M; m++)
            for (int n = 0; n < N; n++) {
                float sum = 0;
                for (int k = 0; k < K; k++) {
                    float a = transA == CblasNoTrans ? A[(size_t)m * lda + k] : A[(size_t)k * lda + m];
                    float b = transB == CblasNoTrans ? B[(size_t)k * ldb + n] : B[(size_t)n * ldb + k];
                    sum += a * b;
                }
                C[(size_t)m * ldc + n] += alpha * sum;
            }
    }
}
