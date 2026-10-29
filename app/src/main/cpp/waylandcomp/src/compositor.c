#define _GNU_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdint.h>
#include <stdatomic.h>
#include <fcntl.h>
#include <time.h>
#include <stdarg.h>
#include <errno.h>
#include <poll.h>
#include <sys/timerfd.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <wayland-server.h>

#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-v1-server-protocol.h"
#include "sc_layer.h"
#include "ahb_swapchain.h"
#include "viewporter-server-protocol.h"
#include "banner-desktop-v1-server-protocol.h"
#include "presentation-time-server-protocol.h"
#include "pointer-constraints-unstable-v1-server-protocol.h"
#include "relative-pointer-unstable-v1-server-protocol.h"
#include "vk_present.h"
#include "framegen_bridge.h"
#include <pthread.h>
#include "effects_chain.h"
#include "cardboard.h"
#include "droiddeck_ext.h"
#include "droiddeck_color.h"
#include "compositor_internal.h"

#define WLOGI(...) __android_log_print(ANDROID_LOG_INFO, "DroidDeckWayland", __VA_ARGS__)
#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DroidDeckWayland", __VA_ARGS__)


#define SESSION_LOG_DIR "/storage/emulated/0/Download/Wayland-logs"

static FILE *g_log;

#define PRELOG_MAX 32
static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;
static char *g_prelog[PRELOG_MAX];
static int g_prelog_n;

void droiddeck_log(const char *tag, const char *fmt, ...) {
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    WLOGI("[%s] %s", tag, msg);
    struct timespec ts;
    struct tm tm;
    clock_gettime(CLOCK_REALTIME, &ts);
    localtime_r(&ts.tv_sec, &tm);
    char line[640];
    snprintf(line, sizeof(line), "%02d:%02d:%02d.%03ld  %-9s %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
             ts.tv_nsec / 1000000, tag, msg);
    pthread_mutex_lock(&g_log_lock);
    if (g_log) fputs(line, g_log);
    else if (g_prelog_n < PRELOG_MAX) g_prelog[g_prelog_n++] = strdup(line);
    pthread_mutex_unlock(&g_log_lock);
}

static void open_session_log(void) {
    char path[256], stamp[32];
    time_t now = time(NULL);
    struct tm tm;
    FILE *f;

    localtime_r(&now, &tm);
    strftime(stamp, sizeof(stamp), "%Y-%m-%d_%H-%M-%S", &tm);
    const char *given = getenv("BL_WAYLAND_LOG");
    if (given && *given) {
        snprintf(path, sizeof(path), "%s", given);
    } else {
        mkdir("/storage/emulated/0/Download", 0775);
        if (mkdir(SESSION_LOG_DIR, 0775) != 0 && errno != EEXIST)
            WLOGE("can't create %s: %s", SESSION_LOG_DIR, strerror(errno));
        snprintf(path, sizeof(path), "%s/wayland-%s.log", SESSION_LOG_DIR, stamp);
    }
    if (!(f = fopen(path, "w"))) {
        WLOGE("can't open session log %s: %s", path, strerror(errno));
        return;
    }
    setvbuf(f, NULL, _IOLBF, 0);
    strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &tm);
    fprintf(f,
            "DroidDeck Wayland session\n"
            "=========================\n"
            "Started   %s\n"
            "Display   Wayland: programs draw through the DroidDeck compositor.\n"
            "          gamescope and the desktop start their own Xwayland for X11 programs.\n"
            "Log       %s\n\n"
            "Time          Area      Event\n"
            "------------  --------  -----------------------------------------------------\n",
            stamp, path);
    pthread_mutex_lock(&g_log_lock);
    for (int i = 0; i < g_prelog_n; i++) { fputs(g_prelog[i], f); free(g_prelog[i]); g_prelog[i] = NULL; }
    g_prelog_n = 0;
    g_log = f;
    pthread_mutex_unlock(&g_log_lock);
    WLOGI("session log: %s", path);
}

static int log_budget(void) {
    static struct timespec window;
    static int used;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec != window.tv_sec) { window = now; used = 0; }
    return used++ < 10;
}

#ifndef BTN_LEFT
#define BTN_LEFT 0x110
#endif
#ifndef BTN_RIGHT
#define BTN_RIGHT 0x111
#endif

#define INPUT_SPACE_W 1920
#define INPUT_SPACE_H 1080

static struct wl_display *g_display;

static struct client_info *g_clients;

struct client_info *client_info_of(struct wl_client *client) {
    for (struct client_info *ci = g_clients; ci; ci = ci->next)
        if (ci->client == client) return ci;
    return NULL;
}

static const char *client_name(struct wl_client *client) {
    struct client_info *ci = client_info_of(client);
    return ci ? ci->name : "a program";
}

static void on_client_destroyed(struct wl_listener *l, void *data) {
    struct client_info *ci = wl_container_of(l, ci, destroy), **pp;
    droiddeck_log("program", "disconnected: %s (pid %d)", ci->name, (int)ci->pid);
    droiddeck_color_client_gone(ci->client);
    for (pp = &g_clients; *pp; pp = &(*pp)->next)
        if (*pp == ci) { *pp = ci->next; break; }
    free(ci);
}

static void on_client_created(struct wl_listener *l, void *data) {
    struct wl_client *client = data;
    struct client_info *ci = calloc(1, sizeof(*ci));
    char path[64], buf[256];
    uid_t uid; gid_t gid;
    int fd;
    ssize_t n;

    if (!ci) return;
    ci->client = client;
    wl_client_get_credentials(client, &ci->pid, &uid, &gid);
    snprintf(ci->name, sizeof(ci->name), "pid %d", (int)ci->pid);
    snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)ci->pid);
    if ((fd = open(path, O_RDONLY | O_CLOEXEC)) >= 0) {
        if ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
            char *base = buf, *p;
            buf[n] = 0;
            for (p = buf; *p; p++) if (*p == '\\' || *p == '/') base = p + 1;
            if (*base) snprintf(ci->name, sizeof(ci->name), "%s", base);
        }
        close(fd);
    }
    ci->destroy.notify = on_client_destroyed;
    wl_client_add_destroy_listener(client, &ci->destroy);
    ci->next = g_clients;
    g_clients = ci;
    droiddeck_log("program", "connected over Wayland: %s (pid %d)", ci->name, (int)ci->pid);
}


enum surface_role { ROLE_NONE, ROLE_TOPLEVEL, ROLE_SUBSURFACE, ROLE_DESKTOP, ROLE_CURSOR };

#define DAMAGE_RECTS 8
struct damage_list { int32_t r[DAMAGE_RECTS][4]; int n; };

struct surface {
    struct wl_resource *resource;
    struct wl_list link;

    struct wl_resource *pending_buffer;
    struct wl_listener pending_buffer_destroy;
    int pending_attach;
    int pending_src_set, pending_dst_set;
    float pending_src[4];
    int pending_dst[2];
    struct wl_list pending_frames;
    struct wl_list pending_feedback;


    struct vkp_image *shm_img;
    struct wl_resource *dmabuf;
    struct dmabuf_buffer *dmabuf_buf;
    struct wl_listener dmabuf_destroy;
    const void *last_dmabuf;
    int buf_w, buf_h, has_content;
    int buf_alpha;
    int opaque[4], pending_opaque[4], pending_opaque_set;
    struct damage_list damage_buf, pending_damage_buf, damage_local, pending_damage_local;
    int src_set, dst_set;
    float src[4];
    int dst[2];
    struct wl_list frames;
    struct wl_list feedback;
    int drawn;
    int64_t next_release_ns;
    int releases_pending;

    enum surface_role role;
    struct wl_resource *idle_buffer;
    struct wl_listener idle_buffer_destroy;
    uint32_t *idle_px;
    int idle_w, idle_h;
    struct wl_resource *xdg_surface, *xdg_toplevel;
    struct wl_resource *viewport;


    int mapped;
    struct wl_list toplevel_link;
    int keep_on_top;
    int x, y, placed;
    uint32_t hwnd;

    struct surface *parent;
    struct wl_resource *subsurface;
    struct wl_list children;
    struct wl_list child_link;
    int sub_x, sub_y, sub_pending_x, sub_pending_y, sub_pending;
    int below_parent;
    int fullscreen;
    int toplevel_committed;

    char *title;
    int announced_vulkan;
    uint32_t hdr_fmt_logged;
};

static struct wl_list g_surfaces;
static struct wl_list g_toplevels;
static struct surface *g_desktop;
static uint32_t *g_zorder;
static size_t g_zorder_count;
static struct wl_event_source *g_render_idle, *g_frame_timer;





static int g_dirty;
static int64_t g_dirty_since_ns;
volatile int g_render_on_commit = 1;
static int64_t g_rendered_tick_ns;
static int g_tick_held;
static int64_t g_hold_missed_ns;
#define HOLD_RETRY_NS 1000000000LL
#define PACE_BUFFERS 32
static struct {
    unsigned ticks, ticks_late_2ms, renders, commit_renders, null_attaches, reattaches, presented, replaced, undrawn, distinct;
    int64_t tick_late_ns, tick_late_max_ns, wait_ns, wait_max_ns;
    const void *buffers[PACE_BUFFERS];
    uint64_t game_frames_seen;
} g_pace;

static void pace_note_buffer(const void *buffer) {
    for (unsigned i = 0; i < g_pace.distinct && i < PACE_BUFFERS; i++)
        if (g_pace.buffers[i] == buffer) return;
    if (g_pace.distinct < PACE_BUFFERS) g_pace.buffers[g_pace.distinct] = buffer;
    g_pace.distinct++;
}
static int64_t g_last_vsync_ns;
static int64_t g_tick_vsync_ns;
static int64_t g_refresh_ns = 16666667;
static struct wl_event_source *g_fallback_timer;
static int g_fallback_armed;

volatile int g_fps_limit;
/* fps limit -1 (phone output, not mirrored): replaced buffers go back to the client at once instead of once per refresh. */
volatile int g_release_unpaced;

volatile int g_hide_shell;

volatile int g_zero_copy;
static int g_zero_copy_paused;
static int g_zero_copy_fx_skip_said;

volatile int g_ubwc = 1;

volatile int g_no_render_node;

static atomic_int g_output_refresh_mhz = 60000;
static atomic_int g_output_refresh_pending;
static int g_output_announced_refresh_mhz = 60000;

volatile int g_output_w, g_output_h;
struct pending_release {
    struct wl_resource *buffer;
    struct wl_listener destroy;
    struct surface *surface;
    int64_t at_ns;
    int64_t since_ns;
    struct wl_list link;
};
static struct wl_list g_pending_releases;
static int g_release_timer_fd = -1;
static struct wl_event_source *g_release_source;
static int g_scene_w, g_scene_h;
static int g_desktop_w, g_desktop_h;
static unsigned g_stat_frames, g_stat_dmabuf, g_stat_shm;

static struct {
    unsigned ticks;
    unsigned scenes;
    int64_t scene_ns, scene_max_ns;
    unsigned copy_scenes;
    unsigned releases, releases_held;
    int64_t release_ns, release_max_ns;
} g_perf;

static void schedule_render(void);
static void on_commit_idle(void *data);

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void perf_note_release(int64_t since_ns) {
    if (since_ns <= 0) return;
    int64_t d = now_ns() - since_ns;
    if (d < 0) d = 0;
    g_perf.releases++;
    g_perf.release_ns += d;
    if (d > g_perf.release_max_ns) g_perf.release_max_ns = d;
    if (d >= 1000000LL) g_perf.releases_held++;
}


static void pending_release_free(struct pending_release *pr) {
    if (pr->surface && pr->surface->releases_pending > 0) pr->surface->releases_pending--;
    wl_list_remove(&pr->destroy.link);
    wl_list_remove(&pr->link);
    free(pr);
}

static void pending_releases_forget_surface(struct surface *s) {
    struct pending_release *pr;
    wl_list_for_each(pr, &g_pending_releases, link)
        if (pr->surface == s) pr->surface = NULL;
}

static void on_pending_release_buffer_destroyed(struct wl_listener *l, void *data) {
    struct pending_release *pr = wl_container_of(l, pr, destroy);
    pending_release_free(pr);
}

static void arm_release_timer(void) {
    struct pending_release *pr;
    int64_t earliest = 0;
    if (g_release_timer_fd < 0) return;
    wl_list_for_each(pr, &g_pending_releases, link)
        if (!earliest || pr->at_ns < earliest) earliest = pr->at_ns;
    struct itimerspec its = {0};
    if (earliest) { its.it_value.tv_sec = earliest / 1000000000LL; its.it_value.tv_nsec = earliest % 1000000000LL; }
    timerfd_settime(g_release_timer_fd, TFD_TIMER_ABSTIME, &its, NULL);
}

static int on_release_timer(int fd, uint32_t mask, void *data) {
    struct pending_release *pr, *tmp;
    uint64_t expirations;
    int64_t now = now_ns() + 300000;
    if (read(fd, &expirations, sizeof(expirations)) < 0 && errno != EAGAIN) return 0;
    wl_list_for_each_safe(pr, tmp, &g_pending_releases, link) {
        if (pr->at_ns <= now) {
            wl_buffer_send_release(pr->buffer);
            perf_note_release(pr->since_ns);
            pending_release_free(pr);
        }
    }
    wl_display_flush_clients(g_display);
    arm_release_timer();
    return 0;
}

static void release_buffer(struct surface *s, struct wl_resource *buffer, int64_t since_ns) {
    const int limit = g_fps_limit;
    const int64_t interval = limit > 0 ? 1000000000LL / limit : g_refresh_ns;
    if (g_release_unpaced || interval <= 0 || g_release_timer_fd < 0) { wl_buffer_send_release(buffer); perf_note_release(since_ns); return; }
    int64_t now = now_ns();
    int64_t at = s->next_release_ns + interval;
    int64_t latest = now + interval * (int64_t)(s->releases_pending + 1);
    if (at < now) at = now;
    if (at > latest) at = latest;
    s->next_release_ns = at;
    struct pending_release *pr = calloc(1, sizeof(*pr));
    if (!pr) { wl_buffer_send_release(buffer); perf_note_release(since_ns); return; }
    pr->buffer = buffer;
    pr->surface = s;
    pr->at_ns = at;
    pr->since_ns = since_ns;
    pr->destroy.notify = on_pending_release_buffer_destroyed;
    wl_resource_add_destroy_listener(buffer, &pr->destroy);
    wl_list_insert(g_pending_releases.prev, &pr->link);
    s->releases_pending++;
    arm_release_timer();
}


struct seat_pointer { struct wl_resource *ptr; struct wl_resource *focus; };
struct seat_keyboard { struct wl_resource *kb; struct wl_resource *focus; };
struct seat_touch { struct wl_resource *touch; struct wl_resource *focus; };
#define MAX_PTRS 32
#define MAX_TOUCH_POINTS 32
static struct seat_pointer g_ptrs[MAX_PTRS];
static int g_nptrs;
static struct seat_keyboard g_kbs[MAX_PTRS];
static int g_nkbs;
static uint32_t g_mods_depressed, g_mods_locked;
static uint32_t g_mod_keys_held;
static uint8_t g_keys_down[0x300 / 8];
static struct seat_touch g_touches[MAX_PTRS];
static int g_ntouches;

#define CURSOR_MAX_PX (256 * 256)
static pthread_mutex_t g_cursor_lock = PTHREAD_MUTEX_INITIALIZER;
static struct surface *g_cursor_surface;
static struct surface *g_cursor_shown;
static int g_cursor_hx, g_cursor_hy;
static uint32_t g_cursor_px[CURSOR_MAX_PX];
static uint32_t g_cursor_rb[CURSOR_MAX_PX];
static int g_cursor_w, g_cursor_h;
static int g_cursor_hidden = 1;
static int g_cursor_serial;

static void cursor_publish_shown(void) {
    pthread_mutex_lock(&g_cursor_lock);
    if (g_cursor_w > 0) { g_cursor_hidden = 0; g_cursor_serial++; }
    pthread_mutex_unlock(&g_cursor_lock);
}

static void cursor_publish_hidden(void) {
    pthread_mutex_lock(&g_cursor_lock);
    if (!g_cursor_hidden) { g_cursor_hidden = 1; g_cursor_serial++; }
    pthread_mutex_unlock(&g_cursor_lock);
}

static void cursor_publish_pixels(const uint8_t *src, int w, int h, size_t stride, int hx, int hy) {
    if (w <= 0 || h <= 0 || (int64_t)w * h > CURSOR_MAX_PX) return;
    pthread_mutex_lock(&g_cursor_lock);
    for (int y = 0; y < h; y++)
        memcpy(&g_cursor_px[y * w], src + (size_t)y * stride, (size_t)w * 4);
    g_cursor_w = w; g_cursor_h = h;
    g_cursor_hx = hx; g_cursor_hy = hy;
    g_cursor_hidden = 0;
    g_cursor_serial++;
    pthread_mutex_unlock(&g_cursor_lock);
}

static void cursor_publish_shm(struct wl_shm_buffer *shm, int hx, int hy) {
    int32_t w = wl_shm_buffer_get_width(shm), h = wl_shm_buffer_get_height(shm);
    int32_t stride = wl_shm_buffer_get_stride(shm);
    if (w <= 0 || h <= 0 || (int64_t)w * h > CURSOR_MAX_PX) return;
    if ((int64_t)stride < (int64_t)w * 4) return;
    wl_shm_buffer_begin_access(shm);
    cursor_publish_pixels((const uint8_t *)wl_shm_buffer_get_data(shm), w, h, (size_t)stride, hx, hy);
    wl_shm_buffer_end_access(shm);
}

int droiddeck_cursor_serial(void) {
    return __atomic_load_n(&g_cursor_serial, __ATOMIC_RELAXED);
}

int droiddeck_cursor_snapshot(int *out, int cap) {
    int n = 0;
    pthread_mutex_lock(&g_cursor_lock);
    int need = 6 + (g_cursor_hidden ? 0 : g_cursor_w * g_cursor_h);
    if (cap >= need) {
        out[0] = g_cursor_serial; out[1] = g_cursor_hidden;
        out[2] = g_cursor_w; out[3] = g_cursor_h;
        out[4] = g_cursor_hx; out[5] = g_cursor_hy;
        if (!g_cursor_hidden)
            memcpy(out + 6, g_cursor_px, (size_t)g_cursor_w * g_cursor_h * 4);
        n = need;
    }
    pthread_mutex_unlock(&g_cursor_lock);
    return n;
}
struct active_touch { struct surface *surface; int pointer_fallback; };
static struct active_touch g_touch_points[MAX_TOUCH_POINTS];
static void touch_cancel_surface(struct surface *surface);
static struct surface *g_grab;
static void publish_query_state(void);
static struct surface *g_key_target;
static struct surface *g_ime_click;
static int g_input_pipe[2] = {-1, -1};
struct input_msg { int type; int p1; int p2; int p3; int p4; };

struct surface;
static void constraints_surface_gone(struct surface *s);
static void constraints_surface_commit(struct surface *s);
static void constraints_pointer_gone(struct wl_resource *pointer);
static void relative_pointers_pointer_gone(struct wl_resource *pointer);
static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client);


void dmabuf_buffer_unref(struct dmabuf_buffer *b) {
    if (!b || --b->refs > 0) return;
    vkp_image_destroy(b->img);
    for (int i = 0; i < b->n_planes; i++)
        if (b->fd[i] >= 0) close(b->fd[i]);
    free(b);
}

