
#ifndef WAYLAND_CLIENT_PROTOCOL_H
#define WAYLAND_CLIENT_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-client.h"

#ifdef  __cplusplus
extern "C" {
#endif

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

struct wl_display_listener {
	void (*error)(void *data,
		      struct wl_display *wl_display,
		      void *object_id,
		      uint32_t code,
		      const char *message);
	void (*delete_id)(void *data,
			  struct wl_display *wl_display,
			  uint32_t id);
};

static inline int
wl_display_add_listener(struct wl_display *wl_display,
			const struct wl_display_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_display,
				     (void (**)(void)) listener, data);
}

#define WL_DISPLAY_SYNC 0
#define WL_DISPLAY_GET_REGISTRY 1

#define WL_DISPLAY_ERROR_SINCE_VERSION 1
#define WL_DISPLAY_DELETE_ID_SINCE_VERSION 1

#define WL_DISPLAY_SYNC_SINCE_VERSION 1
#define WL_DISPLAY_GET_REGISTRY_SINCE_VERSION 1

static inline void
wl_display_set_user_data(struct wl_display *wl_display, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_display, user_data);
}

static inline void *
wl_display_get_user_data(struct wl_display *wl_display)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_display);
}

static inline uint32_t
wl_display_get_version(struct wl_display *wl_display)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_display);
}

static inline struct wl_callback *
wl_display_sync(struct wl_display *wl_display)
{
	struct wl_proxy *callback;

	callback = wl_proxy_marshal_flags((struct wl_proxy *) wl_display,
			 WL_DISPLAY_SYNC, &wl_callback_interface, wl_proxy_get_version((struct wl_proxy *) wl_display), 0, NULL);

	return (struct wl_callback *) callback;
}

static inline struct wl_registry *
wl_display_get_registry(struct wl_display *wl_display)
{
	struct wl_proxy *registry;

	registry = wl_proxy_marshal_flags((struct wl_proxy *) wl_display,
			 WL_DISPLAY_GET_REGISTRY, &wl_registry_interface, wl_proxy_get_version((struct wl_proxy *) wl_display), 0, NULL);

	return (struct wl_registry *) registry;
}

struct wl_registry_listener {
	void (*global)(void *data,
		       struct wl_registry *wl_registry,
		       uint32_t name,
		       const char *interface,
		       uint32_t version);
	void (*global_remove)(void *data,
			      struct wl_registry *wl_registry,
			      uint32_t name);
};

static inline int
wl_registry_add_listener(struct wl_registry *wl_registry,
			 const struct wl_registry_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_registry,
				     (void (**)(void)) listener, data);
}

#define WL_REGISTRY_BIND 0

#define WL_REGISTRY_GLOBAL_SINCE_VERSION 1
#define WL_REGISTRY_GLOBAL_REMOVE_SINCE_VERSION 1

#define WL_REGISTRY_BIND_SINCE_VERSION 1

static inline void
wl_registry_set_user_data(struct wl_registry *wl_registry, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_registry, user_data);
}

static inline void *
wl_registry_get_user_data(struct wl_registry *wl_registry)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_registry);
}

static inline uint32_t
wl_registry_get_version(struct wl_registry *wl_registry)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_registry);
}

static inline void
wl_registry_destroy(struct wl_registry *wl_registry)
{
	wl_proxy_destroy((struct wl_proxy *) wl_registry);
}

static inline void *
wl_registry_bind(struct wl_registry *wl_registry, uint32_t name, const struct wl_interface *interface, uint32_t version)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_registry,
			 WL_REGISTRY_BIND, interface, version, 0, name, interface->name, version, NULL);

	return (void *) id;
}

struct wl_callback_listener {
	void (*done)(void *data,
		     struct wl_callback *wl_callback,
		     uint32_t callback_data);
};

static inline int
wl_callback_add_listener(struct wl_callback *wl_callback,
			 const struct wl_callback_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_callback,
				     (void (**)(void)) listener, data);
}

#define WL_CALLBACK_DONE_SINCE_VERSION 1


static inline void
wl_callback_set_user_data(struct wl_callback *wl_callback, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_callback, user_data);
}

static inline void *
wl_callback_get_user_data(struct wl_callback *wl_callback)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_callback);
}

static inline uint32_t
wl_callback_get_version(struct wl_callback *wl_callback)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_callback);
}

static inline void
wl_callback_destroy(struct wl_callback *wl_callback)
{
	wl_proxy_destroy((struct wl_proxy *) wl_callback);
}

#define WL_COMPOSITOR_CREATE_SURFACE 0
#define WL_COMPOSITOR_CREATE_REGION 1
#define WL_COMPOSITOR_RELEASE 2


#define WL_COMPOSITOR_CREATE_SURFACE_SINCE_VERSION 1
#define WL_COMPOSITOR_CREATE_REGION_SINCE_VERSION 1
#define WL_COMPOSITOR_RELEASE_SINCE_VERSION 7

static inline void
wl_compositor_set_user_data(struct wl_compositor *wl_compositor, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_compositor, user_data);
}

static inline void *
wl_compositor_get_user_data(struct wl_compositor *wl_compositor)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_compositor);
}

static inline uint32_t
wl_compositor_get_version(struct wl_compositor *wl_compositor)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_compositor);
}

static inline void
wl_compositor_destroy(struct wl_compositor *wl_compositor)
{
	wl_proxy_destroy((struct wl_proxy *) wl_compositor);
}

static inline struct wl_surface *
wl_compositor_create_surface(struct wl_compositor *wl_compositor)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_compositor,
			 WL_COMPOSITOR_CREATE_SURFACE, &wl_surface_interface, wl_proxy_get_version((struct wl_proxy *) wl_compositor), 0, NULL);

	return (struct wl_surface *) id;
}

static inline struct wl_region *
wl_compositor_create_region(struct wl_compositor *wl_compositor)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_compositor,
			 WL_COMPOSITOR_CREATE_REGION, &wl_region_interface, wl_proxy_get_version((struct wl_proxy *) wl_compositor), 0, NULL);

	return (struct wl_region *) id;
}

static inline void
wl_compositor_release(struct wl_compositor *wl_compositor)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_compositor,
			 WL_COMPOSITOR_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_compositor), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef WL_SHM_POOL_ERROR_ENUM
#define WL_SHM_POOL_ERROR_ENUM
enum wl_shm_pool_error {
	WL_SHM_POOL_ERROR_INVALID_FORMAT = 0,
	WL_SHM_POOL_ERROR_INVALID_STRIDE = 1,
};
#endif

#define WL_SHM_POOL_CREATE_BUFFER 0
#define WL_SHM_POOL_DESTROY 1
#define WL_SHM_POOL_RESIZE 2


