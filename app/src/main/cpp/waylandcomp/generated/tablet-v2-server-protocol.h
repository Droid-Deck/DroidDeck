
#ifndef TABLET_V2_SERVER_PROTOCOL_H
#define TABLET_V2_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_tablet_v2 Copyright
 *
 * Copyright 2014 © Stephen "Lyude" Chandler Paul
 * Copyright 2015-2024 © Red Hat, Inc.
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






























































































struct wl_seat;
struct wl_surface;
struct zwp_tablet_manager_v2;
struct zwp_tablet_pad_dial_v2;
struct zwp_tablet_pad_group_v2;
struct zwp_tablet_pad_ring_v2;
struct zwp_tablet_pad_strip_v2;
struct zwp_tablet_pad_v2;
struct zwp_tablet_seat_v2;
struct zwp_tablet_tool_v2;
struct zwp_tablet_v2;

#ifndef ZWP_TABLET_MANAGER_V2_INTERFACE
#define ZWP_TABLET_MANAGER_V2_INTERFACE
extern const struct wl_interface zwp_tablet_manager_v2_interface;
#endif
#ifndef ZWP_TABLET_SEAT_V2_INTERFACE
#define ZWP_TABLET_SEAT_V2_INTERFACE
extern const struct wl_interface zwp_tablet_seat_v2_interface;
#endif
#ifndef ZWP_TABLET_TOOL_V2_INTERFACE
#define ZWP_TABLET_TOOL_V2_INTERFACE
extern const struct wl_interface zwp_tablet_tool_v2_interface;
#endif
#ifndef ZWP_TABLET_V2_INTERFACE
#define ZWP_TABLET_V2_INTERFACE
extern const struct wl_interface zwp_tablet_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_RING_V2_INTERFACE
#define ZWP_TABLET_PAD_RING_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_ring_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_STRIP_V2_INTERFACE
#define ZWP_TABLET_PAD_STRIP_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_strip_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_GROUP_V2_INTERFACE
#define ZWP_TABLET_PAD_GROUP_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_group_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_V2_INTERFACE
#define ZWP_TABLET_PAD_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_DIAL_V2_INTERFACE
#define ZWP_TABLET_PAD_DIAL_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_dial_v2_interface;
#endif

struct zwp_tablet_manager_v2_interface {
	void (*get_tablet_seat)(struct wl_client *client,
				struct wl_resource *resource,
				uint32_t tablet_seat,
				struct wl_resource *seat);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};


#define ZWP_TABLET_MANAGER_V2_GET_TABLET_SEAT_SINCE_VERSION 1
#define ZWP_TABLET_MANAGER_V2_DESTROY_SINCE_VERSION 1

struct zwp_tablet_seat_v2_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_SEAT_V2_TABLET_ADDED 0
#define ZWP_TABLET_SEAT_V2_TOOL_ADDED 1
#define ZWP_TABLET_SEAT_V2_PAD_ADDED 2

#define ZWP_TABLET_SEAT_V2_TABLET_ADDED_SINCE_VERSION 1
#define ZWP_TABLET_SEAT_V2_TOOL_ADDED_SINCE_VERSION 1
#define ZWP_TABLET_SEAT_V2_PAD_ADDED_SINCE_VERSION 1

#define ZWP_TABLET_SEAT_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_seat_v2_send_tablet_added(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, ZWP_TABLET_SEAT_V2_TABLET_ADDED, id);
}

static inline void
zwp_tablet_seat_v2_send_tool_added(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, ZWP_TABLET_SEAT_V2_TOOL_ADDED, id);
}

static inline void
zwp_tablet_seat_v2_send_pad_added(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, ZWP_TABLET_SEAT_V2_PAD_ADDED, id);
}