static void dbuf_buffer_destroy_req(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
const struct wl_buffer_interface dbuf_buffer_impl = {
    .destroy = dbuf_buffer_destroy_req,
};

static struct dmabuf_buffer *get_dmabuf(struct wl_resource *buffer) {
    if (buffer && wl_resource_instance_of(buffer, &wl_buffer_interface, &dbuf_buffer_impl))
        return wl_resource_get_user_data(buffer);
    return NULL;
}


static void surface_size(const struct surface *s, int *w, int *h) {
    if (s->dst_set) { *w = s->dst[0]; *h = s->dst[1]; }
    else if (s->src_set) { *w = (int)(s->src[2] + 0.5f); *h = (int)(s->src[3] + 0.5f); }
    else { *w = s->buf_w; *h = s->buf_h; }
}

static void describe(const struct surface *s, char *out, size_t size) {
    const struct surface *w = s;
    while (w->parent) w = w->parent;
    const char *prog = client_name(wl_resource_get_client(w->resource));
    if (w->title && *w->title) snprintf(out, size, "\"%s\" (%s)", w->title, prog);
    else if (w->role == ROLE_DESKTOP) snprintf(out, size, "the desktop (%s)", prog);
    else snprintf(out, size, "window %#x (%s)", w->hwnd, prog);
}

static struct surface *toplevel_by_hwnd(uint32_t hwnd) {
    struct surface *s;
    wl_list_for_each(s, &g_toplevels, toplevel_link)
        if (s->hwnd == hwnd) return s;
    return NULL;
}

static void raise_kept_on_top(void);

static void apply_zorder(void) {
    for (size_t i = g_zorder_count; i-- > 0;) {
        struct surface *s = toplevel_by_hwnd(g_zorder[i]);
        if (!s) continue;
        wl_list_remove(&s->toplevel_link);
        wl_list_insert(g_toplevels.prev, &s->toplevel_link);
    }
    raise_kept_on_top();
}

static void raise_kept_on_top(void) {
    struct surface *s, *tmp, *kept[4];
    int n = 0;
    wl_list_for_each_safe(s, tmp, &g_toplevels, toplevel_link) {
        if (s->keep_on_top && n < 4) { wl_list_remove(&s->toplevel_link); kept[n++] = s; }
    }
    for (int i = 0; i < n; i++) wl_list_insert(g_toplevels.prev, &kept[i]->toplevel_link);
}

static void map_toplevel(struct surface *s) {
    int w, h;
    if (s->mapped) return;
    s->mapped = 1;
    surface_size(s, &w, &h);
    {
        char name[160];
        describe(s, name, sizeof(name));
        droiddeck_log("window", "opened %s %dx%d at %d,%d%s", name, w, h, s->x, s->y,
                   s->placed ? "" : " (no desktop position yet)");
    }
    wl_list_insert(g_toplevels.prev, &s->toplevel_link);
    raise_kept_on_top();
    if (!g_desktop) g_key_target = s;
    apply_zorder();
    publish_query_state();
}

static void unmap_toplevel(struct surface *s) {
    if (!s->mapped) return;
    s->mapped = 0;
    {
        char name[160];
        describe(s, name, sizeof(name));
        droiddeck_log("window", "closed %s", name);
    }
    wl_list_remove(&s->toplevel_link);
    wl_list_init(&s->toplevel_link);
    if (g_ime_click == s) g_ime_click = NULL;
    droiddeck_text_input_refocus();
}

static void drop_dmabuf(struct surface *s, int paced) {
    if (s->dmabuf) {
        wl_list_remove(&s->dmabuf_destroy.link);
        if (!ahb_swapchain_defer_release(s->dmabuf_buf, s->dmabuf, s, paced)) {
            if (paced) release_buffer(s, s->dmabuf, now_ns());
            else wl_buffer_send_release(s->dmabuf);
        }
        s->dmabuf = NULL;
    }
    dmabuf_buffer_unref(s->dmabuf_buf);
    s->dmabuf_buf = NULL;
}

static void on_dmabuf_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, dmabuf_destroy);
    wl_list_remove(&s->dmabuf_destroy.link);
    s->dmabuf = NULL;
}

static void on_pending_buffer_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, pending_buffer_destroy);
    wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer = NULL;
}

static void fire_frames(struct wl_list *frames) {
    struct wl_resource *cb, *tmp;
    uint32_t t;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    t = (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
    wl_resource_for_each_safe(cb, tmp, frames) {
        wl_callback_send_done(cb, t);
        wl_resource_destroy(cb);
    }
}

static void scene_size(int *w, int *h);
static int64_t damage_area(const struct damage_list *d, int bound_w, int bound_h);
static struct surface *g_hud_surface, *g_hud_shm;
static int64_t g_hud_shm_ns;
static int64_t g_hud_last_ns, g_hud_alive_ns;
static int g_hud_fresh;
static int g_hud_new;
#define HUD_TIMES 256
static pthread_mutex_t g_hud_times_lock = PTHREAD_MUTEX_INITIALIZER;
static float g_hud_times[HUD_TIMES];
static int64_t g_hud_times_total, g_hud_shown_ns;

static void hud_frame_shown(int64_t t) {
    const int64_t previous = g_hud_shown_ns;
    g_hud_shown_ns = t;
    if (!previous || t <= previous) return;
    pthread_mutex_lock(&g_hud_times_lock);
    g_hud_times[g_hud_times_total % HUD_TIMES] = (float)((t - previous) / 1e6);
    g_hud_times_total++;
    pthread_mutex_unlock(&g_hud_times_lock);
}

static int tree_rank(const struct surface *s, const struct surface *want, int *rank, int depth) {
    const struct surface *c;
    if (depth > 8) return 0;
    wl_list_for_each(c, &s->children, child_link)
        if (c->below_parent && tree_rank(c, want, rank, depth + 1)) return 1;
    if (s == want) return 1;
    (*rank)++;
    wl_list_for_each(c, &s->children, child_link)
        if (!c->below_parent && tree_rank(c, want, rank, depth + 1)) return 1;
    return 0;
}

static int render_rank(const struct surface *want) {
    const struct surface *s;
    int rank = 0;
    if (g_desktop && tree_rank(g_desktop, want, &rank, 0)) return rank;
    wl_list_for_each(s, &g_toplevels, toplevel_link)
        if (tree_rank(s, want, &rank, 0)) return rank;
    return -1;
}

static int below_surface(const struct surface *s, const struct surface *other) {
    const int a = render_rank(s), b = render_rank(other);
    return a >= 0 && b >= 0 && a < b;
}

int droiddeck_hud_frame_times(float *out, int len, int64_t *total) {
    pthread_mutex_lock(&g_hud_times_lock);
    *total = g_hud_times_total;
    int64_t n = *total < len ? *total : len;
    if (n > HUD_TIMES) n = HUD_TIMES;
    for (int64_t i = 0; i < n; i++) out[i] = g_hud_times[(*total - n + i) % HUD_TIMES];
    pthread_mutex_unlock(&g_hud_times_lock);
    return (int)n;
}
static uint64_t g_game_frames;
extern void droiddeck_on_game_surface(const char *window, const char *gpu);
extern void droiddeck_on_game_frame(void);
extern void droiddeck_on_game_program(int pid, const char *program);

static void take_shm(struct surface *s, struct wl_shm_buffer *shm, struct wl_resource *buffer) {
    int32_t w = wl_shm_buffer_get_width(shm), h = wl_shm_buffer_get_height(shm);
    int32_t stride = wl_shm_buffer_get_stride(shm);

    if (s->shm_img && (vkp_image_width(s->shm_img) != w || vkp_image_height(s->shm_img) != h)) {
        vkp_image_destroy(s->shm_img);
        s->shm_img = NULL;
    }
    if (!s->shm_img) s->shm_img = vkp_image_create_shm(w, h);
    if (s->shm_img) {
        wl_shm_buffer_begin_access(shm);
        vkp_image_upload_shm(s->shm_img, wl_shm_buffer_get_data(shm), stride);
        wl_shm_buffer_end_access(shm);
    }
    wl_buffer_send_release(buffer);
    s->buf_w = w;
    s->buf_h = h;
    s->buf_alpha = wl_shm_buffer_get_format(shm) == WL_SHM_FORMAT_ARGB8888;
    s->has_content = s->shm_img != NULL;
    g_stat_shm++;
    struct client_info *ci = client_info_of(wl_resource_get_client(s->resource));
    if (ci && ci->asked_feedback) ci->shm_frames++;
    int local_w, local_h;
    surface_size(s, &local_w, &local_h);
    const double region = s->src_set ? (double)s->src[2] * s->src[3] : (double)w * h;
    const int64_t local_max = damage_area(&s->damage_local, local_w, local_h);
    const int64_t local = local_w > 0 && local_h > 0
        ? (int64_t)((double)local_max * region / ((double)local_w * local_h)) : local_max;
    const int64_t buffer_max = damage_area(&s->damage_buf, w, h);
    const int64_t area = (int64_t)w * h;
    int64_t damaged = buffer_max > local ? buffer_max : local;
    if (damaged > area) damaged = area;
    const int64_t now = now_ns();
    if (damaged * 16 >= area && (!g_hud_surface || now - g_hud_last_ns > 1000000000LL) &&
        (s == g_hud_shm || !g_hud_shm || now - g_hud_shm_ns > 1000000000LL)) {
        const struct surface *top = s;
        int scene_w, scene_h, top_w, top_h, depth = 0;
        while (top->parent && depth++ < 8) top = top->parent;
        scene_size(&scene_w, &scene_h);
        surface_size(top, &top_w, &top_h);
        int64_t shown = (int64_t)local_w * local_h;
        if ((int64_t)top_w * top_h > shown) shown = (int64_t)top_w * top_h;
        if (top->role == ROLE_TOPLEVEL && top->mapped && shown * 2 >= (int64_t)scene_w * scene_h) {
            g_hud_shm = s;
            g_hud_shm_ns = now;
            g_hud_new = 1;
            droiddeck_on_game_frame();
        }
    }
}

static void take_pixel(struct surface *s, const uint8_t bgra[4], struct wl_resource *buffer) {
    if (s->shm_img && (vkp_image_width(s->shm_img) != 1 || vkp_image_height(s->shm_img) != 1)) {
        vkp_image_destroy(s->shm_img);
        s->shm_img = NULL;
    }
    if (!s->shm_img) s->shm_img = vkp_image_create_shm(1, 1);
    if (s->shm_img) vkp_image_upload_shm(s->shm_img, bgra, 4);
    wl_buffer_send_release(buffer);
    s->buf_w = 1;
    s->buf_h = 1;
    s->buf_alpha = bgra[3] != 0xff;
    s->has_content = s->shm_img != NULL;
}

static void take_dmabuf(struct surface *s, struct dmabuf_buffer *b, struct wl_resource *buffer) {
    const int repeat = s->dmabuf == buffer && s->dmabuf_buf == b;
    if (!repeat) {
        if (!s->dmabuf && s->last_dmabuf == buffer) g_pace.reattaches++;
        s->last_dmabuf = buffer;
        pace_note_buffer(buffer);
        drop_dmabuf(s, 1);
        s->dmabuf = buffer;
        s->dmabuf_buf = b;
        b->refs++;
        s->dmabuf_destroy.notify = on_dmabuf_destroyed;
        wl_resource_add_destroy_listener(buffer, &s->dmabuf_destroy);
    }
    if (!b->img && !b->import_failed && b->n_planes >= 1) {
        b->img = vkp_image_from_dmabuf(b->fd[0], b->format, b->modifier, b->width, b->height,
                                       b->stride[0], b->offset[0]);
        if (b->img && g_zero_copy) sc_layer_probe_dmabuf_fd(b->fd[0]);
        if (!b->img) {
            b->import_failed = 1;
            WLOGE("dmabuf import failed (%dx%d fmt=0x%08x mod=0x%llx)", b->width, b->height,
                  b->format, (unsigned long long)b->modifier);
        }
    }
    if (s->shm_img) { vkp_image_destroy(s->shm_img); s->shm_img = NULL; }
    if (s->announced_vulkan && s->buf_w && s->buf_h &&
        (s->buf_w != b->width || s->buf_h != b->height)) {
        char name[160];
        describe(s, name, sizeof(name));
        droiddeck_log("vulkan", "%s changed its frame size: %dx%d -> %dx%d", name,
                   s->buf_w, s->buf_h, b->width, b->height);
    }
    s->buf_w = b->width;
    s->buf_h = b->height;
    s->buf_alpha = (b->format & 0xff) == 'A';
    s->has_content = b->img != NULL;
    if (!repeat) g_stat_dmabuf++;
    const int announced_now = !s->announced_vulkan;
    if (!s->announced_vulkan) {
        char name[160];
        s->announced_vulkan = 1;
        describe(s, name, sizeof(name));
        if (b->img)
            droiddeck_log("vulkan", "%s is presenting GPU frames through Wayland: %dx%d, format %c%c%c%c, %s (%s)",
                       name, b->width, b->height, b->format & 0xff, (b->format >> 8) & 0xff,
                       (b->format >> 16) & 0xff, (b->format >> 24) & 0xff,
                       vkp_modifier_name(b->modifier),
                       ahb_swapchain_has_ahb(b) ? "gralloc buffers: can go on the display layer without a copy"
                                                : "dma-buf: copied into the screen swapchain");
        else if (ahb_swapchain_has_ahb(b))
            droiddeck_log("vulkan", "%s is presenting GPU frames through Wayland: %dx%d on its own display layer only "
                       "(gralloc buffers the compositor cannot import for the copy path)",
                       name, b->width, b->height);
        else
            droiddeck_log("error", "could not import GPU frames from %s (%dx%d, modifier %#llx)",
                       name, b->width, b->height, (unsigned long long)b->modifier);
    }
    if (s != g_hud_surface && (b->img || ahb_swapchain_has_ahb(b)) &&
        (!g_hud_surface || now_ns() - g_hud_alive_ns > 1000000000LL || (!repeat && below_surface(s, g_hud_surface)) ||
         (announced_now && !below_surface(g_hud_surface, s) &&
          (int64_t)b->width * b->height >= (int64_t)g_hud_surface->buf_w * g_hud_surface->buf_h))) {
        char name[160];
        describe(s, name, sizeof(name));
        g_hud_surface = s;
        droiddeck_on_game_surface(name, vkp_gpu_name());
        struct client_info *ci = client_info_of(wl_resource_get_client(s->resource));
        if (ci) droiddeck_on_game_program((int)ci->pid, strncmp(ci->name, "pid ", 4) ? ci->name : "");
    }
    if (b->format != s->hdr_fmt_logged && droiddeck_color_hdr_open()) {
        char name[160];
        uint32_t af = ahb_swapchain_ahb_format(b);
        int ten = b->format == FOURCC('A', 'B', '3', '0') || b->format == FOURCC('X', 'B', '3', '0');
        describe(s, name, sizeof(name));
        droiddeck_log("color", "%s presents %dx%d buffers in %c%c%c%c (%s), %s%s%s; %s", name, b->width, b->height,
                   b->format & 0xff, (b->format >> 8) & 0xff, (b->format >> 16) & 0xff, (b->format >> 24) & 0xff,
                   ten ? "10-bit A2B10G10R10" : "8-bit", vkp_modifier_name(b->modifier),
                   af ? ", gralloc " : ", no gralloc buffer (copy path only)", af ? droiddeck_ahb_format_name(af) : "",
                   b->img ? "the compositor imported it"
                          : af ? "the compositor's driver could NOT import it (display layer only, fullscreen)"
                               : "the compositor's driver could NOT import it (nothing can show it)");
        s->hdr_fmt_logged = b->format;
    }
    if (s == g_hud_surface) {
        g_hud_alive_ns = now_ns();
        if (!repeat) { g_hud_last_ns = g_hud_alive_ns; g_hud_fresh = 1; g_hud_new = 1; g_game_frames++; droiddeck_on_game_frame(); }
    }
}

uint64_t droiddeck_game_frames(void) { return g_game_frames; }

struct dmabuf_buffer *droiddeck_dmabuf_from_resource(struct wl_resource *buffer) { return get_dmabuf(buffer); }
int droiddeck_dmabuf_fd(const struct dmabuf_buffer *b) { return b && b->n_planes > 0 ? b->fd[0] : -1; }
void droiddeck_dmabuf_size(const struct dmabuf_buffer *b, int *w, int *h) { *w = b->width; *h = b->height; }
void **droiddeck_dmabuf_ahb_slot(struct dmabuf_buffer *b) { return &b->ahb_state; }
void droiddeck_dmabuf_ref(struct dmabuf_buffer *b) { b->refs++; }
void droiddeck_dmabuf_unref(struct dmabuf_buffer *b) { dmabuf_buffer_unref(b); }
void droiddeck_release_buffer(struct surface *s, struct wl_resource *buffer, int paced, int64_t since_ns) {
    if (paced && s) release_buffer(s, buffer, since_ns);
    else { wl_buffer_send_release(buffer); perf_note_release(since_ns); }
}
void droiddeck_surface_describe(const struct surface *s, char *out, size_t size) { describe(s, out, size); }
const struct droiddeck_color *droiddeck_surface_color(const struct surface *s) {
    return s ? droiddeck_color_of(s->resource) : NULL;
}


static void surface_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void surface_attach(struct wl_client *c, struct wl_resource *r,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s->pending_buffer) wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer = buffer;
    s->pending_attach = 1;
    if (buffer) {
        s->pending_buffer_destroy.notify = on_pending_buffer_destroyed;
        wl_resource_add_destroy_listener(buffer, &s->pending_buffer_destroy);
    }
}
static int64_t rect_area(const int32_t *r, int bound_w, int bound_h) {
    int64_t x0 = r[0], y0 = r[1], x1 = (int64_t)r[0] + r[2], y1 = (int64_t)r[1] + r[3];
    if (bound_w > 0 && bound_h > 0) {
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 > bound_w) x1 = bound_w;
        if (y1 > bound_h) y1 = bound_h;
    }
    if (x1 <= x0 || y1 <= y0) return 0;
    const int64_t w = x1 - x0, h = y1 - y0;
    return (w < 65536 ? w : 65536) * (h < 65536 ? h : 65536);
}
static void note_damage(struct damage_list *d, int32_t x, int32_t y, int32_t w, int32_t h) {
    if (w <= 0 || h <= 0) return;
    const int32_t rect[4] = { x, y, w, h };
    int slot = d->n;
    if (slot == DAMAGE_RECTS) {
        slot = 0;
        for (int i = 1; i < DAMAGE_RECTS; i++)
            if (rect_area(d->r[i], 0, 0) < rect_area(d->r[slot], 0, 0)) slot = i;
        if (rect_area(rect, 0, 0) <= rect_area(d->r[slot], 0, 0)) return;
    } else {
        d->n++;
    }
    memcpy(d->r[slot], rect, sizeof(rect));
}
static int64_t damage_area(const struct damage_list *d, int bound_w, int bound_h) {
    int64_t max = 0;
    for (int i = 0; i < d->n; i++) {
        const int64_t area = rect_area(d->r[i], bound_w, bound_h);
        if (area > max) max = area;
    }
    return max;
}
static void surface_damage(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y, int32_t w, int32_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    note_damage(&s->pending_damage_local, x, y, w, h);
}
static void frame_callback_destroy(struct wl_resource *r) {
    wl_list_remove(wl_resource_get_link(r));
}

static void feedback_resource_destroy(struct wl_resource *r) {
    wl_list_remove(wl_resource_get_link(r));
}

static unsigned feedback_discard_all(struct wl_list *list) {
    struct wl_resource *fb, *tmp;
    unsigned n = 0;
    wl_resource_for_each_safe(fb, tmp, list) {
        wp_presentation_feedback_send_discarded(fb);
        wl_resource_destroy(fb);
        n++;
    }
    return n;
}

