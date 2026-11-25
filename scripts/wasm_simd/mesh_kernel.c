/*
 * minecraft-web Hybrid Chunk Mesher — WASM-SIMD128 C Kernel
 *
 * Bit-for-bit parity implementation of target_teavm_263/wasm-gc/minecraft-web-mesh-worker.js
 * compiled either as a standalone freestanding wasm32-simd128 module (~20 KiB binary,
 * ~12.5 MiB linear-memory arena initialized at ARENA_BASE = 0x40000 with auto memory.grow)
 * or as a native x86_64 shared library via wasm_simd128_native.h for parity verification.
 *
 * Exported ABI:
 *   int32_t  wasm_init(void)
 *   void     wasm_reset(int32_t epoch)
 *   mk_ptr_t wasm_job_in_ptr(void)
 *   int32_t  wasm_job_in_cap(void)
 *   mk_ptr_t wasm_out_ptr(void)
 *   int32_t  wasm_out_cap(void)
 *   int32_t  wasm_read_defs(mk_ptr_t ptr, int32_t len)
 *   int32_t  wasm_read_tags(mk_ptr_t ptr, int32_t len)
 *   int32_t  wasm_run_job(mk_ptr_t ptr, int32_t len)
 */

#pragma STDC FP_CONTRACT OFF

#include <stdint.h>
#include <stddef.h>
#include "wasm_simd128_native.h"

#ifdef __wasm__
#define MK_EXPORT(name) __attribute__((export_name(name), visibility("default")))
typedef int32_t mk_ptr_t;
#define MK_PTR_TO_BYTES(p) ((const uint8_t *)(uintptr_t)(uint32_t)(p))
#define MK_BYTES_TO_PTR(p) ((int32_t)(uintptr_t)(p))
#else
#define MK_EXPORT(name) __attribute__((visibility("default")))
typedef uintptr_t mk_ptr_t;
#define MK_PTR_TO_BYTES(p) ((const uint8_t *)(p))
#define MK_BYTES_TO_PTR(p) ((uintptr_t)(p))
#endif

/* ---- Arena Limits (~12.2 MiB linear memory, zero .wasm binary .bss bloat) ------------------- */

#define MAX_STATES         65536
#define MAX_SHAPES         4096
#define MAX_PARTS          16384
#define MAX_MODELS         24576
#define MAX_MODEL_CHILDREN 49152
#define MAX_FLUID_MODELS   256
#define MAX_QUADS          32768
#define MAX_SPRITES        2048
#define MAX_PARTS_STACK    256

#define IN_BUF_CAP         (544 * 1024)       /* 544 KiB: fits 65536-state TAGS + 248 KiB JOB */
#define OUT_BUF_WORDS      (1024 * 1024)      /* 4.0 MiB packed output + SOLID staging (37k quads) */
#define CUTOUT_BUF_WORDS   (512 * 1024)       /* 2.0 MiB CUTOUT arena (18k quads) */
#define TRANSL_BUF_WORDS   (512 * 1024)       /* 2.0 MiB TRANSLUCENT arena (18k quads) */
#define SOLID_PREFIX_WORDS (16 + MAX_SPRITES) /* 64B header + up to 2048 sprite ids before SOLID */

/* Placed safely above wasm_link_single.py's stack (64 KiB) + small .rodata/.data (~1 KiB) */
#define ARENA_BASE         0x40000u           /* 256 KiB */

/* ---- Protocol & Block Flags (must match minecraft-web-mesh-worker.js / Web263MeshOffload.java) -- */

#define F_AIR               (1 << 0)
#define F_SOLID_RENDER      (1 << 1)
#define F_MODEL             (1 << 2)
#define F_EMISSIVE          (1 << 3)
#define F_FORCE_OPAQUE      (1 << 4)
#define F_LIGHT_PERMEABLE   (1 << 5)
#define F_COLLISION_FULL    (1 << 6)
#define F_HAS_OFFSET        (1 << 7)
#define F_CUSTOM_SEED       (1 << 8)
#define F_LEAVES            (1 << 9)
#define F_SOLID             (1 << 10)
#define F_BLOCKS_FLUID_FLOW (1 << 11)
#define F_ICE               (1 << 12)
#define F_OVERLAY_NEIGHBOUR (1 << 13)
#define F_FLUID_SOURCE      (1 << 15)
#define F_FLUID_FALLING     (1 << 16)
#define F_FLUID_TINT        (1 << 17)
#define F_DYNAMIC_SHAPE     (1 << 18)

#define EX_OFFSET           1
#define EX_SEED             2
#define FULL_BRIGHT         15728880 /* 0x00F000F0 */

#define QFLAG_PARTIAL       1
#define QFLAG_PLANAR        2
#define QFLAG_BOUNDARY      4

/* Decline return codes (< 0) from wasm_run_job */
#define DECLINE_UNKNOWN_STATE  (-1)
#define DECLINE_UNSUPPORTED    (-2)
#define DECLINE_EPOCH_OR_HALO  (-3)
#define DECLINE_MISSING_DATA   (-4)
#define DECLINE_OVERFLOW       (-5)

static const float E4               = 1.0e-4f;
static const float N4               = 0.9999f;
static const float MAX_FLUID_HEIGHT = 0.8888889f;
static const float OFFS             = 0.001f;
static const float TINY             = 1.0e-5f;

static const int32_t STEP_X[6]   = { 0,  0,  0, 0, -1, 1 };
static const int32_t STEP_Y[6]   = {-1,  1,  0, 0,  0, 0 };
static const int32_t STEP_Z[6]   = { 0,  0, -1, 1,  0, 0 };
static const uint8_t OPPOSITE[6] = { 1, 0, 3, 2, 5, 4 };
static const uint8_t HORIZ[4]    = { 2, 5, 3, 4 }; /* NORTH EAST SOUTH WEST */

/* ---- SoA Linear-Memory Arena (zero bytes in .wasm binary!) ---------------------------------- */

typedef struct {
    uint8_t  dir;
    uint8_t  layer;
    int8_t   tint;
    int8_t   shade;
    uint8_t  emission;
    uint8_t  flags;
    uint16_t sprite;
} QuadMeta; /* 8 bytes */

typedef struct {
    float    u0, u1, v0, v1;
    uint16_t id;
    uint16_t _pad;
} FluidSprite;

typedef struct {
    uint8_t     valid;
    uint8_t     layer;
    uint8_t     overlay;
    uint8_t     tint;
    FluidSprite sprites[3];
} FluidModel;

typedef struct {
    /* 16-byte aligned hot buffers first */
    uint8_t    in_buf[IN_BUF_CAP]             __attribute__((aligned(16)));
    int32_t    out_buf[OUT_BUF_WORDS]         __attribute__((aligned(16)));
    int32_t    cutout_buf[CUTOUT_BUF_WORDS]   __attribute__((aligned(16)));
    int32_t    transl_buf[TRANSL_BUF_WORDS]   __attribute__((aligned(16)));
    uint16_t   shape_rows[MAX_SHAPES][16]     __attribute__((aligned(16)));
    float      qf[MAX_QUADS * 20]             __attribute__((aligned(16)));
    uint8_t    vis_bits[4096]                 __attribute__((aligned(16)));
    int32_t    q_color[4]                     __attribute__((aligned(16)));
    int32_t    q_light[4]                     __attribute__((aligned(16)));

    /* Block state SoA tables */
    uint8_t    s_known[MAX_STATES];
    int32_t    s_flags[MAX_STATES];
    uint8_t    s_emission[MAX_STATES];
    float      s_shade[MAX_STATES];
    uint8_t    s_skip_rule[MAX_STATES];
    uint16_t   s_skip_key[MAX_STATES];
    uint16_t   s_block[MAX_STATES];
    uint16_t   s_occ[MAX_STATES * 6];
    uint8_t    s_tints[MAX_STATES];
    uint16_t   s_model[MAX_STATES];
    uint8_t    s_fluid_group[MAX_STATES];
    uint16_t   s_fluid_model[MAX_STATES];
    float      s_own_height[MAX_STATES];
    uint8_t    s_sturdy[MAX_STATES];
    uint8_t    s_extra[MAX_STATES];

    /* Shader pack tags (kind = 4) */
    int32_t    tag_block[MAX_STATES];
    int32_t    tag_fluid[MAX_STATES];

    /* Shapes, parts, quads, models, fluid models */
    uint8_t    shape_valid[MAX_SHAPES];
    float      qb[MAX_QUADS * 6];
    QuadMeta   qm[MAX_QUADS];
    uint8_t    part_ao[MAX_PARTS];
    int32_t    part_start[MAX_PARTS * 7];
    uint16_t   part_count[MAX_PARTS * 7];
    int8_t     model_kind[MAX_MODELS];
    int32_t    model_a[MAX_MODELS];
    int32_t    model_off[MAX_MODELS];
    uint16_t   model_cnt[MAX_MODELS];
    uint16_t   model_child_id[MAX_MODEL_CHILDREN];
    int32_t    model_child_w[MAX_MODEL_CHILDREN];
    FluidModel fluid_models[MAX_FLUID_MODELS];

    /* Trig & VisGraph tables */
    float      sin_tab[65536];
    double     asin_tab[257];
    double     cos_tab[257];
    int32_t    vis_edges[1352];
    int32_t    vis_queue[4096];

    /* Per-job scratch */
    int32_t    cell_extra[4096];
    int32_t    extra_used[4096];
    uint32_t   sprite_bits[MAX_SPRITES / 32];
    uint16_t   sprite_list[MAX_SPRITES];
    int32_t    parts[MAX_PARTS_STACK];
} MeshArena;

#ifdef __wasm__
#define A ((MeshArena *)(uintptr_t)ARENA_BASE)
#else
static MeshArena g_native_arena;
#define A (&g_native_arena)
#endif

/* Tiny scalar state (~128 bytes) */
static int32_t  g_inited = 0;
static int32_t  g_epoch  = 0;
static uint32_t g_quad_count = 0;
static uint32_t g_model_pool_n = 0;
static int32_t  g_tag_on = 0;
static int32_t  g_tag_len = 0;
static double   g_frac_bias = 0.0;

static int32_t  g_H = 18, g_HH = 324, g_PAD = 1;
static int32_t  g_dir_step[6];
static const uint16_t *g_states = NULL;
static const uint8_t  *g_light  = NULL;
static const int32_t  *g_ex_i   = NULL;
static int32_t  g_ex_len = 0;
static int32_t  g_extra_used_n = 0;
static int32_t  g_ao = 0;
static float    g_cardinal[6];
static int32_t  g_gray_by_dir[6];
static int32_t  g_cur_tag = 0;
static int32_t  g_cur_fluid_tag = 0;
static int32_t  g_tint_offset_words = 0;
static int32_t  g_tint_count = 0;
static int32_t  g_declined = 0;
static int32_t  g_out_n[3];
static int32_t  g_sprite_count = 0;
static int32_t  g_part_n = 0;
static uint64_t g_rng_seed = 0;
static double   g_flow_x = 0.0, g_flow_z = 0.0;

/* ---- Freestanding Memory & Bit-Cast Utilities (zero libc calls) ----------------------------- */

static inline uint32_t f32_to_u32(float f) {
    union { float f; uint32_t u; } u;
    u.f = f;
    return u.u;
}

static inline float u32_to_f32(uint32_t x) {
    union { float f; uint32_t u; } u;
    u.u = x;
    return u.f;
}

static inline uint16_t rd_u16(const uint8_t *p) {
    return (uint16_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8));
}

