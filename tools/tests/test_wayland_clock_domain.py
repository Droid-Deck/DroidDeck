import shutil
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

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
