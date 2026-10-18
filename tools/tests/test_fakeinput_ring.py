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
#include <unistd.h>

int main(void)
{
    struct input_event events[64];
    int fd = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open");
        return 1;
    }
    while (read(fd, events, sizeof(events)) > 0)
        ;
    puts("ready");
    fflush(stdout);
    char go;
    if (read(0, &go, 1) != 1)
        return 1;
    ssize_t got = read(fd, events, sizeof(events));
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

    def ring(self):
        hooks = self.tmp / "hooks"
        hooks.mkdir()
        (hooks / "event16").touch()
        ring = bytearray(HEADER + CAPACITY * EVENT + DESCRIPTION)
        struct.pack_into("<IIIIQQQ", ring, 0, RING_MAGIC, 2, EVENT, CAPACITY, 5, 0, 0)
        struct.pack_into("<IiHHHHI80s", ring, HEADER + CAPACITY * EVENT, DESCRIPTION_MAGIC, os.getpid(),
                         3, 0x28de, 0x11ff, 1, 16, b"Microsoft X-Box 360 pad 0")
        path = hooks / ".uinput-event16"
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

    def read_through(self, bits):
        library, reader = self.build(bits, READER, "reader")
        hooks, path = self.ring()
        env = dict(os.environ, LD_PRELOAD=str(library), FAKE_EVDEV_DIR=str(hooks))
        process = subprocess.Popen([str(reader)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, text=True)
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

    def test_an_event_node_rejects_joydev_requests_so_sdl_reads_its_id(self):
        library, probe = self.build("-m64", PROBE, "probe")
        hooks, _ = self.ring()
        run = subprocess.run([str(probe)], capture_output=True, text=True, timeout=10,
                             env=dict(os.environ, LD_PRELOAD=str(library), FAKE_EVDEV_DIR=str(hooks)))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "JSIOCGNAME -1 EINVAL\n28de:11ff Microsoft X-Box 360 pad 0\n")


if __name__ == "__main__":
    unittest.main()
