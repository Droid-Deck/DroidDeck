
#ifndef WAYLAND_SERVER_PROTOCOL_H
#define WAYLAND_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_wayland Copyright
 *
 * Copyright © 2008-2011 Kristian Høgsberg
 * Copyright © 2010-2011 Intel Corporation
 * Copyright © 2012-2013 Collabora, Ltd.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation files
 * (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice (including the
 * next paragraph) shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */



























struct wl_buffer;
struct wl_callback;
struct wl_compositor;
struct wl_data_device;
struct wl_data_device_manager;
struct wl_data_offer;
struct wl_data_source;
struct wl_display;
struct wl_fixes;
struct wl_keyboard;
struct wl_output;
struct wl_pointer;
struct wl_region;
struct wl_registry;
struct wl_seat;
struct wl_shell;
struct wl_shell_surface;
struct wl_shm;
struct wl_shm_pool;
struct wl_subcompositor;
struct wl_subsurface;
struct wl_surface;
struct wl_touch;

#ifndef WL_DISPLAY_INTERFACE
#define WL_DISPLAY_INTERFACE
extern const struct wl_interface wl_display_interface;
#endif
#ifndef WL_REGISTRY_INTERFACE
#define WL_REGISTRY_INTERFACE
extern const struct wl_interface wl_registry_interface;
#endif
#ifndef WL_CALLBACK_INTERFACE
#define WL_CALLBACK_INTERFACE
extern const struct wl_interface wl_callback_interface;
#endif
#ifndef WL_COMPOSITOR_INTERFACE
#define WL_COMPOSITOR_INTERFACE
extern const struct wl_interface wl_compositor_interface;
#endif
#ifndef WL_SHM_POOL_INTERFACE
#define WL_SHM_POOL_INTERFACE
extern const struct wl_interface wl_shm_pool_interface;
#endif
#ifndef WL_SHM_INTERFACE
#define WL_SHM_INTERFACE
extern const struct wl_interface wl_shm_interface;
#endif
#ifndef WL_BUFFER_INTERFACE
#define WL_BUFFER_INTERFACE
extern const struct wl_interface wl_buffer_interface;
#endif
#ifndef WL_DATA_OFFER_INTERFACE
#define WL_DATA_OFFER_INTERFACE
extern const struct wl_interface wl_data_offer_interface;
#endif
#ifndef WL_DATA_SOURCE_INTERFACE
#define WL_DATA_SOURCE_INTERFACE
extern const struct wl_interface wl_data_source_interface;
#endif
#ifndef WL_DATA_DEVICE_INTERFACE
#define WL_DATA_DEVICE_INTERFACE
extern const struct wl_interface wl_data_device_interface;
#endif
#ifndef WL_DATA_DEVICE_MANAGER_INTERFACE
#define WL_DATA_DEVICE_MANAGER_INTERFACE
extern const struct wl_interface wl_data_device_manager_interface;
#endif
#ifndef WL_SHELL_INTERFACE
#define WL_SHELL_INTERFACE
extern const struct wl_interface wl_shell_interface;
#endif
#ifndef WL_SHELL_SURFACE_INTERFACE
#define WL_SHELL_SURFACE_INTERFACE
extern const struct wl_interface wl_shell_surface_interface;
#endif
#ifndef WL_SURFACE_INTERFACE
#define WL_SURFACE_INTERFACE
extern const struct wl_interface wl_surface_interface;
#endif
#ifndef WL_SEAT_INTERFACE
#define WL_SEAT_INTERFACE
extern const struct wl_interface wl_seat_interface;
#endif
#ifndef WL_POINTER_INTERFACE
#define WL_POINTER_INTERFACE
extern const struct wl_interface wl_pointer_interface;
#endif
#ifndef WL_KEYBOARD_INTERFACE
#define WL_KEYBOARD_INTERFACE
extern const struct wl_interface wl_keyboard_interface;
#endif
#ifndef WL_TOUCH_INTERFACE
#define WL_TOUCH_INTERFACE
extern const struct wl_interface wl_touch_interface;
#endif
#ifndef WL_OUTPUT_INTERFACE
#define WL_OUTPUT_INTERFACE
extern const struct wl_interface wl_output_interface;
#endif
#ifndef WL_REGION_INTERFACE
#define WL_REGION_INTERFACE
extern const struct wl_interface wl_region_interface;
#endif
#ifndef WL_SUBCOMPOSITOR_INTERFACE
#define WL_SUBCOMPOSITOR_INTERFACE
extern const struct wl_interface wl_subcompositor_interface;
#endif
#ifndef WL_SUBSURFACE_INTERFACE
#define WL_SUBSURFACE_INTERFACE
extern const struct wl_interface wl_subsurface_interface;
#endif
#ifndef WL_FIXES_INTERFACE
#define WL_FIXES_INTERFACE
extern const struct wl_interface wl_fixes_interface;
#endif

#ifndef WL_DISPLAY_ERROR_ENUM
#define WL_DISPLAY_ERROR_ENUM
enum wl_display_error {
	WL_DISPLAY_ERROR_INVALID_OBJECT = 0,
	WL_DISPLAY_ERROR_INVALID_METHOD = 1,
	WL_DISPLAY_ERROR_NO_MEMORY = 2,
	WL_DISPLAY_ERROR_IMPLEMENTATION = 3,
};
#endif

#ifndef WL_DISPLAY_ERROR_ENUM_IS_VALID
#define WL_DISPLAY_ERROR_ENUM_IS_VALID
static inline bool
wl_display_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_DISPLAY_ERROR_INVALID_OBJECT:
		return version >= 1;
	case WL_DISPLAY_ERROR_INVALID_METHOD:
		return version >= 1;
	case WL_DISPLAY_ERROR_NO_MEMORY:
		return version >= 1;
	case WL_DISPLAY_ERROR_IMPLEMENTATION:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_display_interface {
	void (*sync)(struct wl_client *client,
		     struct wl_resource *resource,
		     uint32_t callback);
	void (*get_registry)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t registry);
};

#define WL_DISPLAY_ERROR 0
#define WL_DISPLAY_DELETE_ID 1

#define WL_DISPLAY_ERROR_SINCE_VERSION 1
#define WL_DISPLAY_DELETE_ID_SINCE_VERSION 1

#define WL_DISPLAY_SYNC_SINCE_VERSION 1
#define WL_DISPLAY_GET_REGISTRY_SINCE_VERSION 1

struct wl_registry_interface {
	void (*bind)(struct wl_client *client,
		     struct wl_resource *resource,
		     uint32_t name,
		     const char *interface, uint32_t version, uint32_t id);
};

#define WL_REGISTRY_GLOBAL 0
#define WL_REGISTRY_GLOBAL_REMOVE 1

#define WL_REGISTRY_GLOBAL_SINCE_VERSION 1
#define WL_REGISTRY_GLOBAL_REMOVE_SINCE_VERSION 1

#define WL_REGISTRY_BIND_SINCE_VERSION 1

static inline void
wl_registry_send_global(struct wl_resource *resource_, uint32_t name, const char *interface, uint32_t version)
{
	wl_resource_post_event(resource_, WL_REGISTRY_GLOBAL, name, interface, version);
}

static inline void
wl_registry_send_global_remove(struct wl_resource *resource_, uint32_t name)
{
	wl_resource_post_event(resource_, WL_REGISTRY_GLOBAL_REMOVE, name);
}

#define WL_CALLBACK_DONE 0

#define WL_CALLBACK_DONE_SINCE_VERSION 1


static inline void
wl_callback_send_done(struct wl_resource *resource_, uint32_t callback_data)
{
	wl_resource_post_event(resource_, WL_CALLBACK_DONE, callback_data);
}

struct wl_compositor_interface {
	void (*create_surface)(struct wl_client *client,
			       struct wl_resource *resource,
			       uint32_t id);
	void (*create_region)(struct wl_client *client,
			      struct wl_resource *resource,
			      uint32_t id);
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};


#define WL_COMPOSITOR_CREATE_SURFACE_SINCE_VERSION 1
#define WL_COMPOSITOR_CREATE_REGION_SINCE_VERSION 1
#define WL_COMPOSITOR_RELEASE_SINCE_VERSION 7

#ifndef WL_SHM_POOL_ERROR_ENUM
#define WL_SHM_POOL_ERROR_ENUM
enum wl_shm_pool_error {
	WL_SHM_POOL_ERROR_INVALID_FORMAT = 0,
	WL_SHM_POOL_ERROR_INVALID_STRIDE = 1,
};
#endif

#ifndef WL_SHM_POOL_ERROR_ENUM_IS_VALID
#define WL_SHM_POOL_ERROR_ENUM_IS_VALID
static inline bool
wl_shm_pool_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SHM_POOL_ERROR_INVALID_FORMAT:
		return version >= 1;
	case WL_SHM_POOL_ERROR_INVALID_STRIDE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_shm_pool_interface {
	void (*create_buffer)(struct wl_client *client,
			      struct wl_resource *resource,
			      uint32_t id,
			      int32_t offset,
			      int32_t width,
			      int32_t height,
			      int32_t stride,
			      uint32_t format);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*resize)(struct wl_client *client,
		       struct wl_resource *resource,
		       int32_t size);
};


#define WL_SHM_POOL_CREATE_BUFFER_SINCE_VERSION 1
#define WL_SHM_POOL_DESTROY_SINCE_VERSION 1
#define WL_SHM_POOL_RESIZE_SINCE_VERSION 1

#ifndef WL_SHM_ERROR_ENUM
#define WL_SHM_ERROR_ENUM
enum wl_shm_error {
	WL_SHM_ERROR_INVALID_FORMAT = 0,
	WL_SHM_ERROR_INVALID_STRIDE = 1,
	WL_SHM_ERROR_INVALID_FD = 2,
};
#endif

#ifndef WL_SHM_ERROR_ENUM_IS_VALID
#define WL_SHM_ERROR_ENUM_IS_VALID
static inline bool
wl_shm_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SHM_ERROR_INVALID_FORMAT:
		return version >= 1;
	case WL_SHM_ERROR_INVALID_STRIDE:
		return version >= 1;
	case WL_SHM_ERROR_INVALID_FD:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_SHM_FORMAT_ENUM
