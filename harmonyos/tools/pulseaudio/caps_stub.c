/*
 * The capabilities handling the port does not need.
 *
 * PulseAudio's caps.c uses libcap to drop root and the capabilities of a
 * system-wide daemon. musl has no libcap, and the port runs the server as
 * the app's own user, so there is nothing to drop: the two functions are
 * here so the daemon links.
 */

#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

#include "caps.h"

void pa_drop_root(void) {
}

void pa_drop_caps(void) {
}
