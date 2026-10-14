
#ifndef TEXT_INPUT_UNSTABLE_V3_SERVER_PROTOCOL_H
#define TEXT_INPUT_UNSTABLE_V3_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_text_input_unstable_v3 Copyright
 *
 * Copyright © 2012, 2013 Intel Corporation
 * Copyright © 2015, 2016 Jan Arne Petersen
 * Copyright © 2017, 2018 Red Hat, Inc.
 * Copyright © 2018       Purism SPC
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
struct wl_surface;
struct zwp_text_input_manager_v3;
struct zwp_text_input_v3;

#ifndef ZWP_TEXT_INPUT_V3_INTERFACE
#define ZWP_TEXT_INPUT_V3_INTERFACE
extern const struct wl_interface zwp_text_input_v3_interface;
#endif
#ifndef ZWP_TEXT_INPUT_MANAGER_V3_INTERFACE
#define ZWP_TEXT_INPUT_MANAGER_V3_INTERFACE
extern const struct wl_interface zwp_text_input_manager_v3_interface;
#endif

#ifndef ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_ENUM
#define ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_ENUM
enum zwp_text_input_v3_change_cause {
	ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_INPUT_METHOD = 0,
	ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_OTHER = 1,
};
#endif

#ifndef ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_ENUM_IS_VALID
#define ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_ENUM_IS_VALID
static inline bool
zwp_text_input_v3_change_cause_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_INPUT_METHOD:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_OTHER:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TEXT_INPUT_V3_CONTENT_HINT_ENUM
#define ZWP_TEXT_INPUT_V3_CONTENT_HINT_ENUM
enum zwp_text_input_v3_content_hint {
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_NONE = 0x0,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_COMPLETION = 0x1,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_SPELLCHECK = 0x2,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_AUTO_CAPITALIZATION = 0x4,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_LOWERCASE = 0x8,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_UPPERCASE = 0x10,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_TITLECASE = 0x20,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_HIDDEN_TEXT = 0x40,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_SENSITIVE_DATA = 0x80,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_LATIN = 0x100,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_MULTILINE = 0x200,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_ON_SCREEN_INPUT_PROVIDED = 0x400,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_NO_EMOJI = 0x800,
	ZWP_TEXT_INPUT_V3_CONTENT_HINT_PREEDIT_SHOWN = 0x1000,
};
#define ZWP_TEXT_INPUT_V3_CONTENT_HINT_ON_SCREEN_INPUT_PROVIDED_SINCE_VERSION 2
#define ZWP_TEXT_INPUT_V3_CONTENT_HINT_NO_EMOJI_SINCE_VERSION 2
#define ZWP_TEXT_INPUT_V3_CONTENT_HINT_PREEDIT_SHOWN_SINCE_VERSION 2
#endif

#ifndef ZWP_TEXT_INPUT_V3_CONTENT_HINT_ENUM_IS_VALID
#define ZWP_TEXT_INPUT_V3_CONTENT_HINT_ENUM_IS_VALID
static inline bool
zwp_text_input_v3_content_hint_is_valid(uint32_t value, uint32_t version) {
	uint32_t valid = 0;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_NONE;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_COMPLETION;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_SPELLCHECK;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_AUTO_CAPITALIZATION;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_LOWERCASE;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_UPPERCASE;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_TITLECASE;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_HIDDEN_TEXT;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_SENSITIVE_DATA;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_LATIN;
	if (version >= 1)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_MULTILINE;
	if (version >= 2)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_ON_SCREEN_INPUT_PROVIDED;
	if (version >= 2)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_NO_EMOJI;
	if (version >= 2)
		valid |= ZWP_TEXT_INPUT_V3_CONTENT_HINT_PREEDIT_SHOWN;
	return (value & ~valid) == 0;
}
#endif

#ifndef ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_ENUM
#define ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_ENUM
enum zwp_text_input_v3_content_purpose {
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_NORMAL = 0,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_ALPHA = 1,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_DIGITS = 2,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_NUMBER = 3,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_PHONE = 4,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_URL = 5,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_EMAIL = 6,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_NAME = 7,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_PASSWORD = 8,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_PIN = 9,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_DATE = 10,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_TIME = 11,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_DATETIME = 12,
	ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_TERMINAL = 13,
};
#endif

