import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stdout

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-flatpak"


class FakeInstallation:
    def __init__(self, root):
        self.root = root

    def get_path(self):
        return types.SimpleNamespace(get_path=lambda: self.root)


def load(root, ref, transactions):
    repository = types.ModuleType("gi.repository")
    repository.Flatpak = types.SimpleNamespace()
    repository.Gio = types.SimpleNamespace()
    repository.GLib = types.SimpleNamespace(Error=RuntimeError)
    gi = types.ModuleType("gi")
    gi.require_version = lambda *args: None
    gi.repository = repository
    saved = {name: sys.modules.get(name) for name in ("gi", "gi.repository")}
    sys.modules["gi"] = gi
    sys.modules["gi.repository"] = repository
    try:
        loader = importlib.machinery.SourceFileLoader("droiddeck_flatpak_uninstall", str(SCRIPT))
        module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
        loader.exec_module(module)
    finally:
        for name, value in saved.items():
            if value is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = value
    module.installation = lambda: FakeInstallation(root)
    module.app_ref = lambda inst, app_id: ref

    def transaction(inst):
        transactions.append(inst)
        raise AssertionError("an uninstalled app reached a transaction")

    module.transaction = transaction
    return module


class FlatpakUninstallTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, True)
        self.apps = os.path.join(self.root, "app")
        os.makedirs(self.apps)

    def uninstall(self, app_id, ref=None):
        transactions = []
        module = load(self.root, ref, transactions)
        out = io.StringIO()
        with redirect_stdout(out):
            rc = module.uninstall(app_id)
        return rc, [json.loads(line) for line in out.getvalue().splitlines()], transactions

    def test_an_app_folder_flatpak_no_longer_knows_is_removed(self):
        stale = os.path.join(self.apps, "org.videolan.VLC")
        os.makedirs(os.path.join(stale, "aarch64", "stable"))
        os.symlink("missing-deploy", os.path.join(stale, "aarch64", "stable", "active"))
        rc, events, transactions = self.uninstall("org.videolan.VLC")
        self.assertEqual(rc, 0)
        self.assertFalse(os.path.lexists(stale))
        self.assertEqual(events, [{"e": "done", "note": "not installed"}])
        self.assertEqual(transactions, [])

    def test_other_apps_and_link_targets_are_left_alone(self):
        outside = os.path.join(self.root, "outside")
        os.makedirs(outside)
        keep = os.path.join(outside, "keep")
        open(keep, "w").close()
        stale = os.path.join(self.apps, "com.google.Chrome")
        os.makedirs(stale)
        os.symlink(outside, os.path.join(stale, "linked"))
        other = os.path.join(self.apps, "org.videolan.VLC")
        os.makedirs(other)
        rc, _, _ = self.uninstall("com.google.Chrome")
        self.assertEqual(rc, 0)
        self.assertFalse(os.path.lexists(stale))
        self.assertTrue(os.path.isfile(keep))
        self.assertTrue(os.path.isdir(other))

    def test_a_linked_app_folder_is_not_followed(self):
        target = os.path.join(self.root, "elsewhere")
        os.makedirs(target)
        open(os.path.join(target, "keep"), "w").close()
        os.symlink(target, os.path.join(self.apps, "com.example.Linked"))
        rc, _, _ = self.uninstall("com.example.Linked")
        self.assertEqual(rc, 0)
        self.assertTrue(os.path.isfile(os.path.join(target, "keep")))

    def test_ids_that_leave_the_app_folder_are_refused(self):
        for app_id in ("..", ".", "", "../app", "a/b"):
            rc, events, _ = self.uninstall(app_id)
            self.assertEqual(rc, 0)
            self.assertEqual(events, [{"e": "done", "note": "not installed"}])
        self.assertTrue(os.path.isdir(self.apps))

    def test_an_installed_app_still_goes_through_flatpak(self):
        os.makedirs(os.path.join(self.apps, "org.videolan.VLC"))
        ref = types.SimpleNamespace(format_ref=lambda: "app/org.videolan.VLC/aarch64/stable")
        with self.assertRaises(AssertionError):
            self.uninstall("org.videolan.VLC", ref)
        self.assertTrue(os.path.isdir(os.path.join(self.apps, "org.videolan.VLC")))


if __name__ == "__main__":
    unittest.main()