static uint32_t nominal_refresh_ns(void) {
    int mhz = atomic_load(&g_output_refresh_mhz);
    if (mhz <= 0) mhz = 60000;
    return (uint32_t)(1000000000000LL / (int64_t)mhz);
}

static int64_t presented_ns(int64_t render_ns) {
    int64_t tick = g_tick_vsync_ns;
    int64_t refresh = (int64_t)nominal_refresh_ns();
    if (!tick || render_ns - g_last_vsync_ns > refresh)
        return render_ns;
    int64_t delta = render_ns - tick;
    if (delta > refresh || -delta > refresh)
        return render_ns;
    return tick;
}

static void feedback_present_all(struct wl_list *list, int64_t t) {
    struct wl_resource *fb, *tmp;
    uint64_t sec = (uint64_t)(t / 1000000000LL);
    uint32_t nsec = (uint32_t)(t % 1000000000LL);
    wl_resource_for_each_safe(fb, tmp, list) {
        wp_presentation_feedback_send_presented(fb, (uint32_t)(sec >> 32), (uint32_t)sec, nsec,
                                                nominal_refresh_ns(), 0, 0,
                                                WP_PRESENTATION_FEEDBACK_KIND_VSYNC);
        wl_resource_destroy(fb);
        g_pace.presented++;
    }
}

static void presentation_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void presentation_feedback(struct wl_client *c, struct wl_resource *r,
                                  struct wl_resource *surface, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct wl_resource *fb = wl_resource_create(c, &wp_presentation_feedback_interface,
                                                wl_resource_get_version(r), id);
    if (!fb) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(fb, NULL, NULL, feedback_resource_destroy);
    if (!s) { wp_presentation_feedback_send_discarded(fb); wl_resource_destroy(fb); return; }
    wl_list_insert(s->pending_feedback.prev, wl_resource_get_link(fb));
}
static const struct wp_presentation_interface presentation_impl = {
    .destroy = presentation_destroy,
    .feedback = presentation_feedback,
};
static void bind_presentation(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_presentation_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &presentation_impl, NULL, NULL);
    wp_presentation_send_clock_id(r, CLOCK_MONOTONIC);
}
static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t cb) {
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *callback = wl_resource_create(c, &wl_callback_interface, 1, cb);
    if (!callback) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(callback, NULL, NULL, frame_callback_destroy);
    wl_list_insert(s->pending_frames.prev, wl_resource_get_link(callback));
}
struct region { int set, exact; int x, y, w, h; };

static void surface_set_opaque(struct wl_client *c, struct wl_resource *r,
                               struct wl_resource *region) {
    struct surface *s = wl_resource_get_user_data(r);
    struct region *rg = region ? wl_resource_get_user_data(region) : NULL;
    memset(s->pending_opaque, 0, sizeof(s->pending_opaque));
    s->pending_opaque_set = 1;
    if (rg && rg->set && rg->exact) {
        s->pending_opaque[0] = rg->x;
        s->pending_opaque[1] = rg->y;
        s->pending_opaque[2] = rg->w;
        s->pending_opaque[3] = rg->h;
    }
    struct client_info *ci = client_info_of(c);
    if (ci) ci->declares_opaque = 1;
}
static void surface_set_input(struct wl_client *c, struct wl_resource *r,
                              struct wl_resource *region) {}

static void send_toplevel_configure(struct surface *s);

static void cursor_publish_buffer(struct surface *s, struct wl_resource *buffer) {
    struct dmabuf_buffer *db = get_dmabuf(buffer);
    struct wl_shm_buffer *shm = db ? NULL : wl_shm_buffer_get(buffer);
    if (shm) {
        cursor_publish_shm(shm, g_cursor_hx, g_cursor_hy);
    } else if (db && db->n_planes >= 1 && db->width > 0 && db->height > 0 &&
               (int64_t)db->width * db->height <= CURSOR_MAX_PX) {
        if (!db->img && !db->import_failed) {
            db->img = vkp_image_from_dmabuf(db->fd[0], db->format, db->modifier, db->width, db->height,
                                            db->stride[0], db->offset[0]);
            if (!db->img) db->import_failed = 1;
        }
        if (!db->img) return;
        struct pollfd p = {.fd = db->fd[0], .events = POLLIN};
        int r;
        do { r = poll(&p, 1, 100); } while (r < 0 && errno == EINTR);
        if (vkp_image_readback(db->img, g_cursor_rb, CURSOR_MAX_PX) != 0) return;
        cursor_publish_pixels((const uint8_t *)g_cursor_rb, db->width, db->height,
                              (size_t)db->width * 4, g_cursor_hx, g_cursor_hy);
    } else {
        return;
    }
    g_cursor_shown = s;
}

static void surface_forget_idle_px(struct surface *s) {
    free(s->idle_px);
    s->idle_px = NULL;
    s->idle_w = s->idle_h = 0;
}

static void on_idle_buffer_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, idle_buffer_destroy);
    struct wl_shm_buffer *shm = wl_shm_buffer_get(s->idle_buffer);
    if (shm) {
        int32_t w = wl_shm_buffer_get_width(shm), h = wl_shm_buffer_get_height(shm);
        int32_t stride = wl_shm_buffer_get_stride(shm);
        uint32_t *px = w > 0 && h > 0 && (int64_t)w * h <= CURSOR_MAX_PX && (int64_t)stride >= (int64_t)w * 4
            ? realloc(s->idle_px, (size_t)w * h * 4) : NULL;
        if (px) {
            wl_shm_buffer_begin_access(shm);
            const uint8_t *src = wl_shm_buffer_get_data(shm);
            for (int y = 0; y < h; y++) memcpy(&px[(size_t)y * w], src + (size_t)y * stride, (size_t)w * 4);
            wl_shm_buffer_end_access(shm);
            s->idle_px = px;
            s->idle_w = w;
            s->idle_h = h;
        }
    }
    wl_list_remove(&s->idle_buffer_destroy.link);
    wl_list_init(&s->idle_buffer_destroy.link);
    s->idle_buffer = NULL;
}

static void surface_hold_idle(struct surface *s, struct wl_resource *buffer) {
    struct dmabuf_buffer *db = get_dmabuf(buffer);
    struct wl_shm_buffer *shm = db ? NULL : wl_shm_buffer_get(buffer);
    int64_t px = db ? (int64_t)db->width * db->height
               : shm ? (int64_t)wl_shm_buffer_get_width(shm) * wl_shm_buffer_get_height(shm) : 0;
    if (px <= 0 || px > CURSOR_MAX_PX) { wl_buffer_send_release(buffer); return; }
    s->idle_buffer = buffer;
    s->idle_buffer_destroy.notify = on_idle_buffer_destroyed;
    wl_resource_add_destroy_listener(buffer, &s->idle_buffer_destroy);
}

static struct wl_resource *surface_take_idle(struct surface *s) {
    struct wl_resource *buffer = s->idle_buffer;
    if (!buffer) return NULL;
    wl_list_remove(&s->idle_buffer_destroy.link);
    wl_list_init(&s->idle_buffer_destroy.link);
    s->idle_buffer = NULL;
    return buffer;
}

static void surface_drop_idle(struct surface *s) {
    surface_forget_idle_px(s);
    struct wl_resource *buffer = surface_take_idle(s);
    if (buffer) wl_buffer_send_release(buffer);
}

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    struct surface *child;

    if (s->xdg_toplevel && !s->toplevel_committed) {
        s->toplevel_committed = 1;
        send_toplevel_configure(s);
    }

    if (s->pending_src_set) {
        s->src_set = s->pending_src[2] > 0;
        memcpy(s->src, s->pending_src, sizeof(s->src));
        s->pending_src_set = 0;
    }
    if (s->pending_dst_set) {
        s->dst_set = s->pending_dst[0] > 0;
        memcpy(s->dst, s->pending_dst, sizeof(s->dst));
        s->pending_dst_set = 0;
    }
    if (s->pending_opaque_set) {
        memcpy(s->opaque, s->pending_opaque, sizeof(s->opaque));
        s->pending_opaque_set = 0;
    }

    s->damage_buf = s->pending_damage_buf;
    s->damage_local = s->pending_damage_local;
    s->pending_damage_buf.n = s->pending_damage_local.n = 0;
    if (s->pending_attach) {
        struct wl_resource *buffer = s->pending_buffer;
        struct dmabuf_buffer *db = get_dmabuf(buffer);
        struct wl_shm_buffer *shm = buffer && !db ? wl_shm_buffer_get(buffer) : NULL;
        uint8_t pixel[4];

        g_pace.replaced += feedback_discard_all(&s->feedback);
        if (!buffer) g_pace.null_attaches++;
        else if (!db) s->last_dmabuf = NULL;

        if (buffer) {
            wl_list_remove(&s->pending_buffer_destroy.link);
            wl_list_init(&s->pending_buffer_destroy.link);
        }
        s->pending_buffer = NULL;
        s->pending_attach = 0;

        surface_drop_idle(s);
        if (s->role == ROLE_CURSOR) {
            if (!buffer) {
                cursor_publish_hidden();
            } else {
                cursor_publish_buffer(s, buffer);
                wl_buffer_send_release(buffer);
            }
        } else if (s->role == ROLE_NONE) {
            if (buffer) surface_hold_idle(s, buffer);
        } else if (db) {
            take_dmabuf(s, db, buffer);
        } else if (shm) {
            drop_dmabuf(s, 1);
            take_shm(s, shm, buffer);
        } else if (single_pixel_buffer_get(buffer, pixel)) {
            drop_dmabuf(s, 1);
            take_pixel(s, pixel, buffer);
        } else {
            drop_dmabuf(s, 1);
            s->has_content = 0;
            if (buffer) wl_buffer_send_release(buffer);
        }
    }

    wl_list_for_each(child, &s->children, child_link) {
        if (child->sub_pending) {
            child->sub_x = child->sub_pending_x;
            child->sub_y = child->sub_pending_y;
            child->sub_pending = 0;
        }
    }

    wl_list_insert_list(s->frames.prev, &s->pending_frames);
    wl_list_init(&s->pending_frames);
    wl_list_insert_list(s->feedback.prev, &s->pending_feedback);
    wl_list_init(&s->pending_feedback);
    constraints_surface_commit(s);
    droiddeck_color_commit(s->resource);

    if (s->role == ROLE_TOPLEVEL && s->xdg_toplevel) {
        if (s->has_content) map_toplevel(s);
        else unmap_toplevel(s);
    }
    schedule_render();
}
static void surface_set_buffer_transform(struct wl_client *c, struct wl_resource *r, int32_t t) {}
static void surface_set_buffer_scale(struct wl_client *c, struct wl_resource *r, int32_t s) {}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    note_damage(&s->pending_damage_buf, x, y, w, h);
}
static void surface_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_opaque,
    .set_input_region = surface_set_input,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
};

static void detach_from_parent(struct surface *s) {
    if (!s->parent) return;
    wl_list_remove(&s->child_link);
    wl_list_init(&s->child_link);
    s->parent = NULL;
}

static void surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    struct surface *child, *tmp;
    struct wl_resource *cb, *cbtmp;

    if (!s) return;
    for (int i = 0; i < g_nptrs; i++) if (g_ptrs[i].focus == r) g_ptrs[i].focus = NULL;
    for (int i = 0; i < g_nkbs; i++) if (g_kbs[i].focus == r) g_kbs[i].focus = NULL;
    touch_cancel_surface(s);
    if (g_cursor_surface == s) { g_cursor_surface = NULL; cursor_publish_hidden(); }
    if (g_cursor_shown == s) g_cursor_shown = NULL;
    surface_take_idle(s);
    surface_forget_idle_px(s);
    if (g_grab == s) g_grab = NULL;
    if (g_key_target == s) g_key_target = NULL;
    if (g_ime_click == s) g_ime_click = NULL;
    droiddeck_text_input_surface_gone(r);
    if (g_desktop == s) { g_desktop = NULL; droiddeck_log("desktop", "the desktop closed"); }
    if (g_hud_surface == s) { g_hud_surface = NULL; droiddeck_on_game_surface(NULL, NULL); }
    if (g_hud_shm == s) g_hud_shm = NULL;
    constraints_surface_gone(s);

    unmap_toplevel(s);
    detach_from_parent(s);
    wl_list_for_each_safe(child, tmp, &s->children, child_link) {
        wl_list_remove(&child->child_link);
        wl_list_init(&child->child_link);
        child->parent = NULL;
    }
    if (s->pending_buffer) wl_list_remove(&s->pending_buffer_destroy.link);
    pending_releases_forget_surface(s);
    drop_dmabuf(s, 0);
    ahb_swapchain_surface_gone(s);
    vkp_image_destroy(s->shm_img);
    wl_resource_for_each_safe(cb, cbtmp, &s->pending_frames) wl_resource_destroy(cb);
    wl_resource_for_each_safe(cb, cbtmp, &s->frames) wl_resource_destroy(cb);
    feedback_discard_all(&s->pending_feedback);
    feedback_discard_all(&s->feedback);
    if (s->viewport) wl_resource_set_user_data(s->viewport, NULL);
    if (s->subsurface) wl_resource_set_user_data(s->subsurface, NULL);
    if (s->xdg_surface) wl_resource_set_user_data(s->xdg_surface, NULL);
    if (s->xdg_toplevel) wl_resource_set_user_data(s->xdg_toplevel, NULL);
    wl_list_remove(&s->link);
    free(s->title);
    free(s);
    schedule_render();
}


static void region_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void region_add(struct wl_client *c, struct wl_resource *r,
                       int32_t x, int32_t y, int32_t w, int32_t h) {
    struct region *rg = wl_resource_get_user_data(r);
    if (!rg || w <= 0 || h <= 0) return;
    if (!rg->set) { rg->x = x; rg->y = y; rg->w = w; rg->h = h; rg->set = rg->exact = 1; return; }
    long long x2 = (long long)rg->x + rg->w, y2 = (long long)rg->y + rg->h;
    long long nx2 = (long long)x + w, ny2 = (long long)y + h;
    int inside = x >= rg->x && y >= rg->y && nx2 <= x2 && ny2 <= y2;
    int around = x <= rg->x && y <= rg->y && nx2 >= x2 && ny2 >= y2;
    if (!inside && !around) rg->exact = 0;
    if (nx2 > x2) x2 = nx2;
    if (ny2 > y2) y2 = ny2;
    if (x < rg->x) rg->x = x;
    if (y < rg->y) rg->y = y;
    rg->w = (int)(x2 - rg->x > INT32_MAX ? INT32_MAX : x2 - rg->x);
    rg->h = (int)(y2 - rg->y > INT32_MAX ? INT32_MAX : y2 - rg->y);
}
static void region_subtract(struct wl_client *c, struct wl_resource *r,
                            int32_t x, int32_t y, int32_t w, int32_t h) {
    struct region *rg = wl_resource_get_user_data(r);
    if (rg && w > 0 && h > 0) rg->exact = 0;
}
static void region_resource_destroy(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }
static const struct wl_region_interface region_impl = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_subtract,
};


static void compositor_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(c); return; }
    s->resource = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    if (!s->resource) { free(s); wl_client_post_no_memory(c); return; }
    wl_list_init(&s->pending_frames);
    wl_list_init(&s->frames);
    wl_list_init(&s->pending_feedback);
    wl_list_init(&s->feedback);
    wl_list_init(&s->children);
    wl_list_init(&s->child_link);
    wl_list_init(&s->toplevel_link);
    wl_list_init(&s->pending_buffer_destroy.link);
    wl_list_init(&s->idle_buffer_destroy.link);
    wl_list_insert(&g_surfaces, &s->link);
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_resource_destroy);
}
static void compositor_create_region(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *reg = wl_resource_create(c, &wl_region_interface, 1, id);
    if (!reg) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(reg, &region_impl, calloc(1, sizeof(struct region)),
                                   region_resource_destroy);
}
static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};
static void bind_compositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface, ver, id);
    wl_resource_set_implementation(r, &compositor_impl, NULL, NULL);
}


static void subsurface_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void subsurface_set_position(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->sub_pending_x = x;
    s->sub_pending_y = y;
    s->sub_pending = 1;
}
static void restack_child(struct surface *s, struct surface *sibling, int above) {
    if (!s || !s->parent || !sibling) return;
    if (sibling == s->parent) {
        s->below_parent = !above;
        wl_list_remove(&s->child_link);
        if (above) wl_list_insert(&s->parent->children, &s->child_link);
        else wl_list_insert(s->parent->children.prev, &s->child_link);
    } else if (sibling->parent == s->parent) {
        s->below_parent = sibling->below_parent;
        wl_list_remove(&s->child_link);
        if (above) wl_list_insert(&sibling->child_link, &s->child_link);
        else wl_list_insert(sibling->child_link.prev, &s->child_link);
    }
    schedule_render();
}
static void subsurface_place_above(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *sibling) {
    restack_child(wl_resource_get_user_data(r), wl_resource_get_user_data(sibling), 1);
}
static void subsurface_place_below(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *sibling) {
    restack_child(wl_resource_get_user_data(r), wl_resource_get_user_data(sibling), 0);
}
static void subsurface_set_sync(struct wl_client *c, struct wl_resource *r) {}
static void subsurface_set_desync(struct wl_client *c, struct wl_resource *r) {}
static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy,
    .set_position = subsurface_set_position,
    .place_above = subsurface_place_above,
    .place_below = subsurface_place_below,
    .set_sync = subsurface_set_sync,
    .set_desync = subsurface_set_desync,
};
static void subsurface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    detach_from_parent(s);
    s->subsurface = NULL;
    s->role = ROLE_NONE;
    s->has_content = 0;
    drop_dmabuf(s, 1);
    schedule_render();
}

static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void subcompositor_get_subsurface(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                         struct wl_resource *surface, struct wl_resource *parent) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct surface *p = wl_resource_get_user_data(parent);
    struct wl_resource *sub = wl_resource_create(c, &wl_subsurface_interface, wl_resource_get_version(r), id);
    if (!sub) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(sub, &subsurface_impl, s, subsurface_resource_destroy);
    if (!s || !p || s == p) return;
    detach_from_parent(s);
    s->role = ROLE_SUBSURFACE;
    s->subsurface = sub;
    s->parent = p;
    s->below_parent = 0;
    s->sub_x = s->sub_y = 0;
    wl_list_insert(p->children.prev, &s->child_link);
}
static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};
static void bind_subcompositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_subcompositor_interface, ver, id);
    wl_resource_set_implementation(r, &subcompositor_impl, NULL, NULL);
}


static void viewport_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void viewport_set_source(struct wl_client *c, struct wl_resource *r,
                                wl_fixed_t x, wl_fixed_t y, wl_fixed_t w, wl_fixed_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->pending_src[0] = (float)wl_fixed_to_double(x);
    s->pending_src[1] = (float)wl_fixed_to_double(y);
    s->pending_src[2] = w == wl_fixed_from_int(-1) ? -1.0f : (float)wl_fixed_to_double(w);
    s->pending_src[3] = h == wl_fixed_from_int(-1) ? -1.0f : (float)wl_fixed_to_double(h);
    s->pending_src_set = 1;
}
static void viewport_set_destination(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->pending_dst[0] = w;
    s->pending_dst[1] = h;
    s->pending_dst_set = 1;
}
static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};
static void viewport_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->viewport = NULL;
    s->pending_src[2] = -1.0f; s->pending_src_set = 1;
    s->pending_dst[0] = -1; s->pending_dst_set = 1;
}

static void viewporter_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void viewporter_get_viewport(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                    struct wl_resource *surface) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct wl_resource *vp = wl_resource_create(c, &wp_viewport_interface, wl_resource_get_version(r), id);
    if (!vp) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(vp, &viewport_impl, s, viewport_resource_destroy);
    if (s) s->viewport = vp;
}
static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};
static void bind_viewporter(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_viewporter_interface, ver, id);
    wl_resource_set_implementation(r, &viewporter_impl, NULL, NULL);
}


