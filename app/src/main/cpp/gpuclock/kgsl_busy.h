#ifndef DROIDDECK_KGSL_BUSY_H
#define DROIDDECK_KGSL_BUSY_H
// kgsl lets any client map its memstore read-only (KGSL_PROP_DEVICE_SHADOW). The CP writes a ringbuffer's
// start-of-pipe timestamp there before each submission's command buffers and its end-of-pipe timestamp after them
// (adreno_gen7_ringbuffer.c gen7_ringbuffer_addcmds), so start != end means that ringbuffer has work on the GPU.
// Ringbuffer entries start at KGSL_MEMSTORE_MAX: entries - 2 - 4 levels, or entries - 1 - 4 on kernels without the
// LPAC slot. Entries - 6 .. entries - 2 cover both; a context entry in that range follows the same start/end rule.
#include <stddef.h>
#include <stdint.h>

#define KGSL_BUSY_ENTRY_WORDS 10
#define KGSL_BUSY_SOP 0
#define KGSL_BUSY_EOP 2
#define KGSL_BUSY_SLOTS 5

static inline int kgsl_busy_first_slot(size_t bytes) {
    size_t entries = bytes / (KGSL_BUSY_ENTRY_WORDS * sizeof(uint32_t));
    return entries < 8 ? -1 : (int)(entries - 6);
}

// 1 when a watched slot has a submission between its start and end timestamps. *moved is set when a start
// timestamp differs from last[], which then holds the new ones.
static inline int kgsl_busy_sample(const volatile uint32_t *memstore, int first, uint32_t last[KGSL_BUSY_SLOTS], int *moved) {
    int busy = 0;
    for (int i = 0; i < KGSL_BUSY_SLOTS; i++) {
        const volatile uint32_t *entry = memstore + (size_t)(first + i) * KGSL_BUSY_ENTRY_WORDS;
        uint32_t sop = entry[KGSL_BUSY_SOP], eop = entry[KGSL_BUSY_EOP];
        if (sop != eop) busy = 1;
        if (sop != last[i]) {
            *moved = 1;
            last[i] = sop;
        }
    }
    return busy;
}

#endif
