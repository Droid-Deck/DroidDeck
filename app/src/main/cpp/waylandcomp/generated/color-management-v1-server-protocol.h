
#ifndef COLOR_MANAGEMENT_V1_SERVER_PROTOCOL_H
#define COLOR_MANAGEMENT_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/*
 * @section page_copyright_color_management_v1 Copyright
 *
 * Copyright 2019 Sebastian Wick
 * Copyright 2019 Erwin Burema
 * Copyright 2020 AMD
 * Copyright 2020-2024 Collabora, Ltd.
 * Copyright 2024 Xaver Hugl
 * Copyright 2022-2025 Red Hat, Inc.
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

























































struct wl_output;
struct wl_surface;
struct wp_color_management_output_v1;
struct wp_color_management_surface_feedback_v1;
struct wp_color_management_surface_v1;
struct wp_color_manager_v1;
struct wp_image_description_creator_icc_v1;
struct wp_image_description_creator_params_v1;
struct wp_image_description_info_v1;
struct wp_image_description_v1;

#ifndef WP_COLOR_MANAGER_V1_INTERFACE
#define WP_COLOR_MANAGER_V1_INTERFACE
extern const struct wl_interface wp_color_manager_v1_interface;
#endif
#ifndef WP_COLOR_MANAGEMENT_OUTPUT_V1_INTERFACE
#define WP_COLOR_MANAGEMENT_OUTPUT_V1_INTERFACE
extern const struct wl_interface wp_color_management_output_v1_interface;
#endif
#ifndef WP_COLOR_MANAGEMENT_SURFACE_V1_INTERFACE
#define WP_COLOR_MANAGEMENT_SURFACE_V1_INTERFACE
extern const struct wl_interface wp_color_management_surface_v1_interface;
#endif
#ifndef WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_INTERFACE
#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_INTERFACE
extern const struct wl_interface wp_color_management_surface_feedback_v1_interface;
#endif
#ifndef WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_INTERFACE
#define WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_INTERFACE
extern const struct wl_interface wp_image_description_creator_icc_v1_interface;
#endif
#ifndef WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_INTERFACE
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_INTERFACE
extern const struct wl_interface wp_image_description_creator_params_v1_interface;
#endif
#ifndef WP_IMAGE_DESCRIPTION_V1_INTERFACE
#define WP_IMAGE_DESCRIPTION_V1_INTERFACE
extern const struct wl_interface wp_image_description_v1_interface;
#endif
#ifndef WP_IMAGE_DESCRIPTION_INFO_V1_INTERFACE
#define WP_IMAGE_DESCRIPTION_INFO_V1_INTERFACE
extern const struct wl_interface wp_image_description_info_v1_interface;
#endif

#ifndef WP_COLOR_MANAGER_V1_ERROR_ENUM
#define WP_COLOR_MANAGER_V1_ERROR_ENUM
enum wp_color_manager_v1_error {
	WP_COLOR_MANAGER_V1_ERROR_UNSUPPORTED_FEATURE = 0,
	WP_COLOR_MANAGER_V1_ERROR_SURFACE_EXISTS = 1,
};
#endif

#ifndef WP_COLOR_MANAGER_V1_ERROR_ENUM_IS_VALID
#define WP_COLOR_MANAGER_V1_ERROR_ENUM_IS_VALID
static inline bool
wp_color_manager_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGER_V1_ERROR_UNSUPPORTED_FEATURE:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_ERROR_SURFACE_EXISTS:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WP_COLOR_MANAGER_V1_RENDER_INTENT_ENUM
#define WP_COLOR_MANAGER_V1_RENDER_INTENT_ENUM
enum wp_color_manager_v1_render_intent {
	WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL = 0,
	WP_COLOR_MANAGER_V1_RENDER_INTENT_RELATIVE = 1,
	WP_COLOR_MANAGER_V1_RENDER_INTENT_SATURATION = 2,
	WP_COLOR_MANAGER_V1_RENDER_INTENT_ABSOLUTE = 3,
	WP_COLOR_MANAGER_V1_RENDER_INTENT_RELATIVE_BPC = 4,
};
#endif