static void xdg_toplevel_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_toplevel_noop_parent(struct wl_client *c, struct wl_resource *r, struct wl_resource *p) {}
static void xdg_toplevel_set_title(struct wl_client *c, struct wl_resource *r, const char *title) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    int announce = s->mapped && title && *title && (!s->title || strcmp(s->title, title) != 0);
    char before[160];
    if (announce) describe(s, before, sizeof(before));
    free(s->title);
    s->title = title ? strdup(title) : NULL;
    if (announce) {
        char after[160];
        describe(s, after, sizeof(after));
        droiddeck_log("window", "renamed %s -> %s", before, after);
    }
}
static void xdg_toplevel_set_app_id(struct wl_client *c, struct wl_resource *r, const char *id) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s && id && !strcmp(id, "droiddeck-cardboard-vr")) s->keep_on_top = 1;
}
static void xdg_toplevel_show_menu(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                   uint32_t serial, int32_t x, int32_t y) {}
static void xdg_toplevel_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                              uint32_t serial) {}
static void xdg_toplevel_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                uint32_t serial, uint32_t edges) {}
static void xdg_toplevel_set_i32(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {}
static void xdg_toplevel_noop(struct wl_client *c, struct wl_resource *r) {}
static void send_toplevel_configure(struct surface *s) {
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    if (s->fullscreen) {
        st = wl_array_add(&states, sizeof(uint32_t));
        *st = XDG_TOPLEVEL_STATE_FULLSCREEN;
    }
    xdg_toplevel_send_configure(s->xdg_toplevel, g_output_w > 0 ? g_output_w : 0,
                                g_output_h > 0 ? g_output_h : 0, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(s->xdg_surface, wl_display_next_serial(g_display));
}
static void xdg_toplevel_set_fullscreen(struct wl_client *c, struct wl_resource *r,
                                        struct wl_resource *output) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s || !s->xdg_surface || !s->xdg_toplevel || s->fullscreen) return;
    s->fullscreen = 1;
    send_toplevel_configure(s);
}
static void xdg_toplevel_unset_fullscreen(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s || !s->xdg_surface || !s->xdg_toplevel || !s->fullscreen) return;
    s->fullscreen = 0;
    send_toplevel_configure(s);
}
static const struct xdg_toplevel_interface xdg_toplevel_impl = {
    .destroy = xdg_toplevel_destroy_req,
    .set_parent = xdg_toplevel_noop_parent,
    .set_title = xdg_toplevel_set_title,
    .set_app_id = xdg_toplevel_set_app_id,
    .show_window_menu = xdg_toplevel_show_menu,
    .move = xdg_toplevel_move,
    .resize = xdg_toplevel_resize,
    .set_max_size = xdg_toplevel_set_i32,
    .set_min_size = xdg_toplevel_set_i32,
    .set_maximized = xdg_toplevel_noop,
    .unset_maximized = xdg_toplevel_noop,
    .set_fullscreen = xdg_toplevel_set_fullscreen,
    .unset_fullscreen = xdg_toplevel_unset_fullscreen,
    .set_minimized = xdg_toplevel_noop,
};
static void xdg_toplevel_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->xdg_toplevel = NULL;
    s->toplevel_committed = 0;
    unmap_toplevel(s);
    s->placed = 0;
    s->hwnd = 0;
    schedule_render();
}

static void xdg_surface_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *tl = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id);
    if (!tl) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(tl, &xdg_toplevel_impl, s, xdg_toplevel_resource_destroy);
    if (!s) return;
    s->role = ROLE_TOPLEVEL;
    s->xdg_toplevel = tl;
    send_toplevel_configure(s);
}
static void popup_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void popup_grab(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial) {}
static void popup_reposition(struct wl_client *c, struct wl_resource *r, struct wl_resource *positioner,
                             uint32_t token) {}
static const struct xdg_popup_interface popup_impl = {
    .destroy = popup_destroy,
    .grab = popup_grab,
    .reposition = popup_reposition,
};
static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                  struct wl_resource *parent, struct wl_resource *positioner) {
    struct wl_resource *p = wl_resource_create(c, &xdg_popup_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &popup_impl, NULL, NULL);
    xdg_popup_send_popup_done(p);
}
static void xdg_surface_set_geometry(struct wl_client *c, struct wl_resource *r,
                                     int32_t x, int32_t y, int32_t w, int32_t h) {}
static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy_req,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_geometry,
    .ack_configure = xdg_surface_ack_configure,
};
static void xdg_surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s) s->xdg_surface = NULL;
}

static void xdg_wm_base_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void positioner_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void positioner_set_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {}
static void positioner_set_anchor_rect(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y,
                                       int32_t w, int32_t h) {}
static void positioner_set_anchor(struct wl_client *c, struct wl_resource *r, uint32_t a) {}
static void positioner_set_gravity(struct wl_client *c, struct wl_resource *r, uint32_t g) {}
static void positioner_set_constraint_adjustment(struct wl_client *c, struct wl_resource *r, uint32_t a) {}
static void positioner_set_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {}
static void positioner_set_reactive(struct wl_client *c, struct wl_resource *r) {}
static void positioner_set_parent_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {}
static void positioner_set_parent_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_positioner_interface positioner_impl = {
    .destroy = positioner_destroy,
    .set_size = positioner_set_size,
    .set_anchor_rect = positioner_set_anchor_rect,
    .set_anchor = positioner_set_anchor,
    .set_gravity = positioner_set_gravity,
    .set_constraint_adjustment = positioner_set_constraint_adjustment,
    .set_offset = positioner_set_offset,
    .set_reactive = positioner_set_reactive,
    .set_parent_size = positioner_set_parent_size,
    .set_parent_configure = positioner_set_parent_configure,
};

static void xdg_wm_base_create_positioner(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p = wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &positioner_impl, NULL, NULL);
}
static void xdg_wm_base_get_xdg_surface(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                        struct wl_resource *surf) {
    struct surface *s = wl_resource_get_user_data(surf);
    struct wl_resource *xs = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    if (!xs) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(xs, &xdg_surface_impl, s, xdg_surface_resource_destroy);
    if (s) s->xdg_surface = xs;
}
static void xdg_wm_base_pong(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_wm_base_interface xdg_wm_base_impl = {
    .destroy = xdg_wm_base_destroy,
    .create_positioner = xdg_wm_base_create_positioner,
    .get_xdg_surface = xdg_wm_base_get_xdg_surface,
    .pong = xdg_wm_base_pong,
};
static void bind_xdg_wm_base(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface, ver, id);
    wl_resource_set_implementation(r, &xdg_wm_base_impl, NULL, NULL);
}


static void desktop_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void desktop_set_desktop(struct wl_client *c, struct wl_resource *r, struct wl_resource *surface) {
    struct surface *s = wl_resource_get_user_data(surface);
    if (!s || (s->role != ROLE_NONE && s->role != ROLE_DESKTOP)) return;
    s->role = ROLE_DESKTOP;
    g_desktop = s;
    droiddeck_log("desktop", "Windows virtual desktop created by %s", client_name(c));
    schedule_render();
}
static void desktop_set_window(struct wl_client *c, struct wl_resource *r, struct wl_resource *surface,
                               uint32_t hwnd, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(surface);
    if (!s) return;
    if (s->placed && (s->x != x || s->y != y) && s->mapped && log_budget()) {
        char name[160];
        describe(s, name, sizeof(name));
        droiddeck_log("window", "moved %s to %d,%d", name, x, y);
    }
    s->hwnd = hwnd;
    s->x = x;
    s->y = y;
    s->placed = 1;
    if (s->mapped) apply_zorder();
    schedule_render();
}
static void desktop_set_zorder(struct wl_client *c, struct wl_resource *r, struct wl_array *hwnds) {
    size_t count = hwnds->size / sizeof(uint32_t);
    uint32_t *copy = count ? malloc(count * sizeof(uint32_t)) : NULL;
    if (count && !copy) return;
    if (count) memcpy(copy, hwnds->data, count * sizeof(uint32_t));
    free(g_zorder);
    g_zorder = copy;
    g_zorder_count = count;
    apply_zorder();
    schedule_render();
}
static const struct banner_desktop_v1_interface desktop_impl = {
    .destroy = desktop_destroy_req,
    .set_desktop = desktop_set_desktop,
    .set_window = desktop_set_window,
    .set_zorder = desktop_set_zorder,
};
static void bind_desktop(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &banner_desktop_v1_interface, ver, id);
    wl_resource_set_implementation(r, &desktop_impl, NULL, NULL);
}


static void output_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_output_interface output_impl = { .release = output_release };

static struct wl_list g_outputs;
static void output_resource_destroy(struct wl_resource *r) { wl_list_remove(wl_resource_get_link(r)); }

static void output_send_mode(struct wl_resource *r, int mhz) {
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED,
                        g_output_w > 0 ? g_output_w : 1920, g_output_h > 0 ? g_output_h : 1080,
                        mhz);
}

static void bind_output(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &output_impl, NULL, output_resource_destroy);
    wl_list_insert(&g_outputs, wl_resource_get_link(r));
    wl_output_send_geometry(r, 0, 0, 340, 190, WL_OUTPUT_SUBPIXEL_UNKNOWN,
                            "DroidDeck", "Wayland", WL_OUTPUT_TRANSFORM_NORMAL);
    output_send_mode(r, g_output_announced_refresh_mhz);
    if (ver >= WL_OUTPUT_NAME_SINCE_VERSION) {
        wl_output_send_name(r, "DroidDeck-1");
        wl_output_send_description(r, "DroidDeck display");
    }
    if (ver >= 2) {
        wl_output_send_scale(r, 1);
        wl_output_send_done(r);
    }
}


struct draw_list { struct vkp_draw *d; int n, cap; };

static struct vkp_image *surface_image(struct surface *s) {
    if (!s->has_content) return NULL;
    if (s->shm_img) return s->shm_img;
    return s->dmabuf_buf ? s->dmabuf_buf->img : NULL;
}

static struct surface *g_hdr_unimported;
static int g_hdr_unimported_rect[4], g_hdr_unimported_below;

static void note_hdr_unimported(const struct draw_list *dl, struct surface *s, int ox, int oy) {
    if (!s->dmabuf_buf || s->dmabuf_buf->img || !ahb_swapchain_has_ahb(s->dmabuf_buf)) return;
    const struct droiddeck_color *c = droiddeck_color_of(s->resource);
    if (!c || !c->dataspace) return;
    if (s->src_set && (s->src[0] != 0 || s->src[1] != 0 || (int)(s->src[2] + 0.5f) != s->buf_w ||
                       (int)(s->src[3] + 0.5f) != s->buf_h)) return;
    int dw, dh;
    surface_size(s, &dw, &dh);
    g_hdr_unimported = s;
    g_hdr_unimported_rect[0] = ox; g_hdr_unimported_rect[1] = oy;
    g_hdr_unimported_rect[2] = dw; g_hdr_unimported_rect[3] = dh;
    g_hdr_unimported_below = dl->n;
}

static int surface_translucent(const struct surface *s, int w, int h) {
    if (!s->buf_alpha) return 0;
    const struct client_info *ci = client_info_of(wl_resource_get_client(s->resource));
    if (!ci || !ci->declares_opaque) return 0;
    const int *o = s->opaque;
    return !(o[2] > 0 && o[0] <= 0 && o[1] <= 0 &&
             (long long)o[0] + o[2] >= w && (long long)o[1] + o[3] >= h);
}

static void add_surface(struct draw_list *dl, struct surface *s, int ox, int oy) {
    struct vkp_image *img = surface_image(s);
    float sx = 0, sy = 0, sw = (float)s->buf_w, sh = (float)s->buf_h;
    int dw, dh;

    if (!img) {
        if (g_zero_copy && droiddeck_color_hdr_open()) note_hdr_unimported(dl, s, ox, oy);
        return;
    }
    if (s->src_set) { sx = s->src[0]; sy = s->src[1]; sw = s->src[2]; sh = s->src[3]; }
    surface_size(s, &dw, &dh);
    if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
    if (dl->n == dl->cap) {
        int cap = dl->cap ? dl->cap * 2 : 32;
        struct vkp_draw *d = realloc(dl->d, (size_t)cap * sizeof(*d));
        if (!d) return;
        dl->d = d;
        dl->cap = cap;
    }
    dl->d[dl->n++] = (struct vkp_draw){img, sx, sy, sw, sh, ox, oy, dw, dh,
                                      surface_translucent(s, dw, dh)};
    s->drawn = 1;
}

static void add_tree(struct draw_list *dl, struct surface *s, int ox, int oy, int depth) {
    struct surface *c;
    if (depth > 8) return;
    wl_list_for_each(c, &s->children, child_link)
        if (c->below_parent) add_tree(dl, c, ox + c->sub_x, oy + c->sub_y, depth + 1);
    add_surface(dl, s, ox, oy);
    wl_list_for_each(c, &s->children, child_link)
        if (!c->below_parent) add_tree(dl, c, ox + c->sub_x, oy + c->sub_y, depth + 1);
}

static void scene_size(int *w, int *h) {
    struct surface *s;
    long long best = 0;

    if (g_desktop && g_desktop->has_content) {
        surface_size(g_desktop, w, h);
        g_desktop_w = *w;
        g_desktop_h = *h;
        return;
    }
    if (g_desktop && g_desktop_w > 0) { *w = g_desktop_w; *h = g_desktop_h; return; }
    *w = *h = 0;
    wl_list_for_each(s, &g_toplevels, toplevel_link) {
        int sw, sh;
        surface_size(s, &sw, &sh);
        if ((long long)sw * sh > best) { best = (long long)sw * sh; *w = sw; *h = sh; }
    }
    if (*w <= 0 || *h <= 0) { *w = INPUT_SPACE_W; *h = INPUT_SPACE_H; }
}

static void fire_all_frames(void) {
    struct surface *s;
    wl_list_for_each(s, &g_surfaces, link) fire_frames(&s->frames);
}

static void pace_without_output(void) {
    struct surface *s;
    wl_list_for_each(s, &g_surfaces, link) {
        fire_frames(&s->frames);
        feedback_discard_all(&s->feedback);
    }
    wl_display_flush_clients(g_display);
}

static int on_frame_timer(void *data) {
    pace_without_output();
    if (vkp_has_window()) schedule_render();
    else if (g_frame_timer) wl_event_source_timer_update(g_frame_timer, 16);
    return 0;
}

static int layer_candidate(const struct draw_list *dl, int scene_w, int scene_h) {
    for (int i = dl->n - 1; i >= 0 && i >= dl->n - 2; i--) {
        const struct vkp_draw *d = &dl->d[i];
        if (d->blend) continue;
        if (!vkp_image_is_dmabuf(d->img)) continue;
        if (d->dx != 0 || d->dy != 0 || d->dw != scene_w || d->dh != scene_h) continue;
        if (d->sx != 0 || d->sy != 0 || (int)d->sw != vkp_image_width(d->img) ||
            (int)d->sh != vkp_image_height(d->img)) continue;
        return i;
    }
    return -1;
}

static struct surface *surface_for_image(const struct vkp_image *img) {
    struct surface *s;
    wl_list_for_each(s, &g_surfaces, link)
        if (s->dmabuf_buf && s->dmabuf_buf->img == img) return s;
    return NULL;
}

static struct surface *ahb_layer_only_candidate(int scene_w, int scene_h) {
    struct surface *s;
    int w, h;
    if (wl_list_empty(&g_toplevels)) return NULL;
    s = wl_container_of(g_toplevels.prev, s, toplevel_link);
    if (!s->dmabuf_buf || s->dmabuf_buf->img || !ahb_swapchain_has_ahb(s->dmabuf_buf)) return NULL;
    if ((g_desktop && !s->placed) || s->x != 0 || s->y != 0 || s->src_set || s->dst_set) return NULL;
    if (!wl_list_empty(&s->children)) return NULL;
    if (g_hide_shell && !strcmp(client_name(wl_resource_get_client(s->resource)), "explorer.exe")) return NULL;
    surface_size(s, &w, &h);
    if (w != scene_w || h != scene_h || s->buf_w != scene_w || s->buf_h != scene_h) return NULL;
    return s;
}


struct hdr_scene {
    struct vkp_hdr_frame hf;
    unsigned char *is_hdr;
    int n_hdr;
    const struct droiddeck_color *color;
    struct surface *who;
    const char *why;
};

static struct surface *hdr_unimported_fullscreen(const struct draw_list *dl, int w, int h) {
    if (!g_hdr_unimported || g_hdr_unimported_below != dl->n) return NULL;
    if (g_hdr_unimported_rect[0] != 0 || g_hdr_unimported_rect[1] != 0 ||
        g_hdr_unimported_rect[2] != w || g_hdr_unimported_rect[3] != h) return NULL;
    return g_hdr_unimported;
}

static int is_hdr_surface(struct surface *s) {
    const struct droiddeck_color *c = s ? droiddeck_color_of(s->resource) : NULL;
    return c && c->dataspace;
}

static int hdr_plan(const struct draw_list *dl, int w, int h, int fx_on, int framegen, struct hdr_scene *hs) {
    memset(hs, 0, sizeof(*hs));
    if (!droiddeck_color_hdr_open()) return 0;
    struct surface *un = hdr_unimported_fullscreen(dl, w, h);
    if (!un) {
        struct surface *lo = ahb_layer_only_candidate(w, h);
        if (lo && is_hdr_surface(lo)) un = lo;
    }
    if (un && g_zero_copy) { hs->color = droiddeck_color_of(un->resource); hs->who = un; return 3; }
    hs->hf.tonemap = !droiddeck_color_output();
    if (dl->n <= 0 || !(hs->is_hdr = calloc((size_t)dl->n, 1))) return 0;
    for (int i = 0; i < dl->n; i++) {
        struct surface *s = surface_for_image(dl->d[i].img);
        if (!is_hdr_surface(s)) continue;
        hs->is_hdr[i] = 1;
        hs->n_hdr++;
        hs->color = droiddeck_color_of(s->resource);
        hs->who = s;
    }
    if (!hs->n_hdr) { free(hs->is_hdr); hs->is_hdr = NULL; return 0; }
    hs->hf.is_hdr = hs->is_hdr;
    hs->hf.color = hs->color;
    hs->hf.peak_nits = hs->color->max_cll > 0.0f ? hs->color->max_cll
                     : hs->color->has_st2086 ? hs->color->max_lum : 1000.0f;
    if (framegen) return 2;
    int li = g_zero_copy ? layer_candidate(dl, w, h) : -1;
    int over = li >= 0 ? dl->n - 1 - li : 0;
    if (!hs->hf.tonemap && !fx_on && li >= 0 && hs->is_hdr[li] &&
        (over == 0 || (over == 1 && sc_layer_overlay_affordable())))
        return 0;
    hs->why = hs->hf.tonemap ? "HDR output is switched off in the drawer"
            : fx_on ? "screen effects are on"
            : !g_zero_copy ? "zero-copy presentation is off"
            : (li >= 0 && hs->is_hdr[li]) ? "a window is above the game and this display cannot compose a second layer"
            : "the HDR game is not one fullscreen window";
    return 1;
}