#define WL_SHM_FORMAT_ENUM
enum wl_shm_format {
	WL_SHM_FORMAT_ARGB8888 = 0,
	WL_SHM_FORMAT_XRGB8888 = 1,
	WL_SHM_FORMAT_C8 = 0x20203843,
	WL_SHM_FORMAT_RGB332 = 0x38424752,
	WL_SHM_FORMAT_BGR233 = 0x38524742,
	WL_SHM_FORMAT_XRGB4444 = 0x32315258,
	WL_SHM_FORMAT_XBGR4444 = 0x32314258,
	WL_SHM_FORMAT_RGBX4444 = 0x32315852,
	WL_SHM_FORMAT_BGRX4444 = 0x32315842,
	WL_SHM_FORMAT_ARGB4444 = 0x32315241,
	WL_SHM_FORMAT_ABGR4444 = 0x32314241,
	WL_SHM_FORMAT_RGBA4444 = 0x32314152,
	WL_SHM_FORMAT_BGRA4444 = 0x32314142,
	WL_SHM_FORMAT_XRGB1555 = 0x35315258,
	WL_SHM_FORMAT_XBGR1555 = 0x35314258,
	WL_SHM_FORMAT_RGBX5551 = 0x35315852,
	WL_SHM_FORMAT_BGRX5551 = 0x35315842,
	WL_SHM_FORMAT_ARGB1555 = 0x35315241,
	WL_SHM_FORMAT_ABGR1555 = 0x35314241,
	WL_SHM_FORMAT_RGBA5551 = 0x35314152,
	WL_SHM_FORMAT_BGRA5551 = 0x35314142,
	WL_SHM_FORMAT_RGB565 = 0x36314752,
	WL_SHM_FORMAT_BGR565 = 0x36314742,
	WL_SHM_FORMAT_RGB888 = 0x34324752,
	WL_SHM_FORMAT_BGR888 = 0x34324742,
	WL_SHM_FORMAT_XBGR8888 = 0x34324258,
	WL_SHM_FORMAT_RGBX8888 = 0x34325852,
	WL_SHM_FORMAT_BGRX8888 = 0x34325842,
	WL_SHM_FORMAT_ABGR8888 = 0x34324241,
	WL_SHM_FORMAT_RGBA8888 = 0x34324152,
	WL_SHM_FORMAT_BGRA8888 = 0x34324142,
	WL_SHM_FORMAT_XRGB2101010 = 0x30335258,
	WL_SHM_FORMAT_XBGR2101010 = 0x30334258,
	WL_SHM_FORMAT_RGBX1010102 = 0x30335852,
	WL_SHM_FORMAT_BGRX1010102 = 0x30335842,
	WL_SHM_FORMAT_ARGB2101010 = 0x30335241,
	WL_SHM_FORMAT_ABGR2101010 = 0x30334241,
	WL_SHM_FORMAT_RGBA1010102 = 0x30334152,
	WL_SHM_FORMAT_BGRA1010102 = 0x30334142,
	WL_SHM_FORMAT_YUYV = 0x56595559,
	WL_SHM_FORMAT_YVYU = 0x55595659,
	WL_SHM_FORMAT_UYVY = 0x59565955,
	WL_SHM_FORMAT_VYUY = 0x59555956,
	WL_SHM_FORMAT_AYUV = 0x56555941,
	WL_SHM_FORMAT_NV12 = 0x3231564e,
	WL_SHM_FORMAT_NV21 = 0x3132564e,
	WL_SHM_FORMAT_NV16 = 0x3631564e,
	WL_SHM_FORMAT_NV61 = 0x3136564e,
	WL_SHM_FORMAT_YUV410 = 0x39565559,
	WL_SHM_FORMAT_YVU410 = 0x39555659,
	WL_SHM_FORMAT_YUV411 = 0x31315559,
	WL_SHM_FORMAT_YVU411 = 0x31315659,
	WL_SHM_FORMAT_YUV420 = 0x32315559,
	WL_SHM_FORMAT_YVU420 = 0x32315659,
	WL_SHM_FORMAT_YUV422 = 0x36315559,
	WL_SHM_FORMAT_YVU422 = 0x36315659,
	WL_SHM_FORMAT_YUV444 = 0x34325559,
	WL_SHM_FORMAT_YVU444 = 0x34325659,
	WL_SHM_FORMAT_R8 = 0x20203852,
	WL_SHM_FORMAT_R16 = 0x20363152,
	WL_SHM_FORMAT_RG88 = 0x38384752,
	WL_SHM_FORMAT_GR88 = 0x38385247,
	WL_SHM_FORMAT_RG1616 = 0x32334752,
	WL_SHM_FORMAT_GR1616 = 0x32335247,
	WL_SHM_FORMAT_XRGB16161616F = 0x48345258,
	WL_SHM_FORMAT_XBGR16161616F = 0x48344258,
	WL_SHM_FORMAT_ARGB16161616F = 0x48345241,
	WL_SHM_FORMAT_ABGR16161616F = 0x48344241,
	WL_SHM_FORMAT_XYUV8888 = 0x56555958,
	WL_SHM_FORMAT_VUY888 = 0x34325556,
	WL_SHM_FORMAT_VUY101010 = 0x30335556,
	WL_SHM_FORMAT_Y210 = 0x30313259,
	WL_SHM_FORMAT_Y212 = 0x32313259,
	WL_SHM_FORMAT_Y216 = 0x36313259,
	WL_SHM_FORMAT_Y410 = 0x30313459,
	WL_SHM_FORMAT_Y412 = 0x32313459,
	WL_SHM_FORMAT_Y416 = 0x36313459,
	WL_SHM_FORMAT_XVYU2101010 = 0x30335658,
	WL_SHM_FORMAT_XVYU12_16161616 = 0x36335658,
	WL_SHM_FORMAT_XVYU16161616 = 0x38345658,
	WL_SHM_FORMAT_Y0L0 = 0x304c3059,
	WL_SHM_FORMAT_X0L0 = 0x304c3058,
	WL_SHM_FORMAT_Y0L2 = 0x324c3059,
	WL_SHM_FORMAT_X0L2 = 0x324c3058,
	WL_SHM_FORMAT_YUV420_8BIT = 0x38305559,
	WL_SHM_FORMAT_YUV420_10BIT = 0x30315559,
	WL_SHM_FORMAT_XRGB8888_A8 = 0x38415258,
	WL_SHM_FORMAT_XBGR8888_A8 = 0x38414258,
	WL_SHM_FORMAT_RGBX8888_A8 = 0x38415852,
	WL_SHM_FORMAT_BGRX8888_A8 = 0x38415842,
	WL_SHM_FORMAT_RGB888_A8 = 0x38413852,
	WL_SHM_FORMAT_BGR888_A8 = 0x38413842,
	WL_SHM_FORMAT_RGB565_A8 = 0x38413552,
	WL_SHM_FORMAT_BGR565_A8 = 0x38413542,
	WL_SHM_FORMAT_NV24 = 0x3432564e,
	WL_SHM_FORMAT_NV42 = 0x3234564e,
	WL_SHM_FORMAT_P210 = 0x30313250,
	WL_SHM_FORMAT_P010 = 0x30313050,
	WL_SHM_FORMAT_P012 = 0x32313050,
	WL_SHM_FORMAT_P016 = 0x36313050,
	WL_SHM_FORMAT_AXBXGXRX106106106106 = 0x30314241,
	WL_SHM_FORMAT_NV15 = 0x3531564e,
	WL_SHM_FORMAT_Q410 = 0x30313451,
	WL_SHM_FORMAT_Q401 = 0x31303451,
	WL_SHM_FORMAT_XRGB16161616 = 0x38345258,
	WL_SHM_FORMAT_XBGR16161616 = 0x38344258,
	WL_SHM_FORMAT_ARGB16161616 = 0x38345241,
	WL_SHM_FORMAT_ABGR16161616 = 0x38344241,
	WL_SHM_FORMAT_C1 = 0x20203143,
	WL_SHM_FORMAT_C2 = 0x20203243,
	WL_SHM_FORMAT_C4 = 0x20203443,
	WL_SHM_FORMAT_D1 = 0x20203144,
	WL_SHM_FORMAT_D2 = 0x20203244,
	WL_SHM_FORMAT_D4 = 0x20203444,
	WL_SHM_FORMAT_D8 = 0x20203844,
	WL_SHM_FORMAT_R1 = 0x20203152,
	WL_SHM_FORMAT_R2 = 0x20203252,
	WL_SHM_FORMAT_R4 = 0x20203452,
	WL_SHM_FORMAT_R10 = 0x20303152,
	WL_SHM_FORMAT_R12 = 0x20323152,
	WL_SHM_FORMAT_AVUY8888 = 0x59555641,
	WL_SHM_FORMAT_XVUY8888 = 0x59555658,
	WL_SHM_FORMAT_P030 = 0x30333050,
	WL_SHM_FORMAT_RGB161616 = 0x38344752,
	WL_SHM_FORMAT_BGR161616 = 0x38344742,
	WL_SHM_FORMAT_R16F = 0x48202052,
	WL_SHM_FORMAT_GR1616F = 0x48205247,
	WL_SHM_FORMAT_BGR161616F = 0x48524742,
	WL_SHM_FORMAT_R32F = 0x46202052,
	WL_SHM_FORMAT_GR3232F = 0x46205247,
	WL_SHM_FORMAT_BGR323232F = 0x46524742,
	WL_SHM_FORMAT_ABGR32323232F = 0x46384241,
	WL_SHM_FORMAT_NV20 = 0x3032564e,
	WL_SHM_FORMAT_NV30 = 0x3033564e,
	WL_SHM_FORMAT_S010 = 0x30313053,
	WL_SHM_FORMAT_S210 = 0x30313253,
	WL_SHM_FORMAT_S410 = 0x30313453,
	WL_SHM_FORMAT_S012 = 0x32313053,
	WL_SHM_FORMAT_S212 = 0x32313253,
	WL_SHM_FORMAT_S412 = 0x32313453,
	WL_SHM_FORMAT_S016 = 0x36313053,
	WL_SHM_FORMAT_S216 = 0x36313253,
	WL_SHM_FORMAT_S416 = 0x36313453,
	WL_SHM_FORMAT_XVUY2101010 = 0x30335958,
	WL_SHM_FORMAT_P230 = 0x30333250,
	WL_SHM_FORMAT_T430 = 0x30333454,
	WL_SHM_FORMAT_Y8 = 0x59455247,
	WL_SHM_FORMAT_XYYY2101010 = 0x34415059,
};
#endif

