/* minecraft-web by chiikabu (https://github.com/chiikabu)
 *
 * ogvp_v4_encode.c — Native C99 OGVP-V4 split-pack encoder & mandatory 100% SHA-256 verifier.
 *
 * Usage:
 *   ogvp_v4_encode <manifest.tsv> <out_meta.bin> <out_audio.bin>
 *
 * manifest.tsv lines: <40-hex-sha1>\t<absolute-file-path>\n
 *
 * Produces two outputs:
 *   1) out_meta.bin (designed to be XZ-compressed inside p-rest):
 *        magic "OGVP4M01" (8B)
 *        num_setups (u32 LE), num_entries (u32 LE), prior_size (u32 LE)
 *        for each setup: ident_len (u16), setup_len (u16), ident[], setup[], prior[P4_TOTAL]
 *        for each entry: sha1_raw (20B), orig_len (u32), audio_off (u32), audio_len (u32),
 *                        meta_len (u32), split_meta[meta_len]
 *   2) out_audio.bin (raw CABAC-coded Vorbis streams — NOT XZ-compressed, lazy decoded!):
 *        concatenated compressed audio blobs at [audio_off .. audio_off + audio_len)
 *
 * Every single encoded sound is immediately decoded and verified 100.000% SHA-256 bit-exact!
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include "ogvp_v4_core.h"

/* Built-in SHA-256 for mandatory bit-exact verification */
static const uint32_t K256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};
static inline uint32_t rr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }
static void sha256_buf(const uint8_t *data, size_t len, uint8_t out[32]) {
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint64_t bl = (uint64_t)len * 8;
    size_t pad = ((len + 9 + 63) / 64) * 64;
    uint8_t *b = (uint8_t *)calloc(pad, 1);
    memcpy(b, data, len); b[len] = 0x80;
    for (int i = 0; i < 8; i++) b[pad - 1 - i] = (uint8_t)(bl >> (i * 8));
    for (size_t off = 0; off < pad; off += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = ((uint32_t)b[off+i*4]<<24)|((uint32_t)b[off+i*4+1]<<16)|((uint32_t)b[off+i*4+2]<<8)|b[off+i*4+3];
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rr32(w[i-15],7)^rr32(w[i-15],18)^(w[i-15]>>3);
            uint32_t s1 = rr32(w[i-2],17)^rr32(w[i-2],19)^(w[i-2]>>10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }
        uint32_t a=h[0],bb=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],k=h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rr32(e,6)^rr32(e,11)^rr32(e,25), ch = (e&f)^((~e)&g);
            uint32_t t1 = k + S1 + ch + K256[i] + w[i];
            uint32_t S0 = rr32(a,2)^rr32(a,13)^rr32(a,22), mj = (a&bb)^(a&c)^(bb&c);
            uint32_t t2 = S0 + mj;
            k=g; g=f; f=e; e=d+t1; d=c; c=bb; bb=a; a=t1+t2;
        }
        h[0]+=a; h[1]+=bb; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=k;
    }
    free(b);
    for (int i = 0; i < 8; i++) {
        out[i*4] = h[i]>>24; out[i*4+1] = h[i]>>16; out[i*4+2] = h[i]>>8; out[i*4+3] = h[i];
    }
}

typedef struct { uint8_t htype, nseg, lacing[255]; int64_t granule; uint32_t serial, seqno; int pkts_ending; } PInfo;
typedef struct {
    uint8_t *raw; size_t raw_len; PInfo *pages; int num_pages;
    uint8_t *arena; V4Slice *pkts; int num_pkts;
    int has_cont; uint32_t serial; uint16_t eos_trim;
    uint8_t *ppp; int num_ap; uint8_t *raw_sb; size_t raw_sb_len;
} OggF;

static void oggf_free(OggF *o) {
    free(o->raw); free(o->pages); free(o->arena); free(o->pkts); free(o->ppp); free(o->raw_sb);
    memset(o, 0, sizeof(*o));
}

