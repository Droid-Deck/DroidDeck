/*
 * Steam Native Hook-up fast path: direct access to DroidDeck's shared hook-up file (".hookup").
 *
 * DroidDeck passes the file to the bridge as a file descriptor (Android lets an app use a descriptor another
 * app hands it, only opening the other app's file by path is forbidden). The bridge then writes every HID
 * report of a hooked-up controller straight into the slot's input FIFO and wakes Steam's reader with a futex,
 * and takes Steam's output reports / feature reads from the slot itself -- no Java, no Binder message, no
 * polling on either side. DroidDeck still owns the slot (identity, sysfs, present flag); while the bridge
 * serves a slot it keeps the slot's direct_ms heartbeat fresh and DroidDeck leaves that slot's FIFOs alone.
 *
 * Plain C (no SDL / JNI) so it can be tested on Linux against the real fakeinput hook.
 * Layout must match HookupSlot in DroidDeck's hookup_native.inc and HookupBridge.kt.
 */
#pragma once

#include <limits.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>

enum {
    HKS_MAGIC = 0x4b4f4f48,
    HKS_HEADER = 4096,
    HKS_SLOT = 53248,
    HKS_SLOTS = 4,
    HKS_SIZE = HKS_HEADER + HKS_SLOTS * HKS_SLOT,
    /* slot fields */
    HKS_PRESENT = 0,
    HKS_VENDOR = 8,
    HKS_PRODUCT = 10,
    HKS_IN_WRITE = 344,
    HKS_OUT_WRITE = 352,
    HKS_OUT_READ = 360,
    HKS_GET_REQUEST = 368,
    HKS_GET_REPLY = 376,
    HKS_GET_REPORT_ID = 384,
    HKS_GET_LENGTH = 388,
    HKS_GET = 392,
    HKS_DIRECT_MS = 520,
    HKS_RATE_HZ = 528,     /* the bridge's poll rate: Steam's side hands Steam at most this many reports a second */
    HKS_IN = 5120,
    HKS_OUT = 41984,
    HKS_IN_FIFO = 256,
    HKS_OUT_FIFO = 64,
    HKS_ENTRY = 144,
    HKS_REPORT_MAX = 128
};

#define HKS_FUTEX_WAKE 1   /* shared (not FUTEX_PRIVATE_FLAG): the waiter is Steam, another process */

typedef struct {
    uint8_t *base;
    int fd;
    uint64_t ino;
} HkShm;

typedef struct {
    uint32_t kind;   /* 0 output report, 1 feature report */
    int len;
    uint8_t data[HKS_REPORT_MAX];
} HksOut;

static inline uint64_t hks_now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);   /* = Android's SystemClock.uptimeMillis() clock */
    return (uint64_t)t.tv_sec * 1000u + (uint64_t)t.tv_nsec / 1000000u;
}

/* Read time of an input report for DroidDeck's latency meter: CLOCK_MONOTONIC in us, low 32 bits, never 0. */
static inline uint32_t hks_stamp_us(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    uint32_t us = (uint32_t)((uint64_t)t.tv_sec * 1000000u + (uint64_t)t.tv_nsec / 1000u);
    return us ? us : 1;
}

static inline uint8_t *hks_slot(const HkShm *m, int i) { return m->base + HKS_HEADER + (size_t)i * HKS_SLOT; }
static inline uint64_t *hks_u64(uint8_t *s, int off) { return (uint64_t *)(void *)(s + off); }
static inline uint32_t *hks_u32(uint8_t *s, int off) { return (uint32_t *)(void *)(s + off); }

/* Maps the file behind fd (takes ownership of fd). 0 = ok, <0 = why not. */
static inline int hks_map(HkShm *m, int fd)
{
    struct stat st;
    if (fd < 0) return -1;
    if (fstat(fd, &st) != 0 || st.st_size < HKS_SIZE) { close(fd); return -2; }
    void *p = mmap(NULL, HKS_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) { close(fd); return -3; }
    if (__atomic_load_n((uint32_t *)p, __ATOMIC_ACQUIRE) != HKS_MAGIC) { munmap(p, HKS_SIZE); close(fd); return -4; }
    m->base = (uint8_t *)p;
    m->fd = fd;
    m->ino = (uint64_t)st.st_ino;
    return 0;
}

static inline void hks_unmap(HkShm *m)
{
    if (m->base) munmap(m->base, HKS_SIZE);
    if (m->fd >= 0) close(m->fd);
    m->base = NULL;
    m->fd = -1;
    m->ino = 0;
}

/* Still DroidDeck's live file? (A new DroidDeck session deletes it and makes a new one.) */
static inline int hks_alive(const HkShm *m)
{
    struct stat st;
    return m->base && fstat(m->fd, &st) == 0 && st.st_nlink > 0;
}

/* Does DroidDeck show this controller in slot i right now? Only then may the bridge serve the slot. */
static inline int hks_owns(const HkShm *m, int i, uint16_t vid, uint16_t pid)
{
    if (!m->base || i < 0 || i >= HKS_SLOTS) return 0;
    uint8_t *s = hks_slot(m, i);
    if (!__atomic_load_n(hks_u32(s, HKS_PRESENT), __ATOMIC_ACQUIRE)) return 0;
    uint16_t v, p;
    memcpy(&v, s + HKS_VENDOR, 2);
    memcpy(&p, s + HKS_PRODUCT, 2);
    return v == vid && p == pid;
}

