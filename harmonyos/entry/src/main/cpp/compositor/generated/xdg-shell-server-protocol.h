
#ifndef XDG_SHELL_SERVER_PROTOCOL_H
#define XDG_SHELL_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_xdg_shell Copyright
 *
 * Copyright © 2008-2013 Kristian Høgsberg
 * Copyright © 2013      Rafael Antognolli
 * Copyright © 2013      Jasper St. Pierre
 * Copyright © 2010-2013 Intel Corporation
 * Copyright © 2015-2017 Samsung Electronics Co., Ltd
 * Copyright © 2015-2017 Red Hat Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice (including the next
 * paragraph) shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */









struct wl_output;
struct wl_seat;
struct wl_surface;
struct xdg_popup;
struct xdg_positioner;
struct xdg_surface;
struct xdg_toplevel;
struct xdg_wm_base;

#ifndef XDG_WM_BASE_INTERFACE
#define XDG_WM_BASE_INTERFACE
extern const struct wl_interface xdg_wm_base_interface;
#endif
#ifndef XDG_POSITIONER_INTERFACE
#define XDG_POSITIONER_INTERFACE
extern const struct wl_interface xdg_positioner_interface;
#endif
#ifndef XDG_SURFACE_INTERFACE
#define XDG_SURFACE_INTERFACE
extern const struct wl_interface xdg_surface_interface;
#endif
#ifndef XDG_TOPLEVEL_INTERFACE
#define XDG_TOPLEVEL_INTERFACE
extern const struct wl_interface xdg_toplevel_interface;
#endif
#ifndef XDG_POPUP_INTERFACE
#define XDG_POPUP_INTERFACE
extern const struct wl_interface xdg_popup_interface;
#endif

#ifndef XDG_WM_BASE_ERROR_ENUM
#define XDG_WM_BASE_ERROR_ENUM
enum xdg_wm_base_error {
	XDG_WM_BASE_ERROR_ROLE = 0,
	XDG_WM_BASE_ERROR_DEFUNCT_SURFACES = 1,
	XDG_WM_BASE_ERROR_NOT_THE_TOPMOST_POPUP = 2,
	XDG_WM_BASE_ERROR_INVALID_POPUP_PARENT = 3,
	XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE = 4,
	XDG_WM_BASE_ERROR_INVALID_POSITIONER = 5,
	XDG_WM_BASE_ERROR_UNRESPONSIVE = 6,
};
#endif

#ifndef XDG_WM_BASE_ERROR_ENUM_IS_VALID
#define XDG_WM_BASE_ERROR_ENUM_IS_VALID
static inline bool
xdg_wm_base_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_WM_BASE_ERROR_ROLE:
		return version >= 1;
	case XDG_WM_BASE_ERROR_DEFUNCT_SURFACES:
		return version >= 1;
	case XDG_WM_BASE_ERROR_NOT_THE_TOPMOST_POPUP:
		return version >= 1;
	case XDG_WM_BASE_ERROR_INVALID_POPUP_PARENT:
		return version >= 1;
	case XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE:
		return version >= 1;
	case XDG_WM_BASE_ERROR_INVALID_POSITIONER:
		return version >= 1;
	case XDG_WM_BASE_ERROR_UNRESPONSIVE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct xdg_wm_base_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*create_positioner)(struct wl_client *client,
				  struct wl_resource *resource,
				  uint32_t id);
	void (*get_xdg_surface)(struct wl_client *client,
				struct wl_resource *resource,
				uint32_t id,
				struct wl_resource *surface);
	void (*pong)(struct wl_client *client,
		     struct wl_resource *resource,
		     uint32_t serial);
};

#define XDG_WM_BASE_PING 0

#define XDG_WM_BASE_PING_SINCE_VERSION 1

#define XDG_WM_BASE_DESTROY_SINCE_VERSION 1
#define XDG_WM_BASE_CREATE_POSITIONER_SINCE_VERSION 1
#define XDG_WM_BASE_GET_XDG_SURFACE_SINCE_VERSION 1
#define XDG_WM_BASE_PONG_SINCE_VERSION 1