#define WL_SHM_POOL_CREATE_BUFFER_SINCE_VERSION 1
#define WL_SHM_POOL_DESTROY_SINCE_VERSION 1
#define WL_SHM_POOL_RESIZE_SINCE_VERSION 1

static inline void
wl_shm_pool_set_user_data(struct wl_shm_pool *wl_shm_pool, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_shm_pool, user_data);
}

static inline void *
wl_shm_pool_get_user_data(struct wl_shm_pool *wl_shm_pool)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_shm_pool);
}

static inline uint32_t
wl_shm_pool_get_version(struct wl_shm_pool *wl_shm_pool)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_shm_pool);
}

static inline struct wl_buffer *
wl_shm_pool_create_buffer(struct wl_shm_pool *wl_shm_pool, int32_t offset, int32_t width, int32_t height, int32_t stride, uint32_t format)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_shm_pool,
			 WL_SHM_POOL_CREATE_BUFFER, &wl_buffer_interface, wl_proxy_get_version((struct wl_proxy *) wl_shm_pool), 0, NULL, offset, width, height, stride, format);

	return (struct wl_buffer *) id;
}

static inline void
wl_shm_pool_destroy(struct wl_shm_pool *wl_shm_pool)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shm_pool,
			 WL_SHM_POOL_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shm_pool), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_shm_pool_resize(struct wl_shm_pool *wl_shm_pool, int32_t size)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shm_pool,
			 WL_SHM_POOL_RESIZE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shm_pool), 0, size);
}

#ifndef WL_SHM_ERROR_ENUM
#define WL_SHM_ERROR_ENUM
enum wl_shm_error {
	WL_SHM_ERROR_INVALID_FORMAT = 0,
	WL_SHM_ERROR_INVALID_STRIDE = 1,
	WL_SHM_ERROR_INVALID_FD = 2,
};
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

struct wl_shm_listener {
	void (*format)(void *data,
		       struct wl_shm *wl_shm,
		       uint32_t format);
};

static inline int
wl_shm_add_listener(struct wl_shm *wl_shm,
		    const struct wl_shm_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_shm,
				     (void (**)(void)) listener, data);
}

#define WL_SHM_CREATE_POOL 0
#define WL_SHM_RELEASE 1

#define WL_SHM_FORMAT_SINCE_VERSION 1

#define WL_SHM_CREATE_POOL_SINCE_VERSION 1
#define WL_SHM_RELEASE_SINCE_VERSION 2

static inline void
wl_shm_set_user_data(struct wl_shm *wl_shm, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_shm, user_data);
}

static inline void *
wl_shm_get_user_data(struct wl_shm *wl_shm)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_shm);
}

static inline uint32_t
wl_shm_get_version(struct wl_shm *wl_shm)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_shm);
}

static inline void
wl_shm_destroy(struct wl_shm *wl_shm)
{
	wl_proxy_destroy((struct wl_proxy *) wl_shm);
}

static inline struct wl_shm_pool *
wl_shm_create_pool(struct wl_shm *wl_shm, int32_t fd, int32_t size)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_shm,
			 WL_SHM_CREATE_POOL, &wl_shm_pool_interface, wl_proxy_get_version((struct wl_proxy *) wl_shm), 0, NULL, fd, size);

	return (struct wl_shm_pool *) id;
}

static inline void
wl_shm_release(struct wl_shm *wl_shm)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shm,
			 WL_SHM_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shm), WL_MARSHAL_FLAG_DESTROY);
}

struct wl_buffer_listener {
	void (*release)(void *data,
			struct wl_buffer *wl_buffer);
};

static inline int
wl_buffer_add_listener(struct wl_buffer *wl_buffer,
		       const struct wl_buffer_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_buffer,
				     (void (**)(void)) listener, data);
}

#define WL_BUFFER_DESTROY 0

#define WL_BUFFER_RELEASE_SINCE_VERSION 1

#define WL_BUFFER_DESTROY_SINCE_VERSION 1

static inline void
wl_buffer_set_user_data(struct wl_buffer *wl_buffer, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_buffer, user_data);
}

static inline void *
wl_buffer_get_user_data(struct wl_buffer *wl_buffer)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_buffer);
}

static inline uint32_t
wl_buffer_get_version(struct wl_buffer *wl_buffer)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_buffer);
}

static inline void
wl_buffer_destroy(struct wl_buffer *wl_buffer)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_buffer,
			 WL_BUFFER_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_buffer), WL_MARSHAL_FLAG_DESTROY);
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

struct wl_data_offer_listener {
	void (*offer)(void *data,
		      struct wl_data_offer *wl_data_offer,
		      const char *mime_type);
	void (*source_actions)(void *data,
			       struct wl_data_offer *wl_data_offer,
			       uint32_t source_actions);
	void (*action)(void *data,
		       struct wl_data_offer *wl_data_offer,
		       uint32_t dnd_action);
};

static inline int
wl_data_offer_add_listener(struct wl_data_offer *wl_data_offer,
			   const struct wl_data_offer_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_data_offer,
				     (void (**)(void)) listener, data);
}

#define WL_DATA_OFFER_ACCEPT 0
#define WL_DATA_OFFER_RECEIVE 1
#define WL_DATA_OFFER_DESTROY 2
#define WL_DATA_OFFER_FINISH 3
#define WL_DATA_OFFER_SET_ACTIONS 4

#define WL_DATA_OFFER_OFFER_SINCE_VERSION 1
#define WL_DATA_OFFER_SOURCE_ACTIONS_SINCE_VERSION 3
#define WL_DATA_OFFER_ACTION_SINCE_VERSION 3

#define WL_DATA_OFFER_ACCEPT_SINCE_VERSION 1
#define WL_DATA_OFFER_RECEIVE_SINCE_VERSION 1
#define WL_DATA_OFFER_DESTROY_SINCE_VERSION 1
#define WL_DATA_OFFER_FINISH_SINCE_VERSION 3
#define WL_DATA_OFFER_SET_ACTIONS_SINCE_VERSION 3

static inline void
wl_data_offer_set_user_data(struct wl_data_offer *wl_data_offer, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_data_offer, user_data);
}

static inline void *
wl_data_offer_get_user_data(struct wl_data_offer *wl_data_offer)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_data_offer);
}

static inline uint32_t
wl_data_offer_get_version(struct wl_data_offer *wl_data_offer)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_data_offer);
}

static inline void
wl_data_offer_accept(struct wl_data_offer *wl_data_offer, uint32_t serial, const char *mime_type)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_offer,
			 WL_DATA_OFFER_ACCEPT, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_offer), 0, serial, mime_type);
}

