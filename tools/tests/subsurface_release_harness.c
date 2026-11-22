#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "wayland-util.h"

void wl_list_init(struct wl_list *list) { list->prev = list->next = list; }
void wl_list_insert(struct wl_list *list, struct wl_list *elm) {
    elm->prev = list;
    elm->next = list->next;
    list->next = elm;
    elm->next->prev = elm;
}
void wl_list_remove(struct wl_list *elm) {
    elm->prev->next = elm->next;
    elm->next->prev = elm->prev;
    elm->next = elm->prev = NULL;
}
int wl_list_empty(const struct wl_list *list) { return list->next == list; }

struct wl_listener { struct wl_list link; void (*notify)(struct wl_listener *, void *); };
struct wl_resource { struct wl_list listeners; int acquired, releases, errors, in_flight; };
struct dmabuf_buffer { int unused; };
enum surface_role { ROLE_NONE, ROLE_TOPLEVEL, ROLE_SUBSURFACE };
struct surface {
    struct wl_list link;
    struct wl_resource *dmabuf;
    struct dmabuf_buffer *dmabuf_buf;
    struct wl_listener dmabuf_destroy;
    struct wl_resource *pixel;
    struct wl_listener pixel_destroy;
    int64_t next_release_ns;
    int releases_pending;
    enum surface_role role;
    struct surface *parent;
    int sub_desync;
};

#define TFD_TIMER_ABSTIME 1
static struct wl_list g_surfaces;
static struct wl_list g_pending_releases;
static int g_release_timer_fd = 3;
static int g_fps_limit;
static int64_t g_refresh_ns = 8333333;
static int g_release_unpaced;
static int64_t g_now = 1000000000LL;
static int64_t g_armed;
static struct dmabuf_buffer g_buf;
static int g_late_dispatch;

static int64_t now_ns(void) { return g_now; }
static void perf_note_release(int64_t since) { (void)since; }
static int buffer_release_blocked(struct wl_resource *b) { (void)b; return 0; }
static int ahb_swapchain_defer_release(struct dmabuf_buffer *b, struct wl_resource *r, struct surface *s, int paced) {
    (void)b; (void)r; (void)s; (void)paced;
    return 0;
}
static void dmabuf_buffer_unref(struct dmabuf_buffer *b) { (void)b; }
static void wl_resource_add_destroy_listener(struct wl_resource *r, struct wl_listener *l) {
    wl_list_insert(r->listeners.prev, &l->link);
}
static int timerfd_settime(int fd, int flags, const struct itimerspec *its, struct itimerspec *old) {
    (void)fd; (void)flags; (void)old;
    g_armed = (int64_t)its->it_value.tv_sec * 1000000000LL + its->it_value.tv_nsec;
    return 0;
}
static void dispatch(struct wl_resource *b) {
    for (; b->in_flight; b->in_flight--) {
        if (b->acquired) b->acquired = 0;
        else b->errors++;
    }
}
static void send_buffer_release(struct wl_resource *b) {
    b->releases++;
    b->in_flight++;
    if (!g_late_dispatch) dispatch(b);
}

#include "release.inc"

static void present(struct wl_resource *b) { b->acquired = 1; }

static void commit(struct surface *s, struct wl_resource *b) {
    if (s->pixel != b) drop_pixel(s);
    if (s->dmabuf != b) {
        drop_dmabuf(s, 1);
        if (b) {
            pending_releases_cancel(b);
            s->dmabuf = b;
            s->dmabuf_buf = &g_buf;
            wl_resource_add_destroy_listener(b, &s->dmabuf_destroy);
        }
    }
    pending_releases_apply(s);
}

static void commit_pixel(struct surface *s, struct wl_resource *b) {
    if (s->pixel != b) drop_pixel(s);
    drop_dmabuf(s, 1);
    hold_pixel(s, b);
    pending_releases_apply(s);
}

static void timer_fires(int64_t at) {
    if (g_now < at) g_now = at;
    release_due_buffers();
    arm_release_timer();
}

static void surface_init(struct surface *s, enum surface_role role, struct surface *parent) {
    memset(s, 0, sizeof(*s));
    s->role = role;
    s->parent = parent;
    wl_list_insert(g_surfaces.prev, &s->link);
}

static void buffer_init(struct wl_resource *b) {
    memset(b, 0, sizeof(*b));
    wl_list_init(&b->listeners);
}

static void check(const struct wl_resource *b, int releases) {
    if (b->releases != releases || b->errors) {
        fprintf(stderr, "releases %d (want %d), released while not acquired %d\n", b->releases, releases, b->errors);
        exit(1);
    }
}