#ifndef WL_SHM_FORMAT_ENUM_IS_VALID
#define WL_SHM_FORMAT_ENUM_IS_VALID
static inline bool
wl_shm_format_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SHM_FORMAT_ARGB8888:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB8888:
		return version >= 1;
	case WL_SHM_FORMAT_C8:
		return version >= 1;
	case WL_SHM_FORMAT_RGB332:
		return version >= 1;
	case WL_SHM_FORMAT_BGR233:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB4444:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR4444:
		return version >= 1;
	case WL_SHM_FORMAT_RGBX4444:
		return version >= 1;
	case WL_SHM_FORMAT_BGRX4444:
		return version >= 1;
	case WL_SHM_FORMAT_ARGB4444:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR4444:
		return version >= 1;
	case WL_SHM_FORMAT_RGBA4444:
		return version >= 1;
	case WL_SHM_FORMAT_BGRA4444:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB1555:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR1555:
		return version >= 1;
	case WL_SHM_FORMAT_RGBX5551:
		return version >= 1;
	case WL_SHM_FORMAT_BGRX5551:
		return version >= 1;
	case WL_SHM_FORMAT_ARGB1555:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR1555:
		return version >= 1;
	case WL_SHM_FORMAT_RGBA5551:
		return version >= 1;
	case WL_SHM_FORMAT_BGRA5551:
		return version >= 1;
	case WL_SHM_FORMAT_RGB565:
		return version >= 1;
	case WL_SHM_FORMAT_BGR565:
		return version >= 1;
	case WL_SHM_FORMAT_RGB888:
		return version >= 1;
	case WL_SHM_FORMAT_BGR888:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR8888:
		return version >= 1;
	case WL_SHM_FORMAT_RGBX8888:
		return version >= 1;
	case WL_SHM_FORMAT_BGRX8888:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR8888:
		return version >= 1;
	case WL_SHM_FORMAT_RGBA8888:
		return version >= 1;
	case WL_SHM_FORMAT_BGRA8888:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB2101010:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR2101010:
		return version >= 1;
	case WL_SHM_FORMAT_RGBX1010102:
		return version >= 1;
	case WL_SHM_FORMAT_BGRX1010102:
		return version >= 1;
	case WL_SHM_FORMAT_ARGB2101010:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR2101010:
		return version >= 1;
	case WL_SHM_FORMAT_RGBA1010102:
		return version >= 1;
	case WL_SHM_FORMAT_BGRA1010102:
		return version >= 1;
	case WL_SHM_FORMAT_YUYV:
		return version >= 1;
	case WL_SHM_FORMAT_YVYU:
		return version >= 1;
	case WL_SHM_FORMAT_UYVY:
		return version >= 1;
	case WL_SHM_FORMAT_VYUY:
		return version >= 1;
	case WL_SHM_FORMAT_AYUV:
		return version >= 1;
	case WL_SHM_FORMAT_NV12:
		return version >= 1;
	case WL_SHM_FORMAT_NV21:
		return version >= 1;
	case WL_SHM_FORMAT_NV16:
		return version >= 1;
	case WL_SHM_FORMAT_NV61:
		return version >= 1;
	case WL_SHM_FORMAT_YUV410:
		return version >= 1;
	case WL_SHM_FORMAT_YVU410:
		return version >= 1;
	case WL_SHM_FORMAT_YUV411:
		return version >= 1;
	case WL_SHM_FORMAT_YVU411:
		return version >= 1;
	case WL_SHM_FORMAT_YUV420:
		return version >= 1;
	case WL_SHM_FORMAT_YVU420:
		return version >= 1;
	case WL_SHM_FORMAT_YUV422:
		return version >= 1;
	case WL_SHM_FORMAT_YVU422:
		return version >= 1;
	case WL_SHM_FORMAT_YUV444:
		return version >= 1;
	case WL_SHM_FORMAT_YVU444:
		return version >= 1;
	case WL_SHM_FORMAT_R8:
		return version >= 1;
	case WL_SHM_FORMAT_R16:
		return version >= 1;
	case WL_SHM_FORMAT_RG88:
		return version >= 1;
	case WL_SHM_FORMAT_GR88:
		return version >= 1;
	case WL_SHM_FORMAT_RG1616:
		return version >= 1;
	case WL_SHM_FORMAT_GR1616:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB16161616F:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR16161616F:
		return version >= 1;
	case WL_SHM_FORMAT_ARGB16161616F:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR16161616F:
		return version >= 1;
	case WL_SHM_FORMAT_XYUV8888:
		return version >= 1;
	case WL_SHM_FORMAT_VUY888:
		return version >= 1;
	case WL_SHM_FORMAT_VUY101010:
		return version >= 1;
	case WL_SHM_FORMAT_Y210:
		return version >= 1;
	case WL_SHM_FORMAT_Y212:
		return version >= 1;
	case WL_SHM_FORMAT_Y216:
		return version >= 1;
	case WL_SHM_FORMAT_Y410:
		return version >= 1;
	case WL_SHM_FORMAT_Y412:
		return version >= 1;
	case WL_SHM_FORMAT_Y416:
		return version >= 1;
	case WL_SHM_FORMAT_XVYU2101010:
		return version >= 1;
	case WL_SHM_FORMAT_XVYU12_16161616:
		return version >= 1;
	case WL_SHM_FORMAT_XVYU16161616:
		return version >= 1;
	case WL_SHM_FORMAT_Y0L0:
		return version >= 1;
	case WL_SHM_FORMAT_X0L0:
		return version >= 1;
	case WL_SHM_FORMAT_Y0L2:
		return version >= 1;
	case WL_SHM_FORMAT_X0L2:
		return version >= 1;
	case WL_SHM_FORMAT_YUV420_8BIT:
		return version >= 1;
	case WL_SHM_FORMAT_YUV420_10BIT:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB8888_A8:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR8888_A8:
		return version >= 1;
	case WL_SHM_FORMAT_RGBX8888_A8:
		return version >= 1;
	case WL_SHM_FORMAT_BGRX8888_A8:
		return version >= 1;
	case WL_SHM_FORMAT_RGB888_A8:
		return version >= 1;
	case WL_SHM_FORMAT_BGR888_A8:
		return version >= 1;
	case WL_SHM_FORMAT_RGB565_A8:
		return version >= 1;
	case WL_SHM_FORMAT_BGR565_A8:
		return version >= 1;
	case WL_SHM_FORMAT_NV24:
		return version >= 1;
	case WL_SHM_FORMAT_NV42:
		return version >= 1;
	case WL_SHM_FORMAT_P210:
		return version >= 1;
	case WL_SHM_FORMAT_P010:
		return version >= 1;
	case WL_SHM_FORMAT_P012:
		return version >= 1;
	case WL_SHM_FORMAT_P016:
		return version >= 1;
	case WL_SHM_FORMAT_AXBXGXRX106106106106:
		return version >= 1;
	case WL_SHM_FORMAT_NV15:
		return version >= 1;
	case WL_SHM_FORMAT_Q410:
		return version >= 1;
	case WL_SHM_FORMAT_Q401:
		return version >= 1;
	case WL_SHM_FORMAT_XRGB16161616:
		return version >= 1;
	case WL_SHM_FORMAT_XBGR16161616:
		return version >= 1;
	case WL_SHM_FORMAT_ARGB16161616:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR16161616:
		return version >= 1;
	case WL_SHM_FORMAT_C1:
		return version >= 1;
	case WL_SHM_FORMAT_C2:
		return version >= 1;
	case WL_SHM_FORMAT_C4:
		return version >= 1;
	case WL_SHM_FORMAT_D1:
		return version >= 1;
	case WL_SHM_FORMAT_D2:
		return version >= 1;
	case WL_SHM_FORMAT_D4:
		return version >= 1;
	case WL_SHM_FORMAT_D8:
		return version >= 1;
	case WL_SHM_FORMAT_R1:
		return version >= 1;
	case WL_SHM_FORMAT_R2:
		return version >= 1;
	case WL_SHM_FORMAT_R4:
		return version >= 1;
	case WL_SHM_FORMAT_R10:
		return version >= 1;
	case WL_SHM_FORMAT_R12:
		return version >= 1;
	case WL_SHM_FORMAT_AVUY8888:
		return version >= 1;
	case WL_SHM_FORMAT_XVUY8888:
		return version >= 1;
	case WL_SHM_FORMAT_P030:
		return version >= 1;
	case WL_SHM_FORMAT_RGB161616:
		return version >= 1;
	case WL_SHM_FORMAT_BGR161616:
		return version >= 1;
	case WL_SHM_FORMAT_R16F:
		return version >= 1;
	case WL_SHM_FORMAT_GR1616F:
		return version >= 1;
	case WL_SHM_FORMAT_BGR161616F:
		return version >= 1;
	case WL_SHM_FORMAT_R32F:
		return version >= 1;
	case WL_SHM_FORMAT_GR3232F:
		return version >= 1;
	case WL_SHM_FORMAT_BGR323232F:
		return version >= 1;
	case WL_SHM_FORMAT_ABGR32323232F:
		return version >= 1;
	case WL_SHM_FORMAT_NV20:
		return version >= 1;
	case WL_SHM_FORMAT_NV30:
		return version >= 1;
	case WL_SHM_FORMAT_S010:
		return version >= 1;
	case WL_SHM_FORMAT_S210:
		return version >= 1;
	case WL_SHM_FORMAT_S410:
		return version >= 1;
	case WL_SHM_FORMAT_S012:
		return version >= 1;
	case WL_SHM_FORMAT_S212:
		return version >= 1;
	case WL_SHM_FORMAT_S412:
		return version >= 1;
	case WL_SHM_FORMAT_S016:
		return version >= 1;
	case WL_SHM_FORMAT_S216:
		return version >= 1;
	case WL_SHM_FORMAT_S416:
		return version >= 1;
	case WL_SHM_FORMAT_XVUY2101010:
		return version >= 1;
	case WL_SHM_FORMAT_P230:
		return version >= 1;
	case WL_SHM_FORMAT_T430:
		return version >= 1;
	case WL_SHM_FORMAT_Y8:
		return version >= 1;
	case WL_SHM_FORMAT_XYYY2101010:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_shm_interface {
	void (*create_pool)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t id,
			    int32_t fd,
			    int32_t size);
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_SHM_FORMAT 0

