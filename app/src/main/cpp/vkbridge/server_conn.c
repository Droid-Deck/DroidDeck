/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Connections: one thread per client thread, requests executed in order, replies sent back.
 * A command buffer's recorded commands arrive in one message at vkEndCommandBuffer and are
 * replayed here.
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static pthread_mutex_t tables_lock = PTHREAD_MUTEX_INITIALIZER;
static vkb_srv_table *tables[VKB_MAX_TABLES];
static uint32_t next_table_id = 1;

static pthread_mutex_t procs_lock = PTHREAD_MUTEX_INITIALIZER;
static vkb_srv_proc *procs;

static __thread vkb_srv_table *tls_table;
__thread const char *vkb_srv_current_cmd;

vkb_srv_table *vkb_srv_current_table(void) { return tls_table; }
void vkb_srv_set_current_table(vkb_srv_table *t) { tls_table = t; }

/* Server side of the generated encoders' helpers: handles are already the server's values. */
uint64_t vkb_remote(const void *h) { return (uint64_t)(uintptr_t)h; }
int vkb_cb_begin_is_secondary(const VkCommandBufferBeginInfo *s) { (void)s; return 1; }

uint32_t vkb_table_register(vkb_srv_table *t)
{
    pthread_mutex_lock(&tables_lock);
    uint32_t id = 0;
    for (uint32_t tries = 0; tries < VKB_MAX_TABLES; tries++) {
        uint32_t cand = next_table_id++;
        if (next_table_id >= VKB_MAX_TABLES) next_table_id = 1;
        if (!tables[cand]) {
            id = cand;
            break;
        }
    }
    if (id) {
        t->id = id;
        tables[id] = t;
    }
    pthread_mutex_unlock(&tables_lock);
    return id;
}

void vkb_table_unregister(vkb_srv_table *t)
{
    pthread_mutex_lock(&tables_lock);
    if (t->id && t->id < VKB_MAX_TABLES && tables[t->id] == t) tables[t->id] = NULL;
    pthread_mutex_unlock(&tables_lock);
}

vkb_srv_table *vkb_table_get(uint32_t id)
{
    if (!id || id >= VKB_MAX_TABLES) return NULL;
    pthread_mutex_lock(&tables_lock);
    vkb_srv_table *t = tables[id];
    pthread_mutex_unlock(&tables_lock);
    return t;
}

void vkb_srv_reply_status(vkb_srv_call *c, uint32_t status)
{
    c->status = status;
}

void vkb_srv_bad_message(vkb_srv_call *c)
{
    VKB_ERR("malformed request");
    c->status = VKB_ST_BAD_MESSAGE;
}

void vkb_srv_missing(vkb_srv_call *c, const char *name)
{
    VKB_ERR("the driver has no %s", name);
    c->status = VKB_ST_MISSING;
}

void vkb_srv_close_after_send(vkb_srv_call *c, int fd)
{
    if (c->nclose < VKB_MAX_FDS) c->close_after[c->nclose++] = fd;
}

/* ------------------------------------------------------------------ process cleanup */

void vkb_proc_cleanup(vkb_srv_proc *p)
{
    /* Devices first, then their instances. */
    for (int pass = 0; pass < 2; pass++) {
        for (uint32_t i = 1; i < VKB_MAX_TABLES; i++) {
            pthread_mutex_lock(&tables_lock);
            vkb_srv_table *t = tables[i];
            int mine = t && t->proc == p && t->is_device == (pass == 0);
            if (mine) tables[i] = NULL;
            pthread_mutex_unlock(&tables_lock);
            if (!mine) continue;
            if (t->is_device) {
                VKB_INFO("process %d exited without destroying device %u; cleaning up", p->pid, t->id);
                if (t->real.vkDeviceWaitIdle) t->real.vkDeviceWaitIdle(t->device);
                vkb_emu_device_destroy(t);
                vkb_mem_free_all(t);
                t->real.vkDestroyDevice(t->device, NULL);
            } else {
                VKB_INFO("process %d exited without destroying instance %u; cleaning up", p->pid, t->id);
                t->real.vkDestroyInstance(t->instance, NULL);
            }
            pthread_mutex_destroy(&t->lock);
            free(t);
        }
    }
}