static void moved_between_planes(void) {
    struct surface root, low, high;
    struct wl_resource a, b, c;
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&low, ROLE_SUBSURFACE, &root);
    surface_init(&high, ROLE_SUBSURFACE, &root);
    buffer_init(&a); buffer_init(&b); buffer_init(&c);

    present(&a); present(&b);
    commit(&high, &a);
    commit(&low, &b);
    commit(&root, NULL);

    present(&a); present(&b);
    commit(&high, NULL);
    timer_fires(g_now);
    check(&a, 0);
    commit(&low, &a);
    timer_fires(g_now);
    commit(&root, &b);
    timer_fires(g_now + 100000000LL);
    check(&a, 0);
    check(&b, 0);

    present(&c);
    commit(&high, NULL);
    commit(&low, NULL);
    commit(&root, &c);
    timer_fires(g_now + 100000000LL);
    check(&a, 1);
    check(&b, 1);
    check(&c, 0);
}

static void reattached_before_release(void) {
    struct surface s;
    struct wl_resource a, b, c;
    surface_init(&s, ROLE_TOPLEVEL, NULL);
    buffer_init(&a); buffer_init(&b); buffer_init(&c);
    g_fps_limit = 60;
    present(&a); commit(&s, &a);
    present(&b); commit(&s, &b);
    present(&a); commit(&s, &a);
    present(&c); commit(&s, &c);
    timer_fires(g_now + 1000000000LL);
    check(&a, 1);
    check(&b, 1);
    check(&c, 0);
}

static void desync_child_not_held(void) {
    struct surface root, child;
    struct wl_resource a, b;
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&child, ROLE_SUBSURFACE, &root);
    child.sub_desync = 1;
    buffer_init(&a); buffer_init(&b);
    present(&a); commit(&child, &a);
    present(&b); commit(&child, &b);
    timer_fires(g_now + 100000000LL);
    check(&a, 1);
    check(&b, 0);
}

static void toplevel_pacing_kept(void) {
    struct surface s;
    struct wl_resource a, b, c;
    surface_init(&s, ROLE_TOPLEVEL, NULL);
    buffer_init(&a); buffer_init(&b); buffer_init(&c);
    g_fps_limit = 60;
    const int64_t interval = 1000000000LL / 60;
    present(&a); commit(&s, &a);
    present(&b); commit(&s, &b);
    present(&c); commit(&s, &c);
    const int64_t start = g_now;
    timer_fires(g_now);
    check(&a, 1);
    check(&b, 0);
    assert(g_armed == start + interval);
    timer_fires(start + interval / 2);
    check(&b, 0);
    timer_fires(g_armed);
    check(&b, 1);
    check(&c, 0);
}

static void unpaced_sync_child(void) {
    struct surface root, low, high;
    struct wl_resource a, b;
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&low, ROLE_SUBSURFACE, &root);
    surface_init(&high, ROLE_SUBSURFACE, &root);
    buffer_init(&a); buffer_init(&b);
    g_release_unpaced = 1;
    present(&a); commit(&high, &a); commit(&root, NULL);
    present(&a);
    commit(&high, NULL);
    check(&a, 0);
    commit(&low, &a);
    commit(&root, NULL);
    check(&a, 0);
    commit(&low, NULL);
    check(&a, 0);
    commit(&root, NULL);
    check(&a, 1);
    present(&b); commit(&root, &b);
    commit(&root, NULL);
    check(&b, 1);
}

static void holder_destroyed(void) {
    struct surface root, child;
    struct wl_resource a, b;
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&child, ROLE_SUBSURFACE, &root);
    buffer_init(&a); buffer_init(&b);
    present(&a); commit(&child, &a); commit(&root, NULL);
    present(&b); commit(&child, &b);
    timer_fires(g_now + 100000000LL);
    check(&a, 0);
    child.parent = NULL;
    wl_list_remove(&root.link);
    pending_releases_forget_surface(&root);
    check(&a, 1);
}

static void cycled_faster_than_releases(void) {
    struct surface s;
    struct wl_resource a, b, c;
    struct wl_resource *order[3] = {&a, &b, &c};
    surface_init(&s, ROLE_TOPLEVEL, NULL);
    buffer_init(&a); buffer_init(&b); buffer_init(&c);
    g_fps_limit = 60;
    const int64_t interval = 1000000000LL / 60;
    for (int i = 0; i < 30; i++) {
        present(order[i % 3]);
        commit(&s, order[i % 3]);
        assert(s.releases_pending <= 2);
        assert(g_armed <= g_now + 3 * interval);
    }
    timer_fires(g_now + 1000000000LL);
    check(&a, 1);
    check(&b, 1);
    check(&c, 0);
}