static inline void
wl_data_offer_receive(struct wl_data_offer *wl_data_offer, const char *mime_type, int32_t fd)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_offer,
			 WL_DATA_OFFER_RECEIVE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_offer), 0, mime_type, fd);
}

static inline void
wl_data_offer_destroy(struct wl_data_offer *wl_data_offer)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_offer,
			 WL_DATA_OFFER_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_offer), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_data_offer_finish(struct wl_data_offer *wl_data_offer)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_offer,
			 WL_DATA_OFFER_FINISH, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_offer), 0);
}

static inline void
wl_data_offer_set_actions(struct wl_data_offer *wl_data_offer, uint32_t dnd_actions, uint32_t preferred_action)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_offer,
			 WL_DATA_OFFER_SET_ACTIONS, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_offer), 0, dnd_actions, preferred_action);
}

#ifndef WL_DATA_SOURCE_ERROR_ENUM
#define WL_DATA_SOURCE_ERROR_ENUM
enum wl_data_source_error {
	WL_DATA_SOURCE_ERROR_INVALID_ACTION_MASK = 0,
	WL_DATA_SOURCE_ERROR_INVALID_SOURCE = 1,
};
#endif

struct wl_data_source_listener {
	void (*target)(void *data,
		       struct wl_data_source *wl_data_source,
		       const char *mime_type);
	void (*send)(void *data,
		     struct wl_data_source *wl_data_source,
		     const char *mime_type,
		     int32_t fd);
	void (*cancelled)(void *data,
			  struct wl_data_source *wl_data_source);
	void (*dnd_drop_performed)(void *data,
				   struct wl_data_source *wl_data_source);
	void (*dnd_finished)(void *data,
			     struct wl_data_source *wl_data_source);
	void (*action)(void *data,
		       struct wl_data_source *wl_data_source,
		       uint32_t dnd_action);
};

static inline int
wl_data_source_add_listener(struct wl_data_source *wl_data_source,
			    const struct wl_data_source_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_data_source,
				     (void (**)(void)) listener, data);
}

#define WL_DATA_SOURCE_OFFER 0
#define WL_DATA_SOURCE_DESTROY 1
#define WL_DATA_SOURCE_SET_ACTIONS 2

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
wl_data_source_set_user_data(struct wl_data_source *wl_data_source, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_data_source, user_data);
}

static inline void *
wl_data_source_get_user_data(struct wl_data_source *wl_data_source)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_data_source);
}

static inline uint32_t
wl_data_source_get_version(struct wl_data_source *wl_data_source)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_data_source);
}

static inline void
wl_data_source_offer(struct wl_data_source *wl_data_source, const char *mime_type)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_source,
			 WL_DATA_SOURCE_OFFER, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_source), 0, mime_type);
}

static inline void
wl_data_source_destroy(struct wl_data_source *wl_data_source)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_source,
			 WL_DATA_SOURCE_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_source), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_data_source_set_actions(struct wl_data_source *wl_data_source, uint32_t dnd_actions)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_source,
			 WL_DATA_SOURCE_SET_ACTIONS, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_source), 0, dnd_actions);
}

#ifndef WL_DATA_DEVICE_ERROR_ENUM
#define WL_DATA_DEVICE_ERROR_ENUM
enum wl_data_device_error {
	WL_DATA_DEVICE_ERROR_ROLE = 0,
	WL_DATA_DEVICE_ERROR_USED_SOURCE = 1,
};
#endif

struct wl_data_device_listener {
	void (*data_offer)(void *data,
			   struct wl_data_device *wl_data_device,
			   struct wl_data_offer *id);
	void (*enter)(void *data,
		      struct wl_data_device *wl_data_device,
		      uint32_t serial,
		      struct wl_surface *surface,
		      wl_fixed_t x,
		      wl_fixed_t y,
		      struct wl_data_offer *id);
	void (*leave)(void *data,
		      struct wl_data_device *wl_data_device);
	void (*motion)(void *data,
		       struct wl_data_device *wl_data_device,
		       uint32_t time,
		       wl_fixed_t x,
		       wl_fixed_t y);
	void (*drop)(void *data,
		     struct wl_data_device *wl_data_device);
	void (*selection)(void *data,
			  struct wl_data_device *wl_data_device,
			  struct wl_data_offer *id);
};

static inline int
wl_data_device_add_listener(struct wl_data_device *wl_data_device,
			    const struct wl_data_device_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_data_device,
				     (void (**)(void)) listener, data);
}

#define WL_DATA_DEVICE_START_DRAG 0
#define WL_DATA_DEVICE_SET_SELECTION 1
#define WL_DATA_DEVICE_RELEASE 2

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
wl_data_device_set_user_data(struct wl_data_device *wl_data_device, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_data_device, user_data);
}

static inline void *
wl_data_device_get_user_data(struct wl_data_device *wl_data_device)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_data_device);
}

static inline uint32_t
wl_data_device_get_version(struct wl_data_device *wl_data_device)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_data_device);
}

static inline void
wl_data_device_destroy(struct wl_data_device *wl_data_device)
{
	wl_proxy_destroy((struct wl_proxy *) wl_data_device);
}

static inline void
wl_data_device_start_drag(struct wl_data_device *wl_data_device, struct wl_data_source *source, struct wl_surface *origin, struct wl_surface *icon, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_device,
			 WL_DATA_DEVICE_START_DRAG, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_device), 0, source, origin, icon, serial);
}

static inline void
wl_data_device_set_selection(struct wl_data_device *wl_data_device, struct wl_data_source *source, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_device,
			 WL_DATA_DEVICE_SET_SELECTION, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_device), 0, source, serial);
}

static inline void
wl_data_device_release(struct wl_data_device *wl_data_device)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_device,
			 WL_DATA_DEVICE_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_device), WL_MARSHAL_FLAG_DESTROY);
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

#define WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE 0
#define WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE 1
#define WL_DATA_DEVICE_MANAGER_RELEASE 2


#define WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE_SINCE_VERSION 1
#define WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE_SINCE_VERSION 1
#define WL_DATA_DEVICE_MANAGER_RELEASE_SINCE_VERSION 4

static inline void
wl_data_device_manager_set_user_data(struct wl_data_device_manager *wl_data_device_manager, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_data_device_manager, user_data);
}

static inline void *
wl_data_device_manager_get_user_data(struct wl_data_device_manager *wl_data_device_manager)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_data_device_manager);
}

static inline uint32_t
wl_data_device_manager_get_version(struct wl_data_device_manager *wl_data_device_manager)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_data_device_manager);
}

static inline void
wl_data_device_manager_destroy(struct wl_data_device_manager *wl_data_device_manager)
{
	wl_proxy_destroy((struct wl_proxy *) wl_data_device_manager);
}

