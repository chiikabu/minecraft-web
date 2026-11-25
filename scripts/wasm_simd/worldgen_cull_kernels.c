/*
 * Hybrid Worldgen & Client-Culling WASM-SIMD128 Kernels for Minecraft 26.3 (TeaVM)
 *
 * Designed to execute ZERO-COPY directly over TeaVM's exported linear memory
 * (`globalThis.__teavmMemory`) with zero SharedArrayBuffer requirement and
 * 100% single-HTML offline compatibility.
 *
 * ABI / Memory-Safety Guarantees:
 * - ZERO static `.data`, `.rodata`, or `.bss` segments (never collides with
 *   TeaVM's linear heap when instantiated with `{ env: { memory } }`).
 * - ZERO mutable globals (not even `__stack_pointer` spills where avoidable;
 *   only WASM locals / registers and caller-supplied linear-memory pointers).
 * - ZERO libc / libm imports (`-ffreestanding -fno-builtin`).
 * - Dual-target: compiles identically for both
 *     1) `clang-21 --target=wasm32 -msimd128 -O3 -ffreestanding -fno-builtin -c`
 *     2) `clang-21 -O3 -shared -fPIC` (native x86_64 parity & benchmark testing)
 *   using a single unified SIMD implementation across all 7 kernels.
 *
 * Exported Kernels:
 *   1. simd_improved_noise_batch   - Exact Minecraft ImprovedNoise (f64x2)
 *   2. simd_aquifer_voronoi_batch  - Exact Aquifer 12-neighbor top-4 Voronoi (i32x4)
 *   3. simd_beardifier_volume      - Exact Beardifier rigids + junctions (f32x4 + f64x2)
 *   4. simd_biome_fiddle_batch     - Exact BiomeManager 8-corner LCG + Voronoi (i64x2 + f64x2)
 *   5. simd_cull_aabb_frustum_fog  - 4-wide AABB 6-plane frustum + sph/cyl fog cull (f32x4)
 *   6. simd_sort_translucent_quads - 4-wide 28B BLOCK quad centroid distSq + stable in-place
 *                                    back-to-front sort + 6-index quad expansion (f32x4)
 *   7. simd_mat4_mul_batch         - Column-major JOML Matrix4f batch multiply (f32x4)
 */

#pragma STDC FP_CONTRACT OFF

#include <stdint.h>
#include <stddef.h>

#if defined(__wasm__)
#define KERNEL_EXPORT(name) __attribute__((export_name(#name), used))
#else
#define KERNEL_EXPORT(name) __attribute__((visibility("default")))
#endif

/* ============================================================================
 * Portable 128-bit SIMD Abstraction
 *   - On wasm32 + simd128: maps 1-to-1 to <wasm_simd128.h> instructions.
 *   - On native x86_64:    bit-for-bit identical 128-bit lane semantics so the
 *                          native .so self-check verifies the exact same code.
 * ============================================================================ */

#if defined(__wasm_simd128__)

#include <wasm_simd128.h>

typedef v128_t k_v128_t;

static inline k_v128_t k_v128_load(const void *p) { return wasm_v128_load(p); }
static inline void k_v128_store(void *p, k_v128_t v) { wasm_v128_store(p, v); }
static inline k_v128_t k_v128_and(k_v128_t a, k_v128_t b) { return wasm_v128_and(a, b); }
static inline k_v128_t k_v128_bitselect(k_v128_t a, k_v128_t b, k_v128_t mask) {
    return wasm_v128_bitselect(a, b, mask);
}

/* f32x4 */
static inline k_v128_t k_f32x4_splat(float x) { return wasm_f32x4_splat(x); }
static inline k_v128_t k_f32x4_make(float a, float b, float c, float d) {
    return wasm_f32x4_make(a, b, c, d);
}
static inline k_v128_t k_f32x4_add(k_v128_t a, k_v128_t b) { return wasm_f32x4_add(a, b); }
static inline k_v128_t k_f32x4_sub(k_v128_t a, k_v128_t b) { return wasm_f32x4_sub(a, b); }
static inline k_v128_t k_f32x4_mul(k_v128_t a, k_v128_t b) { return wasm_f32x4_mul(a, b); }
static inline k_v128_t k_f32x4_div(k_v128_t a, k_v128_t b) { return wasm_f32x4_div(a, b); }
static inline k_v128_t k_f32x4_neg(k_v128_t a) { return wasm_f32x4_neg(a); }
static inline k_v128_t k_f32x4_sqrt(k_v128_t a) { return wasm_f32x4_sqrt(a); }
static inline k_v128_t k_f32x4_max(k_v128_t a, k_v128_t b) { return wasm_f32x4_max(a, b); }
static inline k_v128_t k_f32x4_lt(k_v128_t a, k_v128_t b) { return wasm_f32x4_lt(a, b); }
static inline k_v128_t k_f32x4_le(k_v128_t a, k_v128_t b) { return wasm_f32x4_le(a, b); }
static inline k_v128_t k_f32x4_ge(k_v128_t a, k_v128_t b) { return wasm_f32x4_ge(a, b); }
static inline k_v128_t k_f32x4_convert_i32x4(k_v128_t a) { return wasm_f32x4_convert_i32x4(a); }
static inline float k_f32x4_lane0(k_v128_t a) { return wasm_f32x4_extract_lane(a, 0); }
static inline k_v128_t k_f32x4_splat_lane0(k_v128_t a) { return wasm_i32x4_shuffle(a, a, 0, 0, 0, 0); }
static inline k_v128_t k_f32x4_splat_lane1(k_v128_t a) { return wasm_i32x4_shuffle(a, a, 1, 1, 1, 1); }
static inline k_v128_t k_f32x4_splat_lane2(k_v128_t a) { return wasm_i32x4_shuffle(a, a, 2, 2, 2, 2); }
static inline k_v128_t k_f32x4_splat_lane3(k_v128_t a) { return wasm_i32x4_shuffle(a, a, 3, 3, 3, 3); }

/* f64x2 */
static inline k_v128_t k_f64x2_splat(double x) { return wasm_f64x2_splat(x); }
static inline k_v128_t k_f64x2_make(double a, double b) { return wasm_f64x2_make(a, b); }
static inline k_v128_t k_f64x2_add(k_v128_t a, k_v128_t b) { return wasm_f64x2_add(a, b); }
static inline k_v128_t k_f64x2_sub(k_v128_t a, k_v128_t b) { return wasm_f64x2_sub(a, b); }
static inline k_v128_t k_f64x2_mul(k_v128_t a, k_v128_t b) { return wasm_f64x2_mul(a, b); }
static inline k_v128_t k_f64x2_floor(k_v128_t a) { return wasm_f64x2_floor(a); }
static inline k_v128_t k_f64x2_lt(k_v128_t a, k_v128_t b) { return wasm_f64x2_lt(a, b); }
static inline k_v128_t k_f64x2_convert_i32_pair(int32_t a, int32_t b) {
    return wasm_f64x2_convert_low_i32x4(wasm_i32x4_make(a, b, 0, 0));
}
static inline k_v128_t k_f64x2_promote_low_f32x4(k_v128_t a) { return wasm_f64x2_promote_low_f32x4(a); }
static inline k_v128_t k_f64x2_promote_high_f32x4(k_v128_t a) {
    return wasm_f64x2_promote_low_f32x4(wasm_i32x4_shuffle(a, a, 2, 3, 2, 3));
}
static inline k_v128_t k_f32x4_demote_pair(k_v128_t lo, k_v128_t hi) {
    k_v128_t f_lo = wasm_f32x4_demote_f64x2_zero(lo);
    k_v128_t f_hi = wasm_f32x4_demote_f64x2_zero(hi);
    return wasm_i32x4_shuffle(f_lo, f_hi, 0, 1, 4, 5);
}
static inline double k_f64x2_lane0(k_v128_t a) { return wasm_f64x2_extract_lane(a, 0); }
static inline double k_f64x2_lane1(k_v128_t a) { return wasm_f64x2_extract_lane(a, 1); }

/* i32x4 */
static inline k_v128_t k_i32x4_splat(int32_t x) { return wasm_i32x4_splat(x); }
static inline k_v128_t k_i32x4_make(int32_t a, int32_t b, int32_t c, int32_t d) {
    return wasm_i32x4_make(a, b, c, d);
}
static inline k_v128_t k_i32x4_add(k_v128_t a, k_v128_t b) { return wasm_i32x4_add(a, b); }
static inline k_v128_t k_i32x4_sub(k_v128_t a, k_v128_t b) { return wasm_i32x4_sub(a, b); }
static inline k_v128_t k_i32x4_mul(k_v128_t a, k_v128_t b) { return wasm_i32x4_mul(a, b); }
static inline k_v128_t k_i32x4_max(k_v128_t a, k_v128_t b) { return wasm_i32x4_max(a, b); }
static inline int32_t k_i32x4_bitmask(k_v128_t a) { return wasm_i32x4_bitmask(a); }
static inline int32_t k_i32x4_lane0(k_v128_t a) { return wasm_i32x4_extract_lane(a, 0); }
static inline int32_t k_i32x4_lane1(k_v128_t a) { return wasm_i32x4_extract_lane(a, 1); }
static inline int32_t k_i32x4_lane2(k_v128_t a) { return wasm_i32x4_extract_lane(a, 2); }
static inline int32_t k_i32x4_lane3(k_v128_t a) { return wasm_i32x4_extract_lane(a, 3); }
#define K_I32X4_SHUFFLE(a, b, c0, c1, c2, c3) wasm_i32x4_shuffle((a), (b), (c0), (c1), (c2), (c3))

/* i64x2 */
static inline k_v128_t k_i64x2_splat(int64_t x) { return wasm_i64x2_splat(x); }
static inline k_v128_t k_i64x2_make(int64_t a, int64_t b) { return wasm_i64x2_make(a, b); }
static inline k_v128_t k_i64x2_add(k_v128_t a, k_v128_t b) { return wasm_i64x2_add(a, b); }
static inline k_v128_t k_i64x2_sub(k_v128_t a, k_v128_t b) { return wasm_i64x2_sub(a, b); }
static inline k_v128_t k_i64x2_mul(k_v128_t a, k_v128_t b) { return wasm_i64x2_mul(a, b); }
static inline k_v128_t k_i64x2_shr_s(k_v128_t a, uint32_t s) { return wasm_i64x2_shr(a, s); }
static inline k_v128_t k_u64x2_shr(k_v128_t a, uint32_t s) { return wasm_u64x2_shr(a, s); }
static inline int64_t k_i64x2_lane0(k_v128_t a) { return wasm_i64x2_extract_lane(a, 0); }
static inline int64_t k_i64x2_lane1(k_v128_t a) { return wasm_i64x2_extract_lane(a, 1); }

#else /* Native host build (x86_64 / aarch64) - bit-exact mirror of WASM SIMD128 */

typedef union {
    float f32[4];
    double f64[2];
    int32_t i32[4];
    uint32_t u32[4];
    int64_t i64[2];
    uint64_t u64[2];
} k_v128_t;

static inline k_v128_t k_v128_load(const void *p) {
    k_v128_t r;
    const uint32_t *s = (const uint32_t *)p;
    r.u32[0] = s[0]; r.u32[1] = s[1]; r.u32[2] = s[2]; r.u32[3] = s[3];
    return r;
}
static inline void k_v128_store(void *p, k_v128_t v) {
    uint32_t *d = (uint32_t *)p;
    d[0] = v.u32[0]; d[1] = v.u32[1]; d[2] = v.u32[2]; d[3] = v.u32[3];
}
static inline k_v128_t k_v128_and(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    r.u64[0] = a.u64[0] & b.u64[0];
    r.u64[1] = a.u64[1] & b.u64[1];
    return r;
}
static inline k_v128_t k_v128_bitselect(k_v128_t a, k_v128_t b, k_v128_t mask) {
    k_v128_t r;
    r.u64[0] = (a.u64[0] & mask.u64[0]) | (b.u64[0] & ~mask.u64[0]);
    r.u64[1] = (a.u64[1] & mask.u64[1]) | (b.u64[1] & ~mask.u64[1]);
    return r;
}