static inline void
xdg_wm_base_send_ping(struct wl_resource *resource_, uint32_t serial)
{
	wl_resource_post_event(resource_, XDG_WM_BASE_PING, serial);
}

#ifndef XDG_POSITIONER_ERROR_ENUM
#define XDG_POSITIONER_ERROR_ENUM
enum xdg_positioner_error {
	XDG_POSITIONER_ERROR_INVALID_INPUT = 0,
};
#endif

#ifndef XDG_POSITIONER_ERROR_ENUM_IS_VALID
#define XDG_POSITIONER_ERROR_ENUM_IS_VALID
static inline bool
xdg_positioner_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_POSITIONER_ERROR_INVALID_INPUT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef XDG_POSITIONER_ANCHOR_ENUM
#define XDG_POSITIONER_ANCHOR_ENUM
enum xdg_positioner_anchor {
	XDG_POSITIONER_ANCHOR_NONE = 0,
	XDG_POSITIONER_ANCHOR_TOP = 1,
	XDG_POSITIONER_ANCHOR_BOTTOM = 2,
	XDG_POSITIONER_ANCHOR_LEFT = 3,
	XDG_POSITIONER_ANCHOR_RIGHT = 4,
	XDG_POSITIONER_ANCHOR_TOP_LEFT = 5,
	XDG_POSITIONER_ANCHOR_BOTTOM_LEFT = 6,
	XDG_POSITIONER_ANCHOR_TOP_RIGHT = 7,
	XDG_POSITIONER_ANCHOR_BOTTOM_RIGHT = 8,
};
#endif

#ifndef XDG_POSITIONER_ANCHOR_ENUM_IS_VALID
#define XDG_POSITIONER_ANCHOR_ENUM_IS_VALID
static inline bool
xdg_positioner_anchor_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_POSITIONER_ANCHOR_NONE:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_TOP:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_BOTTOM:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_LEFT:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_RIGHT:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_TOP_LEFT:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_BOTTOM_LEFT:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_TOP_RIGHT:
		return version >= 1;
	case XDG_POSITIONER_ANCHOR_BOTTOM_RIGHT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef XDG_POSITIONER_GRAVITY_ENUM
#define XDG_POSITIONER_GRAVITY_ENUM
enum xdg_positioner_gravity {
	XDG_POSITIONER_GRAVITY_NONE = 0,
	XDG_POSITIONER_GRAVITY_TOP = 1,
	XDG_POSITIONER_GRAVITY_BOTTOM = 2,
	XDG_POSITIONER_GRAVITY_LEFT = 3,
	XDG_POSITIONER_GRAVITY_RIGHT = 4,
	XDG_POSITIONER_GRAVITY_TOP_LEFT = 5,
	XDG_POSITIONER_GRAVITY_BOTTOM_LEFT = 6,
	XDG_POSITIONER_GRAVITY_TOP_RIGHT = 7,
	XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT = 8,
};
#endif

#ifndef XDG_POSITIONER_GRAVITY_ENUM_IS_VALID
#define XDG_POSITIONER_GRAVITY_ENUM_IS_VALID
static inline bool
xdg_positioner_gravity_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_POSITIONER_GRAVITY_NONE:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_TOP:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_BOTTOM:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_LEFT:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_RIGHT:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_TOP_LEFT:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_BOTTOM_LEFT:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_TOP_RIGHT:
		return version >= 1;
	case XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_ENUM
#define XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_ENUM
enum xdg_positioner_constraint_adjustment {
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_NONE = 0,
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_X = 1,
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_Y = 2,
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_X = 4,
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_Y = 8,
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_RESIZE_X = 16,
	XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_RESIZE_Y = 32,
};
#endif

#ifndef XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_ENUM_IS_VALID
#define XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_ENUM_IS_VALID
static inline bool
xdg_positioner_constraint_adjustment_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_NONE;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_X;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_Y;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_X;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_FLIP_Y;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_RESIZE_X;
	if (version >= 1)
		valid |= XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_RESIZE_Y;
	return (value & ~valid) == 0;
}
#endif

