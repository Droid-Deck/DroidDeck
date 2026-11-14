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

    def test_a_bundle_for_an_architecture_fex_cannot_run_is_refused_before_installing(self):
        rc, events, installs = self.run_install("riscv64")
        self.assertEqual(rc, 1)
        self.assertEqual(installs, [])
        self.assertEqual(events[0]["e"], "error")
        self.assertEqual(events[0]["code"], "arch")
        self.assertEqual(events[0]["arch"], "riscv64")
        self.assertEqual(events[0]["id"], "io.github.ebkr.r2modman")

    def test_a_bundle_for_this_device_goes_on_to_install(self):
        rc, events, installs = self.run_install("aarch64")
        self.assertEqual(installs, ["bundle"])
        self.assertEqual(events, [])

    def test_an_x86_64_bundle_goes_on_to_install_for_fex(self):
        rc, events, installs = self.run_install("x86_64")
        self.assertEqual(installs, ["bundle"])
        self.assertEqual(events, [])

    def test_flathub_falls_back_to_the_x86_64_build(self):
        module = load("aarch64", [])
        asked = []

        class Remote:
            def __init__(self, branch):
                self.branch = branch

            def get_branch(self):
                return self.branch

        class Installation:
            def fetch_remote_ref_sync(self, remote, kind, app_id, arch, branch, cancellable):
                asked.append((arch, branch))
                if arch == "x86_64" and branch == "stable":
                    return Remote("stable")
                raise RuntimeError("not found")

        added = []

        class Transaction:
            def add_install(self, remote, ref, subpaths):
                added.append(ref)

        module.Flatpak.RefKind = types.SimpleNamespace(APP=0)
        module.installation = lambda: Installation()
        installed = []
        module.app_ref = lambda inst, app_id: (installed[0] if installed else None)
        module.transaction = lambda inst: (Transaction(), {})

        def run(t, state, report_done=True):
            installed.append(object())
            return 0

        module.run = run
        out = io.StringIO()
        with redirect_stdout(out):
            rc = module.install("io.github.ebkr.r2modman")
        self.assertEqual(rc, 0)
        self.assertEqual(asked, [("aarch64", "stable"), ("aarch64", "master"), ("x86_64", "stable")])
        self.assertEqual(added, ["app/io.github.ebkr.r2modman/x86_64/stable"])


if __name__ == "__main__":
    unittest.main()
