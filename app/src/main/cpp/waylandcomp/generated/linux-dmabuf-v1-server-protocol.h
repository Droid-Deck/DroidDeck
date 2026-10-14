
#ifndef LINUX_DMABUF_V1_SERVER_PROTOCOL_H
#define LINUX_DMABUF_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

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

struct zwp_linux_dmabuf_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*create_params)(struct wl_client *client,
			      struct wl_resource *resource,
			      uint32_t params_id);
	void (*get_default_feedback)(struct wl_client *client,
				     struct wl_resource *resource,
				     uint32_t id);
	void (*get_surface_feedback)(struct wl_client *client,
				     struct wl_resource *resource,
				     uint32_t id,
				     struct wl_resource *surface);
};

#define ZWP_LINUX_DMABUF_V1_FORMAT 0
#define ZWP_LINUX_DMABUF_V1_MODIFIER 1

#define ZWP_LINUX_DMABUF_V1_FORMAT_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION 3

#define ZWP_LINUX_DMABUF_V1_DESTROY_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_V1_CREATE_PARAMS_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_V1_GET_DEFAULT_FEEDBACK_SINCE_VERSION 4
#define ZWP_LINUX_DMABUF_V1_GET_SURFACE_FEEDBACK_SINCE_VERSION 4

static inline void
zwp_linux_dmabuf_v1_send_format(struct wl_resource *resource_, uint32_t format)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_V1_FORMAT, format);
}

static inline void
zwp_linux_dmabuf_v1_send_modifier(struct wl_resource *resource_, uint32_t format, uint32_t modifier_hi, uint32_t modifier_lo)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_V1_MODIFIER, format, modifier_hi, modifier_lo);
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

#ifndef ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ENUM_IS_VALID
#define ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ENUM_IS_VALID
static inline bool
zwp_linux_buffer_params_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DIMENSIONS:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER:
		return version >= 1;
	case ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DEV_T_SIZE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_ENUM
#define ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_ENUM
enum zwp_linux_buffer_params_v1_flags {
	ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_Y_INVERT = 1,
	ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_INTERLACED = 2,
	ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_BOTTOM_FIRST = 4,
};
#endif

#ifndef ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_ENUM_IS_VALID
#define ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_ENUM_IS_VALID
static inline bool
zwp_linux_buffer_params_v1_flags_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_Y_INVERT;
	if (version >= 1)
		valid |= ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_INTERLACED;
	if (version >= 1)
		valid |= ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_BOTTOM_FIRST;
	return (value & ~valid) == 0;
}
#endif

struct zwp_linux_buffer_params_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*add)(struct wl_client *client,
		    struct wl_resource *resource,
		    int32_t fd,
		    uint32_t plane_idx,
		    uint32_t offset,
		    uint32_t stride,
		    uint32_t modifier_hi,
		    uint32_t modifier_lo);
	void (*create)(struct wl_client *client,
		       struct wl_resource *resource,
		       int32_t width,
		       int32_t height,
		       uint32_t format,
		       uint32_t flags);
	void (*create_immed)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t buffer_id,
			     int32_t width,
			     int32_t height,
			     uint32_t format,
			     uint32_t flags);
	void (*set_sampling_device)(struct wl_client *client,
				    struct wl_resource *resource,
				    struct wl_array *device);
};

#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATED 0
#define ZWP_LINUX_BUFFER_PARAMS_V1_FAILED 1

#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATED_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_FAILED_SINCE_VERSION 1

#define ZWP_LINUX_BUFFER_PARAMS_V1_DESTROY_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_ADD_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATE_SINCE_VERSION 1
#define ZWP_LINUX_BUFFER_PARAMS_V1_CREATE_IMMED_SINCE_VERSION 2
#define ZWP_LINUX_BUFFER_PARAMS_V1_SET_SAMPLING_DEVICE_SINCE_VERSION 6

static inline void
zwp_linux_buffer_params_v1_send_created(struct wl_resource *resource_, struct wl_resource *buffer)
{
	wl_resource_post_event(resource_, ZWP_LINUX_BUFFER_PARAMS_V1_CREATED, buffer);
}

static inline void
zwp_linux_buffer_params_v1_send_failed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_LINUX_BUFFER_PARAMS_V1_FAILED);
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

#ifndef ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_ENUM_IS_VALID
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_ENUM_IS_VALID
static inline bool
zwp_linux_dmabuf_feedback_v1_tranche_flags_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 4)
		valid |= ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SCANOUT;
	if (version >= 6)
		valid |= ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SAMPLING;
	return (value & ~valid) == 0;
}
#endif

struct zwp_linux_dmabuf_feedback_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_LINUX_DMABUF_FEEDBACK_V1_DONE 0
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_FORMAT_TABLE 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_MAIN_DEVICE 2
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_DONE 3
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_TARGET_DEVICE 4
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FORMATS 5
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS 6

#define ZWP_LINUX_DMABUF_FEEDBACK_V1_DONE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_FORMAT_TABLE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_MAIN_DEVICE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_DONE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_TARGET_DEVICE_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FORMATS_SINCE_VERSION 1
#define ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS_SINCE_VERSION 1

#define ZWP_LINUX_DMABUF_FEEDBACK_V1_DESTROY_SINCE_VERSION 1

static inline void
zwp_linux_dmabuf_feedback_v1_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_DONE);
}

static inline void
zwp_linux_dmabuf_feedback_v1_send_format_table(struct wl_resource *resource_, int32_t fd, uint32_t size)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_FORMAT_TABLE, fd, size);
}

static inline void
zwp_linux_dmabuf_feedback_v1_send_main_device(struct wl_resource *resource_, struct wl_array *device)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_MAIN_DEVICE, device);
}

static inline void
zwp_linux_dmabuf_feedback_v1_send_tranche_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_DONE);
}

static inline void
zwp_linux_dmabuf_feedback_v1_send_tranche_target_device(struct wl_resource *resource_, struct wl_array *device)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_TARGET_DEVICE, device);
}

static inline void
zwp_linux_dmabuf_feedback_v1_send_tranche_formats(struct wl_resource *resource_, struct wl_array *indices)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FORMATS, indices);
}

static inline void
zwp_linux_dmabuf_feedback_v1_send_tranche_flags(struct wl_resource *resource_, uint32_t flags)
{
	wl_resource_post_event(resource_, ZWP_LINUX_DMABUF_FEEDBACK_V1_TRANCHE_FLAGS, flags);
}

#ifdef  __cplusplus
}
#endif

#endif