struct xdg_positioner_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_size)(struct wl_client *client,
			 struct wl_resource *resource,
			 int32_t width,
			 int32_t height);
	void (*set_anchor_rect)(struct wl_client *client,
				struct wl_resource *resource,
				int32_t x,
				int32_t y,
				int32_t width,
				int32_t height);
	void (*set_anchor)(struct wl_client *client,
			   struct wl_resource *resource,
			   uint32_t anchor);
	void (*set_gravity)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t gravity);
	void (*set_constraint_adjustment)(struct wl_client *client,
					  struct wl_resource *resource,
					  uint32_t constraint_adjustment);
	void (*set_offset)(struct wl_client *client,
			   struct wl_resource *resource,
			   int32_t x,
			   int32_t y);
	void (*set_reactive)(struct wl_client *client,
			     struct wl_resource *resource);
	void (*set_parent_size)(struct wl_client *client,
				struct wl_resource *resource,
				int32_t parent_width,
				int32_t parent_height);
	void (*set_parent_configure)(struct wl_client *client,
				     struct wl_resource *resource,
				     uint32_t serial);
};


#define XDG_POSITIONER_DESTROY_SINCE_VERSION 1
#define XDG_POSITIONER_SET_SIZE_SINCE_VERSION 1
#define XDG_POSITIONER_SET_ANCHOR_RECT_SINCE_VERSION 1
#define XDG_POSITIONER_SET_ANCHOR_SINCE_VERSION 1
#define XDG_POSITIONER_SET_GRAVITY_SINCE_VERSION 1
#define XDG_POSITIONER_SET_CONSTRAINT_ADJUSTMENT_SINCE_VERSION 1
#define XDG_POSITIONER_SET_OFFSET_SINCE_VERSION 1
#define XDG_POSITIONER_SET_REACTIVE_SINCE_VERSION 3
#define XDG_POSITIONER_SET_PARENT_SIZE_SINCE_VERSION 3
#define XDG_POSITIONER_SET_PARENT_CONFIGURE_SINCE_VERSION 3

#ifndef XDG_SURFACE_ERROR_ENUM
#define XDG_SURFACE_ERROR_ENUM
enum xdg_surface_error {
	XDG_SURFACE_ERROR_NOT_CONSTRUCTED = 1,
	XDG_SURFACE_ERROR_ALREADY_CONSTRUCTED = 2,
	XDG_SURFACE_ERROR_UNCONFIGURED_BUFFER = 3,
	XDG_SURFACE_ERROR_INVALID_SERIAL = 4,
	XDG_SURFACE_ERROR_INVALID_SIZE = 5,
	XDG_SURFACE_ERROR_DEFUNCT_ROLE_OBJECT = 6,
};
#endif

#ifndef XDG_SURFACE_ERROR_ENUM_IS_VALID
#define XDG_SURFACE_ERROR_ENUM_IS_VALID
static inline bool
xdg_surface_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_SURFACE_ERROR_NOT_CONSTRUCTED:
		return version >= 1;
	case XDG_SURFACE_ERROR_ALREADY_CONSTRUCTED:
		return version >= 1;
	case XDG_SURFACE_ERROR_UNCONFIGURED_BUFFER:
		return version >= 1;
	case XDG_SURFACE_ERROR_INVALID_SERIAL:
		return version >= 1;
	case XDG_SURFACE_ERROR_INVALID_SIZE:
		return version >= 1;
	case XDG_SURFACE_ERROR_DEFUNCT_ROLE_OBJECT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct xdg_surface_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_toplevel)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t id);
	void (*get_popup)(struct wl_client *client,
			  struct wl_resource *resource,
			  uint32_t id,
			  struct wl_resource *parent,
			  struct wl_resource *positioner);
	void (*set_window_geometry)(struct wl_client *client,
				    struct wl_resource *resource,
				    int32_t x,
				    int32_t y,
				    int32_t width,
				    int32_t height);
	void (*ack_configure)(struct wl_client *client,
			      struct wl_resource *resource,
			      uint32_t serial);
};

#define XDG_SURFACE_CONFIGURE 0

#define XDG_SURFACE_CONFIGURE_SINCE_VERSION 1