static void hdr_note_route(int route, const struct hdr_scene *hs) {
    static int said = -1, said_tm = -1;
    static const char *said_why;
    const int tm = route != 0 && !droiddeck_color_output();
    if (route == said && tm == said_tm && (route != 1 || hs->why == said_why)) return;
    int was = said;
    said = route; said_why = hs->why; said_tm = tm;
    char name[160] = "the HDR game";
    if (hs->who) describe(hs->who, name, sizeof(name));
    switch (route) {
    case 1:
        if (tm)
            droiddeck_log("color", "tone-mapped picture for %s: %s - the whole scene is composed and tone-mapped to SDR "
                       "(HDR peak %.0f nits rolled off to SDR white, screen effects applied after it) on the game's "
                       "display layer, untagged", name, hs->why ? hs->why : "?", hs->hf.peak_nits);
        else
            droiddeck_log("color", "HDR picture for %s: %s - the whole scene is composed into one 10-bit PQ BT.2020 picture "
                       "(SDR content at %.0f nits, screen effects applied in 10-bit) on the game's display layer, tagged "
                       "BT2020_PQ", name, hs->why ? hs->why : "?", droiddeck_color_sdr_white());
        break;
    case 2:
        if (tm)
            droiddeck_log("color", "HDR with frame generation for %s, HDR output switched off: the scene is tone-mapped to "
                       "SDR and presented through an SDR screen swapchain", name);
        else
            droiddeck_log("color", "HDR with frame generation for %s: the scene is composed into PQ and presented through "
                       "the screen swapchain (HDR10 where the surface offers it, else tone-mapped to SDR)", name);
        break;
    case 3:
        if (tm)
            droiddeck_log("color", "HDR output is switched off, but %s's HDR frames cannot be imported by the compositor, "
                       "so nothing can tone-map them: they stay HDR on its own display layer", name);
        break;
    default:
        if (was == 1 || was == 2)
            droiddeck_log("color", "%s is back on its own display layer (no composition needed)", name);
        break;
    }
}

static void hdr_note_precedence(struct surface *hs, int fx_on, int framegen) {
    static int fx_said = -1, fg_said = -1;
    int fx_skip = hs && fx_on, fg_skip = hs && framegen;
    char name[160] = "the game";
    if (hs) describe(hs, name, sizeof(name));
    if (fx_skip != fx_said) {
        if (fx_skip)
            droiddeck_log("color", "screen effects are NOT applied to %s: its HDR frames cannot be imported by the "
                       "compositor, so only its own display layer can show them (as they are)", name);
        else if (fx_said == 1)
            droiddeck_log("color", "screen effects apply to the scene again (no HDR game on its layer, or effects off)");
        fx_said = fx_skip;
    }
    if (fg_skip != fg_said) {
        if (fg_skip)
            droiddeck_log("color", "frame generation is NOT applied to %s: its HDR frames cannot be imported by the "
                       "compositor, so only its own display layer can show them (as they are)", name);
        else if (fg_said == 1)
            droiddeck_log("color", "frame generation applies again (no HDR game on its layer, or frame generation off)");
        fg_said = fg_skip;
    }
}

static void hdr_count_copied(const struct draw_list *dl, const char *reason) {
    for (int i = 0; i < dl->n; i++) {
        struct surface *s = surface_for_image(dl->d[i].img);
        const struct droiddeck_color *c = s ? droiddeck_color_of(s->resource) : NULL;
        if (!c || !c->dataspace) continue;
        char name[160];
        describe(s, name, sizeof(name));
        droiddeck_color_frame_copied(name, reason);
    }
}

/*
 * Stream mode (the Xbox Cloud Gaming native player, in its own process): no Wayland clients, the scene is one
 * picture handed over as AHardwareBuffers (ImageReader images of the decoded video). Each buffer is imported once
 * and kept while the reader cycles through its few buffers; the newest frame is the scene, so the screen effects
 * and frame generation apply to it as to a game.
 */
#define STREAM_SLOTS 6
static pthread_mutex_t g_stream_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_stream_on;
static AHardwareBuffer *g_stream_pending;
static struct { AHardwareBuffer *ahb; struct vkp_image *img; uint64_t used; } g_stream_slots[STREAM_SLOTS];
static struct vkp_image *g_stream_img;
static uint64_t g_stream_seq;
static atomic_uint g_stream_frames;

static void stream_forget_all(void) {
    vkp_wait_idle();
    for (int i = 0; i < STREAM_SLOTS; i++) {
        if (g_stream_slots[i].img) vkp_image_destroy(g_stream_slots[i].img);
        if (g_stream_slots[i].ahb) AHardwareBuffer_release(g_stream_slots[i].ahb);
        g_stream_slots[i].img = NULL; g_stream_slots[i].ahb = NULL; g_stream_slots[i].used = 0;
    }
    g_stream_img = NULL;
}

/* Compositor thread: the newest handed-over buffer becomes the scene picture. */
static void stream_take(void) {
    pthread_mutex_lock(&g_stream_lock);
    AHardwareBuffer *ahb = g_stream_pending;
    g_stream_pending = NULL;
    pthread_mutex_unlock(&g_stream_lock);
    if (!ahb) return;
    if (!atomic_load(&g_stream_on)) { AHardwareBuffer_release(ahb); return; }
    int slot = -1, oldest = 0;
    for (int i = 0; i < STREAM_SLOTS; i++) {
        if (g_stream_slots[i].ahb == ahb) { slot = i; break; }
        if (g_stream_slots[i].used < g_stream_slots[oldest].used) oldest = i;
    }
    if (slot >= 0) {
        AHardwareBuffer_release(ahb); /* the slot keeps its own reference */
    } else {
        struct vkp_image *img = vkp_image_from_ahb(ahb);
        if (!img) {
            static int said;
            if (!said) { said = 1; droiddeck_log("stream", "a decoded frame could not be imported: nothing to show"); }
            AHardwareBuffer_release(ahb);
            return;
        }
        droiddeck_log("stream", "decoded frame buffer imported (%dx%d)", vkp_image_width(img), vkp_image_height(img));
        slot = oldest;
        if (g_stream_slots[slot].img) {
            vkp_wait_idle(); /* the GPU may still read the picture being replaced */
            if (g_stream_img == g_stream_slots[slot].img) g_stream_img = NULL;
            vkp_image_destroy(g_stream_slots[slot].img);
        }
        if (g_stream_slots[slot].ahb) AHardwareBuffer_release(g_stream_slots[slot].ahb);
        g_stream_slots[slot].ahb = ahb;
        g_stream_slots[slot].img = img;
    }
    g_stream_slots[slot].used = ++g_stream_seq;
    g_stream_img = g_stream_slots[slot].img;
    atomic_fetch_add(&g_stream_frames, 1);
    schedule_render();
}

static void render_stream(void) {
    g_dirty = 0;
    struct vkp_image *img = g_stream_img;
    if (!img) return;
    int w = vkp_image_width(img), h = vkp_image_height(img);
    if (w != g_scene_w || h != g_scene_h) {
        droiddeck_log("stream", "picture %dx%d", w, h);
        g_scene_w = w;
        g_scene_h = h;
    }
    struct vkp_draw d = {.img = img, .sx = 0, .sy = 0, .sw = (float)w, .sh = (float)h, .dx = 0, .dy = 0, .dw = w, .dh = h, .blend = 0};
    vkp_apply_window_request();
    sc_layer_hide();
    int r = vkp_render(w, h, &d, 1);
    if (r == 0) {
        g_stat_frames++;
        static int first;
        if (!first) { first = 1; droiddeck_log("stream", "first stream frame presented"); }
    } else {
        static int failed;
        if (failed++ % 600 == 0) droiddeck_log("stream", "presenting a stream frame failed (%d, %d times)", r, failed);
    }
}

void droiddeck_stream_set(int on) {
    atomic_store(&g_stream_on, on ? 1 : 0);
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 21, on ? 1 : 0, 0, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

/* Any thread: hands over one decoded frame (acquires its own reference). */
void droiddeck_stream_frame(AHardwareBuffer *ahb) {
    if (!ahb || !atomic_load(&g_stream_on) || g_input_pipe[1] < 0) return;
    AHardwareBuffer_acquire(ahb);
    pthread_mutex_lock(&g_stream_lock);
    AHardwareBuffer *dropped = g_stream_pending;
    g_stream_pending = ahb;
    pthread_mutex_unlock(&g_stream_lock);
    if (dropped) { AHardwareBuffer_release(dropped); return; } /* a wake-up is already queued */
    struct input_msg m = { 20, 0, 0, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

unsigned droiddeck_stream_frames(void) { return atomic_load(&g_stream_frames); }

static void render_scene(void) {
    if (atomic_load(&g_stream_on)) { g_dirty_since_ns = 0; render_stream(); return; }
    struct draw_list dl = {0};
    struct surface *s;
    int w, h;
    const int64_t t_scene = now_ns();
    int copy = 0;

    if (g_dirty_since_ns) {
        const int64_t wait = t_scene - g_dirty_since_ns;
        g_pace.wait_ns += wait;
        if (wait > g_pace.wait_max_ns) g_pace.wait_max_ns = wait;
        g_pace.renders++;
        g_dirty_since_ns = 0;
    }
    g_dirty = 0;
    g_rendered_tick_ns = g_tick_vsync_ns;
    g_tick_held = 0;
    g_hud_fresh = 0;
    g_hdr_unimported = NULL;
    wl_list_for_each(s, &g_surfaces, link) s->drawn = 0;
    scene_size(&w, &h);
    if (w != g_scene_w || h != g_scene_h) {
        if (g_desktop) droiddeck_log("desktop", "size %dx%d", w, h);
        else droiddeck_log("desktop", "no desktop: showing %dx%d (largest window)", w, h);
        g_scene_w = w;
        g_scene_h = h;
    }
    if (g_desktop && !g_hide_shell) add_tree(&dl, g_desktop, 0, 0, 0);
    wl_list_for_each(s, &g_toplevels, toplevel_link) {
        if (g_desktop && !s->placed) continue;
        if (g_hide_shell && !strcmp(client_name(wl_resource_get_client(s->resource)), "explorer.exe")) continue;
        add_tree(&dl, s, s->placed ? s->x : 0, s->placed ? s->y : 0, 0);
    }

    int rendered = 0;
    int fx_on = vkp_effects_active();
    int framegen = vkp_framegen_active();
    const int cardboard = vkp_cardboard_active();
    struct hdr_scene hs;
    int hdr_route = hdr_plan(&dl, w, h, fx_on, framegen, &hs);
    const char *hdr_copy = NULL;
    if (droiddeck_color_hdr_open()) {
        hdr_note_route(hdr_route, &hs);
        hdr_note_precedence(hdr_route == 3 ? hs.who : NULL, fx_on, framegen);
    }
    if (hdr_route == 3) { fx_on = 0; framegen = 0; }
    if (hdr_route == 1) {
        static int fell_back;
        vkp_apply_window_request();
        sc_layer_hide_overlay();
        if (sc_layer_present_hdr_scene(dl.d, dl.n, &hs.hf, w, h, hs.color) == 0) {
            rendered = vkp_base_black(w, h) == 0;
            if (fell_back) { fell_back = 0; droiddeck_log("color", "the composed picture is on the game's display layer again"); }
        } else {
            hdr_route = 2;
            if (!fell_back) {
                fell_back = 1;
                droiddeck_log("color", "the composed picture could not go on the game's display layer (no layer, or the "
                           "10-bit pass failed): it goes through the screen swapchain instead until that changes");
            }
        }
    }
    if (hdr_route == 2) {
        int how = 0;
        sc_layer_hide();
        rendered = vkp_render_hdr(w, h, dl.d, dl.n, &hs.hf, &how) == 0;
        copy = 1;
        if (rendered && how == 1) droiddeck_color_frame_shown(hs.color, DROIDDECK_HDR_SWAPCHAIN, 0);
        else if (rendered && how == 2) droiddeck_color_frame_shown(hs.color, DROIDDECK_HDR_TONEMAPPED, 0);
        else if (rendered) hdr_copy = "the HDR composition pass is unavailable on this driver";
    }
    if (hdr_route == 0 && vkp_quest_layer_presentation() && !framegen && !cardboard &&
        !ahb_layer_only_candidate(w, h)) {
        /* The complete SDR scene stays on one display layer. Do not hide/re-show GAME
         * on each desktop frame just because it is not a single fullscreen window. */
        rendered = vkp_render(w, h, dl.d, dl.n) == 0;
    } else if (hdr_route == 0 || hdr_route == 3) {
    const int pass_on = fx_on || framegen;
    int li = (g_zero_copy && !framegen && !cardboard) ? layer_candidate(&dl, w, h) : -1;
    int over = li >= 0 ? dl.n - 1 - li : 0;
    const int fx_blocks = (li >= 0 && fx_on && over > 0);
    if (fx_blocks) { li = -1; over = 0; }
    const int ov_blocks = (li >= 0 && over > 0 && !fx_blocks && !sc_layer_overlay_affordable());
    if (ov_blocks) { li = -1; over = 0; }
    const int blocked = framegen ? 1 : (fx_blocks ? 2 : (ov_blocks ? 3 : 0));
    if (g_zero_copy && blocked != g_zero_copy_paused) {
        g_zero_copy_paused = blocked;
        if (blocked == 1)
            droiddeck_log("framegen", "zero-copy paused: frame generation needs the compositor pass");
        else if (blocked == 2)
            droiddeck_log("effects", "zero-copy paused: a window above the game needs the compositor pass for the whole scene");
        else if (blocked == 3)
            droiddeck_log("layer", "zero-copy paused: a window above the game would need a second display layer, "
                                "which this display cannot compose in hardware - whole scene on the copy path");
        else
            droiddeck_log("effects", "zero-copy resumed: the game is back on its own display layer");
    }
    struct surface *ls = li >= 0 ? surface_for_image(dl.d[li].img) : NULL;
    if (li < 0) ls = ahb_layer_only_candidate(w, h);
    if (li < 0 && !ls) ls = hdr_unimported_fullscreen(&dl, w, h);
    if (cardboard) ls = NULL;
    if (ls && pass_on && li < 0 && !g_zero_copy_fx_skip_said) {
        g_zero_copy_fx_skip_said = 1;
        droiddeck_log(framegen ? "framegen" : "effects",
                   "the game's frames cannot be imported by the compositor: shown zero-copy, %s skipped",
                   framegen ? "frame generation" : "effects");
    }
    if (li >= 0 || ls) {
        vkp_apply_window_request();
        int r = -1;
        if (!(fx_on && li >= 0) && ls && ls->dmabuf_buf && ahb_swapchain_has_ahb(ls->dmabuf_buf))
            r = ahb_swapchain_present(ls->dmabuf_buf, ls, w, h);
        if (r != 0 && li >= 0)
            r = fx_on ? sc_layer_present_pass(&dl.d[li], 1, w, h)
                      : sc_layer_present(dl.d[li].img, w, h, ls ? droiddeck_surface_color(ls) : NULL);
        if (r == 0) {
            if (ls) ls->drawn = 1;
            int go[8], ov = 0;
            if (over == 1 && li >= 0 && vkp_map_draw(&dl.d[li + 1], go))
                ov = sc_layer_present_overlay(dl.d[li + 1].img, go, dl.d[li + 1].blend) == 0 ? 1 : -1;
            if (ov <= 0) sc_layer_hide_overlay();
            if (ov < 0) {
                sc_layer_hide();
                rendered = vkp_render(w, h, dl.d, dl.n) == 0;
                copy = 1;
                hdr_copy = "the overlay layer refused the window above it";
            } else {
                rendered = vkp_base_black(w, h) == 0;
            }
        } else {
            rendered = vkp_render(w, h, dl.d, dl.n) == 0;
            copy = 1;
            hdr_copy = "its display layer was unavailable this frame";
        }
    } else {
        sc_layer_hide();
        rendered = vkp_render(w, h, dl.d, dl.n) == 0;
        copy = 1;
        hdr_copy = ov_blocks ? "a window is above it and this display cannot compose a second layer in hardware"
                 : fx_blocks ? "a window is above it and screen effects need the whole scene"
                 : !g_zero_copy ? "zero-copy presentation is off"
                 : framegen ? "frame generation needs the compositor pass"
                 : "it is not the one fullscreen window (only that one gets its own display layer)";
    }
    }
    free(hs.is_hdr);
    if (hdr_copy && rendered && droiddeck_color_hdr_open()) hdr_count_copied(&dl, hdr_copy);
    if (rendered) {
        int64_t t = presented_ns(t_scene);
        g_stat_frames++;
        if (g_hud_new) {
            g_hud_new = 0;
            hud_frame_shown(t);
        }
        wl_list_for_each(s, &g_surfaces, link) {
            if (s->drawn) feedback_present_all(&s->feedback, t);
            else g_pace.undrawn += feedback_discard_all(&s->feedback);
        }
        fire_all_frames();
    } else {
        pace_without_output();
        if (!g_frame_timer)
            g_frame_timer = wl_event_loop_add_timer(wl_display_get_event_loop(g_display),
                                                    on_frame_timer, NULL);
        if (g_frame_timer) wl_event_source_timer_update(g_frame_timer, 16);
    }
    free(dl.d);
    wl_display_flush_clients(g_display);
    if (rendered && copy) g_perf.copy_scenes++;
    g_perf.scenes++;
    const int64_t scene_ns = now_ns() - t_scene;
    g_perf.scene_ns += scene_ns;
    if (scene_ns > g_perf.scene_max_ns) g_perf.scene_max_ns = scene_ns;
}

static int on_fallback_timer(void *data) {
    g_fallback_armed = 0;
    if (g_dirty) render_scene();
    return 0;
}

/* A new frame is drawn when it arrives instead of at the next screen tick, at most once per refresh,
   while frames go straight back to the game (no compositor limit), without streaming or frame generation. */
static int commit_renders_on(void) {
    return g_render_on_commit && g_release_unpaced && !atomic_load(&g_stream_on) && !vkp_framegen_active();
}

static int render_on_commit(void) {
    return commit_renders_on() && !g_render_idle && g_rendered_tick_ns != g_tick_vsync_ns;
}

static void schedule_render(void) {
    if (!g_dirty && !g_dirty_since_ns) g_dirty_since_ns = now_ns();
    g_dirty = 1;
    if (!g_display) return;
    if (g_last_vsync_ns && now_ns() - g_last_vsync_ns < 100000000LL) {
        if (render_on_commit())
            g_render_idle = wl_event_loop_add_idle(wl_display_get_event_loop(g_display), on_commit_idle, NULL);
        return;
    }
    if (g_fallback_armed) return;
    if (!g_fallback_timer)
        g_fallback_timer = wl_event_loop_add_timer(wl_display_get_event_loop(g_display),
                                                   on_fallback_timer, NULL);
    if (!g_fallback_timer) return;
    g_fallback_armed = 1;
    wl_event_source_timer_update(g_fallback_timer, 8);
}

static int scene_waits_for_game(int64_t now) {
    return g_hud_surface && !g_hud_fresh && now - g_hud_last_ns < 100000000LL && vkp_framegen_active();
}

static void on_commit_idle(void *data) {
    g_render_idle = NULL;
    if (!g_dirty || !commit_renders_on() || g_rendered_tick_ns == g_tick_vsync_ns || scene_waits_for_game(now_ns())) return;
    g_pace.commit_renders++;
    render_scene();
}

static void on_vsync(int64_t frame_time_ns) {
    int64_t now = now_ns();
    g_perf.ticks++;
    if (frame_time_ns > 0 && now >= frame_time_ns) {
        const int64_t late = now - frame_time_ns;
        g_pace.ticks++;
        g_pace.tick_late_ns += late;
        if (late > g_pace.tick_late_max_ns) g_pace.tick_late_max_ns = late;
        if (late > 2000000LL) g_pace.ticks_late_2ms++;
    }
    if (g_last_vsync_ns) {
        int64_t d = now - g_last_vsync_ns;
        if (d > 3000000LL && d < 40000000LL) g_refresh_ns = (g_refresh_ns * 7 + d) / 8;
    }
    g_last_vsync_ns = now;
    g_tick_vsync_ns = frame_time_ns;
    int tick_change = 0;
    if (vkp_apply_window_request()) tick_change = 1;
    if (vkp_effects_sync()) tick_change = 1;
    if (vkp_cardboard_pose_changed()) tick_change = 1;
    if (tick_change) g_dirty = 1;
    /* A frame that arrived after this refresh was already drawn is left for the next arrival to draw;
       drawing it here would block that arrival too and keep every frame waiting for the tick. A client
       that draws again only after its frame callback sends no such arrival: once a held frame is still
       waiting at the next tick, the tick draws every frame for a second before holding one again. */
    if (g_tick_held && g_dirty) g_hold_missed_ns = now;
    g_tick_held = 0;
    if (!tick_change && commit_renders_on() && g_dirty_since_ns && now - g_dirty_since_ns < g_refresh_ns &&
        now - g_hold_missed_ns >= HOLD_RETRY_NS) {
        g_tick_held = 1;
        return;
    }
    if (g_dirty && !scene_waits_for_game(now)) render_scene();
}


static uint32_t now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                               struct wl_resource *surface, int32_t hx, int32_t hy) {
    if (!surface) {
        if (g_cursor_surface) g_cursor_surface->role = ROLE_NONE;
        g_cursor_surface = NULL;
        cursor_publish_hidden();
        return;
    }
    struct surface *s = wl_resource_get_user_data(surface);
    if (!s) return;
    if (s->role != ROLE_NONE && s->role != ROLE_CURSOR) return;
    s->role = ROLE_CURSOR;
    g_cursor_surface = s;
    g_cursor_hx = hx;
    g_cursor_hy = hy;
    struct wl_resource *buffer = surface_take_idle(s);
    if (buffer) {
        cursor_publish_buffer(s, buffer);
        wl_buffer_send_release(buffer);
    } else if (g_cursor_shown == s) {
        cursor_publish_shown();
    } else if (s->idle_px) {
        cursor_publish_pixels((const uint8_t *)s->idle_px, s->idle_w, s->idle_h, (size_t)s->idle_w * 4, hx, hy);
        g_cursor_shown = s;
    }
}
static void pointer_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor, .release = pointer_release,
};
static void pointer_res_destroy(struct wl_resource *r) {
    constraints_pointer_gone(r);
    relative_pointers_pointer_gone(r);
    for (int i = 0; i < g_nptrs; i++)
        if (g_ptrs[i].ptr == r) { g_ptrs[i] = g_ptrs[--g_nptrs]; break; }
}

static void keyboard_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_keyboard_interface keyboard_impl = { .release = keyboard_release };
static void keyboard_res_destroy(struct wl_resource *r) {
    for (int i = 0; i < g_nkbs; i++)
        if (g_kbs[i].kb == r) { g_kbs[i] = g_kbs[--g_nkbs]; break; }
}
static void touch_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_touch_interface touch_impl = { .release = touch_release };
static void touch_res_destroy(struct wl_resource *r) {
    for (int i = 0; i < g_ntouches; i++)
        if (g_touches[i].touch == r) { g_touches[i] = g_touches[--g_ntouches]; break; }
    publish_query_state();
}

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p = wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &pointer_impl, NULL, pointer_res_destroy);
    if (g_nptrs < MAX_PTRS) { g_ptrs[g_nptrs].ptr = p; g_ptrs[g_nptrs].focus = NULL; g_nptrs++; }
}
static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *k = wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
    if (!k) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(k, &keyboard_impl, NULL, keyboard_res_destroy);
    if (g_nkbs < MAX_PTRS) { g_kbs[g_nkbs].kb = k; g_kbs[g_nkbs].focus = NULL; g_nkbs++; }
    const char *rt = getenv("XDG_RUNTIME_DIR");
    char path[512];
    snprintf(path, sizeof(path), "%s/keymap.xkb", rt ? rt : ".");
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        struct stat st;
        if (fstat(fd, &st) == 0 && st.st_size > 0)
            wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t)st.st_size);
        close(fd);
    } else {
        WLOGE("keymap open failed: %s", path);
    }
    if (wl_resource_get_version(k) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION)
        wl_keyboard_send_repeat_info(k, 25, 500);
}
static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *t = wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id);
    if (!t) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(t, &touch_impl, NULL, touch_res_destroy);
    if (g_ntouches < MAX_PTRS) {
        g_touches[g_ntouches].touch = t;
        g_touches[g_ntouches].focus = NULL;
        g_ntouches++;
    }
    publish_query_state();
}
static void seat_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer, .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch, .release = seat_release,
};
static void bind_seat(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, ver, id);
    wl_resource_set_implementation(r, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH);
    if (ver >= 2) wl_seat_send_name(r, "droiddeck-seat");
}

