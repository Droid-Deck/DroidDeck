import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("component_record",
    Path(__file__).resolve().parents[1] / "hybris/component_record.py")
component = importlib.util.module_from_spec(spec)
spec.loader.exec_module(component)


class HybrisComponentRecordTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for name in ("build_runtime.sh", "component_record.py", "source.env", "powervr-hal-version.patch",
                     "patches/0001-existing.patch", "patches/0002-new-vendor.patch"):
            path = self.root / "tools/hybris" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("HYBRIS_REVISION=source123\n" if name == "source.env" else name)
        elf = bytearray(64)
        elf[:6], elf[18:20] = b"\x7fELF\x02\x01", b"\xb7\x00"
        self.entries = {name: ("file", 0o755, bytes(elf)) for name in component.runtime.REQUIRED_LIBRARIES
                        if name.startswith("lib/")}
        self.entries.update({
            component.runtime.PLACED_MANIFEST: ("file", 0o644, component.runtime.json_bytes({"layer": {
                "name": "VK_LAYER_DROIDDECK_placed_memory", "type": "GLOBAL",
                "library_path": "./libVkLayer_DROIDDECK_placed_memory.so"}})),
            "source-commit": ("file", 0o644, b"source123\n"),
            "licenses/powervr.patch": ("file", 0o644, b"patched source"),
            "licenses/libhybris-source.tar.zst": ("file", 0o644, b"source archive"),
            "licenses/wayland-android.xml": ("file", 0o644, b"protocol source/license"),
            "build-recipe.json": ("file", 0o644, component.runtime.json_bytes(component.recipe_inputs(self.root))),
        })
        self.manifest = {"source_commit": "source123", "compiler": "original compiler",
                         "build_inputs": {"source": "build snapshot"},
                         "elfs": [{"tree": "hybris", "path": name.removeprefix("lib/"), "sha256": "a" * 64}
                                  for name in self.entries if name.startswith("lib/")
                                  and self.entries[name][2].startswith(b"\x7fELF")]}
        self.entries["build-manifest.json"] = ("file", 0o644, component.runtime.json_bytes(self.manifest))
        self.checksums()

    def checksums(self):
        data = "".join(component.runtime.sha256(content) + "  " + name + "\n"
                       for name, (kind, _, content) in self.entries.items()
                       if name.startswith("lib/") and kind == "file")
        self.entries["SHA256SUMS"] = ("file", 0o644, data.encode())

    def record(self, url="https://example.com/releases/runtime.tzst"):
        return component.record(component.runtime.pack(self.entries), self.root, url, "producer123", "https://example.com/run/1")

    def test_records_archive_identity_and_entire_patch_recipe_without_changing_pins(self):
        record = self.record()
        self.assertEqual(record["tree_sha256"], component.runtime.tree_digest(self.entries))
        self.assertEqual(record["provenance"]["upstream_source_commit"], "source123")
        self.assertEqual(record["provenance"]["producer_commit"], "producer123")
        self.assertIn("tools/hybris/patches/0002-new-vendor.patch", record["recipe_inputs"])
        self.assertEqual(len(record["recipe_inputs"]), 6)
        self.assertFalse((self.root / "tools/powervr/release.json").exists())

    def test_rejects_a_different_source_revision(self):
        self.entries["source-commit"] = ("file", 0o644, b"other-source\n")
        with self.assertRaisesRegex(ValueError, "different libhybris revision"):
            self.record()

    def test_rejects_a_stale_patch_stack(self):
        (self.root / "tools/hybris/patches/0003-new.patch").write_text("another vendor fix")
        with self.assertRaisesRegex(ValueError, "different recipe or patch stack"):
            self.record()

    def test_rejects_wrong_architecture_and_missing_libraries(self):
        name = "lib/libhybris-vulkan-icd.so.0"
        elf = bytearray(self.entries[name][2])
        elf[18:20] = b"\x3e\x00"
        self.entries[name] = ("file", 0o755, bytes(elf))
        self.checksums()
        with self.assertRaisesRegex(ValueError, "not ELF64 AArch64"):
            self.record()
        del self.entries[name]
        with self.assertRaisesRegex(ValueError, "ELF inventory differs"):
            self.record()

    def test_rejects_empty_or_wrong_source_manifest(self):
        self.entries["build-manifest.json"] = ("file", 0o644, b"{}")
        with self.assertRaisesRegex(ValueError, "source/compiler provenance"):
            self.record()
        self.manifest["source_commit"] = "other-source"
        self.entries["build-manifest.json"] = ("file", 0o644, component.runtime.json_bytes(self.manifest))
        with self.assertRaisesRegex(ValueError, "source/compiler provenance"):
            self.record()

    def test_requires_source_archive_and_protocol_license(self):
        for name in ("licenses/libhybris-source.tar.zst", "licenses/wayland-android.xml"):
            saved = self.entries.pop(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "missing runtime file"):
                self.record()
            self.entries[name] = saved

    def test_rejects_changed_packaged_library(self):
        name = "lib/libhybris-vulkan-icd.so.0"
        kind, mode, content = self.entries[name]
        self.entries[name] = (kind, mode, content + b"changed after packaging")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.record()

    def test_rejects_an_unpublishable_url(self):
        for url in ("file:///runtime.tzst", "http://example.com/runtime.tzst", "https:///runtime.tzst"):
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, "must use HTTPS"):
                self.record(url)

    def test_requires_placed_layer_manifest_and_local_library(self):
        name = component.runtime.PLACED_MANIFEST
        del self.entries[name]
        self.checksums()
        with self.assertRaisesRegex(ValueError, "missing runtime file"):
            self.record()
        for library in ("/unmanaged/layer.so", "../layer.so", "./wrong-layer.so"):
            self.entries[name] = ("file", 0o644, component.runtime.json_bytes({"layer": {
                "name": "VK_LAYER_DROIDDECK_placed_memory", "type": "GLOBAL",
                "library_path": library}}))
            self.checksums()
            with self.subTest(library=library), self.assertRaisesRegex(ValueError, "invalid placed-memory"):
                self.record()


if __name__ == "__main__":
    unittest.main()
