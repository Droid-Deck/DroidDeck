"""Late-load production TLS storage and run the patched runtime's boundary probe."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/hybris/patches/0009-tls-capacity-and-icd-diagnostics.patch"


class HybrisTlsCapacityTest(unittest.TestCase):
    def test_patched_production_late_load_bounds_and_thread_replay(self):
        source_value = os.environ.get("HYBRIS_TLS_SOURCE")
        if not source_value:
            self.skipTest("set HYBRIS_TLS_SOURCE to the clean pinned libhybris checkout")
        source = Path(source_value)
        self.assertTrue((source / ".git").exists())
        self.assertEqual(subprocess.check_output(
            ["git", "-C", str(source), "status", "--porcelain"], text=True), "")
        revision = next(line.split("=", 1)[1] for line in
                        (ROOT / "tools/hybris/source.env").read_text().splitlines()
                        if line.startswith("HYBRIS_REVISION="))
        self.assertEqual(subprocess.check_output(
            ["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip(), revision)
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a host C compiler is required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-hybris-tls-") as directory:
            work = Path(directory)
            checkout = work / "libhybris"
            subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(source), str(checkout)],
                           check=True)
            subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(PATCH)], check=True)
            subprocess.run(["git", "-C", str(checkout), "apply", str(PATCH)], check=True)
            (work / "config.h").write_text("")
            (work / "probe.h").write_text(
                "#define _GNU_SOURCE\n#include <dlfcn.h>\n#include <pthread.h>\n"
                "#include <stdint.h>\n#include <stdio.h>\n#include <stdlib.h>\n"
                "#include <unistd.h>\n")
            shutil.copyfile(checkout / "tests/baseline/probe_tls_bounds.c",
                            work / "probe_tls_bounds.c")
            (work / "main.c").write_text(
                "int tls_bounds_probe(void);\nint main(void) { return tls_bounds_probe(); }\n")
            flags = [compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-UDEBUG", "-pthread"]
            library = work / "libhybris-common.so.1"
            built = subprocess.run([
                *flags, "-fPIC", "-shared", "-I", str(work),
                str(checkout / "hybris/common/bionic_tls.c"), "-o", str(library)],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            program = work / "tls-capacity-test"
            built = subprocess.run([
                *flags, str(work / "main.c"), str(work / "probe_tls_bounds.c"),
                "-ldl", "-Wl,-rpath,$ORIGIN", "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            env = {**os.environ, "LD_LIBRARY_PATH": str(work)}
            ran = subprocess.run([str(program)], env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            for case in range(5):
                self.assertIn(f"TLS_BOUNDS rejection case={case} PASS", ran.stdout)
            self.assertIn("TLS_BOUNDS offset=984 filesz=0 memsz=41 PASS", ran.stdout)
            self.assertIn("TLS_BOUNDS last-byte=1039 thread-replay PASS", ran.stdout)
            self.assertIn("TLS_REPLAY main mutation=41 catchup=29 PASS", ran.stdout)
            self.assertIn("TLS_BOUNDS PASS", ran.stdout)


if __name__ == "__main__":
    unittest.main()
