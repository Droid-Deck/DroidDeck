#ifndef DDK_VKP_IMAGE_H
#define DDK_VKP_IMAGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AHardwareBuffer;

/**
 * A guest frame.
 *
 * Upstream keeps this opaque and backs it with a Vulkan image. HarmonyOS
 * guarantees EGL and GLES but not Vulkan, so the port backs it with a GLES
 * texture instead; the compositor only ever handles pointers, so it does
 * not notice.
 */
struct vkp_image {
  /** The GLES texture holding the pixels. */
  unsigned int texture;
  int width;
  int height;
  /** True when the image came from a dma-buf rather than shared memory. */
  int from_dmabuf;
};

/** Creates an empty image for a shared memory buffer of the given size. */
struct vkp_image *vkp_image_create_shm(int w, int h);

/** Uploads BGRA pixels into an image. `stride` is the source row length. */
void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride);

int vkp_image_width(const struct vkp_image *img);
int vkp_image_height(const struct vkp_image *img);
void vkp_image_destroy(struct vkp_image *img);

/** True when the image was imported from a dma-buf. */
int vkp_image_is_dmabuf(const struct vkp_image *img);

/**
 * Imports a dma-buf.
 *
 * HarmonyOS has no Vulkan dma-buf import without the Vulkan driver, so this
 * always fails and the guest falls back to shared memory buffers.
 */
struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                        int w, int h, uint32_t stride, uint32_t offset);

/**
 * Imports an AHardwareBuffer.
 *
 * AHardwareBuffer is an AOSP type with no HarmonyOS counterpart, so this
 * always fails too.
 */
struct vkp_image *vkp_image_from_ahb(struct AHardwareBuffer *ahb);

/** Reads an image back into a BGRA buffer, at most `max_px` pixels. */
int vkp_image_readback(const struct vkp_image *src, uint32_t *out, int max_px);

/** True when dma-buf import is available. Always false on HarmonyOS. */
int vkp_can_import_ahb(void);

#ifdef __cplusplus
}
#endif

#endif  // DDK_VKP_IMAGE_H
