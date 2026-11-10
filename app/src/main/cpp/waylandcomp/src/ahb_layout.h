#pragma once
#include <stdint.h>

static inline int ahb_plane_offset(uint64_t pointer, uint64_t start, uint64_t end,
                                  uint64_t mapping_offset, uint64_t bytes, uint32_t *offset) {
    if (pointer < start || pointer >= end || !bytes || bytes > end - pointer ||
        mapping_offset > UINT32_MAX || pointer - start > UINT32_MAX - mapping_offset) return 0;
    const uint64_t value = mapping_offset + pointer - start;
    if (value % 4) return 0;
    *offset = (uint32_t)value;
    return 1;
}

static inline int ahb_plane_fits(uint64_t size, uint32_t offset, uint64_t bytes) {
    return bytes && offset <= size && bytes <= size - offset;
}