#ifndef WP_COLOR_MANAGER_V1_RENDER_INTENT_ENUM_IS_VALID
#define WP_COLOR_MANAGER_V1_RENDER_INTENT_ENUM_IS_VALID
static inline bool
wp_color_manager_v1_render_intent_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_RENDER_INTENT_RELATIVE:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_RENDER_INTENT_SATURATION:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_RENDER_INTENT_ABSOLUTE:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_RENDER_INTENT_RELATIVE_BPC:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WP_COLOR_MANAGER_V1_FEATURE_ENUM
#define WP_COLOR_MANAGER_V1_FEATURE_ENUM
enum wp_color_manager_v1_feature {
	WP_COLOR_MANAGER_V1_FEATURE_ICC_V2_V4 = 0,
	WP_COLOR_MANAGER_V1_FEATURE_PARAMETRIC = 1,
	WP_COLOR_MANAGER_V1_FEATURE_SET_PRIMARIES = 2,
	WP_COLOR_MANAGER_V1_FEATURE_SET_TF_POWER = 3,
	WP_COLOR_MANAGER_V1_FEATURE_SET_LUMINANCES = 4,
	WP_COLOR_MANAGER_V1_FEATURE_SET_MASTERING_DISPLAY_PRIMARIES = 5,
	WP_COLOR_MANAGER_V1_FEATURE_EXTENDED_TARGET_VOLUME = 6,
	WP_COLOR_MANAGER_V1_FEATURE_WINDOWS_SCRGB = 7,
};
#endif

#ifndef WP_COLOR_MANAGER_V1_FEATURE_ENUM_IS_VALID
#define WP_COLOR_MANAGER_V1_FEATURE_ENUM_IS_VALID
static inline bool
wp_color_manager_v1_feature_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGER_V1_FEATURE_ICC_V2_V4:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_PARAMETRIC:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_SET_PRIMARIES:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_SET_TF_POWER:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_SET_LUMINANCES:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_SET_MASTERING_DISPLAY_PRIMARIES:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_EXTENDED_TARGET_VOLUME:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_FEATURE_WINDOWS_SCRGB:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WP_COLOR_MANAGER_V1_PRIMARIES_ENUM
#define WP_COLOR_MANAGER_V1_PRIMARIES_ENUM
enum wp_color_manager_v1_primaries {
	WP_COLOR_MANAGER_V1_PRIMARIES_SRGB = 1,
	WP_COLOR_MANAGER_V1_PRIMARIES_PAL_M = 2,
	WP_COLOR_MANAGER_V1_PRIMARIES_PAL = 3,
	WP_COLOR_MANAGER_V1_PRIMARIES_NTSC = 4,
	WP_COLOR_MANAGER_V1_PRIMARIES_GENERIC_FILM = 5,
	WP_COLOR_MANAGER_V1_PRIMARIES_BT2020 = 6,
	WP_COLOR_MANAGER_V1_PRIMARIES_CIE1931_XYZ = 7,
	WP_COLOR_MANAGER_V1_PRIMARIES_DCI_P3 = 8,
	WP_COLOR_MANAGER_V1_PRIMARIES_DISPLAY_P3 = 9,
	WP_COLOR_MANAGER_V1_PRIMARIES_ADOBE_RGB = 10,
};
#endif

#ifndef WP_COLOR_MANAGER_V1_PRIMARIES_ENUM_IS_VALID
#define WP_COLOR_MANAGER_V1_PRIMARIES_ENUM_IS_VALID
static inline bool
wp_color_manager_v1_primaries_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGER_V1_PRIMARIES_SRGB:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_PAL_M:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_PAL:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_NTSC:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_GENERIC_FILM:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_BT2020:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_CIE1931_XYZ:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_DCI_P3:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_DISPLAY_P3:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_PRIMARIES_ADOBE_RGB:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ENUM
#define WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ENUM
enum wp_color_manager_v1_transfer_function {
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_BT1886 = 1,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA22 = 2,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA28 = 3,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST240 = 4,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_LINEAR = 5,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_LOG_100 = 6,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_LOG_316 = 7,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_XVYCC = 8,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_SRGB = 9,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_SRGB = 10,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ = 11,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST428 = 12,
	WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG = 13,
};
#endif

