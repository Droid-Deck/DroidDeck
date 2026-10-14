/*
 * Copyright © 2008 Kristian Høgsberg
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
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

#ifndef WAYLAND_CLIENT_CORE_H
#define WAYLAND_CLIENT_CORE_H

#include <stdint.h>
#include "wayland-util.h"
#include "wayland-version.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct timespec;

struct wl_proxy;

struct wl_display;

struct wl_event_queue;

#define WL_MARSHAL_FLAG_DESTROY (1 << 0)

void
wl_event_queue_destroy(struct wl_event_queue *queue);

struct wl_proxy *
wl_proxy_marshal_flags(struct wl_proxy *proxy, uint32_t opcode,
		       const struct wl_interface *interface,
		       uint32_t version,
		       uint32_t flags, ...);

struct wl_proxy *
wl_proxy_marshal_array_flags(struct wl_proxy *proxy, uint32_t opcode,
			     const struct wl_interface *interface,
			     uint32_t version,
			     uint32_t flags,
			     union wl_argument *args);

void
wl_proxy_marshal(struct wl_proxy *p, uint32_t opcode, ...);

void
wl_proxy_marshal_array(struct wl_proxy *p, uint32_t opcode,
		       union wl_argument *args);

struct wl_proxy *
wl_proxy_create(struct wl_proxy *factory,
		const struct wl_interface *interface);

void *
wl_proxy_create_wrapper(void *proxy);

void
wl_proxy_wrapper_destroy(void *proxy_wrapper);

struct wl_proxy *
wl_proxy_marshal_constructor(struct wl_proxy *proxy,
			     uint32_t opcode,
			     const struct wl_interface *interface,
			     ...);

struct wl_proxy *
wl_proxy_marshal_constructor_versioned(struct wl_proxy *proxy,
				       uint32_t opcode,
				       const struct wl_interface *interface,
				       uint32_t version,
				       ...);

struct wl_proxy *
wl_proxy_marshal_array_constructor(struct wl_proxy *proxy,
				   uint32_t opcode, union wl_argument *args,
				   const struct wl_interface *interface);

struct wl_proxy *
wl_proxy_marshal_array_constructor_versioned(struct wl_proxy *proxy,
					     uint32_t opcode,
					     union wl_argument *args,
					     const struct wl_interface *interface,
					     uint32_t version);

void
wl_proxy_destroy(struct wl_proxy *proxy);

int
wl_proxy_add_listener(struct wl_proxy *proxy,
		      void (**implementation)(void), void *data);

const void *
wl_proxy_get_listener(struct wl_proxy *proxy);

int
wl_proxy_add_dispatcher(struct wl_proxy *proxy,
			wl_dispatcher_func_t dispatcher_func,
			const void * dispatcher_data, void *data);

void
wl_proxy_set_user_data(struct wl_proxy *proxy, void *user_data);

void *
wl_proxy_get_user_data(struct wl_proxy *proxy);

uint32_t
wl_proxy_get_version(struct wl_proxy *proxy);

uint32_t
wl_proxy_get_id(struct wl_proxy *proxy);

void
wl_proxy_set_tag(struct wl_proxy *proxy,
		 const char * const *tag);

const char * const *
wl_proxy_get_tag(struct wl_proxy *proxy);

const char *
wl_proxy_get_class(struct wl_proxy *proxy);

const struct wl_interface *
wl_proxy_get_interface(struct wl_proxy *proxy);

struct wl_display *
wl_proxy_get_display(struct wl_proxy *proxy);

void
wl_proxy_set_queue(struct wl_proxy *proxy, struct wl_event_queue *queue);

struct wl_event_queue *
wl_proxy_get_queue(const struct wl_proxy *proxy);

const char *
wl_event_queue_get_name(const struct wl_event_queue *queue);

struct wl_display *
wl_display_connect(const char *name);

struct wl_display *
wl_display_connect_to_fd(int fd);

void
wl_display_disconnect(struct wl_display *display);

int
wl_display_get_fd(struct wl_display *display);

int
wl_display_dispatch(struct wl_display *display);

int
wl_display_dispatch_queue(struct wl_display *display,
			  struct wl_event_queue *queue);

int
wl_display_dispatch_timeout(struct wl_display *display,
			    const struct timespec *timeout);

int
wl_display_dispatch_queue_timeout(struct wl_display *display,
				  struct wl_event_queue *queue,
				  const struct timespec *timeout);

int
wl_display_dispatch_queue_pending(struct wl_display *display,
				  struct wl_event_queue *queue);

int
wl_display_dispatch_queue_pending_single(struct wl_display *display,
					 struct wl_event_queue *queue);

int
wl_display_dispatch_pending(struct wl_display *display);

int
wl_display_dispatch_pending_single(struct wl_display *display);

int
wl_display_get_error(struct wl_display *display);

uint32_t
wl_display_get_protocol_error(struct wl_display *display,
			      const struct wl_interface **interface,
			      uint32_t *id);

int
wl_display_flush(struct wl_display *display);

int
wl_display_roundtrip_queue(struct wl_display *display,
			   struct wl_event_queue *queue);

int
wl_display_roundtrip(struct wl_display *display);

struct wl_event_queue *
wl_display_create_queue(struct wl_display *display);

struct wl_event_queue *
wl_display_create_queue_with_name(struct wl_display *display,
				  const char *name);

int
wl_display_prepare_read_queue(struct wl_display *display,
			      struct wl_event_queue *queue);

int
wl_display_prepare_read(struct wl_display *display);

void
wl_display_cancel_read(struct wl_display *display);

int
wl_display_read_events(struct wl_display *display);

void
wl_log_set_handler_client(wl_log_func_t handler);

void
wl_display_set_max_buffer_size(struct wl_display *display,
                               size_t max_buffer_size);

#ifdef  __cplusplus
}
#endif

#endif