static vkb_srv_proc *proc_join(uint64_t token, int pid)
{
    pthread_mutex_lock(&procs_lock);
    vkb_srv_proc *p = procs;
    while (p && p->token != token) p = p->next;
    if (!p) {
        p = calloc(1, sizeof(*p));
        p->token = token;
        p->pid = pid;
        p->next = procs;
        procs = p;
        VKB_INFO("client process %d connected", pid);
    }
    p->conns++;
    pthread_mutex_unlock(&procs_lock);
    return p;
}

static void proc_leave(vkb_srv_proc *p)
{
    pthread_mutex_lock(&procs_lock);
    int last = --p->conns == 0;
    if (last) {
        vkb_srv_proc **pp = &procs;
        while (*pp && *pp != p) pp = &(*pp)->next;
        if (*pp) *pp = p->next;
    }
    pthread_mutex_unlock(&procs_lock);
    if (last) {
        vkb_proc_cleanup(p);
        VKB_INFO("client process %d gone", p->pid);
        free(p);
    }
}

/* ------------------------------------------------------------------ command buffer replay */

static int is_stream_cmd(uint32_t id)
{
    const char *n = vkb_cmd_names[id];
    return !strncmp(n, "vkCmd", 5) || id == VKB_CMD_vkBeginCommandBuffer || id == VKB_CMD_vkbPushDescRaw;
}

void vkb_sv_vkEndCommandBuffer(vkb_srv_call *c)
{
    VkCommandBuffer cb = (VkCommandBuffer)(uintptr_t)vkb_dec_u64(&c->d);
    size_t len = 0;
    const uint8_t *s = vkb_dec_blob(&c->d, &len);
    if (c->d.err) {
        vkb_srv_bad_message(c);
        return;
    }
    VkResult begin_r = VK_SUCCESS;
    size_t pos = 0;
    vkb_enc scratch;
    vkb_enc_init(&scratch);
    uint32_t ncmds = 0;
    while (s && pos + 8 <= len) {
        uint32_t id, n;
        memcpy(&id, s + pos, 4);
        memcpy(&n, s + pos + 4, 4);
        pos += 8;
        if (id >= VKB_CMD_COUNT || !is_stream_cmd(id) || n > len - pos) {
            VKB_ERR("command stream: bad entry (id %u, %u bytes) after %u commands", id, n, ncmds);
            c->status = VKB_ST_BAD_MESSAGE;
            vkb_enc_free(&scratch);
            return;
        }
        vkb_srv_call sub;
        memset(&sub, 0, sizeof(sub));
        vkb_dec_init(&sub.d, s + pos, n, c->d.arena, NULL, 0);
        sub.r = scratch;
        vkb_enc_reset(&sub.r);
        sub.dt = c->dt;
        sub.table = c->table;
        sub.conn = c->conn;
        sub.in_stream = 1;
        vkb_srv_current_cmd = vkb_cmd_names[id];
        vkb_srv_handlers[id](&sub);
        vkb_srv_current_cmd = vkb_cmd_names[VKB_CMD_vkEndCommandBuffer];
        scratch = sub.r;
        if (sub.status != VKB_ST_OK) {
            VKB_ERR("command stream: %s failed (status %u)", vkb_cmd_names[id], sub.status);
        }
        if (id == VKB_CMD_vkBeginCommandBuffer && scratch.len >= 4) memcpy(&begin_r, scratch.buf, 4);
        pos += (n + 7) & ~(size_t)7;
        ncmds++;
    }
    vkb_enc_free(&scratch);
    VkResult r = c->dt->vkEndCommandBuffer(cb);
    if (begin_r != VK_SUCCESS) r = begin_r;
    if (vkb_verbose) VKB_DBG("command buffer %p: %u commands, %zu bytes -> %d", (void *)cb, ncmds, len, r);
    vkb_enc_u32(&c->r, (uint32_t)r);
}

/* ------------------------------------------------------------------ hello */

void vkb_sv_vkbHello(vkb_srv_call *c)
{
    uint64_t hash = vkb_dec_u64(&c->d);
    uint64_t token = vkb_dec_u64(&c->d);
    uint32_t pid = vkb_dec_u32(&c->d);
    const char *comm = vkb_dec_str(&c->d);
    if (c->d.err) {
        vkb_srv_bad_message(c);
        return;
    }
    vkb_enc_u64(&c->r, VKB_PROTOCOL_HASH);
    if (hash != VKB_PROTOCOL_HASH) {
        VKB_ERR("client %d (%s) speaks protocol %016llx, this server %016llx: refusing", pid,
                comm ? comm : "?", (unsigned long long)hash, (unsigned long long)VKB_PROTOCOL_HASH);
        vkb_enc_u32(&c->r, 0);
        return;
    }
    if (!c->conn->proc) {
        c->conn->proc = proc_join(token, (int)pid);
        if (c->conn->proc->conns == 1) VKB_INFO("process %u is %s", pid, comm ? comm : "?");
    }
    vkb_enc_u32(&c->r, 1);
}