#ifndef WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ENUM_IS_VALID
#define WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ENUM_IS_VALID
static inline bool
wp_color_manager_v1_transfer_function_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_BT1886:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA22:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA28:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST240:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_LINEAR:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_LOG_100:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_LOG_316:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_XVYCC:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_SRGB:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_SRGB:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST428:
		return version >= 1;
	case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wp_color_manager_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_output)(struct wl_client *client,
			   struct wl_resource *resource,
			   uint32_t id,
			   struct wl_resource *output);
	void (*get_surface)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t id,
			    struct wl_resource *surface);
	void (*get_surface_feedback)(struct wl_client *client,
				     struct wl_resource *resource,
				     uint32_t id,
				     struct wl_resource *surface);
	void (*create_icc_creator)(struct wl_client *client,
				   struct wl_resource *resource,
				   uint32_t obj);
	void (*create_parametric_creator)(struct wl_client *client,
					  struct wl_resource *resource,
					  uint32_t obj);
	void (*create_windows_scrgb)(struct wl_client *client,
				     struct wl_resource *resource,
				     uint32_t image_description);
};

#define WP_COLOR_MANAGER_V1_SUPPORTED_INTENT 0
#define WP_COLOR_MANAGER_V1_SUPPORTED_FEATURE 1
#define WP_COLOR_MANAGER_V1_SUPPORTED_TF_NAMED 2
#define WP_COLOR_MANAGER_V1_SUPPORTED_PRIMARIES_NAMED 3
#define WP_COLOR_MANAGER_V1_DONE 4

#define WP_COLOR_MANAGER_V1_SUPPORTED_INTENT_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_SUPPORTED_FEATURE_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_SUPPORTED_TF_NAMED_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_SUPPORTED_PRIMARIES_NAMED_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_DONE_SINCE_VERSION 1

#define WP_COLOR_MANAGER_V1_DESTROY_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_GET_OUTPUT_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_GET_SURFACE_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_GET_SURFACE_FEEDBACK_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_CREATE_ICC_CREATOR_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_CREATE_PARAMETRIC_CREATOR_SINCE_VERSION 1
#define WP_COLOR_MANAGER_V1_CREATE_WINDOWS_SCRGB_SINCE_VERSION 1

static inline void
wp_color_manager_v1_send_supported_intent(struct wl_resource *resource_, uint32_t render_intent)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGER_V1_SUPPORTED_INTENT, render_intent);
}

static inline void
wp_color_manager_v1_send_supported_feature(struct wl_resource *resource_, uint32_t feature)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGER_V1_SUPPORTED_FEATURE, feature);
}

static inline void
wp_color_manager_v1_send_supported_tf_named(struct wl_resource *resource_, uint32_t tf)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGER_V1_SUPPORTED_TF_NAMED, tf);
}

static inline void
wp_color_manager_v1_send_supported_primaries_named(struct wl_resource *resource_, uint32_t primaries)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGER_V1_SUPPORTED_PRIMARIES_NAMED, primaries);
}

static inline void
wp_color_manager_v1_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGER_V1_DONE);
}

struct wp_color_management_output_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_image_description)(struct wl_client *client,
				      struct wl_resource *resource,
				      uint32_t image_description);
};

#define WP_COLOR_MANAGEMENT_OUTPUT_V1_IMAGE_DESCRIPTION_CHANGED 0

#define WP_COLOR_MANAGEMENT_OUTPUT_V1_IMAGE_DESCRIPTION_CHANGED_SINCE_VERSION 1

#define WP_COLOR_MANAGEMENT_OUTPUT_V1_DESTROY_SINCE_VERSION 1
#define WP_COLOR_MANAGEMENT_OUTPUT_V1_GET_IMAGE_DESCRIPTION_SINCE_VERSION 1

static inline void
wp_color_management_output_v1_send_image_description_changed(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGEMENT_OUTPUT_V1_IMAGE_DESCRIPTION_CHANGED);
}

#ifndef WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_ENUM
#define WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_ENUM
enum wp_color_management_surface_v1_error {
	WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_RENDER_INTENT = 0,
	WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_IMAGE_DESCRIPTION = 1,
	WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_INERT = 2,
};
#endif

