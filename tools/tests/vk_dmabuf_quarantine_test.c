/* Production lifecycle functions are inserted verbatim by Python. No GPU,
 * Android app, external client or real timer is involved in this probe. */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <wayland-util.h>
#ifndef __linux__
struct itimerspec { struct timespec it_interval, it_value; };
#endif

struct wl_listener { struct wl_list link; void (*notify)(struct wl_listener *, void *); };
struct wl_resource { int dma; void *user_data; struct wl_list listeners; unsigned releases; };
struct wl_client;
struct wl_event_source { int unused; };
typedef struct AHardwareBuffer { int unused; } AHardwareBuffer;
struct vkp_image { int unused; };
struct dmabuf_buffer {
    int refs, n_planes, fd[4];
    struct vkp_image *img;
    void *ahb_state;
};
struct surface {
    struct wl_resource *dmabuf;
    struct dmabuf_buffer *dmabuf_buf;
    struct wl_listener dmabuf_destroy;
    int releases_pending;
    int64_t next_release_ns;
};
/* PRODUCTION_STRUCTS */
static int g_dev_state, g_fps_limit, g_release_timer_fd;
static int64_t g_refresh_ns;
static struct wl_list g_pending_releases, g_bufs;
static void *g_display;
static unsigned image_destroys, ahb_releases, source_removes;
static struct itimerspec last_timer;
static const int wl_buffer_interface = 1, dbuf_buffer_impl = 2;
#define TFD_TIMER_ABSTIME 1

/* List operations use the real Wayland layouts/macros. OS/IPC endpoints alone
 * are fakes, so no event is sent and no process or hardware is touched. */
void wl_list_init(struct wl_list *l) { l->prev = l; l->next = l; }
void wl_list_insert(struct wl_list *l, struct wl_list *e) {
    e->next = l->next; e->prev = l; l->next->prev = e; l->next = e;
}
void wl_list_remove(struct wl_list *e) { e->next->prev = e->prev; e->prev->next = e->next; }
static int wl_resource_instance_of(struct wl_resource *r, const void *i, const void *impl) { return r->dma; }
static void *wl_resource_get_user_data(struct wl_resource *r) { return r->user_data; }
static void wl_resource_add_destroy_listener(struct wl_resource *r, struct wl_listener *l) {
    wl_list_insert(r->listeners.prev, &l->link);
}
static void wl_buffer_send_release(struct wl_resource *r) { ++r->releases; }
static void wl_display_flush_clients(void *d) {}
static int timerfd_settime(int fd, int flags, const struct itimerspec *v, void *old) { last_timer = *v; return 0; }
static void perf_note_release(int64_t since) {}
static int64_t now_ns(void) { return 1000000000LL; }
static void vkp_image_destroy(struct vkp_image *img) { ++image_destroys; }
static void AHardwareBuffer_release(AHardwareBuffer *ahb) { ++ahb_releases; }
static int wl_event_source_remove(struct wl_event_source *src) { ++source_removes; return 0; }
static struct dmabuf_buffer *get_dmabuf(struct wl_resource *r);
int ahb_swapchain_defer_release(struct dmabuf_buffer *, struct wl_resource *, struct surface *, int);
/* PRODUCTION_FUNCTIONS */