#define XDG_SURFACE_DESTROY_SINCE_VERSION 1
#define XDG_SURFACE_GET_TOPLEVEL_SINCE_VERSION 1
#define XDG_SURFACE_GET_POPUP_SINCE_VERSION 1
#define XDG_SURFACE_SET_WINDOW_GEOMETRY_SINCE_VERSION 1
#define XDG_SURFACE_ACK_CONFIGURE_SINCE_VERSION 1

static inline void
xdg_surface_send_configure(struct wl_resource *resource_, uint32_t serial)
{
	wl_resource_post_event(resource_, XDG_SURFACE_CONFIGURE, serial);
}

#ifndef XDG_TOPLEVEL_ERROR_ENUM
#define XDG_TOPLEVEL_ERROR_ENUM
enum xdg_toplevel_error {
	XDG_TOPLEVEL_ERROR_INVALID_RESIZE_EDGE = 0,
	XDG_TOPLEVEL_ERROR_INVALID_PARENT = 1,
	XDG_TOPLEVEL_ERROR_INVALID_SIZE = 2,
};
#endif

#ifndef XDG_TOPLEVEL_ERROR_ENUM_IS_VALID
#define XDG_TOPLEVEL_ERROR_ENUM_IS_VALID
static inline bool
xdg_toplevel_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_TOPLEVEL_ERROR_INVALID_RESIZE_EDGE:
		return version >= 1;
	case XDG_TOPLEVEL_ERROR_INVALID_PARENT:
		return version >= 1;
	case XDG_TOPLEVEL_ERROR_INVALID_SIZE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef XDG_TOPLEVEL_RESIZE_EDGE_ENUM
#define XDG_TOPLEVEL_RESIZE_EDGE_ENUM
enum xdg_toplevel_resize_edge {
	XDG_TOPLEVEL_RESIZE_EDGE_NONE = 0,
	XDG_TOPLEVEL_RESIZE_EDGE_TOP = 1,
	XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM = 2,
	XDG_TOPLEVEL_RESIZE_EDGE_LEFT = 4,
	XDG_TOPLEVEL_RESIZE_EDGE_TOP_LEFT = 5,
	XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_LEFT = 6,
	XDG_TOPLEVEL_RESIZE_EDGE_RIGHT = 8,
	XDG_TOPLEVEL_RESIZE_EDGE_TOP_RIGHT = 9,
	XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT = 10,
};
#endif

#ifndef XDG_TOPLEVEL_RESIZE_EDGE_ENUM_IS_VALID
#define XDG_TOPLEVEL_RESIZE_EDGE_ENUM_IS_VALID
static inline bool
xdg_toplevel_resize_edge_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_TOPLEVEL_RESIZE_EDGE_NONE:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_TOP:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_LEFT:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_TOP_LEFT:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_LEFT:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_RIGHT:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_TOP_RIGHT:
		return version >= 1;
	case XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef XDG_TOPLEVEL_STATE_ENUM
#define XDG_TOPLEVEL_STATE_ENUM
enum xdg_toplevel_state {
	XDG_TOPLEVEL_STATE_MAXIMIZED = 1,
	XDG_TOPLEVEL_STATE_FULLSCREEN = 2,
	XDG_TOPLEVEL_STATE_RESIZING = 3,
	XDG_TOPLEVEL_STATE_ACTIVATED = 4,
	XDG_TOPLEVEL_STATE_TILED_LEFT = 5,
	XDG_TOPLEVEL_STATE_TILED_RIGHT = 6,
	XDG_TOPLEVEL_STATE_TILED_TOP = 7,
	XDG_TOPLEVEL_STATE_TILED_BOTTOM = 8,
};
#define XDG_TOPLEVEL_STATE_TILED_LEFT_SINCE_VERSION 2
#define XDG_TOPLEVEL_STATE_TILED_RIGHT_SINCE_VERSION 2
#define XDG_TOPLEVEL_STATE_TILED_TOP_SINCE_VERSION 2
#define XDG_TOPLEVEL_STATE_TILED_BOTTOM_SINCE_VERSION 2
#endif

