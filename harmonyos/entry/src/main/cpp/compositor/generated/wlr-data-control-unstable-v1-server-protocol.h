
#ifndef WLR_DATA_CONTROL_UNSTABLE_V1_SERVER_PROTOCOL_H
#define WLR_DATA_CONTROL_UNSTABLE_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_wlr_data_control_unstable_v1 Copyright
 *
 * Copyright © 2018 Simon Ser
 * Copyright © 2019 Ivan Molodetskikh
 *
 * Permission to use, copy, modify, distribute, and sell this
 * software and its documentation for any purpose is hereby granted
 * without fee, provided that the above copyright notice appear in
 * all copies and that both that copyright notice and this permission
 * notice appear in supporting documentation, and that the name of
 * the copyright holders not be used in advertising or publicity
 * pertaining to distribution of the software without specific,
 * written prior permission.  The copyright holders make no
 * representations about the suitability of this software for any
 * purpose.  It is provided "as is" without express or implied
 * warranty.
 *
 * THE COPYRIGHT HOLDERS DISCLAIM ALL WARRANTIES WITH REGARD TO THIS
 * SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS, IN NO EVENT SHALL THE COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN
 * AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
 * ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */




























struct wl_seat;
struct zwlr_data_control_device_v1;
struct zwlr_data_control_manager_v1;
struct zwlr_data_control_offer_v1;
struct zwlr_data_control_source_v1;

#ifndef ZWLR_DATA_CONTROL_MANAGER_V1_INTERFACE
#define ZWLR_DATA_CONTROL_MANAGER_V1_INTERFACE
extern const struct wl_interface zwlr_data_control_manager_v1_interface;
#endif
#ifndef ZWLR_DATA_CONTROL_DEVICE_V1_INTERFACE
#define ZWLR_DATA_CONTROL_DEVICE_V1_INTERFACE
extern const struct wl_interface zwlr_data_control_device_v1_interface;
#endif
#ifndef ZWLR_DATA_CONTROL_SOURCE_V1_INTERFACE
#define ZWLR_DATA_CONTROL_SOURCE_V1_INTERFACE
extern const struct wl_interface zwlr_data_control_source_v1_interface;
#endif
#ifndef ZWLR_DATA_CONTROL_OFFER_V1_INTERFACE
#define ZWLR_DATA_CONTROL_OFFER_V1_INTERFACE
extern const struct wl_interface zwlr_data_control_offer_v1_interface;
#endif

struct zwlr_data_control_manager_v1_interface {
	void (*create_data_source)(struct wl_client *client,
				   struct wl_resource *resource,
				   uint32_t id);
	void (*get_data_device)(struct wl_client *client,
				struct wl_resource *resource,
				uint32_t id,
				struct wl_resource *seat);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};


#define ZWLR_DATA_CONTROL_MANAGER_V1_CREATE_DATA_SOURCE_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_MANAGER_V1_GET_DATA_DEVICE_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_MANAGER_V1_DESTROY_SINCE_VERSION 1

#ifndef ZWLR_DATA_CONTROL_DEVICE_V1_ERROR_ENUM
#define ZWLR_DATA_CONTROL_DEVICE_V1_ERROR_ENUM
enum zwlr_data_control_device_v1_error {
	ZWLR_DATA_CONTROL_DEVICE_V1_ERROR_USED_SOURCE = 1,
};
#endif

#ifndef ZWLR_DATA_CONTROL_DEVICE_V1_ERROR_ENUM_IS_VALID
#define ZWLR_DATA_CONTROL_DEVICE_V1_ERROR_ENUM_IS_VALID
static inline bool
zwlr_data_control_device_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWLR_DATA_CONTROL_DEVICE_V1_ERROR_USED_SOURCE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwlr_data_control_device_v1_interface {
	void (*set_selection)(struct wl_client *client,
			      struct wl_resource *resource,
			      struct wl_resource *source);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_primary_selection)(struct wl_client *client,
				      struct wl_resource *resource,
				      struct wl_resource *source);
};

