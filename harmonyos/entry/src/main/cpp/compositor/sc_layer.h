#ifndef SC_LAYER_H
#define SC_LAYER_H

/**
 * The display layer path.
 *
 * Upstream draws the guest's frames straight onto a SurfaceControl layer,
 * which is the zero-copy route. SurfaceControl is an AOSP API with no
 * HarmonyOS counterpart, so the port does not have it: the frames are
 * presented through the GLES path in `render/vk_present.h` instead.
 *
 * The API is kept exactly as upstream has it, so the ported compositor
 * compiles unchanged. Every entry point reports that the capability is
 * missing, which is the state upstream's compositor already handles for
 * devices without an accelerated path.
 */

#include <stddef.h>
#include <stdint.h>

struct AHardwareBuffer;
struct vkp_image;
struct vkp_draw;
struct vkp_hdr_frame;
struct droiddeck_color;

enum sc_layer_id { SC_LAYER_GAME = 0, SC_LAYER_OVERLAY = 1, SC_LAYER_COUNT = 2 };

/** True when a display layer is available. Never on HarmonyOS. */
int sc_layer_available(void);

/** Probes a dma-buf for the accelerated path. */
void sc_layer_probe_dmabuf_fd(int fd);

/** True when the layer can tag HDR metadata. */
int sc_layer_can_tag_hdr(void);

/** Fills a buffer with the HDR extension names the layer supports. */
void sc_layer_hdr_symbols(char *out, size_t size);

/** Presents one image. Returns 0 on success, as upstream does. */
int sc_layer_present(struct vkp_image *src, int scene_w, int scene_h,
                     const struct droiddeck_color *color);

/** Presents a list of draws. Returns 0 on success. */
int sc_layer_present_pass(const struct vkp_draw *draws, int n, int scene_w, int scene_h);

/** Presents an HDR scene. Returns 0 on success. */
int sc_layer_present_hdr_scene(const struct vkp_draw *draws, int n, const struct vkp_hdr_frame *hf,
                                int scene_w, int scene_h, const struct droiddeck_color *color);

/** Presents an AHardwareBuffer. AHardwareBuffer does not exist here. */
int sc_layer_present_ahb(struct AHardwareBuffer *ahb, int w, int h, int acquire_fd, void *token,
                         int scene_w, int scene_h, const struct droiddeck_color *color,
                         uint32_t ahb_format);

/** Sets the frame rate the layer should aim for. */
void sc_layer_set_frame_rate(float fps);

/** True when a second layer is cheap enough to draw. */
int sc_layer_overlay_affordable(void);

/** Presents the overlay layer. Returns 0 on success. */
int sc_layer_present_overlay(struct vkp_image *src, const int geo[8], int translucent);

/** Hides every layer. */
void sc_layer_hide(void);

/** Fills the base layer with black. Returns 0 on success. */
int sc_layer_base_black(int width, int height);

void sc_layer_hide_base(void);
void sc_layer_hide_overlay(void);

/** Called when the display window goes away. */
void sc_layer_window_gone(void);

/** Takes and resets the frame and drop counters. */
unsigned sc_layer_frames_take(void);
unsigned sc_layer_drops_take(void);

#endif  // SC_LAYER_H
