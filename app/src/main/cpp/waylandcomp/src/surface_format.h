#pragma once

#include <stdint.h>

static inline uint32_t droiddeck_select_surface_format(const uint32_t *formats, uint32_t count,
                                                       uint32_t fallback) {
    for (uint32_t i = 0; i < count; i++)
        if (formats[i] != 0) return formats[i];
    return fallback;
}
