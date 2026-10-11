/* SPDX-License-Identifier: GPL-3.0-or-later
 * Minimal Xlib declarations for the bridge ICD: it never links libX11, it only needs the types
 * vulkan_xlib.h names and looks up XGetXCBConnection at run time. */
#ifndef VKB_COMPAT_XLIB_H
#define VKB_COMPAT_XLIB_H
typedef struct _XDisplay Display;
typedef unsigned long XID;
typedef XID Window;
typedef unsigned long VisualID;
#endif
