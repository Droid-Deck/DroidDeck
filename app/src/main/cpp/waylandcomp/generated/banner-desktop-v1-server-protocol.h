
#ifndef BANNER_DESKTOP_V1_SERVER_PROTOCOL_H
#define BANNER_DESKTOP_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_banner_desktop_v1 Copyright
 *
 * Copyright 2026 The412Banner
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





struct banner_desktop_v1;
struct wl_surface;

#ifndef BANNER_DESKTOP_V1_INTERFACE
#define BANNER_DESKTOP_V1_INTERFACE
extern const struct wl_interface banner_desktop_v1_interface;
#endif

struct banner_desktop_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_desktop)(struct wl_client *client,
			    struct wl_resource *resource,
			    struct wl_resource *surface);
	void (*set_window)(struct wl_client *client,
			   struct wl_resource *resource,
			   struct wl_resource *surface,
			   uint32_t hwnd,
			   int32_t x,
			   int32_t y);
	void (*set_zorder)(struct wl_client *client,
			   struct wl_resource *resource,
			   struct wl_array *hwnds);
};


#define BANNER_DESKTOP_V1_DESTROY_SINCE_VERSION 1
#define BANNER_DESKTOP_V1_SET_DESKTOP_SINCE_VERSION 1
#define BANNER_DESKTOP_V1_SET_WINDOW_SINCE_VERSION 1
#define BANNER_DESKTOP_V1_SET_ZORDER_SINCE_VERSION 1

#ifdef  __cplusplus
}
#endif

#endif