#define WL_SHM_FORMAT_SINCE_VERSION 1

#define WL_SHM_CREATE_POOL_SINCE_VERSION 1
#define WL_SHM_RELEASE_SINCE_VERSION 2

static inline void
wl_shm_send_format(struct wl_resource *resource_, uint32_t format)
{
	wl_resource_post_event(resource_, WL_SHM_FORMAT, format);
}

struct wl_buffer_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_BUFFER_RELEASE 0

#define WL_BUFFER_RELEASE_SINCE_VERSION 1

#define WL_BUFFER_DESTROY_SINCE_VERSION 1

static inline void
wl_buffer_send_release(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_BUFFER_RELEASE);
}

#ifndef WL_DATA_OFFER_ERROR_ENUM
#define WL_DATA_OFFER_ERROR_ENUM
enum wl_data_offer_error {
	WL_DATA_OFFER_ERROR_INVALID_FINISH = 0,
	WL_DATA_OFFER_ERROR_INVALID_ACTION_MASK = 1,
	WL_DATA_OFFER_ERROR_INVALID_ACTION = 2,
	WL_DATA_OFFER_ERROR_INVALID_OFFER = 3,
};
#endif

#ifndef WL_DATA_OFFER_ERROR_ENUM_IS_VALID
#define WL_DATA_OFFER_ERROR_ENUM_IS_VALID
static inline bool
wl_data_offer_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_DATA_OFFER_ERROR_INVALID_FINISH:
		return version >= 1;
	case WL_DATA_OFFER_ERROR_INVALID_ACTION_MASK:
		return version >= 1;
	case WL_DATA_OFFER_ERROR_INVALID_ACTION:
		return version >= 1;
	case WL_DATA_OFFER_ERROR_INVALID_OFFER:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_data_offer_interface {
	void (*accept)(struct wl_client *client,
		       struct wl_resource *resource,
		       uint32_t serial,
		       const char *mime_type);
	void (*receive)(struct wl_client *client,
			struct wl_resource *resource,
			const char *mime_type,
			int32_t fd);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*finish)(struct wl_client *client,
		       struct wl_resource *resource);
	void (*set_actions)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t dnd_actions,
			    uint32_t preferred_action);
};

#define WL_DATA_OFFER_OFFER 0
#define WL_DATA_OFFER_SOURCE_ACTIONS 1
#define WL_DATA_OFFER_ACTION 2

#define WL_DATA_OFFER_OFFER_SINCE_VERSION 1
#define WL_DATA_OFFER_SOURCE_ACTIONS_SINCE_VERSION 3
#define WL_DATA_OFFER_ACTION_SINCE_VERSION 3

#define WL_DATA_OFFER_ACCEPT_SINCE_VERSION 1
#define WL_DATA_OFFER_RECEIVE_SINCE_VERSION 1
#define WL_DATA_OFFER_DESTROY_SINCE_VERSION 1
#define WL_DATA_OFFER_FINISH_SINCE_VERSION 3
#define WL_DATA_OFFER_SET_ACTIONS_SINCE_VERSION 3

static inline void
wl_data_offer_send_offer(struct wl_resource *resource_, const char *mime_type)
{
	wl_resource_post_event(resource_, WL_DATA_OFFER_OFFER, mime_type);
}

static inline void
wl_data_offer_send_source_actions(struct wl_resource *resource_, uint32_t source_actions)
{
	wl_resource_post_event(resource_, WL_DATA_OFFER_SOURCE_ACTIONS, source_actions);
}

static inline void
wl_data_offer_send_action(struct wl_resource *resource_, uint32_t dnd_action)
{
	wl_resource_post_event(resource_, WL_DATA_OFFER_ACTION, dnd_action);
}

#ifndef WL_DATA_SOURCE_ERROR_ENUM
#define WL_DATA_SOURCE_ERROR_ENUM
enum wl_data_source_error {
	WL_DATA_SOURCE_ERROR_INVALID_ACTION_MASK = 0,
	WL_DATA_SOURCE_ERROR_INVALID_SOURCE = 1,
};
#endif

#ifndef WL_DATA_SOURCE_ERROR_ENUM_IS_VALID
#define WL_DATA_SOURCE_ERROR_ENUM_IS_VALID
static inline bool
wl_data_source_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_DATA_SOURCE_ERROR_INVALID_ACTION_MASK:
		return version >= 1;
	case WL_DATA_SOURCE_ERROR_INVALID_SOURCE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_data_source_interface {
	void (*offer)(struct wl_client *client,
		      struct wl_resource *resource,
		      const char *mime_type);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_actions)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t dnd_actions);
};

#define WL_DATA_SOURCE_TARGET 0
#define WL_DATA_SOURCE_SEND 1
#define WL_DATA_SOURCE_CANCELLED 2
#define WL_DATA_SOURCE_DND_DROP_PERFORMED 3
#define WL_DATA_SOURCE_DND_FINISHED 4
#define WL_DATA_SOURCE_ACTION 5

#define WL_DATA_SOURCE_TARGET_SINCE_VERSION 1
#define WL_DATA_SOURCE_SEND_SINCE_VERSION 1
#define WL_DATA_SOURCE_CANCELLED_SINCE_VERSION 1
#define WL_DATA_SOURCE_DND_DROP_PERFORMED_SINCE_VERSION 3
#define WL_DATA_SOURCE_DND_FINISHED_SINCE_VERSION 3
#define WL_DATA_SOURCE_ACTION_SINCE_VERSION 3

#define WL_DATA_SOURCE_OFFER_SINCE_VERSION 1
#define WL_DATA_SOURCE_DESTROY_SINCE_VERSION 1
#define WL_DATA_SOURCE_SET_ACTIONS_SINCE_VERSION 3

static inline void
wl_data_source_send_target(struct wl_resource *resource_, const char *mime_type)
{
	wl_resource_post_event(resource_, WL_DATA_SOURCE_TARGET, mime_type);
}

static inline void
wl_data_source_send_send(struct wl_resource *resource_, const char *mime_type, int32_t fd)
{
	wl_resource_post_event(resource_, WL_DATA_SOURCE_SEND, mime_type, fd);
}

static inline void
wl_data_source_send_cancelled(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_DATA_SOURCE_CANCELLED);
}

static inline void
wl_data_source_send_dnd_drop_performed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_DATA_SOURCE_DND_DROP_PERFORMED);
}

static inline void
wl_data_source_send_dnd_finished(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_DATA_SOURCE_DND_FINISHED);
}

static inline void
wl_data_source_send_action(struct wl_resource *resource_, uint32_t dnd_action)
{
	wl_resource_post_event(resource_, WL_DATA_SOURCE_ACTION, dnd_action);
}

#ifndef WL_DATA_DEVICE_ERROR_ENUM
#define WL_DATA_DEVICE_ERROR_ENUM
enum wl_data_device_error {
	WL_DATA_DEVICE_ERROR_ROLE = 0,
	WL_DATA_DEVICE_ERROR_USED_SOURCE = 1,
};
#endif

#ifndef WL_DATA_DEVICE_ERROR_ENUM_IS_VALID
#define WL_DATA_DEVICE_ERROR_ENUM_IS_VALID
static inline bool
wl_data_device_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_DATA_DEVICE_ERROR_ROLE:
		return version >= 1;
	case WL_DATA_DEVICE_ERROR_USED_SOURCE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_data_device_interface {
	void (*start_drag)(struct wl_client *client,
			   struct wl_resource *resource,
			   struct wl_resource *source,
			   struct wl_resource *origin,
			   struct wl_resource *icon,
			   uint32_t serial);
	void (*set_selection)(struct wl_client *client,
			      struct wl_resource *resource,
			      struct wl_resource *source,
			      uint32_t serial);
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_DATA_DEVICE_DATA_OFFER 0
#define WL_DATA_DEVICE_ENTER 1
#define WL_DATA_DEVICE_LEAVE 2
#define WL_DATA_DEVICE_MOTION 3
#define WL_DATA_DEVICE_DROP 4
#define WL_DATA_DEVICE_SELECTION 5

#define WL_DATA_DEVICE_DATA_OFFER_SINCE_VERSION 1
#define WL_DATA_DEVICE_ENTER_SINCE_VERSION 1
#define WL_DATA_DEVICE_LEAVE_SINCE_VERSION 1
#define WL_DATA_DEVICE_MOTION_SINCE_VERSION 1
#define WL_DATA_DEVICE_DROP_SINCE_VERSION 1
#define WL_DATA_DEVICE_SELECTION_SINCE_VERSION 1

#define WL_DATA_DEVICE_START_DRAG_SINCE_VERSION 1
#define WL_DATA_DEVICE_SET_SELECTION_SINCE_VERSION 1
#define WL_DATA_DEVICE_RELEASE_SINCE_VERSION 2

static inline void
wl_data_device_send_data_offer(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, WL_DATA_DEVICE_DATA_OFFER, id);
}

static inline void
wl_data_device_send_enter(struct wl_resource *resource_, uint32_t serial, struct wl_resource *surface, wl_fixed_t x, wl_fixed_t y, struct wl_resource *id)
{
	wl_resource_post_event(resource_, WL_DATA_DEVICE_ENTER, serial, surface, x, y, id);
}

static inline void
wl_data_device_send_leave(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_DATA_DEVICE_LEAVE);
}

static inline void
wl_data_device_send_motion(struct wl_resource *resource_, uint32_t time, wl_fixed_t x, wl_fixed_t y)
{
	wl_resource_post_event(resource_, WL_DATA_DEVICE_MOTION, time, x, y);
}

static inline void
wl_data_device_send_drop(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_DATA_DEVICE_DROP);
}

