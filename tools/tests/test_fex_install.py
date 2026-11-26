import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-fex-update"
SOURCE = SCRIPT.read_text()
START = SOURCE.index("fex_install() {")
END = SOURCE.index("\n}\n", START) + 3
FEX_INSTALL = SOURCE[START:END]
THUNK_DIRS = (("share/fex-emu/GuestThunks_32", "libGL-guest.so"), ("lib/fex-emu/HostThunks_32", "libGL-host.so"))

HARNESS = textwrap.dedent("""\
    set -eu
    # The archive comes from PKG; the installed tree loads when INSTALLED_LOADS=1, any staged tree always does.
    curl() { local out=; while [ $# -gt 0 ]; do [ "$1" = -o ] && out=$2; shift; done; cp "$PKG" "$out"; }
    fex_loads() { case $1 in "$ROOT"/) [ "${INSTALLED_LOADS:-1}" = 1 ] ;; *) true ;; esac; }
    """)


class FexInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.root = base / "root"
        old = self.root / "usr/local"
        (old / "bin").mkdir(parents=True)
        (old / "bin/FEX").write_text("old FEX\n")
        (old / "share/fex-emu").mkdir(parents=True)
        (old / "share/fex-emu/.droiddeck-tag").write_text("fex-2610-r5\n")
        new = base / "pkg/usr/local"
        (new / "bin").mkdir(parents=True)
        (new / "bin/FEX").write_text("new FEX\n")
        (new / "lib").mkdir()
        (new / "lib/libxxhash.so.0").write_text("lib\n")
        self.pkg = base / "fex.tzst"
        self.pack()

    def pack(self):
        base = Path(self.temp.name)
        subprocess.run(f"tar -C '{base / 'pkg'}' -c . | zstd -q -f -o '{self.pkg}'", shell=True, check=True)
        self.sha = hashlib.sha256(self.pkg.read_bytes()).hexdigest()

    def add_old_thunks(self):
        old, new = self.root / "usr/local", Path(self.temp.name) / "pkg/usr/local"
        for side, name in THUNK_DIRS:
            (new / side).mkdir(parents=True)
            (new / side / name).write_text("new thunk\n")
            (old / side).mkdir(parents=True)
            (old / side / name).write_text("old thunk\n")
            (old / side / "libwayland-client-thunk.so").write_text("stale\n")
        (old / "share/fex-emu/GuestThunks").mkdir()
        (old / "share/fex-emu/GuestThunks/libkept-guest.so").write_text("kept\n")
        self.pack()

    def install(self, installed_loads, sha=None):
        body = FEX_INSTALL.replace("local tag=$1 sha=$2 urls=$3 dst=/ ", "local tag=$1 sha=$2 urls=$3 dst=$ROOT/ ")
        self.assertNotEqual(body, FEX_INSTALL)
        script = HARNESS + body + 'if fex_install fex-2610-r6 "$SHA" u; then echo OK; else echo FAILED; fi\n'
        env = dict(os.environ, ROOT=str(self.root), PKG=str(self.pkg), SHA=sha or self.sha,
                   INSTALLED_LOADS="1" if installed_loads else "0")
        out = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=30)
        self.assertEqual(0, out.returncode, out.stderr)
        return out.stdout.strip().splitlines()[-1], out.stderr

    def tag(self):
        return (self.root / "usr/local/share/fex-emu/.droiddeck-tag").read_text().strip()

    def test_a_build_that_loads_once_installed_replaces_the_old_one(self):
        result, _ = self.install(installed_loads=True)
        self.assertEqual("OK", result)
        self.assertEqual("new FEX\n", (self.root / "usr/local/bin/FEX").read_text())
        self.assertTrue((self.root / "usr/local/lib/libxxhash.so.0").is_file())
        self.assertEqual("fex-2610-r6", self.tag())

    def test_an_installed_copy_that_does_not_load_puts_the_old_files_back(self):
        result, err = self.install(installed_loads=False)
        self.assertEqual("FAILED", result)
        self.assertIn("putting the previous files back", err)
        self.assertEqual("old FEX\n", (self.root / "usr/local/bin/FEX").read_text())
        self.assertFalse((self.root / "usr/local/lib/libxxhash.so.0").exists())
        self.assertEqual("fex-2610-r5", self.tag())

    def test_a_tag_that_cannot_be_written_puts_the_old_files_back(self):
        share = self.root / "usr/local/share/fex-emu"
        (share / ".droiddeck-tag").unlink()
        share.rmdir()
        share.write_text("not a folder\n")
        result, err = self.install(installed_loads=True)
        self.assertEqual("FAILED", result)
        self.assertIn("putting the previous files back", err)
        self.assertEqual("old FEX\n", (self.root / "usr/local/bin/FEX").read_text())
        self.assertFalse((self.root / "usr/local/lib/libxxhash.so.0").exists())

    def test_thunks_the_new_build_no_longer_ships_are_removed(self):
        self.add_old_thunks()
        self.assertEqual("OK", self.install(installed_loads=True)[0])
        for side, name in THUNK_DIRS:
            with self.subTest(side=side):
                self.assertEqual("new thunk\n", (self.root / "usr/local" / side / name).read_text())
                self.assertFalse((self.root / "usr/local" / side / "libwayland-client-thunk.so").exists())

    def test_a_thunk_folder_the_new_build_does_not_ship_is_left_alone(self):
        self.add_old_thunks()
        self.assertEqual("OK", self.install(installed_loads=True)[0])
        self.assertEqual("kept\n", (self.root / "usr/local/share/fex-emu/GuestThunks/libkept-guest.so").read_text())

    def test_a_failed_install_puts_removed_thunks_back(self):
        self.add_old_thunks()
        self.assertEqual("FAILED", self.install(installed_loads=False)[0])
        for side, name in THUNK_DIRS:
            with self.subTest(side=side):
                self.assertEqual("old thunk\n", (self.root / "usr/local" / side / name).read_text())
                self.assertEqual("stale\n", (self.root / "usr/local" / side / "libwayland-client-thunk.so").read_text())

    def test_an_archive_that_does_not_match_installs_nothing(self):
        result, err = self.install(installed_loads=True, sha="0" * 64)
        self.assertEqual("FAILED", result)
        self.assertIn("no source gave the archive", err)
        self.assertEqual("old FEX\n", (self.root / "usr/local/bin/FEX").read_text())
        self.assertEqual("fex-2610-r5", self.tag())


if __name__ == "__main__":
    unittest.main()
