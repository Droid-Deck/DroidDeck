#pragma once
#include <stdint.h>

#define DROIDDECK_AHB_MAGIC 0x32424841u
#define DROIDDECK_AHB_OUTPUT_MAGIC 0x43424841u
#define DROIDDECK_AHB_SOCKET "droiddeck-ahb"
#define DROIDDECK_AHB_RELEASE_EVENTS 1u
#define DROIDDECK_AHB_RELEASE_MAGIC 0x52424841u
struct droiddeck_ahb_request {
    uint32_t magic, width, height, format;
};
struct droiddeck_ahb_reply {
    int32_t status;
    uint32_t stride, offset, reserved;
    uint64_t modifier, size;
};
struct droiddeck_ahb_release {
    uint32_t magic, reserved;
    uint64_t serial;
};