static inline struct wl_data_source *
wl_data_device_manager_create_data_source(struct wl_data_device_manager *wl_data_device_manager)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_data_device_manager,
			 WL_DATA_DEVICE_MANAGER_CREATE_DATA_SOURCE, &wl_data_source_interface, wl_proxy_get_version((struct wl_proxy *) wl_data_device_manager), 0, NULL);

	return (struct wl_data_source *) id;
}

static inline struct wl_data_device *
wl_data_device_manager_get_data_device(struct wl_data_device_manager *wl_data_device_manager, struct wl_seat *seat)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_data_device_manager,
			 WL_DATA_DEVICE_MANAGER_GET_DATA_DEVICE, &wl_data_device_interface, wl_proxy_get_version((struct wl_proxy *) wl_data_device_manager), 0, NULL, seat);

	return (struct wl_data_device *) id;
}

static inline void
wl_data_device_manager_release(struct wl_data_device_manager *wl_data_device_manager)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_data_device_manager,
			 WL_DATA_DEVICE_MANAGER_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_data_device_manager), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef WL_SHELL_ERROR_ENUM
#define WL_SHELL_ERROR_ENUM
enum wl_shell_error {
	WL_SHELL_ERROR_ROLE = 0,
};
#endif

#define WL_SHELL_GET_SHELL_SURFACE 0


#define WL_SHELL_GET_SHELL_SURFACE_SINCE_VERSION 1

static inline void
wl_shell_set_user_data(struct wl_shell *wl_shell, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_shell, user_data);
}

static inline void *
wl_shell_get_user_data(struct wl_shell *wl_shell)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_shell);
}

static inline uint32_t
wl_shell_get_version(struct wl_shell *wl_shell)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_shell);
}

static inline void
wl_shell_destroy(struct wl_shell *wl_shell)
{
	wl_proxy_destroy((struct wl_proxy *) wl_shell);
}

static inline struct wl_shell_surface *
wl_shell_get_shell_surface(struct wl_shell *wl_shell, struct wl_surface *surface)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_shell,
			 WL_SHELL_GET_SHELL_SURFACE, &wl_shell_surface_interface, wl_proxy_get_version((struct wl_proxy *) wl_shell), 0, NULL, surface);

	return (struct wl_shell_surface *) id;
}

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

#ifndef WL_SHELL_SURFACE_TRANSIENT_ENUM
#define WL_SHELL_SURFACE_TRANSIENT_ENUM
enum wl_shell_surface_transient {
	WL_SHELL_SURFACE_TRANSIENT_INACTIVE = 0x1,
};
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

struct wl_shell_surface_listener {
	void (*ping)(void *data,
		     struct wl_shell_surface *wl_shell_surface,
		     uint32_t serial);
	void (*configure)(void *data,
			  struct wl_shell_surface *wl_shell_surface,
			  uint32_t edges,
			  int32_t width,
			  int32_t height);
	void (*popup_done)(void *data,
			   struct wl_shell_surface *wl_shell_surface);
};

static inline int
wl_shell_surface_add_listener(struct wl_shell_surface *wl_shell_surface,
			      const struct wl_shell_surface_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_shell_surface,
				     (void (**)(void)) listener, data);
}

#define WL_SHELL_SURFACE_PONG 0
#define WL_SHELL_SURFACE_MOVE 1
#define WL_SHELL_SURFACE_RESIZE 2
#define WL_SHELL_SURFACE_SET_TOPLEVEL 3
#define WL_SHELL_SURFACE_SET_TRANSIENT 4
#define WL_SHELL_SURFACE_SET_FULLSCREEN 5
#define WL_SHELL_SURFACE_SET_POPUP 6
#define WL_SHELL_SURFACE_SET_MAXIMIZED 7
#define WL_SHELL_SURFACE_SET_TITLE 8
#define WL_SHELL_SURFACE_SET_CLASS 9

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
wl_shell_surface_set_user_data(struct wl_shell_surface *wl_shell_surface, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_shell_surface, user_data);
}

static inline void *
wl_shell_surface_get_user_data(struct wl_shell_surface *wl_shell_surface)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_shell_surface);
}

static inline uint32_t
wl_shell_surface_get_version(struct wl_shell_surface *wl_shell_surface)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_shell_surface);
}

static inline void
wl_shell_surface_destroy(struct wl_shell_surface *wl_shell_surface)
{
	wl_proxy_destroy((struct wl_proxy *) wl_shell_surface);
}

static inline void
wl_shell_surface_pong(struct wl_shell_surface *wl_shell_surface, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_PONG, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, serial);
}

static inline void
wl_shell_surface_move(struct wl_shell_surface *wl_shell_surface, struct wl_seat *seat, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_MOVE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, seat, serial);
}

static inline void
wl_shell_surface_resize(struct wl_shell_surface *wl_shell_surface, struct wl_seat *seat, uint32_t serial, uint32_t edges)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_RESIZE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, seat, serial, edges);
}

static inline void
wl_shell_surface_set_toplevel(struct wl_shell_surface *wl_shell_surface)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_TOPLEVEL, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0);
}

static inline void
wl_shell_surface_set_transient(struct wl_shell_surface *wl_shell_surface, struct wl_surface *parent, int32_t x, int32_t y, uint32_t flags)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_TRANSIENT, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, parent, x, y, flags);
}

static inline void
wl_shell_surface_set_fullscreen(struct wl_shell_surface *wl_shell_surface, uint32_t method, uint32_t framerate, struct wl_output *output)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_FULLSCREEN, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, method, framerate, output);
}

static inline void
wl_shell_surface_set_popup(struct wl_shell_surface *wl_shell_surface, struct wl_seat *seat, uint32_t serial, struct wl_surface *parent, int32_t x, int32_t y, uint32_t flags)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_POPUP, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, seat, serial, parent, x, y, flags);
}

static inline void
wl_shell_surface_set_maximized(struct wl_shell_surface *wl_shell_surface, struct wl_output *output)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_MAXIMIZED, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, output);
}

static inline void
wl_shell_surface_set_title(struct wl_shell_surface *wl_shell_surface, const char *title)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_TITLE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, title);
}

static inline void
wl_shell_surface_set_class(struct wl_shell_surface *wl_shell_surface, const char *class_)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_shell_surface,
			 WL_SHELL_SURFACE_SET_CLASS, NULL, wl_proxy_get_version((struct wl_proxy *) wl_shell_surface), 0, class_);
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

struct wl_surface_listener {
	void (*enter)(void *data,
		      struct wl_surface *wl_surface,
		      struct wl_output *output);
	void (*leave)(void *data,
		      struct wl_surface *wl_surface,
		      struct wl_output *output);
	void (*preferred_buffer_scale)(void *data,
				       struct wl_surface *wl_surface,
				       int32_t factor);
	void (*preferred_buffer_transform)(void *data,
					   struct wl_surface *wl_surface,
					   uint32_t transform);
};

