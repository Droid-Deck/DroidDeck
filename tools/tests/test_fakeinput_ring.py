import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"
RING_MAGIC = 0x46494252
DESCRIPTION_MAGIC = 0x46495544
HEADER = 64
EVENT = 24
CAPACITY = 4096
DESCRIPTION = 1824
READER = r"""
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <sys/uio.h>
#include <unistd.h>

static int vector_count;

static ssize_t controller_read(int fd, void *buffer, size_t size)
{
    if (!vector_count)
        return read(fd, buffer, size);
    struct iovec vectors[3] = {
        {buffer, sizeof(struct input_event)},
        {(char *)buffer + sizeof(struct input_event), 0},
        {(char *)buffer + sizeof(struct input_event), size - sizeof(struct input_event)},
    };
    if (vector_count == 1) {
        vectors[0].iov_len = size;
        return readv(fd, vectors, 1);
    }
    return readv(fd, vectors, 3);
}

int main(int argc, char **argv)
{
    vector_count = argc > 1 ? (argv[1][0] == '1' ? 1 : 3) : 0;
    struct input_event events[64];
    int fd = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open");
        return 1;
    }
    while (controller_read(fd, events, sizeof(events)) > 0)
        ;
    puts("ready");
    fflush(stdout);
    char go;
    if (read(0, &go, 1) != 1)
        return 1;
    ssize_t got = controller_read(fd, events, sizeof(events));
    for (ssize_t i = 0; i < got / (ssize_t)sizeof(events[0]); i++)
        printf("%ld.%06ld %u %u %d\n", (long)events[i].time.tv_sec, (long)events[i].time.tv_usec, events[i].type,
               events[i].code, events[i].value);
    return got > 0 ? 0 : 1;
}
"""
PROBE = r"""
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <stdio.h>
#include <sys/ioctl.h>

int main(void)
{
    char name[128] = "";
    struct input_id id = {0};
    int fd = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
    if (fd < 0)
        return 1;
    int joydev = ioctl(fd, JSIOCGNAME(sizeof(name)), name);
    printf("JSIOCGNAME %d %s\n", joydev, joydev < 0 && errno == EINVAL ? "EINVAL" : name);
    if (ioctl(fd, EVIOCGID, &id) < 0 || ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0)
        return 1;
    printf("%04x:%04x %s\n", id.vendor, id.product, name);
    return 0;
}
"""
READV_CHECKS = r"""
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>

int main(void)
{
    struct input_event events[64];
    int fd = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
    if (fd < 0)
        return 1;
    while (read(fd, events, sizeof(events)) > 0)
        ;
    struct iovec vector = {events, sizeof(events)};
    if (readv(fd, &vector, 1) != -1 || errno != EAGAIN)
        return 2;
    if (readv(fd, NULL, 0) != 0)
        return 3;
    vector.iov_len = 0;
    if (readv(fd, &vector, 1) != 0)
        return 4;
    vector.iov_len = sizeof(struct input_event) - 1;
    if (readv(fd, &vector, 1) != -1 || errno != EINVAL)
        return 5;
    ssize_t (*invoke)(int, const struct iovec *, int) = readv;
    if (invoke(fd, NULL, -1) != -1 || errno != EINVAL)
        return 6;
    if (invoke(fd, NULL, 1025) != -1 || errno != EINVAL)
        return 7;
    if (invoke(fd, NULL, 1) != -1 || errno != EFAULT)
        return 8;
    long page = sysconf(_SC_PAGESIZE);
    void *area = mmap(NULL, 2 * page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (area == MAP_FAILED)
        return 10;
    struct iovec *boundary = (struct iovec *)((char *)area + page - sizeof(struct iovec));
    *boundary = vector;
    if (mprotect((char *)area + page, page, PROT_NONE))
        return 11;
    if (invoke(fd, boundary, 2) != -1 || errno != EFAULT)
        return 12;
    if (invoke(fd, boundary + 1, 1) != -1 || errno != EFAULT)
        return 13;
    if (munmap(area, 2 * page))
        return 14;
    if (invoke(fd, boundary, 1) != -1 || errno != EFAULT)
        return 15;
    int pipefd[2];
    char text[5] = {0};
    struct iovec parts[2] = {{text, 1}, {text + 1, 3}};
    if (pipe(pipefd) || write(pipefd[1], "test", 4) != 4 ||
        readv(pipefd[0], parts, 2) != 4 || strcmp(text, "test"))
        return 9;
    puts("ok");
    return 0;
}
"""
READV_DESTINATION_CHECKS = r"""
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct input_event events[64];
    int fd = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
    if (fd < 0)
        return 1;
    if (argc > 1)
        while (read(fd, events, sizeof(events)) > 0)
            ;
    puts("ready");
    fflush(stdout);
    char go;
    if (read(0, &go, 1) != 1)
        return 2;
    long page = sysconf(_SC_PAGESIZE);
    char *area = mmap(NULL, 2 * page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (area == MAP_FAILED || mprotect(area + page, page, PROT_NONE))
        return 3;
    struct iovec vector = {area + page, sizeof(struct input_event)};
    if (readv(fd, &vector, 1) != -1 || errno != EFAULT)
        return 4;
    if (read(fd, vector.iov_base, vector.iov_len) != -1 || errno != EFAULT)
        return 5;
    if (mprotect(area, page, PROT_READ))
        return 6;
    vector.iov_base = area;
    if (readv(fd, &vector, 1) != -1 || errno != EFAULT)
        return 7;
    if (mprotect(area, page, PROT_READ | PROT_WRITE) || munmap(area + page, page))
        return 8;
    vector.iov_base = area + page;
    if (readv(fd, &vector, 1) != -1 || errno != EFAULT)
        return 9;
    vector.iov_base = area + page - 1;
    if (readv(fd, &vector, 1) != -1 || errno != EFAULT)
        return 15;
    vector.iov_base = area + page - sizeof(struct input_event);
    vector.iov_len = 2 * sizeof(struct input_event);
    if (readv(fd, &vector, 1) != sizeof(struct input_event))
        return 10;
    struct input_event *first = vector.iov_base;
    if (first->type != EV_KEY || first->code != BTN_A)
        return 11;
    struct iovec parts[2] = {{events, sizeof(struct input_event)}, {NULL, sizeof(struct input_event)}};
    if (readv(fd, parts, 2) != sizeof(struct input_event))
        return 12;
    if (events[0].type != (argc > 1 ? EV_ABS : EV_KEY) || events[0].code != (argc > 1 ? ABS_Y : BTN_B))
        return 13;
    vector.iov_base = events;
    vector.iov_len = sizeof(events);
    ssize_t got = readv(fd, &vector, 1);
    if (got != (argc > 1 ? 1 : 18) * sizeof(struct input_event) ||
        events[0].type != (argc > 1 ? EV_SYN : EV_KEY) || events[0].code != (argc > 1 ? SYN_REPORT : BTN_X))
        return 14;
    munmap(area, page);
    puts("ok");
    return 0;
}
"""
OVERLAY_READ = r"""
#include <sys/uio.h>
#include <unistd.h>

ssize_t read(int fd, void *buffer, size_t size)
{
    struct iovec vector = {buffer, size};
    return readv(fd, &vector, 1);
}
"""