#ifndef ZWP_TABLET_TOOL_V2_TYPE_ENUM
#define ZWP_TABLET_TOOL_V2_TYPE_ENUM
enum zwp_tablet_tool_v2_type {
	ZWP_TABLET_TOOL_V2_TYPE_PEN = 0x140,
	ZWP_TABLET_TOOL_V2_TYPE_ERASER = 0x141,
	ZWP_TABLET_TOOL_V2_TYPE_BRUSH = 0x142,
	ZWP_TABLET_TOOL_V2_TYPE_PENCIL = 0x143,
	ZWP_TABLET_TOOL_V2_TYPE_AIRBRUSH = 0x144,
	ZWP_TABLET_TOOL_V2_TYPE_FINGER = 0x145,
	ZWP_TABLET_TOOL_V2_TYPE_MOUSE = 0x146,
	ZWP_TABLET_TOOL_V2_TYPE_LENS = 0x147,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_TYPE_ENUM_IS_VALID
#define ZWP_TABLET_TOOL_V2_TYPE_ENUM_IS_VALID
static inline bool
zwp_tablet_tool_v2_type_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_TOOL_V2_TYPE_PEN:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_ERASER:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_BRUSH:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_PENCIL:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_AIRBRUSH:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_FINGER:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_MOUSE:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_TYPE_LENS:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TABLET_TOOL_V2_CAPABILITY_ENUM
#define ZWP_TABLET_TOOL_V2_CAPABILITY_ENUM
enum zwp_tablet_tool_v2_capability {
	ZWP_TABLET_TOOL_V2_CAPABILITY_TILT = 1,
	ZWP_TABLET_TOOL_V2_CAPABILITY_PRESSURE = 2,
	ZWP_TABLET_TOOL_V2_CAPABILITY_DISTANCE = 3,
	ZWP_TABLET_TOOL_V2_CAPABILITY_ROTATION = 4,
	ZWP_TABLET_TOOL_V2_CAPABILITY_SLIDER = 5,
	ZWP_TABLET_TOOL_V2_CAPABILITY_WHEEL = 6,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_CAPABILITY_ENUM_IS_VALID
#define ZWP_TABLET_TOOL_V2_CAPABILITY_ENUM_IS_VALID
static inline bool
zwp_tablet_tool_v2_capability_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_TOOL_V2_CAPABILITY_TILT:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_CAPABILITY_PRESSURE:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_CAPABILITY_DISTANCE:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_CAPABILITY_ROTATION:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_CAPABILITY_SLIDER:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_CAPABILITY_WHEEL:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TABLET_TOOL_V2_BUTTON_STATE_ENUM
#define ZWP_TABLET_TOOL_V2_BUTTON_STATE_ENUM
enum zwp_tablet_tool_v2_button_state {
	ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED = 0,
	ZWP_TABLET_TOOL_V2_BUTTON_STATE_PRESSED = 1,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_BUTTON_STATE_ENUM_IS_VALID
#define ZWP_TABLET_TOOL_V2_BUTTON_STATE_ENUM_IS_VALID
static inline bool
zwp_tablet_tool_v2_button_state_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED:
		return version >= 1;
	case ZWP_TABLET_TOOL_V2_BUTTON_STATE_PRESSED:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TABLET_TOOL_V2_ERROR_ENUM
#define ZWP_TABLET_TOOL_V2_ERROR_ENUM
enum zwp_tablet_tool_v2_error {
	ZWP_TABLET_TOOL_V2_ERROR_ROLE = 0,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_ERROR_ENUM_IS_VALID
#define ZWP_TABLET_TOOL_V2_ERROR_ENUM_IS_VALID
static inline bool
zwp_tablet_tool_v2_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_TOOL_V2_ERROR_ROLE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwp_tablet_tool_v2_interface {
	void (*set_cursor)(struct wl_client *client,
			   struct wl_resource *resource,
			   uint32_t serial,
			   struct wl_resource *surface,
			   int32_t hotspot_x,
			   int32_t hotspot_y);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_TOOL_V2_TYPE 0
#define ZWP_TABLET_TOOL_V2_HARDWARE_SERIAL 1
#define ZWP_TABLET_TOOL_V2_HARDWARE_ID_WACOM 2
#define ZWP_TABLET_TOOL_V2_CAPABILITY 3
#define ZWP_TABLET_TOOL_V2_DONE 4
#define ZWP_TABLET_TOOL_V2_REMOVED 5
#define ZWP_TABLET_TOOL_V2_PROXIMITY_IN 6
#define ZWP_TABLET_TOOL_V2_PROXIMITY_OUT 7
#define ZWP_TABLET_TOOL_V2_DOWN 8
#define ZWP_TABLET_TOOL_V2_UP 9
#define ZWP_TABLET_TOOL_V2_MOTION 10
#define ZWP_TABLET_TOOL_V2_PRESSURE 11
#define ZWP_TABLET_TOOL_V2_DISTANCE 12
#define ZWP_TABLET_TOOL_V2_TILT 13
#define ZWP_TABLET_TOOL_V2_ROTATION 14
#define ZWP_TABLET_TOOL_V2_SLIDER 15
#define ZWP_TABLET_TOOL_V2_WHEEL 16
#define ZWP_TABLET_TOOL_V2_BUTTON 17
#define ZWP_TABLET_TOOL_V2_FRAME 18

#define ZWP_TABLET_TOOL_V2_TYPE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_HARDWARE_SERIAL_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_HARDWARE_ID_WACOM_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_CAPABILITY_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_REMOVED_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_PROXIMITY_IN_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_PROXIMITY_OUT_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DOWN_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_UP_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_MOTION_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_PRESSURE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DISTANCE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_TILT_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_ROTATION_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_SLIDER_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_WHEEL_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_BUTTON_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_TOOL_V2_SET_CURSOR_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_tool_v2_send_type(struct wl_resource *resource_, uint32_t tool_type)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_TYPE, tool_type);
}

static inline void
zwp_tablet_tool_v2_send_hardware_serial(struct wl_resource *resource_, uint32_t hardware_serial_hi, uint32_t hardware_serial_lo)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_HARDWARE_SERIAL, hardware_serial_hi, hardware_serial_lo);
}

static inline void
zwp_tablet_tool_v2_send_hardware_id_wacom(struct wl_resource *resource_, uint32_t hardware_id_hi, uint32_t hardware_id_lo)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_HARDWARE_ID_WACOM, hardware_id_hi, hardware_id_lo);
}

