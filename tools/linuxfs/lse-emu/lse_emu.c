// SPDX-License-Identifier: GPL-3.0-or-later
// https://github.com/SildurFX/LSE-Emu
// Copyright (C) 2026 SildurFX

#define _GNU_SOURCE
#include <signal.h>
#include <ucontext.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

#ifndef HWCAP_ATOMICS
#define HWCAP_ATOMICS (1UL << 8)
#endif
#ifndef SA_RESTORER
#define SA_RESTORER 0x04000000
#endif
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

/* ======================= raw syscalls (no PLT in handler) ================= */

static long sc(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 asm("x8") = n,  x0 asm("x0") = a, x1 asm("x1") = b;
    register long x2 asm("x2") = c,  x3 asm("x3") = d, x4 asm("x4") = e, x5 asm("x5") = f;
    asm volatile("svc 0" : "+r"(x0)
                 : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                 : "memory", "cc");
    return x0;
}

/* ======================= slow path: emulation ============================= */

#define DEFINE_RMW(NAME, UT, ST)                                              \
static UT NAME(void *p, unsigned op, UT v) {                                  \
    UT *q = (UT *)p;                                                          \
    switch (op) {                                                             \
    case 0: return __atomic_fetch_add(q, v, __ATOMIC_SEQ_CST);                \
    case 1: return __atomic_fetch_and(q, (UT)~v, __ATOMIC_SEQ_CST);           \
    case 2: return __atomic_fetch_xor(q, v, __ATOMIC_SEQ_CST);                \
    case 3: return __atomic_fetch_or (q, v, __ATOMIC_SEQ_CST);                \
    case 8: return __atomic_exchange_n(q, v, __ATOMIC_SEQ_CST);               \
    }                                                                         \
    UT old = __atomic_load_n(q, __ATOMIC_RELAXED), nw;                        \
    do {                                                                      \
        switch (op) {                                                         \
        case 4:  nw = ((ST)old > (ST)v) ? old : v; break;                     \
        case 5:  nw = ((ST)old < (ST)v) ? old : v; break;                     \
        case 6:  nw = (old > v) ? old : v; break;                             \
        default: nw = (old < v) ? old : v; break;                             \
        }                                                                     \
    } while (!__atomic_compare_exchange_n(q, &old, nw, 1,                     \
                                          __ATOMIC_SEQ_CST, __ATOMIC_RELAXED));\
    return old;                                                               \
}
DEFINE_RMW(rmw8,  uint8_t,  int8_t)
DEFINE_RMW(rmw16, uint16_t, int16_t)
DEFINE_RMW(rmw32, uint32_t, int32_t)
DEFINE_RMW(rmw64, uint64_t, int64_t)

#define DEFINE_CAS(NAME, UT)                                                  \
static UT NAME(void *p, UT exp, UT des) {                                     \
    UT e = exp;                                                               \
    __atomic_compare_exchange_n((UT *)p, &e, des, 0,                          \
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);          \
    return e;                                                                 \
}
DEFINE_CAS(cas8,  uint8_t)
DEFINE_CAS(cas16, uint16_t)
DEFINE_CAS(cas32, uint32_t)
DEFINE_CAS(cas64, uint64_t)

static void casp128(void *p, uint64_t *o0, uint64_t *o1,
                    uint64_t e0, uint64_t e1, uint64_t d0, uint64_t d1) {
    uint64_t a, b, t, s;
    asm volatile(
        "1: ldaxp %0, %1, [%6]\n"
        "   eor   %2, %0, %7\n"
        "   eor   %3, %1, %8\n"
        "   orr   %2, %2, %3\n"
        "   cbnz  %2, 2f\n"
        "   stlxp %w2, %9, %10, [%6]\n"
        "   cbnz  %w2, 1b\n"
        "2: clrex\n"
        : "=&r"(a), "=&r"(b), "=&r"(t), "=&r"(s)
        : "0"(0), "1"(0), "r"(p), "r"(e0), "r"(e1), "r"(d0), "r"(d1)
        : "memory", "cc");
    *o0 = a; *o1 = b;
}

