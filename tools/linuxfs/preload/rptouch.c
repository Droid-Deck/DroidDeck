/* Steam's Linux Remote Play host has no touch injection: client touches arrive as an XTest mouse (absolute
 * motion plus button 1). While a stream holds the encoder, those become touches into gamescope's own touch
 * input, as a finger on the device screen would. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "../../../app/src/main/cpp/venc/venc_proto.h"

int bl_venc_streaming(void) __attribute__((visibility("hidden")));
int bl_venc_connect(void) __attribute__((visibility("hidden")));

static pthread_mutex_t rt_lock = PTHREAD_MUTEX_INITIALIZER;
static int rt_fd = -1, rt_down, rt_x, rt_y, rt_screen;

static int rt_enabled(void) {
    static int enabled = -1;
    if (enabled < 0) {
        const char *v = getenv("BL_REMOTE_TOUCH");
        enabled = v == NULL || strcmp(v, "0") != 0;
    }
    return enabled && bl_venc_streaming();
}

static uint32_t rt_fraction(int value, int extent) {
    if (extent <= 1 || value <= 0) return 0;
    if (value >= extent - 1) return 65535;
    return (uint32_t)((int64_t)value * 65535 / (extent - 1));
}

static void rt_send(void *dpy, uint32_t action) {
    static int (*width)(void *, int), (*height)(void *, int);
    if (!width) width = (int (*)(void *, int))dlsym(RTLD_DEFAULT, "XDisplayWidth");
    if (!height) height = (int (*)(void *, int))dlsym(RTLD_DEFAULT, "XDisplayHeight");
    if (!width || !height) return;
    struct venc_touch m = {
        .type = VENC_TOUCH, .action = action,
        .x = rt_fraction(rt_x, width(dpy, rt_screen)), .y = rt_fraction(rt_y, height(dpy, rt_screen)),
    };
    for (int attempt = 0; attempt < 2; attempt++) {
        if (rt_fd < 0) rt_fd = bl_venc_connect();
        if (rt_fd < 0) return;
        if (send(rt_fd, &m, sizeof m, MSG_NOSIGNAL) == (ssize_t)sizeof m) return;
        close(rt_fd);
        rt_fd = -1;
    }
}

static void *rt_xtst(const char *name) {
    void *fn = dlsym(RTLD_NEXT, name);
    if (fn) return fn;
    void *lib = dlopen("libXtst.so.6", RTLD_LAZY | RTLD_NOLOAD);
    return lib ? dlsym(lib, name) : NULL;
}

int XTestFakeMotionEvent(void *dpy, int screen, int x, int y, unsigned long delay) {
    static int (*real)(void *, int, int, int, unsigned long);
    if (!real) real = (int (*)(void *, int, int, int, unsigned long))rt_xtst("XTestFakeMotionEvent");
    if (rt_enabled()) {
        pthread_mutex_lock(&rt_lock);
        rt_x = x;
        rt_y = y;
        rt_screen = screen < 0 ? 0 : screen;
        int down = rt_down;
        if (down) rt_send(dpy, VENC_TOUCH_MOVE);
        pthread_mutex_unlock(&rt_lock);
        if (down) return 1;
    }
    return real ? real(dpy, screen, x, y, delay) : 0;
}

int XTestFakeButtonEvent(void *dpy, unsigned int button, int press, unsigned long delay) {
    static int (*real)(void *, unsigned int, int, unsigned long);
    if (!real) real = (int (*)(void *, unsigned int, int, unsigned long))rt_xtst("XTestFakeButtonEvent");
    if (button == 1) {
        pthread_mutex_lock(&rt_lock);
        int handled = 0;
        if (press && rt_enabled()) {
            rt_down = 1;
            rt_send(dpy, VENC_TOUCH_DOWN);
            handled = 1;
        } else if (!press && rt_down) {
            rt_down = 0;
            rt_send(dpy, VENC_TOUCH_UP);
            handled = 1;
        }
        pthread_mutex_unlock(&rt_lock);
        if (handled) return 1;
    }
    return real ? real(dpy, button, press, delay) : 0;
}
