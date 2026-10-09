/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Client transport: one Unix-socket connection per thread (the server mirrors it with one thread),
 * so calls from different threads run in parallel exactly as they would on a local driver.
 */
#define _GNU_SOURCE
#include "vkb_client.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------ logging */

static int log_level_v = -1;
static FILE *log_file;
static pthread_mutex_t log_lock = PTHREAD_MUTEX_INITIALIZER;

int vkb_log_level(void)
{
    if (log_level_v < 0) {
        const char *d = getenv("VKBRIDGE_DEBUG");
        log_level_v = d && *d && strcmp(d, "0") ? VKB_LOG_DEBUG : VKB_LOG_WARN;
        const char *v = getenv("VKBRIDGE_LOG_LEVEL");
        if (v) log_level_v = atoi(v);
        const char *f = getenv("VKBRIDGE_CLIENT_LOG");
        if (f && *f) log_file = fopen(f, "ae");
    }
    return log_level_v;
}

void vkb_log(int level, const char *fmt, ...)
{
    if (level > vkb_log_level()) return;
    char msg[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    static const char *const tags[] = {"error", "warning", "info", "debug"};
    char comm[32] = "?";
    FILE *cf = fopen("/proc/self/comm", "re");
    if (cf) {
        if (fgets(comm, sizeof(comm), cf)) comm[strcspn(comm, "\n")] = 0;
        fclose(cf);
    }
    pthread_mutex_lock(&log_lock);
    fprintf(stderr, "vkbridge %s [%s %d]: %s\n", tags[level & 3], comm, getpid(), msg);
    if (log_file) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        fprintf(log_file, "%02d:%02d:%02d.%03ld vkbridge-client %s [%s %d]: %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
                ts.tv_nsec / 1000000, tags[level & 3], comm, getpid(), msg);
        fflush(log_file);
    }
    pthread_mutex_unlock(&log_lock);
}

/* ------------------------------------------------------------------ connection */

typedef struct vkb_conn {
    int sock;
    int dead;
    uint32_t epoch;
    vkb_enc enc;            /* reused request buffer */
    uint8_t *in;
    size_t incap;
    vkb_arena arena;
    int busy;
} vkb_conn;

static uint64_t process_token;
static uint32_t fork_epoch;
static pthread_key_t conn_key;
static pthread_once_t conn_once = PTHREAD_ONCE_INIT;
static __thread vkb_conn *tls_conn;

static void conn_free(vkb_conn *c)
{
    if (!c) return;
    if (c->sock >= 0) close(c->sock);
    vkb_enc_free(&c->enc);
    free(c->in);
    vkb_arena_free(&c->arena);
    free(c);
}

static void conn_thread_exit(void *p)
{
    conn_free(p);
}

static void atfork_child(void)
{
    /* The child must not talk on the parent's sockets: new token, new connections. */
    fork_epoch++;
    process_token = 0;
    tls_conn = NULL; /* the old one is leaked deliberately: its socket belongs to the parent too */
}

static void conn_init_once(void)
{
    pthread_key_create(&conn_key, conn_thread_exit);
    pthread_atfork(NULL, NULL, atfork_child);
}

void vkb_conn_after_fork(void) { atfork_child(); }

static uint64_t make_token(void)
{
    uint64_t t = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (read(fd, &t, sizeof(t)) != sizeof(t)) t = 0;
        close(fd);
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    t ^= ((uint64_t)getpid() << 32) ^ (uint64_t)ts.tv_nsec ^ (uint64_t)ts.tv_sec * 1000003u;
    return t ? t : 1;
}

static const char *socket_path(void)
{
    const char *p = getenv(VKB_DEFAULT_SOCKET_ENV);
    if (p && *p) return p;
    static char buf[256];
    const char *rd = getenv("XDG_RUNTIME_DIR");
    snprintf(buf, sizeof(buf), "%s/vkbridge.sock", rd && *rd ? rd : "/tmp");
    return buf;
}

