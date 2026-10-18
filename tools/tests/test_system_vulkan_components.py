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

    def test_bootstrap_output_pins_each_sidecar_digest_and_release_tag(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)

            def build(args, variant):
                path = output / f"droiddeck-component-system-vulkan-{variant}.json"
                path.write_bytes(variant.encode())
                return path, components.runtime.sha256(path.read_bytes())

            argv = [
                "package_components.py", "--output", str(output), "--repository", "wallentx/DroidDeck",
                "--repository-id", "1406436811", "--commit", "a" * 40,
                "--build-url", "https://github.com/wallentx/DroidDeck/actions/runs/1",
                "--release-tag", "system-vulkan-1", "--version", "1",
            ]
            with mock.patch.object(components, "build_variant", side_effect=build), mock.patch.object(sys, "argv", argv):
                components.main()
            pins = json.loads((output / "component-bootstrap.json").read_text())
            self.assertEqual(1406436811, pins["repositoryId"])
            self.assertEqual(set(components.VARIANTS), set(pins["profiles"]))
            for variant, pin in pins["profiles"].items():
                self.assertEqual("system-vulkan-1", pin["tag"])
                self.assertEqual(components.runtime.sha256(variant.encode()), pin["manifestSha256"])

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
