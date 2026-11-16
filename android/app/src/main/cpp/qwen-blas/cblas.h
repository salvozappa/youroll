/*
 * Copyright (c) 2026 YouRoll contributors. MIT License.
 *
 * The one CBLAS routine qwen-asr calls (cblas_sgemm), for Android, where
 * neither Accelerate nor OpenBLAS is available. Implemented in youroll_sgemm.c.
 */
#ifndef YOUROLL_CBLAS_H
#define YOUROLL_CBLAS_H

#ifdef __cplusplus
extern "C" {
#endif

enum CBLAS_ORDER { CblasRowMajor = 101, CblasColMajor = 102 };
enum CBLAS_TRANSPOSE { CblasNoTrans = 111, CblasTrans = 112, CblasConjTrans = 113 };

void cblas_sgemm(enum CBLAS_ORDER order, enum CBLAS_TRANSPOSE transA, enum CBLAS_TRANSPOSE transB,
                 int M, int N, int K, float alpha, const float *A, int lda,
                 const float *B, int ldb, float beta, float *C, int ldc);

/* Threads used by cblas_sgemm (default: all online CPUs). */
void youroll_sgemm_set_threads(int n);

#ifdef __cplusplus
}
#endif

#endif
