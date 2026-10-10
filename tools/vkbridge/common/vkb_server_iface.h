/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * What the generated server handlers need from the server: the per-call context.
 */
#ifndef VKB_SERVER_IFACE_H
#define VKB_SERVER_IFACE_H

#include "vkb_wire.h"

struct vkb_srv_table;
struct vkb_srv_conn;

struct vkb_srv_call {
    vkb_dec d;                     /* request */
    vkb_enc r;                     /* reply payload */
    vkb_dispatch *dt;              /* dispatch for the table named in the request */
    struct vkb_srv_table *table;   /* NULL for global commands */
    struct vkb_srv_conn *conn;
    uint32_t status;               /* reply status (VKB_ST_*) */
    int close_after[VKB_MAX_FDS];  /* server fds to close once the reply is sent */
    int nclose;
    int in_stream;                 /* replaying a command buffer stream */
};

void vkb_srv_bad_message(struct vkb_srv_call *c);
void vkb_srv_missing(struct vkb_srv_call *c, const char *name);
void vkb_srv_close_after_send(struct vkb_srv_call *c, int fd);

#endif