#ifndef ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_ENUM_IS_VALID
#define ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_ENUM_IS_VALID
static inline bool
zwp_text_input_v3_content_purpose_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_NORMAL:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_ALPHA:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_DIGITS:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_NUMBER:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_PHONE:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_URL:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_EMAIL:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_NAME:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_PASSWORD:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_PIN:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_DATE:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_TIME:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_DATETIME:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_TERMINAL:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TEXT_INPUT_V3_ERROR_ENUM
#define ZWP_TEXT_INPUT_V3_ERROR_ENUM
enum zwp_text_input_v3_error {
	ZWP_TEXT_INPUT_V3_ERROR_INVALID_ACTION = 0,
};
#endif

#ifndef ZWP_TEXT_INPUT_V3_ERROR_ENUM_IS_VALID
#define ZWP_TEXT_INPUT_V3_ERROR_ENUM_IS_VALID
static inline bool
zwp_text_input_v3_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TEXT_INPUT_V3_ERROR_INVALID_ACTION:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TEXT_INPUT_V3_ACTION_ENUM
#define ZWP_TEXT_INPUT_V3_ACTION_ENUM
enum zwp_text_input_v3_action {
	ZWP_TEXT_INPUT_V3_ACTION_NONE = 0,
	ZWP_TEXT_INPUT_V3_ACTION_SUBMIT = 1,
};
#endif

#ifndef ZWP_TEXT_INPUT_V3_ACTION_ENUM_IS_VALID
#define ZWP_TEXT_INPUT_V3_ACTION_ENUM_IS_VALID
static inline bool
zwp_text_input_v3_action_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TEXT_INPUT_V3_ACTION_NONE:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_ACTION_SUBMIT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef ZWP_TEXT_INPUT_V3_PREEDIT_HINT_ENUM
#define ZWP_TEXT_INPUT_V3_PREEDIT_HINT_ENUM
enum zwp_text_input_v3_preedit_hint {
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_WHOLE = 1,
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SELECTION = 2,
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_PREDICTION = 3,
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_PREFIX = 4,
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SUFFIX = 5,
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SPELLING_ERROR = 6,
	ZWP_TEXT_INPUT_V3_PREEDIT_HINT_COMPOSE_ERROR = 7,
};
#endif

#ifndef ZWP_TEXT_INPUT_V3_PREEDIT_HINT_ENUM_IS_VALID
#define ZWP_TEXT_INPUT_V3_PREEDIT_HINT_ENUM_IS_VALID
static inline bool
zwp_text_input_v3_preedit_hint_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_WHOLE:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SELECTION:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_PREDICTION:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_PREFIX:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SUFFIX:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SPELLING_ERROR:
		return version >= 1;
	case ZWP_TEXT_INPUT_V3_PREEDIT_HINT_COMPOSE_ERROR:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct zwp_text_input_v3_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*enable)(struct wl_client *client,
		       struct wl_resource *resource);
	void (*disable)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_surrounding_text)(struct wl_client *client,
				     struct wl_resource *resource,
				     const char *text,
				     int32_t cursor,
				     int32_t anchor);
	void (*set_text_change_cause)(struct wl_client *client,
				      struct wl_resource *resource,
				      uint32_t cause);
	void (*set_content_type)(struct wl_client *client,
				 struct wl_resource *resource,
				 uint32_t hint,
				 uint32_t purpose);
	void (*set_cursor_rectangle)(struct wl_client *client,
				     struct wl_resource *resource,
				     int32_t x,
				     int32_t y,
				     int32_t width,
				     int32_t height);
	void (*commit)(struct wl_client *client,
		       struct wl_resource *resource);
	void (*set_available_actions)(struct wl_client *client,
				      struct wl_resource *resource,
				      struct wl_array *available_actions);
	void (*show_input_panel)(struct wl_client *client,
				 struct wl_resource *resource);
	void (*hide_input_panel)(struct wl_client *client,
				 struct wl_resource *resource);
};