#ifndef XDG_TOPLEVEL_STATE_ENUM_IS_VALID
#define XDG_TOPLEVEL_STATE_ENUM_IS_VALID
static inline bool
xdg_toplevel_state_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_TOPLEVEL_STATE_MAXIMIZED:
		return version >= 1;
	case XDG_TOPLEVEL_STATE_FULLSCREEN:
		return version >= 1;
	case XDG_TOPLEVEL_STATE_RESIZING:
		return version >= 1;
	case XDG_TOPLEVEL_STATE_ACTIVATED:
		return version >= 1;
	case XDG_TOPLEVEL_STATE_TILED_LEFT:
		return version >= 2;
	case XDG_TOPLEVEL_STATE_TILED_RIGHT:
		return version >= 2;
	case XDG_TOPLEVEL_STATE_TILED_TOP:
		return version >= 2;
	case XDG_TOPLEVEL_STATE_TILED_BOTTOM:
		return version >= 2;
	default:
		return false;
	}
}
#endif

#ifndef XDG_TOPLEVEL_WM_CAPABILITIES_ENUM
#define XDG_TOPLEVEL_WM_CAPABILITIES_ENUM
enum xdg_toplevel_wm_capabilities {
	XDG_TOPLEVEL_WM_CAPABILITIES_WINDOW_MENU = 1,
	XDG_TOPLEVEL_WM_CAPABILITIES_MAXIMIZE = 2,
	XDG_TOPLEVEL_WM_CAPABILITIES_FULLSCREEN = 3,
	XDG_TOPLEVEL_WM_CAPABILITIES_MINIMIZE = 4,
};
#endif

#ifndef XDG_TOPLEVEL_WM_CAPABILITIES_ENUM_IS_VALID
#define XDG_TOPLEVEL_WM_CAPABILITIES_ENUM_IS_VALID
static inline bool
xdg_toplevel_wm_capabilities_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_TOPLEVEL_WM_CAPABILITIES_WINDOW_MENU:
		return version >= 1;
	case XDG_TOPLEVEL_WM_CAPABILITIES_MAXIMIZE:
		return version >= 1;
	case XDG_TOPLEVEL_WM_CAPABILITIES_FULLSCREEN:
		return version >= 1;
	case XDG_TOPLEVEL_WM_CAPABILITIES_MINIMIZE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct xdg_toplevel_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_parent)(struct wl_client *client,
			   struct wl_resource *resource,
			   struct wl_resource *parent);
	void (*set_title)(struct wl_client *client,
			  struct wl_resource *resource,
			  const char *title);
	void (*set_app_id)(struct wl_client *client,
			   struct wl_resource *resource,
			   const char *app_id);
	void (*show_window_menu)(struct wl_client *client,
				 struct wl_resource *resource,
				 struct wl_resource *seat,
				 uint32_t serial,
				 int32_t x,
				 int32_t y);
	void (*move)(struct wl_client *client,
		     struct wl_resource *resource,
		     struct wl_resource *seat,
		     uint32_t serial);
	void (*resize)(struct wl_client *client,
		       struct wl_resource *resource,
		       struct wl_resource *seat,
		       uint32_t serial,
		       uint32_t edges);
	void (*set_max_size)(struct wl_client *client,
			     struct wl_resource *resource,
			     int32_t width,
			     int32_t height);
	void (*set_min_size)(struct wl_client *client,
			     struct wl_resource *resource,
			     int32_t width,
			     int32_t height);
	void (*set_maximized)(struct wl_client *client,
			      struct wl_resource *resource);
	void (*unset_maximized)(struct wl_client *client,
				struct wl_resource *resource);
	void (*set_fullscreen)(struct wl_client *client,
			       struct wl_resource *resource,
			       struct wl_resource *output);
	void (*unset_fullscreen)(struct wl_client *client,
				 struct wl_resource *resource);
	void (*set_minimized)(struct wl_client *client,
			      struct wl_resource *resource);
};

#define XDG_TOPLEVEL_CONFIGURE 0
#define XDG_TOPLEVEL_CLOSE 1
#define XDG_TOPLEVEL_CONFIGURE_BOUNDS 2
#define XDG_TOPLEVEL_WM_CAPABILITIES 3

