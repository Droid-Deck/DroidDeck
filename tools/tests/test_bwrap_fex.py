import os
import runpy
import shutil
import struct
import tempfile
import unittest
from pathlib import Path

BWRAP = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"


def elf(path, machine):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x7fELF\x02\x01\x01" + bytes(11) + struct.pack("<H", machine) + bytes(64))
    path.chmod(0o755)


class BwrapFexTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.shim = runpy.run_path(str(BWRAP))
        self.fex = self.dir / "fex"
        for name in ("bin/FEX", "bin/FEXServer", "lib/libxxhash.so.0"):
            elf(self.fex / name, 183)
        self.shim["through_fex"].__globals__["OWN_FEX"] = str(self.fex)
        self.runtime = self.dir / "runtime"
        self.app = self.dir / "app"
        elf(self.runtime / "bin/ldconfig", 62)
        elf(self.runtime / "bin/bash", 62)
        os.symlink("bash", self.runtime / "bin/sh")
        elf(self.runtime / "bin/arm-tool", 183)
        (self.app / "bin").mkdir(parents=True)
        (self.app / "bin/r2modman").write_text("#!/bin/sh\nexec /app/r2modman \"$@\"\n")

    def spec(self, command, path="/app/bin:/usr/bin"):
        s = self.shim["Spec"]()
        s.env = {"PATH": path, "XDG_CACHE_HOME": "/home/user/.var/app/x/cache", "FEX_ROOTFS": "/sniper"}
        s.ops = [
            {"t": "bind", "src": str(self.runtime), "dest": "/usr", "try": False, "ro": True},
            {"t": "bind", "src": str(self.app), "dest": "/app", "try": False, "ro": True},
            {"t": "symlink", "target": "usr/bin", "dest": "/bin"},
        ]
        s.command = command
        return s

    def test_an_x86_64_program_found_on_path_runs_through_fex(self):
        s = self.spec(["ldconfig", "-X", "-C", "/run/cache"])
        self.assertTrue(self.shim["through_fex"](s))
        self.assertEqual(["/run/droiddeck-fex/bin/FEX", "/usr/bin/ldconfig", "-X", "-C", "/run/cache"], s.command)
        dests = {op["dest"]: op["src"] for op in s.ops if op["t"] == "bind"}
        self.assertEqual(str(self.fex / "bin/FEX"), dests["/run/droiddeck-fex/bin/FEX"])
        self.assertEqual(str(self.fex / "lib/libxxhash.so.0"), dests["/run/droiddeck-fex/lib/libxxhash.so.0"])
        self.assertIn("/usr/lib/aarch64-linux-gnu", dests)
        self.assertIn("/usr/lib/ld-linux-aarch64.so.1", dests)
        self.assertNotIn("FEX_ROOTFS", s.env)
        self.assertEqual("/home/user/.var/app/x/cache/droiddeck-fex/", s.env["FEX_APP_DATA_LOCATION"])

    def test_a_script_whose_interpreter_is_x86_64_runs_through_fex(self):
        s = self.spec(["r2modman", "--flag"])
        self.assertTrue(self.shim["through_fex"](s))
        self.assertEqual(["/run/droiddeck-fex/bin/FEX", "/app/bin/r2modman", "--flag"], s.command)

    def test_a_program_reached_through_a_sandbox_symlink_is_checked(self):
        s = self.spec(["/bin/sh", "-c", "true"])
        self.assertTrue(self.shim["through_fex"](s))
        self.assertEqual("/bin/sh", s.command[1])

    def test_a_bind_whose_source_is_a_symlink_resolves_from_the_source(self):
        real = self.dir / "store" / "tool-real"
        elf(real, 62)
        os.symlink("tool-real", self.dir / "store" / "tool-link")
        s = self.spec(["/opt/tool"])
        s.ops.append({"t": "bind", "src": str(self.dir / "store" / "tool-link"), "dest": "/opt/tool", "try": False,
                      "ro": True})
        self.assertTrue(self.shim["through_fex"](s))
        self.assertEqual("/opt/tool", s.command[1])

    def test_a_relative_path_entry_is_searched_from_the_working_directory(self):
        s = self.spec(["r2modman"], path="bin")
        s.cwd = "/app"
        self.assertTrue(self.shim["through_fex"](s))
        self.assertEqual("/app/bin/r2modman", s.command[1])

    def test_an_arm64_program_runs_as_it_is(self):
        s = self.spec(["arm-tool"])
        self.assertFalse(self.shim["through_fex"](s))
        self.assertEqual(["arm-tool"], s.command)
        self.assertEqual(3, len(s.ops))

    def test_a_missing_program_is_left_alone(self):
        s = self.spec(["nothing-here"])
        self.assertFalse(self.shim["through_fex"](s))
        self.assertEqual(["nothing-here"], s.command)


if __name__ == "__main__":
    unittest.main()
