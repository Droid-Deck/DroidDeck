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

#ifndef WAYLAND_SERVER_CORE_H
#define WAYLAND_SERVER_CORE_H

#include <sys/types.h>
#include <stdint.h>
#include <stdbool.h>
#include "wayland-util.h"
#include "wayland-version.h"

#ifdef  __cplusplus
extern "C" {
#endif

enum {
	WL_EVENT_READABLE = 0x01,
	WL_EVENT_WRITABLE = 0x02,
	WL_EVENT_HANGUP   = 0x04,
	WL_EVENT_ERROR    = 0x08
};

typedef int (*wl_event_loop_fd_func_t)(int fd, uint32_t mask, void *data);

typedef int (*wl_event_loop_timer_func_t)(void *data);

typedef int (*wl_event_loop_signal_func_t)(int signal_number, void *data);

typedef void (*wl_event_loop_idle_func_t)(void *data);



struct wl_event_loop *
wl_event_loop_create(void);

void
wl_event_loop_destroy(struct wl_event_loop *loop);

struct wl_event_source *
wl_event_loop_add_fd(struct wl_event_loop *loop,
		     int fd, uint32_t mask,
		     wl_event_loop_fd_func_t func,
		     void *data);

int
wl_event_source_fd_update(struct wl_event_source *source, uint32_t mask);

struct wl_event_source *
wl_event_loop_add_timer(struct wl_event_loop *loop,
			wl_event_loop_timer_func_t func,
			void *data);

struct wl_event_source *
wl_event_loop_add_signal(struct wl_event_loop *loop,
			 int signal_number,
			 wl_event_loop_signal_func_t func,
			 void *data);

int
wl_event_source_timer_update(struct wl_event_source *source,
			     int ms_delay);

int
wl_event_source_remove(struct wl_event_source *source);

void
wl_event_source_check(struct wl_event_source *source);

int
wl_event_loop_dispatch(struct wl_event_loop *loop, int timeout);

void
wl_event_loop_dispatch_idle(struct wl_event_loop *loop);

struct wl_event_source *
wl_event_loop_add_idle(struct wl_event_loop *loop,
		       wl_event_loop_idle_func_t func,
		       void *data);

int
wl_event_loop_get_fd(struct wl_event_loop *loop);

struct wl_listener;

typedef void (*wl_notify_func_t)(struct wl_listener *listener, void *data);

void
wl_event_loop_add_destroy_listener(struct wl_event_loop *loop,
				   struct wl_listener *listener);

struct wl_listener *
wl_event_loop_get_destroy_listener(struct wl_event_loop *loop,
				   wl_notify_func_t notify);

struct wl_display *
wl_display_create(void);

void
wl_display_destroy(struct wl_display *display);

struct wl_event_loop *
wl_display_get_event_loop(struct wl_display *display);

int
wl_display_add_socket(struct wl_display *display, const char *name);

const char *
wl_display_add_socket_auto(struct wl_display *display);

int
wl_display_add_socket_fd(struct wl_display *display, int sock_fd);

int
wl_display_remove_socket_fd(struct wl_display *display, int sock_fd);

void
wl_display_terminate(struct wl_display *display);

void
wl_display_run(struct wl_display *display);

void
wl_display_flush_clients(struct wl_display *display);

void
wl_display_destroy_clients(struct wl_display *display);

void
wl_display_set_default_max_buffer_size(struct wl_display *display,
				       size_t max_buffer_size);

struct wl_client;
struct wl_global;

typedef void (*wl_global_bind_func_t)(struct wl_client *client, void *data,
				      uint32_t version, uint32_t id);

typedef void (*wl_global_withdrawn_func_t)(struct wl_global *global);

uint32_t
wl_display_get_serial(const struct wl_display *display);

uint32_t
wl_display_next_serial(struct wl_display *display);

void
wl_display_add_destroy_listener(struct wl_display *display,
				struct wl_listener *listener);

void
wl_display_add_client_created_listener(struct wl_display *display,
					struct wl_listener *listener);

struct wl_listener *
wl_display_get_destroy_listener(struct wl_display *display,
				wl_notify_func_t notify);

struct wl_global *
wl_global_create(struct wl_display *display,
		 const struct wl_interface *interface,
		 int version,
		 void *data, wl_global_bind_func_t bind);

void
wl_global_remove(struct wl_global *global);

void
wl_global_set_withdrawn_listener(struct wl_global *global,
				 wl_global_withdrawn_func_t func);

void
wl_global_destroy(struct wl_global *global);

typedef bool (*wl_display_global_filter_func_t)(const struct wl_client *client,
						const struct wl_global *global,
						void *data);

void
wl_display_set_global_filter(struct wl_display *display,
			     wl_display_global_filter_func_t filter,
			     void *data);

const struct wl_interface *
wl_global_get_interface(const struct wl_global *global);

uint32_t
wl_global_get_name(const struct wl_global *global,
                   const struct wl_client *client);

uint32_t
wl_global_get_version(const struct wl_global *global);

struct wl_display *
wl_global_get_display(const struct wl_global *global);

void *
wl_global_get_user_data(const struct wl_global *global);

void
wl_global_set_user_data(struct wl_global *global, void *data);

struct wl_client *
wl_client_create(struct wl_display *display, int fd);

struct wl_list *
wl_display_get_client_list(struct wl_display *display);

struct wl_list *
wl_client_get_link(struct wl_client *client);

struct wl_client *
wl_client_from_link(struct wl_list *link);

#define wl_client_for_each(client, list)				\
	for (client = wl_client_from_link((list)->next);	\
	     wl_client_get_link(client) != (list);			\
	     client = wl_client_from_link(wl_client_get_link(client)->next))

void
wl_client_destroy(struct wl_client *client);

void
wl_client_flush(struct wl_client *client);

void
wl_client_get_credentials(const struct wl_client *client,
			  pid_t *pid, uid_t *uid, gid_t *gid);

int
wl_client_get_fd(struct wl_client *client);

void
wl_client_add_destroy_listener(struct wl_client *client,
			       struct wl_listener *listener);

struct wl_listener *
wl_client_get_destroy_listener(struct wl_client *client,
			       wl_notify_func_t notify);

void
wl_client_add_destroy_late_listener(struct wl_client *client,
				    struct wl_listener *listener);

struct wl_listener *
wl_client_get_destroy_late_listener(struct wl_client *client,
				    wl_notify_func_t notify);

struct wl_resource *
wl_client_get_object(struct wl_client *client, uint32_t id);

void
wl_client_post_no_memory(struct wl_client *client);

void
wl_client_post_implementation_error(struct wl_client *client,
                                    const char* msg, ...) WL_PRINTF(2,3);

void
wl_client_add_resource_created_listener(struct wl_client *client,
                                        struct wl_listener *listener);

typedef enum wl_iterator_result (*wl_client_for_each_resource_iterator_func_t)(
						struct wl_resource *resource,
						void *user_data);

void
wl_client_for_each_resource(struct wl_client *client,
                            wl_client_for_each_resource_iterator_func_t iterator,
                            void *user_data);

typedef void (*wl_user_data_destroy_func_t)(void *data);

void
wl_client_set_user_data(struct wl_client *client,
			void *data,
			wl_user_data_destroy_func_t dtor);

void *
wl_client_get_user_data(struct wl_client *client);

void
wl_client_set_max_buffer_size(struct wl_client *client, size_t max_buffer_size);

struct wl_listener {
	struct wl_list link;

	wl_notify_func_t notify;
};

struct wl_signal {
	struct wl_list listener_list;
};

static inline void
wl_signal_init(struct wl_signal *signal)
{
	wl_list_init(&signal->listener_list);
}

static inline void
wl_signal_add(struct wl_signal *signal, struct wl_listener *listener)
{
	wl_list_insert(signal->listener_list.prev, &listener->link);
}

static inline struct wl_listener *
wl_signal_get(struct wl_signal *signal, wl_notify_func_t notify)
{
	struct wl_listener *l;

	wl_list_for_each(l, &signal->listener_list, link)
		if (l->notify == notify)
			return l;

	return NULL;
}

static inline void
wl_signal_emit(struct wl_signal *signal, void *data)
{
	struct wl_listener *l, *next;

	wl_list_for_each_safe(l, next, &signal->listener_list, link)
		l->notify(l, data);
}

void
wl_signal_emit_mutable(struct wl_signal *signal, void *data);

typedef void (*wl_resource_destroy_func_t)(struct wl_resource *resource);

void
wl_resource_post_event(struct wl_resource *resource,
		       uint32_t opcode, ...);

void
wl_resource_post_event_array(struct wl_resource *resource,
			     uint32_t opcode, union wl_argument *args);

void
wl_resource_queue_event(struct wl_resource *resource,
			uint32_t opcode, ...);

void
wl_resource_queue_event_array(struct wl_resource *resource,
			      uint32_t opcode, union wl_argument *args);

void
wl_resource_post_error_vargs(struct wl_resource *resource,
			     uint32_t code, const char *msg, va_list argp);

void
wl_resource_post_error(struct wl_resource *resource,
		       uint32_t code, const char *msg, ...) WL_PRINTF(3, 4);

void
wl_resource_post_no_memory(struct wl_resource *resource);

struct wl_display *
wl_client_get_display(struct wl_client *client);

struct wl_resource *
wl_resource_create(struct wl_client *client,
		   const struct wl_interface *interface,
		   int version, uint32_t id);

void
wl_resource_set_implementation(struct wl_resource *resource,
			       const void *implementation,
			       void *data,
			       wl_resource_destroy_func_t destroy);

void
wl_resource_set_dispatcher(struct wl_resource *resource,
			   wl_dispatcher_func_t dispatcher,
			   const void *implementation,
			   void *data,
			   wl_resource_destroy_func_t destroy);

void
wl_resource_destroy(struct wl_resource *resource);

uint32_t
wl_resource_get_id(const struct wl_resource *resource);

struct wl_list *
wl_resource_get_link(struct wl_resource *resource);

struct wl_resource *
wl_resource_from_link(struct wl_list *resource);

struct wl_resource *
wl_resource_find_for_client(struct wl_list *list, struct wl_client *client);

struct wl_client *
wl_resource_get_client(struct wl_resource *resource);

void
wl_resource_set_user_data(struct wl_resource *resource, void *data);

void *
wl_resource_get_user_data(struct wl_resource *resource);

int
wl_resource_get_version(const struct wl_resource *resource);

void
wl_resource_set_destructor(struct wl_resource *resource,
			   wl_resource_destroy_func_t destroy);

int
wl_resource_instance_of(struct wl_resource *resource,
			const struct wl_interface *interface,
			const void *implementation);

const char *
wl_resource_get_class(const struct wl_resource *resource);

const struct wl_interface *
wl_resource_get_interface(struct wl_resource *resource);

void
wl_resource_add_destroy_listener(struct wl_resource *resource,
				 struct wl_listener *listener);

struct wl_listener *
wl_resource_get_destroy_listener(struct wl_resource *resource,
				 wl_notify_func_t notify);

#define wl_resource_for_each(resource, list)					\
	for (resource = 0, resource = wl_resource_from_link((list)->next);	\
	     wl_resource_get_link(resource) != (list);				\
	     resource = wl_resource_from_link(wl_resource_get_link(resource)->next))

#define wl_resource_for_each_safe(resource, tmp, list)					\
	for (resource = 0, tmp = 0,							\
	     resource = wl_resource_from_link((list)->next),	\
	     tmp = wl_resource_from_link((list)->next->next);	\
	     wl_resource_get_link(resource) != (list);				\
	     resource = tmp,							\
	     tmp = wl_resource_from_link(wl_resource_get_link(resource)->next))

struct wl_shm_buffer *
wl_shm_buffer_get(struct wl_resource *resource);

void
wl_shm_buffer_begin_access(struct wl_shm_buffer *buffer);

void
wl_shm_buffer_end_access(struct wl_shm_buffer *buffer);

void *
wl_shm_buffer_get_data(struct wl_shm_buffer *buffer);

int32_t
wl_shm_buffer_get_stride(const struct wl_shm_buffer *buffer);

uint32_t
wl_shm_buffer_get_format(const struct wl_shm_buffer *buffer);

int32_t
wl_shm_buffer_get_width(const struct wl_shm_buffer *buffer);

int32_t
wl_shm_buffer_get_height(const struct wl_shm_buffer *buffer);

struct wl_shm_buffer *
wl_shm_buffer_ref(struct wl_shm_buffer *buffer);

void
wl_shm_buffer_unref(struct wl_shm_buffer *buffer);

struct wl_shm_pool *
wl_shm_buffer_ref_pool(struct wl_shm_buffer *buffer);

void
wl_shm_pool_unref(struct wl_shm_pool *pool);

int
wl_display_init_shm(struct wl_display *display);

uint32_t *
wl_display_add_shm_format(struct wl_display *display, uint32_t format);

WL_DEPRECATED
struct wl_shm_buffer *
wl_shm_buffer_create(struct wl_client *client,
		     uint32_t id, int32_t width, int32_t height,
		     int32_t stride, uint32_t format);

void
wl_log_set_handler_server(wl_log_func_t handler);

enum wl_protocol_logger_type {
	WL_PROTOCOL_LOGGER_REQUEST,
	WL_PROTOCOL_LOGGER_EVENT,
};

struct wl_protocol_logger_message {
	struct wl_resource *resource;
	int message_opcode;
	const struct wl_message *message;
	int arguments_count;
	const union wl_argument *arguments;
};

typedef void (*wl_protocol_logger_func_t)(void *user_data,
					  enum wl_protocol_logger_type direction,
					  const struct wl_protocol_logger_message *message);

struct wl_protocol_logger *
wl_display_add_protocol_logger(struct wl_display *display,
			       wl_protocol_logger_func_t, void *user_data);

void
wl_protocol_logger_destroy(struct wl_protocol_logger *logger);

void
wl_fixes_handle_ack_global_remove(struct wl_resource *fixes_resource,
				  struct wl_resource *registry_resource,
				  uint32_t global_name);

#ifdef  __cplusplus
}
#endif

#endif