static int emulate(ucontext_t *uc, uint32_t i) {
    uint64_t *x = (uint64_t *)uc->uc_mcontext.regs;
    unsigned rt = i & 31, rn = (i >> 5) & 31, rs = (i >> 16) & 31;
    unsigned size = i >> 30;
    void *p = (void *)(rn == 31 ? uc->uc_mcontext.sp : x[rn]);
#define RD(r)    ((r) >= 31 ? 0 : x[r])
#define WR(r, v) do { if ((r) < 31) x[r] = (v); } while (0)

    if ((i & 0x3f200c00) == 0x38200000) {            // LD<op>, SWP, LDAPR
        unsigned o3 = (i >> 15) & 1, opc = (i >> 12) & 7, op;
        uint64_t v, old;
        if (!o3)            op = opc;
        else if (opc == 0)  op = 8;
        else if (opc == 4 && rs == 31 &&
                 (i & (1u << 23)) && !(i & (1u << 22))) {
            switch (size) {
            case 0:  v = __atomic_load_n((uint8_t  *)p, __ATOMIC_ACQUIRE); break;
            case 1:  v = __atomic_load_n((uint16_t *)p, __ATOMIC_ACQUIRE); break;
            case 2:  v = __atomic_load_n((uint32_t *)p, __ATOMIC_ACQUIRE); break;
            default: v = __atomic_load_n((uint64_t *)p, __ATOMIC_ACQUIRE); break;
            }
            WR(rt, v);
            return 1;
        } else return 0;
        v = RD(rs);
        switch (size) {
        case 0:  old = rmw8 (p, op, (uint8_t)v);  break;
        case 1:  old = rmw16(p, op, (uint16_t)v); break;
        case 2:  old = rmw32(p, op, (uint32_t)v); break;
        default: old = rmw64(p, op, v);           break;
        }
        WR(rt, old);
        return 1;
    }
    if ((i & 0x3fa07c00) == 0x08a07c00) {            // CAS
        uint64_t e = RD(rs), d = RD(rt), old;
        switch (size) {
        case 0:  old = cas8 (p, (uint8_t)e,  (uint8_t)d);  break;
        case 1:  old = cas16(p, (uint16_t)e, (uint16_t)d); break;
        case 2:  old = cas32(p, (uint32_t)e, (uint32_t)d); break;
        default: old = cas64(p, e, d);                     break;
        }
        WR(rs, old);
        return 1;
    }
    if ((i & 0xbfa07c00) == 0x08207c00) {            // CASP
        if ((rs & 1) || (rt & 1)) return 0;
        uint64_t e0 = RD(rs), e1 = RD(rs + 1), d0 = RD(rt), d1 = RD(rt + 1);
        if (size == 0) {
            uint64_t e = (uint32_t)e0 | ((uint64_t)(uint32_t)e1 << 32);
            uint64_t d = (uint32_t)d0 | ((uint64_t)(uint32_t)d1 << 32);
            uint64_t o = cas64(p, e, d);
            WR(rs, (uint32_t)o); WR(rs + 1, o >> 32);
        } else {
            uint64_t o0, o1;
            casp128(p, &o0, &o1, e0, e1, d0, d1);
            WR(rs, o0); WR(rs + 1, o1);
        }
        return 1;
    }
    return 0;
}

/* ======================= fast path: stub generator ======================== */

#define STP2(a,b)  (0xA9BE0000u | ((b) << 10) | (31u << 5) | (a))  // stp a,b,[sp,#-32]!
#define LDP2(a,b)  (0xA8C20000u | ((b) << 10) | (31u << 5) | (a))  // ldp a,b,[sp],#32
#define STR16(c)   (0xF9000800u | (31u << 5) | (c))                // str c,[sp,#16]
#define LDR16(c)   (0xF9400800u | (31u << 5) | (c))                // ldr c,[sp,#16]
#define MOVX(d,m)  (0xAA0003E0u | ((m) << 16) | (d))               // mov xd,xm
#define W(x)       do { uint32_t _v = (x); w[n++] = _v; } while (0)

static uint32_t ldx(unsigned sz, unsigned acq, unsigned rt, unsigned rn) {
    return (sz << 30) | 0x085F7C00u | (acq << 15) | (rn << 5) | rt;
}
static uint32_t stx(unsigned sz, unsigned rel, unsigned rs, unsigned rt, unsigned rn) {
    return (sz << 30) | 0x08007C00u | (rs << 16) | (rel << 15) | (rn << 5) | rt;
}
static uint32_t alu(unsigned op, unsigned d, unsigned n, unsigned m) {
    static const uint32_t base[4] = { 0x8B000000u, 0x8A200000u, 0xCA000000u, 0xAA000000u };
    return base[op] | (m << 16) | (n << 5) | d;          // add, bic, eor, orr
}