static inline int
wl_surface_add_listener(struct wl_surface *wl_surface,
			const struct wl_surface_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_surface,
				     (void (**)(void)) listener, data);
}

#define WL_SURFACE_DESTROY 0
#define WL_SURFACE_ATTACH 1
#define WL_SURFACE_DAMAGE 2
#define WL_SURFACE_FRAME 3
#define WL_SURFACE_SET_OPAQUE_REGION 4
#define WL_SURFACE_SET_INPUT_REGION 5
#define WL_SURFACE_COMMIT 6
#define WL_SURFACE_SET_BUFFER_TRANSFORM 7
#define WL_SURFACE_SET_BUFFER_SCALE 8
#define WL_SURFACE_DAMAGE_BUFFER 9
#define WL_SURFACE_OFFSET 10
#define WL_SURFACE_GET_RELEASE 11

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
wl_surface_set_user_data(struct wl_surface *wl_surface, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_surface, user_data);
}

static inline void *
wl_surface_get_user_data(struct wl_surface *wl_surface)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_surface);
}

static inline uint32_t
wl_surface_get_version(struct wl_surface *wl_surface)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_surface);
}

static inline void
wl_surface_destroy(struct wl_surface *wl_surface)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_surface_attach(struct wl_surface *wl_surface, struct wl_buffer *buffer, int32_t x, int32_t y)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_ATTACH, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, buffer, x, y);
}

static inline void
wl_surface_damage(struct wl_surface *wl_surface, int32_t x, int32_t y, int32_t width, int32_t height)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_DAMAGE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, x, y, width, height);
}

static inline struct wl_callback *
wl_surface_frame(struct wl_surface *wl_surface)
{
	struct wl_proxy *callback;

	callback = wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_FRAME, &wl_callback_interface, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, NULL);

	return (struct wl_callback *) callback;
}

static inline void
wl_surface_set_opaque_region(struct wl_surface *wl_surface, struct wl_region *region)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_SET_OPAQUE_REGION, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, region);
}

static inline void
wl_surface_set_input_region(struct wl_surface *wl_surface, struct wl_region *region)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_SET_INPUT_REGION, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, region);
}

static inline void
wl_surface_commit(struct wl_surface *wl_surface)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_COMMIT, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0);
}

static inline void
wl_surface_set_buffer_transform(struct wl_surface *wl_surface, int32_t transform)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_SET_BUFFER_TRANSFORM, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, transform);
}

static inline void
wl_surface_set_buffer_scale(struct wl_surface *wl_surface, int32_t scale)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_SET_BUFFER_SCALE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, scale);
}

static inline void
wl_surface_damage_buffer(struct wl_surface *wl_surface, int32_t x, int32_t y, int32_t width, int32_t height)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_DAMAGE_BUFFER, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, x, y, width, height);
}

static inline void
wl_surface_offset(struct wl_surface *wl_surface, int32_t x, int32_t y)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_OFFSET, NULL, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, x, y);
}

static inline struct wl_callback *
wl_surface_get_release(struct wl_surface *wl_surface)
{
	struct wl_proxy *callback;

	callback = wl_proxy_marshal_flags((struct wl_proxy *) wl_surface,
			 WL_SURFACE_GET_RELEASE, &wl_callback_interface, wl_proxy_get_version((struct wl_proxy *) wl_surface), 0, NULL);

	return (struct wl_callback *) callback;
}

#ifndef WL_SEAT_CAPABILITY_ENUM
#define WL_SEAT_CAPABILITY_ENUM
enum wl_seat_capability {
	WL_SEAT_CAPABILITY_POINTER = 1,
	WL_SEAT_CAPABILITY_KEYBOARD = 2,
	WL_SEAT_CAPABILITY_TOUCH = 4,
};
#endif

#ifndef WL_SEAT_ERROR_ENUM
#define WL_SEAT_ERROR_ENUM
enum wl_seat_error {
	WL_SEAT_ERROR_MISSING_CAPABILITY = 0,
};
#endif

struct wl_seat_listener {
	void (*capabilities)(void *data,
			     struct wl_seat *wl_seat,
			     uint32_t capabilities);
	void (*name)(void *data,
		     struct wl_seat *wl_seat,
		     const char *name);
};

static inline int
wl_seat_add_listener(struct wl_seat *wl_seat,
		     const struct wl_seat_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_seat,
				     (void (**)(void)) listener, data);
}

#define WL_SEAT_GET_POINTER 0
#define WL_SEAT_GET_KEYBOARD 1
#define WL_SEAT_GET_TOUCH 2
#define WL_SEAT_RELEASE 3

#define WL_SEAT_CAPABILITIES_SINCE_VERSION 1
#define WL_SEAT_NAME_SINCE_VERSION 2

#define WL_SEAT_GET_POINTER_SINCE_VERSION 1
#define WL_SEAT_GET_KEYBOARD_SINCE_VERSION 1
#define WL_SEAT_GET_TOUCH_SINCE_VERSION 1
#define WL_SEAT_RELEASE_SINCE_VERSION 5

static inline void
wl_seat_set_user_data(struct wl_seat *wl_seat, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_seat, user_data);
}

static inline void *
wl_seat_get_user_data(struct wl_seat *wl_seat)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_seat);
}

static inline uint32_t
wl_seat_get_version(struct wl_seat *wl_seat)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_seat);
}

static inline void
wl_seat_destroy(struct wl_seat *wl_seat)
{
	wl_proxy_destroy((struct wl_proxy *) wl_seat);
}

static inline struct wl_pointer *
wl_seat_get_pointer(struct wl_seat *wl_seat)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_seat,
			 WL_SEAT_GET_POINTER, &wl_pointer_interface, wl_proxy_get_version((struct wl_proxy *) wl_seat), 0, NULL);

	return (struct wl_pointer *) id;
}

static inline struct wl_keyboard *
wl_seat_get_keyboard(struct wl_seat *wl_seat)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_seat,
			 WL_SEAT_GET_KEYBOARD, &wl_keyboard_interface, wl_proxy_get_version((struct wl_proxy *) wl_seat), 0, NULL);

	return (struct wl_keyboard *) id;
}

static inline struct wl_touch *
wl_seat_get_touch(struct wl_seat *wl_seat)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_seat,
			 WL_SEAT_GET_TOUCH, &wl_touch_interface, wl_proxy_get_version((struct wl_proxy *) wl_seat), 0, NULL);

	return (struct wl_touch *) id;
}

static inline void
wl_seat_release(struct wl_seat *wl_seat)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_seat,
			 WL_SEAT_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_seat), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef WL_POINTER_ERROR_ENUM
