import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-steam-x64"
SOURCE = SCRIPT.read_text()
START = SOURCE.index("x64_fex() {")
END = SOURCE.index("\n}\n", START) + 3
X64_FEX = SOURCE[START:END]

HARNESS = textwrap.dedent("""\
    set -eu
    FEX_TAG=fex-2610-r5 FEX_SHA256=builtin FEX_URL=builtin-url
    step() { echo "STEP $*"; }
    # Installed FEX loads when LOADS=1; a build installs when its tag is in INSTALLABLE.
    fex_loads() { [ "${LOADS:-0}" = 1 ]; }
    fex_install() {
      echo "INSTALL $1 $2 $3"
      case " ${INSTALLABLE:-} " in *" $1 "*) echo "$1" > "$ROOT/tag"; : > "$ROOT/FEX"; chmod +x "$ROOT/FEX"; return 0 ;; esac
      return 1
    }
    """)


class FexSelectTest(unittest.TestCase):
    def run_select(self, installed=None, loads=False, catalog=None, installable=()):
        with tempfile.TemporaryDirectory() as root:
            if installed:
                Path(root, "tag").write_text(installed + "\n")
                fex = Path(root, "FEX")
                fex.write_text("")
                fex.chmod(0o755)
            body = (X64_FEX.replace("/usr/local/share/fex-emu/.droiddeck-tag", "$ROOT/tag")
                    .replace("/usr/local/bin/FEX", "$ROOT/FEX"))
            script = HARNESS + body + 'if x64_fex; then echo "NOW $FEX_NOW|$FEX_FROM"; else echo FAILED; fi\n'
            env = dict(os.environ, ROOT=root, LOADS="1" if loads else "0", INSTALLABLE=" ".join(installable))
            env.pop("BL_FEX_TAG", None)
            if catalog:
                env.update(BL_FEX_TAG=catalog, BL_FEX_SHA256="catsha", BL_FEX_URLS="a b")
            out = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, out.returncode, out.stderr)
            lines = out.stdout.splitlines()
            return [l for l in lines if l.startswith("INSTALL")], lines[-1]

    def test_the_catalog_build_already_installed_is_used(self):
        installs, now = self.run_select(installed="fex-2610-r6", loads=True, catalog="fex-2610-r6")
        self.assertEqual([], installs)
        self.assertEqual("NOW fex-2610-r6|catalog", now)

    def test_a_newer_catalog_build_is_installed(self):
        installs, now = self.run_select(installed="fex-2610-r5", loads=True, catalog="fex-2610-r6", installable=["fex-2610-r6"])
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r6|catalog", now)

    def test_a_catalog_build_that_does_not_install_keeps_the_working_one(self):
        installs, now = self.run_select(installed="fex-2610-r5", loads=True, catalog="fex-2610-r6")
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r5|kept; fex-2610-r6 did not install", now)

    def test_without_a_catalog_the_built_in_build_replaces_an_older_one(self):
        installs, now = self.run_select(installed="fex-2610-r4", loads=True, installable=["fex-2610-r5"])
        self.assertEqual(["INSTALL fex-2610-r5 builtin builtin-url"], installs)
        self.assertEqual("NOW fex-2610-r5|built-in", now)

    def test_an_installed_build_that_does_not_load_is_replaced(self):
        installs, now = self.run_select(installed="fex-2610-r6", loads=False, catalog="fex-2610-r6", installable=["fex-2610-r6"])
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r6|catalog", now)

    def test_a_first_install_falls_back_to_the_built_in_build(self):
        installs, now = self.run_select(catalog="fex-2610-r6", installable=["fex-2610-r5"])
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b", "INSTALL fex-2610-r5 builtin builtin-url"], installs)
        self.assertEqual("NOW fex-2610-r5|built-in", now)

    def test_nothing_usable_fails(self):
        installs, now = self.run_select(catalog="fex-2610-r6")
        self.assertEqual(2, len(installs))
        self.assertEqual("FAILED", now)


if __name__ == "__main__":
    unittest.main()
