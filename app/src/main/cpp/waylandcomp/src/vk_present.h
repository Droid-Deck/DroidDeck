#ifndef VK_PRESENT_H
#define VK_PRESENT_H
#include <stdint.h>
#include <android/native_window.h>
void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir);

void vk_present_set_window(ANativeWindow *window);
int vkp_apply_window_request(void);

int vkp_quest_layer_presentation(void);

enum vkp_scale_mode { VKP_MODE_OFF = 0, VKP_MODE_FIT = 1, VKP_MODE_STRETCH = 2, VKP_MODE_FILL = 3,
                      VKP_MODE_INTEGER = 4 };
enum vkp_align { VKP_ALIGN_CENTER = 0, VKP_ALIGN_TOP = 1, VKP_ALIGN_BOTTOM = 2, VKP_ALIGN_PICTURE_TOP = 3, VKP_ALIGN_PICTURE_BOTTOM = 4 };
void vk_present_set_scale_mode(int mode, int alignment);
int vkp_game_picture_rect(float rect[4]);
int vkp_output_to_scene(double ox, double oy, double *sx, double *sy);
void vkp_output_size(int *w, int *h);

struct vkp_image;

#define VKP_MOD_LINEAR          0x0000000000000000ULL
#define VKP_MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define VKP_MOD_INVALID         0x00ffffffffffffffULL
const char *vkp_modifier_name(uint64_t modifier);
int vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max);

struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                        int w, int h, uint32_t stride, uint32_t offset);
struct vkp_image *vkp_image_import_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                          int w, int h, uint32_t stride, uint32_t offset,
                                          int as_blit_dst);
int vkp_image_is_dmabuf(const struct vkp_image *img);

struct vkp_image *vkp_image_create_shm(int w, int h);
struct AHardwareBuffer;
struct vkp_image *vkp_image_from_ahb(struct AHardwareBuffer *ahb);
void vkp_wait_idle(void);
void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride);

int vkp_image_width(const struct vkp_image *img);
int vkp_image_height(const struct vkp_image *img);
void vkp_image_destroy(struct vkp_image *img);

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

struct droiddeck_color;
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
int vkp_image_readback(struct vkp_image *src, uint32_t *out, int max_px);
int vkp_update_map(int scene_w, int scene_h);
int vkp_map_draw(const struct vkp_draw *d, int out[8]);
int vkp_map_rect(int img_w, int img_h, int scene_w, int scene_h, int out[8]);
int vkp_surface_rotation_degrees(void);

ANativeWindow *vkp_window(void);
void vkp_signal_first_frame(void);

int vkp_pass_begin(int scene_w, int scene_h, const struct vkp_draw *draws, int n, int *rw, int *rh);
int vkp_pass_copy_to(struct vkp_image *dst, int wait_fd);
void vkp_pass_abort(void);

void droiddeck_log(const char *tag, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

#endif