#define WL_POINTER_ERROR_ENUM
enum wl_pointer_error {
	WL_POINTER_ERROR_ROLE = 0,
};
#endif

#ifndef WL_POINTER_BUTTON_STATE_ENUM
#define WL_POINTER_BUTTON_STATE_ENUM
enum wl_pointer_button_state {
	WL_POINTER_BUTTON_STATE_RELEASED = 0,
	WL_POINTER_BUTTON_STATE_PRESSED = 1,
};
#endif

#ifndef WL_POINTER_AXIS_ENUM
#define WL_POINTER_AXIS_ENUM
enum wl_pointer_axis {
	WL_POINTER_AXIS_VERTICAL_SCROLL = 0,
	WL_POINTER_AXIS_HORIZONTAL_SCROLL = 1,
};
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

#ifndef WL_POINTER_AXIS_RELATIVE_DIRECTION_ENUM
#define WL_POINTER_AXIS_RELATIVE_DIRECTION_ENUM
enum wl_pointer_axis_relative_direction {
	WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL = 0,
	WL_POINTER_AXIS_RELATIVE_DIRECTION_INVERTED = 1,
};
#endif

struct wl_pointer_listener {
	void (*enter)(void *data,
		      struct wl_pointer *wl_pointer,
		      uint32_t serial,
		      struct wl_surface *surface,
		      wl_fixed_t surface_x,
		      wl_fixed_t surface_y);
	void (*leave)(void *data,
		      struct wl_pointer *wl_pointer,
		      uint32_t serial,
		      struct wl_surface *surface);
	void (*motion)(void *data,
		       struct wl_pointer *wl_pointer,
		       uint32_t time,
		       wl_fixed_t surface_x,
		       wl_fixed_t surface_y);
	void (*button)(void *data,
		       struct wl_pointer *wl_pointer,
		       uint32_t serial,
		       uint32_t time,
		       uint32_t button,
		       uint32_t state);
	void (*axis)(void *data,
		     struct wl_pointer *wl_pointer,
		     uint32_t time,
		     uint32_t axis,
		     wl_fixed_t value);
	void (*frame)(void *data,
		      struct wl_pointer *wl_pointer);
	void (*axis_source)(void *data,
			    struct wl_pointer *wl_pointer,
			    uint32_t axis_source);
	void (*axis_stop)(void *data,
			  struct wl_pointer *wl_pointer,
			  uint32_t time,
			  uint32_t axis);
	void (*axis_discrete)(void *data,
			      struct wl_pointer *wl_pointer,
			      uint32_t axis,
			      int32_t discrete);
	void (*axis_value120)(void *data,
			      struct wl_pointer *wl_pointer,
			      uint32_t axis,
			      int32_t value120);
	void (*axis_relative_direction)(void *data,
					struct wl_pointer *wl_pointer,
					uint32_t axis,
					uint32_t direction);
	void (*warp)(void *data,
		     struct wl_pointer *wl_pointer,
		     wl_fixed_t surface_x,
		     wl_fixed_t surface_y);
};

static inline int
wl_pointer_add_listener(struct wl_pointer *wl_pointer,
			const struct wl_pointer_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_pointer,
				     (void (**)(void)) listener, data);
}

#define WL_POINTER_SET_CURSOR 0
#define WL_POINTER_RELEASE 1

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
wl_pointer_set_user_data(struct wl_pointer *wl_pointer, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_pointer, user_data);
}

static inline void *
wl_pointer_get_user_data(struct wl_pointer *wl_pointer)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_pointer);
}

static inline uint32_t
wl_pointer_get_version(struct wl_pointer *wl_pointer)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_pointer);
}

static inline void
wl_pointer_destroy(struct wl_pointer *wl_pointer)
{
	wl_proxy_destroy((struct wl_proxy *) wl_pointer);
}

static inline void
wl_pointer_set_cursor(struct wl_pointer *wl_pointer, uint32_t serial, struct wl_surface *surface, int32_t hotspot_x, int32_t hotspot_y)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_pointer,
			 WL_POINTER_SET_CURSOR, NULL, wl_proxy_get_version((struct wl_proxy *) wl_pointer), 0, serial, surface, hotspot_x, hotspot_y);
}

static inline void
wl_pointer_release(struct wl_pointer *wl_pointer)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_pointer,
			 WL_POINTER_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_pointer), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef WL_KEYBOARD_KEYMAP_FORMAT_ENUM
#define WL_KEYBOARD_KEYMAP_FORMAT_ENUM
enum wl_keyboard_keymap_format {
	WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP = 0,
	WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 = 1,
};
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

struct wl_keyboard_listener {
	void (*keymap)(void *data,
		       struct wl_keyboard *wl_keyboard,
		       uint32_t format,
		       int32_t fd,
		       uint32_t size);
	void (*enter)(void *data,
		      struct wl_keyboard *wl_keyboard,
		      uint32_t serial,
		      struct wl_surface *surface,
		      struct wl_array *keys);
	void (*leave)(void *data,
		      struct wl_keyboard *wl_keyboard,
		      uint32_t serial,
		      struct wl_surface *surface);
	void (*key)(void *data,
		    struct wl_keyboard *wl_keyboard,
		    uint32_t serial,
		    uint32_t time,
		    uint32_t key,
		    uint32_t state);
	void (*modifiers)(void *data,
			  struct wl_keyboard *wl_keyboard,
			  uint32_t serial,
			  uint32_t mods_depressed,
			  uint32_t mods_latched,
			  uint32_t mods_locked,
			  uint32_t group);
	void (*repeat_info)(void *data,
			    struct wl_keyboard *wl_keyboard,
			    int32_t rate,
			    int32_t delay);
};

static inline int
wl_keyboard_add_listener(struct wl_keyboard *wl_keyboard,
			 const struct wl_keyboard_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_keyboard,
				     (void (**)(void)) listener, data);
}

#define WL_KEYBOARD_RELEASE 0

#define WL_KEYBOARD_KEYMAP_SINCE_VERSION 1
#define WL_KEYBOARD_ENTER_SINCE_VERSION 1
#define WL_KEYBOARD_LEAVE_SINCE_VERSION 1
#define WL_KEYBOARD_KEY_SINCE_VERSION 1
#define WL_KEYBOARD_MODIFIERS_SINCE_VERSION 1
#define WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION 4

#define WL_KEYBOARD_RELEASE_SINCE_VERSION 3

static inline void
wl_keyboard_set_user_data(struct wl_keyboard *wl_keyboard, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_keyboard, user_data);
}

static inline void *
wl_keyboard_get_user_data(struct wl_keyboard *wl_keyboard)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_keyboard);
}