static inline void
wl_data_device_send_selection(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, WL_DATA_DEVICE_SELECTION, id);
}

#ifndef WL_DATA_DEVICE_MANAGER_DND_ACTION_ENUM
#define WL_DATA_DEVICE_MANAGER_DND_ACTION_ENUM
enum wl_data_device_manager_dnd_action {
	WL_DATA_DEVICE_MANAGER_DND_ACTION_NONE = 0,
	WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY = 1,
	WL_DATA_DEVICE_MANAGER_DND_ACTION_MOVE = 2,
	WL_DATA_DEVICE_MANAGER_DND_ACTION_ASK = 4,
};
#endif

#ifndef WL_DATA_DEVICE_MANAGER_DND_ACTION_ENUM_IS_VALID
#define WL_DATA_DEVICE_MANAGER_DND_ACTION_ENUM_IS_VALID
static inline bool
wl_data_device_manager_dnd_action_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= WL_DATA_DEVICE_MANAGER_DND_ACTION_NONE;
	if (version >= 1)
		valid |= WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY;
	if (version >= 1)
		valid |= WL_DATA_DEVICE_MANAGER_DND_ACTION_MOVE;
	if (version >= 1)
		valid |= WL_DATA_DEVICE_MANAGER_DND_ACTION_ASK;
	return (value & ~valid) == 0;
}
#endif

struct wl_data_device_manager_interface {
	void (*create_data_source)(struct wl_client *client,
				   struct wl_resource *resource,
				   uint32_t id);
	void (*get_data_device)(struct wl_client *client,
				struct wl_resource *resource,
				uint32_t id,
				struct wl_resource *seat);
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};


#define WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE_SINCE_VERSION 1
#define WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE_SINCE_VERSION 1
#define WL_DATA_DEVICE_MANAGER_RELEASE_SINCE_VERSION 4

#ifndef WL_SHELL_ERROR_ENUM
#define WL_SHELL_ERROR_ENUM
enum wl_shell_error {
	WL_SHELL_ERROR_ROLE = 0,
};
#endif

#ifndef WL_SHELL_ERROR_ENUM_IS_VALID
#define WL_SHELL_ERROR_ENUM_IS_VALID
static inline bool
wl_shell_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SHELL_ERROR_ROLE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_shell_interface {
	void (*get_shell_surface)(struct wl_client *client,
				  struct wl_resource *resource,
				  uint32_t id,
				  struct wl_resource *surface);
};


#define WL_SHELL_GET_SHELL_SURFACE_SINCE_VERSION 1

#ifndef WL_SHELL_SURFACE_RESIZE_ENUM
#define WL_SHELL_SURFACE_RESIZE_ENUM
enum wl_shell_surface_resize {
	WL_SHELL_SURFACE_RESIZE_NONE = 0,
	WL_SHELL_SURFACE_RESIZE_TOP = 1,
	WL_SHELL_SURFACE_RESIZE_BOTTOM = 2,
	WL_SHELL_SURFACE_RESIZE_LEFT = 4,
	WL_SHELL_SURFACE_RESIZE_TOP_LEFT = 5,
	WL_SHELL_SURFACE_RESIZE_BOTTOM_LEFT = 6,
	WL_SHELL_SURFACE_RESIZE_RIGHT = 8,
	WL_SHELL_SURFACE_RESIZE_TOP_RIGHT = 9,
	WL_SHELL_SURFACE_RESIZE_BOTTOM_RIGHT = 10,
};
#endif

#ifndef WL_SHELL_SURFACE_RESIZE_ENUM_IS_VALID
#define WL_SHELL_SURFACE_RESIZE_ENUM_IS_VALID
static inline bool
wl_shell_surface_resize_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_NONE;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_TOP;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_BOTTOM;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_LEFT;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_TOP_LEFT;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_BOTTOM_LEFT;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_RIGHT;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_TOP_RIGHT;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_RESIZE_BOTTOM_RIGHT;
	return (value & ~valid) == 0;
}
#endif

#ifndef WL_SHELL_SURFACE_TRANSIENT_ENUM
#define WL_SHELL_SURFACE_TRANSIENT_ENUM
enum wl_shell_surface_transient {
	WL_SHELL_SURFACE_TRANSIENT_INACTIVE = 0x1,
};
#endif

#ifndef WL_SHELL_SURFACE_TRANSIENT_ENUM_IS_VALID
#define WL_SHELL_SURFACE_TRANSIENT_ENUM_IS_VALID
static inline bool
wl_shell_surface_transient_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= WL_SHELL_SURFACE_TRANSIENT_INACTIVE;
	return (value & ~valid) == 0;
}
#endif

#ifndef WL_SHELL_SURFACE_FULLSCREEN_METHOD_ENUM
#define WL_SHELL_SURFACE_FULLSCREEN_METHOD_ENUM
enum wl_shell_surface_fullscreen_method {
	WL_SHELL_SURFACE_FULLSCREEN_METHOD_DEFAULT = 0,
	WL_SHELL_SURFACE_FULLSCREEN_METHOD_SCALE = 1,
	WL_SHELL_SURFACE_FULLSCREEN_METHOD_DRIVER = 2,
	WL_SHELL_SURFACE_FULLSCREEN_METHOD_FILL = 3,
};
#endif

#ifndef WL_SHELL_SURFACE_FULLSCREEN_METHOD_ENUM_IS_VALID
#define WL_SHELL_SURFACE_FULLSCREEN_METHOD_ENUM_IS_VALID
static inline bool
wl_shell_surface_fullscreen_method_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SHELL_SURFACE_FULLSCREEN_METHOD_DEFAULT:
		return version >= 1;
	case WL_SHELL_SURFACE_FULLSCREEN_METHOD_SCALE:
		return version >= 1;
	case WL_SHELL_SURFACE_FULLSCREEN_METHOD_DRIVER:
		return version >= 1;
	case WL_SHELL_SURFACE_FULLSCREEN_METHOD_FILL:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_shell_surface_interface {
	void (*pong)(struct wl_client *client,
		     struct wl_resource *resource,
		     uint32_t serial);
	void (*move)(struct wl_client *client,
		     struct wl_resource *resource,
		     struct wl_resource *seat,
		     uint32_t serial);
	void (*resize)(struct wl_client *client,
		       struct wl_resource *resource,
		       struct wl_resource *seat,
		       uint32_t serial,
		       uint32_t edges);
	void (*set_toplevel)(struct wl_client *client,
			     struct wl_resource *resource);
	void (*set_transient)(struct wl_client *client,
			      struct wl_resource *resource,
			      struct wl_resource *parent,
			      int32_t x,
			      int32_t y,
			      uint32_t flags);
	void (*set_fullscreen)(struct wl_client *client,
			       struct wl_resource *resource,
			       uint32_t method,
			       uint32_t framerate,
			       struct wl_resource *output);
	void (*set_popup)(struct wl_client *client,
			  struct wl_resource *resource,
			  struct wl_resource *seat,
			  uint32_t serial,
			  struct wl_resource *parent,
			  int32_t x,
			  int32_t y,
			  uint32_t flags);
	void (*set_maximized)(struct wl_client *client,
			      struct wl_resource *resource,
			      struct wl_resource *output);
	void (*set_title)(struct wl_client *client,
			  struct wl_resource *resource,
			  const char *title);
	void (*set_class)(struct wl_client *client,
			  struct wl_resource *resource,
			  const char *class_);
};

#define WL_SHELL_SURFACE_PING 0
#define WL_SHELL_SURFACE_CONFIGURE 1
#define WL_SHELL_SURFACE_POPUP_DONE 2

#define WL_SHELL_SURFACE_PING_SINCE_VERSION 1
#define WL_SHELL_SURFACE_CONFIGURE_SINCE_VERSION 1
#define WL_SHELL_SURFACE_POPUP_DONE_SINCE_VERSION 1

#define WL_SHELL_SURFACE_PONG_SINCE_VERSION 1
#define WL_SHELL_SURFACE_MOVE_SINCE_VERSION 1
#define WL_SHELL_SURFACE_RESIZE_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_TOPLEVEL_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_TRANSIENT_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_FULLSCREEN_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_POPUP_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_MAXIMIZED_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_TITLE_SINCE_VERSION 1
#define WL_SHELL_SURFACE_SET_CLASS_SINCE_VERSION 1

static inline void
wl_shell_surface_send_ping(struct wl_resource *resource_, uint32_t serial)
{
	wl_resource_post_event(resource_, WL_SHELL_SURFACE_PING, serial);
}

static inline void
wl_shell_surface_send_configure(struct wl_resource *resource_, uint32_t edges, int32_t width, int32_t height)
{
	wl_resource_post_event(resource_, WL_SHELL_SURFACE_CONFIGURE, edges, width, height);
}

static inline void
wl_shell_surface_send_popup_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_SHELL_SURFACE_POPUP_DONE);
}

#ifndef WL_SURFACE_ERROR_ENUM
#define WL_SURFACE_ERROR_ENUM
enum wl_surface_error {
	WL_SURFACE_ERROR_INVALID_SCALE = 0,
	WL_SURFACE_ERROR_INVALID_TRANSFORM = 1,
	WL_SURFACE_ERROR_INVALID_SIZE = 2,
	WL_SURFACE_ERROR_INVALID_OFFSET = 3,
	WL_SURFACE_ERROR_DEFUNCT_ROLE_OBJECT = 4,
	WL_SURFACE_ERROR_NO_BUFFER = 5,
};
#endif