static inline int32_t rd_i32(const uint8_t *p) {
    return (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                     ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}

static inline float rd_f32(const uint8_t *p) {
    return u32_to_f32((uint32_t)rd_i32(p));
}

static void mk_zero_bytes(uint8_t *dst, uint32_t len) {
    uint32_t i = 0;
    v128_t z = wasm_i32x4_const_splat(0);
    for (; i + 16 <= len; i += 16) {
        wasm_v128_store(dst + i, z);
    }
    for (; i < len; i++) {
        dst[i] = 0;
        __asm__ volatile("" ::: "memory");
    }
}

static void mk_fill_i8(int8_t *dst, int8_t val, uint32_t len) {
    uint32_t i = 0;
    v128_t v = wasm_i8x16_splat(val);
    for (; i + 16 <= len; i += 16) {
        wasm_v128_store(dst + i, v);
    }
    for (; i < len; i++) {
        dst[i] = val;
        __asm__ volatile("" ::: "memory");
    }
}

static void mk_copy_i32_fwd(int32_t *dst, const int32_t *src, uint32_t count) {
    uint32_t i = 0;
    for (; i + 4 <= count; i += 4) {
        v128_t v = wasm_v128_load(src + i);
        wasm_v128_store(dst + i, v);
    }
    for (; i < count; i++) {
        dst[i] = src[i];
        __asm__ volatile("" ::: "memory");
    }
}

/* ---- 106-bit Double-Double Trig Initialization (zero libm imports) -------------------------- */

typedef struct { double hi, lo; } mk_dd;

static inline mk_dd dd_quick_two_sum(double a, double b) {
    double s = a + b;
    double e = b - (s - a);
    mk_dd r; r.hi = s; r.lo = e;
    return r;
}

static inline mk_dd dd_two_sum(double a, double b) {
    double s = a + b;
    double v = s - a;
    double e = (a - (s - v)) + (b - v);
    mk_dd r; r.hi = s; r.lo = e;
    return r;
}

static inline mk_dd dd_two_prod(double a, double b) {
    double p = a * b;
    const double S = 134217729.0; /* 2^27 + 1 */
    double ca = S * a, ah = ca - (ca - a), al = a - ah;
    double cb = S * b, bh = cb - (cb - b), bl = b - bh;
    double e = ((ah * bh - p) + ah * bl + al * bh) + al * bl;
    mk_dd r; r.hi = p; r.lo = e;
    return r;
}

static inline mk_dd dd_add(mk_dd a, mk_dd b) {
    mk_dd s = dd_two_sum(a.hi, b.hi);
    mk_dd t = dd_two_sum(a.lo, b.lo);
    s.lo += t.hi;
    s = dd_quick_two_sum(s.hi, s.lo);
    s.lo += t.lo;
    return dd_quick_two_sum(s.hi, s.lo);
}

static inline mk_dd dd_sub(mk_dd a, mk_dd b) {
    mk_dd nb; nb.hi = -b.hi; nb.lo = -b.lo;
    return dd_add(a, nb);
}

static inline mk_dd dd_mul(mk_dd a, mk_dd b) {
    mk_dd p = dd_two_prod(a.hi, b.hi);
    p.lo += a.hi * b.lo + a.lo * b.hi;
    return dd_quick_two_sum(p.hi, p.lo);
}

static inline mk_dd dd_div_d(mk_dd a, double b) {
    double q1 = a.hi / b;
    mk_dd p = dd_two_prod(q1, b);
    mk_dd r = dd_sub(a, p);
    double q2 = r.hi / b;
    return dd_quick_two_sum(q1, q2);
}

static inline mk_dd dd_sqrt(mk_dd a) {
    if (a.hi <= 0.0) { mk_dd z; z.hi = 0.0; z.lo = 0.0; return z; }
    double x = __builtin_sqrt(a.hi);
    mk_dd ax = dd_two_prod(x, x);
    mk_dd r = dd_sub(a, ax);
    double dx = r.hi / (2.0 * x);
    mk_dd res = dd_quick_two_sum(x, dx);
    mk_dd r2 = dd_sub(a, dd_mul(res, res));
    mk_dd dx2 = dd_div_d(r2, 2.0 * res.hi);
    return dd_add(res, dx2);
}

static void dd_sincos(double x, double *out_s, double *out_c) {
    static const mk_dd PI2 = { 1.57079632679489655800e+00, 6.12323399573676603587e-17 };
    static const double INV_PI2 = 0.63661977236758134307553505349006;
    int64_t q = (int64_t)(x * INV_PI2 + (x >= 0.0 ? 0.5 : -0.5));
    mk_dd xd; xd.hi = x; xd.lo = 0.0;
    mk_dd qd; qd.hi = (double)q; qd.lo = 0.0;
    mk_dd r = dd_sub(xd, dd_mul(qd, PI2));
    mk_dd r2 = dd_mul(r, r);
    mk_dd s_sum = r, s_term = r;
    mk_dd c_sum; c_sum.hi = 1.0; c_sum.lo = 0.0;
    mk_dd c_term = c_sum;
    for (int k = 1; k <= 12; k++) {
        s_term = dd_div_d(dd_mul(s_term, r2), -(double)((2 * k) * (2 * k + 1)));
        s_sum = dd_add(s_sum, s_term);
        c_term = dd_div_d(dd_mul(c_term, r2), -(double)((2 * k - 1) * (2 * k)));
        c_sum = dd_add(c_sum, c_term);
    }
    double s = s_sum.hi + s_sum.lo;
    double c = c_sum.hi + c_sum.lo;
    switch ((int)(q & 3)) {
        case 0: *out_s =  s; *out_c =  c; break;
        case 1: *out_s =  c; *out_c = -s; break;
        case 2: *out_s = -s; *out_c = -c; break;
        default: *out_s = -c; *out_c = s; break;
    }
}

static mk_dd dd_asin_half(mk_dd x) {
    mk_dd x2 = dd_mul(x, x);
    mk_dd term = x, sum = x;
    for (int k = 1; k <= 40; k++) {
        mk_dd num; num.hi = (double)(2 * k - 1); num.lo = 0.0;
        term = dd_div_d(dd_mul(dd_mul(term, x2), num), (double)(2 * k));
        sum = dd_add(sum, dd_div_d(term, (double)(2 * k + 1)));
    }
    return sum;
}

static double dd_asin(double x) {
    static const mk_dd PI2 = { 1.57079632679489655800e+00, 6.12323399573676603587e-17 };
    mk_dd xd; xd.hi = x; xd.lo = 0.0;
    if (x <= 0.5) {
        mk_dd r = dd_asin_half(xd);
        return r.hi + r.lo;
    }
    mk_dd half_1mx; half_1mx.hi = 0.5 * (1.0 - x); half_1mx.lo = 0.0;
    mk_dd z = dd_sqrt(half_1mx);
    mk_dd a = dd_asin_half(z);
    mk_dd two_a; two_a.hi = a.hi * 2.0; two_a.lo = a.lo * 2.0;
    mk_dd r = dd_sub(PI2, two_a);
    return r.hi + r.lo;
}

/* ---- Native i64 Mth.getSeed & 48-bit LCG RNG ------------------------------------------------ */

static inline uint64_t mth_seed(int32_t x, int32_t y, int32_t z) {
    int32_t ax = (int32_t)((uint32_t)x * 3129871u);
    int64_t i = (int64_t)ax ^ ((int64_t)z * 116129781LL) ^ (int64_t)y;
    uint64_t u = (uint64_t)i;
    uint64_t r = u * u * 42317861ULL + u * 11ULL;
    return (uint64_t)((int64_t)r >> 16);
}

static inline void rng_set_seed(uint64_t seed) {
    g_rng_seed = (seed ^ 0x5DEECE66DULL) & ((1ULL << 48) - 1ULL);
}

static inline int32_t rng_next(int bits) {
    g_rng_seed = (g_rng_seed * 0x5DEECE66DULL + 11ULL) & ((1ULL << 48) - 1ULL);
    return (int32_t)(g_rng_seed >> (48 - bits));
}

static inline int32_t rng_next_int(int32_t bound) {
    if (bound <= 0) {
        g_declined = DECLINE_MISSING_DATA;
        return 0;
    }
    if ((bound & (bound - 1)) == 0) {
        return (int32_t)((uint32_t)rng_next(31) >> __builtin_clz((uint32_t)bound));
    }
    int32_t sample, modulo;
    do {
        sample = rng_next(31);
        modulo = sample % bound;
    } while ((int32_t)((uint32_t)sample - (uint32_t)modulo + (uint32_t)(bound - 1)) < 0);
    return modulo;
}

/* ---- Color, Light & Sprite Helpers ---------------------------------------------------------- */

static inline int32_t make_gray(float brightness) {
    int32_t c = ((int32_t)__builtin_floorf(brightness * 255.0f)) & 255;
    return (int32_t)(0xFF000000u | ((uint32_t)c << 16) | ((uint32_t)c << 8) | (uint32_t)c);
}

static inline int32_t multiply_color(int32_t lhs, int32_t rhs) {
    if (lhs == -1) return rhs;
    if (rhs == -1) return lhs;
    uint32_t l = (uint32_t)lhs, r = (uint32_t)rhs;
    uint32_t a = (((l >> 24) * (r >> 24)) / 255u) & 255u;
    uint32_t R = ((((l >> 16) & 255u) * ((r >> 16) & 255u)) / 255u) & 255u;
    uint32_t G = ((((l >> 8)  & 255u) * ((r >> 8)  & 255u)) / 255u) & 255u;
    uint32_t B = (((l & 255u) * (r & 255u)) / 255u) & 255u;
    return (int32_t)((a << 24) | (R << 16) | (G << 8) | B);
}

static inline int32_t clamp255(int32_t v) {
    return v < 0 ? 0 : (v > 255 ? 255 : v);
}

static inline int32_t scale_rgb(int32_t color, float scale) {
    int32_t r = clamp255((int32_t)((float)((color >> 16) & 255) * scale));
    int32_t g = clamp255((int32_t)((float)((color >> 8)  & 255) * scale));
    int32_t b = clamp255((int32_t)((float)(color & 255) * scale));
    return (int32_t)(((uint32_t)color & 0xFF000000u) | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b);
}

static inline int32_t argb_to_abgr(int32_t c) {
    uint32_t u = (uint32_t)c;
    return (int32_t)((u & 0xFF00FF00u) | ((u & 0x00FF0000u) >> 16) | ((u & 0x000000FFu) << 16));
}

static inline int32_t light_at(int32_t cell) {
    uint32_t l = g_light[cell];
    return (int32_t)(((l & 15u) << 4) | ((l >> 4) << 20));
}

static inline int32_t light_coords(uint16_t sid, int32_t cell) {
    if (A->s_flags[sid] & F_EMISSIVE) return FULL_BRIGHT;
    int32_t packed = light_at(cell);
    int32_t e = (int32_t)A->s_emission[sid];
    return ((packed >> 4) & 15) < e ? ((packed & 0x00FF0000) | (e << 4)) : packed;
}

static inline int32_t with_emission(int32_t lc, int32_t emission) {
    if (emission == 0) return lc;
    int32_t sky = (lc >> 20) & 15;
    int32_t blk = (lc >> 4) & 15;
    if (emission > sky) sky = emission;
    if (emission > blk) blk = emission;
    return (blk << 4) | (sky << 20);
}

static inline void add_sprite(uint16_t id) {
    if (id == 0) return;
    if (id < MAX_SPRITES) {
        uint32_t w = (uint32_t)id >> 5;
        uint32_t mask = 1u << ((uint32_t)id & 31u);
        if ((A->sprite_bits[w] & mask) == 0u) {
            A->sprite_bits[w] |= mask;
            A->sprite_list[g_sprite_count++] = id;
        }
    } else {
        for (int32_t i = 0; i < g_sprite_count; i++) {
            if (A->sprite_list[i] == id) return;
        }
        if (g_sprite_count < MAX_SPRITES) {
            A->sprite_list[g_sprite_count++] = id;
        }
    }
}

static inline int32_t *layer_reserve(int32_t layer, int32_t words) {
    int32_t cur = g_out_n[layer];
    int32_t next = cur + words;
    if (layer == 0) {
        if (SOLID_PREFIX_WORDS + next > OUT_BUF_WORDS) {
            g_declined = DECLINE_OVERFLOW;
            return NULL;
        }
        g_out_n[0] = next;
        return A->out_buf + SOLID_PREFIX_WORDS + cur;
    } else if (layer == 1) {
        if (next > CUTOUT_BUF_WORDS) {
            g_declined = DECLINE_OVERFLOW;
            return NULL;
        }
        g_out_n[1] = next;
        return A->cutout_buf + cur;
    } else if (layer == 2) {
        if (next > TRANSL_BUF_WORDS) {
            g_declined = DECLINE_OVERFLOW;
            return NULL;
        }
        g_out_n[2] = next;
        return A->transl_buf + cur;
    } else {
        g_declined = DECLINE_UNSUPPORTED;
        return NULL;
    }
}

/* ---- Block Face Culling (SIMD128 32-byte Shape Occlusion) ----------------------------------- */

static inline int should_render_face(uint16_t sid, uint16_t nsid, int32_t dir) {
    uint16_t occ = A->s_occ[(uint32_t)nsid * 6u + OPPOSITE[dir]];
    if (occ == 1) return 0;

    uint8_t rule = A->s_skip_rule[sid];
    if (rule != 0) {
        if (rule == 1) {
            if (A->s_block[nsid] == A->s_skip_key[sid]) return 0;
        } else if (rule == 2) {
            if (A->s_flags[nsid] & F_LEAVES) return 0;
        } else if (rule == 3) {
            if (A->s_fluid_group[nsid] != 0 && A->s_fluid_group[nsid] == A->s_fluid_group[sid]) return 0;
        } else if (rule == 4) {
            if (dir < 2 && A->s_block[nsid] == A->s_skip_key[sid]) return 0;
        } else if (rule == 5) {
            if (A->s_block[nsid] == A->s_block[sid] && (dir >= 2 || A->s_skip_key[nsid] == A->s_skip_key[sid])) return 0;
        } else {
            g_declined = DECLINE_UNSUPPORTED;
            return 0;
        }
    }

    if (occ == 0) return 1;
    uint16_t sh = A->s_occ[(uint32_t)sid * 6u + (uint32_t)dir];
    if (sh == 0) return 1;
    if (sh >= MAX_SHAPES || occ >= MAX_SHAPES || !A->shape_valid[sh] || !A->shape_valid[occ]) {
        g_declined = DECLINE_MISSING_DATA;
        return 1;
    }

    /* 32-byte shape occlusion test in 5 branchless SIMD instructions: any_true((a0 & ~b0) | (a1 & ~b1)) */
    const uint16_t *a = A->shape_rows[sh];
    const uint16_t *b = A->shape_rows[occ];
    v128_t a0 = wasm_v128_load(a);
    v128_t b0 = wasm_v128_load(b);
    v128_t a1 = wasm_v128_load(a + 8);
    v128_t b1 = wasm_v128_load(b + 8);
    return wasm_v128_any_true(wasm_v128_or(wasm_v128_andnot(a0, b0), wasm_v128_andnot(a1, b1))) ? 1 : 0;
}

/* ---- Model Tree Part Collection ------------------------------------------------------------- */

static void collect_parts(uint16_t model) {
    if (model >= MAX_MODELS) {
        g_declined = DECLINE_UNSUPPORTED;
        return;
    }
    int8_t kind = A->model_kind[model];
    if (kind == 0) {
        if (g_part_n >= MAX_PARTS_STACK) {
            g_declined = DECLINE_OVERFLOW;
            return;
        }
        A->parts[g_part_n++] = A->model_a[model];
    } else if (kind == 1) {
        int32_t total = A->model_a[model];
        int32_t off   = A->model_off[model];
        uint32_t cnt  = A->model_cnt[model];
        int32_t sel   = rng_next_int(total);
        if (g_declined) return;
        uint32_t i = 0;
        for (; i < cnt; i++) {
            sel -= A->model_child_w[off + (int32_t)i];
            if (sel < 0) break;
        }
        if (i == cnt) {
            g_declined = DECLINE_MISSING_DATA;
            return;
        }
        collect_parts(A->model_child_id[off + (int32_t)i]);
    } else if (kind == 2) {
        int32_t off  = A->model_off[model];
        uint32_t cnt = A->model_cnt[model];
        int32_t upper = rng_next(32);
        int32_t lower = rng_next(32);
        /* Java RandomSource.nextLong(): ((long)upper << 32) + (long)lower */
        uint64_t sub_seed = (uint64_t)(((int64_t)upper << 32) + (int64_t)lower);
        for (uint32_t k = 0; k < cnt; k++) {
            rng_set_seed(sub_seed);
            collect_parts(A->model_child_id[off + (int32_t)k]);
            if (g_declined) return;
        }
    } else {
        g_declined = DECLINE_UNSUPPORTED;
    }
}

/* ---- Quad Output (flat-lit fast path + SIMD ABGR swizzle + shader tags) --------------------- */

static void put_quad(int32_t q, float fx, float fy, float fz, int32_t force_opaque, int32_t tint_base) {
    const QuadMeta *qm = &A->qm[q];
    const float *qf = A->qf + (uint32_t)q * 20u;
    int32_t tint_index = (int32_t)qm->tint;
    int32_t c0 = A->q_color[0], c1 = A->q_color[1], c2 = A->q_color[2], c3 = A->q_color[3];
    int32_t l0 = A->q_light[0], l1 = A->q_light[1], l2 = A->q_light[2], l3 = A->q_light[3];
    int flat = (c0 == c1 && c0 == c2 && c0 == c3 && l0 == l1 && l0 == l2 && l0 == l3);

    if (tint_index != -1) {
        int32_t tint = -1;
        if (tint_index < g_tint_count) {
            if (tint_base < 0) {
                g_declined = DECLINE_MISSING_DATA;
                return;
            }
            int32_t idx = tint_base + 1 + g_tint_offset_words + tint_index;
            if (idx < 0 || idx >= g_ex_len) {
                g_declined = DECLINE_MISSING_DATA;
                return;
            }
            tint = g_ex_i[idx];
        }
        if (flat) {
            c0 = multiply_color(c0, tint);
        } else {
            c0 = multiply_color(c0, tint);
            c1 = multiply_color(c1, tint);
            c2 = multiply_color(c2, tint);
            c3 = multiply_color(c3, tint);
        }
    }

    int32_t layer = force_opaque ? 0 : (int32_t)qm->layer;
    if (qm->sprite != 0) add_sprite(qm->sprite);
    int32_t emission = (int32_t)qm->emission;
    int32_t tag = g_tag_on ? (g_cur_tag | (((int32_t)qm->dir + 1) << 8)) : 0;

    int32_t *out = layer_reserve(layer, 28);
    if (!out) return;

    if (flat) {
        int32_t abgr = argb_to_abgr(c0);
        int32_t packed_light = with_emission(l0, emission) | tag;
        out[0]  = (int32_t)f32_to_u32(qf[0]  + fx); out[1]  = (int32_t)f32_to_u32(qf[1]  + fy); out[2]  = (int32_t)f32_to_u32(qf[2]  + fz);
        out[3]  = abgr; out[4]  = (int32_t)f32_to_u32(qf[12]); out[5]  = (int32_t)f32_to_u32(qf[13]); out[6]  = packed_light;
        out[7]  = (int32_t)f32_to_u32(qf[3]  + fx); out[8]  = (int32_t)f32_to_u32(qf[4]  + fy); out[9]  = (int32_t)f32_to_u32(qf[5]  + fz);
        out[10] = abgr; out[11] = (int32_t)f32_to_u32(qf[14]); out[12] = (int32_t)f32_to_u32(qf[15]); out[13] = packed_light;
        out[14] = (int32_t)f32_to_u32(qf[6]  + fx); out[15] = (int32_t)f32_to_u32(qf[7]  + fy); out[16] = (int32_t)f32_to_u32(qf[8]  + fz);
        out[17] = abgr; out[18] = (int32_t)f32_to_u32(qf[16]); out[19] = (int32_t)f32_to_u32(qf[17]); out[20] = packed_light;
        out[21] = (int32_t)f32_to_u32(qf[9]  + fx); out[22] = (int32_t)f32_to_u32(qf[10] + fy); out[23] = (int32_t)f32_to_u32(qf[11] + fz);
        out[24] = abgr; out[25] = (int32_t)f32_to_u32(qf[18]); out[26] = (int32_t)f32_to_u32(qf[19]); out[27] = packed_light;
        return;
    }

    /* ARGB -> ABGR across all 4 vertices in a single i8x16.swizzle */
    int32_t abgrs[4] __attribute__((aligned(16)));
    v128_t vc = wasm_i32x4_make(c0, c1, c2, c3);
    v128_t swiz = wasm_i8x16_const(2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15);
    wasm_v128_store(abgrs, wasm_i8x16_swizzle(vc, swiz));

    int32_t ls[4] = { l0, l1, l2, l3 };
    for (int v = 0; v < 4; v++) {
        int32_t o = v * 7;
        out[o + 0] = (int32_t)f32_to_u32(qf[v * 3 + 0] + fx);
        out[o + 1] = (int32_t)f32_to_u32(qf[v * 3 + 1] + fy);
        out[o + 2] = (int32_t)f32_to_u32(qf[v * 3 + 2] + fz);
        out[o + 3] = abgrs[v];
        out[o + 4] = (int32_t)f32_to_u32(qf[12 + v * 2 + 0]);
        out[o + 5] = (int32_t)f32_to_u32(qf[12 + v * 2 + 1]);
        out[o + 6] = with_emission(ls[v], emission) | tag;
    }
}

/* ---- Smooth Lighting / Ambient Occlusion (Unified f32x4 + i32x4 SIMD128) -------------------- */

static const uint8_t AO_CORNERS[6][4] = {
    {4, 5, 2, 3}, {5, 4, 2, 3}, {1, 0, 5, 4},
    {4, 5, 0, 1}, {1, 0, 2, 3}, {0, 1, 2, 3}
};

enum {
    SZ_D = 0, SZ_U = 1, SZ_N = 2, SZ_S = 3, SZ_W = 4, SZ_E = 5,
    SZ_FD = 6, SZ_FU = 7, SZ_FN = 8, SZ_FS = 9, SZ_FW = 10, SZ_FE = 11
};

static const uint8_t AO_WEIGHTS[6][4][8] = {
    {
        {SZ_FW, SZ_S, SZ_FW, SZ_FS, SZ_W, SZ_FS, SZ_W, SZ_S},
        {SZ_FW, SZ_N, SZ_FW, SZ_FN, SZ_W, SZ_FN, SZ_W, SZ_N},
        {SZ_FE, SZ_N, SZ_FE, SZ_FN, SZ_E, SZ_FN, SZ_E, SZ_N},
        {SZ_FE, SZ_S, SZ_FE, SZ_FS, SZ_E, SZ_FS, SZ_E, SZ_S}
    },
    {
        {SZ_E, SZ_S, SZ_E, SZ_FS, SZ_FE, SZ_FS, SZ_FE, SZ_S},
        {SZ_E, SZ_N, SZ_E, SZ_FN, SZ_FE, SZ_FN, SZ_FE, SZ_N},
        {SZ_W, SZ_N, SZ_W, SZ_FN, SZ_FW, SZ_FN, SZ_FW, SZ_N},
        {SZ_W, SZ_S, SZ_W, SZ_FS, SZ_FW, SZ_FS, SZ_FW, SZ_S}
    },
    {
        {SZ_U, SZ_FW, SZ_U, SZ_W, SZ_FU, SZ_W, SZ_FU, SZ_FW},
        {SZ_U, SZ_FE, SZ_U, SZ_E, SZ_FU, SZ_E, SZ_FU, SZ_FE},
        {SZ_D, SZ_FE, SZ_D, SZ_E, SZ_FD, SZ_E, SZ_FD, SZ_FE},
        {SZ_D, SZ_FW, SZ_D, SZ_W, SZ_FD, SZ_W, SZ_FD, SZ_FW}
    },
    {
        {SZ_U, SZ_FW, SZ_FU, SZ_FW, SZ_FU, SZ_W, SZ_U, SZ_W},
        {SZ_D, SZ_FW, SZ_FD, SZ_FW, SZ_FD, SZ_W, SZ_D, SZ_W},
        {SZ_D, SZ_FE, SZ_FD, SZ_FE, SZ_FD, SZ_E, SZ_D, SZ_E},
        {SZ_U, SZ_FE, SZ_FU, SZ_FE, SZ_FU, SZ_E, SZ_U, SZ_E}
    },
    {
        {SZ_U, SZ_S, SZ_U, SZ_FS, SZ_FU, SZ_FS, SZ_FU, SZ_S},
        {SZ_U, SZ_N, SZ_U, SZ_FN, SZ_FU, SZ_FN, SZ_FU, SZ_N},
        {SZ_D, SZ_N, SZ_D, SZ_FN, SZ_FD, SZ_FN, SZ_FD, SZ_N},
        {SZ_D, SZ_S, SZ_D, SZ_FS, SZ_FD, SZ_FS, SZ_FD, SZ_S}
    },
    {
        {SZ_FD, SZ_S, SZ_FD, SZ_FS, SZ_D, SZ_FS, SZ_D, SZ_S},
        {SZ_FD, SZ_N, SZ_FD, SZ_FN, SZ_D, SZ_FN, SZ_D, SZ_N},
        {SZ_FU, SZ_N, SZ_FU, SZ_FN, SZ_U, SZ_FN, SZ_U, SZ_N},
        {SZ_FU, SZ_S, SZ_FU, SZ_FS, SZ_U, SZ_FS, SZ_U, SZ_S}
    }
};

static const uint8_t AO_REMAP[6][4] = {
    {0, 1, 2, 3}, {2, 3, 0, 1}, {3, 0, 1, 2},
    {0, 1, 2, 3}, {3, 0, 1, 2}, {1, 2, 3, 0}
};

static void prepare_quad_ao(uint16_t sid, int32_t cell, int32_t q) {
    const QuadMeta *qm = &A->qm[q];
    const float *qb = A->qb + (uint32_t)q * 6u;
    int32_t dir = (int32_t)qm->dir;
    uint8_t flags = qm->flags;
    int face_cubic = ((flags & QFLAG_PLANAR) != 0) &&
                     (((flags & QFLAG_BOUNDARY) != 0) || ((A->s_flags[sid] & F_COLLISION_FULL) != 0));
    int32_t dstep = g_dir_step[dir];
    int32_t base = face_cubic ? (cell + dstep) : cell;
    const uint8_t *corners = AO_CORNERS[dir];
    int32_t o0 = g_dir_step[corners[0]];
    int32_t o1 = g_dir_step[corners[1]];
    int32_t o2 = g_dir_step[corners[2]];
    int32_t o3 = g_dir_step[corners[3]];

    uint16_t s0 = g_states[base + o0], s1 = g_states[base + o1];
    uint16_t s2 = g_states[base + o2], s3 = g_states[base + o3];
    int32_t light0 = light_coords(s0, base + o0); float shade0 = A->s_shade[s0];
    int32_t light1 = light_coords(s1, base + o1); float shade1 = A->s_shade[s1];
    int32_t light2 = light_coords(s2, base + o2); float shade2 = A->s_shade[s2];
    int32_t light3 = light_coords(s3, base + o3); float shade3 = A->s_shade[s3];

    int perm0 = (A->s_flags[g_states[base + o0 + dstep]] & F_LIGHT_PERMEABLE) != 0;
    int perm1 = (A->s_flags[g_states[base + o1 + dstep]] & F_LIGHT_PERMEABLE) != 0;
    int perm2 = (A->s_flags[g_states[base + o2 + dstep]] & F_LIGHT_PERMEABLE) != 0;
    int perm3 = (A->s_flags[g_states[base + o3 + dstep]] & F_LIGHT_PERMEABLE) != 0;

    float shade02, shade03, shade12, shade13;
    int32_t light02, light03, light12, light13;
    /* Exact vanilla MC-138211 parity: !perm2&&!perm1 and !perm3&&!perm1 fall back to shade0/light0 */
    if (!perm2 && !perm0) { shade02 = shade0; light02 = light0; }
    else { int32_t c = base + o0 + o2; uint16_t cs = g_states[c]; shade02 = A->s_shade[cs]; light02 = light_coords(cs, c); }
    if (!perm3 && !perm0) { shade03 = shade0; light03 = light0; }
    else { int32_t c = base + o0 + o3; uint16_t cs = g_states[c]; shade03 = A->s_shade[cs]; light03 = light_coords(cs, c); }
    if (!perm2 && !perm1) { shade12 = shade0; light12 = light0; }
    else { int32_t c = base + o1 + o2; uint16_t cs = g_states[c]; shade12 = A->s_shade[cs]; light12 = light_coords(cs, c); }
    if (!perm3 && !perm1) { shade13 = shade0; light13 = light0; }
    else { int32_t c = base + o1 + o3; uint16_t cs = g_states[c]; shade13 = A->s_shade[cs]; light13 = light_coords(cs, c); }

    int32_t light_center = light_coords(sid, cell);
    uint16_t next = g_states[cell + dstep];
    if (face_cubic || (A->s_flags[next] & F_SOLID_RENDER) == 0) {
        light_center = light_coords(next, cell + dstep);
    }
    float shade_center = face_cubic ? A->s_shade[g_states[base]] : A->s_shade[g_states[cell]];
    const uint8_t *remap = AO_REMAP[dir];
    int32_t shade_dir = (int32_t)qm->shade;
    float scale = g_cardinal[shade_dir >= 0 ? shade_dir : dir];

    /* 4-lane f32x4 shade average in exact left-associative order (((a + b) + c) + center) * 0.25f */
    v128_t va = wasm_f32x4_make(shade3, shade2, shade2, shade3);
    v128_t vb = wasm_f32x4_make(shade0, shade0, shade1, shade1);
    v128_t vc = wasm_f32x4_make(shade03, shade02, shade12, shade13);
    v128_t v_t = wasm_f32x4_mul(
        wasm_f32x4_add(wasm_f32x4_add(wasm_f32x4_add(va, vb), vc), wasm_f32x4_splat(shade_center)),
        wasm_f32x4_const_splat(0.25f)
    );

    /* 4-lane i32x4 smoothBlend */
    v128_t vn1 = wasm_i32x4_make(light3, light2, light2, light3);
    v128_t vn2 = wasm_i32x4_make(light0, light0, light1, light1);
    v128_t vn3 = wasm_i32x4_make(light03, light02, light12, light13);
    v128_t vlc = wasm_i32x4_splat(light_center);
    if (((light_center >> 20) & 15) > 2 || ((light_center >> 4) & 15) > 2) {
        v128_t vzero = wasm_i32x4_const_splat(0);
        v128_t vsky_nib = wasm_i32x4_const_splat(0x00F00000);
        v128_t vlc_sky = wasm_i32x4_splat(light_center & 0x00FF0000);
        v128_t m1 = wasm_i32x4_eq(vn1, vzero);
        vn1 = wasm_v128_bitselect(vlc, vn1, m1);
        vn1 = wasm_v128_or(vn1, wasm_v128_and(wasm_i32x4_eq(wasm_v128_and(vn1, vsky_nib), vzero), vlc_sky));
        v128_t m2 = wasm_i32x4_eq(vn2, vzero);
        vn2 = wasm_v128_bitselect(vlc, vn2, m2);
        vn2 = wasm_v128_or(vn2, wasm_v128_and(wasm_i32x4_eq(wasm_v128_and(vn2, vsky_nib), vzero), vlc_sky));
        v128_t m3 = wasm_i32x4_eq(vn3, vzero);
        vn3 = wasm_v128_bitselect(vlc, vn3, m3);
        vn3 = wasm_v128_or(vn3, wasm_v128_and(wasm_i32x4_eq(wasm_v128_and(vn3, vsky_nib), vzero), vlc_sky));
    }
    v128_t v_tc = wasm_v128_and(
        wasm_i32x4_shr(wasm_i32x4_add(wasm_i32x4_add(vn1, vn2), wasm_i32x4_add(vn3, vlc)), 2),
        wasm_i32x4_const_splat(0x00FF00FF)
    );

    v128_t v_bright;
    v128_t v_ql;
    if (flags & QFLAG_PARTIAL) {
        float fs[12];
        fs[SZ_W] = qb[0]; fs[SZ_E] = qb[3]; fs[SZ_D] = qb[1]; fs[SZ_U] = qb[4]; fs[SZ_N] = qb[2]; fs[SZ_S] = qb[5];
        fs[SZ_FW] = (float)(1.0 - (double)fs[SZ_W]); fs[SZ_FE] = (float)(1.0 - (double)fs[SZ_E]);
        fs[SZ_FD] = (float)(1.0 - (double)fs[SZ_D]); fs[SZ_FU] = (float)(1.0 - (double)fs[SZ_U]);
        fs[SZ_FN] = (float)(1.0 - (double)fs[SZ_N]); fs[SZ_FS] = (float)(1.0 - (double)fs[SZ_S]);

        const uint8_t (*ws)[8] = AO_WEIGHTS[dir];
        v128_t vw1 = wasm_f32x4_mul(
            wasm_f32x4_make(fs[ws[0][0]], fs[ws[1][0]], fs[ws[2][0]], fs[ws[3][0]]),
            wasm_f32x4_make(fs[ws[0][1]], fs[ws[1][1]], fs[ws[2][1]], fs[ws[3][1]]));
        v128_t vw2 = wasm_f32x4_mul(
            wasm_f32x4_make(fs[ws[0][2]], fs[ws[1][2]], fs[ws[2][2]], fs[ws[3][2]]),
            wasm_f32x4_make(fs[ws[0][3]], fs[ws[1][3]], fs[ws[2][3]], fs[ws[3][3]]));
        v128_t vw3 = wasm_f32x4_mul(
            wasm_f32x4_make(fs[ws[0][4]], fs[ws[1][4]], fs[ws[2][4]], fs[ws[3][4]]),
            wasm_f32x4_make(fs[ws[0][5]], fs[ws[1][5]], fs[ws[2][5]], fs[ws[3][5]]));
        v128_t vw4 = wasm_f32x4_mul(
            wasm_f32x4_make(fs[ws[0][6]], fs[ws[1][6]], fs[ws[2][6]], fs[ws[3][6]]),
            wasm_f32x4_make(fs[ws[0][7]], fs[ws[1][7]], fs[ws[2][7]], fs[ws[3][7]]));

        v128_t vt1 = wasm_i32x4_shuffle(v_t, v_t, 0, 0, 0, 0);
        v128_t vt2 = wasm_i32x4_shuffle(v_t, v_t, 1, 1, 1, 1);
        v128_t vt3 = wasm_i32x4_shuffle(v_t, v_t, 2, 2, 2, 2);
        v128_t vt4 = wasm_i32x4_shuffle(v_t, v_t, 3, 3, 3, 3);
        v_bright = wasm_f32x4_add(
            wasm_f32x4_add(wasm_f32x4_add(wasm_f32x4_mul(vt1, vw1), wasm_f32x4_mul(vt2, vw2)), wasm_f32x4_mul(vt3, vw3)),
            wasm_f32x4_mul(vt4, vw4));
        v_bright = wasm_f32x4_pmin(wasm_f32x4_const_splat(1.0f), wasm_f32x4_pmax(wasm_f32x4_const_splat(0.0f), v_bright));

        v128_t v_sky = wasm_f32x4_convert_i32x4(wasm_v128_and(wasm_i32x4_shr(v_tc, 16), wasm_i32x4_const_splat(255)));
        v128_t v_blk = wasm_f32x4_convert_i32x4(wasm_v128_and(v_tc, wasm_i32x4_const_splat(255)));

        v128_t vs1 = wasm_i32x4_shuffle(v_sky, v_sky, 0, 0, 0, 0);
        v128_t vs2 = wasm_i32x4_shuffle(v_sky, v_sky, 1, 1, 1, 1);
        v128_t vs3 = wasm_i32x4_shuffle(v_sky, v_sky, 2, 2, 2, 2);
        v128_t vs4 = wasm_i32x4_shuffle(v_sky, v_sky, 3, 3, 3, 3);
        v128_t sky_f = wasm_f32x4_add(
            wasm_f32x4_add(wasm_f32x4_add(wasm_f32x4_mul(vs1, vw1), wasm_f32x4_mul(vs2, vw2)), wasm_f32x4_mul(vs3, vw3)),
            wasm_f32x4_mul(vs4, vw4));

        v128_t vb1 = wasm_i32x4_shuffle(v_blk, v_blk, 0, 0, 0, 0);
        v128_t vb2 = wasm_i32x4_shuffle(v_blk, v_blk, 1, 1, 1, 1);
        v128_t vb3 = wasm_i32x4_shuffle(v_blk, v_blk, 2, 2, 2, 2);
        v128_t vb4 = wasm_i32x4_shuffle(v_blk, v_blk, 3, 3, 3, 3);
        v128_t blk_f = wasm_f32x4_add(
            wasm_f32x4_add(wasm_f32x4_add(wasm_f32x4_mul(vb1, vw1), wasm_f32x4_mul(vb2, vw2)), wasm_f32x4_mul(vb3, vw3)),
            wasm_f32x4_mul(vb4, vw4));

        v128_t v255 = wasm_i32x4_const_splat(255);
        v128_t sky_i = wasm_v128_and(wasm_i32x4_trunc_sat_f32x4(sky_f), v255);
        v128_t blk_i = wasm_v128_and(wasm_i32x4_trunc_sat_f32x4(blk_f), v255);
        v_ql = wasm_v128_or(blk_i, wasm_i32x4_shl(sky_i, 16));
    } else {
        v_bright = v_t;
        v_ql = v_tc;
    }

    /* Combined gray(brightness) -> scaleRGB(gray, scale) for all 4 vertices in f32x4/i32x4 */
    v128_t v_g = wasm_v128_and(
        wasm_i32x4_trunc_sat_f32x4(wasm_f32x4_floor(wasm_f32x4_mul(v_bright, wasm_f32x4_const_splat(255.0f)))),
        wasm_i32x4_const_splat(255)
    );
    v128_t v_sc = wasm_i32x4_trunc_sat_f32x4(wasm_f32x4_mul(wasm_f32x4_convert_i32x4(v_g), wasm_f32x4_splat(scale)));
    v_sc = wasm_i32x4_min(wasm_i32x4_const_splat(255), wasm_i32x4_max(wasm_i32x4_const_splat(0), v_sc));
    v128_t v_argb = wasm_v128_or(wasm_i32x4_const_splat((int32_t)0xFF000000u),
                                 wasm_i32x4_mul(v_sc, wasm_i32x4_const_splat(0x00010101)));

    int32_t tmp_c[4] __attribute__((aligned(16)));
    int32_t tmp_l[4] __attribute__((aligned(16)));
    wasm_v128_store(tmp_c, v_argb);
    wasm_v128_store(tmp_l, v_ql);
    A->q_color[remap[0]] = tmp_c[0]; A->q_color[remap[1]] = tmp_c[1];
    A->q_color[remap[2]] = tmp_c[2]; A->q_color[remap[3]] = tmp_c[3];
    A->q_light[remap[0]] = tmp_l[0]; A->q_light[remap[1]] = tmp_l[1];
    A->q_light[remap[2]] = tmp_l[2]; A->q_light[remap[3]] = tmp_l[3];
}

/* ---- Block Tessellation (ModelBlockRenderer.tesselateBlock) --------------------------------- */

static void tesselate_block(uint16_t sid, int32_t cell, int32_t x, int32_t y, int32_t z,
                            int32_t ox, int32_t oy, int32_t oz) {
    g_cur_tag = (g_tag_on && (int32_t)sid < g_tag_len) ? A->tag_block[sid] : 0;
    int32_t fl = A->s_flags[sid];
    uint16_t model = A->s_model[sid];
    if (model == 65535) {
        g_declined = DECLINE_UNSUPPORTED;
        return;
    }

    int32_t ex = -1;
    if (A->s_extra[sid] != 0 || A->s_tints[sid] != 0 || (fl & F_FLUID_TINT)) {
        ex = A->cell_extra[x | (z << 4) | (y << 8)];
        if (ex < 0 && A->s_extra[sid] != 0) {
            g_declined = DECLINE_MISSING_DATA;
            return;
        }
    }

    g_part_n = 0;
    if (model < MAX_MODELS && A->model_kind[model] == 0) {
        A->parts[g_part_n++] = A->model_a[model];
    } else {
        if (fl & F_CUSTOM_SEED) {
            int32_t so = ex + 1 + (((g_ex_i[ex] >> 16) & EX_OFFSET) ? 3 : 0);
            if (so < 0 || so + 1 >= g_ex_len) {
                g_declined = DECLINE_MISSING_DATA;
                return;
            }
            uint64_t seed = (uint64_t)(uint32_t)g_ex_i[so] | ((uint64_t)(uint32_t)g_ex_i[so + 1] << 32);
            rng_set_seed(seed);
        } else {
            rng_set_seed(mth_seed(ox + x, oy + y, oz + z));
        }
        collect_parts(model);
        if (g_declined) return;
    }
    if (g_part_n == 0) return;

    float fx = (float)x, fy = (float)y, fz = (float)z;
    int32_t tint_base = -1;
    if (ex >= 0) {
        int32_t ex_flags = (g_ex_i[ex] >> 16) & 255;
        g_tint_offset_words = ((ex_flags & EX_OFFSET) ? 3 : 0) + ((ex_flags & EX_SEED) ? 2 : 0);
        tint_base = ex;
        if (ex_flags & EX_OFFSET) {
            if (ex + 3 >= g_ex_len) {
                g_declined = DECLINE_MISSING_DATA;
                return;
            }
            fx = (float)((double)x + (double)u32_to_f32((uint32_t)g_ex_i[ex + 1]));
            fy = (float)((double)y + (double)u32_to_f32((uint32_t)g_ex_i[ex + 2]));
            fz = (float)((double)z + (double)u32_to_f32((uint32_t)g_ex_i[ex + 3]));
        }
    }

    g_tint_count = (int32_t)A->s_tints[sid];
    int32_t force_opaque = (fl & F_FORCE_OPAQUE) != 0;
    int32_t smooth = g_ao && A->s_emission[sid] == 0 && A->part_ao[A->parts[0]] != 0;
    int32_t cache_valid = 0, render_cache = 0;

    for (int32_t pi = 0; pi < g_part_n; pi++) {
        int32_t part = A->parts[pi] * 7;
        for (int32_t dir = 0; dir < 6; dir++) {
            int32_t mask = 1 << dir;
            int valid = (cache_valid & mask) != 0;
            int render = (render_cache & mask) != 0;
            if (!valid || render) {
                uint32_t n = A->part_count[part + dir];
                if (n != 0) {
                    int32_t ncell = cell + g_dir_step[dir];
                    if (!valid) {
                        render = should_render_face(sid, g_states[ncell], dir);
                        if (g_declined) return;
                        cache_valid |= mask;
                        if (render) render_cache |= mask;
                    }
                    if (render) {
                        int32_t start = A->part_start[part + dir];
                        if (smooth) {
                            for (uint32_t i = 0; i < n; i++) {
                                int32_t q = start + (int32_t)i;
                                prepare_quad_ao(sid, cell, q);
                                put_quad(q, fx, fy, fz, force_opaque, tint_base);
                                if (g_declined) return;
                            }
                        } else {
                            int32_t lc = light_coords(sid, ncell);
                            for (uint32_t i = 0; i < n; i++) {
                                int32_t q = start + (int32_t)i;
                                A->q_light[0] = A->q_light[1] = A->q_light[2] = A->q_light[3] = lc;
                                int32_t sd = (int32_t)A->qm[q].shade;
                                int32_t gc = g_gray_by_dir[sd >= 0 ? sd : (int32_t)A->qm[q].dir];
                                A->q_color[0] = A->q_color[1] = A->q_color[2] = A->q_color[3] = gc;
                                put_quad(q, fx, fy, fz, force_opaque, tint_base);
                                if (g_declined) return;
                            }
                        }
                    }
                }
            }
        }

        uint32_t n = A->part_count[part + 6];
        if (n != 0) {
            int32_t start = A->part_start[part + 6];
            for (uint32_t i = 0; i < n; i++) {
                int32_t q = start + (int32_t)i;
                if (smooth) {
                    prepare_quad_ao(sid, cell, q);
                } else {
                    uint8_t qf = A->qm[q].flags;
                    int32_t qd = (int32_t)A->qm[q].dir;
                    int cubic = ((qf & QFLAG_PLANAR) != 0) &&
                                (((qf & QFLAG_BOUNDARY) != 0) || ((fl & F_COLLISION_FULL) != 0));
                    int32_t lcell = cubic ? (cell + g_dir_step[qd]) : cell;
                    int32_t lc = light_coords(sid, lcell);
                    A->q_light[0] = A->q_light[1] = A->q_light[2] = A->q_light[3] = lc;
                    int32_t sd2 = (int32_t)A->qm[q].shade;
                    int32_t gc = g_gray_by_dir[sd2 >= 0 ? sd2 : qd];
                    A->q_color[0] = A->q_color[1] = A->q_color[2] = A->q_color[3] = gc;
                }
                put_quad(q, fx, fy, fz, force_opaque, tint_base);
                if (g_declined) return;
            }
        }
    }
}

/* ---- Fluids (FluidRenderer.tesselate) ------------------------------------------------------- */

static inline float mth_sin(double v) {
    int32_t idx = (int32_t)(int64_t)(v * 10430.378350470453);
    return A->sin_tab[idx & 65535];
}

static inline float mth_cos(double v) {
    int32_t idx = (int32_t)(int64_t)(v * 10430.378350470453 + 16384.0);
    return A->sin_tab[idx & 65535];
}

static inline double fast_inv_sqrt(double x) {
    double xhalf = 0.5 * x;
    union { double d; int64_t i; } u;
    u.d = x;
    u.i = 6910469410427058090LL - (u.i >> 1);
    x = u.d;
    return x * (1.5 - xhalf * x * x);
}

static double mth_atan2(double y, double x) {
    double d2 = x * x + y * y;
    if (d2 != d2) return d2;
    int neg_y = y < 0.0; if (neg_y) y = -y;
    int neg_x = x < 0.0; if (neg_x) x = -x;
    int steep = y > x;
    if (steep) { double t = x; x = y; y = t; }
    double rinv = fast_inv_sqrt(d2);
    x *= rinv; y *= rinv;
    double yp = g_frac_bias + y;
    union { double d; uint64_t u; } u;
    u.d = yp;
    uint32_t index = (uint32_t)u.u;
    if (index > 256u) index = 256u;
    double phi = A->asin_tab[index];
    double c_phi = A->cos_tab[index];
    double s_phi = yp - g_frac_bias;
    double sd = y * c_phi - x * s_phi;
    double d = (6.0 + sd * sd) * sd * 0.16666666666666666;
    double theta = phi + d;
    if (steep) theta = 1.5707963267948966 - theta;
    if (neg_x) theta = 3.141592653589793 - theta;
    if (neg_y) theta = -theta;
    return theta;
}

static int fluid_occluded(int32_t dir, float height, uint16_t nsid) {
    uint16_t occ = A->s_occ[(uint32_t)nsid * 6u + OPPOSITE[dir]];
    if (occ == 0) return 0;
    if (occ == 1) return (dir != 1) || (height == 1.0f);
    if (occ >= MAX_SHAPES || !A->shape_valid[occ]) {
        g_declined = DECLINE_MISSING_DATA;
        return 0;
    }
    const uint16_t *rows = A->shape_rows[occ];
    if (dir < 2) {
        if (dir == 1) {
            double diff = (double)height - 1.0;
            if (diff < 0.0) diff = -diff;
            if (!(diff <= 1.0e-7)) return 0;
        }
        v128_t b0 = wasm_v128_load(rows);
        v128_t b1 = wasm_v128_load(rows + 8);
        return wasm_v128_any_true(wasm_v128_not(wasm_v128_and(b0, b1))) ? 0 : 1;
    }
    for (int r = 0; r < 16; r++) {
        if (((double)r / 16.0) < (double)height && rows[r] != 65535) return 0;
    }
    return 1;
}

static inline float fluid_height(uint8_t group, uint16_t sid, int32_t cell) {
    if (A->s_fluid_group[sid] == group) {
        return A->s_fluid_group[g_states[cell + g_HH]] == group ? 1.0f : A->s_own_height[sid];
    }
    return (A->s_flags[sid] & F_SOLID) == 0 ? 0.0f : -1.0f;
}

static float average_height(uint8_t group, float height_self, float height2, float height1, int32_t corner_cell) {
    if (!(height1 >= 1.0f) && !(height2 >= 1.0f)) {
        float sum = 0.0f, weight = 0.0f;
        if (height1 > 0.0f || height2 > 0.0f) {
            float hc = fluid_height(group, g_states[corner_cell], corner_cell);
            if (hc >= 1.0f) return 1.0f;
            if (hc >= 0.8f) { sum = sum + (hc * 10.0f); weight = weight + 10.0f; }
            else if (hc >= 0.0f) { sum = sum + hc; weight = weight + 1.0f; }
        }
        if (height_self >= 0.8f) { sum = sum + (height_self * 10.0f); weight = weight + 10.0f; }
        else if (height_self >= 0.0f) { sum = sum + height_self; weight = weight + 1.0f; }
        if (height1 >= 0.8f) { sum = sum + (height1 * 10.0f); weight = weight + 10.0f; }
        else if (height1 >= 0.0f) { sum = sum + height1; weight = weight + 1.0f; }
        if (height2 >= 0.8f) { sum = sum + (height2 * 10.0f); weight = weight + 10.0f; }
        else if (height2 >= 0.0f) { sum = sum + height2; weight = weight + 1.0f; }
        return sum / weight;
    }
    return 1.0f;
}

static inline int32_t fluid_light(int32_t cell) {
    int32_t a = light_coords(g_states[cell], cell);
    int32_t b = light_coords(g_states[cell + g_HH], cell + g_HH);
    int32_t ba = (a >> 4) & 15, bb = (b >> 4) & 15;
    int32_t sa = (a >> 20) & 15, sb = (b >> 20) & 15;
    return ((ba > bb ? ba : bb) << 4) | ((sa > sb ? sa : sb) << 20);
}

static inline int fluid_solid_face(uint8_t group, int32_t cell, int32_t dir) {
    uint16_t s = g_states[cell];
    if (A->s_fluid_group[s] == group) return 0;
    if (A->s_flags[s] & F_ICE) return 0;
    if (A->s_flags[s] & F_DYNAMIC_SHAPE) {
        g_declined = DECLINE_UNSUPPORTED;
        return 0;
    }
    return ((A->s_sturdy[s] >> dir) & 1) != 0;
}

static void fluid_flow(uint16_t sid, int32_t cell) {
    uint8_t group = A->s_fluid_group[sid];
    float own = A->s_own_height[sid];
    double fx = 0.0, fz = 0.0;

    for (int i = 0; i < 4; i++) {
        int32_t dir = HORIZ[i];
        int32_t ncell = cell + g_dir_step[dir];
        uint16_t ns = g_states[ncell];
        uint8_t ng = A->s_fluid_group[ns];
        if (ng == 0 || ng == group) {
            float nh = (ng == 0) ? 0.0f : A->s_own_height[ns];
            float distance = 0.0f;
            if (nh == 0.0f) {
                if ((A->s_flags[ns] & F_BLOCKS_FLUID_FLOW) == 0) {
                    uint16_t bs = g_states[ncell - g_HH];
                    uint8_t bg = A->s_fluid_group[bs];
                    if (bg == 0 || bg == group) {
                        nh = (bg == 0) ? 0.0f : A->s_own_height[bs];
                        if (nh > 0.0f) distance = own - (nh - MAX_FLUID_HEIGHT);
                    }
                }
            } else if (nh > 0.0f) {
                distance = own - nh;
            }
            if (distance != 0.0f) {
                fx += (double)STEP_X[dir] * (double)distance;
                fz += (double)STEP_Z[dir] * (double)distance;
            }
        }
    }

    double vx = fx, vy = 0.0, vz = fz;
    if (A->s_flags[sid] & F_FLUID_FALLING) {
        for (int i = 0; i < 4; i++) {
            int32_t dir = HORIZ[i];
            int32_t ncell = cell + g_dir_step[dir];
            if (fluid_solid_face(group, ncell, dir) || fluid_solid_face(group, ncell + g_HH, dir)) {
                double len = __builtin_sqrt(vx * vx + vy * vy + vz * vz);
                if (len < (double)TINY) { vx = 0.0; vy = 0.0; vz = 0.0; }
                else { vx /= len; vy /= len; vz /= len; }
                vy += -6.0;
                break;
            }
        }
    }
    double len = __builtin_sqrt(vx * vx + vy * vy + vz * vz);
    if (len < (double)TINY) { g_flow_x = 0.0; g_flow_z = 0.0; }
    else { g_flow_x = vx / len; g_flow_z = vz / len; }
}

static inline void write_fluid_vtx(int32_t *out, float x, float y, float z,
                                   int32_t abgr, float u, float v, int32_t light_tagged) {
    out[0] = (int32_t)f32_to_u32(x);
    out[1] = (int32_t)f32_to_u32(y);
    out[2] = (int32_t)f32_to_u32(z);
    out[3] = abgr;
    out[4] = (int32_t)f32_to_u32(u);
    out[5] = (int32_t)f32_to_u32(v);
    out[6] = light_tagged;
}

static void fluid_face(int32_t layer,
                       float x0, float y0, float z0, float u0, float v0,
                       float x1, float y1, float z1, float u1, float v1,
                       float x2, float y2, float z2, float u2, float v2,
                       float x3, float y3, float z3, float u3, float v3,
                       int32_t color, int32_t lc, int back, int32_t face_dir) {
    int32_t *out = layer_reserve(layer, back ? 56 : 28);
    if (!out) return;
    int32_t abgr = argb_to_abgr(color);
    int32_t lt = g_tag_on ? (lc | g_cur_fluid_tag | ((face_dir + 1) << 8)) : lc;
    write_fluid_vtx(out + 0,  x0, y0, z0, abgr, u0, v0, lt);
    write_fluid_vtx(out + 7,  x1, y1, z1, abgr, u1, v1, lt);
    write_fluid_vtx(out + 14, x2, y2, z2, abgr, u2, v2, lt);
    write_fluid_vtx(out + 21, x3, y3, z3, abgr, u3, v3, lt);
    if (back) {
        int32_t blt = g_tag_on ? (lc | g_cur_fluid_tag | (((int32_t)OPPOSITE[face_dir] + 1) << 8)) : lc;
        write_fluid_vtx(out + 28, x0, y0, z0, abgr, u0, v0, blt);
        write_fluid_vtx(out + 35, x3, y3, z3, abgr, u3, v3, blt);
        write_fluid_vtx(out + 42, x2, y2, z2, abgr, u2, v2, blt);
        write_fluid_vtx(out + 49, x1, y1, z1, abgr, u1, v1, blt);
    }
}

static inline float sprite_u(const FluidSprite *sp, float t) {
    return sp->u0 + ((sp->u1 - sp->u0) * t);
}

static inline float sprite_v(const FluidSprite *sp, float t) {
    return sp->v0 + ((sp->v1 - sp->v0) * t);
}

static void tesselate_fluid(uint16_t sid, int32_t cell, int32_t xi, int32_t yi, int32_t zi) {
    g_cur_fluid_tag = (g_tag_on && (int32_t)sid < g_tag_len) ? A->tag_fluid[sid] : 0;
    uint8_t group = A->s_fluid_group[sid];
    int32_t cD = cell - g_HH, cU = cell + g_HH;
    int32_t cN = cell - g_H,  cS = cell + g_H;
    int32_t cW = cell - 1,    cE = cell + 1;
    uint16_t sD = g_states[cD], sU = g_states[cU];
    uint16_t sN = g_states[cN], sS = g_states[cS];
    uint16_t sW = g_states[cW], sE = g_states[cE];

    int render_up    = A->s_fluid_group[sU] != group;
    int render_down  = A->s_fluid_group[sD] != group && !fluid_occluded(1, 1.0f, sid) && !fluid_occluded(0, MAX_FLUID_HEIGHT, sD);
    int render_north = A->s_fluid_group[sN] != group && !fluid_occluded(3, 1.0f, sid) && !fluid_occluded(2, 1.0f, sN);
    int render_south = A->s_fluid_group[sS] != group && !fluid_occluded(2, 1.0f, sid) && !fluid_occluded(3, 1.0f, sS);
    int render_west  = A->s_fluid_group[sW] != group && !fluid_occluded(5, 1.0f, sid) && !fluid_occluded(4, 1.0f, sW);
    int render_east  = A->s_fluid_group[sE] != group && !fluid_occluded(4, 1.0f, sid) && !fluid_occluded(5, 1.0f, sE);
    if (g_declined) return;
    if (!(render_up || render_down || render_east || render_west || render_north || render_south)) return;

    uint16_t fm_id = A->s_fluid_model[sid];
    if (fm_id >= MAX_FLUID_MODELS || !A->fluid_models[fm_id].valid) {
        g_declined = DECLINE_UNSUPPORTED;
        return;
    }
    const FluidModel *model = &A->fluid_models[fm_id];
    const FluidSprite *still   = &model->sprites[0];
    const FluidSprite *flowing = &model->sprites[1];
    const FluidSprite *overlay = &model->sprites[2];
    if (still->id) add_sprite(still->id);
    if (flowing->id) add_sprite(flowing->id);
    if (model->overlay && overlay->id) add_sprite(overlay->id);

    int32_t layer = (int32_t)model->layer;
    int32_t tint = -1;
    if (model->tint) {
        int32_t ex = A->cell_extra[xi | (zi << 4) | (yi << 8)];
        if (ex < 0) {
            g_declined = DECLINE_MISSING_DATA;
            return;
        }
        int32_t ex_flags = (g_ex_i[ex] >> 16) & 255;
        int32_t colours  = (int32_t)(((uint32_t)g_ex_i[ex] >> 24) & 255u);
        int32_t idx = ex + 1 + ((ex_flags & EX_OFFSET) ? 3 : 0) + ((ex_flags & EX_SEED) ? 2 : 0) + colours - 1;
        if (colours <= 0 || idx < 0 || idx >= g_ex_len) {
            g_declined = DECLINE_MISSING_DATA;
            return;
        }
        tint = g_ex_i[idx];
    }

    float height_self = fluid_height(group, sid, cell);
    float hNE, hNW, hSE, hSW;
    if (height_self >= 1.0f) {
        hNE = 1.0f; hNW = 1.0f; hSE = 1.0f; hSW = 1.0f;
    } else {
        float hN = fluid_height(group, sN, cN);
        float hS = fluid_height(group, sS, cS);
        float hE = fluid_height(group, sE, cE);
        float hW = fluid_height(group, sW, cW);
        hNE = average_height(group, height_self, hN, hE, cN + 1);
        hNW = average_height(group, height_self, hN, hW, cN - 1);
        hSE = average_height(group, height_self, hS, hE, cS + 1);
        hSW = average_height(group, height_self, hS, hW, cS - 1);
    }

    float x = (float)xi, y = (float)yi, z = (float)zi;
    float bottom_offs = render_down ? OFFS : 0.0f;
    float x1 = x + 1.0f, z1 = z + 1.0f;

    if (render_up) {
        float m1 = hNW < hSW ? hNW : hSW;
        float m2 = hSE < hNE ? hSE : hNE;
        float hmin = m1 < m2 ? m1 : m2;
        if (!fluid_occluded(1, hmin, sU)) {
            if (g_declined) return;
            hNW = hNW - OFFS; hSW = hSW - OFFS; hSE = hSE - OFFS; hNE = hNE - OFFS;
            fluid_flow(sid, cell);
            if (g_declined) return;
            float u00, u01, u10, u11, v00, v01, v10, v11;
            if (g_flow_x == 0.0 && g_flow_z == 0.0) {
                u00 = still->u0; v00 = still->v0;
                u01 = u00;       v01 = still->v1;
                u10 = still->u1; v10 = v01;
                u11 = u10;       v11 = v00;
            } else {
                const float HALF_PI_F = (float)(3.141592653589793 / 2.0);
                float angle = (float)mth_atan2(g_flow_z, g_flow_x) - HALF_PI_F;
                float s = mth_sin((double)angle) * 0.25f;
                float c = mth_cos((double)angle) * 0.25f;
                u00 = sprite_u(flowing, 0.5f + ((-c) - s)); v00 = sprite_v(flowing, 0.5f + ((-c) + s));
                u01 = sprite_u(flowing, 0.5f + ((-c) + s)); v01 = sprite_v(flowing, 0.5f + (c + s));
                u10 = sprite_u(flowing, 0.5f + (c + s));    v10 = sprite_v(flowing, 0.5f + (c - s));
                u11 = sprite_u(flowing, 0.5f + (c - s));    v11 = sprite_v(flowing, 0.5f + ((-c) - s));
            }
            int32_t top_light = fluid_light(cell);
            int32_t top_color = scale_rgb(tint, g_cardinal[1]);
            int back = 0;
            for (int bz = -1; bz <= 1 && !back; bz++) {
                for (int bx = -1; bx <= 1; bx++) {
                    uint16_t bs = g_states[cU + bx + bz * g_H];
                    if (A->s_fluid_group[bs] != group && (A->s_flags[bs] & F_SOLID_RENDER) == 0) {
                        back = 1;
                        break;
                    }
                }
            }
            fluid_face(layer,
                       x,  y + hNW, z,  u00, v00,
                       x,  y + hSW, z1, u01, v01,
                       x1, y + hSE, z1, u10, v10,
                       x1, y + hNE, z,  u11, v11,
                       top_color, top_light, back, 1);
            if (g_declined) return;
        } else if (g_declined) {
            return;
        }
    }

    if (render_down) {
        float yb = y + bottom_offs;
        fluid_face(layer,
                   x,  yb, z,  still->u0, still->v0,
                   x1, yb, z,  still->u1, still->v0,
                   x1, yb, z1, still->u1, still->v1,
                   x,  yb, z1, still->u0, still->v1,
                   scale_rgb(tint, g_cardinal[0]), fluid_light(cD), 0, 0);
        if (g_declined) return;
    }

    int32_t side_light = fluid_light(cell);
    for (int hi = 0; hi < 4; hi++) {
        int32_t face_dir = HORIZ[hi];
        float hh0, hh1, fx0, fz0, fx1, fz1;
        int cond;
        uint16_t face_state;
        if (face_dir == 2) {
            hh0 = hNW; hh1 = hNE; fx0 = x; fx1 = x1; fz0 = z + OFFS; fz1 = fz0; cond = render_north; face_state = sN;
        } else if (face_dir == 3) {
            hh0 = hSE; hh1 = hSW; fx0 = x1; fx1 = x; fz0 = z1 - OFFS; fz1 = fz0; cond = render_south; face_state = sS;
        } else if (face_dir == 4) {
            hh0 = hSW; hh1 = hNW; fx0 = x + OFFS; fx1 = fx0; fz0 = z1; fz1 = z; cond = render_west; face_state = sW;
        } else {
            hh0 = hNE; hh1 = hSE; fx0 = x1 - OFFS; fx1 = fx0; fz0 = z; fz1 = z1; cond = render_east; face_state = sE;
        }
        float hmax = hh0 > hh1 ? hh0 : hh1;
        if (cond && !fluid_occluded(face_dir, hmax, face_state)) {
            if (g_declined) return;
            const FluidSprite *sprite = flowing;
            int is_overlay = 0;
            if (model->overlay && (A->s_flags[face_state] & F_OVERLAY_NEIGHBOUR)) {
                sprite = overlay;
                is_overlay = 1;
            }
            float su0  = sprite_u(sprite, 0.0f);
            float su1  = sprite_u(sprite, 0.5f);
            float sv01 = sprite_v(sprite, (1.0f - hh0) * 0.5f);
            float sv02 = sprite_v(sprite, (1.0f - hh1) * 0.5f);
            float sv1  = sprite_v(sprite, 0.5f);
            float shade_side = (face_dir < 4) ? g_cardinal[2] : g_cardinal[4];
            int32_t face_color = scale_rgb(tint, g_cardinal[1] * shade_side);
            float yb2 = y + bottom_offs;
            fluid_face(layer,
                       fx0, y + hh0, fz0, su0, sv01,
                       fx1, y + hh1, fz1, su1, sv02,
                       fx1, yb2,     fz1, su1, sv1,
                       fx0, yb2,     fz0, su0, sv1,
                       face_color, side_light, !is_overlay, face_dir);
            if (g_declined) return;
        } else if (g_declined) {
            return;
        }
    }
}

/* ---- VisGraph BFS --------------------------------------------------------------------------- */

static void vis_resolve(int32_t opaque, int32_t out_vis[2]) {
    if (opaque < 256) {
        out_vis[0] = -1;
        out_vis[1] = 15;
        return;
    }
    uint64_t vis = 0;
    if (opaque == 4096) {
        out_vis[0] = 0;
        out_vis[1] = 0;
        return;
    }
    uint8_t *vbits = A->vis_bits;
    int32_t *vqueue = A->vis_queue;
    for (int32_t e = 0; e < 1352; e++) {
        int32_t start_idx = A->vis_edges[e];
        if (vbits[start_idx]) continue;
        uint32_t faces = 0;
        int32_t head = 0, tail = 0;
        vqueue[tail++] = start_idx;
        vbits[start_idx] = 1;
        while (head < tail) {
            int32_t index = vqueue[head++];
            int32_t x = index & 15;
            int32_t z = (index >> 4) & 15;
            int32_t y = (index >> 8) & 15;
            if (x == 0) faces |= 16u; else if (x == 15) faces |= 32u;
            if (y == 0) faces |= 1u;  else if (y == 15) faces |= 2u;
            if (z == 0) faces |= 4u;  else if (z == 15) faces |= 8u;
            if (y != 0  && !vbits[index - 256]) { vbits[index - 256] = 1; vqueue[tail++] = index - 256; }
            if (y != 15 && !vbits[index + 256]) { vbits[index + 256] = 1; vqueue[tail++] = index + 256; }
            if (z != 0  && !vbits[index - 16])  { vbits[index - 16]  = 1; vqueue[tail++] = index - 16; }
            if (z != 15 && !vbits[index + 16])  { vbits[index + 16]  = 1; vqueue[tail++] = index + 16; }
            if (x != 0  && !vbits[index - 1])   { vbits[index - 1]   = 1; vqueue[tail++] = index - 1; }
            if (x != 15 && !vbits[index + 1])   { vbits[index + 1]   = 1; vqueue[tail++] = index + 1; }
        }
        for (int d1 = 0; d1 < 6; d1++) {
            if (!((faces >> d1) & 1u)) continue;
            for (int d2 = 0; d2 < 6; d2++) {
                if ((faces >> d2) & 1u) {
                    vis |= 1ULL << (d1 + d2 * 6);
                }
            }
        }
    }
    out_vis[0] = (int32_t)(uint32_t)vis;
    out_vis[1] = (int32_t)(uint32_t)(vis >> 32);
}

/* ---- Exported ABI Functions ----------------------------------------------------------------- */

MK_EXPORT("wasm_init")
int32_t wasm_init(void) {
#ifdef __wasm__
    size_t need_pages = ((size_t)ARENA_BASE + sizeof(MeshArena) + 65535u) >> 16;
    size_t cur_pages  = __builtin_wasm_memory_size(0);
    if (cur_pages < need_pages) {
        if (__builtin_wasm_memory_grow(0, need_pages - cur_pages) == (size_t)-1) {
            return -1;
        }
    }
#endif

    /* 1. Trig tables matching JS initTrig() */
    for (int i = 0; i < 65536; i++) {
        double s, c;
        dd_sincos((double)i / 10430.378350470453, &s, &c);
        A->sin_tab[i] = (float)s;
    }
    {
        union { double d; uint64_t u; } u;
        u.u = 0x42B0000000000000ULL; /* Double.longBitsToDouble(4805340802404319232L) */
        g_frac_bias = u.d;
    }
    for (int ind = 0; ind < 257; ind++) {
        double a = dd_asin((double)ind / 256.0);
        double s, c;
        dd_sincos(a, &s, &c);
        A->asin_tab[ind] = a;
        A->cos_tab[ind]  = c;
    }

    /* 2. VisGraph 1352 boundary cells */
    int32_t n = 0;
    for (int ex = 0; ex < 16; ex++) {
        for (int ey = 0; ey < 16; ey++) {
            for (int ez = 0; ez < 16; ez++) {
                if (ex == 0 || ex == 15 || ey == 0 || ey == 15 || ez == 0 || ez == 15) {
                    A->vis_edges[n++] = ex | (ey << 8) | (ez << 4);
                }
            }
        }
    }

    /* 3. Shape 1 = full 16x16 solid block */
    for (int r = 0; r < 16; r++) A->shape_rows[1][r] = 65535u;
    A->shape_valid[1] = 1;

    /* 4. Per-job extra index array initialized to -1 */
    for (int i = 0; i < 4096; i++) A->cell_extra[i] = -1;
    g_extra_used_n = 0;

    /* 5. Model kinds initialized to -1 (undefined) */
    mk_fill_i8(A->model_kind, -1, MAX_MODELS);

    g_inited = 1;
    return 0;
}

MK_EXPORT("wasm_reset")
void wasm_reset(int32_t epoch) {
    if (!g_inited) wasm_init();
    g_epoch = epoch;
    mk_zero_bytes(A->s_known, MAX_STATES);
    mk_zero_bytes(A->shape_valid, MAX_SHAPES);
    for (int r = 0; r < 16; r++) A->shape_rows[1][r] = 65535u;
    A->shape_valid[1] = 1;

    g_quad_count = 0;
    g_model_pool_n = 0;
    mk_fill_i8(A->model_kind, -1, MAX_MODELS);
    for (int i = 0; i < MAX_FLUID_MODELS; i++) A->fluid_models[i].valid = 0;

    g_tag_on = 0;
    g_tag_len = 0;
    g_cur_tag = 0;
    g_cur_fluid_tag = 0;
}

MK_EXPORT("wasm_job_in_ptr")
mk_ptr_t wasm_job_in_ptr(void) {
    return MK_BYTES_TO_PTR(A->in_buf);
}

MK_EXPORT("wasm_job_in_cap")
int32_t wasm_job_in_cap(void) {
    return IN_BUF_CAP;
}

MK_EXPORT("wasm_out_ptr")
mk_ptr_t wasm_out_ptr(void) {
    return MK_BYTES_TO_PTR(A->out_buf);
}

MK_EXPORT("wasm_out_cap")
int32_t wasm_out_cap(void) {
    return OUT_BUF_WORDS * 4;
}

MK_EXPORT("wasm_read_defs")
int32_t wasm_read_defs(mk_ptr_t ptr, int32_t len) {
    if (!g_inited) wasm_init();
    if (len < 8) return -1;
    const uint8_t *buf = MK_PTR_TO_BYTES(ptr);
    int32_t kind = rd_i32(buf);
    int32_t def_epoch = rd_i32(buf + 4);
    if (kind != 1) return -1;
    if (def_epoch != g_epoch) return 0; /* stale epoch silently ignored like JS worker */

    int32_t p = 8;
    while (p < len) {
        uint8_t rec = buf[p++];
        if (rec == 1) { /* shape */
            if (p + 34 > len) return -2;
            uint16_t sid = rd_u16(buf + p); p += 2;
            if (sid >= MAX_SHAPES) return -3;
            for (int r = 0; r < 16; r++) {
                A->shape_rows[sid][r] = rd_u16(buf + p);
                p += 2;
            }
            A->shape_valid[sid] = 1;
        } else if (rec == 2) { /* part */
            if (p + 3 > len) return -2;
            uint16_t pid = rd_u16(buf + p); p += 2;
            if (pid >= MAX_PARTS) return -3;
            A->part_ao[pid] = buf[p++];
            for (int b = 0; b < 7; b++) {
                if (p + 2 > len) return -2;
                uint16_t n = rd_u16(buf + p); p += 2;
                A->part_start[(uint32_t)pid * 7u + (uint32_t)b] = (int32_t)g_quad_count;
                A->part_count[(uint32_t)pid * 7u + (uint32_t)b] = n;
                if (p + (int32_t)n * 88 > len) return -2;
                for (uint32_t i = 0; i < n; i++) {
                    if (g_quad_count >= MAX_QUADS) return -3;
                    uint32_t q = g_quad_count++;
                    float *qf = A->qf + q * 20u;
                    for (int k = 0; k < 20; k++) {
                        qf[k] = rd_f32(buf + p);
                        p += 4;
                    }
                    uint8_t dir = buf[p];
                    QuadMeta *qm = &A->qm[q];
                    qm->dir      = dir;
                    qm->layer    = buf[p + 1];
                    qm->tint     = (int8_t)buf[p + 2];
                    qm->shade    = (int8_t)buf[p + 3];
                    qm->emission = buf[p + 4];
                    qm->sprite   = rd_u16(buf + p + 6);
                    p += 8;

                    float minX = 32.0f, minY = 32.0f, minZ = 32.0f;
                    float maxX = -32.0f, maxY = -32.0f, maxZ = -32.0f;
                    for (int v = 0; v < 4; v++) {
                        float vx = qf[v * 3 + 0];
                        float vy = qf[v * 3 + 1];
                        float vz = qf[v * 3 + 2];
                        if (vx < minX) minX = vx;
                        if (vy < minY) minY = vy;
                        if (vz < minZ) minZ = vz;
                        if (vx > maxX) maxX = vx;
                        if (vy > maxY) maxY = vy;
                        if (vz > maxZ) maxZ = vz;
                    }
                    float *qb = A->qb + q * 6u;
                    qb[0] = minX; qb[1] = minY; qb[2] = minZ;
                    qb[3] = maxX; qb[4] = maxY; qb[5] = maxZ;

                    int partial, planar, boundary;
                    if (dir < 2) {
                        partial  = (minX >= E4) || (minZ >= E4) || (maxX <= N4) || (maxZ <= N4);
                        planar   = (minY == maxY);
                        boundary = (dir == 0) ? (minY < E4) : (maxY > N4);
                    } else if (dir < 4) {
                        partial  = (minX >= E4) || (minY >= E4) || (maxX <= N4) || (maxY <= N4);
                        planar   = (minZ == maxZ);
                        boundary = (dir == 2) ? (minZ < E4) : (maxZ > N4);
                    } else {
                        partial  = (minY >= E4) || (minZ >= E4) || (maxY <= N4) || (maxZ <= N4);
                        planar   = (minX == maxX);
                        boundary = (dir == 4) ? (minX < E4) : (maxX > N4);
                    }
                    qm->flags = (uint8_t)((partial ? QFLAG_PARTIAL : 0) |
                                          (planar ? QFLAG_PLANAR : 0) |
                                          (boundary ? QFLAG_BOUNDARY : 0));
                }
            }
        } else if (rec == 3) { /* model */
            if (p + 3 > len) return -2;
            uint16_t mid = rd_u16(buf + p); p += 2;
            uint8_t kind_m = buf[p++];
            if (mid >= MAX_MODELS) return -3;
            A->model_kind[mid] = (int8_t)kind_m;
            if (kind_m == 0) {
                if (p + 2 > len) return -2;
                A->model_a[mid] = (int32_t)rd_u16(buf + p); p += 2;
            } else if (kind_m == 1) {
                if (p + 6 > len) return -2;
                uint16_t cn = rd_u16(buf + p); p += 2;
                int32_t total = rd_i32(buf + p); p += 4;
                if (p + (int32_t)cn * 6 > len) return -2;
                if (g_model_pool_n + cn > MAX_MODEL_CHILDREN) return -3;
                A->model_a[mid]   = total;
                A->model_off[mid] = (int32_t)g_model_pool_n;
                A->model_cnt[mid] = cn;
                for (uint32_t c = 0; c < cn; c++) {
                    A->model_child_w[g_model_pool_n]  = rd_i32(buf + p);
                    A->model_child_id[g_model_pool_n] = rd_u16(buf + p + 4);
                    g_model_pool_n++;
                    p += 6;
                }
            } else {
                if (p + 2 > len) return -2;
                uint16_t mn = rd_u16(buf + p); p += 2;
                if (p + (int32_t)mn * 2 > len) return -2;
                if (g_model_pool_n + mn > MAX_MODEL_CHILDREN) return -3;
                A->model_off[mid] = (int32_t)g_model_pool_n;
                A->model_cnt[mid] = mn;
                for (uint32_t c = 0; c < mn; c++) {
                    A->model_child_id[g_model_pool_n++] = rd_u16(buf + p);
                    p += 2;
                }
            }
        } else if (rec == 4) { /* fluid model */
            if (p + 5 + 54 > len) return -2;
            uint16_t fid = rd_u16(buf + p); p += 2;
            if (fid >= MAX_FLUID_MODELS) return -3;
            FluidModel *fm = &A->fluid_models[fid];
            fm->layer   = buf[p];
            fm->overlay = buf[p + 1] != 0 ? 1 : 0;
            fm->tint    = buf[p + 2] != 0 ? 1 : 0;
            p += 3;
            for (int s = 0; s < 3; s++) {
                fm->sprites[s].u0 = rd_f32(buf + p + 0);
                fm->sprites[s].u1 = rd_f32(buf + p + 4);
                fm->sprites[s].v0 = rd_f32(buf + p + 8);
                fm->sprites[s].v1 = rd_f32(buf + p + 12);
                fm->sprites[s].id = rd_u16(buf + p + 16);
                p += 18;
            }
            fm->valid = 1;
        } else if (rec == 5) { /* state */
            if (p + 47 > len) return -2;
            uint16_t id = rd_u16(buf + p); p += 2;
            A->s_flags[id]     = rd_i32(buf + p); /* sNeed at p+4 unused during meshing */ p += 8;
            A->s_emission[id]  = buf[p++];
            A->s_shade[id]     = rd_f32(buf + p); p += 4;
            A->s_skip_rule[id] = buf[p];
            A->s_skip_key[id]  = rd_u16(buf + p + 1);
            A->s_block[id]     = rd_u16(buf + p + 3);
            p += 5;
            for (int d = 0; d < 6; d++) {
                A->s_occ[(uint32_t)id * 6u + (uint32_t)d] = rd_u16(buf + p);
                p += 2;
            }
            A->s_tints[id]       = buf[p];
            A->s_model[id]       = rd_u16(buf + p + 1); p += 3;
            A->s_fluid_group[id] = buf[p];
            A->s_fluid_model[id] = rd_u16(buf + p + 1); /* sAmount at p+3 unused */ p += 4;
            A->s_own_height[id]  = rd_f32(buf + p); p += 4;
            A->s_sturdy[id]      = buf[p];
            A->s_extra[id]       = buf[p + 1]; p += 2;
            uint16_t name_len    = rd_u16(buf + p); p += 2;
            if (p + (int32_t)name_len > len) return -2;
            p += (int32_t)name_len;
            A->s_known[id] = 1;
        } else {
            return -4; /* unknown record */
        }
    }
    return 0;
}

MK_EXPORT("wasm_read_tags")
int32_t wasm_read_tags(mk_ptr_t ptr, int32_t len) {
    if (!g_inited) wasm_init();
    if (len < 16) {
        g_tag_on = 0; g_tag_len = 0; g_cur_tag = 0; g_cur_fluid_tag = 0;
        return -1;
    }
    const uint8_t *buf = MK_PTR_TO_BYTES(ptr);
    int32_t kind = rd_i32(buf);
    int32_t on   = rd_i32(buf + 4);
    int32_t n    = rd_i32(buf + 8);
    if (kind != 4) return -1;

    if (on == 0 || n <= 0 || len < 16 + n * 8) {
        g_tag_on = 0; g_tag_len = 0; g_cur_tag = 0; g_cur_fluid_tag = 0;
        return 0;
    }
    int32_t copy_n = n < MAX_STATES ? n : MAX_STATES;
    const uint8_t *blk_src = buf + 16;
    const uint8_t *fld_src = buf + 16 + n * 4;
    for (int32_t i = 0; i < copy_n; i++) {
        A->tag_block[i] = rd_i32(blk_src + i * 4);
        A->tag_fluid[i] = rd_i32(fld_src + i * 4);
    }
    g_tag_on = 1;
    g_tag_len = copy_n;
    return 0;
}

MK_EXPORT("wasm_run_job")
int32_t wasm_run_job(mk_ptr_t ptr, int32_t len) {
    if (!g_inited) wasm_init();
    if (len < 64) return DECLINE_OVERFLOW;

    const uint8_t *buf = MK_PTR_TO_BYTES(ptr);
    int32_t job_id    = rd_i32(buf + 4);
    int32_t job_epoch = rd_i32(buf + 8);
    int32_t flags     = rd_i32(buf + 12);
    int32_t halo      = rd_i32(buf + 56);
    if (halo == 0) halo = 18;

    g_H = halo;
    g_HH = halo * halo;
    g_PAD = (halo - 16) >> 1;
    for (int d = 0; d < 6; d++) {
        g_dir_step[d] = STEP_X[d] + STEP_Y[d] * g_HH + STEP_Z[d] * g_H;
    }

    int32_t cells = halo * halo * halo;
    int32_t extra_at = 64 + cells * 3;
    int32_t extra_bytes = rd_i32(buf + 52);
    if (halo < 18 || halo > 24 || (halo & 1) != 0 || extra_bytes < 0 || extra_at + extra_bytes > len) {
        for (int i = 0; i < 16; i++) A->out_buf[i] = 0;
        A->out_buf[0] = 2; A->out_buf[1] = job_id; A->out_buf[2] = job_epoch; A->out_buf[3] = 1;
        return DECLINE_OVERFLOW;
    }

    g_states = (const uint16_t *)(buf + 64);
    g_light  = buf + 64 + cells * 2;
    g_ex_i   = (const int32_t *)(buf + extra_at);
    g_ex_len = extra_bytes >> 2;
    g_ao     = (flags & 1) != 0;
    g_declined = 0;

    if (job_epoch != g_epoch) g_declined = DECLINE_EPOCH_OR_HALO;
    if (g_ao && g_PAD < 2)    g_declined = DECLINE_EPOCH_OR_HALO;

    int32_t ox = rd_i32(buf + 40);
    int32_t oy = rd_i32(buf + 44);
    int32_t oz = rd_i32(buf + 48);
    for (int i = 0; i < 6; i++) {
        g_cardinal[i] = rd_f32(buf + 16 + i * 4);
        g_gray_by_dir[i] = make_gray(g_cardinal[i]);
    }

    if (g_extra_used_n > 0) {
        for (int32_t i = 0; i < g_extra_used_n; i++) {
            A->cell_extra[A->extra_used[i]] = -1;
        }
        g_extra_used_n = 0;
    }
    for (int32_t i = 0; i < g_ex_len; ) {
        int32_t w = g_ex_i[i];
        int32_t ex_flags = (w >> 16) & 255;
        int32_t colours  = (int32_t)(((uint32_t)w >> 24) & 255u);
        int32_t cell_idx = w & 65535;
        if (cell_idx < 4096) {
            if (A->cell_extra[cell_idx] == -1) A->extra_used[g_extra_used_n++] = cell_idx;
            A->cell_extra[cell_idx] = i;
        }
        i += 1 + ((ex_flags & EX_OFFSET) ? 3 : 0) + ((ex_flags & EX_SEED) ? 2 : 0) + colours;
    }

    g_out_n[0] = 0; g_out_n[1] = 0; g_out_n[2] = 0;
    if (g_sprite_count > 0) {
        for (int32_t i = 0; i < g_sprite_count; i++) {
            uint16_t sid = A->sprite_list[i];
            if (sid < MAX_SPRITES) A->sprite_bits[sid >> 5] = 0u;
        }
        g_sprite_count = 0;
    }
    mk_zero_bytes(A->vis_bits, 4096);

    int32_t opaque = 0;
    if (!g_declined) {
        for (int32_t z = 0; z < 16 && !g_declined; z++) {
            for (int32_t y = 0; y < 16 && !g_declined; y++) {
                int32_t cell = ((y + g_PAD) * g_H + (z + g_PAD)) * g_H + g_PAD;
                for (int32_t x = 0; x < 16; x++, cell++) {
                    uint16_t sid = g_states[cell];
                    if (!A->s_known[sid]) {
                        g_declined = DECLINE_UNKNOWN_STATE;
                        break;
                    }
                    int32_t fl = A->s_flags[sid];
                    if (fl & F_AIR) continue;
                    if (fl & F_SOLID_RENDER) {
                        A->vis_bits[x | (y << 8) | (z << 4)] = 1;
                        opaque++;
                    }
                    if (A->s_fluid_group[sid] != 0) {
                        tesselate_fluid(sid, cell, x, y, z);
                    }
                    if (!g_declined && (fl & F_MODEL)) {
                        tesselate_block(sid, cell, x, y, z, ox, oy, oz);
                    }
                    if (g_declined) break;
                }
            }
        }
    }

    if (g_declined) {
        for (int i = 0; i < 16; i++) A->out_buf[i] = 0;
        A->out_buf[0] = 2; A->out_buf[1] = job_id; A->out_buf[2] = job_epoch; A->out_buf[3] = 1;
        return g_declined;
    }

    int32_t vis_out[2];
    vis_resolve(opaque, vis_out);

    int32_t animated = g_sprite_count;
    int32_t n0 = g_out_n[0], n1 = g_out_n[1], n2 = g_out_n[2];
    int32_t total_words = 16 + animated + n0 + n1 + n2;
    if (total_words > OUT_BUF_WORDS) {
        for (int i = 0; i < 16; i++) A->out_buf[i] = 0;
        A->out_buf[0] = 2; A->out_buf[1] = job_id; A->out_buf[2] = job_epoch; A->out_buf[3] = 1;
        return DECLINE_OVERFLOW;
    }

    /* Pack exact 64-byte header */
    A->out_buf[0]  = 2;
    A->out_buf[1]  = job_id;
    A->out_buf[2]  = job_epoch;
    A->out_buf[3]  = 0;
    A->out_buf[4]  = vis_out[0];
    A->out_buf[5]  = vis_out[1];
    A->out_buf[6]  = n0 * 4;
    A->out_buf[7]  = n1 * 4;
    A->out_buf[8]  = n2 * 4;
    A->out_buf[9]  = animated;
    A->out_buf[10] = 0;
    A->out_buf[11] = 0; /* host JS wrapper fills timing micros */
    A->out_buf[12] = 0;
    A->out_buf[13] = 0;
    A->out_buf[14] = 0;
    A->out_buf[15] = 0;

    int32_t p = 16;
    for (int32_t i = 0; i < animated; i++) {
        A->out_buf[p++] = (int32_t)A->sprite_list[i];
    }
    /* Shift SOLID left from SOLID_PREFIX_WORDS -> p (dst <= src, forward SIMD copy is safe) */
    if (n0 > 0) {
        mk_copy_i32_fwd(A->out_buf + p, A->out_buf + SOLID_PREFIX_WORDS, (uint32_t)n0);
        p += n0;
    }
    if (n1 > 0) {
        mk_copy_i32_fwd(A->out_buf + p, A->cutout_buf, (uint32_t)n1);
        p += n1;
    }
    if (n2 > 0) {
        mk_copy_i32_fwd(A->out_buf + p, A->transl_buf, (uint32_t)n2);
        p += n2;
    }

    return p * 4;
}
