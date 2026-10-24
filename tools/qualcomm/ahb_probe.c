#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 3; }
    int (*supported)(void) = dlsym(library, "vkp_android_buffer_supported");
    void *(*import_buffer)(AHardwareBuffer *) = dlsym(library, "vkp_image_from_android_buffer");
    int (*readback)(void *, uint32_t *, int) = dlsym(library, "vkp_image_readback");
    void (*destroy)(void *) = dlsym(library, "vkp_image_destroy");
    if (!supported || !import_buffer || !readback || !destroy || !supported()) return 4;
    AHardwareBuffer_Desc desc = {
        .width = 64, .height = 64, .layers = 1, .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
    };
    AHardwareBuffer *buffer = NULL;
    if (AHardwareBuffer_allocate(&desc, &buffer)) return 5;
    AHardwareBuffer_describe(buffer, &desc);
    void *pixels = NULL;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, &pixels)) return 6;
    for (uint32_t y = 0; y < desc.height; y++)
        for (uint32_t x = 0; x < desc.width; x++)
            ((uint32_t *)pixels)[y * desc.stride + x] = 0xff224466u;
    if (AHardwareBuffer_unlock(buffer, NULL)) return 7;
    void *image = import_buffer(buffer);
    if (!image) return 8;
    uint32_t result[64 * 64];
    int status = readback(image, result, 64 * 64);
    if (status < 0) { fprintf(stderr, "Readback failed: %d\n", status); return 9; }
    /* vkp_image_readback converts its input to B8G8R8A8_UNORM. */
    const uint32_t expected = 0xff664422u;
    for (int i = 0; i < 64 * 64; i++) {
        if (result[i] != expected) {
            fprintf(stderr, "Pixel %d: %#x expected %#x\n", i, result[i], expected);
            return 10;
        }
    }
    destroy(image);
    AHardwareBuffer_release(buffer);
    puts("PASS: Android hardware buffer imported and 4096 pixels read back through the compositor");
    return 0;
}
