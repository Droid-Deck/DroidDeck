import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"
MAGIC = 0x4655444B
EV_SYN, EV_KEY, EV_REL, EV_ABS = 0, 1, 2, 3
KEY_A, BTN_LEFT, REL_X = 30, 0x110, 0

PROGRAM = r"""
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int emit(int fd, int type, int code, int value) {
  struct input_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = type;
  ev.code = code;
  ev.value = value;
  return write(fd, &ev, sizeof(ev)) == sizeof(ev) ? 0 : -1;
}

static int make(const char *name, const int *evs, const int *keys, const int *abses, int max) {
  int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
  if (fd < 0) return -1;
  for (; *evs >= 0; evs++) ioctl(fd, UI_SET_EVBIT, *evs);
  for (; *keys >= 0; keys++) ioctl(fd, UI_SET_KEYBIT, *keys);
  for (; *abses >= 0; abses++) {
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = *abses;
    abs.absinfo.maximum = max;
    ioctl(fd, UI_SET_ABSBIT, *abses);
    ioctl(fd, UI_ABS_SETUP, &abs);
  }
  struct uinput_setup setup;
  memset(&setup, 0, sizeof(setup));
  setup.id.bustype = BUS_USB;
  setup.id.vendor = 0x1209;
  strncpy(setup.name, name, sizeof(setup.name) - 1);
  if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) return -1;
  return fd;
}

int main(void) {
  static const int none[] = {-1};
  static const int key_ev[] = {EV_KEY, -1}, rel_ev[] = {EV_KEY, EV_REL, -1}, abs_ev[] = {EV_KEY, EV_ABS, -1};
  static const int abs_only[] = {EV_ABS, -1}, pad_ev[] = {EV_KEY, EV_ABS, -1};
  static const int keyboard[] = {KEY_A, KEY_LEFTSHIFT, -1}, mouse[] = {BTN_LEFT, BTN_RIGHT, -1};
  static const int pad_keys[] = {BTN_SOUTH, BTN_EAST, -1}, xy[] = {ABS_X, ABS_Y, -1}, xyz[] = {ABS_X, ABS_Y, ABS_Z, -1};

  int kbd = make("test Keyboard", key_ev, keyboard, none, 0);
  if (kbd < 0 || emit(kbd, EV_KEY, KEY_A, 1) || emit(kbd, EV_SYN, SYN_REPORT, 0)) return 1;
  close(kbd);

  int rel = make("test Mouse", rel_ev, mouse, none, 0);
  if (rel < 0 || emit(rel, EV_REL, REL_X, 7) || emit(rel, EV_KEY, BTN_LEFT, 1) || emit(rel, EV_SYN, SYN_REPORT, 0)) return 2;
  ioctl(rel, UI_DEV_DESTROY);
  close(rel);

  int abs = make("test Mouse (Absolute)", abs_ev, mouse, xy, 19200);
  if (abs < 0 || emit(abs, EV_ABS, ABS_X, 9600) || emit(abs, EV_SYN, SYN_REPORT, 0)) return 3;
  close(abs);

  int sensor = make("test Motion Sensors", abs_only, none, xyz, 32767);
  if (sensor < 0 || emit(sensor, EV_ABS, ABS_X, 5) || emit(sensor, EV_SYN, SYN_REPORT, 0)) return 4;
  close(sensor);

  int pad = make("test Gamepad", pad_ev, pad_keys, xy, 32767);
  if (pad < 0 || emit(pad, EV_KEY, BTN_SOUTH, 1) || emit(pad, EV_SYN, SYN_REPORT, 0)) return 5;
  char ring[4096];
  snprintf(ring, sizeof(ring), "%s/.uinput-event16", getenv("FAKE_EVDEV_DIR"));
  if (access(ring, F_OK) != 0) return 6;
  snprintf(ring, sizeof(ring), "%s/.uinput-event17", getenv("FAKE_EVDEV_DIR"));
  if (access(ring, F_OK) == 0) return 7;
  close(pad);
  return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("g++") and shutil.which("gcc"), "needs Linux, gcc and g++")
class FakeinputDesktopUinputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.addClassCleanup(shutil.rmtree, cls.tmp, True)
        cls.library = cls.tmp / "libfakeinput.so"
        subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wno-attributes", "-pthread", "-std=c++17",
                        "-static-libstdc++", "-static-libgcc", "-Wl,--exclude-libs,ALL", "-o", str(cls.library),
                        str(SOURCE), "-ldl"], check=True)
        cls.program = cls.tmp / "program"
        (cls.tmp / "program.c").write_text(PROGRAM)
        subprocess.run(["gcc", "-o", str(cls.program), str(cls.tmp / "program.c")], check=True)

    def run_program(self):
        hooks = Path(tempfile.mkdtemp(dir=self.tmp))
        env = dict(os.environ, LD_PRELOAD=str(self.library), FAKE_EVDEV_DIR=str(hooks), FAKE_EVDEV_UINPUT="1")
        run = subprocess.run([str(self.program)], env=env, capture_output=True, timeout=10)
        self.assertEqual(run.returncode, 0, run.stderr)

    def devices(self):
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(server.close)
        try:
            server.bind("\0droiddeck-uinput")
        except OSError:
            self.skipTest("the desktop input socket name is taken on this machine")
        server.listen(16)
        server.settimeout(5)
        got = []

        def accept():
            while True:
                try:
                    client, _ = server.accept()
                except OSError:
                    return
                with client:
                    data = b""
                    while chunk := client.recv(4096):
                        data += chunk
                got.append(data)

        reader = threading.Thread(target=accept, daemon=True)
        reader.start()
        self.run_program()
        server.settimeout(0.5)
        reader.join(10)
        return got

    @staticmethod
    def decode(data):
        header = struct.unpack_from("<Iiiii", data)
        events = [struct.unpack_from("<HHi", data, 20 + i * 8) for i in range((len(data) - 20) // 8)]
        return header, events

    def test_keyboard_and_mice_reach_the_app_while_sensors_and_gamepads_do_not(self):
        got = self.devices()
        self.assertEqual(len(got), 3)
        decoded = [self.decode(d) for d in got]
        self.assertEqual(decoded[0], ((MAGIC, 0, 0, 0, 0), [(EV_KEY, KEY_A, 1), (EV_SYN, 0, 0)]))
        self.assertEqual(decoded[1], ((MAGIC, 0, 0, 0, 0), [(EV_REL, REL_X, 7), (EV_KEY, BTN_LEFT, 1), (EV_SYN, 0, 0)]))
        self.assertEqual(decoded[2], ((MAGIC, 0, 19200, 0, 19200), [(EV_ABS, 0, 9600), (EV_SYN, 0, 0)]))

    def test_without_the_app_listening_devices_still_work(self):
        self.run_program()


if __name__ == "__main__":
    unittest.main()