#ifndef WL_SURFACE_ERROR_ENUM_IS_VALID
#define WL_SURFACE_ERROR_ENUM_IS_VALID
static inline bool
wl_surface_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SURFACE_ERROR_INVALID_SCALE:
		return version >= 1;
	case WL_SURFACE_ERROR_INVALID_TRANSFORM:
		return version >= 1;
	case WL_SURFACE_ERROR_INVALID_SIZE:
		return version >= 1;
	case WL_SURFACE_ERROR_INVALID_OFFSET:
		return version >= 1;
	case WL_SURFACE_ERROR_DEFUNCT_ROLE_OBJECT:
		return version >= 1;
	case WL_SURFACE_ERROR_NO_BUFFER:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_surface_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*attach)(struct wl_client *client,
		       struct wl_resource *resource,
		       struct wl_resource *buffer,
		       int32_t x,
		       int32_t y);
	void (*damage)(struct wl_client *client,
		       struct wl_resource *resource,
		       int32_t x,
		       int32_t y,
		       int32_t width,
		       int32_t height);
	void (*frame)(struct wl_client *client,
		      struct wl_resource *resource,
		      uint32_t callback);
	void (*set_opaque_region)(struct wl_client *client,
				  struct wl_resource *resource,
				  struct wl_resource *region);
	void (*set_input_region)(struct wl_client *client,
				 struct wl_resource *resource,
				 struct wl_resource *region);
	void (*commit)(struct wl_client *client,
		       struct wl_resource *resource);
	void (*set_buffer_transform)(struct wl_client *client,
				     struct wl_resource *resource,
				     int32_t transform);
	void (*set_buffer_scale)(struct wl_client *client,
				 struct wl_resource *resource,
				 int32_t scale);
	void (*damage_buffer)(struct wl_client *client,
			      struct wl_resource *resource,
			      int32_t x,
			      int32_t y,
			      int32_t width,
			      int32_t height);
	void (*offset)(struct wl_client *client,
		       struct wl_resource *resource,
		       int32_t x,
		       int32_t y);
	void (*get_release)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t callback);
};

#define WL_SURFACE_ENTER 0
#define WL_SURFACE_LEAVE 1
#define WL_SURFACE_PREFERRED_BUFFER_SCALE 2
#define WL_SURFACE_PREFERRED_BUFFER_TRANSFORM 3

#define WL_SURFACE_ENTER_SINCE_VERSION 1
#define WL_SURFACE_LEAVE_SINCE_VERSION 1
#define WL_SURFACE_PREFERRED_BUFFER_SCALE_SINCE_VERSION 6
#define WL_SURFACE_PREFERRED_BUFFER_TRANSFORM_SINCE_VERSION 6

#define WL_SURFACE_DESTROY_SINCE_VERSION 1
#define WL_SURFACE_ATTACH_SINCE_VERSION 1
#define WL_SURFACE_DAMAGE_SINCE_VERSION 1
#define WL_SURFACE_FRAME_SINCE_VERSION 1
#define WL_SURFACE_SET_OPAQUE_REGION_SINCE_VERSION 1
#define WL_SURFACE_SET_INPUT_REGION_SINCE_VERSION 1
#define WL_SURFACE_COMMIT_SINCE_VERSION 1
#define WL_SURFACE_SET_BUFFER_TRANSFORM_SINCE_VERSION 2
#define WL_SURFACE_SET_BUFFER_SCALE_SINCE_VERSION 3
#define WL_SURFACE_DAMAGE_BUFFER_SINCE_VERSION 4
#define WL_SURFACE_OFFSET_SINCE_VERSION 5
#define WL_SURFACE_GET_RELEASE_SINCE_VERSION 7

static inline void
wl_surface_send_enter(struct wl_resource *resource_, struct wl_resource *output)
{
	wl_resource_post_event(resource_, WL_SURFACE_ENTER, output);
}

static inline void
wl_surface_send_leave(struct wl_resource *resource_, struct wl_resource *output)
{
	wl_resource_post_event(resource_, WL_SURFACE_LEAVE, output);
}

static inline void
wl_surface_send_preferred_buffer_scale(struct wl_resource *resource_, int32_t factor)
{
	wl_resource_post_event(resource_, WL_SURFACE_PREFERRED_BUFFER_SCALE, factor);
}

static inline void
wl_surface_send_preferred_buffer_transform(struct wl_resource *resource_, uint32_t transform)
{
	wl_resource_post_event(resource_, WL_SURFACE_PREFERRED_BUFFER_TRANSFORM, transform);
}

#ifndef WL_SEAT_CAPABILITY_ENUM
#define WL_SEAT_CAPABILITY_ENUM
enum wl_seat_capability {
	WL_SEAT_CAPABILITY_POINTER = 1,
	WL_SEAT_CAPABILITY_KEYBOARD = 2,
	WL_SEAT_CAPABILITY_TOUCH = 4,
};
#endif

#ifndef WL_SEAT_CAPABILITY_ENUM_IS_VALID
#define WL_SEAT_CAPABILITY_ENUM_IS_VALID
static inline bool
wl_seat_capability_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= WL_SEAT_CAPABILITY_POINTER;
	if (version >= 1)
		valid |= WL_SEAT_CAPABILITY_KEYBOARD;
	if (version >= 1)
		valid |= WL_SEAT_CAPABILITY_TOUCH;
	return (value & ~valid) == 0;
}
#endif

#ifndef WL_SEAT_ERROR_ENUM
#define WL_SEAT_ERROR_ENUM
enum wl_seat_error {
	WL_SEAT_ERROR_MISSING_CAPABILITY = 0,
};
#endif

#ifndef WL_SEAT_ERROR_ENUM_IS_VALID
#define WL_SEAT_ERROR_ENUM_IS_VALID
static inline bool
wl_seat_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SEAT_ERROR_MISSING_CAPABILITY:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_seat_interface {
	void (*get_pointer)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t id);
	void (*get_keyboard)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t id);
	void (*get_touch)(struct wl_client *client,
			  struct wl_resource *resource,
			  uint32_t id);
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_SEAT_CAPABILITIES 0
#define WL_SEAT_NAME 1

#define WL_SEAT_CAPABILITIES_SINCE_VERSION 1
#define WL_SEAT_NAME_SINCE_VERSION 2

#define WL_SEAT_GET_POINTER_SINCE_VERSION 1
#define WL_SEAT_GET_KEYBOARD_SINCE_VERSION 1
#define WL_SEAT_GET_TOUCH_SINCE_VERSION 1
#define WL_SEAT_RELEASE_SINCE_VERSION 5

static inline void
wl_seat_send_capabilities(struct wl_resource *resource_, uint32_t capabilities)
{
	wl_resource_post_event(resource_, WL_SEAT_CAPABILITIES, capabilities);
}

static inline void
wl_seat_send_name(struct wl_resource *resource_, const char *name)
{
	wl_resource_post_event(resource_, WL_SEAT_NAME, name);
}

#ifndef WL_POINTER_ERROR_ENUM
#define WL_POINTER_ERROR_ENUM
enum wl_pointer_error {
	WL_POINTER_ERROR_ROLE = 0,
};
#endif

#ifndef WL_POINTER_ERROR_ENUM_IS_VALID
#define WL_POINTER_ERROR_ENUM_IS_VALID
static inline bool
wl_pointer_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_POINTER_ERROR_ROLE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_POINTER_BUTTON_STATE_ENUM
#define WL_POINTER_BUTTON_STATE_ENUM
enum wl_pointer_button_state {
	WL_POINTER_BUTTON_STATE_RELEASED = 0,
	WL_POINTER_BUTTON_STATE_PRESSED = 1,
};
#endif

#ifndef WL_POINTER_BUTTON_STATE_ENUM_IS_VALID
#define WL_POINTER_BUTTON_STATE_ENUM_IS_VALID
static inline bool
wl_pointer_button_state_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_POINTER_BUTTON_STATE_RELEASED:
		return version >= 1;
	case WL_POINTER_BUTTON_STATE_PRESSED:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_POINTER_AXIS_ENUM
#define WL_POINTER_AXIS_ENUM
enum wl_pointer_axis {
	WL_POINTER_AXIS_VERTICAL_SCROLL = 0,
	WL_POINTER_AXIS_HORIZONTAL_SCROLL = 1,
};
#endif

#ifndef WL_POINTER_AXIS_ENUM_IS_VALID
#define WL_POINTER_AXIS_ENUM_IS_VALID
static inline bool
wl_pointer_axis_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_POINTER_AXIS_VERTICAL_SCROLL:
		return version >= 1;
	case WL_POINTER_AXIS_HORIZONTAL_SCROLL:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_POINTER_AXIS_SOURCE_ENUM
#define WL_POINTER_AXIS_SOURCE_ENUM
enum wl_pointer_axis_source {
	WL_POINTER_AXIS_SOURCE_WHEEL = 0,
	WL_POINTER_AXIS_SOURCE_FINGER = 1,
	WL_POINTER_AXIS_SOURCE_CONTINUOUS = 2,
	WL_POINTER_AXIS_SOURCE_WHEEL_TILT = 3,
};
#define WL_POINTER_AXIS_SOURCE_WHEEL_TILT_SINCE_VERSION 6
#endif

#ifndef WL_POINTER_AXIS_SOURCE_ENUM_IS_VALID
#define WL_POINTER_AXIS_SOURCE_ENUM_IS_VALID
static inline bool
wl_pointer_axis_source_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_POINTER_AXIS_SOURCE_WHEEL:
		return version >= 1;
	case WL_POINTER_AXIS_SOURCE_FINGER:
		return version >= 1;
	case WL_POINTER_AXIS_SOURCE_CONTINUOUS:
		return version >= 1;
	case WL_POINTER_AXIS_SOURCE_WHEEL_TILT:
		return version >= 6;
	default:
		return false;
	}
}
#endif

#ifndef WL_POINTER_AXIS_RELATIVE_DIRECTION_ENUM
#define WL_POINTER_AXIS_RELATIVE_DIRECTION_ENUM
enum wl_pointer_axis_relative_direction {
	WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL = 0,
	WL_POINTER_AXIS_RELATIVE_DIRECTION_INVERTED = 1,
};
#endif