static int oggf_load(const char *path, OggF *o) {
    memset(o, 0, sizeof(*o));
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    if (sz <= 0) { fclose(f); return -2; }
    o->raw_len = (size_t)sz;
    o->raw = (uint8_t *)malloc((size_t)sz);
    if (!o->raw || fread(o->raw, 1, (size_t)sz, f) != (size_t)sz) { fclose(f); oggf_free(o); return -2; }
    fclose(f);

    size_t pos = 0, ap = 0, cs = 0; int pc = 16, pkc = 256;
    o->pages = (PInfo *)malloc((size_t)pc * sizeof(PInfo));
    o->pkts = (V4Slice *)malloc((size_t)pkc * sizeof(V4Slice));
    o->arena = (uint8_t *)malloc(o->raw_len);
    if (!o->pages || !o->pkts || !o->arena) { oggf_free(o); return -3; }

    while (pos < o->raw_len) {
        if (pos + 27 > o->raw_len || memcmp(o->raw + pos, "OggS", 4) != 0 || o->raw[pos + 4] != 0) {
            oggf_free(o); return -4;
        }
        if (o->num_pages >= pc) { pc *= 2; o->pages = (PInfo *)realloc(o->pages, (size_t)pc * sizeof(PInfo)); }
        PInfo *pg = &o->pages[o->num_pages++];
        pg->htype = o->raw[pos + 5];
        memcpy(&pg->granule, o->raw + pos + 6, 8);
        memcpy(&pg->serial, o->raw + pos + 14, 4);
        memcpy(&pg->seqno, o->raw + pos + 18, 4);
        pg->nseg = o->raw[pos + 26];
        if (pos + 27 + pg->nseg > o->raw_len) { oggf_free(o); return -4; }
        memcpy(pg->lacing, o->raw + pos + 27, pg->nseg);
        pg->pkts_ending = 0;
        if (pg->htype & 1) o->has_cont = 1;
        size_t bp = pos + 27 + pg->nseg;
        for (int i = 0; i < pg->nseg; i++) {
            uint8_t l = pg->lacing[i];
            if (bp + l > o->raw_len) { oggf_free(o); return -4; }
            memcpy(o->arena + ap, o->raw + bp, l);
            ap += l; bp += l;
            if (l < 255) {
                if (o->num_pkts >= pkc) { pkc *= 2; o->pkts = (V4Slice *)realloc(o->pkts, (size_t)pkc * sizeof(V4Slice)); }
                o->pkts[o->num_pkts].ptr = o->arena + cs;
                o->pkts[o->num_pkts].len = (uint32_t)(ap - cs);
                o->num_pkts++; cs = ap; pg->pkts_ending++;
            }
        }
        pos = bp;
    }
    if (o->num_pages < 3 || o->num_pkts < 4 || o->pages[0].pkts_ending != 1 || o->pages[1].pkts_ending != 2) {
        oggf_free(o); return -5;
    }
    o->serial = o->pages[0].serial;
    o->num_ap = o->num_pages - 2;
    o->ppp = (uint8_t *)malloc((size_t)(o->num_ap + 1));
    for (int i = 0; i < o->num_ap; i++) o->ppp[i] = (uint8_t)o->pages[i + 2].pkts_ending;
    if (o->has_cont) {
        size_t sbsz = 0; for (int i = 2; i < o->num_pages; i++) sbsz += 2 + o->pages[i].nseg;
        o->raw_sb = (uint8_t *)malloc(sbsz); size_t sp = 0;
        for (int i = 2; i < o->num_pages; i++) {
            o->raw_sb[sp++] = o->pages[i].htype; o->raw_sb[sp++] = o->pages[i].nseg;
            memcpy(o->raw_sb + sp, o->pages[i].lacing, o->pages[i].nseg); sp += o->pages[i].nseg;
        }
        o->raw_sb_len = sbsz;
    }
    return 0;
}

static size_t emit_pg(uint8_t *out, uint8_t ht, int64_t gr, uint32_t ser, uint32_t seq,
                      const uint8_t *lac, uint8_t ns, const uint8_t *body, size_t bl) {
    memcpy(out, "OggS", 4); out[4] = 0; out[5] = ht;
    memcpy(out + 6, &gr, 8); memcpy(out + 14, &ser, 4); memcpy(out + 18, &seq, 4); memset(out + 22, 0, 4);
    out[26] = ns; memcpy(out + 27, lac, ns); memcpy(out + 27 + ns, body, bl);
    size_t tot = 27 + (size_t)ns + bl; uint32_t crc = v4_crc32(out, tot); memcpy(out + 22, &crc, 4);
    return tot;
}