static int do_connect(vkb_conn *c)
{
    const char *path = socket_path();
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(a.sun_path)) {
        VKB_ERR("socket path too long: %s", path);
        return -1;
    }
    strcpy(a.sun_path, path);
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    if (connect(s, (struct sockaddr *)&a, sizeof(a)) < 0) {
        VKB_ONCE(VKB_LOG_ERROR, "cannot reach the Vulkan bridge server at %s: %s", path, strerror(errno));
        close(s);
        return -1;
    }
    c->sock = s;
    /* Hello: protocol check and which process this thread belongs to. */
    if (!process_token) process_token = make_token();
    vkb_enc e;
    vkb_enc_init(&e);
    vkb_enc_u64(&e, VKB_PROTOCOL_HASH);
    vkb_enc_u64(&e, process_token);
    vkb_enc_u32(&e, (uint32_t)getpid());
    char comm[64] = "?";
    FILE *f = fopen("/proc/self/comm", "re");
    if (f) {
        if (fgets(comm, sizeof(comm), f)) comm[strcspn(comm, "\n")] = 0;
        fclose(f);
    }
    vkb_enc_str(&e, comm);
    vkb_msg_hdr h = {VKB_MAGIC, (uint32_t)e.len, VKB_CMD_vkbHello, 0, 0, 0};
    int ok = vkb_send_msg(s, &h, e.buf, NULL, 0) == 0;
    vkb_enc_free(&e);
    int fds[VKB_MAX_FDS], nfds = 0;
    vkb_msg_hdr rh;
    if (ok) ok = vkb_recv_msg(s, &rh, &c->in, &c->incap, fds, &nfds) == 0 && rh.cmd == VKB_ST_OK && rh.size >= 12;
    if (ok) {
        uint64_t sh;
        uint32_t accepted;
        memcpy(&sh, c->in, 8);
        memcpy(&accepted, c->in + 8, 4);
        if (!accepted) {
            VKB_ONCE(VKB_LOG_ERROR, "the bridge server speaks protocol %016llx, this driver %016llx; update DroidDeck's Linux side",
                     (unsigned long long)sh, (unsigned long long)VKB_PROTOCOL_HASH);
            ok = 0;
        }
    }
    if (!ok) {
        close(s);
        c->sock = -1;
        return -1;
    }
    return 0;
}

static vkb_conn *get_conn(void)
{
    pthread_once(&conn_once, conn_init_once);
    vkb_conn *c = tls_conn;
    if (c && c->epoch != fork_epoch) c = tls_conn = NULL;
    if (c) return c->dead ? NULL : c;
    c = calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->sock = -1;
    c->epoch = fork_epoch;
    vkb_enc_init(&c->enc);
    if (do_connect(c) < 0) {
        c->dead = 1;
    }
    tls_conn = c;
    pthread_setspecific(conn_key, c);
    return c->dead ? NULL : c;
}

int vkb_connected(void)
{
    return get_conn() != NULL;
}

void vkb_call_begin(vkb_call *c, uint32_t cmd, uint32_t table)
{
    memset(c, 0, sizeof(*c));
    c->cmd = cmd;
    c->table = table;
    c->conn = get_conn();
    if (c->conn && !c->conn->busy) {
        c->conn->busy = 1;
        c->e = c->conn->enc;
        vkb_enc_reset(&c->e);
    } else {
        /* No connection, or a nested call: encode into a private buffer. */
        if (c->conn && c->conn->busy) VKB_ERR("internal: nested bridge call (%s)", vkb_cmd_names[cmd]);
        vkb_enc_init(&c->e);
        c->flags |= 0x80000000u;
    }
}