#define ZWP_TEXT_INPUT_V3_ENTER 0
#define ZWP_TEXT_INPUT_V3_LEAVE 1
#define ZWP_TEXT_INPUT_V3_PREEDIT_STRING 2
#define ZWP_TEXT_INPUT_V3_COMMIT_STRING 3
#define ZWP_TEXT_INPUT_V3_DELETE_SURROUNDING_TEXT 4
#define ZWP_TEXT_INPUT_V3_DONE 5
#define ZWP_TEXT_INPUT_V3_ACTION 6
#define ZWP_TEXT_INPUT_V3_LANGUAGE 7
#define ZWP_TEXT_INPUT_V3_PREEDIT_HINT 8

#define ZWP_TEXT_INPUT_V3_ENTER_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_LEAVE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_PREEDIT_STRING_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_COMMIT_STRING_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_DELETE_SURROUNDING_TEXT_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_DONE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_ACTION_SINCE_VERSION 2
#define ZWP_TEXT_INPUT_V3_LANGUAGE_SINCE_VERSION 2
#define ZWP_TEXT_INPUT_V3_PREEDIT_HINT_SINCE_VERSION 2

#define ZWP_TEXT_INPUT_V3_DESTROY_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_ENABLE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_DISABLE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_SET_SURROUNDING_TEXT_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_SET_TEXT_CHANGE_CAUSE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_SET_CONTENT_TYPE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_SET_CURSOR_RECTANGLE_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_COMMIT_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_V3_SET_AVAILABLE_ACTIONS_SINCE_VERSION 2
#define ZWP_TEXT_INPUT_V3_SHOW_INPUT_PANEL_SINCE_VERSION 2
#define ZWP_TEXT_INPUT_V3_HIDE_INPUT_PANEL_SINCE_VERSION 2

static inline void
zwp_text_input_v3_send_enter(struct wl_resource *resource_, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_ENTER, surface);
}

static inline void
zwp_text_input_v3_send_leave(struct wl_resource *resource_, struct wl_resource *surface)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_LEAVE, surface);
}

static inline void
zwp_text_input_v3_send_preedit_string(struct wl_resource *resource_, const char *text, int32_t cursor_begin, int32_t cursor_end)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_PREEDIT_STRING, text, cursor_begin, cursor_end);
}

static inline void
zwp_text_input_v3_send_commit_string(struct wl_resource *resource_, const char *text)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_COMMIT_STRING, text);
}

static inline void
zwp_text_input_v3_send_delete_surrounding_text(struct wl_resource *resource_, uint32_t before_length, uint32_t after_length)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_DELETE_SURROUNDING_TEXT, before_length, after_length);
}

static inline void
zwp_text_input_v3_send_done(struct wl_resource *resource_, uint32_t serial)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_DONE, serial);
}

static inline void
zwp_text_input_v3_send_action(struct wl_resource *resource_, uint32_t action, uint32_t serial)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_ACTION, action, serial);
}

static inline void
zwp_text_input_v3_send_language(struct wl_resource *resource_, const char *language)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_LANGUAGE, language);
}

static inline void
zwp_text_input_v3_send_preedit_hint(struct wl_resource *resource_, uint32_t start, uint32_t end, uint32_t hint)
{
	wl_resource_post_event(resource_, ZWP_TEXT_INPUT_V3_PREEDIT_HINT, start, end, hint);
}

struct zwp_text_input_manager_v3_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_text_input)(struct wl_client *client,
			       struct wl_resource *resource,
			       uint32_t id,
			       struct wl_resource *seat);
};


#define ZWP_TEXT_INPUT_MANAGER_V3_DESTROY_SINCE_VERSION 1
#define ZWP_TEXT_INPUT_MANAGER_V3_GET_TEXT_INPUT_SINCE_VERSION 1

#ifdef  __cplusplus
}
#endif

#endif
