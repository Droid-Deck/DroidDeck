from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

# Stands in for libfakeinput.so, which ld.so.preload lists after libblsession.so.
SHIM = r"""
#include <linux/input.h>
#include <stdarg.h>
#include <stdio.h>

int ioctl(int fd, unsigned long request, ...) {
  va_list ap;
  va_start(ap, request);
  struct ff_effect *effect = va_arg(ap, struct ff_effect *);
  va_end(ap);
  if (request == EVIOCSFF) printf("shim type %u length %u\n", effect->type, effect->replay.length);
  return 0;
}
"""

UPLOAD = r"""
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>

void upload(unsigned short type, unsigned short length) {
  struct ff_effect effect;
  memset(&effect, 0, sizeof(effect));
  effect.id = -1;
  effect.type = type;
  effect.replay.length = length;
  ioctl(3, EVIOCSFF, &effect);
  printf("caller length %u\n", effect.replay.length);
}
"""

PROGRAM = r"""
#include <dlfcn.h>
#include <linux/input.h>
#include <stdio.h>

void upload(unsigned short type, unsigned short length);

int main(int argc, char **argv) {
  void *winebus = dlopen(argv[1], RTLD_NOW);
  void (*from_winebus)(unsigned short, unsigned short) = (void (*)(unsigned short, unsigned short))dlsym(winebus, "upload");
  if (argv[2][0] == 'w') from_winebus(argv[3][0] == 'r' ? FF_RUMBLE : FF_PERIODIC, 1000);
  else upload(FF_RUMBLE, 1000);
  fflush(stdout);
  return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("cc"), "needs Linux and cc")
class WinebusRumblePreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        build = cls.dir / "build"
        build.mkdir()
        for name, text in (("shim.c", SHIM), ("upload.c", UPLOAD), ("program.c", PROGRAM)):
            (build / name).write_text(text)
        cls.lib = cls.dir / "libblsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.lib),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        cls.shim = cls.dir / "libshim.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.shim), str(build / "shim.c")], check=True)
        cls.winebus = cls.dir / "winebus.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.winebus), str(build / "upload.c")], check=True)
        cls.program = cls.dir / "program"
        subprocess.run(["cc", "-O2", "-o", str(cls.program), str(build / "program.c"), str(build / "upload.c"), "-ldl"],
                       check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def upload(self, caller, kind="rumble"):
        env = dict(os.environ, LD_PRELOAD="%s:%s" % (self.lib, self.shim))
        result = subprocess.run([str(self.program), str(self.winebus), caller, kind], env=env,
                                capture_output=True, text=True, timeout=10, check=True)
        return result.stdout.splitlines()

    def test_winebus_rumble_reaches_the_shim_held_and_comes_back_unchanged(self):
        self.assertEqual(self.upload("winebus"), ["shim type %d length 0" % 0x50, "caller length 1000"])

    def test_other_callers_and_effects_keep_their_length(self):
        self.assertEqual(self.upload("program"), ["shim type %d length 1000" % 0x50, "caller length 1000"])
        self.assertEqual(self.upload("winebus", "periodic"), ["shim type %d length 1000" % 0x51, "caller length 1000"])


if __name__ == "__main__":
    unittest.main()