static void release_enc(vkb_call *c)
{
    if (c->flags & 0x80000000u) {
        vkb_enc_free(&c->e);
    } else if (c->conn) {
        c->conn->enc = c->e;
        c->conn->busy = 0;
        /* Don't keep a huge buffer around after one big message. */
        if (c->conn->enc.cap > (16u << 20)) {
            vkb_enc_free(&c->conn->enc);
            vkb_enc_init(&c->conn->enc);
        }
    }
}

static int trace_on(void)
{
    static int v = -1;
    if (v < 0) v = getenv("VKBRIDGE_TRACE") && strcmp(getenv("VKBRIDGE_TRACE"), "0");
    return v;
}

int vkb_call_exec(vkb_call *c)
{
    vkb_conn *conn = c->conn;
    if (trace_on()) {
        /* VkResult-returning calls put it first in the reply; peeked after the round trip. */
        vkb_log(VKB_LOG_ERROR, "trace: %s", vkb_cmd_names[c->cmd]);
    }
    if (!conn || conn->dead || (c->flags & 0x80000000u)) {
        release_enc(c);
        c->conn = NULL;
        return 0;
    }
    if (c->e.oom) {
        VKB_ERR("%s: request could not be encoded", vkb_cmd_names[c->cmd]);
        release_enc(c);
        c->conn = NULL;
        return 0;
    }
    vkb_msg_hdr h = {VKB_MAGIC, (uint32_t)c->e.len, c->cmd, c->table, (uint32_t)c->e.nfds, c->flags & VKB_F_NOREPLY};
    if (vkb_send_msg(conn->sock, &h, c->e.buf, c->e.fds, c->e.nfds) < 0) {
        VKB_ERR("%s: lost the bridge server (%s)", vkb_cmd_names[c->cmd], strerror(errno));
        conn->dead = 1;
        release_enc(c);
        c->conn = NULL;
        return 0;
    }
    release_enc(c);
    if (c->flags & VKB_F_NOREPLY) {
        c->conn = NULL;
        return 1;
    }
    int fds[VKB_MAX_FDS], nfds = 0;
    vkb_msg_hdr rh;
    if (vkb_recv_msg(conn->sock, &rh, &conn->in, &conn->incap, fds, &nfds) < 0) {
        VKB_ERR("%s: lost the bridge server while waiting for the reply", vkb_cmd_names[c->cmd]);
        conn->dead = 1;
        c->conn = NULL;
        return 0;
    }
    if (rh.cmd != VKB_ST_OK) {
        static const char *const why[] = {"ok", "malformed request", "the driver lacks this command", "unknown object"};
        VKB_ERR("%s: the server refused the call (%s)", vkb_cmd_names[c->cmd], rh.cmd < 4 ? why[rh.cmd] : "?");
        for (int i = 0; i < nfds; i++) close(fds[i]);
        c->conn = NULL;
        return 0;
    }
    /* The decoder keeps fds in a per-call array. */
    static __thread int call_fds[VKB_MAX_FDS];
    memcpy(call_fds, fds, sizeof(int) * (size_t)nfds);
    vkb_dec_init(&c->d, conn->in, rh.size, &conn->arena, call_fds, nfds);
    return 1;
}

void vkb_call_end(vkb_call *c)
{
    if (!c->conn) return;
    if (c->d.err) VKB_ERR("%s: malformed reply", vkb_cmd_names[c->cmd]);
    vkb_dec_close_untaken(&c->d);
    vkb_arena_reset(&c->conn->arena);
    c->conn = NULL;
}

uint32_t vkb_table_of(const void *h)
{
    return h ? ((const vkb_object *)h)->table : 0;
}

uint64_t vkb_remote(const void *h)
{
    return h ? ((const vkb_object *)h)->remote : 0;
}

