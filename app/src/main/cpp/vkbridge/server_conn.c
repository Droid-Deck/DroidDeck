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
#include <time.h>
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
                vkb_spvfix_device_destroy(t);
                vkb_dsmap_device_destroy(t);
                vkb_pcache_device_destroy(t);
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
        pthread_mutex_init(&p->olock, NULL);
        pthread_cond_init(&p->ocond, NULL);
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
        pthread_mutex_destroy(&p->olock);
        pthread_cond_destroy(&p->ocond);
        free(p);
    } else {
        pthread_mutex_lock(&p->olock);
        p->conn_closed = 1;
        pthread_cond_broadcast(&p->ocond);
        pthread_mutex_unlock(&p->olock);
    }
}

/* Waits until every async request of the process up to `barrier` has run. */
static void order_wait(vkb_srv_proc *p, uint64_t barrier)
{
    if (!p || !barrier) return;
    pthread_mutex_lock(&p->olock);
    int waited = 0;
    while (p->watermark < barrier) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 1;
        if (pthread_cond_timedwait(&p->ocond, &p->olock, &ts) != 0 && ++waited >= 3 && p->conn_closed) {
            /* A connection of this process is gone with requests it never sent: go on. */
            VKB_WARN("process %d: async request %llu never arrived; continuing", p->pid, (unsigned long long)(p->watermark + 1));
            p->watermark = barrier;
            memset(p->done, 0, sizeof(p->done));
            break;
        }
    }
    pthread_mutex_unlock(&p->olock);
}