/* f32x4 */
static inline k_v128_t k_f32x4_splat(float x) {
    k_v128_t r; r.f32[0] = x; r.f32[1] = x; r.f32[2] = x; r.f32[3] = x; return r;
}
static inline k_v128_t k_f32x4_make(float a, float b, float c, float d) {
    k_v128_t r; r.f32[0] = a; r.f32[1] = b; r.f32[2] = c; r.f32[3] = d; return r;
}
static inline k_v128_t k_f32x4_add(k_v128_t a, k_v128_t b) {
    return k_f32x4_make(a.f32[0]+b.f32[0], a.f32[1]+b.f32[1], a.f32[2]+b.f32[2], a.f32[3]+b.f32[3]);
}
static inline k_v128_t k_f32x4_sub(k_v128_t a, k_v128_t b) {
    return k_f32x4_make(a.f32[0]-b.f32[0], a.f32[1]-b.f32[1], a.f32[2]-b.f32[2], a.f32[3]-b.f32[3]);
}
static inline k_v128_t k_f32x4_mul(k_v128_t a, k_v128_t b) {
    return k_f32x4_make(a.f32[0]*b.f32[0], a.f32[1]*b.f32[1], a.f32[2]*b.f32[2], a.f32[3]*b.f32[3]);
}
static inline k_v128_t k_f32x4_div(k_v128_t a, k_v128_t b) {
    return k_f32x4_make(a.f32[0]/b.f32[0], a.f32[1]/b.f32[1], a.f32[2]/b.f32[2], a.f32[3]/b.f32[3]);
}
static inline k_v128_t k_f32x4_neg(k_v128_t a) {
    return k_f32x4_make(-a.f32[0], -a.f32[1], -a.f32[2], -a.f32[3]);
}
static inline k_v128_t k_f32x4_sqrt(k_v128_t a) {
    return k_f32x4_make(__builtin_sqrtf(a.f32[0]), __builtin_sqrtf(a.f32[1]),
                        __builtin_sqrtf(a.f32[2]), __builtin_sqrtf(a.f32[3]));
}
static inline k_v128_t k_f32x4_max(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.f32[i] = (a.f32[i] >= b.f32[i]) ? a.f32[i] : b.f32[i];
    return r;
}
static inline k_v128_t k_f32x4_lt(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.u32[i] = (a.f32[i] < b.f32[i]) ? 0xFFFFFFFFu : 0u;
    return r;
}
static inline k_v128_t k_f32x4_le(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.u32[i] = (a.f32[i] <= b.f32[i]) ? 0xFFFFFFFFu : 0u;
    return r;
}
static inline k_v128_t k_f32x4_ge(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.u32[i] = (a.f32[i] >= b.f32[i]) ? 0xFFFFFFFFu : 0u;
    return r;
}
static inline k_v128_t k_f32x4_convert_i32x4(k_v128_t a) {
    return k_f32x4_make((float)a.i32[0], (float)a.i32[1], (float)a.i32[2], (float)a.i32[3]);
}
static inline float k_f32x4_lane0(k_v128_t a) { return a.f32[0]; }
static inline k_v128_t k_f32x4_splat_lane0(k_v128_t a) { return k_f32x4_splat(a.f32[0]); }
static inline k_v128_t k_f32x4_splat_lane1(k_v128_t a) { return k_f32x4_splat(a.f32[1]); }
static inline k_v128_t k_f32x4_splat_lane2(k_v128_t a) { return k_f32x4_splat(a.f32[2]); }
static inline k_v128_t k_f32x4_splat_lane3(k_v128_t a) { return k_f32x4_splat(a.f32[3]); }

/* f64x2 */
static inline k_v128_t k_f64x2_splat(double x) { k_v128_t r; r.f64[0] = x; r.f64[1] = x; return r; }
static inline k_v128_t k_f64x2_make(double a, double b) { k_v128_t r; r.f64[0] = a; r.f64[1] = b; return r; }
static inline k_v128_t k_f64x2_add(k_v128_t a, k_v128_t b) {
    return k_f64x2_make(a.f64[0] + b.f64[0], a.f64[1] + b.f64[1]);
}
static inline k_v128_t k_f64x2_sub(k_v128_t a, k_v128_t b) {
    return k_f64x2_make(a.f64[0] - b.f64[0], a.f64[1] - b.f64[1]);
}
static inline k_v128_t k_f64x2_mul(k_v128_t a, k_v128_t b) {
    return k_f64x2_make(a.f64[0] * b.f64[0], a.f64[1] * b.f64[1]);
}
static inline k_v128_t k_f64x2_floor(k_v128_t a) {
    return k_f64x2_make(__builtin_floor(a.f64[0]), __builtin_floor(a.f64[1]));
}
static inline k_v128_t k_f64x2_lt(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    r.u64[0] = (a.f64[0] < b.f64[0]) ? 0xFFFFFFFFFFFFFFFFULL : 0ULL;
    r.u64[1] = (a.f64[1] < b.f64[1]) ? 0xFFFFFFFFFFFFFFFFULL : 0ULL;
    return r;
}
static inline k_v128_t k_f64x2_convert_i32_pair(int32_t a, int32_t b) {
    return k_f64x2_make((double)a, (double)b);
}
static inline k_v128_t k_f64x2_promote_low_f32x4(k_v128_t a) {
    return k_f64x2_make((double)a.f32[0], (double)a.f32[1]);
}
static inline k_v128_t k_f64x2_promote_high_f32x4(k_v128_t a) {
    return k_f64x2_make((double)a.f32[2], (double)a.f32[3]);
}
static inline k_v128_t k_f32x4_demote_pair(k_v128_t lo, k_v128_t hi) {
    return k_f32x4_make((float)lo.f64[0], (float)lo.f64[1], (float)hi.f64[0], (float)hi.f64[1]);
}
static inline double k_f64x2_lane0(k_v128_t a) { return a.f64[0]; }
static inline double k_f64x2_lane1(k_v128_t a) { return a.f64[1]; }

/* i32x4 */
static inline k_v128_t k_i32x4_splat(int32_t x) {
    k_v128_t r; r.i32[0] = x; r.i32[1] = x; r.i32[2] = x; r.i32[3] = x; return r;
}
static inline k_v128_t k_i32x4_make(int32_t a, int32_t b, int32_t c, int32_t d) {
    k_v128_t r; r.i32[0] = a; r.i32[1] = b; r.i32[2] = c; r.i32[3] = d; return r;
}
static inline k_v128_t k_i32x4_add(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.u32[i] = a.u32[i] + b.u32[i];
    return r;
}
static inline k_v128_t k_i32x4_sub(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.u32[i] = a.u32[i] - b.u32[i];
    return r;
}
static inline k_v128_t k_i32x4_mul(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.u32[i] = a.u32[i] * b.u32[i];
    return r;
}
static inline k_v128_t k_i32x4_max(k_v128_t a, k_v128_t b) {
    k_v128_t r;
    for (int i = 0; i < 4; i++) r.i32[i] = (a.i32[i] > b.i32[i]) ? a.i32[i] : b.i32[i];
    return r;
}
static inline int32_t k_i32x4_bitmask(k_v128_t a) {
    return (int32_t)(((a.u32[0] >> 31) & 1u)
                   | (((a.u32[1] >> 31) & 1u) << 1)
                   | (((a.u32[2] >> 31) & 1u) << 2)
                   | (((a.u32[3] >> 31) & 1u) << 3));
}
static inline int32_t k_i32x4_lane0(k_v128_t a) { return a.i32[0]; }
static inline int32_t k_i32x4_lane1(k_v128_t a) { return a.i32[1]; }
static inline int32_t k_i32x4_lane2(k_v128_t a) { return a.i32[2]; }
static inline int32_t k_i32x4_lane3(k_v128_t a) { return a.i32[3]; }
static inline k_v128_t k_i32x4_shuffle_fn(k_v128_t a, k_v128_t b, int c0, int c1, int c2, int c3) {
    k_v128_t r;
    r.i32[0] = (c0 < 4) ? a.i32[c0] : b.i32[c0 - 4];
    r.i32[1] = (c1 < 4) ? a.i32[c1] : b.i32[c1 - 4];
    r.i32[2] = (c2 < 4) ? a.i32[c2] : b.i32[c2 - 4];
    r.i32[3] = (c3 < 4) ? a.i32[c3] : b.i32[c3 - 4];
    return r;
}
#define K_I32X4_SHUFFLE(a, b, c0, c1, c2, c3) k_i32x4_shuffle_fn((a), (b), (c0), (c1), (c2), (c3))

/* i64x2 */
static inline k_v128_t k_i64x2_splat(int64_t x) { k_v128_t r; r.i64[0] = x; r.i64[1] = x; return r; }
static inline k_v128_t k_i64x2_make(int64_t a, int64_t b) { k_v128_t r; r.i64[0] = a; r.i64[1] = b; return r; }
static inline k_v128_t k_i64x2_add(k_v128_t a, k_v128_t b) {
    k_v128_t r; r.u64[0] = a.u64[0] + b.u64[0]; r.u64[1] = a.u64[1] + b.u64[1]; return r;
}
static inline k_v128_t k_i64x2_sub(k_v128_t a, k_v128_t b) {
    k_v128_t r; r.u64[0] = a.u64[0] - b.u64[0]; r.u64[1] = a.u64[1] - b.u64[1]; return r;
}
static inline k_v128_t k_i64x2_mul(k_v128_t a, k_v128_t b) {
    k_v128_t r; r.u64[0] = a.u64[0] * b.u64[0]; r.u64[1] = a.u64[1] * b.u64[1]; return r;
}
static inline k_v128_t k_i64x2_shr_s(k_v128_t a, uint32_t s) {
    k_v128_t r; r.i64[0] = a.i64[0] >> s; r.i64[1] = a.i64[1] >> s; return r;
}
static inline k_v128_t k_u64x2_shr(k_v128_t a, uint32_t s) {
    k_v128_t r; r.u64[0] = a.u64[0] >> s; r.u64[1] = a.u64[1] >> s; return r;
}
static inline int64_t k_i64x2_lane0(k_v128_t a) { return a.i64[0]; }
static inline int64_t k_i64x2_lane1(k_v128_t a) { return a.i64[1]; }

#endif /* __wasm_simd128__ */


/* ============================================================================
 * KERNEL 1: simd_improved_noise_batch
 * Exact Minecraft ImprovedNoise.noise(x, y, z) over a 3D DensityVolume grid.
 *
 * Output indexing matches Minecraft 26.3 DensityVolume:
 *   out[(iz * nx + ix) * ny + iy]
 * Coordinates sampled:
 *   sx = (x0 + ix * dx) * freq + xo
 *   sy = (y0 + iy * dy) * freq + yo
 *   sz = (z0 + iz * dz) * freq + zo
 *   val = ImprovedNoise.sampleAndLerp(sx, sy, sz) * amp
 *   accumulate == 0 -> out[idx] = (float)val
 *   accumulate != 0 -> out[idx] += (float)val
 * ============================================================================ */

/* SimplexNoise.GRADIENT[0..15] encoded as immediate bitmasks (zero .rodata!) */
#define MC_GX_POS ((1u<<0)|(1u<<2)|(1u<<4)|(1u<<6)|(1u<<12))
#define MC_GX_NEG ((1u<<1)|(1u<<3)|(1u<<5)|(1u<<7)|(1u<<14))
#define MC_GY_POS ((1u<<0)|(1u<<1)|(1u<<8)|(1u<<10)|(1u<<12)|(1u<<14))
#define MC_GY_NEG ((1u<<2)|(1u<<3)|(1u<<9)|(1u<<11)|(1u<<13)|(1u<<15))
#define MC_GZ_POS ((1u<<4)|(1u<<5)|(1u<<8)|(1u<<9)|(1u<<13))
#define MC_GZ_NEG ((1u<<6)|(1u<<7)|(1u<<10)|(1u<<11)|(1u<<15))

