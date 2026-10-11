/* SPDX-License-Identifier: GPL-3.0-or-later
 * Minimal xcb declarations for the bridge ICD: libxcb is opened at run time (dlopen), so only the
 * types and the few request/reply layouts the software present path uses are declared here. */
#ifndef VKB_COMPAT_XCB_H
#define VKB_COMPAT_XCB_H
#include <stdint.h>
typedef struct xcb_connection_t xcb_connection_t;
typedef uint32_t xcb_window_t;
typedef uint32_t xcb_visualid_t;
typedef uint32_t xcb_drawable_t;
typedef uint32_t xcb_gcontext_t;
typedef uint32_t xcb_pixmap_t;
typedef struct { unsigned int sequence; } xcb_void_cookie_t;
typedef struct { unsigned int sequence; } xcb_get_geometry_cookie_t;
typedef struct {
    uint8_t response_type;
    uint8_t depth;
    uint16_t sequence;
    uint32_t length;
    xcb_window_t root;
    int16_t x, y;
    uint16_t width, height, border_width;
    uint8_t pad0[2];
} xcb_get_geometry_reply_t;
typedef struct {
    uint8_t response_type;
    uint8_t error_code;
    uint16_t sequence;
    uint32_t resource_id;
    uint16_t minor_code;
    uint8_t major_code;
    uint8_t pad0;
    uint32_t pad[5];
    uint32_t full_sequence;
} xcb_generic_error_t;
#define XCB_IMAGE_FORMAT_Z_PIXMAP 2
#endif