static void init_resource(struct wl_resource *r, struct dmabuf_buffer *b) {
    *r = (struct wl_resource){.dma = b != NULL, .user_data = b};
    wl_list_init(&r->listeners);
}
static struct dmabuf_buffer *init_buffer(struct surface *s, struct wl_resource *r) {
    struct dmabuf_buffer *b = calloc(1, sizeof(*b)); assert(b);
    b->refs = 2; b->n_planes = 1;
    int fds[2]; assert(pipe(fds) == 0); b->fd[0] = fds[0]; close(fds[1]);
    init_resource(r, b);
    assert(get_dmabuf(r) == b);
    *s = (struct surface){.dmabuf = r, .dmabuf_buf = b};
    s->dmabuf_destroy.notify = on_dmabuf_destroyed;
    wl_resource_add_destroy_listener(r, &s->dmabuf_destroy);
    return b;
}
static void destroy_resource(struct wl_resource *r) {
    while (r->listeners.next != &r->listeners) {
        struct wl_listener *l = wl_container_of(r->listeners.next, l, link);
        l->notify(l, r);
    }
    if (r->dma) dmabuf_buffer_unref(r->user_data);
    r->user_data = NULL;
}
static void fire_timer(void) {
    int fd[2]; uint64_t expirations = 1;
    assert(pipe(fd) == 0 && write(fd[1], &expirations, sizeof(expirations)) == sizeof(expirations));
    on_release_timer(fd[0], 0, NULL); close(fd[0]); close(fd[1]);
}
static void assert_retained(struct dmabuf_buffer *b, int refs) {
    assert(b->refs == refs && fcntl(b->fd[0], F_GETFD) >= 0 && !image_destroys);
}
static void test_drop(int paced) {
    struct surface s; struct wl_resource r;
    struct dmabuf_buffer *b = init_buffer(&s, &r);
    g_dev_state = -3; drop_dmabuf(&s, paced);
    assert(!s.dmabuf && !s.dmabuf_buf && !r.releases && !s.releases_pending);
    assert(r.listeners.next == &r.listeners); /* no listener into a dead surface */
    assert_retained(b, 2);
    destroy_resource(&r); assert_retained(b, 2);
    close(b->fd[0]); free(b); /* fake GPU only */
}
static void test_queue(int mixed, int client_gone) {
    struct surface s; struct wl_resource r, shm;
    struct dmabuf_buffer *b = init_buffer(&s, &r);
    g_release_timer_fd = 123; g_refresh_ns = 100000;
    drop_dmabuf(&s, 1); assert(s.releases_pending == 1 && !r.releases);
    assert(last_timer.it_value.tv_sec || last_timer.it_value.tv_nsec);
    if (mixed) { init_resource(&shm, NULL); release_buffer(&s, &shm, now_ns()); }
    pending_releases_forget_surface(&s);
    g_dev_state = -3;
    fire_timer();
    assert(!r.releases && g_pending_releases.next != &g_pending_releases);
    if (mixed) assert(shm.releases == 1);
    assert(!last_timer.it_value.tv_sec && !last_timer.it_value.tv_nsec);
    assert_retained(b, 1);
    if (client_gone) {
        destroy_resource(&r);
        assert(g_pending_releases.next == &g_pending_releases);
        assert_retained(b, 1);
    }
    close(b->fd[0]); free(b); /* fake GPU only */
}
static void test_active_client_destroy(void) {
    struct surface s; struct wl_resource r;
    struct dmabuf_buffer *b = init_buffer(&s, &r);
    g_dev_state = -3;
    destroy_resource(&r);
    assert(!s.dmabuf && s.dmabuf_buf == b && !r.releases);
    drop_dmabuf(&s, 0); assert(!s.dmabuf_buf);
    assert_retained(b, 2);
    close(b->fd[0]); free(b); /* fake GPU only */
}
static void test_ahb(int terminal, int lost) {
    struct surface s; struct wl_resource r;
    struct dmabuf_buffer *b = init_buffer(&s, &r);
    struct ahb_buf *ab = calloc(1, sizeof(*ab)); assert(ab);
    AHardwareBuffer ahb;
    *ab = (struct ahb_buf){.b = b, .ahb = &ahb, .resource = &r, .on_layer = 1, .fence_fd = -1};
    b->ahb_state = ab; ++b->refs;
    ab->resource_destroy.notify = on_buffer_resource_destroyed;
    wl_resource_add_destroy_listener(&r, &ab->resource_destroy);
    wl_list_insert(&g_bufs, &ab->link);
    drop_dmabuf(&s, 1); assert(ab->release_pending && ab->surface == &s);
    int backing_fd = b->fd[0];
    g_dev_state = terminal ? -3 : lost ? -2 : 1;
    ahb_swapchain_surface_gone(&s); assert(!ab->surface);
    /* Actual event-loop callback after the display's fence becomes readable. */
    struct wl_event_source source; int fds[2]; assert(pipe(fds) == 0);
    ab->on_layer = 0; ab->fence_src = &source; ab->fence_fd = fds[0];
    on_fence_readable(fds[0], 0, ab); close(fds[1]);
    assert(source_removes == 1 && ab->fence_fd == -1 && !ab->fence_src);
    assert(r.releases == (terminal ? 0u : 1u));
    assert(ab->release_pending == terminal && !ahb_releases);
    destroy_resource(&r);
    if (terminal) {
        assert(!ab->resource && b->ahb_state == ab && !ahb_releases);
        assert_retained(b, 2);
        close(b->fd[0]); free(ab); free(b); /* fake GPU only */
    } else {
        assert(ahb_releases == 1 && image_destroys == 1);
        errno = 0; assert(fcntl(backing_fd, F_GETFD) == -1 && errno == EBADF);
    }
}
static void test_cleanup(int lost) {
    struct surface s; struct wl_resource r;
    struct dmabuf_buffer *b = init_buffer(&s, &r);
    int fd = b->fd[0]; g_dev_state = lost ? -2 : 1;
    assert(!vkp_completion_unknown());
    drop_dmabuf(&s, 0);
    assert(r.releases == 1 && b->refs == 1 && !image_destroys);
    destroy_resource(&r); assert(image_destroys == 1);
    errno = 0; assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
}
int main(int argc, char **argv) {
    assert(argc == 2);
    wl_list_init(&g_pending_releases); wl_list_init(&g_bufs);
    g_dev_state = 1; g_release_timer_fd = -1;
    if (!strcmp(argv[1], "replacement")) test_drop(1);
    else if (!strcmp(argv[1], "surface-gone")) test_drop(0);
    else if (!strcmp(argv[1], "queued")) test_queue(0, 0);
    else if (!strcmp(argv[1], "mixed")) test_queue(1, 0);
    else if (!strcmp(argv[1], "client-gone")) test_queue(0, 1);
    else if (!strcmp(argv[1], "ahb")) test_ahb(1, 0);
    else if (!strcmp(argv[1], "ahb-healthy")) test_ahb(0, 0);
    else if (!strcmp(argv[1], "ahb-device-lost")) test_ahb(0, 1);
    else if (!strcmp(argv[1], "active-client-gone")) test_active_client_destroy();
    else if (!strcmp(argv[1], "healthy")) test_cleanup(0);
    else if (!strcmp(argv[1], "device-lost")) test_cleanup(1);
    else assert(0 && "unknown scenario");
    return 0;
}
