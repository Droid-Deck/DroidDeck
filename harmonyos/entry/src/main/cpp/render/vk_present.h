#ifndef VK_PRESENT_H
#define VK_PRESENT_H

#include <stdint.h>
#include <stddef.h>

#include "render/vkp_image.h"

#ifdef __cplusplus
extern "C" {
#endif

struct ANativeWindow;
struct AHardwareBuffer;
struct droiddeck_color;

/**
 * The guest frame presentation layer.
 *
 * This header keeps upstream DroidDeck's API exactly, so the ported
 * `compositor.c` and `wl_dmabuf.c` compile against it unchanged.
 *
 * The difference is the implementation: upstream keeps guest frames in
 * Vulkan images and presents them with `VK_KHR_android_surface`. HarmonyOS
 * guarantees EGL and GLES but not Vulkan, and `AHardwareBuffer` has no
 * counterpart at all, so the port keeps the frames in GLES textures and
 * presents them by drawing into the XComponent's window.
 */

void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir);

void vk_present_set_window(struct ANativeWindow *window);
int vkp_apply_window_request(void);

int vkp_quest_layer_presentation(void);

enum vkp_scale_mode { VKP_MODE_OFF = 0, VKP_MODE_FIT = 1, VKP_MODE_STRETCH = 2, VKP_MODE_FILL = 3,
                      VKP_MODE_INTEGER = 4 };
enum vkp_align { VKP_ALIGN_CENTER = 0, VKP_ALIGN_TOP = 1, VKP_ALIGN_BOTTOM = 2, VKP_ALIGN_PICTURE_TOP = 3, VKP_ALIGN_PICTURE_BOTTOM = 4 };
void vk_present_set_scale_mode(int mode, int alignment);
int vkp_game_picture_rect(float rect[4]);
int vkp_output_to_scene(double ox, double oy, double *sx, double *sy);
int vkp_scene_to_output(double sx, double sy, double *ox, double *oy);
void vkp_output_size(int *w, int *h);

struct vkp_image;

int vkp_android_buffer_supported(void);
struct vkp_image *vkp_image_from_android_buffer(struct AHardwareBuffer *buffer);

#define VKP_MOD_LINEAR          0x0000000000000000ULL
#define VKP_MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define VKP_MOD_INVALID         0x00ffffffffffffffULL
const char *vkp_modifier_name(uint64_t modifier);
int vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max);

/** Why presenting failed, empty while it works. */
const char *vkp_fault(void);

/** The display driver as the compositor reports it. */
const char *vkp_display_driver(void);

struct vkp_image *vkp_image_import_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                          int w, int h, uint32_t stride, uint32_t offset,
                                          int as_blit_dst);
int vkp_can_import_ahb(void);
struct vkp_image *vkp_image_import_ahb(struct AHardwareBuffer *ahb, char *info, size_t info_n);

struct vkp_image *vkp_image_from_ahb(struct AHardwareBuffer *ahb);
void vkp_wait_idle(void);

struct vkp_draw {
    struct vkp_image *img;
    float sx, sy, sw, sh;
    int dx, dy, dw, dh;
    int blend;
};

const char *vkp_gpu_name(void);

int vkp_ready(void);
int vkp_has_window(void);
int vkp_wait_window_request(int timeout_ms);
int vkp_device_lost(void);
int vkp_completion_unknown(void);

int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n);
int vkp_render_plain(int scene_w, int scene_h);
int vkp_base_black(int scene_w, int scene_h);

struct vkp_perf {
    unsigned acquires, presents, waits;
    int64_t acquire_ns, acquire_max_ns;
    int64_t present_ns, present_max_ns;
    int64_t wait_ns, wait_max_ns;
    unsigned base_presents;
    unsigned base_kept;
    unsigned gpu_release_waits;
};
void vkp_perf_take(struct vkp_perf *out);
int vkp_can_wait_sync_fd(void);

struct vkp_hdr_frame {
    const unsigned char *is_hdr;
    float peak_nits;
    int tonemap;
    const struct droiddeck_color *color;
};
int vkp_render_hdr(int scene_w, int scene_h, const struct vkp_draw *draws, int n,
                   const struct vkp_hdr_frame *hf, int *how);
int vkp_pass_begin_hdr(int scene_w, int scene_h, const struct vkp_draw *draws, int n,
                       const struct vkp_hdr_frame *hf, int *rw, int *rh);

int vkp_blit_image(struct vkp_image *src, struct vkp_image *dst, int wait_fd);
int vkp_update_map(int scene_w, int scene_h);
int vkp_map_draw(const struct vkp_draw *d, int out[8]);
int vkp_map_rect(int img_w, int img_h, int scene_w, int scene_h, int out[8]);
int vkp_surface_rotation_degrees(void);

struct ANativeWindow *vkp_window(void);
void vkp_signal_first_frame(void);

int vkp_pass_begin(int scene_w, int scene_h, const struct vkp_draw *draws, int n, int *rw, int *rh);
int vkp_pass_copy_to(struct vkp_image *dst, int wait_fd);
void vkp_pass_abort(void);

#ifdef __cplusplus
}
#endif

#endif