static struct seat_pointer *pointer_for(struct wl_client *client) {
    for (int i = 0; i < g_nptrs; i++)
        if (wl_resource_get_client(g_ptrs[i].ptr) == client) return &g_ptrs[i];
    return NULL;
}
static struct seat_keyboard *keyboard_for(struct wl_client *client) {
    for (int i = 0; i < g_nkbs; i++)
        if (wl_resource_get_client(g_kbs[i].kb) == client) return &g_kbs[i];
    return NULL;
}

#define for_each_pointer_of(client, sp) \
    for (int _i = 0; _i < g_nptrs; _i++) \
        if (((sp) = &g_ptrs[_i]), wl_resource_get_client((sp)->ptr) == (client))
#define for_each_keyboard_of(client, sk) \
    for (int _i = 0; _i < g_nkbs; _i++) \
        if (((sk) = &g_kbs[_i]), wl_resource_get_client((sk)->kb) == (client))
#define for_each_touch_of(client, st) \
    for (int _i = 0; _i < g_ntouches; _i++) \
        if (((st) = &g_touches[_i]), wl_resource_get_client((st)->touch) == (client))

static struct surface *toplevel_at(double x, double y) {
    struct surface *s;
    wl_list_for_each_reverse(s, &g_toplevels, toplevel_link) {
        int w, h, sx = s->placed ? s->x : 0, sy = s->placed ? s->y : 0;
        surface_size(s, &w, &h);
        if (x >= sx && y >= sy && x < sx + w && y < sy + h) return s;
    }
    return NULL;
}

static void pointer_focus(struct wl_resource *target, wl_fixed_t fx, wl_fixed_t fy) {
    struct wl_client *client = wl_resource_get_client(target);
    for (int i = 0; i < g_nptrs; i++) {
        if (!g_ptrs[i].focus || g_ptrs[i].focus == target) continue;
        wl_pointer_send_leave(g_ptrs[i].ptr, wl_display_next_serial(g_display), g_ptrs[i].focus);
        if (wl_resource_get_version(g_ptrs[i].ptr) >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(g_ptrs[i].ptr);
        g_ptrs[i].focus = NULL;
    }
    struct seat_pointer *sp;
    int entered = 0;
    for_each_pointer_of(client, sp) {
        if (sp->focus == target) continue;
        sp->focus = target;
        wl_pointer_send_enter(sp->ptr, wl_display_next_serial(g_display), target, fx, fy);
        entered = 1;
    }
    if (entered) constraints_focus_entered(target, client);
}

static void keyboard_focus(struct wl_resource *target) {
    struct wl_client *client = wl_resource_get_client(target);
    struct seat_keyboard *sk;
    int need = 0;
    for_each_keyboard_of(client, sk) if (sk->focus != target) need = 1;
    if (!need) return;
    for (int i = 0; i < g_nkbs; i++) {
        if (!g_kbs[i].focus || g_kbs[i].focus == target) continue;
        wl_keyboard_send_leave(g_kbs[i].kb, wl_display_next_serial(g_display), g_kbs[i].focus);
        g_kbs[i].focus = NULL;
    }
    struct wl_array keys;
    wl_array_init(&keys);
    for_each_keyboard_of(client, sk) {
        if (sk->focus == target) continue;
        sk->focus = target;
        wl_keyboard_send_enter(sk->kb, wl_display_next_serial(g_display), target, &keys);
        wl_keyboard_send_modifiers(sk->kb, wl_display_next_serial(g_display),
                                   g_mods_depressed, 0, g_mods_locked, 0);
    }
    wl_array_release(&keys);
    droiddeck_clipboard_keyboard_focus(client);
}


struct constraint {
    struct wl_list link;
    struct wl_resource *resource;
    struct wl_resource *pointer;
    struct surface *surface;
    int is_lock;
    uint32_t lifetime;
    int active;
    int defunct;
    struct region region, pending_region;
    int pending_region_set;
    int hint_set, pending_hint_set;
    double hint_x, hint_y, pending_hint_x, pending_hint_y;
    int gave_keys;
};
static struct wl_list g_constraints;
static struct constraint *g_active_constraint;

struct relative_pointer {
    struct wl_list link;
    struct wl_resource *resource;
    struct wl_resource *pointer;
};
static struct wl_list g_relative_pointers;

static double g_ptr_x, g_ptr_y;
static int g_ptr_known;
static double g_raw_x, g_raw_y;
static int g_raw_valid;

extern void droiddeck_on_pointer_lock(int locked, int x, int y);

static void sync_lock_notify(void) {
    static int last;
    int locked = g_active_constraint && g_active_constraint->is_lock;
    if (locked == last) return;
    last = locked;
    droiddeck_on_pointer_lock(locked, (int)g_ptr_x, (int)g_ptr_y);
}

static void constraint_describe(const struct constraint *k, char *out, size_t size) {
    if (k->surface) describe(k->surface, out, size);
    else snprintf(out, size, "a closed window");
}

static void constraint_origin(const struct constraint *k, int *x, int *y) {
    *x = *y = 0;
    if (k->surface && k->surface != g_desktop && k->surface->placed) { *x = k->surface->x; *y = k->surface->y; }
}

static void confine_clamp(const struct constraint *k, double *x, double *y) {
    int ox, oy, w, h, rx = 0, ry = 0, rw, rh;
    if (!k->surface) return;
    constraint_origin(k, &ox, &oy);
    surface_size(k->surface, &w, &h);
    rw = w; rh = h;
    if (k->region.set) {
        int x2 = rx + rw < k->region.x + k->region.w ? rx + rw : k->region.x + k->region.w;
        int y2 = ry + rh < k->region.y + k->region.h ? ry + rh : k->region.y + k->region.h;
        if (k->region.x > rx) rx = k->region.x;
        if (k->region.y > ry) ry = k->region.y;
        rw = x2 - rx; rh = y2 - ry;
    }
    if (rw <= 0 || rh <= 0) return;
    if (*x < ox + rx) *x = ox + rx;
    if (*x > ox + rx + rw - 1) *x = ox + rx + rw - 1;
    if (*y < oy + ry) *y = oy + ry;
    if (*y > oy + ry + rh - 1) *y = oy + ry + rh - 1;
}

static void constraint_send_state(struct constraint *k, int on) {
    if (k->is_lock) {
        if (on) zwp_locked_pointer_v1_send_locked(k->resource);
        else zwp_locked_pointer_v1_send_unlocked(k->resource);
    } else {
        if (on) zwp_confined_pointer_v1_send_confined(k->resource);
        else zwp_confined_pointer_v1_send_unconfined(k->resource);
    }
}

static void constraint_end(struct constraint *k, int tell_client, const char *why) {
    char name[160];
    if (!k->active) return;
    k->active = 0;
    if (g_active_constraint == k) g_active_constraint = NULL;
    if (k->lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT) k->defunct = 1;
    if (k->is_lock && k->hint_set && k->surface) {
        int ox, oy;
        constraint_origin(k, &ox, &oy);
        g_ptr_x = ox + k->hint_x;
        g_ptr_y = oy + k->hint_y;
        g_ptr_known = 1;
    }
    g_raw_valid = 0;
    if (k->gave_keys) {
        k->gave_keys = 0;
        for (int i = 0; i < g_nkbs; i++) {
            if (!k->surface || g_kbs[i].focus != k->surface->resource) continue;
            wl_keyboard_send_leave(g_kbs[i].kb, wl_display_next_serial(g_display), g_kbs[i].focus);
            g_kbs[i].focus = NULL;
        }
    }
    if (tell_client) constraint_send_state(k, 0);
    constraint_describe(k, name, sizeof(name));
    droiddeck_log("pointer", "%s: %s (%s), pointer at %d,%d", k->is_lock ? "unlocked" : "unconfined",
               name, why, (int)g_ptr_x, (int)g_ptr_y);
    sync_lock_notify();
}

static void constraint_activate(struct constraint *k) {
    char name[160];
    int ox, oy;
    if (k->active || k->defunct || !k->surface || !k->pointer) return;
    if (g_active_constraint && g_active_constraint != k)
        constraint_end(g_active_constraint, 1, "replaced by a newer request");
    k->active = 1;
    g_active_constraint = k;
    if (!k->is_lock) confine_clamp(k, &g_ptr_x, &g_ptr_y);
    constraint_origin(k, &ox, &oy);
    pointer_focus(k->surface->resource, wl_fixed_from_double(g_ptr_x - ox), wl_fixed_from_double(g_ptr_y - oy));
    int focused = 0;
    for (int i = 0; i < g_nkbs; i++) if (g_kbs[i].focus) focused = 1;
    if (k->is_lock && !focused && keyboard_for(wl_resource_get_client(k->surface->resource))) {
        keyboard_focus(k->surface->resource);
        k->gave_keys = 1;
    }
    constraint_send_state(k, 1);
    constraint_describe(k, name, sizeof(name));
    if (k->is_lock)
        droiddeck_log("pointer", "locked: %s (%s), pointer frozen at %d,%d", name,
                   k->lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT ? "oneshot" : "persistent",
                   (int)g_ptr_x, (int)g_ptr_y);
    else if (k->region.set)
        droiddeck_log("pointer", "confined: %s (%s) to %dx%d at %d,%d of the window", name,
                   k->lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT ? "oneshot" : "persistent",
                   k->region.w, k->region.h, k->region.x, k->region.y);
    else
        droiddeck_log("pointer", "confined: %s (%s) to the whole window", name,
                   k->lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT ? "oneshot" : "persistent");
    sync_lock_notify();
    wl_display_flush_clients(g_display);
}

static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client) {
    struct constraint *k;
    if (g_active_constraint) return;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->active || k->defunct || !k->surface || !k->pointer) continue;
        if (k->surface->resource != target || wl_resource_get_client(k->pointer) != client) continue;
        constraint_activate(k);
        return;
    }
}

static void constraints_surface_gone(struct surface *s) {
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->surface != s) continue;
        constraint_end(k, 1, "window closed");
        k->surface = NULL;
        k->defunct = 1;
    }
}

static void constraints_pointer_gone(struct wl_resource *pointer) {
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->pointer != pointer) continue;
        constraint_end(k, 1, "pointer released");
        k->pointer = NULL;
        k->defunct = 1;
    }
}

static void constraints_surface_commit(struct surface *s) {
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->surface != s) continue;
        if (k->pending_hint_set) {
            k->hint_set = 1;
            k->hint_x = k->pending_hint_x;
            k->hint_y = k->pending_hint_y;
            k->pending_hint_set = 0;
            if (k->active && k->is_lock) {
                int ox, oy;
                constraint_origin(k, &ox, &oy);
                g_ptr_x = ox + k->hint_x;
                g_ptr_y = oy + k->hint_y;
                g_ptr_known = 1;
                if (log_budget())
                    droiddeck_log("pointer", "position hint: pointer moved to %d,%d", (int)g_ptr_x, (int)g_ptr_y);
            }
        }
        if (k->pending_region_set) {
            k->region = k->pending_region;
            k->pending_region_set = 0;
            if (k->active && !k->is_lock) confine_clamp(k, &g_ptr_x, &g_ptr_y);
        }
    }
}

static void constraint_resource_destroy(struct wl_resource *r) {
    struct constraint *k = wl_resource_get_user_data(r);
    if (!k) return;
    constraint_end(k, 0, "released by the program");
    wl_list_remove(&k->link);
    free(k);
}

static void constraint_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void locked_pointer_set_cursor_position_hint(struct wl_client *c, struct wl_resource *r,
                                                    wl_fixed_t x, wl_fixed_t y) {
    struct constraint *k = wl_resource_get_user_data(r);
    if (!k) return;
    k->pending_hint_set = 1;
    k->pending_hint_x = wl_fixed_to_double(x);
    k->pending_hint_y = wl_fixed_to_double(y);
}
static void locked_pointer_set_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {
}
static const struct zwp_locked_pointer_v1_interface locked_pointer_impl = {
    .destroy = constraint_destroy_req,
    .set_cursor_position_hint = locked_pointer_set_cursor_position_hint,
    .set_region = locked_pointer_set_region,
};
static void confined_pointer_set_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {
    struct constraint *k = wl_resource_get_user_data(r);
    struct region *rg = region ? wl_resource_get_user_data(region) : NULL;
    if (!k) return;
    k->pending_region_set = 1;
    if (rg) k->pending_region = *rg;
    else memset(&k->pending_region, 0, sizeof(k->pending_region));
}
static const struct zwp_confined_pointer_v1_interface confined_pointer_impl = {
    .destroy = constraint_destroy_req,
    .set_region = confined_pointer_set_region,
};

static void constraint_create(struct wl_client *c, struct wl_resource *r, uint32_t id,
                              struct wl_resource *surface_res, struct wl_resource *pointer_res,
                              struct wl_resource *region_res, uint32_t lifetime, int is_lock) {
    struct surface *s = wl_resource_get_user_data(surface_res);
    struct constraint *k;
    struct wl_resource *res;
    char name[160];

    wl_list_for_each(k, &g_constraints, link) {
        if (k->defunct || k->surface != s || k->pointer != pointer_res) continue;
        wl_resource_post_error(r, ZWP_POINTER_CONSTRAINTS_V1_ERROR_ALREADY_CONSTRAINED,
                               "the surface already has a pointer constraint for this pointer");
        return;
    }
    res = wl_resource_create(c, is_lock ? &zwp_locked_pointer_v1_interface : &zwp_confined_pointer_v1_interface,
                             wl_resource_get_version(r), id);
    if (!res || !(k = calloc(1, sizeof(*k)))) { wl_client_post_no_memory(c); return; }
    k->resource = res;
    k->pointer = pointer_res;
    k->surface = s;
    k->is_lock = is_lock;
    k->lifetime = lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT
                  ? ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT : ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT;
    if (!is_lock && region_res) {
        struct region *rg = wl_resource_get_user_data(region_res);
        if (rg) k->region = *rg;
    }
    wl_resource_set_implementation(res, is_lock ? (const void *)&locked_pointer_impl : (const void *)&confined_pointer_impl,
                                   k, constraint_resource_destroy);
    wl_list_insert(g_constraints.prev, &k->link);
    if (!s) { k->defunct = 1; return; }
    constraint_describe(k, name, sizeof(name));
    droiddeck_log("pointer", "%s requested by %s for %s", is_lock ? "lock" : "confine", client_name(c), name);
    constraint_activate(k);
}

static void pointer_constraints_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void pointer_constraints_lock_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                             struct wl_resource *surface, struct wl_resource *pointer,
                                             struct wl_resource *region, uint32_t lifetime) {
    constraint_create(c, r, id, surface, pointer, region, lifetime, 1);
}
static void pointer_constraints_confine_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                                struct wl_resource *surface, struct wl_resource *pointer,
                                                struct wl_resource *region, uint32_t lifetime) {
    constraint_create(c, r, id, surface, pointer, region, lifetime, 0);
}
static const struct zwp_pointer_constraints_v1_interface pointer_constraints_impl = {
    .destroy = pointer_constraints_destroy,
    .lock_pointer = pointer_constraints_lock_pointer,
    .confine_pointer = pointer_constraints_confine_pointer,
};
static void bind_pointer_constraints(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_pointer_constraints_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &pointer_constraints_impl, NULL, NULL);
}