int vkb_memfd(const char *name, size_t size)
{
    int fd = (int)syscall(SYS_memfd_create, name, 1u /* MFD_CLOEXEC */);
    if (fd < 0) {
        /* No memfd (old kernel or a filter): an unlinked file in the runtime dir. */
        char path[256];
        const char *rd = getenv("XDG_RUNTIME_DIR");
        snprintf(path, sizeof(path), "%s/vkbridge-XXXXXX", rd && *rd ? rd : "/tmp");
        fd = mkostemp(path, O_CLOEXEC);
        if (fd < 0) return -1;
        unlink(path);
    }
    if (ftruncate(fd, (off_t)size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* ------------------------------------------------------------------ hash map */

void vkb_map_init(vkb_map *m)
{
    memset(m, 0, sizeof(*m));
    pthread_mutex_init(&m->lock, NULL);
}

void vkb_map_destroy(vkb_map *m)
{
    free(m->keys);
    free(m->vals);
    pthread_mutex_destroy(&m->lock);
    memset(m, 0, sizeof(*m));
}

static uint32_t hash64(uint64_t k)
{
    k ^= k >> 33;
    k *= 0xff51afd7ed558ccdull;
    k ^= k >> 33;
    return (uint32_t)k;
}

/* Keys are never 0 (VK_NULL_HANDLE); 0 marks an empty slot, ~0 a deleted one. */
#define TOMB (~(uint64_t)0)

static void map_rehash(vkb_map *m, uint32_t ncap)
{
    uint64_t *ok = m->keys;
    void **ov = m->vals;
    uint32_t oc = m->cap;
    m->keys = calloc(ncap, sizeof(uint64_t));
    m->vals = calloc(ncap, sizeof(void *));
    m->cap = ncap;
    m->count = 0;
    for (uint32_t i = 0; i < oc; i++) {
        if (ok[i] && ok[i] != TOMB) {
            uint32_t j = hash64(ok[i]) & (ncap - 1);
            while (m->keys[j]) j = (j + 1) & (ncap - 1);
            m->keys[j] = ok[i];
            m->vals[j] = ov[i];
            m->count++;
        }
    }
    free(ok);
    free(ov);
}

void vkb_map_put(vkb_map *m, uint64_t k, void *v)
{
    if (!k) return;
    pthread_mutex_lock(&m->lock);
    if (!m->cap || (m->count + 1) * 2 > m->cap) map_rehash(m, m->cap ? m->cap * 2 : 64);
    uint32_t j = hash64(k) & (m->cap - 1);
    int64_t tomb = -1;
    while (m->keys[j]) {
        if (m->keys[j] == k) {
            m->vals[j] = v;
            pthread_mutex_unlock(&m->lock);
            return;
        }
        if (m->keys[j] == TOMB && tomb < 0) tomb = j;
        j = (j + 1) & (m->cap - 1);
    }
    if (tomb >= 0) j = (uint32_t)tomb;
    m->keys[j] = k;
    m->vals[j] = v;
    m->count++;
    pthread_mutex_unlock(&m->lock);
}

static int64_t map_find(vkb_map *m, uint64_t k)
{
    if (!m->cap || !k) return -1;
    uint32_t j = hash64(k) & (m->cap - 1);
    for (uint32_t n = 0; n < m->cap && m->keys[j]; n++) {
        if (m->keys[j] == k) return j;
        j = (j + 1) & (m->cap - 1);
    }
    return -1;
}

void *vkb_map_get(vkb_map *m, uint64_t k)
{
    pthread_mutex_lock(&m->lock);
    int64_t j = map_find(m, k);
    void *v = j >= 0 ? m->vals[j] : NULL;
    pthread_mutex_unlock(&m->lock);
    return v;
}

void *vkb_map_remove(vkb_map *m, uint64_t k)
{
    pthread_mutex_lock(&m->lock);
    int64_t j = map_find(m, k);
    void *v = NULL;
    if (j >= 0) {
        v = m->vals[j];
        m->keys[j] = TOMB;
        m->vals[j] = NULL;
        /* count keeps tombstones, so the table rehashes before it fills with them */
    }
    pthread_mutex_unlock(&m->lock);
    return v;
}