static inline double mc_grad_dot_scalar(uint32_t hash, double x, double y, double z) {
    uint32_t h = hash & 15u;
    int32_t gx = (int32_t)((MC_GX_POS >> h) & 1u) - (int32_t)((MC_GX_NEG >> h) & 1u);
    int32_t gy = (int32_t)((MC_GY_POS >> h) & 1u) - (int32_t)((MC_GY_NEG >> h) & 1u);
    int32_t gz = (int32_t)((MC_GZ_POS >> h) & 1u) - (int32_t)((MC_GZ_NEG >> h) & 1u);
    return ((double)gx * x + (double)gy * y) + (double)gz * z;
}

static inline k_v128_t mc_grad_dot_f64x2(uint32_t hash0, uint32_t hash1, k_v128_t x, k_v128_t y, k_v128_t z) {
    uint32_t h0 = hash0 & 15u;
    uint32_t h1 = hash1 & 15u;
    int32_t gx0 = (int32_t)((MC_GX_POS >> h0) & 1u) - (int32_t)((MC_GX_NEG >> h0) & 1u);
    int32_t gx1 = (int32_t)((MC_GX_POS >> h1) & 1u) - (int32_t)((MC_GX_NEG >> h1) & 1u);
    int32_t gy0 = (int32_t)((MC_GY_POS >> h0) & 1u) - (int32_t)((MC_GY_NEG >> h0) & 1u);
    int32_t gy1 = (int32_t)((MC_GY_POS >> h1) & 1u) - (int32_t)((MC_GY_NEG >> h1) & 1u);
    int32_t gz0 = (int32_t)((MC_GZ_POS >> h0) & 1u) - (int32_t)((MC_GZ_NEG >> h0) & 1u);
    int32_t gz1 = (int32_t)((MC_GZ_POS >> h1) & 1u) - (int32_t)((MC_GZ_NEG >> h1) & 1u);
    k_v128_t vx = k_f64x2_convert_i32_pair(gx0, gx1);
    k_v128_t vy = k_f64x2_convert_i32_pair(gy0, gy1);
    k_v128_t vz = k_f64x2_convert_i32_pair(gz0, gz1);
    return k_f64x2_add(k_f64x2_add(k_f64x2_mul(vx, x), k_f64x2_mul(vy, y)), k_f64x2_mul(vz, z));
}

