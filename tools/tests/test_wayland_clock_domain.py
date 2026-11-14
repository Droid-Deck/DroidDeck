import ctypes
import mmap
import shutil
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

from tools.tests.test_clock_pause_preload import clock_reset_command
from tools.tests.test_output_refresh import extract_function as extract_void_function
from tools.tests.test_scene_limit_hold import extract_function

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/waylandcomp/src/clock_domain.c"
HEADER = SOURCE.with_suffix(".h")


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class WaylandClockDomainTest(unittest.TestCase):
    def test_host_timestamp_uses_the_guest_domain(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 10_000_000_000))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld %u %lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL), '
                '         droiddeck_clock_domain_now_ms(), (long long)droiddeck_clock_domain_adjust_ns(5LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent), "-o", str(binary), str(program), str(SOURCE)],
                           check=True)
            result = subprocess.run([str(binary), str(control)], capture_output=True, text=True, check=True)
            adjusted, now_ms, clamped = map(int, result.stdout.split())
            self.assertEqual(adjusted, 20_000_000_000)
            self.assertGreaterEqual(now_ms, 0)
            self.assertLessEqual(now_ms, 0xFFFFFFFF)
            self.assertEqual(clamped, 0)

    def test_jni_host_input_then_native_button_across_pauses_and_timestamp_wrap(self):
        compositor = SOURCE.with_name("compositor.c").read_text()
        jni = SOURCE.with_name("waylandcomp_jni.c").read_text().replace("JNIEXPORT void JNICALL\n", "void ")
        prefix = "Java_com_droiddeck_launcher_wayland_WaylandCompositor_"
        functions = "\n".join(
            extract_void_function(compositor, name)
            for name in ("now_ms", "pointer_input", "pointer_button", "droiddeck_wayland_send_gesture")
        )
        functions += "\n" + "\n".join(
            extract_void_function(jni, prefix + name) for name in ("nativePointerGesture", "nativeVsync")
        )
        harness = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "clock_domain.h"

typedef const struct jni_api *JNIEnv;
typedef const char *jstring;
typedef void *jclass;
typedef int64_t jlong;
typedef int jint;
typedef float jfloat;
struct jni_api {
    const char *(*GetStringUTFChars)(JNIEnv *, jstring, void *);
    void (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);
};
static const char *get_string(JNIEnv *env, jstring key, void *copy) { return key; }
static void release_string(JNIEnv *env, jstring key, const char *name) { assert(key == name); }
static const struct jni_api api = {get_string, release_string};
static JNIEnv env = &api;
static int64_t host_ns, vsync_ns;
int __wrap_clock_gettime(clockid_t clock, struct timespec *ts) {
    assert(clock == CLOCK_MONOTONIC);
    ts->tv_sec = host_ns / 1000000000LL;
    ts->tv_nsec = host_ns % 1000000000LL;
    return 0;
}
static void droiddeck_wayland_vsync(int64_t time) { vsync_ns = time; }

struct input_msg { int type, p1, p2, p3, p4; uint32_t time; };
static int g_input_pipe[2];
static struct input_msg window_message;
static void queue_window_request(const char *key, uint64_t generation, void *window,
                                int type, int width, int height, int a, int b, int c, uint32_t time) {
    assert(!strcmp(key, "wl:test") && generation == 7 && !window && height == 0);
    window_message = (struct input_msg){type, a, b, c, width, time};
}

struct wl_resource { int unused; };
struct seat_pointer { struct wl_resource *ptr, *focus; };
static struct wl_resource resource;
static struct seat_pointer g_ptrs[] = {{&resource, &resource}};
static int g_nptrs = 1;
static void *g_display, *g_grab;
static uint32_t button_time;
#define WL_POINTER_BUTTON_STATE_RELEASED 0
#define WL_POINTER_FRAME_SINCE_VERSION 5
static int wl_resource_get_version(struct wl_resource *r) { return 5; }
static uint32_t wl_display_next_serial(void *display) { return 1; }
static void wl_pointer_send_frame(struct wl_resource *r) {}
static void wl_display_flush_clients(void *display) {}
static void wl_pointer_send_button(struct wl_resource *r, uint32_t serial, uint32_t time,
                                   uint32_t button, int state) {
    assert(button == 272 && state == WL_POINTER_BUTTON_STATE_RELEASED);
    button_time = time;
}
static void pointer_input_at(double x, double y, int relative, uint32_t button, int pressed, uint32_t time) {
    assert(relative == 1 && button == 272 && pressed == 1);
    button_time = time;
}