static inline void
zwp_tablet_tool_v2_send_capability(struct wl_resource *resource_, uint32_t capability)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_CAPABILITY, capability);
}

static inline void
zwp_tablet_tool_v2_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_DONE);
}

static inline void
zwp_tablet_tool_v2_send_removed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_REMOVED);
}

static inline void
zwp_tablet_tool_v2_send_proximity_in(struct wl_resource *resource_, uint32_t serial, struct wl_resource *tablet, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_PROXIMITY_IN, serial, tablet, surface);
}

static inline void
zwp_tablet_tool_v2_send_proximity_out(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_PROXIMITY_OUT);
}

static inline void
zwp_tablet_tool_v2_send_down(struct wl_resource *resource_, uint32_t serial)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_DOWN, serial);
}

static inline void
zwp_tablet_tool_v2_send_up(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_UP);
}

static inline void
zwp_tablet_tool_v2_send_motion(struct wl_resource *resource_, wl_fixed_t x, wl_fixed_t y)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_MOTION, x, y);
}

static inline void
zwp_tablet_tool_v2_send_pressure(struct wl_resource *resource_, uint32_t pressure)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_PRESSURE, pressure);
}

static inline void
zwp_tablet_tool_v2_send_distance(struct wl_resource *resource_, uint32_t distance)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_DISTANCE, distance);
}

static inline void
zwp_tablet_tool_v2_send_tilt(struct wl_resource *resource_, wl_fixed_t tilt_x, wl_fixed_t tilt_y)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_TILT, tilt_x, tilt_y);
}

static inline void
zwp_tablet_tool_v2_send_rotation(struct wl_resource *resource_, wl_fixed_t degrees)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_ROTATION, degrees);
}

static inline void
zwp_tablet_tool_v2_send_slider(struct wl_resource *resource_, int32_t position)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_SLIDER, position);
}

static inline void
zwp_tablet_tool_v2_send_wheel(struct wl_resource *resource_, wl_fixed_t degrees, int32_t clicks)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_WHEEL, degrees, clicks);
}

static inline void
zwp_tablet_tool_v2_send_button(struct wl_resource *resource_, uint32_t serial, uint32_t button, uint32_t state)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_BUTTON, serial, button, state);
}