static size_t rebuild_ogg(
    const V4Setup *s, const uint8_t *id, size_t il, const uint8_t *cm, size_t cl, const uint8_t *su, size_t sl,
    const uint8_t *aa, const uint32_t *al, int nap, uint32_t ser, uint16_t trim,
    int npg, const uint8_t *ppp, int has_cont, const uint8_t *rsb, uint8_t *out
) {
    size_t pos = 0; uint8_t lac[255];
    { uint8_t ns = 0; size_t r = il; while (r >= 255) { lac[ns++] = 255; r -= 255; } lac[ns++] = (uint8_t)r;
      pos += emit_pg(out + pos, 0x02, 0, ser, 0, lac, ns, id, il); }
    { uint8_t ns = 0; size_t r = cl; while (r >= 255) { lac[ns++] = 255; r -= 255; } lac[ns++] = (uint8_t)r;
      r = sl; while (r >= 255) { lac[ns++] = 255; r -= 255; } lac[ns++] = (uint8_t)r;
      uint8_t *hb = (uint8_t *)malloc(cl + sl); memcpy(hb, cm, cl); memcpy(hb + cl, su, sl);
      pos += emit_pg(out + pos, 0x00, 0, ser, 1, lac, ns, hb, cl + sl); free(hb); }
    int64_t *pg = (int64_t *)malloc((size_t)(nap + 1) * 8);
    size_t *po = (size_t *)malloc((size_t)(nap + 1) * sizeof(size_t));
    int pbs = -1; int64_t cg = 0; size_t ao = 0; uint32_t mask = (1u << s->mode_bits) - 1u;
    for (int i = 0; i < nap; i++) {
        po[i] = ao; int md = (aa[ao] >> 1) & (int)mask; int cbs = s->blocksize[s->mode_bf[md]];
        if (pbs >= 0) cg += (pbs + cbs) >> 2; pbs = cbs; pg[i] = cg; ao += al[i];
    }
    if (!has_cont) {
        int cp = 0;
        for (int p = 0; p < npg; p++) {
            int np = ppp[p]; uint8_t ns = 0; size_t bs = po[cp], bl = 0;
            for (int k = 0; k < np; k++) { size_t r = al[cp + k]; bl += r; while (r >= 255) { lac[ns++] = 255; r -= 255; } lac[ns++] = (uint8_t)r; }
            int eos = (p == npg - 1);
            int64_t gr = pg[cp + np - 1] - (eos ? (int64_t)trim : 0);
            pos += emit_pg(out + pos, eos ? 0x04 : 0x00, gr, ser, (uint32_t)(p + 2), lac, ns, aa + bs, bl);
            cp += np;
        }
    } else {
        size_t sp = 0, ac = 0; int cp = 0;
        for (int p = 0; p < npg; p++) {
            uint8_t ht = rsb[sp++], ns = rsb[sp++]; const uint8_t *lc = rsb + sp; sp += ns;
            size_t bl = 0; int ed = 0; for (int i = 0; i < ns; i++) { bl += lc[i]; if (lc[i] < 255) ed++; }
            int eos = (p == npg - 1);
            int64_t gr = (ed == 0) ? -1 : (pg[(cp += ed) - 1] - (eos ? (int64_t)trim : 0));
            pos += emit_pg(out + pos, ht, gr, ser, (uint32_t)(p + 2), lc, ns, aa + ac, bl); ac += bl;
        }
    }
    free(pg); free(po);
    return pos;
}

static int hx(uint8_t c) { return (c>='0'&&c<='9')?(c-'0'):((c>='a'&&c<='f')?(c-'a'+10):((c>='A'&&c<='F')?(c-'A'+10):-1)); }
static size_t pack_comm(const uint8_t *cp, size_t cl, uint8_t *out) {
    static const char P[] =
        "\x03vorbis\x0c\x00\x00\x00Lavf61.1.100\x03\x00\x00\x00"
        "\x1e\x00\x00\x00" "encoder=Lavc61.3.100 libvorbis"
        "\x0e\x00\x00\x00" "mj_e=\"ogg_aq4\""
        "\x47\x00\x00\x00" "mj_s=\"";
    if (cl == 155 && memcmp(cp, P, 89) == 0 && cp[153] == '"' && cp[154] == 1) {
        uint8_t b32[32]; int ok = 1;
        for (int i = 0; i < 32; i++) { int a = hx(cp[89+i*2]), b = hx(cp[89+i*2+1]); if (a<0||b<0) { ok=0; break; } b32[i]=(uint8_t)((a<<4)|b); }
        if (ok) { out[0] = 1; memcpy(out + 1, b32, 32); return 33; }
    }
    out[0] = 0; out[1] = (uint8_t)(cl & 255); out[2] = (uint8_t)((cl >> 8) & 255);
    memcpy(out + 3, cp, cl); return 3 + cl;
}

