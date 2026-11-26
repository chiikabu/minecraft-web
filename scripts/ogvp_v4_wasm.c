/* minecraft-web by chiikabu (https://github.com/chiikabu)
 *
 * ogvp_v4_wasm.c — Zero-import, zero-libm WebAssembly OGVP-V4 lazy per-sound decoder.
 * Reconstructs 100.000% SHA-256 bit-exact Ogg-Vorbis files on demand from split
 * (XZ-compressed metadata + raw CABAC audio payload) streams.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "ogvp_v4_core.h"

#define W4_MAX_SETUPS 64
static V4Setup g_s[W4_MAX_SETUPS];
static uint8_t *g_id[W4_MAX_SETUPS];
static uint32_t g_il[W4_MAX_SETUPS];
static uint8_t *g_su[W4_MAX_SETUPS];
static uint32_t g_sl[W4_MAX_SETUPS];
static uint8_t *g_pr[W4_MAX_SETUPS];
static int g_ok[W4_MAX_SETUPS];
static V4Work g_work; /* static reusable workspace — zero malloc/free per decode */

static size_t w4_emit_pg(uint8_t *out, size_t out_cap, size_t pos,
                         uint8_t ht, int64_t gr, uint32_t ser, uint32_t seq,
                         const uint8_t *lac, uint8_t ns, const uint8_t *body, size_t bl) {
    size_t tot = 27 + (size_t)ns + bl;
    if (pos + tot > out_cap) return 0;
    uint8_t *p = out + pos;
    memcpy(p, "OggS", 4); p[4] = 0; p[5] = ht;
    memcpy(p + 6, &gr, 8); memcpy(p + 14, &ser, 4); memcpy(p + 18, &seq, 4); memset(p + 22, 0, 4);
    p[26] = ns; memcpy(p + 27, lac, ns); memcpy(p + 27 + ns, body, bl);
    uint32_t crc = v4_crc32(p, tot); memcpy(p + 22, &crc, 4);
    return tot;
}

static size_t w4_unpack_comm(const uint8_t *src, size_t avail, size_t *consumed, uint8_t *out, size_t out_cap) {
    static const char P[] =
        "\x03vorbis\x0c\x00\x00\x00Lavf61.1.100\x03\x00\x00\x00"
        "\x1e\x00\x00\x00" "encoder=Lavc61.3.100 libvorbis"
        "\x0e\x00\x00\x00" "mj_e=\"ogg_aq4\""
        "\x47\x00\x00\x00" "mj_s=\"";
    static const char HEX[] = "0123456789abcdef";
    if (avail < 1) return 0;
    if (src[0] == 1) {
        if (avail < 33 || out_cap < 155) return 0;
        memcpy(out, P, 89);
        for (int i = 0; i < 32; i++) {
            out[89 + i * 2]     = (uint8_t)HEX[(src[1 + i] >> 4) & 15];
            out[89 + i * 2 + 1] = (uint8_t)HEX[src[1 + i] & 15];
        }
        out[153] = '"'; out[154] = 1; *consumed = 33; return 155;
    } else if (src[0] == 0) {
        if (avail < 3) return 0;
        size_t cl = (size_t)src[1] | ((size_t)src[2] << 8);
        if (avail < 3 + cl || cl > out_cap) return 0;
        memcpy(out, src + 3, cl); *consumed = 3 + cl; return cl;
    }
    return 0;
}

__attribute__((export_name("ogvp_prior_size")))
int ogvp_prior_size(void) {
    return P4_TOTAL;
}