static inline void
zwp_tablet_tool_v2_send_frame(struct wl_resource *resource_, uint32_t time)
{
	wl_resource_post_event(resource_, ZWP_TABLET_TOOL_V2_FRAME, time);
}

#ifndef ZWP_TABLET_V2_BUSTYPE_ENUM
#define ZWP_TABLET_V2_BUSTYPE_ENUM
enum zwp_tablet_v2_bustype {
	ZWP_TABLET_V2_BUSTYPE_USB = 3,
	ZWP_TABLET_V2_BUSTYPE_BLUETOOTH = 5,
	ZWP_TABLET_V2_BUSTYPE_VIRTUAL = 6,
	ZWP_TABLET_V2_BUSTYPE_SERIAL = 17,
	ZWP_TABLET_V2_BUSTYPE_I2C = 24,
};
#endif

#ifndef ZWP_TABLET_V2_BUSTYPE_ENUM_IS_VALID
#define ZWP_TABLET_V2_BUSTYPE_ENUM_IS_VALID
static inline bool
zwp_tablet_v2_bustype_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_V2_BUSTYPE_USB:
		return version >= 1;
	case ZWP_TABLET_V2_BUSTYPE_BLUETOOTH:
		return version >= 1;
	case ZWP_TABLET_V2_BUSTYPE_VIRTUAL:
		return version >= 1;
	case ZWP_TABLET_V2_BUSTYPE_SERIAL:
		return version >= 1;
	case ZWP_TABLET_V2_BUSTYPE_I2C:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwp_tablet_v2_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_V2_NAME 0
#define ZWP_TABLET_V2_ID 1
#define ZWP_TABLET_V2_PATH 2
#define ZWP_TABLET_V2_DONE 3
#define ZWP_TABLET_V2_REMOVED 4
#define ZWP_TABLET_V2_BUSTYPE 5

#define ZWP_TABLET_V2_NAME_SINCE_VERSION 1
#define ZWP_TABLET_V2_ID_SINCE_VERSION 1
#define ZWP_TABLET_V2_PATH_SINCE_VERSION 1
#define ZWP_TABLET_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_V2_REMOVED_SINCE_VERSION 1
#define ZWP_TABLET_V2_BUSTYPE_SINCE_VERSION 2

#define ZWP_TABLET_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_v2_send_name(struct wl_resource *resource_, const char *name)
{
	wl_resource_post_event(resource_, ZWP_TABLET_V2_NAME, name);
}

static inline void
zwp_tablet_v2_send_id(struct wl_resource *resource_, uint32_t vid, uint32_t pid)
{
	wl_resource_post_event(resource_, ZWP_TABLET_V2_ID, vid, pid);
}

static inline void
zwp_tablet_v2_send_path(struct wl_resource *resource_, const char *path)
{
	wl_resource_post_event(resource_, ZWP_TABLET_V2_PATH, path);
}

static inline void
zwp_tablet_v2_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_V2_DONE);
}

static inline void
zwp_tablet_v2_send_removed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_V2_REMOVED);
}

static inline void
zwp_tablet_v2_send_bustype(struct wl_resource *resource_, uint32_t bustype)
{
	wl_resource_post_event(resource_, ZWP_TABLET_V2_BUSTYPE, bustype);
}

#ifndef ZWP_TABLET_PAD_RING_V2_SOURCE_ENUM
#define ZWP_TABLET_PAD_RING_V2_SOURCE_ENUM
enum zwp_tablet_pad_ring_v2_source {
	ZWP_TABLET_PAD_RING_V2_SOURCE_FINGER = 1,
};
#endif

#ifndef ZWP_TABLET_PAD_RING_V2_SOURCE_ENUM_IS_VALID
#define ZWP_TABLET_PAD_RING_V2_SOURCE_ENUM_IS_VALID
static inline bool
zwp_tablet_pad_ring_v2_source_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_PAD_RING_V2_SOURCE_FINGER:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwp_tablet_pad_ring_v2_interface {
	void (*set_feedback)(struct wl_client *client,
			     struct wl_resource *resource,
			     const char *description,
			     uint32_t serial);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_PAD_RING_V2_SOURCE 0
#define ZWP_TABLET_PAD_RING_V2_ANGLE 1
#define ZWP_TABLET_PAD_RING_V2_STOP 2
#define ZWP_TABLET_PAD_RING_V2_FRAME 3

#define ZWP_TABLET_PAD_RING_V2_SOURCE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_ANGLE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_STOP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_PAD_RING_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_ring_v2_send_source(struct wl_resource *resource_, uint32_t source)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_RING_V2_SOURCE, source);
}

