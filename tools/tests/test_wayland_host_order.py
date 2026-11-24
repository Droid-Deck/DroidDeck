"""Compiled FIFO regression for the native host queue in droiddeck_ext.c."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CC = shutil.which("cc")
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/cpp/waylandcomp/src/droiddeck_ext.c"
HEADER = SOURCE.with_name("droiddeck_ext.h")

WAYLAND_H = """
#ifndef STUB_WAYLAND_SERVER_H
#define STUB_WAYLAND_SERVER_H
#include <stdint.h>
struct wl_display;
struct wl_event_loop;
struct wl_resource;
struct wl_client;
struct wl_event_source;
#define WL_EVENT_READABLE 1u
struct wl_event_loop *wl_display_get_event_loop(struct wl_display *);
void wl_display_flush_clients(struct wl_display *);
struct wl_event_source *wl_event_loop_add_fd(struct wl_event_loop *, int, uint32_t,
    int (*)(int, uint32_t, void *), void *);
#endif
"""

ANDROID_H = """
#ifndef STUB_ANDROID_LOG_H
#define STUB_ANDROID_LOG_H
#define ANDROID_LOG_ERROR 6
int __android_log_print(int, const char *, const char *, ...);
#endif
"""

AHB_H = """
#ifndef STUB_AHB_SWAPCHAIN_H
#define STUB_AHB_SWAPCHAIN_H
void ahb_swapchain_set_mode(int, int);
#endif
"""

COLOR_H = """
#ifndef STUB_DROIDDECK_COLOR_H
#define STUB_DROIDDECK_COLOR_H
void droiddeck_color_set_output(int);
#endif
"""

PROGRAM = """
#define _GNU_SOURCE 1
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "android/log.h"
#include "droiddeck_ext.h"
#include "ahb_swapchain.h"
#include "droiddeck_color.h"
#include "droiddeck_ext.c"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "check failed at line %d", __LINE__); return 1; } } while (0)

struct ev { int kind; uint32_t a; int b; char text[64]; };
static struct ev g_ev[32];
static int g_n;
static int g_display_stub;
static int g_loop_stub;
static int g_fallback_accept;
static int g_fallback_token;
static int g_hdr_count;

int text_input_host_commit(const char *u, size_t n)
{
    struct ev *e;
    if (n == 3 && memcmp(u, "x11", 3) == 0)
        return 0;
    if (g_n >= 32 || n >= sizeof(g_ev[0].text))
        return 0;
    e = &g_ev[g_n++];
    e->kind = 1;
    e->a = (uint32_t)n;
    memcpy(e->text, u, n);
    e->text[n] = 0;
    return 1;
}

void text_input_host_preedit(const char *u, size_t n, int a, int b)
{
    (void)u; (void)n; (void)a; (void)b;
}

void text_input_host_delete(int a, int b) { (void)a; (void)b; }
void clipboard_host_text(char *u, size_t n) { (void)n; free(u); }
int droiddeck_on_text_input_fallback(const char *u, int n, int token)
{
    if (!g_fallback_accept) return 0;
    if (n == 3 && memcmp(u, "x11", 3) == 0) {
        g_fallback_token = token;
        return 1;
    }
    g_fallback_token = token;
    return 1;
}

void droiddeck_inject_key(uint32_t k, int s)
{
    if (g_n < 32) {
        g_ev[g_n].kind = 2;
        g_ev[g_n].a = k;
        g_ev[g_n].b = s;
        g_ev[g_n].text[0] = 0;
        g_n++;
    }
}

void clipboard_init(struct wl_display *d) { (void)d; }
void text_input_init(struct wl_display *d) { (void)d; }
void toplevel_icon_init(struct wl_display *d) { (void)d; }
void single_pixel_init(struct wl_display *d) { (void)d; }
void ahb_swapchain_set_mode(int a, int b) { (void)a; (void)b; }
void droiddeck_color_set_output(int a) { (void)a; g_hdr_count++; }

struct wl_display *droiddeck_get_display(void) { return (struct wl_display *)&g_display_stub; }
void wl_display_flush_clients(struct wl_display *d) { (void)d; }