typedef struct {
    uint8_t sha[32]; uint8_t *id; size_t il; uint8_t *su; size_t sl;
    V4Setup vs; uint32_t tz[P4_TOTAL], to[P4_TOTAL]; uint8_t pri[P4_TOTAL];
} SE4;
#define MAX_SETUPS 64
static SE4 g_se[MAX_SETUPS];
static int g_nse = 0;

static int add_setup(const uint8_t *id, size_t il, const uint8_t *su, size_t sl) {
    uint8_t h[32]; sha256_buf(su, sl, h);
    for (int i = 0; i < g_nse; i++)
        if (memcmp(g_se[i].sha, h, 32) == 0 && g_se[i].il == il && memcmp(g_se[i].id, id, il) == 0) return i;
    if (g_nse >= MAX_SETUPS) return -1;
    int k = g_nse++;
    memset(&g_se[k], 0, sizeof(SE4));
    memcpy(g_se[k].sha, h, 32);
    g_se[k].id = (uint8_t *)malloc(il); memcpy(g_se[k].id, id, il); g_se[k].il = il;
    g_se[k].su = (uint8_t *)malloc(sl); memcpy(g_se[k].su, su, sl); g_se[k].sl = sl;
    if (v4_setup_parse(&g_se[k].vs, id, il, su, sl) != 0) { g_nse--; return -1; }
    return k;
}

typedef struct {
    uint8_t sha1[20];
    char path[1024];
    int sid;
    uint32_t orig_len, audio_off, audio_len, meta_len;
    uint8_t *meta_buf;
} Entry;