#define XDG_TOPLEVEL_CONFIGURE_SINCE_VERSION 1
#define XDG_TOPLEVEL_CLOSE_SINCE_VERSION 1
#define XDG_TOPLEVEL_CONFIGURE_BOUNDS_SINCE_VERSION 4
#define XDG_TOPLEVEL_WM_CAPABILITIES_SINCE_VERSION 5

#define XDG_TOPLEVEL_DESTROY_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_PARENT_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_TITLE_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_APP_ID_SINCE_VERSION 1
#define XDG_TOPLEVEL_SHOW_WINDOW_MENU_SINCE_VERSION 1
#define XDG_TOPLEVEL_MOVE_SINCE_VERSION 1
#define XDG_TOPLEVEL_RESIZE_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_MAX_SIZE_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_MIN_SIZE_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_MAXIMIZED_SINCE_VERSION 1
#define XDG_TOPLEVEL_UNSET_MAXIMIZED_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_FULLSCREEN_SINCE_VERSION 1
#define XDG_TOPLEVEL_UNSET_FULLSCREEN_SINCE_VERSION 1
#define XDG_TOPLEVEL_SET_MINIMIZED_SINCE_VERSION 1

static inline void
xdg_toplevel_send_configure(struct wl_resource *resource_, int32_t width, int32_t height, struct wl_array *states)
{
	wl_resource_post_event(resource_, XDG_TOPLEVEL_CONFIGURE, width, height, states);
}

static inline void
xdg_toplevel_send_close(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, XDG_TOPLEVEL_CLOSE);
}

static inline void
xdg_toplevel_send_configure_bounds(struct wl_resource *resource_, int32_t width, int32_t height)
{
	wl_resource_post_event(resource_, XDG_TOPLEVEL_CONFIGURE_BOUNDS, width, height);
}

static inline void
xdg_toplevel_send_wm_capabilities(struct wl_resource *resource_, struct wl_array *capabilities)
{
	wl_resource_post_event(resource_, XDG_TOPLEVEL_WM_CAPABILITIES, capabilities);
}

#ifndef XDG_POPUP_ERROR_ENUM
#define XDG_POPUP_ERROR_ENUM
enum xdg_popup_error {
	XDG_POPUP_ERROR_INVALID_GRAB = 0,
};
#endif

#ifndef XDG_POPUP_ERROR_ENUM_IS_VALID
#define XDG_POPUP_ERROR_ENUM_IS_VALID
static inline bool
xdg_popup_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case XDG_POPUP_ERROR_INVALID_GRAB:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct xdg_popup_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*grab)(struct wl_client *client,
		     struct wl_resource *resource,
		     struct wl_resource *seat,
		     uint32_t serial);
	void (*reposition)(struct wl_client *client,
			   struct wl_resource *resource,
			   struct wl_resource *positioner,
			   uint32_t token);
};

#define XDG_POPUP_CONFIGURE 0
#define XDG_POPUP_POPUP_DONE 1
#define XDG_POPUP_REPOSITIONED 2

#define XDG_POPUP_CONFIGURE_SINCE_VERSION 1
#define XDG_POPUP_POPUP_DONE_SINCE_VERSION 1
#define XDG_POPUP_REPOSITIONED_SINCE_VERSION 3

#define XDG_POPUP_DESTROY_SINCE_VERSION 1
#define XDG_POPUP_GRAB_SINCE_VERSION 1
#define XDG_POPUP_REPOSITION_SINCE_VERSION 3

static inline void
xdg_popup_send_configure(struct wl_resource *resource_, int32_t x, int32_t y, int32_t width, int32_t height)
{
	wl_resource_post_event(resource_, XDG_POPUP_CONFIGURE, x, y, width, height);
}

static inline void
xdg_popup_send_popup_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, XDG_POPUP_POPUP_DONE);
}

static inline void
xdg_popup_send_repositioned(struct wl_resource *resource_, uint32_t token)
{
	wl_resource_post_event(resource_, XDG_POPUP_REPOSITIONED, token);
}

#ifdef  __cplusplus
}
#endif

#endif