@FUNCTIONS@

static struct input_msg host_input(const char *key, int type, int64_t time) {
    Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativePointerGesture(
        &env, NULL, key, 7, type, 1.25f, -2.5f, 1.0f, 0.0f, time);
    struct input_msg message;
    if (key) {
        message = window_message;
        assert(message.type == type + 8);
    } else {
        assert(read(g_input_pipe[0], &message, sizeof(message)) == sizeof(message));
        assert(message.type == (type == 10 ? 27 : type + (type >= 5 ? 18 : 13)));
    }
    const float a = 1.25f, b = -2.5f, c = 1.0f, d = 0.0f;
    assert(!memcmp(&message.p1, &a, sizeof(a)) && !memcmp(&message.p2, &b, sizeof(b)));
    if (!key && type == 10) assert(message.p3 == 1);
    else assert(!memcmp(&message.p3, &c, sizeof(c)));
    assert(!memcmp(&message.p4, &d, sizeof(d)));
    return message;
}

int main(int argc, char **argv) {
    (void)argc;
    const int64_t ms = 1000000LL, wrap = 1LL << 32;
    const struct { int64_t shift, guest; } cases[] = {
        {0, 30000000000LL},
        {10000250000LL, 30000000000LL},
        {300000750000LL, 30000000000LL},
        {10000000000LL, (wrap - 10005) * ms},
        {10000000000LL, (wrap - 2) * ms},
        {(wrap + 30000) * ms + 250000, (wrap - 2) * ms + 750000},
        {10000000000LL, (2 * wrap + 5) * ms},
    };
    int fd = open(argv[1], O_RDWR);
    assert(fd >= 0 && pipe2(g_input_pipe, O_NONBLOCK | O_CLOEXEC) == 0);
    droiddeck_clock_domain_set_control(argv[1]);
    for (unsigned i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        int64_t shift = cases[i].shift;
        assert(pwrite(fd, &shift, sizeof(shift), 0) == sizeof(shift));
        host_ns = shift + cases[i].guest;
        int64_t time = host_ns / ms;
        uint32_t expected = (uint32_t)((time * ms - shift) / ms);
        for (int window = 0; window <= 1; window++) {
            const char *key = window ? "wl:test" : NULL;
            for (int type = 0; type <= 10; type++) {
                if (!window && type == 9) continue;
                assert(host_input(key, type, time).time == expected);
            }
            uint32_t motion_time = host_input(key, 10, time).time;
            assert(motion_time == (uint32_t)(droiddeck_clock_domain_adjust_ns(time * ms) / ms));
            host_ns += 3 * ms;
            pointer_button(272, 1);
            assert(button_time == (uint32_t)((host_ns - shift) / ms));
            assert((uint32_t)(button_time - motion_time) >= 3 &&
                   (uint32_t)(button_time - motion_time) <= 4);
            host_ns += ms;
            pointer_button(272, 0);
            assert(button_time == (uint32_t)((host_ns - shift) / ms));
            assert((uint32_t)(button_time - motion_time) >= 4 &&
                   (uint32_t)(button_time - motion_time) <= 5);
            uint32_t before_pause = button_time;
            shift += 20000750000LL;
            host_ns += 20000750000LL;
            assert(pwrite(fd, &shift, sizeof(shift), 0) == sizeof(shift));
            time = host_ns / ms;
            expected = (uint32_t)((time * ms - shift) / ms);
            uint32_t after_pause = host_input(key, 10, time).time;
            assert(after_pause == expected && (uint32_t)(before_pause - after_pause) <= 1);
            Java_com_droiddeck_launcher_wayland_WaylandCompositor_nativeVsync(&env, NULL, host_ns);
            assert(vsync_ns == host_ns);
        }
    }
    int64_t shift = 10000500000LL;
    assert(pwrite(fd, &shift, sizeof(shift), 0) == sizeof(shift));
    assert(droiddeck_clock_domain_adjust_ms(-1) == 0);
    assert(droiddeck_clock_domain_adjust_ms(10000) == 0);
    assert(droiddeck_clock_domain_adjust_ms(10001) == 0);
    assert(droiddeck_clock_domain_adjust_ms(10002) == 1);
    assert(droiddeck_clock_domain_adjust_ms(INT64_MAX) == (uint32_t)(INT64_MAX - 10001));
    shift = -1;
    assert(pwrite(fd, &shift, sizeof(shift), 0) == sizeof(shift));
    assert(droiddeck_clock_domain_adjust_ms(wrap + 5) == 5);
    droiddeck_clock_domain_set_control(NULL);
    assert(droiddeck_clock_domain_adjust_ms(wrap + 5) == 5);
    close(g_input_pipe[0]); close(g_input_pipe[1]); close(fd);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 0))
            program = root / "program.c"
            program.write_text(harness.replace("@FUNCTIONS@", functions))
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter", "-O2", "-pthread",
                            "-Wl,--wrap=clock_gettime", "-I", str(SOURCE.parent), "-o", str(binary),
                            str(program), str(SOURCE)], check=True)
            subprocess.run([str(binary), str(control)], check=True, timeout=5)

    def test_paced_release_uses_kernel_time_across_pause_offsets(self):
        compositor = SOURCE.with_name("compositor.c").read_text()
        functions = extract_function(compositor, "now_ns") + "\n" + extract_void_function(compositor, "arm_release_timer")
        harness = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>
