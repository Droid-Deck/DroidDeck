#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "kgsl_busy.h"
#include "kgsl_layout.h"

#define ENTRIES 819

static uint32_t memstore[ENTRIES * KGSL_BUSY_ENTRY_WORDS];

static void stamp(int entry, uint32_t sop, uint32_t eop) {
    memstore[entry * KGSL_BUSY_ENTRY_WORDS + KGSL_BUSY_SOP] = sop;
    memstore[entry * KGSL_BUSY_ENTRY_WORDS + KGSL_BUSY_EOP] = eop;
}

int main(void) {
    assert(kgsl_busy_first_slot(8 * 4096) == 813);
    assert(kgsl_busy_first_slot(8 * 16384) == 3270);
    assert(kgsl_busy_first_slot(7 * 40) == -1);

    int first = kgsl_busy_first_slot(sizeof(memstore));
    uint32_t last[KGSL_BUSY_SLOTS] = {0};
    int moved = 0;

    stamp(first, 100, 100);
    stamp(first + 2, 7, 7);
    assert(kgsl_busy_sample(memstore, first, last, &moved) == 0);
    assert(moved == 1 && last[0] == 100 && last[2] == 7);

    moved = 0;
    assert(kgsl_busy_sample(memstore, first, last, &moved) == 0);
    assert(moved == 0);

    stamp(first + 2, 8, 7);
    assert(kgsl_busy_sample(memstore, first, last, &moved) == 1);
    assert(moved == 1);

    moved = 0;
    stamp(first + 2, 8, 8);
    assert(kgsl_busy_sample(memstore, first, last, &moved) == 0);
    assert(moved == 0);

    stamp(first + 4, 3, 2);
    assert(kgsl_busy_sample(memstore, first, last, &moved) == 1);

    memset(memstore, 0, sizeof(memstore));
    stamp(first - 1, 5, 4);
    stamp(first + KGSL_BUSY_SLOTS, 9, 8);
    memset(last, 0, sizeof(last));
    moved = 0;
    assert(kgsl_busy_sample(memstore, first, last, &moved) == 0);
    assert(moved == 0);

    puts("PASS: kgsl memstore busy");

    // Hardware-scheduled kernels: ringbuffer entries stay 0, contexts carry the stamps.
    memset(memstore, 0, sizeof(memstore));
    static uint32_t seen[ENTRIES], ctx_last[ENTRIES];
    int live[KGSL_BUSY_MAX_LIVE];
    stamp(3, 50, 50);
    stamp(9, 21, 20);
    stamp(first - 1, 4, 4);
    int count = kgsl_busy_scan_contexts(memstore, first, seen, live, KGSL_BUSY_MAX_LIVE);
    assert(count == 3 && live[0] == 3 && live[1] == 9 && live[2] == first - 1);
    moved = 0;
    assert(kgsl_busy_sample_contexts(memstore, live, count, ctx_last, &moved) == 1);
    assert(moved == 1 && ctx_last[9] == 21);

    // Idle and unchanged since the last scan: dropped. Busy or newly started: kept.
    stamp(9, 21, 21);
    stamp(5, 2, 1);
    count = kgsl_busy_scan_contexts(memstore, first, seen, live, KGSL_BUSY_MAX_LIVE);
    assert(count == 1 && live[0] == 5);
    moved = 0;
    assert(kgsl_busy_sample_contexts(memstore, live, count, ctx_last, &moved) == 1);
    assert(moved == 1);
    moved = 0;
    assert(kgsl_busy_sample_contexts(memstore, live, count, ctx_last, &moved) == 1);
    assert(moved == 0);
    stamp(5, 2, 2);
    assert(kgsl_busy_sample_contexts(memstore, live, count, ctx_last, &moved) == 0);

    // Entry 0 (global) and the ringbuffer entries are not contexts; the live list is capped.
    memset(memstore, 0, sizeof(memstore));
    memset(seen, 0, sizeof(seen));
    stamp(0, 8, 7);
    stamp(first, 8, 7);
    assert(kgsl_busy_scan_contexts(memstore, first, seen, live, KGSL_BUSY_MAX_LIVE) == 0);
    for (int id = 1; id <= KGSL_BUSY_MAX_LIVE + 10; id++) stamp(id, 1, 1);
    assert(kgsl_busy_scan_contexts(memstore, first, seen, live, KGSL_BUSY_MAX_LIVE) == KGSL_BUSY_MAX_LIVE);
    puts("PASS: kgsl memstore contexts");

    assert(kgsl_layout_for(0x44050001u) == &KGSL_LAYOUT_A8XX);  // Adreno 830
    assert(kgsl_layout_for(0x44030a20u) == &KGSL_LAYOUT_A8XX);  // Adreno 829
    assert(kgsl_layout_for(0x43020100u) == &KGSL_LAYOUT_A7XX);  // Adreno 722
    assert(kgsl_layout_for(0x43050a01u) == &KGSL_LAYOUT_A7XX);  // Adreno 740
    assert(kgsl_layout_for(0x07010000u) == &KGSL_LAYOUT_A7XX);  // Adreno 710
    assert(kgsl_layout_for(0x06030001u) == &KGSL_LAYOUT_A7XX);  // Adreno 630
    assert(kgsl_layout_for(0x45000000u) == NULL);
    assert(KGSL_LAYOUT_A8XX.always_on == 0x8e7u && KGSL_LAYOUT_A8XX.cycles == 1u);
    assert(KGSL_LAYOUT_A7XX.always_on == 0x980u && KGSL_LAYOUT_A7XX.cycles == 0u);
    puts("PASS: kgsl counter layout");
    return 0;
}