// CASP{A}{L} Xs,Xs+1,Xt,Xt+1,[Xn] (64-bit pair): ldxp/ldaxp + compare + stxp/stlxp. Four scratch registers, no flag changes, 15 words.
static int gen_casp(uint32_t *w, uintptr_t stub, uintptr_t ret, unsigned sz,
                    unsigned rs, unsigned rt, unsigned rn, unsigned acq, unsigned rel) {
    // sz 1: 64-bit pair (LDXP x), sz 0: 32-bit pair (LDXP w, 8-byte aligned)
    const uint32_t LDXP = sz ? 0xC87F0000u : 0x887F0000u, STXP = sz ? 0xC8200000u : 0x88200000u;
    const uint32_t EOR  = sz ? 0xCA000000u : 0x4A000000u, ORR  = sz ? 0xAA000000u : 0x2A000000u;
    unsigned t[4], k = 0; int n = 0;
    for (unsigned r = 9; r <= 17 && k < 4; r++)
        if (r != rs && r != rs + 1 && r != rt && r != rt + 1 && r != rn) t[k++] = r;
    if (k < 4) return 0;
    unsigned t0 = t[0], t1 = t[1], t2 = t[2], t3 = t[3];
    W(STP2(t0, t1));
    W(0xA9010000u | (t3 << 10) | (31u << 5) | t2);                          // stp t2,t3,[sp,#16]
    int loop = n;
    W(LDXP | (acq << 15) | (t1 << 10) | (rn << 5) | t0);                    // ldxp/ldaxp t0,t1,[rn]
    W(EOR | (rs << 16) | (t0 << 5) | t2);                                   // eor t2,t0,rs
    W(EOR | ((rs + 1) << 16) | (t1 << 5) | t3);                             // eor t3,t1,rs+1
    W(ORR | (t3 << 16) | (t2 << 5) | t2);                                   // orr t2,t2,t3
    W(0xB5000000u | (3u << 5) | t2);                                        // cbnz t2, clrex (+3)
    W(STXP | (t2 << 16) | (rel << 15) | ((rt + 1) << 10) | (rn << 5) | rt); // stxp/stlxp w(t2),rt,rt+1,[rn]
    W(0x35000000u | (((unsigned)(loop - n) & 0x7FFFF) << 5) | t2);          // cbnz w(t2), loop
    W(0xD5033F5Fu);                                                         // clrex
    W(MOVX(rs, t0));
    W(MOVX(rs + 1, t1));
    W(0xA9410000u | (t3 << 10) | (31u << 5) | t2);                          // ldp t2,t3,[sp,#16]
    W(LDP2(t0, t1));
    { int64_t off = (int64_t)ret - (int64_t)(stub + 4 * (uintptr_t)n);
      W(0x14000000u | (((uint32_t)(off >> 2)) & 0x03FFFFFFu)); }
    return n;
}

// Builds the stub for one instruction. Returns word count, 0 if unsupported.
static int gen(uint32_t *w, uintptr_t stub, uintptr_t ret, uint32_t i) {
    unsigned size = i >> 30, rt = i & 31, rn = (i >> 5) & 31, rs = (i >> 16) & 31;
    unsigned kind, op = 0, acq, rel;                     // kind: 0 ldop, 1 swp, 2 cas, 3 casp, 4 min/max
    int n = 0;

    if ((i & 0x3FFFFC00u) == 0x38BFC000u) {              // LDAPR[B|H]: stub = LDAR[B|H] (same operands, stronger ordering) ; b ret
        W((i & 0xC0000000u) | 0x08DFFC00u | (i & 0x3FFu));
        { int64_t off = (int64_t)ret - (int64_t)(stub + 4 * (uintptr_t)n);
          W(0x14000000u | (((uint32_t)(off >> 2)) & 0x03FFFFFFu)); }
        return n;
    }
    if ((i & 0x3f200c00) == 0x38200000) {
        unsigned o3 = (i >> 15) & 1, opc = (i >> 12) & 7;
        if (!o3 && opc <= 3) { kind = 0; op = opc; }
        else if (!o3) { kind = 4; op = opc; }            // LDSMAX LDSMIN LDUMAX LDUMIN (opc 4..7)
        else if (o3 && opc == 0) kind = 1;
        else return 0;
        acq = (i >> 23) & 1; rel = (i >> 22) & 1;
    } else if ((i & 0x3fa07c00) == 0x08a07c00) {
        kind = 2; acq = (i >> 22) & 1; rel = (i >> 15) & 1;
    } else if ((i & 0xbfa07c00) == 0x08207c00) {         // CASP, 32-bit (size 0) and 64-bit (size 1) pairs
        if (size > 1 || (rs & 1) || (rt & 1) || rs >= 30 || rt >= 30) return 0;
        kind = 3; acq = (i >> 22) & 1; rel = (i >> 15) & 1;
    } else return 0;
    if (rn == 31) return 0;                              // sp-based: leave to trap path
    if (kind == 3) return gen_casp(w, stub, ret, size, rs, rt, rn, acq, rel);

    unsigned t[3], k = 0;                                // three scratch regs from x9..x17
    for (unsigned r = 9; r <= 17 && k < 3; r++)
        if (r != rs && r != rt && r != rn) t[k++] = r;
    unsigned t0 = t[0], t1 = t[1], t2 = t[2];

    W(STP2(t0, t1));
    W(STR16(t2));
    int loop = n;
    W(ldx(size, acq, t0, rn));                           // t0 = old
    if (kind == 1) {
        W(stx(size, rel, t2, rs, rn));
        W(0x35000000u | (((unsigned)(loop - n) & 0x7FFFF) << 5) | t2);
    } else if (kind == 4) {                              // LDSMAX/LDSMIN/LDUMAX/LDUMIN
        static const unsigned cc[4] = { 0xC /*gt*/, 0xB /*lt*/, 0x8 /*hi*/, 0x3 /*lo*/ };
        unsigned sgn = !(op & 2), sf = (size == 3), cmpn = t0;
        if (size < 2 && sgn) {                           // sign-extend the loaded value: sxtb/sxth wt1,wt0
            W((size == 1 ? 0x13003C00u : 0x13001C00u) | (t0 << 5) | t1);
            cmpn = t1;
        }
        if (size < 2)                                    // cmp wcmpn, wrs, {u,s}xt{b,h}
            W(0x6B20001Fu | (rs << 16) | ((sgn ? (size == 1 ? 5u : 4u) : (size == 1 ? 1u : 0u)) << 13) | (cmpn << 5));
        else                                             // cmp t0, rs
            W((sf ? 0xEB00001Fu : 0x6B00001Fu) | (rs << 16) | (t0 << 5));
        W((sf ? 0x9A800000u : 0x1A800000u) | (rs << 16) | (cc[op - 4] << 12) | (t0 << 5) | t1);   // csel t1,t0,rs,cond
        W(stx(size, rel, t2, t1, rn));
        W(0x35000000u | (((unsigned)(loop - n) & 0x7FFFF) << 5) | t2);
    } else if (kind == 0) {
        W(alu(op, t1, t0, rs));                          // t1 = new
        W(stx(size, rel, t2, t1, rn));
        W(0x35000000u | (((unsigned)(loop - n) & 0x7FFFF) << 5) | t2);
    } else {
        if (size == 3)      W(0xCA000000u | (rs << 16) | (t0 << 5) | t1);
        else if (size == 2) W(0x4A000000u | (rs << 16) | (t0 << 5) | t1);   // 32-bit eor
        else {
            W((size == 1 ? 0x53003C00u : 0x53001C00u) | (rs << 5) | t1);    // uxth/uxtb
            W(0xCA000000u | (t1 << 16) | (t0 << 5) | t1);
        }
        W(0xB5000000u | (3u << 5) | t1);                 // cbnz t1, fail (+3)
        W(stx(size, rel, t2, rt, rn));
        W(0x35000000u | (((unsigned)(loop - n) & 0x7FFFF) << 5) | t2);
        W(0xD5033F5Fu);                                  // fail: clrex
    }
    if (kind == 2) { if (rs != 31) W(MOVX(rs, t0)); }
    else if (rt != 31) W(MOVX(rt, t0));
    W(LDR16(t2));
    W(LDP2(t0, t1));
    { int64_t off = (int64_t)ret - (int64_t)(stub + 4 * (uintptr_t)n);
      W(0x14000000u | (((uint32_t)(off >> 2)) & 0x03FFFFFFu)); }
    return n;
}