static void relative_pointer_resource_destroy(struct wl_resource *r) {
    struct relative_pointer *rp = wl_resource_get_user_data(r);
    if (!rp) return;
    droiddeck_log("pointer", "relative pointer released by %s", client_name(wl_resource_get_client(r)));
    wl_list_remove(&rp->link);
    free(rp);
}
static void relative_pointer_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_relative_pointer_v1_interface relative_pointer_impl = {
    .destroy = relative_pointer_destroy_req,
};

static void relative_pointer_manager_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void relative_pointer_manager_get(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                         struct wl_resource *pointer) {
    struct wl_resource *res = wl_resource_create(c, &zwp_relative_pointer_v1_interface, wl_resource_get_version(r), id);
    struct relative_pointer *rp;
    if (!res || !(rp = calloc(1, sizeof(*rp)))) { wl_client_post_no_memory(c); return; }
    rp->resource = res;
    rp->pointer = pointer;
    wl_resource_set_implementation(res, &relative_pointer_impl, rp, relative_pointer_resource_destroy);
    wl_list_insert(g_relative_pointers.prev, &rp->link);
    droiddeck_log("pointer", "relative pointer created for %s (motion arrives as deltas)", client_name(c));
}
static const struct zwp_relative_pointer_manager_v1_interface relative_pointer_manager_impl = {
    .destroy = relative_pointer_manager_destroy,
    .get_relative_pointer = relative_pointer_manager_get,
};
static void bind_relative_pointer_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_relative_pointer_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &relative_pointer_manager_impl, NULL, NULL);
}

static void relative_pointers_pointer_gone(struct wl_resource *pointer) {
    struct relative_pointer *rp;
    wl_list_for_each(rp, &g_relative_pointers, link)
        if (rp->pointer == pointer) rp->pointer = NULL;
}

static int send_relative_motion(struct wl_client *client, double dx, double dy) {
    struct relative_pointer *rp;
    struct timespec ts;
    uint64_t ut;
    int sent = 0;
    if (dx == 0 && dy == 0) return 0;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    ut = (uint64_t)ts.tv_sec * 1000000ULL + (uint64_t)ts.tv_nsec / 1000;
    wl_list_for_each(rp, &g_relative_pointers, link) {
        if (!rp->pointer || wl_resource_get_client(rp->pointer) != client) continue;
        zwp_relative_pointer_v1_send_relative_motion(rp->resource, (uint32_t)(ut >> 32), (uint32_t)ut,
                                                     wl_fixed_from_double(dx), wl_fixed_from_double(dy),
                                                     wl_fixed_from_double(dx), wl_fixed_from_double(dy));
        sent = 1;
    }
    return sent;
}


