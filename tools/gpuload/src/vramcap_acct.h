#ifndef VRAMCAP_ACCT_H
#define VRAMCAP_ACCT_H

#include <stdint.h>

static inline uint64_t vramcap_clamp(uint64_t value, uint64_t cap) { return cap && value > cap ? cap : value; }

// Reserves size bytes against cap; false (nothing reserved) when that would pass it.
static inline int vramcap_reserve(uint64_t *used, uint64_t cap, uint64_t size) {
    if (cap && (size > cap || *used > cap - size)) return 0;
    *used += size;
    return 1;
}

static inline void vramcap_release(uint64_t *used, uint64_t size) { *used = size > *used ? 0 : *used - size; }

#endif