__attribute__((export_name("ogvp_init_setup")))
int ogvp_init_setup(int sid, const uint8_t *ident, uint32_t ilen,
                    const uint8_t *setup, uint32_t slen, const uint8_t *prior) {
    v4_crc32_init(); v4_math_init();
    if (sid < 0 || sid >= W4_MAX_SETUPS) return -1;
    if (g_ok[sid]) {
        v4_setup_free(&g_s[sid]);
        free(g_id[sid]); free(g_su[sid]); free(g_pr[sid]);
        g_id[sid] = NULL; g_su[sid] = NULL; g_pr[sid] = NULL; g_ok[sid] = 0;
    }
    g_id[sid] = (uint8_t *)malloc(ilen);
    g_su[sid] = (uint8_t *)malloc(slen);
    if (!g_id[sid] || !g_su[sid]) { free(g_id[sid]); free(g_su[sid]); return -2; }
    memcpy(g_id[sid], ident, ilen); g_il[sid] = ilen;
    memcpy(g_su[sid], setup, slen); g_sl[sid] = slen;
    if (prior) {
        g_pr[sid] = (uint8_t *)malloc(P4_TOTAL);
        if (!g_pr[sid]) { free(g_id[sid]); free(g_su[sid]); return -2; }
        memcpy(g_pr[sid], prior, P4_TOTAL);
    } else {
        g_pr[sid] = NULL;
    }
    int rc = v4_setup_parse(&g_s[sid], ident, ilen, setup, slen);
    if (rc != 0) {
        free(g_id[sid]); free(g_su[sid]); free(g_pr[sid]);
        g_id[sid] = NULL; g_su[sid] = NULL; g_pr[sid] = NULL;
        return rc;
    }
    g_ok[sid] = 1;
    return 0;
}

/* Split meta record layout:
 *   [0]      sid (u8)
 *   [1]      flags (u8: bit0 = has_cont)
 *   [2..5]   serial (u32 LE)
 *   [6..7]   eos_trim (u16 LE)
 *   [8..11]  nap (u32 LE: number of audio packets)
 *   [12..15] npg (u32 LE: number of audio Ogg pages)
 *   [16..]   if !has_cont: ppp[npg] (u8 each)
 *            if  has_cont: rsb_len (u32 LE) + raw_sb[rsb_len]
 *   next:    packed comment (1 + 32 bytes OR 3 + comm_len bytes)
 */