/* "The bridge serves this slot": DroidDeck and Steam's side read it (stale after ~1.5 s). */
static inline void hks_heartbeat(const HkShm *m, int i, uint64_t now_ms)
{
    __atomic_store_n(hks_u64(hks_slot(m, i), HKS_DIRECT_MS), now_ms, __ATOMIC_RELEASE);
}

/* The rate Steam's side hands this slot's reports to Steam (the bridge's Poll rate setting; 0 = every report). */
static inline void hks_set_rate(const HkShm *m, int i, uint32_t hz)
{
    __atomic_store_n(hks_u32(hks_slot(m, i), HKS_RATE_HZ), hz, __ATOMIC_RELEASE);
}

/* One input report (controller -> Steam): same protocol as HookupBridge.input(), then wake Steam's reader. */
static inline void hks_write_in(const HkShm *m, int i, const uint8_t *data, int n)
{
    uint8_t *s = hks_slot(m, i);
    uint64_t *write = hks_u64(s, HKS_IN_WRITE);
    uint64_t idx = __atomic_load_n(write, __ATOMIC_RELAXED);
    uint8_t *e = s + HKS_IN + (size_t)(idx % HKS_IN_FIFO) * HKS_ENTRY;
    if (n < 0) n = 0;
    if (n > HKS_REPORT_MAX) n = HKS_REPORT_MAX;
    __atomic_store_n(hks_u64(e, 0), 0, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(hks_u32(e, 8), (uint32_t)n, __ATOMIC_RELAXED);
    __atomic_store_n(hks_u32(e, 12), hks_stamp_us(), __ATOMIC_RELAXED);   /* when the bridge read it: latency meter */
    memcpy(e + 16, data, (size_t)n);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(hks_u64(e, 0), idx + 1, __ATOMIC_RELEASE);
    __atomic_store_n(write, idx + 1, __ATOMIC_RELEASE);
    /* Steam's reader sleeps on the low half of in_write (little endian). */
    syscall(SYS_futex, (uint32_t *)(void *)write, HKS_FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

/* Takes up to max queued reports Steam wrote (rumble, lights, settings). */
static inline int hks_take_out(const HkShm *m, int i, HksOut *out, int max)
{
    uint8_t *s = hks_slot(m, i);
    uint64_t read = __atomic_load_n(hks_u64(s, HKS_OUT_READ), __ATOMIC_ACQUIRE);
    uint64_t written = __atomic_load_n(hks_u64(s, HKS_OUT_WRITE), __ATOMIC_ACQUIRE);
    int n = 0;
    while (read < written && n < max) {
        uint8_t *e = s + HKS_OUT + (size_t)(read % HKS_OUT_FIFO) * HKS_ENTRY;
        if (__atomic_load_n(hks_u64(e, 0), __ATOMIC_ACQUIRE) != read + 1) break;   /* still being written */
        uint32_t len = __atomic_load_n(hks_u32(e, 8), __ATOMIC_RELAXED);
        if (len > HKS_REPORT_MAX) len = HKS_REPORT_MAX;
        out[n].len = (int)len;
        out[n].kind = __atomic_load_n(hks_u32(e, 12), __ATOMIC_RELAXED);
        memcpy(out[n].data, e + 16, len);
        n++;
        read++;
    }
    __atomic_store_n(hks_u64(s, HKS_OUT_READ), read, __ATOMIC_RELEASE);
    return n;
}

/* A feature / input report read Steam is waiting for? (1 = yes; *last remembers what was taken.) */
static inline int hks_get_pending(const HkShm *m, int i, uint64_t *last, uint64_t *request, int *report_id,
                                  int *input, int *length)
{
    uint8_t *s = hks_slot(m, i);
    uint64_t r = __atomic_load_n(hks_u64(s, HKS_GET_REQUEST), __ATOMIC_ACQUIRE);
    if (r == *last || r == __atomic_load_n(hks_u64(s, HKS_GET_REPLY), __ATOMIC_ACQUIRE)) return 0;
    *last = r;
    uint32_t word = __atomic_load_n(hks_u32(s, HKS_GET_REPORT_ID), __ATOMIC_RELAXED);
    int32_t len;
    memcpy(&len, s + HKS_GET_LENGTH, 4);
    *request = r;
    *report_id = (int)(word & 0xff);
    *input = (int)((word >> 8) & 1);
    *length = len < 1 ? 1 : len > HKS_REPORT_MAX ? HKS_REPORT_MAX : len;
    return 1;
}

/* Answer to that read (n <= 0: it failed). */
static inline void hks_get_reply(const HkShm *m, int i, uint64_t request, const uint8_t *data, int n)
{
    uint8_t *s = hks_slot(m, i);
    if (n < 0 || !data) n = 0;
    if (n > HKS_REPORT_MAX) n = HKS_REPORT_MAX;
    if (n) memcpy(s + HKS_GET, data, (size_t)n);
    int32_t len = n;
    __atomic_store_n((int32_t *)(void *)(s + HKS_GET_LENGTH), len, __ATOMIC_RELAXED);
    __atomic_store_n(hks_u64(s, HKS_GET_REPLY), request, __ATOMIC_RELEASE);
}