static inline double mc_smoothstep_f64(double t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

static inline k_v128_t mc_smoothstep_f64x2(k_v128_t t) {
    k_v128_t t2 = k_f64x2_mul(t, t);
    k_v128_t t3 = k_f64x2_mul(t2, t);
    k_v128_t poly = k_f64x2_add(
        k_f64x2_mul(t, k_f64x2_sub(k_f64x2_mul(t, k_f64x2_splat(6.0)), k_f64x2_splat(15.0))),
        k_f64x2_splat(10.0)
    );
    return k_f64x2_mul(t3, poly);
}

static inline double mc_lerp_f64(double a, double p0, double p1) {
    return p0 + a * (p1 - p0);
}

static inline k_v128_t mc_lerp_f64x2(k_v128_t a, k_v128_t p0, k_v128_t p1) {
    return k_f64x2_add(p0, k_f64x2_mul(a, k_f64x2_sub(p1, p0)));
}

static inline int32_t mc_floor_f64_i32(double v) {
    int32_t i = (int32_t)v;
    return (v < (double)i && i != INT32_MIN) ? (i - 1) : i;
}

KERNEL_EXPORT(simd_improved_noise_batch)
void simd_improved_noise_batch(
    const uint8_t *perm,
    double xo, double yo, double zo,
    double x0, double y0, double z0,
    double dx, double dy, double dz,
    int nx, int ny, int nz,
    double freq, double amp,
    float *out, int accumulate
) {
    if (nx <= 0 || ny <= 0 || nz <= 0) return;

    const k_v128_t v_one = k_f64x2_splat(1.0);
    const k_v128_t v_freq = k_f64x2_splat(freq);
    const k_v128_t v_amp = k_f64x2_splat(amp);

    /* Fast 2D-slice path (ny == 1): vectorize 2-wide along contiguous x axis */
    if (ny == 1) {
        double sy = y0 * freq + yo;
        int32_t yf = mc_floor_f64_i32(sy);
        double yr = sy - (double)yf;
        double yr1 = yr - 1.0;
        double yAlpha = mc_smoothstep_f64(yr);
        k_v128_t v_yr = k_f64x2_splat(yr);
        k_v128_t v_yr1 = k_f64x2_splat(yr1);
        k_v128_t v_yAlpha = k_f64x2_splat(yAlpha);
        k_v128_t v_x0 = k_f64x2_splat(x0);
        k_v128_t v_dx = k_f64x2_splat(dx);
        k_v128_t v_xo = k_f64x2_splat(xo);

        for (int iz = 0; iz < nz; iz++) {
            double sz = (z0 + (double)iz * dz) * freq + zo;
            int32_t zf = mc_floor_f64_i32(sz);
            double zr = sz - (double)zf;
            double zr1 = zr - 1.0;
            double zAlpha = mc_smoothstep_f64(zr);
            k_v128_t v_zr = k_f64x2_splat(zr);
            k_v128_t v_zr1 = k_f64x2_splat(zr1);
            k_v128_t v_zAlpha = k_f64x2_splat(zAlpha);

            float *row = out + iz * nx;
            int ix = 0;
            for (; ix + 2 <= nx; ix += 2) {
                k_v128_t v_ix = k_f64x2_make((double)ix, (double)(ix + 1));
                k_v128_t v_sx = k_f64x2_add(k_f64x2_mul(k_f64x2_add(v_x0, k_f64x2_mul(v_ix, v_dx)), v_freq), v_xo);
                k_v128_t v_xfl = k_f64x2_floor(v_sx);
                k_v128_t v_xr = k_f64x2_sub(v_sx, v_xfl);
                k_v128_t v_xr1 = k_f64x2_sub(v_xr, v_one);
                int32_t xf_a = (int32_t)k_f64x2_lane0(v_xfl);
                int32_t xf_b = (int32_t)k_f64x2_lane1(v_xfl);

                uint32_t px0_a = perm[(uint32_t)xf_a & 255u];
                uint32_t px1_a = perm[(uint32_t)(xf_a + 1) & 255u];
                uint32_t px0_b = perm[(uint32_t)xf_b & 255u];
                uint32_t px1_b = perm[(uint32_t)(xf_b + 1) & 255u];

                uint32_t xy00_a = perm[(px0_a + (uint32_t)yf) & 255u];
                uint32_t xy01_a = perm[(px0_a + (uint32_t)(yf + 1)) & 255u];
                uint32_t xy10_a = perm[(px1_a + (uint32_t)yf) & 255u];
                uint32_t xy11_a = perm[(px1_a + (uint32_t)(yf + 1)) & 255u];

                uint32_t xy00_b = perm[(px0_b + (uint32_t)yf) & 255u];
                uint32_t xy01_b = perm[(px0_b + (uint32_t)(yf + 1)) & 255u];
                uint32_t xy10_b = perm[(px1_b + (uint32_t)yf) & 255u];
                uint32_t xy11_b = perm[(px1_b + (uint32_t)(yf + 1)) & 255u];

                uint32_t uz0 = (uint32_t)zf;
                uint32_t uz1 = (uint32_t)(zf + 1);
                k_v128_t d000 = mc_grad_dot_f64x2(perm[(xy00_a + uz0) & 255u], perm[(xy00_b + uz0) & 255u], v_xr,  v_yr,  v_zr);
                k_v128_t d100 = mc_grad_dot_f64x2(perm[(xy10_a + uz0) & 255u], perm[(xy10_b + uz0) & 255u], v_xr1, v_yr,  v_zr);
                k_v128_t d010 = mc_grad_dot_f64x2(perm[(xy01_a + uz0) & 255u], perm[(xy01_b + uz0) & 255u], v_xr,  v_yr1, v_zr);
                k_v128_t d110 = mc_grad_dot_f64x2(perm[(xy11_a + uz0) & 255u], perm[(xy11_b + uz0) & 255u], v_xr1, v_yr1, v_zr);
                k_v128_t d001 = mc_grad_dot_f64x2(perm[(xy00_a + uz1) & 255u], perm[(xy00_b + uz1) & 255u], v_xr,  v_yr,  v_zr1);
                k_v128_t d101 = mc_grad_dot_f64x2(perm[(xy10_a + uz1) & 255u], perm[(xy10_b + uz1) & 255u], v_xr1, v_yr,  v_zr1);
                k_v128_t d011 = mc_grad_dot_f64x2(perm[(xy01_a + uz1) & 255u], perm[(xy01_b + uz1) & 255u], v_xr,  v_yr1, v_zr1);
                k_v128_t d111 = mc_grad_dot_f64x2(perm[(xy11_a + uz1) & 255u], perm[(xy11_b + uz1) & 255u], v_xr1, v_yr1, v_zr1);

                k_v128_t v_xAlpha = mc_smoothstep_f64x2(v_xr);
                k_v128_t ly0 = mc_lerp_f64x2(v_yAlpha, mc_lerp_f64x2(v_xAlpha, d000, d100), mc_lerp_f64x2(v_xAlpha, d010, d110));
                k_v128_t ly1 = mc_lerp_f64x2(v_yAlpha, mc_lerp_f64x2(v_xAlpha, d001, d101), mc_lerp_f64x2(v_xAlpha, d011, d111));
                k_v128_t res = k_f64x2_mul(mc_lerp_f64x2(v_zAlpha, ly0, ly1), v_amp);
                float f0 = (float)k_f64x2_lane0(res);
                float f1 = (float)k_f64x2_lane1(res);
                if (accumulate) {
                    row[ix]     += f0;
                    row[ix + 1] += f1;
                } else {
                    row[ix]     = f0;
                    row[ix + 1] = f1;
                }
            }
            if (ix < nx) {
                double sx = (x0 + (double)ix * dx) * freq + xo;
                int32_t xf = mc_floor_f64_i32(sx);
                double xr = sx - (double)xf;
                double xr1 = xr - 1.0;
                uint32_t px0 = perm[(uint32_t)xf & 255u];
                uint32_t px1 = perm[(uint32_t)(xf + 1) & 255u];
                uint32_t xy00 = perm[(px0 + (uint32_t)yf) & 255u];
                uint32_t xy01 = perm[(px0 + (uint32_t)(yf + 1)) & 255u];
                uint32_t xy10 = perm[(px1 + (uint32_t)yf) & 255u];
                uint32_t xy11 = perm[(px1 + (uint32_t)(yf + 1)) & 255u];
                uint32_t uz0 = (uint32_t)zf;
                uint32_t uz1 = (uint32_t)(zf + 1);
                double d000 = mc_grad_dot_scalar(perm[(xy00 + uz0) & 255u], xr,  yr,  zr);
                double d100 = mc_grad_dot_scalar(perm[(xy10 + uz0) & 255u], xr1, yr,  zr);
                double d010 = mc_grad_dot_scalar(perm[(xy01 + uz0) & 255u], xr,  yr1, zr);
                double d110 = mc_grad_dot_scalar(perm[(xy11 + uz0) & 255u], xr1, yr1, zr);
                double d001 = mc_grad_dot_scalar(perm[(xy00 + uz1) & 255u], xr,  yr,  zr1);
                double d101 = mc_grad_dot_scalar(perm[(xy10 + uz1) & 255u], xr1, yr,  zr1);
                double d011 = mc_grad_dot_scalar(perm[(xy01 + uz1) & 255u], xr,  yr1, zr1);
                double d111 = mc_grad_dot_scalar(perm[(xy11 + uz1) & 255u], xr1, yr1, zr1);
                double xAlpha = mc_smoothstep_f64(xr);
                double ly0 = mc_lerp_f64(yAlpha, mc_lerp_f64(xAlpha, d000, d100), mc_lerp_f64(xAlpha, d010, d110));
                double ly1 = mc_lerp_f64(yAlpha, mc_lerp_f64(xAlpha, d001, d101), mc_lerp_f64(xAlpha, d011, d111));
                float f0 = (float)(mc_lerp_f64(zAlpha, ly0, ly1) * amp);
                if (accumulate) row[ix] += f0;
                else row[ix] = f0;
            }
        }
        return;
    }

    /* Standard 3D DensityVolume path: (z, x, y) with f64x2 along contiguous y */
    const k_v128_t v_y0 = k_f64x2_splat(y0);
    const k_v128_t v_dy = k_f64x2_splat(dy);
    const k_v128_t v_yo = k_f64x2_splat(yo);

    for (int iz = 0; iz < nz; iz++) {
        double sz = (z0 + (double)iz * dz) * freq + zo;
        int32_t zf = mc_floor_f64_i32(sz);
        double zr = sz - (double)zf;
        double zr1 = zr - 1.0;
        double zAlpha = mc_smoothstep_f64(zr);
        k_v128_t v_zr = k_f64x2_splat(zr);
        k_v128_t v_zr1 = k_f64x2_splat(zr1);
        k_v128_t v_zAlpha = k_f64x2_splat(zAlpha);
        uint32_t uz0 = (uint32_t)zf;
        uint32_t uz1 = (uint32_t)(zf + 1);

        for (int ix = 0; ix < nx; ix++) {
            double sx = (x0 + (double)ix * dx) * freq + xo;
            int32_t xf = mc_floor_f64_i32(sx);
            double xr = sx - (double)xf;
            double xr1 = xr - 1.0;
            double xAlpha = mc_smoothstep_f64(xr);
            k_v128_t v_xr = k_f64x2_splat(xr);
            k_v128_t v_xr1 = k_f64x2_splat(xr1);
            k_v128_t v_xAlpha = k_f64x2_splat(xAlpha);

            uint32_t px0 = perm[(uint32_t)xf & 255u];
            uint32_t px1 = perm[(uint32_t)(xf + 1) & 255u];

            float *col = out + (iz * nx + ix) * ny;
            int iy = 0;
            for (; iy + 2 <= ny; iy += 2) {
                k_v128_t v_iy = k_f64x2_make((double)iy, (double)(iy + 1));
                k_v128_t v_sy = k_f64x2_add(k_f64x2_mul(k_f64x2_add(v_y0, k_f64x2_mul(v_iy, v_dy)), v_freq), v_yo);
                k_v128_t v_yfl = k_f64x2_floor(v_sy);
                k_v128_t v_yr = k_f64x2_sub(v_sy, v_yfl);
                k_v128_t v_yr1 = k_f64x2_sub(v_yr, v_one);

                int32_t yf_a = (int32_t)k_f64x2_lane0(v_yfl);
                int32_t yf_b = (int32_t)k_f64x2_lane1(v_yfl);

                uint32_t xy00_a = perm[(px0 + (uint32_t)yf_a) & 255u];
                uint32_t xy01_a = perm[(px0 + (uint32_t)(yf_a + 1)) & 255u];
                uint32_t xy10_a = perm[(px1 + (uint32_t)yf_a) & 255u];
                uint32_t xy11_a = perm[(px1 + (uint32_t)(yf_a + 1)) & 255u];

                uint32_t xy00_b = perm[(px0 + (uint32_t)yf_b) & 255u];
                uint32_t xy01_b = perm[(px0 + (uint32_t)(yf_b + 1)) & 255u];
                uint32_t xy10_b = perm[(px1 + (uint32_t)yf_b) & 255u];
                uint32_t xy11_b = perm[(px1 + (uint32_t)(yf_b + 1)) & 255u];

                k_v128_t d000 = mc_grad_dot_f64x2(perm[(xy00_a + uz0) & 255u], perm[(xy00_b + uz0) & 255u], v_xr,  v_yr,  v_zr);
                k_v128_t d100 = mc_grad_dot_f64x2(perm[(xy10_a + uz0) & 255u], perm[(xy10_b + uz0) & 255u], v_xr1, v_yr,  v_zr);
                k_v128_t d010 = mc_grad_dot_f64x2(perm[(xy01_a + uz0) & 255u], perm[(xy01_b + uz0) & 255u], v_xr,  v_yr1, v_zr);
                k_v128_t d110 = mc_grad_dot_f64x2(perm[(xy11_a + uz0) & 255u], perm[(xy11_b + uz0) & 255u], v_xr1, v_yr1, v_zr);
                k_v128_t d001 = mc_grad_dot_f64x2(perm[(xy00_a + uz1) & 255u], perm[(xy00_b + uz1) & 255u], v_xr,  v_yr,  v_zr1);
                k_v128_t d101 = mc_grad_dot_f64x2(perm[(xy10_a + uz1) & 255u], perm[(xy10_b + uz1) & 255u], v_xr1, v_yr,  v_zr1);
                k_v128_t d011 = mc_grad_dot_f64x2(perm[(xy01_a + uz1) & 255u], perm[(xy01_b + uz1) & 255u], v_xr,  v_yr1, v_zr1);
                k_v128_t d111 = mc_grad_dot_f64x2(perm[(xy11_a + uz1) & 255u], perm[(xy11_b + uz1) & 255u], v_xr1, v_yr1, v_zr1);

                k_v128_t v_yAlpha = mc_smoothstep_f64x2(v_yr);
                k_v128_t ly0 = mc_lerp_f64x2(v_yAlpha, mc_lerp_f64x2(v_xAlpha, d000, d100), mc_lerp_f64x2(v_xAlpha, d010, d110));
                k_v128_t ly1 = mc_lerp_f64x2(v_yAlpha, mc_lerp_f64x2(v_xAlpha, d001, d101), mc_lerp_f64x2(v_xAlpha, d011, d111));
                k_v128_t res = k_f64x2_mul(mc_lerp_f64x2(v_zAlpha, ly0, ly1), v_amp);
                float f0 = (float)k_f64x2_lane0(res);
                float f1 = (float)k_f64x2_lane1(res);
                if (accumulate) {
                    col[iy]     += f0;
                    col[iy + 1] += f1;
                } else {
                    col[iy]     = f0;
                    col[iy + 1] = f1;
                }
            }
            if (iy < ny) {
                double sy = (y0 + (double)iy * dy) * freq + yo;
                int32_t yf = mc_floor_f64_i32(sy);
                double yr = sy - (double)yf;
                double yr1 = yr - 1.0;
                uint32_t xy00 = perm[(px0 + (uint32_t)yf) & 255u];
                uint32_t xy01 = perm[(px0 + (uint32_t)(yf + 1)) & 255u];
                uint32_t xy10 = perm[(px1 + (uint32_t)yf) & 255u];
                uint32_t xy11 = perm[(px1 + (uint32_t)(yf + 1)) & 255u];
                double d000 = mc_grad_dot_scalar(perm[(xy00 + uz0) & 255u], xr,  yr,  zr);
                double d100 = mc_grad_dot_scalar(perm[(xy10 + uz0) & 255u], xr1, yr,  zr);
                double d010 = mc_grad_dot_scalar(perm[(xy01 + uz0) & 255u], xr,  yr1, zr);
                double d110 = mc_grad_dot_scalar(perm[(xy11 + uz0) & 255u], xr1, yr1, zr);
                double d001 = mc_grad_dot_scalar(perm[(xy00 + uz1) & 255u], xr,  yr,  zr1);
                double d101 = mc_grad_dot_scalar(perm[(xy10 + uz1) & 255u], xr1, yr,  zr1);
                double d011 = mc_grad_dot_scalar(perm[(xy01 + uz1) & 255u], xr,  yr1, zr1);
                double d111 = mc_grad_dot_scalar(perm[(xy11 + uz1) & 255u], xr1, yr1, zr1);
                double yAlpha = mc_smoothstep_f64(yr);
                double ly0 = mc_lerp_f64(yAlpha, mc_lerp_f64(xAlpha, d000, d100), mc_lerp_f64(xAlpha, d010, d110));
                double ly1 = mc_lerp_f64(yAlpha, mc_lerp_f64(xAlpha, d001, d101), mc_lerp_f64(xAlpha, d011, d111));
                float f0 = (float)(mc_lerp_f64(zAlpha, ly0, ly1) * amp);
                if (accumulate) col[iy] += f0;
                else col[iy] = f0;
            }
        }
    }
}


/* ============================================================================
 * KERNEL 2: simd_aquifer_voronoi_batch
 * Exact Aquifer.NoiseBasedAquifer 12-neighbor (or general N-cell) squared
 * Euclidean distance & sorted top-4 extraction using i32x4 SIMD.
 *
 * Tie-breaking matches Aquifer.java lines 258-282 (`>=` replaces and shifts,
 * so later indices win on exact distance ties).
 * ============================================================================ */

static inline __attribute__((always_inline)) void aquifer_load4_soa(const int32_t *p, k_v128_t *cx, k_v128_t *cy, k_v128_t *cz) {
    /* p layout: [x0,y0,z0, x1, y1,z1,x2,y2, z2,x3,y3,z3] (3 x v128 loads) */
    k_v128_t v0 = k_v128_load(p);
    k_v128_t v1 = k_v128_load(p + 4);
    k_v128_t v2 = k_v128_load(p + 8);
    k_v128_t tx = K_I32X4_SHUFFLE(v0, v1, 0, 3, 6, 7);
    *cx = K_I32X4_SHUFFLE(tx, v2, 0, 1, 2, 5);
    k_v128_t ty = K_I32X4_SHUFFLE(v0, v1, 1, 4, 7, 5);
    *cy = K_I32X4_SHUFFLE(ty, v2, 0, 1, 2, 6);
    k_v128_t tz = K_I32X4_SHUFFLE(v0, v1, 2, 5, 0, 0);
    *cz = K_I32X4_SHUFFLE(tz, v2, 0, 1, 4, 7);
}

#define AQUIFER_INSERT_TOP4(newDist_, idx_) \
    do { \
        int32_t _nd = (newDist_); \
        int32_t _ix = (idx_); \
        if (d1 >= _nd) { \
            i4 = i3; i3 = i2; i2 = i1; i1 = _ix; \
            d4 = d3; d3 = d2; d2 = d1; d1 = _nd; \
        } else if (d2 >= _nd) { \
            i4 = i3; i3 = i2; i2 = _ix; \
            d4 = d3; d3 = d2; d2 = _nd; \
        } else if (d3 >= _nd) { \
            i4 = i3; i3 = _ix; \
            d4 = d3; d3 = _nd; \
        } else if (d4 >= _nd) { \
            i4 = _ix; \
            d4 = _nd; \
        } \
    } while (0)

static inline __attribute__((always_inline)) k_v128_t aquifer_dist4_i32x4(
    k_v128_t cx, k_v128_t cy, k_v128_t cz,
    k_v128_t qx, k_v128_t qy, k_v128_t qz
) {
    k_v128_t dx = k_i32x4_sub(cx, qx);
    k_v128_t dy = k_i32x4_sub(cy, qy);
    k_v128_t dz = k_i32x4_sub(cz, qz);
    return k_i32x4_add(k_i32x4_add(k_i32x4_mul(dx, dx), k_i32x4_mul(dy, dy)), k_i32x4_mul(dz, dz));
}

KERNEL_EXPORT(simd_aquifer_voronoi_batch)
void simd_aquifer_voronoi_batch(
    const int32_t *cellXyz, int cellCount,
    const int32_t *queryXyz, int queryCount,
    int32_t *outBestIdx4, int32_t *outBestDist4
) {
    if (queryCount <= 0) return;

    /* Dedicated fast path for Minecraft's exact 12-neighbor (2x3x2) aquifer stencil:
     * hoist all 12 candidate coords into 9 SIMD registers once outside the query loop. */
    if (cellCount == 12) {
        k_v128_t cx0, cy0, cz0, cx1, cy1, cz1, cx2, cy2, cz2;
        aquifer_load4_soa(cellXyz + 0,  &cx0, &cy0, &cz0);
        aquifer_load4_soa(cellXyz + 12, &cx1, &cy1, &cz1);
        aquifer_load4_soa(cellXyz + 24, &cx2, &cy2, &cz2);

        for (int q = 0; q < queryCount; q++) {
            const int32_t *qp = queryXyz + q * 3;
            k_v128_t qx = k_i32x4_splat(qp[0]);
            k_v128_t qy = k_i32x4_splat(qp[1]);
            k_v128_t qz = k_i32x4_splat(qp[2]);

            k_v128_t distA = aquifer_dist4_i32x4(cx0, cy0, cz0, qx, qy, qz);
            k_v128_t distB = aquifer_dist4_i32x4(cx1, cy1, cz1, qx, qy, qz);
            k_v128_t distC = aquifer_dist4_i32x4(cx2, cy2, cz2, qx, qy, qz);

            int32_t d1 = INT32_MAX, d2 = INT32_MAX, d3 = INT32_MAX, d4 = INT32_MAX;
            int32_t i1 = 0, i2 = 0, i3 = 0, i4 = 0;

            AQUIFER_INSERT_TOP4(k_i32x4_lane0(distA), 0);
            AQUIFER_INSERT_TOP4(k_i32x4_lane1(distA), 1);
            AQUIFER_INSERT_TOP4(k_i32x4_lane2(distA), 2);
            AQUIFER_INSERT_TOP4(k_i32x4_lane3(distA), 3);
            AQUIFER_INSERT_TOP4(k_i32x4_lane0(distB), 4);
            AQUIFER_INSERT_TOP4(k_i32x4_lane1(distB), 5);
            AQUIFER_INSERT_TOP4(k_i32x4_lane2(distB), 6);
            AQUIFER_INSERT_TOP4(k_i32x4_lane3(distB), 7);
            AQUIFER_INSERT_TOP4(k_i32x4_lane0(distC), 8);
            AQUIFER_INSERT_TOP4(k_i32x4_lane1(distC), 9);
            AQUIFER_INSERT_TOP4(k_i32x4_lane2(distC), 10);
            AQUIFER_INSERT_TOP4(k_i32x4_lane3(distC), 11);

            int32_t *oi = outBestIdx4 + q * 4;
            int32_t *od = outBestDist4 + q * 4;
            k_v128_store(oi, k_i32x4_make(i1, i2, i3, i4));
            k_v128_store(od, k_i32x4_make(d1, d2, d3, d4));
        }
        return;
    }

    /* General cellCount path: 4 candidates per i32x4 SIMD iteration + scalar tail */
    for (int q = 0; q < queryCount; q++) {
        const int32_t *qp = queryXyz + q * 3;
        int32_t bqx = qp[0], bqy = qp[1], bqz = qp[2];
        k_v128_t qx = k_i32x4_splat(bqx);
        k_v128_t qy = k_i32x4_splat(bqy);
        k_v128_t qz = k_i32x4_splat(bqz);

        int32_t d1 = INT32_MAX, d2 = INT32_MAX, d3 = INT32_MAX, d4 = INT32_MAX;
        int32_t i1 = 0, i2 = 0, i3 = 0, i4 = 0;

        int c = 0;
        for (; c + 4 <= cellCount; c += 4) {
            k_v128_t cx, cy, cz;
            aquifer_load4_soa(cellXyz + c * 3, &cx, &cy, &cz);
            k_v128_t dist = aquifer_dist4_i32x4(cx, cy, cz, qx, qy, qz);
            AQUIFER_INSERT_TOP4(k_i32x4_lane0(dist), c + 0);
            AQUIFER_INSERT_TOP4(k_i32x4_lane1(dist), c + 1);
            AQUIFER_INSERT_TOP4(k_i32x4_lane2(dist), c + 2);
            AQUIFER_INSERT_TOP4(k_i32x4_lane3(dist), c + 3);
        }
        for (; c < cellCount; c++) {
            const int32_t *cp = cellXyz + c * 3;
            int32_t dx = (int32_t)((uint32_t)cp[0] - (uint32_t)bqx);
            int32_t dy = (int32_t)((uint32_t)cp[1] - (uint32_t)bqy);
            int32_t dz = (int32_t)((uint32_t)cp[2] - (uint32_t)bqz);
            int32_t dist = (int32_t)((uint32_t)dx * (uint32_t)dx
                                   + (uint32_t)dy * (uint32_t)dy
                                   + (uint32_t)dz * (uint32_t)dz);
            AQUIFER_INSERT_TOP4(dist, c);
        }

        int32_t *oi = outBestIdx4 + q * 4;
        int32_t *od = outBestDist4 + q * 4;
        k_v128_store(oi, k_i32x4_make(i1, i2, i3, i4));
        k_v128_store(od, k_i32x4_make(d1, d2, d3, d4));
    }
}


/* ============================================================================
 * KERNEL 3: simd_beardifier_volume
 * Exact Minecraft 26.3 Beardifier over a (z, x, y) DensityVolume grid.
 *
 * Packed input layouts:
 *   rigids[r*8 + 0..7] = { minX, minY, minZ, maxX, maxY, maxZ, groundLevelDelta, adjOrdinal }
 *     adjOrdinal matches TerrainAdjustment.ordinal():
 *       0 = NONE, 1 = BURY, 2 = BEARD_THIN, 3 = BEARD_BOX, 4 = ENCAPSULATE
 *   junctions[j*3 + 0..2] = { sourceX, sourceGroundY, sourceZ }
 *   beardKernel[zi * 576 + xi * 24 + yi] = exact 24x24x24 Minecraft BEARD_KERNEL (13824 floats)
 *
 * Output indexing:
 *   outDensity[(iz * nx + ix) * ny + iy]
 * ============================================================================ */

static inline k_v128_t mc_fast_inv_sqrt_f64x2(k_v128_t x) {
    /* Exact Minecraft Mth.fastInvSqrt(double):
     *   xhalf = 0.5 * x;
     *   i = 6910469410427058090L - (bits(x) >> 1);
     *   y = double(i);
     *   return y * (1.5 - xhalf * y * y);
     */
    k_v128_t xhalf = k_f64x2_mul(k_f64x2_splat(0.5), x);
    k_v128_t magic = k_i64x2_splat(6910469410427058090LL);
    k_v128_t y = k_i64x2_sub(magic, k_i64x2_shr_s(x, 1));
    return k_f64x2_mul(y, k_f64x2_sub(k_f64x2_splat(1.5), k_f64x2_mul(k_f64x2_mul(xhalf, y), y)));
}

static inline float mc_fast_inv_sqrt_scalar(float x_f) {
    k_v128_t v = mc_fast_inv_sqrt_f64x2(k_f64x2_splat((double)x_f));
    return (float)k_f64x2_lane0(v);
}

static inline k_v128_t beard_eval_4(
    float dx_f, float dz_f,
    k_v128_t dyToGround_i32,
    k_v128_t kvals,
    float scale
) {
    k_v128_t v_half = k_f32x4_splat(0.5f);
    k_v128_t dyOff = k_f32x4_add(k_f32x4_convert_i32x4(dyToGround_i32), v_half);
    k_v128_t horizSq = k_f32x4_splat(dx_f * dx_f + dz_f * dz_f);
    k_v128_t distSq = k_f32x4_add(horizSq, k_f32x4_mul(dyOff, dyOff));
    k_v128_t halfDistSq = k_f32x4_mul(distSq, v_half);

    k_v128_t inv_lo = mc_fast_inv_sqrt_f64x2(k_f64x2_promote_low_f32x4(halfDistSq));
    k_v128_t inv_hi = mc_fast_inv_sqrt_f64x2(k_f64x2_promote_high_f32x4(halfDistSq));
    k_v128_t invSqrt = k_f32x4_demote_pair(inv_lo, inv_hi);

    /* value = -dyWithOffset * (float)fastInvSqrt(distanceSqr / 2.0F) / 2.0F */
    k_v128_t val = k_f32x4_mul(k_f32x4_mul(k_f32x4_neg(dyOff), invSqrt), v_half);
    return k_f32x4_mul(k_f32x4_mul(val, kvals), k_f32x4_splat(scale));
}

static inline k_v128_t bury_eval_4(float dx_f, float dz_f, k_v128_t dy_f, float scale) {
    /* distanceSq >= 36.0f ? 0.0f : (1.0f - sqrt(distanceSq) / 6.0f) * scale */
    k_v128_t horizSq = k_f32x4_splat(dx_f * dx_f + dz_f * dz_f);
    k_v128_t distSq = k_f32x4_add(horizSq, k_f32x4_mul(dy_f, dy_f));
    k_v128_t mask = k_f32x4_lt(distSq, k_f32x4_splat(36.0f));
    k_v128_t root = k_f32x4_sqrt(distSq);
    k_v128_t contrib = k_f32x4_sub(k_f32x4_splat(1.0f), k_f32x4_div(root, k_f32x4_splat(6.0f)));
    return k_f32x4_mul(k_v128_and(contrib, mask), k_f32x4_splat(scale));
}

static inline float bury_eval_scalar(float dx, float dy, float dz, float scale) {
    float distSq = dx * dx + dy * dy + dz * dz;
    if (distSq >= 36.0f) return 0.0f;
    float root = k_f32x4_lane0(k_f32x4_sqrt(k_f32x4_splat(distSq)));
    return (1.0f - root / 6.0f) * scale;
}

static inline float beard_eval_scalar(int32_t dx, int32_t dy, int32_t dz, int32_t yToGround, const float *beardKernel, float scale) {
    int32_t xi = dx + 12;
    int32_t yi = dy + 12;
    int32_t zi = dz + 12;
    if ((uint32_t)xi >= 24u || (uint32_t)yi >= 24u || (uint32_t)zi >= 24u) return 0.0f;
    float dyOff = (float)yToGround + 0.5f;
    float distSq = (float)dx * (float)dx + dyOff * dyOff + (float)dz * (float)dz;
    float value = -dyOff * mc_fast_inv_sqrt_scalar(distSq / 2.0f) / 2.0f;
    return (value * beardKernel[zi * 576 + xi * 24 + yi]) * scale;
}

KERNEL_EXPORT(simd_beardifier_volume)
void simd_beardifier_volume(
    const float *rigids, int rigidCount,
    const float *junctions, int junctionCount,
    const float *beardKernel,
    int minX, int minY, int minZ,
    int nx, int ny, int nz,
    float *outDensity
) {
    if (nx <= 0 || ny <= 0 || nz <= 0) return;

    /* Zero output buffer using SIMD stores (avoids compiler memset idiom call) */
    int total = nx * ny * nz;
    k_v128_t v_zero = k_f32x4_splat(0.0f);
    int idx = 0;
    for (; idx + 4 <= total; idx += 4) {
        k_v128_store(outDensity + idx, v_zero);
    }
    for (; idx < total; idx++) {
        outDensity[idx] = 0.0f;
    }

    if (rigidCount <= 0 && junctionCount <= 0) return;

    const k_v128_t v_step0123 = k_i32x4_make(0, 1, 2, 3);
    const k_v128_t v_izero = k_i32x4_splat(0);
    const k_v128_t v_half = k_f32x4_splat(0.5f);

    /* Accumulate rigids in exact Beardifier.java order */
    for (int r = 0; r < rigidCount; r++) {
        const float *rp = rigids + r * 8;
        int32_t bMinX = (int32_t)rp[0];
        int32_t bMinY = (int32_t)rp[1];
        int32_t bMinZ = (int32_t)rp[2];
        int32_t bMaxX = (int32_t)rp[3];
        int32_t bMaxY = (int32_t)rp[4];
        int32_t bMaxZ = (int32_t)rp[5];
        int32_t groundDelta = (int32_t)rp[6];
        int32_t adj = (int32_t)rp[7];

        if (adj <= 0 || adj > 4) continue; /* 0 = NONE */
        int32_t groundY = bMinY + groundDelta;

        /* Horizontal bounding reach for O(1) column culling */
        int32_t horizReach = (adj == 1) ? 5 : 11; /* BURY: distSq<36 -> dx<=5; BEARD/ENCAPSULATE: <=11 */
        int zStart = (bMinZ - horizReach) - minZ; if (zStart < 0) zStart = 0;
        int zEnd   = (bMaxZ + horizReach) - minZ; if (zEnd >= nz) zEnd = nz - 1;
        int xStart = (bMinX - horizReach) - minX; if (xStart < 0) xStart = 0;
        int xEnd   = (bMaxX + horizReach) - minX; if (xEnd >= nx) xEnd = nx - 1;

        for (int iz = zStart; iz <= zEnd; iz++) {
            int32_t bz = minZ + iz;
            int32_t dz = bMinZ - bz;
            if (bz - bMaxZ > dz) dz = bz - bMaxZ;
            if (dz < 0) dz = 0;

            for (int ix = xStart; ix <= xEnd; ix++) {
                int32_t bx = minX + ix;
                int32_t dx = bMinX - bx;
                if (bx - bMaxX > dx) dx = bx - bMaxX;
                if (dx < 0) dx = 0;

                float *col = outDensity + (iz * nx + ix) * ny;

                if (adj == 1) {
                    /* BURY: getBuryContribution(dx, (blockY - groundY) / 2.0f, dz) */
                    float dx_f = (float)dx;
                    float dz_f = (float)dz;
                    if (dx_f * dx_f + dz_f * dz_f >= 36.0f) continue;

                    int yStart = (groundY - 11) - minY; if (yStart < 0) yStart = 0;
                    int yEnd   = (groundY + 11) - minY; if (yEnd >= ny) yEnd = ny - 1;
                    int iy = yStart;
                    for (; iy + 4 <= yEnd + 1; iy += 4) {
                        k_v128_t by = k_i32x4_add(k_i32x4_splat(minY + iy), v_step0123);
                        k_v128_t dy = k_f32x4_mul(k_f32x4_convert_i32x4(k_i32x4_sub(by, k_i32x4_splat(groundY))), v_half);
                        k_v128_t cur = k_v128_load(col + iy);
                        k_v128_store(col + iy, k_f32x4_add(cur, bury_eval_4(dx_f, dz_f, dy, 1.0f)));
                    }
                    for (; iy <= yEnd; iy++) {
                        int32_t by = minY + iy;
                        col[iy] += bury_eval_scalar(dx_f, (float)(by - groundY) * 0.5f, dz_f, 1.0f);
                    }
                } else if (adj == 4) {
                    /* ENCAPSULATE: getBuryContribution(dx/2, dy/2, dz/2) * 0.8f */
                    float dx_f = (float)dx * 0.5f;
                    float dz_f = (float)dz * 0.5f;
                    if (dx_f * dx_f + dz_f * dz_f >= 36.0f) continue;

                    int yStart = (bMinY - 11) - minY; if (yStart < 0) yStart = 0;
                    int yEnd   = (bMaxY + 11) - minY; if (yEnd >= ny) yEnd = ny - 1;
                    k_v128_t v_bMinY = k_i32x4_splat(bMinY);
                    k_v128_t v_bMaxY = k_i32x4_splat(bMaxY);
                    int iy = yStart;
                    for (; iy + 4 <= yEnd + 1; iy += 4) {
                        k_v128_t by = k_i32x4_add(k_i32x4_splat(minY + iy), v_step0123);
                        k_v128_t dy_i = k_i32x4_max(v_izero, k_i32x4_max(k_i32x4_sub(v_bMinY, by), k_i32x4_sub(by, v_bMaxY)));
                        k_v128_t dy = k_f32x4_mul(k_f32x4_convert_i32x4(dy_i), v_half);
                        k_v128_t cur = k_v128_load(col + iy);
                        k_v128_store(col + iy, k_f32x4_add(cur, bury_eval_4(dx_f, dz_f, dy, 0.8f)));
                    }
                    for (; iy <= yEnd; iy++) {
                        int32_t by = minY + iy;
                        int32_t dy = bMinY - by;
                        if (by - bMaxY > dy) dy = by - bMaxY;
                        if (dy < 0) dy = 0;
                        col[iy] += bury_eval_scalar(dx_f, (float)dy * 0.5f, dz_f, 0.8f);
                    }
                } else if (adj == 2) {
                    /* BEARD_THIN: dy = blockY - groundY; kernel slice is contiguous in y! */
                    int32_t xi = dx + 12;
                    int32_t zi = dz + 12;
                    if ((uint32_t)xi >= 24u || (uint32_t)zi >= 24u) continue;
                    const float *kSlice = beardKernel + zi * 576 + xi * 24;

                    int yStart = (groundY - 12) - minY; if (yStart < 0) yStart = 0;
                    int yEnd   = (groundY + 11) - minY; if (yEnd >= ny) yEnd = ny - 1;
                    int iy = yStart;
                    for (; iy + 4 <= yEnd + 1; iy += 4) {
                        int32_t dy0 = (minY + iy) - groundY;
                        int32_t yi0 = dy0 + 12;
                        k_v128_t kvals = k_v128_load(kSlice + yi0);
                        k_v128_t dyVec = k_i32x4_add(k_i32x4_splat(dy0), v_step0123);
                        k_v128_t cur = k_v128_load(col + iy);
                        k_v128_store(col + iy, k_f32x4_add(cur, beard_eval_4((float)dx, (float)dz, dyVec, kvals, 0.8f)));
                    }
                    for (; iy <= yEnd; iy++) {
                        int32_t dy = (minY + iy) - groundY;
                        col[iy] += beard_eval_scalar(dx, dy, dz, dy, beardKernel, 0.8f);
                    }
                } else {
                    /* adj == 3: BEARD_BOX: dy = max(0, max(groundY - blockY, blockY - maxY)) */
                    int32_t xi = dx + 12;
                    int32_t zi = dz + 12;
                    if ((uint32_t)xi >= 24u || (uint32_t)zi >= 24u) continue;
                    const float *kSlice = beardKernel + zi * 576 + xi * 24;

                    int32_t lowY  = (groundY < bMaxY) ? groundY : bMaxY;
                    int32_t highY = (groundY > bMaxY) ? groundY : bMaxY;
                    int yStart = (lowY - 12) - minY;  if (yStart < 0) yStart = 0;
                    int yEnd   = (highY + 11) - minY; if (yEnd >= ny) yEnd = ny - 1;
                    k_v128_t v_groundY = k_i32x4_splat(groundY);
                    k_v128_t v_bMaxY   = k_i32x4_splat(bMaxY);

                    int iy = yStart;
                    for (; iy + 4 <= yEnd + 1; iy += 4) {
                        k_v128_t by = k_i32x4_add(k_i32x4_splat(minY + iy), v_step0123);
                        k_v128_t dyToGround = k_i32x4_sub(by, v_groundY);
                        k_v128_t dyBox = k_i32x4_max(v_izero, k_i32x4_max(k_i32x4_sub(v_groundY, by), k_i32x4_sub(by, v_bMaxY)));
                        int32_t yi0 = k_i32x4_lane0(dyBox) + 12;
                        int32_t yi1 = k_i32x4_lane1(dyBox) + 12;
                        int32_t yi2 = k_i32x4_lane2(dyBox) + 12;
                        int32_t yi3 = k_i32x4_lane3(dyBox) + 12;
                        float k0 = ((uint32_t)yi0 < 24u) ? kSlice[yi0] : 0.0f;
                        float k1 = ((uint32_t)yi1 < 24u) ? kSlice[yi1] : 0.0f;
                        float k2 = ((uint32_t)yi2 < 24u) ? kSlice[yi2] : 0.0f;
                        float k3 = ((uint32_t)yi3 < 24u) ? kSlice[yi3] : 0.0f;
                        k_v128_t kvals = k_f32x4_make(k0, k1, k2, k3);
                        k_v128_t cur = k_v128_load(col + iy);
                        k_v128_store(col + iy, k_f32x4_add(cur, beard_eval_4((float)dx, (float)dz, dyToGround, kvals, 0.8f)));
                    }
                    for (; iy <= yEnd; iy++) {
                        int32_t by = minY + iy;
                        int32_t dyToGround = by - groundY;
                        int32_t dyBox = groundY - by;
                        if (by - bMaxY > dyBox) dyBox = by - bMaxY;
                        if (dyBox < 0) dyBox = 0;
                        col[iy] += beard_eval_scalar(dx, dyBox, dz, dyToGround, beardKernel, 0.8f);
                    }
                }
            }
        }
    }

    /* Accumulate JigsawJunction contributions (scale = 0.4f) */
    for (int j = 0; j < junctionCount; j++) {
        const float *jp = junctions + j * 3;
        int32_t jx = (int32_t)jp[0];
        int32_t jy = (int32_t)jp[1];
        int32_t jz = (int32_t)jp[2];

        int zStart = (jz - 12) - minZ; if (zStart < 0) zStart = 0;
        int zEnd   = (jz + 11) - minZ; if (zEnd >= nz) zEnd = nz - 1;
        int xStart = (jx - 12) - minX; if (xStart < 0) xStart = 0;
        int xEnd   = (jx + 11) - minX; if (xEnd >= nx) xEnd = nx - 1;
        int yStart = (jy - 12) - minY; if (yStart < 0) yStart = 0;
        int yEnd   = (jy + 11) - minY; if (yEnd >= ny) yEnd = ny - 1;

        for (int iz = zStart; iz <= zEnd; iz++) {
            int32_t dz = (minZ + iz) - jz;
            int32_t zi = dz + 12;
            for (int ix = xStart; ix <= xEnd; ix++) {
                int32_t dx = (minX + ix) - jx;
                int32_t xi = dx + 12;
                const float *kSlice = beardKernel + zi * 576 + xi * 24;
                float *col = outDensity + (iz * nx + ix) * ny;

                int iy = yStart;
                for (; iy + 4 <= yEnd + 1; iy += 4) {
                    int32_t dy0 = (minY + iy) - jy;
                    int32_t yi0 = dy0 + 12;
                    k_v128_t kvals = k_v128_load(kSlice + yi0);
                    k_v128_t dyVec = k_i32x4_add(k_i32x4_splat(dy0), v_step0123);
                    k_v128_t cur = k_v128_load(col + iy);
                    k_v128_store(col + iy, k_f32x4_add(cur, beard_eval_4((float)dx, (float)dz, dyVec, kvals, 0.4f)));
                }
                for (; iy <= yEnd; iy++) {
                    int32_t dy = (minY + iy) - jy;
                    col[iy] += beard_eval_scalar(dx, dy, dz, dy, beardKernel, 0.4f);
                }
            }
        }
    }
}


/* ============================================================================
 * KERNEL 4: simd_biome_fiddle_batch
 * Exact Minecraft BiomeManager.computeBiome 8-corner LCG + Voronoi argmin.
 *
 * Uses Minecraft's exact LinearCongruentialGenerator.next(rval, c):
 *   rval = rval * (rval * 6364136223846793005L + 1442695040888963407L) + c
 * Vectorized in 4 x (i64x2 + f64x2) pairs across the 8 corners (0,1), (2,3),
 * (4,5), (6,7) with exact smallest-index tie-breaking!
 * ============================================================================ */

static inline uint64_t mc_lcg_next_u64(uint64_t rval, int64_t c) {
    rval *= rval * 6364136223846793005ULL + 1442695040888963407ULL;
    return rval + (uint64_t)c;
}

static inline k_v128_t mc_lcg_next_i64x2(k_v128_t rval, k_v128_t c) {
    k_v128_t mult = k_i64x2_splat(6364136223846793005LL);
    k_v128_t inc  = k_i64x2_splat(1442695040888963407LL);
    k_v128_t mixed = k_i64x2_mul(rval, k_i64x2_add(k_i64x2_mul(rval, mult), inc));
    return k_i64x2_add(mixed, c);
}

static inline k_v128_t mc_fiddle_pair_f64x2(k_v128_t rval) {
    /* floorMod(rval >> 24, 1024) == (rval >> 24) & 1023 for all signed 64-bit rval */
    k_v128_t shifted = k_u64x2_shr(rval, 24);
    int32_t m0 = (int32_t)((uint64_t)k_i64x2_lane0(shifted) & 1023ULL);
    int32_t m1 = (int32_t)((uint64_t)k_i64x2_lane1(shifted) & 1023ULL);
    k_v128_t unif = k_f64x2_mul(k_f64x2_convert_i32_pair(m0, m1), k_f64x2_splat(1.0 / 1024.0));
    return k_f64x2_mul(k_f64x2_sub(unif, k_f64x2_splat(0.5)), k_f64x2_splat(0.9));
}

static inline k_v128_t biome_corner_pair_dist2(
    uint64_t r_xy,
    k_v128_t v_seed,
    k_v128_t v_cx,
    k_v128_t v_cy,
    k_v128_t v_cz_pair,
    k_v128_t v_dx,
    k_v128_t v_dy,
    k_v128_t v_dz_pair
) {
    k_v128_t r = k_i64x2_splat((int64_t)r_xy);
    r = mc_lcg_next_i64x2(r, v_cz_pair);
    r = mc_lcg_next_i64x2(r, v_cx);
    r = mc_lcg_next_i64x2(r, v_cy);
    r = mc_lcg_next_i64x2(r, v_cz_pair);
    k_v128_t fidX = mc_fiddle_pair_f64x2(r);
    r = mc_lcg_next_i64x2(r, v_seed);
    k_v128_t fidY = mc_fiddle_pair_f64x2(r);
    r = mc_lcg_next_i64x2(r, v_seed);
    k_v128_t fidZ = mc_fiddle_pair_f64x2(r);

    k_v128_t sx = k_f64x2_add(v_dx, fidX);
    k_v128_t sy = k_f64x2_add(v_dy, fidY);
    k_v128_t sz = k_f64x2_add(v_dz_pair, fidZ);
    /* Exact BiomeManager.java line 143 order: square(dz) + square(dy) + square(dx) */
    return k_f64x2_add(k_f64x2_add(k_f64x2_mul(sz, sz), k_f64x2_mul(sy, sy)), k_f64x2_mul(sx, sx));
}

KERNEL_EXPORT(simd_biome_fiddle_batch)
void simd_biome_fiddle_batch(
    int64_t seed,
    const int32_t *coordsXyz,
    int count,
    int32_t *outQuartXyz
) {
    if (count <= 0) return;

    const k_v128_t v_seed = k_i64x2_splat(seed);
    const k_v128_t v_idx01 = k_i64x2_make(0, 1);
    const k_v128_t v_idx23 = k_i64x2_make(2, 3);
    const k_v128_t v_idx45 = k_i64x2_make(4, 5);
    const k_v128_t v_idx67 = k_i64x2_make(6, 7);

    for (int i = 0; i < count; i++) {
        const int32_t *cp = coordsXyz + i * 3;
        int32_t absX = cp[0] - 2;
        int32_t absY = cp[1] - 2;
        int32_t absZ = cp[2] - 2;

        int32_t parentX = absX >> 2;
        int32_t parentY = absY >> 2;
        int32_t parentZ = absZ >> 2;

        double fractX = (double)(absX & 3) * 0.25;
        double fractY = (double)(absY & 3) * 0.25;
        double fractZ = (double)(absZ & 3) * 0.25;

        int64_t cx0 = (int64_t)parentX;
        int64_t cx1 = (int64_t)(parentX + 1);
        int64_t cy0 = (int64_t)parentY;
        int64_t cy1 = (int64_t)(parentY + 1);

        /* Precompute shared x and (x,y) LCG prefixes for the 4 corner pairs */
        uint64_t rx0 = mc_lcg_next_u64((uint64_t)seed, cx0);
        uint64_t rx1 = mc_lcg_next_u64((uint64_t)seed, cx1);
        uint64_t r00 = mc_lcg_next_u64(rx0, cy0);
        uint64_t r01 = mc_lcg_next_u64(rx0, cy1);
        uint64_t r10 = mc_lcg_next_u64(rx1, cy0);
        uint64_t r11 = mc_lcg_next_u64(rx1, cy1);

        k_v128_t v_cx0 = k_i64x2_splat(cx0);
        k_v128_t v_cx1 = k_i64x2_splat(cx1);
        k_v128_t v_cy0 = k_i64x2_splat(cy0);
        k_v128_t v_cy1 = k_i64x2_splat(cy1);
        k_v128_t v_cz  = k_i64x2_make((int64_t)parentZ, (int64_t)(parentZ + 1));

        k_v128_t v_dx0 = k_f64x2_splat(fractX);
        k_v128_t v_dx1 = k_f64x2_splat(fractX - 1.0);
        k_v128_t v_dy0 = k_f64x2_splat(fractY);
        k_v128_t v_dy1 = k_f64x2_splat(fractY - 1.0);
        k_v128_t v_dz  = k_f64x2_make(fractZ, fractZ - 1.0);

        /* 4 SIMD f64x2 pairs covering all 8 corners */
        k_v128_t d01 = biome_corner_pair_dist2(r00, v_seed, v_cx0, v_cy0, v_cz, v_dx0, v_dy0, v_dz);
        k_v128_t d23 = biome_corner_pair_dist2(r01, v_seed, v_cx0, v_cy1, v_cz, v_dx0, v_dy1, v_dz);
        k_v128_t d45 = biome_corner_pair_dist2(r10, v_seed, v_cx1, v_cy0, v_cz, v_dx1, v_dy0, v_dz);
        k_v128_t d67 = biome_corner_pair_dist2(r11, v_seed, v_cx1, v_cy1, v_cz, v_dx1, v_dy1, v_dz);

        /* Vectorized argmin with exact smallest-index tie-breaking */
        k_v128_t mA = k_f64x2_lt(d23, d01);
        k_v128_t vA = k_v128_bitselect(d23, d01, mA);
        k_v128_t iA = k_v128_bitselect(v_idx23, v_idx01, mA);

        k_v128_t mB = k_f64x2_lt(d67, d45);
        k_v128_t vB = k_v128_bitselect(d67, d45, mB);
        k_v128_t iB = k_v128_bitselect(v_idx67, v_idx45, mB);

        k_v128_t mC = k_f64x2_lt(vB, vA);
        k_v128_t vMin = k_v128_bitselect(vB, vA, mC);
        k_v128_t iMin = k_v128_bitselect(iB, iA, mC);

        double val0 = k_f64x2_lane0(vMin);
        double val1 = k_f64x2_lane1(vMin);
        int32_t idx0 = (int32_t)k_i64x2_lane0(iMin);
        int32_t idx1 = (int32_t)k_i64x2_lane1(iMin);

        int32_t minI = (val1 < val0 || (val1 == val0 && idx1 < idx0)) ? idx1 : idx0;

        int32_t *op = outQuartXyz + i * 3;
        op[0] = ((minI & 4) == 0) ? parentX : (parentX + 1);
        op[1] = ((minI & 2) == 0) ? parentY : (parentY + 1);
        op[2] = ((minI & 1) == 0) ? parentZ : (parentZ + 1);
    }
}


/* ============================================================================
 * KERNEL 5: simd_cull_aabb_frustum_fog
 * Tests 4 world-space AABBs simultaneously in f32x4 against:
 *   1) All 6 frustum planes (planes24[p*4 + 0..3] = {nx, ny, nz, d},
 *      camera-relative p-vertex test `nx*px + ny*py + nz*pz + d >= 0.0f`), AND
 *   2) Spherical fog (`sphRadSq > 0`: `dx^2 + dy^2 + dz^2 <= sphRadSq`) AND
 *      Cylindrical fog (`cylRadSq > 0`: `max(dx^2 + dz^2, dy^2) <= cylRadSq`),
 *      where `dx = max(0, max(minX - camX, camX - maxX))` etc.
 * Writes packed 1-bit visibility masks into `outVisibleMask[i >> 5] |= (vis << (i & 31))`.
 * ============================================================================ */

static inline int32_t cull_4_aabbs(
    const float *planes24,
    k_v128_t v_camX, k_v128_t v_camY, k_v128_t v_camZ,
    float sphRadSq, float cylRadSq,
    k_v128_t v_minX, k_v128_t v_minY, k_v128_t v_minZ,
    k_v128_t v_maxX, k_v128_t v_maxY, k_v128_t v_maxZ
) {
    k_v128_t x1 = k_f32x4_sub(v_minX, v_camX);
    k_v128_t y1 = k_f32x4_sub(v_minY, v_camY);
    k_v128_t z1 = k_f32x4_sub(v_minZ, v_camZ);
    k_v128_t x2 = k_f32x4_sub(v_maxX, v_camX);
    k_v128_t y2 = k_f32x4_sub(v_maxY, v_camY);
    k_v128_t z2 = k_f32x4_sub(v_maxZ, v_camZ);

    const k_v128_t v_zero = k_f32x4_splat(0.0f);
    k_v128_t vis = k_i32x4_splat(-1);

    /* 6 frustum planes: scalar sign selects p-vertex vector across all 4 AABBs */
    for (int p = 0; p < 6; p++) {
        float nx = planes24[p * 4 + 0];
        float ny = planes24[p * 4 + 1];
        float nz = planes24[p * 4 + 2];
        float d  = planes24[p * 4 + 3];

        k_v128_t px = (nx < 0.0f) ? x1 : x2;
        k_v128_t py = (ny < 0.0f) ? y1 : y2;
        k_v128_t pz = (nz < 0.0f) ? z1 : z2;

        k_v128_t dot = k_f32x4_add(
            k_f32x4_add(k_f32x4_mul(k_f32x4_splat(nx), px), k_f32x4_mul(k_f32x4_splat(ny), py)),
            k_f32x4_add(k_f32x4_mul(k_f32x4_splat(nz), pz), k_f32x4_splat(d))
        );
        vis = k_v128_and(vis, k_f32x4_ge(dot, v_zero));
    }

    /* Spherical and/or cylindrical fog bounds (matching Web263Culling.beyondFog) */
    if (sphRadSq > 0.0f || cylRadSq > 0.0f) {
        k_v128_t dx = k_f32x4_max(v_zero, k_f32x4_max(x1, k_f32x4_neg(x2)));
        k_v128_t dy = k_f32x4_max(v_zero, k_f32x4_max(y1, k_f32x4_neg(y2)));
        k_v128_t dz = k_f32x4_max(v_zero, k_f32x4_max(z1, k_f32x4_neg(z2)));
        k_v128_t horizSq = k_f32x4_add(k_f32x4_mul(dx, dx), k_f32x4_mul(dz, dz));
        k_v128_t dySq    = k_f32x4_mul(dy, dy);

        if (sphRadSq > 0.0f) {
            k_v128_t distSq = k_f32x4_add(horizSq, dySq);
            vis = k_v128_and(vis, k_f32x4_le(distSq, k_f32x4_splat(sphRadSq)));
        }
        if (cylRadSq > 0.0f) {
            k_v128_t cylSq = k_f32x4_max(horizSq, dySq);
            vis = k_v128_and(vis, k_f32x4_le(cylSq, k_f32x4_splat(cylRadSq)));
        }
    }

    return k_i32x4_bitmask(vis);
}

KERNEL_EXPORT(simd_cull_aabb_frustum_fog)
void simd_cull_aabb_frustum_fog(
    const float *planes24,
    float camX, float camY, float camZ,
    float sphRadSq, float cylRadSq,
    const float *minX, const float *minY, const float *minZ,
    const float *maxX, const float *maxY, const float *maxZ,
    int count,
    uint32_t *outVisibleMask
) {
    if (count <= 0) return;

    const k_v128_t v_camX = k_f32x4_splat(camX);
    const k_v128_t v_camY = k_f32x4_splat(camY);
    const k_v128_t v_camZ = k_f32x4_splat(camZ);

    int wordCount = (count + 31) >> 5;
    for (int w = 0; w < wordCount; w++) {
        int base = w << 5;
        int rem = count - base;
        if (rem > 32) rem = 32;

        uint32_t maskWord = 0u;
        int off = 0;
        for (; off + 4 <= rem; off += 4) {
            int i = base + off;
            int32_t m4 = cull_4_aabbs(
                planes24, v_camX, v_camY, v_camZ, sphRadSq, cylRadSq,
                k_v128_load(minX + i), k_v128_load(minY + i), k_v128_load(minZ + i),
                k_v128_load(maxX + i), k_v128_load(maxY + i), k_v128_load(maxZ + i)
            );
            maskWord |= ((uint32_t)m4) << (uint32_t)off;
        }
        if (off < rem) {
            int tail = rem - off;
            int i0 = base + off;
            int i1 = (tail > 1) ? (i0 + 1) : i0;
            int i2 = (tail > 2) ? (i0 + 2) : i0;
            int32_t m4 = cull_4_aabbs(
                planes24, v_camX, v_camY, v_camZ, sphRadSq, cylRadSq,
                k_f32x4_make(minX[i0], minX[i1], minX[i2], minX[i0]),
                k_f32x4_make(minY[i0], minY[i1], minY[i2], minY[i0]),
                k_f32x4_make(minZ[i0], minZ[i1], minZ[i2], minZ[i0]),
                k_f32x4_make(maxX[i0], maxX[i1], maxX[i2], maxX[i0]),
                k_f32x4_make(maxY[i0], maxY[i1], maxY[i2], maxY[i0]),
                k_f32x4_make(maxZ[i0], maxZ[i1], maxZ[i2], maxZ[i0])
            );
            uint32_t validBits = (1u << (uint32_t)tail) - 1u;
            maskWord |= ((uint32_t)m4 & validBits) << (uint32_t)off;
        }
        outVisibleMask[w] = maskWord;
    }
}


/* ============================================================================
 * KERNEL 6: simd_sort_translucent_quads
 * Minecraft DefaultVertexFormat.BLOCK = 28 bytes/vertex (7 floats/vertex),
 * 112 bytes/quad (28 floats/quad).
 *   - Centroid matches MeshData.decodeQuadCentroids:
 *       midpoint of vertex 0 (floats +0..+2) and vertex 2 (floats +14..+16).
 *   - Vectorized 4-wide f32x4 centroid + distSq computation into scratchDist.
 *   - Zero-allocation in-place O(N log N) Floyd heapsort (insertion sort for
 *     small N <= 32) using a strict total order that matches Java's stable
 *     descending `IntArrays.mergeSort(..., Floats.compare(keys[o2], keys[o1]))`.
 *   - Emits 6 triangle indices per quad: `q*4+0, +1, +2, +2, +3, +0`.
 * ============================================================================ */

/* Strict total order matching descending Floats.compare(dist[a], dist[b]) with
 * stable tie-breaking (smaller original quad index comes first).
 * Returns non-zero iff quad `a` should appear BEFORE quad `b` in sorted output. */
static inline int quad_comes_before(const float *dist, uint32_t a, uint32_t b) {
    float da = dist[a];
    float db = dist[b];
    int na = (da != da);
    int nb = (db != db);
    if (na != nb) return na;          /* NaN sorts before finite (Floats.compare) */
    if (!na && da != db) return da > db;
    return a < b;                     /* exact stable tie-break */
}

/* Sift down for a MIN-heap wrt `quad_comes_before` (so the LAST element in final
 * descending order sits at the heap root, and popping to the back yields sorted order). */
static inline __attribute__((always_inline)) void quad_sift_down(const float *dist, uint32_t *order, int start, int end) {
    int root = start;
    uint32_t val = order[root];
    for (;;) {
        int child = (root << 1) + 1;
        if (child >= end) break;
        /* Pick the child that comes LATER in sorted order (worst/smallest distSq) */
        if (child + 1 < end && quad_comes_before(dist, order[child], order[child + 1])) {
            child++;
        }
        if (quad_comes_before(dist, val, order[child])) {
            order[root] = order[child];
            root = child;
        } else {
            break;
        }
    }
    order[root] = val;
}

KERNEL_EXPORT(simd_sort_translucent_quads)
void simd_sort_translucent_quads(
    const float *verts28B,
    int quadCount,
    float camX, float camY, float camZ,
    float *scratchDist,
    uint32_t *scratchOrder,
    uint32_t *outIndices
) {
    if (quadCount <= 0) return;

    const k_v128_t v_half = k_f32x4_splat(0.5f);
    const k_v128_t v_camX = k_f32x4_splat(camX);
    const k_v128_t v_camY = k_f32x4_splat(camY);
    const k_v128_t v_camZ = k_f32x4_splat(camZ);
    const k_v128_t v_0123 = k_i32x4_make(0, 1, 2, 3);

    /* Phase 1: 4-wide f32x4 centroid squared distances + initial order [0..N-1] */
    int q = 0;
    for (; q + 4 <= quadCount; q += 4) {
        const float *p0 = verts28B + (q + 0) * 28;
        const float *p1 = verts28B + (q + 1) * 28;
        const float *p2 = verts28B + (q + 2) * 28;
        const float *p3 = verts28B + (q + 3) * 28;

        k_v128_t x0 = k_f32x4_make(p0[0],  p1[0],  p2[0],  p3[0]);
        k_v128_t y0 = k_f32x4_make(p0[1],  p1[1],  p2[1],  p3[1]);
        k_v128_t z0 = k_f32x4_make(p0[2],  p1[2],  p2[2],  p3[2]);
        k_v128_t x2 = k_f32x4_make(p0[14], p1[14], p2[14], p3[14]);
        k_v128_t y2 = k_f32x4_make(p0[15], p1[15], p2[15], p3[15]);
        k_v128_t z2 = k_f32x4_make(p0[16], p1[16], p2[16], p3[16]);

        k_v128_t dx = k_f32x4_sub(k_f32x4_mul(k_f32x4_add(x0, x2), v_half), v_camX);
        k_v128_t dy = k_f32x4_sub(k_f32x4_mul(k_f32x4_add(y0, y2), v_half), v_camY);
        k_v128_t dz = k_f32x4_sub(k_f32x4_mul(k_f32x4_add(z0, z2), v_half), v_camZ);

        k_v128_t distSq = k_f32x4_add(
            k_f32x4_add(k_f32x4_mul(dx, dx), k_f32x4_mul(dy, dy)),
            k_f32x4_mul(dz, dz)
        );
        k_v128_store(scratchDist + q, distSq);
        k_v128_store(scratchOrder + q, k_i32x4_add(k_i32x4_splat(q), v_0123));
    }
    for (; q < quadCount; q++) {
        const float *p = verts28B + q * 28;
        float dx = (p[0] + p[14]) * 0.5f - camX;
        float dy = (p[1] + p[15]) * 0.5f - camY;
        float dz = (p[2] + p[16]) * 0.5f - camZ;
        scratchDist[q] = dx * dx + dy * dy + dz * dz;
        scratchOrder[q] = (uint32_t)q;
    }

    /* Phase 2: In-place O(N log N) sort back-to-front (zero heap / zero stack array) */
    if (quadCount <= 32) {
        for (int i = 1; i < quadCount; i++) {
            uint32_t key = scratchOrder[i];
            int j = i - 1;
            while (j >= 0 && quad_comes_before(scratchDist, key, scratchOrder[j])) {
                scratchOrder[j + 1] = scratchOrder[j];
                j--;
            }
            scratchOrder[j + 1] = key;
        }
    } else {
        for (int start = (quadCount >> 1) - 1; start >= 0; start--) {
            quad_sift_down(scratchDist, scratchOrder, start, quadCount);
        }
        for (int end = quadCount - 1; end > 0; end--) {
            uint32_t tmp = scratchOrder[0];
            scratchOrder[0] = scratchOrder[end];
            scratchOrder[end] = tmp;
            quad_sift_down(scratchDist, scratchOrder, 0, end);
        }
    }

    /* Phase 3: Emit 6 sorted triangle indices per quad (q*4 + {0,1,2,2,3,0}) */
    for (int i = 0; i < quadCount; i++) {
        uint32_t base = scratchOrder[i] << 2;
        uint32_t *dst = outIndices + i * 6;
        k_v128_store(dst, k_i32x4_make((int32_t)base, (int32_t)(base + 1u), (int32_t)(base + 2u), (int32_t)(base + 2u)));
        dst[4] = base + 3u;
        dst[5] = base;
    }
}


/* ============================================================================
 * KERNEL 7: simd_mat4_mul_batch
 * Column-major f32x4 SIMD Matrix4f batch multiplication:
 *   out[i] = a[i] * b[i]  (16 floats per matrix; safe for in-place aliasing!)
 * ============================================================================ */

KERNEL_EXPORT(simd_mat4_mul_batch)
void simd_mat4_mul_batch(const float *a, const float *b, float *out, int count) {
    if (count <= 0) return;

    for (int i = 0; i < count; i++) {
        const float *pa = a + i * 16;
        const float *pb = b + i * 16;
        float *po = out + i * 16;

        k_v128_t a0 = k_v128_load(pa + 0);
        k_v128_t a1 = k_v128_load(pa + 4);
        k_v128_t a2 = k_v128_load(pa + 8);
        k_v128_t a3 = k_v128_load(pa + 12);

        k_v128_t b0 = k_v128_load(pb + 0);
        k_v128_t b1 = k_v128_load(pb + 4);
        k_v128_t b2 = k_v128_load(pb + 8);
        k_v128_t b3 = k_v128_load(pb + 12);

        k_v128_t c0 = k_f32x4_add(
            k_f32x4_add(
                k_f32x4_add(k_f32x4_mul(a0, k_f32x4_splat_lane0(b0)),
                            k_f32x4_mul(a1, k_f32x4_splat_lane1(b0))),
                k_f32x4_mul(a2, k_f32x4_splat_lane2(b0))),
            k_f32x4_mul(a3, k_f32x4_splat_lane3(b0))
        );
        k_v128_t c1 = k_f32x4_add(
            k_f32x4_add(
                k_f32x4_add(k_f32x4_mul(a0, k_f32x4_splat_lane0(b1)),
                            k_f32x4_mul(a1, k_f32x4_splat_lane1(b1))),
                k_f32x4_mul(a2, k_f32x4_splat_lane2(b1))),
            k_f32x4_mul(a3, k_f32x4_splat_lane3(b1))
        );
        k_v128_t c2 = k_f32x4_add(
            k_f32x4_add(
                k_f32x4_add(k_f32x4_mul(a0, k_f32x4_splat_lane0(b2)),
                            k_f32x4_mul(a1, k_f32x4_splat_lane1(b2))),
                k_f32x4_mul(a2, k_f32x4_splat_lane2(b2))),
            k_f32x4_mul(a3, k_f32x4_splat_lane3(b2))
        );
        k_v128_t c3 = k_f32x4_add(
            k_f32x4_add(
                k_f32x4_add(k_f32x4_mul(a0, k_f32x4_splat_lane0(b3)),
                            k_f32x4_mul(a1, k_f32x4_splat_lane1(b3))),
                k_f32x4_mul(a2, k_f32x4_splat_lane2(b3))),
            k_f32x4_mul(a3, k_f32x4_splat_lane3(b3))
        );

        k_v128_store(po + 0,  c0);
        k_v128_store(po + 4,  c1);
        k_v128_store(po + 8,  c2);
        k_v128_store(po + 12, c3);
    }
}
