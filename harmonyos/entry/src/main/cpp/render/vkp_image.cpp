#include "render/vkp_image.h"

#include <cstdlib>
#include <cstring>

#include "ddk_log.h"
#include "surface/surface.h"

/**
 * Guest frame images, backed by GLES textures.
 *
 * The textures live in the surface host's EGL context, which is the one
 * the XComponent's window uses, so the frames can be drawn straight into
 * the screen.
 */

struct vkp_image *vkp_image_create_shm(int w, int h) {
  if (w <= 0 || h <= 0) {
    return nullptr;
  }
  struct vkp_image *image = static_cast<struct vkp_image *>(std::calloc(1, sizeof(struct vkp_image)));
  if (image == nullptr) {
    return nullptr;
  }
  image->texture = ddk::SurfaceHost::Instance().CreateTexture(w, h);
  if (image->texture == 0) {
    DDK_LOGW("cannot create a %{public}dx%{public}d guest frame texture", w, h);
    std::free(image);
    return nullptr;
  }
  image->width = w;
  image->height = h;
  return image;
}

void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride) {
  if (img == nullptr || data == nullptr || img->texture == 0) {
    return;
  }
  ddk::SurfaceHost::Instance().UploadTexture(img->texture, data, img->width, img->height, stride);
}

int vkp_image_width(const struct vkp_image *img) {
  return img == nullptr ? 0 : img->width;
}

int vkp_image_height(const struct vkp_image *img) {
  return img == nullptr ? 0 : img->height;
}

void vkp_image_destroy(struct vkp_image *img) {
  if (img == nullptr) {
    return;
  }
  if (img->texture != 0) {
    ddk::SurfaceHost::Instance().DeleteTexture(img->texture);
  }
  std::free(img);
}

int vkp_image_is_dmabuf(const struct vkp_image *img) {
  return img == nullptr ? 0 : img->from_dmabuf;
}

struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                        int w, int h, uint32_t stride, uint32_t offset) {
  (void)fd;
  (void)drm_format;
  (void)modifier;
  (void)w;
  (void)h;
  (void)stride;
  (void)offset;
  DDK_LOGW("dma-buf import is unavailable without a Vulkan driver; the guest has to use shared memory");
  return nullptr;
}

struct vkp_image *vkp_image_from_ahb(struct AHardwareBuffer *ahb) {
  (void)ahb;
  DDK_LOGW("AHardwareBuffer import is unavailable on HarmonyOS");
  return nullptr;
}

int vkp_can_import_ahb(void) {
  return 0;
}

int vkp_image_readback(const struct vkp_image *src, uint32_t *out, int max_px) {
  if (src == nullptr || out == nullptr || max_px <= 0) {
    return 0;
  }
  const int pixels = src->width * src->height;
  if (pixels <= 0) {
    return 0;
  }
  if (!ddk::SurfaceHost::Instance().ReadTexture(src->texture, src->width, src->height, out)) {
    return 0;
  }
  return pixels < max_px ? pixels : max_px;
}
