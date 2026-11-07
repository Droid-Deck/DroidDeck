#include "render/vk_present.h"

#include <GLES3/gl3.h>

#include <cstdlib>
#include <cstring>

#include "ddk_log.h"
#include "device/device.h"
#include "surface/surface.h"

/**
 * The HarmonyOS implementation of upstream's presentation layer.
 *
 * Guest frames live in GLES textures instead of Vulkan images, and the
 * frames reach the screen by drawing into the XComponent's window. The
 * Vulkan-only parts of the upstream API report that they are unavailable,
 * which is what the compositor expects when a capability is missing.
 */

namespace {

int g_scaleMode = VKP_MODE_FIT;
int g_align = VKP_ALIGN_CENTER;

}  // namespace

// --- Images ---------------------------------------------------------------
//
// The image type and its shared memory path live in vkp_image.cpp. Only the
// AOSP-only entry points remain here, and they report that the capability
// is missing.

struct vkp_image *vkp_image_from_android_buffer(AHardwareBuffer *buffer) {
  (void)buffer;
  return nullptr;
}

struct vkp_image *vkp_image_import_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                          int w, int h, uint32_t stride, uint32_t offset,
                                          int as_blit_dst) {
  (void)as_blit_dst;
  return vkp_image_from_dmabuf(fd, drm_format, modifier, w, h, stride, offset);
}

struct vkp_image *vkp_image_import_ahb(struct AHardwareBuffer *ahb, char *info, size_t info_n) {
  (void)ahb;
  if (info != nullptr && info_n > 0) {
    info[0] = '\0';
  }
  return nullptr;
}

int vkp_android_buffer_supported(void) {
  return 0;
}

// --- Window and capabilities ----------------------------------------------

void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir) {
  (void)driver_path;
  (void)library_name;
  (void)native_lib_dir;
}

void vk_present_set_window(ANativeWindow *window) {
  (void)window;
}

int vkp_apply_window_request(void) {
  return 0;
}

int vkp_quest_layer_presentation(void) {
  // The Quest layer is a Vulkan presentation path that HarmonyOS has no
  // counterpart for.
  return 0;
}

int vkp_ready(void) {
  return ddk::SurfaceHost::Instance().IsAttached() ? 1 : 0;
}

int vkp_has_window(void) {
  return ddk::SurfaceHost::Instance().IsAttached() ? 1 : 0;
}

int vkp_wait_window_request(int timeout_ms) {
  (void)timeout_ms;
  return 0;
}

int vkp_device_lost(void) {
  return 0;
}

int vkp_completion_unknown(void) {
  return 0;
}

int vkp_can_wait_sync_fd(void) {
  return 0;
}

const char *vkp_gpu_name(void) {
  static std::string name;
  if (name.empty()) {
    name = ddk::DetectGpu().name;
  }
  return name.c_str();
}

ANativeWindow *vkp_window(void) {
  return reinterpret_cast<ANativeWindow *>(ddk::SurfaceHost::Instance().Window());
}

void vkp_signal_first_frame(void) {
}

void vkp_wait_idle(void) {
  glFinish();
}

// --- Geometry -------------------------------------------------------------

void vk_present_set_scale_mode(int mode, int alignment) {
  g_scaleMode = mode;
  g_align = alignment;
}

int vkp_game_picture_rect(float rect[4]) {
  (void)g_scaleMode;
  (void)g_align;
  if (rect == nullptr) {
    return 0;
  }
  rect[0] = 0.0f;
  rect[1] = 0.0f;
  rect[2] = 1.0f;
  rect[3] = 1.0f;
  return 1;
}

int vkp_output_to_scene(double ox, double oy, double *sx, double *sy) {
  if (sx != nullptr) {
    *sx = ox;
  }
  if (sy != nullptr) {
    *sy = oy;
  }
  return 1;
}

int vkp_scene_to_output(double sx, double sy, double *ox, double *oy) {
  if (ox != nullptr) {
    *ox = sx;
  }
  if (oy != nullptr) {
    *oy = sy;
  }
  return 1;
}

void vkp_output_size(int *w, int *h) {
  ddk::SurfaceHost::Instance().Size(w, h);
}

int vkp_update_map(int scene_w, int scene_h) {
  (void)scene_w;
  (void)scene_h;
  return 1;
}

int vkp_map_draw(const struct vkp_draw *d, int out[8]) {
  if (d == nullptr || out == nullptr) {
    return 0;
  }
  out[0] = d->dx;
  out[1] = d->dy;
  out[2] = d->dw;
  out[3] = d->dh;
  out[4] = static_cast<int>(d->sx);
  out[5] = static_cast<int>(d->sy);
  out[6] = static_cast<int>(d->sw);
  out[7] = static_cast<int>(d->sh);
  return 1;
}