def can_build(bits):
    with tempfile.TemporaryDirectory() as tmp:
        probe = Path(tmp) / "probe.cpp"
        probe.write_text("#include <string>\nint main() { return std::string().size(); }\n")
        return subprocess.run(["g++", bits, "-static-libstdc++", "-o", str(Path(tmp) / "probe"), str(probe)],
                              capture_output=True).returncode == 0


@unittest.skipUnless(sys.platform == "linux" and os.uname().machine == "x86_64" and shutil.which("g++")
                     and shutil.which("gcc"), "needs gcc and g++ on an x86-64 Linux host")
class FakeinputRingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def ring(self, virtual=True):
        hooks = self.tmp / "hooks"
        hooks.mkdir()
        (hooks / ("event16" if virtual else "event0")).touch()
        ring = bytearray(HEADER + CAPACITY * EVENT + (DESCRIPTION if virtual else 0))
        struct.pack_into("<IIIIQQQ", ring, 0, RING_MAGIC, 2, EVENT, CAPACITY, 5, 0, 0)
        if virtual:
            struct.pack_into("<IiHHHHI80s", ring, HEADER + CAPACITY * EVENT, DESCRIPTION_MAGIC, os.getpid(),
                             3, 0x28de, 0x11ff, 1, 16, b"Microsoft X-Box 360 pad 0")
        path = hooks / ".uinput-event16" if virtual else self.tmp / "ring0"
        path.write_bytes(bytes(ring))
        return hooks, path

    def build(self, bits, program, name):
        if not can_build(bits):
            self.skipTest("no %s C++ toolchain" % bits)
        library = self.tmp / ("libfakeinput%s.so" % bits)
        subprocess.run(["g++", bits, "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread", "-std=c++17",
                        "-static-libstdc++", "-static-libgcc", "-Wl,--exclude-libs,ALL", "-o", str(library),
                        str(SOURCE), "-ldl"], check=True, capture_output=True)
        binary = self.tmp / (name + bits)
        (self.tmp / (name + ".c")).write_text(program)
        subprocess.run(["gcc", bits, "-o", str(binary), str(self.tmp / (name + ".c"))], check=True)
        return library, binary

    def read_through(self, bits, vectors=None, overlay=False, virtual=True, program=READER):
        if not virtual:
            program = program.replace("event16", "event0")
        library, reader = self.build(bits, program, "reader")
        hooks, path = self.ring(virtual=virtual)
        env = dict(os.environ, LD_PRELOAD=str(library), FAKE_EVDEV_DIR=str(hooks))
        if not virtual:
            env["FAKE_EVDEV_MEMFD_PATHS"] = "0=" + str(path)
        if overlay:
            source = self.tmp / "overlay.c"
            source.write_text(OVERLAY_READ)
            overlay_library = self.tmp / "overlay.so"
            subprocess.run(["gcc", bits, "-shared", "-fPIC", "-o", str(overlay_library), str(source)], check=True)
            env["LD_PRELOAD"] = str(overlay_library) + ":" + str(library)
        command = [str(reader)] + ([str(vectors)] if vectors else [])
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, text=True)
        self.addCleanup(process.kill)
        self.assertEqual(process.stdout.readline(), "ready\n")
        with open(path, "r+b") as ring:
            for i, (kind, code, value) in enumerate(((1, 304, 1), (3, 1, -32768), (0, 0, 0))):
                ring.seek(HEADER + (5 + i) * EVENT)
                ring.write(struct.pack("<qqHHi", 1700000000 + i, 250000, kind, code, value))
            ring.seek(16)
            ring.write(struct.pack("<Q", 8))
        output, _ = process.communicate("g", timeout=10)
        self.assertEqual(process.returncode, 0)
        return output

    def test_a_32_bit_guest_reads_the_64_bit_ring(self):
        self.assertEqual(self.read_through("-m32"),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_a_64_bit_guest_reads_the_same_ring(self):
        self.assertEqual(self.read_through("-m64"),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_readv_reads_events_instead_of_the_backing_file(self):
        self.assertEqual(self.read_through("-m64", vectors=1),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_readv_reads_the_original_controller_ring(self):
        self.assertEqual(self.read_through("-m64", vectors=1, virtual=False),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_an_overlay_read_fallback_to_readv_reads_events_without_recursion(self):
        self.assertEqual(self.read_through("-m64", overlay=True),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_readv_reads_event_aligned_vectors_and_skips_empty_ones(self):
        self.assertEqual(self.read_through("-m64", vectors=3),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_a_32_bit_guest_reads_the_same_events_with_readv(self):
        self.assertEqual(self.read_through("-m32", vectors=1),
                         "1700000000.250000 1 304 1\n1700000001.250000 3 1 -32768\n1700000002.250000 0 0 0\n")

    def test_readv_preserves_nonblocking_errors_and_other_descriptors(self):
        library, probe = self.build("-m64", READV_CHECKS, "readv-checks")
        hooks, _ = self.ring()
        run = subprocess.run([str(probe)], capture_output=True, text=True, timeout=10,
                             env=dict(os.environ, LD_PRELOAD=str(library), FAKE_EVDEV_DIR=str(hooks)))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "ok\n")

    def test_readv_keyframes_handle_destination_faults_and_partial_progress(self):
        self.assertEqual(self.read_through("-m64", program=READV_DESTINATION_CHECKS), "ok\n")

    def test_readv_ring_events_handle_destination_faults_and_partial_progress(self):
        self.assertEqual(self.read_through("-m64", vectors=1, program=READV_DESTINATION_CHECKS), "ok\n")

    def test_a_32_bit_guest_handles_readv_destination_faults(self):
        self.assertEqual(self.read_through("-m32", program=READV_DESTINATION_CHECKS), "ok\n")

    def test_an_event_node_rejects_joydev_requests_so_sdl_reads_its_id(self):
        library, probe = self.build("-m64", PROBE, "probe")
        hooks, _ = self.ring()
        run = subprocess.run([str(probe)], capture_output=True, text=True, timeout=10,
                             env=dict(os.environ, LD_PRELOAD=str(library), FAKE_EVDEV_DIR=str(hooks)))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "JSIOCGNAME -1 EINVAL\n28de:11ff Microsoft X-Box 360 pad 0\n")


if __name__ == "__main__":
    unittest.main()
