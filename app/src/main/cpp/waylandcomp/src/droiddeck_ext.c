#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <limits.h>
#include <android/log.h>
#include "droiddeck_ext.h"
#include "ahb_swapchain.h"
#include "droiddeck_color.h"

enum host_kind { HOST_CLIPBOARD = 1, HOST_TEXT_COMMIT, HOST_TEXT_PREEDIT, HOST_TEXT_DELETE, HOST_ZERO_COPY, HOST_HDR_OUTPUT, HOST_KEY };

struct host_msg {
    enum host_kind kind;
    char *text;
    size_t len;
    int a, b;
    struct host_msg *next;
};

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static struct host_msg *g_head, *g_tail;
static int g_wake[2] = {-1, -1};

/* Compositor-thread-only fallback ordering state. */
static int g_pending_token;
static int g_next_token = 1;
static struct host_msg *g_deferred_head, *g_deferred_tail;
static atomic_int g_last_completed;

void droiddeck_host_text_input_fallback_complete(int token) {
    atomic_store(&g_last_completed, token);
    if (g_wake[1] >= 0) {
        char c = 1;
        ssize_t n = write(g_wake[1], &c, 1);
        (void)n;
    }
}

static void host_post(enum host_kind kind, const char *text, size_t len, int a, int b) {
    struct host_msg *m = calloc(1, sizeof(*m));
    if (!m) return;
    m->kind = kind;
    m->a = a; m->b = b;
    if (text && len) {
        if (!(m->text = malloc(len + 1))) { free(m); return; }
        memcpy(m->text, text, len);
        m->text[len] = 0;
        m->len = len;
    }
    pthread_mutex_lock(&g_mu);
    if (g_tail) g_tail->next = m; else g_head = m;
    g_tail = m;
    pthread_mutex_unlock(&g_mu);
    if (g_wake[1] >= 0) {
        char c = 1;
        ssize_t n = write(g_wake[1], &c, 1);
        (void)n;
    }
}

void droiddeck_host_clipboard_text(const char *utf8, int len) {
    host_post(HOST_CLIPBOARD, utf8, len > 0 ? (size_t)len : 0, 0, 0);
}
void droiddeck_host_text_commit(const char *utf8, int len) {
    host_post(HOST_TEXT_COMMIT, utf8, len > 0 ? (size_t)len : 0, 0, 0);
}
void droiddeck_host_text_preedit(const char *utf8, int len, int cursor_begin, int cursor_end) {
    host_post(HOST_TEXT_PREEDIT, utf8, len > 0 ? (size_t)len : 0, cursor_begin, cursor_end);
}
void droiddeck_host_text_delete(int before, int after) {
    host_post(HOST_TEXT_DELETE, NULL, 0, before, after);
}
void droiddeck_host_zero_copy(int on, int live) {
    host_post(HOST_ZERO_COPY, NULL, 0, on, live);
}
void droiddeck_host_hdr_output(int on) {
    host_post(HOST_HDR_OUTPUT, NULL, 0, on, 0);
}
void droiddeck_host_key(int evdev, int state) {
    host_post(HOST_KEY, NULL, 0, evdev, state);
}

static int on_wake(int fd, uint32_t mask, void *data) {
    char buf[64];
    while (read(fd, buf, sizeof(buf)) > 0) {}
    for (;;) {
        int deferred = 0;
        pthread_mutex_lock(&g_mu);
        if (g_pending_token != 0 && g_pending_token == atomic_load(&g_last_completed)) {
            g_pending_token = 0;
            if (g_deferred_head) {
                g_deferred_tail->next = g_head;
                if (!g_head) g_tail = g_deferred_tail;
                g_head = g_deferred_head;
                g_deferred_head = g_deferred_tail = NULL;
            }
        }
        struct host_msg *m = g_head;
        if (m) { g_head = m->next; if (!g_head) g_tail = NULL; m->next = NULL; }
        pthread_mutex_unlock(&g_mu);
        if (!m) break;
        if (g_pending_token != 0 &&
            (m->kind == HOST_TEXT_COMMIT || m->kind == HOST_TEXT_PREEDIT ||
             m->kind == HOST_TEXT_DELETE || m->kind == HOST_KEY)) {
            if (g_deferred_tail) g_deferred_tail->next = m; else g_deferred_head = m;
            g_deferred_tail = m;
            deferred = 1;
        }
        if (deferred) continue;
        switch (m->kind) {
        case HOST_CLIPBOARD:
            clipboard_host_text(m->text, m->len);
            m->text = NULL;
            break;
        case HOST_TEXT_COMMIT:
            if (!text_input_host_commit(m->text ? m->text : "", m->len)) {
                int token = 0;
                if (g_next_token == INT_MAX) g_next_token = 1;
                else g_next_token++;
                token = g_next_token;
                g_pending_token = 0;
                if (droiddeck_on_text_input_fallback(m->text ? m->text : "", (int)m->len, token))
                    g_pending_token = token;
            }
            break;
        case HOST_TEXT_PREEDIT:
            text_input_host_preedit(m->text ? m->text : "", m->len, m->a, m->b);
            break;
        case HOST_TEXT_DELETE:
            text_input_host_delete(m->a, m->b);
            break;
        case HOST_ZERO_COPY:
            ahb_swapchain_set_mode(m->a, m->b);
            break;
        case HOST_HDR_OUTPUT:
            droiddeck_color_set_output(m->a);
            break;
        case HOST_KEY:
            droiddeck_inject_key((uint32_t)m->a, m->b);
            break;
        }
        free(m->text);
        free(m);
    }
    wl_display_flush_clients(droiddeck_get_display());
    return 0;
}

void droiddeck_ext_init(struct wl_display *display) {
    struct wl_event_loop *loop = wl_display_get_event_loop(display);
    if (pipe2(g_wake, O_CLOEXEC | O_NONBLOCK) == 0) {
        wl_event_loop_add_fd(loop, g_wake[0], WL_EVENT_READABLE, on_wake, NULL);
        pthread_mutex_lock(&g_mu);
        int queued = g_head != NULL;
        pthread_mutex_unlock(&g_mu);
        if (queued) { char c = 1; ssize_t n = write(g_wake[1], &c, 1); (void)n; }
    } else
        __android_log_print(ANDROID_LOG_ERROR, "DroidDeckWayland", "host queue pipe failed");
    clipboard_init(display);
    text_input_init(display);
    toplevel_icon_init(display);
    single_pixel_init(display);
}
