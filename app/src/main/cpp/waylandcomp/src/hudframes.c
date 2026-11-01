#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "hudframes.h"

/* Layout written by gamescope patch 0125 (tools/gamescope/patches). */
#define HUD_FRAMES_MAGIC 0x46484444u
#define HUD_FRAMES_HEADER 128u
#define HUD_FRAMES_ENTRY 16u

struct hud_frames {
    const uint8_t *map;
    size_t size;
    uint64_t seen;
};

static uint32_t load32(const uint8_t *p) { return __atomic_load_n((const uint32_t *)p, __ATOMIC_ACQUIRE); }
static uint64_t load64(const uint8_t *p) { return __atomic_load_n((const uint64_t *)p, __ATOMIC_ACQUIRE); }

struct hud_frames *hud_frames_open(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    struct stat st;
    void *map = MAP_FAILED;
    if (fstat(fd, &st) == 0 && st.st_size >= (off_t)HUD_FRAMES_HEADER)
        map = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return NULL;
    struct hud_frames *h = calloc(1, sizeof(*h));
    if (!h) {
        munmap(map, (size_t)st.st_size);
        return NULL;
    }
    h->map = map;
    h->size = (size_t)st.st_size;
    return h;
}

void hud_frames_close(struct hud_frames *h) {
    if (!h) return;
    munmap((void *)h->map, h->size);
    free(h);
}

static uint32_t capacity(const struct hud_frames *h) {
    if (load32(h->map) != HUD_FRAMES_MAGIC) return 0;
    uint32_t cap;
    memcpy(&cap, h->map + 8, 4);
    if (cap == 0 || HUD_FRAMES_HEADER + (size_t)cap * HUD_FRAMES_ENTRY > h->size) return 0;
    return cap;
}

int hud_frames_poll(struct hud_frames *h, int64_t *frametimes, int64_t *times, int max) {
    uint32_t cap = capacity(h);
    if (!cap || max <= 0) return -1;
    uint64_t count = load64(h->map + 16);
    if (count < h->seen) h->seen = 0;
    uint64_t from = h->seen;
    if (count - from > cap) from = count - cap;
    if (count - from > (uint64_t)max) from = count - (uint64_t)max;
    int n = 0;
    for (uint64_t i = from; i < count; i++, n++) {
        const uint8_t *e = h->map + HUD_FRAMES_HEADER + (i % cap) * HUD_FRAMES_ENTRY;
        memcpy(&frametimes[n], e, 8);
        memcpy(&times[n], e + 8, 8);
    }
    /* The writer may have lapped the ring while we copied: drop what it overwrote. */
    uint64_t after = load64(h->map + 16);
    uint64_t valid = after > cap ? after - cap : 0;
    if (from < valid) {
        int skip = (int)(valid - from < (uint64_t)n ? valid - from : (uint64_t)n);
        memmove(frametimes, frametimes + skip, (size_t)(n - skip) * sizeof(int64_t));
        memmove(times, times + skip, (size_t)(n - skip) * sizeof(int64_t));
        n -= skip;
    }
    h->seen = count;
    return n;
}

int hud_frames_info(const struct hud_frames *h, int32_t *info, char *engine, size_t engine_len) {
    if (!capacity(h) || engine_len == 0) return 0;
    for (int tries = 0; tries < 8; tries++) {
        uint32_t seq = load32(h->map + 24);
        if (seq & 1u) continue;
        memcpy(&info[0], h->map + 12, 4);
        memcpy(&info[1], h->map + 28, 16);
        size_t n = engine_len - 1 < 40 ? engine_len - 1 : 40;
        memcpy(engine, h->map + 44, n);
        engine[n] = '\0';
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (__atomic_load_n((const uint32_t *)(h->map + 24), __ATOMIC_RELAXED) == seq) return 1;
    }
    return 0;
}
