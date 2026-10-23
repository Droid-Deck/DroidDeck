import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock


TOOLS = Path(__file__).resolve().parents[1] / "powervr"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("package_components", TOOLS / "package_components.py")
components = importlib.util.module_from_spec(spec)
spec.loader.exec_module(components)


class SystemVulkanComponentsTest(unittest.TestCase):
    def test_inventory_digest_is_order_independent_and_matches_runtime_contract(self):
        entries = {
            "run-proton.py": ("file", 0o755, b"wrapper"),
            "licenses/LICENSE": ("file", 0o644, b"license"),
        }
        rows = components.normalized_inventory(entries)
        expected = "".join(
            f"{row['path']}\0file\0{row['size']}\0{row['sha256']}\0{row['mode']}\n"
            for row in sorted(rows, key=lambda item: item["path"])
        ).encode()
        import hashlib
        self.assertEqual(hashlib.sha256(expected).hexdigest(), components.tree_digest(list(reversed(rows))))
        self.assertEqual(0o755, next(row["mode"] for row in rows if row["path"] == "run-proton.py"))

    def test_published_archive_starts_with_manifest_and_contains_no_links(self):
        entries = {
            "lib/real.so": ("file", 0o755, b"ELF"),
            "lib/alias.so": ("symlink", 0o777, "real.so"),
        }
        regular = components.regularize(entries)
        self.assertEqual(("file", 0o755, b"ELF"), regular["lib/alias.so"])
        archive = components.pack(b'{"schema":1}\n', regular)
        unpacked = subprocess.run(["zstd", "-q", "-dc"], input=archive, capture_output=True, check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(unpacked)) as package:
            members = package.getmembers()
            self.assertEqual("component.json", members[0].name)
            self.assertTrue(all(member.isfile() or member.isdir() for member in members))

    def test_single_vendor_release_preserves_other_bootstrap_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            existing = self.bootstrap()
            bootstrap = base / "bootstrap.json"
            bootstrap.write_text(json.dumps(existing))
            original = bootstrap.read_bytes()
            entries = {"run-proton.py": ("file", 0o755, b"wrapper"), "licenses/LICENSE": ("file", 0o644, b"license")}
            for variant in components.VARIANTS:
                for version in ("v0.1.0", "v0.2.0-rc.1"):
                    with self.subTest(variant=variant, version=version):
                        output = base / f"{variant}-{version}"
                        argv = self.arguments(output, variant, version) + ["--bootstrap", str(bootstrap)]
                        with mock.patch.object(components, "selected_entries", return_value=entries) as select:
                            self.run_main(argv)
                        self.assertEqual(variant, select.call_args.args[2])
                        self.assertEqual(1, select.call_count)
                        sidecar_name = f"droiddeck-component-system-vulkan-{variant}.json"
                        archive_name = f"system-vulkan-{variant}.tar.zst"
                        self.assertEqual({sidecar_name, archive_name, "component-bootstrap.json"},
                                         {path.name for path in output.iterdir()})
                        sidecar = json.loads((output / sidecar_name).read_text())
                        self.assertEqual((variant, version), (sidecar["variant"], sidecar["version"]))
                        archive = (output / archive_name).read_bytes()
                        self.assertEqual((len(archive), components.runtime.sha256(archive)),
                                         (sidecar["artifact"]["size"], sidecar["artifact"]["sha256"]))
                        expected = self.bootstrap()
                        expected["profiles"][variant] = {
                            "tag": f"system-vulkan-{variant}-{version}",
                            "manifestSha256": components.runtime.sha256((output / sidecar_name).read_bytes()),
                        }
                        self.assertEqual(expected, json.loads((output / "component-bootstrap.json").read_text()))
                        self.assertEqual(original, bootstrap.read_bytes())

    def test_invalid_versions_and_nonempty_output_fail_before_building(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "out"
            for version in ("", "abc1234", "0.1.0", "v1.2", "v01.2.3", "v1.2.3\nother", "v1.2.3/other"):
                with self.subTest(version=version), mock.patch.object(components, "build_variant") as build:
                    with self.assertRaises(SystemExit):
                        self.run_main(self.arguments(output, "mali", version))
                    build.assert_not_called()
                    self.assertFalse(output.exists())
            output.mkdir()
            stale = output / "system-vulkan-powervr.tar.zst"
            stale.write_bytes(b"keep")
            with mock.patch.object(components, "build_variant") as build, self.assertRaises(SystemExit):
                self.run_main(self.arguments(output, "mali", "v1.2.3"))
            build.assert_not_called()
            self.assertEqual(b"keep", stale.read_bytes())

    def test_bootstrap_pins_cannot_cross_producer_identities(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            bootstrap = base / "bootstrap.json"
            existing = self.bootstrap()
            existing["repositoryId"] += 1
            bootstrap.write_text(json.dumps(existing))
            argv = self.arguments(base / "out", "mali", "v0.1.0") + ["--bootstrap", str(bootstrap)]
            with mock.patch.object(components, "build_variant") as build, self.assertRaises(SystemExit):
                self.run_main(argv)
            build.assert_not_called()
            existing["repository"] = "another/producer"
            bootstrap.write_text(json.dumps(existing))
            with mock.patch.object(components, "build_variant", return_value=(None, "b" * 64)):
                self.run_main(argv)
            self.assertEqual({"schema": 1, "repository": "wallentx/droiddeck", "repositoryId": 1406436811,
                              "profiles": {"mali": {"tag": "system-vulkan-mali-v0.1.0", "manifestSha256": "b" * 64}}},
                             json.loads((base / "out/component-bootstrap.json").read_text()))

    @staticmethod
    def bootstrap():
        return {"schema": 1, "repository": "wallentx/droiddeck", "repositoryId": 1406436811,
                "profiles": {variant: {"tag": "system-vulkan-existing", "manifestSha256": "a" * 64}
                             for variant in components.VARIANTS}}

    @staticmethod
    def arguments(output, variant, version):
        return ["package_components.py", "--output", str(output), "--repository", "wallentx/DroidDeck",
                "--repository-id", "1406436811", "--commit", "a" * 40,
                "--build-url", "https://github.com/wallentx/DroidDeck/actions/runs/1",
                "--variant", variant, "--version", version]

    @staticmethod
    def run_main(argv):
        with mock.patch.object(sys, "argv", argv), mock.patch("sys.stdout", new_callable=io.StringIO), \
                mock.patch("sys.stderr", new_callable=io.StringIO):
            components.main()

    def test_regularize_rejects_link_cycles_and_escape_targets(self):
        for entries in (
            {"a": ("symlink", 0o777, "b"), "b": ("symlink", 0o777, "a")},
            {"a": ("symlink", 0o777, "../outside")},
        ):
            with self.subTest(entries=entries), self.assertRaises((ValueError, KeyError)):
                components.regularize(entries)

    def test_vendor_bundle_rejects_foreign_dxvk_roots_and_missing_provenance(self):
        valid = {
            "dxvk/x64/dxgi.dll": ("file", 0o755, b"dll"),
            "licenses/dxvk-LICENSE": ("file", 0o644, b"license"),
            "provenance/dxvk-source-commit": ("file", 0o644, b"commit"),
            "provenance/dxvk-SHA256SUMS": ("file", 0o644, b"sums"),
        }
        components.validate_variant_payload(valid, "powervr")
        with self.assertRaisesRegex(ValueError, "wrong DXVK payload roots"):
            components.validate_variant_payload({**valid, "dxvk-mali/x86_64-windows/dxgi.dll": valid["dxvk/x64/dxgi.dll"]}, "powervr")
        missing = dict(valid)
        del missing["licenses/dxvk-LICENSE"]
        with self.assertRaisesRegex(ValueError, "lacks DXVK license/provenance"):
            components.validate_variant_payload(missing, "powervr")


if __name__ == "__main__":
    unittest.main()