#ifndef WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_ENUM_IS_VALID
#define WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_ENUM_IS_VALID
static inline bool
wp_color_management_surface_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_RENDER_INTENT:
		return version >= 1;
	case WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_IMAGE_DESCRIPTION:
		return version >= 1;
	case WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_INERT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wp_color_management_surface_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*set_image_description)(struct wl_client *client,
				      struct wl_resource *resource,
				      struct wl_resource *image_description,
				      uint32_t render_intent);
	void (*unset_image_description)(struct wl_client *client,
					struct wl_resource *resource);
};


#define WP_COLOR_MANAGEMENT_SURFACE_V1_DESTROY_SINCE_VERSION 1
#define WP_COLOR_MANAGEMENT_SURFACE_V1_SET_IMAGE_DESCRIPTION_SINCE_VERSION 1
#define WP_COLOR_MANAGEMENT_SURFACE_V1_UNSET_IMAGE_DESCRIPTION_SINCE_VERSION 1

#ifndef WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_ENUM
#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_ENUM
enum wp_color_management_surface_feedback_v1_error {
	WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_INERT = 0,
	WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_UNSUPPORTED_FEATURE = 1,
};
#endif

#ifndef WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_ENUM_IS_VALID
#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_ENUM_IS_VALID
static inline bool
wp_color_management_surface_feedback_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_INERT:
		return version >= 1;
	case WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_ERROR_UNSUPPORTED_FEATURE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wp_color_management_surface_feedback_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_preferred)(struct wl_client *client,
			      struct wl_resource *resource,
			      uint32_t image_description);
	void (*get_preferred_parametric)(struct wl_client *client,
					 struct wl_resource *resource,
					 uint32_t image_description);
};

#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_PREFERRED_CHANGED 0

#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_PREFERRED_CHANGED_SINCE_VERSION 1

#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_DESTROY_SINCE_VERSION 1
#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_GET_PREFERRED_SINCE_VERSION 1
#define WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_GET_PREFERRED_PARAMETRIC_SINCE_VERSION 1

static inline void
wp_color_management_surface_feedback_v1_send_preferred_changed(struct wl_resource *resource_, uint32_t identity)
{
	wl_resource_post_event(resource_, WP_COLOR_MANAGEMENT_SURFACE_FEEDBACK_V1_PREFERRED_CHANGED, identity);
}

#ifndef WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_ENUM
#define WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_ENUM
enum wp_image_description_creator_icc_v1_error {
	WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_INCOMPLETE_SET = 0,
	WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_ALREADY_SET = 1,
	WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_BAD_FD = 2,
	WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_BAD_SIZE = 3,
	WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_OUT_OF_FILE = 4,
};
#endif

#ifndef WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_ENUM_IS_VALID
#define WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_ENUM_IS_VALID
static inline bool
wp_image_description_creator_icc_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_INCOMPLETE_SET:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_ALREADY_SET:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_BAD_FD:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_BAD_SIZE:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_ERROR_OUT_OF_FILE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wp_image_description_creator_icc_v1_interface {
	void (*create)(struct wl_client *client,
		       struct wl_resource *resource,
		       uint32_t image_description);
	void (*set_icc_file)(struct wl_client *client,
			     struct wl_resource *resource,
			     int32_t icc_profile,
			     uint32_t offset,
			     uint32_t length);
};


#define WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_CREATE_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_ICC_V1_SET_ICC_FILE_SINCE_VERSION 1

#ifndef WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ENUM
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ENUM
enum wp_image_description_creator_params_v1_error {
	WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INCOMPLETE_SET = 0,
	WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ALREADY_SET = 1,
	WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE = 2,
	WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_TF = 3,
	WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_PRIMARIES_NAMED = 4,
	WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_LUMINANCE = 5,
};
#endif

