import fcntl
import mmap
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"
RING_MAGIC = 0x46494252
DESCRIPTION_MAGIC = 0x46495544
HEADER = 64
EVENT = 24
CAPACITY = 4096
DESCRIPTION = 1824
RING_SIZE = HEADER + CAPACITY * EVENT
DECK_IMU = 64
TRITON_BLOCK = 256
TRITON_MAGIC = 0x4E525454
TOUCH_MAGIC = 0x31484354
TOUCH_RING_SIZE = 256
PAD_TYPE_DECK = 0
PAD_TYPE_STEAM = 1
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
HIDRAW_PROBE = r"""
#include <errno.h>
#include <fcntl.h>
#include <linux/hidraw.h>
#include <poll.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    int fd = open(argv[1], O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        puts(errno == ENOENT ? "refused" : "failed");
        return 0;
    }
    struct hidraw_devinfo info;
    if (ioctl(fd, HIDIOCGRAWINFO, &info) < 0)
        return 1;
    struct pollfd ready = {fd, POLLIN, 0};
    unsigned char report[64];
    if (poll(&ready, 1, 2000) != 1 || read(fd, report, sizeof(report)) <= 0)
        return 2;
    printf("%04hx:%04hx report %02x\n", info.vendor, info.product, report[0]);
    return 0;
}
"""
FEATURE_PROBE = r"""
#include <fcntl.h>
#include <linux/hidraw.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>

int main(int argc, char **argv)
{
    unsigned char id = (unsigned char)strtoul(argv[1], NULL, 0);
    int fd = open("/dev/hidraw18", O_RDWR);
    if (fd < 0)
        return 1;
    int count = argc > 2 ? atoi(argv[2]) : 200, wrong = 0;
    for (int i = 0; i < count; i++) {
        unsigned char report[64] = {id};
        if (ioctl(fd, HIDIOCGFEATURE(sizeof(report)), report) < 0 || report[0] != id || report[1] != id)
            wrong++;
    }
    printf("%d wrong\n", wrong);
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

    def pad_ring(self, pad_type, triton_connected=False):
        hooks, ring = self.ring(virtual=False)
        data = bytearray(ring.read_bytes()) + bytearray(DECK_IMU + TRITON_BLOCK)
        struct.pack_into("<I", data, RING_SIZE + 44, 0x80000000 | pad_type)
        struct.pack_into("<II", data, RING_SIZE + DECK_IMU, TRITON_MAGIC, 1 if triton_connected else 0)
        ring.write_bytes(bytes(data))
        env = dict(os.environ, FAKE_EVDEV_DIR=str(hooks), FAKE_EVDEV_DECK="1", FAKE_EVDEV_TRITON="1",
                   FAKE_EVDEV_MEMFD_PATHS="0=" + str(ring))
        return ring, env

    def as_process(self, binary, name):
        named = self.tmp / name
        shutil.copy(binary, named)
        return str(named)

    def assert_opened_by_steam_and_remote_play_only(self, device, expected, env):
        library, probe = self.build("-m64", HIDRAW_PROBE, "hidraw-probe")
        env = dict(env, LD_PRELOAD=str(library))
        for name, want in (("steam", expected), ("streaming_client", expected), ("game", "refused\n")):
            with self.subTest(name=name):
                run = subprocess.run([self.as_process(probe, name), device], capture_output=True, text=True,
                                     timeout=10, env=env)
                self.assertEqual(run.returncode, 0, run.stderr)
                self.assertEqual(run.stdout, want)

    def test_the_deck_pad_opens_in_steam_and_its_remote_play_client_only(self):
        _, env = self.pad_ring(PAD_TYPE_DECK)
        self.assert_opened_by_steam_and_remote_play_only("/dev/hidraw16", "28de:1205 report 01\n", env)

    def test_the_steam_controller_pad_opens_in_steam_and_its_remote_play_client_only(self):
        _, env = self.pad_ring(PAD_TYPE_STEAM)
        self.assert_opened_by_steam_and_remote_play_only("/dev/hidraw19", "28de:1302 report 42\n", env)

    def test_the_touch_controller_opens_in_steam_and_its_remote_play_client_only(self):
        _, env = self.pad_ring(PAD_TYPE_DECK)
        touch = self.tmp / "touchctl"
        data = bytearray(TOUCH_RING_SIZE)
        struct.pack_into("<I", data, 0, TOUCH_MAGIC)
        struct.pack_into("<I", data, 56, 1)
        touch.write_bytes(bytes(data))
        env["FAKE_TOUCHCTL_RING"] = str(touch)
        self.assert_opened_by_steam_and_remote_play_only("/dev/hidraw17", "0000:11fb report 00\n", env)

    def ask_paired_controller(self, probes, locks_unavailable=False, held=False, count=200):
        library, probe = self.build("-m64", FEATURE_PROBE, "feature-probe")
        ring, env = self.pad_ring(PAD_TYPE_DECK, triton_connected=True)
        env["LD_PRELOAD"] = str(library)
        lock = Path(env["FAKE_EVDEV_DIR"]) / ".triton-feature.lock"
        if locks_unavailable:
            lock.mkdir()
        holder = open(lock, "w") if held else None
        if holder:
            self.addCleanup(holder.close)
            fcntl.flock(holder, fcntl.LOCK_EX)
        block = RING_SIZE + DECK_IMU
        stop = threading.Event()

        def controller():
            with open(ring, "r+b") as f, mmap.mmap(f.fileno(), 0) as shared:
                served = 0
                while not stop.is_set():
                    request = struct.unpack_from("<Q", shared, block + 168)[0]
                    if request == served:
                        time.sleep(0.0001)
                        continue
                    report_id = struct.unpack_from("<I", shared, block + 184)[0]
                    struct.pack_into("<i", shared, block + 188, 2)
                    struct.pack_into("<BB", shared, block + 192, report_id, report_id)
                    struct.pack_into("<Q", shared, block + 176, request)
                    served = request

        thread = threading.Thread(target=controller)
        thread.start()
        try:
            runs = [subprocess.Popen([self.as_process(probe, name), report_id, str(count)], stdout=subprocess.PIPE,
                                     text=True, env=env)
                    for name, report_id in probes]
            outputs = [run.communicate(timeout=60)[0] for run in runs]
        finally:
            stop.set()
            thread.join()
        self.assertEqual([run.returncode for run in runs], [0] * len(probes))
        return outputs

    def test_steam_and_its_remote_play_client_take_turns_with_the_paired_controllers_features(self):
        outputs = self.ask_paired_controller((("steam", "0x11"), ("streaming_client", "0x22")))
        self.assertEqual(outputs, ["0 wrong\n", "0 wrong\n"])

    def test_without_the_lock_only_steam_asks_the_paired_controller(self):
        outputs = self.ask_paired_controller((("steam", "0x11"), ("streaming_client", "0x22")), locks_unavailable=True)
        self.assertEqual(outputs, ["0 wrong\n", "200 wrong\n"])

    def test_a_stopped_lock_holder_does_not_stall_steam_or_its_remote_play_client(self):
        started = time.monotonic()
        outputs = self.ask_paired_controller((("steam", "0x11"), ("streaming_client", "0x22")), held=True, count=3)
        self.assertEqual(outputs, ["3 wrong\n", "3 wrong\n"])
        self.assertLess(time.monotonic() - started, 20)


if __name__ == "__main__":
    unittest.main()