#ifndef WL_POINTER_AXIS_RELATIVE_DIRECTION_ENUM_IS_VALID
#define WL_POINTER_AXIS_RELATIVE_DIRECTION_ENUM_IS_VALID
static inline bool
wl_pointer_axis_relative_direction_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL:
		return version >= 1;
	case WL_POINTER_AXIS_RELATIVE_DIRECTION_INVERTED:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_pointer_interface {
	void (*set_cursor)(struct wl_client *client,
			   struct wl_resource *resource,
			   uint32_t serial,
			   struct wl_resource *surface,
			   int32_t hotspot_x,
			   int32_t hotspot_y);
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_POINTER_ENTER 0
#define WL_POINTER_LEAVE 1
#define WL_POINTER_MOTION 2
#define WL_POINTER_BUTTON 3
#define WL_POINTER_AXIS 4
#define WL_POINTER_FRAME 5
#define WL_POINTER_AXIS_SOURCE 6
#define WL_POINTER_AXIS_STOP 7
#define WL_POINTER_AXIS_DISCRETE 8
#define WL_POINTER_AXIS_VALUE120 9
#define WL_POINTER_AXIS_RELATIVE_DIRECTION 10
#define WL_POINTER_WARP 11

#define WL_POINTER_ENTER_SINCE_VERSION 1
#define WL_POINTER_LEAVE_SINCE_VERSION 1
#define WL_POINTER_MOTION_SINCE_VERSION 1
#define WL_POINTER_BUTTON_SINCE_VERSION 1
#define WL_POINTER_AXIS_SINCE_VERSION 1
#define WL_POINTER_FRAME_SINCE_VERSION 5
#define WL_POINTER_AXIS_SOURCE_SINCE_VERSION 5
#define WL_POINTER_AXIS_STOP_SINCE_VERSION 5
#define WL_POINTER_AXIS_DISCRETE_SINCE_VERSION 5
#define WL_POINTER_AXIS_VALUE120_SINCE_VERSION 8
#define WL_POINTER_AXIS_RELATIVE_DIRECTION_SINCE_VERSION 9
#define WL_POINTER_WARP_SINCE_VERSION 11

#define WL_POINTER_SET_CURSOR_SINCE_VERSION 1
#define WL_POINTER_RELEASE_SINCE_VERSION 3

static inline void
wl_pointer_send_enter(struct wl_resource *resource_, uint32_t serial, struct wl_resource *surface, wl_fixed_t surface_x, wl_fixed_t surface_y)
{
	wl_resource_post_event(resource_, WL_POINTER_ENTER, serial, surface, surface_x, surface_y);
}

static inline void
wl_pointer_send_leave(struct wl_resource *resource_, uint32_t serial, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, WL_POINTER_LEAVE, serial, surface);
}

static inline void
wl_pointer_send_motion(struct wl_resource *resource_, uint32_t time, wl_fixed_t surface_x, wl_fixed_t surface_y)
{
	wl_resource_post_event(resource_, WL_POINTER_MOTION, time, surface_x, surface_y);
}

static inline void
wl_pointer_send_button(struct wl_resource *resource_, uint32_t serial, uint32_t time, uint32_t button, uint32_t state)
{
	wl_resource_post_event(resource_, WL_POINTER_BUTTON, serial, time, button, state);
}

static inline void
wl_pointer_send_axis(struct wl_resource *resource_, uint32_t time, uint32_t axis, wl_fixed_t value)
{
	wl_resource_post_event(resource_, WL_POINTER_AXIS, time, axis, value);
}

static inline void
wl_pointer_send_frame(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_POINTER_FRAME);
}

static inline void
wl_pointer_send_axis_source(struct wl_resource *resource_, uint32_t axis_source)
{
	wl_resource_post_event(resource_, WL_POINTER_AXIS_SOURCE, axis_source);
}

static inline void
wl_pointer_send_axis_stop(struct wl_resource *resource_, uint32_t time, uint32_t axis)
{
	wl_resource_post_event(resource_, WL_POINTER_AXIS_STOP, time, axis);
}

static inline void
wl_pointer_send_axis_discrete(struct wl_resource *resource_, uint32_t axis, int32_t discrete)
{
	wl_resource_post_event(resource_, WL_POINTER_AXIS_DISCRETE, axis, discrete);
}

static inline void
wl_pointer_send_axis_value120(struct wl_resource *resource_, uint32_t axis, int32_t value120)
{
	wl_resource_post_event(resource_, WL_POINTER_AXIS_VALUE120, axis, value120);
}

static inline void
wl_pointer_send_axis_relative_direction(struct wl_resource *resource_, uint32_t axis, uint32_t direction)
{
	wl_resource_post_event(resource_, WL_POINTER_AXIS_RELATIVE_DIRECTION, axis, direction);
}

static inline void
wl_pointer_send_warp(struct wl_resource *resource_, wl_fixed_t surface_x, wl_fixed_t surface_y)
{
	wl_resource_post_event(resource_, WL_POINTER_WARP, surface_x, surface_y);
}

#ifndef WL_KEYBOARD_KEYMAP_FORMAT_ENUM
#define WL_KEYBOARD_KEYMAP_FORMAT_ENUM
enum wl_keyboard_keymap_format {
	WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP = 0,
	WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 = 1,
};
#endif

#ifndef WL_KEYBOARD_KEYMAP_FORMAT_ENUM_IS_VALID
#define WL_KEYBOARD_KEYMAP_FORMAT_ENUM_IS_VALID
static inline bool
wl_keyboard_keymap_format_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP:
		return version >= 1;
	case WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_KEYBOARD_KEY_STATE_ENUM
#define WL_KEYBOARD_KEY_STATE_ENUM
enum wl_keyboard_key_state {
	WL_KEYBOARD_KEY_STATE_RELEASED = 0,
	WL_KEYBOARD_KEY_STATE_PRESSED = 1,
	WL_KEYBOARD_KEY_STATE_REPEATED = 2,
};
#define WL_KEYBOARD_KEY_STATE_REPEATED_SINCE_VERSION 10
#endif

#ifndef WL_KEYBOARD_KEY_STATE_ENUM_IS_VALID
#define WL_KEYBOARD_KEY_STATE_ENUM_IS_VALID
static inline bool
wl_keyboard_key_state_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_KEYBOARD_KEY_STATE_RELEASED:
		return version >= 1;
	case WL_KEYBOARD_KEY_STATE_PRESSED:
		return version >= 1;
	case WL_KEYBOARD_KEY_STATE_REPEATED:
		return version >= 10;
	default:
		return false;
	}
}
#endif

struct wl_keyboard_interface {
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_KEYBOARD_KEYMAP 0
#define WL_KEYBOARD_ENTER 1
#define WL_KEYBOARD_LEAVE 2
#define WL_KEYBOARD_KEY 3
#define WL_KEYBOARD_MODIFIERS 4
#define WL_KEYBOARD_REPEAT_INFO 5

#define WL_KEYBOARD_KEYMAP_SINCE_VERSION 1
#define WL_KEYBOARD_ENTER_SINCE_VERSION 1
#define WL_KEYBOARD_LEAVE_SINCE_VERSION 1
#define WL_KEYBOARD_KEY_SINCE_VERSION 1
#define WL_KEYBOARD_MODIFIERS_SINCE_VERSION 1
#define WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION 4

#define WL_KEYBOARD_RELEASE_SINCE_VERSION 3

static inline void
wl_keyboard_send_keymap(struct wl_resource *resource_, uint32_t format, int32_t fd, uint32_t size)
{
	wl_resource_post_event(resource_, WL_KEYBOARD_KEYMAP, format, fd, size);
}

static inline void
wl_keyboard_send_enter(struct wl_resource *resource_, uint32_t serial, struct wl_resource *surface, struct wl_array *keys)
{
	wl_resource_post_event(resource_, WL_KEYBOARD_ENTER, serial, surface, keys);
}

static inline void
wl_keyboard_send_leave(struct wl_resource *resource_, uint32_t serial, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, WL_KEYBOARD_LEAVE, serial, surface);
}

static inline void
wl_keyboard_send_key(struct wl_resource *resource_, uint32_t serial, uint32_t time, uint32_t key, uint32_t state)
{
	wl_resource_post_event(resource_, WL_KEYBOARD_KEY, serial, time, key, state);
}

static inline void
wl_keyboard_send_modifiers(struct wl_resource *resource_, uint32_t serial, uint32_t mods_depressed, uint32_t mods_latched, uint32_t mods_locked, uint32_t group)
{
	wl_resource_post_event(resource_, WL_KEYBOARD_MODIFIERS, serial, mods_depressed, mods_latched, mods_locked, group);
}

static inline void
wl_keyboard_send_repeat_info(struct wl_resource *resource_, int32_t rate, int32_t delay)
{
	wl_resource_post_event(resource_, WL_KEYBOARD_REPEAT_INFO, rate, delay);
}

struct wl_touch_interface {
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_TOUCH_DOWN 0
#define WL_TOUCH_UP 1
#define WL_TOUCH_MOTION 2
#define WL_TOUCH_FRAME 3
#define WL_TOUCH_CANCEL 4
#define WL_TOUCH_SHAPE 5
#define WL_TOUCH_ORIENTATION 6

#define WL_TOUCH_DOWN_SINCE_VERSION 1
#define WL_TOUCH_UP_SINCE_VERSION 1
#define WL_TOUCH_MOTION_SINCE_VERSION 1
#define WL_TOUCH_FRAME_SINCE_VERSION 1
#define WL_TOUCH_CANCEL_SINCE_VERSION 1
#define WL_TOUCH_SHAPE_SINCE_VERSION 6
#define WL_TOUCH_ORIENTATION_SINCE_VERSION 6

#define WL_TOUCH_RELEASE_SINCE_VERSION 3

static inline void
wl_touch_send_down(struct wl_resource *resource_, uint32_t serial, uint32_t time, struct wl_resource *surface, int32_t id, wl_fixed_t x, wl_fixed_t y)
{
	wl_resource_post_event(resource_, WL_TOUCH_DOWN, serial, time, surface, id, x, y);
}

static inline void
wl_touch_send_up(struct wl_resource *resource_, uint32_t serial, uint32_t time, int32_t id)
{
	wl_resource_post_event(resource_, WL_TOUCH_UP, serial, time, id);
}

static inline void
wl_touch_send_motion(struct wl_resource *resource_, uint32_t time, int32_t id, wl_fixed_t x, wl_fixed_t y)
{
	wl_resource_post_event(resource_, WL_TOUCH_MOTION, time, id, x, y);
}

static inline void
wl_touch_send_frame(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_TOUCH_FRAME);
}

static inline void
wl_touch_send_cancel(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_TOUCH_CANCEL);
}

