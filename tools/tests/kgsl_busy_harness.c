#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "kgsl_busy.h"

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
    return 0;
}
