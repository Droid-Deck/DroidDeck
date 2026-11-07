import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SHIM = Path(__file__).resolve().parents[1] / "mangoapp/sysfs-listing-shim.c"
PROBE = r"""
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
int main(int argc, char **argv) {
    DIR *dir = opendir(argv[1]);
    if (!dir) { printf("error=%s\n", strerror(errno)); return 0; }
    int entries = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)))
        if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) ++entries;
    closedir(dir);
    printf("entries=%d\n", entries);
    return 0;
}
"""


@unittest.skipIf(os.geteuid() == 0, "root can list mode-000 directories")
class MangoAppSysfsShimTest(unittest.TestCase):
    def test_denied_sys_listings_read_as_empty(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-mangoapp-shim-") as directory:
            root = Path(directory)
            sys_root = root / "sys"
            for path in (sys_root / "class/thermal/thermal_zone0", sys_root / "class/drm/card0",
                         sys_root / "class/power_supply/battery", root / "outside/child"):
                path.mkdir(parents=True)
            shim = root / "shim.so"
            probe = root / "probe"
            (root / "probe.c").write_text(PROBE)
            subprocess.run([compiler, "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror",
                            f'-DSYS_ROOT="{sys_root}"', "-o", shim, SHIM, "-ldl"], check=True)
            subprocess.run([compiler, "-Wall", "-Werror", "-o", probe, root / "probe.c"], check=True)
            denied = (sys_root / "class/thermal", sys_root / "class/drm", root / "outside")
            for path in denied:
                path.chmod(0)
            try:
                def listing(path, preload=True):
                    env = dict(os.environ, LD_PRELOAD=str(shim)) if preload else os.environ
                    return subprocess.run([probe, path], env=env, text=True, capture_output=True,
                                          check=True).stdout.strip()

                self.assertEqual(listing(sys_root / "class/thermal", preload=False),
                                 "error=Permission denied")
                for path in ("class/thermal", "class/thermal/", "class/drm"):
                    self.assertEqual(listing(f"{sys_root}/{path}"), "entries=0", path)
                self.assertEqual(listing(sys_root / "class/power_supply"), "entries=1")
                self.assertEqual(listing(sys_root / "class/missing"), "error=No such file or directory")
                self.assertEqual(listing(root / "outside"), "error=Permission denied")
                self.assertEqual(listing(f"{sys_root}-other"), "error=No such file or directory")
            finally:
                for path in denied:
                    path.chmod(0o755)


if __name__ == "__main__":
    unittest.main()