struct wl_event_loop *wl_display_get_event_loop(struct wl_display *d)
{
    (void)d;
    return (struct wl_event_loop *)&g_loop_stub;
}

struct wl_event_source *wl_event_loop_add_fd(struct wl_event_loop *l, int fd,
    uint32_t m, int (*f)(int, uint32_t, void *), void *d)
{
    (void)l; (void)fd; (void)m; (void)f; (void)d;
    return NULL;
}

int __android_log_print(int p, const char *t, const char *f, ...)
{
    (void)p; (void)t; (void)f;
    return 0;
}

int main(void)
{
    int saved;
    CHECK(pipe2(g_wake, O_CLOEXEC | O_NONBLOCK) == 0);
    droiddeck_host_text_commit("中文", (int)strlen("中文"));
    droiddeck_host_key(28, 1);
    droiddeck_host_key(28, 0);
    droiddeck_host_text_commit("日本語", (int)strlen("日本語"));
    droiddeck_host_key(14, 1);
    droiddeck_host_key(14, 0);
    droiddeck_host_key(111, 1);
    droiddeck_host_key(111, 0);
    droiddeck_host_key(15, 1);
    droiddeck_host_key(15, 0);
    CHECK(g_n == 0);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == 10);
    CHECK(g_ev[0].kind == 1 && strcmp(g_ev[0].text, "中文") == 0);
    CHECK(g_ev[1].kind == 2 && g_ev[1].a == 28u && g_ev[1].b == 1);
    CHECK(g_ev[2].kind == 2 && g_ev[2].a == 28u && g_ev[2].b == 0);
    CHECK(g_ev[3].kind == 1 && strcmp(g_ev[3].text, "日本語") == 0);
    CHECK(g_ev[4].kind == 2 && g_ev[4].a == 14u && g_ev[4].b == 1);
    CHECK(g_ev[5].kind == 2 && g_ev[5].a == 14u && g_ev[5].b == 0);
    CHECK(g_ev[6].kind == 2 && g_ev[6].a == 111u && g_ev[6].b == 1);
    CHECK(g_ev[7].kind == 2 && g_ev[7].a == 111u && g_ev[7].b == 0);
    CHECK(g_ev[8].kind == 2 && g_ev[8].a == 15u && g_ev[8].b == 1);
    CHECK(g_ev[9].kind == 2 && g_ev[9].a == 15u && g_ev[9].b == 0);
    saved = g_n;
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved);
    droiddeck_host_text_commit("尾", (int)strlen("尾"));
    droiddeck_host_key(16, 1);
    droiddeck_host_key(16, 0);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 3);
    CHECK(g_ev[saved].kind == 1 && strcmp(g_ev[saved].text, "尾") == 0);
    CHECK(g_ev[saved + 1].kind == 2 && g_ev[saved + 1].a == 16u && g_ev[saved + 1].b == 1);
    CHECK(g_ev[saved + 2].kind == 2 && g_ev[saved + 2].a == 16u && g_ev[saved + 2].b == 0);
    CHECK(g_head == NULL && g_tail == NULL);
    saved = g_n;
    g_fallback_accept = 1;
    droiddeck_host_text_commit("x11", 3);
    droiddeck_host_key(29, 1);
    droiddeck_host_key(28, 1);
    droiddeck_host_key(28, 0);
    droiddeck_host_key(29, 0);
    droiddeck_host_text_commit("later", 5);
    droiddeck_host_hdr_output(1);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved);
    CHECK(g_hdr_count == 1);
    CHECK(g_fallback_token > 0);
    droiddeck_host_text_input_fallback_complete(g_fallback_token + 1);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved);
    droiddeck_host_key(30, 1);
    droiddeck_host_key(30, 0);
    droiddeck_host_text_input_fallback_complete(g_fallback_token);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 7);
    CHECK(g_ev[saved].kind == 2 && g_ev[saved].a == 29u && g_ev[saved].b == 1);
    CHECK(g_ev[saved + 1].kind == 2 && g_ev[saved + 1].a == 28u && g_ev[saved + 1].b == 1);
    CHECK(g_ev[saved + 2].kind == 2 && g_ev[saved + 2].a == 28u && g_ev[saved + 2].b == 0);
    CHECK(g_ev[saved + 3].kind == 2 && g_ev[saved + 3].a == 29u && g_ev[saved + 3].b == 0);
    CHECK(g_ev[saved + 4].kind == 1 && strcmp(g_ev[saved + 4].text, "later") == 0);
    CHECK(g_ev[saved + 5].kind == 2 && g_ev[saved + 5].a == 30u && g_ev[saved + 5].b == 1);
    CHECK(g_ev[saved + 6].kind == 2 && g_ev[saved + 6].a == 30u && g_ev[saved + 6].b == 0);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 7);
    g_fallback_accept = 0;
    droiddeck_host_text_commit("x11", 3);
    droiddeck_host_key(31, 1);
    droiddeck_host_key(31, 0);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 9);
    CHECK(g_ev[saved + 7].kind == 2 && g_ev[saved + 7].a == 31u && g_ev[saved + 7].b == 1);
    CHECK(g_ev[saved + 8].kind == 2 && g_ev[saved + 8].a == 31u && g_ev[saved + 8].b == 0);
    CHECK(g_head == NULL && g_tail == NULL);
    saved = g_n;
    g_fallback_accept = 1;
    droiddeck_host_key(29, 1);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 1);
    CHECK(g_ev[saved].kind == 2 && g_ev[saved].a == 29u && g_ev[saved].b == 1);
    droiddeck_host_text_commit("x11", 3);
    droiddeck_host_text_commit("discard", 7);
    droiddeck_host_key(29, 0);
    droiddeck_host_key(32, 1);
    droiddeck_host_key(32, 0);
    droiddeck_host_hdr_output(0);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 1);
    CHECK(g_hdr_count == 2);
    CHECK(g_fallback_token > 0);
    droiddeck_host_text_input_fallback_complete(-g_fallback_token);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 2);
    CHECK(g_ev[saved + 1].kind == 2 && g_ev[saved + 1].a == 29u && g_ev[saved + 1].b == 0);
    droiddeck_host_text_commit("held", 4);
    droiddeck_host_key(33, 1);
    droiddeck_host_key(33, 0);
    droiddeck_host_key(34, 0);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 2);
    droiddeck_host_text_input_fallback_complete(g_fallback_token);
    droiddeck_host_text_commit("resume", 6);
    CHECK(on_wake(g_wake[0], 0, NULL) == 0);
    CHECK(g_n == saved + 3);
    CHECK(g_ev[saved + 2].kind == 1 && strcmp(g_ev[saved + 2].text, "resume") == 0);
    CHECK(g_head == NULL && g_tail == NULL);
    close(g_wake[0]);
    close(g_wake[1]);
    return 0;
}
"""

@unittest.skipUnless(sys.platform.startswith("linux"), "requires Linux pipe2")
@unittest.skipUnless(CC, "requires cc")
class WaylandHostOrderTest(unittest.TestCase):
    def test_fifo_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "android").mkdir()
            (tmp / "android/log.h").write_text(ANDROID_H)
            (tmp / "wayland-server.h").write_text(WAYLAND_H)
            (tmp / "ahb_swapchain.h").write_text(AHB_H)
            (tmp / "droiddeck_color.h").write_text(COLOR_H)
            shutil.copyfile(SOURCE, tmp / "droiddeck_ext.c")
            shutil.copyfile(HEADER, tmp / "droiddeck_ext.h")
            (tmp / "program.c").write_text(PROGRAM, encoding="utf-8")
            subprocess.run([CC, "-std=c11", "-Wall", "-Wextra", "-Werror",
                "-Wno-unused-parameter", "-pthread", "-I", str(tmp),
                "-o", str(tmp / "probe"), str(tmp / "program.c")],
                cwd=tmp, check=True, timeout=30)
            subprocess.run([str(tmp / "probe")], cwd=tmp, check=True, timeout=10)

if __name__ == "__main__":
    unittest.main()
