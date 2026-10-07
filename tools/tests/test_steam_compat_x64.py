import os
from pathlib import Path
import runpy
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))

CONFIG = '''"InstallConfigStore"
{
\t"Software"
\t{
\t\t"Valve"
\t\t{
\t\t\t"Steam"
\t\t\t{
\t\t\t\t"CompatToolMapping"
\t\t\t\t{
\t\t\t\t\t"550"
\t\t\t\t\t{
\t\t\t\t\t\t"name"\t\t"proton_experimental"
\t\t\t\t\t\t"config"\t\t""
\t\t\t\t\t\t"priority"\t\t"250"
\t\t\t\t\t}
\t\t\t\t\t"620"
\t\t\t\t\t{
\t\t\t\t\t\t"name"\t\t"proton-cachyos-a64"
\t\t\t\t\t\t"config"\t\t""
\t\t\t\t\t\t"priority"\t\t"250"
\t\t\t\t\t}
\t\t\t\t\t"730"
\t\t\t\t\t{
\t\t\t\t\t\t"name"\t\t"steamlinuxruntime"
\t\t\t\t\t\t"config"\t\t""
\t\t\t\t\t\t"priority"\t\t"250"
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t}
\t}
}
'''


class X64RepointTest(unittest.TestCase):
    """steam-compatibility --x64: the x86-64 client's Proton entries go to its arm64 wrapper."""

    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.root = Path(self.home.name) / "Steam"
        (self.root / "config").mkdir(parents=True)
        (self.root / "config" / "config.vdf").write_text(CONFIG)
        # A wrapper of its own whose name starts with "proton": kept, never repointed.
        own = self.root / "compatibilitytools.d" / "proton-cachyos-a64"
        own.mkdir(parents=True)
        (own / "compatibilitytool.vdf").write_text('"compatibilitytools"\n{\n\t"compat_tools"\n\t{\n\t\t"proton-cachyos-a64"\n\t\t{\n\t\t}\n\t}\n}\n')
        self.old_home = os.environ.get("HOME")
        os.environ["HOME"] = self.home.name

    def tearDown(self):
        if self.old_home is None:
            os.environ.pop("HOME", None)
        else:
            os.environ["HOME"] = self.old_home
        self.home.cleanup()

    def mapping(self):
        tokens = COMPAT["tokenize"]((self.root / "config" / "config.vdf").read_text())
        return COMPAT["read_mapping"](tokens, COMPAT["mapping_block"](tokens))

    def test_repoints_valve_proton_only(self):
        moved = COMPAT["x64_repoint"](str(self.root), "GE-Proton11-7-a64")
        self.assertEqual(moved, ["550"])
        m = self.mapping()
        self.assertEqual(m["550"], "GE-Proton11-7-a64")
        self.assertEqual(m["620"], "proton-cachyos-a64")
        self.assertEqual(m["730"], "steamlinuxruntime")
        self.assertEqual(m["0"], "GE-Proton11-7-a64")

    def test_live_notes_pending_restart(self):
        COMPAT["x64_repoint"](str(self.root), "GE-Proton11-7-a64", live=True)
        self.assertEqual((Path(self.home.name) / ".bl-compat-pending").read_text(), "550\n")

    def test_no_tool_changes_nothing(self):
        before = (self.root / "config" / "config.vdf").read_text()
        self.assertEqual(COMPAT["x64_repoint"](str(self.root), None), [])
        self.assertEqual((self.root / "config" / "config.vdf").read_text(), before)


if __name__ == "__main__":
    unittest.main()