static void desync_parent_holds_sync_grandchild(void) {
    struct surface root, middle, child;
    struct wl_resource a, b;
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&middle, ROLE_SUBSURFACE, &root);
    surface_init(&child, ROLE_SUBSURFACE, &middle);
    middle.sub_desync = 1;
    buffer_init(&a); buffer_init(&b);
    present(&a); commit(&child, &a); commit(&middle, NULL);
    present(&b); commit(&child, &b);
    timer_fires(g_now + 100000000LL);
    check(&a, 0);
    commit(&middle, NULL);
    timer_fires(g_now + 100000000LL);
    check(&a, 1);
    check(&b, 0);
}

static void set_desync_frees_held_release(void) {
    struct surface root, outer, inner;
    struct wl_resource a, b, c, d;
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&outer, ROLE_SUBSURFACE, &root);
    surface_init(&inner, ROLE_SUBSURFACE, &outer);
    buffer_init(&a); buffer_init(&b); buffer_init(&c); buffer_init(&d);
    present(&a); commit(&inner, &a); commit(&root, NULL);
    present(&b); commit(&inner, &b);
    present(&c); commit(&outer, &c); commit(&root, NULL);
    present(&d); commit(&outer, &d);
    inner.sub_desync = 1;
    pending_releases_rehold(&inner);
    timer_fires(g_now + 100000000LL);
    check(&a, 1);
    check(&c, 0);
    outer.sub_desync = 1;
    pending_releases_rehold(&outer);
    timer_fires(g_now + 100000000LL);
    check(&c, 1);
    check(&b, 0);
    check(&d, 0);
}

static void backing_attached_every_frame(void) {
    struct surface root, plane;
    struct wl_resource black, a, b, c;
    struct wl_resource *frames[3] = {&a, &b, &c};
    surface_init(&root, ROLE_TOPLEVEL, NULL);
    surface_init(&plane, ROLE_SUBSURFACE, &root);
    buffer_init(&black); buffer_init(&a); buffer_init(&b); buffer_init(&c);
    g_late_dispatch = 1;
    for (int i = 0; i < 6; i += 2) {
        present(&black); present(frames[i % 3]);
        present(&black); present(frames[(i + 1) % 3]);
        commit(&plane, frames[i % 3]); commit_pixel(&root, &black);
        commit(&plane, frames[(i + 1) % 3]); commit_pixel(&root, &black);
        timer_fires(g_now + 100000000LL);
        dispatch(&black); dispatch(&a); dispatch(&b); dispatch(&c);
    }
    check(&black, 0);
    check(&a, 2);
    check(&b, 2);
    check(&c, 1);
    present(&a);
    commit(&plane, NULL);
    commit(&root, &a);
    timer_fires(g_now + 100000000LL);
    dispatch(&black); dispatch(&a); dispatch(&b); dispatch(&c);
    check(&black, 1);
    check(&a, 2);
    check(&c, 2);
    present(&black);
    commit_pixel(&root, &black);
    wl_list_remove(&root.link);
    drop_pixel(&root);
    dispatch(&black);
    check(&black, 2);
}

static void backing_moved_to_another_surface(void) {
    struct surface low, high;
    struct wl_resource black;
    surface_init(&low, ROLE_TOPLEVEL, NULL);
    surface_init(&high, ROLE_TOPLEVEL, NULL);
    buffer_init(&black);
    present(&black);
    commit_pixel(&low, &black);
    commit_pixel(&high, &black);
    commit(&low, NULL);
    check(&black, 0);
    commit(&high, NULL);
    check(&black, 1);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    wl_list_init(&g_surfaces);
    wl_list_init(&g_pending_releases);
    if (!strcmp(argv[1], "moved")) moved_between_planes();
    else if (!strcmp(argv[1], "reattached")) reattached_before_release();
    else if (!strcmp(argv[1], "cycled")) cycled_faster_than_releases();
    else if (!strcmp(argv[1], "desync")) desync_child_not_held();
    else if (!strcmp(argv[1], "toplevel")) toplevel_pacing_kept();
    else if (!strcmp(argv[1], "unpaced")) unpaced_sync_child();
    else if (!strcmp(argv[1], "destroyed")) holder_destroyed();
    else if (!strcmp(argv[1], "nested")) desync_parent_holds_sync_grandchild();
    else if (!strcmp(argv[1], "set-desync")) set_desync_frees_held_release();
    else if (!strcmp(argv[1], "backing")) backing_attached_every_frame();
    else if (!strcmp(argv[1], "backing-moved")) backing_moved_to_another_surface();
    else return 2;
    return 0;
}
