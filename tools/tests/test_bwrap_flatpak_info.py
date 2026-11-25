import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

BWRAP = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"

PROBE = """
import json, os
print(json.dumps({"pid": os.getpid(), "binds": os.environ.get("DDNATV_BINDS")}))
"""

INFO = b"[Application]\nname=org.example.App\n\n[Instance]\ninstance-id=42\n"


class BwrapFlatpakInfoInPlaceTest(unittest.TestCase):
    def run_shim(self, env):
        read, write = os.pipe()
        os.write(write, INFO)
        os.close(write)
        self.addCleanup(os.close, read)
        full = {k: v for k, v in os.environ.items() if not k.startswith("DDNATV_")}
        full.update(env)
        out = subprocess.run([sys.executable, str(BWRAP), "--ro-bind-data", str(read), "/.flatpak-info", "--",
                              sys.executable, "-c", PROBE], env=full, pass_fds=(read,), capture_output=True,
                             text=True, timeout=30)
        self.assertEqual(0, out.returncode, out.stderr)
        return json.loads(out.stdout)

    def file_bind(self, binds, pid):
        entries = [e for e in (binds or "").split("|") if e.endswith(":/.flatpak-info")]
        self.assertEqual(1, len(entries))
        src = entries[0][:-len(":/.flatpak-info")]
        self.assertTrue(src.startswith("/proc/%d/fd/" % pid), src)
        return src

    def test_a_native_program_run_in_place_sees_its_flatpak_info(self):
        got = self.run_shim({"DDNATV_ROOT": "/data/rootfs"})
        self.file_bind(got["binds"], got["pid"])
        self.assertEqual(1, len(got["binds"].split("|")))

    def test_the_session_binds_are_kept_in_front(self):
        got = self.run_shim({"DDNATV_ROOT": "/data/rootfs", "DDNATV_BINDS": "/proc:/proc|/sdcard:/media/sdcard"})
        self.file_bind(got["binds"], got["pid"])
        self.assertEqual(["/proc:/proc", "/sdcard:/media/sdcard"], got["binds"].split("|")[:2])

    def test_the_bound_file_holds_the_data_and_outlives_exec(self):
        script = (
            "import os, sys; b = os.environ['DDNATV_BINDS'].split('|')[-1].split(':')[0]; "
            "sys.stdout.write(open(b.replace('/proc/%d/' % os.getpid(), '/proc/self/')).read())"
        )
        read, write = os.pipe()
        os.write(write, INFO)
        os.close(write)
        self.addCleanup(os.close, read)
        env = {k: v for k, v in os.environ.items() if not k.startswith("DDNATV_")}
        env["DDNATV_ROOT"] = "/data/rootfs"
        out = subprocess.run([sys.executable, str(BWRAP), "--ro-bind-data", str(read), "/.flatpak-info", "--",
                              sys.executable, "-c", script], env=env, pass_fds=(read,), capture_output=True,
                             timeout=30)
        self.assertEqual(0, out.returncode, out.stderr)
        self.assertEqual(INFO, out.stdout)

    def test_without_the_native_hook_nothing_is_added(self):
        got = self.run_shim({})
        self.assertIsNone(got["binds"])


if __name__ == "__main__":
    unittest.main()
