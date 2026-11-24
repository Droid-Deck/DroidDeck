import json
import os
import runpy
import shutil
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
WEBKIT = "/usr/lib/aarch64-linux-gnu/webkit2gtk-4.1"


class SnapLayoutTest(unittest.TestCase):
    def setUp(self):
        try:
            self.snap_run = runpy.run_path(str(BIN / "droiddeck-snap-run"))
        except ImportError:
            self.skipTest("PyYAML is not installed")
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.snaps = self.tmp / "snap"
        self.snap_run["main"].__globals__["SNAP_DIR"] = str(self.snaps)
        provider = self.snaps / "gnome-core26/36"
        (provider / "meta").mkdir(parents=True)
        (provider / "meta/snap.yaml").write_text(json.dumps(
            {"slots": {"gnome-core26": {"interface": "content", "read": ["/"]}}}))
        os.symlink("36", self.snaps / "gnome-core26/current")
        self.snap = str(self.snaps / "app/12")
        self.env = {"SNAP": self.snap, "SNAP_DATA": str(self.tmp / "data/12"),
                    "SNAP_COMMON": str(self.tmp / "data/common")}

    def args(self, meta):
        binds = self.snap_run["content_binds"]("app", meta, self.env)
        mounted = [(source, target) for _, source, target in binds]
        return self.snap_run["layout_args"](meta, self.env, mounted)

    def test_a_layout_into_a_content_snap_binds_the_provider_directory(self):
        meta = {
            "plugs": {"gnome-core26": {"interface": "content", "target": "$SNAP/gnome-platform",
                                       "default-provider": "gnome-core26"}},
            "layout": {WEBKIT: {"bind": "$SNAP/gnome-platform" + WEBKIT}},
        }
        self.assertEqual(["--bind", str(self.snaps / "gnome-core26/36") + WEBKIT, WEBKIT], self.args(meta))

    def test_every_layout_kind_is_mounted_and_links_come_last(self):
        meta = {"layout": {
            "/etc/app.conf": {"bind-file": "$SNAP_DATA/etc/app.conf"},
            "/usr/share/app": {"symlink": "$SNAP/usr/share/app"},
            "/var/cache/app": {"type": "tmpfs"},
            "/var/lib/app": {"bind": "$SNAP_COMMON/lib"},
        }}
        data, common = self.env["SNAP_DATA"], self.env["SNAP_COMMON"]
        self.assertEqual([
            "--bind", data + "/etc/app.conf", "/etc/app.conf",
            "--tmpfs", "/var/cache/app",
            "--bind", common + "/lib", "/var/lib/app",
            "--symlink", self.snap + "/usr/share/app", "/usr/share/app",
        ], self.args(meta))
        self.assertTrue(os.path.isfile(data + "/etc/app.conf"))
        self.assertTrue(os.path.isdir(common + "/lib"))

    def test_a_snap_without_a_layout_gets_no_extra_mounts(self):
        self.assertEqual([], self.args({"plugs": {"home": None}}))
        self.assertEqual([], self.args({"layout": {"/opt/x": "not a mapping"}}))

    def test_a_read_only_source_that_is_missing_is_not_created(self):
        self.assertEqual(["--bind", self.snap + "/opt/x", "/opt/x"],
                         self.args({"layout": {"/opt/x": {"bind": "$SNAP/opt/x"}}}))
        self.assertFalse(os.path.exists(self.snap))


if __name__ == "__main__":
    unittest.main()
