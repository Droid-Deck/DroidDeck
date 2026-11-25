
#ifndef DROIDDECK_REFRESH_V1_SERVER_PROTOCOL_H
#define DROIDDECK_REFRESH_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-server.h"

#ifdef  __cplusplus
extern "C" {
#endif

struct wl_client;
struct wl_resource;

/**
 * @page page_droiddeck_refresh_v1 The droiddeck_refresh_v1 protocol
 * @section page_ifaces_droiddeck_refresh_v1 Interfaces
 * - @subpage page_iface_droiddeck_refresh_v1 - panel refresh rates a nested compositor may ask for
 * @section page_copyright_droiddeck_refresh_v1 Copyright
 * <pre>
 *
 * Copyright 2026 DroidDeck contributors
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
 * </pre>
 */
struct droiddeck_refresh_v1;

#ifndef DROIDDECK_REFRESH_V1_INTERFACE
#define DROIDDECK_REFRESH_V1_INTERFACE
/**
 * @page page_iface_droiddeck_refresh_v1 droiddeck_refresh_v1
 * @section page_iface_droiddeck_refresh_v1_desc Description
 *
 * A nested compositor paces its clients against the refresh rate of the
 * panel it is shown on. When its clients are held to a frame rate the
 * panel's current rate does not divide evenly, frames are shown for an
 * uneven number of refreshes. This interface lets the nested compositor
 * see every refresh rate the panel can run at its current size and ask
 * for the one that suits the frame rate it is pacing to.
 *
 * On bind the compositor sends one rate event per refresh rate, lowest
 * first, followed by done. The list is sent again, in full, whenever it
 * changes (a different panel, or a limit the user placed on the panel's
 * rate). The rate the panel is running at is still reported the standard
 * way, through wl_output.mode.
 *
 * A request is a preference, not a guarantee: the user, the platform or
 * another output mirroring the panel may hold the panel at another rate.
 * The client reads the result from wl_output.mode.
 * @section page_iface_droiddeck_refresh_v1_api API
 * See @ref iface_droiddeck_refresh_v1.
 */
/**
 * @defgroup iface_droiddeck_refresh_v1 The droiddeck_refresh_v1 interface
 *
 * A nested compositor paces its clients against the refresh rate of the
 * panel it is shown on. When its clients are held to a frame rate the
 * panel's current rate does not divide evenly, frames are shown for an
 * uneven number of refreshes. This interface lets the nested compositor
 * see every refresh rate the panel can run at its current size and ask
 * for the one that suits the frame rate it is pacing to.
 *
 * On bind the compositor sends one rate event per refresh rate, lowest
 * first, followed by done. The list is sent again, in full, whenever it
 * changes (a different panel, or a limit the user placed on the panel's
 * rate). The rate the panel is running at is still reported the standard
 * way, through wl_output.mode.
 *
 * A request is a preference, not a guarantee: the user, the platform or
 * another output mirroring the panel may hold the panel at another rate.
 * The client reads the result from wl_output.mode.
 */
extern const struct wl_interface droiddeck_refresh_v1_interface;
#endif

/**
 * @ingroup iface_droiddeck_refresh_v1
 * @struct droiddeck_refresh_v1_interface
 */
struct droiddeck_refresh_v1_interface {
	/**
	 * destroy the object
	 *
	 * The preference set through this object is dropped.
	 */
	void (*destroy)(struct wl_client *client,
			struct wl_resource *resource);
	/**
	 * ask for a refresh rate
	 *
	 * refresh is the panel rate the client would like, in Hz, taken
	 * from the advertised list; 0 withdraws the preference. frame_rate
	 * is the frame rate the client holds its content to, in frames per
	 * second, or 0 when the content is not held to a rate. The
	 * compositor may multiply the content's frame rate itself
	 * (generated frames); when it does, it picks the advertised rate
	 * that the multiplied frame rate divides evenly instead of the
	 * requested one.
	 * @param refresh wanted panel refresh rate in Hz, 0 for none
	 * @param frame_rate content frame rate in frames per second, 0 for unpaced
	 */
	void (*set_target)(struct wl_client *client,
			   struct wl_resource *resource,
			   uint32_t refresh,
			   uint32_t frame_rate);
};

#define DROIDDECK_REFRESH_V1_RATE 0
#define DROIDDECK_REFRESH_V1_DONE 1

/**
 * @ingroup iface_droiddeck_refresh_v1
 */
#define DROIDDECK_REFRESH_V1_RATE_SINCE_VERSION 1
/**
 * @ingroup iface_droiddeck_refresh_v1
 */
#define DROIDDECK_REFRESH_V1_DONE_SINCE_VERSION 1

/**
 * @ingroup iface_droiddeck_refresh_v1
 */
#define DROIDDECK_REFRESH_V1_DESTROY_SINCE_VERSION 1
/**
 * @ingroup iface_droiddeck_refresh_v1
 */
#define DROIDDECK_REFRESH_V1_SET_TARGET_SINCE_VERSION 1

/**
 * @ingroup iface_droiddeck_refresh_v1
 * Sends an rate event to the client owning the resource.
 * @param resource_ The client's resource
 * @param refresh panel refresh rate in Hz
 */
static inline void
droiddeck_refresh_v1_send_rate(struct wl_resource *resource_, uint32_t refresh)
{
	wl_resource_post_event(resource_, DROIDDECK_REFRESH_V1_RATE, refresh);
}

/**
 * @ingroup iface_droiddeck_refresh_v1
 * Sends an done event to the client owning the resource.
 * @param resource_ The client's resource
 */
static inline void
droiddeck_refresh_v1_send_done(struct wl_resource *resource_)
{
	wl_resource_post_event(resource_, DROIDDECK_REFRESH_V1_DONE);
}

#ifdef  __cplusplus
}
#endif

#endif
