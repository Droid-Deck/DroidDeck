import os
import platform
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/linuxfs/fex/webkitinput.c"

WEBKIT = r"""
#include <stdio.h>
#include <stdlib.h>
const char *g_getenv(const char *name) { return getenv(name); }
void webkit_web_view_load_uri(void *view, const char *uri) { (void)view; printf("%s\n", uri); }
"""

LOADER = r"""
void webkit_web_view_load_uri(void *view, const char *uri);
int main(int argc, char **argv) {
  for (int i = 1; i < argc; i++) webkit_web_view_load_uri(0, argv[i]);
  return 0;
}
"""

URIS = [
    "https://www.fab.com",
    "https://fab.com/listings/1",
    "http://www.fab.com?x=1",
    "https://www.fab.com:443/",
    "https://store.epicgames.com/en-US/",
    "https://www.fab.community/",
    "https://notfab.com/",
    "tauri://localhost",
]


@unittest.skipUnless(platform.machine() == "x86_64" and shutil.which("gcc"), "webkitinput is built for x86_64 guests")
class WebKitInputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        (cls.tmp / "webkit.c").write_text(WEBKIT)
        (cls.tmp / "loader.c").write_text(LOADER)
        subprocess.run(["gcc", "-m64", "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-nostdlib", "-fno-stack-protector",
                        "-fno-builtin", "-fvisibility=hidden", "-o", str(cls.tmp / "libwebkitinput.so"), str(SOURCE)], check=True)
        subprocess.run(["gcc", "-shared", "-fPIC", "-o", str(cls.tmp / "libfakewebkit.so"), str(cls.tmp / "webkit.c")], check=True)
        subprocess.run(["gcc", "-o", str(cls.tmp / "loader"), str(cls.tmp / "loader.c"), "-L" + str(cls.tmp),
                        "-Wl,--no-as-needed", "-lwebkitinput", "-lfakewebkit", "-ldl", "-Wl,-rpath," + str(cls.tmp)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def load(self, hidden):
        env = {k: v for k, v in os.environ.items() if k != "DROIDDECK_EPIC_HIDDEN"}
        if hidden is not None:
            env["DROIDDECK_EPIC_HIDDEN"] = hidden
        result = subprocess.run([str(self.tmp / "loader")] + URIS, env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    def test_the_hidden_launcher_loads_a_blank_page_for_fab(self):
        self.assertEqual(["about:blank"] * 4 + URIS[4:], self.load("1"))

    def test_the_visible_launcher_loads_every_page(self):
        self.assertEqual(URIS, self.load(None))
        self.assertEqual(URIS, self.load("0"))
        self.assertEqual(URIS, self.load("10"))


if __name__ == "__main__":
    unittest.main()
