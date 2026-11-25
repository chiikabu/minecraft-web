/*===---- wasm_simd128_native.h - Portable WASM SIMD128 parity shim -------===
 *
 * When compiling for WebAssembly (__wasm__), delegates directly to Clang's
 * <wasm_simd128.h>.
 *
 * When compiling natively (x86_64 / ARM64) for parity testing with
 * `clang -O3 -shared -fPIC`, provides bit-exact inline implementations of
 * WebAssembly SIMD128 intrinsics with zero external libm dependency.
 *===----------------------------------------------------------------------===*/

#ifndef EAGLER_WASM_SIMD128_NATIVE_H
#define EAGLER_WASM_SIMD128_NATIVE_H

#ifdef __wasm__

#include <wasm_simd128.h>

#else /* Native host build (x86_64 / ARM64) */

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

/* User-facing 128-bit vector type matching Clang's <wasm_simd128.h>. */
typedef int32_t v128_t __attribute__((__vector_size__(16), __aligned__(16)));

/* Internal lane-view vector types. */
typedef int8_t __wn_i8x16 __attribute__((__vector_size__(16), __aligned__(16)));
typedef uint8_t __wn_u8x16 __attribute__((__vector_size__(16), __aligned__(16)));
typedef int16_t __wn_i16x8 __attribute__((__vector_size__(16), __aligned__(16)));
typedef uint16_t __wn_u16x8 __attribute__((__vector_size__(16), __aligned__(16)));
typedef int32_t __wn_i32x4 __attribute__((__vector_size__(16), __aligned__(16)));
typedef uint32_t __wn_u32x4 __attribute__((__vector_size__(16), __aligned__(16)));
typedef int64_t __wn_i64x2 __attribute__((__vector_size__(16), __aligned__(16)));
typedef uint64_t __wn_u64x2 __attribute__((__vector_size__(16), __aligned__(16)));
typedef float __wn_f32x4 __attribute__((__vector_size__(16), __aligned__(16)));
typedef double __wn_f64x2 __attribute__((__vector_size__(16), __aligned__(16)));

#define __WN_INLINE static __inline__ __attribute__((__always_inline__))

/* -------------------------------------------------------------------------
 * Internal scalar IEEE-754 helpers (zero libm dependency, exact WASM rules)
 * ------------------------------------------------------------------------- */

__WN_INLINE uint32_t __wn_f32_to_u32(float x) {
  uint32_t u;
  memcpy(&u, &x, 4);
  return u;
}

__WN_INLINE float __wn_u32_to_f32(uint32_t u) {
  float x;
  memcpy(&x, &u, 4);
  return x;
}

__WN_INLINE uint64_t __wn_f64_to_u64(double x) {
  uint64_t u;
  memcpy(&u, &x, 8);
  return u;
}

__WN_INLINE double __wn_u64_to_f64(uint64_t u) {
  double x;
  memcpy(&x, &u, 8);
  return x;
}

__WN_INLINE float __wn_f32_abs(float x) {
  return __wn_u32_to_f32(__wn_f32_to_u32(x) & 0x7FFFFFFFu);
}

__WN_INLINE double __wn_f64_abs(double x) {
  return __wn_u64_to_f64(__wn_f64_to_u64(x) & 0x7FFFFFFFFFFFFFFFULL);
}

__WN_INLINE float __wn_f32_min(float a, float b) {
  if (a != a || b != b) return __wn_u32_to_f32(0x7FC00000u);
  if (a == 0.0f && b == 0.0f) {
    return ((__wn_f32_to_u32(a) | __wn_f32_to_u32(b)) & 0x80000000u) ? -0.0f : 0.0f;
  }
  return a < b ? a : b;
}

__WN_INLINE float __wn_f32_max(float a, float b) {
  if (a != a || b != b) return __wn_u32_to_f32(0x7FC00000u);
  if (a == 0.0f && b == 0.0f) {
    return ((__wn_f32_to_u32(a) & __wn_f32_to_u32(b)) & 0x80000000u) ? -0.0f : 0.0f;
  }
  return a > b ? a : b;
}

__WN_INLINE double __wn_f64_min(double a, double b) {
  if (a != a || b != b) return __wn_u64_to_f64(0x7FF8000000000000ULL);
  if (a == 0.0 && b == 0.0) {
    return ((__wn_f64_to_u64(a) | __wn_f64_to_u64(b)) & 0x8000000000000000ULL) ? -0.0 : 0.0;
  }
  return a < b ? a : b;
}

__WN_INLINE double __wn_f64_max(double a, double b) {
  if (a != a || b != b) return __wn_u64_to_f64(0x7FF8000000000000ULL);
  if (a == 0.0 && b == 0.0) {
    return ((__wn_f64_to_u64(a) & __wn_f64_to_u64(b)) & 0x8000000000000000ULL) ? -0.0 : 0.0;
  }
  return a > b ? a : b;
}

__WN_INLINE float __wn_f32_trunc(float x) {
  uint32_t u = __wn_f32_to_u32(x);
  int exp = (int)((u >> 23) & 0xFFu) - 127;
  if (exp >= 23) return x;
  if (exp < 0) return __wn_u32_to_f32(u & 0x80000000u);
  uint32_t mask = 0x007FFFFFu >> exp;
  return __wn_u32_to_f32(u & ~mask);
}

__WN_INLINE float __wn_f32_floor(float x) {
  float t = __wn_f32_trunc(x);
  return (x < t) ? (t - 1.0f) : t;
}

__WN_INLINE float __wn_f32_ceil(float x) {
  float t = __wn_f32_trunc(x);
  return (x > t) ? (t + 1.0f) : t;
}

__WN_INLINE float __wn_f32_nearest(float x) {
  uint32_t u = __wn_f32_to_u32(x);
  uint32_t sign = u & 0x80000000u;
  float ax = __wn_u32_to_f32(u & 0x7FFFFFFFu);
  if (!(ax < 8388608.0f)) return x; /* NaN, Inf, or already integer */
  /* Round-to-nearest-even via IEEE-754 addition/subtraction of 2^23. */
  volatile float t = ax + 8388608.0f;
  float r = t - 8388608.0f;
  return __wn_u32_to_f32(__wn_f32_to_u32(r) | sign);
}

__WN_INLINE double __wn_f64_trunc(double x) {
  uint64_t u = __wn_f64_to_u64(x);
  int exp = (int)((u >> 52) & 0x7FFu) - 1023;
  if (exp >= 52) return x;
  if (exp < 0) return __wn_u64_to_f64(u & 0x8000000000000000ULL);
  uint64_t mask = 0x000FFFFFFFFFFFFFULL >> exp;
  return __wn_u64_to_f64(u & ~mask);
}

__WN_INLINE double __wn_f64_floor(double x) {
  double t = __wn_f64_trunc(x);
  return (x < t) ? (t - 1.0) : t;
}

__WN_INLINE double __wn_f64_ceil(double x) {
  double t = __wn_f64_trunc(x);
  return (x > t) ? (t + 1.0) : t;
}

__WN_INLINE double __wn_f64_nearest(double x) {
  uint64_t u = __wn_f64_to_u64(x);
  uint64_t sign = u & 0x8000000000000000ULL;
  double ax = __wn_u64_to_f64(u & 0x7FFFFFFFFFFFFFFFULL);
  if (!(ax < 4503599627370496.0)) return x; /* NaN, Inf, or already integer */
  volatile double t = ax + 4503599627370496.0;
  double r = t - 4503599627370496.0;
  return __wn_u64_to_f64(__wn_f64_to_u64(r) | sign);
}

__WN_INLINE int32_t __wn_trunc_sat_s_i32_f32(float x) {
  if (x != x) return 0;
  if (x >= 2147483648.0f) return INT32_MAX;
  if (x <= -2147483648.0f) return INT32_MIN;
  return (int32_t)x;
}