static inline void
zwp_tablet_pad_ring_v2_send_angle(struct wl_resource *resource_, wl_fixed_t degrees)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_RING_V2_ANGLE, degrees);
}

static inline void
zwp_tablet_pad_ring_v2_send_stop(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_RING_V2_STOP);
}

static inline void
zwp_tablet_pad_ring_v2_send_frame(struct wl_resource *resource_, uint32_t time)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_RING_V2_FRAME, time);
}

#ifndef ZWP_TABLET_PAD_STRIP_V2_SOURCE_ENUM
#define ZWP_TABLET_PAD_STRIP_V2_SOURCE_ENUM
enum zwp_tablet_pad_strip_v2_source {
	ZWP_TABLET_PAD_STRIP_V2_SOURCE_FINGER = 1,
};
#endif

#ifndef ZWP_TABLET_PAD_STRIP_V2_SOURCE_ENUM_IS_VALID
#define ZWP_TABLET_PAD_STRIP_V2_SOURCE_ENUM_IS_VALID
static inline bool
zwp_tablet_pad_strip_v2_source_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_PAD_STRIP_V2_SOURCE_FINGER:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwp_tablet_pad_strip_v2_interface {
	void (*set_feedback)(struct wl_client *client,
			     struct wl_resource *resource,
			     const char *description,
			     uint32_t serial);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_PAD_STRIP_V2_SOURCE 0
#define ZWP_TABLET_PAD_STRIP_V2_POSITION 1
#define ZWP_TABLET_PAD_STRIP_V2_STOP 2
#define ZWP_TABLET_PAD_STRIP_V2_FRAME 3

#define ZWP_TABLET_PAD_STRIP_V2_SOURCE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_POSITION_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_STOP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_PAD_STRIP_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_strip_v2_send_source(struct wl_resource *resource_, uint32_t source)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_STRIP_V2_SOURCE, source);
}

static inline void
zwp_tablet_pad_strip_v2_send_position(struct wl_resource *resource_, uint32_t position)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_STRIP_V2_POSITION, position);
}

static inline void
zwp_tablet_pad_strip_v2_send_stop(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_STRIP_V2_STOP);
}

static inline void
zwp_tablet_pad_strip_v2_send_frame(struct wl_resource *resource_, uint32_t time)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_STRIP_V2_FRAME, time);
}

struct zwp_tablet_pad_group_v2_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_PAD_GROUP_V2_BUTTONS 0
#define ZWP_TABLET_PAD_GROUP_V2_RING 1
#define ZWP_TABLET_PAD_GROUP_V2_STRIP 2
#define ZWP_TABLET_PAD_GROUP_V2_MODES 3
#define ZWP_TABLET_PAD_GROUP_V2_DONE 4
#define ZWP_TABLET_PAD_GROUP_V2_MODE_SWITCH 5
#define ZWP_TABLET_PAD_GROUP_V2_DIAL 6

#define ZWP_TABLET_PAD_GROUP_V2_BUTTONS_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_RING_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_STRIP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_MODES_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_MODE_SWITCH_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_DIAL_SINCE_VERSION 2

#define ZWP_TABLET_PAD_GROUP_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_group_v2_send_buttons(struct wl_resource *resource_, struct wl_array *buttons)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_BUTTONS, buttons);
}

static inline void
zwp_tablet_pad_group_v2_send_ring(struct wl_resource *resource_, struct wl_resource *ring)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_RING, ring);
}

static inline void
zwp_tablet_pad_group_v2_send_strip(struct wl_resource *resource_, struct wl_resource *strip)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_STRIP, strip);
}

static inline void
zwp_tablet_pad_group_v2_send_modes(struct wl_resource *resource_, uint32_t modes)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_MODES, modes);
}

static inline void
zwp_tablet_pad_group_v2_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_DONE);
}