#include "clock_domain.h"

struct pending_release { void *buffer; int64_t at_ns; struct pending_release *next; };
static struct pending_release *g_pending_releases;
static int g_release_timer_fd;
#define wl_list_for_each(pr, list, link) for (pr = *(list); pr; pr = pr->next)
static int buffer_release_blocked(void *buffer) { return buffer != NULL; }

@FUNCTIONS@

int main(int argc, char **argv) {
    (void)argc;
    droiddeck_clock_domain_set_control(argv[1]);
    int control = open(argv[1], O_WRONLY);
    assert(control >= 0);
    g_release_timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    assert(g_release_timer_fd >= 0);
    const int64_t offsets[] = {0, 10000000000LL, 300000000000LL};
    for (unsigned i = 0; i < sizeof(offsets) / sizeof(offsets[0]); i++) {
        assert(pwrite(control, &offsets[i], sizeof(offsets[i]), 0) == sizeof(offsets[i]));
        struct timespec started, ended;
        clock_gettime(CLOCK_MONOTONIC, &started);
        int64_t start = now_ns();
        struct pending_release blocked = {(void *)1, start + 1000000LL, NULL};
        struct pending_release release = {NULL, start + 60000000LL, &blocked};
        g_pending_releases = &release;
        arm_release_timer();
        int64_t next_offset = offsets[i] + 10000000000LL;
        assert(pwrite(control, &next_offset, sizeof(next_offset), 0) == sizeof(next_offset));
        struct pollfd pollfd = {g_release_timer_fd, POLLIN, 0};
        assert(poll(&pollfd, 1, 1000) == 1);
        clock_gettime(CLOCK_MONOTONIC, &ended);
        int64_t elapsed = (ended.tv_sec - started.tv_sec) * 1000000000LL + ended.tv_nsec - started.tv_nsec;
        assert(elapsed >= 50000000LL && elapsed < 1000000000LL);
        uint64_t expirations;
        assert(read(g_release_timer_fd, &expirations, sizeof(expirations)) == sizeof(expirations));
        assert(expirations == 1);
        g_pending_releases = &blocked;
        arm_release_timer();
        struct itimerspec remaining;
        assert(timerfd_gettime(g_release_timer_fd, &remaining) == 0);
        assert(remaining.it_value.tv_sec == 0 && remaining.it_value.tv_nsec == 0);
    }
    close(g_release_timer_fd);
    close(control);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 0))
            program = root / "program.c"
            program.write_text(harness.replace("@FUNCTIONS@", functions))
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent),
                            "-o", str(binary), str(program), str(SOURCE)], check=True)
            subprocess.run([str(binary), str(control)], check=True, timeout=5)

    def test_zero_and_missing_offsets_are_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 0))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  droiddeck_clock_domain_set_control(argv[2]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent), "-o", str(binary), str(program), str(SOURCE)],
                           check=True)
            result = subprocess.run([str(binary), str(control), str(root / "missing")], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.split(), ["30000000000", "30000000000"])

    def test_replaced_control_file_updates_offset_without_invalidating_readers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            replacement = root / "replacement"
            control.write_bytes(struct.pack("<q", 10_000_000_000))
            replacement.write_bytes(struct.pack("<q", 20_000_000_000))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  if (rename(argv[2], argv[1]) != 0) return 1;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent), "-o", str(binary), str(program), str(SOURCE)],
                           check=True)
            result = subprocess.run([str(binary), str(control), str(replacement)], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.split(), ["20000000000", "10000000000"])

    def test_session_transition_clears_stale_offset_but_live_failures_preserve_it(self):
        harness = r'''
#include <assert.h>
#include <fcntl.h>
#include <stdint.h>
#include <unistd.h>
#include "clock_domain.h"

int main(int argc, char **argv) {
    (void)argc;
    int64_t shift = 10000000000LL;
    int fd = open(argv[1], O_CREAT | O_RDWR, 0600);
    assert(fd >= 0);
    assert(write(fd, &shift, sizeof(shift)) == sizeof(shift));
    droiddeck_clock_domain_set_control(argv[1]);
    assert(droiddeck_clock_domain_adjust_ns(30000000000LL) == 20000000000LL);
    assert(unlink(argv[1]) == 0);
    droiddeck_clock_domain_set_control(argv[1]);
    assert(droiddeck_clock_domain_adjust_ns(30000000000LL) == 20000000000LL);
    int replacement = open(argv[1], O_CREAT | O_RDWR, 0600);
    assert(replacement >= 0);
    droiddeck_clock_domain_set_control(argv[1]);
    assert(droiddeck_clock_domain_adjust_ns(30000000000LL) == 20000000000LL);
    droiddeck_clock_domain_set_control(NULL);
    droiddeck_clock_domain_set_control(argv[1]);
    assert(droiddeck_clock_domain_adjust_ns(30000000000LL) == 30000000000LL);
    shift = 20000000000LL;
    assert(write(replacement, &shift, sizeof(shift)) == sizeof(shift));
    droiddeck_clock_domain_set_control(argv[1]);
    assert(droiddeck_clock_domain_adjust_ns(30000000000LL) == 10000000000LL);
    close(replacement);
    close(fd);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            program = root / "program.c"
            program.write_text(harness)
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent),
                            "-o", str(binary), str(program), str(SOURCE)], check=True)
            subprocess.run([str(binary), str(root / "clock")], check=True, timeout=5)
        host = SOURCE.parents[3] / "java/com/droiddeck/launcher/wayland/CompositorHost.kt"
        self.assertIn("nativeSetClockControl(null)", host.read_text().split("fun newSession() {", 1)[1].split("}", 1)[0])

    def test_startup_reset_keeps_host_and_guest_on_the_same_inode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 10_000_000_000))
            library = root / "clock.so"
            subprocess.run(["cc", "-shared", "-fPIC", "-pthread", "-Wall", "-Wextra", "-Werror",
                            "-o", str(library), str(SOURCE)], check=True)
            host = ctypes.CDLL(str(library))
            host.droiddeck_clock_domain_set_control.argtypes = [ctypes.c_char_p]
            host.droiddeck_clock_domain_adjust_ns.argtypes = [ctypes.c_int64]
            host.droiddeck_clock_domain_adjust_ns.restype = ctypes.c_int64
            host.droiddeck_clock_domain_set_control(str(control).encode())
            with control.open("r+b") as file, mmap.mmap(file.fileno(), 8) as guest:
                inode = control.stat().st_ino
                subprocess.run(["bash", "-c", clock_reset_command(control)],
                               check=True, timeout=5)
                self.assertEqual(control.stat().st_ino, inode)
                self.assertEqual(struct.unpack("<q", guest[:])[0], 0)
                self.assertEqual(host.droiddeck_clock_domain_adjust_ns(30_000_000_000), 30_000_000_000)
                file.seek(0)
                file.write(struct.pack("<q", 20_000_000_000))
                file.flush()
                self.assertEqual(struct.unpack("<q", guest[:])[0], 20_000_000_000)
                self.assertEqual(host.droiddeck_clock_domain_adjust_ns(30_000_000_000), 10_000_000_000)
            host.droiddeck_clock_domain_set_control(None)

    def test_initially_missing_control_is_mapped_after_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            replacement = root / "replacement"
            replacement.write_bytes(struct.pack("<q", 20_000_000_000))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  if (rename(argv[2], argv[1]) != 0) return 1;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent),
                            "-o", str(binary), str(program), str(SOURCE)], check=True)
            result = subprocess.run([str(binary), str(control), str(replacement)], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.split(), ["30000000000", "10000000000"])


if __name__ == "__main__":
    unittest.main()
