#ifndef DROIDDECK_COLOR_H
#define DROIDDECK_COLOR_H
#include <stdint.h>
#include <wayland-server.h>

#define DROIDDECK_ADATASPACE_UNKNOWN    0
#define DROIDDECK_ADATASPACE_BT2020_PQ  163971072
#define DROIDDECK_ADATASPACE_BT2020_HLG 168165376

struct droiddeck_color {
    uint32_t identity;
    uint32_t primaries, tf;
    int32_t dataspace;
    int has_st2086;
    float red[2], green[2], blue[2], white[2];
    float max_lum, min_lum;
    int has_cta861;
    float max_cll, max_fall;
    char text[256];
};

void droiddeck_color_set_request(int mode, const char *source, int dxvk_hdr, int zero_copy_forced);
void droiddeck_color_set_display(int id, const char *name, const char *formats, int hdr10, float max_lum,
                              float max_avg, float min_lum, int ratio_available, float ratio, int api);
void droiddeck_color_ratio_sample(float ratio, int listener);
int droiddeck_color_last_frame_age_ms(void);
int droiddeck_color_gate_state(void);
void droiddeck_color_session_end(void);
int droiddeck_color_hdr_on_screen(void);
int droiddeck_color_hdr_state(void);
void droiddeck_color_set_sdr_white(float nits);
float droiddeck_color_sdr_white(void);
int droiddeck_color_requested(void);
void droiddeck_color_set_output(int on);
int droiddeck_color_output(void);
int droiddeck_color_tonemapped_on_screen(void);
void droiddeck_color_env_sample(int thermal, float headroom, int brightness, int bmode);

float droiddeck_color_desired_headroom(const struct droiddeck_color *c, char *why, size_t n);
void droiddeck_color_note_headroom_request(float ratio);
void droiddeck_color_set_highest_ratio(float ratio);
float droiddeck_color_screen_headroom(char *why, size_t n);

void droiddeck_color_init(struct wl_display *display);
int droiddeck_color_hdr_open(void);
void droiddeck_color_commit(struct wl_resource *surface);
const struct droiddeck_color *droiddeck_color_of(struct wl_resource *surface);
void droiddeck_color_client_gone(struct wl_client *client);
void droiddeck_color_stats_tick(void);
void droiddeck_color_frame_copied(const char *who, const char *reason);

enum droiddeck_hdr_path {
    DROIDDECK_HDR_ZERO_COPY = 0,
    DROIDDECK_HDR_LAYER_COPY,
    DROIDDECK_HDR_COMPOSED,
    DROIDDECK_HDR_SWAPCHAIN,
    DROIDDECK_HDR_TONEMAPPED,

};
void droiddeck_color_frame_shown(const struct droiddeck_color *c, int path, uint32_t ahb_format);
const char *droiddeck_ahb_format_name(uint32_t format);

#endif