static void pointer_input(double x, double y, int relative, uint32_t button, int pressed) {
    struct surface *target;
    struct constraint *k = g_active_constraint;
    double dx, dy;

    if (relative) {
        dx = x; dy = y;
    } else {
        dx = g_raw_valid ? x - g_raw_x : 0;
        dy = g_raw_valid ? y - g_raw_y : 0;
        g_raw_x = x; g_raw_y = y; g_raw_valid = 1;
    }

    if (k && k->is_lock) {
        target = k->surface;
    } else {
        if (relative) {
            int w, h;
            scene_size(&w, &h);
            x = g_ptr_x + dx; y = g_ptr_y + dy;
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            if (x > w - 1) x = w - 1;
            if (y > h - 1) y = h - 1;
        }
        if (k) confine_clamp(k, &x, &y);
        g_ptr_x = x; g_ptr_y = y;
        g_ptr_known = 1;
        if (k) {
            target = k->surface;
        } else if (g_desktop) {
            target = g_desktop;
        } else {
            target = g_grab ? g_grab : toplevel_at(x, y);
            if (button && pressed) { g_grab = target; g_key_target = target; }
            if (button && !pressed) g_grab = NULL;
        }
    }
    publish_query_state();
    if (!target) return;

    struct wl_client *client = wl_resource_get_client(target->resource);
    struct seat_pointer *sp;
    if (!pointer_for(client)) return;
    int tx = 0, ty = 0;
    if (target != g_desktop && target->placed) { tx = target->x; ty = target->y; }
    wl_fixed_t fx = wl_fixed_from_double(g_ptr_x - tx), fy = wl_fixed_from_double(g_ptr_y - ty);
    uint32_t t = now_ms();

    pointer_focus(target->resource, fx, fy);
    // A button-only input must not replay a cached absolute position. A game
    // may have warped its cursor since the last motion (before taking lock,
    // or while releasing it), so that replay can become an unintended pan.
    // Absolute touch events still carry their position, including on clicks.
    int rel_consumed = send_relative_motion(client, dx, dy);
    if (!(k && k->is_lock) && !(relative && rel_consumed) && (!relative || dx != 0 || dy != 0))
        for_each_pointer_of(client, sp) wl_pointer_send_motion(sp->ptr, t, fx, fy);
    for_each_pointer_of(client, sp) {
        if (button)
            wl_pointer_send_button(sp->ptr, wl_display_next_serial(g_display), t, button,
                                   pressed ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
        if (wl_resource_get_version(sp->ptr) >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(sp->ptr);
    }
    if (button && pressed && !(k && k->is_lock)) {
        struct surface *clicked = toplevel_at(g_ptr_x, g_ptr_y);
        if (clicked) g_ime_click = clicked;
        droiddeck_text_input_refocus();
    }
    wl_display_flush_clients(g_display);
}

static void pointer_event(double x, double y, uint32_t button, int pressed) {
    pointer_input(x, y, 0, button, pressed);
}

static void pointer_delta(double dx, double dy) {
    pointer_input(dx, dy, 1, 0, 0);
}

static void pointer_button(uint32_t button, int pressed) {
    if (pressed) {
        pointer_input(0, 0, 1, button, pressed);
        return;
    }
    // Releases also clean up interrupted gestures. Deliver them to the existing
    // pointer focus without motion or hit-testing: native touch has independent
    // coordinates, so replaying g_ptr_x/y here can warp the game cursor.
    uint32_t t = now_ms();
    for (int i = 0; i < g_nptrs; i++) {
        struct seat_pointer *sp = &g_ptrs[i];
        if (!sp->focus) continue;
        wl_pointer_send_button(sp->ptr, wl_display_next_serial(g_display), t, button,
                               WL_POINTER_BUTTON_STATE_RELEASED);
        if (wl_resource_get_version(sp->ptr) >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(sp->ptr);
    }
    g_grab = NULL;
    wl_display_flush_clients(g_display);
}

static void deliver_pointer(const struct input_msg *m) {
    int w, h, ow, oh;
    double x, y;
    scene_size(&w, &h);
    vkp_output_size(&ow, &oh);
    if (ow <= 0 || oh <= 0 ||
        !vkp_output_to_scene((double)m->p2 * ow / INPUT_SPACE_W, (double)m->p3 * oh / INPUT_SPACE_H, &x, &y)) {
        x = (double)m->p2 * w / INPUT_SPACE_W;
        y = (double)m->p3 * h / INPUT_SPACE_H;
    }
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x > w - 1) x = w - 1;
    if (y > h - 1) y = h - 1;
    pointer_event(x, y, m->p1 == 1 ? 0 : BTN_LEFT, m->p1 == 0);
}

static void input_output_to_scene(int px, int py, double *x, double *y) {
    int w, h, ow, oh;
    scene_size(&w, &h);
    vkp_output_size(&ow, &oh);
    if (ow <= 0 || oh <= 0 ||
        !vkp_output_to_scene((double)px * ow / INPUT_SPACE_W, (double)py * oh / INPUT_SPACE_H, x, y)) {
        *x = (double)px * w / INPUT_SPACE_W;
        *y = (double)py * h / INPUT_SPACE_H;
    }
    if (*x < 0) *x = 0;
    if (*y < 0) *y = 0;
    if (*x > w - 1) *x = w - 1;
    if (*y > h - 1) *y = h - 1;
}

static int touch_points_for_client(struct wl_client *client) {
    for (int i = 0; i < MAX_TOUCH_POINTS; i++) {
        struct surface *surface = g_touch_points[i].surface;
        if (surface && wl_resource_get_client(surface->resource) == client) return 1;
    }
    return 0;
}

static void touch_frame(struct wl_client *client) {
    struct seat_touch *st;
    for_each_touch_of(client, st)
        if (wl_resource_get_version(st->touch) >= WL_TOUCH_FRAME_SINCE_VERSION)
            wl_touch_send_frame(st->touch);
}

static void touch_cancel_client(struct wl_client *client) {
    struct seat_touch *st;
    int release_pointer = 0;
    for_each_touch_of(client, st) {
        if (st->focus) wl_touch_send_cancel(st->touch);
        st->focus = NULL;
    }
    for (int i = 0; i < MAX_TOUCH_POINTS; i++) {
        struct surface *surface = g_touch_points[i].surface;
        if (surface && wl_resource_get_client(surface->resource) == client) {
            release_pointer |= g_touch_points[i].pointer_fallback;
            memset(&g_touch_points[i], 0, sizeof(g_touch_points[i]));
        }
    }
    if (release_pointer) pointer_button(BTN_LEFT, 0);
    wl_display_flush_clients(g_display);
}

static void touch_cancel_all(void) {
    int release_pointer = 0;
    for (int i = 0; i < g_ntouches; i++) {
        if (g_touches[i].focus) wl_touch_send_cancel(g_touches[i].touch);
        g_touches[i].focus = NULL;
    }
    for (int i = 0; i < MAX_TOUCH_POINTS; i++)
        release_pointer |= g_touch_points[i].pointer_fallback;
    memset(g_touch_points, 0, sizeof(g_touch_points));
    if (release_pointer) pointer_button(BTN_LEFT, 0);
    wl_display_flush_clients(g_display);
}

static void touch_cancel_surface(struct surface *surface) {
    struct wl_client *client = wl_resource_get_client(surface->resource);
    for (int i = 0; i < MAX_TOUCH_POINTS; i++)
        if (g_touch_points[i].surface == surface) {
            touch_cancel_client(client);
            return;
        }
}

static struct surface *touch_target_at(double x, double y) {
    struct surface *target = toplevel_at(x, y);
    if (target) return target;
    if (g_desktop) return g_desktop;
    return g_grab ? g_grab : g_key_target;
}

static pthread_mutex_t g_query_lock = PTHREAD_MUTEX_INITIALIZER;
struct query_snapshot {
    int has_position;
    int input_x, input_y;
    int focus_takes_touch;
};
static struct query_snapshot g_query = { 0, 0, 0, 1 };

static void publish_query_state(void) {
    struct surface *target = g_key_target ? g_key_target : (g_grab ? g_grab : toplevel_at(g_ptr_x, g_ptr_y));
    int takes_touch = 1;
    if (target) {
        struct wl_client *client = wl_resource_get_client(target->resource);
        struct seat_touch *st;
        takes_touch = 0;
        for_each_touch_of(client, st) takes_touch = 1;
    }
    int w = 0, h = 0, ow = 0, oh = 0, input_x = 0, input_y = 0, has_position = 0;
    scene_size(&w, &h);
    vkp_output_size(&ow, &oh);
    if (g_ptr_known && w > 0 && h > 0) {
        double ox, oy;
        if (ow > 0 && oh > 0 && vkp_scene_to_output(g_ptr_x, g_ptr_y, &ox, &oy)) {
            input_x = (int)(ox * INPUT_SPACE_W / ow);
            input_y = (int)(oy * INPUT_SPACE_H / oh);
        } else {
            input_x = (int)(g_ptr_x * INPUT_SPACE_W / w);
            input_y = (int)(g_ptr_y * INPUT_SPACE_H / h);
        }
        has_position = 1;
    }
    pthread_mutex_lock(&g_query_lock);
    g_query.focus_takes_touch = takes_touch;
    g_query.has_position = has_position;
    g_query.input_x = input_x;
    g_query.input_y = input_y;
    pthread_mutex_unlock(&g_query_lock);
}

int droiddeck_focus_takes_touch(void) {
    pthread_mutex_lock(&g_query_lock);
    int takes_touch = g_query.focus_takes_touch;
    pthread_mutex_unlock(&g_query_lock);
    return takes_touch;
}

int droiddeck_cursor_input_pos(int *x, int *y) {
    pthread_mutex_lock(&g_query_lock);
    int has_position = g_query.has_position;
    int input_x = g_query.input_x, input_y = g_query.input_y;
    pthread_mutex_unlock(&g_query_lock);
    if (!has_position) return 0;
    *x = input_x;
    *y = input_y;
    return 1;
}

static void touch_down(int id, double x, double y) {
    if (id < 0 || id >= MAX_TOUCH_POINTS) return;
    struct surface *target = touch_target_at(x, y);
    if (!target) return;
    struct wl_client *client = wl_resource_get_client(target->resource);
    if (g_touch_points[id].surface) touch_cancel_client(client);
    int any_active = 0;
    for (int i = 0; i < MAX_TOUCH_POINTS; i++) {
        struct surface *active = g_touch_points[i].surface;
        if (!active) continue;
        if (active != target) {
            touch_cancel_all();
            any_active = 0;
            break;
        }
        any_active = 1;
    }
    struct seat_touch *st;
    int has_touch = 0;
    for_each_touch_of(client, st) has_touch = 1;
    if (!any_active) {
        char name[160];
        describe(target, name, sizeof(name));
        droiddeck_log("touch", "%s gets %s", name, has_touch ? "touch" : "the pointer (it takes no touch)");
    }
    if (!has_touch) {
        if (any_active) return;
        g_touch_points[id].surface = target;
        g_touch_points[id].pointer_fallback = 1;
        pointer_event(x, y, BTN_LEFT, 1);
        wl_display_flush_clients(g_display);
        return;
    }
    int tx = 0, ty = 0;
    if (target != g_desktop && target->placed) { tx = target->x; ty = target->y; }
    wl_fixed_t fx = wl_fixed_from_double(x - tx), fy = wl_fixed_from_double(y - ty);
    int sent = 0;
    for_each_touch_of(client, st) {
        st->focus = target->resource;
        wl_touch_send_down(st->touch, wl_display_next_serial(g_display), now_ms(),
                           target->resource, id, fx, fy);
        sent = 1;
    }
    if (!sent) return;
    g_touch_points[id].surface = target;
    touch_frame(client);
    wl_display_flush_clients(g_display);
}

static void touch_motion(int id, double x, double y) {
    if (id < 0 || id >= MAX_TOUCH_POINTS) return;
    struct surface *target = g_touch_points[id].surface;
    if (!target) return;
    if (g_touch_points[id].pointer_fallback) {
        pointer_event(x, y, 0, 0);
        wl_display_flush_clients(g_display);
        return;
    }
    struct wl_client *client = wl_resource_get_client(target->resource);
    int tx = 0, ty = 0;
    if (target != g_desktop && target->placed) { tx = target->x; ty = target->y; }
    wl_fixed_t fx = wl_fixed_from_double(x - tx), fy = wl_fixed_from_double(y - ty);
    struct seat_touch *st;
    for_each_touch_of(client, st)
        if (st->focus == target->resource)
            wl_touch_send_motion(st->touch, now_ms(), id, fx, fy);
    touch_frame(client);
    wl_display_flush_clients(g_display);
}

static void touch_up(int id, double x, double y) {
    if (id < 0 || id >= MAX_TOUCH_POINTS) return;
    struct surface *target = g_touch_points[id].surface;
    if (!target) return;
    if (g_touch_points[id].pointer_fallback) {
        memset(&g_touch_points[id], 0, sizeof(g_touch_points[id]));
        pointer_event(x, y, BTN_LEFT, 0);
        wl_display_flush_clients(g_display);
        return;
    }
    struct wl_client *client = wl_resource_get_client(target->resource);
    struct seat_touch *st;
    for_each_touch_of(client, st)
        if (st->focus == target->resource)
            wl_touch_send_up(st->touch, wl_display_next_serial(g_display), now_ms(), id);
    memset(&g_touch_points[id], 0, sizeof(g_touch_points[id]));
    touch_frame(client);
    if (!touch_points_for_client(client))
        for_each_touch_of(client, st) st->focus = NULL;
    wl_display_flush_clients(g_display);
}

static void deliver_touch(const struct input_msg *m) {
    double x, y;
    input_output_to_scene(m->p3, m->p4, &x, &y);
    if (m->p1 == 0) touch_down(m->p2, x, y);
    else if (m->p1 == 1) touch_motion(m->p2, x, y);
    else if (m->p1 == 2) touch_up(m->p2, x, y);
    else if (m->p1 == 3) touch_cancel_all();
    publish_query_state();
}

static void key_event(uint32_t evdev, int pressed);
struct wl_resource *droiddeck_ime_target(void);
static void deliver_key(const struct input_msg *m) {
    key_event((uint32_t)m->p1, m->p2);
}

static void send_axis(struct seat_pointer *sp, uint32_t t, uint32_t axis, int value120) {
    if (!value120) return;
    if (wl_resource_get_version(sp->ptr) >= WL_POINTER_AXIS_VALUE120_SINCE_VERSION)
        wl_pointer_send_axis_value120(sp->ptr, axis, value120);
    else if (wl_resource_get_version(sp->ptr) >= WL_POINTER_AXIS_DISCRETE_SINCE_VERSION && value120 % 120 == 0)
        wl_pointer_send_axis_discrete(sp->ptr, axis, value120 / 120);
    wl_pointer_send_axis(sp->ptr, t, axis, wl_fixed_from_double(value120 / 12.0));
}

static void axis_event(int vertical120, int horizontal120, int finger) {
    struct surface *target = g_active_constraint ? g_active_constraint->surface
                           : g_desktop ? g_desktop : (g_grab ? g_grab : toplevel_at(g_ptr_x, g_ptr_y));
    if (!target || (!finger && !vertical120 && !horizontal120)) return;
    struct wl_client *client = wl_resource_get_client(target->resource);
    struct seat_pointer *sp;
    if (!pointer_for(client)) return;
    int tx = 0, ty = 0;
    if (target != g_desktop && target->placed) { tx = target->x; ty = target->y; }
    uint32_t t = now_ms();
    pointer_focus(target->resource, wl_fixed_from_double(g_ptr_x - tx), wl_fixed_from_double(g_ptr_y - ty));
    for_each_pointer_of(client, sp) {
        int version = wl_resource_get_version(sp->ptr);
        if (!finger) {
            send_axis(sp, t, WL_POINTER_AXIS_VERTICAL_SCROLL, vertical120);
            send_axis(sp, t, WL_POINTER_AXIS_HORIZONTAL_SCROLL, horizontal120);
        } else {
            if (version >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION)
                wl_pointer_send_axis_source(sp->ptr, WL_POINTER_AXIS_SOURCE_FINGER);
            if (!vertical120 && !horizontal120) {
                if (version >= WL_POINTER_AXIS_STOP_SINCE_VERSION) {
                    wl_pointer_send_axis_stop(sp->ptr, t, WL_POINTER_AXIS_VERTICAL_SCROLL);
                    wl_pointer_send_axis_stop(sp->ptr, t, WL_POINTER_AXIS_HORIZONTAL_SCROLL);
                }
            } else {
                if (vertical120)
                    wl_pointer_send_axis(sp->ptr, t, WL_POINTER_AXIS_VERTICAL_SCROLL, wl_fixed_from_double(vertical120 / 12.0));
                if (horizontal120)
                    wl_pointer_send_axis(sp->ptr, t, WL_POINTER_AXIS_HORIZONTAL_SCROLL, wl_fixed_from_double(horizontal120 / 12.0));
            }
        }
        if (version >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(sp->ptr);
    }
    wl_display_flush_clients(g_display);
}

static void scroll_event(int steps) {
    axis_event(steps * 120, 0, 0);
}

static int update_modifiers(uint32_t evdev, int pressed) {
    static const struct { uint32_t evdev, mask; } mod_keys[] = {
        { 42, 0x1 }, { 54, 0x1 },
        { 29, 0x4 }, { 97, 0x4 },
        { 56, 0x8 }, { 100, 0x8 },
        { 125, 0x40 }, { 126, 0x40 },
    };
    uint32_t depressed = g_mods_depressed, locked = g_mods_locked;
    if (evdev == 58) {
        if (pressed) locked ^= 0x2;
    } else {
        int i, n = (int)(sizeof(mod_keys) / sizeof(mod_keys[0]));
        for (i = 0; i < n && mod_keys[i].evdev != evdev; i++) {}
        if (i == n) return 0;
        if (pressed) g_mod_keys_held |= 1u << i; else g_mod_keys_held &= ~(1u << i);
        depressed = 0;
        for (int j = 0; j < n; j++) if (g_mod_keys_held & (1u << j)) depressed |= mod_keys[j].mask;
    }
    if (depressed == g_mods_depressed && locked == g_mods_locked) return 0;
    g_mods_depressed = depressed;
    g_mods_locked = locked;
    return 1;
}

static void key_event(uint32_t evdev, int pressed) {
    if (evdev < 0x300) {
        uint8_t bit = (uint8_t)(1u << (evdev & 7));
        if (pressed && (g_keys_down[evdev >> 3] & bit)) return;
        if (pressed) g_keys_down[evdev >> 3] |= bit; else g_keys_down[evdev >> 3] &= (uint8_t)~bit;
    }
    int mods_changed = update_modifiers(evdev, pressed);
    struct surface *target = NULL;
    struct wl_resource *ime = droiddeck_ime_target();
    if (ime) target = wl_resource_get_user_data(ime);
    if (!target && g_key_target && g_key_target->mapped && g_key_target != g_desktop) target = g_key_target;
    if (!target) target = g_desktop;
    if (!target) {
        struct surface *s;
        wl_list_for_each_reverse(s, &g_toplevels, toplevel_link) { target = s; break; }
    }
    if (!target) return;
    struct wl_client *client = wl_resource_get_client(target->resource);
    struct seat_keyboard *sk;
    if (!keyboard_for(client)) return;
    keyboard_focus(target->resource);
    for_each_keyboard_of(client, sk) {
        wl_keyboard_send_key(sk->kb, wl_display_next_serial(g_display), now_ms(), evdev,
                             pressed ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
        if (mods_changed)
            wl_keyboard_send_modifiers(sk->kb, wl_display_next_serial(g_display),
                                       g_mods_depressed, 0, g_mods_locked, 0);
    }
    wl_display_flush_clients(g_display);
}

static void output_notify_mode(void) {
    int mhz = atomic_load(&g_output_refresh_mhz);
    struct wl_resource *r;
    wl_resource_for_each(r, &g_outputs) {
        output_send_mode(r, mhz);
        if (wl_resource_get_version(r) >= 2) wl_output_send_done(r);
    }
    g_output_announced_refresh_mhz = mhz;
}

static void output_resized(void) {
    struct surface *s;
    output_notify_mode();
    wl_list_for_each(s, &g_surfaces, link)
        if (s->xdg_toplevel && s->toplevel_committed) send_toplevel_configure(s);
    droiddeck_log("display", "output resized to %dx%d", g_output_w, g_output_h);
    wl_display_flush_clients(g_display);
}

static int on_input_readable(int fd, uint32_t mask, void *data) {
    struct input_msg m;
    int vsync = 0;
    int64_t vsync_ns = 0;
    while (read(fd, &m, sizeof(m)) == (ssize_t)sizeof(m)) {
        switch (m.type) {
        case 1: deliver_key(&m); break;
        case 2: pointer_event(m.p1, m.p2, 0, 0); break;
        case 3: pointer_button(m.p1, m.p2); break;
        case 4: scroll_event(m.p1); break;
        case 5: vsync = 1; vsync_ns = ((int64_t)m.p1 << 32) | (uint32_t)m.p2; break;
        case 6: pointer_delta(m.p1 / 256.0, m.p2 / 256.0); break;
        case 7: deliver_touch(&m); break;
        case 8: output_resized(); break;
        case 9:
            vk_present_set_scale_mode(m.p1, m.p2);
            schedule_render();
            break;
        case 10: break;
        case 11: axis_event(m.p1, m.p2, 0); break;
        case 12: axis_event(m.p1, m.p2, 1); break;
        case 20: stream_take(); break;
        case 21: if (!m.p1) stream_forget_all(); schedule_render(); break;
        default: deliver_pointer(&m); break;
        }
    }
    if (atomic_exchange(&g_output_refresh_pending, 0) &&
            atomic_load(&g_output_refresh_mhz) != g_output_announced_refresh_mhz) {
        output_notify_mode();
        wl_display_flush_clients(g_display);
    }
    if (vsync) on_vsync(vsync_ns);
    return 0;
}

void droiddeck_wayland_set_scale_mode(int mode, int alignment) {
    if (g_input_pipe[1] < 0) {
        vk_present_set_scale_mode(mode, alignment);
        return;
    }
    struct input_msg m = { 9, mode, alignment, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

void droiddeck_wayland_send_pointer(int action, int x, int y) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 0, action, x, y, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

void droiddeck_wayland_send_touch(int action, int pointer_id, int x, int y) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 7, action, pointer_id, x, y };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

void droiddeck_wayland_send_key(int evdev, int state) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 1, evdev, state, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

void droiddeck_wayland_output_resized(void) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 8, 0, 0, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

void droiddeck_wayland_set_output_refresh_rate(int mhz) {
    if (mhz <= 0) mhz = 60000;
    if (atomic_exchange(&g_output_refresh_mhz, mhz) == mhz || g_input_pipe[1] < 0) return;
    atomic_store(&g_output_refresh_pending, 1);
    struct input_msg m = { 10, 0, 0, 0, 0 };
    ssize_t n;
    do { n = write(g_input_pipe[1], &m, sizeof(m)); } while (n < 0 && errno == EINTR);
    (void)n;
}

void droiddeck_wayland_vsync(int64_t frame_time_ns) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 5, (int)(frame_time_ns >> 32), (int)(uint32_t)frame_time_ns, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

void droiddeck_wayland_send_scene_input(int type, int a, int b) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { type, a, b, 0, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}


const char *droiddeck_client_name(struct wl_client *client) { return client_name(client); }
struct wl_display *droiddeck_get_display(void) { return g_display; }
void droiddeck_request_redraw(void) { schedule_render(); }

struct wl_resource *droiddeck_ime_target(void) {
    struct surface *s;
    if (g_ime_click && g_ime_click->mapped) return g_ime_click->resource;
    struct wl_client *shell = g_desktop ? wl_resource_get_client(g_desktop->resource) : NULL;
    wl_list_for_each_reverse(s, &g_toplevels, toplevel_link)
        if (!shell || wl_resource_get_client(s->resource) != shell) return s->resource;
    return NULL;
}

void droiddeck_surface_scene_origin(struct wl_resource *surface, int *x, int *y) {
    struct surface *s = surface ? wl_resource_get_user_data(surface) : NULL;
    *x = 0; *y = 0;
    if (s && s->placed) { *x = s->x; *y = s->y; }
}

void droiddeck_inject_key(uint32_t evdev, int pressed) { key_event(evdev, pressed); }


static struct wl_event_source *g_stats_timer;
volatile unsigned g_zero_copy_last;

static int on_stats_timer(void *data) {
    int windows = 0;
    struct surface *s;
    wl_list_for_each(s, &g_toplevels, toplevel_link) windows++;
    unsigned zero_copy = ahb_swapchain_stats_take();
    unsigned layer_frames = sc_layer_frames_take();
    g_zero_copy_last = zero_copy;
    unsigned generated = vkp_framegen_stats_take();
    if (g_stat_frames || g_stat_dmabuf || g_stat_shm || generated) {
        char extra[160] = "";
        int off = 0;
        if (g_zero_copy || zero_copy) off += snprintf(extra + off, sizeof(extra) - (size_t)off, " | %u zero-copy frames", zero_copy);
        if (layer_frames) off += snprintf(extra + off, sizeof(extra) - (size_t)off, " | %u layer frames", layer_frames);
        if (generated) snprintf(extra + off, sizeof(extra) - (size_t)off, " | %u generated frames", generated);
        droiddeck_log("stats", "last 10 s: %u frames on screen (%.1f fps) | %u GPU frames from games | %u window redraws | %d windows open%s",
                   g_stat_frames + generated, (g_stat_frames + generated) / 10.0, g_stat_dmabuf, g_stat_shm, windows, extra);
    }
    {
        struct vkp_perf vp;
        vkp_perf_take(&vp);
        const unsigned drops = sc_layer_drops_take();
#define PERF_MS(sum, n) ((n) ? (double)(sum) / 1e6 / (double)(n) : 0.0)
        if (g_perf.scenes || vp.presents || g_perf.releases || drops)
            droiddeck_log("perf", "last 10 s: %u ticks, %u scenes, %u on screen (copy %u, zero-copy %u, layer copy %u) | "
                       "render_scene %.2f/%.2f ms | base %u black kept, %u presented | acquire %.2f/%.2f ms | "
                       "present %.2f/%.2f ms (%u) | fence wait %.2f/%.2f ms (%u, %u GPU release waits) | "
                       "release %.2f/%.2f ms (%u, %u held) | %u pool drops",
                       g_perf.ticks, g_perf.scenes, g_stat_frames, g_perf.copy_scenes, zero_copy, layer_frames,
                       PERF_MS(g_perf.scene_ns, g_perf.scenes), (double)g_perf.scene_max_ns / 1e6,
                       vp.base_kept, vp.base_presents,
                       PERF_MS(vp.acquire_ns, vp.acquires), (double)vp.acquire_max_ns / 1e6,
                       PERF_MS(vp.present_ns, vp.presents), (double)vp.present_max_ns / 1e6, vp.presents,
                       PERF_MS(vp.wait_ns, vp.waits), (double)vp.wait_max_ns / 1e6, vp.waits, vp.gpu_release_waits,
                       PERF_MS(g_perf.release_ns, g_perf.releases), (double)g_perf.release_max_ns / 1e6,
                       g_perf.releases, g_perf.releases_held, drops);
#undef PERF_MS
        memset(&g_perf, 0, sizeof(g_perf));
    }
    const uint64_t game = g_game_frames - g_pace.game_frames_seen;
    if (g_pace.ticks || g_pace.renders || game || g_pace.reattaches || g_pace.null_attaches || g_pace.distinct ||
        g_pace.presented || g_pace.replaced || g_pace.undrawn) {
        droiddeck_log("pacing", "last 10 s: tick reaches compositor %.2f/%.2f ms late (%u ticks, %u over 2 ms) | "
                   "new frame waits %.2f/%.2f ms to be drawn (%u scenes, %u on arrival) | %llu game frames, %u re-attached after an "
                   "empty attach, %u empty attaches, %u distinct buffers | feedback %u shown, %u replaced before drawing, "
                   "%u not drawn",
                   g_pace.ticks ? (double)g_pace.tick_late_ns / 1e6 / g_pace.ticks : 0.0, (double)g_pace.tick_late_max_ns / 1e6,
                   g_pace.ticks, g_pace.ticks_late_2ms,
                   g_pace.renders ? (double)g_pace.wait_ns / 1e6 / g_pace.renders : 0.0, (double)g_pace.wait_max_ns / 1e6,
                   g_pace.renders, g_pace.commit_renders, (unsigned long long)game, g_pace.reattaches, g_pace.null_attaches, g_pace.distinct,
                   g_pace.presented, g_pace.replaced, g_pace.undrawn);
    }
    {
        const uint64_t seen = g_game_frames;
        memset(&g_pace, 0, sizeof(g_pace));
        g_pace.game_frames_seen = seen;
    }
    g_stat_frames = g_stat_dmabuf = g_stat_shm = 0;
    droiddeck_color_stats_tick();



    for (struct client_info *ci = g_clients; ci; ci = ci->next) {
        if (!ci->asked_feedback || ci->dmabuf_buffers || ci->shm_gl_said || ci->shm_frames < 150)
            continue;
        ci->shm_gl_said = 1;
        droiddeck_log("opengl", "%s asked for GPU buffers but has drawn only software (shared-memory) "
                   "frames: its OpenGL fell back to software rendering, which the Wayland layer cannot "
                   "draw - expect a black picture (main device %u:%u)", ci->name,
                   (unsigned)major(g_main_device), (unsigned)minor(g_main_device));
    }
    wl_event_source_timer_update(g_stats_timer, 10000);
    return 0;
}


static char g_test_buf[512];
static size_t g_test_len;

static void test_input_line(const char *line) {
    double x, y;
    unsigned code;
    char cmd[16];
    int n = sscanf(line, "%15s %lf %lf", cmd, &x, &y);

    if (n < 2) return;
    droiddeck_log("test", "input: %s", line);
    if (!strcmp(cmd, "key") || !strcmp(cmd, "keydown") || !strcmp(cmd, "keyup")) {
        code = (unsigned)x;
        if (strcmp(cmd, "keyup")) key_event(code, 1);
        if (strcmp(cmd, "keydown")) key_event(code, 0);
        return;
    }
    if (n < 3) return;
    if (!strcmp(cmd, "move")) pointer_event(x, y, 0, 0);
    else if (!strcmp(cmd, "rel")) pointer_delta(x, y);
    else if (!strcmp(cmd, "down")) pointer_event(x, y, BTN_LEFT, 1);
    else if (!strcmp(cmd, "up")) pointer_event(x, y, BTN_LEFT, 0);
    else if (!strcmp(cmd, "rdown")) pointer_event(x, y, BTN_RIGHT, 1);
    else if (!strcmp(cmd, "rup")) pointer_event(x, y, BTN_RIGHT, 0);
    else if (!strcmp(cmd, "click") || !strcmp(cmd, "rclick") || !strcmp(cmd, "dclick")) {
        uint32_t button = cmd[0] == 'r' ? BTN_RIGHT : BTN_LEFT;
        int times = cmd[0] == 'd' ? 2 : 1;
        pointer_event(x, y, 0, 0);
        for (int i = 0; i < times; i++) {
            pointer_event(x, y, button, 1);
            pointer_event(x, y, button, 0);
        }
    }
}

static int on_test_input(int fd, uint32_t mask, void *data) {
    ssize_t r;
    while ((r = read(fd, g_test_buf + g_test_len, sizeof(g_test_buf) - 1 - g_test_len)) > 0) {
        char *start = g_test_buf, *nl;
        g_test_len += (size_t)r;
        g_test_buf[g_test_len] = 0;
        while ((nl = strchr(start, '\n'))) {
            *nl = 0;
            test_input_line(start);
            start = nl + 1;
        }
        g_test_len = strlen(start);
        memmove(g_test_buf, start, g_test_len);
        if (g_test_len >= sizeof(g_test_buf) - 1) g_test_len = 0;
    }
    return 0;
}

static void start_test_input(struct wl_event_loop *loop) {
    const char *rt = getenv("XDG_RUNTIME_DIR");
    char path[512];
    int fd;

    if (!rt || !*rt) return;
    snprintf(path, sizeof(path), "%s/test-input", rt);
    unlink(path);
    if (mkfifo(path, 0600) != 0) return;
    if ((fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC)) < 0) return;
    wl_event_loop_add_fd(loop, fd, WL_EVENT_READABLE, on_test_input, NULL);
}


static struct wl_listener g_client_created = { .notify = on_client_created };

int droiddeck_wayland_run(void) {
    wl_list_init(&g_surfaces);
    wl_list_init(&g_toplevels);
    wl_list_init(&g_outputs);
    wl_list_init(&g_constraints);
    wl_list_init(&g_relative_pointers);
    open_session_log();

    if (vkp_ready() != 0) WLOGE("renderer unavailable; clients will connect but nothing is drawn");
    /* Presentation compatibility is not HDR capability. Set the initial layer mode before
     * clients bind, including when Desktop is the first session in this app process. */
    if (vkp_quest_layer_presentation() && sc_layer_available()) g_zero_copy = 1;

    struct wl_display *display = wl_display_create();
    if (!display) {
        WLOGE("wl_display_create failed");
        return 1;
    }

    const char *rt = getenv("XDG_RUNTIME_DIR");
    if (rt && *rt) {
        char p[512];
        snprintf(p, sizeof(p), "%s/wayland-0", rt);      unlink(p);
        snprintf(p, sizeof(p), "%s/wayland-0.lock", rt); unlink(p);
    }
    if (wl_display_add_socket(display, "wayland-0") != 0) {
        WLOGE("add_socket(wayland-0) failed in XDG_RUNTIME_DIR=%s (errno path/perms?)",
              rt ? rt : "(null)");
        return 1;
    }
    droiddeck_log("display", "Wayland compositor listening on %s/wayland-0", rt ? rt : "?");
    wl_display_add_client_created_listener(display, &g_client_created);

    wl_global_create(display, &wl_compositor_interface, 6, NULL, bind_compositor);
    wl_global_create(display, &wl_subcompositor_interface, 1, NULL, bind_subcompositor);
    wl_global_create(display, &wp_viewporter_interface, 1, NULL, bind_viewporter);
    wl_display_init_shm(display);

    wl_global_create(display, &wl_output_interface, 4, NULL, bind_output);
    wl_global_create(display, &xdg_wm_base_interface, 1, NULL, bind_xdg_wm_base);
    wl_global_create(display, &zwp_linux_dmabuf_v1_interface, 4, NULL, bind_dmabuf);
    wl_global_create(display, &wl_seat_interface, 9, NULL, bind_seat);
    wl_global_create(display, &banner_desktop_v1_interface, 1, NULL, bind_desktop);
    wl_global_create(display, &wp_presentation_interface, 2, NULL, bind_presentation);
    wl_global_create(display, &zwp_pointer_constraints_v1_interface, 1, NULL, bind_pointer_constraints);
    wl_global_create(display, &zwp_relative_pointer_manager_v1_interface, 1, NULL, bind_relative_pointer_manager);
    droiddeck_ext_init(display);
    ahb_swapchain_init(display);


    droiddeck_color_init(display);



    wl_list_init(&g_pending_releases);
    g_release_timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (g_release_timer_fd >= 0)
        g_release_source = wl_event_loop_add_fd(wl_display_get_event_loop(display), g_release_timer_fd,
                                                WL_EVENT_READABLE, on_release_timer, NULL);

    g_display = display;
    if (pipe2(g_input_pipe, O_CLOEXEC | O_NONBLOCK) == 0) {
        wl_event_loop_add_fd(wl_display_get_event_loop(display), g_input_pipe[0],
                             WL_EVENT_READABLE, on_input_readable, NULL);
    } else {
        WLOGE("input pipe creation failed");
    }
    start_test_input(wl_display_get_event_loop(display));
    if ((g_stats_timer = wl_event_loop_add_timer(wl_display_get_event_loop(display), on_stats_timer, NULL)))
        wl_event_source_timer_update(g_stats_timer, 10000);

    g_output_announced_refresh_mhz = atomic_load(&g_output_refresh_mhz);
    wl_display_run(display);

    wl_display_destroy(display);
    return 0;
}
