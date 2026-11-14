import os
from pathlib import Path
import shutil
import socket
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

GAME = r"""
#include <fcntl.h>
#include <linux/input.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
  int fd = open("/dev/input/event16", O_RDWR | O_NONBLOCK);
  if (fd < 0) return 1;
  struct ff_effect effect;
  memset(&effect, 0, sizeof(effect));
  effect.id = -1;
  effect.type = FF_RUMBLE;
  effect.u.rumble.strong_magnitude = 40000;
  effect.u.rumble.weak_magnitude = 20000;
  if (ioctl(fd, EVIOCSFF, &effect) < 0) return 2;
  struct input_event play;
  memset(&play, 0, sizeof(play));
  play.type = EV_FF;
  play.code = effect.id;
  play.value = 1;
  if (write(fd, &play, sizeof(play)) != sizeof(play)) return 3;
  if (argv[1][0] == 'e') ioctl(fd, EVIOCRMFF, effect.id);
  else if (argv[1][0] == 'c') close(fd);
  else if (argv[1][0] == 'f') {
    pid_t child = fork();
    if (child == 0) {
      close(fd);
      _exit(0);
    }
    waitpid(child, NULL, 0);
  }
  return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("g++") and shutil.which("gcc"), "needs Linux, gcc and g++")
class FakeinputRumbleStopTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.addClassCleanup(shutil.rmtree, cls.tmp, True)
        cls.library = cls.tmp / "libfakeinput.so"
        subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread", "-std=c++17",
                        "-static-libstdc++", "-static-libgcc", "-Wl,--exclude-libs,ALL", "-o", str(cls.library),
                        str(SOURCE), "-ldl"], check=True)
        cls.game = cls.tmp / "game"
        (cls.tmp / "game.c").write_text(GAME)
        subprocess.run(["gcc", "-o", str(cls.game), str(cls.tmp / "game.c")], check=True)
        cls.hooks = cls.tmp / "hooks"
        cls.hooks.mkdir()
        (cls.hooks / "event16").touch()
        ring = bytearray(HEADER + CAPACITY * EVENT + DESCRIPTION)
        struct.pack_into("<IIIIQQQ", ring, 0, RING_MAGIC, 2, EVENT, CAPACITY, 0, 0, 0)
        struct.pack_into("<IiHHHHI80s", ring, HEADER + CAPACITY * EVENT, DESCRIPTION_MAGIC, os.getpid(),
                         3, 0x28de, 0x11ff, 1, 16, b"Microsoft X-Box 360 pad 0")
        (cls.hooks / ".uinput-event16").write_bytes(bytes(ring))

    def packets(self, ending):
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(server.close)
        try:
            server.bind("\0droiddeck-rumble")
        except OSError:
            self.skipTest("the rumble socket name is taken on this machine")
        server.listen(16)
        server.settimeout(0.5)
        env = dict(os.environ, LD_PRELOAD=str(self.library), FAKE_EVDEV_DIR=str(self.hooks), FAKE_EVDEV_VIBRATION="1")
        run = subprocess.run([str(self.game), ending], env=env, capture_output=True, timeout=10)
        self.assertEqual(run.returncode, 0, run.stderr)
        got = []
        while True:
            try:
                client, _ = server.accept()
            except socket.timeout:
                return got
            with client:
                got.append(struct.unpack("<4H", client.recv(8))[:3])

    def test_closing_the_controller_stops_its_held_rumble(self):
        self.assertEqual(self.packets("close")[-1], (0, 0, 0))

    def test_erasing_the_playing_effect_stops_it(self):
        self.assertEqual(self.packets("erase")[-1], (0, 0, 0))

    def test_a_forked_child_closing_its_copy_leaves_the_parents_rumble(self):
        self.assertEqual(self.packets("fork")[-1], (40000, 20000, 0xffff))

    def test_a_held_effect_still_plays_until_then(self):
        self.assertIn((40000, 20000, 0xffff), self.packets("keep"))


if __name__ == "__main__":
    unittest.main()