static inline uint32_t
wl_keyboard_get_version(struct wl_keyboard *wl_keyboard)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_keyboard);
}

static inline void
wl_keyboard_destroy(struct wl_keyboard *wl_keyboard)
{
	wl_proxy_destroy((struct wl_proxy *) wl_keyboard);
}

static inline void
wl_keyboard_release(struct wl_keyboard *wl_keyboard)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_keyboard,
			 WL_KEYBOARD_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_keyboard), WL_MARSHAL_FLAG_DESTROY);
}

struct wl_touch_listener {
	void (*down)(void *data,
		     struct wl_touch *wl_touch,
		     uint32_t serial,
		     uint32_t time,
		     struct wl_surface *surface,
		     int32_t id,
		     wl_fixed_t x,
		     wl_fixed_t y);
	void (*up)(void *data,
		   struct wl_touch *wl_touch,
		   uint32_t serial,
		   uint32_t time,
		   int32_t id);
	void (*motion)(void *data,
		       struct wl_touch *wl_touch,
		       uint32_t time,
		       int32_t id,
		       wl_fixed_t x,
		       wl_fixed_t y);
	void (*frame)(void *data,
		      struct wl_touch *wl_touch);
	void (*cancel)(void *data,
		       struct wl_touch *wl_touch);
	void (*shape)(void *data,
		      struct wl_touch *wl_touch,
		      int32_t id,
		      wl_fixed_t major,
		      wl_fixed_t minor);
	void (*orientation)(void *data,
			    struct wl_touch *wl_touch,
			    int32_t id,
			    wl_fixed_t orientation);
};

static inline int
wl_touch_add_listener(struct wl_touch *wl_touch,
		      const struct wl_touch_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_touch,
				     (void (**)(void)) listener, data);
}

#define WL_TOUCH_RELEASE 0

#define WL_TOUCH_DOWN_SINCE_VERSION 1
#define WL_TOUCH_UP_SINCE_VERSION 1
#define WL_TOUCH_MOTION_SINCE_VERSION 1
#define WL_TOUCH_FRAME_SINCE_VERSION 1
#define WL_TOUCH_CANCEL_SINCE_VERSION 1
#define WL_TOUCH_SHAPE_SINCE_VERSION 6
#define WL_TOUCH_ORIENTATION_SINCE_VERSION 6

#define WL_TOUCH_RELEASE_SINCE_VERSION 3

static inline void
wl_touch_set_user_data(struct wl_touch *wl_touch, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_touch, user_data);
}

static inline void *
wl_touch_get_user_data(struct wl_touch *wl_touch)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_touch);
}

static inline uint32_t
wl_touch_get_version(struct wl_touch *wl_touch)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_touch);
}

static inline void
wl_touch_destroy(struct wl_touch *wl_touch)
{
	wl_proxy_destroy((struct wl_proxy *) wl_touch);
}

static inline void
wl_touch_release(struct wl_touch *wl_touch)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_touch,
			 WL_TOUCH_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_touch), WL_MARSHAL_FLAG_DESTROY);
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

#ifndef WL_OUTPUT_MODE_ENUM
#define WL_OUTPUT_MODE_ENUM
enum wl_output_mode {
	WL_OUTPUT_MODE_CURRENT = 0x1,
	WL_OUTPUT_MODE_PREFERRED = 0x2,
};
#endif

struct wl_output_listener {
	void (*geometry)(void *data,
			 struct wl_output *wl_output,
			 int32_t x,
			 int32_t y,
			 int32_t physical_width,
			 int32_t physical_height,
			 int32_t subpixel,
			 const char *make,
			 const char *model,
			 int32_t transform);
	void (*mode)(void *data,
		     struct wl_output *wl_output,
		     uint32_t flags,
		     int32_t width,
		     int32_t height,
		     int32_t refresh);
	void (*done)(void *data,
		     struct wl_output *wl_output);
	void (*scale)(void *data,
		      struct wl_output *wl_output,
		      int32_t factor);
	void (*name)(void *data,
		     struct wl_output *wl_output,
		     const char *name);
	void (*description)(void *data,
			    struct wl_output *wl_output,
			    const char *description);
};

static inline int
wl_output_add_listener(struct wl_output *wl_output,
		       const struct wl_output_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) wl_output,
				     (void (**)(void)) listener, data);
}

#define WL_OUTPUT_RELEASE 0

#define WL_OUTPUT_GEOMETRY_SINCE_VERSION 1
#define WL_OUTPUT_MODE_SINCE_VERSION 1
#define WL_OUTPUT_DONE_SINCE_VERSION 2
#define WL_OUTPUT_SCALE_SINCE_VERSION 2
#define WL_OUTPUT_NAME_SINCE_VERSION 4
#define WL_OUTPUT_DESCRIPTION_SINCE_VERSION 4

#define WL_OUTPUT_RELEASE_SINCE_VERSION 3

static inline void
wl_output_set_user_data(struct wl_output *wl_output, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_output, user_data);
}

static inline void *
wl_output_get_user_data(struct wl_output *wl_output)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_output);
}

static inline uint32_t
wl_output_get_version(struct wl_output *wl_output)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_output);
}

static inline void
wl_output_destroy(struct wl_output *wl_output)
{
	wl_proxy_destroy((struct wl_proxy *) wl_output);
}

static inline void
wl_output_release(struct wl_output *wl_output)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_output,
			 WL_OUTPUT_RELEASE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_output), WL_MARSHAL_FLAG_DESTROY);
}

#define WL_REGION_DESTROY 0
#define WL_REGION_ADD 1
#define WL_REGION_SUBTRACT 2


#define WL_REGION_DESTROY_SINCE_VERSION 1
#define WL_REGION_ADD_SINCE_VERSION 1
#define WL_REGION_SUBTRACT_SINCE_VERSION 1

static inline void
wl_region_set_user_data(struct wl_region *wl_region, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_region, user_data);
}

static inline void *
wl_region_get_user_data(struct wl_region *wl_region)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_region);
}

static inline uint32_t
wl_region_get_version(struct wl_region *wl_region)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_region);
}

static inline void
wl_region_destroy(struct wl_region *wl_region)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_region,
			 WL_REGION_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_region), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_region_add(struct wl_region *wl_region, int32_t x, int32_t y, int32_t width, int32_t height)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_region,
			 WL_REGION_ADD, NULL, wl_proxy_get_version((struct wl_proxy *) wl_region), 0, x, y, width, height);
}

static inline void
wl_region_subtract(struct wl_region *wl_region, int32_t x, int32_t y, int32_t width, int32_t height)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_region,
			 WL_REGION_SUBTRACT, NULL, wl_proxy_get_version((struct wl_proxy *) wl_region), 0, x, y, width, height);
}