static inline void
wl_touch_send_shape(struct wl_resource *resource_, int32_t id, wl_fixed_t major, wl_fixed_t minor)
{
	wl_resource_post_event(resource_, WL_TOUCH_SHAPE, id, major, minor);
}

static inline void
wl_touch_send_orientation(struct wl_resource *resource_, int32_t id, wl_fixed_t orientation)
{
	wl_resource_post_event(resource_, WL_TOUCH_ORIENTATION, id, orientation);
}

#ifndef WL_OUTPUT_SUBPIXEL_ENUM
#define WL_OUTPUT_SUBPIXEL_ENUM
enum wl_output_subpixel {
	WL_OUTPUT_SUBPIXEL_UNKNOWN = 0,
	WL_OUTPUT_SUBPIXEL_NONE = 1,
	WL_OUTPUT_SUBPIXEL_HORIZONTAL_RGB = 2,
	WL_OUTPUT_SUBPIXEL_HORIZONTAL_BGR = 3,
	WL_OUTPUT_SUBPIXEL_VERTICAL_RGB = 4,
	WL_OUTPUT_SUBPIXEL_VERTICAL_BGR = 5,
};
#endif

#ifndef WL_OUTPUT_SUBPIXEL_ENUM_IS_VALID
#define WL_OUTPUT_SUBPIXEL_ENUM_IS_VALID
static inline bool
wl_output_subpixel_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_OUTPUT_SUBPIXEL_UNKNOWN:
		return version >= 1;
	case WL_OUTPUT_SUBPIXEL_NONE:
		return version >= 1;
	case WL_OUTPUT_SUBPIXEL_HORIZONTAL_RGB:
		return version >= 1;
	case WL_OUTPUT_SUBPIXEL_HORIZONTAL_BGR:
		return version >= 1;
	case WL_OUTPUT_SUBPIXEL_VERTICAL_RGB:
		return version >= 1;
	case WL_OUTPUT_SUBPIXEL_VERTICAL_BGR:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_OUTPUT_TRANSFORM_ENUM
#define WL_OUTPUT_TRANSFORM_ENUM
enum wl_output_transform {
	WL_OUTPUT_TRANSFORM_NORMAL = 0,
	WL_OUTPUT_TRANSFORM_90 = 1,
	WL_OUTPUT_TRANSFORM_180 = 2,
	WL_OUTPUT_TRANSFORM_270 = 3,
	WL_OUTPUT_TRANSFORM_FLIPPED = 4,
	WL_OUTPUT_TRANSFORM_FLIPPED_90 = 5,
	WL_OUTPUT_TRANSFORM_FLIPPED_180 = 6,
	WL_OUTPUT_TRANSFORM_FLIPPED_270 = 7,
};
#endif

#ifndef WL_OUTPUT_TRANSFORM_ENUM_IS_VALID
#define WL_OUTPUT_TRANSFORM_ENUM_IS_VALID
static inline bool
wl_output_transform_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_OUTPUT_TRANSFORM_NORMAL:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_90:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_180:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_270:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_FLIPPED:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_FLIPPED_90:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_FLIPPED_180:
		return version >= 1;
	case WL_OUTPUT_TRANSFORM_FLIPPED_270:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WL_OUTPUT_MODE_ENUM
#define WL_OUTPUT_MODE_ENUM
enum wl_output_mode {
	WL_OUTPUT_MODE_CURRENT = 0x1,
	WL_OUTPUT_MODE_PREFERRED = 0x2,
};
#endif

#ifndef WL_OUTPUT_MODE_ENUM_IS_VALID
#define WL_OUTPUT_MODE_ENUM_IS_VALID
static inline bool
wl_output_mode_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= WL_OUTPUT_MODE_CURRENT;
	if (version >= 1)
		valid |= WL_OUTPUT_MODE_PREFERRED;
	return (value & ~valid) == 0;
}
#endif

struct wl_output_interface {
	void (*release)(struct wl_client *client,
			struct wl_resource *resource);
};

#define WL_OUTPUT_GEOMETRY 0
#define WL_OUTPUT_MODE 1
#define WL_OUTPUT_DONE 2
#define WL_OUTPUT_SCALE 3
#define WL_OUTPUT_NAME 4
#define WL_OUTPUT_DESCRIPTION 5

#define WL_OUTPUT_GEOMETRY_SINCE_VERSION 1
#define WL_OUTPUT_MODE_SINCE_VERSION 1
#define WL_OUTPUT_DONE_SINCE_VERSION 2
#define WL_OUTPUT_SCALE_SINCE_VERSION 2
#define WL_OUTPUT_NAME_SINCE_VERSION 4
#define WL_OUTPUT_DESCRIPTION_SINCE_VERSION 4

#define WL_OUTPUT_RELEASE_SINCE_VERSION 3

static inline void
wl_output_send_geometry(struct wl_resource *resource_, int32_t x, int32_t y, int32_t physical_width, int32_t physical_height, int32_t subpixel, const char *make, const char *model, int32_t transform)
{
	wl_resource_post_event(resource_, WL_OUTPUT_GEOMETRY, x, y, physical_width, physical_height, subpixel, make, model, transform);
}

static inline void
wl_output_send_mode(struct wl_resource *resource_, uint32_t flags, int32_t width, int32_t height, int32_t refresh)
{
	wl_resource_post_event(resource_, WL_OUTPUT_MODE, flags, width, height, refresh);
}

static inline void
wl_output_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WL_OUTPUT_DONE);
}

static inline void
wl_output_send_scale(struct wl_resource *resource_, int32_t factor)
{
	wl_resource_post_event(resource_, WL_OUTPUT_SCALE, factor);
}

static inline void
wl_output_send_name(struct wl_resource *resource_, const char *name)
{
	wl_resource_post_event(resource_, WL_OUTPUT_NAME, name);
}

static inline void
wl_output_send_description(struct wl_resource *resource_, const char *description)
{
	wl_resource_post_event(resource_, WL_OUTPUT_DESCRIPTION, description);
}

struct wl_region_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*add)(struct wl_client *client,
		    struct wl_resource *resource,
		    int32_t x,
		    int32_t y,
		    int32_t width,
		    int32_t height);
	void (*subtract)(struct wl_client *client,
			 struct wl_resource *resource,
			 int32_t x,
			 int32_t y,
			 int32_t width,
			 int32_t height);
};


#define WL_REGION_DESTROY_SINCE_VERSION 1
#define WL_REGION_ADD_SINCE_VERSION 1
#define WL_REGION_SUBTRACT_SINCE_VERSION 1

#ifndef WL_SUBCOMPOSITOR_ERROR_ENUM
#define WL_SUBCOMPOSITOR_ERROR_ENUM
enum wl_subcompositor_error {
	WL_SUBCOMPOSITOR_ERROR_BAD_SURFACE = 0,
	WL_SUBCOMPOSITOR_ERROR_BAD_PARENT = 1,
};
#endif

#ifndef WL_SUBCOMPOSITOR_ERROR_ENUM_IS_VALID
#define WL_SUBCOMPOSITOR_ERROR_ENUM_IS_VALID
static inline bool
wl_subcompositor_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SUBCOMPOSITOR_ERROR_BAD_SURFACE:
		return version >= 1;
	case WL_SUBCOMPOSITOR_ERROR_BAD_PARENT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_subcompositor_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_subsurface)(struct wl_client *client,
			       struct wl_resource *resource,
			       uint32_t id,
			       struct wl_resource *surface,
			       struct wl_resource *parent);
};


#define WL_SUBCOMPOSITOR_DESTROY_SINCE_VERSION 1
#define WL_SUBCOMPOSITOR_GET_SUBSURFACE_SINCE_VERSION 1

#ifndef WL_SUBSURFACE_ERROR_ENUM
#define WL_SUBSURFACE_ERROR_ENUM
enum wl_subsurface_error {
	WL_SUBSURFACE_ERROR_BAD_SURFACE = 0,
};
#endif

#ifndef WL_SUBSURFACE_ERROR_ENUM_IS_VALID
#define WL_SUBSURFACE_ERROR_ENUM_IS_VALID
static inline bool
wl_subsurface_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_SUBSURFACE_ERROR_BAD_SURFACE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_subsurface_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_position)(struct wl_client *client,
			     struct wl_resource *resource,
			     int32_t x,
			     int32_t y);
	void (*place_above)(struct wl_client *client,
			    struct wl_resource *resource,
			    struct wl_resource *sibling);
	void (*place_below)(struct wl_client *client,
			    struct wl_resource *resource,
			    struct wl_resource *sibling);
	void (*set_sync)(struct wl_client *client,
			 struct wl_resource *resource);
	void (*set_desync)(struct wl_client *client,
			   struct wl_resource *resource);
};


#define WL_SUBSURFACE_DESTROY_SINCE_VERSION 1
#define WL_SUBSURFACE_SET_POSITION_SINCE_VERSION 1
#define WL_SUBSURFACE_PLACE_ABOVE_SINCE_VERSION 1
#define WL_SUBSURFACE_PLACE_BELOW_SINCE_VERSION 1
#define WL_SUBSURFACE_SET_SYNC_SINCE_VERSION 1
#define WL_SUBSURFACE_SET_DESYNC_SINCE_VERSION 1

#ifndef WL_FIXES_ERROR_ENUM
#define WL_FIXES_ERROR_ENUM
enum wl_fixes_error {
	WL_FIXES_ERROR_INVALID_ACK_REMOVE = 0,
};
#endif

#ifndef WL_FIXES_ERROR_ENUM_IS_VALID
#define WL_FIXES_ERROR_ENUM_IS_VALID
static inline bool
wl_fixes_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WL_FIXES_ERROR_INVALID_ACK_REMOVE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wl_fixes_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*destroy_registry)(struct wl_client *client,
				 struct wl_resource *resource,
				 struct wl_resource *registry);
	void (*ack_global_remove)(struct wl_client *client,
				  struct wl_resource *resource,
				  struct wl_resource *registry,
				  uint32_t name);
};


#define WL_FIXES_DESTROY_SINCE_VERSION 1
#define WL_FIXES_DESTROY_REGISTRY_SINCE_VERSION 1
#define WL_FIXES_ACK_GLOBAL_REMOVE_SINCE_VERSION 2

#ifdef  __cplusplus
}
#endif

#endif
