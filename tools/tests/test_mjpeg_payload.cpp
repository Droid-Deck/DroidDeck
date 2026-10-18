#include "../../app/src/main/cpp/viture/mjpeg_payload.h"
#include <cassert>
#include <cstdio>
#include <cstring>

int main() {
    const uint8_t jpeg[] = {0xff,0xd8,0xff,0xc0,0,4,1,2,0xff,0xd9};
    uint8_t frame[32]{};
    std::memcpy(frame,jpeg,sizeof(jpeg));
    for (std::size_t padding=0; padding<=7; ++padding)
        assert(mjpeg_payload_size(frame,sizeof(jpeg)+padding) == sizeof(jpeg));
    assert(mjpeg_payload_size(frame,sizeof(jpeg)+8) == 0);
    for (std::size_t size=0; size<sizeof(jpeg); ++size)
        assert(mjpeg_payload_size(frame,size) == 0);
    frame[sizeof(jpeg)] = 0x42;
    assert(mjpeg_payload_size(frame,sizeof(jpeg)+1) == 0);
    frame[sizeof(jpeg)] = 0;
    frame[0] = 0;
    assert(mjpeg_payload_size(frame,sizeof(jpeg)) == 0);
    assert(mjpeg_payload_size(nullptr,10) == 0);
    assert(mjpeg_payload_size(jpeg,4*1024*1024+1) == 0);
    std::puts("PASS: JPEG boundaries, 0-7 padding bytes, truncation, garbage, and bounds");
}
