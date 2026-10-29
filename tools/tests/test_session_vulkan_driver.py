import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def probe_function():
    return re.search(r"^icd_library_loads\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S).group(0)


class VulkanDriverProbeTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)

    def probe(self, library):
        script = probe_function() + 'icd_library_loads "$1"\n'
        return subprocess.run(["bash", "-c", script, "bash", str(library)]).returncode

    def test_fallback_is_wired_to_the_probe(self):
        self.assertIn('[ -r "$bl_icd_lib" ] && icd_library_loads "$bl_icd_lib"', SESSION.read_text())

    @unittest.skipUnless(shutil.which("ldd"), "needs ldd")
    def test_library_with_resolvable_dependencies_passes(self):
        self.assertEqual(self.probe(shutil.which("true")), 0)

    @unittest.skipUnless(shutil.which("ldd") and shutil.which("cc"), "needs ldd and a C compiler")
    def test_library_with_missing_dependency_fails(self):
        (self.root / "dep.c").write_text("int dep(void){return 1;}\n")
        (self.root / "lib.c").write_text("int dep(void); int f(void){return dep();}\n")
        subprocess.run(["cc", "-shared", "-o", str(self.root / "libdep.so"), str(self.root / "dep.c")], check=True)
        subprocess.run(["cc", "-shared", "-o", str(self.root / "libicd.so"), str(self.root / "lib.c"),
                        "-L", str(self.root), "-ldep", "-Wl,-rpath,/nonexistent"], check=True)
        (self.root / "libdep.so").unlink()
        self.assertNotEqual(self.probe(self.root / "libicd.so"), 0)


if __name__ == "__main__":
    unittest.main()
