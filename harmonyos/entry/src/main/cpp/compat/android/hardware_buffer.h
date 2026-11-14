#ifndef DDK_COMPAT_ANDROID_HARDWARE_BUFFER_H
#define DDK_COMPAT_ANDROID_HARDWARE_BUFFER_H

/**
 * AOSP's <android/hardware_buffer.h>.
 *
 * AHardwareBuffer is an AOSP gralloc type with no HarmonyOS counterpart.
 * The ported compositor only ever handles pointers to it, so the type is
 * declared and left opaque; the code paths that would use it report that
 * the capability is missing.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AHardwareBuffer;

/**
 * AOSP's hardware_buffer.h typedefs the type, and the ported compositor
 * refers to it without the struct tag, so the alias is provided here too.
 */
typedef struct AHardwareBuffer AHardwareBuffer;

/**
 * The AHardwareBuffer reference counting the ported compositor performs.
 *
 * HarmonyOS has no AHardwareBuffer, so the buffers never exist and the
 * calls are no-ops.
 */
static inline void AHardwareBuffer_acquire(struct AHardwareBuffer *buffer) {
  (void)buffer;
}

static inline void AHardwareBuffer_release(struct AHardwareBuffer *buffer) {
  (void)buffer;
}

enum AHardwareBuffer_Usage {
  AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN = 0x00000001,
  AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN = 0x00000002,
  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE = 0x00000100,
  AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT = 0x00000200,
};

enum AHardwareBuffer_Format {
  AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1,
  AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM = 2,
  AHARDWAREBUFFER_FORMAT_R8G8B8A8_SRGB = 3,
  AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT = 0x300,
};

#ifdef __cplusplus
}
#endif

#endif  // DDK_COMPAT_ANDROID_HARDWARE_BUFFER_H