#ifndef WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ENUM_IS_VALID
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ENUM_IS_VALID
static inline bool
wp_image_description_creator_params_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INCOMPLETE_SET:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ALREADY_SET:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_TF:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_PRIMARIES_NAMED:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_LUMINANCE:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wp_image_description_creator_params_v1_interface {
	void (*create)(struct wl_client *client,
		       struct wl_resource *resource,
		       uint32_t image_description);
	void (*set_tf_named)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t tf);
	void (*set_tf_power)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t eexp);
	void (*set_primaries_named)(struct wl_client *client,
				    struct wl_resource *resource,
				    uint32_t primaries);
	void (*set_primaries)(struct wl_client *client,
			      struct wl_resource *resource,
			      int32_t r_x,
			      int32_t r_y,
			      int32_t g_x,
			      int32_t g_y,
			      int32_t b_x,
			      int32_t b_y,
			      int32_t w_x,
			      int32_t w_y);
	void (*set_luminances)(struct wl_client *client,
			       struct wl_resource *resource,
			       uint32_t min_lum,
			       uint32_t max_lum,
			       uint32_t reference_lum);
	void (*set_mastering_display_primaries)(struct wl_client *client,
						struct wl_resource *resource,
						int32_t r_x,
						int32_t r_y,
						int32_t g_x,
						int32_t g_y,
						int32_t b_x,
						int32_t b_y,
						int32_t w_x,
						int32_t w_y);
	void (*set_mastering_luminance)(struct wl_client *client,
					struct wl_resource *resource,
					uint32_t min_lum,
					uint32_t max_lum);
	void (*set_max_cll)(struct wl_client *client,
			    struct wl_resource *resource,
			    uint32_t max_cll);
	void (*set_max_fall)(struct wl_client *client,
			     struct wl_resource *resource,
			     uint32_t max_fall);
};


#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_CREATE_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_TF_NAMED_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_TF_POWER_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_PRIMARIES_NAMED_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_PRIMARIES_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_LUMINANCES_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_MASTERING_DISPLAY_PRIMARIES_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_MASTERING_LUMINANCE_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_MAX_CLL_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_SET_MAX_FALL_SINCE_VERSION 1

#ifndef WP_IMAGE_DESCRIPTION_V1_ERROR_ENUM
#define WP_IMAGE_DESCRIPTION_V1_ERROR_ENUM
enum wp_image_description_v1_error {
	WP_IMAGE_DESCRIPTION_V1_ERROR_NOT_READY = 0,
	WP_IMAGE_DESCRIPTION_V1_ERROR_NO_INFORMATION = 1,
};
#endif

#ifndef WP_IMAGE_DESCRIPTION_V1_ERROR_ENUM_IS_VALID
#define WP_IMAGE_DESCRIPTION_V1_ERROR_ENUM_IS_VALID
static inline bool
wp_image_description_v1_error_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_IMAGE_DESCRIPTION_V1_ERROR_NOT_READY:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_V1_ERROR_NO_INFORMATION:
		return version >= 1;
	default:
		return false;
	}
}
#endif

#ifndef WP_IMAGE_DESCRIPTION_V1_CAUSE_ENUM
#define WP_IMAGE_DESCRIPTION_V1_CAUSE_ENUM
enum wp_image_description_v1_cause {
	WP_IMAGE_DESCRIPTION_V1_CAUSE_LOW_VERSION = 0,
	WP_IMAGE_DESCRIPTION_V1_CAUSE_UNSUPPORTED = 1,
	WP_IMAGE_DESCRIPTION_V1_CAUSE_OPERATING_SYSTEM = 2,
	WP_IMAGE_DESCRIPTION_V1_CAUSE_NO_OUTPUT = 3,
};
#endif

#ifndef WP_IMAGE_DESCRIPTION_V1_CAUSE_ENUM_IS_VALID
#define WP_IMAGE_DESCRIPTION_V1_CAUSE_ENUM_IS_VALID
static inline bool
wp_image_description_v1_cause_is_valid(uint32_t value, uint32_t version) {
	switch (value) {
	case WP_IMAGE_DESCRIPTION_V1_CAUSE_LOW_VERSION:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_V1_CAUSE_UNSUPPORTED:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_V1_CAUSE_OPERATING_SYSTEM:
		return version >= 1;
	case WP_IMAGE_DESCRIPTION_V1_CAUSE_NO_OUTPUT:
		return version >= 1;
	default:
		return false;
	}
}
#endif

struct wp_image_description_v1_interface {
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	void (*get_information)(struct wl_client *client,
				struct wl_resource *resource,
				uint32_t information);
};