/* ---- stub arenas (must sit within +-128 MiB of the patched code) ---- */

#define ARENA_SZ   0x10000
#define SLOT       64
#define MAX_ARENAS 256
#define REACH      0x7000000          // 112 MiB, safely inside the 128 MiB branch range
static struct { uintptr_t base, used; } arena[MAX_ARENAS];
static volatile int narena;

static int near_(uintptr_t b, uintptr_t pc) {
    intptr_t d = (intptr_t)b - (intptr_t)pc;
    return d > -(intptr_t)REACH && d < (intptr_t)REACH;
}
static int in_arena(uintptr_t a) {
    int n = __atomic_load_n(&narena, __ATOMIC_ACQUIRE);  // pairs with the release store in alloc_slot
    for (int k = 0; k < n; k++)
        if (a >= arena[k].base && a < arena[k].base + ARENA_SZ) return 1;
    return 0;
}
static unsigned long hexv(const char **p);

//Find a free 64 KiB-aligned hole within REACH of pc by walking /proc/self/maps called with the patch lock held, so the static buffers are safe
static uintptr_t find_gap(uintptr_t pc, const uintptr_t *tr, int ntr) {
    static char buf[4096], line[512];
    long fd = sc(SYS_openat, -100, (long)"/proc/self/maps", O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return 0;
    uintptr_t lo = pc > (uintptr_t)REACH + 0x100000 ? pc - REACH : 0x100000;
    uintptr_t hi = pc + REACH;
    uintptr_t prev = 0, best = 0, bestd = ~(uintptr_t)0;
    int ll = 0; long n;
    while ((n = sc(SYS_read, fd, (long)buf, sizeof buf, 0, 0, 0)) > 0)
        for (long k = 0; k < n; k++) {
            char c = buf[k];
            if (c != '\n') { if (ll < 511) line[ll++] = c; continue; }
            line[ll] = 0; ll = 0;
            const char *p = line;
            uintptr_t s0 = hexv(&p); if (*p != '-') continue; p++;
            uintptr_t e0 = hexv(&p);
            uintptr_t g0 = prev > lo ? prev : lo;
            g0 = (g0 + 0xFFFF) & ~(uintptr_t)0xFFFF;
            uintptr_t g1 = (s0 < hi ? s0 : hi) & ~(uintptr_t)0xFFFF;
            if (g1 > g0 && g1 - g0 >= ARENA_SZ) {
                uintptr_t rlo = g0, rhi = g1 - ARENA_SZ;          // valid block start range
                uintptr_t cand = pc & ~(uintptr_t)0xFFFF;
                if (cand < rlo) cand = rlo;
                if (cand > rhi) cand = rhi;
                // skip blocks a previous mmap refused, walking outwards inside this hole
                for (int st = 0; st < 16; st++) {
                    uintptr_t pick = 0; int found = 0;
                    for (int w = 0; w < 2 && !found; w++) {
                        if (w == 1 && st == 0) continue;
                        uintptr_t c;
                        if (w == 0) { if (cand + (uintptr_t)st * ARENA_SZ > rhi) continue; c = cand + (uintptr_t)st * ARENA_SZ; }
                        else        { if (cand < rlo + (uintptr_t)st * ARENA_SZ) continue;  c = cand - (uintptr_t)st * ARENA_SZ; }
                        int is_bad = 0;
                        for (int t = 0; t < ntr; t++) if (tr[t] == c) is_bad = 1;
                        if (!is_bad) { pick = c; found = 1; }
                    }
                    if (found) {
                        uintptr_t d = pick > pc ? pick - pc : pc - pick;
                        if (d < bestd) { bestd = d; best = pick; }
                        break;
                    }
                }
            }
            if (e0 > prev) prev = e0;
        }
    sc(SYS_close, fd, 0, 0, 0, 0, 0);
    return best;
}

static uintptr_t alloc_slot(uintptr_t pc) {
    for (int k = 0; k < narena; k++)
        if (arena[k].used + SLOT <= ARENA_SZ && near_(arena[k].base, pc)) {
            uintptr_t s = arena[k].base + arena[k].used;
            arena[k].used += SLOT;
            return s;
        }
    if (narena >= MAX_ARENAS) return 0;
    // free hole nearest to the site, found by walking /proc/self/maps
    uintptr_t tried[16]; int ntried = 0;                 // blocks mmap refused, don't offer them again
    for (int attempt = 0; attempt < 12; attempt++) {
        uintptr_t hint = find_gap(pc, tried, ntried);
        if (!hint) return 0;
        long r = sc(SYS_mmap, hint, ARENA_SZ, PROT_READ | PROT_WRITE | PROT_EXEC,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if ((unsigned long)r > (unsigned long)-4096) { tried[ntried++] = hint; continue; }
        if ((uintptr_t)r != hint) {                      //kernel without MAP_FIXED_NOREPLACE moved it
            sc(SYS_munmap, r, ARENA_SZ, 0, 0, 0, 0);
            tried[ntried++] = hint; continue;
        }
        arena[narena].base = (uintptr_t)r;
        arena[narena].used = SLOT;
        int idx = narena;
        __atomic_store_n(&narena, idx + 1, __ATOMIC_RELEASE);   // publish the slot only after base/used are written
        return arena[idx].base;
    }
    return 0;
}

/* ---- known executable file-backed ranges (parsed from /proc/self/maps) ---- */

#define MAX_RANGES 512
static struct { uintptr_t lo, hi; } rng[MAX_RANGES];
static volatile int nrng;
static int misses;

static int in_ranges(uintptr_t pc) {
    for (int k = 0; k < nrng; k++) if (pc >= rng[k].lo && pc < rng[k].hi) return 1;
    return 0;
}
static unsigned long hexv(const char **p) {
    unsigned long v = 0;
    for (;; (*p)++) {
        char c = **p; int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else break;
        v = (v << 4) | d;
    }
    return v;
}
static void parse_line(const char *p) {
    unsigned long lo = hexv(&p); if (*p != '-') return; p++;
    unsigned long hi = hexv(&p); if (*p != ' ') return; p++;
    if (!(p[0] == 'r' && p[1] == '-' && p[2] == 'x' && p[3] == 'p')) return;
    for (int f = 0; f < 4 && *p; f++) { while (*p && *p != ' ') p++; while (*p == ' ') p++; }
    if (*p != '/') return;
    if (nrng < MAX_RANGES) { rng[nrng].lo = lo; rng[nrng].hi = hi; nrng = nrng + 1; }
}
static void refresh_ranges(void) {
    static char buf[4096], line[512];
    long fd = sc(SYS_openat, -100, (long)"/proc/self/maps", O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return;
    nrng = 0;
    int ll = 0; long n;
    while ((n = sc(SYS_read, fd, (long)buf, sizeof buf, 0, 0, 0)) > 0)
        for (long k = 0; k < n; k++) {
            char c = buf[k];
            if (c == '\n') { line[ll] = 0; parse_line(line); ll = 0; }
            else if (ll < 511) line[ll++] = c;
        }
    sc(SYS_close, fd, 0, 0, 0, 0, 0);
}

/* ---- debug log (LSE_EMU_LOG=1) ---- */

static int add_str(char *b, int n, const char *s);
static int add_dec(char *b, int n, unsigned long v);

static volatile int logon;
static char logpath[200] = "/tmp/lse_emu.log";
static unsigned long st_traps, st_patched, st_failed;

static void logwrite(const char *b, int n) {            // raw syscalls, O_APPEND: safe from the handler
    long fd = sc(SYS_openat, -100, (long)logpath, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0666, 0, 0);
    if (fd < 0) return;
    sc(SYS_write, fd, (long)b, n, 0, 0, 0);
    sc(SYS_close, fd, 0, 0, 0, 0, 0);
}
static int add_hex(char *b, int n, uint64_t v, int digits) {
    for (int k = (digits - 1) * 4; k >= 0; k -= 4) b[n++] = "0123456789abcdef"[(v >> k) & 15];
    return n;
}
// One line per site, patched is logged as it happens, failures are de-duplicated.
static void logsite(const char *why, uintptr_t pc, uint32_t insn) {
    if (!logon) return;
    static uintptr_t seen[256]; static volatile int ns;
    if (why[0] != 'p') {                                  // only "patched" is logged every time
        for (int k = 0; k < ns; k++) if (seen[k] == pc) return;
        if (ns >= 256) return;
        seen[ns] = pc; ns = ns + 1;
    }
    char b[160]; int n = 0;
    n = add_str(b, n, "pid="); n = add_dec(b, n, (unsigned long)sc(SYS_getpid, 0, 0, 0, 0, 0, 0));
    n = add_str(b, n, " ");    n = add_str(b, n, why);
    n = add_str(b, n, " pc=0x");   n = add_hex(b, n, pc, 16);
    n = add_str(b, n, " insn=0x"); n = add_hex(b, n, insn, 8);
    b[n++] = '\n';
    logwrite(b, n);
}
static void logstats(const char *tag) {
    if (!logon) return;
    char b[160]; int n = 0;
    n = add_str(b, n, "pid="); n = add_dec(b, n, (unsigned long)sc(SYS_getpid, 0, 0, 0, 0, 0, 0));
    n = add_str(b, n, " ");    n = add_str(b, n, tag);
    n = add_str(b, n, " traps=");   n = add_dec(b, n, st_traps);
    n = add_str(b, n, " patched="); n = add_dec(b, n, st_patched);
    n = add_str(b, n, " failed=");  n = add_dec(b, n, st_failed);
    n = add_str(b, n, " arenas=");  n = add_dec(b, n, (unsigned long)narena);
    b[n++] = '\n';
    logwrite(b, n);
}

/* ---- patching ---- */

static volatile int plock, patch_enabled, stats;
static uintptr_t bad[256];

// Try-lock, a handler that interrupts try_patch on the same thread and traps again must never spin on a lock it already owns. A skipped site is simply retried on its next trap.
static int  trylock(void) { return !__atomic_exchange_n(&plock, 1, __ATOMIC_ACQUIRE); }
static void unlock(void)  { __atomic_store_n(&plock, 0, __ATOMIC_RELEASE); }

// Overwrite one instruction word with a branch (single aligned release store), then flush the icache.
static int patch_word(uintptr_t pc, uint32_t v) {
    uintptr_t page = pc & ~(uintptr_t)4095;
    if (sc(SYS_mprotect, page, 4096, PROT_READ | PROT_WRITE | PROT_EXEC, 0, 0, 0)) return -1;
    __atomic_store_n((uint32_t *)pc, v, __ATOMIC_RELEASE);
    __builtin___clear_cache((char *)pc, (char *)pc + 4);
    sc(SYS_mprotect, page, 4096, PROT_READ | PROT_EXEC, 0, 0, 0);
    return 0;
}

static void try_patch(uintptr_t pc, uint32_t insn) {
    uint32_t w[16];
    if (bad[(pc >> 2) & 255] == pc) return;
    if (!gen(w, 0, 0, insn)) { logsite("unsupported-form", pc, insn); return; }   // not a patchable form

    if (!trylock()) return;
    if (*(volatile uint32_t *)pc != insn) goto out;      // already patched by another thread
    if (!in_ranges(pc)) {
        if (misses++ < 16) refresh_ranges();
        if (!in_ranges(pc)) { logsite("not-file-backed", pc, insn); bad[(pc >> 2) & 255] = pc; goto out; }
    }
    uintptr_t slot = alloc_slot(pc);
    if (!slot) { logsite("no-arena", pc, insn); bad[(pc >> 2) & 255] = pc; st_failed++; goto out; }
    int n = gen(w, slot, pc + 4, insn);

    for (int k = 0; k < n; k++) ((volatile uint32_t *)slot)[k] = w[k];
    __builtin___clear_cache((char *)slot, (char *)slot + 4 * n);

    long off = (long)slot - (long)pc;
    uint32_t b = 0x14000000u | (((uint32_t)(off >> 2)) & 0x03FFFFFFu);
    if (patch_word(pc, b)) {
        logsite("mprotect-failed", pc, insn);
        bad[(pc >> 2) & 255] = pc; st_failed++; goto out;
    }
    st_patched++;
    logsite("patched", pc, insn);
out:
    unlock();
}

/* ======================= libc hooks (unchanged logic) ===================== */

static int  (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
static int  (*real_sigprocmask)(int, const sigset_t *, sigset_t *);
static int  (*real_pthread_sigmask)(int, const sigset_t *, sigset_t *);
static long (*real_syscall)(long, ...);

static void resolve(void) {
    if (!real_sigaction)       real_sigaction       = dlsym(RTLD_NEXT, "sigaction");
    if (!real_sigprocmask)     real_sigprocmask     = dlsym(RTLD_NEXT, "sigprocmask");
    if (!real_pthread_sigmask) real_pthread_sigmask = dlsym(RTLD_NEXT, "pthread_sigmask");
    if (!real_syscall)         real_syscall         = dlsym(RTLD_NEXT, "syscall");
}

static volatile int           active, app_valid;
static void * volatile        app_h;
static volatile unsigned long app_f;
struct ksa { void *h; unsigned long f; void *restorer; uint64_t mask; };

int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    resolve();
    if (!active || sig != SIGILL) return real_sigaction(sig, act, old);
    if (old) {
        memset(old, 0, sizeof *old);
        if (app_valid) { old->sa_sigaction = app_h; old->sa_flags = (int)(app_f & ~(unsigned long)SA_RESTORER); }
        else old->sa_handler = SIG_DFL;
    }
    if (act) { app_h = (void *)act->sa_sigaction; app_f = (unsigned long)act->sa_flags; app_valid = 1; }
    return 0;
}

sighandler_t signal(int sig, sighandler_t h) {
    struct sigaction sa, o;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = h; sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    if (sigaction(sig, &sa, &o) < 0) return SIG_ERR;
    return o.sa_handler;
}

int sigprocmask(int how, const sigset_t *set, sigset_t *old) {
    resolve();
    if (active && set && how != SIG_UNBLOCK) {
        sigset_t t = *set; sigdelset(&t, SIGILL);
        return real_sigprocmask(how, &t, old);
    }
    return real_sigprocmask(how, set, old);
}
int pthread_sigmask(int how, const sigset_t *set, sigset_t *old) {
    resolve();
    if (active && set && how != SIG_UNBLOCK) {
        sigset_t t = *set; sigdelset(&t, SIGILL);
        return real_pthread_sigmask(how, &t, old);
    }
    return real_pthread_sigmask(how, set, old);
}

// a process that turns on seccomp must not be patched afterwards
int prctl(int opt, ...) {
    va_list ap; va_start(ap, opt);
    unsigned long a1 = va_arg(ap, unsigned long), a2 = va_arg(ap, unsigned long);
    unsigned long a3 = va_arg(ap, unsigned long), a4 = va_arg(ap, unsigned long);
    va_end(ap);
    resolve();
    if (opt == PR_SET_SECCOMP) patch_enabled = 0;
    return (int)real_syscall(SYS_prctl, (long)opt, a1, a2, a3, a4);
}

long syscall(long n, ...) {
    unsigned long a[6];
    va_list ap; va_start(ap, n);
    for (int k = 0; k < 6; k++) a[k] = va_arg(ap, unsigned long);
    va_end(ap);
    resolve();
    if (active) {
#ifdef SYS_seccomp
        if (n == SYS_seccomp) patch_enabled = 0;
#endif
        if (n == SYS_prctl && a[0] == PR_SET_SECCOMP) patch_enabled = 0;
        if (n == SYS_rt_sigaction && a[0] == SIGILL) {
            struct ksa *act = (struct ksa *)a[1], *old = (struct ksa *)a[2];
            if (old) {
                memset(old, 0, sizeof *old);
                if (app_valid) { old->h = app_h; old->f = app_f; }
            }
            if (act) { app_h = act->h; app_f = act->f; app_valid = 1; }
            return 0;
        }
        if (n == SYS_rt_sigprocmask && a[1] && a[3] == 8 && a[0] != SIG_UNBLOCK) {
            uint64_t m = *(uint64_t *)a[1];
            m &= ~(1ULL << (SIGILL - 1));
            return real_syscall(n, a[0], &m, a[2], a[3], a[4], a[5]);
        }
    }
    return real_syscall(n, a[0], a[1], a[2], a[3], a[4], a[5]);
}

/* ======================= handler ========================================== */

static void put_hex(uint64_t v) {
    char b[18]; b[0] = '0'; b[1] = 'x';
    for (int k = 0; k < 16; k++) b[2 + k] = "0123456789abcdef"[(v >> (60 - 4 * k)) & 15];
    (void)!write(2, b, 18);
}

static void on_sigill(int sig, siginfo_t *si, void *ctx) {
    ucontext_t *uc = (ucontext_t *)ctx;
    uintptr_t pc = uc->uc_mcontext.pc;
    uint32_t insn = __atomic_load_n((uint32_t *)pc, __ATOMIC_ACQUIRE);   // pairs with the release store in patch_word

    if ((insn & 0xFC000000u) == 0x14000000u) {           // another thread patched it first
        intptr_t off = ((int32_t)(insn << 6)) >> 4;
        if (in_arena(pc + off)) return;                  // just re-execute the new branch
    }

    if (emulate(uc, insn)) {
        uc->uc_mcontext.pc = pc + 4;
        st_traps++;
        if (patch_enabled) try_patch(pc, insn);
        else if (logon) logsite("no-patch", pc, insn);
        if (logon && !(st_traps & 0x3FFF)) logstats("stats");    // every 16384 traps
        return;
    }

    logsite("unhandled", pc, insn);
    (void)!write(2, "lse_emu: unhandled pc=", 22);
    put_hex(pc);
    (void)!write(2, " insn=", 6);
    put_hex(insn);
    (void)!write(2, "\n", 1);

    void *h = app_h;
    if (app_valid && h != (void *)SIG_DFL && h != (void *)SIG_IGN) {
        if (app_f & SA_SIGINFO) ((void (*)(int, siginfo_t *, void *))h)(sig, si, ctx);
        else                    ((void (*)(int))h)(sig);
        return;
    }
    struct sigaction dfl; memset(&dfl, 0, sizeof dfl);
    dfl.sa_handler = SIG_DFL;
    real_sigaction(SIGILL, &dfl, NULL);
}

/* ======================= init / fini ====================================== */

static int add_str(char *b, int n, const char *s) { while (*s) b[n++] = *s++; return n; }
static int add_dec(char *b, int n, unsigned long v) {
    char t[21]; int k = 0;
    if (!v) t[k++] = '0';
    while (v) { t[k++] = '0' + v % 10; v /= 10; }
    while (k) b[n++] = t[--k];
    return n;
}

static int skipped(void) {                               // LSE_EMU_SKIP=name:name
    const char *s = getenv("LSE_EMU_SKIP");
    if (!s || !*s) return 0;
    char exe[512]; ssize_t r = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (r <= 0) return 0;
    exe[r] = 0;
    char tok[128];
    while (*s) {
        int k = 0;
        while (*s && *s != ':' && k < 127) tok[k++] = *s++;
        tok[k] = 0;
        if (*s == ':') s++;
        if (k && strstr(exe, tok)) return 1;
    }
    return 0;
}

__attribute__((constructor)) static void init(void) {
    resolve();
    if (getauxval(AT_HWCAP) & HWCAP_ATOMICS) return;     // CPU has real LSE
    
    // Exit silently if LSE_EMU is missing or not set to "1"
    const char *emu_env = getenv("LSE_EMU");
    if (!emu_env || emu_env[0] != '1') {
        return; 
    } 

    struct sigaction sa; memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_sigill;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    real_sigaction(SIGILL, &sa, NULL);
    active = 1;

    const char *p = getenv("LSE_EMU_PATCH");
    stats = getenv("LSE_EMU_STATS") != NULL;
    if (p && p[0] == '1' && !skipped()) {
        refresh_ranges();
        patch_enabled = 1;
    }

    const char *lg = getenv("LSE_EMU_LOG");              // =1 -> /tmp/lse_emu.log, =/path -> that file
    if (lg && lg[0] && lg[0] != '0') {
        if (lg[0] == '/') { size_t l = strlen(lg); if (l < sizeof logpath) memcpy(logpath, lg, l + 1); }
        logon = 1;
        char b[700], exe[480]; int n = 0;
        ssize_t r = readlink("/proc/self/exe", exe, sizeof exe - 1);
        exe[r > 0 ? r : 0] = 0;
        n = add_str(b, n, "pid="); n = add_dec(b, n, (unsigned long)getpid());
        n = add_str(b, n, " start patch="); n = add_dec(b, n, (unsigned long)patch_enabled);
        n = add_str(b, n, " exe="); n = add_str(b, n, exe);
        b[n++] = '\n';
        logwrite(b, n);
    }
}

__attribute__((destructor)) static void fini(void) {
    if (!active) return;
    logstats("exit");
    if (!stats) return;
    char b[160]; int n = 0;
    n = add_str(b, n, "lse_emu: pid=");   n = add_dec(b, n, (unsigned long)getpid());
    n = add_str(b, n, " traps=");         n = add_dec(b, n, st_traps);
    n = add_str(b, n, " patched=");       n = add_dec(b, n, st_patched);
    n = add_str(b, n, " failed=");        n = add_dec(b, n, st_failed);
    b[n++] = '\n';
    (void)!write(2, b, n);
}