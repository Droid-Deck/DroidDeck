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


#ifndef WAYLAND_UTIL_H
#define WAYLAND_UTIL_H

#include <math.h>
#include <stddef.h>
#include <inttypes.h>
#include <stdarg.h>

#ifdef  __cplusplus
extern "C" {
#endif

#if defined(__GNUC__) && __GNUC__ >= 4
#define WL_EXPORT __attribute__ ((visibility("default")))
#else
#define WL_EXPORT
#endif

#if (defined(__STDC_VERSION__) && __STDC_VERSION__ >= 202311L) || (defined(__cplusplus) && __cplusplus >= 201402L)
#define WL_DEPRECATED [[deprecated]]
#elif defined(__GNUC__) && __GNUC__ >= 4
#define WL_DEPRECATED __attribute__ ((deprecated))
#else
#define WL_DEPRECATED
#endif

#if defined(__GNUC__) && __GNUC__ >= 4
#define WL_PRINTF(x, y) __attribute__((__format__(__printf__, x, y)))
#else
#define WL_PRINTF(x, y)
#endif

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 202311L
#define WL_TYPEOF(expr) typeof(expr)
#else
#define WL_TYPEOF(expr) __typeof__(expr)
#endif

struct wl_object;

#define WL_MAX_MESSAGE_SIZE 4096

struct wl_message {
	const char *name;
	const char *signature;
	const struct wl_interface **types;
};

struct wl_interface {
	const char *name;
	int version;
	int method_count;
	const struct wl_message *methods;
	int event_count;
	const struct wl_message *events;
};

struct wl_list {
	struct wl_list *prev;
	struct wl_list *next;
};

void
wl_list_init(struct wl_list *list);

void
wl_list_insert(struct wl_list *list, struct wl_list *elm);

void
wl_list_remove(struct wl_list *elm);

int
wl_list_length(const struct wl_list *list);

int
wl_list_empty(const struct wl_list *list);

void
wl_list_insert_list(struct wl_list *list, struct wl_list *other);

#define wl_container_of(ptr, sample, member)				\
	(WL_TYPEOF(sample))((char *)(ptr) -				\
			     offsetof(WL_TYPEOF(*sample), member))

#define wl_list_for_each(pos, head, member)				\
	for (pos = wl_container_of((head)->next, pos, member);	\
	     &pos->member != (head);					\
	     pos = wl_container_of(pos->member.next, pos, member))

#define wl_list_for_each_safe(pos, tmp, head, member)			\
	for (pos = wl_container_of((head)->next, pos, member),		\
	     tmp = wl_container_of((pos)->member.next, tmp, member);	\
	     &pos->member != (head);					\
	     pos = tmp,							\
	     tmp = wl_container_of(pos->member.next, tmp, member))

#define wl_list_for_each_reverse(pos, head, member)			\
	for (pos = wl_container_of((head)->prev, pos, member);	\
	     &pos->member != (head);					\
	     pos = wl_container_of(pos->member.prev, pos, member))

#define wl_list_for_each_reverse_safe(pos, tmp, head, member)		\
	for (pos = wl_container_of((head)->prev, pos, member),	\
	     tmp = wl_container_of((pos)->member.prev, tmp, member);	\
	     &pos->member != (head);					\
	     pos = tmp,							\
	     tmp = wl_container_of(pos->member.prev, tmp, member))

struct wl_array {
	size_t size;
	size_t alloc;
	void *data;
};

void
wl_array_init(struct wl_array *array);

void
wl_array_release(struct wl_array *array);

void *
wl_array_add(struct wl_array *array, size_t size);

int
wl_array_copy(struct wl_array *array, struct wl_array *source);

#define wl_array_for_each(pos, array)					\
	for (pos = (array)->data;					\
	     (array)->size != 0 &&					\
	     (const char *) pos < ((const char *) (array)->data + (array)->size); \
	     (pos)++)

typedef int32_t wl_fixed_t;

static inline double
wl_fixed_to_double(wl_fixed_t f)
{
	return f / 256.0;
}

static inline wl_fixed_t
wl_fixed_from_double(double d)
{
	return (wl_fixed_t) (round(d * 256.0));
}

static inline int
wl_fixed_to_int(wl_fixed_t f)
{
	return f / 256;
}

static inline wl_fixed_t
wl_fixed_from_int(int i)
{
	return i * 256;
}

union wl_argument {
	int32_t i;
	uint32_t u;
	wl_fixed_t f;
	const char *s;
	struct wl_object *o;
	uint32_t n;
	struct wl_array *a;
	int32_t h;
};

typedef int (*wl_dispatcher_func_t)(const void *user_data, void *target,
				    uint32_t opcode, const struct wl_message *msg,
				    union wl_argument *args);

typedef void (*wl_log_func_t)(const char *fmt, va_list args) WL_PRINTF(1, 0);

enum wl_iterator_result {
	WL_ITERATOR_STOP,
	WL_ITERATOR_CONTINUE
};

#ifdef  __cplusplus
}
#endif

#endif