#define WP_IMAGE_DESCRIPTION_V1_FAILED 0
#define WP_IMAGE_DESCRIPTION_V1_READY 1

#define WP_IMAGE_DESCRIPTION_V1_FAILED_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_V1_READY_SINCE_VERSION 1

#define WP_IMAGE_DESCRIPTION_V1_DESTROY_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_V1_GET_INFORMATION_SINCE_VERSION 1

static inline void
wp_image_description_v1_send_failed(struct wl_resource *resource_, uint32_t cause, const char *msg)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_V1_FAILED, cause, msg);
}

static inline void
wp_image_description_v1_send_ready(struct wl_resource *resource_, uint32_t identity)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_V1_READY, identity);
}

#define WP_IMAGE_DESCRIPTION_INFO_V1_DONE 0
#define WP_IMAGE_DESCRIPTION_INFO_V1_ICC_FILE 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_PRIMARIES 2
#define WP_IMAGE_DESCRIPTION_INFO_V1_PRIMARIES_NAMED 3
#define WP_IMAGE_DESCRIPTION_INFO_V1_TF_POWER 4
#define WP_IMAGE_DESCRIPTION_INFO_V1_TF_NAMED 5
#define WP_IMAGE_DESCRIPTION_INFO_V1_LUMINANCES 6
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_PRIMARIES 7
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_LUMINANCE 8
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_MAX_CLL 9
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_MAX_FALL 10

#define WP_IMAGE_DESCRIPTION_INFO_V1_DONE_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_ICC_FILE_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_PRIMARIES_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_PRIMARIES_NAMED_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_TF_POWER_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_TF_NAMED_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_LUMINANCES_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_PRIMARIES_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_LUMINANCE_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_MAX_CLL_SINCE_VERSION 1
#define WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_MAX_FALL_SINCE_VERSION 1


static inline void
wp_image_description_info_v1_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_DONE);
}

static inline void
wp_image_description_info_v1_send_icc_file(struct wl_resource *resource_, int32_t icc, uint32_t icc_size)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_ICC_FILE, icc, icc_size);
}

static inline void
wp_image_description_info_v1_send_primaries(struct wl_resource *resource_, int32_t r_x, int32_t r_y, int32_t g_x, int32_t g_y, int32_t b_x, int32_t b_y, int32_t w_x, int32_t w_y)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_PRIMARIES, r_x, r_y, g_x, g_y, b_x, b_y, w_x, w_y);
}

static inline void
wp_image_description_info_v1_send_primaries_named(struct wl_resource *resource_, uint32_t primaries)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_PRIMARIES_NAMED, primaries);
}

static inline void
wp_image_description_info_v1_send_tf_power(struct wl_resource *resource_, uint32_t eexp)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_TF_POWER, eexp);
}

static inline void
wp_image_description_info_v1_send_tf_named(struct wl_resource *resource_, uint32_t tf)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_TF_NAMED, tf);
}

static inline void
wp_image_description_info_v1_send_luminances(struct wl_resource *resource_, uint32_t min_lum, uint32_t max_lum, uint32_t reference_lum)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_LUMINANCES, min_lum, max_lum, reference_lum);
}

static inline void
wp_image_description_info_v1_send_target_primaries(struct wl_resource *resource_, int32_t r_x, int32_t r_y, int32_t g_x, int32_t g_y, int32_t b_x, int32_t b_y, int32_t w_x, int32_t w_y)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_PRIMARIES, r_x, r_y, g_x, g_y, b_x, b_y, w_x, w_y);
}

static inline void
wp_image_description_info_v1_send_target_luminance(struct wl_resource *resource_, uint32_t min_lum, uint32_t max_lum)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_LUMINANCE, min_lum, max_lum);
}

static inline void
wp_image_description_info_v1_send_target_max_cll(struct wl_resource *resource_, uint32_t max_cll)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_MAX_CLL, max_cll);
}

static inline void
wp_image_description_info_v1_send_target_max_fall(struct wl_resource *resource_, uint32_t max_fall)
{
	wl_resource_post_event(resource_, WP_IMAGE_DESCRIPTION_INFO_V1_TARGET_MAX_FALL, max_fall);
}

#ifdef  __cplusplus
}
#endif

#endif