/* ------------------------------------------------------------------ connection loop */

static void *conn_main(void *arg)
{
    vkb_srv_conn *conn = arg;
    vkb_srv_call c;
    vkb_enc_init(&c.r);
    int fds[VKB_MAX_FDS];
    for (;;) {
        vkb_msg_hdr h;
        int nfds = 0;
        if (vkb_recv_msg(conn->sock, &h, &conn->inbuf, &conn->incap, fds, &nfds) < 0) break;
        vkb_enc_reset(&c.r);
        vkb_dec_init(&c.d, conn->inbuf, h.size, &conn->arena, fds, nfds);
        c.conn = conn;
        c.status = VKB_ST_OK;
        c.nclose = 0;
        c.in_stream = 0;
        c.table = NULL;
        c.dt = &vkb_global_dt;
        if (h.cmd >= VKB_CMD_COUNT) {
            VKB_ERR("unknown command id %u", h.cmd);
            c.status = VKB_ST_BAD_MESSAGE;
        } else if (!conn->proc && h.cmd != VKB_CMD_vkbHello) {
            VKB_ERR("%s before hello", vkb_cmd_names[h.cmd]);
            c.status = VKB_ST_BAD_MESSAGE;
        } else {
            if (h.table) {
                c.table = vkb_table_get(h.table);
                if (!c.table || c.table->proc != conn->proc) {
                    VKB_ERR("%s: unknown dispatch table %u", vkb_cmd_names[h.cmd], h.table);
                    c.status = VKB_ST_BAD_TABLE;
                } else {
                    c.dt = &c.table->dt;
                }
            }
            if (c.status == VKB_ST_OK) {
                vkb_srv_set_current_table(c.table);
                if (vkb_verbose) VKB_DBG("-> %s", vkb_cmd_names[h.cmd]);
                vkb_srv_current_cmd = vkb_cmd_names[h.cmd];
                vkb_srv_handlers[h.cmd](&c);
                vkb_srv_current_cmd = NULL;
                vkb_srv_set_current_table(NULL);
            }
        }
        vkb_dec_close_untaken(&c.d);
        if (!(h.flags & VKB_F_NOREPLY)) {
            vkb_msg_hdr rh = {VKB_MAGIC, c.status == VKB_ST_OK ? (uint32_t)c.r.len : 0, c.status, 0,
                              c.status == VKB_ST_OK ? (uint32_t)c.r.nfds : 0, 0};
            if (c.r.oom) {
                rh.cmd = VKB_ST_BAD_MESSAGE;
                rh.size = 0;
                rh.nfds = 0;
            }
            if (vkb_send_msg(conn->sock, &rh, c.r.buf, c.r.fds, (int)rh.nfds) < 0) {
                for (int i = 0; i < c.nclose; i++) close(c.close_after[i]);
                break;
            }
        } else if (c.status != VKB_ST_OK) {
            VKB_ERR("asynchronous %s failed (status %u)", vkb_cmd_names[h.cmd], c.status);
        }
        for (int i = 0; i < c.nclose; i++) close(c.close_after[i]);
        vkb_arena_reset(&conn->arena);
    }
    close(conn->sock);
    vkb_enc_free(&c.r);
    free(conn->inbuf);
    vkb_arena_free(&conn->arena);
    if (conn->proc) proc_leave(conn->proc);
    free(conn);
    return NULL;
}

void vkb_srv_serve(int lfd)
{
    for (;;) {
        int s = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
        if (s < 0) {
            if (errno == EINTR || errno == ECONNABORTED) continue;
            VKB_ERR("accept: %s", strerror(errno));
            continue;
        }
        vkb_srv_conn *conn = calloc(1, sizeof(*conn));
        conn->sock = s;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        /* Driver calls (pipeline compiles) can use a lot of stack. */
        pthread_attr_setstacksize(&attr, 4u << 20);
        if (pthread_create(&conn->thread, &attr, conn_main, conn) != 0) {
            VKB_ERR("cannot start a connection thread");
            close(s);
            free(conn);
        }
        pthread_attr_destroy(&attr);
    }
}