static void w_u16(FILE *f, uint16_t v) { uint8_t b[2] = { (uint8_t)(v & 255), (uint8_t)(v >> 8) }; fwrite(b, 1, 2, f); }
static void w_u32(FILE *f, uint32_t v) { uint8_t b[4] = { (uint8_t)(v&255), (uint8_t)((v>>8)&255), (uint8_t)((v>>16)&255), (uint8_t)(v>>24) }; fwrite(b, 1, 4, f); }

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <manifest.tsv> <out_meta.bin> <out_audio.bin>\n", argv[0]);
        return 1;
    }
    v4_crc32_init(); v4_math_init();

    FILE *mf = fopen(argv[1], "r");
    if (!mf) { fprintf(stderr, "Cannot open manifest %s\n", argv[1]); return 1; }
    int cap = 1024, n = 0;
    Entry *entries = (Entry *)calloc((size_t)cap, sizeof(Entry));
    char line[2048];
    while (fgets(line, sizeof(line), mf)) {
        char *tab = strchr(line, '\t');
        if (!tab) continue;
        *tab++ = 0;
        char *nl = strpbrk(tab, "\r\n"); if (nl) *nl = 0;
        if (strlen(line) != 40) continue;
        if (n >= cap) { cap *= 2; entries = (Entry *)realloc(entries, (size_t)cap * sizeof(Entry)); }
        Entry *e = &entries[n];
        memset(e, 0, sizeof(*e));
        int ok = 1;
        for (int i = 0; i < 20; i++) {
            int a = hx((uint8_t)line[i*2]), b = hx((uint8_t)line[i*2+1]);
            if (a < 0 || b < 0) { ok = 0; break; }
            e->sha1[i] = (uint8_t)((a << 4) | b);
        }
        if (!ok) continue;
        strncpy(e->path, tab, sizeof(e->path) - 1);
        n++;
    }
    fclose(mf);

    size_t WCAP = 16 * 1024 * 1024;
    uint8_t *cbuf = (uint8_t *)malloc(WCAP);
    uint8_t *darena = (uint8_t *)malloc(WCAP);
    uint8_t *rogg = (uint8_t *)malloc(WCAP);
    uint32_t *dlens = (uint32_t *)malloc(131072 * 4);
    V4Work *work = (V4Work *)malloc(sizeof(V4Work));
    uint8_t tcm[4096];

    double t0 = (double)clock() / CLOCKS_PER_SEC;
    /* Pass 1: Train corpus priors across all entries */
    for (int i = 0; i < n; i++) {
        OggF o;
        if (oggf_load(entries[i].path, &o) != 0) { fprintf(stderr, "OGG load failed: %s\n", entries[i].path); return 2; }
        int sid = add_setup(o.pkts[0].ptr, o.pkts[0].len, o.pkts[2].ptr, o.pkts[2].len);
        if (sid < 0) { fprintf(stderr, "Setup parse failed: %s\n", entries[i].path); oggf_free(&o); return 3; }
        entries[i].sid = sid;
        entries[i].orig_len = (uint32_t)o.raw_len;
        int nap = o.num_pkts - 3;
        int cl = ogvp_v4_encode(&g_se[sid].vs, o.pkts + 3, nap, NULL, work, cbuf, WCAP, g_se[sid].tz, g_se[sid].to);
        if (cl < 0) { fprintf(stderr, "Pass 1 encode failed: %s\n", entries[i].path); oggf_free(&o); return 4; }
        oggf_free(&o);
    }

    for (int sid = 0; sid < g_nse; sid++) {
        SE4 *se = &g_se[sid];
        for (int c = 0; c < P4_TOTAL; c++) {
            uint64_t tot = (uint64_t)se->tz[c] + se->to[c];
            if (tot < 6) se->pri[c] = 0;
            else {
                uint64_t p = ((uint64_t)se->tz[c] * 256ULL + (tot >> 1)) / tot;
                if (p < 1) p = 1;
                if (p > 255) p = 255;
                se->pri[c] = (uint8_t)p;
            }
        }
    }
    double t1 = (double)clock() / CLOCKS_PER_SEC;

    /* Pass 2: Encode with priors, SHA-256 verify 100%, write out_audio.bin & collect split meta */
    FILE *fa = fopen(argv[3], "wb");
    if (!fa) { fprintf(stderr, "Cannot open %s\n", argv[3]); return 5; }
    uint64_t total_orig = 0, total_audio = 0, total_meta_entries = 0;
    uint32_t audio_cursor = 0;

    for (int i = 0; i < n; i++) {
        OggF o;
        if (oggf_load(entries[i].path, &o) != 0) return 2;
        SE4 *se = &g_se[entries[i].sid];
        int nap = o.num_pkts - 3;

        /* Compute deterministic eos_trim */
        int pbs = -1; int64_t cg = 0; uint32_t mask = (1u << se->vs.mode_bits) - 1u;
        for (int k = 0; k < nap; k++) {
            int md = (o.pkts[3 + k].ptr[0] >> 1) & (int)mask;
            int cbs = se->vs.blocksize[se->vs.mode_bf[md]];
            if (pbs >= 0) cg += (pbs + cbs) >> 2;
            pbs = cbs;
        }
        int64_t eos_g = o.pages[o.num_pages - 1].granule;
        int64_t trim64 = cg - eos_g;
        if (trim64 < 0 || trim64 > 65535) { fprintf(stderr, "Unexpected eos_trim=%lld on %s\n", (long long)trim64, entries[i].path); oggf_free(&o); return 6; }
        uint16_t trim = (uint16_t)trim64;

        int cl = ogvp_v4_encode(&se->vs, o.pkts + 3, nap, se->pri, work, cbuf, WCAP, NULL, NULL);
        if (cl < 0) { fprintf(stderr, "Pass 2 encode failed: %s\n", entries[i].path); oggf_free(&o); return 7; }

        /* Mandatory 100% SHA-256 bit-exact roundtrip verification */
        int db = ogvp_v4_decode(&se->vs, cbuf, (size_t)cl, nap, se->pri, work, darena, WCAP, dlens);
        if (db < 0) { fprintf(stderr, "Decode failed on %s (%d)\n", entries[i].path, db); oggf_free(&o); return 8; }
        size_t rlen = rebuild_ogg(&se->vs, se->id, se->il, o.pkts[1].ptr, o.pkts[1].len, se->su, se->sl,
                                  darena, dlens, nap, o.serial, trim, o.num_ap, o.ppp, o.has_cont, o.raw_sb, rogg);
        uint8_t h_orig[32], h_reb[32];
        sha256_buf(o.raw, o.raw_len, h_orig);
        sha256_buf(rogg, rlen, h_reb);
        if (rlen != o.raw_len || memcmp(h_orig, h_reb, 32) != 0) {
            fprintf(stderr, "SHA-256 MISMATCH on %s (orig=%zu rebuilt=%zu)\n", entries[i].path, o.raw_len, rlen);
            oggf_free(&o);
            return 9;
        }

        /* Pack split metadata record */
        size_t cm_len = pack_comm(o.pkts[1].ptr, o.pkts[1].len, tcm);
        size_t ml = 16 + (o.has_cont ? (4 + o.raw_sb_len) : (size_t)o.num_ap) + cm_len;
        uint8_t *mb = (uint8_t *)malloc(ml);
        mb[0] = (uint8_t)entries[i].sid;
        mb[1] = (uint8_t)(o.has_cont ? 1 : 0);
        memcpy(mb + 2, &o.serial, 4);
        memcpy(mb + 6, &trim, 2);
        uint32_t nap_u = (uint32_t)nap, npg_u = (uint32_t)o.num_ap;
        memcpy(mb + 8, &nap_u, 4);
        memcpy(mb + 12, &npg_u, 4);
        size_t mp = 16;
        if (!o.has_cont) {
            memcpy(mb + mp, o.ppp, (size_t)o.num_ap); mp += (size_t)o.num_ap;
        } else {
            uint32_t rl = (uint32_t)o.raw_sb_len;
            memcpy(mb + mp, &rl, 4); mp += 4;
            memcpy(mb + mp, o.raw_sb, o.raw_sb_len); mp += o.raw_sb_len;
        }
        memcpy(mb + mp, tcm, cm_len); mp += cm_len;

        entries[i].audio_off = audio_cursor;
        entries[i].audio_len = (uint32_t)cl;
        entries[i].meta_len = (uint32_t)mp;
        entries[i].meta_buf = mb;
        fwrite(cbuf, 1, (size_t)cl, fa);
        audio_cursor += (uint32_t)cl;

        total_orig += o.raw_len;
        total_audio += (uint64_t)cl;
        total_meta_entries += mp;
        oggf_free(&o);
    }
    fclose(fa);

    /* Write out_meta.bin */
    FILE *fm = fopen(argv[2], "wb");
    if (!fm) { fprintf(stderr, "Cannot open %s\n", argv[2]); return 10; }
    fwrite("OGVP4M01", 1, 8, fm);
    w_u32(fm, (uint32_t)g_nse);
    w_u32(fm, (uint32_t)n);
    w_u32(fm, (uint32_t)P4_TOTAL);
    for (int sid = 0; sid < g_nse; sid++) {
        w_u16(fm, (uint16_t)g_se[sid].il);
        w_u16(fm, (uint16_t)g_se[sid].sl);
        fwrite(g_se[sid].id, 1, g_se[sid].il, fm);
        fwrite(g_se[sid].su, 1, g_se[sid].sl, fm);
        fwrite(g_se[sid].pri, 1, P4_TOTAL, fm);
    }
    for (int i = 0; i < n; i++) {
        fwrite(entries[i].sha1, 1, 20, fm);
        w_u32(fm, entries[i].orig_len);
        w_u32(fm, entries[i].audio_off);
        w_u32(fm, entries[i].audio_len);
        w_u32(fm, entries[i].meta_len);
        fwrite(entries[i].meta_buf, 1, entries[i].meta_len, fm);
        free(entries[i].meta_buf);
    }
    fclose(fm);

    double t2 = (double)clock() / CLOCKS_PER_SEC;
    printf("[OGVP-V4] SHA-256 verified %d/%d files (100%% bit-exact, %d setups): %.2f MB -> %.2f MB audio + %.2f MB raw meta (pass1=%.1fs, pass2=%.1fs)\n",
           n, n, g_nse, total_orig / 1e6, total_audio / 1e6, total_meta_entries / 1e6, t1 - t0, t2 - t1);

    free(entries); free(cbuf); free(darena); free(rogg); free(dlens); free(work);
    return 0;
}
