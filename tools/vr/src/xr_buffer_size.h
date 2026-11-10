#pragma once
#include <cstdint>
#include <sys/stat.h>
#include <unistd.h>

inline bool xr_buffer_allocation_size(int descriptor, uint64_t &bytes) {
    bytes = 0;
    struct stat status{};
    if (fstat(descriptor, &status) != 0 || status.st_size < 0) return false;
    if (status.st_size > 0) {
        bytes = static_cast<uint64_t>(status.st_size);
        return true;
    }
    // Older DMA-BUF exporters expose their size through SEEK_END while st_size is zero.
    off_t allocation = lseek(descriptor, 0, SEEK_END);
    if (allocation <= 0 || lseek(descriptor, 0, SEEK_SET) != 0) return false;
    bytes = static_cast<uint64_t>(allocation);
    return true;
}
