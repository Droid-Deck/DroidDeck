import glob
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

TESTS = Path(__file__).resolve().parent
TERMUX_DIR = TESTS.parents[1] / "app/src/main/cpp/termux"


def jni_include_dir():
    """A directory holding jni.h: JNI_INCLUDE, then the JDK, then the Android NDK."""
    candidates = [os.environ.get("JNI_INCLUDE")]
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(os.path.join(java_home, "include"))
    candidates += glob.glob("/usr/lib/jvm/*/include")
    for var in ("ANDROID_NDK_LATEST_HOME", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        ndk = os.environ.get(var)
        if ndk:
            candidates += glob.glob(os.path.join(ndk, "toolchains/llvm/prebuilt/*/sysroot/usr/include"))
    for candidate in candidates:
        if candidate and os.path.isfile(os.path.join(candidate, "jni.h")):
            return candidate
    return None


def include_flags():
    """-I flags for jni.h, plus the JDK's per-platform directory that holds jni_md.h."""
    base = jni_include_dir()
    flags = ["-I", base]
    for sub in glob.glob(os.path.join(base, "*")):
        if os.path.isfile(os.path.join(sub, "jni_md.h")):
            flags += ["-I", sub]
    return flags


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
@unittest.skipUnless(jni_include_dir(), "needs jni.h (set JNI_INCLUDE, JAVA_HOME or ANDROID_NDK_HOME)")
class TermuxJniTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.binary = os.path.join(cls.tmp.name, "termux_jni_harness")
        subprocess.run(
            ["cc", "-std=gnu11", "-D_GNU_SOURCE", "-Wall", *include_flags(), "-I", str(TERMUX_DIR),
             str(TESTS / "termux_jni_harness.c"), "-o", cls.binary],
            check=True, capture_output=True, text=True)

    def run_harness(self, args, envs):
        result = subprocess.run([self.binary, str(args), str(envs)],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        line = next(l for l in result.stdout.splitlines() if l.startswith("peak_refs="))
        return {key: int(value) for key, value in re.findall(r"(\w+)=(\d+)", line)}

    def test_a_long_argv_and_environment_stay_under_the_local_reference_limit(self):
        # 300 + 300 is under 512 for each loop on its own but over it together, as the issue says:
        # ART aborts with "local reference table overflow (max=512)".
        stats = self.run_harness(300, 300)
        self.assertEqual(stats["overflowed"], 0, stats)

    def test_every_element_reference_is_released(self):
        stats = self.run_harness(8, 8)
        self.assertEqual(stats["live_refs"], 0, stats)
        self.assertLessEqual(stats["peak_refs"], 1, stats)

    def test_utf_chars_are_released_against_the_string_they_came_from(self):
        stats = self.run_harness(1, 1)
        self.assertEqual(stats["mismatched_releases"], 0, stats)
        self.assertEqual(stats["unreleased_chars"], 0, stats)


if __name__ == "__main__":
    unittest.main()