static void order_done(vkb_srv_proc *p, uint64_t seq)
{
    if (!p || !seq) return;
    pthread_mutex_lock(&p->olock);
    if (seq > p->watermark) {
        p->done[seq % VKB_SEQ_RING] = 1;
        while (p->done[(p->watermark + 1) % VKB_SEQ_RING]) {
            p->done[(p->watermark + 1) % VKB_SEQ_RING] = 0;
            p->watermark++;
        }
        pthread_cond_broadcast(&p->ocond);
    }
    pthread_mutex_unlock(&p->olock);
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

/* ------------------------------------------------------------------ statistics */

/* VKBRIDGE_STATS=<seconds> on the server: time spent per command, waiting for order, idle. */
static uint64_t st_ns[VKB_CMD_COUNT], st_n[VKB_CMD_COUNT], st_wait_ns, st_last;
static int st_period = -1;

static uint64_t mono_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void stats_add(uint32_t cmd, uint64_t ns, uint64_t wait_ns)
{
    if (st_period < 0) {
        const char *v = getenv("VKBRIDGE_STATS");
        st_period = v ? atoi(v) : 0;
    }
    if (!st_period) return;
    __atomic_add_fetch(&st_ns[cmd], ns, __ATOMIC_RELAXED);
    __atomic_add_fetch(&st_n[cmd], 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&st_wait_ns, wait_ns, __ATOMIC_RELAXED);
    uint64_t now = mono_ns(), last = __atomic_load_n(&st_last, __ATOMIC_RELAXED);
    if (!last) {
        __atomic_store_n(&st_last, now, __ATOMIC_RELAXED);
        return;
    }
    if (now - last < (uint64_t)st_period * 1000000000ull || !__atomic_compare_exchange_n(&st_last, &last, now, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED))
        return;
    uint64_t total = 0;
    for (uint32_t i = 0; i < VKB_CMD_COUNT; i++) total += st_ns[i];
    VKB_INFO("stats over %ds: %.1f ms in handlers, %.1f ms waiting for order", st_period, total / 1e6, st_wait_ns / 1e6);
    for (int k = 0; k < 8; k++) {
        uint64_t best = 0;
        uint32_t bi = 0;
        for (uint32_t i = 0; i < VKB_CMD_COUNT; i++)
            if (st_ns[i] > best) {
                best = st_ns[i];
                bi = i;
            }
        if (!best) break;
        VKB_INFO("  %-40s %8llu calls %9.2f ms (%.2f us each)", vkb_cmd_names[bi], (unsigned long long)st_n[bi], best / 1e6,
                 best / 1e3 / (double)st_n[bi]);
        st_ns[bi] = 0;
    }
    memset(st_ns, 0, sizeof(st_ns));
    memset(st_n, 0, sizeof(st_n));
    st_wait_ns = 0;
}

/* ------------------------------------------------------------------ connection loop */

/* Reads the next request. Buffered: a burst of small asynchronous requests costs one recvmsg, not
 * two reads each. Descriptors arrive with the first bytes of their message, so they queue up in
 * order and each header takes its nfds from the front. The payload points into the buffer and
 * stays valid until the next call. */
static int srv_recv(vkb_srv_conn *conn, vkb_msg_hdr *h, uint8_t **payload, int *fds, int *nfds)
{
    *nfds = 0;
    if (conn->rpos == conn->rlen && conn->rcap > (16u << 20)) {
        free(conn->rb);
        conn->rb = NULL;
        conn->rcap = conn->rpos = conn->rlen = 0;
    }
    for (;;) {
        size_t avail = conn->rlen - conn->rpos;
        size_t need = sizeof(*h);
        if (avail >= sizeof(*h)) {
            memcpy(h, conn->rb + conn->rpos, sizeof(*h));
            if (h->magic != VKB_MAGIC) {
                VKB_ERR("transport: bad message magic 0x%08x", h->magic);
                return -1;
            }
            if (h->size > (1u << 30) || h->nfds > VKB_MAX_FDS) {
                VKB_ERR("transport: bad message (%u bytes, %u descriptors)", h->size, h->nfds);
                return -1;
            }
            need += h->size;
            if (avail >= need) {
                if ((int)h->nfds > conn->fdn) {
                    VKB_ERR("transport: message arrived without its %u descriptors", h->nfds);
                    return -1;
                }
                *nfds = (int)h->nfds;
                memcpy(fds, conn->fdq, sizeof(int) * h->nfds);
                conn->fdn -= (int)h->nfds;
                memmove(conn->fdq, conn->fdq + h->nfds, sizeof(int) * conn->fdn);
                *payload = conn->rb + conn->rpos + sizeof(*h);
                conn->rpos += need;
                return 0;
            }
        }
        if (conn->rpos) {
            memmove(conn->rb, conn->rb + conn->rpos, avail);
            conn->rpos = 0;
            conn->rlen = avail;
        }
        if (need < (256u << 10)) need = 256u << 10;
        if (need > conn->rcap) {
            size_t nc = conn->rcap ? conn->rcap : (256u << 10);
            while (nc < need) nc *= 2;
            uint8_t *nb = realloc(conn->rb, nc);
            if (!nb) return -1;
            conn->rb = nb;
            conn->rcap = nc;
        }
        union {
            char buf[CMSG_SPACE(sizeof(int) * VKB_MAX_FDS)];
            struct cmsghdr align;
        } cm;
        struct iovec iov = {conn->rb + conn->rlen, conn->rcap - conn->rlen};
        struct msghdr msg;
        memset(&msg, 0, sizeof(msg));
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cm.buf;
        msg.msg_controllen = sizeof(cm.buf);
        ssize_t r;
        do {
            r = recvmsg(conn->sock, &msg, MSG_CMSG_CLOEXEC);
        } while (r < 0 && errno == EINTR);
        if (r <= 0) return -1;
        for (struct cmsghdr *cmh = CMSG_FIRSTHDR(&msg); cmh; cmh = CMSG_NXTHDR(&msg, cmh)) {
            if (cmh->cmsg_level != SOL_SOCKET || cmh->cmsg_type != SCM_RIGHTS) continue;
            int n = (int)((cmh->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < n; i++) {
                int fd;
                memcpy(&fd, CMSG_DATA(cmh) + i * sizeof(int), sizeof(int));
                if (conn->fdn < (int)(sizeof(conn->fdq) / sizeof(conn->fdq[0]))) conn->fdq[conn->fdn++] = fd;
                else close(fd);
            }
        }
        if (msg.msg_flags & MSG_CTRUNC) VKB_WARN("transport: descriptors truncated");
        conn->rlen += (size_t)r;
    }
}

static void *conn_main(void *arg)
{
    vkb_srv_conn *conn = arg;
    vkb_srv_call c;
    vkb_enc_init(&c.r);
    int fds[VKB_MAX_FDS];
    for (;;) {
        vkb_msg_hdr h;
        int nfds = 0;
        uint8_t *payload;
        if (srv_recv(conn, &h, &payload, fds, &nfds) < 0) break;
        vkb_enc_reset(&c.r);
        vkb_dec_init(&c.d, payload, h.size, &conn->arena, fds, nfds);
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
                uint64_t t0 = st_period ? mono_ns() : 0;
                order_wait(conn->proc, h.barrier);
                uint64_t t1 = st_period ? mono_ns() : 0;
                vkb_srv_set_current_table(c.table);
                if (vkb_verbose) VKB_DBG("-> %s", vkb_cmd_names[h.cmd]);
                vkb_srv_current_cmd = vkb_cmd_names[h.cmd];
                vkb_srv_handlers[h.cmd](&c);
                vkb_srv_current_cmd = NULL;
                vkb_srv_set_current_table(NULL);
                if (st_period) stats_add(h.cmd, mono_ns() - t1, t1 - t0);
            }
        }
        vkb_dec_close_untaken(&c.d);
        order_done(conn->proc, h.seq);
        if (!(h.flags & VKB_F_NOREPLY)) {
            vkb_msg_hdr rh = {VKB_MAGIC, c.status == VKB_ST_OK ? (uint32_t)c.r.len : 0, c.status, 0,
                              c.status == VKB_ST_OK ? (uint32_t)c.r.nfds : 0, 0, 0, 0};
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
        } else if (vkb_cmd_result[h.cmd] && c.r.len >= sizeof(int32_t)) {
            /* The client already told the app VK_SUCCESS; a failure can only be reported here. */
            int32_t vr;
            memcpy(&vr, c.r.buf, sizeof(vr));
            if (vr < 0) VKB_ERR("asynchronous %s returned %d", vkb_cmd_names[h.cmd], vr);
        }
        for (int i = 0; i < c.nclose; i++) close(c.close_after[i]);
        vkb_arena_reset(&conn->arena);
    }
    close(conn->sock);
    vkb_enc_free(&c.r);
    free(conn->rb);
    for (int i = 0; i < conn->fdn; i++) close(conn->fdq[i]);
    vkb_arena_free(&conn->arena);
    if (conn->proc) proc_leave(conn->proc);
    free(conn);
    return NULL;
}

void vkb_srv_serve(int lfd)
{
    stats_add(0, 0, 0);
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
