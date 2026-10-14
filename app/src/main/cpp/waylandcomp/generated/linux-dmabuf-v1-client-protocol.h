
#ifndef LINUX_DMABUF_V1_CLIENT_PROTOCOL_H
#define LINUX_DMABUF_V1_CLIENT_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-client.h"

#ifdef  __cplusplus
extern "C" {
#endif

/*
 * @section page_copyright_linux_dmabuf_v1 Copyright
 *
 * Copyright © 2014, 2015 Collabora, Ltd.
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







struct wl_buffer;
struct wl_surface;
struct zwp_linux_buffer_params_v1;
struct zwp_linux_dmabuf_feedback_v1;
struct zwp_linux_dmabuf_v1;

#ifndef ZWP_LINUX_DMABUF_V1_INTERFACE
#define ZWP_LINUX_DMABUF_V1_INTERFACE
extern const struct wl_interface zwp_linux_dmabuf_v1_interface;
#endif
#ifndef ZWP_LINUX_BUFFER_PARAMS_V1_INTERFACE
#define ZWP_LINUX_BUFFER_PARAMS_V1_INTERFACE
extern const struct wl_interface zwp_linux_buffer_params_v1_interface;
#endif
#ifndef ZWP_LINUX_DMABUF_FEEDBACK_V1_INTERFACE
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_INTERFACE
extern const struct wl_interface zwp_linux_dmabuf_feedback_v1_interface;
#endif

struct zwp_linux_dmabuf_v1_listener {
	void (*format)(void *data,
		       struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1,
		       uint32_t format);
	void (*modifier)(void *data,
			 struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1,
			 uint32_t format,
			 uint32_t modifier_hi,
			 uint32_t modifier_lo);
};

static inline int
zwp_linux_dmabuf_v1_add_listener(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1,
				 const struct zwp_linux_dmabuf_v1_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_linux_dmabuf_v1,
				     (void (**)(void)) listener, data);
}

#define ZWP_LINUX_DMABUF_V1_DESTROY 0
#define ZWP_LINUX_DMABUF_V1_CREATE_PARAMS 1
#define ZWP_LINUX_DMABUF_V1_GET_DEFAULT_FEEDBACK 2
#define ZWP_LINUX_DMABUF_V1_GET_SURFACE_FEEDBACK 3

#define ZWP_LINUX_DMABUF_V1_FORMAT_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION 3

#define ZWP_LINUX_DMABUF_V1_DESTROY_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_V1_CREATE_PARAMS_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_V1_GET_DEFAULT_FEEDBACK_SINCE_VERSION 4
#define ZWP_LINUX_DMABUF_V1_GET_SURFACE_FEEDBACK_SINCE_VERSION 4

static inline void
zwp_linux_dmabuf_v1_set_user_data(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_linux_dmabuf_v1, user_data);
}

static inline void *
zwp_linux_dmabuf_v1_get_user_data(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_linux_dmabuf_v1);
}

static inline uint32_t
zwp_linux_dmabuf_v1_get_version(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_v1);
}

static inline void
zwp_linux_dmabuf_v1_destroy(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_dmabuf_v1,
			 ZWP_LINUX_DMABUF_V1_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_v1), WL_MARSHAL_FLAG_DESTROY);
}

static inline struct zwp_linux_buffer_params_v1 *
zwp_linux_dmabuf_v1_create_params(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1)
{
	struct wl_proxy *params_id;

	params_id = wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_dmabuf_v1,
			 ZWP_LINUX_DMABUF_V1_CREATE_PARAMS, &zwp_linux_buffer_params_v1_interface, wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_v1), 0, NULL);

	return (struct zwp_linux_buffer_params_v1 *) params_id;
}

static inline struct zwp_linux_dmabuf_feedback_v1 *
zwp_linux_dmabuf_v1_get_default_feedback(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_dmabuf_v1,
			 ZWP_LINUX_DMABUF_V1_GET_DEFAULT_FEEDBACK, &zwp_linux_dmabuf_feedback_v1_interface, wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_v1), 0, NULL);

	return (struct zwp_linux_dmabuf_feedback_v1 *) id;
}

static inline struct zwp_linux_dmabuf_feedback_v1 *
zwp_linux_dmabuf_v1_get_surface_feedback(struct zwp_linux_dmabuf_v1 *zwp_linux_dmabuf_v1, struct wl_surface *surface)
{
	struct wl_proxy *id;

	id = wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_dmabuf_v1,
			 ZWP_LINUX_DMABUF_V1_GET_SURFACE_FEEDBACK, &zwp_linux_dmabuf_feedback_v1_interface, wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_v1), 0, NULL, surface);

	return (struct zwp_linux_dmabuf_feedback_v1 *) id;
}

#ifndef ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ENUM
#define ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ENUM
enum zwp_linux_buffer_params_v1_error {
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED = 0,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX = 1,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET = 2,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE = 3,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT = 4,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DIMENSIONS = 5,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS = 6,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER = 7,
	ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DEV_T_SIZE = 8,
};
#endif

#ifndef ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_ENUM
#define ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_ENUM
enum zwp_linux_buffer_params_v1_flags {
	ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_Y_INVERT = 1,
	ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_INTERLACED = 2,
	ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_BOTTOM_FIRST = 4,
};
#endif

struct zwp_linux_buffer_params_v1_listener {
	void (*created)(void *data,
			struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1,
			struct wl_buffer *buffer);
	void (*failed)(void *data,
		       struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1);
};

static inline int
zwp_linux_buffer_params_v1_add_listener(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1,
					const struct zwp_linux_buffer_params_v1_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_linux_buffer_params_v1,
				     (void (**)(void)) listener, data);
}

#define ZWP_LINUX_BUFFER_PARAMS_V1_DESTROY 0
#define ZWP_LINUX_BUFFER_PARAMS_V1_ADD 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATE 2
#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATE_IMMED 3
#define ZWP_LINUX_BUFFER_PARAMS_V1_SET_SAMPLING_DEVICE 4

#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATED_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_FAILED_SINCE_VERSION 1

#define ZWP_LINUX_BUFFER_PARAMS_V1_DESTROY_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_ADD_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATE_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATE_IMMED_SINCE_VERSION 2
#define ZWP_LINUX_BUFFER_PARAMS_V1_SET_SAMPLING_DEVICE_SINCE_VERSION 6

static inline void
zwp_linux_buffer_params_v1_set_user_data(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_linux_buffer_params_v1, user_data);
}

static inline void *
zwp_linux_buffer_params_v1_get_user_data(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_linux_buffer_params_v1);
}

static inline uint32_t
zwp_linux_buffer_params_v1_get_version(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_linux_buffer_params_v1);
}

static inline void
zwp_linux_buffer_params_v1_destroy(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_buffer_params_v1,
			 ZWP_LINUX_BUFFER_PARAMS_V1_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_linux_buffer_params_v1), WL_MARSHAL_FLAG_DESTROY);
}

static inline void
zwp_linux_buffer_params_v1_add(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1, int32_t fd, uint32_t plane_idx, uint32_t offset, uint32_t stride, uint32_t modifier_hi, uint32_t modifier_lo)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_buffer_params_v1,
			 ZWP_LINUX_BUFFER_PARAMS_V1_ADD, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_linux_buffer_params_v1), 0, fd, plane_idx, offset, stride, modifier_hi, modifier_lo);
}

static inline void
zwp_linux_buffer_params_v1_create(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1, int32_t width, int32_t height, uint32_t format, uint32_t flags)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_buffer_params_v1,
			 ZWP_LINUX_BUFFER_PARAMS_V1_CREATE, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_linux_buffer_params_v1), 0, width, height, format, flags);
}

static inline struct wl_buffer *
zwp_linux_buffer_params_v1_create_immed(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1, int32_t width, int32_t height, uint32_t format, uint32_t flags)
{
	struct wl_proxy *buffer_id;

	buffer_id = wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_buffer_params_v1,
			 ZWP_LINUX_BUFFER_PARAMS_V1_CREATE_IMMED, &wl_buffer_interface, wl_proxy_get_version((struct wl_proxy *) zwp_linux_buffer_params_v1), 0, NULL, width, height, format, flags);

	return (struct wl_buffer *) buffer_id;
}

static inline void
zwp_linux_buffer_params_v1_set_sampling_device(struct zwp_linux_buffer_params_v1 *zwp_linux_buffer_params_v1, struct wl_array *device)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_buffer_params_v1,
			 ZWP_LINUX_BUFFER_PARAMS_V1_SET_SAMPLING_DEVICE, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_linux_buffer_params_v1), 0, device);
}

#ifndef ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_ENUM
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_ENUM
enum zwp_linux_dmabuf_feedback_v1_tranche_flags {
	ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SCANOUT = 1,
	ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SAMPLING = 2,
};
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SCANOUT_SINCE_VERSION 4
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SAMPLING_SINCE_VERSION 6
#endif

struct zwp_linux_dmabuf_feedback_v1_listener {
	void (*done)(void *data,
		     struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1);
	void (*format_table)(void *data,
			     struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1,
			     int32_t fd,
			     uint32_t size);
	void (*main_device)(void *data,
			    struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1,
			    struct wl_array *device);
	void (*tranche_done)(void *data,
			     struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1);
	void (*tranche_target_device)(void *data,
				      struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1,
				      struct wl_array *device);
	void (*tranche_formats)(void *data,
				struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1,
				struct wl_array *indices);
	void (*tranche_flags)(void *data,
			      struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1,
			      uint32_t flags);
};

static inline int
zwp_linux_dmabuf_feedback_v1_add_listener(struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1,
					  const struct zwp_linux_dmabuf_feedback_v1_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_linux_dmabuf_feedback_v1,
				     (void (**)(void)) listener, data);
}

#define ZWP_LINUX_DMABUF_FEEDBACK_V1_DESTROY 0

#define ZWP_LINUX_DMABUF_FEEDBACK_V1_DONE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_FORMAT_TABLE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_MAIN_DEVICE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_DONE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_TARGET_DEVICE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FORMATS_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SINCE_VERSION 1

#define ZWP_LINUX_DMABUF_FEEDBACK_V1_DESTROY_SINCE_VERSION 1

static inline void
zwp_linux_dmabuf_feedback_v1_set_user_data(struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_linux_dmabuf_feedback_v1, user_data);
}

static inline void *
zwp_linux_dmabuf_feedback_v1_get_user_data(struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_linux_dmabuf_feedback_v1);
}

static inline uint32_t
zwp_linux_dmabuf_feedback_v1_get_version(struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_feedback_v1);
}

static inline void
zwp_linux_dmabuf_feedback_v1_destroy(struct zwp_linux_dmabuf_feedback_v1 *zwp_linux_dmabuf_feedback_v1)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_linux_dmabuf_feedback_v1,
			 ZWP_LINUX_DMABUF_FEEDBACK_V1_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_linux_dmabuf_feedback_v1), WL_MARSHAL_FLAG_DESTROY);
}

#ifdef  __cplusplus
}
#endif

#endif