__WN_INLINE uint32_t __wn_trunc_sat_u_i32_f32(float x) {
  if (x != x || x <= 0.0f) return 0u;
  if (x >= 4294967296.0f) return UINT32_MAX;
  return (uint32_t)x;
}

__WN_INLINE int32_t __wn_trunc_sat_s_i32_f64(double x) {
  if (x != x) return 0;
  if (x >= 2147483647.0) return INT32_MAX;
  if (x <= -2147483648.0) return INT32_MIN;
  return (int32_t)x;
}

__WN_INLINE uint32_t __wn_trunc_sat_u_i32_f64(double x) {
  if (x != x || x <= 0.0) return 0u;
  if (x >= 4294967295.0) return UINT32_MAX;
  return (uint32_t)x;
}

/* -------------------------------------------------------------------------
 * Memory load / store intrinsics
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_v128_load(const void *__mem) {
  v128_t __v;
  memcpy(&__v, __mem, 16);
  return __v;
}

__WN_INLINE void wasm_v128_store(void *__mem, v128_t __a) {
  memcpy(__mem, &__a, 16);
}

__WN_INLINE v128_t wasm_v128_load8_splat(const void *__mem) {
  uint8_t __v;
  memcpy(&__v, __mem, 1);
  return (v128_t)(__wn_u8x16){__v, __v, __v, __v, __v, __v, __v, __v,
                              __v, __v, __v, __v, __v, __v, __v, __v};
}

__WN_INLINE v128_t wasm_v128_load16_splat(const void *__mem) {
  uint16_t __v;
  memcpy(&__v, __mem, 2);
  return (v128_t)(__wn_u16x8){__v, __v, __v, __v, __v, __v, __v, __v};
}

__WN_INLINE v128_t wasm_v128_load32_splat(const void *__mem) {
  uint32_t __v;
  memcpy(&__v, __mem, 4);
  return (v128_t)(__wn_u32x4){__v, __v, __v, __v};
}

__WN_INLINE v128_t wasm_v128_load64_splat(const void *__mem) {
  uint64_t __v;
  memcpy(&__v, __mem, 8);
  return (v128_t)(__wn_u64x2){__v, __v};
}

__WN_INLINE v128_t wasm_v128_load32_zero(const void *__mem) {
  int32_t __v;
  memcpy(&__v, __mem, 4);
  return (v128_t)(__wn_i32x4){__v, 0, 0, 0};
}

__WN_INLINE v128_t wasm_v128_load64_zero(const void *__mem) {
  int64_t __v;
  memcpy(&__v, __mem, 8);
  return (v128_t)(__wn_i64x2){__v, 0};
}

__WN_INLINE v128_t wasm_i16x8_load8x8(const void *__mem) {
  int8_t b[8];
  memcpy(b, __mem, 8);
  return (v128_t)(__wn_i16x8){b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]};
}

__WN_INLINE v128_t wasm_u16x8_load8x8(const void *__mem) {
  uint8_t b[8];
  memcpy(b, __mem, 8);
  return (v128_t)(__wn_u16x8){b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]};
}

__WN_INLINE v128_t wasm_i32x4_load16x4(const void *__mem) {
  int16_t b[4];
  memcpy(b, __mem, 8);
  return (v128_t)(__wn_i32x4){b[0], b[1], b[2], b[3]};
}

__WN_INLINE v128_t wasm_u32x4_load16x4(const void *__mem) {
  uint16_t b[4];
  memcpy(b, __mem, 8);
  return (v128_t)(__wn_u32x4){b[0], b[1], b[2], b[3]};
}

__WN_INLINE v128_t wasm_i64x2_load32x2(const void *__mem) {
  int32_t b[2];
  memcpy(b, __mem, 8);
  return (v128_t)(__wn_i64x2){b[0], b[1]};
}

__WN_INLINE v128_t wasm_u64x2_load32x2(const void *__mem) {
  uint32_t b[2];
  memcpy(b, __mem, 8);
  return (v128_t)(__wn_u64x2){b[0], b[1]};
}

__WN_INLINE v128_t wasm_v128_load8_lane(const void *__mem, v128_t __vec, int __i) {
  int8_t __v;
  memcpy(&__v, __mem, 1);
  __wn_i8x16 __r = (__wn_i8x16)__vec;
  __r[__i] = __v;
  return (v128_t)__r;
}

__WN_INLINE v128_t wasm_v128_load16_lane(const void *__mem, v128_t __vec, int __i) {
  int16_t __v;
  memcpy(&__v, __mem, 2);
  __wn_i16x8 __r = (__wn_i16x8)__vec;
  __r[__i] = __v;
  return (v128_t)__r;
}

__WN_INLINE v128_t wasm_v128_load32_lane(const void *__mem, v128_t __vec, int __i) {
  int32_t __v;
  memcpy(&__v, __mem, 4);
  __wn_i32x4 __r = (__wn_i32x4)__vec;
  __r[__i] = __v;
  return (v128_t)__r;
}

__WN_INLINE v128_t wasm_v128_load64_lane(const void *__mem, v128_t __vec, int __i) {
  int64_t __v;
  memcpy(&__v, __mem, 8);
  __wn_i64x2 __r = (__wn_i64x2)__vec;
  __r[__i] = __v;
  return (v128_t)__r;
}

__WN_INLINE void wasm_v128_store8_lane(void *__mem, v128_t __vec, int __i) {
  int8_t __v = ((__wn_i8x16)__vec)[__i];
  memcpy(__mem, &__v, 1);
}

__WN_INLINE void wasm_v128_store16_lane(void *__mem, v128_t __vec, int __i) {
  int16_t __v = ((__wn_i16x8)__vec)[__i];
  memcpy(__mem, &__v, 2);
}

__WN_INLINE void wasm_v128_store32_lane(void *__mem, v128_t __vec, int __i) {
  int32_t __v = ((__wn_i32x4)__vec)[__i];
  memcpy(__mem, &__v, 4);
}

__WN_INLINE void wasm_v128_store64_lane(void *__mem, v128_t __vec, int __i) {
  int64_t __v = ((__wn_i64x2)__vec)[__i];
  memcpy(__mem, &__v, 8);
}

/* -------------------------------------------------------------------------
 * Constructors, constants, splats, extract / replace lane
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i8x16_make(
    int8_t c0, int8_t c1, int8_t c2, int8_t c3, int8_t c4, int8_t c5, int8_t c6, int8_t c7,
    int8_t c8, int8_t c9, int8_t c10, int8_t c11, int8_t c12, int8_t c13, int8_t c14, int8_t c15) {
  return (v128_t)(__wn_i8x16){c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15};
}

__WN_INLINE v128_t wasm_u8x16_make(
    uint8_t c0, uint8_t c1, uint8_t c2, uint8_t c3, uint8_t c4, uint8_t c5, uint8_t c6, uint8_t c7,
    uint8_t c8, uint8_t c9, uint8_t c10, uint8_t c11, uint8_t c12, uint8_t c13, uint8_t c14, uint8_t c15) {
  return (v128_t)(__wn_u8x16){c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15};
}

__WN_INLINE v128_t wasm_i16x8_make(
    int16_t c0, int16_t c1, int16_t c2, int16_t c3, int16_t c4, int16_t c5, int16_t c6, int16_t c7) {
  return (v128_t)(__wn_i16x8){c0, c1, c2, c3, c4, c5, c6, c7};
}

__WN_INLINE v128_t wasm_u16x8_make(
    uint16_t c0, uint16_t c1, uint16_t c2, uint16_t c3, uint16_t c4, uint16_t c5, uint16_t c6, uint16_t c7) {
  return (v128_t)(__wn_u16x8){c0, c1, c2, c3, c4, c5, c6, c7};
}

__WN_INLINE v128_t wasm_i32x4_make(int32_t c0, int32_t c1, int32_t c2, int32_t c3) {
  return (v128_t)(__wn_i32x4){c0, c1, c2, c3};
}

__WN_INLINE v128_t wasm_u32x4_make(uint32_t c0, uint32_t c1, uint32_t c2, uint32_t c3) {
  return (v128_t)(__wn_u32x4){c0, c1, c2, c3};
}

__WN_INLINE v128_t wasm_i64x2_make(int64_t c0, int64_t c1) {
  return (v128_t)(__wn_i64x2){c0, c1};
}

__WN_INLINE v128_t wasm_u64x2_make(uint64_t c0, uint64_t c1) {
  return (v128_t)(__wn_u64x2){c0, c1};
}

__WN_INLINE v128_t wasm_f32x4_make(float c0, float c1, float c2, float c3) {
  return (v128_t)(__wn_f32x4){c0, c1, c2, c3};
}

__WN_INLINE v128_t wasm_f64x2_make(double c0, double c1) {
  return (v128_t)(__wn_f64x2){c0, c1};
}

#define wasm_i8x16_const wasm_i8x16_make
#define wasm_u8x16_const wasm_u8x16_make
#define wasm_i16x8_const wasm_i16x8_make
#define wasm_u16x8_const wasm_u16x8_make
#define wasm_i32x4_const wasm_i32x4_make
#define wasm_u32x4_const wasm_u32x4_make
#define wasm_i64x2_const wasm_i64x2_make
#define wasm_u64x2_const wasm_u64x2_make
#define wasm_f32x4_const wasm_f32x4_make
#define wasm_f64x2_const wasm_f64x2_make

__WN_INLINE v128_t wasm_i8x16_splat(int8_t a) {
  return (v128_t)(__wn_i8x16){a, a, a, a, a, a, a, a, a, a, a, a, a, a, a, a};
}
__WN_INLINE v128_t wasm_u8x16_splat(uint8_t a) {
  return (v128_t)(__wn_u8x16){a, a, a, a, a, a, a, a, a, a, a, a, a, a, a, a};
}
__WN_INLINE v128_t wasm_i16x8_splat(int16_t a) {
  return (v128_t)(__wn_i16x8){a, a, a, a, a, a, a, a};
}
__WN_INLINE v128_t wasm_u16x8_splat(uint16_t a) {
  return (v128_t)(__wn_u16x8){a, a, a, a, a, a, a, a};
}
__WN_INLINE v128_t wasm_i32x4_splat(int32_t a) {
  return (v128_t)(__wn_i32x4){a, a, a, a};
}
__WN_INLINE v128_t wasm_u32x4_splat(uint32_t a) {
  return (v128_t)(__wn_u32x4){a, a, a, a};
}
__WN_INLINE v128_t wasm_i64x2_splat(int64_t a) {
  return (v128_t)(__wn_i64x2){a, a};
}
__WN_INLINE v128_t wasm_u64x2_splat(uint64_t a) {
  return (v128_t)(__wn_u64x2){a, a};
}
__WN_INLINE v128_t wasm_f32x4_splat(float a) {
  return (v128_t)(__wn_f32x4){a, a, a, a};
}
__WN_INLINE v128_t wasm_f64x2_splat(double a) {
  return (v128_t)(__wn_f64x2){a, a};
}

#define wasm_i8x16_const_splat wasm_i8x16_splat
#define wasm_u8x16_const_splat wasm_u8x16_splat
#define wasm_i16x8_const_splat wasm_i16x8_splat
#define wasm_u16x8_const_splat wasm_u16x8_splat
#define wasm_i32x4_const_splat wasm_i32x4_splat
#define wasm_u32x4_const_splat wasm_u32x4_splat
#define wasm_i64x2_const_splat wasm_i64x2_splat
#define wasm_u64x2_const_splat wasm_u64x2_splat
#define wasm_f32x4_const_splat wasm_f32x4_splat
#define wasm_f64x2_const_splat wasm_f64x2_splat

__WN_INLINE int8_t wasm_i8x16_extract_lane(v128_t a, int i) { return ((__wn_i8x16)a)[i]; }
__WN_INLINE uint8_t wasm_u8x16_extract_lane(v128_t a, int i) { return ((__wn_u8x16)a)[i]; }
__WN_INLINE int16_t wasm_i16x8_extract_lane(v128_t a, int i) { return ((__wn_i16x8)a)[i]; }
__WN_INLINE uint16_t wasm_u16x8_extract_lane(v128_t a, int i) { return ((__wn_u16x8)a)[i]; }
__WN_INLINE int32_t wasm_i32x4_extract_lane(v128_t a, int i) { return ((__wn_i32x4)a)[i]; }
__WN_INLINE uint32_t wasm_u32x4_extract_lane(v128_t a, int i) { return ((__wn_u32x4)a)[i]; }
__WN_INLINE int64_t wasm_i64x2_extract_lane(v128_t a, int i) { return ((__wn_i64x2)a)[i]; }
__WN_INLINE uint64_t wasm_u64x2_extract_lane(v128_t a, int i) { return ((__wn_u64x2)a)[i]; }
__WN_INLINE float wasm_f32x4_extract_lane(v128_t a, int i) { return ((__wn_f32x4)a)[i]; }
__WN_INLINE double wasm_f64x2_extract_lane(v128_t a, int i) { return ((__wn_f64x2)a)[i]; }

__WN_INLINE v128_t wasm_i8x16_replace_lane(v128_t a, int i, int8_t b) {
  __wn_i8x16 v = (__wn_i8x16)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_u8x16_replace_lane(v128_t a, int i, uint8_t b) {
  __wn_u8x16 v = (__wn_u8x16)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_i16x8_replace_lane(v128_t a, int i, int16_t b) {
  __wn_i16x8 v = (__wn_i16x8)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_u16x8_replace_lane(v128_t a, int i, uint16_t b) {
  __wn_u16x8 v = (__wn_u16x8)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_i32x4_replace_lane(v128_t a, int i, int32_t b) {
  __wn_i32x4 v = (__wn_i32x4)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_u32x4_replace_lane(v128_t a, int i, uint32_t b) {
  __wn_u32x4 v = (__wn_u32x4)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_i64x2_replace_lane(v128_t a, int i, int64_t b) {
  __wn_i64x2 v = (__wn_i64x2)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_u64x2_replace_lane(v128_t a, int i, uint64_t b) {
  __wn_u64x2 v = (__wn_u64x2)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_f32x4_replace_lane(v128_t a, int i, float b) {
  __wn_f32x4 v = (__wn_f32x4)a; v[i] = b; return (v128_t)v;
}
__WN_INLINE v128_t wasm_f64x2_replace_lane(v128_t a, int i, double b) {
  __wn_f64x2 v = (__wn_f64x2)a; v[i] = b; return (v128_t)v;
}

/* -------------------------------------------------------------------------
 * Bitwise operations & boolean reductions
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_v128_not(v128_t a) { return ~a; }
__WN_INLINE v128_t wasm_v128_and(v128_t a, v128_t b) { return a & b; }
__WN_INLINE v128_t wasm_v128_or(v128_t a, v128_t b) { return a | b; }
__WN_INLINE v128_t wasm_v128_xor(v128_t a, v128_t b) { return a ^ b; }
__WN_INLINE v128_t wasm_v128_andnot(v128_t a, v128_t b) { return a & ~b; }
__WN_INLINE v128_t wasm_v128_bitselect(v128_t a, v128_t b, v128_t mask) {
  return (a & mask) | (b & ~mask);
}

__WN_INLINE bool wasm_v128_any_true(v128_t a) {
  __wn_u64x2 u = (__wn_u64x2)a;
  return (u[0] | u[1]) != 0;
}

__WN_INLINE bool wasm_i8x16_all_true(v128_t a) {
  __wn_u8x16 v = (__wn_u8x16)a;
  for (int i = 0; i < 16; ++i) if (v[i] == 0) return false;
  return true;
}
__WN_INLINE bool wasm_i16x8_all_true(v128_t a) {
  __wn_u16x8 v = (__wn_u16x8)a;
  for (int i = 0; i < 8; ++i) if (v[i] == 0) return false;
  return true;
}
__WN_INLINE bool wasm_i32x4_all_true(v128_t a) {
  __wn_u32x4 v = (__wn_u32x4)a;
  return (v[0] != 0) && (v[1] != 0) && (v[2] != 0) && (v[3] != 0);
}
__WN_INLINE bool wasm_i64x2_all_true(v128_t a) {
  __wn_u64x2 v = (__wn_u64x2)a;
  return (v[0] != 0) && (v[1] != 0);
}

__WN_INLINE uint32_t wasm_i8x16_bitmask(v128_t a) {
  __wn_u8x16 v = (__wn_u8x16)a;
  uint32_t m = 0;
  for (int i = 0; i < 16; ++i) m |= ((uint32_t)(v[i] >> 7)) << i;
  return m;
}
__WN_INLINE uint32_t wasm_i16x8_bitmask(v128_t a) {
  __wn_u16x8 v = (__wn_u16x8)a;
  uint32_t m = 0;
  for (int i = 0; i < 8; ++i) m |= ((uint32_t)(v[i] >> 15)) << i;
  return m;
}
__WN_INLINE uint32_t wasm_i32x4_bitmask(v128_t a) {
  __wn_u32x4 v = (__wn_u32x4)a;
  return ((v[0] >> 31) << 0) | ((v[1] >> 31) << 1) | ((v[2] >> 31) << 2) | ((v[3] >> 31) << 3);
}
__WN_INLINE uint32_t wasm_i64x2_bitmask(v128_t a) {
  __wn_u64x2 v = (__wn_u64x2)a;
  return (uint32_t)(((v[0] >> 63) << 0) | ((v[1] >> 63) << 1));
}

/* -------------------------------------------------------------------------
 * Comparisons
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i8x16_eq(v128_t a, v128_t b) { return (v128_t)((__wn_i8x16)a == (__wn_i8x16)b); }
__WN_INLINE v128_t wasm_i8x16_ne(v128_t a, v128_t b) { return (v128_t)((__wn_i8x16)a != (__wn_i8x16)b); }
__WN_INLINE v128_t wasm_i8x16_lt(v128_t a, v128_t b) { return (v128_t)((__wn_i8x16)a < (__wn_i8x16)b); }
__WN_INLINE v128_t wasm_u8x16_lt(v128_t a, v128_t b) { return (v128_t)((__wn_u8x16)a < (__wn_u8x16)b); }
__WN_INLINE v128_t wasm_i8x16_gt(v128_t a, v128_t b) { return (v128_t)((__wn_i8x16)a > (__wn_i8x16)b); }
__WN_INLINE v128_t wasm_u8x16_gt(v128_t a, v128_t b) { return (v128_t)((__wn_u8x16)a > (__wn_u8x16)b); }
__WN_INLINE v128_t wasm_i8x16_le(v128_t a, v128_t b) { return (v128_t)((__wn_i8x16)a <= (__wn_i8x16)b); }
__WN_INLINE v128_t wasm_u8x16_le(v128_t a, v128_t b) { return (v128_t)((__wn_u8x16)a <= (__wn_u8x16)b); }
__WN_INLINE v128_t wasm_i8x16_ge(v128_t a, v128_t b) { return (v128_t)((__wn_i8x16)a >= (__wn_i8x16)b); }
__WN_INLINE v128_t wasm_u8x16_ge(v128_t a, v128_t b) { return (v128_t)((__wn_u8x16)a >= (__wn_u8x16)b); }

__WN_INLINE v128_t wasm_i16x8_eq(v128_t a, v128_t b) { return (v128_t)((__wn_i16x8)a == (__wn_i16x8)b); }
__WN_INLINE v128_t wasm_i16x8_ne(v128_t a, v128_t b) { return (v128_t)((__wn_i16x8)a != (__wn_i16x8)b); }
__WN_INLINE v128_t wasm_i16x8_lt(v128_t a, v128_t b) { return (v128_t)((__wn_i16x8)a < (__wn_i16x8)b); }
__WN_INLINE v128_t wasm_u16x8_lt(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a < (__wn_u16x8)b); }
__WN_INLINE v128_t wasm_i16x8_gt(v128_t a, v128_t b) { return (v128_t)((__wn_i16x8)a > (__wn_i16x8)b); }
__WN_INLINE v128_t wasm_u16x8_gt(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a > (__wn_u16x8)b); }
__WN_INLINE v128_t wasm_i16x8_le(v128_t a, v128_t b) { return (v128_t)((__wn_i16x8)a <= (__wn_i16x8)b); }
__WN_INLINE v128_t wasm_u16x8_le(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a <= (__wn_u16x8)b); }
__WN_INLINE v128_t wasm_i16x8_ge(v128_t a, v128_t b) { return (v128_t)((__wn_i16x8)a >= (__wn_i16x8)b); }
__WN_INLINE v128_t wasm_u16x8_ge(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a >= (__wn_u16x8)b); }

__WN_INLINE v128_t wasm_i32x4_eq(v128_t a, v128_t b) { return (v128_t)((__wn_i32x4)a == (__wn_i32x4)b); }
__WN_INLINE v128_t wasm_i32x4_ne(v128_t a, v128_t b) { return (v128_t)((__wn_i32x4)a != (__wn_i32x4)b); }
__WN_INLINE v128_t wasm_i32x4_lt(v128_t a, v128_t b) { return (v128_t)((__wn_i32x4)a < (__wn_i32x4)b); }
__WN_INLINE v128_t wasm_u32x4_lt(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a < (__wn_u32x4)b); }
__WN_INLINE v128_t wasm_i32x4_gt(v128_t a, v128_t b) { return (v128_t)((__wn_i32x4)a > (__wn_i32x4)b); }
__WN_INLINE v128_t wasm_u32x4_gt(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a > (__wn_u32x4)b); }
__WN_INLINE v128_t wasm_i32x4_le(v128_t a, v128_t b) { return (v128_t)((__wn_i32x4)a <= (__wn_i32x4)b); }
__WN_INLINE v128_t wasm_u32x4_le(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a <= (__wn_u32x4)b); }
__WN_INLINE v128_t wasm_i32x4_ge(v128_t a, v128_t b) { return (v128_t)((__wn_i32x4)a >= (__wn_i32x4)b); }
__WN_INLINE v128_t wasm_u32x4_ge(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a >= (__wn_u32x4)b); }

__WN_INLINE v128_t wasm_i64x2_eq(v128_t a, v128_t b) { return (v128_t)((__wn_i64x2)a == (__wn_i64x2)b); }
__WN_INLINE v128_t wasm_i64x2_ne(v128_t a, v128_t b) { return (v128_t)((__wn_i64x2)a != (__wn_i64x2)b); }
__WN_INLINE v128_t wasm_i64x2_lt(v128_t a, v128_t b) { return (v128_t)((__wn_i64x2)a < (__wn_i64x2)b); }
__WN_INLINE v128_t wasm_i64x2_gt(v128_t a, v128_t b) { return (v128_t)((__wn_i64x2)a > (__wn_i64x2)b); }
__WN_INLINE v128_t wasm_i64x2_le(v128_t a, v128_t b) { return (v128_t)((__wn_i64x2)a <= (__wn_i64x2)b); }
__WN_INLINE v128_t wasm_i64x2_ge(v128_t a, v128_t b) { return (v128_t)((__wn_i64x2)a >= (__wn_i64x2)b); }

__WN_INLINE v128_t wasm_f32x4_eq(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a == (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_ne(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a != (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_lt(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a < (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_gt(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a > (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_le(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a <= (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_ge(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a >= (__wn_f32x4)b); }

__WN_INLINE v128_t wasm_f64x2_eq(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a == (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_ne(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a != (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_lt(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a < (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_gt(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a > (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_le(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a <= (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_ge(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a >= (__wn_f64x2)b); }

/* -------------------------------------------------------------------------
 * i8x16 arithmetic, shifts, swizzle, narrow
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i8x16_neg(v128_t a) { return (v128_t)(-(__wn_u8x16)a); }
__WN_INLINE v128_t wasm_i8x16_abs(v128_t a) {
  __wn_i8x16 v = (__wn_i8x16)a, r;
  for (int i = 0; i < 16; ++i) r[i] = v[i] < 0 ? (int8_t)(-(uint8_t)v[i]) : v[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i8x16_add(v128_t a, v128_t b) { return (v128_t)((__wn_u8x16)a + (__wn_u8x16)b); }
__WN_INLINE v128_t wasm_i8x16_sub(v128_t a, v128_t b) { return (v128_t)((__wn_u8x16)a - (__wn_u8x16)b); }
__WN_INLINE v128_t wasm_i8x16_shl(v128_t a, uint32_t s) { return (v128_t)((__wn_u8x16)a << (s & 7u)); }
__WN_INLINE v128_t wasm_i8x16_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_i8x16)a >> (s & 7u)); }
__WN_INLINE v128_t wasm_u8x16_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_u8x16)a >> (s & 7u)); }
__WN_INLINE v128_t wasm_i8x16_min(v128_t a, v128_t b) {
  __wn_i8x16 va = (__wn_i8x16)a, vb = (__wn_i8x16)b, r;
  for (int i = 0; i < 16; ++i) r[i] = va[i] < vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u8x16_min(v128_t a, v128_t b) {
  __wn_u8x16 va = (__wn_u8x16)a, vb = (__wn_u8x16)b, r;
  for (int i = 0; i < 16; ++i) r[i] = va[i] < vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i8x16_max(v128_t a, v128_t b) {
  __wn_i8x16 va = (__wn_i8x16)a, vb = (__wn_i8x16)b, r;
  for (int i = 0; i < 16; ++i) r[i] = va[i] > vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u8x16_max(v128_t a, v128_t b) {
  __wn_u8x16 va = (__wn_u8x16)a, vb = (__wn_u8x16)b, r;
  for (int i = 0; i < 16; ++i) r[i] = va[i] > vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i8x16_add_sat(v128_t a, v128_t b) {
  __wn_i8x16 va = (__wn_i8x16)a, vb = (__wn_i8x16)b, r;
  for (int i = 0; i < 16; ++i) {
    int32_t s = (int32_t)va[i] + (int32_t)vb[i];
    r[i] = (int8_t)(s > 127 ? 127 : (s < -128 ? -128 : s));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u8x16_add_sat(v128_t a, v128_t b) {
  __wn_u8x16 va = (__wn_u8x16)a, vb = (__wn_u8x16)b, r;
  for (int i = 0; i < 16; ++i) {
    uint32_t s = (uint32_t)va[i] + (uint32_t)vb[i];
    r[i] = (uint8_t)(s > 255u ? 255u : s);
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i8x16_sub_sat(v128_t a, v128_t b) {
  __wn_i8x16 va = (__wn_i8x16)a, vb = (__wn_i8x16)b, r;
  for (int i = 0; i < 16; ++i) {
    int32_t s = (int32_t)va[i] - (int32_t)vb[i];
    r[i] = (int8_t)(s > 127 ? 127 : (s < -128 ? -128 : s));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u8x16_sub_sat(v128_t a, v128_t b) {
  __wn_u8x16 va = (__wn_u8x16)a, vb = (__wn_u8x16)b, r;
  for (int i = 0; i < 16; ++i) r[i] = va[i] > vb[i] ? (uint8_t)(va[i] - vb[i]) : 0u;
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u8x16_avgr(v128_t a, v128_t b) {
  __wn_u8x16 va = (__wn_u8x16)a, vb = (__wn_u8x16)b, r;
  for (int i = 0; i < 16; ++i) r[i] = (uint8_t)(((uint32_t)va[i] + (uint32_t)vb[i] + 1u) >> 1);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i8x16_popcnt(v128_t a) {
  __wn_u8x16 va = (__wn_u8x16)a, r;
  for (int i = 0; i < 16; ++i) r[i] = (uint8_t)__builtin_popcount((unsigned int)va[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i8x16_swizzle(v128_t a, v128_t idx) {
  __wn_u8x16 va = (__wn_u8x16)a, vi = (__wn_u8x16)idx, r;
  for (int i = 0; i < 16; ++i) r[i] = vi[i] < 16u ? va[vi[i]] : 0u;
  return (v128_t)r;
}

/* -------------------------------------------------------------------------
 * i16x8 arithmetic & shifts
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i16x8_neg(v128_t a) { return (v128_t)(-(__wn_u16x8)a); }
__WN_INLINE v128_t wasm_i16x8_abs(v128_t a) {
  __wn_i16x8 v = (__wn_i16x8)a, r;
  for (int i = 0; i < 8; ++i) r[i] = v[i] < 0 ? (int16_t)(-(uint16_t)v[i]) : v[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i16x8_add(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a + (__wn_u16x8)b); }
__WN_INLINE v128_t wasm_i16x8_sub(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a - (__wn_u16x8)b); }
__WN_INLINE v128_t wasm_i16x8_mul(v128_t a, v128_t b) { return (v128_t)((__wn_u16x8)a * (__wn_u16x8)b); }
__WN_INLINE v128_t wasm_i16x8_shl(v128_t a, uint32_t s) { return (v128_t)((__wn_u16x8)a << (s & 15u)); }
__WN_INLINE v128_t wasm_i16x8_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_i16x8)a >> (s & 15u)); }
__WN_INLINE v128_t wasm_u16x8_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_u16x8)a >> (s & 15u)); }
__WN_INLINE v128_t wasm_i16x8_min(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b, r;
  for (int i = 0; i < 8; ++i) r[i] = va[i] < vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_min(v128_t a, v128_t b) {
  __wn_u16x8 va = (__wn_u16x8)a, vb = (__wn_u16x8)b, r;
  for (int i = 0; i < 8; ++i) r[i] = va[i] < vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i16x8_max(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b, r;
  for (int i = 0; i < 8; ++i) r[i] = va[i] > vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_max(v128_t a, v128_t b) {
  __wn_u16x8 va = (__wn_u16x8)a, vb = (__wn_u16x8)b, r;
  for (int i = 0; i < 8; ++i) r[i] = va[i] > vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i16x8_add_sat(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b, r;
  for (int i = 0; i < 8; ++i) {
    int32_t s = (int32_t)va[i] + (int32_t)vb[i];
    r[i] = (int16_t)(s > 32767 ? 32767 : (s < -32768 ? -32768 : s));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_add_sat(v128_t a, v128_t b) {
  __wn_u16x8 va = (__wn_u16x8)a, vb = (__wn_u16x8)b, r;
  for (int i = 0; i < 8; ++i) {
    uint32_t s = (uint32_t)va[i] + (uint32_t)vb[i];
    r[i] = (uint16_t)(s > 65535u ? 65535u : s);
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i16x8_sub_sat(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b, r;
  for (int i = 0; i < 8; ++i) {
    int32_t s = (int32_t)va[i] - (int32_t)vb[i];
    r[i] = (int16_t)(s > 32767 ? 32767 : (s < -32768 ? -32768 : s));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_sub_sat(v128_t a, v128_t b) {
  __wn_u16x8 va = (__wn_u16x8)a, vb = (__wn_u16x8)b, r;
  for (int i = 0; i < 8; ++i) r[i] = va[i] > vb[i] ? (uint16_t)(va[i] - vb[i]) : 0u;
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_avgr(v128_t a, v128_t b) {
  __wn_u16x8 va = (__wn_u16x8)a, vb = (__wn_u16x8)b, r;
  for (int i = 0; i < 8; ++i) r[i] = (uint16_t)(((uint32_t)va[i] + (uint32_t)vb[i] + 1u) >> 1);
  return (v128_t)r;
}

/* -------------------------------------------------------------------------
 * i32x4 / u32x4 arithmetic & shifts
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i32x4_neg(v128_t a) { return (v128_t)(-(__wn_u32x4)a); }
__WN_INLINE v128_t wasm_i32x4_abs(v128_t a) {
  __wn_i32x4 v = (__wn_i32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = v[i] < 0 ? (int32_t)(-(uint32_t)v[i]) : v[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i32x4_add(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a + (__wn_u32x4)b); }
__WN_INLINE v128_t wasm_i32x4_sub(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a - (__wn_u32x4)b); }
__WN_INLINE v128_t wasm_i32x4_mul(v128_t a, v128_t b) { return (v128_t)((__wn_u32x4)a * (__wn_u32x4)b); }
__WN_INLINE v128_t wasm_i32x4_shl(v128_t a, uint32_t s) { return (v128_t)((__wn_u32x4)a << (s & 31u)); }
__WN_INLINE v128_t wasm_i32x4_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_i32x4)a >> (s & 31u)); }
__WN_INLINE v128_t wasm_u32x4_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_u32x4)a >> (s & 31u)); }
__WN_INLINE v128_t wasm_i32x4_min(v128_t a, v128_t b) {
  __wn_i32x4 va = (__wn_i32x4)a, vb = (__wn_i32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = va[i] < vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u32x4_min(v128_t a, v128_t b) {
  __wn_u32x4 va = (__wn_u32x4)a, vb = (__wn_u32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = va[i] < vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i32x4_max(v128_t a, v128_t b) {
  __wn_i32x4 va = (__wn_i32x4)a, vb = (__wn_i32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = va[i] > vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u32x4_max(v128_t a, v128_t b) {
  __wn_u32x4 va = (__wn_u32x4)a, vb = (__wn_u32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = va[i] > vb[i] ? va[i] : vb[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i32x4_dot_i16x8(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b;
  __wn_i32x4 r;
  for (int i = 0; i < 4; ++i) {
    r[i] = (int32_t)va[2 * i] * (int32_t)vb[2 * i] + (int32_t)va[2 * i + 1] * (int32_t)vb[2 * i + 1];
  }
  return (v128_t)r;
}

/* -------------------------------------------------------------------------
 * i64x2 / u64x2 arithmetic & shifts
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i64x2_neg(v128_t a) { return (v128_t)(-(__wn_u64x2)a); }
__WN_INLINE v128_t wasm_i64x2_abs(v128_t a) {
  __wn_i64x2 v = (__wn_i64x2)a, r;
  r[0] = v[0] < 0 ? (int64_t)(-(uint64_t)v[0]) : v[0];
  r[1] = v[1] < 0 ? (int64_t)(-(uint64_t)v[1]) : v[1];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i64x2_add(v128_t a, v128_t b) { return (v128_t)((__wn_u64x2)a + (__wn_u64x2)b); }
__WN_INLINE v128_t wasm_i64x2_sub(v128_t a, v128_t b) { return (v128_t)((__wn_u64x2)a - (__wn_u64x2)b); }
__WN_INLINE v128_t wasm_i64x2_mul(v128_t a, v128_t b) { return (v128_t)((__wn_u64x2)a * (__wn_u64x2)b); }
__WN_INLINE v128_t wasm_i64x2_shl(v128_t a, uint32_t s) { return (v128_t)((__wn_u64x2)a << (s & 63u)); }
__WN_INLINE v128_t wasm_i64x2_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_i64x2)a >> (s & 63u)); }
__WN_INLINE v128_t wasm_u64x2_shr(v128_t a, uint32_t s) { return (v128_t)((__wn_u64x2)a >> (s & 63u)); }

/* -------------------------------------------------------------------------
 * f32x4 arithmetic
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_f32x4_add(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a + (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_sub(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a - (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_mul(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a * (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_div(v128_t a, v128_t b) { return (v128_t)((__wn_f32x4)a / (__wn_f32x4)b); }
__WN_INLINE v128_t wasm_f32x4_neg(v128_t a) { return (v128_t)(-(__wn_f32x4)a); }
__WN_INLINE v128_t wasm_f32x4_abs(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_abs(v[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_sqrt(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = __builtin_sqrtf(v[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_ceil(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_ceil(v[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_floor(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_floor(v[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_trunc(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_trunc(v[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_nearest(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_nearest(v[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_min(v128_t a, v128_t b) {
  __wn_f32x4 va = (__wn_f32x4)a, vb = (__wn_f32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_min(va[i], vb[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_max(v128_t a, v128_t b) {
  __wn_f32x4 va = (__wn_f32x4)a, vb = (__wn_f32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = __wn_f32_max(va[i], vb[i]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_pmin(v128_t a, v128_t b) {
  __wn_f32x4 va = (__wn_f32x4)a, vb = (__wn_f32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = vb[i] < va[i] ? vb[i] : va[i];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_f32x4_pmax(v128_t a, v128_t b) {
  __wn_f32x4 va = (__wn_f32x4)a, vb = (__wn_f32x4)b, r;
  for (int i = 0; i < 4; ++i) r[i] = va[i] < vb[i] ? vb[i] : va[i];
  return (v128_t)r;
}

/* -------------------------------------------------------------------------
 * f64x2 arithmetic
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_f64x2_add(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a + (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_sub(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a - (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_mul(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a * (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_div(v128_t a, v128_t b) { return (v128_t)((__wn_f64x2)a / (__wn_f64x2)b); }
__WN_INLINE v128_t wasm_f64x2_neg(v128_t a) { return (v128_t)(-(__wn_f64x2)a); }
__WN_INLINE v128_t wasm_f64x2_abs(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f64x2){__wn_f64_abs(v[0]), __wn_f64_abs(v[1])};
}
__WN_INLINE v128_t wasm_f64x2_sqrt(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f64x2){__builtin_sqrt(v[0]), __builtin_sqrt(v[1])};
}
__WN_INLINE v128_t wasm_f64x2_ceil(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f64x2){__wn_f64_ceil(v[0]), __wn_f64_ceil(v[1])};
}
__WN_INLINE v128_t wasm_f64x2_floor(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f64x2){__wn_f64_floor(v[0]), __wn_f64_floor(v[1])};
}
__WN_INLINE v128_t wasm_f64x2_trunc(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f64x2){__wn_f64_trunc(v[0]), __wn_f64_trunc(v[1])};
}
__WN_INLINE v128_t wasm_f64x2_nearest(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f64x2){__wn_f64_nearest(v[0]), __wn_f64_nearest(v[1])};
}
__WN_INLINE v128_t wasm_f64x2_min(v128_t a, v128_t b) {
  __wn_f64x2 va = (__wn_f64x2)a, vb = (__wn_f64x2)b;
  return (v128_t)(__wn_f64x2){__wn_f64_min(va[0], vb[0]), __wn_f64_min(va[1], vb[1])};
}
__WN_INLINE v128_t wasm_f64x2_max(v128_t a, v128_t b) {
  __wn_f64x2 va = (__wn_f64x2)a, vb = (__wn_f64x2)b;
  return (v128_t)(__wn_f64x2){__wn_f64_max(va[0], vb[0]), __wn_f64_max(va[1], vb[1])};
}
__WN_INLINE v128_t wasm_f64x2_pmin(v128_t a, v128_t b) {
  __wn_f64x2 va = (__wn_f64x2)a, vb = (__wn_f64x2)b;
  return (v128_t)(__wn_f64x2){vb[0] < va[0] ? vb[0] : va[0], vb[1] < va[1] ? vb[1] : va[1]};
}
__WN_INLINE v128_t wasm_f64x2_pmax(v128_t a, v128_t b) {
  __wn_f64x2 va = (__wn_f64x2)a, vb = (__wn_f64x2)b;
  return (v128_t)(__wn_f64x2){va[0] < vb[0] ? vb[0] : va[0], va[1] < vb[1] ? vb[1] : va[1]};
}

/* -------------------------------------------------------------------------
 * Conversions, widening, narrowing, shuffles
 * ------------------------------------------------------------------------- */