static inline void
zwp_tablet_pad_group_v2_send_mode_switch(struct wl_resource *resource_, uint32_t time, uint32_t serial, uint32_t mode)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_MODE_SWITCH, time, serial, mode);
}

static inline void
zwp_tablet_pad_group_v2_send_dial(struct wl_resource *resource_, struct wl_resource *dial)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_GROUP_V2_DIAL, dial);
}

#ifndef ZWP_TABLET_PAD_V2_BUTTON_STATE_ENUM
#define ZWP_TABLET_PAD_V2_BUTTON_STATE_ENUM
enum zwp_tablet_pad_v2_button_state {
	ZWP_TABLET_PAD_V2_BUTTON_STATE_RELEASED = 0,
	ZWP_TABLET_PAD_V2_BUTTON_STATE_PRESSED = 1,
};
#endif

#ifndef ZWP_TABLET_PAD_V2_BUTTON_STATE_ENUM_IS_VALID
#define ZWP_TABLET_PAD_V2_BUTTON_STATE_ENUM_IS_VALID
static inline bool
zwp_tablet_pad_v2_button_state_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TABLET_PAD_V2_BUTTON_STATE_RELEASED:
		return version >= 1;
	case ZWP_TABLET_PAD_V2_BUTTON_STATE_PRESSED:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwp_tablet_pad_v2_interface {
	void (*set_feedback)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t button,
			     const char *description,
			     uint32_t serial);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_PAD_V2_GROUP 0
#define ZWP_TABLET_PAD_V2_PATH 1
#define ZWP_TABLET_PAD_V2_BUTTONS 2
#define ZWP_TABLET_PAD_V2_DONE 3
#define ZWP_TABLET_PAD_V2_BUTTON 4
#define ZWP_TABLET_PAD_V2_ENTER 5
#define ZWP_TABLET_PAD_V2_LEAVE 6
#define ZWP_TABLET_PAD_V2_REMOVED 7

#define ZWP_TABLET_PAD_V2_GROUP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_PATH_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_BUTTONS_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_BUTTON_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_ENTER_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_LEAVE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_REMOVED_SINCE_VERSION 1

#define ZWP_TABLET_PAD_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_v2_send_group(struct wl_resource *resource_, struct wl_resource *pad_group)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_GROUP, pad_group);
}

static inline void
zwp_tablet_pad_v2_send_path(struct wl_resource *resource_, const char *path)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_PATH, path);
}

static inline void
zwp_tablet_pad_v2_send_buttons(struct wl_resource *resource_, uint32_t buttons)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_BUTTONS, buttons);
}

static inline void
zwp_tablet_pad_v2_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_DONE);
}

static inline void
zwp_tablet_pad_v2_send_button(struct wl_resource *resource_, uint32_t time, uint32_t button, uint32_t state)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_BUTTON, time, button, state);
}

static inline void
zwp_tablet_pad_v2_send_enter(struct wl_resource *resource_, uint32_t serial, struct wl_resource *tablet, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_ENTER, serial, tablet, surface);
}

static inline void
zwp_tablet_pad_v2_send_leave(struct wl_resource *resource_, uint32_t serial, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_LEAVE, serial, surface);
}

static inline void
zwp_tablet_pad_v2_send_removed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_V2_REMOVED);
}

struct zwp_tablet_pad_dial_v2_interface {
	void (*set_feedback)(struct wl_client *client,
			     struct wl_resource *resource,
			     const char *description,
			     uint32_t serial);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWP_TABLET_PAD_DIAL_V2_DELTA 0
#define ZWP_TABLET_PAD_DIAL_V2_FRAME 1

#define ZWP_TABLET_PAD_DIAL_V2_DELTA_SINCE_VERSION 1
#define ZWP_TABLET_PAD_DIAL_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_PAD_DIAL_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_DIAL_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_dial_v2_send_delta(struct wl_resource *resource_, int32_t value120)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_DIAL_V2_DELTA, value120);
}

static inline void
zwp_tablet_pad_dial_v2_send_frame(struct wl_resource *resource_, uint32_t time)
{
	wl_resource_post_event(resource_, ZWP_TABLET_PAD_DIAL_V2_FRAME, time);
}

#ifdef  __cplusplus
}
#endif

#endif
