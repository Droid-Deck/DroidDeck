import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import sys
import types
import unittest
from contextlib import redirect_stdout

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-flatpak"


class FakeRef:
    def __init__(self, arch):
        self.arch = arch

    def get_name(self):
        return "io.github.ebkr.r2modman"

    def get_arch(self):
        return self.arch


def load(bundle_arch, installs):
    flatpak = types.SimpleNamespace(
        get_default_arch=lambda: "aarch64",
        BundleRef=types.SimpleNamespace(new=lambda bundle: FakeRef(bundle_arch)),
    )
    repository = types.ModuleType("gi.repository")
    repository.Flatpak = flatpak
    repository.Gio = types.SimpleNamespace(File=types.SimpleNamespace(new_for_path=lambda path: path))
    repository.GLib = types.SimpleNamespace(Error=RuntimeError)
    gi = types.ModuleType("gi")
    gi.require_version = lambda *args: None
    gi.repository = repository
    saved = {name: sys.modules.get(name) for name in ("gi", "gi.repository")}
    sys.modules["gi"] = gi
    sys.modules["gi.repository"] = repository
    try:
        loader = importlib.machinery.SourceFileLoader("droiddeck_flatpak", str(SCRIPT))
        module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
        loader.exec_module(module)
    finally:
        for name, value in saved.items():
            if value is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = value
    module.installation = lambda: object()
    module.app_ref = lambda inst, app_id: None

    def transaction(inst):
        installs.append("bundle")
        raise AssertionError("the bundle reached a transaction")

    module.transaction = transaction
    return module


class FlatpakBundleArchTest(unittest.TestCase):
    def run_install(self, arch):
        installs = []
        module = load(arch, installs)
        out = io.StringIO()
        with redirect_stdout(out):
            try:
                rc = module.install_bundle("/tmp/r2modman.flatpak")
            except AssertionError:
                rc = None
        return rc, [json.loads(line) for line in out.getvalue().splitlines()], installs

    def test_a_bundle_for_another_architecture_is_refused_before_installing(self):
        rc, events, installs = self.run_install("x86_64")
        self.assertEqual(rc, 1)
        self.assertEqual(installs, [])
        self.assertEqual(events[0]["e"], "error")
        self.assertEqual(events[0]["code"], "arch")
        self.assertEqual(events[0]["arch"], "x86_64")
        self.assertEqual(events[0]["id"], "io.github.ebkr.r2modman")

    def test_a_bundle_for_this_device_goes_on_to_install(self):
        rc, events, installs = self.run_install("aarch64")
        self.assertEqual(installs, ["bundle"])
        self.assertEqual(events, [])


if __name__ == "__main__":
    unittest.main()
