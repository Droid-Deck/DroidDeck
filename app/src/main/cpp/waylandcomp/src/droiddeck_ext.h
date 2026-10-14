#pragma once
#include <stdint.h>
#include <wayland-server.h>

void droiddeck_log(const char *tag, const char *fmt, ...);


void droiddeck_ext_init(struct wl_display *display);
void droiddeck_clipboard_keyboard_focus(struct wl_client *client);
void droiddeck_text_input_refocus(void);
void droiddeck_text_input_surface_gone(struct wl_resource *surface);

const char *droiddeck_client_name(struct wl_client *client);
struct wl_display *droiddeck_get_display(void);
struct wl_resource *droiddeck_ime_target(void);
void droiddeck_surface_scene_origin(struct wl_resource *surface, int *x, int *y);
void droiddeck_inject_key(uint32_t evdev, int pressed);

void droiddeck_on_clipboard_text(const char *utf8, int len);
void droiddeck_on_text_input(int enabled, const char *program, int x, int y, int w, int h);


void droiddeck_host_clipboard_text(const char *utf8, int len);
void droiddeck_host_text_commit(const char *utf8, int len);
void droiddeck_host_text_preedit(const char *utf8, int len, int cursor_begin, int cursor_end);
void droiddeck_host_text_delete(int before, int after);
void droiddeck_host_zero_copy(int on, int live);
void droiddeck_host_hdr_output(int on);

void clipboard_init(struct wl_display *display);
void clipboard_host_text(char *utf8, size_t len);
void text_input_init(struct wl_display *display);
void text_input_host_commit(const char *utf8, size_t len);
void text_input_host_preedit(const char *utf8, size_t len, int cursor_begin, int cursor_end);
void text_input_host_delete(int before, int after);
void toplevel_icon_init(struct wl_display *display);
void single_pixel_init(struct wl_display *display);
int single_pixel_buffer_get(struct wl_resource *buffer, uint8_t bgra[4]);
