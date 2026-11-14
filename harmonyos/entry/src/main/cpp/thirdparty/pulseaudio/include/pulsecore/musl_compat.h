/*
 * The declarations musl does not have.
 *
 * PulseAudio calls gethostid() when the system has no hostname, to build a
 * session identifier. musl does not implement it — it is a legacy BSD
 * function that glibc kept — so the port provides it.
 *
 * This header is force-included into every translation unit of the build, so
 * the declaration is visible wherever PulseAudio calls the function.
 */
#ifndef DROIDDECK_MUSL_COMPAT_H
#define DROIDDECK_MUSL_COMPAT_H

long gethostid(void);

#endif
