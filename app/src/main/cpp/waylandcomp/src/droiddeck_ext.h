/*
 * Interface between compositor.c and the extension modules that live in their own files:
 *   wl_clipboard.c      wl_data_device_manager + zwlr_data_control_manager_v1 (selection only)
 *   wl_text_input.c     zwp_text_input_manager_v3 (host IME / soft keyboard)
 *   wl_toplevel_icon.c  xdg_toplevel_icon_manager_v1 (accepted, not rendered)
 *   droiddeck_ext.c        the host→compositor message queue shared by the modules
 * Everything here runs on the compositor (dispatch) thread unless noted.
 */
#pragma once
#include <stdint.h>
#include <wayland-server.h>

void droiddeck_log(const char *tag, const char *fmt, ...);

/* ---- compositor.c → modules */
void droiddeck_ext_init(struct wl_display *display);           /* creates the globals + host queue */
void droiddeck_clipboard_keyboard_focus(struct wl_client *client); /* keyboard focus moved; NULL = none */
void droiddeck_text_input_refocus(void);                        /* the text-input target may have changed */
void droiddeck_text_input_surface_gone(struct wl_resource *surface);

/* ---- modules → compositor.c */
const char *droiddeck_client_name(struct wl_client *client);
struct wl_display *droiddeck_get_display(void);
/* The surface text input follows (the last clicked window, else the topmost program window). */
struct wl_resource *droiddeck_ime_target(void);
/* Scene (virtual desktop) origin of a toplevel surface; 0,0 when unknown. */
void droiddeck_surface_scene_origin(struct wl_resource *surface, int *x, int *y);
/* Deliver a key press/release through the wl_keyboard path as if typed. */
void droiddeck_inject_key(uint32_t evdev, int pressed);

/* ---- modules → app (JNI upcalls; waylandcomp_jni.c) */
void droiddeck_on_clipboard_text(const char *utf8, int len);
void droiddeck_on_text_input(int enabled, const char *program, int x, int y, int w, int h);

/* ---- app → modules (callable from any thread; queued to the compositor thread) */
void droiddeck_host_clipboard_text(const char *utf8, int len);  /* len 0 = clear */
void droiddeck_host_text_commit(const char *utf8, int len);
void droiddeck_host_text_preedit(const char *utf8, int len, int cursor_begin, int cursor_end);
void droiddeck_host_text_delete(int before, int after);
void droiddeck_host_zero_copy(int on, int live);                 /* the drawer's zero-copy switch (ahb_swapchain_set_mode) */
void droiddeck_host_hdr_output(int on);                          /* the drawer's HDR output switch (droiddeck_color_set_output) */

/* ---- module internals reached through the queue (droiddeck_ext.c → modules) */
void clipboard_init(struct wl_display *display);
void clipboard_host_text(char *utf8, size_t len);            /* takes ownership of utf8 */
void text_input_init(struct wl_display *display);
void text_input_host_commit(const char *utf8, size_t len);
void text_input_host_preedit(const char *utf8, size_t len, int cursor_begin, int cursor_end);
void text_input_host_delete(int before, int after);
void toplevel_icon_init(struct wl_display *display);
void single_pixel_init(struct wl_display *display);
/* A wp_single_pixel_buffer_manager_v1 buffer: 1 and its pixel (B, G, R, A, premultiplied), else 0. */
int single_pixel_buffer_get(struct wl_resource *buffer, uint8_t bgra[4]);