int vkp_map_rect(int img_w, int img_h, int scene_w, int scene_h, int out[8]) {
  (void)img_h;
  if (out == nullptr) {
    return 0;
  }
  out[0] = 0;
  out[1] = 0;
  out[2] = img_w;
  out[3] = scene_w;
  out[4] = 0;
  out[5] = 0;
  out[6] = scene_h;
  out[7] = img_w;
  return 1;
}

int vkp_surface_rotation_degrees(void) {
  return 0;
}

// --- Presentation ---------------------------------------------------------
//
// The compositor reads these as `rendered = vkp_render(...) == 0`, so zero
// means success, matching upstream.

int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n) {
  (void)scene_w;
  (void)scene_h;
  ddk::SurfaceHost &host = ddk::SurfaceHost::Instance();
  if (!host.IsAttached() || draws == nullptr || n <= 0) {
    return -1;
  }

  int surfaceWidth = 0;
  int surfaceHeight = 0;
  host.Size(&surfaceWidth, &surfaceHeight);
  if (surfaceWidth <= 0 || surfaceHeight <= 0) {
    return -1;
  }

  for (int index = 0; index < n; ++index) {
    const struct vkp_draw *draw = &draws[index];
    if (draw->img == nullptr) {
      continue;
    }
    // The scene is the surface, so the destination rectangle is scaled from
    // the scene size onto the surface.
    const float scaleX = scene_w > 0 ? static_cast<float>(surfaceWidth) / static_cast<float>(scene_w) : 1.0f;
    const float scaleY = scene_h > 0 ? static_cast<float>(surfaceHeight) / static_cast<float>(scene_h) : 1.0f;
    host.DrawTextureRect(draw->img->texture, draw->sx, draw->sy, draw->sw, draw->sh,
                         static_cast<int>(draw->dx * scaleX), static_cast<int>(draw->dy * scaleY),
                         static_cast<int>(draw->dw * scaleX), static_cast<int>(draw->dh * scaleY));
  }

  host.SwapBuffers();
  return 0;
}

int vkp_render_plain(int scene_w, int scene_h) {
  (void)scene_w;
  (void)scene_h;
  ddk::SurfaceHost &host = ddk::SurfaceHost::Instance();
  if (!host.IsAttached()) {
    return -1;
  }
  host.Clear();
  host.SwapBuffers();
  return 0;
}

int vkp_base_black(int scene_w, int scene_h) {
  (void)scene_w;
  (void)scene_h;
  ddk::SurfaceHost &host = ddk::SurfaceHost::Instance();
  if (!host.IsAttached()) {
    return -1;
  }
  host.Clear();
  host.SwapBuffers();
  return 0;
}

int vkp_render_hdr(int scene_w, int scene_h, const struct vkp_draw *draws, int n,
                   const struct vkp_hdr_frame *hf, int *how) {
  (void)hf;
  if (how != nullptr) {
    *how = 0;
  }
  return vkp_render(scene_w, scene_h, draws, n);
}

int vkp_pass_begin_hdr(int scene_w, int scene_h, const struct vkp_draw *draws, int n,
                       const struct vkp_hdr_frame *hf, int *rw, int *rh) {
  (void)hf;
  return vkp_pass_begin(scene_w, scene_h, draws, n, rw, rh);
}

int vkp_pass_begin(int scene_w, int scene_h, const struct vkp_draw *draws, int n, int *rw, int *rh) {
  (void)draws;
  (void)n;
  int width = 0;
  int height = 0;
  ddk::SurfaceHost::Instance().Size(&width, &height);
  if (rw != nullptr) {
    *rw = width > 0 ? width : scene_w;
  }
  if (rh != nullptr) {
    *rh = height > 0 ? height : scene_h;
  }
  return 0;
}

int vkp_pass_copy_to(struct vkp_image *dst, int wait_fd) {
  (void)dst;
  (void)wait_fd;
  return 0;
}

void vkp_pass_abort(void) {
}

int vkp_blit_image(struct vkp_image *src, struct vkp_image *dst, int wait_fd) {
  (void)src;
  (void)dst;
  (void)wait_fd;
  return 0;
}

const char *vkp_modifier_name(uint64_t modifier) {
  return modifier == VKP_MOD_LINEAR ? "linear" : "unsupported";
}

int vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max) {
  (void)drm_format;
  (void)out;
  (void)max;
  return 0;
}

void vkp_perf_take(struct vkp_perf *out) {
  if (out == nullptr) {
    return;
  }
  std::memset(out, 0, sizeof(*out));
}

// --- Diagnostics ----------------------------------------------------------

const char *vkp_fault(void) {
  static std::string fault;
  fault.clear();
  if (!ddk::SurfaceHost::Instance().IsAttached()) {
    fault = "the output surface is not attached";
  }
  return fault.empty() ? NULL : fault.c_str();
}

const char *vkp_display_driver(void) {
  static std::string driver;
  driver = ddk::DetectGpu().name;
  return driver.empty() ? NULL : driver.c_str();
}