__WN_INLINE v128_t wasm_i32x4_trunc_sat_f32x4(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a;
  return (v128_t)(__wn_i32x4){
      __wn_trunc_sat_s_i32_f32(v[0]), __wn_trunc_sat_s_i32_f32(v[1]),
      __wn_trunc_sat_s_i32_f32(v[2]), __wn_trunc_sat_s_i32_f32(v[3])};
}
__WN_INLINE v128_t wasm_u32x4_trunc_sat_f32x4(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a;
  return (v128_t)(__wn_u32x4){
      __wn_trunc_sat_u_i32_f32(v[0]), __wn_trunc_sat_u_i32_f32(v[1]),
      __wn_trunc_sat_u_i32_f32(v[2]), __wn_trunc_sat_u_i32_f32(v[3])};
}
__WN_INLINE v128_t wasm_f32x4_convert_i32x4(v128_t a) {
  __wn_i32x4 v = (__wn_i32x4)a;
  return (v128_t)(__wn_f32x4){(float)v[0], (float)v[1], (float)v[2], (float)v[3]};
}
__WN_INLINE v128_t wasm_f32x4_convert_u32x4(v128_t a) {
  __wn_u32x4 v = (__wn_u32x4)a;
  return (v128_t)(__wn_f32x4){(float)v[0], (float)v[1], (float)v[2], (float)v[3]};
}
__WN_INLINE v128_t wasm_f64x2_convert_low_i32x4(v128_t a) {
  __wn_i32x4 v = (__wn_i32x4)a;
  return (v128_t)(__wn_f64x2){(double)v[0], (double)v[1]};
}
__WN_INLINE v128_t wasm_f64x2_convert_low_u32x4(v128_t a) {
  __wn_u32x4 v = (__wn_u32x4)a;
  return (v128_t)(__wn_f64x2){(double)v[0], (double)v[1]};
}
__WN_INLINE v128_t wasm_i32x4_trunc_sat_f64x2_zero(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_i32x4){__wn_trunc_sat_s_i32_f64(v[0]), __wn_trunc_sat_s_i32_f64(v[1]), 0, 0};
}
__WN_INLINE v128_t wasm_u32x4_trunc_sat_f64x2_zero(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_u32x4){__wn_trunc_sat_u_i32_f64(v[0]), __wn_trunc_sat_u_i32_f64(v[1]), 0u, 0u};
}
__WN_INLINE v128_t wasm_f32x4_demote_f64x2_zero(v128_t a) {
  __wn_f64x2 v = (__wn_f64x2)a;
  return (v128_t)(__wn_f32x4){(float)v[0], (float)v[1], 0.0f, 0.0f};
}
__WN_INLINE v128_t wasm_f64x2_promote_low_f32x4(v128_t a) {
  __wn_f32x4 v = (__wn_f32x4)a;
  return (v128_t)(__wn_f64x2){(double)v[0], (double)v[1]};
}

