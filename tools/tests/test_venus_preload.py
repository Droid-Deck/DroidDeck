import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#include <stdio.h>
#include <stdlib.h>
int unsetenv(const char *name) { return -1; }
int setenv(const char *name, const char *value, int overwrite) { return -1; }
int putenv(char *string) { return -1; }
int main(void) {
  const char *names[] = {"LIBGL_KOPPER_DISABLE", "LIBGL_KOPPER_DISABLE_X", "ZINK_DEBUG", "VN_DEBUG"};
  for (int i = 0; i < 4; i++) {
    const char *value = getenv(names[i]);
    printf("%s=%s\n", names[i], value ? value : "(unset)");
  }
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs cc")
class VenusPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        cls.program = cls.tmp / "program"
        cls.library = cls.tmp / "libvenus.so"
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-o", str(cls.program), str(source)], check=True)
        subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-shared", "-fPIC", "-o", str(cls.library),
                        str(PRELOAD / "venus.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def values(self, **extra):
        env = {"PATH": os.environ.get("PATH", ""), "LD_PRELOAD": str(self.library), **extra}
        result = subprocess.run([str(self.program)], env=env, capture_output=True, text=True, check=True)
        return dict(line.split("=", 1) for line in result.stdout.splitlines())

    def test_kopper_is_kept_when_environment_changes_are_refused(self):
        values = self.values(VN_DEBUG="vtest", LIBGL_KOPPER_DISABLE="true", LIBGL_KOPPER_DISABLE_X="1",
                             ZINK_DEBUG="mem")
        self.assertEqual(values["LIBGL_KOPPER_DISABLE"], "(unset)")
        self.assertEqual(values["LIBGL_KOPPER_DISABLE_X"], "1")
        self.assertEqual(values["ZINK_DEBUG"], "mem")
        self.assertEqual(values["VN_DEBUG"], "vtest")

    def test_other_sessions_keep_kopper_disabled(self):
        self.assertEqual(self.values(LIBGL_KOPPER_DISABLE="true")["LIBGL_KOPPER_DISABLE"], "true")
        self.assertEqual(self.values(VN_DEBUG="init", LIBGL_KOPPER_DISABLE="true")["LIBGL_KOPPER_DISABLE"], "true")

    def test_absent_variable_is_harmless(self):
        self.assertEqual(self.values(VN_DEBUG="vtest")["LIBGL_KOPPER_DISABLE"], "(unset)")


if __name__ == "__main__":
    unittest.main()
