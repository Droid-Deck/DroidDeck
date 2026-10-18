#pragma once
#include <cstddef>
#include <cstdint>

inline std::size_t mjpeg_payload_size(const uint8_t* bytes, std::size_t size) {
    if (!bytes || size < 4 || size > 4*1024*1024 || bytes[0] != 0xff || bytes[1] != 0xd8) return 0;
    std::size_t end = size;
    // Luma Ultra UVC frames can pad the JPEG to an eight-byte boundary with zeros.
    while (end > 4 && size-end < 7 && bytes[end-1] == 0) --end;
    return bytes[end-2] == 0xff && bytes[end-1] == 0xd9 ? end : 0;
}