__WN_INLINE v128_t wasm_i8x16_narrow_i16x8(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b;
  __wn_i8x16 r;
  for (int i = 0; i < 8; ++i) {
    int16_t x = va[i], y = vb[i];
    r[i] = (int8_t)(x > 127 ? 127 : (x < -128 ? -128 : x));
    r[i + 8] = (int8_t)(y > 127 ? 127 : (y < -128 ? -128 : y));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u8x16_narrow_i16x8(v128_t a, v128_t b) {
  __wn_i16x8 va = (__wn_i16x8)a, vb = (__wn_i16x8)b;
  __wn_u8x16 r;
  for (int i = 0; i < 8; ++i) {
    int16_t x = va[i], y = vb[i];
    r[i] = (uint8_t)(x > 255 ? 255 : (x < 0 ? 0 : x));
    r[i + 8] = (uint8_t)(y > 255 ? 255 : (y < 0 ? 0 : y));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i16x8_narrow_i32x4(v128_t a, v128_t b) {
  __wn_i32x4 va = (__wn_i32x4)a, vb = (__wn_i32x4)b;
  __wn_i16x8 r;
  for (int i = 0; i < 4; ++i) {
    int32_t x = va[i], y = vb[i];
    r[i] = (int16_t)(x > 32767 ? 32767 : (x < -32768 ? -32768 : x));
    r[i + 4] = (int16_t)(y > 32767 ? 32767 : (y < -32768 ? -32768 : y));
  }
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_narrow_i32x4(v128_t a, v128_t b) {
  __wn_i32x4 va = (__wn_i32x4)a, vb = (__wn_i32x4)b;
  __wn_u16x8 r;
  for (int i = 0; i < 4; ++i) {
    int32_t x = va[i], y = vb[i];
    r[i] = (uint16_t)(x > 65535 ? 65535 : (x < 0 ? 0 : x));
    r[i + 4] = (uint16_t)(y > 65535 ? 65535 : (y < 0 ? 0 : y));
  }
  return (v128_t)r;
}

__WN_INLINE v128_t wasm_i16x8_extend_low_i8x16(v128_t a) {
  __wn_i8x16 v = (__wn_i8x16)a;
  return (v128_t)(__wn_i16x8){v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]};
}
__WN_INLINE v128_t wasm_i16x8_extend_high_i8x16(v128_t a) {
  __wn_i8x16 v = (__wn_i8x16)a;
  return (v128_t)(__wn_i16x8){v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]};
}
__WN_INLINE v128_t wasm_u16x8_extend_low_u8x16(v128_t a) {
  __wn_u8x16 v = (__wn_u8x16)a;
  return (v128_t)(__wn_u16x8){v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]};
}
__WN_INLINE v128_t wasm_u16x8_extend_high_u8x16(v128_t a) {
  __wn_u8x16 v = (__wn_u8x16)a;
  return (v128_t)(__wn_u16x8){v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]};
}
__WN_INLINE v128_t wasm_i32x4_extend_low_i16x8(v128_t a) {
  __wn_i16x8 v = (__wn_i16x8)a;
  return (v128_t)(__wn_i32x4){v[0], v[1], v[2], v[3]};
}
__WN_INLINE v128_t wasm_i32x4_extend_high_i16x8(v128_t a) {
  __wn_i16x8 v = (__wn_i16x8)a;
  return (v128_t)(__wn_i32x4){v[4], v[5], v[6], v[7]};
}
__WN_INLINE v128_t wasm_u32x4_extend_low_u16x8(v128_t a) {
  __wn_u16x8 v = (__wn_u16x8)a;
  return (v128_t)(__wn_u32x4){v[0], v[1], v[2], v[3]};
}
__WN_INLINE v128_t wasm_u32x4_extend_high_u16x8(v128_t a) {
  __wn_u16x8 v = (__wn_u16x8)a;
  return (v128_t)(__wn_u32x4){v[4], v[5], v[6], v[7]};
}
__WN_INLINE v128_t wasm_i64x2_extend_low_i32x4(v128_t a) {
  __wn_i32x4 v = (__wn_i32x4)a;
  return (v128_t)(__wn_i64x2){v[0], v[1]};
}
__WN_INLINE v128_t wasm_i64x2_extend_high_i32x4(v128_t a) {
  __wn_i32x4 v = (__wn_i32x4)a;
  return (v128_t)(__wn_i64x2){v[2], v[3]};
}
__WN_INLINE v128_t wasm_u64x2_extend_low_u32x4(v128_t a) {
  __wn_u32x4 v = (__wn_u32x4)a;
  return (v128_t)(__wn_u64x2){v[0], v[1]};
}
__WN_INLINE v128_t wasm_u64x2_extend_high_u32x4(v128_t a) {
  __wn_u32x4 v = (__wn_u32x4)a;
  return (v128_t)(__wn_u64x2){v[2], v[3]};
}

__WN_INLINE v128_t wasm_i16x8_extadd_pairwise_i8x16(v128_t a) {
  __wn_i8x16 v = (__wn_i8x16)a;
  __wn_i16x8 r;
  for (int i = 0; i < 8; ++i) r[i] = (int16_t)((int16_t)v[2 * i] + (int16_t)v[2 * i + 1]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u16x8_extadd_pairwise_u8x16(v128_t a) {
  __wn_u8x16 v = (__wn_u8x16)a;
  __wn_u16x8 r;
  for (int i = 0; i < 8; ++i) r[i] = (uint16_t)((uint16_t)v[2 * i] + (uint16_t)v[2 * i + 1]);
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_i32x4_extadd_pairwise_i16x8(v128_t a) {
  __wn_i16x8 v = (__wn_i16x8)a;
  __wn_i32x4 r;
  for (int i = 0; i < 4; ++i) r[i] = (int32_t)v[2 * i] + (int32_t)v[2 * i + 1];
  return (v128_t)r;
}
__WN_INLINE v128_t wasm_u32x4_extadd_pairwise_u16x8(v128_t a) {
  __wn_u16x8 v = (__wn_u16x8)a;
  __wn_u32x4 r;
  for (int i = 0; i < 4; ++i) r[i] = (uint32_t)v[2 * i] + (uint32_t)v[2 * i + 1];
  return (v128_t)r;
}

__WN_INLINE v128_t wasm_i16x8_extmul_low_i8x16(v128_t a, v128_t b) {
  return (v128_t)((__wn_i16x8)wasm_i16x8_extend_low_i8x16(a) * (__wn_i16x8)wasm_i16x8_extend_low_i8x16(b));
}
__WN_INLINE v128_t wasm_i16x8_extmul_high_i8x16(v128_t a, v128_t b) {
  return (v128_t)((__wn_i16x8)wasm_i16x8_extend_high_i8x16(a) * (__wn_i16x8)wasm_i16x8_extend_high_i8x16(b));
}
__WN_INLINE v128_t wasm_u16x8_extmul_low_u8x16(v128_t a, v128_t b) {
  return (v128_t)((__wn_u16x8)wasm_u16x8_extend_low_u8x16(a) * (__wn_u16x8)wasm_u16x8_extend_low_u8x16(b));
}
__WN_INLINE v128_t wasm_u16x8_extmul_high_u8x16(v128_t a, v128_t b) {
  return (v128_t)((__wn_u16x8)wasm_u16x8_extend_high_u8x16(a) * (__wn_u16x8)wasm_u16x8_extend_high_u8x16(b));
}
__WN_INLINE v128_t wasm_i32x4_extmul_low_i16x8(v128_t a, v128_t b) {
  return (v128_t)((__wn_i32x4)wasm_i32x4_extend_low_i16x8(a) * (__wn_i32x4)wasm_i32x4_extend_low_i16x8(b));
}
__WN_INLINE v128_t wasm_i32x4_extmul_high_i16x8(v128_t a, v128_t b) {
  return (v128_t)((__wn_i32x4)wasm_i32x4_extend_high_i16x8(a) * (__wn_i32x4)wasm_i32x4_extend_high_i16x8(b));
}
__WN_INLINE v128_t wasm_u32x4_extmul_low_u16x8(v128_t a, v128_t b) {
  return (v128_t)((__wn_u32x4)wasm_u32x4_extend_low_u16x8(a) * (__wn_u32x4)wasm_u32x4_extend_low_u16x8(b));
}
__WN_INLINE v128_t wasm_u32x4_extmul_high_u16x8(v128_t a, v128_t b) {
  return (v128_t)((__wn_u32x4)wasm_u32x4_extend_high_u16x8(a) * (__wn_u32x4)wasm_u32x4_extend_high_u16x8(b));
}
__WN_INLINE v128_t wasm_i64x2_extmul_low_i32x4(v128_t a, v128_t b) {
  return (v128_t)((__wn_i64x2)wasm_i64x2_extend_low_i32x4(a) * (__wn_i64x2)wasm_i64x2_extend_low_i32x4(b));
}
__WN_INLINE v128_t wasm_i64x2_extmul_high_i32x4(v128_t a, v128_t b) {
  return (v128_t)((__wn_i64x2)wasm_i64x2_extend_high_i32x4(a) * (__wn_i64x2)wasm_i64x2_extend_high_i32x4(b));
}
__WN_INLINE v128_t wasm_u64x2_extmul_low_u32x4(v128_t a, v128_t b) {
  return (v128_t)((__wn_u64x2)wasm_u64x2_extend_low_u32x4(a) * (__wn_u64x2)wasm_u64x2_extend_low_u32x4(b));
}
__WN_INLINE v128_t wasm_u64x2_extmul_high_u32x4(v128_t a, v128_t b) {
  return (v128_t)((__wn_u64x2)wasm_u64x2_extend_high_u32x4(a) * (__wn_u64x2)wasm_u64x2_extend_high_u32x4(b));
}

#define wasm_i8x16_shuffle(__a, __b, __c0, __c1, __c2, __c3, __c4, __c5, __c6, \
                           __c7, __c8, __c9, __c10, __c11, __c12, __c13,       \
                           __c14, __c15)                                       \
  ((v128_t)__builtin_shufflevector(                                            \
      (__wn_i8x16)(__a), (__wn_i8x16)(__b), __c0, __c1, __c2, __c3, __c4,      \
      __c5, __c6, __c7, __c8, __c9, __c10, __c11, __c12, __c13, __c14, __c15))

#define wasm_i16x8_shuffle(__a, __b, __c0, __c1, __c2, __c3, __c4, __c5, __c6, \
                           __c7)                                               \
  ((v128_t)__builtin_shufflevector((__wn_i16x8)(__a), (__wn_i16x8)(__b), __c0, \
                                   __c1, __c2, __c3, __c4, __c5, __c6, __c7))

#define wasm_i32x4_shuffle(__a, __b, __c0, __c1, __c2, __c3)                   \
  ((v128_t)__builtin_shufflevector((__wn_i32x4)(__a), (__wn_i32x4)(__b), __c0, \
                                   __c1, __c2, __c3))

#define wasm_i64x2_shuffle(__a, __b, __c0, __c1)                               \
  ((v128_t)__builtin_shufflevector((__wn_i64x2)(__a), (__wn_i64x2)(__b), __c0, \
                                   __c1))

#undef __WN_INLINE

#endif /* __wasm__ */

/* Convenience aliases available identically on both wasm32 and native builds. */
#ifndef wasm_v128_const
static __inline__ __attribute__((__always_inline__)) v128_t
wasm_v128_const(int32_t __c0, int32_t __c1, int32_t __c2, int32_t __c3) {
  return wasm_i32x4_make(__c0, __c1, __c2, __c3);
}
#endif

#ifndef wasm_i16x8_and
static __inline__ __attribute__((__always_inline__)) v128_t
wasm_i16x8_and(v128_t __a, v128_t __b) {
  return wasm_v128_and(__a, __b);
}
#endif

#endif /* EAGLER_WASM_SIMD128_NATIVE_H */