#ifndef WL_SUBCOMPOSITOR_ERROR_ENUM
#define WL_SUBCOMPOSITOR_ERROR_ENUM
enum wl_subcompositor_error {
	WL_SUBCOMPOSITOR_ERROR_BAD_SURFACE = 0,
	WL_SUBCOMPOSITOR_ERROR_BAD_PARENT = 1,
};
#endif

#define WL_SUBCOMPOSITOR_DESTROY 0
#define WL_SUBCOMPOSITOR_GET_SUBSURFACE 1


#define WL_SUBCOMPOSITOR_DESTROY_SINCE_VERSION 1
#define WL_SUBCOMPOSITOR_GET_SUBSURFACE_SINCE_VERSION 1

static inline void
wl_subcompositor_set_user_data(struct wl_subcompositor *wl_subcompositor, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_subcompositor, user_data);
}

static inline void *
wl_subcompositor_get_user_data(struct wl_subcompositor *wl_subcompositor)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_subcompositor);
}

static inline uint32_t
wl_subcompositor_get_version(struct wl_subcompositor *wl_subcompositor)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_subcompositor);
}

static inline void
wl_subcompositor_destroy(struct wl_subcompositor *wl_subcompositor)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subcompositor,
			 WL_SUBCOMPOSITOR_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subcompositor), WL_MARSHAL_FLAG_DESTROY);
}

static inline struct wl_subsurface *
wl_subcompositor_get_subsurface(struct wl_subcompositor *wl_subcompositor, struct wl_surface *surface, struct wl_surface *parent)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) wl_subcompositor,
			 WL_SUBCOMPOSITOR_GET_SUBSURFACE, &wl_subsurface_interface, wl_proxy_get_version((struct wl_proxy *) wl_subcompositor), 0, NULL, surface, parent);

	return (struct wl_subsurface *) id;
}

#ifndef WL_SUBSURFACE_ERROR_ENUM
#define WL_SUBSURFACE_ERROR_ENUM
enum wl_subsurface_error {
	WL_SUBSURFACE_ERROR_BAD_SURFACE = 0,
};
#endif

#define WL_SUBSURFACE_DESTROY 0
#define WL_SUBSURFACE_SET_POSITION 1
#define WL_SUBSURFACE_PLACE_ABOVE 2
#define WL_SUBSURFACE_PLACE_BELOW 3
#define WL_SUBSURFACE_SET_SYNC 4
#define WL_SUBSURFACE_SET_DESYNC 5


#define WL_SUBSURFACE_DESTROY_SINCE_VERSION 1
#define WL_SUBSURFACE_SET_POSITION_SINCE_VERSION 1
#define WL_SUBSURFACE_PLACE_ABOVE_SINCE_VERSION 1
#define WL_SUBSURFACE_PLACE_BELOW_SINCE_VERSION 1
#define WL_SUBSURFACE_SET_SYNC_SINCE_VERSION 1
#define WL_SUBSURFACE_SET_DESYNC_SINCE_VERSION 1

static inline void
wl_subsurface_set_user_data(struct wl_subsurface *wl_subsurface, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_subsurface, user_data);
}

static inline void *
wl_subsurface_get_user_data(struct wl_subsurface *wl_subsurface)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_subsurface);
}

static inline uint32_t
wl_subsurface_get_version(struct wl_subsurface *wl_subsurface)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_subsurface);
}

static inline void
wl_subsurface_destroy(struct wl_subsurface *wl_subsurface)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subsurface,
			 WL_SUBSURFACE_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subsurface), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_subsurface_set_position(struct wl_subsurface *wl_subsurface, int32_t x, int32_t y)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subsurface,
			 WL_SUBSURFACE_SET_POSITION, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subsurface), 0, x, y);
}

static inline void
wl_subsurface_place_above(struct wl_subsurface *wl_subsurface, struct wl_surface *sibling)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subsurface,
			 WL_SUBSURFACE_PLACE_ABOVE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subsurface), 0, sibling);
}

static inline void
wl_subsurface_place_below(struct wl_subsurface *wl_subsurface, struct wl_surface *sibling)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subsurface,
			 WL_SUBSURFACE_PLACE_BELOW, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subsurface), 0, sibling);
}

static inline void
wl_subsurface_set_sync(struct wl_subsurface *wl_subsurface)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subsurface,
			 WL_SUBSURFACE_SET_SYNC, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subsurface), 0);
}

static inline void
wl_subsurface_set_desync(struct wl_subsurface *wl_subsurface)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_subsurface,
			 WL_SUBSURFACE_SET_DESYNC, NULL, wl_proxy_get_version((struct wl_proxy *) wl_subsurface), 0);
}

#ifndef WL_FIXES_ERROR_ENUM
#define WL_FIXES_ERROR_ENUM
enum wl_fixes_error {
	WL_FIXES_ERROR_INVALID_ACK_REMOVE = 0,
};
#endif

#define WL_FIXES_DESTROY 0
#define WL_FIXES_DESTROY_REGISTRY 1
#define WL_FIXES_ACK_GLOBAL_REMOVE 2


#define WL_FIXES_DESTROY_SINCE_VERSION 1
#define WL_FIXES_DESTROY_REGISTRY_SINCE_VERSION 1
#define WL_FIXES_ACK_GLOBAL_REMOVE_SINCE_VERSION 2

static inline void
wl_fixes_set_user_data(struct wl_fixes *wl_fixes, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) wl_fixes, user_data);
}

static inline void *
wl_fixes_get_user_data(struct wl_fixes *wl_fixes)
{
	return wl_proxy_get_user_data((struct wl_proxy *) wl_fixes);
}

static inline uint32_t
wl_fixes_get_version(struct wl_fixes *wl_fixes)
{
	return wl_proxy_get_version((struct wl_proxy *) wl_fixes);
}

static inline void
wl_fixes_destroy(struct wl_fixes *wl_fixes)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_fixes,
			 WL_FIXES_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_fixes), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
wl_fixes_destroy_registry(struct wl_fixes *wl_fixes, struct wl_registry *registry)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_fixes,
			 WL_FIXES_DESTROY_REGISTRY, NULL, wl_proxy_get_version((struct wl_proxy *) wl_fixes), 0, registry);
}

static inline void
wl_fixes_ack_global_remove(struct wl_fixes *wl_fixes, struct wl_registry *registry, uint32_t name)
{
	wl_proxy_marshal_flags((struct wl_proxy *) wl_fixes,
			 WL_FIXES_ACK_GLOBAL_REMOVE, NULL, wl_proxy_get_version((struct wl_proxy *) wl_fixes), 0, registry, name);
}

#ifdef  __cplusplus
}
#endif

#endif