#define ZWLR_DATA_CONTROL_DEVICE_V1_DATA_OFFER 0
#define ZWLR_DATA_CONTROL_DEVICE_V1_SELECTION 1
#define ZWLR_DATA_CONTROL_DEVICE_V1_FINISHED 2
#define ZWLR_DATA_CONTROL_DEVICE_V1_PRIMARY_SELECTION 3

#define ZWLR_DATA_CONTROL_DEVICE_V1_DATA_OFFER_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_DEVICE_V1_SELECTION_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_DEVICE_V1_FINISHED_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_DEVICE_V1_PRIMARY_SELECTION_SINCE_VERSION 2

#define ZWLR_DATA_CONTROL_DEVICE_V1_SET_SELECTION_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_DEVICE_V1_DESTROY_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_DEVICE_V1_SET_PRIMARY_SELECTION_SINCE_VERSION 2

static inline void
zwlr_data_control_device_v1_send_data_offer(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_DEVICE_V1_DATA_OFFER, id);
}

static inline void
zwlr_data_control_device_v1_send_selection(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_DEVICE_V1_SELECTION, id);
}

static inline void
zwlr_data_control_device_v1_send_finished(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_DEVICE_V1_FINISHED);
}

static inline void
zwlr_data_control_device_v1_send_primary_selection(struct wl_resource *resource_, struct wl_resource *id)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_DEVICE_V1_PRIMARY_SELECTION, id);
}

#ifndef ZWLR_DATA_CONTROL_SOURCE_V1_ERROR_ENUM
#define ZWLR_DATA_CONTROL_SOURCE_V1_ERROR_ENUM
enum zwlr_data_control_source_v1_error {
	ZWLR_DATA_CONTROL_SOURCE_V1_ERROR_INVALID_OFFER = 1,
};
#endif

#ifndef ZWLR_DATA_CONTROL_SOURCE_V1_ERROR_ENUM_IS_VALID
#define ZWLR_DATA_CONTROL_SOURCE_V1_ERROR_ENUM_IS_VALID
static inline bool
zwlr_data_control_source_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWLR_DATA_CONTROL_SOURCE_V1_ERROR_INVALID_OFFER:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwlr_data_control_source_v1_interface {
	void (*offer)(struct wl_client *client,
		      struct wl_resource *resource,
		      const char *mime_type);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWLR_DATA_CONTROL_SOURCE_V1_SEND 0
#define ZWLR_DATA_CONTROL_SOURCE_V1_CANCELLED 1

#define ZWLR_DATA_CONTROL_SOURCE_V1_SEND_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_SOURCE_V1_CANCELLED_SINCE_VERSION 1

#define ZWLR_DATA_CONTROL_SOURCE_V1_OFFER_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_SOURCE_V1_DESTROY_SINCE_VERSION 1

static inline void
zwlr_data_control_source_v1_send_send(struct wl_resource *resource_, const char *mime_type, int32_t fd)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_SOURCE_V1_SEND, mime_type, fd);
}

static inline void
zwlr_data_control_source_v1_send_cancelled(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_SOURCE_V1_CANCELLED);
}

struct zwlr_data_control_offer_v1_interface {
	void (*receive)(struct wl_client *client,
			struct wl_resource *resource,
			const char *mime_type,
			int32_t fd);
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
};

#define ZWLR_DATA_CONTROL_OFFER_V1_OFFER 0

#define ZWLR_DATA_CONTROL_OFFER_V1_OFFER_SINCE_VERSION 1

#define ZWLR_DATA_CONTROL_OFFER_V1_RECEIVE_SINCE_VERSION 1
#define ZWLR_DATA_CONTROL_OFFER_V1_DESTROY_SINCE_VERSION 1

static inline void
zwlr_data_control_offer_v1_send_offer(struct wl_resource *resource_, const char *mime_type)
{
	wl_resource_post_event(resource_, ZWLR_DATA_CONTROL_OFFER_V1_OFFER, mime_type);
}

#ifdef  __cplusplus
}
#endif

#endif