__attribute__((export_name("ogvp_decode_split")))
int ogvp_decode_split(const uint8_t *meta, uint32_t meta_len,
                      const uint8_t *comp, uint32_t clen,
                      uint8_t *out_ogg, uint32_t out_cap) {
    if (meta_len < 16) return -1;
    int sid = meta[0];
    int has_cont = meta[1] & 1;
    if (sid < 0 || sid >= W4_MAX_SETUPS || !g_ok[sid]) return -2;
    uint32_t ser, nap_u, npg_u; uint16_t trim;
    memcpy(&ser, meta + 2, 4);
    memcpy(&trim, meta + 6, 2);
    memcpy(&nap_u, meta + 8, 4);
    memcpy(&npg_u, meta + 12, 4);
    int nap = (int)nap_u, npg = (int)npg_u;
    if (nap <= 0 || npg <= 0 || nap > 131072 || npg > 65536) return -3;

    size_t off = 16;
    const uint8_t *ppp = NULL;
    const uint8_t *rsb = NULL;
    if (!has_cont) {
        if (off + (size_t)npg > meta_len) return -4;
        ppp = meta + off;
        off += (size_t)npg;
    } else {
        if (off + 4 > meta_len) return -4;
        uint32_t rsb_len; memcpy(&rsb_len, meta + off, 4); off += 4;
        if (off + rsb_len > meta_len) return -4;
        rsb = meta + off;
        off += rsb_len;
    }

    uint8_t comm[4096]; size_t comm_cons = 0;
    size_t comm_len = w4_unpack_comm(meta + off, meta_len - off, &comm_cons, comm, sizeof(comm));
    if (comm_len == 0) return -5;

    const V4Setup *s = &g_s[sid];
    uint8_t *arena = (uint8_t *)malloc(out_cap);
    uint32_t *lens = (uint32_t *)malloc((size_t)(nap + 1) * 4);
    if (!arena || !lens) { free(arena); free(lens); return -6; }

    int db = ogvp_v4_decode(s, comp, clen, nap, g_pr[sid], &g_work, arena, out_cap, lens);
    if (db < 0) { free(arena); free(lens); return -7; }

    size_t pos = 0; uint8_t lac[255];
    {
        uint8_t ns = 0; size_t r = g_il[sid];
        while (r >= 255) { lac[ns++] = 255; r -= 255; }
        lac[ns++] = (uint8_t)r;
        size_t w = w4_emit_pg(out_ogg, out_cap, pos, 0x02, 0, ser, 0, lac, ns, g_id[sid], g_il[sid]);
        if (!w) { free(arena); free(lens); return -8; }
        pos += w;
    }
    {
        uint8_t ns = 0; size_t r = comm_len;
        while (r >= 255) { lac[ns++] = 255; r -= 255; }
        lac[ns++] = (uint8_t)r;
        r = g_sl[sid];
        while (r >= 255) { lac[ns++] = 255; r -= 255; }
        lac[ns++] = (uint8_t)r;
        uint8_t *hb = (uint8_t *)malloc(comm_len + g_sl[sid]);
        if (!hb) { free(arena); free(lens); return -6; }
        memcpy(hb, comm, comm_len); memcpy(hb + comm_len, g_su[sid], g_sl[sid]);
        size_t w = w4_emit_pg(out_ogg, out_cap, pos, 0x00, 0, ser, 1, lac, ns, hb, comm_len + g_sl[sid]);
        free(hb);
        if (!w) { free(arena); free(lens); return -8; }
        pos += w;
    }

    int64_t *pg = (int64_t *)malloc((size_t)(nap + 1) * 8);
    size_t *po = (size_t *)malloc((size_t)(nap + 1) * sizeof(size_t));
    if (!pg || !po) { free(pg); free(po); free(arena); free(lens); return -6; }

    int pbs = -1; int64_t cg = 0; size_t ao = 0;
    uint32_t mask = (1u << s->mode_bits) - 1u;
    for (int i = 0; i < nap; i++) {
        po[i] = ao;
        int md = (arena[ao] >> 1) & (int)mask;
        int cbs = s->blocksize[s->mode_bf[md]];
        if (pbs >= 0) cg += (pbs + cbs) >> 2;
        pbs = cbs; pg[i] = cg; ao += lens[i];
    }

    if (!has_cont) {
        int cp = 0;
        for (int p = 0; p < npg; p++) {
            int np = ppp[p]; uint8_t ns = 0; size_t bs = po[cp], bl = 0;
            for (int k = 0; k < np; k++) {
                size_t r = lens[cp + k]; bl += r;
                while (r >= 255) { lac[ns++] = 255; r -= 255; }
                lac[ns++] = (uint8_t)r;
            }
            int eos = (p == npg - 1);
            int64_t gr = pg[cp + np - 1] - (eos ? (int64_t)trim : 0);
            size_t w = w4_emit_pg(out_ogg, out_cap, pos, eos ? 0x04 : 0x00, gr, ser, (uint32_t)(p + 2), lac, ns, arena + bs, bl);
            if (!w) { free(pg); free(po); free(arena); free(lens); return -8; }
            pos += w;
            cp += np;
        }
    } else {
        size_t sp = 0, ac = 0; int cp = 0;
        for (int p = 0; p < npg; p++) {
            uint8_t ht = rsb[sp++], ns = rsb[sp++];
            const uint8_t *lc = rsb + sp; sp += ns;
            size_t bl = 0; int ed = 0;
            for (int i = 0; i < ns; i++) { bl += lc[i]; if (lc[i] < 255) ed++; }
            int eos = (p == npg - 1);
            int64_t gr = (ed == 0) ? -1 : (pg[(cp += ed) - 1] - (eos ? (int64_t)trim : 0));
            size_t w = w4_emit_pg(out_ogg, out_cap, pos, ht, gr, ser, (uint32_t)(p + 2), lc, ns, arena + ac, bl);
            if (!w) { free(pg); free(po); free(arena); free(lens); return -8; }
            pos += w;
            ac += bl;
        }
    }

    free(pg); free(po); free(arena); free(lens);
    return (int)pos;
}
